package com.andyyhope.featureflag.values

import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Ported from FlagValueTypeTests (matching) and FlagRecordTests (non-finite detection). */
class FlagValueBoxTest {

    @Test
    fun aBoxMatchesItsOwnTypeAndNoOther() {
        assertTrue(FlagValueBox.Bool(true).matches(FlagValueType.Bool))
        assertTrue(FlagValueBox.Int(1).matches(FlagValueType.Int))
        assertTrue(FlagValueBox.Url(URI("https://a.example")).matches(FlagValueType.Url))
        assertFalse(FlagValueBox.Int(1).matches(FlagValueType.Bool))
        assertFalse(FlagValueBox.Double(1.0).matches(FlagValueType.Int))
        assertFalse(FlagValueBox.String("a").matches(FlagValueType.Url))
    }

    @Test
    fun anEmptyCollectionMatchesAnyCollectionOfThatShape() {
        assertTrue(FlagValueBox.Array(emptyList()).matches(FlagValueType.Array(FlagValueType.Bool)))
        val dictionaries = FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Int))
        assertTrue(FlagValueBox.Array(emptyList()).matches(dictionaries))
        assertTrue(FlagValueBox.Dictionary(emptyMap()).matches(FlagValueType.Dictionary(FlagValueType.String)))
    }

    @Test
    fun aCollectionDoesNotMatchAScalarOrTheOtherShape() {
        val bools = FlagValueBox.Array(listOf(FlagValueBox.Bool(true)))
        assertFalse(bools.matches(FlagValueType.Bool))
        assertFalse(FlagValueBox.Bool(true).matches(FlagValueType.Array(FlagValueType.Bool)))
        assertFalse(bools.matches(FlagValueType.Dictionary(FlagValueType.Bool)))
    }

    @Test
    fun oneWrongElementFailsTheWholeCollectionAllTheWayDown() {
        val mixed = FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.String("a")))
        assertFalse(mixed.matches(FlagValueType.Array(FlagValueType.Int)))
        assertFalse(
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1), "b" to FlagValueBox.Bool(true)))
                .matches(FlagValueType.Dictionary(FlagValueType.Int)),
        )
        val nestedInts = FlagValueType.Array(FlagValueType.Array(FlagValueType.Int))
        assertTrue(FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Int(1))))).matches(nestedInts))
        val nestedStrings = FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.String("a")))))
        assertFalse(nestedStrings.matches(nestedInts))
    }

    @Test
    fun nonFiniteNumbersAreFoundAnywhere() {
        assertTrue(FlagValueBox.Double(Double.POSITIVE_INFINITY).containsNonFiniteNumber)
        assertTrue(FlagValueBox.Float(Float.NaN).containsNonFiniteNumber)
        assertTrue(FlagValueBox.Array(listOf(FlagValueBox.Double(Double.NaN))).containsNonFiniteNumber)
        val dictionary = FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Float(Float.NEGATIVE_INFINITY)))
        assertTrue(dictionary.containsNonFiniteNumber)
        assertFalse(FlagValueBox.Double(0.0).containsNonFiniteNumber)
        assertFalse(FlagValueBox.Float(-1.5f).containsNonFiniteNumber)
        assertFalse(FlagValueBox.Int(Long.MAX_VALUE).containsNonFiniteNumber)
    }

    @Test
    fun dataAndUrlCompareByContent() {
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), FlagValueBox.Data(byteArrayOf(1, 2)))
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)).hashCode(), FlagValueBox.Data(byteArrayOf(1, 2)).hashCode())
        assertNotEquals(FlagValueBox.Data(byteArrayOf(1, 2)), FlagValueBox.Data(byteArrayOf(2, 1)))
        assertEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueBox.Url(URI("https://example.com")))
        assertNotEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueBox.Url(URI("https://example.com/")))
        assertEquals(FlagValueBox.Date(Instant.ofEpochSecond(1000)), FlagValueBox.Date(Instant.ofEpochSecond(1000)))
    }

    @Test
    fun doublesCompareAsKotlinDataClassesDo() {
        // Documented difference: Swift's FlagValueBox uses IEEE ==, so there 0.0 == -0.0 and NaN != NaN.
        assertNotEquals(FlagValueBox.Double(0.0), FlagValueBox.Double(-0.0))
        assertEquals(FlagValueBox.Double(Double.NaN), FlagValueBox.Double(Double.NaN))
    }
}
