package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import kotlinx.coroutines.channels.Channel

/**
 * One collector's mailbox: changes offered while it is busy merge into a single pending
 * change, so nothing is lost and memory stays bounded.
 *
 * Merge rules: `All` absorbs everything, and `Keys` unite. Accumulating is linear in the
 * number of keys, however many changes arrive before the collector takes them.
 */
internal class ChangeInbox {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var all = false
    private val keys = HashSet<FlagKey>()

    /** Never suspends. May run a collector that is waiting in [next] inline, if it is `Unconfined`. */
    fun offer(change: FlagChange) {
        synchronized(lock) {
            when (change) {
                is FlagChange.All -> {
                    all = true
                    keys.clear()
                }
                is FlagChange.Keys -> if (!all) keys.addAll(change.keys)
            }
        }
        signal.trySend(Unit)
    }

    /** Suspends until something is pending, then takes all of it. */
    suspend fun next(): FlagChange {
        while (true) {
            signal.receive()
            take()?.let { return it }
        }
    }

    private fun take(): FlagChange? = synchronized(lock) {
        val taken = when {
            all -> FlagChange.All
            keys.isNotEmpty() -> FlagChange.Keys(keys.toSet())
            else -> null
        }
        all = false
        keys.clear()
        taken
    }
}
