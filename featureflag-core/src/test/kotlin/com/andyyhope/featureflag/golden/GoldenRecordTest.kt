package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The golden record codecs, hand-written in B2 and generated from B4: both must give these answers. */
class GoldenRecordTest {
    private val tierCases = listOf("free", "pro", "enterprise").map { FlagValueBox.String(it) }

    /** An empty record list, as Swift stores it. */
    private val emptyRecordList = FlagValueBox.String("[]")

    @Test fun theEndpointShapeCarriesKeysDefaultsOptionalsAndNestedFields() {
        val targets = listOf(
            FlagRecordField("host", FlagValueType.String),
            FlagRecordField("port", FlagValueType.Int),
        )
        assertEquals(targets, GoldenTargetFlagRecord.shape)
        assertEquals(
            listOf(
                FlagRecordField("name", FlagValueType.String, isKey = true),
                FlagRecordField("url", FlagValueType.Url),
                FlagRecordField("weight", FlagValueType.Int, defaultValue = FlagValueBox.Int(1)),
                FlagRecordField("note", FlagValueType.String, isOptional = true, decodedName = "memo"),
                FlagRecordField("kind", FlagValueType.String, tierCases, defaultValue = FlagValueBox.String("free")),
                FlagRecordField("targets", FlagValueType.String, defaultValue = emptyRecordList, fields = targets),
            ),
            GoldenEndpointFlagRecord.shape,
        )
        assertEquals("name", GoldenEndpointFlagRecord.keyField)
        assertNull(GoldenTargetFlagRecord.keyField)
    }

    @Test fun aNilOptionalIsLeftOutAndAMissingOneReadsAsNil() {
        val endpoint = GoldenEndpoint(name = "prod", url = URI("https://prod.example.com"))
        val boxes = GoldenEndpointFlagRecord.boxes(endpoint)
        assertEquals(setOf("name", "url", "weight", "kind", "targets"), boxes.keys)
        val noted = GoldenEndpointFlagRecord.boxes(endpoint.copy(note = "memo text"))
        assertEquals(FlagValueBox.String("memo text"), noted["note"])
        assertNull(GoldenEndpointFlagRecord.fromBoxes(boxes - "url"))
    }

    @Test fun aTargetRoundTripsThroughItsBoxes() {
        val target = GoldenTarget("example.com", 443)
        assertEquals(target, GoldenTargetFlagRecord.fromBoxes(GoldenTargetFlagRecord.boxes(target)))
    }

    @Test fun recordsAreFoundByTheirKey() {
        val endpoints = FlagRecords(
            listOf(
                GoldenEndpoint("prod", URI("https://prod.example.com")),
                GoldenEndpoint("staging", URI("https://staging.example.com")),
            ),
        )
        assertEquals(URI("https://staging.example.com"), endpoints["staging"]?.url)
        assertNull(endpoints["missing"])
        assertEquals("prod", endpoints[0].name)
    }
}
