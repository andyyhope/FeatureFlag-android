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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@FlagRecord
internal data class DiffEndpoint(@FlagRecordKey val name: String, val url: URI, val weight: Int)

@FlagRecord
internal data class DiffPort(val host: String, val port: Int)

@FlagContainer
internal interface RecordDiffFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<DiffEndpoint>
        get() = FlagRecords(
            listOf(DiffEndpoint("staging", URI("https://staging.a"), 1), DiffEndpoint("prod", URI("https://prod.a"), 3)),
        )

    @Flag(description = "Ports", remoteKey = "config.ports")
    val ports: FlagRecords<DiffPort> get() = FlagRecords(listOf(DiffPort("a", 80)))

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10
}

class FlagRecordDiffTest {

    private fun audit(json: String) = FlagMappingAudit(RecordDiffFlagsSpec, json.toByteArray())

    private fun endpoints(audit: FlagMappingAudit): FlagDefaultComparison = audit.defaults.first {
        it.key == FlagKey("endpoints")
    }

    @Test
    fun aChangedFieldIsNamedWithBothValues() {
        val audit =
            audit("""{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.b", "weight": 1 } ] } }""")
        val record = endpoints(audit).records.orEmpty().first { it.identifier == "staging" }
        val fields = assertIs<FlagRecordDiff.Change.Changed>(record.change).fields
        val url = fields.first { it.field == "url" }
        assertEquals(FlagValueBox.Url(URI("https://staging.a")), url.defaultValue)
        assertEquals(FlagValueBox.Url(URI("https://staging.b")), url.incomingValue)
        assertNull(fields.firstOrNull { it.field == "weight" }, "an unchanged field is not listed")
    }

    @Test
    fun aRecordOnlyInTheConfigIsAdded() {
        val audit = audit(
            """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 }, { "name": "canary", "url": "https://canary.a", "weight": 2 } ] } }""",
        )
        assertEquals(FlagRecordDiff.Change.Added, endpoints(audit).records.orEmpty().first { it.identifier == "canary" }.change)
    }

    @Test
    fun aRecordOnlyInTheDefaultIsRemoved() {
        val audit =
            audit("""{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 } ] } }""")
        assertEquals(FlagRecordDiff.Change.Removed, endpoints(audit).records.orEmpty().first { it.identifier == "prod" }.change)
    }

    @Test
    fun aKeylessRecordListPairsByIndex() {
        val ports = audit("""{ "config": { "ports": [ { "host": "a", "port": 443 } ] } }""").defaults.first {
            it.key == FlagKey("ports")
        }
        val record = ports.records.orEmpty().first()
        assertEquals("[0]", record.identifier)
        val fields = assertIs<FlagRecordDiff.Change.Changed>(record.change).fields
        assertEquals(FlagValueBox.Int(443), fields.first { it.field == "port" }.incomingValue)
    }

    @Test
    fun aReorderedListSaysSoRatherThanDumpingTheBlob() {
        val audit = audit(
            """{ "config": { "endpoints": [ { "name": "prod", "url": "https://prod.a", "weight": 3 }, { "name": "staging", "url": "https://staging.a", "weight": 1 } ] } }""",
        )
        assertEquals(emptyList(), endpoints(audit).records, "no record changed, only order")
        assertTrue(FlagKey("endpoints") in audit.changesDefault, "order is a real change")
        assertEquals("Default vs config:\n  changes (1):\n    • endpoints: records reordered", audit.defaultsDescription)
    }

    @Test
    fun aRecordListRestatedExactlyHasNoRecordChanges() {
        val audit = audit(
            """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 }, { "name": "prod", "url": "https://prod.a", "weight": 3 } ] } }""",
        )
        assertEquals(emptyList(), endpoints(audit).records)
        assertTrue(FlagKey("endpoints") in audit.matchesDefault)
    }

    @Test
    fun aScalarFlagHasNoRecordBreakdown() {
        val audit = audit(
            """{ "config": { "pageSize": 99, "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 }, { "name": "prod", "url": "https://prod.a", "weight": 3 } ] } }""",
        )
        assertNull(audit.defaults.first { it.key == FlagKey("page-size") }.records)
    }

    @Test
    fun theDescriptionShowsFieldsNotATruncatedBlob() {
        val text = audit(
            """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.b", "weight": 1 }, { "name": "canary", "url": "https://canary.a", "weight": 9 } ] } }""",
        ).defaultsDescription
        assertEquals(
            """
            Default vs config:
              changes (1):
                • endpoints:
                    ~ staging:
                        url: "https://staging.a" → "https://staging.b"
                    - prod
                    + canary
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("\"url\":"), text)
    }
}
