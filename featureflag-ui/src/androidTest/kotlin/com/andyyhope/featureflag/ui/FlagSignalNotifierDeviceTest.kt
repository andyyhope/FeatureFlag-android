package com.andyyhope.featureflag.ui

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalCountdown
import com.andyyhope.featureflag.ui.signals.FlagSignalNotifier
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Posts the countdown on a device, opens the shade and screenshots it to
 * /data/local/tmp/featureflag-ui/7-notification.png for the controller to read.
 */
@RunWith(AndroidJUnit4::class)
class FlagSignalNotifierDeviceTest {

    private enum class DeviceSignal(override val signalDescription: String) : FlagSignal {
        Refetch("Re-fetch remote configuration"),
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @Test
    fun theCountdownIsPostedAndShownInTheShade() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("mkdir -p $DIRECTORY")
        val notifier = FlagSignalNotifier(context)

        notifier.show(DeviceSignal.Refetch, FlagSignalCountdown(Instant.now(), 10.seconds), timeout = 5.seconds)

        assertTrue(waitFor { manager.activeNotifications.any { it.id == FlagSignalNotifier.NOTIFICATION_ID } })
        shell("cmd statusbar expand-notifications")
        Thread.sleep(SHADE_SETTLE_MILLIS)
        shell("screencap -p $DIRECTORY/7-notification.png")
        shell("cmd statusbar collapse")
        assertTrue("7-notification.png" in shell("ls $DIRECTORY"), "screencap wrote no 7-notification.png")

        notifier.cancel()
        assertTrue(waitFor { manager.activeNotifications.none { it.id == FlagSignalNotifier.NOTIFICATION_ID } })
        assertFalse(manager.activeNotifications.any { it.id == FlagSignalNotifier.NOTIFICATION_ID })
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(POLLS) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        const val DIRECTORY = "/data/local/tmp/featureflag-ui"
        const val SHADE_SETTLE_MILLIS = 1_500L
        const val POLLS = 20
        const val POLL_MILLIS = 100L
    }
}
