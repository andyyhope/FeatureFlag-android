package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `fixtures/qr/single.json`, measured with FeatureFlag 0.12.0: `["bool-flag": .bool(true)]` exported at 1_767_260_460. */
private val SINGLE_EXPORT = """
    {
      "exportedAt" : "2026-01-01T09:41:00Z",
      "formatVersion" : 1,
      "values" : {
        "bool-flag" : true
      }
    }
""".trimIndent()

/** Measured with FeatureFlag 0.12.0: an empty object is its braces around an empty line. */
private val EMPTY_EXPORT = """
    {
      "exportedAt" : "2026-01-01T09:41:00Z",
      "formatVersion" : 1,
      "values" : {

      }
    }
""".trimIndent()

/** FeatureFlag 0.12.0's `String(describing:)` of the rejection in `handEditedMistakesAreReportedExactlyAsAppleReportsThem`. */
private val NINE_PROBLEMS = """
    Nothing was applied. 9 problems:
      • bool-flag: expected bool, got 1
      • double-flag: expected double, got <null>
      • int-flag: expected int, got true
      • level: 99 is not one of int(1), int(2), int(3)
      • limits: expected dictionary<int>, got an array of 2
      • markets: expected array<string>, got "NZ"
      • nope: no flag has this key. The document came from a different app, or from a build where the flag was named differently
      • string-flag: expected string, got 1.5
      • tier: "platinum" is not one of free, pro, enterprise
    Import is all or nothing, so no flag was changed.
""".trimIndent()

class FlagPayloadTest {

    private val boolFlag = FlagKey("bool-flag")
    private val endpoints = FlagKey("endpoints")

    private fun decode(json: String): FlagPayload = FlagPayload.decode(json.encodeToByteArray(), goldenSchema)

    private fun rejection(json: String): FlagImportException.Rejected =
        assertFailsWith<FlagImportException.Rejected> { decode(json) }

    @Test
    fun aRealValueOfTheWrongTypeIsDescribedAsNsNumberWouldDescribeIt() {
        val found = { json: String -> rejection("""{"formatVersion":1,"values":{"string-flag":$json}}""").problems.single().found }
        assertEquals("1.5e+300", found("1.5e300"))
        assertEquals("0.1", found("0.1"))
        assertEquals("1.234567890123457e+19", found("1.2345678901234568e19"))
        assertEquals("-1.5e-07", found("-1.5e-7"))
        assertEquals("-inf", found("-1e400"))
    }

    // Writing

