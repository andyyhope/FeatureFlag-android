package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.audit.FlagMappingAuditException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.remote.shortDescription
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal enum class MessageTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

@FlagRecord
internal data class MessageEndpoint(val name: String, val port: Int)

@FlagContainer
internal interface MessageFlags {
    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Tier", remoteKey = "config.tier")
    val checkoutTier: MessageTier get() = MessageTier.Free

    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<MessageEndpoint> get() = FlagRecords()
}

private val onboardingMismatch =
    RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding", Kind.TypeMismatch, "bool", "\"yes\"")

/** The report quoted in RemoteOverrides.md, "What a rejection tells you". */
private val documentedProblems = listOf(
    RemoteOverrideProblem(FlagKey("checkout-tier"), "config.tier", Kind.UnknownCase, "free, pro", "\"gold\""),
    RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding", Kind.TypeMismatch, "bool", "\"yes\""),
    RemoteOverrideProblem(FlagKey("page-size"), "config.pageSize", Kind.TypeMismatch, "int", "an array of 3"),
)

/** `MessageFixtures.decodeError()`'s document, imported into `GoldenFlags` on both sides. */
private val GOLDEN_IMPORT_DOCUMENT = """
    { "formatVersion": 1, "exportedAt": "2026-01-01T00:00:00Z", "values": {
        "int-flag": "a very long string with a \n newline, a \t tab and a \\ backslash in it",
        "bool-flag": 1,
        "double-flag": "1.5",
        "unknown-flag": [1, 2],
        "tier": "platinum",
        "level": 7,
        "limits": { "a": "x" },
        "markets": { "a": 1, "b": 2 },
        "date-flag": "2026-13-01T00:00:00Z",
        "endpoints": "[{\"name\":\"a\",\"url\":\"https://a.example\"},{\"name\":\"a\",\"url\":\"https://b.example\"}]",
        "checkout.tier": true,
        "url-flag": "",
        "data-flag": "not base64!"
    } }
""".trimIndent()

/** `MessageFixtures.remoteError()`'s payload, applied to `GoldenFlags` on both sides. */
private val GOLDEN_REMOTE_PAYLOAD = """
    {
      "toggles": { "boolean": "true" },
      "numbers": { "int": 1.5, "double": [1] },
      "strings": { "plain": null },
      "dates": { "launch": 1767225600 },
      "config": { "endpoint": { "a": 1 }, "markets": [1, 2, 3],
        "endpoints": [ { "name": "a", "url": "https://a.example" }, { "name": "a", "url": "https://b.example" } ] },
      "tier": "Platinum Plus Ultra Extreme Deluxe Edition Max Pro",
      "featureToggles": { "checkout": { "applePay": 0 } }
    }
""".trimIndent()

/** Messages Android words differently on purpose (Global Constraints). They are checked by fragment below. */
private val REWORDED = setOf("FlagSchemaError.notPublished", "FlagError.noMutableSource", "FlagError.unsupportedValue")

/** Messages for features M1 does not have. */
private val DROPPED =
    setOf("FlagQRCodeError.imageGenerationFailed", "FlagSignalError.notAcknowledged", "FlagSignalError.unavailableAppGroup")

/**
 * What a developer actually reads when something goes wrong: a port of ErrorMessageTests.swift,
 * plus a byte comparison against every Swift-generated message golden.
 */
class ErrorMessageTest {

    private val messagesDirectory =
        File(requireNotNull(System.getProperty("featureflag.fixtures")) { "featureflag.fixtures is not set" }, "messages")

    private val messageSchema = FlagSchema.of(MessageFlagsSpec)

    private fun importFailure(document: String, schema: FlagSchema = messageSchema): FlagImportException =
        assertFailsWith<FlagImportException> { FlagPayload.decode(document.toByteArray(), schema) }

    /** `message` and `localizedMessage` must both say it, and neither may leak a type name. */
    private fun assertSays(error: Throwable, vararg fragments: String) {
        for (candidate in listOf(error.message, error.localizedMessage)) {
            val message = assertNotNull(candidate)
            for (fragment in fragments) assertTrue(message.contains(fragment), "expected \"$fragment\" in: $message")
            assertFalse(message.contains("com.andyyhope"), "a type name leaked into: $message")
            assertFalse(message.contains("couldn’t be completed"), "a system placeholder leaked into: $message")
        }
    }

