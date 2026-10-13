package com.andyyhope.featureflag.android.bridge

import android.app.Application
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.DeadObjectException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagBridgeSourceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val host = "com.example.host"

    private val enabled = FlagKey("enabled")
    private val limit = FlagKey("limit")
    private val tier = FlagKey("tier")
    private val banner = FlagKey("banner")

    private val schema = FlagSchema(
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        flags = listOf(
            entry(enabled, FlagValueType.Bool, FlagValueBox.Bool(false)),
            entry(limit, FlagValueType.Int, FlagValueBox.Int(3)),
            entry(
                tier,
                FlagValueType.String,
                FlagValueBox.String("free"),
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry(banner, FlagValueType.String, FlagValueBox.String("")),
        ),
    )

    private lateinit var bridge: FakeBridgeProvider
    private val opened = mutableListOf<FlagBridgeSource>()

    @Before
    fun setUp() {
        bridge = Robolectric.buildContentProvider(FakeBridgeProvider::class.java)
            .create(FlagBridgeContract.authority(host))
            .get()
        bridge.hostPackage = host
        bridge.schema = schema
    }

    @After
    fun closeSources() {
        opened.forEach { it.close() }
    }

    // Construction and reads

    @Test
    fun aNewSourceStartsWithTheHostsOverrides() {
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))

        val source = source()

        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
        assertEquals("Companion", source.sourceName)
    }

    @Test
    fun theNameIsTheSourceName() {
        assertEquals("Bridge", source(name = "Bridge").sourceName)
    }

    @Test
    fun readsComeFromTheCacheAndNeverCallTheHost() {
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))
        val source = source()

        repeat(10) { source.box(enabled, FlagValueType.Bool) }

        assertEquals(listOf("overrides"), bridge.calls)
    }

    @Test
    fun aValueReadAsAnotherTypeIsNothing() {
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))

        assertNull(source().box(enabled, FlagValueType.Int))
    }

    @Test
    fun aSourceWhoseFirstRefreshFailsStartsEmptyInsteadOfThrowing() {
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED

        assertNull(source().box(enabled, FlagValueType.Bool))
        assertNull(source(host = "com.example.absent").box(enabled, FlagValueType.Bool))
    }

    @Test
    fun aSourceIsBuiltOffTheMainThread() {
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))

        val source = source()

        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
        assertEquals("overrides", bridge.calls.first())
        assertFalse(bridge.onMainThread.first(), "The first overrides call ran on the main thread.")
    }

    @Test
    fun aCancelledSourceCreationLeaksNothing() = runBlocking<Unit> {
        val stall = CountDownLatch(1)
        bridge.stall = stall
        val before = flagBridgeThreads().toSet()
        val uri = FlagBridgeContract.overridesUri(host)

        val creation = launch(Dispatchers.Default) { FlagBridgeClient(context, host).source(schema).also(opened::add) }
        val deadline = System.nanoTime() + THREAD_EXIT_NANOS
        while (bridge.calls.isEmpty() && System.nanoTime() < deadline) delay(10)
        assertTrue(bridge.calls.isNotEmpty(), "The source never reached the host.")
        creation.cancel()
        stall.countDown()
        creation.join()

        assertTrue(creation.isCancelled)
        val exit = System.nanoTime() + THREAD_EXIT_NANOS
        while ((flagBridgeThreads().toSet() - before).isNotEmpty() && System.nanoTime() < exit) delay(10)
        assertTrue((flagBridgeThreads().toSet() - before).isEmpty(), "A FlagBridgeSource thread outlived the cancelled call.")
        assertTrue(shadowOf(context.contentResolver).getContentObservers(uri).isEmpty())
        assertTrue(opened.isEmpty())
    }

    // Writes

    @Test
    fun setBoxSendsOneApplyAndAnnouncesTheKey() = runBlocking<Unit> {
        val source = source()

        source.changes.test {
            source.setBox(limit, FlagValueBox.Int(7))

            assertEquals(FlagChange.Keys(setOf(limit)), awaitItem())
        }
        assertEquals(FlagValueBox.Int(7), source.box(limit, FlagValueType.Int))
        assertEquals(mapOf(limit to FlagValueBox.Int(7)), bridge.values)
        assertEquals(listOf("overrides", "apply"), bridge.calls.take(2))
    }

    @Test
    fun setBoxesSendsEveryChangeInOneApply() = runBlocking<Unit> {
        bridge.values = mapOf(limit to FlagValueBox.Int(3))
        val source = source()

        source.changes.test {
            source.setBoxes(mapOf(enabled to FlagValueBox.Bool(true), limit to null))

            assertEquals(FlagChange.Keys(setOf(enabled, limit)), awaitItem())
        }
        assertEquals(mapOf(enabled to FlagValueBox.Bool(true)), bridge.values)
        assertEquals(1, bridge.calls.count { it == "apply" })
        assertNull(source.box(limit, FlagValueType.Int))
    }

    @Test
    fun writingWhatTheSourceAlreadyHoldsAnnouncesNothing() = runBlocking<Unit> {
        bridge.values = mapOf(limit to FlagValueBox.Int(3))
        val source = source()

        source.changes.test {
            source.setBox(limit, FlagValueBox.Int(3))
            source.setBox(enabled, null)

            delay(OBSERVER_SETTLE_MILLIS)
            expectNoEvents()
        }
    }

    @Test
    fun aRejectedWriteThrowsTheHostsReasonAndChangesNothing() = runBlocking<Unit> {
        val source = source()
        val expected = assertFailsWith<FlagImportException> {
            FlagPayload.decode(FlagPayload(mapOf(tier to FlagValueBox.String("enterprise"))).jsonBytes(), schema)
        }.message

        source.changes.test {
            val error = assertFailsWith<FlagBridgeException.Rejected> {
                source.setBox(tier, FlagValueBox.String("enterprise"))
            }

            assertEquals(expected, error.reason)
            expectNoEvents()
        }
        assertNull(source.box(tier, FlagValueType.String))
    }

    @Test
    fun writingToAHostThatNeverPublishedThrowsNotPublished() {
        val source = source()
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED

        assertFailsWith<FlagBridgeException.NotPublished> { source.setBox(enabled, FlagValueBox.Bool(true)) }
    }

    @Test
    fun writingToAHostWithoutTheBridgeThrowsUnavailable() {
        val source = source(host = "com.example.absent")

        assertFailsWith<FlagBridgeException.Unavailable> { source.setBox(enabled, FlagValueBox.Bool(true)) }
    }

    @Test
    fun aWriteIsRetriedOnceWhenTheHostDies() {
        val source = source()
        bridge.deaths.set(1)

        source.setBox(enabled, FlagValueBox.Bool(true))

        assertEquals(mapOf(enabled to FlagValueBox.Bool(true)), bridge.values)
    }

    @Test
    fun aHostThatDiesTwiceIsUnavailable() {
        val source = source()
        bridge.deaths.set(2)

        val error = assertFailsWith<FlagBridgeException.Unavailable> { source.setBox(enabled, FlagValueBox.Bool(true)) }

        assertIs<DeadObjectException>(error.cause)
    }

    @Test
    fun writingMoreThanTheLimitFailsBeforeSending() {
        // A Bundle past about 1 MB would throw TransactionTooLargeException; this never leaves the companion.
        val source = source()

        val error = assertFailsWith<FlagBridgeException.TooLarge> {
            source.setBox(banner, FlagValueBox.String("x".repeat(FlagBridgeContract.MAX_PAYLOAD_BYTES)))
        }

        assertTrue(error.bytes > FlagBridgeContract.MAX_PAYLOAD_BYTES)
        assertEquals(FlagBridgeContract.MAX_PAYLOAD_BYTES, error.limit)
        assertEquals(listOf("overrides"), bridge.calls)
    }

    @Test
    fun writingANonFiniteNumberIsRejectedBeforeSending() {
        val source = source()

        val error = assertFailsWith<FlagBridgeException.Rejected> {
            source.setBox(banner, FlagValueBox.Double(Double.NaN))
        }

        assertEquals(FlagBridgeException.Rejected(FlagException.UnsupportedValue(banner).message.orEmpty()).message, error.message)
        assertEquals(listOf("overrides"), bridge.calls)
    }

    // Refresh

    @Test
    fun refreshReplacesTheCacheAndAnnouncesAWholesaleChange() = runBlocking<Unit> {
        val source = source()
        // Changed without telling observers, as a host that was killed mid-write might leave it.
        bridge.values = mapOf(tier to FlagValueBox.String("pro"))

        source.changes.test {
            source.refresh()

            assertEquals(FlagChange.All, awaitItem())
        }
        assertEquals(FlagValueBox.String("pro"), source.box(tier, FlagValueType.String))
    }

    @Test
    fun aRefreshThatFindsNothingNewAnnouncesNothing() = runBlocking<Unit> {
        bridge.values = mapOf(tier to FlagValueBox.String("pro"))
        val source = source()

        source.changes.test {
            source.refresh()

            expectNoEvents()
        }
    }

    @Test
    fun refreshingOverridesTooLargeToCarryThrowsTooLarge() {
        val source = source()
        bridge.forcedStatus = FlagBridgeContract.STATUS_TOO_LARGE
        bridge.forcedSize = 600_000

        val error = assertFailsWith<FlagBridgeException.TooLarge> { source.refresh() }

        assertEquals(600_000, error.bytes)
        assertEquals(FlagBridgeContract.MAX_PAYLOAD_BYTES, error.limit)
    }

    @Test
    fun refreshingAHostThatNeverPublishedThrowsNotPublished() {
        val source = source()
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED

        assertFailsWith<FlagBridgeException.NotPublished> { source.refresh() }
    }

    @Test
    fun aHostThatFailsWhileAnsweringIsUnavailable() {
        val source = source()
        bridge.failure = IllegalStateException("boom")

        assertFailsWith<FlagBridgeException.Unavailable> { source.refresh() }
        assertFailsWith<FlagBridgeException.Unavailable> { source.setBox(enabled, FlagValueBox.Bool(true)) }
    }

    @Test
    @Config(shadows = [UnobservableContentResolver::class])
    fun aSourceThatCannotObserveTheHostIsUnavailableAndLeaksNoThread() {
        val error = assertFailsWith<FlagBridgeException.Unavailable> { source() }

        assertIs<SecurityException>(error.cause)
        val deadline = System.nanoTime() + THREAD_EXIT_NANOS
        while (flagBridgeThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(flagBridgeThreads().isEmpty())
    }

    // Observing

    @Test
    fun aChangeTheHostMakesReachesTheSourceThroughTheObserver() = runBlocking<Unit> {
        val source = source()

        source.changes.test {
            bridge.hostWrites(mapOf(enabled to FlagValueBox.Bool(true)))

            assertEquals(FlagChange.All, awaitItem())
        }
        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
    }

    @Test
    fun afterCloseTheHostsChangesAreNotObserved() = runBlocking<Unit> {
        val source = source()
        source.close()

        source.changes.test {
            bridge.hostWrites(mapOf(enabled to FlagValueBox.Bool(true)))

            delay(OBSERVER_SETTLE_MILLIS)
            expectNoEvents()
        }
        assertNull(source.box(enabled, FlagValueType.Bool))
    }

    // Helpers

    private fun source(name: String = "Companion", host: String = this.host): FlagBridgeSource =
        runBlocking { FlagBridgeClient(context, host).source(schema, name) }.also(opened::add)

    private fun flagBridgeThreads() =
        Thread.getAllStackTraces().keys.filter { it.name == "FlagBridgeSource" && it.isAlive }

    /** The framework's answer when the provider cannot be resolved for the caller. */
    @Implements(ContentResolver::class)
    class UnobservableContentResolver : ShadowContentResolver() {
        @Implementation
        override fun registerContentObserver(uri: Uri, notifyForDescendents: Boolean, observer: ContentObserver) =
            throw SecurityException("Failed to find provider for ${uri.authority}")
    }

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

    private companion object {
        /** Long enough for the observer thread to have refreshed, had it been told to. */
        const val OBSERVER_SETTLE_MILLIS = 300L
        const val THREAD_EXIT_NANOS = 2_000_000_000L
    }
}
