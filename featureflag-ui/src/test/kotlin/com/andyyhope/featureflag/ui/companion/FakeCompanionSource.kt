package com.andyyhope.featureflag.ui.companion

import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A bridge source in memory, for the session's tests. Its refresh does what a bridge source's
 * does: on failure it keeps its values, publishes the failure and throws it; on success it clears it.
 */
internal class FakeCompanionSource(
    val schema: FlagSchema,
    values: Map<FlagKey, FlagValueBox> = emptyMap(),
) : CompanionSource {

    private val snapshot = SnapshotSource("fake", values)
    private val failures = MutableStateFlow<FlagBridgeException?>(null)

    override val sourceName: String = "fake"
    override val changes: Flow<FlagChange> = snapshot.changes
    override val lastFailure: StateFlow<FlagBridgeException?> = failures

    /** What the next refreshes throw; null succeeds. */
    @Volatile
    var refreshFailure: FlagBridgeException? = null

    /** The name of the thread each refresh ran on. */
    val refreshThreads = CopyOnWriteArrayList<String>()

    @Volatile
    var closed = false
        private set

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = snapshot.box(key, type)

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        snapshot.setBox(key, box)
    }

    override fun refresh() {
        refreshThreads += Thread.currentThread().name
        val failure = refreshFailure
        failures.value = failure
        if (failure != null) throw failure
    }

    override fun close() {
        closed = true
    }

    /** A change the host makes, as the bridge's observer would deliver it. */
    fun hostWrites(key: String, box: FlagValueBox) {
        snapshot.setBox(FlagKey(key), box)
    }
}
