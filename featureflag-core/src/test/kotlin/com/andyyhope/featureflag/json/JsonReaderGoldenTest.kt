package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonReaderGoldenTest {

    @Test
    fun acceptsAndRejectsExactlyWhatAppleDoes() {
        val rows = Fixtures.tsv("json/parse.tsv")
        for ((input, expected) in rows) {
            val parsed = runCatching { JsonReader.parse(input.encodeToByteArray()) }
            if (expected == "reject") {
                assertTrue(parsed.isFailure, "Apple rejects ${input.take(80)}, Kotlin accepted it")
            } else {
                val error = parsed.exceptionOrNull()
                assertTrue(parsed.isSuccess, "Apple accepts ${input.take(80)}, Kotlin rejected it: $error")
            }
        }
    }

    @Test
    fun classifiesNumbersAsAppleDoes() {
        val mismatches = mutableListOf<String>()
        for ((literal, kind, canonical) in Fixtures.tsv("json/number-parse.tsv")) {
            val actual = runCatching {
                (JsonReader.parse("[$literal]".encodeToByteArray()) as JsonValue.Array).items.single()
            }
            val description = when (val value = actual.getOrNull()) {
                null -> "reject\t-"
                is JsonValue.Integer -> "integer\t${value.value}"
                is JsonValue.Real -> "real\t%016x".format(value.value.toRawBits())
                else -> "unexpected $value"
            }
            if (description != "$kind\t$canonical" && literal !in KNOWN_NUMBER_DEVIATIONS) {
                mismatches += "${literal.take(40)}: Apple $kind $canonical, Kotlin $description"
            }
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun knownNumberDeviationsReallyDeviate() {
        for (literal in KNOWN_NUMBER_DEVIATIONS) {
            val row = Fixtures.tsv("json/number-parse.tsv").single { it[0] == literal }
            val actual = runCatching { JsonReader.parse("[$literal]".encodeToByteArray()) }.getOrNull()
            assertTrue(row[1] == "reject" || actual == null, "deviation list is stale for ${literal.take(40)}")
        }
    }

    @Test
    fun utf16IsADocumentedDeviation() {
        // Apple detects UTF-16 by its byte order mark and accepts this; Kotlin reads UTF-8 only.
        assertFailsWith<JsonSyntaxException> {
            JsonReader.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '['.code.toByte(), 0, ']'.code.toByte(), 0))
        }
    }

    private companion object {
        /** Literals Apple routes through NSDecimalNumber, whose limits Kotlin does not copy. */
        val KNOWN_NUMBER_DEVIATIONS = setOf("0." + "0".repeat(400) + "1")
    }
}
