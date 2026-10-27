package com.andyyhope.featureflag.sample.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real activity, with the real FlagCompanion and bridge client, on a device where the host is
 * not installed. The companion's own manifest is Robolectric's, so this also proves the
 * <uses-permission> placeholder was filled: otherwise it would say to add that line instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CompanionActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<CompanionActivity>()

    // Section: no host installed

    @Test
    fun withNoHostInstalledTheCompanionSaysTheHostWasNotFound() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No flags yet").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("No flags yet").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No app with the package 'com.andyyhope.featureflag.sample.host' is installed",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }
}
