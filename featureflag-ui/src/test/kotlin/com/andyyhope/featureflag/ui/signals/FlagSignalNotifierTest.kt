package com.andyyhope.featureflag.ui.signals

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagSignalNotifierTest {

    private enum class TestSignal(override val signalDescription: String) : FlagSignal {
        Refetch("Re-fetch remote configuration"),
    }

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val start: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val countdown = FlagSignalCountdown(start, 5.seconds)

    private fun notifier(now: Instant = start) = FlagSignalNotifier(context) { now }

    private fun posted(): Notification =
        checkNotNull(shadowOf(manager).getNotification(FlagSignalNotifier.NOTIFICATION_TAG, FlagSignalNotifier.NOTIFICATION_ID)) {
            "No countdown is posted under the library's tag."
        }

    /** One of the companion app's own notifications, under the same id but no tag. */
    private fun postTheAppsOwnNotification(): Notification {
        val own = Notification.Builder(context, FlagSignalNotifier.CHANNEL_ID).setContentTitle("The app's own").build()
        manager.notify(FlagSignalNotifier.NOTIFICATION_ID, own)
        return own
    }

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun onApi36TheCountdownIsAProgressStyleLiveUpdate() {
        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val notification = posted()
        assertEquals("android.app.Notification\$ProgressStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals("Re-fetch remote configuration", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Sending in", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(FlagSignalNotifier.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun theChronometerCountsDownToTheMomentTheHostDelivers() {
        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val notification = posted()
        assertEquals(start.plusSeconds(5).toEpochMilli(), notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }

    @Test
    fun itTimesOutAfterTheDelayAndTheTimeout() {
        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        assertEquals(10_000L, posted().timeoutAfter)
    }

    @Test
    fun aRepostHalfwayThroughKeepsTheOriginalDeadline() {
        notifier(now = start.plusMillis(2_500)).show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        // end (start + 5 s) + timeout (5 s) - now (start + 2.5 s)
        assertEquals(7_500L, posted().timeoutAfter)
    }

    @Test
    fun aRepostPastTheDeadlineTimesOutAtOnce() {
        notifier(now = start.plusSeconds(30)).show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        assertEquals(1L, posted().timeoutAfter)
    }

    @Test
    fun theBarFollowsTheClock() {
        notifier(now = start.plusMillis(2_500)).show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val style = assertIs<Notification.ProgressStyle>(Notification.Builder.recoverBuilder(context, posted()).style)
        assertEquals(500, style.progress)
    }

    @Test
    fun withoutThePermissionNothingIsPosted() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun cancellingRemovesIt() {
        val notifier = notifier()
        notifier.show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        notifier.cancel()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun theCountdownNeitherReplacesNorCancelsTheAppsOwnNotification() {
        val own = postTheAppsOwnNotification()
        val notifier = notifier()

        notifier.show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        assertSame(own, shadowOf(manager).getNotification(FlagSignalNotifier.NOTIFICATION_ID))
        assertEquals("Re-fetch remote configuration", posted().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())

        notifier.cancel()

        assertEquals(listOf(own), shadowOf(manager).allNotifications)
    }

    @Test
    @Config(sdk = [35])
    fun beforeApi36ItIsAChronometerWithAProgressBar() {
        notifier(now = start.plusMillis(2_500)).show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val notification = posted()
        assertFalse(notification.extras.containsKey("android.requestPromotedOngoing"))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(1_000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(500, notification.extras.getInt(Notification.EXTRA_PROGRESS))
    }
}
