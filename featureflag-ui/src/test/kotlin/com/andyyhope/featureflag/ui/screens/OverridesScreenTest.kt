package com.andyyhope.featureflag.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverridesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(store: FlagEditingStore) {
        composeRule.setFlagContent { OverridesScreen(store = store) }
    }

    private fun overridden() = testStore(
        ScreenFixtures.companion,
        mapOf(FlagKey("max-items") to FlagValueBox.Int(3), FlagKey("new-onboarding") to FlagValueBox.Bool(true)),
    )

    private fun clickAction(text: String) {
        composeRule.scrollTo(text)
        composeRule.onNodeWithText(text).performClick()
    }

    // Listing

    @Test
    fun nothingOverriddenSaysSoAndStillOffersImport() {
        show(testStore(ScreenFixtures.companion))

        composeRule.onNodeWithText("Nothing overridden").assertExists()
        composeRule.onNodeWithText("Every flag is reporting the value the app was built with.").assertExists()
        composeRule.onNodeWithText("Import…").assertExists()
        composeRule.onNodeWithText("JSON").assertDoesNotExist()
    }

    @Test
    fun eachOverrideIsListedWithItsValue() {
        show(overridden())

        composeRule.onNodeWithText("2 changed").assertExists()
        composeRule.onNodeWithText("New onboarding experience").assertExists()
        composeRule.onNodeWithText("true").assertExists()
        composeRule.onNodeWithText("Maximum items").assertExists()
        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithText("Tap the arrow to put a flag back to the app's default.").assertExists()
    }

    @Test
    fun aStoredValueTheSchemaRejectsIsNotListedAsAnOverride() {
        show(testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.String("nonsense"))))

        composeRule.onNodeWithText("Nothing overridden").assertExists()
    }

    @Test
    fun theArrowPutsOneFlagBack() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store)

        composeRule.onNodeWithContentDescription("Reset to the app's default").performClick()

        composeRule.onNodeWithText("Nothing overridden").assertExists()
        assertTrue(store.overriddenKeys.isEmpty())
    }

    // JSON

    @Test
    fun theJsonBlockCopiesTheExport() {
        show(overridden())

        composeRule.scrollTo("Only overrides travel \u2014 the defaults are already in the app. This is what a QR code carries, compressed.")
        composeRule.onNodeWithContentDescription("Copy JSON").performClick()

        val copied = checkNotNull(clipboardText())
        assertTrue(copied.startsWith("{") && "\"max-items\"" in copied && "\"new-onboarding\"" in copied, copied)
    }

    @Test
    fun shareJsonOffersTheExportToTheChooser() {
        show(overridden())

        clickAction("Share JSON")

        val chooser = checkNotNull(
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity,
        )
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue("\"max-items\"" in send.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
    }

    // Actions

    @Test
    fun resetEveryOverrideAsksFirst() {
        val store = overridden()
        show(store)

        clickAction("Reset every override")
        composeRule.onNodeWithText("Reset every override?").assertExists()
        composeRule.onNodeWithText("Every flag goes back to the value the app was built with.").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Reset every override?").assertDoesNotExist()
        assertEquals(2, store.overriddenKeys.size)

        clickAction("Reset every override")
        composeRule.onNodeWithText("Reset").performClick()

        composeRule.onNodeWithText("Nothing overridden").assertExists()
        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun showQrCodeOpensTheSheet() {
        show(overridden())

        clickAction("Show QR code")
        composeRule.onNodeWithText("Scan to copy").assertExists()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Scan to copy").assertDoesNotExist()
    }

    @Test
    fun importOpensTheSheet() {
        show(testStore(ScreenFixtures.companion))

        clickAction("Import…")

        composeRule.onNodeWithText("Paste exported JSON, or the contents of a scanned flag code.").assertExists()
    }
}
