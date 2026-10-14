package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ports Swift's FlagRecordEditorTests: a row per record, a control per field, and the four verbs
// a list needs. Where Swift reads the result back through a FlagPole, these decode it with the
// host's own record codec.
class FlagRecordEditorTest {

    private val source = SnapshotSource("shared")
    private val store = FlagEditingStore(CompanionSchemas.recordEditor, source, Dispatchers.Unconfined)
    private val endpoints get() = assertNotNull(store.entry(FlagKey("endpoints")))

    // Which flags get the record editor

    @Test
    fun aRecordFlagGetsTheRecordEditor() {
        assertEquals(FlagEditorKind.Records(CompanionSchemas.endpointShape), endpoints.editorKind)
    }

    @Test
    fun aPlainStringFlagIsStillJustText() {
        assertEquals(FlagEditorKind.Text, assertNotNull(store.entry(FlagKey("name"))).editorKind)
    }

    // Reading

    @Test
    fun theDefaultRecordsAreReadableBeforeAnythingIsOverridden() {
        val records = assertNotNull(store.records(endpoints))

        assertEquals(1, records.size)
        assertEquals(FlagValueBox.String("production"), records.first()["name"])
    }

    @Test
    fun textThatIsNotAListOfRecordsReadsAsUnreadableRatherThanEmpty() = runBlocking<Unit> {
        // Empty and unreadable differ: one is a list you emptied, the other a value the host
        // already ignores. Showing "0 records" for the second would invite an add that does nothing.
        store.setValue(endpoints, FlagValueBox.String("{ not json"))

        assertNull(store.records(endpoints))
    }

    @Test
    fun aFlagWithoutAShapeHasNoRecords() {
        assertNull(store.records(assertNotNull(store.entry(FlagKey("name")))))
    }

    // The four verbs

    @Test
    fun addingAppendsARecordOfEmptyFields() = runBlocking<Unit> {
        val records = assertNotNull(store.records(endpoints))

        store.setRecords(endpoints, records + FlagRecordList.emptyRecord(CompanionSchemas.endpointShape))

        val updated = assertNotNull(store.records(endpoints))
        assertEquals(2, updated.size)
        assertEquals(FlagValueBox.String(""), updated.last()["name"])
        assertEquals(FlagValueBox.Int(0), updated.last()["weight"])
    }

    @Test
    fun aNewRecordsEnumFieldStartsOnARealCase() = runBlocking<Unit> {
        // An empty string is not a Tier, so a record added with one would make the host refuse the
        // whole list and fall back to its default.
        store.setRecords(endpoints, listOf(FlagRecordList.emptyRecord(CompanionSchemas.endpointShape)))

        val read = assertNotNull(hostEndpoints(source.box(FlagKey("endpoints"), FlagValueType.String)))
        assertEquals(1, read.size)
        assertEquals(Tier.Primary, read.first().tier)
    }

    @Test
    fun duplicatingPutsTheCopyRightAfterTheOriginal() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records.add(1, records[0])
        store.setRecords(endpoints, records)

        assertEquals(listOf("staging", "staging", "canary"), names())
    }

    @Test
    fun reorderingIsWrittenBackInTheNewOrder() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records.add(0, records.removeAt(1))
        store.setRecords(endpoints, records)

        assertEquals(listOf("canary", "staging"), names())
    }

    @Test
    fun removingLeavesTheRest() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records.removeAt(0)
        store.setRecords(endpoints, records)

        assertEquals(listOf("canary"), names())
    }

    @Test
    fun editingOneFieldLeavesEveryOtherRecordAlone() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records[1] = records[1] + ("weight" to FlagValueBox.Int(99))
        store.setRecords(endpoints, records)

        val updated = assertNotNull(store.records(endpoints))
        assertEquals(staging, updated[0])
        assertEquals(FlagValueBox.Int(99), updated[1]["weight"])
        assertEquals(FlagValueBox.String("canary"), updated[1]["name"])
    }

    // Writing something the host can read

    @Test
    fun whatTheEditorWritesIsWhatTheHostReads() = runBlocking<Unit> {
        // The whole point: the companion has only the shape; the host has the type.
        store.setRecords(endpoints, listOf(staging, canary))

        val read = assertNotNull(hostEndpoints(source.box(FlagKey("endpoints"), FlagValueType.String)))
        assertEquals(listOf("staging", "canary"), read.map { it.name })
        assertEquals(listOf(7L, 3L), read.map { it.weight })
        assertEquals(Tier.Secondary, read.first().tier)
    }

    private fun names(): List<String> =
        assertNotNull(store.records(endpoints)).map { (it["name"] as FlagValueBox.String).value }

    private val staging = mapOf(
        "name" to FlagValueBox.String("staging"),
        "url" to FlagValueBox.Url(URI("https://staging.example")),
        "enabled" to FlagValueBox.Bool(false),
        "weight" to FlagValueBox.Int(7),
        "tier" to FlagValueBox.String("secondary"),
    )

    private val canary = mapOf(
        "name" to FlagValueBox.String("canary"),
        "url" to FlagValueBox.Url(URI("https://canary.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(3),
        "tier" to FlagValueBox.String("primary"),
    )
}
