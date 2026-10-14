package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ports the value-level half of Swift's FlagRecordEditorTests and FlagRecordKeyEditorTests: what
// the editor reads, writes and adds, checked against the same reader the host uses.
class FlagRecordListTest {

    private val now = Instant.parse("2026-01-01T00:00:00.400Z")

    private val tier = FlagRecordField(
        name = "tier",
        type = FlagValueType.String,
        cases = listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")),
    )
    private val endpoint = listOf(
        FlagRecordField("name", FlagValueType.String),
        FlagRecordField("url", FlagValueType.Url),
        FlagRecordField("enabled", FlagValueType.Bool),
        FlagRecordField("weight", FlagValueType.Int),
        tier,
    )
    private val hop = listOf(FlagRecordField("host", FlagValueType.String, isKey = true))
    private val route = listOf(
        FlagRecordField("name", FlagValueType.String, isKey = true),
        FlagRecordField("hops", FlagValueType.String, defaultValue = FlagValueBox.String("[]"), fields = hop),
    )
    private val stamped = listOf(
        FlagRecordField("at", FlagValueType.Date, isKey = true),
        FlagRecordField("note", FlagValueType.String, defaultValue = FlagValueBox.String("")),
    )
    private val note = listOf(FlagRecordField("text", FlagValueType.String))

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

    // Reading

    @Test
    fun textThatIsNotAListOfRecordsReadsAsUnreadableRatherThanEmpty() {
        assertNull(FlagRecordList.decode(FlagValueBox.String("{ not json"), endpoint))
        assertNull(FlagRecordList.decode(FlagValueBox.String("{}"), endpoint))
        assertNull(FlagRecordList.decode(FlagValueBox.String("[{\"name\":\"a\"}]"), endpoint))
        assertNull(FlagRecordList.decode(FlagValueBox.Int(1), endpoint))
        assertEquals(emptyList(), FlagRecordList.decode(FlagValueBox.String("[]"), endpoint))
    }

    @Test
    fun aListWithADuplicateKeyReadsAsUnreadableToTheEditorToo() {
        val box = FlagValueBox.String("[{\"name\":\"a\",\"hops\":\"[]\"},{\"name\":\"a\",\"hops\":\"[]\"}]")

        assertNull(FlagRecordList.decode(box, route))
        assertEquals(FlagValueBox.String("a"), FlagRecordList.duplicateKey(box, route))
    }

    @Test
    fun aDuplicateIsOnlyReportedForAnOtherwiseReadableList() {
        assertNull(FlagRecordList.duplicateKey(FlagValueBox.String("{ not json"), route))
        assertNull(FlagRecordList.duplicateKey(FlagValueBox.String("[{\"name\":\"a\"},{\"name\":\"b\"}]"), route))
        assertNull(FlagRecordList.duplicateKey(FlagValueBox.Bool(true), route))
    }

    // Writing

    @Test
    fun whatTheEditorWritesIsWhatTheHostReads() {
        val written = FlagRecordList.encode(listOf(staging, canary))

        assertEquals(
            "[{\"enabled\":false,\"name\":\"staging\",\"tier\":\"secondary\",\"url\":\"https://staging.example\",\"weight\":7}," +
                "{\"enabled\":true,\"name\":\"canary\",\"tier\":\"primary\",\"url\":\"https://canary.example\",\"weight\":3}]",
            written.value,
        )
        assertEquals(listOf(staging, canary), FlagRecordList.decode(written, endpoint))
        assertEquals(listOf(staging, canary), RecordJson.decode(written.value, endpoint))
    }

