package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow

/** What changed in a source. */
public sealed interface FlagChange {

    /** Whether someone watching [key] has to read it again. */
    public fun affects(key: FlagKey): Boolean

    /**
     * Every key should be read again. A source that cannot say more precisely what
     * changed, such as a whole remote payload landing, reports this.
     */
    public data object All : FlagChange {
        override fun affects(key: FlagKey): Boolean = true
    }

    /** Only these keys changed. */
    public data class Keys(public val keys: Set<FlagKey>) : FlagChange {
        override fun affects(key: FlagKey): Boolean = key in keys
    }
}

/**
 * Supplies flag values to a `FlagPole`.
 *
 * A pole holds an ordered stack of these and asks each in turn, so the order of the
 * stack is the precedence.
 *
 * Implementations must be safe to call from any thread, and [box] must be cheap: apps
 * read flags constantly, from whatever thread they happen to be on.
 */
public interface FlagValueSource {

    /** Identifies the source when explaining where a value came from. */
    public val sourceName: String

    /**
     * The value this source holds for [key], or null.
     *
     * [type] is the flag's declared type. A source stored in a loosely typed medium can
     * use it to decode exactly rather than guess.
     */
    public fun box(key: FlagKey, type: FlagValueType): FlagValueBox?

    /**
     * Emits whenever stored values change.
     *
     * Delivery must be lossless: no change may be dropped, though changes a slow collector
     * has not yet seen may be merged into one. A bounded, dropping buffer such as a
     * `MutableSharedFlow` with `DROP_OLDEST` breaks this. Use `FlagChangeEmitter`.
     *
     * A collector must be subscribed by the time `collect` first suspends. A pole's flows
     * rely on that so that they never miss a write made while they subscribe.
     */
    public val changes: Flow<FlagChange>
}

/** A source whose values can change at runtime, such as a companion's override or an import. */
public interface MutableFlagValueSource : FlagValueSource {

    /** Stores [box] for [key], or removes the key when [box] is null. */
    public fun setBox(key: FlagKey, box: FlagValueBox?)

    /**
     * Stores several values. The default writes each one through [setBox]. A source that
     * can commit them together, such as one backed by preferences, should override this.
     */
    public fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        changes.forEach { (key, box) -> setBox(key, box) }
    }
}
