package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals

class MessageTextDescriptionTest {

    @Test
    fun doublesReadTheWaySwiftDescribesThem() {
        val cases = listOf(
            3.0 to "3.0",
            0.1 to "0.1",
            -0.0 to "-0.0",
            0.0 to "0.0",
            2.5 to "2.5",
            10.0 to "10.0",
            100.0 to "100.0",
            1234567.0 to "1234567.0",
            12345678.9 to "12345678.9",
            4.35 to "4.35",
            0.30000000000000004 to "0.30000000000000004",
            1.0000000000000002 to "1.0000000000000002",
            1e15 to "1000000000000000.0",
            9007199254740990.0 to "9007199254740990.0",
            9007199254740992.0 to "9007199254740992.0",
            9007199254740994.0 to "9.007199254740994e+15",
            9.5e15 to "9.5e+15",
            1e16 to "1e+16",
            -1e16 to "-1e+16",
            1.8014398509481984e16 to "1.8014398509481984e+16",
            1e19 to "1e+19",
            9.223372036854776e18 to "9.223372036854776e+18",
            1e21 to "1e+21",
            1e22 to "1e+22",
            1e100 to "1e+100",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            0.001 to "0.001",
            0.0001 to "0.0001",
            0.00011 to "0.00011",
            9.999e-5 to "9.999e-05",
            9.999999999999999e-05 to "9.999999999999999e-05",
            5e-5 to "5e-05",
            1e-5 to "1e-05",
            -1.5e-7 to "-1.5e-07",
            2.2250738585072014e-308 to "2.2250738585072014e-308",
            Double.MIN_VALUE to "5e-324",
            Double.POSITIVE_INFINITY to "inf",
            Double.NEGATIVE_INFINITY to "-inf",
            Double.NaN to "nan",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, MessageText.double(value), "String(Double) of ${value.toRawBits()}")
        }
    }

    @Test
    fun floatsReadTheWaySwiftDescribesThem() {
        val cases = listOf(
            0.1f to "0.1",
            1f to "1.0",
            3f to "3.0",
            100f to "100.0",
            12345.678f to "12345.678",
            1.0e7f to "10000000.0",
            16777215f to "16777215.0",
            16777216f to "16777216.0",
            16777218f to "1.6777218e+07",
            -16777218f to "-1.6777218e+07",
            33554432f to "3.3554432e+07",
            1e10f to "1e+10",
            Float.MAX_VALUE to "3.4028235e+38",
            0.0001f to "0.0001",
            9.999e-5f to "9.999e-05",
            1e-5f to "1e-05",
            Float.MIN_VALUE to "1e-45",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, MessageText.float(value), "String(Float) of ${value.toRawBits()}")
        }
    }

    @Test
    fun quotedEscapesAndCapsAtFortyCharacters() {
        assertEquals("\"short\"", MessageText.quoted("short"))
        assertEquals("\"line one\\nline two\"", MessageText.quoted("line one\nline two"))
        assertEquals("\"a\\\\b\\tc\\rd\"", MessageText.quoted("a\\b\tc\rd"))
        assertEquals("\"${"A".repeat(40)}\"", MessageText.quoted("A".repeat(40)))
        assertEquals("\"${"A".repeat(40)}…\"", MessageText.quoted("A".repeat(41)))
        assertEquals("\"${"A".repeat(40)}…\"", MessageText.quoted("A".repeat(200)))
    }

    @Test
    fun quotedCountsAnEscapeAsTwoCharacters() {
        // Swift caps the escaped text, so "\n" spends two of the forty.
        assertEquals("\"${"A".repeat(38)}\\n…\"", MessageText.quoted("A".repeat(38) + "\n" + "B"))
    }

    @Test
    fun shortDescribesEveryBoxOnOneLine() {
        assertEquals("\"hi\"", MessageText.short(FlagValueBox.String("hi")))
        assertEquals("true", MessageText.short(FlagValueBox.Bool(true)))
        assertEquals("10000000000", MessageText.short(FlagValueBox.Int(10_000_000_000)))
        assertEquals("3.0", MessageText.short(FlagValueBox.Double(3.0)))
        assertEquals("0.1", MessageText.short(FlagValueBox.Float(0.1f)))
        assertEquals("\"https://a.example/x\"", MessageText.short(FlagValueBox.Url(URI("https://a.example/x"))))
        assertEquals("2026-01-01T00:00:00Z", MessageText.short(FlagValueBox.Date(Instant.ofEpochSecond(1_767_225_600))))
        assertEquals("5 bytes", MessageText.short(FlagValueBox.Data(byteArrayOf(0, 1, 2, 3, 4))))
        assertEquals("an array of 2", MessageText.short(FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)))))
        assertEquals(
            "an object with 1 field(s)",
            MessageText.short(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1)))),
        )
    }

    @Test
    fun diffFieldIsGenerousAndDoesNotEscape() {
        assertEquals("\"line one\nline two\"", MessageText.diffField(FlagValueBox.String("line one\nline two")))
        assertEquals("\"${"B".repeat(80)}\"", MessageText.diffField(FlagValueBox.String("B".repeat(80))))
        assertEquals("\"${"B".repeat(80)}…\"", MessageText.diffField(FlagValueBox.String("B".repeat(81))))
        assertEquals("\"https://staging.a\"", MessageText.diffField(FlagValueBox.Url(URI("https://staging.a"))))
        assertEquals("10.0", MessageText.diffField(FlagValueBox.Double(10.0)))
        assertEquals("443", MessageText.diffField(FlagValueBox.Int(443)))
        assertEquals("false", MessageText.diffField(FlagValueBox.Bool(false)))
        assertEquals("3 bytes", MessageText.diffField(FlagValueBox.Data(byteArrayOf(1, 2, 3))))
        assertEquals("an array of 0", MessageText.diffField(FlagValueBox.Array(emptyList())))
    }

    @Test
    fun caseListNamesStringsBareAndOtherCasesTheWaySwiftReflectsThem() {
        assertEquals("free, pro", MessageText.caseList(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro"))))
        // Swift renders a non-string case with String(describing:), which is "int(1)".
        assertEquals(
            "int(1), int(2), int(3)",
            MessageText.caseList(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2), FlagValueBox.Int(3))),
        )
    }
}
