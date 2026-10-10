package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** A source nothing can write to, standing in for a remote payload. */
internal class ReadOnlySource(
    override val sourceName: String,
    private val values: Map<FlagKey, FlagValueBox> = emptyMap(),
) : FlagValueSource {
    override val changes: Flow<FlagChange> = emptyFlow()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]
}

/** Counts every single write and records every batch, so a test can see how much a bulk operation touched. */
internal class CountingSource : MutableFlagValueSource {
    override val sourceName: String = "counting"
    override val changes: Flow<FlagChange> = emptyFlow()
    var writes: Int = 0
    val batches = mutableListOf<Map<FlagKey, FlagValueBox?>>()
    private val values = mutableMapOf<FlagKey, FlagValueBox>()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        writes += 1
        if (box == null) {
            values.remove(key)
        } else {
            values[key] = box
        }
    }

    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        batches.add(changes)
        super.setBoxes(changes)
    }
}

/** Runs [task] [times] times across eight threads, and rethrows the first failure. */
internal fun runConcurrently(times: Int, task: (Int) -> Unit) {
    val pool = Executors.newFixedThreadPool(8)
    try {
        pool.invokeAll((0 until times).map { index -> Callable { task(index) } }).forEach { it.get() }
    } finally {
        pool.shutdownNow()
    }
}
