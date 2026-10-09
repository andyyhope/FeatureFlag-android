package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenLevel
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals

/** `fixtures/export/golden.json` is FeatureFlag 0.12.0's export of [GoldenOverrides]. */
class ExportGoldenTest {

    private val path = "export/golden.json"

    @Test
    fun theAppleExportDecodesAndReEncodesByteForByte() {
        val bytes = Fixtures.bytes(path)

        assertEquals(bytes.decodeToString(), FlagPayload.decode(bytes, goldenSchema).jsonBytes().decodeToString())
    }

    @Test
    fun theAppleExportCarriesTheDocumentedOverrideSet() {
        val decoded = FlagPayload.decode(Fixtures.bytes(path), goldenSchema)

        assertEquals(GoldenOverrides.exportedAt, decoded.exportedAt)
        assertEquals(GoldenOverrides.decodedValues, decoded.values)
    }

    @Test
    fun kotlinExportsTheOverrideSetByteForByte() {
        // Includes a date with a fractional second, which the export floors as iOS does.
        val payload = FlagPayload(GoldenOverrides.values, GoldenOverrides.exportedAt)

        assertEquals(Fixtures.text(path), payload.jsonBytes().decodeToString())
    }

    @Test
    fun aPoleExportsTheSameDocument() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))
        GoldenOverrides.values.forEach { (key, box) -> pole.setOverride(key, box) }

        val exported = pole.exportPayload().copy(exportedAt = GoldenOverrides.exportedAt)

        assertEquals(Fixtures.text(path), exported.jsonBytes().decodeToString())
    }

    @Test
    fun typedOverridesBoxTheSameWayAsTheGoldenSet() {
        // int-flag and limits are left out: whether they are Int or Long in Kotlin is M1b's choice.
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        pole.setOverride(GoldenFlagsSpec.doubleFlag, 0.30000000000000004)
        pole.setOverride(GoldenFlagsSpec.floatFlag, 1.1f)
        pole.setOverride(GoldenFlagsSpec.stringFlag, "line1\nline2\t\"q\" / \u00e9 \ud83d\ude00")
        pole.setOverride(GoldenFlagsSpec.dataFlag, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        pole.setOverride(GoldenFlagsSpec.dateFlag, Instant.ofEpochMilli(1_767_260_460_750))
        pole.setOverride(GoldenFlagsSpec.urlFlag, URI("https://example.com/path?q=1&r=a%20b#frag"))
        pole.setOverride(GoldenFlagsSpec.useHTTPSOnly, true)
        pole.setOverride(GoldenFlagsSpec.markets, listOf("AU", "NZ", "US"))
        pole.setOverride(GoldenFlagsSpec.matrix, listOf(emptyList(), listOf(true)))
        pole.setOverride(GoldenFlagsSpec.tier, GoldenTier.ENTERPRISE)
        pole.setOverride(GoldenFlagsSpec.level, GoldenLevel.THREE)
        pole.setOverride(GoldenFlagsSpec.endpoints, GoldenOverrides.endpoints)
        pole.setOverride(GoldenFlagsSpec.checkout.applePay, false)
        pole.setOverride(GoldenFlagsSpec.checkout.tier, GoldenTier.FREE)
        pole.setOverride(GoldenFlagsSpec.checkout.express.oneTap, true)
        pole.setOverride(GoldenFlagsSpec.checkout.express.confirmationDelay, 0.5)

        val expected: Map<FlagKey, FlagValueBox> = GoldenOverrides.values - FlagKey("int-flag") - FlagKey("limits")
        assertEquals(expected, pole.overrides)
    }
}
