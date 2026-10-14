package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BrowserScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()
    private var backs = 0

    private fun showBrowser(store: FlagEditingStore) {
        composeRule.setFlagContent { BrowserScreen(store = store, onOpen = { opened += it }) }
    }

    private fun showGroup(store: FlagEditingStore, path: List<String>) {
        composeRule.setFlagContent {
            GroupScreen(store = store, path = path, onOpen = { opened += it }, onBack = { backs++ })
        }
    }

    private fun search(text: String) {
        composeRule.onNodeWithText("Search flags").performTextInput(text)
    }

    // The tree

    @Test
    fun rootFlagsComeFirstThenTheGroups() {
        showBrowser(testStore(ScreenFixtures.companion))

        composeRule.onNodeWithText("New onboarding experience").assertExists()
        composeRule.onNodeWithText("Maximum items").assertExists()
        composeRule.onNodeWithText("Groups").assertExists()
        composeRule.onNode(hasText("Checkout") and hasText("7 flags")).assertExists()
        composeRule.onNodeWithText("Apple Pay").assertDoesNotExist()
    }

    @Test
    fun theTitleIsTheApplicationNameOrFeatureFlags() {
        showBrowser(testStore(ScreenFixtures.companion.copy(applicationName = null)))

        composeRule.onNodeWithText("Feature Flags").assertExists()
    }

    @Test
    fun theSchemaDescriptionShowsUntilASearchStarts() {
        showBrowser(testStore(ScreenFixtures.companion))
        composeRule.onNodeWithText("What the checkout team can turn on").assertExists()

        search("apple")

        composeRule.onNodeWithText("What the checkout team can turn on").assertDoesNotExist()
    }

    @Test
    fun aGroupBadgeUpdatesWhileTheBrowserIsShown() {
        val store = testStore(ScreenFixtures.companion)
        showBrowser(store)
        composeRule.onNode(hasText("Checkout") and hasText("1")).assertDoesNotExist()

        runBlocking { store.setValue(store.requireEntry("checkout.apple-pay"), FlagValueBox.Bool(true)) }

        composeRule.onNode(hasText("Checkout") and hasText("1")).assertExists()
    }

    @Test
    fun tappingAGroupOpensIt() {
        showBrowser(testStore(ScreenFixtures.companion))

        composeRule.onNodeWithText("Checkout").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Group(listOf("checkout"))), opened)
    }

    // Searching

    @Test
    fun searchingFlattensTheTreeIntoSections() {
        showBrowser(testStore(ScreenFixtures.companion))

        search("express")

        composeRule.onNodeWithText("Checkout › Express").assertExists()
        composeRule.onNodeWithText("Label").assertExists()
        composeRule.onNodeWithText("Tags").assertExists()
        composeRule.onNodeWithText("Groups").assertDoesNotExist()
        composeRule.onNodeWithText("New onboarding experience").assertDoesNotExist()
    }

    @Test
    fun aMatchInAGroupIsHeadedByTheGroup() {
        showBrowser(testStore(ScreenFixtures.companion))

        search("apple")

        composeRule.onNodeWithText("Checkout").assertExists()
        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Ratio").assertDoesNotExist()
    }

    @Test
    fun aSearchWithNoMatchesSaysSo() {
        showBrowser(testStore(ScreenFixtures.companion))

        search("zzzz")

        composeRule.onNodeWithText("No matches").assertExists()
        composeRule.onNodeWithText("No flag matches “zzzz”.").assertExists()
    }

    @Test
    fun anEmptySchemaSaysNoFlags() {
        showBrowser(testStore(ScreenFixtures.empty))

        composeRule.onNodeWithText("No flags").assertExists()
        composeRule.onNodeWithText("The app has not published any flags yet.").assertExists()
    }

    // The group screen

    @Test
    fun theGroupScreenListsItsFlagsThenItsSubgroups() {
        showGroup(testStore(ScreenFixtures.companion), listOf("checkout"))

        composeRule.onNodeWithText("Checkout").assertExists()
        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.scrollTo("Express")
        composeRule.onNodeWithText("Groups").assertExists()
        composeRule.onNode(hasText("Express") and hasText("2 flags")).assertExists()
    }

    @Test
    fun aGroupWithOnlySubgroupsHasNoGroupsHeader() {
        val schema = FlagSchema(
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            flags = listOf(
                ScreenFixtures.entry("checkout.express.one-tap", FlagValueType.Bool, FlagValueBox.Bool(false), "One tap"),
            ),
            groups = listOf(
                FlagSchema.Group(listOf("checkout"), "Checkout"),
                FlagSchema.Group(listOf("checkout", "express"), "Express"),
            ),
        )
        showGroup(testStore(schema), listOf("checkout"))

        composeRule.onNode(hasText("Express") and hasText("1 flag")).assertExists()
        composeRule.onNodeWithText("Groups").assertDoesNotExist()
    }

    @Test
    fun theBackArrowLeavesTheGroup() {
        showGroup(testStore(ScreenFixtures.companion), listOf("checkout"))

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
