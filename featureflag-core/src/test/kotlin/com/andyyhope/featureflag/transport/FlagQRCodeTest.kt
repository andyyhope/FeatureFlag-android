package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.json.StrictBase64
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `fixtures/qr/single.qr.txt`: FeatureFlag 0.12.0's code for `["bool-flag": .bool(true)]`, exported at 1_767_260_460. */
private const val APPLE_SINGLE =
    "FFQR1:AAAAbavmUlBQSq0oyC8qSU1xLFFSsFJQMjIwMtM1MASiEANLKxNDKwODKCUdkMK0_KLcxJKw1KLizPw8kFpDsHBZYk5pajGIXw3kAgWS8vNzdNNyEtNBYiVFpalA4VquWgA"

/** FeatureFlag 0.12.0's code for no overrides, exported at 1_767_260_460. Measured. */
private const val APPLE_EMPTY =
    "FFQR1:AAAAV6vmUlBQSq0oyC8qSU1xLFFSsFJQMjIwMtM1MASiEANLKxNDKwODKCUdkMK0_KLcxJKw1KLizPw8kFpDsHBZYk5pajGIX80F5Ndy1QIA"

class FlagQRCodeTest {

    private val singlePayload =
        FlagPayload(mapOf(FlagKey("bool-flag") to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt)

    private fun goldenPole(): FlagPole<GoldenFlags> = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

    private fun code(bytes: ByteArray): String = FlagQRCode.PREFIX + FlagQRCode.base64UrlEncode(bytes)

    // Wire format

    @Test
    fun kotlinEncodesExactlyWhatAppleEncodes() {
        assertEquals(APPLE_SINGLE, FlagQRCode.encode(singlePayload))
        assertEquals(APPLE_EMPTY, FlagQRCode.encode(FlagPayload(emptyMap(), GoldenOverrides.exportedAt)))
    }

    @Test
    fun appleCodesDecode() {
        assertEquals(singlePayload, FlagQRCode.decode(APPLE_SINGLE, goldenSchema))
        assertEquals(FlagPayload(emptyMap(), GoldenOverrides.exportedAt), FlagQRCode.decode(APPLE_EMPTY, goldenSchema))
    }

    @Test
    fun encodedStringCarriesAVersionedPrefix() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertTrue(pole.qrCodeString().startsWith("FFQR1:"))
    }

    @Test
    fun encodedStringIsSafeForAQRCode() {
        // base64url, so nothing a QR code or a URL would need escaped, and no padding.
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertTrue(Regex("FFQR1:[A-Za-z0-9_-]+").matches(pole.qrCodeString()))
    }

    // Round trips

