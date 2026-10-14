package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlagValueJsonTest {

    // Encoding

    @Test
    fun encodingWritesCompactJsonValueText() {
        assertEquals("true", FlagValueJson.encode(FlagValueBox.Bool(true)))
        assertEquals("3", FlagValueJson.encode(FlagValueBox.Int(3)))
        assertEquals("0.10000000000000001", FlagValueJson.encode(FlagValueBox.Double(0.1)))
        assertEquals("1.1000000238418579", FlagValueJson.encode(FlagValueBox.Float(1.1f)))
        assertEquals("\"a \\\"b\\\" / c\"", FlagValueJson.encode(FlagValueBox.String("a \"b\" / c")))
        assertEquals("\"AQI=\"", FlagValueJson.encode(FlagValueBox.Data(byteArrayOf(1, 2))))
        assertEquals(
            "\"2026-01-01T00:00:00Z\"",
            FlagValueJson.encode(FlagValueBox.Date(Instant.parse("2026-01-01T00:00:00.75Z"))),
        )
        assertEquals("\"https://example.com/a?b=c\"", FlagValueJson.encode(FlagValueBox.Url(URI("https://example.com/a?b=c"))))
        assertEquals("[1,2]", FlagValueJson.encode(FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)))))
        assertEquals(
            "{\"a\":\"b\",\"b\":[]}",
            FlagValueJson.encode(
                FlagValueBox.Dictionary(mapOf("b" to FlagValueBox.Array(emptyList()), "a" to FlagValueBox.String("b"))),
            ),
        )
    }

    @Test
    fun aNonFiniteNumberCannotBeEncoded() {
        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            FlagValueJson.encode(FlagValueBox.Array(listOf(FlagValueBox.Double(Double.NaN))))
        }

        assertNull(error.key)
    }

    // Decoding

    @Test
    fun everyTypeRoundTrips() {
        val cases = listOf(
            FlagValueBox.Bool(true) to FlagValueType.Bool,
            FlagValueBox.Int(-7) to FlagValueType.Int,
            FlagValueBox.Double(0.1) to FlagValueType.Double,
            FlagValueBox.Float(1.1f) to FlagValueType.Float,
            FlagValueBox.String("line1\nline2 é 😀") to FlagValueType.String,
            FlagValueBox.Data(byteArrayOf(0x01, 0x02)) to FlagValueType.Data,
            FlagValueBox.Date(Instant.ofEpochSecond(1_000)) to FlagValueType.Date,
            FlagValueBox.Url(URI("https://example.com/a?b=c")) to FlagValueType.Url,
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Bool(true))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Bool)),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.String("x"))) to FlagValueType.Dictionary(FlagValueType.String),
            FlagValueBox.Array(emptyList()) to FlagValueType.Array(FlagValueType.String),
            FlagValueBox.Dictionary(emptyMap()) to FlagValueType.Dictionary(FlagValueType.Int),
        )

        for ((box, type) in cases) {
            assertEquals(box, FlagValueJson.decode(FlagValueJson.encode(box), type), "round trip of $box as $type")
        }
    }

    @Test
    fun aScalarMayBeTheWholeText() {
        assertEquals(FlagValueBox.Int(3), FlagValueJson.decode("3", FlagValueType.Int))
        assertEquals(FlagValueBox.Bool(false), FlagValueJson.decode(" \nfalse\t", FlagValueType.Bool))
        assertEquals(FlagValueBox.String("x"), FlagValueJson.decode("\"x\"", FlagValueType.String))
    }

    @Test
    fun anIntegerWidensToADoubleButNothingElseConverts() {
        assertEquals(FlagValueBox.Double(3.0), FlagValueJson.decode("3", FlagValueType.Double))
        assertNull(FlagValueJson.decode("1.5", FlagValueType.Int))
        assertNull(FlagValueJson.decode("1", FlagValueType.Bool))
        assertNull(FlagValueJson.decode("true", FlagValueType.Int))
        assertNull(FlagValueJson.decode("\"3\"", FlagValueType.Int))
    }

    @Test
    fun textOfAnotherTypeDecodesToNothing() {
        assertNull(FlagValueJson.decode("\"not a date\"", FlagValueType.Date))
        assertNull(FlagValueJson.decode("\"not base64!\"", FlagValueType.Data))
        assertNull(FlagValueJson.decode("\"\"", FlagValueType.Url))
        assertNull(FlagValueJson.decode("{\"a\":1}", FlagValueType.Array(FlagValueType.Int)))
    }

    @Test
    fun aPartlyWrongCollectionIsRejectedWhole() {
        assertNull(FlagValueJson.decode("[1,\"two\"]", FlagValueType.Array(FlagValueType.Int)))
        assertNull(FlagValueJson.decode("{\"a\":1,\"b\":null}", FlagValueType.Dictionary(FlagValueType.Int)))
    }

    @Test
    fun textThatIsNotJsonDecodesToNothing() {
        for (text in listOf("", " ", "tru", "[1,", "1 2", "'x'", "x", "\"unterminated", "1e400")) {
            assertNull(FlagValueJson.decode(text, FlagValueType.Int), "decoding <$text>")
        }
    }
}
