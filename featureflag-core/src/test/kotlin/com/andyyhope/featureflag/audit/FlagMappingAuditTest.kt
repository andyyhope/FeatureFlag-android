package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal enum class AuditTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

@FlagContainer
internal interface AuditFlags {
    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Tier", remoteKey = "config.tier")
    val checkoutTier: AuditTier get() = AuditTier.Free

    @Flag(description = "Not remotely overridable")
    val localOnly: String get() = "x"
}

@FlagRecord
internal data class AuditEndpoint(@FlagRecordKey val name: String, val url: URI)

@FlagContainer
internal interface RecordAuditFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<AuditEndpoint> get() = FlagRecords()
}

class FlagMappingAuditTest {

    private val complete = """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" } }"""

    private fun audit(json: String) = FlagMappingAudit(AuditFlagsSpec, json.toByteArray())

    private fun keys(vararg raw: String): List<FlagKey> = raw.map { FlagKey(it) }

    // Forward coverage

    @Test
    fun aCompletePayloadHasNothingAbsentAndIsComplete() {
        val audit = audit(complete)
        assertEquals(emptyList(), audit.absent)
        assertEquals(emptyList(), audit.mismatched)
        assertEquals(keys("checkout-tier", "new-onboarding", "page-size"), audit.applied)
        assertTrue(audit.isComplete)
    }

    @Test
    fun aTypoInARemoteKeyShowsAsAnAbsentFlag() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""")
        assertEquals(keys("page-size"), audit.absent)
        assertFalse(audit.isComplete)
    }

    @Test
    fun aFlatDottedMemberIsAbsentYetNotReportedAsUnconsumed() {
        // Review Focus 1: the member's name equals the remoteKey, so it is "claimed", yet no path reaches it.
        val audit = audit("""{ "featureToggles.onboarding": true, "config": { "pageSize": 25, "tier": "pro" } }""")
        assertEquals(keys("new-onboarding"), audit.absent)
        assertEquals(emptyList(), audit.unconsumed)
    }

    // Reverse coverage

    @Test
    fun aValueNoFlagReadsIsListedAsUnconsumed() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }""")
        assertEquals(listOf("meta.version"), audit.unconsumed)
    }

    @Test
    fun unconsumedValuesDoNotFailTheDefaultAudit() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }""")
        assertTrue(audit.isComplete)
        assertFalse(audit.isComplete(strict = true))
        assertTrue(audit.isComplete(strict = true, ignoring = listOf("meta")))
    }

    @Test
    fun anIgnoredPrefixMatchesWholePathSegmentsOnly() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }""")
        assertEquals(listOf("meta.version"), audit.unconsumed(ignoring = listOf("met")))
        assertEquals(emptyList(), audit.unconsumed(ignoring = listOf("meta.version")))
    }

    @Test
    fun strictModeCatchesATypoThatLenientCoverageMisses() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""")
        assertEquals(keys("page-size"), audit.absent)
        assertEquals(listOf("config.page_size"), audit.unconsumed)
    }

    @Test
    fun aRecordFlagCoversEveryLeafBeneathItsPath() {
        val audit = FlagMappingAudit(
            RecordAuditFlagsSpec,
            """{ "config": { "endpoints": [ { "name": "a", "url": "https://a.example" }, { "name": "b", "url": "https://b.example" } ] } }""".toByteArray(),
        )
        assertEquals(emptyList(), audit.unconsumed, "the record flag reads the whole list")
        assertEquals(keys("endpoints"), audit.applied)
    }

    // Mismatches are collected, not thrown

    @Test
    fun everyTypeMismatchIsReportedAtOnce() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": "yes" }, "config": { "pageSize": "lots", "tier": "pro" } }""")
        assertEquals(setOf(FlagKey("new-onboarding"), FlagKey("page-size")), audit.mismatched.map { it.key }.toSet())
        assertFalse(audit.isComplete)
    }

    @Test
    fun anEnumCaseThisBuildLacksIsAMismatch() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "gold" } }""")
        assertEquals(keys("checkout-tier"), audit.mismatched.map { it.key })
        assertEquals(Kind.UnknownCase, audit.mismatched.first().kind)
    }

    // Flags with no remoteKey

    @Test
    fun aFlagWithNoRemoteKeyIsNeitherAppliedNorAbsent() {
        val audit = audit(complete)
        assertEquals(keys("local-only"), audit.notRemotelyOverridable)
        assertFalse(FlagKey("local-only") in audit.applied)
        assertFalse(FlagKey("local-only") in audit.absent)
    }

    // Reporting

    @Test
    fun theDescriptionNamesEveryProblem() {
        val text = audit("""{ "config": { "page_size": 25, "tier": "gold" }, "meta": { "version": 7 } }""").toString()
        assertEquals(
            """
            Flag mapping audit — incomplete.
              absent (2) — a flag declares this path, the payload has nothing there:
                • new-onboarding
                • page-size
              mismatched (1):
                • 'config.tier' → checkout-tier: "gold" is not one of free, pro
              unconsumed (2) — usually fine, often backend metadata — no flag reads these:
                • config.page_size
                • meta.version
              0 of 3 remotely-overridable flags applied.
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("com.andyyhope"), text)
    }

    @Test
    fun aStrictDescriptionDropsTheNoteAndHonoursIgnoredPrefixes() {
        val audit = audit(
            """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 }, "extra": 1 }""",
        )
        assertEquals(
            """
            Flag mapping audit — incomplete.
              unconsumed (1) — no flag reads these:
                • extra
              3 of 3 remotely-overridable flags applied.
            """.trimIndent(),
            audit.description(strict = true, ignoring = listOf("meta")),
        )
        assertEquals(
            """
            Flag mapping audit — complete.
              unconsumed (1) — usually fine, often backend metadata — no flag reads these:
                • extra
              3 of 3 remotely-overridable flags applied.
            """.trimIndent(),
            audit.description(strict = false, ignoring = listOf("meta")),
        )
    }

    @Test
    fun requireCompleteThrowsTheReportWhenIncomplete() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""")
        val error = assertFailsWith<FlagMappingAuditException.Incomplete> { audit.requireComplete() }
        assertEquals(audit.toString(), error.message)
        assertEquals(audit.toString(), error.report)
    }

    @Test
    fun requireCompleteIsSilentWhenComplete() {
        audit(complete).requireComplete()
    }

    // Degenerate payloads

    @Test
    fun anEmptyPayloadReportsNothingUnconsumedNotABlankPath() {
        val audit = audit("{}")
        assertEquals(emptyList(), audit.unconsumed)
        assertFalse(audit.toString().contains("• \n"), audit.toString())
    }

    @Test
    fun aRootLevelArrayDoesNotProduceLeadingDotPaths() {
        assertEquals(listOf("0", "1"), audit("[1, 2]").unconsumed)
    }

    @Test
    fun aNestedEmptyContainerIsStillNamed() {
        assertEquals(listOf("meta"), audit("""{"meta": {}}""").unconsumed)
    }

    // Bad input

    @Test
    fun malformedJsonThrowsRatherThanAuditing() {
        assertFailsWith<RemoteOverrideException.Malformed> { audit("{ not json") }
    }
}
