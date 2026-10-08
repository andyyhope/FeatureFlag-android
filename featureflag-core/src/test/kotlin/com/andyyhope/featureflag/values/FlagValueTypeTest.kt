package com.andyyhope.featureflag.values

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from FlagValueTypeTests (names and parsing). */
class FlagValueTypeTest {

    @Test
    fun everyPrimitiveHasAName() {
        val expected = listOf(
            FlagValueType.Bool to "bool", FlagValueType.Int to "int", FlagValueType.Double to "double",
            FlagValueType.Float to "float", FlagValueType.String to "string", FlagValueType.Data to "data",
            FlagValueType.Date to "date", FlagValueType.Url to "url",
        )
        for ((type, name) in expected) assertEquals(name, type.typeName)
    }

    @Test
    fun collectionNamesCarryAndNestTheirElementType() {
        assertEquals("array<string>", FlagValueType.Array(FlagValueType.String).typeName)
        assertEquals("dictionary<int>", FlagValueType.Dictionary(FlagValueType.Int).typeName)
        assertEquals(
            "array<dictionary<array<bool>>>",
            FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Array(FlagValueType.Bool))).typeName,
        )
    }

    @Test
    fun everyNameRoundTrips() {
        val types = listOf(
            FlagValueType.Bool, FlagValueType.Int, FlagValueType.Double, FlagValueType.Float, FlagValueType.String,
            FlagValueType.Data, FlagValueType.Date, FlagValueType.Url,
            FlagValueType.Array(FlagValueType.String), FlagValueType.Dictionary(FlagValueType.Int),
            FlagValueType.Array(FlagValueType.Array(FlagValueType.Bool)),
            FlagValueType.Dictionary(FlagValueType.Dictionary(FlagValueType.Date)),
            FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Array(FlagValueType.Url))),
        )
        for (type in types) assertEquals(type, FlagValueType.parse(type.typeName), "failed for $type")
    }

    @Test
    fun unknownAndMalformedNamesAreRejected() {
        val names = listOf(
            "", "banana", "Bool", "INT", "array", "dictionary",
            "array<>", "array<banana>", "array<int", "array int>", "dictionary<>", "dictionary<banana>", "array<<int>>",
        )
        for (name in names) assertNull(FlagValueType.parse(name), "should reject $name")
    }
}
