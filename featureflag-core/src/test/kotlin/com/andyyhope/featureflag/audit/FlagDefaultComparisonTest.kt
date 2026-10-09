package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagDefaultComparisonTest {

    @Test
    fun aRestatedValueSaysItMatches() {
        val comparison = FlagDefaultComparison(FlagKey("page-size"), FlagValueBox.Int(10), FlagValueBox.Int(10))
        assertTrue(comparison.matchesDefault)
        assertEquals("page-size: 10 — matches the default", comparison.toString())
    }

    @Test
    fun aScalarChangeShowsTheDefaultBecomingTheIncomingValue() {
        val comparison = FlagDefaultComparison(FlagKey("ratio"), FlagValueBox.Double(3.0), FlagValueBox.Double(2.5))
        assertFalse(comparison.matchesDefault)
        assertEquals("ratio: 3.0 → 2.5", comparison.toString())
    }

    @Test
    fun scalarValuesUseTheOneLineForm() {
        assertEquals(
            "label: \"a\" → \"line one\\nline two\"",
            FlagDefaultComparison(FlagKey("label"), FlagValueBox.String("a"), FlagValueBox.String("line one\nline two")).toString(),
        )
    }

    @Test
    fun aRecordFlagWithNoRecordChangesWasReordered() {
        val comparison =
            FlagDefaultComparison(FlagKey("endpoints"), FlagValueBox.String("[1]"), FlagValueBox.String("[2]"), emptyList())
        assertEquals("endpoints: records reordered", comparison.toString())
    }

    @Test
    fun aRecordFlagThatMatchesStillSaysSo() {
        val comparison =
            FlagDefaultComparison(FlagKey("endpoints"), FlagValueBox.String("[]"), FlagValueBox.String("[]"), emptyList())
        assertEquals("endpoints: \"[]\" — matches the default", comparison.toString())
    }

    @Test
    fun aRecordFlagListsEachRecordIndentedUnderTheKey() {
        val records = listOf(
            FlagRecordDiff(
                "staging",
                FlagRecordDiff.Change.Changed(
                    listOf(FlagFieldDiff("url", FlagValueBox.Url(URI("https://staging.a")), FlagValueBox.Url(URI("https://staging.b")))),
                ),
            ),
            FlagRecordDiff("prod", FlagRecordDiff.Change.Removed),
        )
        val comparison =
            FlagDefaultComparison(FlagKey("endpoints"), FlagValueBox.String("[1]"), FlagValueBox.String("[2]"), records)
        assertEquals(
            "endpoints:\n  ~ staging:\n      url: \"https://staging.a\" → \"https://staging.b\"\n  - prod",
            comparison.toString(),
        )
    }
}
