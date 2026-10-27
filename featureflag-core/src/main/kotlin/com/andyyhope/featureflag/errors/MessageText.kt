package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.json.AppleNumberFormat
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.values.FlagValueBox
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs

/**
 * The value-rendering helpers behind every error message, ported from `ErrorMessages.swift`.
 *
 * Each problem is a bullet, so a raw newline in a value would put the rest of it in column zero
 * where it reads as another bullet. Length is capped for the same reason.
 *
 * Numbers are rendered the way Swift's `description` renders them (`String(Double)`,
 * `String(describing:)`), not with the JSON writer. That is why a double reads `0.1` in a message
 * while the same value is exported as `0.10000000000000001`.
 */
internal object MessageText {

    private const val MAX_QUOTED_LENGTH = 40
    private const val DIFF_QUOTED_LIMIT = 80
    private const val DOUBLE_DIGITS = 17
    private const val FLOAT_DIGITS = 9
    private const val SMALLEST_DECIMAL_EXPONENT = -3

    // Swift switches to exponent form above the largest integer the significand holds exactly.
    private const val DOUBLE_DECIMAL_LIMIT = 9_007_199_254_740_992.0
    private const val FLOAT_DECIMAL_LIMIT = 16_777_216f

    /**
     * A value as one short, single-line, quoted fragment: `\`, newline, carriage return and tab
     * are escaped, and anything over 40 characters is cut to 40 plus `…`.
     *
     * Swift counts grapheme clusters; this counts code points, so a value whose 40th character
     * is part of a combining sequence or an emoji ZWJ sequence is cut at a different place.
     */
    fun quoted(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return quote(escaped, MAX_QUOTED_LENGTH)
    }

    fun arrayOf(count: Int): String = "an array of $count"

    fun objectWith(count: Int): String = "an object with $count field(s)"

    fun date(instant: Instant): String = IsoDate.format(instant)

    /** Swift's `String(Double)`: the shortest digits that read back exactly. */
    fun double(value: Double): String = when {
        value.isNaN() -> "nan"
        value.isInfinite() -> if (value > 0.0) "inf" else "-inf"
        value == 0.0 -> if (1.0 / value < 0.0) "-0.0" else "0.0"
        else -> {
            val magnitude = abs(value)
            val digits = shortest(BigDecimal(magnitude), DOUBLE_DIGITS) { it.toDouble() == magnitude }
            sign(value < 0.0) + render(digits, exponential = magnitude > DOUBLE_DECIMAL_LIMIT)
        }
    }

    /** Swift's `String(Float)`: the same rules, with nine digits and a 2^24 threshold. */
    fun float(value: Float): String = when {
        value.isNaN() -> "nan"
        value.isInfinite() -> if (value > 0f) "inf" else "-inf"
        value == 0f -> if (1f / value < 0f) "-0.0" else "0.0"
        else -> {
            val magnitude = abs(value)
            val digits = shortest(BigDecimal(magnitude.toDouble()), FLOAT_DIGITS) { it.toFloat() == magnitude }
            sign(value < 0f) + render(digits, exponential = magnitude > FLOAT_DECIMAL_LIMIT)
        }
    }

    /** Swift's `FlagValueBox.shortMessageDescription`. */
    fun short(box: FlagValueBox): String = when (box) {
        is FlagValueBox.String -> quoted(box.value)
        is FlagValueBox.Bool -> box.value.toString()
        is FlagValueBox.Int -> box.value.toString()
        is FlagValueBox.Double -> double(box.value)
        is FlagValueBox.Float -> float(box.value)
        is FlagValueBox.Url -> quoted(box.value.toString())
        is FlagValueBox.Date -> date(box.value)
        is FlagValueBox.Data -> "${box.value.size} bytes"
        is FlagValueBox.Array -> arrayOf(box.value.size)
        is FlagValueBox.Dictionary -> objectWith(box.value.size)
    }

    /** Swift's `FlagValueBox.diffFieldDescription`: generous, because a diff exists to show values. */
    fun diffField(box: FlagValueBox): String = when (box) {
        is FlagValueBox.String -> quote(box.value, DIFF_QUOTED_LIMIT)
        is FlagValueBox.Url -> quote(box.value.toString(), DIFF_QUOTED_LIMIT)
        is FlagValueBox.Bool -> box.value.toString()
        is FlagValueBox.Int -> box.value.toString()
        is FlagValueBox.Double -> double(box.value)
        is FlagValueBox.Float -> float(box.value)
        is FlagValueBox.Date -> date(box.value)
        is FlagValueBox.Data -> "${box.value.size} bytes"
        is FlagValueBox.Array, is FlagValueBox.Dictionary -> short(box)
    }

    /**
     * Swift's `caseListDescription`. A string case is written bare. Any other case goes
     * through `String(describing:)`, which for the int cases an enum can carry is `int(1)`.
     */
    fun caseList(cases: List<FlagValueBox>): String = cases.joinToString(", ") { box ->
        when (box) {
            is FlagValueBox.String -> box.value
            is FlagValueBox.Int -> "int(${box.value})"
            // Cases only ever come from string- and int-backed enums.
            else -> short(box)
        }
    }

    /** Caps by code point, so a surrogate pair is never split. Swift counts grapheme clusters. */
    private fun quote(text: String, limit: Int): String {
        if (text.codePointCount(0, text.length) <= limit) return "\"$text\""
        return "\"${text.substring(0, text.offsetByCodePoints(0, limit))}…\""
    }

    private fun shortest(exact: BigDecimal, maxDigits: Int, roundTrips: (BigDecimal) -> Boolean): BigDecimal {
        for (digits in 1 until maxDigits) {
            val nearest = exact.round(MathContext(digits, RoundingMode.HALF_EVEN))
            if (roundTrips(nearest)) return nearest
            // Beside a power of two the rounding interval is lopsided, so the farther
            // neighbour can be the one that reads back.
            val down = exact.round(MathContext(digits, RoundingMode.DOWN))
            val other = if (nearest.compareTo(down) == 0) exact.round(MathContext(digits, RoundingMode.UP)) else down
            if (roundTrips(other)) return other
        }
        return exact.round(MathContext(maxDigits, RoundingMode.HALF_EVEN))
    }

    private fun render(number: BigDecimal, exponential: Boolean): String {
        val stripped = number.stripTrailingZeros()
        val digits = stripped.unscaledValue().toString()
        // The value is 0.<digits> × 10^point.
        val point = digits.length - stripped.scale()
        return if (exponential || point < SMALLEST_DECIMAL_EXPONENT) {
            AppleNumberFormat.exponential(digits, point - 1)
        } else {
            decimalForm(digits, point)
        }
    }

    private fun decimalForm(digits: String, point: Int): String = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length) + ".0"
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }

    private fun sign(negative: Boolean): String = if (negative) "-" else ""
}
