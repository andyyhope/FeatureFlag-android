package com.andyyhope.featureflag.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The public entry point, with its real ViewModel and bridge session. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagCompanionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aHostThatIsNotInstalledShowsNoFlagsYet() {
        composeRule.setContent { MaterialTheme { FlagCompanion(hostPackage = "com.example.not.installed") } }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No flags yet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun duplicateTabIdsThrowTheIosMessage() {
        val error = assertFailsWith<IllegalArgumentException> {
            composeRule.setContent {
                MaterialTheme {
                    FlagCompanion(
                        hostPackage = "com.example.not.installed",
                        tabs = listOf(FlagCompanionTab.Flags, FlagCompanionTab.Flags),
                    )
                }
            }
        }

        assertEquals(
            "A companion cannot have two tabs with the id 'flags'. Give one of them a different id, or drop it.",
            error.message,
        )
    }
}
