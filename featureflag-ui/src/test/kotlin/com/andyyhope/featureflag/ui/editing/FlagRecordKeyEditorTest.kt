package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagRecordKeyEditorTests: the companion cannot write a list the host will refuse,
// so a key the host enforces is enforced here too, including by "Add" pressed twice.
class FlagRecordKeyEditorTest {

    private val source = SnapshotSource("shared")
    private val store = FlagEditingStore(CompanionSchemas.keyedEditor, source, Dispatchers.Unconfined)
    private val routes get() = entry("routes")

    @Test
    fun addingTwiceMakesTwoRecordsTheHostCanRead() = runBlocking<Unit> {
        store.setRecords(routes, listOf(FlagRecordList.emptyRecord(CompanionSchemas.routeShape)))
        val records = assertNotNull(store.records(routes)).toMutableList()

        records += FlagRecordList.emptyRecord(CompanionSchemas.routeShape, alongside = records)
        store.setRecords(routes, records)

        val read = assertNotNull(hostRead("routes", CompanionSchemas.routeShape), "the host refused the list")
        assertEquals(2, read.size)
        assertEquals(2, read.map { it["name"] }.toSet().size, "both records share a name")
    }

    @Test
    fun aThirdAddIsStillDistinct() = runBlocking<Unit> {
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(CompanionSchemas.routeShape, alongside = records) }

        store.setRecords(routes, records)

        assertEquals(3, hostRead("routes", CompanionSchemas.routeShape)?.size)
    }

    @Test
    fun anEditThatWouldCollideIsRefused() = runBlocking<Unit> {
        store.setRecords(routes, listOf(named("a"), named("b")))
        val records = assertNotNull(store.records(routes)).toMutableList()

        records[1] = records[1] + ("name" to FlagValueBox.String("a"))
        val refusal = assertFailsWith<FlagRecordEditingException> { store.setRecords(routes, records) }

        assertEquals("name", refusal.field)
        assertEquals(FlagValueBox.String("a"), refusal.value)
        assertEquals(listOf("a", "b"), names(), "the refused write should have changed nothing")
    }

    @Test
    fun aRefusedRecordWriteStillBumpsTheRevision() = runBlocking<Unit> {
        store.setRecords(routes, listOf(named("a"), named("b")))
        val stored = store.value(routes)
        val before = store.revision.value

        assertFailsWith<FlagRecordEditingException> { store.setRecords(routes, listOf(named("a"), named("a"))) }

        assertTrue(store.revision.value > before, "a refused edit should still bump the revision")
        assertEquals(stored, store.value(routes))
    }

    @Test
    fun theRefusalSaysWhichFieldTellsRecordsApart() {
        assertEquals(
            "Another record already has that name. It is what tells one record from another, so two cannot share it.",
            FlagRecordEditingException("name", FlagValueBox.String("a")).message,
        )
    }

    @Test
    fun anEditThatDoesNotCollideIsAccepted() = runBlocking<Unit> {
        store.setRecords(routes, listOf(named("a"), named("b")))
        val records = assertNotNull(store.records(routes)).toMutableList()

        records[1] = records[1] + ("name" to FlagValueBox.String("c"))
        store.setRecords(routes, records)

        assertEquals(listOf("a", "c"), names())
    }

    @Test
    fun aRecordWithNoKeyIsUnaffected() = runBlocking<Unit> {
        val same = mapOf("text" to FlagValueBox.String("same"))

        store.setRecords(entry("notes"), listOf(same, same))

        assertEquals(listOf(same, same), store.records(entry("notes")))
    }

    @Test
    fun aListWithADuplicateKeyReadsAsUnreadableToTheEditorToo() = runBlocking<Unit> {
        // The host refuses it, so the editor must agree, and must say which rule was broken.
        store.setValue(routes, FlagValueBox.String("[{\"name\":\"a\",\"hops\":\"[]\"},{\"name\":\"a\",\"hops\":\"[]\"}]"))

        assertNull(store.records(routes))
        assertEquals(FlagValueBox.String("a"), FlagRecordList.duplicateKey(store.value(routes), assertNotNull(routes.recordShape)))
    }

    @Test
    fun twoAddsOnADateKeyStayDistinctOnceStored() = runBlocking<Unit> {
        // Dates are held to the nanosecond and stored to the second, so records added within one
        // second must still differ once stored.
        val stamped = entry("stamped")
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(CompanionSchemas.stampedShape, alongside = records) }

        store.setRecords(stamped, records)

        assertEquals(3, hostRead("stamped", CompanionSchemas.stampedShape)?.size, "the host refused the list")
    }

    @Test
    fun aNestedListWithADuplicateKeyIsRefused() = runBlocking<Unit> {
        // The store must not write what the host will not read at any depth: a repeated key in a
        // nested list makes the whole outer flag unreadable.
        val nested = FlagRecordList.encode(listOf(hop("a"), hop("a")))

        val refusal = assertFailsWith<FlagRecordEditingException> {
            store.setRecords(routes, listOf(mapOf("name" to FlagValueBox.String("one"), "hops" to nested)))
        }

        assertEquals("host", refusal.field)
        assertNull(source.box(FlagKey("routes"), FlagValueType.String))
    }

    @Test
    fun aNestedListWithDistinctKeysIsFine() = runBlocking<Unit> {
        val nested = FlagRecordList.encode(listOf(hop("a"), hop("b")))

        store.setRecords(routes, listOf(mapOf("name" to FlagValueBox.String("one"), "hops" to nested)))

        assertEquals(1, hostRead("routes", CompanionSchemas.routeShape)?.size)
    }

    private fun entry(key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun hostRead(key: String, shape: List<FlagRecordField>) =
        hostRecords(source.box(FlagKey(key), FlagValueType.String), shape)

    private fun names(): List<String> =
        assertNotNull(store.records(routes)).map { (it["name"] as FlagValueBox.String).value }

    private fun named(name: String) = mapOf("name" to FlagValueBox.String(name), "hops" to FlagValueBox.String("[]"))

    private fun hop(host: String) = mapOf("host" to FlagValueBox.String(host))
}
