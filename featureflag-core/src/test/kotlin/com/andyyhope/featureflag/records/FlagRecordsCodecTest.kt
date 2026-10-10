package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.golden.GoldenEndpoint
import com.andyyhope.featureflag.golden.GoldenEndpointFlagRecord
import com.andyyhope.featureflag.golden.GoldenTarget
import com.andyyhope.featureflag.golden.GoldenTargetFlagRecord
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** FlagCodecs.records with the hand-written GoldenEndpoint codec. */
class FlagRecordsCodecTest {

    private val codec = FlagCodecs.records(GoldenEndpointFlagRecord)

    @Test
    fun aRecordListIsAStringToAnybodyWhoDoesNotKnowBetter() {
        assertEquals(FlagValueType.String, codec.type)
        assertEquals(GoldenEndpointFlagRecord.shape, codec.recordShape)
        assertNull(codec.cases)
    }

    @Test
    fun theShapeCarriesKeyDefaultsCasesAndNestedFields() {
        val shape = GoldenEndpointFlagRecord.shape.associateBy { it.name }
        assertEquals(
            listOf("name", "url", "weight", "note", "kind", "targets"),
            GoldenEndpointFlagRecord.shape.map { it.name },
        )
        assertEquals(true, shape.getValue("name").isKey)
        assertEquals(false, shape.getValue("url").isKey)
        assertEquals(FlagValueBox.Int(1), shape.getValue("weight").defaultValue)
        assertNull(shape.getValue("name").defaultValue)
        assertEquals("memo", shape.getValue("note").payloadName)
        assertEquals(3, shape.getValue("kind").cases?.size)
        assertEquals(FlagValueBox.String("[]"), shape.getValue("targets").defaultValue)
        assertEquals(listOf("host", "port"), shape.getValue("targets").fields?.map { it.name })
        assertEquals(listOf(FlagValueType.String, FlagValueType.Int), shape.getValue("targets").fields?.map { it.type })
        assertNull(shape.getValue("name").fields)
    }

    @Test
    fun aNestedListRoundTripsThroughTheBox() {
        val targets = FlagRecords(listOf(GoldenTarget("a", 1), GoldenTarget("b", 2)))
        val value = FlagRecords(listOf(GoldenEndpoint("ap", URI("https://ap.example"), targets = targets)))
        assertEquals(value, codec.decode(codec.encode(value)))
        assertEquals(listOf("a", "b"), codec.decode(codec.encode(value))?.single()?.targets?.map { it.host })
    }

    @Test
    fun anOptionalNilRoundTripsThroughTheBox() {
        val value = FlagRecords(listOf(GoldenEndpoint("card", URI("https://card.example"), note = null)))
        assertEquals(value, codec.decode(codec.encode(value)))
    }

    @Test
    fun aWrongNestedRecordFailsTheWholeList() {
        val text = """[{"name":"a","url":"https://a.example","targets":"[{\"host\":\"a\",\"port\":\"https\"}]"}]"""
        assertNull(codec.decode(FlagValueBox.String(text)))
    }

    @Test
    fun recordsBuiltFromBoxesAloneMatchWhatTheTypeWouldHaveWritten() {
        val canary = GoldenEndpoint("canary", URI("https://canary.example"), kind = GoldenTier.PRO)
        val staging = GoldenEndpoint("staging", URI("https://staging.example"))
        val built = FlagValueBox.String(RecordJson.encode(listOf(canary, staging).map(GoldenEndpointFlagRecord::boxes)))
        assertEquals(built, codec.encode(FlagRecords(listOf(canary, staging))))
    }

    @Test
    fun aValueThatIsNotEvenTextIsNotRecords() {
        assertNull(codec.decode(FlagValueBox.Int(3)))
        assertNull(FlagCodecs.records(GoldenTargetFlagRecord).decode(FlagValueBox.String("{ not json")))
    }

    @Test
    fun flagRecordsEqualsAnyListWithTheSameElements() {
        val targets = listOf(GoldenTarget("a", 1))
        assertEquals<List<GoldenTarget>>(targets, FlagRecords(targets))
        assertEquals<List<GoldenTarget>>(FlagRecords(targets), targets)
        assertEquals(targets.hashCode(), FlagRecords(targets).hashCode())
        assertEquals(1, FlagRecords(targets).size)
    }
}
