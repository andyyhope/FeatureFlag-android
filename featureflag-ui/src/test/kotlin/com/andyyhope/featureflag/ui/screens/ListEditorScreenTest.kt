package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
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
class ListEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(store: FlagEditingStore, key: String) {
        composeRule.setFlagContent { ListEditorScreen(store = store, key = key, onBack = {}) }
    }

    private fun FlagEditingStore.elements(key: String): List<FlagValueBox> =
        (value(requireEntry(key)) as FlagValueBox.Array).value

    private fun openActions(row: Int) {
        composeRule.onAllNodesWithContentDescription("Actions")[row].performClick()
    }

    // Building

    @Test
    fun theEditorBuilds() {
        show(testStore(ScreenFixtures.editors), "markets")

        composeRule.onNodeWithText("Markets").assertExists()
        composeRule.onNodeWithText("2 items").assertExists()
        composeRule.onNodeWithText(
            "Every element is a string. Removing them all leaves an empty array, which is a value like any " +
                "other — reset the flag if you want the app's default back.",
        ).assertExists()
    }

    @Test
    fun anElementRowBuildsForEveryScalar() {
        val store = testStore(ScreenFixtures.elements)
        var key by mutableStateOf("strings")
        composeRule.setFlagContent { ListEditorScreen(store = store, key = key, onBack = {}) }

        listOf("strings", "ints", "doubles", "bools", "dates", "urls", "blobs").forEach { element ->
            key = element
            composeRule.waitForIdle()

            composeRule.onNodeWithText("1 item").assertExists()
            composeRule.onAllNodesWithContentDescription("Actions").assertCountEquals(1)
        }
    }

    // Editing

    @Test
    fun addingAppendsAnEmptyElementOfTheRightType() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        composeRule.onNodeWithText("Add").performClick()

        composeRule.onNodeWithText("3 items").assertExists()
        assertEquals(
            listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"), FlagValueBox.String("")),
            store.elements("markets"),
        )
    }

    @Test
    fun editingAnElementWritesTheWholeArrayBack() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "counts")
        val field = composeRule.textFieldShowing("2")

        field.performClick()
        field.performTextReplacement("20")
        field.performImeAction()
        composeRule.waitForIdle()

        assertEquals(listOf(FlagValueBox.Int(1), FlagValueBox.Int(20)), store.elements("counts"))
    }

    @Test
    fun anUneditableElementIsRefusedAndWarns() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "counts")
        val field = composeRule.textFieldShowing("2")

        field.performClick()
        field.performTextReplacement("two")
        field.performImeAction()

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertExists()
        assertFalse(store.isOverridden(store.requireEntry("counts")))
    }

    // Reordering and removing

    @Test
    fun movingAnElementDownSwapsItWithTheNext() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        openActions(0)
        composeRule.onNodeWithText("Move down").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(FlagValueBox.String("NZ"), FlagValueBox.String("AU")), store.elements("markets"))
    }

    @Test
    fun theFirstElementCannotMoveUp() {
        show(testStore(ScreenFixtures.editors), "markets")

        openActions(0)

        composeRule.onNodeWithText("Move up").assertIsNotEnabled()
    }

    @Test
    fun deletingAnElementLeavesTheRest() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        openActions(0)
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(FlagValueBox.String("NZ")), store.elements("markets"))
    }

    @Test
    fun anEmptyArrayIsStillAnOverrideUntilItIsReset() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        repeat(2) {
            openActions(0)
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText("0 items").assertExists()
        assertTrue(store.isOverridden(store.requireEntry("markets")))
        composeRule.onNodeWithText("Reset to the app's default").performClick()
        composeRule.onNodeWithText("2 items").assertExists()
        assertFalse(store.isOverridden(store.requireEntry("markets")))
    }
}
