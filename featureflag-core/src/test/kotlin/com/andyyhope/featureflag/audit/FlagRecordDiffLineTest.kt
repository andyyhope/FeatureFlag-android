package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlagRecordDiffLineTest {

    private val payShape = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String, isKey = true),
        FlagRecordField(name = "applePay", type = FlagValueType.Bool, decodedName = "apple_pay"),
        FlagRecordField(name = "minimumSpend", type = FlagValueType.Double, isOptional = true),
    )

    private val endpointShape = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String, isKey = true),
        FlagRecordField(name = "url", type = FlagValueType.Url),
    )

    private val portShape = listOf(
        FlagRecordField(name = "host", type = FlagValueType.String),
        FlagRecordField(name = "port", type = FlagValueType.Int),
    )

    private fun card(minimumSpend: Double? = null): Map<String, FlagValueBox> = buildMap {
        put("name", FlagValueBox.String("card"))
        put("applePay", FlagValueBox.Bool(false))
        if (minimumSpend != null) put("minimumSpend", FlagValueBox.Double(minimumSpend))
    }

    private fun endpoint(name: String, url: String): Map<String, FlagValueBox> =
        mapOf("name" to FlagValueBox.String(name), "url" to FlagValueBox.Url(URI(url)))

    private fun port(host: String, number: Long): Map<String, FlagValueBox> =
        mapOf("host" to FlagValueBox.String(host), "port" to FlagValueBox.Int(number))

    @Test
    fun aDiffShowsAnOptionalFieldGainingAValue() {
        val diff = FlagRecordDiff.diff(listOf(card()), listOf(card(minimumSpend = 10.0)), payShape).single()

        val changed = assertIs<FlagRecordDiff.Change.Changed>(diff.change)
        assertEquals(listOf(FlagFieldDiff("minimumSpend", null, FlagValueBox.Double(10.0))), changed.fields)
        assertEquals("~ card:\n    minimumSpend: unset → 10.0", diff.line)
    }

    @Test
    fun keyedRecordsAreRemovedOrChangedInTheDefaultsOrderThenAddedInTheConfigsOrder() {
        val diffs = FlagRecordDiff.diff(
            listOf(endpoint("staging", "https://staging.a"), endpoint("prod", "https://prod.a")),
            listOf(endpoint("canary", "https://canary.a"), endpoint("staging", "https://staging.b"), endpoint("edge", "https://edge.a")),
            endpointShape,
        )
        assertEquals(
            listOf("~ staging:\n    url: \"https://staging.a\" → \"https://staging.b\"", "- prod", "+ canary", "+ edge"),
            diffs.map { it.line },
        )
    }

    @Test
    fun anUnchangedOrMerelyReorderedKeyedListHasNoDiffs() {
        val a = endpoint("a", "https://a.example")
        val b = endpoint("b", "https://b.example")
        assertEquals(emptyList(), FlagRecordDiff.diff(listOf(a, b), listOf(a, b), endpointShape))
        assertEquals(emptyList(), FlagRecordDiff.diff(listOf(a, b), listOf(b, a), endpointShape))
    }

    @Test
    fun keylessRecordsPairByPosition() {
        assertEquals(
            listOf("~ [0]:\n    port: 80 → 443", "+ [1]"),
            FlagRecordDiff.diff(listOf(port("a", 80)), listOf(port("a", 443), port("b", 80)), portShape).map { it.line },
        )
        assertEquals(listOf("- [0]"), FlagRecordDiff.diff(listOf(port("a", 80)), emptyList(), portShape).map { it.line })
    }

    @Test
    fun aRecordIsNamedByItsKeyBareOrByItsDiffTextOrByAQuestionMark() {
        val idShape = listOf(FlagRecordField(name = "id", type = FlagValueType.Int, isKey = true))
        val added = FlagRecordDiff.diff(emptyList(), listOf(mapOf("id" to FlagValueBox.Int(7))), idShape)
        assertEquals(listOf("+ 7"), added.map { it.line })
        assertEquals(listOf("+ ?"), FlagRecordDiff.diff(emptyList(), listOf(emptyMap()), idShape).map { it.line })
    }
}
