package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import java.time.Instant
import java.util.Locale
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoDateTest {

    @Test
    fun formatsEveryDateAsAppleDoes() {
        val mismatches = Fixtures.tsv("dates/format.tsv").mapNotNull { (epoch, expected) ->
            val seconds = epoch.toDouble()
            val whole = floor(seconds)
            val actual = IsoDate.format(Instant.ofEpochSecond(whole.toLong(), ((seconds - whole) * 1e9).toLong()))
            if (actual == expected) null else "$epoch: Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun parsesEveryDateAsAppleDoesExceptTheLenientForms() {
        val mismatches = Fixtures.tsv("dates/parse.tsv").mapNotNull { (text, expected) ->
            val actual = IsoDate.parse(text)?.epochSecond?.toString() ?: "nil"
            val wanted = if (text in APPLE_ONLY_DATE_FORMS) "nil" else expected
            if (actual == wanted) null else "'$text': Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun everyAppleOnlyFormReallyIsAcceptedByApple() {
        val apple = Fixtures.tsv("dates/parse.tsv").associate { it[0] to it[1] }
        for (form in APPLE_ONLY_DATE_FORMS) {
            assertTrue(apple[form] != null && apple[form] != "nil", "'$form' is not Apple-only")
        }
    }

    @Test
    fun probeFactsHold() {
        assertEquals("1970-01-01T00:00:01Z", IsoDate.format(Instant.ofEpochMilli(1_999)))
        assertEquals("1969-12-31T23:59:59Z", IsoDate.format(Instant.ofEpochMilli(-500)))
        assertEquals("0001-01-01T00:00:00Z", IsoDate.format(Instant.ofEpochSecond(-62_135_769_600)))
        assertEquals("1582-10-04T00:00:00Z", IsoDate.format(Instant.ofEpochSecond(-12_219_379_200)))
        assertEquals("10000-01-01T00:00:00Z", IsoDate.format(Instant.ofEpochSecond(253_402_300_800)))
        assertNull(IsoDate.parse("2026-01-01T00:00:00.123Z"))
        assertNull(IsoDate.parse("2026-01-01T00:00:00"))
        assertNull(IsoDate.parse("2026-01-01 00:00:00Z"))
    }

    @Test
    fun theExtremesClampInsteadOfThrowing() {
        assertEquals("5828963-12-20T00:00:00Z", IsoDate.format(Instant.MAX))
        assertEquals("5838270-09-20T00:00:00Z", IsoDate.format(Instant.MIN))
        assertEquals(183_882_168_921_600, IsoDate.parse(IsoDate.format(Instant.MAX))?.epochSecond)
    }

    @Test
    fun outputIgnoresTheDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-ca-buddhist", "ja-JP-u-ca-japanese")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("2026-01-01T09:41:00Z", IsoDate.format(Instant.ofEpochSecond(1_767_260_460)), tag)
                assertEquals(1_767_260_460, IsoDate.parse("2026-01-01T09:41:00Z")?.epochSecond, tag)
                assertEquals("0.10000000000000001", AppleNumberFormat.format(0.1), tag)
                assertEquals("1e+21", AppleNumberFormat.format(1e21), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    private companion object {
        /** Apple's lenient ISO 8601 forms, which Kotlin's canonical subset refuses. */
        val APPLE_ONLY_DATE_FORMS = setOf(
            "2026-1-1T0:0:0Z", "2026-01-01T00:00:00+10", "2026-01-01T00:00:00+5:30", "2026-01-01T00:00:00GMT",
            "2026-01-01T00:00:00UTC", "2026-01-01T00:00:00 Z", " 2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z ",
            "2026-02-30T00:00:00Z", "2026-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2026-04-31T00:00:00Z",
            "2026-01-01T24:00:00Z", "2026-01-01T00:00:00+99:99", "2026-01-01T00:00:00+24:00",
            "2026-01-01T00:00:00+00:60", "2026-01-01T00:00:00+1", "2026-01-01T00:00:00+100",
            "2026-01-01T00:00:00+10000", "2026-01-01T00:00:00+10:00:00", "2026-01-01T00:00:00 +10:00",
            "-0001-01-01T00:00:00Z", "２０２６-01-01T00:00:00Z", "1000000000-12-31T23:59:59Z",
            "2026-01-01T00:00:00+10:0", "2026-01-01T00:00:00Z\n", "2026-01-01T00:00:00ZZ", "2026/01/01T00:00:00Z",
            "999-01-01T00:00:00Z", "2026-01-01T00:00:00+10:",
        )
    }
}
