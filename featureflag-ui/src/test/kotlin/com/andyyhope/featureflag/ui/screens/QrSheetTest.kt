package com.andyyhope.featureflag.ui.screens

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.share.forgetFileProviderStrategies
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun freshProviderStrategies() = forgetFileProviderStrategies()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var dismissals = 0

    private fun show(store: FlagEditingStore) {
        composeRule.setFlagContent { QrSheet(store = store, onDismiss = { dismissals++ }) }
    }

    /** A file where the PNGs' directory should be, so writing one fails. */
    private fun blockTheImageDirectory() {
        val directory = File(context.cacheDir, "featureflag")
        directory.deleteRecursively()
        directory.writeText("not a directory")
    }

    private fun overridden() = testStore(ScreenFixtures.companion, mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))

    /** iOS FlagQRCodeTextTests' incompressible text: base64 of 6,000 bytes from a 64-bit LCG. */
    private fun incompressible(): String {
        var state = 0x9E3779B97F4A7C15uL
        val bytes = ByteArray(6_000) {
            state = state * 6364136223846793005uL + 1442695040888963407uL
            (state shr 33).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun theSheetBuilds() {
        show(overridden())

        composeRule.onNodeWithText("Scan to copy").assertExists()
        composeRule.onNodeWithContentDescription("QR code containing 1 overrides").assertExists()
        composeRule.onNodeWithText("Copy image").assertExists()
        composeRule.onNodeWithText("Save").assertExists()
        composeRule.onNodeWithText("Copy code").assertExists()
        composeRule.onNodeWithText("1 override(s)").assertExists()
    }

    @Test
    fun copyImagePutsThePngOnTheClipboard() {
        show(overridden())

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Copy image").performClick()
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Image copied").assertExists()
        val clip = checkNotNull(checkNotNull(context.getSystemService(ClipboardManager::class.java)).primaryClip)
        assertTrue(clip.description.hasMimeType("image/png"))
        assertEquals("${context.packageName}.featureflag.files", clip.getItemAt(0).uri.authority)
        assertTrue(File(context.cacheDir, "featureflag/feature-flags-1-overrides.png").exists())
    }

    @Test
    fun copyCodeCopiesTextTheImportFieldAccepts() {
        val store = overridden()
        show(store)

        composeRule.onNodeWithText("Copy code").performClick()

        assertEquals(store.qrCode(), clipboardText())
        assertTrue(store.qrCode().startsWith("FFQR1:"))
    }

    @Test
    fun saveOffersThePngToTheChooser() {
        show(overridden())

        composeRule.onNodeWithText("Save").performClick()

        val chooser = checkNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals("image/png", send.type)
        val stream = checkNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("${context.packageName}.featureflag.files", stream.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun anImageThatCannotBeCopiedSaysWhyRatherThanCrashing() {
        blockTheImageDirectory()
        show(overridden())

        composeRule.onNodeWithText("Copy image").performClick()

        composeRule.onNodeWithText("Could not write the image:", substring = true).assertExists()
        composeRule.onNodeWithText("Image copied").assertDoesNotExist()
        composeRule.onNodeWithText("Copy image").assertExists()
    }

    @Test
    fun anImageThatCannotBeSavedSaysWhyRatherThanCrashing() {
        blockTheImageDirectory()
        show(overridden())

        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithText("Could not write the image:", substring = true).assertExists()
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun aPayloadTooLargeExplainsItself() {
        val store = testStore(
            ScreenFixtures.companion,
            mapOf(FlagKey("checkout.express.label") to FlagValueBox.String(incompressible())),
        )
        val tooLarge = assertFailsWith<FlagQRCodeException.PayloadTooLarge> { store.qrCode() }
        show(store)

        composeRule.onNodeWithText(
            "1 overrides need ${tooLarge.bytes} characters, and a QR code holds ${tooLarge.limit}. " +
                "Reset a few, or export JSON instead.",
        ).assertExists()
        composeRule.onNodeWithText("Copy image").assertDoesNotExist()
        composeRule.onNodeWithText("Copy code").assertDoesNotExist()
    }

    @Test
    fun doneClosesTheSheet() {
        show(overridden())

        composeRule.onNodeWithText("Done").performClick()

        assertEquals(1, dismissals)
    }
}
