package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Walks the source stack to answer "what is this flag's value, and who supplied it?",
 * and watches the stack for changes.
 *
 * Kept apart from [FlagPole] so the pole can hand a fully formed lookup to its container
 * as it is built.
 */
internal class FlagResolver(
    private val sources: List<FlagValueSource>,
    override val keyEncoding: KeyEncoding,
) : FlagLookup {

    private val topMutableSource: MutableFlagValueSource? =
        sources.firstNotNullOfOrNull { it as? MutableFlagValueSource }

    /**
     * Every change every source reports, merged. Every source is subscribed before
     * `collect` first suspends. A collector more than 64 changes behind loses the
     * oldest, as with the sources themselves.
     */
    val changes: Flow<FlagChange> = flow {
        coroutineScope {
            val pending = Channel<FlagChange>(CHANGE_BUFFER, BufferOverflow.DROP_OLDEST)
            subscribe { change -> pending.trySend(change) }
            while (true) emit(pending.receive())
        }
    }

    /**
     * One tick straight away, then one whenever a change may affect [key]. Ticks are
     * conflated, so a reader that is busy reads once for any number of changes and never
     * misses the last one.
     */
    fun ticks(key: FlagKey): Flow<Unit> = flow {
        coroutineScope {
            val pending = Channel<Unit>(Channel.CONFLATED)
            subscribe { change -> if (change.affects(key)) pending.trySend(Unit) }
            pending.trySend(Unit)
            while (true) emit(pending.receive())
        }
    }

    /**
     * The first source holding a value of the declared type wins. A value of the wrong
     * type is skipped rather than surfaced, so a stale or hand-edited store degrades to
     * the next source instead of to a crash.
     */
    fun resolution(key: FlagKey, type: FlagValueType): FlagResolution {
        for (source in sources) {
            val box = source.box(key, type)
            if (box != null && box.matches(type)) return FlagResolution(key, source.sourceName, box)
        }
        return FlagResolution(key, sourceName = null, box = null)
    }

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = resolution(key, type).box

    /** Writes to the highest-priority source that accepts writes. */
    fun setOverride(key: FlagKey, box: FlagValueBox?) {
        writableSource().setBox(key, box)
    }

    /** Writes several values to the highest-priority mutable source, in one `setBoxes` call. */
    fun setOverrides(changes: Map<FlagKey, FlagValueBox?>) {
        writableSource().setBoxes(changes)
    }

    /**
     * What the source writes go to currently holds, whatever its type. Undoing a partly
     * applied import has to read back exactly that source, not the resolved value.
     */
    fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox? = topMutableSource?.box(key, type)

    private fun writableSource(): MutableFlagValueSource = topMutableSource ?: throw FlagException.NoMutableSource()

    /**
     * Subscribes to every source before returning. `UNDISPATCHED` registers each
     * subscription now, in the caller's frame. `Unconfined` then runs [onChange] on the
     * writer's thread, so a busy collector never leaves changes queued in a source's
     * bounded buffer. That makes the dispatcher part of the mechanism, not a choice to inject.
     */
    @Suppress("InjectDispatcher")
    private fun CoroutineScope.subscribe(onChange: (FlagChange) -> Unit) {
        for (source in sources) {
            launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                source.changes.collect { change -> onChange(change) }
            }
        }
    }

    private companion object {
        const val CHANGE_BUFFER = 64
    }
}
