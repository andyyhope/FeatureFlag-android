package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagSignalGroupTests: filing signals into groups, and deciding which earn a screen.
class FlagSignalGroupTest {

    private enum class SmallSignal(override val signalDescription: String, override val requiresRestart: Boolean) : FlagSignal {
        SignOut("Sign out", requiresRestart = false),
        SwapDependency("Swap dependency", requiresRestart = true),
    }

    private enum class CollidingSignal : FlagSignal { SignOut }

    private data class Named(override val name: String) : FlagSignal

    private val six = ('a'..'f').map { Named("$it") }
    private val seven = ('a'..'g').map { Named("$it") }

    // Automatic nesting

    @Test
    fun aSmallGroupStaysFlat() {
        val group = FlagSignalGroup("Session", SmallSignal.entries)

        assertEquals(2, group.signals.size)
        assertFalse(group.isNested)
    }

    @Test
    fun aGroupNestsOnceItPassesTheThreshold() {
        // Six fit beside the delay picker; a seventh is where a list starts to bury it.
        assertEquals(6, FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD)
        assertFalse(FlagSignalGroup("Six", six).isNested)
        assertTrue(FlagSignalGroup("Seven", seven).isNested)
    }

    @Test
    fun anExplicitDisplayOverridesTheCount() {
        assertTrue(FlagSignalGroup("Session", SmallSignal.entries, FlagSignalGroupDisplay.Nested).isNested)
        assertFalse(FlagSignalGroup("Seven", seven, FlagSignalGroupDisplay.Flat).isNested)
    }

    // Contents

    @Test
    fun aGroupCarriesEachSignalsDescriptionAndRestartFlag() {
        val group = FlagSignalGroup("Session", SmallSignal.entries)

        val signOut = group.signals.first { it.name == "SignOut" }
        assertEquals("Sign out", signOut.signalDescription)
        assertFalse(signOut.requiresRestart)
        assertTrue(group.signals.first { it.name == "SwapDependency" }.requiresRestart)
    }

    @Test
    fun groupsKeepTheOrderTheyAreGivenAndSoDoTheirSignals() {
        val groups = listOf(FlagSignalGroup("Session", SmallSignal.entries), FlagSignalGroup("Six", six))

        assertEquals(listOf("Session", "Six"), groups.map { it.title })
        assertEquals(listOf("SignOut", "SwapDependency"), groups[0].signals.map { it.name })
    }

    @Test
    fun aGroupIsIdentifiedByItsTitle() {
        val group = FlagSignalGroup("Session", SmallSignal.entries)

        assertEquals("Session", group.id)
        assertEquals(FlagSignalGroupDisplay.Automatic, group.display)
    }

    // Collisions

    @Test
    fun twoGroupsSharingANameAreDetected() {
        // A signal travels as its name and nothing else, so one name in two groups fires both observers.
        val groups = listOf(FlagSignalGroup("A", SmallSignal.entries), FlagSignalGroup("B", CollidingSignal.entries))

        assertEquals("SignOut", groups.duplicateSignalName())
    }

    @Test
    fun aNameRepeatedWithinOneGroupIsDetectedToo() {
        assertEquals("a", listOf(FlagSignalGroup("Twice", listOf(Named("a"), Named("b"), Named("a")))).duplicateSignalName())
    }

    @Test
    fun distinctGroupsDoNotCollide() {
        val groups = listOf(FlagSignalGroup("Session", SmallSignal.entries), FlagSignalGroup("Six", six))

        assertNull(groups.duplicateSignalName())
        assertNull(emptyList<FlagSignalGroup>().duplicateSignalName())
    }
}
