package com.andyyhope.featureflag.ui.screens

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SignalCountdownNotificationTest {

    private enum class TestSignal(override val signalDescription: String) : FlagSignal {
        Refetch("Re-fetch remote configuration"),
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val gate = CompletableDeferred<Boolean>()
    private lateinit var model: FlagSignalsModel

    private suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
        check(signal == TestSignal.Refetch && delay > Duration.ZERO)
        return gate.await()
    }

    private fun showNotification(isShown: () -> Boolean = { true }) {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = ::send, scope = scope) }
            if (isShown()) SignalCountdownNotification(model = model, timeout = 5.seconds)
        }
    }

    private fun startCountdown() {
        composeRule.runOnIdle {
            model.delay.value = FlagSignalDelay.Three
            model.tapped(TestSignal.Refetch)
        }
        composeRule.waitForIdle()
    }

    // The notification

    @Test
    fun aCountdownPostsTheNotificationAndTheAnswerRemovesIt() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showNotification()

        startCountdown()

        assertEquals(1, shadowOf(manager).allNotifications.size)
        gate.complete(true)
        composeRule.waitForIdle()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun leavingTheTabRemovesTheNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var isShown by mutableStateOf(true)
        showNotification { isShown }
        startCountdown()

        isShown = false
        composeRule.waitForIdle()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    // The permission

    @Test
    fun choosingADelayAsksForThePermission() {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = ::send, scope = scope) }
            RequestNotificationPermissionOnDelay(model)
        }
        assertNull(shadowOf(composeRule.activity).lastRequestedPermission)

        composeRule.runOnIdle { model.delay.value = FlagSignalDelay.Three }
        composeRule.waitForIdle()

        val request = checkNotNull(shadowOf(composeRule.activity).lastRequestedPermission)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
    }

    @Test
    fun aGrantedPermissionIsNotAskedFor() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = ::send, scope = scope) }
            RequestNotificationPermissionOnDelay(model)
        }

        composeRule.runOnIdle { model.delay.value = FlagSignalDelay.Five }
        composeRule.waitForIdle()

        assertNull(shadowOf(composeRule.activity).lastRequestedPermission)
    }
}
