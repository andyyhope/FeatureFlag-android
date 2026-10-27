package com.andyyhope.featureflag.ui.companion

import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.editing.CompanionSchemas
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The session's states and its schema reload, which ports Swift's FlagSchemaReloadTests and the
// refresh half of FlagContainerDescriptionUITests: there a store re-read its schema; here the
// session reconnects and swaps the store when the host's schema changed.
// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class FlagCompanionSessionTest {

    private val schema = FlagSchema(generatedAt = CompanionSchemas.generatedAt, flags = listOf(flag("one")))
    private val grown = schema.copy(flags = listOf(flag("one"), flag("two")))

    // Connecting

    @Test
    fun startingConnectsAndBuildsAStore() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        assertEquals(FlagCompanionState.Connecting, session.state.value)

        session.start()
        runCurrent()

        val ready = assertIs<FlagCompanionState.Ready>(session.state.value)
        assertEquals(schema, ready.store.schema)
        assertNull(ready.failure)
        assertEquals(listOf(schema), host.opened.map { it.schema })
    }

    @Test
    fun startingTwiceConnectsOnce() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)

        session.start()
        session.start()
        runCurrent()

        assertEquals(1, host.connects)
    }

    @Test
    fun aHostThatIsNotConnectedIsUnavailableWithItsMessage() = runTest {
        val missing = FlagBridgeConnection.HostNotFound("com.example.host")
        val session = session(Host(missing))

        session.start()
        runCurrent()

        assertEquals(FlagCompanionState.Unavailable(missing.message), session.state.value)
    }

    @Test
    fun aSourceThatCannotOpenIsUnavailableWithItsMessage() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        host.openFailure = FlagBridgeException.Unavailable(SecurityException("Permission Denial"))
        val session = session(host)

        session.start()
        runCurrent()

        assertEquals(
            FlagCompanionState.Unavailable("The host could not be reached. Open the host app once, then try again."),
            session.state.value,
        )
    }

    @Test
    fun retryingFromUnavailableConnectsAgain() = runTest {
        val host = Host(FlagBridgeConnection.NotPublished, FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        assertIs<FlagCompanionState.Unavailable>(session.state.value)

        session.retry()
        assertEquals(FlagCompanionState.Connecting, session.state.value)
        runCurrent()

        assertIs<FlagCompanionState.Ready>(session.state.value)
        assertEquals(2, host.connects)
    }

    @Test
    fun retryingWhileReadyDoesNothing() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.retry()
        runCurrent()

        assertEquals(1, host.connects)
    }

    @Test
    fun comingBackWhileUnavailableRetries() = runTest {
        val host = Host(FlagBridgeConnection.NotPublished, FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.onForeground()
        runCurrent()

        assertIs<FlagCompanionState.Ready>(session.state.value)
    }

    // Coming back to the foreground

    @Test
    fun aChangedSchemaReplacesTheStore() = runTest {
        // The host was reinstalled with a new flag while the companion stayed open.
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.onForeground()
        runCurrent()

        val after = assertIs<FlagCompanionState.Ready>(session.state.value)
        assertNotSame(before.store, after.store)
        assertEquals(listOf("one", "two"), after.store.schema.flags.map { it.key.rawValue })
        assertEquals(listOf(schema, grown), host.opened.map { it.schema })
        assertTrue(host.opened[0].closed, "the old source outlived its store")
        assertFalse(host.opened[1].closed)
    }

    @Test
    fun aChangedSchemaIsAnnouncedOnce() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()

        session.state.test {
            val before = assertIs<FlagCompanionState.Ready>(awaitItem())
            session.onForeground()

            val after = assertIs<FlagCompanionState.Ready>(awaitItem())
            assertNotSame(before.store, after.store)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun anUnchangedSchemaKeepsTheStore() = runTest {
        // The screens keep their state: same store, overrides re-read instead.
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.state.test {
            awaitItem()
            session.onForeground()
            runCurrent()
            expectNoEvents()
        }

        assertSame(before, session.state.value)
        assertEquals(2, host.connects)
        assertEquals(1, host.opened.single().refreshThreads.size)
        assertFalse(host.opened.single().closed)
    }

    @Test
    fun aRepublishedSchemaWithTheSameFlagsKeepsTheStore() = runTest {
        // The host stamps generatedAt on every launch; a relaunch alone is not a change.
        val later = schema.copy(generatedAt = schema.generatedAt.plusSeconds(60))
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(later))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.onForeground()
        runCurrent()

        assertSame(before, session.state.value)
        assertEquals(1, host.opened.size)
        assertEquals(1, host.opened.single().refreshThreads.size)
        assertFalse(host.opened.single().closed)
    }

    @Test
    fun aHostThatHasStoppedPublishingLeavesTheLastSchemaInPlace() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.NotPublished)
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.onForeground()
        runCurrent()

        assertSame(before.store, assertIs<FlagCompanionState.Ready>(session.state.value).store)
        assertEquals(1, host.opened.size)
    }

    @Test
    fun aSessionThatIsNeverForegroundedNeverReconnects() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()

        host.opened.single().hostWrites("one", FlagValueBox.Bool(true))
        runCurrent()

        assertEquals(1, host.connects)
        assertEquals(schema, assertIs<FlagCompanionState.Ready>(session.state.value).store.schema)
    }

    @Test
    fun aRefreshPicksUpADescriptionAddedByTheHost() = runTest {
        val host = Host(
            FlagBridgeConnection.Connected(CompanionSchemas.plain),
            FlagBridgeConnection.Connected(CompanionSchemas.described),
        )
        val session = session(host)
        session.start()
        runCurrent()
        assertNull(assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.description)

        session.onForeground()
        runCurrent()

        assertEquals(
            "What the checkout team can turn on",
            assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.description,
        )
    }

    @Test
    fun aChangedSchemaWhoseSourceCannotOpenIsUnavailable() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()

        host.openFailure = FlagBridgeException.Unavailable(null)
        session.onForeground()
        runCurrent()

        assertIs<FlagCompanionState.Unavailable>(session.state.value)
        assertTrue(host.opened.single().closed)
    }

    // The host republishing its schema

    @Test
    fun anAnnouncedSchemaWithANewApplicationNameReplacesTheStore() = runTest {
        // An environment switch: the host relaunched and republished under a new name while the companion stayed open.
        val production = schema.copy(applicationName = "Demo · Production")
        val staging = schema.copy(applicationName = "Demo · Staging")
        val host = Host(FlagBridgeConnection.Connected(production), FlagBridgeConnection.Connected(staging))
        val session = session(host)
        session.start()
        runCurrent()

        host.announce()
        runCurrent()

        assertEquals("Demo · Staging", assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.applicationName)
    }

    @Test
    fun anAnnouncedSchemaWithNewEffectiveValuesReplacesTheStore() = runTest {
        val bundled = schema.copy(flags = listOf(flag("one").copy(effectiveValue = FlagValueBox.Bool(true), effectiveSource = "Bundled")))
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(bundled))
        val session = session(host)
        session.start()
        runCurrent()

        host.announce()
        runCurrent()

        val store = assertIs<FlagCompanionState.Ready>(session.state.value).store
        assertEquals(FlagValueBox.Bool(true), store.value(store.schema.flags.single()))
        assertEquals("Bundled", store.sourceName(store.schema.flags.single()))
    }

    @Test
    fun theHostIsWatchedOnceFromTheFirstConnectionAndUntilClose() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        assertEquals(0, host.watches)
        runCurrent()
        assertEquals(1, host.watches)

        session.onForeground()
        runCurrent()
        assertEquals(1, host.watches, "a replaced store must not watch the host again")

        session.close()
        assertTrue(host.watchClosed)
    }

    @Test
    fun aHostThatCannotBeWatchedStillReconnectsOnResume() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        host.watchable = false
        val session = session(host)
        session.start()
        runCurrent()

        session.onForeground()
        runCurrent()

        assertEquals(grown, assertIs<FlagCompanionState.Ready>(session.state.value).store.schema)
    }

    @Test
    fun aWatchThatCouldNotStartIsTriedAgainOnTheNextConnection() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        host.watchable = false
        val session = session(host)
        session.start()
        runCurrent()
        assertEquals(1, host.watches)

        host.watchable = true
        session.onForeground()
        runCurrent()
        assertEquals(2, host.watches)

        host.announce()
        runCurrent()
        assertEquals(3, host.connects, "the second watch hears the host")
    }

    @Test
    fun anAnnouncementDuringAConnectionReadsTheSchemaAgainAfterIt() = runTest {
        // The connection under way may have read the schema from before the announcement.
        val host = Host(
            FlagBridgeConnection.Connected(schema),
            FlagBridgeConnection.Connected(schema),
            FlagBridgeConnection.Connected(grown),
        )
        val session = session(host)
        session.start()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        host.gate = gate
        session.onForeground()
        runCurrent()

        host.announce()
        gate.complete(Unit)
        runCurrent()

        assertEquals(3, host.connects)
        assertEquals(grown, assertIs<FlagCompanionState.Ready>(session.state.value).store.schema)
    }

    @Test
    fun anAnnouncementWithNothingChangedKeepsTheStore() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        host.announce()
        runCurrent()

        assertSame(before, session.state.value)
        assertEquals(2, host.connects)
    }

    // Signals

    @Test
    fun aSignalReloadsTheSchemaOnceTheHostHasSettled() = runTest {
        // The host switches environment on the signal: it replies, restarts, and republishes under a new name.
        val staging = schema.copy(applicationName = "Demo · Staging")
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(staging))
        val session = session(host)
        session.start()
        runCurrent()

        session.onSignalSent()
        advanceTimeBy(FlagCompanionSession.SIGNAL_SETTLE - 1.milliseconds)
        runCurrent()
        assertEquals(1, host.connects, "reconnecting at once would reach the host before it restarted")

        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertEquals(2, host.connects)
        assertEquals("Demo · Staging", assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.applicationName)
    }

    @Test
    fun signalsInQuickSuccessionReloadOnce() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.onSignalSent()
        advanceTimeBy(FlagCompanionSession.SIGNAL_SETTLE / 2)
        session.onSignalSent()
        advanceTimeBy(FlagCompanionSession.SIGNAL_SETTLE * 2)
        runCurrent()

        assertEquals(2, host.connects)
    }

    @Test
    fun sendingThroughTheSessionReloadsAfterTheSignal() = runTest {
        // What FlagCompanionViewModel.send does: the signal goes out, then the session reloads.
        val staging = schema.copy(applicationName = "Demo · Staging")
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(staging))
        val session = session(host)
        session.start()
        runCurrent()
        val sent = mutableListOf<FlagSignal>()
        val send = session.reloadingAfter(backgroundScope) { signal, _ -> sent.add(signal) }

        assertTrue(send(Ping.Ping, Duration.ZERO))
        advanceTimeBy(FlagCompanionSession.SIGNAL_SETTLE * 2)
        runCurrent()

        assertEquals(listOf<FlagSignal>(Ping.Ping), sent)
        assertEquals("Demo · Staging", assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.applicationName)
    }

    @Test
    fun aSendThatThrowsSchedulesNoReload() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val send = session.reloadingAfter(backgroundScope) { _, _ -> throw IllegalStateException("refused") }

        assertFailsWith<IllegalStateException> { send(Ping.Ping, Duration.ZERO) }
        advanceTimeBy(FlagCompanionSession.SIGNAL_SETTLE * 2)
        runCurrent()

        assertEquals(1, host.connects)
    }

    @Test
    fun closingCancelsAReloadAfterASignal() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.onSignalSent()
        session.close()
        advanceTimeBy(FlagCompanionSession.SIGNAL_SETTLE * 2)
        runCurrent()

        assertEquals(1, host.connects)
    }

    // The banner

    @Test
    fun aFailedRefreshIsShownAndKeepsTheValues() = runTest {
        val overrides = mapOf(FlagKey("one") to FlagValueBox.Bool(true))
        val host = Host(FlagBridgeConnection.Connected(schema), values = overrides)
        val session = session(host)
        session.start()
        runCurrent()
        val store = assertIs<FlagCompanionState.Ready>(session.state.value).store
        val failure = FlagBridgeException.Unavailable(null)
        host.opened.single().refreshFailure = failure

        session.refresh()
        runCurrent()

        assertEquals(FlagCompanionState.Ready(store, failure), session.state.value)
        assertEquals(FlagValueBox.Bool(true), store.value(store.schema.flags.single()))
        assertTrue(store.isOverridden(store.schema.flags.single()))
    }

    @Test
    fun aRefreshThatSucceedsClearsTheBanner() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val source = host.opened.single()
        source.refreshFailure = FlagBridgeException.NotPublished()
        session.refresh()
        runCurrent()

        source.refreshFailure = null
        session.refresh()
        runCurrent()

        assertNull(assertIs<FlagCompanionState.Ready>(session.state.value).failure)
    }

    @Test
    fun tryAgainLoadsAChangedSchema() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.refresh()
        runCurrent()

        val after = assertIs<FlagCompanionState.Ready>(session.state.value)
        assertNotSame(before.store, after.store)
        assertEquals(grown, after.store.schema)
        assertTrue(host.opened[0].closed, "the old source outlived its store")
        assertFalse(host.opened[1].closed)
    }

    @Test
    fun tryAgainWithTheSameSchemaRefreshes() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.refresh()
        runCurrent()

        assertSame(before.store, assertIs<FlagCompanionState.Ready>(session.state.value).store)
        assertEquals(1, host.opened.single().refreshThreads.size)
        assertFalse(host.opened.single().closed)
    }

    @Test
    fun comingBackToAHostThatCannotBeReadShowsTheBanner() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        host.opened.single().refreshFailure = FlagBridgeException.Unavailable(null)

        session.onForeground()
        runCurrent()

        assertIs<FlagBridgeException.Unavailable>(assertIs<FlagCompanionState.Ready>(session.state.value).failure)
    }

    // The store

    @Test
    fun theStoreFollowsItsSource() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val store = assertIs<FlagCompanionState.Ready>(session.state.value).store

        host.opened.single().hostWrites("one", FlagValueBox.Bool(true))
        runCurrent()

        assertEquals(1L, store.revision.value)
    }

    @Test
    fun closingClosesTheSource() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.close()

        assertTrue(host.opened.single().closed)
    }

    @Test
    fun refreshesRunOnTheIoDispatcher() = runBlocking<Unit> {
        val io = Executors.newSingleThreadExecutor { Thread(it, "session-io") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = Host(FlagBridgeConnection.Connected(schema))
            val session = FlagCompanionSession(host::connect, host::open, scope, io.asCoroutineDispatcher())
            session.start()
            withTimeout(5.seconds) { session.state.first { it is FlagCompanionState.Ready } }

            session.refresh()
            val threads = host.opened.single().refreshThreads
            withTimeout(5.seconds) { while (threads.isEmpty()) delay(10) }

            // Coroutine debugging, on in tests, appends " @coroutine#n" to the name.
            assertTrue(threads.single().startsWith("session-io"), "$threads")
        } finally {
            scope.cancel()
            io.shutdown()
        }
    }

    // Helpers

    private enum class Ping : FlagSignal { Ping }

    /** A host that answers each connect with the next of [connections], then keeps giving the last. */
    private class Host(vararg connections: FlagBridgeConnection, val values: Map<FlagKey, FlagValueBox> = emptyMap()) {
        private val answers = ArrayDeque(connections.toList())
        var connects = 0
        val opened = mutableListOf<FakeCompanionSource>()
        var openFailure: FlagBridgeException? = null

        /** When set, each connect waits for it after taking its answer, as a slow host would. */
        var gate: CompletableDeferred<Unit>? = null
        var watchable = true
        var watches = 0
        var watchClosed = false
        private var onRepublish: (() -> Unit)? = null

        suspend fun connect(): FlagBridgeConnection {
            connects++
            val answer = if (answers.size > 1) answers.removeFirst() else answers.first()
            gate?.await()
            return answer
        }

        fun watch(onRepublish: () -> Unit): AutoCloseable? {
            watches++
            if (!watchable) return null
            this.onRepublish = onRepublish
            return AutoCloseable { watchClosed = true }
        }

        /** What publishSchema(context) does: tell a watching companion the schema was republished. */
        fun announce() {
            checkNotNull(onRepublish) { "nothing is watching" }()
        }

        suspend fun open(schema: FlagSchema): CompanionSource {
            openFailure?.let { throw it }
            return FakeCompanionSource(schema, values).also(opened::add)
        }
    }

    private fun TestScope.session(host: Host) =
        FlagCompanionSession(host::connect, host::open, backgroundScope, StandardTestDispatcher(testScheduler), host::watch)

    private fun flag(key: String) = CompanionSchemas.entry(key, listOf(key), key, FlagValueBox.Bool(false))
}
