package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.pole.ReadOnlySource
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPoleTransportTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val ratio = FlagKey("ratio")
    private val enabled = FlagKey("enabled")

    private fun goldenPole(): FlagPole<GoldenFlags> = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

    private fun transportPole(): FlagPole<TransportFlags> = FlagPole(TransportFlagsSpec, listOf(SnapshotSource("local")))

    private fun document(json: String): ByteArray = json.encodeToByteArray()

    private fun exportedValues(pole: FlagPole<*>): Map<String, JsonValue> {
        val exported = JsonReader.parse(pole.exportJson()) as JsonValue.Object
        return (exported.fields["values"] as JsonValue.Object).fields
    }

    // Export

    @Test
    fun exportCarriesOnlyOverriddenFlags() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertEquals(mapOf(boolFlag to FlagValueBox.Bool(true)), pole.exportPayload().values)
    }

    @Test
    fun exportOfAnUntouchedPoleIsEmpty() {
        assertTrue(goldenPole().exportPayload().values.isEmpty())
    }

    @Test
    fun exportedJsonIsFlatAndHandEditable() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        pole.setOverride(GoldenFlagsSpec.intFlag, 3)

        val exported = JsonReader.parse(pole.exportJson()) as JsonValue.Object
        val values = (exported.fields["values"] as JsonValue.Object).fields

        assertEquals(JsonValue.Bool(true), values["bool-flag"])
        assertEquals(JsonValue.Integer(3), values["int-flag"])
        assertEquals(JsonValue.Integer(1), exported.fields["formatVersion"])
    }

    @Test
    fun datesDataAndUrlsExportAsPlainStrings() {
        // No "$type" wrappers: the schema already says what each key is.
        val pole = transportPole()
        pole.setOverride(TransportFlagsSpec.launchedAt, Instant.EPOCH)
        pole.setOverride(TransportFlagsSpec.endpoint, URI("https://example.com"))
        pole.setOverride(TransportFlagsSpec.blob, byteArrayOf(0x01, 0x02))

        val values = exportedValues(pole)

        assertEquals(JsonValue.Text("1970-01-01T00:00:00Z"), values["launched-at"])
        assertEquals(JsonValue.Text("https://example.com"), values["endpoint"])
        assertEquals(JsonValue.Text("AQI="), values["blob"])
    }

    @Test
    fun exportedJsonDoesNotEscapeSlashes() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.urlFlag, URI("https://staging.example.com/v3"))

        val json = pole.exportJson().decodeToString()

        assertTrue(json.contains("https://staging.example.com/v3"), json)
        assertFalse(json.contains("\\/"), "slashes should not be escaped")
    }

    // Round trips

    @Test
    fun jsonRoundTripsEveryOverride() {
        val source = transportPole()
        source.setOverride(TransportFlagsSpec.launchedAt, Instant.ofEpochSecond(1_000))
        source.setOverride(TransportFlagsSpec.endpoint, URI("https://example.com/a?b=c"))
        source.setOverride(TransportFlagsSpec.blob, byteArrayOf(0x01, 0xFF.toByte()))
        source.setOverride(TransportFlagsSpec.tags, listOf("x", "y"))
        source.setOverride(TransportFlagsSpec.limits, mapOf("a" to 1))
        source.setOverride(TransportFlagsSpec.ratio, 2.5f)

        val destination = transportPole()
        destination.importJson(source.exportJson())

        assertEquals(source.overrides, destination.overrides)
    }

    @Test
    fun aFloatThatDecimalCannotHoldExactlyRoundTrips() {
        val source = transportPole()
        source.setOverride(TransportFlagsSpec.ratio, 0.1f)

        assertEquals(JsonValue.Real(0.10000000149011612), exportedValues(source)["ratio"])

        val destination = transportPole()
        destination.importJson(source.exportJson())

        assertEquals(0.1f, destination.flags.ratio)
    }

    @Test
    fun enumOverridesRoundTrip() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.checkout.tier, GoldenTier.ENTERPRISE)

        val destination = goldenPole()
        destination.importJson(source.exportJson())

        assertEquals(GoldenTier.ENTERPRISE, destination.flags.checkout.tier)
    }

    @Test
    fun aRecordListRoundTripsIntoTheTypedFlag() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.endpoints, GoldenOverrides.endpoints)

        val destination = goldenPole()
        destination.importJson(source.exportJson())

        assertEquals(GoldenOverrides.endpoints, destination.flags.endpoints)
    }

    @Test
    fun escapingChangeDoesNotBreakTheRoundTrip() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.urlFlag, URI("https://a.example/x/y?z=1/2"))

        val destination = goldenPole()
        destination.importJson(source.exportJson())

        assertEquals(source.overrides, destination.overrides)
    }

    @Test
    fun exportingAndImportingAnEmptySetRoundTrips() {
        val pole = goldenPole()

        pole.importJson(pole.exportJson())

        assertTrue(pole.overrides.isEmpty())
    }

    // Import is strict and all or nothing

    @Test
    fun importReportsWhatItApplied() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.boolFlag, true)
        source.setOverride(GoldenFlagsSpec.intFlag, 3)

        val applied = goldenPole().importJson(source.exportJson())

        assertEquals(listOf(boolFlag, intFlag), applied.values.keys.sortedBy { it.rawValue })
    }

    @Test
    fun importRejectsAnUnknownKeyAndAppliesNothing() {
        val pole = goldenPole()

        val error = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson(document("""{"formatVersion": 1, "values": {"bool-flag": true, "not-a-flag": 1}}"""))
        }

        assertEquals(listOf(FlagImportProblem(FlagKey("not-a-flag"), FlagImportProblem.Kind.UnknownKey, found = "1")), error.problems)
        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun importRejectsAMistypedValueAndAppliesNothing() {
        val pole = goldenPole()

        val error = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson(document("""{"formatVersion": 1, "values": {"bool-flag": true, "int-flag": "lots"}}"""))
        }

        assertEquals(listOf(FlagImportProblem(intFlag, FlagImportProblem.Kind.TypeMismatch, "int", "\"lots\"")), error.problems)
        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun importReportsEveryProblemAtOnce() {
        val error = assertFailsWith<FlagImportException.Rejected> {
            goldenPole().importJson(document("""{"formatVersion": 1, "values": {"not-a-flag": 1, "int-flag": "lots"}}"""))
        }

        assertEquals(
            listOf(
                FlagImportProblem(intFlag, FlagImportProblem.Kind.TypeMismatch, "int", "\"lots\""),
                FlagImportProblem(FlagKey("not-a-flag"), FlagImportProblem.Kind.UnknownKey, found = "1"),
            ),
            error.problems,
        )
    }

    @Test
    fun importRejectsAnUnknownFormatVersion() {
        val error = assertFailsWith<FlagImportException.UnsupportedFormatVersion> {
            goldenPole().importJson(document("""{"formatVersion": 99, "values": {}}"""))
        }

        assertEquals(99L, error.version)
    }

    @Test
    fun importRejectsMalformedData() {
        assertFailsWith<FlagImportException.Malformed> { goldenPole().importJson(document("not json")) }
    }

    @Test
    fun aPayloadMissingItsValuesIsRejected() {
        val error = assertFailsWith<FlagImportException.Malformed> {
            goldenPole().importJson(document("""{"formatVersion": 1}"""))
        }

        assertEquals("missing values", error.reason)
    }

    @Test
    fun aPayloadMissingItsVersionIsRejected() {
        val error = assertFailsWith<FlagImportException.Malformed> {
            goldenPole().importJson(document("""{"values": {}}"""))
        }

        assertEquals("missing formatVersion", error.reason)
    }

    @Test
    fun anEmptyPayloadIsValidAndAppliesNothing() {
        val pole = goldenPole()

        val applied = pole.importJson(document("""{"formatVersion": 1, "values": {}}"""))

        assertTrue(applied.values.isEmpty())
        assertTrue(pole.overrides.isEmpty())
    }

    @Test
    fun importingLeavesUnmentionedOverridesAlone() {
        // Import applies what it carries. It is not a wholesale replacement.
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        pole.importJson(document("""{"formatVersion": 1, "values": {"int-flag": 3}}"""))

        assertTrue(pole.flags.boolFlag)
        assertEquals(3L, pole.flags.intFlag.toLong())
    }

    @Test
    fun importedValuesLandInTheTopMutableSourceAndOutrankTheRest() {
        val local = SnapshotSource("local")
        val remote = ReadOnlySource("remote", mapOf(boolFlag to FlagValueBox.Bool(false)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local, remote))

        pole.importJson(document("""{"formatVersion": 1, "values": {"bool-flag": true}}"""))

        assertEquals(FlagValueBox.Bool(true), local.values[boolFlag])
        assertTrue(pole.flags.boolFlag)
    }

    @Test
    fun importingAnEnumCaseThisBuildLacksIsRejected() {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        val error = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson(document("""{"formatVersion": 1, "values": {"tier": "platinum"}}"""))
        }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownCase), error.problems.map { it.kind })
        assertEquals(listOf(FlagKey("tier")), error.problems.map { it.key })
        assertNull(local.values[FlagKey("tier")], "nothing is stored")
        assertEquals(GoldenTier.FREE, pole.flags.tier)
    }

    @Test
    fun importingAKnownEnumCaseStillWorks() {
        val pole = goldenPole()

        pole.importJson(document("""{"formatVersion": 1, "values": {"tier": "pro"}}"""))

        assertEquals(GoldenTier.PRO, pole.flags.tier)
    }

    // Numbers JSON cannot write

    @Test
    fun exportingAnInfiniteDoubleThrowsRatherThanCrashing() {
        val local = SnapshotSource("local", mapOf(ratio to FlagValueBox.Double(Double.POSITIVE_INFINITY)))
        val pole = FlagPole(EdgeFlagsSpec, listOf(local))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { pole.exportJson() }

        assertEquals(ratio, error.key)
    }

    @Test
    fun exportingANaNThrows() {
        val local = SnapshotSource("local", mapOf(ratio to FlagValueBox.Double(Double.NaN)))
        val pole = FlagPole(EdgeFlagsSpec, listOf(local))

        assertFailsWith<FlagSerializationException.NonFiniteNumber> { pole.exportJson() }
    }

    @Test
    fun aNonFiniteNumberNestedInACollectionIsCaught() {
        val ratios = FlagKey("ratios")
        val nested = FlagValueBox.Array(listOf(FlagValueBox.Double(1.0), FlagValueBox.Double(Double.POSITIVE_INFINITY)))
        val pole = FlagPole(EdgeFlagsSpec, listOf(SnapshotSource("local", mapOf(ratios to nested))))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { pole.exportJson() }

        assertEquals(ratios, error.key)
    }

    @Test
    fun finiteNumbersAreUnaffected() {
        val pole = FlagPole(EdgeFlagsSpec, listOf(SnapshotSource("local", mapOf(ratio to FlagValueBox.Double(1.5)))))

        assertTrue(pole.exportJson().decodeToString().contains("\"ratio\" : 1.5"))
    }

    // Import used to apply part of a payload

    @Test
    fun aWriteFailingPartwayUndoesTheRest() {
        val source = RefusingSource(refusedKey = enabled)
        val pole = FlagPole(EdgeFlagsSpec, listOf(source))

        assertFailsWith<FlagException.UnsupportedValue> {
            pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":true}}"""))
        }

        assertTrue(source.storedKeys.isEmpty(), "left behind: ${source.storedKeys}")
        assertEquals(0.0, pole.flags.ratio)
        assertFalse(pole.flags.enabled)
    }

    @Test
    fun rollbackRestoresAPreviousOverrideRatherThanClearingIt() {
        val source = RefusingSource(refusedKey = enabled)
        source.setBox(ratio, FlagValueBox.Double(9.5))
        val pole = FlagPole(EdgeFlagsSpec, listOf(source))

        assertFailsWith<FlagException.UnsupportedValue> {
            pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":false}}"""))
        }

        assertEquals(9.5, pole.flags.ratio, "the earlier override should be restored, not cleared")
    }

    @Test
    fun rollbackIsBestEffortWhenTheSourceRefusesEverything() {
        // A store that has run out of room refuses the undo as well.
        val source = RefusingSource(refusedKey = enabled, refusesEverythingAfterwards = true)
        val pole = FlagPole(EdgeFlagsSpec, listOf(source))

        assertFailsWith<FlagException.UnsupportedValue> {
            pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":true}}"""))
        }

        assertTrue(pole.flags.ratio == 1.5 || pole.flags.ratio == 0.0)
    }

    @Test
    fun aSuccessfulImportStillAppliesEverything() {
        val pole = FlagPole(EdgeFlagsSpec, listOf(SnapshotSource("local")))

        val applied = pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":true}}"""))

        assertEquals(listOf(enabled, ratio), applied.values.keys.sortedBy { it.rawValue })
        assertEquals(1.5, pole.flags.ratio)
        assertTrue(pole.flags.enabled)
    }
}

