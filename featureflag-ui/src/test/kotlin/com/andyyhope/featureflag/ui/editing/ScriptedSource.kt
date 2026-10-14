package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory source that records each write, and the thread it ran on, and can be told to
 * refuse writes the way a bridge does: whole, before anything is stored.
 */
internal class ScriptedSource(values: Map<FlagKey, FlagValueBox> = emptyMap()) : MutableFlagValueSource {

    private val snapshot = SnapshotSource("scripted", values)

    override val sourceName: String = "scripted"
    override val changes: Flow<FlagChange> = snapshot.changes

    /** Thrown by every write while set; nothing is stored. */
    @Volatile
    var failure: RuntimeException? = null

    /** Every write, in order, as one map per call. */
    val writes = CopyOnWriteArrayList<Map<FlagKey, FlagValueBox?>>()

    /** For each entry of [writes], the name of the thread it ran on. */
    val writeThreads = CopyOnWriteArrayList<String>()

    val values: Map<FlagKey, FlagValueBox> get() = snapshot.values

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = snapshot.box(key, type)

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        setBoxes(mapOf(key to box))
    }

    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        writes += changes
        writeThreads += Thread.currentThread().name
        failure?.let { throw it }
        changes.forEach { (key, box) -> snapshot.setBox(key, box) }
    }

    /** Writes as the host or another companion would, around the store. */
    fun elsewhereWrites(key: String, box: FlagValueBox?) {
        snapshot.setBox(FlagKey(key), box)
    }
}
