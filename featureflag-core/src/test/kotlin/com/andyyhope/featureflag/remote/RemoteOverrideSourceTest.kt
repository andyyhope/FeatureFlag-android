package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal enum class RemoteDemoTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

internal enum class RemoteLevel(override val flagRawValue: Long) : FlagIntEnum { One(1), Two(2), Three(3) }

@FlagContainer
internal interface RemoteDemoFlags {
    @Flag(description = "New onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Maximum items")
    val maxItems: Int get() = 10

    @FlagGroup(description = "Checkout")
    val checkout: RemoteDemoCheckoutFlags
}

@FlagContainer
internal interface RemoteDemoCheckoutFlags {
    @Flag(description = "Apple Pay", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = false

    @FlagGroup(description = "Express")
    val express: RemoteDemoExpressFlags

    @Flag(description = "Tier")
    val tier: RemoteDemoTier get() = RemoteDemoTier.Free
}

@FlagContainer
internal interface RemoteDemoExpressFlags {
    @Flag(description = "One tap")
    val oneTap: Boolean get() = false
}

@FlagContainer
internal interface IndexedFlags {
    @Flag(description = "Second experiment", remoteKey = "experiments.1.enabled")
    val secondExperiment: Boolean get() = false
}

@FlagContainer
internal interface NumericFlags {
    @Flag(description = "Count", remoteKey = "cfg.count")
    val count: Int get() = 0

    @Flag(description = "Big count", remoteKey = "cfg.bigCount")
    val bigCount: Long get() = 0L

    @Flag(description = "Ratio", remoteKey = "cfg.ratio")
    val ratio: Double get() = 0.0
}

@FlagContainer
internal interface EnumFlags {
    @Flag(description = "Tier", remoteKey = "cfg.tier")
    val tier: RemoteDemoTier get() = RemoteDemoTier.Free

    @Flag(description = "Level", remoteKey = "cfg.level")
    val level: RemoteLevel get() = RemoteLevel.One
}

/** A backend that sends a list of records rather than a nested object. */
private object RecordListMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val records = payload.valueAt("flags") as? RemoteValue.Array ?: return emptyMap()
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (record in records.value) {
            val name = record.valueAt("name") as? RemoteValue.String
            val value = record.valueAt("value")
            if (name != null && value != null) result[FlagKey(name.value)] = value
        }
        return result
    }
}

class RemoteOverrideSourceTest {

    private fun json(text: String): ByteArray = text.toByteArray()

    private fun makeSource() = RemoteOverrideSource(RemoteDemoFlagsSpec)

    // Dot paths

