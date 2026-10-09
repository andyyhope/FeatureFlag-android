package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An in-memory set of flag values.
 *
 * Useful as the top of a stack in tests and previews, as the landing place for an
 * imported payload, and as a way to capture the current state of a pole.
 *
 * Reads are lock-free: each one sees a single immutable map, which every write replaces
 * whole. Writes are serialised, and a write that changes nothing announces nothing.
 */
public class SnapshotSource(
    name: String = "Snapshot",
    values: Map<FlagKey, FlagValueBox> = emptyMap(),
) : MutableFlagValueSource {

    override val sourceName: String = name

    private val lock = Any()

    @Volatile
    private var storage: Map<FlagKey, FlagValueBox> = values.toMap()

    private val changeFlow = MutableSharedFlow<FlagChange>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<FlagChange> = changeFlow.asSharedFlow()

    /** Everything this snapshot holds, as of the moment it is read. */
    public val values: Map<FlagKey, FlagValueBox>
        get() = storage

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = storage[key]

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        val changed = synchronized(lock) {
            val current = storage
            if (current[key] == box) {
                false
            } else {
                storage = if (box == null) current - key else current + (key to box)
                true
            }
        }
        if (changed) changeFlow.tryEmit(FlagChange.Keys(setOf(key)))
    }

    /** Replaces the entire contents in one step, reporting a single change. */
    public fun replaceAll(values: Map<FlagKey, FlagValueBox>) {
        val replacement = values.toMap()
        synchronized(lock) { storage = replacement }
        changeFlow.tryEmit(FlagChange.All)
    }

    private companion object {
        const val CHANGE_BUFFER = 64
    }
}
