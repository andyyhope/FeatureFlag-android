package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.StrictBase64
import java.net.URI
import java.time.Instant

/**
 * A box as the text an editor shows and reads back, as Swift's `displayString` and
 * `FlagValueBox(displayString:as:)` do.
 *
 * Not JSON for scalars: a string is shown unquoted, and a number in Swift's `description`
 * form. Collections are compact JSON, because there is no better single line for them.
 * Reading never guesses: text that is not exactly a value of the type is refused.
 */
public object FlagValueText {

    private val INTEGER = Regex("[+-]?[0-9]+")
    private val DECIMAL = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")
    private val TRUE_WORDS = setOf("true", "yes", "1")
    private val FALSE_WORDS = setOf("false", "no", "0")
    private const val PLACEHOLDER_URL = "https://example.com"

    /**
     * The text an editor shows: `true`, `-7`, `3.5`, `1e-05`, the string itself, padded base64,
     * `1970-01-01T00:00:00Z`, the URL's text, or compact JSON with sorted keys and unescaped
     * slashes for a collection. A collection holding a non-finite number, which JSON cannot
     * carry, is shown as the empty string instead of throwing.
     */
    public fun format(box: FlagValueBox): String = when (box) {
        is FlagValueBox.Bool -> box.value.toString()
        is FlagValueBox.Int -> box.value.toString()
        is FlagValueBox.Double -> MessageText.double(box.value)
        is FlagValueBox.Float -> MessageText.float(box.value)
        is FlagValueBox.String -> box.value
        is FlagValueBox.Data -> StrictBase64.encode(box.value)
        is FlagValueBox.Date -> IsoDate.format(box.value)
        is FlagValueBox.Url -> box.value.toString()
        is FlagValueBox.Array, is FlagValueBox.Dictionary ->
            if (box.containsNonFiniteNumber) "" else FlagValueJson.encode(box)
    }

    /**
     * The box [text] holds as [type], or null to refuse it. Nothing is trimmed.
     *
     * - Bool: `true`, `yes`, `1`, `false`, `no` or `0`, in any case.
     * - Int: an optional sign and ASCII digits, within `Long`.
     * - Double and Float: decimal digits with an optional fraction and exponent, finite once
     *   read. `nan`, `inf`, hex floats, a `d` or `f` suffix and locale commas are refused, and so
     *   is a float that overflows to infinity.
     * - String: anything.
     * - Data: padded standard base64, as an import reads it; the empty string is empty data.
     * - Date and Url: exactly as an import reads them (second-precision ISO 8601, and
     *   `URL(string:)`'s rules), so no fractional seconds and no empty URL.
     * - Array and Dictionary: JSON of that shape, holding only finite numbers.
     */
    public fun parse(text: String, type: FlagValueType): FlagValueBox? = when (type) {
        FlagValueType.Bool -> bool(text)
        FlagValueType.Int -> text.takeIf(INTEGER::matches)?.toLongOrNull()?.let { FlagValueBox.Int(it) }
        FlagValueType.Double ->
            text.takeIf(DECIMAL::matches)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { FlagValueBox.Double(it) }
        FlagValueType.Float ->
            text.takeIf(DECIMAL::matches)?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { FlagValueBox.Float(it) }
        FlagValueType.String -> FlagValueBox.String(text)
        FlagValueType.Data -> StrictBase64.decode(text)?.let { FlagValueBox.Data(it) }
        FlagValueType.Date, FlagValueType.Url -> JsonValue.Text(text).toBox(type)
        is FlagValueType.Array, is FlagValueType.Dictionary ->
            FlagValueJson.decode(text, type)?.takeUnless { it.containsNonFiniteNumber }
    }

    /**
     * What a new list element starts as: `false`, `0`, `0.0`, `""`, empty data, [now],
     * `https://example.com`, a one-element array of the element's own empty value, or `{}`.
     */
    public fun empty(type: FlagValueType, now: Instant = Instant.now()): FlagValueBox = when (type) {
        FlagValueType.Bool -> FlagValueBox.Bool(false)
        FlagValueType.Int -> FlagValueBox.Int(0)
        FlagValueType.Double -> FlagValueBox.Double(0.0)
        FlagValueType.Float -> FlagValueBox.Float(0f)
        FlagValueType.String -> FlagValueBox.String("")
        FlagValueType.Data -> FlagValueBox.Data(ByteArray(0))
        FlagValueType.Date -> FlagValueBox.Date(now)
        FlagValueType.Url -> FlagValueBox.Url(URI(PLACEHOLDER_URL))
        is FlagValueType.Array -> FlagValueBox.Array(listOf(empty(type.element, now)))
        is FlagValueType.Dictionary -> FlagValueBox.Dictionary(emptyMap())
    }

    private fun bool(text: String): FlagValueBox.Bool? = when (text.lowercase()) {
        in TRUE_WORDS -> FlagValueBox.Bool(true)
        in FALSE_WORDS -> FlagValueBox.Bool(false)
        else -> null
    }
}
