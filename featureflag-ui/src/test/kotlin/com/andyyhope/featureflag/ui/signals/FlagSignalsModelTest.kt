package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The send loop of Swift's FlagSignalsModel, with the delay handed to the host: one send at a time,
// a cancellable countdown, and the five latest results.
// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class FlagSignalsModelTest {

    private enum class TestSignal(override val requiresRestart: Boolean = false) : FlagSignal {
        Purge,
        Refetch,
        SwapDependency(requiresRestart = true),
    }

    private val host = ScriptedSend()
    private var now = Instant.parse("2026-10-14T09:00:00Z")

    // Sending at once

    @Test
    fun theDelayStartsAtInstant() = runTest {
        assertEquals(FlagSignalDelay.Instant, model().delay.value)
    }

    @Test
    fun anInstantSendIsInFlightUntilItsAnswer() = runTest {
        val model = model()

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertEquals(TestSignal.Purge, model.inFlight.value)
        assertNull(model.pending.value)
        assertEquals(listOf<Pair<FlagSignal, Duration>>(TestSignal.Purge to Duration.ZERO), host.calls)

        host.answer(true)
        runCurrent()

        assertNull(model.inFlight.value)
        assertEquals(listOf(FlagSignalAttempt(TestSignal.Purge, handled = true, at = now)), model.history.value)
    }

    @Test
    fun whileASendIsInFlightEverySignalIsDisabled() = runTest {
        val model = model()

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertTrue(model.isDisabled(TestSignal.Purge))
        assertTrue(model.isDisabled(TestSignal.Refetch))
        model.tapped(TestSignal.Refetch)
        runCurrent()
        assertEquals(1, host.calls.size)
    }

    // Sending after a delay

    @Test
    fun aDelayedSendAsksTheHostOnceWithItsDelay() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Five

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertEquals(listOf<Pair<FlagSignal, Duration>>(TestSignal.Purge to 5.seconds), host.calls)
        assertEquals(TestSignal.Purge, model.pending.value)
        assertEquals(FlagSignalCountdown(now, 5.seconds), model.countdown.value)
        assertNull(model.inFlight.value)
    }

    @Test
    fun aDelayedSendClearsItsCountdownAndRecordsTheAnswer() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Three
        model.tapped(TestSignal.SwapDependency)
        runCurrent()
        now = now.plusSeconds(3)

        host.answer(true)
        runCurrent()

        assertNull(model.pending.value)
        assertNull(model.countdown.value)
        val attempt = model.history.value.single()
        assertEquals(FlagSignalAttempt(TestSignal.SwapDependency, handled = true, at = now), attempt)
        assertEquals("handled — relaunch to see it", attempt.outcome)
    }

    @Test
    fun cancellingDuringTheCountdownRecordsNothing() = runTest {
        // Leaving the tab cancels too. The host must not deliver it, so the send itself is cancelled.
        val model = model()
        model.delay.value = FlagSignalDelay.Ten
        model.tapped(TestSignal.Purge)
        runCurrent()

        model.cancelPending()
        runCurrent()

        assertNull(model.pending.value)
        assertNull(model.countdown.value)
        assertTrue(model.history.value.isEmpty())
        assertEquals(1, host.withdrawn)
        // A late answer for the cancelled send changes nothing.
        host.answer(true)
        runCurrent()
        assertTrue(model.history.value.isEmpty())
    }

    @Test
    fun tappingThePendingSignalAgainCancelsIt() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Three
        model.tapped(TestSignal.Purge)
        runCurrent()

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertNull(model.pending.value)
        assertEquals(1, host.withdrawn)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun whileOneSignalCountsDownTheOthersAreDisabled() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Five
        model.tapped(TestSignal.Purge)
        runCurrent()

        assertFalse(model.isDisabled(TestSignal.Purge), "the pending signal stays enabled, to cancel it")
        assertTrue(model.isDisabled(TestSignal.Refetch))
        model.tapped(TestSignal.Refetch)
        runCurrent()
        assertEquals(TestSignal.Purge, model.pending.value)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun cancellingTheCountdownLeavesAnInstantSendAlone() = runTest {
        val model = model()
        model.tapped(TestSignal.Purge)
        runCurrent()

        model.cancelPending()
        host.answer(false)
        runCurrent()

        assertEquals(0, host.withdrawn)
        assertEquals(listOf(false), model.history.value.map { it.handled })
    }

    @Test
    fun aSignalCanBeSentAgainOnceItsCountdownIsCancelled() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Three
        model.tapped(TestSignal.Purge)
        runCurrent()
        model.cancelPending()

        model.tapped(TestSignal.Purge)
        runCurrent()
        host.answer(true)
        runCurrent()

        assertEquals(2, host.calls.size)
        assertEquals(listOf(true), model.history.value.map { it.handled })
        assertNull(model.pending.value)
    }

    // History

    @Test
    fun historyIsNewestFirstAndKeepsFive() = runTest {
        val model = model()
        repeat(6) { index ->
            now = now.plusSeconds(1)
            model.tapped(if (index % 2 == 0) TestSignal.Purge else TestSignal.Refetch)
            runCurrent()
            host.answer(index != 5)
            runCurrent()
        }

        val history = model.history.value
        assertEquals(FlagSignalsModel.HISTORY_LIMIT, history.size)
        assertEquals(
            listOf(TestSignal.Refetch, TestSignal.Purge, TestSignal.Refetch, TestSignal.Purge, TestSignal.Refetch),
            history.map { it.signal },
        )
        assertEquals(listOf(false, true, true, true, true), history.map { it.handled })
        assertEquals(history.map { it.at }.sortedDescending(), history.map { it.at })
    }

    @Test
    fun eachOutcomeSaysWhatHappened() {
        assertEquals("handled — relaunch to see it", FlagSignalAttempt(TestSignal.SwapDependency, true, now).outcome)
        assertEquals("handled", FlagSignalAttempt(TestSignal.Purge, true, now).outcome)
        assertEquals("no response", FlagSignalAttempt(TestSignal.Purge, false, now).outcome)
        assertEquals("no response", FlagSignalAttempt(TestSignal.SwapDependency, false, now).outcome)
    }

    private fun TestScope.model() = FlagSignalsModel(host::send, backgroundScope) { now }
}
