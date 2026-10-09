package com.andyyhope.featureflag.sources

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
 * [emit] never suspends and never waits for a slow collector, so it is safe from any
 * thread and delivery does not depend on a dispatcher. It may run an `Unconfined`
 * collector's code inline, on the writer's thread. A collector sees only the changes
 * emitted after its collection starts, and it is registered before `collect` first suspends.
 */
public class FlagChangeEmitter {

    private val inboxes = CopyOnWriteArraySet<ChangeInbox>()

    /** How many collections are active. Exposed for tests. */
    internal val subscriberCount: Int
        get() = inboxes.size

    /** Cold: each collection registers itself when it starts and unregisters when it ends. */
    public val changes: Flow<FlagChange> = flow {
        val inbox = ChangeInbox()
        inboxes.add(inbox)
        try {
            while (true) emit(inbox.next())
        } finally {
            inboxes.remove(inbox)
        }
    }

    /** Hands [change] to every active collector. Never suspends, never waits, never loses information. */
    public fun emit(change: FlagChange) {
        for (inbox in inboxes) inbox.offer(change)
    }
}
