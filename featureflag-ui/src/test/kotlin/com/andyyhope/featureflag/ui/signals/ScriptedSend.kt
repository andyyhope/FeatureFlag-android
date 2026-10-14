package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

/**
 * The bridge's send, scripted: each call is recorded and waits for the test to [answer] it, as the
 * host does once its delay has passed. A cancelled call is counted, as the client would withdraw it.
 */
internal class ScriptedSend {

    private val answers = Channel<Boolean>(Channel.UNLIMITED)

    /** Every call, in order, with the delay it asked the host for. */
    val calls = mutableListOf<Pair<FlagSignal, Duration>>()

    /** How many calls were cancelled while they waited. */
    var withdrawn = 0
        private set

    suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
        calls += signal to delay
        try {
            return answers.receive()
        } catch (cancelled: CancellationException) {
            withdrawn++
            throw cancelled
        }
    }

    /** The host's answer to the oldest call still waiting. */
    fun answer(handled: Boolean) {
        answers.trySend(handled)
    }
}