@FlagContainer
internal interface TransportFlags {

    @Flag(description = "Launched at")
    val launchedAt: Instant get() = Instant.EPOCH

    @Flag(description = "Endpoint")
    val endpoint: URI get() = URI("https://default.example")

    @Flag(description = "Blob")
    val blob: ByteArray get() = ByteArray(0)

    @Flag(description = "Tags")
    val tags: List<String> get() = emptyList()

    @Flag(description = "Limits")
    val limits: Map<String, Int> get() = emptyMap()

    @Flag(description = "Ratio")
    val ratio: Float get() = 1.0f
}

@FlagContainer
internal interface EdgeFlags {

    @Flag(description = "Ratio", remoteKey = "cfg.ratio")
    val ratio: Double get() = 0.0

    @Flag(description = "Enabled")
    val enabled: Boolean get() = false

    @Flag(description = "Ratios")
    val ratios: List<Double> get() = emptyList()
}

/** Refuses one key, standing in for a store that cannot represent a value. Optionally refuses everything after that. */
private class RefusingSource(
    private val refusedKey: FlagKey,
    private val refusesEverythingAfterwards: Boolean = false,
) : MutableFlagValueSource {
    override val sourceName: String = "refusing"
    override val changes: Flow<FlagChange> = emptyFlow()
    private val values = LinkedHashMap<FlagKey, FlagValueBox>()
    private var hasRefused = false

    val storedKeys: List<FlagKey>
        get() = values.keys.sortedBy { it.rawValue }

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        if (refusesEverythingAfterwards && hasRefused) throw FlagException.UnsupportedValue(key)
        if (key == refusedKey && box != null) {
            hasRefused = true
            throw FlagException.UnsupportedValue(key)
        }
        if (box == null) {
            values.remove(key)
        } else {
            values[key] = box
        }
    }
}
