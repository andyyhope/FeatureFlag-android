package com.andyyhope.featureflag.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalGroupDisplay
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SignalsScreenTest {

    private enum class AppSignal(override val signalDescription: String) : FlagSignal {
        RefetchRemoteConfiguration("Re-fetch remote configuration"),
        ClearRemoteConfiguration("Clear remote configuration"),
    }

    private enum class CacheSignal(override val signalDescription: String, override val requiresRestart: Boolean) : FlagSignal {
        PurgeImageCache("Purge the image cache", requiresRestart = false),
        PurgeEverything("Purge everything", requiresRestart = true),
    }

    private enum class SevenSignal : FlagSignal { One, Two, Three, Four, Five, Six, Seven }

    /** The bridge client's send: records each call, answers [answer], and waits on [gate] when one is set. */
    private class FakeSend {
        val sent = mutableListOf<Pair<FlagSignal, Duration>>()
        var answer = true
        var gate: CompletableDeferred<Unit>? = null
        var wasCancelled = false

        suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
            sent += signal to delay
            try {
                gate?.await()
            } catch (cancelled: CancellationException) {
                wasCancelled = true
                throw cancelled
            }
            return answer
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeSend()
    private val opened = mutableListOf<FlagSignalGroup>()
    private val start: Instant = Instant.parse("2026-01-01T15:04:05Z")

    /** The model lives outside the composition, as it does in the ViewModel. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = FlagSignalsModel(send = fake::send, scope = scope, clock = { start })

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val grouped = listOf(
        FlagSignalGroup("Configuration", AppSignal.entries),
        FlagSignalGroup("Caches", CacheSignal.entries),
    )

    private fun show(groups: List<FlagSignalGroup>, isShown: () -> Boolean = { true }) {
        composeRule.setFlagContent {
            if (isShown()) {
                CancelPendingOnLeave(model)
                SignalsScreen(model = model, groups = groups, onOpenGroup = { opened += it }, clock = { start })
            }
        }
    }

    private fun ring() = composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true)

    // Layout

    @Test
    fun theGroupedViewBuilds() {
        show(grouped)

        composeRule.onNodeWithText("Signals").assertExists()
        composeRule.onNodeWithText("Delay").assertExists()
        listOf("Instant", "3s", "5s", "10s").forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithText("Configuration").assertExists()
        composeRule.onNodeWithText("Caches").assertExists()
        composeRule.onNodeWithText("Re-fetch remote configuration").assertExists()
        composeRule.onNodeWithText("Purge everything").assertExists()
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun theSingleTypeFormIsOneFlatGroup() {
        show(listOf(FlagSignalGroup("", AppSignal.entries, display = FlagSignalGroupDisplay.Flat)))

        composeRule.onNodeWithText("Re-fetch remote configuration").assertExists()
        composeRule.onNodeWithText("Signals carry no payload. State belongs in flags, which you can already edit.").assertExists()
    }

    @Test
    fun theDelayFooterFollowsTheChoice() {
        show(grouped)
        composeRule.onNodeWithText(
            "Sent the moment you tap. The app has to be running to hear it, and opening this one puts it in the background.",
        ).assertExists()

        composeRule.onNodeWithText("5s").performClick()

        composeRule.onNodeWithText("5s").assertIsSelected()
        composeRule.onNodeWithText(
            "Tap a signal, then switch to the app. It fires when the circle completes, by which time the app is in " +
                "front and listening.",
        ).assertExists()
        assertEquals(FlagSignalDelay.Five, model.delay.value)
    }

    @Test
    fun aGroupPastTheThresholdIsNestedUnderGroups() {
        val seven = FlagSignalGroup("Seven", SevenSignal.entries)
        show(listOf(seven))

        composeRule.onNodeWithText("Groups").assertExists()
        composeRule.onNodeWithText("One").assertDoesNotExist()
        composeRule.onNode(hasText("Seven") and hasText("7")).performClick()

        assertEquals(listOf(seven), opened)
    }

    // Sending

    @Test
    fun anInstantSignalIsSentAndItsOutcomeRecorded() {
        show(grouped)

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        composeRule.onNodeWithText("Recent").assertExists()
        composeRule.onNodeWithText("handled").assertExists()
        assertEquals(listOf<Pair<FlagSignal, Duration>>(AppSignal.RefetchRemoteConfiguration to Duration.ZERO), fake.sent)
    }

    @Test
    fun aSignalThatNeedsARelaunchSaysSoBeforeAndAfter() {
        show(grouped)
        composeRule.onNodeWithText("Takes effect after a relaunch").assertExists()

        composeRule.onNodeWithText("Purge everything").performClick()

        composeRule.onNodeWithText("handled — relaunch to see it").assertExists()
    }

    @Test
    fun anUnansweredSignalIsNoResponse() {
        fake.answer = false
        show(grouped)

        composeRule.onNodeWithText("Clear remote configuration").performClick()

        composeRule.onNodeWithText("no response").assertExists()
    }

    @Test
    fun eachResultShowsItsLocalTime() {
        show(grouped)

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(start.atZone(ZoneId.systemDefault()))
        composeRule.onNodeWithText(time).assertExists()
    }

    @Test
    fun theHistoryKeepsTheFiveNewest() {
        show(listOf(FlagSignalGroup("Configuration", listOf(AppSignal.RefetchRemoteConfiguration))))

        repeat(6) {
            // Once there is history, the same text is also a Recent row; only the signal's row clicks.
            composeRule.onNode(hasText("Re-fetch remote configuration") and hasClickAction()).performClick()
            composeRule.waitForIdle()
        }

        composeRule.onAllNodesWithText("handled").assertCountEquals(5)
    }

    @Test
    fun aSpinnerShowsWhileAnInstantSendIsInFlight() {
        fake.gate = CompletableDeferred()
        composeRule.mainClock.autoAdvance = false
        show(grouped)
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.mainClock.advanceTimeBy(100)

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Clear remote configuration").assertIsNotEnabled()
    }

    // Delayed signals

    @Test
    fun aDelayedSignalShowsTheRingAndTappingAgainCancels() {
        fake.gate = CompletableDeferred()
        show(listOf(FlagSignalGroup("", AppSignal.entries, display = FlagSignalGroupDisplay.Flat)))

        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        ring().assertExists()
        composeRule.onNodeWithText("Tap again to cancel.").assertExists()
        assertEquals(listOf<Pair<FlagSignal, Duration>>(AppSignal.RefetchRemoteConfiguration to 3.seconds), fake.sent)

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        ring().assertDoesNotExist()
        assertTrue(fake.wasCancelled)
        assertNull(model.pending.value)
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun otherSignalsAndTheDelayAreDisabledDuringACountdown() {
        fake.gate = CompletableDeferred()
        show(grouped)

        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        composeRule.onNodeWithText("Re-fetch remote configuration").assertIsEnabled()
        composeRule.onNodeWithText("Clear remote configuration").assertIsNotEnabled()
        composeRule.onNodeWithText("5s").assertIsNotEnabled()
    }

    @Test
    fun theRingFillsAsTheDelayRunsDown() {
        fake.gate = CompletableDeferred()
        show(grouped)
        composeRule.onNodeWithText("3s").performClick()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        ring().assert(progressNear(0f))

        composeRule.mainClock.advanceTimeBy(1_500)

        ring().assert(progressNear(0.5f))
    }

    @Test
    fun leavingTheTabCancelsThePendingSend() {
        fake.gate = CompletableDeferred()
        var isShown by mutableStateOf(true)
        show(grouped, isShown = { isShown })
        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.waitForIdle()

        isShown = false
        composeRule.waitForIdle()

        assertNull(model.pending.value)
        assertTrue(fake.wasCancelled)
        assertTrue(model.history.value.isEmpty())
    }

    @Test
    fun aConfigurationChangeKeepsThePendingSend() {
        fake.gate = CompletableDeferred()
        show(grouped)
        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.recreate()

        assertEquals(AppSignal.RefetchRemoteConfiguration, model.pending.value)
        assertFalse(fake.wasCancelled)
    }

    @Test
    fun onlyAConfigurationChangeSkipsTheCancel() {
        assertTrue(shouldCancelOnLeave(isChangingConfigurations = false))
        assertFalse(shouldCancelOnLeave(isChangingConfigurations = true))
    }

    // A group on its own screen

    @Test
    fun theGroupScreenRepeatsTheDelayAndTheHistory() {
        val seven = FlagSignalGroup("Seven", SevenSignal.entries)
        composeRule.setFlagContent {
            SignalGroupScreen(model = model, group = seven, onBack = {}, clock = { start })
        }

        // The title bar and the seventh signal.
        composeRule.onAllNodesWithText("Seven").assertCountEquals(2)
        composeRule.onNodeWithText("Delay").assertExists()
        composeRule.onNodeWithText("One").performClick()

        composeRule.onNodeWithText("Recent").assertExists()
    }

    private fun progressNear(expected: Float) = SemanticsMatcher("progress near $expected") { node ->
        val progress = node.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }?.current
        progress != null && abs(progress - expected) < 0.05f
    }
}
