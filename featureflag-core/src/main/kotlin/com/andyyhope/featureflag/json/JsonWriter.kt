package com.andyyhope.featureflag.json

/**
 * Writes JSON byte-for-byte as `JSONSerialization` does with `.sortedKeys` and
 * `.withoutEscapingSlashes`, compact or with `.prettyPrinted` as well.
 *
 * - Pretty output indents by two spaces, writes `"key" : value`, and has no trailing newline.
 *   An empty container is the opening bracket, a newline, an empty line, then the closing
 *   bracket at the current indent.
 * - Keys are sorted with [AppleKeyOrder]. Numbers are written by [AppleNumberFormat].
 * - Strings escape `"`, `\`, `\b \t \n \f \r` and other C0 controls as lowercase `\u00xx`;
 *   `/`, DEL, U+2028, U+2029 and all non-ASCII are written raw.
 */
internal object JsonWriter {

    fun pretty(value: JsonValue): String = StringBuilder().also { it.writePretty(value, 0) }.toString()

    fun compact(value: JsonValue): String = StringBuilder().also { it.writeCompact(value) }.toString()

    private fun StringBuilder.writeCompact(value: JsonValue) {
        when (value) {
            is JsonValue.Array -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    writeCompact(item)
                }
                append(']')
            }
            is JsonValue.Object -> {
                append('{')
                value.fields.keys.sortedWith(AppleKeyOrder).forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    writeString(key)
                    append(':')
                    writeCompact(value.fields.getValue(key))
                }
                append('}')
            }
            else -> writeScalar(value)
        }
    }

    private fun StringBuilder.writePretty(value: JsonValue, depth: Int) {
        when (value) {
            is JsonValue.Array -> {
                append('[')
                if (value.items.isEmpty()) {
                    append("\n\n").indent(depth)
                } else {
                    value.items.forEachIndexed { index, item ->
                        append(if (index > 0) ",\n" else "\n").indent(depth + 1)
                        writePretty(item, depth + 1)
                    }
                    append('\n').indent(depth)
                }
                append(']')
            }
            is JsonValue.Object -> {
                append('{')
                if (value.fields.isEmpty()) {
                    append("\n\n").indent(depth)
                } else {
                    value.fields.keys.sortedWith(AppleKeyOrder).forEachIndexed { index, key ->
                        append(if (index > 0) ",\n" else "\n").indent(depth + 1)
                        writeString(key)
                        append(" : ")
                        writePretty(value.fields.getValue(key), depth + 1)
                    }
                    append('\n').indent(depth)
                }
                append('}')
            }
            else -> writeScalar(value)
        }
    }

    private fun StringBuilder.indent(depth: Int): StringBuilder = apply { repeat(depth) { append("  ") } }

    private fun StringBuilder.writeScalar(value: JsonValue) {
        when (value) {
            JsonValue.Null -> append("null")
            is JsonValue.Bool -> append(value.value)
            is JsonValue.Integer -> append(value.value)
            is JsonValue.Real -> append(AppleNumberFormat.format(value.value))
            is JsonValue.Text -> writeString(value.value)
            is JsonValue.Array, is JsonValue.Object -> error("containers are written by the caller")
        }
    }

    private fun StringBuilder.writeString(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else ->
                    if (char < ' ') {
                        append("\\u00").append(HEX[char.code shr 4]).append(HEX[char.code and 0xF])
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}