    @Test
    fun overridesSurviveAScan() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.boolFlag, true)
        source.setOverride(GoldenFlagsSpec.intFlag, 7)
        source.setOverride(GoldenFlagsSpec.checkout.tier, GoldenTier.ENTERPRISE)

        val destination = goldenPole()
        val applied = destination.importQRCode(source.qrCodeString())

        assertEquals(source.overrides, destination.overrides)
        assertEquals(source.overrides, applied.values)
    }

    @Test
    fun anEmptySetOfOverridesRoundTrips() {
        val destination = goldenPole()

        destination.importQRCode(goldenPole().qrCodeString())

        assertTrue(destination.overrides.isEmpty())
    }

    @Test
    fun compressionMakesRealisticPayloadsFit() {
        // Keys and JSON structure repeat heavily, which is what deflate is good at.
        val pole = FlagPole(ManyFlagsSpec, listOf(SnapshotSource("local")))
        val value = "a moderately long override value. ".repeat(8)
        val refs = listOf(
            ManyFlagsSpec.alpha, ManyFlagsSpec.bravo, ManyFlagsSpec.charlie, ManyFlagsSpec.delta,
            ManyFlagsSpec.echo, ManyFlagsSpec.foxtrot, ManyFlagsSpec.golf, ManyFlagsSpec.hotel,
            ManyFlagsSpec.india, ManyFlagsSpec.juliett, ManyFlagsSpec.kilo, ManyFlagsSpec.lima,
            ManyFlagsSpec.mike, ManyFlagsSpec.november, ManyFlagsSpec.oscar, ManyFlagsSpec.papa,
        )
        for (ref in refs) pole.setOverride(ref, value)

        assertTrue(pole.exportJson().size > FlagQRCode.MAX_ENCODED_LENGTH)
        assertTrue(pole.qrCodeString().length <= FlagQRCode.MAX_ENCODED_LENGTH)
    }

    // Size limit

    @Test
    fun anOversizedPayloadIsRefusedWithSomethingActionable() {
        val pole = FlagPole(BlobFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(BlobFlagsSpec.blob, incompressibleBytes(8_000, 0x2545_F491_4F6C_DD1DuL))

        val error = assertFailsWith<FlagQRCodeException.PayloadTooLarge> { pole.qrCodeString() }

        assertTrue(error.bytes > error.limit)
        assertEquals(2953, error.limit)
        assertEquals(1, error.overrideCount)
    }

    @Test
    fun aPayloadJustUnderTheLimitEncodes() {
        val pole = FlagPole(BigStringFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(BigStringFlagsSpec.blob, StrictBase64.encode(incompressibleBytes(1_800, 0x9E37_79B9_7F4A_7C15uL)))

        assertTrue(pole.qrCodeString().length <= FlagQRCode.MAX_ENCODED_LENGTH)
    }

    @Test
    fun aPayloadJustOverTheLimitIsRefused() {
        val pole = FlagPole(BigStringFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(BigStringFlagsSpec.blob, StrictBase64.encode(incompressibleBytes(3_000, 0x9E37_79B9_7F4A_7C15uL)))

        assertFailsWith<FlagQRCodeException.PayloadTooLarge> { pole.qrCodeString() }
    }

    @Test
    fun aQRCodeOfANonFiniteNumberThrows() {
        val local = SnapshotSource("local", mapOf(FlagKey("ratio") to FlagValueBox.Double(Double.POSITIVE_INFINITY)))

        assertFailsWith<FlagSerializationException.NonFiniteNumber> { FlagPole(EdgeFlagsSpec, listOf(local)).qrCodeString() }
    }

    // Rejecting bad input

    @Test
    fun decodingRejectsAStringThatIsNotAFlagCode() {
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { goldenPole().importQRCode("https://example.com") }
    }

    @Test
    fun decodingRejectsAFutureFormatVersion() {
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { goldenPole().importQRCode("FFQR9:abcdef") }
    }

    @Test
    fun anEmptyScannedStringIsRejected() {
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { FlagQRCode.decode("", goldenSchema) }
    }

    @Test
    fun decodingRejectsCorruptedContent() {
        assertFailsWith<FlagQRCodeException.Corrupt> { goldenPole().importQRCode("FFQR1:!!!not-base64!!!") }
    }

    @Test
    fun aPrefixWithNoBodyIsCorrupt() {
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode("FFQR1:", goldenSchema) }
    }

    @Test
    fun decodingRejectsTruncatedContent() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        val truncated = pole.qrCodeString().dropLast(8)

        assertFailsWith<FlagQRCodeException.Corrupt> { goldenPole().importQRCode(truncated) }
    }

    @Test
    fun aCorruptLengthPrefixDoesNotAllocateWildly() {
        // Four 0xFF bytes claim a 4 GB payload. It must be refused, not attempted.
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode(code(byteArrayOf(-1, -1, -1, -1, 0)), goldenSchema) }
        // 50,000,001 bytes: one past the cap.
        val overCap = byteArrayOf(0x02, 0xFA.toByte(), 0xF0.toByte(), 0x81.toByte(), 0)
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode(code(overCap), goldenSchema) }
    }

    @Test
    fun scannedCodeStillValidatesAgainstTheSchema() {
        // A code from another app's build must not smuggle in unknown keys.
        val foreign = FlagPole(BlobFlagsSpec, listOf(SnapshotSource("local")))
        foreign.setOverride(BlobFlagsSpec.blob, byteArrayOf(0x01))

        assertFailsWith<FlagImportException.Rejected> { goldenPole().importQRCode(foreign.qrCodeString()) }
    }

    @Test
    fun aValidCodeWithForeignKeysIsRejectedNotIgnored() {
        val encoded = FlagQRCode.encode(FlagPayload(mapOf(FlagKey("not-a-flag") to FlagValueBox.Bool(true))))

        val error = assertFailsWith<FlagImportException.Rejected> { FlagQRCode.decode(encoded, goldenSchema) }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownKey), error.problems.map { it.kind })
    }

    @Test
    fun aScannedCodeWithAnUnknownCaseIsRejected() {
        val sender = FlagPole(WiderTierFlagsSpec, listOf(SnapshotSource("local")))
        sender.setOverride(WiderTierFlagsSpec.tier, WiderTier.PLATINUM)
        val receiver = goldenPole()

        val error = assertFailsWith<FlagImportException.Rejected> { receiver.importQRCode(sender.qrCodeString()) }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownCase), error.problems.map { it.kind })
        assertEquals(GoldenTier.FREE, receiver.flags.tier)
    }

    // Matching Apple at the edges

    @Test
    fun scannedTextVariantsMatchApple() {
        // Review focus 3: what a scanner or a clipboard hands over.
        assertEquals(singlePayload, FlagQRCode.decode(APPLE_SINGLE.replace('-', '+').replace('_', '/'), goldenSchema))
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode(APPLE_SINGLE + "\n", goldenSchema) }
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode("$APPLE_SINGLE ", goldenSchema) }
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { FlagQRCode.decode(" $APPLE_SINGLE", goldenSchema) }
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { FlagQRCode.decode(APPLE_SINGLE.lowercase(), goldenSchema) }

        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        assertContentEquals(bytes, FlagQRCode.base64UrlDecode("-_8"))
        assertContentEquals(bytes, FlagQRCode.base64UrlDecode("+/8"))
        assertContentEquals(bytes, FlagQRCode.base64UrlDecode("-_8="))
        assertNull(FlagQRCode.base64UrlDecode("-_8\n"))
        assertNull(FlagQRCode.base64UrlDecode("A"))
    }

    @Test
    fun aDeclaredLengthShorterThanTheStreamTruncatesTheDocument() {
        // Apple's decoder fills exactly the declared length and stops, so the JSON is cut short.
        val compressed = FlagQRCode.compress(singlePayload.jsonBytes())
        val lying = byteArrayOf(0, 0, 0, 50) + compressed.copyOfRange(4, compressed.size)

        val error = assertFailsWith<FlagImportException.Malformed> { FlagQRCode.decode(code(lying), goldenSchema) }

        assertEquals("not a JSON object", error.reason)
    }

    @Test
    fun trailingBytesAfterACompleteStreamAreIgnoredLikeApple() {
        val padded = FlagQRCode.compress(singlePayload.jsonBytes()) + byteArrayOf(1, 2, 3)

        assertEquals(singlePayload, FlagQRCode.decode(code(padded), goldenSchema))
    }

    @Test
    fun aDeclaredLengthOfZeroMeansAnEmptyBody() {
        assertContentEquals(ByteArray(0), FlagQRCode.decompress(byteArrayOf(0, 0, 0, 0)))
        assertNull(FlagQRCode.decompress(byteArrayOf(0, 0, 0, 0, 1)))

        val error = assertFailsWith<FlagImportException.Malformed> {
            FlagQRCode.decode(code(byteArrayOf(0, 0, 0, 0)), goldenSchema)
        }

        assertEquals("not a JSON object", error.reason)
    }

    @Test
    fun aBodyStoredRawIsReadBack() {
        // iOS stores the JSON itself when deflate cannot fit it in max(n, 64) bytes.
        val json = singlePayload.jsonBytes()
        val stored = byteArrayOf(0, 0, (json.size shr 8).toByte(), json.size.toByte()) + json

        assertEquals(singlePayload, FlagQRCode.decode(code(stored), goldenSchema))
    }

    @Test
    fun incompressibleInputIsStoredRaw() {
        val input = incompressibleBytes(1_000, 1uL)

        val compressed = FlagQRCode.compress(input)

        assertContentEquals(byteArrayOf(0, 0, 0x03, 0xE8.toByte()) + input, compressed)
        assertContentEquals(input, FlagQRCode.decompress(compressed))
    }

    @Test
    fun smallInputKeepsItsDeflatedFormEvenWhenLarger() {
        // Apple's buffer is max(n, 64) bytes, so a few bytes deflate cannot shrink still fit.
        val input = incompressibleBytes(40, 2uL)

        val compressed = FlagQRCode.compress(input)
        val body = compressed.copyOfRange(4, compressed.size)

        assertTrue(body.size > input.size, "deflate cannot shrink random bytes")
        assertTrue(body.size < 64, "but the 64-byte buffer still has room, so they stay deflated")
        assertContentEquals(input, FlagQRCode.decompress(compressed))
    }

    @Test
    fun aDeflatedBodyThatWouldFillApplesBufferExactlyIsStored() {
        // Measured: these 59 bytes deflate to exactly 64, and Apple's 64-byte buffer then
        // refuses them, because its encoder needs one byte to spare. The first 58 deflate
        // to 63 and are kept.
        val filling = incompressibleBytes(59, 3uL)
        val fitting = incompressibleBytes(58, 3uL)

        assertContentEquals(byteArrayOf(0, 0, 0, 59) + filling, FlagQRCode.compress(filling))
        val kept = FlagQRCode.compress(fitting)
        assertEquals(4 + 63, kept.size)
        assertContentEquals(fitting, FlagQRCode.decompress(kept))
    }
}

