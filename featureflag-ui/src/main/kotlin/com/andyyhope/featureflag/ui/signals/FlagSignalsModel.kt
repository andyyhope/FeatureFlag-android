package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A delayed send's countdown, drawn from the clock rather than animated. */
internal data class FlagSignalCountdown(val start: Instant, val duration: Duration) {

    /** When the host delivers the signal. */
    val end: Instant
        get() = start + duration.toJavaDuration()

    /** How far round the ring is at [at]: 0 at the tap, 1 once the delay has passed, never outside 0 to 1. */
    fun progress(at: Instant): Float {
        if (duration <= Duration.ZERO) return 1f
        val elapsed = java.time.Duration.between(start, at).toNanos().toDouble()
        return (elapsed / duration.inWholeNanoseconds).coerceIn(0.0, 1.0).toFloat()
    }
}

/** One send and what came of it, for the Recent list. */
internal data class FlagSignalAttempt(val signal: FlagSignal, val handled: Boolean, val at: Instant) {

    /** "handled — relaunch to see it" for a signal that needs a relaunch, "handled", or "no response". */
    val outcome: String
        get() = when {
            handled && signal.requiresRestart -> "handled — relaunch to see it"
            handled -> "handled"
            else -> "no response"
        }
}

/**
 * The Signals screen's state. One send at a time: an instant one is in flight until the host
 * answers; a delayed one is pending, with a countdown, until the host delivers it and answers.
 *
 * A delayed send asks the host once, at the tap, and the host waits. Tapping the pending signal
 * again, or leaving the screen, cancels the send's coroutine, which withdraws it from the host.
 * Plain Kotlin; call it from one thread, the main thread in the app.
 */
internal class FlagSignalsModel(
    private val send: suspend (signal: FlagSignal, delay: Duration) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {

    /** The delay the next tap uses. */
    val delay: MutableStateFlow<FlagSignalDelay> = MutableStateFlow(FlagSignalDelay.Instant)

    private val pendingState = MutableStateFlow<FlagSignal?>(null)
    private val countdownState = MutableStateFlow<FlagSignalCountdown?>(null)
    private val inFlightState = MutableStateFlow<FlagSignal?>(null)
    private val historyState = MutableStateFlow<List<FlagSignalAttempt>>(emptyList())

    /** The signal counting down to delivery, if any. */
    val pending: StateFlow<FlagSignal?> = pendingState.asStateFlow()

    /** The pending signal's countdown. */
    val countdown: StateFlow<FlagSignalCountdown?> = countdownState.asStateFlow()

    /** The instant send waiting for the host's answer, if any. */
    val inFlight: StateFlow<FlagSignal?> = inFlightState.asStateFlow()

    /** The latest attempts, newest first, at most [HISTORY_LIMIT]. */
    val history: StateFlow<List<FlagSignalAttempt>> = historyState.asStateFlow()

    private var pendingJob: Job? = null

    /** Whether [signal]'s button is disabled: another send is under way. The pending signal stays enabled, to cancel it. */
    fun isDisabled(signal: FlagSignal): Boolean {
        val pending = pending.value
        return inFlight.value != null || (pending != null && pending != signal)
    }

    /** A tap on [signal]: cancels it when it is the pending one, otherwise sends it with the chosen [delay]. */
    fun tapped(signal: FlagSignal) {
        if (pending.value == signal) {
            cancelPending()
            return
        }
        if (isDisabled(signal)) return
        val chosen = delay.value.duration
        if (chosen > Duration.ZERO) schedule(signal, chosen) else sendNow(signal)
    }

    /** Cancels the pending send, which withdraws it from the host, and records nothing. */
    fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
        pendingState.value = null
        countdownState.value = null
    }

    private fun sendNow(signal: FlagSignal) {
        inFlightState.value = signal
        scope.launch {
            val handled = send(signal, Duration.ZERO)
            record(signal, handled)
            inFlightState.value = null
        }
    }

    private fun schedule(signal: FlagSignal, duration: Duration) {
        pendingState.value = signal
        countdownState.value = FlagSignalCountdown(clock(), duration)
        pendingJob = scope.launch {
            val handled = send(signal, duration)
            // A send cancelled as it returned must not clear a newer one's state.
            ensureActive()
            pendingJob = null
            pendingState.value = null
            countdownState.value = null
            record(signal, handled)
        }
    }

    private fun record(signal: FlagSignal, handled: Boolean) {
        val attempt = FlagSignalAttempt(signal, handled, clock())
        historyState.update { (listOf(attempt) + it).take(HISTORY_LIMIT) }
    }

    companion object {
        /** How many attempts Recent shows. */
        const val HISTORY_LIMIT: Int = 5
    }
}
