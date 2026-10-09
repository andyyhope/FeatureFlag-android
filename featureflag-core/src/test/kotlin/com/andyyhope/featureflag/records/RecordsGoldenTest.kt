package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.golden.GoldenEndpoint
import com.andyyhope.featureflag.golden.GoldenEndpointFlagRecord
import com.andyyhope.featureflag.golden.GoldenTarget
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals

class RecordsGoldenTest {

    private val codec = FlagCodecs.records(GoldenEndpointFlagRecord)

    /** Built identically by `TransportFixtures.recordLists` in the Swift generator. */
    private val lists = mapOf(
        "default" to listOf(GoldenEndpoint("prod", URI("https://prod.example.com"))),
        "empty" to emptyList(),
        "full" to listOf(
            GoldenEndpoint(
                "prod", URI("https://prod.example.com/v1?x=1"), weight = 10, note = "primary \"quoted\" / slash",
                kind = GoldenTier.ENTERPRISE,
                targets = FlagRecords(listOf(GoldenTarget("a.example", 443), GoldenTarget("b.example", 8443))),
            ),
            GoldenEndpoint("staging", URI("https://staging.example.com"), weight = 0, kind = GoldenTier.PRO),
            GoldenEndpoint("ünïcode", URI("https://example.com/%C3%BC"), weight = -3, note = ""),
        ),
    )

    @Test
    fun writesEveryListByteForByteAsSwiftDoes() {
        for ((name, endpoints) in lists) {
            val expected = FlagValueBox.String(Fixtures.text("records/$name.txt"))
            assertEquals(expected, codec.encode(FlagRecords(endpoints)), name)
        }
    }

    @Test
    fun readsEverySwiftListBack() {
        for ((name, endpoints) in lists) {
            val decoded = codec.decode(FlagValueBox.String(Fixtures.text("records/$name.txt")))
            assertEquals<List<GoldenEndpoint>?>(endpoints, decoded, name)
        }
    }
}
