package com.andyyhope.featureflag.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalNotifier
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val UpdateInterval = 1.seconds

/**
 * Shows [model]'s countdown as a notification while a delayed signal is pending, updating it once a
 * second, and removes it when the send ends, is cancelled, or this leaves the composition.
 */
@Composable
internal fun SignalCountdownNotification(model: FlagSignalsModel, timeout: Duration) {
    val context = LocalContext.current
    val notifier = remember(context) { FlagSignalNotifier(context.applicationContext) }
    val pending by model.pending.collectAsState()
    val countdown by model.countdown.collectAsState()
    LaunchedEffect(pending, countdown) {
        val signal = pending
        val running = countdown
        if (signal == null || running == null) {
            notifier.cancel()
            return@LaunchedEffect
        }
        // The chronometer counts down on its own; the updates only move the bar. One per second of the delay, then it rests.
        repeat(running.duration.inWholeSeconds.toInt() + 1) {
            notifier.show(signal, running, timeout)
            delay(UpdateInterval)
        }
    }
    DisposableEffect(notifier) {
        onDispose { notifier.cancel() }
    }
}

/**
 * Asks for POST_NOTIFICATIONS the first time a delay is chosen (API 33+). Refusing only loses the notification.
 * [hasAsked] is hoisted by the shell so it outlives this leaving the composition with the Signals tab.
 */
@Composable
internal fun RequestNotificationPermissionOnDelay(
    model: FlagSignalsModel,
    hasAsked: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val chosenDelay by model.delay.collectAsState()
    LaunchedEffect(chosenDelay) {
        val isGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (chosenDelay != FlagSignalDelay.Instant && !hasAsked.value && !isGranted) {
            hasAsked.value = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