    @Test
    fun aRecordHoldingANonFiniteNumberCannotBeWritten() {
        assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            FlagRecordList.encode(listOf(mapOf("ratio" to FlagValueBox.Double(Double.NaN))))
        }
    }

    @Test
    fun aRecordWithNoKeyHasNoDuplicate() {
        val same = mapOf("text" to FlagValueBox.String("same"))

        assertNull(FlagRecordList.duplicateKey(listOf(same, same), note))
    }

    @Test
    fun aDuplicateInTheRowsIsTheFirstKeySeenTwice() {
        val rows = listOf("a", "b", "b", "a").map { mapOf("name" to FlagValueBox.String(it)) }

        assertEquals(FlagValueBox.String("b"), FlagRecordList.duplicateKey(rows, route))
    }

    @Test
    fun aNestedListWithADuplicateKeyIsFoundInItsOwnBox() {
        val hostA = mapOf("host" to FlagValueBox.String("a"))
        val nested = FlagRecordList.encode(listOf(hostA, hostA))
        val outer = listOf(mapOf("name" to FlagValueBox.String("one"), "hops" to nested))

        // Top level only: the outer rows are distinct, and the nested box names its own duplicate.
        assertNull(FlagRecordList.duplicateKey(outer, route))
        assertEquals(FlagValueBox.String("a"), FlagRecordList.duplicateKey(nested, hop))
    }

    // Adding

    @Test
    fun aNewRecordStartsWithEmptyFields() {
        val record = FlagRecordList.emptyRecord(endpoint, now = now)

        assertEquals(FlagValueBox.String(""), record["name"])
        assertEquals(FlagValueBox.Url(URI("https://example.com")), record["url"])
        assertEquals(FlagValueBox.Bool(false), record["enabled"])
        assertEquals(FlagValueBox.Int(0), record["weight"])
        assertEquals(listOf("name", "url", "enabled", "weight", "tier"), record.keys.toList())
    }

    @Test
    fun aNewRecordsEnumFieldStartsOnARealCase() {
        val record = FlagRecordList.emptyRecord(endpoint, now = now)

        assertEquals(FlagValueBox.String("primary"), record["tier"])
        val read = assertNotNull(FlagRecordList.decode(FlagRecordList.encode(listOf(record)), endpoint))
        assertEquals(FlagValueBox.String("primary"), read.single()["tier"])
    }

    @Test
    fun aFieldsDefaultWinsOverItsCases() {
        val withDefault = listOf(tier.copy(defaultValue = FlagValueBox.String("secondary")))

        assertEquals(FlagValueBox.String("secondary"), FlagRecordList.emptyRecord(withDefault, now = now)["tier"])
    }

    @Test
    fun addingTwiceMakesTwoRecordsTheHostCanRead() {
        val first = FlagRecordList.emptyRecord(route, now = now)
        val second = FlagRecordList.emptyRecord(route, alongside = listOf(first), now = now)

        assertEquals(FlagValueBox.String(""), first["name"])
        assertEquals(FlagValueBox.String("2"), second["name"])
        assertEquals(2, FlagRecordList.decode(FlagRecordList.encode(listOf(first, second)), route)?.size)
    }

    @Test
    fun aThirdAddIsStillDistinct() {
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(route, alongside = records, now = now) }

        assertEquals(listOf("", "2", "3"), records.map { (it["name"] as FlagValueBox.String).value })
        assertEquals(3, FlagRecordList.decode(FlagRecordList.encode(records), route)?.size)
    }

    @Test
    fun twoAddsOnADateKeyStayDistinctOnceStored() {
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(stamped, alongside = records, now = now) }

        assertEquals(
            listOf(now, now.plusSeconds(1), now.plusSeconds(2)).map { FlagValueBox.Date(it) },
            records.map { it["at"] },
        )
        assertEquals(3, FlagRecordList.decode(FlagRecordList.encode(records), stamped)?.size)
    }

    @Test
    fun aDateTakenWithinTheSameSecondCountsAsTaken() {
        // Different in memory, the same once stored: the stored form keeps whole seconds.
        val taken = listOf(mapOf("at" to FlagValueBox.Date(Instant.parse("2026-01-01T00:00:00.900Z"))))

        assertEquals(FlagValueBox.Date(now.plusSeconds(1)), FlagRecordList.emptyRecord(stamped, alongside = taken, now = now)["at"])
    }

    @Test
    fun suffixesFollowTheKeysType() {
        val counter = FlagRecordField("id", FlagValueType.Int, isKey = true)
        val link = FlagRecordField("url", FlagValueType.Url, isKey = true)
        val named = FlagRecordField(
            name = "name",
            type = FlagValueType.String,
            defaultValue = FlagValueBox.String("new"),
            isKey = true,
        )

        assertEquals(FlagValueBox.Int(1), suffixed(counter, FlagValueBox.Int(0)))
        assertEquals(FlagValueBox.Url(URI("https://example.com-2")), suffixed(link, FlagValueBox.Url(URI("https://example.com"))))
        assertEquals(FlagValueBox.String("new 2"), suffixed(named, FlagValueBox.String("new")))
    }

    @Test
    fun theFirstFreeSuffixWins() {
        val taken = listOf("", "3").map { mapOf("name" to FlagValueBox.String(it)) }

        assertEquals(FlagValueBox.String("2"), FlagRecordList.emptyRecord(route, alongside = taken, now = now)["name"])
    }

    @Test
    fun aKeyThatTakesNoSuffixKeepsItsCollidingValue() {
        val flag = listOf(FlagRecordField("on", FlagValueType.Bool, isKey = true))
        val taken = listOf(mapOf("on" to FlagValueBox.Bool(false)))

        assertEquals(FlagValueBox.Bool(false), FlagRecordList.emptyRecord(flag, alongside = taken, now = now)["on"])
    }

    /** What a new record's key becomes beside one record already holding [taken]. */
    private fun suffixed(key: FlagRecordField, taken: FlagValueBox): FlagValueBox? =
        FlagRecordList.emptyRecord(listOf(key), alongside = listOf(mapOf(key.name to taken)), now = now)[key.name]
}
