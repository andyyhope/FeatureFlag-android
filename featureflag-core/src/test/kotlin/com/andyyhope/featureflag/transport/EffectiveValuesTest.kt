package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

// A published schema carries what each flag reads beneath the overrides, so a companion shows the value the
// app actually uses (a bundled or remote config) instead of the compiled default.
class EffectiveValuesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val boolean = FlagKey("bool-flag")
    private val tier = FlagKey("tier")
    private val integer = FlagKey("int-flag")

    private fun pole(vararg sources: FlagValueSource): FlagPole<GoldenFlags> =
        FlagPole(GoldenFlagsSpec, sources.toList(), applicationName = "Golden")

    private fun FlagSchema.entry(key: FlagKey): FlagSchema.Entry = flags.single { it.key == key }

    private fun remote(json: String): RemoteOverrideSource =
        RemoteOverrideSource(GoldenFlagsSpec, name = "Environment (staging)").also { it.apply(json.encodeToByteArray()) }

    @Test
    fun aValueFromAConfigIsPublishedWithItsSource() {
        // As in an app whose bundled staging config overrules a compiled default.
        val pole = pole(SnapshotSource("Companion"), remote("""{"toggles": {"boolean": true}, "tier": "pro"}"""))

        val schema = FlagSchema.read(pole.publishSchema(temporary.root).parentFile)

        assertEquals(FlagValueBox.Bool(true), schema.entry(boolean).effectiveValue)
        assertEquals("Environment (staging)", schema.entry(boolean).effectiveSource)
        assertEquals(FlagValueBox.String("pro"), schema.entry(tier).effectiveValue)
        // The default is still the compiled one: the effective value sits beside it.
        assertEquals(FlagValueBox.Bool(false), schema.entry(boolean).defaultValue)
    }

    @Test
    fun aFlagOnlyItsDefaultSuppliesCarriesNeither() {
        val schema = pole(SnapshotSource("Companion"), remote("""{"toggles": {"boolean": true}}""")).schemaWithEffectiveValues()

        assertNull(schema.entry(integer).effectiveValue)
        assertNull(schema.entry(integer).effectiveSource)
    }

    @Test
    fun theOverrideIsNotTheEffectiveValue() {
        // The companion reads overrides itself; publishing one here would outlive its reset.
        val overrides = SnapshotSource(
            "Companion",
            mapOf(boolean to FlagValueBox.Bool(false), integer to FlagValueBox.Int(7)),
        )
        val schema = pole(overrides, remote("""{"toggles": {"boolean": true}}""")).schemaWithEffectiveValues()

        assertEquals(FlagValueBox.Bool(true), schema.entry(boolean).effectiveValue)
        assertEquals("Environment (staging)", schema.entry(boolean).effectiveSource)
        assertNull(schema.entry(integer).effectiveValue)
    }

    @Test
    fun onlyTheTopMutableSourceIsSkipped() {
        val lower = SnapshotSource("Bundled", mapOf(integer to FlagValueBox.Int(7)))
        val schema = pole(SnapshotSource("Companion"), lower).schemaWithEffectiveValues()

        assertEquals(FlagValueBox.Int(7), schema.entry(integer).effectiveValue)
        assertEquals("Bundled", schema.entry(integer).effectiveSource)
    }

    @Test
    fun aPoleWithNoMutableSourcePublishesEverySourcesValue() {
        val schema = pole(remote("""{"toggles": {"boolean": true}}""")).schemaWithEffectiveValues()

        assertEquals(FlagValueBox.Bool(true), schema.entry(boolean).effectiveValue)
    }

    @Test
    fun aValueThePoleWouldNotReadIsLeftOut() {
        // The pole falls back to the default for an enum entry it lacks, a wrong type, or a NaN.
        val lower = SnapshotSource(
            "Bundled",
            mapOf(
                tier to FlagValueBox.String("platinum"),
                integer to FlagValueBox.String("lots"),
                FlagKey("double-flag") to FlagValueBox.Double(Double.NaN),
            ),
        )
        val schema = pole(SnapshotSource("Companion"), lower).schemaWithEffectiveValues()

        assertNull(schema.entry(tier).effectiveValue)
        assertNull(schema.entry(integer).effectiveValue)
        assertNull(schema.entry(FlagKey("double-flag")).effectiveValue)
        // And the schema still writes.
        schema.write(temporary.root)
    }

    @Test
    fun theEffectiveValuesAreReadWhenPublishedNotWhenThePoleWasBuilt() {
        val config = RemoteOverrideSource(GoldenFlagsSpec, name = "Remote")
        val pole = pole(SnapshotSource("Companion"), config)
        assertNull(pole.schemaWithEffectiveValues().entry(boolean).effectiveValue)

        config.apply("""{"toggles": {"boolean": true}}""".encodeToByteArray())

        assertEquals(FlagValueBox.Bool(true), FlagSchema.read(pole.publishSchema(temporary.root).parentFile).entry(boolean).effectiveValue)
        // The pole's own schema, which exports and imports validate against, stays as it was built.
        assertNull(pole.schema.entry(boolean).effectiveValue)
    }

    // Compatibility

    @Test
    fun theFormatVersionStaysOneSoAnOlderCompanionStillReadsIt() {
        val bytes = pole(SnapshotSource("Companion"), remote("""{"toggles": {"boolean": true}}""")).schemaWithEffectiveValues().jsonBytes()
        val root = (JsonReader.parse(bytes) as JsonValue.Object).fields

        assertEquals(JsonValue.Integer(1), root["formatVersion"])
    }

    @Test
    fun theNewFieldsAreOnlyAdded() {
        // Strip them and what is left is byte for byte the schema 0.1.0 published.
        val pole = pole(SnapshotSource("Companion"), remote("""{"toggles": {"boolean": true}, "tier": "pro"}"""))
        val published = pole.schemaWithEffectiveValues()
        val stripped = published.copy(flags = published.flags.map { it.copy(effectiveValue = null, effectiveSource = null) })

        assertEquals(pole.schema.jsonBytes().decodeToString(), stripped.jsonBytes().decodeToString())
    }

    @Test
    fun aSchemaFromAnOlderHostHasNoEffectiveValues() {
        val schema = FlagSchema.parse(pole().schema.jsonBytes())

        assertEquals(List(schema.flags.size) { null }, schema.flags.map { it.effectiveValue })
        assertEquals(List(schema.flags.size) { null }, schema.flags.map { it.effectiveSource })
    }

    @Test
    fun anEffectiveValueThatDoesNotMatchItsTypeOrLacksItsPartnerIsDropped() {
        val schema = FlagSchema.parse(
            """
            {"formatVersion": 1, "flags": [
              {"key": "a", "valueType": "int", "defaultValue": 1, "effectiveValue": "lots", "effectiveSource": "Remote"},
              {"key": "b", "valueType": "int", "defaultValue": 1, "effectiveValue": 2, "effectiveSource": "Remote"},
              {"key": "c", "valueType": "int", "defaultValue": 1, "effectiveValue": 3},
              {"key": "d", "valueType": "int", "defaultValue": 1, "effectiveSource": "Remote"}
            ]}
            """.trimIndent().encodeToByteArray(),
        )

        assertNull(schema.flags[0].effectiveValue)
        assertNull(schema.flags[0].effectiveSource)
        assertEquals(FlagValueBox.Int(2), schema.flags[1].effectiveValue)
        assertEquals("Remote", schema.flags[1].effectiveSource)
        // One field without the other is dropped: they are present together or not at all.
        assertNull(schema.flags[2].effectiveValue)
        assertNull(schema.flags[3].effectiveSource)
    }
}
