package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.test.assertEquals

class JsonWriterTest {

    private val emptyArray = JsonValue.Array(emptyList())
    private val emptyObject = JsonValue.Object(emptyMap())

    /** U+2028, which Apple writes raw. */
    private val lineSeparator = Char(0x2028)

    @Test
    fun emptyContainersFollowApplesPrettyLayout() {
        assertEquals("[\n\n]", JsonWriter.pretty(emptyArray))
        assertEquals("{\n  \"a\" : [\n\n  ]\n}", JsonWriter.pretty(JsonValue.Object(mapOf("a" to emptyArray))))
        val unsorted = JsonValue.Object(mapOf("b" to emptyObject, "a" to emptyArray))
        assertEquals("{\"a\":[],\"b\":{}}", JsonWriter.compact(unsorted))
    }

    @Test
    fun stringsEscapeOnlyWhatAppleEscapes() {
        val text = JsonValue.Text("\"\\/\b\t\n\u000C\r\u000B\u001F\u007F${lineSeparator}é")
        val expected = "[\"\\\"\\\\/\\b\\t\\n\\f\\r\\u000b\\u001f\u007F${lineSeparator}é\"]"
        assertEquals(expected, JsonWriter.compact(JsonValue.Array(listOf(text))))
    }

    @Test
    fun numbersAreWrittenAsAppleWritesThem() {
        val numbers = JsonValue.Array(
            listOf(JsonValue.Integer(-7), JsonValue.Real(3.0), JsonValue.Real(0.1), JsonValue.Real(-0.0)),
        )
        assertEquals("[-7,3,0.10000000000000001,-0]", JsonWriter.compact(numbers))
    }
}