    @Test
    fun resolvesNestedDotPaths() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun resolvesArrayIndicesInAPath() {
        val source = RemoteOverrideSource(IndexedFlagsSpec)
        source.apply(json("""{"experiments": [{"enabled": false}, {"enabled": true}]}"""))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("second-experiment"), FlagValueType.Bool))
    }

    @Test
    fun flagsWithoutARemoteKeyAreNeverRemotelyOverridable() {
        val source = makeSource()
        source.apply(json("""{"max-items": 99, "maxItems": 99}"""))
        assertNull(source.box(FlagKey("max-items"), FlagValueType.Int))
    }

    @Test
    fun absentPathsAreReportedAndLeftAlone() {
        val source = makeSource()
        val result = source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        assertEquals(listOf(FlagKey("new-onboarding")), result.appliedKeys)
        assertEquals(listOf(FlagKey("checkout.apple-pay")), result.absentKeys)
        assertNull(source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun appliedKeysAreSortedAndAbsentKeysFollowTheSchema() {
        val source = makeSource()
        assertEquals(listOf(FlagKey("new-onboarding"), FlagKey("checkout.apple-pay")), source.apply(json("{}")).absentKeys)
        val both = source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}, "checkout": {"applePay": true}}}"""))
        assertEquals(listOf(FlagKey("checkout.apple-pay"), FlagKey("new-onboarding")), both.appliedKeys)
    }

    @Test
    fun aNullIsTreatedAsAbsent() {
        val result = makeSource().apply(json("""{"featureToggles": {"onboarding": {"v2": null}}}"""))
        assertEquals(emptyList(), result.appliedKeys)
        assertEquals(listOf(FlagKey("new-onboarding"), FlagKey("checkout.apple-pay")), result.absentKeys)
    }

    @Test
    fun applyingANewPayloadReplacesTheOldOne() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        source.apply(json("""{"featureToggles": {"checkout": {"applePay": true}}}"""))
        assertNull(source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun clearingRemovesEverything() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        source.clear()
        assertNull(source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun aConditionalApplyOrClearWhoseConditionFailsChangesNothing() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))

        assertNull(source.apply(json("""{"featureToggles": {"checkout": {"applePay": true}}}""")) { false })
        assertFalse(source.clear { false })

        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
        assertNull(source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    // Strict typing

    @Test
    fun rejectsAStringForABooleanAndAppliesNothing() {
        val source = makeSource()
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            source.apply(json("""{"featureToggles": {"onboarding": {"v2": "true"}, "checkout": {"applePay": true}}}"""))
        }
        assertEquals(
            listOf(
                RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding.v2", Kind.TypeMismatch, "bool", "\"true\""),
            ),
            error.problems,
        )
        assertNull(source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun rejectsANumberForABoolean() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            makeSource().apply(json("""{"featureToggles": {"onboarding": {"v2": 1}}}"""))
        }
    }

    @Test
    fun acceptsAWholeNumberForADoubleFlag() {
        val source = RemoteOverrideSource(NumericFlagsSpec)
        source.apply(json("""{"cfg": {"ratio": 1}}"""))
        assertEquals(FlagValueBox.Double(1.0), source.box(FlagKey("ratio"), FlagValueType.Double))
    }

    @Test
    fun rejectsAFractionalNumberForAnIntegerFlag() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NumericFlagsSpec).apply(json("""{"cfg": {"count": 1.5}}"""))
        }
    }

    @Test
    fun rejectsAValueOutsideAnEnumsCases() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(EnumFlagsSpec).apply(json("""{"cfg": {"tier": "enterprise"}}"""))
        }
        assertEquals(listOf(Kind.UnknownCase), error.problems.map { it.kind })
    }

    @Test
    fun acceptsAValidEnumCase() {
        val source = RemoteOverrideSource(EnumFlagsSpec)
        source.apply(json("""{"cfg": {"tier": "pro"}}"""))
        assertEquals(FlagValueBox.String("pro"), source.box(FlagKey("tier"), FlagValueType.String))
    }

    @Test
    fun anIntEnumsCasesAreListedTheWaySwiftDescribesThem() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(EnumFlagsSpec).apply(json("""{"cfg": {"level": 9}}"""))
        }
        assertEquals("'cfg.level' → level: 9 is not one of int(1), int(2), int(3)", error.problems.single().toString())
    }

    @Test
    fun reportsEveryProblemAtOnce() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NumericFlagsSpec).apply(json("""{"cfg": {"count": 1.5, "ratio": "lots"}}"""))
        }
        assertEquals(setOf(FlagKey("count"), FlagKey("ratio")), error.problems.map { it.key }.toSet())
    }

    @Test
    fun problemsAreSortedByFlagKey() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            makeSource().apply(json("""{"featureToggles": {"onboarding": {"v2": 1}, "checkout": {"applePay": "x"}}}"""))
        }
        assertEquals(listOf(FlagKey("checkout.apple-pay"), FlagKey("new-onboarding")), error.problems.map { it.key })
    }

    @Test
    fun rejectsMalformedData() {
        assertFailsWith<RemoteOverrideException.Malformed> { makeSource().apply(json("not json")) }
    }

    @Test
    fun anIntBeyondKotlinIntRangeIsAcceptedButAnIntFlagReadsItsDefault() {
        // Review Focus 2. iOS's Int is 64-bit, so the schema type `int` admits 3_000_000_000
        // and apply accepts it. A Kotlin Int flag cannot hold it, so it reads its default
        // while its resolution still names the source. Declare Long for such values.
        val remote = RemoteOverrideSource(NumericFlagsSpec)
        remote.apply(json("""{"cfg": {"count": 3000000000, "bigCount": 3000000000}}"""))
        val pole = FlagPole(NumericFlagsSpec, listOf(remote))

        assertEquals(FlagValueBox.Int(3_000_000_000), remote.box(FlagKey("count"), FlagValueType.Int))
        assertEquals(0, pole.flags.count)
        assertEquals("Remote", pole.resolution(FlagKey("count"), FlagValueType.Int).sourceName)
        assertEquals(3_000_000_000L, pole.flags.bigCount)
    }

    // Custom mappers

    @Test
    fun aCustomMapperCanReshapeAnAwkwardPayload() {
        val source = RemoteOverrideSource(RemoteDemoFlagsSpec, mapper = RecordListMapper)
        source.apply(json("""{"flags": [{"name": "new-onboarding", "value": true}]}"""))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun aMapperKeyNoFlagHasIsReportedNotIgnored() {
        val typo = RemoteOverrideMapper { _, _ -> mapOf(FlagKey("rat1o") to RemoteValue.Double(2.5)) }
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NumericFlagsSpec, mapper = typo).apply(json("""{"a": 1}"""))
        }
        assertEquals(listOf(RemoteOverrideProblem(FlagKey("rat1o"), "rat1o", Kind.UnknownKey, found = "2.5")), error.problems)
    }

    @Test
    fun theBuiltInMapperCannotProduceThatProblem() {
        RemoteOverrideSource(NumericFlagsSpec).apply(json("""{"cfg": {"ratio": 1.5}}"""))
    }

    // Precedence and the change stream

    @Test
    fun aLocalOverrideBeatsARemotePayload() {
        val local = SnapshotSource("local")
        val remote = makeSource()
        remote.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        val pole = FlagPole(RemoteDemoFlagsSpec, listOf(local, remote))
        assertTrue(pole.flags.newOnboarding)

        local.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(false))

        assertFalse(pole.flags.newOnboarding)
        assertEquals("local", pole.resolution(RemoteDemoFlagsSpec.newOnboarding).sourceName)
    }

    @Test
    fun applyingAPayloadEmitsAChange() = runTest {
        val source = makeSource()
        val change = async(start = CoroutineStart.UNDISPATCHED) { source.changes.first() }
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        assertEquals(FlagChange.All, change.await())
    }

    @Test
    fun clearingEmitsAChange() = runTest {
        val source = makeSource()
        val change = async(start = CoroutineStart.UNDISPATCHED) { source.changes.first() }
        source.clear()
        assertEquals(FlagChange.All, change.await())
    }

    @Test
    fun settingAnOverrideWithOnlyAReadOnlySourceReports() {
        val pole = FlagPole(RemoteDemoFlagsSpec, listOf(makeSource()))
        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(RemoteDemoFlagsSpec.newOnboarding, true) }
    }

    @Test
    fun writesSkipAReadOnlySourceAboveAMutableOne() {
        val local = SnapshotSource("local")
        val pole = FlagPole(RemoteDemoFlagsSpec, listOf(makeSource(), local))
        pole.setOverride(RemoteDemoFlagsSpec.newOnboarding, true)
        assertEquals(FlagValueBox.Bool(true), local.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun applyingAPayloadDoesNotTrapOnACollidingSchema() {
        // A schema handed in directly is not guaranteed well formed: the first entry for a key wins.
        val schema = FlagSchema.of(RemoteDemoFlagsSpec)
        val first = schema.flags.first()
        val colliding = schema.copy(flags = listOf(first, first.copy(description = "B")))

        val result = RemoteOverrideSource(colliding).apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))

        assertEquals(listOf(FlagKey("new-onboarding")), result.appliedKeys)
        assertEquals(emptyList(), result.absentKeys)
    }
}
