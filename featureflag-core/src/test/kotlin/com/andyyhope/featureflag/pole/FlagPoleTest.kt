package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Kotlin type of `intFlag` (Int or Long) is M1b's choice, so these tests compare `.toLong()`.
class FlagPoleTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val applePay = FlagKey("checkout.apple-pay")
    private val oneTap = FlagKey("checkout.express.one-tap")

    // Resolution

    @Test
    fun readsTheCompiledDefaultWhenNoSourceHasAValue() {
        val pole = FlagPole(GoldenFlagsSpec, emptyList())

        assertFalse(pole.flags.boolFlag)
        assertEquals(42L, pole.flags.intFlag.toLong())
        assertEquals(GoldenTier.PRO, pole.flags.checkout.tier)
    }

    @Test
    fun readsFromASource() {
        val local = SnapshotSource("local")
        local.setBox(boolFlag, FlagValueBox.Bool(true))

        assertTrue(FlagPole(GoldenFlagsSpec, listOf(local)).flags.boolFlag)
    }

    @Test
    fun localOverrideBeatsRemote() {
        // The precedence that makes the companion trustworthy: a value set by hand stays
        // set until it is explicitly cleared.
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(false)))
        val remote = SnapshotSource("remote", mapOf(boolFlag to FlagValueBox.Bool(true)))

        assertFalse(FlagPole(GoldenFlagsSpec, listOf(local, remote)).flags.boolFlag)
    }

    @Test
    fun remoteAppliesWhereThereIsNoLocalOverride() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val remote = SnapshotSource("remote", mapOf(intFlag to FlagValueBox.Int(3)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local, remote))

        assertEquals(3L, pole.flags.intFlag.toLong())
        assertTrue(pole.flags.boolFlag)
    }

    @Test
    fun fallsThroughWhenTheHighestSourceHoldsTheWrongType() {
        val local = SnapshotSource("local", mapOf(intFlag to FlagValueBox.String("nonsense")))
        val remote = SnapshotSource("remote", mapOf(intFlag to FlagValueBox.Int(3)))

        assertEquals(3L, FlagPole(GoldenFlagsSpec, listOf(local, remote)).flags.intFlag.toLong())
    }

    @Test
    fun nestedFlagsResolveThroughThePole() {
        val local = SnapshotSource("local", mapOf(oneTap to FlagValueBox.Bool(true)))

        assertTrue(FlagPole(GoldenFlagsSpec, listOf(local)).flags.checkout.express.oneTap)
    }

    @Test
    fun theIndexOperatorReadsAFlagWithoutGoingThroughFlags() {
        val local = SnapshotSource("local", mapOf(intFlag to FlagValueBox.Int(4)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        assertEquals(4L, pole[GoldenFlagsSpec.intFlag].toLong())
        assertFalse(pole[GoldenFlagsSpec.checkout.express.oneTap])
    }

    @Test
    fun keyEncodingIsConfigurable() {
        val local = SnapshotSource("local", mapOf(FlagKey("checkout.apple_pay") to FlagValueBox.Bool(false)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local), keyEncoding = KeyEncoding.Snakecase)

        assertFalse(pole.flags.checkout.applePay)
        assertEquals(FlagKey("checkout.apple_pay"), pole.key(GoldenFlagsSpec.checkout.applePay))
    }

    // Explaining a value

    @Test
    fun resolutionNamesTheWinningSource() {
        val remote = SnapshotSource("remote", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local"), remote))

        val resolution = pole.resolution(GoldenFlagsSpec.boolFlag)

        assertEquals("remote", resolution.sourceName)
        assertEquals(FlagValueBox.Bool(true), resolution.box)
        assertFalse(resolution.isDefault)
    }

    @Test
    fun resolutionReportsTheCompiledDefault() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        val resolution = pole.resolution(GoldenFlagsSpec.boolFlag)

        assertNull(resolution.sourceName)
        assertTrue(resolution.isDefault)
        assertEquals(FlagValueBox.Bool(false), resolution.box)
    }

    @Test
    fun resolutionSkipsASourceHoldingTheWrongType() {
        val local = SnapshotSource("local", mapOf(intFlag to FlagValueBox.String("nonsense")))
        val remote = SnapshotSource("remote", mapOf(intFlag to FlagValueBox.Int(3)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local, remote))

        assertEquals("remote", pole.resolution(GoldenFlagsSpec.intFlag).sourceName)
    }

    // Overrides

    @Test
    fun overridesListsOnlyFlagsSomeSourceSupplies() {
        val values = mapOf(boolFlag to FlagValueBox.Bool(true), applePay to FlagValueBox.Bool(true))
        val local = SnapshotSource("local", values)

        assertEquals(
            mapOf(boolFlag to FlagValueBox.Bool(true), applePay to FlagValueBox.Bool(true)),
            FlagPole(GoldenFlagsSpec, listOf(local)).overrides,
        )
    }

    @Test
    fun overridesSpanTheWholeStackButOnlyDeclaredFlags() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val remoteValues = mapOf(intFlag to FlagValueBox.Int(3), FlagKey("not-declared") to FlagValueBox.Int(1))
        val remote = ReadOnlySource("remote", remoteValues)

        assertEquals(
            mapOf(boolFlag to FlagValueBox.Bool(true), intFlag to FlagValueBox.Int(3)),
            FlagPole(GoldenFlagsSpec, listOf(local, remote)).overrides,
        )
    }

    @Test
    fun overridesIsEmptyWhenEverythingIsDefault() {
        assertTrue(FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local"))).overrides.isEmpty())
    }

    @Test
    fun settingAnOverrideThroughTheRefTakesEffect() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertTrue(pole.flags.boolFlag)
    }

    @Test
    fun removingAnOverrideRestoresTheDefault() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        pole.removeOverride(GoldenFlagsSpec.boolFlag)

        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun removeAllOverridesTouchesOnlyKnownFlags() {
        val unrelated = FlagKey("someone-elses-key")
        val values = mapOf(boolFlag to FlagValueBox.Bool(true), unrelated to FlagValueBox.String("unrelated"))
        val local = SnapshotSource("local", values)
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.removeAllOverrides()

        assertFalse(pole.flags.boolFlag)
        assertEquals(FlagValueBox.String("unrelated"), local.box(unrelated, FlagValueType.String))
    }
}