    /** The Kotlin exception for each golden, built the way the generator built the Swift one. */
    private val kotlinFor: Map<String, () -> Throwable> = mapOf(
        "RemoteOverrideError.malformed" to { RemoteOverrideException.Malformed("not valid JSON") },
        "RemoteOverrideError.rejected.empty" to { RemoteOverrideException.Rejected(emptyList()) },
        "RemoteOverrideError.rejected.one" to { RemoteOverrideException.Rejected(listOf(onboardingMismatch)) },
        "RemoteOverrideError.rejected.bare" to {
            RemoteOverrideException.Rejected(
                listOf(
                    RemoteOverrideProblem(FlagKey("a"), "x.a", Kind.TypeMismatch),
                    RemoteOverrideProblem(FlagKey("b"), "x.b", Kind.UnknownCase),
                    RemoteOverrideProblem(FlagKey("new-onbaording"), "new-onbaording", Kind.UnknownKey),
                ),
            )
        },
        "RemoteOverrideError.rejected.documented" to { RemoteOverrideException.Rejected(documentedProblems) },
        "RemoteOverrideError.rejected.decoded" to {
            assertFailsWith<RemoteOverrideException.Rejected> {
                RemoteOverrideSource(GoldenFlagsSpec).apply(GOLDEN_REMOTE_PAYLOAD.toByteArray())
            }
        },
        "FlagImportError.malformed" to { FlagImportException.Malformed("not valid JSON") },
        "FlagImportError.unsupportedFormatVersion" to { FlagImportException.UnsupportedFormatVersion(9) },
        "FlagImportError.rejected.empty" to { FlagImportException.Rejected(emptyList()) },
        "FlagImportError.rejected.one" to { importFailure("""{"formatVersion":1,"values":{"page-size":"big"}}""") },
        "FlagImportError.rejected.unknownKey" to { importFailure("""{"formatVersion":1,"values":{"nope":true}}""") },
        "FlagImportError.rejected.bare" to {
            FlagImportException.Rejected(
                listOf(
                    FlagImportProblem(FlagKey("a"), FlagImportProblem.Kind.TypeMismatch),
                    FlagImportProblem(FlagKey("b"), FlagImportProblem.Kind.UnknownCase),
                    FlagImportProblem(FlagKey("c"), FlagImportProblem.Kind.UnknownKey),
                ),
            )
        },
        "FlagImportError.rejected.decoded" to { importFailure(GOLDEN_IMPORT_DOCUMENT, FlagSchema.of(GoldenFlagsSpec)) },
        "FlagSerializationError.nonFiniteNumber" to { FlagSerializationException.NonFiniteNumber(FlagKey("ratio")) },
        "FlagSchemaError.unsupportedFormatVersion" to { FlagSchemaException.UnsupportedFormatVersion(9) },
        "FlagSchemaError.malformed" to { FlagSchemaException.Malformed("missing formatVersion") },
        "FlagQRCodeError.payloadTooLarge.many" to { FlagQRCodeException.PayloadTooLarge(3400, 2900, 12) },
        "FlagQRCodeError.payloadTooLarge.one" to { FlagQRCodeException.PayloadTooLarge(3400, 2900, 1) },
        "FlagQRCodeError.unrecognisedFormat" to { FlagQRCodeException.UnrecognisedFormat() },
        "FlagQRCodeError.corrupt" to { FlagQRCodeException.Corrupt() },
        "FlagMappingAuditError.incomplete" to {
            assertFailsWith<FlagMappingAuditException.Incomplete> {
                FlagMappingAudit(GoldenFlagsSpec, "{}".toByteArray()).requireComplete()
            }
        },
    )

    // Goldens

