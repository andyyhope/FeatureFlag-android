package com.andyyhope.featureflag.bridge

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PermissionInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagBridgeProviderTest {

    private enum class TestSignal : FlagSignal { Purge, Unobserved }

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val host = context.packageName
    private val permission = FlagBridgeContract.permission(host)
    private val preferences = context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val enabled = FlagKey("enabled")
    private val limit = FlagKey("limit")
    private val ratio = FlagKey("ratio")
    private val scale = FlagKey("scale")
    private val tier = FlagKey("tier")
    private val banner = FlagKey("banner")

    private val schema = FlagSchema(
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        flags = listOf(
            entry(enabled, FlagValueType.Bool, FlagValueBox.Bool(false)),
            entry(limit, FlagValueType.Int, FlagValueBox.Int(3)),
            entry(ratio, FlagValueType.Double, FlagValueBox.Double(0.5)),
            entry(scale, FlagValueType.Float, FlagValueBox.Float(1f)),
            entry(
                tier,
                FlagValueType.String,
                FlagValueBox.String("free"),
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry(banner, FlagValueType.String, FlagValueBox.String("")),
        ),
    )

    private lateinit var provider: FlagBridgeProvider

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(permission)
        schema.write(FlagBridgeContract.schemaDirectory(context))
        provider = Robolectric.buildContentProvider(FlagBridgeProvider::class.java)
            .create(FlagBridgeContract.authority(host))
            .get()
    }

    // Permission

    @Test
    fun theManifestGuardsTheProviderWithTheHostsSignaturePermission() {
        // The placeholders resolve to the app that merges the library: here, the unit-test app.
        @Suppress("DEPRECATION")
        val info = checkNotNull(context.packageManager.resolveContentProvider(FlagBridgeContract.authority(host), 0))
        val declared = context.packageManager.getPermissionInfo(permission, 0)

        assertEquals(FlagBridgeProvider::class.java.name, info.name)
        assertTrue(info.exported)
        assertEquals(permission, info.readPermission)
        assertEquals(permission, info.writePermission)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, declared.protection)
    }

    @Test
    fun aCallerWithoutThePermissionIsRefused() {
        // Defence in depth: the manifest permission already stops a stranger opening the provider.
        shadowOf(context).denyPermissions(permission)

        assertFailsWith<SecurityException> { provider.call(FlagBridgeContract.METHOD_HELLO, null, null) }
        assertFailsWith<SecurityException> { provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", null) }
    }

    // Hello

    @Test
    fun helloAnswersTheProtocol() {
        val reply = provider.call(FlagBridgeContract.METHOD_HELLO, null, null)

        assertEquals(FlagBridgeContract.PROTOCOL, reply.getInt(FlagBridgeContract.KEY_PROTOCOL))
    }

    // Overrides

    @Test
    fun overridesAnswersTheStoredOverridesAsAnExport() {
        preferences.edit().putBoolean("enabled", true).putLong("limit", 7L).putString("ratio", "0.25").commit()

        val reply = provider.call(FlagBridgeContract.METHOD_OVERRIDES, null, null)

        assertEquals(FlagBridgeContract.STATUS_OK, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(
            mapOf(enabled to FlagValueBox.Bool(true), limit to FlagValueBox.Int(7), ratio to FlagValueBox.Double(0.25)),
            payload(reply).values,
        )
    }

    @Test
    fun overridesLeavesOutKeysTheSchemaDoesNotDeclare() {
        preferences.edit().putString("stray", "left by the host's own code").putBoolean("enabled", true).commit()

        val reply = provider.call(FlagBridgeContract.METHOD_OVERRIDES, null, null)

        assertEquals(mapOf(enabled to FlagValueBox.Bool(true)), payload(reply).values)
    }

    @Test
    fun aStoredValueTheSchemaRejectsIsLeftOutOfOverrides() {
        // The file belongs to the host, so its own code may have written any of these.
        preferences.edit()
            .putString("tier", "enterprise") // a string, but not one of the cases
            .putString("limit", "seven") // the wrong type: a type-guided read finds nothing
            .putFloat("scale", Float.NaN) // a float JSON cannot carry
            .putBoolean("enabled", true)
            .commit()

        val reply = provider.call(FlagBridgeContract.METHOD_OVERRIDES, null, null)

        assertEquals(FlagBridgeContract.STATUS_OK, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(mapOf(enabled to FlagValueBox.Bool(true)), payload(reply).values)
    }

    @Test
    fun overridesLargerThanTheLimitAreRefusedNotThrown() {
        // Past about 1 MB a Bundle would throw TransactionTooLargeException in the host.
        preferences.edit().putString("banner", "x".repeat(FlagBridgeContract.MAX_PAYLOAD_BYTES)).commit()

        val reply = provider.call(FlagBridgeContract.METHOD_OVERRIDES, null, null)

        assertEquals(FlagBridgeContract.STATUS_TOO_LARGE, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertTrue(reply.getInt(FlagBridgeContract.KEY_SIZE) > FlagBridgeContract.MAX_PAYLOAD_BYTES)
        assertNull(reply.getByteArray(FlagBridgeContract.KEY_PAYLOAD))
    }

    @Test
    fun everyMethodAnswersNotPublishedWithoutASchema() {
        FlagBridgeContract.schemaDirectory(context).deleteRecursively()

        val overrides = provider.call(FlagBridgeContract.METHOD_OVERRIDES, null, null)
        val apply = provider.call(FlagBridgeContract.METHOD_APPLY, null, applyExtras(mapOf(enabled to FlagValueBox.Bool(true))))

        assertEquals(FlagBridgeContract.STATUS_NOT_PUBLISHED, overrides.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(FlagBridgeContract.STATUS_NOT_PUBLISHED, apply.getString(FlagBridgeContract.KEY_STATUS))
        assertFalse(preferences.contains("enabled"))
        // The rest still answer, so a companion can tell "not published" from "not reachable".
        assertEquals(FlagBridgeContract.PROTOCOL, provider.call(FlagBridgeContract.METHOD_HELLO, null, null).getInt(FlagBridgeContract.KEY_PROTOCOL))
        assertFalse(provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", null).getBoolean(FlagBridgeContract.KEY_HANDLED))
        assertFailsWith<FileNotFoundException> { provider.openFile(FlagBridgeContract.schemaUri(host), "r") }
    }

    // Apply

    @Test
    fun applyWritesEveryValueAndRemovesEveryKeyItNames() {
        preferences.edit().putLong("limit", 3L).commit()

        val reply = provider.call(
            FlagBridgeContract.METHOD_APPLY,
            null,
            applyExtras(mapOf(enabled to FlagValueBox.Bool(true), ratio to FlagValueBox.Double(0.25)), remove = listOf(limit)),
        )

        assertEquals(FlagBridgeContract.STATUS_OK, reply.getString(FlagBridgeContract.KEY_STATUS))
        // The host's own pole reads the same file through the same source.
        val hostSource = SharedPreferencesSource.companion(context)
        assertEquals(FlagValueBox.Bool(true), hostSource.box(enabled, FlagValueType.Bool))
        assertEquals(FlagValueBox.Double(0.25), hostSource.box(ratio, FlagValueType.Double))
        assertFalse(preferences.contains("limit"))
        hostSource.close()
    }

    @Test
    fun applyRejectsAPayloadTheSchemaRejectsAndWritesNothing() {
        val extras = applyExtras(mapOf(enabled to FlagValueBox.Bool(true), tier to FlagValueBox.String("enterprise")))
        val expected = assertFailsWith<FlagImportException> {
            FlagPayload.decode(extras.getByteArray(FlagBridgeContract.KEY_PAYLOAD)!!, schema)
        }.message

        val reply = provider.call(FlagBridgeContract.METHOD_APPLY, null, extras)

        assertEquals(FlagBridgeContract.STATUS_REJECTED, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(expected, reply.getString(FlagBridgeContract.KEY_MESSAGE))
        assertFalse(preferences.contains("enabled"))
    }

    @Test
    fun applyRejectsADocumentThatIsNotJson() {
        val extras = Bundle().apply { putByteArray(FlagBridgeContract.KEY_PAYLOAD, "nope".encodeToByteArray()) }

        val reply = provider.call(FlagBridgeContract.METHOD_APPLY, null, extras)

        assertEquals(FlagBridgeContract.STATUS_REJECTED, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals("The document could not be read: not a JSON object.", reply.getString(FlagBridgeContract.KEY_MESSAGE))
    }

    @Test
    fun aNonFiniteNumberIsRejectedNotThrown() {
        // Strict import reads -1e400 as negative infinity, which the store cannot hold. The provider
        // must answer, not throw into the host's binder thread, and must write nothing.
        val document = """{"formatVersion" : 1, "values" : {"enabled" : true, "ratio" : -1e400}}"""
        val extras = Bundle().apply { putByteArray(FlagBridgeContract.KEY_PAYLOAD, document.encodeToByteArray()) }

        val reply = provider.call(FlagBridgeContract.METHOD_APPLY, null, extras)

        assertEquals(FlagBridgeContract.STATUS_REJECTED, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(FlagException.UnsupportedValue(ratio).message, reply.getString(FlagBridgeContract.KEY_MESSAGE))
        assertFalse(preferences.contains("enabled"))
        assertFalse(preferences.contains("ratio"))
    }

    @Test
    fun applyRejectsRemovingAKeyTheSchemaDoesNotDeclare() {
        val extras = applyExtras(
            mapOf(enabled to FlagValueBox.Bool(true)),
            remove = listOf(limit, FlagKey("ghost"), FlagKey("phantom")),
        )

        val reply = provider.call(FlagBridgeContract.METHOD_APPLY, null, extras)

        assertEquals(FlagBridgeContract.STATUS_REJECTED, reply.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(
            "The host's schema has no flag 'ghost' or 'phantom', so it cannot remove them. Reconnect to load the current schema.",
            reply.getString(FlagBridgeContract.KEY_MESSAGE),
        )
        assertFalse(preferences.contains("enabled"))
    }

    @Test
    fun applyOverwritesAValueStoredWithAnotherType() {
        preferences.edit().putString("limit", "seven").commit()

        provider.call(FlagBridgeContract.METHOD_APPLY, null, applyExtras(mapOf(limit to FlagValueBox.Int(7))))

        assertEquals(7L, preferences.getLong("limit", 0L))
    }

    // Observing

    @Test
    fun anApplyNotifiesObserversOfTheOverrides() {
        val changes = observeOverrides()

        provider.call(FlagBridgeContract.METHOD_APPLY, null, applyExtras(mapOf(enabled to FlagValueBox.Bool(true))))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(changes.get() > 0)
    }

    @Test
    fun aNoOpApplyStillAnnouncesAChange() {
        // The companion's ordering of replies relies on every successful apply being announced.
        preferences.edit().putBoolean("enabled", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        val changes = observeOverrides()

        provider.call(FlagBridgeContract.METHOD_APPLY, null, applyExtras(mapOf(enabled to FlagValueBox.Bool(true))))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(changes.get() > 0)
    }

    @Test
    fun aWriteByTheHostItselfNotifiesObserversToo() {
        // The host's own setOverride lands in the same file, so a companion sees it as well.
        val changes = observeOverrides()

        preferences.edit().putBoolean("enabled", true).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(changes.get() > 0)
    }

    // Signals

    @Test
    fun aSignalIsDeliveredThroughTheHostChannel() {
        val purges = AtomicInteger()
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { purges.incrementAndGet() }
        try {
            val handled = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", null)
            val unobserved = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Unobserved", null)

            assertTrue(handled.getBoolean(FlagBridgeContract.KEY_HANDLED))
            assertFalse(unobserved.getBoolean(FlagBridgeContract.KEY_HANDLED))
            assertEquals(1, purges.get())
        } finally {
            observation.close()
        }
    }

    @Test
    fun aSignalWaitsNoLongerThanItsDeadline() {
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { }
        try {
            startUp()
            val deadline = realClock() + 300

            val (handled, waited) = signalFromBackground(deadline)

            // The task is queued behind a main looper that never runs, as a blocked handler would hold it.
            assertFalse(handled)
            assertTrue(waited in 100..2_000, "waited $waited ms")
        } finally {
            observation.close()
        }
    }

    @Test
    fun anExpiredDeadlineAnswersAtOnce() {
        val purges = AtomicInteger()
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { purges.incrementAndGet() }
        try {
            startUp()

            val (handled, waited) = signalFromBackground(realClock() - 5_000)
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(handled)
            assertTrue(waited < 1_000, "waited $waited ms")
            // Idled after the answer: nothing was queued for the handler to run from.
            assertEquals(0, purges.get())
        } finally {
            observation.close()
        }
    }

    @Test
    fun anExpiredDeadlineDoesNotWaitForStartup() {
        // The main looper never runs, as on a cold host: the client has given up, so the barrier is not worth holding a thread for.
        provider.elapsedRealtime = ::realClock

        val (handled, waited) = signalFromBackground(realClock() - 5_000)

        assertFalse(handled)
        assertTrue(waited < 1_000, "waited $waited ms")
    }

    @Test
    fun aSignalWithoutADeadlineKeepsTheDefaultMeasuredFromWhenItArrived() {
        provider.elapsedRealtime = { 10_000L }

        val timeout = provider.deliveryTimeout(null, enteredAt = 9_000L)

        assertEquals(FlagSignalChannel.DELIVERY_TIMEOUT - 1.seconds, timeout)
        assertEquals(Duration.ZERO, provider.deliveryTimeout(Bundle(), enteredAt = 1_000L))
    }

    @Test
    fun aDeadlineLeavesTheReplyItsMargin() {
        provider.elapsedRealtime = { 10_000L }

        assertEquals(1_900.milliseconds, provider.deliveryTimeout(deadlineExtras(12_000L), enteredAt = 10_000L))
    }

    @Test
    fun aFarDeadlineIsClamped() {
        provider.elapsedRealtime = { 10_000L }

        assertEquals(30.seconds, provider.deliveryTimeout(deadlineExtras(Long.MAX_VALUE), enteredAt = 10_000L))
        assertEquals(Duration.ZERO, provider.deliveryTimeout(deadlineExtras(Long.MIN_VALUE), enteredAt = 10_000L))
    }

    // Startup barrier

    @Test
    fun theFirstCallFromAnotherThreadWaitsForTheMainLooper() {
        // On a device the first call can arrive while Application.onCreate is still running.
        val answered = CountDownLatch(1)
        thread { provider.call(FlagBridgeContract.METHOD_HELLO, null, null).also { answered.countDown() } }

        assertFalse(answered.await(200, TimeUnit.MILLISECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(answered.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun afterTheFirstAnswerCallsDoNotWaitAgain() {
        val first = CountDownLatch(1)
        thread { provider.call(FlagBridgeContract.METHOD_HELLO, null, null).also { first.countDown() } }
        while (!first.await(10, TimeUnit.MILLISECONDS)) shadowOf(Looper.getMainLooper()).idle()

        val second = CountDownLatch(1)
        thread { provider.call(FlagBridgeContract.METHOD_HELLO, null, null).also { second.countDown() } }

        assertTrue(second.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aMainLooperThatNeverRunsDelaysTheFirstAnswerOnlyUntilTheBound() {
        provider.startupTimeout = 100.milliseconds
        val answered = CountDownLatch(1)
        thread { provider.call(FlagBridgeContract.METHOD_HELLO, null, null).also { answered.countDown() } }

        assertTrue(answered.await(5, TimeUnit.SECONDS))
    }

    // Schema file

    @Test
    fun openFileServesThePublishedSchema() {
        val descriptor = provider.openFile(FlagBridgeContract.schemaUri(host), "r")

        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertContentEquals(schema.jsonBytes(), bytes)
    }

    @Test
    fun openFileRefusesAnyOtherPath() {
        assertFailsWith<FileNotFoundException> { provider.openFile(FlagBridgeContract.overridesUri(host), "r") }
        assertFailsWith<FileNotFoundException> {
            provider.openFile(Uri.parse("content://${FlagBridgeContract.authority(host)}/schema/../secrets"), "r")
        }
    }

    // Everything else

    @Test
    fun anUnknownMethodIsAnError() {
        val error = assertFailsWith<IllegalArgumentException> { provider.call("nope", null, null) }

        assertEquals("Unknown bridge method 'nope'.", error.message)
    }

    @Test
    fun theTableApiIsEmpty() {
        val uri = FlagBridgeContract.overridesUri(host)

        assertNull(provider.query(uri, null, null, null, null))
        assertNull(provider.getType(uri))
        assertNull(provider.insert(uri, ContentValues()))
        assertEquals(0, provider.update(uri, ContentValues(), null, null))
        assertEquals(0, provider.delete(uri, null, null))
    }

    // Helpers

    private fun entry(
        key: FlagKey,
        type: FlagValueType,
        default: FlagValueBox,
        cases: List<FlagValueBox>? = null,
    ) = FlagSchema.Entry(
        key = key,
        propertyPath = listOf(key.rawValue),
        description = key.rawValue,
        valueType = type,
        defaultValue = default,
        cases = cases,
    )

    private fun deadlineExtras(deadline: Long) = Bundle().apply { putLong(FlagBridgeContract.KEY_DEADLINE, deadline) }

    private fun realClock(): Long = System.nanoTime() / NANOS_PER_MILLI

    /** Lets the first call through the startup barrier, so later calls do not need the main looper. */
    private fun startUp() {
        provider.elapsedRealtime = ::realClock
        val first = CountDownLatch(1)
        thread { provider.call(FlagBridgeContract.METHOD_HELLO, null, null).also { first.countDown() } }
        while (!first.await(10, TimeUnit.MILLISECONDS)) shadowOf(Looper.getMainLooper()).idle()
    }

    /** Sends Purge with [deadline] from a binder-like thread; whether it was handled, and how long it took in ms. */
    private fun signalFromBackground(deadline: Long): Pair<Boolean, Long> {
        val answer = AtomicReference<Pair<Boolean, Long>>()
        val done = CountDownLatch(1)
        thread {
            val started = realClock()
            val reply = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", deadlineExtras(deadline))
            answer.set(reply.getBoolean(FlagBridgeContract.KEY_HANDLED) to (realClock() - started))
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return answer.get()
    }

    private fun applyExtras(values: Map<FlagKey, FlagValueBox>, remove: List<FlagKey> = emptyList()) = Bundle().apply {
        putByteArray(FlagBridgeContract.KEY_PAYLOAD, FlagPayload(values).jsonBytes())
        putStringArray(FlagBridgeContract.KEY_REMOVE, remove.map { it.rawValue }.toTypedArray())
    }

    private fun payload(reply: Bundle): FlagPayload =
        FlagPayload.decode(checkNotNull(reply.getByteArray(FlagBridgeContract.KEY_PAYLOAD)), schema)

    private fun observeOverrides(): AtomicInteger {
        val changes = AtomicInteger()
        context.contentResolver.registerContentObserver(
            FlagBridgeContract.overridesUri(host),
            false,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    changes.incrementAndGet()
                }
            },
        )
        return changes
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
