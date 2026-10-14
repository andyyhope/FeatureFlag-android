package com.andyyhope.featureflag.bridge

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.android.bridge.FlagBridgeSource
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * The bridge across a real process boundary. The test app is both apps: the tests run in its
 * main process as the companion, and FlagBridgeProvider runs in its `:host` process, where
 * [HostTestApplication] publishes the schema and observes signals.
 */
@RunWith(AndroidJUnit4::class)
class CrossProcessBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = context.packageName
    private val schema = BridgeTestHost.schema

    // Long enough for a cold start of the host, including its deliberately slow onCreate.
    private val client = FlagBridgeClient(context, host, timeout = 15.seconds)
    private val opened = mutableListOf<FlagBridgeSource>()

    @Before
    fun clearOverrides() {
        source().setBoxes(schema.flags.associate { it.key to null })
    }

    @After
    fun closeSources() {
        opened.forEach { it.close() }
    }

    // connect

    @Test
    fun connectLoadsTheSchemaTheHostPublished() = runBlocking<Unit> {
        assertEquals(FlagBridgeConnection.Connected(schema), client.connect())
    }

    @Test
    fun aColdHostConnectsWithinTheDefaultTimeout() = runBlocking<Unit> {
        client.connect()
        killHost()

        assertEquals(FlagBridgeConnection.Connected(schema), FlagBridgeClient(context, host).connect())
    }

    @Test
    fun theProviderRunsInTheHostProcess() = runBlocking<Unit> {
        client.connect()

        val pid = assertNotNull(hostPid())
        assertNotEquals(Process.myPid(), pid)
    }

    // Overrides

    @Test
    fun everyValueTypeCrossesTheProcessBoundary() {
        val values = mapOf(
            BridgeTestHost.enabled to FlagValueBox.Bool(true),
            BridgeTestHost.limit to FlagValueBox.Int(Long.MAX_VALUE),
            BridgeTestHost.ratio to FlagValueBox.Double(0.1),
            BridgeTestHost.scale to FlagValueBox.Float(0.1f),
            BridgeTestHost.tier to FlagValueBox.String("pro"),
            BridgeTestHost.logo to FlagValueBox.Data(byteArrayOf(0, 1, -1)),
            BridgeTestHost.launch to FlagValueBox.Date(Instant.parse("2026-10-10T09:41:00Z")),
            BridgeTestHost.endpoint to FlagValueBox.Url(URI("https://example.com/a?b=c")),
            BridgeTestHost.markets to FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
            BridgeTestHost.limits to FlagValueBox.Dictionary(mapOf("daily" to FlagValueBox.Int(5))),
        )

        source().setBoxes(values)

        val reader = source()
        for (entry in schema.flags) {
            assertEquals(values[entry.key], reader.box(entry.key, entry.valueType), "${entry.key}")
        }
    }

    @Test
    fun aWriteByOneSourceReachesAnotherThroughTheObserver() = runBlocking<Unit> {
        val writer = source()
        val watcher = source()

        watcher.changes.test(timeout = 10.seconds) {
            writer.setBox(BridgeTestHost.tier, FlagValueBox.String("pro"))

            assertEquals(FlagChange.All, awaitItem())
        }
        assertEquals(FlagValueBox.String("pro"), watcher.box(BridgeTestHost.tier, FlagValueType.String))
    }

    @Test
    fun aWriteTheHostRejectsComesBackWithItsReason() {
        val expected = assertFailsWith<FlagImportException> {
            FlagPayload.decode(FlagPayload(mapOf(BridgeTestHost.tier to FlagValueBox.String("enterprise"))).jsonBytes(), schema)
        }.message

        val error = assertFailsWith<FlagBridgeException.Rejected> {
            source().setBox(BridgeTestHost.tier, FlagValueBox.String("enterprise"))
        }

        assertEquals(expected, error.reason)
    }

    @Test
    fun removingAKeyTheHostDoesNotKnowIsRejected() {
        val error = assertFailsWith<FlagBridgeException.Rejected> { source().setBox(FlagKey("ghost"), null) }

        assertEquals(
            "The host's schema has no flag 'ghost', so it cannot remove it. Reconnect to load the current schema.",
            error.reason,
        )
    }

    // Signals

    @Test
    fun aSignalIsHandledInTheHostProcess() = runBlocking<Unit> {
        assertTrue(client.send(BridgeTestSignal.Purge))
    }

    @Test
    fun aSignalNoObserverListsIsNotHandled() = runBlocking<Unit> {
        assertFalse(client.send(BridgeTestSignal.Unobserved))
    }

    @Test
    fun aSignalWhoseHandlerThrowsIsNotHandledAndTheHostSurvives() = runBlocking<Unit> {
        client.connect()
        val pid = hostPid()

        assertFalse(client.send(BridgeTestSignal.Explode))
        assertEquals(pid, hostPid())
    }

    @Test
    fun aSignalToAColdHostWaitsForItsApplicationToStart() = runBlocking<Unit> {
        // Without the startup barrier the call lands mid-onCreate, before the observer exists (S6).
        client.connect()
        killHost()

        assertTrue(client.send(BridgeTestSignal.Purge))
    }

    @Test
    fun aColdHostRunsASlowSignalWithinTheCallersBudget() = runBlocking<Unit> {
        // The handler takes 4.5 s, longer than the old fixed 4 s delivery timeout; the host now waits for
        // what is left of the caller's 7 s after its 1 s cold start.
        client.connect()
        killHost()

        assertTrue(client.send(BridgeTestSignal.Slow, timeout = 10.seconds))
    }

    // Delayed signals

    @Test
    fun aDelayedSignalIsHandledOnceItsDelayHasPassed() = runBlocking<Unit> {
        deliveries.delete()
        client.connect()

        val (handled, waited) = measureTimedValue { client.send(BridgeTestSignal.Record, delay = 2.seconds) }

        assertTrue(handled)
        assertTrue(waited >= 2.seconds, "answered after $waited")
        assertEquals(listOf("Record"), deliveries.readLines())
    }

    @Test
    fun aCancelledDelayedSignalIsNotDelivered() = runBlocking<Unit> {
        deliveries.delete()
        client.connect()
        val pid = hostPid()

        val send = launch { client.send(BridgeTestSignal.Record, delay = 3.seconds) }
        delay(1.seconds)
        send.cancelAndJoin()
        // Past the moment the host would have delivered it.
        delay(4.seconds)

        assertFalse(deliveries.exists(), "the host delivered a cancelled signal")
        assertEquals(pid, hostPid())
        // The host is still answering signals.
        assertTrue(client.send(BridgeTestSignal.Record))
        assertEquals(listOf("Record"), deliveries.readLines())
    }

    // Host death

    @Test
    fun aCallTheHostDiesDuringIsRetriedOnceOnAFreshHost() = runBlocking<Unit> {
        File(context.filesDir, HostTestApplication.DIE_ONCE_MARKER).delete()
        client.connect()
        val before = assertNotNull(hostPid())

        // The first delivery kills the host mid-call: DeadObjectException. The retry starts a new one.
        assertTrue(client.send(BridgeTestSignal.DieOnce))
        assertNotEquals(before, hostPid())
    }

    @Test
    fun aSourceSurvivesTheHostProcessDying() = runBlocking<Unit> {
        val held = source()
        held.setBox(BridgeTestHost.enabled, FlagValueBox.Bool(true))
        val before = assertNotNull(hostPid())

        killHost()

        // The observer is registered with the system, not the provider, so it outlives the host:
        // a write from elsewhere cold-starts a new host, whose notification still reaches it.
        held.changes.test(timeout = 10.seconds) {
            source().setBox(BridgeTestHost.tier, FlagValueBox.String("pro"))

            assertEquals(FlagChange.All, awaitItem())
        }
        assertEquals(FlagValueBox.String("pro"), held.box(BridgeTestHost.tier, FlagValueType.String))
        assertNotEquals(before, hostPid())

        // The held source's next write acquires a fresh client and succeeds.
        held.setBox(BridgeTestHost.limit, FlagValueBox.Int(7))
        val reader = source()
        assertEquals(FlagValueBox.Bool(true), reader.box(BridgeTestHost.enabled, FlagValueType.Bool))
        assertEquals(FlagValueBox.Int(7), reader.box(BridgeTestHost.limit, FlagValueType.Int))
    }

    // Absent host

    @Test
    fun aSourceForAnAbsentHostIsUnavailable() {
        // Sources opened by this test already own threads of this name, so count rather than look for one.
        val before = sourceThreads()

        val error = assertFailsWith<FlagBridgeException.Unavailable> {
            runBlocking { FlagBridgeClient(context, ABSENT_HOST).source(schema) }
        }

        assertTrue(error.cause is SecurityException, "${error.cause}")
        val deadline = SystemClock.uptimeMillis() + THREAD_EXIT_MILLIS
        while (sourceThreads() > before && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MILLIS)
        assertEquals(before, sourceThreads(), "A FlagBridgeSource thread outlived its failed construction.")
    }

    // Helpers

    private fun source(): FlagBridgeSource = runBlocking { client.source(schema) }.also(opened::add)

    /** What the host's Record handler wrote; both processes share filesDir. */
    private val deliveries: File get() = File(context.filesDir, HostTestApplication.DELIVERIES_LOG)

    private fun sourceThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == SOURCE_THREAD_NAME }

    private fun hostPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == host + HostTestApplication.HOST_PROCESS_SUFFIX }
        ?.pid

    private fun killHost() {
        val pid = assertNotNull(hostPid(), "The host process is not running.")
        Process.killProcess(pid)
        val deadline = SystemClock.uptimeMillis() + KILL_TIMEOUT_MILLIS
        while (hostPid() == pid && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MILLIS)
        assertNotEquals(pid, hostPid(), "The host process outlived killProcess.")
    }

    private companion object {
        const val KILL_TIMEOUT_MILLIS = 10_000L
        const val THREAD_EXIT_MILLIS = 2_000L
        const val POLL_MILLIS = 50L
        const val ABSENT_HOST = "com.andyyhope.featureflag.absent"
        const val SOURCE_THREAD_NAME = "FlagBridgeSource"
    }
}
