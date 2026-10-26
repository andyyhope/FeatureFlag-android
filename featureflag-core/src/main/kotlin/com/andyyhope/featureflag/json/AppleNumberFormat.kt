package com.andyyhope.featureflag.json

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Formats a double the way `JSONSerialization` writes one: C's `printf("%.17g")` of the exact
 * binary value, with trailing zeros (and a trailing `.`) removed.
 *
 * `3.0` → `3`, `0.1` → `0.10000000000000001`, `-0.0` → `-0`, `1e21` → `1e+21`,
 * `1e-5` → `1.0000000000000001e-05`. Java's own `%.17g` is not used: it rounds a decimal
 * approximation rather than the exact binary value, and gets `0.1` wrong.
 */
internal object AppleNumberFormat {

    private const val LOWEST_FIXED_EXPONENT = -4
    private const val JSON_DIGITS = 17

    fun format(value: Double): String {
        require(value.isFinite()) { "JSON cannot represent $value" }
        return g(value, JSON_DIGITS)
    }

    /**
     * C's `%.<significantDigits>g` of the exact binary value of a finite [value], with trailing
     * zeros (and a trailing `.`) removed.
     */
    fun g(value: Double, significantDigits: Int): String {
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val rounded = BigDecimal(value).round(MathContext(significantDigits, RoundingMode.HALF_EVEN))
        val exponent = rounded.precision() - rounded.scale() - 1
        return if (exponent < LOWEST_FIXED_EXPONENT || exponent >= significantDigits) {
            val sign = if (rounded.signum() < 0) "-" else ""
            sign + exponential(rounded.unscaledValue().abs().toString().trimEnd('0'), exponent)
        } else {
            stripTrailingZeros(rounded.toPlainString())
        }
    }

    /** `d.ddd` + `e` + sign + at least two exponent digits, as C writes an exponent. Unsigned. */
    fun exponential(digits: String, exponent: Int): String {
        val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
        val exponentSign = if (exponent < 0) '-' else '+'
        return "${mantissa}e$exponentSign${abs(exponent).toString().padStart(2, '0')}"
    }

    private fun stripTrailingZeros(plain: String): String =
        if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
}
