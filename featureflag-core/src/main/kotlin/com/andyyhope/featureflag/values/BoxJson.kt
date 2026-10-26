package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.json.StrictBase64
import java.net.URI
import java.net.URISyntaxException

/**
 * The JSON representation of this value, as `FlagValueBox.jsonValue` writes it.
 *
 * JSON has no date, data or URL, and the schema already records each flag's type, so these
 * become plain strings: dates as second-precision ISO 8601, data as padded base64, URLs as text.
 * A float is widened to a double. Throws [FlagSerializationException.NonFiniteNumber] for an
 * infinity or a NaN, which JSON cannot represent.
 */
internal fun FlagValueBox.toJson(): JsonValue = when (this) {
    is FlagValueBox.Bool -> JsonValue.Bool(value)
    is FlagValueBox.Int -> JsonValue.Integer(value)
    is FlagValueBox.Double -> finiteReal(value)
    is FlagValueBox.Float -> finiteReal(value.toDouble())
    is FlagValueBox.String -> JsonValue.Text(value)
    is FlagValueBox.Data -> JsonValue.Text(StrictBase64.encode(value))
    is FlagValueBox.Date -> JsonValue.Text(IsoDate.format(value))
    is FlagValueBox.Url -> JsonValue.Text(value.toString())
    is FlagValueBox.Array -> JsonValue.Array(value.map { it.toJson() })
    is FlagValueBox.Dictionary -> JsonValue.Object(value.mapValues { it.value.toJson() })
}

private fun finiteReal(value: Double): JsonValue {
    if (!value.isFinite()) throw FlagSerializationException.NonFiniteNumber(null)
    return JsonValue.Real(value)
}

/**
 * Rebuilds a box from JSON, guided by the flag's declared type, exactly as
 * `FlagValueBox(jsonValue:as:)` does. Returns `null` when the value cannot be read as that type.
 *
 * - `Bool` only from a JSON boolean; `Int` only from an [JsonValue.Integer].
 * - `Double` and `Float` from a [JsonValue.Real], or from an [JsonValue.Integer] that a double
 *   represents exactly (`9007199254740993` is refused, as `NSNumber as? Double` refuses it).
 *   `Float` then narrows the double as Swift's `Float(_:)` does.
 * - `Data` through [StrictBase64], `Date` through [IsoDate], `Url` as `URL(string:)` reads it.
 * - Collections are all or nothing.
 */
internal fun JsonValue.toBox(type: FlagValueType): FlagValueBox? {
    return when (type) {
        FlagValueType.Bool -> (this as? JsonValue.Bool)?.let { FlagValueBox.Bool(it.value) }
        FlagValueType.Int -> (this as? JsonValue.Integer)?.let { FlagValueBox.Int(it.value) }
        FlagValueType.Double -> exactDouble()?.let { FlagValueBox.Double(it) }
        FlagValueType.Float -> exactDouble()?.let { FlagValueBox.Float(it.toFloat()) }
        FlagValueType.String -> text()?.let { FlagValueBox.String(it) }
        FlagValueType.Data -> text()?.let(StrictBase64::decode)?.let { FlagValueBox.Data(it) }
        FlagValueType.Date -> text()?.let(IsoDate::parse)?.let { FlagValueBox.Date(it) }
        FlagValueType.Url -> text()?.let(::parseUrl)?.let { FlagValueBox.Url(it) }
        is FlagValueType.Array -> {
            val items = (this as? JsonValue.Array)?.items ?: return null
            FlagValueBox.Array(items.map { it.toBox(type.element) ?: return null })
        }
        is FlagValueType.Dictionary -> {
            val fields = (this as? JsonValue.Object)?.fields ?: return null
            FlagValueBox.Dictionary(fields.mapValues { it.value.toBox(type.value) ?: return null })
        }
    }
}

