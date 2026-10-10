package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@FlagRecord
internal data class DefaultDiffEndpoint(@FlagRecordKey val name: String, val url: URI)

@FlagContainer
internal interface RecordDefaultDiffFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<DefaultDiffEndpoint>
        get() = FlagRecords(listOf(DefaultDiffEndpoint("a", URI("https://a.example"))))
}

@FlagContainer
internal interface DiffFlags {
    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Ratio", remoteKey = "config.ratio")
    val ratio: Double get() = 3.0
}

class FlagDefaultDiffTest {

    private fun audit(json: String) = FlagMappingAudit(DiffFlagsSpec, json.toByteArray())

    private fun keys(vararg raw: String): List<FlagKey> = raw.map { FlagKey(it) }

    @Test
    fun aValueEqualToTheDefaultIsAMatch() {
        val audit = audit("""{ "featureToggles": { "onboarding": false }, "config": { "pageSize": 99 } }""")
        assertEquals(keys("new-onboarding"), audit.matchesDefault)
        assertEquals(keys("page-size"), audit.changesDefault)
    }

    @Test
    fun everyComparisonCarriesBothValues() {
        val diff = audit("""{ "config": { "pageSize": 99 } }""").defaults.first { it.key == FlagKey("page-size") }
        assertEquals(FlagValueBox.Int(10), diff.defaultValue)
        assertEquals(FlagValueBox.Int(99), diff.incomingValue)
        assertFalse(diff.matchesDefault)
    }

    @Test
    fun onlyFlagsTheConfigSuppliedAppearInTheDiff() {
        assertEquals(keys("page-size"), audit("""{ "config": { "pageSize": 99 } }""").defaults.map { it.key })
    }

    @Test
    fun aValueMatchingTheDefaultDoesNotFailTheAudit() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": false }, "config": { "pageSize": 10, "ratio": 3.0 } }""")
        assertEquals(keys("new-onboarding", "page-size", "ratio"), audit.matchesDefault)
        assertEquals(emptyList(), audit.changesDefault)
        assertTrue(audit.isComplete, "restating a default is not a coverage failure")
    }

    @Test
    fun aWholeNumberSentForADoubleStillMatchesADoubleDefault() {
        assertEquals(keys("ratio"), audit("""{ "config": { "ratio": 3 } }""").matchesDefault)
    }

    @Test
    fun aRecordListRestatedExactlyMatchesItsDefault() {
        val audit = FlagMappingAudit(
            RecordDefaultDiffFlagsSpec,
            """{"config":{"endpoints":[{"name":"a","url":"https://a.example"}]}}""".toByteArray(),
        )
        assertEquals(keys("endpoints"), audit.matchesDefault)
    }

    @Test
    fun recordFieldOrderInTheConfigDoesNotCreateAFalseChange() {
        val audit = FlagMappingAudit(
            RecordDefaultDiffFlagsSpec,
            """{"config":{"endpoints":[{"url":"https://a.example","name":"a"}]}}""".toByteArray(),
        )
        assertEquals(emptyList(), audit.changesDefault)
    }

    @Test
    fun theDiffDescriptionShowsChangesAndMatches() {
        val text = audit("""{ "featureToggles": { "onboarding": false }, "config": { "pageSize": 99, "ratio": 2.5 } }""").defaultsDescription
        assertEquals(
            """
            Default vs config:
              changes (2):
                • page-size: 10 → 99
                • ratio: 3.0 → 2.5
              restated (1) — same as the default, could be omitted:
                • new-onboarding
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aConfigThatRestatesEverythingSaysSo() {
        assertEquals(
            """
            Default vs config:
              every supplied value matches the compiled default.
              restated (1) — same as the default, could be omitted:
                • page-size
            """.trimIndent(),
            audit("""{ "config": { "pageSize": 10 } }""").defaultsDescription,
        )
    }

    @Test
    fun theDiffOfAConfigThatChangesNothingSaysSo() {
        val audit = audit("{}")
        assertEquals(emptyList(), audit.defaults)
        assertEquals("This config changes nothing — it supplies no value for any flag.", audit.defaultsDescription)
    }
}
