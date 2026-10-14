package com.andyyhope.featureflag.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The build the B tasks' screens rely on: the compose compiler runs on this module, Material 3
 * draws under Robolectric with ui-test-junit4, and robolectric.properties gives a phone-sized screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ComposeScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aComposableRendersAndRecomposesUnderRobolectric() {
        compose.setContent { MaterialTheme { Counter() } }

        compose.onNodeWithText("Tapped 0 times").assertIsDisplayed().performClick()

        compose.onNodeWithText("Tapped 1 times").assertIsDisplayed()
    }

    @Test
    fun theScreenIsPhoneSized() {
        val configuration = ApplicationProvider.getApplicationContext<Application>().resources.configuration

        assertEquals(411, configuration.screenWidthDp)
        assertEquals(891, configuration.screenHeightDp)
    }

    @Composable
    private fun Counter() {
        var taps by remember { mutableIntStateOf(0) }
        Text("Tapped $taps times", Modifier.fillMaxSize().clickable { taps++ })
    }
}
