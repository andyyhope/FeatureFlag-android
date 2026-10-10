package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.json.StrictBase64
import com.andyyhope.featureflag.schema.FlagSchema
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Carries flag overrides in a QR code.
 *
 * The wire format is `FFQR1:`, then base64url without padding of a 4-byte big-endian
 * length followed by the raw DEFLATE of the export JSON. When deflate cannot fit the JSON
 * in `max(n, 64)` bytes, the JSON itself goes in its place. Codes made on iOS decode here
 * and codes made here decode on iOS. The compressed bytes match wherever both zlibs agree,
 * as the JDK's does.
 *
 * Only overrides travel, never the whole flag tree. No camera UI ships here: [decode]
 * takes whatever string a scanner produced.
 */
public object FlagQRCode {

    /** Identifies a flag code and its wire format version. */
    public const val PREFIX: String = "FFQR1:"

    /** The capacity of the largest QR code (version 40) at error correction level L, in byte mode. */
    public const val MAX_ENCODED_LENGTH: Int = 2953

    private const val DEFLATE_LEVEL = 5
    private const val MIN_CAPACITY = 64
    private const val LENGTH_BYTES = 4
    private const val MAX_DECLARED_LENGTH = 50_000_000L

    /**
     * Compresses and encodes a payload.
     *
     * @throws FlagQRCodeException.PayloadTooLarge when the result will not fit in one code.
     */
    public fun encode(payload: FlagPayload): String {
        val encoded = PREFIX + base64UrlEncode(compress(payload.jsonBytes()))
        if (encoded.length > MAX_ENCODED_LENGTH) {
            throw FlagQRCodeException.PayloadTooLarge(encoded.length, MAX_ENCODED_LENGTH, payload.values.size)
        }
        return encoded
    }

    /**
     * Decodes a scanned string and validates every value against [schema], exactly as an
     * imported document is validated. A code from another app's build is rejected, not
     * partly applied.
     */
    public fun decode(text: String, schema: FlagSchema): FlagPayload = FlagPayload.decode(decodeJson(text), schema)

    /** The export JSON a code carries. */
    internal fun decodeJson(text: String): ByteArray {
        if (!text.startsWith(PREFIX)) throw FlagQRCodeException.UnrecognisedFormat()
        return base64UrlDecode(text.substring(PREFIX.length))?.let { decompress(it) }
            ?: throw FlagQRCodeException.Corrupt()
    }

    /**
     * The length prefix, then the deflated bytes, or the input itself when deflate cannot
     * fit it. Apple's encoder writes into a buffer of `max(n, 64)` bytes and needs one byte
     * to spare: it gives up, and the input is stored, when the deflated bytes would fill
     * that buffer exactly or overflow it. This was measured, and the `stored` golden pins it.
     */
    internal fun compress(json: ByteArray): ByteArray {
        val deflated = deflate(json)
        val body = if (deflated.size >= maxOf(json.size, MIN_CAPACITY)) json else deflated
        return lengthPrefix(json.size) + body
    }

    internal fun decompress(data: ByteArray): ByteArray? {
        if (data.size < LENGTH_BYTES) return null
        val expected = (0 until LENGTH_BYTES).fold(0L) { length, index -> (length shl 8) or (data[index].toLong() and 0xFF) }
        val body = data.copyOfRange(LENGTH_BYTES, data.size)
        if (expected == 0L) return if (body.isEmpty()) ByteArray(0) else null
        // A corrupt length must not turn into an implausible allocation.
        if (expected > MAX_DECLARED_LENGTH) return null
        val output = ByteArray(expected.toInt())
        return when {
            inflate(body, output) == output.size -> output
            // Deflate could not shrink it when it was encoded, so it was stored as it is.
            body.size == output.size -> body
            else -> null
        }
    }

    /** base64url without padding: no character a QR code or a URL would need escaped. */
    internal fun base64UrlEncode(bytes: ByteArray): String =
        StrictBase64.encode(bytes).replace('+', '-').replace('/', '_').replace("=", "")

    /** The reverse, as Apple does it: map back to the standard alphabet, pad, and decode strictly. */
    internal fun base64UrlDecode(text: String): ByteArray? {
        val base64 = text.replace('-', '+').replace('_', '/')
        val remainder = base64.length % 4
        return StrictBase64.decode(if (remainder > 0) base64 + "=".repeat(4 - remainder) else base64)
    }

    private fun lengthPrefix(length: Int): ByteArray =
        byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(DEFLATE_LEVEL, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            var output = ByteArray(maxOf(input.size, MIN_CAPACITY))
            var size = 0
            while (!deflater.finished()) {
                if (size == output.size) output = output.copyOf(output.size * 2)
                size += deflater.deflate(output, size, output.size - size)
            }
            return output.copyOf(size)
        } finally {
            deflater.end()
        }
    }

    /**
     * Inflates into [output] until it is full, the stream ends, or the input runs out or
     * turns out not to be DEFLATE. Apple's decoder stops in the same places, so trailing
     * bytes are ignored and a short declared length cuts the output short.
     */
    private fun inflate(body: ByteArray, output: ByteArray): Int {
        val inflater = Inflater(true)
        try {
            inflater.setInput(body)
            var written = 0
            var progressing = true
            while (progressing && written < output.size) {
                val count = inflateStep(inflater, output, written)
                progressing = count > 0
                if (progressing) written += count
            }
            return written
        } finally {
            inflater.end()
        }
    }

    private fun inflateStep(inflater: Inflater, output: ByteArray, offset: Int): Int = try {
        inflater.inflate(output, offset, output.size - offset)
    } catch (ignored: DataFormatException) {
        -1
    }
}
