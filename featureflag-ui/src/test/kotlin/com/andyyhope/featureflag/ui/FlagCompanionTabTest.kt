package com.andyyhope.featureflag.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagCompanionTabTest {

    private enum class SessionSignal : FlagSignal { SignOut, SwapDependency }

    private enum class OtherSignal : FlagSignal { SignOut }

    private val session = FlagSignalGroup("Session", SessionSignal.entries)

    // Descriptors

    @Test
    fun theDefaultTabsAreOverridesAndFlags() {
        assertEquals(listOf("overrides", "flags"), FlagCompanionTab.Defaults.map { it.id })
        assertEquals(listOf("Overrides", "Flags"), FlagCompanionTab.Defaults.map { it.title })
    }

    @Test
    fun tabsKeepTheOrderTheyAreGiven() {
        val tabs = listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Signals(session), FlagCompanionTab.Flags)

        assertEquals(listOf("overrides", "signals", "flags"), tabs.map { it.id })
    }

    @Test
    fun theGroupedTabDescriptor() {
        val tab = FlagCompanionTab.Signals(session)

        assertEquals("signals", tab.id)
        assertEquals("Signals", tab.title)
        assertEquals(listOf(session), tab.groups)
        assertEquals("Send", FlagCompanionTab.Signals(session, title = "Send").title)
    }

    @Test
    fun detailTabsAreIdentifiedByTheirKey() {
        val tabs = listOf(
            FlagCompanionTab.Detail(key = "environment", title = "Environment"),
            FlagCompanionTab.Detail(key = "checkout.tier", title = "Tier"),
        )

        assertEquals(listOf("detail.environment", "detail.checkout.tier"), tabs.map { it.id })
        assertNull(tabs.duplicateTabId())
    }

    @Test
    fun aCustomTabCarriesItsOwnIdentity() {
        val tab = FlagCompanionTab.Custom(id = "environment", title = "Environment", icon = Icons.Filled.Settings) {}

        assertEquals("environment", tab.id)
        assertEquals("Environment", tab.title)
        assertSame(Icons.Filled.Settings, tab.icon)
        assertSame(Icons.Filled.Settings, tab.icon(selected = true))
    }

    // Malformed lists

    @Test
    fun aDuplicateIdIsDetectedAndNamed() {
        assertEquals("overrides", listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Flags, FlagCompanionTab.Overrides).duplicateTabId())
        val custom = FlagCompanionTab.Custom(id = "flags", title = "Mine", icon = Icons.Filled.Star) {}
        assertEquals("flags", listOf(FlagCompanionTab.Flags, custom).duplicateTabId())

        val error = assertFailsWith<IllegalArgumentException> {
            requireDistinctTabIds(listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Overrides))
        }
        assertEquals(
            "A companion cannot have two tabs with the id 'overrides'. Give one of them a different id, or drop it.",
            error.message,
        )
    }

    @Test
    fun aWellFormedListHasNoDuplicate() {
        assertNull(listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Signals(session), FlagCompanionTab.Flags).duplicateTabId())
    }

    @Test
    fun twoSignalGroupsSharingANameAreRefused() {
        val error = assertFailsWith<IllegalArgumentException> {
            FlagCompanionTab.Signals(session, FlagSignalGroup("Other", OtherSignal.entries))
        }

        assertEquals(
            "More than one signal group defines 'SignOut'. A signal travels as its raw value, so it has to be unique " +
                "across every group.",
            error.message,
        )
    }
}
