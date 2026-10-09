package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonReaderTest {

    private fun parse(text: String): JsonValue = JsonReader.parse(text.encodeToByteArray())

    private fun number(literal: String): JsonValue = (parse("[$literal]") as JsonValue.Array).items.single()

    @Test
    fun readsEveryKindOfValue() {
        val parsed = parse("""{"a":[true,false,null,1,1.5,"x"],"b":{}}""")
        assertEquals(
            JsonValue.Object(
                mapOf(
                    "a" to JsonValue.Array(
                        listOf(
                            JsonValue.Bool(true), JsonValue.Bool(false), JsonValue.Null, JsonValue.Integer(1),
                            JsonValue.Real(1.5), JsonValue.Text("x"),
                        ),
                    ),
                    "b" to JsonValue.Object(emptyMap()),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun theFirstOfARepeatedKeyWinsAndKeepsItsPlace() {
        val fields = (parse("""{"b":1,"a":2,"b":3}""") as JsonValue.Object).fields
        assertEquals(listOf("b", "a"), fields.keys.toList())
        assertEquals(JsonValue.Integer(1), fields["b"])
    }

    @Test
    fun numbersAreClassifiedAsNSNumberAnswersAsInt() {
        assertEquals(JsonValue.Integer(3), number("3.0"))
        assertEquals(JsonValue.Integer(100), number("1e2"))
        assertEquals(JsonValue.Integer(0), number("-0"))
        assertEquals(JsonValue.Integer(0), number("-0.0"))
        assertEquals(JsonValue.Integer(Long.MAX_VALUE), number("9.2233720368547758E18"))
        assertEquals(JsonValue.Real(1.0E19), number("10000000000000000000"))
        assertEquals(JsonValue.Real(0.1), number("0.1"))
        assertEquals(JsonValue.Real(Double.NEGATIVE_INFINITY), number("-1e400"))
    }

    @Test
    fun appleExtensionsAreAccepted() {
        assertEquals(JsonValue.Array(listOf(JsonValue.Integer(1))), parse("[1,]"))
        assertEquals(JsonValue.Object(mapOf("a" to JsonValue.Integer(1))), parse("{\"a\":1,}"))
        val withByteOrderMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x5B, 0x5D)
        assertEquals(JsonValue.Array(emptyList()), JsonReader.parse(withByteOrderMark))
    }

    @Test
    fun nestingStopsAt513() {
        parse("[".repeat(513) + "]".repeat(513))
        assertFailsWith<JsonSyntaxException> { parse("[".repeat(514) + "]".repeat(514)) }
        assertFailsWith<JsonSyntaxException> { parse("[".repeat(100_000) + "]".repeat(100_000)) }
    }

    @Test
    fun invalidUtf8IsRejected() {
        val documents = listOf(
            byteArrayOf(0x5B, 0x22, 0xFF.toByte(), 0x22, 0x5D),
            byteArrayOf(0x5B, 0x22, 0xC0.toByte(), 0x80.toByte(), 0x22, 0x5D),
            byteArrayOf(0x5B, 0x22, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x22, 0x5D),
            byteArrayOf(0x5B, 0x22, 0xE2.toByte(), 0x82.toByte(), 0x22, 0x5D),
        )
        for (document in documents) assertFailsWith<JsonSyntaxException> { JsonReader.parse(document) }
    }

    @Test
    fun escapesDecodeToTheirCharacters() {
        val text = (parse("""["\/\b\f\n\r\t\"\\Aé😀"]""") as JsonValue.Array).items.single()
        assertEquals(JsonValue.Text("/\b\u000C\n\r\t\"\\Aé😀"), text)
    }
}
