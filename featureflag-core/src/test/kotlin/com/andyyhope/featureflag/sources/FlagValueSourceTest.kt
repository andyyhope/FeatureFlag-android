package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagValueSourceTest {

    @Test
    fun aWholesaleChangeAffectsEveryKey() {
        assertTrue(FlagChange.All.affects(FlagKey("anything")))
    }

    @Test
    fun aKeyedChangeAffectsOnlyItsKeys() {
        val change = FlagChange.Keys(setOf(FlagKey("a"), FlagKey("b")))

        assertTrue(change.affects(FlagKey("a")))
        assertFalse(change.affects(FlagKey("c")))
    }

    @Test
    fun anEmptyKeyedChangeAffectsNothing() {
        assertFalse(FlagChange.Keys(emptySet()).affects(FlagKey("a")))
    }

    @Test
    fun setBoxesWritesEachEntryThroughSetBoxByDefault() {
        val source = RecordingSource()

        source.setBoxes(linkedMapOf(FlagKey("a") to FlagValueBox.Int(1), FlagKey("b") to null))

        assertEquals(listOf<Pair<FlagKey, FlagValueBox?>>(FlagKey("a") to FlagValueBox.Int(1), FlagKey("b") to null), source.writes)
    }

    private class RecordingSource : MutableFlagValueSource {
        val writes = mutableListOf<Pair<FlagKey, FlagValueBox?>>()
        override val sourceName: String = "recording"
        override val changes: Flow<FlagChange> = emptyFlow()

        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null

        override fun setBox(key: FlagKey, box: FlagValueBox?) {
            writes.add(key to box)
        }
    }
}
