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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private lateinit var threadsBefore: Set<Thread>

    @Before
    fun setUp() {
        threadsBefore = flagBridgeThreads().toSet()
        bridge = Robolectric.buildContentProvider(FakeBridgeProvider::class.java)
            .create(FlagBridgeContract.authority(host))
            .get()
        bridge.hostPackage = host
        bridge.schema = schema
    }

    @After
    fun closeSources() {
        bridge.stall?.countDown()
        bridge.replyGate?.countDown()
        bridge.applyGate?.countDown()
        opened.forEach { it.close() }
        // quitSafely still runs what is queued; none of it may reach the next test's provider.
        awaitSourceThreadsGone()
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
        // Without this the host's own announcement can let a refresh overtake the write (see aWriteOvertakenByANewerRefreshIsSkipped).
        bridge.notifiesOnApply = false
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
        // Without this the host's own announcement can let a refresh overtake the write (see aWriteOvertakenByANewerRefreshIsSkipped).
        bridge.notifiesOnApply = false
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

    // Failures and ordering

    @Test
    fun aRefreshThatFailsIsReportedAndKeepsTheCache() {
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))
        val source = source()
        assertNull(source.lastFailure.value)
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED

        assertFailsWith<FlagBridgeException.NotPublished> { source.refresh() }

        assertIs<FlagBridgeException.NotPublished>(source.lastFailure.value)
        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
    }

    @Test
    fun aSuccessfulRefreshClearsTheFailure() {
        val source = source()
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED
        assertFailsWith<FlagBridgeException.NotPublished> { source.refresh() }
        bridge.forcedStatus = null

        source.refresh()

        assertNull(source.lastFailure.value)
    }

    @Test
    fun anObserverRefreshThatFailsIsReported() = runBlocking<Unit> {
        val source = source()
        bridge.forcedStatus = FlagBridgeContract.STATUS_REJECTED

        bridge.hostWrites(mapOf(enabled to FlagValueBox.Bool(true)))

        val failure = withTimeout(OBSERVER_WAIT_MILLIS) { source.lastFailure.first { it != null } }
        assertIs<FlagBridgeException.Rejected>(failure)
    }

    @Test
    fun aFirstRefreshThatFailsIsReported() {
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED

        val source = source()

        assertNull(source.box(enabled, FlagValueType.Bool))
        assertIs<FlagBridgeException.NotPublished>(source.lastFailure.value)
    }

    @Test
    fun aWriteDoesNotClearAFailure() {
        val source = source()
        bridge.forcedStatus = FlagBridgeContract.STATUS_NOT_PUBLISHED
        assertFailsWith<FlagBridgeException.NotPublished> { source.refresh() }
        bridge.forcedStatus = null
        bridge.notifiesOnApply = false

        source.setBox(enabled, FlagValueBox.Bool(true))

        assertIs<FlagBridgeException.NotPublished>(source.lastFailure.value)
    }

    @Test
    fun anOlderReplyDoesNotOverwriteANewerOne() = runBlocking<Unit> {
        val source = source()
        val gate = CountDownLatch(1)
        val reached = CountDownLatch(1)
        bridge.replyGate = gate
        bridge.replyReached = reached
        // Its reply is computed from the empty overrides, then held while a newer refresh passes it.
        val older = launch(Dispatchers.Default) { source.refresh() }
        assertTrue(reached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The older refresh never reached the host.")
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))

        source.changes.test {
            source.refresh()
            assertEquals(FlagChange.All, awaitItem())

            gate.countDown()
            older.join()

            expectNoEvents()
        }
        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
        assertNull(source.lastFailure.value)
    }

    @Test
    fun anOlderReplyDoesNotOverwriteAWrite() = runBlocking<Unit> {
        val source = source()
        val gate = CountDownLatch(1)
        val reached = CountDownLatch(1)
        bridge.replyGate = gate
        bridge.replyReached = reached
        bridge.notifiesOnApply = false
        val older = launch(Dispatchers.Default) { source.refresh() }
        assertTrue(reached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The older refresh never reached the host.")

        source.changes.test {
            source.setBox(enabled, FlagValueBox.Bool(true))
            assertEquals(FlagChange.Keys(setOf(enabled)), awaitItem())

            gate.countDown()
            older.join()

            expectNoEvents()
        }
        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
    }

    @Test
    fun aRefreshIssuedDuringAWriteIsNotDropped() = runBlocking<Unit> {
        val source = source()
        val applyGate = CountDownLatch(1)
        val applyReached = CountDownLatch(1)
        val replyGate = CountDownLatch(1)
        val replyReached = CountDownLatch(1)
        bridge.applyGate = applyGate
        bridge.applyReached = applyReached
        bridge.notifiesOnApply = false
        // The host commits the write but its reply is held; meanwhile another change lands and a refresh
        // reads both, then its own reply is held until after the write has returned.
        val write = launch(Dispatchers.Default) { source.setBox(enabled, FlagValueBox.Bool(true)) }
        assertTrue(applyReached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The write never reached the host.")
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true), limit to FlagValueBox.Int(7))
        bridge.replyGate = replyGate
        bridge.replyReached = replyReached
        val refresh = launch(Dispatchers.Default) { source.refresh() }
        assertTrue(replyReached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The refresh never reached the host.")

        applyGate.countDown()
        write.join()
        replyGate.countDown()
        refresh.join()

        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
        assertEquals(FlagValueBox.Int(7), source.box(limit, FlagValueType.Int))
    }

    @Test
    fun aWriteOvertakenByANewerRefreshIsSkipped() = runBlocking<Unit> {
        val source = source()
        val applyGate = CountDownLatch(1)
        val applyReached = CountDownLatch(1)
        bridge.applyGate = applyGate
        bridge.applyReached = applyReached
        bridge.notifiesOnApply = false
        // The host commits the write but holds its reply; a refresh that began after the write reads it and
        // is applied first, so the write's own update is stale by the time its reply arrives.
        val write = launch(Dispatchers.Default) { source.setBox(enabled, FlagValueBox.Bool(true)) }
        assertTrue(applyReached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The write never reached the host.")
        bridge.values = bridge.values + (limit to FlagValueBox.Int(7))

        source.changes.test {
            source.refresh()
            assertEquals(FlagChange.All, awaitItem())

            applyGate.countDown()
            write.join()

            expectNoEvents()
        }
        assertEquals(FlagValueBox.Bool(true), source.box(enabled, FlagValueType.Bool))
        assertEquals(FlagValueBox.Int(7), source.box(limit, FlagValueType.Int))
    }

    @Test
    fun aNoOpWriteDoesNotLoseANewerHostChange() = runBlocking<Unit> {
        bridge.values = mapOf(enabled to FlagValueBox.Bool(true))
        val source = source()
        val gate = CountDownLatch(1)
        val reached = CountDownLatch(1)
        bridge.replyGate = gate
        bridge.replyReached = reached
        // The host changes and the observer's refresh reads it, but its reply is held.
        bridge.hostWrites(mapOf(enabled to FlagValueBox.Bool(true), limit to FlagValueBox.Int(7)))
        assertTrue(reached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The observer's refresh never reached the host.")

        // A write of what the host already holds returns first. The host still announces it, as the real
        // provider does, so a refresh follows the write and the held reply may be dropped.
        source.setBox(enabled, FlagValueBox.Bool(true))
        gate.countDown()

        withTimeout(OBSERVER_WAIT_MILLIS) {
            while (source.box(limit, FlagValueType.Int) == null) delay(10)
        }
        assertEquals(FlagValueBox.Int(7), source.box(limit, FlagValueType.Int))
        assertNull(source.lastFailure.value)
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

    @Test
    fun aClosedSourceIgnoresALateChange() = runBlocking<Unit> {
        val source = source()
        val gate = CountDownLatch(1)
        val reached = CountDownLatch(1)
        bridge.replyGate = gate
        bridge.replyReached = reached
        // The first notification holds the observer thread inside its refresh; the second waits queued behind it.
        bridge.hostWrites(emptyMap())
        assertTrue(reached.await(THREAD_EXIT_NANOS, TimeUnit.NANOSECONDS), "The observer never reached the host.")
        bridge.hostWrites(mapOf(enabled to FlagValueBox.Bool(true)))
        source.close()

        source.changes.test {
            gate.countDown()
            awaitSourceThreadsGone()

            expectNoEvents()
        }
        assertEquals(listOf("overrides", "overrides"), bridge.calls.toList())
        assertNull(source.box(enabled, FlagValueType.Bool))
    }

    // Helpers

    private fun source(name: String = "Companion", host: String = this.host): FlagBridgeSource =
        runBlocking { FlagBridgeClient(context, host).source(schema, name) }.also(opened::add)

    /** Waits for every FlagBridgeSource thread this test started to finish what it had queued and exit. */
    private fun awaitSourceThreadsGone() {
        val deadline = System.nanoTime() + THREAD_EXIT_NANOS
        while ((flagBridgeThreads().toSet() - threadsBefore).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue((flagBridgeThreads().toSet() - threadsBefore).isEmpty(), "A FlagBridgeSource thread outlived its test.")
    }

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
        const val OBSERVER_WAIT_MILLIS = 5_000L
        const val THREAD_EXIT_NANOS = 2_000_000_000L
    }
}
