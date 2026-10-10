package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagLookupTest {
    /** Records what it was asked for and answers from [boxes], ignoring the type, as Swift's StubLookup does. */
    private class RecordingLookup(private val boxes: Map<FlagKey, FlagValueBox> = emptyMap()) : FlagLookup {
        val asked = mutableListOf<Pair<FlagKey, FlagValueType>>()
        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? {
            asked += key to type
            return boxes[key]
        }
    }

    private val key = FlagKey("max-items")

    @Test fun readDecodesAStoredBox() {
        assertEquals(3, RecordingLookup(mapOf(key to FlagValueBox.Int(3))).read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readFallsBackToTheDefaultWhenNothingIsStored() {
        assertEquals(10, RecordingLookup().read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readFallsBackToTheDefaultWhenTheStoredValueIsTheWrongType() {
        val lookup = RecordingLookup(mapOf(key to FlagValueBox.String("nonsense")))
        assertEquals(10, lookup.read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readFallsBackToTheDefaultWhenTheStoredValueWillNotDecode() {
        // The right type, but outside what a Kotlin Int holds: a malformed store never crashes the app.
        val lookup = RecordingLookup(mapOf(key to FlagValueBox.Int(Long.MAX_VALUE)))
        assertEquals(10, lookup.read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readAsksForTheCodecsType() {
        val lookup = RecordingLookup()
        lookup.read(key, FlagCodecs.list(FlagCodecs.string)) { emptyList<String>() }
        assertEquals(key, lookup.asked.single().first)
        assertEquals(FlagValueType.Array(FlagValueType.String), lookup.asked.single().second)
    }

    @Test fun theDefaultIsOnlyEvaluatedWhenItIsNeeded() {
        var evaluated = 0
        RecordingLookup(mapOf(key to FlagValueBox.Int(3))).read(key, FlagCodecs.int) {
            evaluated++
            10
        }
        assertEquals(0, evaluated)
    }
}
