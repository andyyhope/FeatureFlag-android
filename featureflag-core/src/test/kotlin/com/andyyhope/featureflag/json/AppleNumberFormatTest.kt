package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleNumberFormatTest {

    @Test
    fun matchesEveryAppleGolden() {
        val mismatches = Fixtures.tsv("json/numbers.tsv").mapNotNull { (bits, expected) ->
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16))
            val actual = AppleNumberFormat.format(value)
            if (actual == expected) null else "$bits: Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun gRoundsTheExactBinaryValueToTheGivenSignificantDigits() {
        assertEquals("0.10000000000000001", AppleNumberFormat.g(0.1, 17))
        assertEquals("0.1", AppleNumberFormat.g(0.1, 16))
        assertEquals("1.5e+300", AppleNumberFormat.g(1.5e300, 16))
        assertEquals("1.234567890123457e+17", AppleNumberFormat.g(123456789012345680.0, 16))
        assertEquals("-1.5e-07", AppleNumberFormat.g(-1.5e-7, 16))
        assertEquals("3", AppleNumberFormat.g(3.0, 16))
    }

    @Test
    fun exponentialWritesTheMantissaAndASignedTwoDigitExponent() {
        assertEquals("1.5e+300", AppleNumberFormat.exponential("15", 300))
        assertEquals("1e-07", AppleNumberFormat.exponential("1", -7))
        assertEquals("1e+21", AppleNumberFormat.exponential("1", 21))
    }

    @Test
    fun probeFactsHold() {
        assertEquals("3", AppleNumberFormat.format(3.0))
        assertEquals("0.10000000000000001", AppleNumberFormat.format(0.1))
        assertEquals("-0", AppleNumberFormat.format(-0.0))
        assertEquals("10000000000000000", AppleNumberFormat.format(1e16))
        assertEquals("1.0000000000000001e-05", AppleNumberFormat.format(1e-5))
        assertEquals("1e+21", AppleNumberFormat.format(1e21))
        assertEquals("4.9406564584124654e-324", AppleNumberFormat.format(Double.MIN_VALUE))
        assertEquals("0.10000000149011612", AppleNumberFormat.format(0.1f.toDouble()))
    }

    @Test
    fun refusesNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> { AppleNumberFormat.format(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { AppleNumberFormat.format(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun aMillionRandomDoublesReadBackExactly() {
        val random = Random(20261008)
        repeat(1_000_000) {
            val value = Double.fromBits(random.nextLong())
            if (value.isFinite()) {
                val text = AppleNumberFormat.format(value)
                assertEquals(value.toRawBits(), text.toDouble().toRawBits(), "$value was written as $text")
            }
        }
    }
}
