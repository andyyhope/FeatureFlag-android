package com.andyyhope.featureflag.values

import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// Ports the value-text half of Swift's FlagEditorValueTests, plus the refusals the companion adds.
class FlagValueTextTest {

    // Rendering

    @Test
    fun everyTypeRendersAsText() {
        assertEquals("true", FlagValueText.format(FlagValueBox.Bool(true)))
        assertEquals("false", FlagValueText.format(FlagValueBox.Bool(false)))
        assertEquals("-7", FlagValueText.format(FlagValueBox.Int(-7)))
        assertEquals("3.5", FlagValueText.format(FlagValueBox.Double(3.5)))
        assertEquals("2.5", FlagValueText.format(FlagValueBox.Float(2.5f)))
        assertEquals("hi", FlagValueText.format(FlagValueBox.String("hi")))
        assertEquals("AQI=", FlagValueText.format(FlagValueBox.Data(byteArrayOf(1, 2))))
        assertEquals("1970-01-01T00:00:00Z", FlagValueText.format(FlagValueBox.Date(Instant.EPOCH)))
    }

    @Test
    fun collectionsRenderAsJson() {
        assertEquals("[]", FlagValueText.format(FlagValueBox.Array(emptyList())))
        assertEquals("{}", FlagValueText.format(FlagValueBox.Dictionary(emptyMap())))
        assertEquals(
            "{\"a\":1,\"b\":2}",
            FlagValueText.format(FlagValueBox.Dictionary(mapOf("b" to FlagValueBox.Int(2), "a" to FlagValueBox.Int(1)))),
        )
    }

    @Test
    fun urlsRenderWithoutEscapingTheirSlashes() {
        assertEquals("https://a.example/x?y=z", FlagValueText.format(FlagValueBox.Url(URI("https://a.example/x?y=z"))))
        val inAList = FlagValueBox.Array(listOf(FlagValueBox.Url(URI("https://a.example/x"))))
        assertEquals("[\"https://a.example/x\"]", FlagValueText.format(inAList))
    }

    @Test
    fun numbersRenderAsSwiftDescribesThem() {
        assertEquals("1.0", FlagValueText.format(FlagValueBox.Double(1.0)))
        assertEquals("0.1", FlagValueText.format(FlagValueBox.Double(0.1)))
        assertEquals("1e-05", FlagValueText.format(FlagValueBox.Double(0.00001)))
        assertEquals("1e+16", FlagValueText.format(FlagValueBox.Double(1e16)))
        assertEquals("0.1", FlagValueText.format(FlagValueBox.Float(0.1f)))
        // Inside a collection a float is widened, as JSON writes it.
        assertEquals("[0.10000000149011612]", FlagValueText.format(FlagValueBox.Array(listOf(FlagValueBox.Float(0.1f)))))
    }

    @Test
    fun datesRenderToTheSecond() {
        assertEquals("2026-01-01T00:00:00Z", FlagValueText.format(FlagValueBox.Date(Instant.parse("2026-01-01T00:00:00.75Z"))))
    }

