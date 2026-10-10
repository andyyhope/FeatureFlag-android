package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenLevel
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPoleEdgeCaseTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val tier = FlagKey("tier")
    private val level = FlagKey("level")

    // Writing with nothing to write to

    @Test
    fun settingAnOverrideWithNoMutableSourceReports() {
        val pole = FlagPole(GoldenFlagsSpec, emptyList())

        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(GoldenFlagsSpec.boolFlag, true) }
    }

    @Test
    fun settingAnOverrideWithOnlyAReadOnlySourceReports() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(ReadOnlySource("remote")))

        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(GoldenFlagsSpec.boolFlag, true) }
        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(boolFlag, FlagValueBox.Bool(true)) }
        assertFailsWith<FlagException.NoMutableSource> { pole.removeOverride(GoldenFlagsSpec.boolFlag) }
    }

    @Test
    fun writesGoToTheHighestPriorityMutableSource() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")

        FlagPole(GoldenFlagsSpec, listOf(top, bottom)).setOverride(GoldenFlagsSpec.boolFlag, true)

        assertEquals(FlagValueBox.Bool(true), top.values[boolFlag])
        assertNull(bottom.values[boolFlag])
    }

    @Test
    fun writesSkipAReadOnlySourceAboveAMutableOne() {
        val local = SnapshotSource("local")

        FlagPole(GoldenFlagsSpec, listOf(ReadOnlySource("remote"), local)).setOverride(GoldenFlagsSpec.boolFlag, true)

        assertEquals(FlagValueBox.Bool(true), local.values[boolFlag])
    }

    @Test
    fun settingANullBoxRemovesTheOverride() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.setOverride(boolFlag, null)

        assertTrue(local.values.isEmpty())
        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun currentOverrideReadsOnlyTheWritableSource() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val remote = ReadOnlySource("remote", mapOf(boolFlag to FlagValueBox.Bool(false)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(remote, top, bottom))

        assertNull(pole.currentOverride(boolFlag, FlagValueType.Bool))

        top.setBox(boolFlag, FlagValueBox.String("not a bool"))
        assertEquals(FlagValueBox.String("not a bool"), pole.currentOverride(boolFlag, FlagValueType.Bool))
        assertNull(FlagPole(GoldenFlagsSpec, listOf(remote)).currentOverride(boolFlag, FlagValueType.Bool))
    }

    // Describing the tree

    @Test
    fun descriptorsAreFlattenedDepthFirst() {
        assertEquals(
            listOf(
                "boolFlag", "intFlag", "doubleFlag", "floatFlag", "stringFlag", "dataFlag", "dateFlag", "urlFlag",
                "useHTTPSOnly", "markets", "matrix", "limits", "tier", "level", "endpoints",
                "applePay", "tier", "oneTap", "confirmationDelay",
            ),
            FlagPole(GoldenFlagsSpec, emptyList()).descriptors.map { it.propertyName },
        )
    }

    @Test
    fun flattenedDescriptorsKeepTheirFullKeyPaths() {
        val paths = FlagPole(GoldenFlagsSpec, emptyList()).descriptors.map { it.keyPath.propertyNames }

        assertEquals(listOf("boolFlag"), paths.first())
        assertEquals(
            listOf(
                listOf("checkout", "applePay"),
                listOf("checkout", "tier"),
                listOf("checkout", "express", "oneTap"),
                listOf("checkout", "express", "confirmationDelay"),
            ),
            paths.takeLast(4),
        )
    }

    @Test
    fun keysMatchTheConfiguredEncoding() {
        assertTrue(FlagKey("checkout.apple-pay") in FlagPole(GoldenFlagsSpec, emptyList()).keys)
        assertTrue(FlagKey("use-https-only") in FlagPole(GoldenFlagsSpec, emptyList()).keys)
        assertTrue(FlagKey("checkout.apple_pay") in FlagPole(GoldenFlagsSpec, emptyList(), KeyEncoding.Snakecase).keys)
    }

    @Test
    fun everyDeclaredFlagAppearsExactlyOnce() {
        val keys = FlagPole(GoldenFlagsSpec, emptyList()).keys

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(19, keys.size)
    }

    @Test
    fun theSchemaCarriesTheApplicationNameOrNothing() {
        assertNull(FlagPole(GoldenFlagsSpec, emptyList()).schema.applicationName)
        assertEquals("Golden", FlagPole(GoldenFlagsSpec, emptyList(), applicationName = "Golden").schema.applicationName)
    }

    @Test
    fun theSchemaDescribesTheSpecWithThePolesEncoding() {
        val pole = FlagPole(GoldenFlagsSpec, emptyList(), KeyEncoding.Snakecase, "Golden")

        assertEquals(FlagSchema.of(GoldenFlagsSpec, KeyEncoding.Snakecase, "Golden").flags, pole.schema.flags)
        assertEquals(pole.schema.flags.map { it.key }, pole.keys)
    }

    @Test
    fun sourcesKeepTheirOrder() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")

        assertEquals(listOf(top, bottom), FlagPole(GoldenFlagsSpec, listOf(top, bottom)).sources)
    }

    // Clearing

    @Test
    fun removeAllOverridesOnlyClearsTheTopMutableSource() {
        // Writes go to one source, so clearing does too. A value in a lower source is not
        // an override this pole put there.
        val top = SnapshotSource("top", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val bottom = SnapshotSource("bottom", mapOf(intFlag to FlagValueBox.Int(3)))

        FlagPole(GoldenFlagsSpec, listOf(top, bottom)).removeAllOverrides()

        assertTrue(top.values.isEmpty())
        assertEquals(FlagValueBox.Int(3), bottom.values[intFlag])
    }

    @Test
    fun clearingEverythingOnlyWritesWhatWasSetInOneBatch() {
        // Writing null to every declared flag would turn one reset into hundreds of
        // cross-process broadcasts.
        val source = CountingSource()
        val pole = FlagPole(GoldenFlagsSpec, listOf(source))
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        source.writes = 0

        pole.removeAllOverrides()

        assertEquals(1, source.writes)
        assertEquals(listOf(mapOf<FlagKey, FlagValueBox?>(boolFlag to null)), source.batches)
        assertTrue(pole.overrides.isEmpty())
    }

    @Test
    fun clearingNothingWritesNothing() {
        val source = CountingSource()

        FlagPole(GoldenFlagsSpec, listOf(source)).removeAllOverrides()

        assertEquals(0, source.writes)
        assertTrue(source.batches.isEmpty())
    }

    @Test
    fun removeAllOverridesWithNoWritableSourceIsANoOp() {
        // Review focus 4. Swift finds nothing to clear, so it never reaches the write that would throw.
        val remote = ReadOnlySource("remote", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(remote))

        pole.removeAllOverrides()

        assertTrue(pole.flags.boolFlag)
    }

    // Values the codec cannot read

    @Test
    fun aBoxOfTheRightTypeThatWillNotDecodeReadsTheDefaultButNamesItsSource() {
        val values = mapOf(tier to FlagValueBox.String("platinum"), level to FlagValueBox.Int(99))
        val local = SnapshotSource("local", values)
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        assertEquals(GoldenTier.FREE, pole.flags.tier)
        assertEquals(GoldenTier.FREE, pole[GoldenFlagsSpec.tier])
        assertEquals(GoldenLevel.TWO, pole[GoldenFlagsSpec.level])
        assertEquals(FlagResolution(tier, "local", FlagValueBox.String("platinum")), pole.resolution(GoldenFlagsSpec.tier))
        assertEquals(FlagResolution(level, "local", FlagValueBox.Int(99)), pole.resolution(level, FlagValueType.Int))
    }

    @Test
    fun anUndecodableTopValueMasksAValidOneBelowIt() {
        // The ported resolver rule: the first box of the declared type wins, even one the
        // codec then cannot read. A stale case from a newer build hides the remote value.
        val top = SnapshotSource("top", mapOf(tier to FlagValueBox.String("platinum")))
        val bottom = SnapshotSource("bottom", mapOf(tier to FlagValueBox.String("pro")))
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        assertEquals(GoldenTier.FREE, pole.flags.tier)
        assertEquals("top", pole.resolution(GoldenFlagsSpec.tier).sourceName)
    }

    // Reading concurrently

    @Test
    fun readingFromManyThreadsAtOnceIsSafe() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local", mapOf(intFlag to FlagValueBox.Int(42)))))

        runConcurrently(200) {
            assertEquals(42L, pole.flags.intFlag.toLong())
            assertFalse(pole.flags.checkout.express.oneTap)
        }
    }

    @Test
    fun readingWhileAnotherThreadWritesIsSafe() {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        // Only even values are ever written, and the default, 42, is even too.
        runConcurrently(200) { index ->
            if (index % 2 == 0) {
                local.setBox(intFlag, FlagValueBox.Int(index.toLong()))
            } else {
                assertEquals(0L, pole.flags.intFlag.toLong() % 2)
            }
        }
    }

    // Lookups and resolution by key

    @Test
    fun aMinimalLookupNeedsOnlyTwoMembers() {
        // Previews and tests should not have to implement change publishing or writing.
        val lookup = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase

            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = if (key == intFlag) FlagValueBox.Int(99) else null
        }
        val flags = GoldenFlagsSpec.createFlags(lookup)

        assertEquals(99L, flags.intFlag.toLong())
        assertFalse(flags.boolFlag)
    }

    @Test
    fun resolutionByKeyMatchesTheTypedForm() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        val byKey = pole.resolution(boolFlag, FlagValueType.Bool)

        assertEquals(pole.resolution(GoldenFlagsSpec.boolFlag), byKey)
        assertEquals("local", byKey.sourceName)
    }

    @Test
    fun resolutionByKeyFillsInTheCompiledDefault() {
        val resolution = FlagPole(GoldenFlagsSpec, emptyList()).resolution(intFlag, FlagValueType.Int)

        assertTrue(resolution.isDefault)
        assertEquals(FlagValueBox.Int(42), resolution.box, "should report the value in effect")
    }

    @Test
    fun resolutionByAnUndeclaredKeyHasNoValueInEffect() {
        val nope = FlagKey("nope")

        assertEquals(FlagResolution(nope, null, null), FlagPole(GoldenFlagsSpec, emptyList()).resolution(nope, FlagValueType.Bool))
    }

    @Test
    fun theTypedAndKeyedDefaultsAgree() {
        // The typed form encodes the ref's default; the keyed form reads the schema's.
        val pole = FlagPole(GoldenFlagsSpec, emptyList())

        assertEquals(pole.resolution(GoldenFlagsSpec.endpoints), pole.resolution(FlagKey("endpoints"), FlagValueType.String))
        assertEquals(pole.resolution(GoldenFlagsSpec.dataFlag), pole.resolution(FlagKey("data-flag"), FlagValueType.Data))
        assertEquals(
            pole.resolution(GoldenFlagsSpec.checkout.express.confirmationDelay),
            pole.resolution(FlagKey("checkout.express.confirmation-delay"), FlagValueType.Double),
        )
    }

    @Test
    fun everyFlagCanBeExplainedInALoop() {
        // The point of the keyed form: a diagnostics screen over the whole schema.
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        val explained = pole.schema.flags.map { pole.resolution(it.key, it.valueType) }

        assertEquals(19, explained.size)
        assertTrue(explained.all { it.box != null }, "every flag has a value in effect")
    }
}
