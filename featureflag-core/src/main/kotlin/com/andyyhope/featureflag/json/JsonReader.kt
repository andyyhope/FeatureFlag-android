package com.andyyhope.featureflag.json

import kotlin.math.floor

/**
 * A strict JSON reader that accepts and rejects exactly what Apple's `JSONSerialization`
 * does for UTF-8 input (measured on macOS 27; see `fixtures/json/parse.tsv`).
 *
 * - The top level must be an array or an object; surrounding space, tab, CR and LF are allowed,
 *   and so is a leading UTF-8 byte order mark.
 * - One trailing comma is allowed after the last element of an array or object.
 * - Containers nest at most 513 deep.
 * - When an object repeats a key, the first value wins.
 * - Positive overflow (`1e400`) is rejected; negative overflow (`-1e400`) reads as -Infinity.
 *
 * Documented deviations: UTF-16/UTF-32 input is rejected (Apple detects and accepts it), and
 * literals with more significant digits than a `Double` carries are rounded correctly here,
 * where Apple's `NSDecimalNumber` path can be off by an ulp or reject very long literals.
 */
internal object JsonReader {

    private const val MAX_DEPTH = 513

    fun parse(bytes: ByteArray): JsonValue {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: throw JsonSyntaxException("The data is not valid UTF-8.")
        return Parser(text.removePrefix(BYTE_ORDER_MARK.toString())).document()
    }

    private class Parser(private val text: String) {
        private var index = 0
        private var depth = 0

        fun document(): JsonValue {
            skipWhitespace()
            val value = when (peek()) {
                '[' -> array()
                '{' -> obj()
                else -> fail("The top level must be an array or an object")
            }
            skipWhitespace()
            if (index != text.length) fail("Unexpected text after the document")
            return value
        }

        private fun value(): JsonValue = when (val char = peek()) {
            '[' -> array()
            '{' -> obj()
            '"' -> JsonValue.Text(string())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (char == '-' || char in '0'..'9') number() else fail("Unexpected character")
        }

        private fun array(): JsonValue {
            enter()
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
            } else {
                while (true) {
                    items += value()
                    skipWhitespace()
                    if (endOfContainer(']')) break
                }
            }
            depth--
            return JsonValue.Array(items)
        }

        private fun obj(): JsonValue {
            enter()
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
            } else {
                while (true) {
                    if (peek() != '"') fail("Expected a string key")
                    val key = string()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    val value = value()
                    if (!fields.containsKey(key)) fields[key] = value
                    skipWhitespace()
                    if (endOfContainer('}')) break
                }
            }
            depth--
            return JsonValue.Object(fields)
        }

        private fun enter() {
            index++
            depth++
            if (depth > MAX_DEPTH) fail("Too deeply nested")
        }

        /** After an element: `,` (optionally followed by the closing bracket), or the closing bracket. */
        private fun endOfContainer(closing: Char): Boolean = when (peek()) {
            ',' -> {
                index++
                skipWhitespace()
                if (peek() == closing) {
                    index++
                    true
                } else {
                    false
                }
            }
            closing -> {
                index++
                true
            }
            else -> fail("Expected ',' or '$closing'")
        }

        private fun string(): String {
            index++
            val out = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> escape(out)
                    char < ' ' -> fail("Unescaped control character in a string")
                    else -> out.append(char)
                }
            }
        }

        private fun escape(out: StringBuilder) {
            when (next()) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> unicodeEscape(out)
                else -> fail("Invalid escape")
            }
        }

        private fun unicodeEscape(out: StringBuilder) {
            val unit = hex4()
            when {
                Character.isHighSurrogate(unit) -> {
                    if (next() != '\\' || next() != 'u') fail("Unpaired surrogate")
                    val low = hex4()
                    if (!Character.isLowSurrogate(low)) fail("Unpaired surrogate")
                    out.append(unit).append(low)
                }
                Character.isLowSurrogate(unit) -> fail("Unpaired surrogate")
                else -> out.append(unit)
            }
        }

        private fun hex4(): Char {
            var value = 0
            repeat(4) {
                val digit = when (val char = next()) {
                    in '0'..'9' -> char - '0'
                    in 'a'..'f' -> char - 'a' + 10
                    in 'A'..'F' -> char - 'A' + 10
                    else -> fail("Invalid \\u escape")
                }
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun number(): JsonValue {
            val start = index
            if (peek() == '-') index++
            when {
                peek() == '0' -> index++
                peek() in '1'..'9' -> skipDigits()
                else -> fail("Invalid number")
            }
            var integerLiteral = true
            if (peek() == '.') {
                integerLiteral = false
                index++
                if (peek() !in '0'..'9') fail("Invalid number")
                skipDigits()
            }
            if (peek() == 'e' || peek() == 'E') {
                integerLiteral = false
                index++
                if (peek() == '+' || peek() == '-') index++
                if (peek() !in '0'..'9') fail("Invalid number")
                skipDigits()
            }
            return classify(text.substring(start, index), integerLiteral)
        }

        private fun classify(literal: String, integerLiteral: Boolean): JsonValue {
            if (integerLiteral) literal.toLongOrNull()?.let { return JsonValue.Integer(it) }
            val value = literal.toDouble()
            if (value == Double.POSITIVE_INFINITY || (integerLiteral && value.isInfinite())) {
                fail("Number out of range")
            }
            // `as? Int` on an NSNumber holding a double: integral and within [-2^63, 2^63],
            // where 2^63 itself clamps to Long.MAX_VALUE exactly as Swift's bridge does.
            val integral = !integerLiteral && value == floor(value) && value in LONG_MIN_AS_DOUBLE..TWO_TO_63
            return if (integral) JsonValue.Integer(value.toLong()) else JsonValue.Real(value)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("Invalid literal")
            index += word.length
            return value
        }

        private fun expect(char: Char) {
            if (peek() != char) fail("Expected '$char'")
            index++
        }

        private fun skipDigits() {
            while (peek() in '0'..'9') index++
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in WHITESPACE) index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else END

        private fun next(): Char {
            if (index >= text.length) fail("Unexpected end of data")
            return text[index++]
        }

        private fun fail(reason: String): Nothing = throw JsonSyntaxException("$reason at offset $index.")
    }

    private const val END = '\u0000'
    private val BYTE_ORDER_MARK = Char(0xFEFF)
    private const val WHITESPACE = " \t\n\r"
    private const val TWO_TO_63 = 9.223372036854775808E18
    private const val LONG_MIN_AS_DOUBLE = -9.223372036854775808E18
}
