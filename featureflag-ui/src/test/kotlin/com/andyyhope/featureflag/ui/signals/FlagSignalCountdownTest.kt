package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Ports Swift's FlagSignalCountdownTests: the ring is drawn from the clock, so how far round it is
// at a given moment is plain arithmetic.
// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class FlagSignalCountdownTest {

    private enum class CountdownSignal : FlagSignal { Purge }

    private val start = Instant.parse("2001-01-01T00:16:40Z")

    @Test
    fun theRingIsEmptyWhenTheCountdownStarts() {
        assertEquals(0f, FlagSignalCountdown(start, 5.seconds).progress(start))
    }

    @Test
    fun theRingIsHalfwayHalfwayThrough() {
        assertEquals(0.5f, FlagSignalCountdown(start, 10.seconds).progress(start.plusSeconds(5)))
    }

    @Test
    fun theRingIsFullOnceTheDelayHasPassed() {
        // A companion back from the background after the send shows a full ring.
        assertEquals(1f, FlagSignalCountdown(start, 3.seconds).progress(start.plusSeconds(30)))
    }

    @Test
    fun theRingNeverRunsBackwards() {
        assertEquals(0f, FlagSignalCountdown(start, 3.seconds).progress(start.minusSeconds(1)))
    }

    @Test
    fun theCountdownEndsWhenTheSignalFires() {
        assertEquals(start.plusSeconds(5), FlagSignalCountdown(start, 5.seconds).end)
    }

    @Test
    fun aCountdownOfNoTimeIsAlreadyFull() {
        assertEquals(1f, FlagSignalCountdown(start, Duration.ZERO).progress(start))
    }

    @Test
    fun schedulingASignalStartsACountdownForTheChosenDelay() = runTest {
        val model = FlagSignalsModel(ScriptedSend()::send, backgroundScope) { start }
        model.delay.value = FlagSignalDelay.Five

        model.tapped(CountdownSignal.Purge)
        runCurrent()

        val countdown = assertNotNull(model.countdown.value)
        assertEquals(CountdownSignal.Purge, model.pending.value)
        assertEquals(5.seconds, countdown.duration)
        assertTrue(countdown.start >= start)
        model.cancelPending()
    }

    @Test
    fun cancellingClearsTheCountdown() = runTest {
        val model = FlagSignalsModel(ScriptedSend()::send, backgroundScope) { start }
        model.delay.value = FlagSignalDelay.Three

        model.tapped(CountdownSignal.Purge)
        model.tapped(CountdownSignal.Purge)
        runCurrent()

        assertNull(model.pending.value)
        assertNull(model.countdown.value)
    }
}
