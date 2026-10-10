package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.test.assertEquals

class JsonWriterGoldenTest {

    @Test
    fun sortsKeysAsAppleDoes() {
        val keys = Fixtures.lines("json/key-order.keys.txt")
        val expected = Fixtures.text("json/key-order.json")
        val actual = JsonWriter.compact(JsonValue.Object(keys.associateWith { JsonValue.Integer(0) }))
        if (actual != expected) {
            val appleOrder = (JsonReader.parse(expected.encodeToByteArray()) as JsonValue.Object).fields.keys.toList()
            val kotlinOrder = keys.sortedWith(AppleKeyOrder)
            val firstDifference = appleOrder.indices.first { appleOrder[it] != kotlinOrder[it] }
            assertEquals(
                appleOrder.subList(firstDifference, minOf(firstDifference + 5, appleOrder.size)),
                kotlinOrder.subList(firstDifference, minOf(firstDifference + 5, kotlinOrder.size)),
                "first difference at index $firstDifference",
            )
        }
        assertEquals(expected, actual)
    }

    @Test
    fun escapesStringsAsAppleDoes() {
        val strings = Fixtures.lines("json/strings.codepoints.txt").map { line ->
            if (line.isEmpty()) "" else line.split(" ").joinToString("") { String(Character.toChars(it.toInt(16))) }
        }
        val actual = JsonWriter.compact(JsonValue.Array(strings.map { JsonValue.Text(it) }))
        assertEquals(Fixtures.text("json/strings.json"), actual)
    }

    @Test
    fun prettyPrintsAsAppleDoes() {
        assertEquals(Fixtures.text("json/pretty.json"), JsonWriter.pretty(prettyStructure()))
    }

    @Test
    fun reserialisesEveryAcceptedDocumentAsAppleDoes() {
        val mismatches = Fixtures.tsv("json/parse.tsv").filter { it[1] != "reject" }.mapNotNull { (input, expected) ->
            val actual = JsonWriter.compact(JsonReader.parse(input.encodeToByteArray()))
            if (actual == expected) null else "${input.take(60)}: Apple ${expected.take(60)}, Kotlin ${actual.take(60)}"
        }
        assertEquals(emptyList(), mismatches)
    }

    /** Built identically by `JsonFixtures.prettyStructure` in the Swift generator. */
    private fun prettyStructure(): JsonValue {
        val emptyArray = JsonValue.Array(emptyList())
        val emptyObject = JsonValue.Object(emptyMap())
        return JsonValue.Object(
            linkedMapOf(
                "array" to JsonValue.Array(
                    listOf(
                        JsonValue.Integer(1), JsonValue.Real(2.5), JsonValue.Text("three"), JsonValue.Bool(true),
                        JsonValue.Bool(false), JsonValue.Null, emptyArray, emptyObject,
                        JsonValue.Array(listOf(emptyArray)), JsonValue.Array(listOf(emptyObject)),
                    ),
                ),
                "emptyArray" to emptyArray,
                "emptyObject" to emptyObject,
                "nested" to JsonValue.Object(
                    linkedMapOf(
                        "deeper" to JsonValue.Object(
                            linkedMapOf("deepest" to emptyArray, "value" to JsonValue.Real(-0.0)),
                        ),
                        "list" to JsonValue.Array(
                            listOf(JsonValue.Object(linkedMapOf("a" to JsonValue.Integer(1))), emptyObject),
                        ),
                    ),
                ),
                "numbers" to JsonValue.Array(
                    listOf(
                        JsonValue.Integer(0), JsonValue.Integer(-1), JsonValue.Real(1e21), JsonValue.Real(0.1),
                        JsonValue.Real(Double.MIN_VALUE), JsonValue.Integer(Long.MAX_VALUE),
                        JsonValue.Integer(Long.MIN_VALUE),
                        JsonValue.Real(3.0),
                    ),
                ),
                "strings" to JsonValue.Array(
                    listOf("", "/", "\"\\", "\u0001\t\n", "é😀").map { JsonValue.Text(it) },
                ),
                "Z" to JsonValue.Text("capital"),
                "z" to JsonValue.Null,
                "item10" to JsonValue.Integer(10),
                "item9" to JsonValue.Integer(9),
            ),
        )
    }
}