    @Test
    fun everyMessageGoldenIsAccountedFor() {
        val names = messagesDirectory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".txt") }
            .map { it.removeSuffix(".txt") }
            .toSet()
        val unaccounted = names - kotlinFor.keys - REWORDED - DROPPED
        assertTrue(unaccounted.isEmpty(), "goldens with no Kotlin counterpart: $unaccounted")
        val stale = (REWORDED + DROPPED) - names
        assertTrue(stale.isEmpty(), "REWORDED or DROPPED names with no golden: $stale")
        val missing = kotlinFor.keys - names
        assertTrue(missing.isEmpty(), "Kotlin messages the generator did not write: $missing")
    }

    @Test
    fun everyMessageMatchesItsGoldenByteForByte() {
        for ((name, make) in kotlinFor) {
            assertEquals(File(messagesDirectory, "$name.txt").readText().removeSuffix("\n"), make().message, name)
        }
    }

    // Remote overrides

    @Test
    fun aRejectedRemotePayloadNamesTheValueAndWhatWasExpected() {
        assertSays(
            RemoteOverrideException.Rejected(listOf(onboardingMismatch)),
            "featureToggles.onboarding", "new-onboarding", "bool", "\"yes\"", "Nothing was applied",
        )
    }

    @Test
    fun aRejectedPayloadSaysHowManyProblemsThereWere() {
        val problems = listOf(
            RemoteOverrideProblem(FlagKey("a"), "x.a", Kind.TypeMismatch),
            RemoteOverrideProblem(FlagKey("b"), "x.b", Kind.TypeMismatch),
        )
        assertSays(RemoteOverrideException.Rejected(problems), "2 problems")
    }

    @Test
    fun anUnknownCaseListsTheOnesThatWouldHaveWorked() {
        val problem = RemoteOverrideProblem(FlagKey("tier"), "config.tier", Kind.UnknownCase, "free, pro", "\"gold\"")
        assertSays(RemoteOverrideException.Rejected(listOf(problem)), "\"gold\"", "free, pro")
    }

    @Test
    fun anUnknownKeyPointsAtTheMapperRatherThanTheBackend() {
        val problem = RemoteOverrideProblem(FlagKey("new-onbaording"), "new-onbaording", Kind.UnknownKey)
        assertSays(RemoteOverrideException.Rejected(listOf(problem)), "new-onbaording", "mapper")
    }

    @Test
    fun aMalformedPayloadSaysWhatWasWrongWithIt() {
        assertSays(RemoteOverrideException.Malformed("not valid JSON"), "not valid JSON")
    }

    // Values that fight the layout

    @Test
    fun aValueContainingNewlinesStaysOnOneLine() {
        val found = RemoteValue.String("line one\nline two").shortDescription
        val message = assertNotNull(
            RemoteOverrideException.Rejected(listOf(RemoteOverrideProblem(FlagKey("k"), "r", Kind.TypeMismatch, "int", found))).message,
        )
        assertEquals(1, message.split("\n").count { "•" in it })
        assertTrue(message.contains("line one\\nline two"))
    }

    @Test
    fun aLongValueSaysThatItWasCutShort() {
        val long = RemoteValue.String("A".repeat(200)).shortDescription
        assertTrue(long.endsWith("…\""), long)
        assertTrue(long.length < 60, long)
    }

    @Test
    fun aValueThatFitsIsNotMarkedAsCutShort() {
        assertEquals("\"short\"", RemoteValue.String("short").shortDescription)
    }

    @Test
    fun theSameIsTrueOfValuesFromADocument() {
        // Swift checks flagShortDescription(of:) directly; here the same rendering runs through a real import.
        val long = importFailure("""{"formatVersion":1,"values":{"page-size":"${"A".repeat(200)}"}}""")
        assertTrue(assertNotNull(long.message).contains("…\""), long.message)
        val multiline = importFailure("""{"formatVersion":1,"values":{"page-size":"line one\nline two"}}""")
        assertEquals(1, assertNotNull(multiline.message).split("\n").count { "•" in it })
    }

    // Degenerate input

    @Test
    fun aRejectionWithNoProblemsStillReadsAsASentence() {
        val remote = assertNotNull(RemoteOverrideException.Rejected(emptyList()).message)
        assertFalse(remote.contains("0 problems"), remote)
        assertFalse(remote.endsWith(":"), remote)
        val imported = assertNotNull(FlagImportException.Rejected(emptyList()).message)
        assertFalse(imported.contains("0 problems"), imported)
    }

    // Import

    @Test
    fun aRejectedImportNamesTheFlagAndTheProblem() {
        assertSays(importFailure("""{"formatVersion":1,"values":{"page-size":"big"}}"""), "page-size", "int", "\"big\"", "Nothing was applied")
    }

    @Test
    fun anUnsupportedDocumentVersionSaysWhichBuildIsBehind() {
        assertSays(importFailure("""{"formatVersion":9,"values":{}}"""), "9", "1")
    }

    // Schema

    @Test
    fun anUnpublishedSchemaSaysWhatToGoAndDo() {
        // Swift also names the App Group; Android has none, so M1c rewords that half.
        assertSays(FlagSchemaException.NotPublished(), "publishSchema")
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            FlagSchemaException.NotPublished().message,
        )
    }

    @Test
    fun aMalformedSchemaSaysWhatWasWrongWithIt() {
        assertSays(FlagSchemaException.Malformed("missing formatVersion"), "missing formatVersion")
    }

    // QR codes

    @Test
    fun anOversizedCodeSaysHowFarOverItIs() {
        assertSays(FlagQRCodeException.PayloadTooLarge(3400, 2900, 12), "12", "3400", "2900")
    }

    @Test
    fun anUnrecognisedCodeSaysWhatAFlagCodeLooksLike() {
        assertSays(FlagQRCodeException.UnrecognisedFormat(), "FFQR1:")
    }

    @Test
    fun aCorruptCodeBlamesThePartialScanItUsuallyIs() {
        assertSays(FlagQRCodeException.Corrupt(), "scan")
    }

    // The messages the real paths actually produce

    @Test
    fun aRealRemoteRejectionNamesThePathTheValueAndTheType() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(MessageFlagsSpec).apply("""{"featureToggles":{"onboarding":"yes"}}""".toByteArray())
        }
        assertSays(error, "featureToggles.onboarding", "new-onboarding", "expected bool", "\"yes\"", "Nothing was applied")
    }

    @Test
    fun aRealUnknownCaseListsTheCasesThisBuildHas() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(MessageFlagsSpec).apply("""{"config":{"tier":"gold"}}""".toByteArray())
        }
        assertSays(error, "checkout-tier", "\"gold\"", "free, pro")
    }

    @Test
    fun theDocumentedRejectionReportIsWhatARealPayloadProduces() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(MessageFlagsSpec).apply(
                """{"featureToggles":{"onboarding":"yes"},"config":{"pageSize":[1,2,3],"tier":"gold"}}""".toByteArray(),
            )
        }
        assertEquals(documentedProblems, error.problems)
        assertEquals(
            "Nothing was applied. 3 problems:\n" +
                "  • 'config.tier' → checkout-tier: \"gold\" is not one of free, pro\n" +
                "  • 'featureToggles.onboarding' → new-onboarding: expected bool, got \"yes\"\n" +
                "  • 'config.pageSize' → page-size: expected int, got an array of 3\n" +
                "A remote payload is all or nothing, so one bad value leaves every other flag reading whatever sits below this source.",
            error.message,
        )
    }

    @Test
    fun aRealImportRejectionNamesTheFlagTheValueAndTheType() {
        assertSays(importFailure("""{"formatVersion":1,"values":{"page-size":"big"}}"""), "page-size", "expected int", "\"big\"")
    }

    @Test
    fun aRealRecordRejectionSaysWhichFieldsWereExpected() {
        assertSays(
            importFailure("""{"formatVersion":1,"values":{"endpoints":"nonsense"}}"""),
            "endpoints", "a list of records", "name, port", "\"nonsense\"",
        )
    }

    // Sources and serialisation

    @Test
    fun writingWithNothingWritableSaysWhatToAdd() {
        // Swift names UserDefaultsSource; Android's noun differs, and SnapshotSource is common to both.
        assertSays(FlagException.NoMutableSource(), "SnapshotSource")
        assertEquals(
            "No source in the stack can be written to. Add a SharedPreferencesSource or a SnapshotSource — " +
                "a pole built only from read-only sources has nowhere to put an override.",
            FlagException.NoMutableSource().message,
        )
    }

    @Test
    fun aRefusedValueNamesTheFlag() {
        assertSays(FlagException.UnsupportedValue(FlagKey("page-size")), "page-size")
        assertEquals(
            "The source refused the value for 'page-size' because it cannot represent it. " +
                "The store accepts a narrower set of types than a flag can declare.",
            FlagException.UnsupportedValue(FlagKey("page-size")).message,
        )
    }

    @Test
    fun aNonFiniteNumberNamesTheFlagAndWhyJsonCannotTakeIt() {
        assertSays(FlagSerializationException.NonFiniteNumber(FlagKey("ratio")), "ratio", "JSON")
    }
}