    @Test
    fun aCollectionHoldingANonFiniteNumberRendersAsEmptyRatherThanThrowing() {
        assertEquals("", FlagValueText.format(FlagValueBox.Array(listOf(FlagValueBox.Double(Double.NaN)))))
        assertEquals("", FlagValueText.format(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Float(Float.POSITIVE_INFINITY)))))
        // A scalar still says what it holds.
        assertEquals("nan", FlagValueText.format(FlagValueBox.Double(Double.NaN)))
        assertEquals("-inf", FlagValueText.format(FlagValueBox.Double(Double.NEGATIVE_INFINITY)))
    }

    // Parsing

    @Test
    fun booleansAcceptTheUsualSpellings() {
        for (text in listOf("true", "TRUE", "yes", "1")) {
            assertEquals(FlagValueBox.Bool(true), FlagValueText.parse(text, FlagValueType.Bool), text)
        }
        for (text in listOf("false", "FALSE", "no", "0")) {
            assertEquals(FlagValueBox.Bool(false), FlagValueText.parse(text, FlagValueType.Bool), text)
        }
        assertNull(FlagValueText.parse("maybe", FlagValueType.Bool))
        assertNull(FlagValueText.parse(" true", FlagValueType.Bool))
    }

    @Test
    fun numbersRejectAnythingThatIsNotOne() {
        assertEquals(FlagValueBox.Int(-7), FlagValueText.parse("-7", FlagValueType.Int))
        assertEquals(FlagValueBox.Int(5), FlagValueText.parse("+5", FlagValueType.Int))
        assertNull(FlagValueText.parse("3.5", FlagValueType.Int))
        assertNull(FlagValueText.parse(" 3", FlagValueType.Int))
        assertNull(FlagValueText.parse("3 ", FlagValueType.Int))
        assertNull(FlagValueText.parse("3abc", FlagValueType.Int))
        assertNull(FlagValueText.parse("", FlagValueType.Int))
        assertNull(FlagValueText.parse("-", FlagValueType.Int))
        assertNull(FlagValueText.parse("9223372036854775808", FlagValueType.Int))
        assertNull(FlagValueText.parse("٣", FlagValueType.Int)) // ARABIC-INDIC DIGIT THREE
        assertEquals(FlagValueBox.Float(2.5f), FlagValueText.parse("2.5", FlagValueType.Float))
        assertEquals(FlagValueBox.Double(0.5), FlagValueText.parse(".5", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(5.0), FlagValueText.parse("5.", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(100000.0), FlagValueText.parse("1e5", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(-1.0), FlagValueText.parse("-1", FlagValueType.Double))
        // Locale commas, whitespace, Kotlin suffixes and hex floats are not numbers here.
        for (text in listOf("3,5", " 3.5", "3.5 ", "3.5d", "3.5f", "0x1p3", "", ".", "1e", "e5")) {
            assertNull(FlagValueText.parse(text, FlagValueType.Double), text)
            assertNull(FlagValueText.parse(text, FlagValueType.Float), text)
        }
    }

    @Test
    fun nonFiniteNumbersAreRejected() {
        for (text in listOf("nan", "NaN", "inf", "-inf", "infinity", "Infinity", "1e400")) {
            assertNull(FlagValueText.parse(text, FlagValueType.Double), text)
            assertNull(FlagValueText.parse(text, FlagValueType.Float), text)
        }
        // A float that overflows to infinity, though a double holds it.
        assertNull(FlagValueText.parse("1e40", FlagValueType.Float))
        assertEquals(FlagValueBox.Double(1e40), FlagValueText.parse("1e40", FlagValueType.Double))
        // Inside a collection, where JSON reads -1e400 as an infinity and 1e39 narrows to one.
        assertNull(FlagValueText.parse("[-1e400]", FlagValueType.Array(FlagValueType.Double)))
        assertNull(FlagValueText.parse("{\"a\":1e39}", FlagValueType.Dictionary(FlagValueType.Float)))
    }

    @Test
    fun stringsAcceptAnything() {
        assertEquals(FlagValueBox.String(""), FlagValueText.parse("", FlagValueType.String))
        assertEquals(FlagValueBox.String("{not json}"), FlagValueText.parse("{not json}", FlagValueType.String))
        assertEquals(FlagValueBox.String("  spaced  "), FlagValueText.parse("  spaced  ", FlagValueType.String))
    }

    @Test
    fun datesAndDataAndUrlsRoundTripThroughText() {
        val cases = listOf(
            FlagValueBox.Date(Instant.ofEpochSecond(1_000)) to FlagValueType.Date,
            FlagValueBox.Data(byteArrayOf(0x01, 0xFF.toByte())) to FlagValueType.Data,
            FlagValueBox.Url(URI("https://a.example/x")) to FlagValueType.Url,
        )
        for ((box, type) in cases) {
            assertEquals(box, FlagValueText.parse(FlagValueText.format(box), type), "$type")
        }
    }

    @Test
    fun malformedStructuredTextIsRejected() {
        assertNull(FlagValueText.parse("yesterday", FlagValueType.Date))
        assertNull(FlagValueText.parse("!!!", FlagValueType.Data))
        assertNull(FlagValueText.parse("{}", FlagValueType.Array(FlagValueType.String)))
        assertNull(FlagValueText.parse("[1]", FlagValueType.Array(FlagValueType.String)))
        assertNull(FlagValueText.parse("[", FlagValueType.Array(FlagValueType.String)))
        // What the companion adds: fractional seconds, unpadded or URL-safe base64, an empty URL.
        assertNull(FlagValueText.parse("1970-01-01T00:00:00.5Z", FlagValueType.Date))
        assertNull(FlagValueText.parse("1970-01-01", FlagValueType.Date))
        assertNull(FlagValueText.parse(" 1970-01-01T00:00:00Z", FlagValueType.Date))
        assertNull(FlagValueText.parse("AQI", FlagValueType.Data))
        assertNull(FlagValueText.parse("AQ-_", FlagValueType.Data))
        assertNull(FlagValueText.parse("AQI= ", FlagValueType.Data))
        assertNull(FlagValueText.parse("", FlagValueType.Url))
        assertNull(FlagValueText.parse("{\"a\":\"b\"}", FlagValueType.Dictionary(FlagValueType.Int)))
    }

    @Test
    fun datesMayCarryAnOffsetButNothingFiner() {
        assertEquals(
            FlagValueBox.Date(Instant.parse("1970-01-01T00:00:00Z")),
            FlagValueText.parse("1970-01-01T10:00:00+10:00", FlagValueType.Date),
        )
    }

    @Test
    fun emptyTextIsEmptyData() {
        val box = assertIs<FlagValueBox.Data>(FlagValueText.parse("", FlagValueType.Data))
        assertContentEquals(ByteArray(0), box.value)
    }

    @Test
    fun collectionsRoundTripThroughText() {
        val cases = listOf(
            FlagValueBox.Array(listOf(FlagValueBox.String("a"), FlagValueBox.String("b"))) to
                FlagValueType.Array(FlagValueType.String),
            FlagValueBox.Array(emptyList()) to FlagValueType.Array(FlagValueType.Int),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1))) to FlagValueType.Dictionary(FlagValueType.Int),
            FlagValueBox.Dictionary(emptyMap()) to FlagValueType.Dictionary(FlagValueType.Bool),
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Int(1))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Int)),
        )
        for ((box, type) in cases) {
            assertEquals(box, FlagValueText.parse(FlagValueText.format(box), type), "failed for ${type.typeName}")
        }
    }

    // Empty values

    @Test
    fun everyTypeHasAnEmptyValue() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(FlagValueBox.Bool(false), FlagValueText.empty(FlagValueType.Bool, now))
        assertEquals(FlagValueBox.Int(0), FlagValueText.empty(FlagValueType.Int, now))
        assertEquals(FlagValueBox.Double(0.0), FlagValueText.empty(FlagValueType.Double, now))
        assertEquals(FlagValueBox.Float(0f), FlagValueText.empty(FlagValueType.Float, now))
        assertEquals(FlagValueBox.String(""), FlagValueText.empty(FlagValueType.String, now))
        assertEquals(FlagValueBox.Data(ByteArray(0)), FlagValueText.empty(FlagValueType.Data, now))
        assertEquals(FlagValueBox.Date(now), FlagValueText.empty(FlagValueType.Date, now))
        assertEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueText.empty(FlagValueType.Url, now))
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.Int(0))),
            FlagValueText.empty(FlagValueType.Array(FlagValueType.Int), now),
        )
        assertEquals(FlagValueBox.Dictionary(emptyMap()), FlagValueText.empty(FlagValueType.Dictionary(FlagValueType.Int), now))
    }
}
