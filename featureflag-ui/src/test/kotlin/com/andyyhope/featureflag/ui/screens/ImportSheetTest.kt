package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
class ImportSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var isShown by mutableStateOf(true)

    private fun show(store: FlagEditingStore) {
        composeRule.setFlagContent {
            if (isShown) ImportSheet(store = store, onDismiss = { isShown = false })
        }
    }

    private fun importText(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(text)
        composeRule.onNode(hasText("Import") and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun importingJsonAppliesItAndCloses() {
        val store = testStore(ScreenFixtures.companion)
        show(store)

        importText("""{"formatVersion":1,"values":{"max-items":3}}""")

        assertEquals(FlagValueBox.Int(3), store.value(store.requireEntry("max-items")))
        assertFalse(isShown)
    }

    @Test
    fun importingACodeAppliesIt() {
        val exporter = testStore(ScreenFixtures.companion, mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))
        val store = testStore(ScreenFixtures.companion)
        show(store)

        importText("  ${exporter.qrCode()}\n")

        assertEquals(listOf(FlagKey("new-onboarding")), store.overriddenKeys)
        assertFalse(isShown)
    }

    @Test
    fun aRejectedDocumentListsEveryProblemAndStaysOpen() {
        val store = testStore(ScreenFixtures.companion)
        show(store)

        importText("""{"formatVersion":1,"values":{"nope":1,"max-items":"lots"}}""")

        composeRule.onNodeWithText("max-items: wrong type\nnope: not a flag in this app").assertExists()
        assertTrue(isShown)
        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun importIsDisabledUntilThereIsText() {
        show(testStore(ScreenFixtures.companion))
        val importButton = composeRule.onNode(hasText("Import") and hasClickAction())
        importButton.assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("{")

        importButton.assertIsEnabled()
    }

    @Test
    fun cancelChangesNothing() {
        val store = testStore(ScreenFixtures.companion)
        show(store)

        composeRule.onNode(hasSetTextAction()).performTextInput("""{"formatVersion":1,"values":{"max-items":3}}""")
        composeRule.onNodeWithText("Cancel").performClick()

        assertFalse(isShown)
        assertTrue(store.overriddenKeys.isEmpty())
    }
}
