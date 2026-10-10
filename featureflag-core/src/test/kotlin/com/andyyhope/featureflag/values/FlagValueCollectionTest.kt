package com.andyyhope.featureflag.values

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from FlagValueCollectionTests. Every Kotlin enum publishes its cases (a documented difference). */
class FlagValueCollectionTest {

    private enum class Tier(override val flagRawValue: String) : FlagStringEnum { FREE("free"), PRO("pro") }

    private enum class Level(override val flagRawValue: Long) : FlagIntEnum { LOW(1), HIGH(2) }

    private enum class Plain { ALPHA, BETA }

    private val tier = FlagCodecs.stringEnum(Tier.entries)
    private val level = FlagCodecs.intEnum(Level.entries)

    // Arrays

    @Test
    fun arrayBoxesEachElementAndRoundTrips() {
        val bools = FlagCodecs.list(FlagCodecs.boolean)
        val expected = FlagValueBox.Array(listOf(FlagValueBox.Bool(true), FlagValueBox.Bool(false)))
        assertEquals(expected, bools.encode(listOf(true, false)))
        val strings = FlagCodecs.list(FlagCodecs.string)
        assertEquals(listOf("a", "b", "c"), strings.decode(strings.encode(listOf("a", "b", "c"))))
        val ints = FlagCodecs.list(FlagCodecs.int)
        assertEquals(emptyList(), ints.decode(ints.encode(emptyList())))
        val nested = FlagCodecs.list(FlagCodecs.list(FlagCodecs.int))
        assertEquals(listOf(listOf(1, 2), listOf(3)), nested.decode(nested.encode(listOf(listOf(1, 2), listOf(3)))))
    }

    @Test
    fun arrayRejectsAnElementOfTheWrongTypeAndReportsItsElementType() {
        val mixed = FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.String("2")))
        assertNull(FlagCodecs.list(FlagCodecs.int).decode(mixed))
        assertEquals(FlagValueType.Array(FlagValueType.Bool), FlagCodecs.list(FlagCodecs.boolean).type)
        assertNull(FlagCodecs.list(tier).cases)
    }

    // Dictionaries

    @Test
    fun dictionaryBoxesEachValueAndRoundTrips() {
        val ints = FlagCodecs.map(FlagCodecs.int)
        assertEquals(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1))), ints.encode(mapOf("a" to 1)))
        assertEquals(mapOf("a" to 1, "b" to 2), ints.decode(ints.encode(mapOf("a" to 1, "b" to 2))))
    }

    @Test
    fun dictionaryRejectsAValueOfTheWrongTypeAndReportsItsValueType() {
        val mixed = FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1), "b" to FlagValueBox.Bool(true)))
        assertNull(FlagCodecs.map(FlagCodecs.int).decode(mixed))
        assertEquals(FlagValueType.Dictionary(FlagValueType.Double), FlagCodecs.map(FlagCodecs.double).type)
    }

    // Enums

    @Test
    fun stringBackedEnumBoxesAsItsRawValueAndRoundTrips() {
        assertEquals(FlagValueBox.String("pro"), tier.encode(Tier.PRO))
        assertEquals(Tier.PRO, tier.decode(tier.encode(Tier.PRO)))
        assertEquals(FlagValueType.String, tier.type)
    }

    @Test
    fun intBackedEnumBoxesAsItsRawValueAndRoundTrips() {
        assertEquals(FlagValueBox.Int(2), level.encode(Level.HIGH))
        assertEquals(Level.HIGH, level.decode(level.encode(Level.HIGH)))
        assertEquals(FlagValueType.Int, level.type)
    }

    @Test
    fun enumByNameUsesTheConstantName() {
        val plain = FlagCodecs.enumByName(Plain.entries)
        assertEquals(FlagValueBox.String("BETA"), plain.encode(Plain.BETA))
        assertEquals(Plain.ALPHA, plain.decode(FlagValueBox.String("ALPHA")))
        assertEquals(listOf(FlagValueBox.String("ALPHA"), FlagValueBox.String("BETA")), plain.cases)
    }

    @Test
    fun enumRejectsAnUnknownRawValueOrAMismatchedBox() {
        assertNull(tier.decode(FlagValueBox.String("enterprise")))
        assertNull(tier.decode(FlagValueBox.Int(1)))
        assertNull(level.decode(FlagValueBox.Int(3)))
        assertNull(level.decode(FlagValueBox.String("1")))
    }

    @Test
    fun everyEnumSurfacesItsCasesForEditors() {
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), tier.cases)
        assertEquals(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)), level.cases)
    }
}
