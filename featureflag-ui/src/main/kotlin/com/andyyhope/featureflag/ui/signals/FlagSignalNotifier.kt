package com.andyyhope.featureflag.ui.signals

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.R
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The countdown for a delayed signal, shown outside the app while someone switches to the host (the
 * stand-in for iOS's Live Activity).
 * - API 36 and later: a ProgressStyle notification that asks to be promoted to a Live Update.
 * - Earlier: a chronometer counting down to the delivery, over a progress bar.
 * It times out after delay + timeout, so a frozen companion cannot leave it behind. Without the
 * notification permission it posts nothing, and the send goes ahead regardless.
 */
internal class FlagSignalNotifier(
    private val context: Context,
    private val clock: () -> Instant = Instant::now,
) {

    private val manager: NotificationManager =
        checkNotNull(context.getSystemService(NotificationManager::class.java)) { "This device has no notification manager." }

    /** Posts, or updates, the countdown for [signal]. [timeout] is how long the host has to answer once the delay ends. */
    fun show(signal: FlagSignal, countdown: FlagSignalCountdown, timeout: Duration) {
        if (!canPost()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, FlagStrings.NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_LOW),
        )
        val progress = (countdown.progress(clock()) * PROGRESS_MAX).roundToInt()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.featureflag_ic_signal)
            .setContentTitle(signal.signalDescription)
            .setContentText(FlagStrings.SENDING_IN)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(countdown.end.toEpochMilli())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((countdown.duration + timeout).inWholeMilliseconds)
        launchIntent()?.let { builder.setContentIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX)))
                    .setProgress(progress),
            )
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        } else {
            builder.setProgress(PROGRESS_MAX, progress, false)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Tapping it brings the companion back. */
    private fun launchIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    companion object {
        const val CHANNEL_ID = "featureflag.signal-countdown"
        const val NOTIFICATION_ID = 0x0F1A
        private const val PROGRESS_MAX = 1_000

        /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING, which setRequestPromotedOngoing sets; both are API 36.1. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
