package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.golden.GoldenEndpoint
import com.andyyhope.featureflag.golden.GoldenEndpointFlagRecord
import com.andyyhope.featureflag.golden.GoldenTarget
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.net.URI
import java.time.Instant

/**
 * The override set behind `fixtures/export/golden.json` and `fixtures/qr/golden.*`.
 *
 * It must stay identical to `TransportFixtures.goldenPayload` in the Swift generator, as
 * `RecordsGoldenTest` stays identical to `recordLists`.
 */
internal object GoldenOverrides {

    val exportedAt: Instant = Instant.ofEpochSecond(1_767_260_460)

    /** `TransportFixtures.recordLists`' "full" list. */
    val endpoints: FlagRecords<GoldenEndpoint> = FlagRecords(
        listOf(
            GoldenEndpoint(
                "prod",
                URI("https://prod.example.com/v1?x=1"),
                weight = 10,
                note = "primary \"quoted\" / slash",
                kind = GoldenTier.ENTERPRISE,
                targets = FlagRecords(listOf(GoldenTarget("a.example", 443), GoldenTarget("b.example", 8443))),
            ),
            GoldenEndpoint("staging", URI("https://staging.example.com"), weight = 0, kind = GoldenTier.PRO),
            GoldenEndpoint("\u00fcn\u00efcode", URI("https://example.com/%C3%BC"), weight = -3, note = ""),
        ),
    )

    val values: Map<FlagKey, FlagValueBox> = linkedMapOf(
        FlagKey("bool-flag") to FlagValueBox.Bool(true),
        FlagKey("int-flag") to FlagValueBox.Int(-7),
        FlagKey("double-flag") to FlagValueBox.Double(0.30000000000000004),
        FlagKey("float-flag") to FlagValueBox.Float(1.1f),
        FlagKey("string-flag") to FlagValueBox.String("line1\nline2\t\"q\" / \u00e9 \ud83d\ude00"),
        FlagKey("data-flag") to FlagValueBox.Data(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())),
        FlagKey("date-flag") to FlagValueBox.Date(Instant.ofEpochMilli(1_767_260_460_750)),
        FlagKey("url-flag") to FlagValueBox.Url(URI("https://example.com/path?q=1&r=a%20b#frag")),
        FlagKey("use-https-only") to FlagValueBox.Bool(true),
        FlagKey("markets") to FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"), FlagValueBox.String("US"))),
        FlagKey("matrix") to FlagValueBox.Array(listOf(FlagValueBox.Array(emptyList()), FlagValueBox.Array(listOf(FlagValueBox.Bool(true))))),
        FlagKey("limits") to FlagValueBox.Dictionary(
            mapOf("z" to FlagValueBox.Int(26), "a10" to FlagValueBox.Int(10), "a9" to FlagValueBox.Int(9), "A" to FlagValueBox.Int(1)),
        ),
        FlagKey("tier") to FlagValueBox.String("enterprise"),
        FlagKey("level") to FlagValueBox.Int(3),
        FlagKey("endpoints") to FlagCodecs.records(GoldenEndpointFlagRecord).encode(endpoints),
        FlagKey("checkout.apple-pay") to FlagValueBox.Bool(false),
        FlagKey("checkout.tier") to FlagValueBox.String("free"),
        FlagKey("checkout.express.one-tap") to FlagValueBox.Bool(true),
        FlagKey("checkout.express.confirmation-delay") to FlagValueBox.Double(0.5),
    )

    /** What reading the export back yields: the document carries the date to the second. */
    val decodedValues: Map<FlagKey, FlagValueBox> =
        values + (FlagKey("date-flag") to FlagValueBox.Date(Instant.ofEpochSecond(1_767_260_460)))
}