    @Test
    fun anExportIsByteIdenticalToApple() {
        val payload = FlagPayload(mapOf(boolFlag to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt)

        assertEquals(SINGLE_EXPORT, payload.jsonBytes().decodeToString())
    }

    @Test
    fun anEmptyExportIsByteIdenticalToApple() {
        assertEquals(EMPTY_EXPORT, FlagPayload(emptyMap(), GoldenOverrides.exportedAt).jsonBytes().decodeToString())
    }

    @Test
    fun aNonFiniteValueIsRefusedAndNamed() {
        val payload = FlagPayload(mapOf(FlagKey("double-flag") to FlagValueBox.Double(Double.NaN)))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { payload.jsonBytes() }

        assertEquals(FlagKey("double-flag"), error.key)
    }

    // Reading

    @Test
    fun anAppleExportDecodes() {
        assertEquals(FlagPayload(mapOf(boolFlag to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt), decode(SINGLE_EXPORT))
    }

    @Test
    fun handEditedMistakesAreReportedExactlyAsAppleReportsThem() {
        // Review focus 2. Every problem is found, sorted by key, and worded as iOS words it.
        val error = rejection(
            """{"formatVersion":1,"values":{"bool-flag":1,"int-flag":true,"double-flag":null,"string-flag":1.5,"markets":"NZ","limits":[1,2],"level":99,"tier":"platinum","nope":1e300}}""",
        )

        assertEquals(NINE_PROBLEMS, error.message)
        assertEquals(
            FlagImportProblem(FlagKey("nope"), FlagImportProblem.Kind.UnknownKey, found = "1e+300"),
            error.problems.single { it.key == FlagKey("nope") },
        )
    }

    @Test
    fun aByteOrderMarkIsSkipped() {
        // Review focus 5. JSONSerialization skips a UTF-8 BOM, which some editors write; M1a's JsonReader does too.
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SINGLE_EXPORT.encodeToByteArray()

        assertEquals(decode(SINGLE_EXPORT), FlagPayload.decode(marked, goldenSchema))
    }

    @Test
    fun theFirstOfTwoDuplicateKeysWins() {
        val payload = decode("""{"formatVersion":1,"values":{"bool-flag":true,"bool-flag":false}}""")

        assertEquals(mapOf(boolFlag to FlagValueBox.Bool(true)), payload.values)
    }

    @Test
    fun aBooleanFormatVersionReadsAsANumberLikeApple() {
        // Swift reads the field with `as? Int`, and an NSNumber `true` answers 1.
        assertEquals(1, decode("""{"formatVersion":true,"values":{}}""").formatVersion)
    }

    @Test
    fun aNegativeOverflowIsMinusInfinityLikeApple() {
        // Measured: JSONSerialization reads -1e400 as -inf. A double flag takes it, and a message names it "-inf".
        assertEquals(
            FlagValueBox.Double(Double.NEGATIVE_INFINITY),
            decode("""{"formatVersion":1,"values":{"double-flag":-1e400}}""").values[FlagKey("double-flag")],
        )
        val error = rejection("""{"formatVersion":1,"values":{"bool-flag":-1e400}}""")

        assertEquals("bool-flag: expected bool, got -inf", error.problems.single().toString())
    }

    @Test
    fun aFalseFormatVersionIsVersionZero() {
        val error = assertFailsWith<FlagImportException.UnsupportedFormatVersion> {
            decode("""{"formatVersion":false,"values":{}}""")
        }

        assertEquals(0L, error.version)
    }

    @Test
    fun aHugeFormatVersionIsNamedInFull() {
        val error = assertFailsWith<FlagImportException.UnsupportedFormatVersion> {
            decode("""{"formatVersion":5000000000,"values":{}}""")
        }

        assertEquals(
            "The document is format version 5000000000; this build understands 1. Update whichever app is older.",
            error.message,
        )
    }

    @Test
    fun aFormatVersionThatIsNotAWholeNumberIsMissing() {
        for (version in listOf("1.5", "\"1\"", "null")) {
            val error = assertFailsWith<FlagImportException.Malformed>(version) {
                decode("""{"formatVersion":$version,"values":{}}""")
            }
            assertEquals("The document could not be read: missing formatVersion.", error.message)
        }
    }

    @Test
    fun aMissingOrUnreadableExportDateIsTheEpoch() {
        assertEquals(Instant.EPOCH, decode("""{"formatVersion":1,"values":{}}""").exportedAt)
        assertEquals(Instant.EPOCH, decode("""{"formatVersion":1,"exportedAt":"garbage","values":{}}""").exportedAt)
        assertEquals(Instant.EPOCH, decode("""{"formatVersion":1,"exportedAt":"2026-01-01T09:41:00.5Z","values":{}}""").exportedAt)
    }

    @Test
    fun aDocumentThatIsNotAnObjectIsMalformed() {
        for (text in listOf("[]", "not json", "")) {
            val error = assertFailsWith<FlagImportException.Malformed>(text) { decode(text) }
            assertEquals("not a JSON object", error.reason)
        }
    }

    @Test
    fun aDocumentWithoutValuesIsMalformed() {
        val error = assertFailsWith<FlagImportException.Malformed> { decode("""{"formatVersion":1,"values":[]}""") }

        assertEquals("missing values", error.reason)
    }

    @Test
    fun aDuplicateRecordKeySaysWhichKey() {
        val error = rejection(
            """{"formatVersion":1,"values":{"endpoints":"[{\"name\":\"a\",\"url\":\"https://one.example\"},{\"name\":\"a\",\"url\":\"https://two.example\"}]"}}""",
        )

        assertEquals(
            "endpoints: expected every record to have its own name, got two with \"a\"",
            error.problems.single().toString(),
        )
    }

    @Test
    fun textThatIsNotARecordListSaysWhichFieldsWereExpected() {
        val error = rejection("""{"formatVersion":1,"values":{"endpoints":"nonsense"}}""")

        assertEquals(
            "endpoints: expected a list of records (name, url, weight, note, kind, targets), got \"nonsense\"",
            error.problems.single().toString(),
        )
    }

    @Test
    fun aRecordMissingARequiredFieldIsRejected() {
        val error = rejection("""{"formatVersion":1,"values":{"endpoints":"[{\"name\":\"half\"}]"}}""")

        assertEquals(FlagImportProblem.Kind.TypeMismatch, error.problems.single().kind)
        assertEquals("\"[{\"name\":\"half\"}]\"", error.problems.single().found)
    }

    @Test
    fun aRecordListSentAsAnArrayIsATypeMismatch() {
        val error = rejection("""{"formatVersion":1,"values":{"endpoints":[]}}""")

        assertEquals("endpoints: expected string, got an array of 0", error.problems.single().toString())
    }

    @Test
    fun aRecordFieldHoldingAnUnknownEnumCaseIsAcceptedLikeApple() {
        // FeatureFlag 0.12.0 checks a record list's shape, not its enum cases. The flag then
        // reads its default. Measured; mirrors the spec's documented record-list quirk.
        val payload = decode(
            """{"formatVersion":1,"values":{"endpoints":"[{\"name\":\"a\",\"url\":\"https://one.example\",\"kind\":\"platinum\"}]"}}""",
        )

        assertEquals(setOf(endpoints), payload.values.keys)
    }

    @Test
    fun aValidRecordListIsAccepted() {
        val records = GoldenOverrides.values.getValue(endpoints)
        val payload = FlagPayload(mapOf(endpoints to records), GoldenOverrides.exportedAt)

        assertEquals(payload, FlagPayload.decode(payload.jsonBytes(), goldenSchema))
    }
}
