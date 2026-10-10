package com.andyyhope.featureflag.json

/**
 * Standard, padded base64 — `Data.base64EncodedString()` to write and exactly what
 * `Data(base64Encoded:)` accepts to read (see `fixtures/base64/decode.tsv`).
 *
 * Reading rejects whitespace, the URL-safe alphabet, a body of length 1 mod 4, and missing
 * padding. It accepts extra trailing padding, non-zero trailing bits, and two Apple quirks:
 * a final quad of the form `xx=x` decodes to two bytes (the first byte and the last sextet),
 * and a string of four or more `=` and nothing else decodes to a single zero byte.
 */
internal object StrictBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val PAD = '='
    private const val QUAD = 4
    private const val MIN_PADDING_ONLY = 4

    private val VALUES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String = buildString {
        for (start in bytes.indices step 3) {
            val b0 = bytes[start].toInt() and 0xFF
            val b1 = if (start + 1 < bytes.size) bytes[start + 1].toInt() and 0xFF else 0
            val b2 = if (start + 2 < bytes.size) bytes[start + 2].toInt() and 0xFF else 0
            append(ALPHABET[b0 shr 2])
            append(ALPHABET[(b0 and 0x03) shl 4 or (b1 shr 4)])
            append(if (start + 1 < bytes.size) ALPHABET[(b1 and 0x0F) shl 2 or (b2 shr 6)] else PAD)
            append(if (start + 2 < bytes.size) ALPHABET[b2 and 0x3F] else PAD)
        }
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.any { it != PAD && (it.code >= VALUES.size || VALUES[it.code] < 0) }) return null
        val body = text.trimEnd(PAD)
        val padding = text.length - body.length
        if (body.isEmpty()) return if (padding >= MIN_PADDING_ONLY) ByteArray(1) else null

        val remainder = body.length % QUAD
        val innerPad = body.indexOf(PAD)
        val quirkyQuad = innerPad >= 0 && remainder == 0 && innerPad == body.length - 2
        val paddingIsEnough = when (remainder) {
            0 -> true
            2 -> padding >= 2
            3 -> padding >= 1
            else -> false
        }
        if ((innerPad >= 0 && !quirkyQuad) || !paddingIsEnough) return null

        val out = ArrayList<Byte>(body.length / QUAD * 3 + 2)
        var start = 0
        while (start < body.length) {
            val end = minOf(start + QUAD, body.length)
            decodeGroup(body.substring(start, end), out)
            start = end
        }
        return out.toByteArray()
    }

    private fun decodeGroup(group: String, out: MutableList<Byte>) {
        val c0 = VALUES[group[0].code]
        val c1 = VALUES[group[1].code]
        out += (c0 shl 2 or (c1 shr 4)).toByte()
        when {
            group.length == 4 && group[2] == PAD -> out += VALUES[group[3].code].toByte()
            group.length >= 3 -> {
                val c2 = VALUES[group[2].code]
                out += ((c1 and 0x0F) shl 4 or (c2 shr 2)).toByte()
                if (group.length == 4) out += ((c2 and 0x03) shl 6 or VALUES[group[3].code]).toByte()
            }
        }
    }
}
