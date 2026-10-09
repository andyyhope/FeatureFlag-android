package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * Walks the source stack to answer "what is this flag's value, and who supplied it?".
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
}
