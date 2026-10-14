package com.andyyhope.featureflag.android.bridge

import android.app.Application
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagBridgeClientTest {

    private enum class TestSignal : FlagSignal { Purge, Unobserved }

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val host = "com.example.host"
    private val permission = FlagBridgeContract.permission(host)
    private val schema = FlagSchema(
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        flags = listOf(
            FlagSchema.Entry(
                key = FlagKey("enabled"),
                propertyPath = listOf("enabled"),
                description = "enabled",
                valueType = FlagValueType.Bool,
                defaultValue = FlagValueBox.Bool(false),
            ),
        ),
    )
    private val stall = CountDownLatch(1)

    @Before
    fun setUp() {
        requestPermission(permission)
        installHost()
        // A host without the bridge does not define the permission, so the system never grants it.
        shadowOf(context).denyPermissions(permission)
    }

    @After
    fun releaseStalledCalls() {
        stall.countDown()
    }

    // connect: each status, in the order a companion should fix them

    @Test
    fun aReachableHostWithASchemaIsConnected() = runBlocking<Unit> {
        installBridge()

        assertEquals(FlagBridgeConnection.Connected(schema), FlagBridgeClient(context, host).connect())
    }

    @Test
    fun aCompanionThatDoesNotRequestThePermissionIsToldToAddIt() = runBlocking<Unit> {
        requestPermission()

        val connection = FlagBridgeClient(context, host).connect()

        assertEquals(FlagBridgeConnection.UsesPermissionMissing(permission), connection)
        assertEquals(
            "This app does not request 'com.example.host.permission.FEATURE_FLAG_BRIDGE'. Add " +
                "<uses-permission android:name=\"com.example.host.permission.FEATURE_FLAG_BRIDGE\" /> " +
                "to the companion's manifest.",
            connection.message,
        )
    }

    @Test
    fun theMissingUsesPermissionIsReportedBeforeAMissingHost() = runBlocking<Unit> {
        // Without the <uses-permission> the host is invisible, so "not found" would mislead.
        requestPermission()
        shadowOf(context.packageManager).deletePackage(host)

        assertEquals(FlagBridgeConnection.UsesPermissionMissing(permission), FlagBridgeClient(context, host).connect())
    }

    @Test
    fun hostNotFoundNamesThePackageAndTheSuffixHint() = runBlocking<Unit> {
        // Only the release build is installed, but the companion was given the debug build's id.
        val debugHost = "com.example.host.debug"
        requestPermission(FlagBridgeContract.permission(debugHost))

        val connection = FlagBridgeClient(context, debugHost).connect()

        assertEquals(FlagBridgeConnection.HostNotFound(debugHost), connection)
        assertEquals(
            "No app with the package 'com.example.host.debug' is installed, or it does not include featureflag-bridge. " +
                "Install and run the host app, check the package name (a debug build often adds a suffix such as " +
                "'.debug'), and check that the host depends on featureflag-bridge.",
            connection.message,
        )
    }

    @Test
    fun aDeniedPermissionNamesBothCertificates() = runBlocking<Unit> {
        installHost(certificate = byteArrayOf(1, 2, 3))
        signCompanion(byteArrayOf(4, 5, 6))
        definePermission()

        val connection = FlagBridgeClient(context, host).connect()

        assertEquals(FlagBridgeConnection.PermissionDenied(host, listOf(HOST_SHA256), listOf(COMPANION_SHA256)), connection)
        assertEquals(
            "'com.example.host' only lets apps signed with its own key connect. Sign this app with the same key, " +
                "or list this app's certificate in the host's knownSigner permission (Android 12 and later). " +
                "Host: $HOST_SHA256. This app: $COMPANION_SHA256.",
            connection.message,
        )
    }

    @Test
    fun aHostWithoutTheBridgeIsNotInstalled() = runBlocking<Unit> {
        val connection = FlagBridgeClient(context, host).connect()

        assertEquals(FlagBridgeConnection.BridgeNotInstalled(host), connection)
        assertEquals("'com.example.host' does not include featureflag-bridge. Add it to the host app's dependencies.", connection.message)
    }

    @Test
    fun aHostThatDoesNotDefineThePermissionIsNotInstalledRatherThanDenied() = runBlocking<Unit> {
        // Without the bridge the permission does not exist, so the system can never grant it.
        assertEquals(FlagBridgeConnection.BridgeNotInstalled(host), FlagBridgeClient(context, host).connect())
    }

    @Test
    @Config(shadows = [RefusingContentResolver::class])
    fun aRefusedAcquireIsPermissionDenied() = runBlocking<Unit> {
        // The framework refuses to open the provider for a caller without the permission (S6).
        installBridge()

        val connection = FlagBridgeClient(context, host).connect()

        assertEquals(FlagBridgeConnection.PermissionDenied(host, listOf(HOST_SHA256), listOf()), connection)
    }

    @Test
    fun aProviderThatRefusesTheCallerIsPermissionDenied() = runBlocking<Unit> {
        installBridge().refusal = SecurityException("Permission Denial")

        val connection = FlagBridgeClient(context, host).connect()

        assertTrue(connection is FlagBridgeConnection.PermissionDenied, "$connection")
    }

    @Test
    fun aHostThatDoesNotAnswerInTimeIsUnresponsive() = runBlocking<Unit> {
        installBridge().stall = stall

        val (connection, waited) = measureTimedValue { FlagBridgeClient(context, host, timeout = 200.milliseconds).connect() }

        assertEquals(FlagBridgeConnection.Unresponsive(host, 200.milliseconds), connection)
        // The answer comes at the timeout, while the stuck call is still waiting.
        assertTrue(waited < 2.seconds, "waited $waited")
        assertEquals(
            "'com.example.host' did not answer within 200ms. It may be stuck starting up; open it once, then try again.",
            connection.message,
        )
    }

    @Test
    fun aHostSpeakingAnotherProtocolIsIncompatible() = runBlocking<Unit> {
        installBridge().protocol = 2

        val connection = FlagBridgeClient(context, host).connect()

        assertEquals(
            FlagBridgeConnection.Incompatible(
                "The host speaks bridge protocol 2; this companion speaks 1. Update whichever app is older.",
            ),
            connection,
        )
        assertEquals((connection as FlagBridgeConnection.Incompatible).reason, connection.message)
    }

    @Test
    fun aHostWithoutASchemaIsNotPublished() = runBlocking<Unit> {
        installBridge().schema = null

        val connection = FlagBridgeClient(context, host).connect()

        assertEquals(FlagBridgeConnection.NotPublished, connection)
        assertEquals(FlagSchemaException.NotPublished().message, connection.message)
    }

    @Test
    fun aSchemaThisBuildCannotReadIsIncompatible() = runBlocking<Unit> {
        val future = """{"formatVersion" : 2, "flags" : []}""".encodeToByteArray()
        installBridge().schemaBytes = future
        val expected = assertFailsWith<FlagSchemaException> { FlagSchema.parse(future) }.message.orEmpty()

        assertEquals(FlagBridgeConnection.Incompatible(expected), FlagBridgeClient(context, host).connect())
    }

    @Test
    fun aHostThatDiesMidCallIsRetriedOnceWithAFreshClient() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.deaths.set(1)

        assertEquals(FlagBridgeConnection.Connected(schema), FlagBridgeClient(context, host).connect())
        assertEquals(listOf("hello", "hello", "openFile /schema"), bridge.calls)
    }

    @Test
    fun aHostThatDiesTwiceIsUnresponsive() = runBlocking<Unit> {
        installBridge().deaths.set(2)

        assertEquals(FlagBridgeConnection.Unresponsive(host, 5000.milliseconds), FlagBridgeClient(context, host).connect())
    }

    @Test
    fun aHostThatFailsWhileAnsweringIsIncompatible() = runBlocking<Unit> {
        installBridge().failure = IllegalStateException("boom")

        assertEquals(
            FlagBridgeConnection.Incompatible(
                "'com.example.host' failed while answering the bridge: boom. Update whichever app is older, then try again.",
            ),
            FlagBridgeClient(context, host).connect(),
        )
    }

    @Test
    fun aFailureWithoutAMessageNamesItsClass() = runBlocking<Unit> {
        installBridge().failure = UnsupportedOperationException()

        assertEquals(
            FlagBridgeConnection.Incompatible(
                "'com.example.host' failed while answering the bridge: UnsupportedOperationException. " +
                    "Update whichever app is older, then try again.",
            ),
            FlagBridgeClient(context, host).connect(),
        )
    }

    @Test
    fun onlyConnectedHasNoMessage() {
        assertEquals(null, FlagBridgeConnection.Connected(schema).message)
    }

    // send

    @Test
    fun aSignalTheHostHandledIsTrue() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")

        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge))
        assertEquals(listOf("signal Purge"), bridge.calls)
    }

    @Test
    fun aSignalNoObserverHandledIsFalse() = runBlocking<Unit> {
        installBridge().handledSignals = setOf("Purge")

        assertFalse(FlagBridgeClient(context, host).send(TestSignal.Unobserved))
    }

    @Test
    fun aSignalToAHostWithoutTheBridgeIsFalse() = runBlocking<Unit> {
        assertFalse(FlagBridgeClient(context, host).send(TestSignal.Purge))
    }

    @Test
    fun aSignalThatOutlivesItsTimeoutIsFalse() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        bridge.stall = stall

        val (handled, waited) = measureTimedValue {
            FlagBridgeClient(context, host).send(TestSignal.Purge, timeout = 200.milliseconds)
        }

        assertFalse(handled)
        assertTrue(waited < 2.seconds, "waited $waited")
    }

    @Test
    fun aSignalTheProviderRefusesIsFalse() = runBlocking<Unit> {
        installBridge().refusal = SecurityException("Permission Denial")

        assertFalse(FlagBridgeClient(context, host).send(TestSignal.Purge))
    }

    @Test
    fun aSignalTheHostFailsOnIsFalse() = runBlocking<Unit> {
        installBridge().failure = IllegalArgumentException("Unknown bridge method 'signal'.")

        assertFalse(FlagBridgeClient(context, host).send(TestSignal.Purge))
    }

    @Test
    fun aSignalIsRetriedOnceWhenTheHostDies() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        bridge.deaths.set(1)

        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge))
        assertEquals(listOf("signal Purge", "signal Purge"), bridge.calls)
    }

    @Test
    fun aSignalToAHostThatDiesTwiceIsFalse() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        bridge.deaths.set(2)

        assertFalse(FlagBridgeClient(context, host).send(TestSignal.Purge))
    }

    @Test
    fun aSignalCarriesItsDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        val timeout = 7.seconds

        val before = SystemClock.elapsedRealtime()
        FlagBridgeClient(context, host).send(TestSignal.Purge, timeout)
        val after = SystemClock.elapsedRealtime()

        val deadline = bridge.extrasSeen.single()!!.getLong(FlagBridgeContract.KEY_DEADLINE)
        assertTrue(deadline in (before + timeout.inWholeMilliseconds)..(after + timeout.inWholeMilliseconds), "deadline $deadline")
    }

    @Test
    fun anInfiniteTimeoutCarriesAFutureDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")

        val before = SystemClock.elapsedRealtime()
        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge, Duration.INFINITE))

        val deadline = bridge.extrasSeen.single()!!.getLong(FlagBridgeContract.KEY_DEADLINE)
        assertTrue(deadline > before, "deadline $deadline")
    }

    @Test
    fun aRetriedSignalKeepsItsFirstDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        bridge.deaths.set(1)

        FlagBridgeClient(context, host).send(TestSignal.Purge, 7.seconds)

        val deadlines = bridge.extrasSeen.map { it!!.getLong(FlagBridgeContract.KEY_DEADLINE) }
        assertEquals(2, deadlines.size)
        assertEquals(deadlines[0], deadlines[1])
    }

    // send, delayed

    @Test
    fun aSignalWithoutADelaySendsOnlyItsDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")

        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge, delay = Duration.ZERO))

        assertEquals(setOf(FlagBridgeContract.KEY_DEADLINE), bridge.extrasSeen.single()!!.keySet())
    }

    @Test
    fun aDelayedSignalCarriesItsDelayATicketAndALaterDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        val timeout = 7.seconds
        val delay = 300.milliseconds

        val before = SystemClock.elapsedRealtime()
        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge, timeout, delay))
        val after = SystemClock.elapsedRealtime()

        val extras = bridge.extrasSeen.single()!!
        assertEquals(300L, extras.getLong(FlagBridgeContract.KEY_DELAY))
        assertTrue(extras.getString(FlagBridgeContract.KEY_TICKET).orEmpty().isNotEmpty())
        val budget = (timeout + delay).inWholeMilliseconds
        val deadline = extras.getLong(FlagBridgeContract.KEY_DEADLINE)
        assertTrue(deadline in (before + budget)..(after + budget), "deadline $deadline")
    }

    @Test
    fun eachDelayedSendHasItsOwnTicket() = runBlocking<Unit> {
        val bridge = installBridge()
        val client = FlagBridgeClient(context, host)

        client.send(TestSignal.Purge, delay = 10.milliseconds)
        client.send(TestSignal.Purge, delay = 10.milliseconds)

        val tickets = bridge.extrasSeen.map { it!!.getString(FlagBridgeContract.KEY_TICKET) }
        assertEquals(2, tickets.toSet().size, "$tickets")
    }

    @Test
    fun aDelayedSignalIsGivenItsDelayOnTopOfItsTimeout() = runBlocking<Unit> {
        // The host waits 500 ms before it answers; a 200 ms timeout alone would have given up.
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")

        val (handled, waited) = measureTimedValue {
            FlagBridgeClient(context, host).send(TestSignal.Purge, timeout = 200.milliseconds, delay = 500.milliseconds)
        }

        assertTrue(handled)
        assertTrue(waited >= 500.milliseconds, "waited $waited")
    }

    @Test
    fun cancellingADelayedSendWithdrawsItFromTheHost() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        val waiting = CountDownLatch(1)
        bridge.delayReached = waiting

        // Undispatched, so the call is on its way before this thread blocks on the latch.
        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            FlagBridgeClient(context, host).send(TestSignal.Purge, delay = 10.seconds)
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        val (_, took) = measureTimedValue { send.cancelAndJoin() }

        val ticket = bridge.extrasSeen.first()!!.getString(FlagBridgeContract.KEY_TICKET)
        assertTrue(send.isCancelled)
        assertEquals(listOf("signal Purge", "cancel $ticket"), bridge.calls)
        assertTrue(took < 5.seconds, "took $took")
    }

    @Test
    fun cancellingAnUndelayedSendWithdrawsNothing() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        bridge.stall = stall

        val send = launch { FlagBridgeClient(context, host).send(TestSignal.Purge, timeout = 10.seconds) }
        while (bridge.calls.isEmpty()) delay(10)
        send.cancelAndJoin()
        stall.countDown()

        assertEquals(listOf("signal Purge"), bridge.calls)
    }

    @Test
    fun aWithdrawalTheHostCannotTakeIsStillACancellation() = runBlocking<Unit> {
        val bridge = installBridge()
        val waiting = CountDownLatch(1)
        bridge.delayReached = waiting

        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            FlagBridgeClient(context, host).send(TestSignal.Purge, delay = 1.seconds)
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        bridge.failure = IllegalStateException("The host is going away.")
        send.cancelAndJoin()

        assertTrue(send.isCancelled)
    }

    // Helpers

    /** Replaces the companion's `<uses-permission>` list. */
    private fun requestPermission(vararg permissions: String) {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).requestedPermissions =
            arrayOf(*permissions)
    }

    private fun installHost(certificate: ByteArray = byteArrayOf(1, 2, 3)) {
        val info = PackageInfo().apply {
            packageName = host
            applicationInfo = ApplicationInfo().apply { packageName = host }
            signingInfo = signedWith(certificate)
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun signCompanion(certificate: ByteArray) {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).signingInfo =
            signedWith(certificate)
    }

    // Robolectric shadows SigningInfo: the signers are set on the shadow, not through a constructor.
    private fun signedWith(certificate: ByteArray) =
        SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(certificate))) }

    /** The framework's answer to a caller that lacks the provider's permission. */
    @Implements(ContentResolver::class)
    class RefusingContentResolver : ShadowContentResolver() {
        @Implementation
        override fun acquireUnstableContentProviderClient(name: String): ContentProviderClient? =
            throw SecurityException("Permission Denial: opening provider $name")
    }

    private companion object {
        // SHA-256 of the one-certificate signers [1, 2, 3] and [4, 5, 6].
        const val HOST_SHA256 = "039058C6F2C0CB492C533B0A4D14EF77CC0F78ABCCCED5287D84A1A2011CFB81"
        const val COMPANION_SHA256 = "787C798E39A5BC1910355BAE6D0CD87A36B2E10FD0202A83E3BB6B005DA83472"
    }

    /** A host with the bridge defines its permission; the system grants it to a companion signed alike. */
    @Suppress("DEPRECATION") // PermissionInfo() is the only constructor before API 28 and Robolectric reads only the name.
    private fun definePermission() {
        shadowOf(context.packageManager).addPermissionInfo(PermissionInfo().apply { name = permission })
    }

    private fun installBridge(): FakeBridgeProvider {
        definePermission()
        shadowOf(context).grantPermissions(permission)
        return Robolectric.buildContentProvider(FakeBridgeProvider::class.java)
            .create(FlagBridgeContract.authority(host))
            .get()
            .also {
                it.hostPackage = host
                it.schema = schema
            }
    }
}
