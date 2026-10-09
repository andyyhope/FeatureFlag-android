package com.andyyhope.featureflag.sources

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Announces [FlagChange]s to any number of collectors without ever losing one.
 *
 * A `MutableSharedFlow` with a bounded buffer drops its oldest event when a collector
 * lags, and a collector resumed by an event loop that is already running (such as an
 * `Unconfined` or `Main.immediate` writer) lags no matter which dispatcher it asked for.
 * A dropped `Keys` change leaves a watched flag stale for good. This emitter instead
 * keeps one merged change per collector: [emit] folds the change into it, so memory stays
 * bounded and nothing is lost. A collector that falls behind receives one change covering
 * everything it missed.
 *
 * [emit] never suspends and never blocks on a collector, so it is safe from any thread and
 * delivery does not depend on a dispatcher. Changes reach each collector in its own
 * context. A collector sees only the changes emitted after its collection starts, and it
 * is registered before `collect` first suspends.
 */
public class FlagChangeEmitter {

    private val subscribers = CopyOnWriteArraySet<Subscriber>()

    /** How many collections are active. Exposed for tests. */
    internal val subscriberCount: Int
        get() = subscribers.size

    /** Cold: each collection registers itself when it starts and unregisters when it ends. */
    public val changes: Flow<FlagChange> = flow {
        val subscriber = Subscriber()
        subscribers.add(subscriber)
        try {
            while (true) {
                subscriber.signal.receive()
                subscriber.take()?.let { emit(it) }
            }
        } finally {
            subscribers.remove(subscriber)
        }
    }

    /** Hands [change] to every active collector. Never suspends, never blocks, never loses information. */
    public fun emit(change: FlagChange) {
        for (subscriber in subscribers) subscriber.offer(change)
    }

    private class Subscriber {
        val signal = Channel<Unit>(Channel.CONFLATED)
        private val lock = Any()
        private var pending: FlagChange? = null

        fun offer(change: FlagChange) {
            synchronized(lock) { pending = merge(pending, change) }
            signal.trySend(Unit)
        }

        fun take(): FlagChange? = synchronized(lock) {
            val taken = pending
            pending = null
            taken
        }
    }
}

internal fun merge(pending: FlagChange?, change: FlagChange): FlagChange = when {
    pending == null -> change
    pending is FlagChange.All || change is FlagChange.All -> FlagChange.All
    else -> FlagChange.Keys((pending as FlagChange.Keys).keys + (change as FlagChange.Keys).keys)
}