/**
 * This value as its stored form identifies it, for deciding whether two record keys collide.
 *
 * Two values are the same key when they are written the same way, which is not always the same
 * as being equal in memory: a date carries more precision than the wire format keeps. A value
 * JSON cannot write (a non-finite number) is identified by its own description instead of
 * throwing, as Swift's `String(describing:)` would.
 */
internal val FlagValueBox.storageIdentity: String
    get() = if (containsNonFiniteNumber) toString() else JsonWriter.compact(toJson())

private fun JsonValue.text(): String? = (this as? JsonValue.Text)?.value

private fun JsonValue.exactDouble(): Double? = when (this) {
    is JsonValue.Real -> value
    is JsonValue.Integer -> value.toDouble().takeIf { value != Long.MAX_VALUE && it.toLong() == value }
    else -> null
}

// URL(string:)

private const val URL_SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@"
private const val SCHEME_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-."
private const val HEX_DIGITS = "0123456789ABCDEF"
private val URL_PARTS =
    Regex("""(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?""", RegexOption.DOT_MATCHES_ALL)

/**
 * Reads a URL the way `URL(string:)` does on Apple platforms since macOS 14 / iOS 17: characters
 * that are not allowed where they appear (spaces, quotes, `<>`, `{}`, `|`, `\`, `^`, `` ` ``,
 * controls, non-ASCII, and `%` not followed by two hex digits) are percent-encoded as UTF-8
 * rather than refused. The empty string, an invalid scheme, and a host or port containing such
 * characters are refused.
 *
 * Deviations, pinned by `UrlGoldenTest`: Apple converts a non-ASCII host to punycode, which this
 * refuses, and `java.net.URI` refuses a few shapes Apple accepts (an empty authority, `[zz]`).
 */
private fun parseUrl(text: String): URI? {
    if (text.isEmpty()) return null
    val parts = URL_PARTS.matchEntire(text)?.groups ?: return null
    val scheme = parts[1]?.value
    if (scheme != null && (!scheme[0].isAsciiLetter() || scheme.any { it !in SCHEME_CHARACTERS })) return null
    val authority = parts[2]?.value?.let(::encodeAuthority)
    if (parts[2] != null && authority == null) return null
    val rebuilt = buildString {
        if (scheme != null) append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        append(percentEncode(parts[3]?.value.orEmpty(), extra = "/"))
        parts[4]?.let { append('?').append(percentEncode(it.value, extra = "/?")) }
        parts[5]?.let { append('#').append(percentEncode(it.value, extra = "/?")) }
    }
    return try {
        URI(rebuilt)
    } catch (ignored: URISyntaxException) {
        null
    }
}

private fun encodeAuthority(authority: String): String? {
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) percentEncode(authority.substring(0, at), extra = "") + "@" else ""
    val hostAndPort = authority.substring(at + 1)
    if (hostAndPort.startsWith("[")) return userInfo + hostAndPort
    val colon = hostAndPort.lastIndexOf(':')
    val host = if (colon >= 0) hostAndPort.substring(0, colon) else hostAndPort
    val port = if (colon >= 0) hostAndPort.substring(colon + 1) else ""
    val hostIsValid = host.all { it in URL_SAFE && it != ':' && it != '@' || it == '%' }
    if (!hostIsValid || port.any { it !in '0'..'9' }) return null
    return userInfo + hostAndPort
}

private fun percentEncode(component: String, extra: String): String = buildString {
    var index = 0
    while (index < component.length) {
        val char = component[index]
        val validEscape = char == '%' && index + 2 < component.length &&
            component[index + 1].isHexDigit() && component[index + 2].isHexDigit()
        when {
            char in URL_SAFE || char in extra || validEscape -> append(char)
            else -> {
                val codePoint = component.codePointAt(index)
                for (byte in String(Character.toChars(codePoint)).encodeToByteArray()) {
                    val unsigned = byte.toInt() and 0xFF
                    append('%').append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0xF])
                }
                index += Character.charCount(codePoint) - 1
            }
        }
        index++
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
