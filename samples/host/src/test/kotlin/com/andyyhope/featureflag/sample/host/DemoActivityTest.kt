package com.andyyhope.featureflag.sample.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DemoActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<DemoActivity>()

    // The launcher activity

    @Test
    fun theActivityShowsTheApplicationsModel() {
        composeRule.onNodeWithText("Demo").assertIsDisplayed()
        composeRule.onNode(hasText("Payload applied") and hasAnySibling(hasText("Production"))).assertIsDisplayed()
    }
}
