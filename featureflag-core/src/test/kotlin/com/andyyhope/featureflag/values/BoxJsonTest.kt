package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Ported from FlagValueBox+JSON.swift's rules and FlagValueBoxTests' round trips. */
class BoxJsonTest {

    private fun json(text: String): JsonValue =
        (JsonReader.parse("[$text]".encodeToByteArray()) as JsonValue.Array).items.single()

    private fun box(text: String, type: FlagValueType): FlagValueBox? = json(text).toBox(type)

    @Test
    fun everyTypeRoundTripsThroughJson() {
        val cases = listOf(
            FlagValueBox.Bool(true) to FlagValueType.Bool,
            FlagValueBox.Int(-7) to FlagValueType.Int,
            FlagValueBox.Double(3.5) to FlagValueType.Double,
            FlagValueBox.Float(3.5f) to FlagValueType.Float,
            FlagValueBox.String("hello") to FlagValueType.String,
            FlagValueBox.Data(byteArrayOf(1, 2)) to FlagValueType.Data,
            FlagValueBox.Date(Instant.ofEpochSecond(1000)) to FlagValueType.Date,
            FlagValueBox.Url(URI("https://example.com")) to FlagValueType.Url,
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Int(1))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Int)),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Bool(false))) to
                FlagValueType.Dictionary(FlagValueType.Bool),
        )
        for ((box, type) in cases) assertEquals(box, box.toJson().toBox(type), "round trip of $box")
    }

    @Test
    fun valuesBecomeTheJsonAppleWrites() {
        assertEquals(JsonValue.Real(0.10000000149011612), FlagValueBox.Float(0.1f).toJson())
        assertEquals(JsonValue.Text("AAEC/v8="), FlagValueBox.Data(byteArrayOf(0, 1, 2, -2, -1)).toJson())
        val date = FlagValueBox.Date(Instant.ofEpochMilli(1_767_260_460_750))
        assertEquals(JsonValue.Text("2026-01-01T09:41:00Z"), date.toJson())
        val url = FlagValueBox.Url(URI("https://example.com/a?b=c"))
        assertEquals(JsonValue.Text("https://example.com/a?b=c"), url.toJson())
    }

    @Test
    fun nonFiniteNumbersCannotBecomeJson() {
        assertFailsWith<FlagSerializationException.NonFiniteNumber> { FlagValueBox.Double(Double.NaN).toJson() }
        assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            FlagValueBox.Array(listOf(FlagValueBox.Float(Float.POSITIVE_INFINITY))).toJson()
        }
    }

    @Test
    fun booleansAndNumbersAreNeverConfused() {
        assertEquals(FlagValueBox.Bool(true), box("true", FlagValueType.Bool))
        assertNull(box("1", FlagValueType.Bool))
        assertNull(box("true", FlagValueType.Int))
        assertNull(box("true", FlagValueType.Double))
        assertNull(box("\"true\"", FlagValueType.Bool))
    }

    @Test
    fun intsComeOnlyFromIntegralNumbersInRange() {
        assertEquals(FlagValueBox.Int(3), box("3", FlagValueType.Int))
        assertEquals(FlagValueBox.Int(3), box("3.0", FlagValueType.Int))
        assertEquals(FlagValueBox.Int(100), box("1e2", FlagValueType.Int))
        assertNull(box("1.5", FlagValueType.Int))
        assertNull(box("1e19", FlagValueType.Int))
        assertNull(box("\"3\"", FlagValueType.Int))
    }

    @Test
    fun doublesTakeIntegersOnlyWhenADoubleHoldsThemExactly() {
        assertEquals(FlagValueBox.Double(3.0), box("3", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(9007199254740992.0), box("9007199254740992", FlagValueType.Double))
        assertNull(box("9007199254740993", FlagValueType.Double))
        assertNull(box("9223372036854775807", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(-9.223372036854775808E18), box("-9223372036854775808", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(1.0E19), box("10000000000000000000", FlagValueType.Double))
    }

    @Test
    fun floatsNarrowAsSwiftsFloatInitialiserDoes() {
        assertEquals(FlagValueBox.Float(0.1f), box("0.1", FlagValueType.Float))
        assertEquals(FlagValueBox.Float(16777216f), box("16777217", FlagValueType.Float))
        assertEquals(FlagValueBox.Float(Float.POSITIVE_INFINITY), box("1e39", FlagValueType.Float))
    }

    @Test
    fun negativeZeroIsADocumentedDeviation() {
        // `-0.0` reads as Integer(0), so a double flag gets 0.0; Apple keeps -0.0 and writes "-0".
        assertEquals(FlagValueBox.Double(0.0), box("-0.0", FlagValueType.Double))
        assertEquals(FlagValueBox.Int(0), box("-0.0", FlagValueType.Int))
    }

    @Test
    fun stringsDataDatesAndUrlsComeOnlyFromValidText() {
        assertEquals(FlagValueBox.String("a"), box("\"a\"", FlagValueType.String))
        assertNull(box("1", FlagValueType.String))
        assertEquals(FlagValueBox.Data(byteArrayOf(0x61)), box("\"YQ==\"", FlagValueType.Data))
        assertNull(box("\"YQ\"", FlagValueType.Data))
        val newYear = FlagValueBox.Date(Instant.ofEpochSecond(1_767_225_600))
        assertEquals(newYear, box("\"2026-01-01T00:00:00Z\"", FlagValueType.Date))
        assertNull(box("\"2026-01-01\"", FlagValueType.Date))
        assertNull(box("1767225600", FlagValueType.Date))
        assertNull(box("\"\"", FlagValueType.Url))
        val encoded = FlagValueBox.Url(URI("https://example.com/a%20b"))
        assertEquals(encoded, box("\"https://example.com/a b\"", FlagValueType.Url))
    }

    @Test
    fun collectionsAreAllOrNothing() {
        val ints = FlagValueType.Array(FlagValueType.Int)
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))), box("[1,2]", ints))
        assertNull(box("[1,\"2\"]", ints))
        assertNull(box("{\"a\":1}", ints))
        val dictionary = FlagValueType.Dictionary(FlagValueType.Bool)
        assertEquals(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Bool(true))), box("{\"a\":true}", dictionary))
        assertNull(box("{\"a\":true,\"b\":1}", dictionary))
        assertEquals(FlagValueBox.Array(emptyList()), box("[]", FlagValueType.Array(FlagValueType.Url)))
    }

    @Test
    fun storageIdentityComparesValuesAsTheyAreStored() {
        val early = FlagValueBox.Date(Instant.ofEpochMilli(1_000_100))
        val late = FlagValueBox.Date(Instant.ofEpochMilli(1_000_900))
        assertNotEquals<FlagValueBox>(early, late)
        assertEquals(early.storageIdentity, late.storageIdentity)
        assertEquals("\"a\"", FlagValueBox.String("a").storageIdentity)
        assertNotEquals(FlagValueBox.String("1").storageIdentity, FlagValueBox.Int(1).storageIdentity)
        val infinite = FlagValueBox.Float(Float.POSITIVE_INFINITY)
        assertEquals(infinite.toString(), infinite.storageIdentity)
    }
}