@FlagContainer
internal interface BlobFlags {

    @Flag(description = "Blob")
    val blob: ByteArray get() = ByteArray(0)
}

@FlagContainer
internal interface BigStringFlags {

    @Flag(description = "Blob")
    val blob: String get() = ""
}

/** A build with a case the receiving build has never heard of. */
internal enum class WiderTier(override val flagRawValue: String) : FlagStringEnum {
    FREE("free"),
    PRO("pro"),
    ENTERPRISE("enterprise"),
    PLATINUM("platinum"),
}

@FlagContainer
internal interface WiderTierFlags {

    @Flag(description = "Tier")
    val tier: WiderTier get() = WiderTier.FREE
}

@FlagContainer
internal interface ManyFlags {

    @Flag(description = "Alpha")
    val alpha: String get() = ""

    @Flag(description = "Bravo")
    val bravo: String get() = ""

    @Flag(description = "Charlie")
    val charlie: String get() = ""

    @Flag(description = "Delta")
    val delta: String get() = ""

    @Flag(description = "Echo")
    val echo: String get() = ""

    @Flag(description = "Foxtrot")
    val foxtrot: String get() = ""

    @Flag(description = "Golf")
    val golf: String get() = ""

    @Flag(description = "Hotel")
    val hotel: String get() = ""

    @Flag(description = "India")
    val india: String get() = ""

    @Flag(description = "Juliett")
    val juliett: String get() = ""

    @Flag(description = "Kilo")
    val kilo: String get() = ""

    @Flag(description = "Lima")
    val lima: String get() = ""

    @Flag(description = "Mike")
    val mike: String get() = ""

    @Flag(description = "November")
    val november: String get() = ""

    @Flag(description = "Oscar")
    val oscar: String get() = ""

    @Flag(description = "Papa")
    val papa: String get() = ""
}
