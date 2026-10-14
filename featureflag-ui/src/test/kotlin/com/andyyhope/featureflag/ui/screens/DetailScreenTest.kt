package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(store: FlagEditingStore, key: String, title: String? = null) {
        composeRule.setFlagContent { DetailScreen(store = store, key = key, title = title, onOpen = {}) }
    }

    @Test
    fun theDetailViewBuildsForAFlagThatExists() {
        show(testStore(ScreenFixtures.companion), "new-onboarding")

        // The title bar, the section header and the row all name it.
        composeRule.onAllNodesWithText("New onboarding experience").assertCountEquals(3)
        composeRule.onNodeWithText("Details").assertExists()
        composeRule.onNodeWithText("Key").assertExists()
        composeRule.onNodeWithText(
            "Anything you set here stays set. A value set by hand outranks whatever a backend sends, until you reset it.",
        ).assertExists()
    }

    @Test
    fun theDetailViewBuildsForAKeyThatDoesNot() {
        show(testStore(ScreenFixtures.companion), "newOnboarding")

        composeRule.onNodeWithText("This app publishes no flag called “newOnboarding”.").assertExists()
        composeRule.onNodeWithText(
            "Check the key as the schema publishes it — a nested flag is “checkout.apple-pay”, " +
                "not “applePay”.",
        ).assertExists()
    }

    @Test
    fun aDetailTabRendersTheFlagItNames() {
        show(testStore(ScreenFixtures.companion), "new-onboarding", title = "Onboarding")

        composeRule.onNodeWithText("Onboarding").assertExists()
    }

    @Test
    fun anEnumIsARadioListOfItsCases() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.tier")
        composeRule.onNodeWithText("Free").assertIsSelected()

        composeRule.onNodeWithText("Pro").performClick()

        composeRule.onNodeWithText("Pro").assertIsSelected()
        assertEquals(FlagValueBox.String("pro"), store.value(store.requireEntry("checkout.tier")))
    }

    @Test
    fun theDetailsNameTheKeyTheDefaultAndTheRemoteKey() {
        show(testStore(ScreenFixtures.companion), "checkout.endpoint")

        // The row and the Details section both show the key and the value.
        composeRule.onAllNodesWithText("checkout.endpoint").assertCountEquals(2)
        composeRule.onAllNodesWithText("https://example.com").assertCountEquals(2)
        composeRule.onNodeWithText("Yes — checkout_endpoint").assertExists()
        composeRule.onNodeWithText("This flag carries a remote key, so a payload can change it.").assertExists()
    }

    @Test
    fun aFlagWithoutARemoteKeySaysSo() {
        show(testStore(ScreenFixtures.companion), "max-items")

        composeRule.onNodeWithText("No").assertExists()
        composeRule.onNodeWithText(
            "No remote key, so nothing a payload contains can change this flag. For a flag that decides which " +
                "payload gets fetched, that is deliberate — otherwise the two drive each other.",
        ).assertExists()
    }

    @Test
    fun resetIsOfferedOnlyWhenOverridden() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store, "max-items")
        assertTrue(store.isOverridden(store.requireEntry("max-items")))

        composeRule.onNodeWithText("Reset to the app's default").performClick()

        composeRule.onNodeWithText("Reset to the app's default").assertDoesNotExist()
        assertFalse(store.isOverridden(store.requireEntry("max-items")))
    }
}
