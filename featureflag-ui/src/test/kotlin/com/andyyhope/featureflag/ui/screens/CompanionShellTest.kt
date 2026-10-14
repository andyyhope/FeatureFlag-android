package com.andyyhope.featureflag.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.companion.FlagCompanionState
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CompanionShellTest {

    private enum class AppSignal(override val signalDescription: String) : FlagSignal {
        RefetchRemoteConfiguration("Re-fetch remote configuration"),
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Owns a signals model the way FlagCompanionViewModel does: in viewModelScope, kept across recreation. */
    private class SignalsViewModel(send: suspend (FlagSignal, Duration) -> Boolean) : ViewModel() {
        val signals = FlagSignalsModel(send = send, scope = viewModelScope)
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var retries = 0
    private var refreshes = 0
    private val sent = mutableListOf<Pair<FlagSignal, Duration>>()
    private var gate: CompletableDeferred<Unit>? = null
    private var sendWasCancelled = false

    private val signals = FlagCompanionTab.Signals(FlagSignalGroup("Configuration", AppSignal.entries))

    /** The shell's signals model lives outside the composition, as it does in the ViewModel. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = FlagSignalsModel(send = ::send, scope = scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
        sent += signal to delay
        try {
            gate?.await()
        } catch (cancelled: CancellationException) {
            sendWasCancelled = true
            throw cancelled
        }
        return true
    }

    private fun content(state: FlagCompanionState, tabs: List<FlagCompanionTab>): @Composable () -> Unit = {
        MaterialTheme {
            FlagCompanionContent(
                state = state,
                tabs = tabs,
                signals = model,
                onRetry = { retries++ },
                onRefresh = { refreshes++ },
            )
        }
    }

    private fun show(state: FlagCompanionState, tabs: List<FlagCompanionTab>) {
        composeRule.setContent(content(state, tabs))
    }

    private fun ready(store: FlagEditingStore = testStore(ScreenFixtures.companion)) =
        FlagCompanionState.Ready(store, failure = null)

    private fun tab(title: String) = composeRule.onNode(hasText(title) and isSelectable())

    // Tabs

    @Test
    fun theDefaultLayoutShowsOverridesAndFlags() {
        show(ready(), FlagCompanionTab.Defaults)

        tab("Overrides").assertIsSelected()
        tab("Flags").assertIsNotSelected()
        composeRule.onNodeWithText("Nothing overridden").assertExists()
    }

    @Test
    fun theFirstTabIsSelected() {
        show(ready(), listOf(FlagCompanionTab.Flags, FlagCompanionTab.Overrides))

        tab("Flags").assertIsSelected()
        composeRule.onNodeWithText("Demo").assertExists()
    }

    @Test
    fun signalsCanBeLeftOutEntirely() {
        show(ready(), listOf(FlagCompanionTab.Overrides))

        tab("Overrides").assertIsSelected()
        tab("Signals").assertDoesNotExist()
        tab("Flags").assertDoesNotExist()
    }

    @Test
    fun theSignalsViewBuilds() {
        show(ready(), listOf(FlagCompanionTab.Overrides, signals))

        tab("Signals").performClick()

        composeRule.onNodeWithText("Delay").assertExists()
        composeRule.onNodeWithText("Re-fetch remote configuration").assertExists()
    }

    @Test
    fun aDetailTabRendersTheFlagItNames() {
        show(ready(), listOf(FlagCompanionTab.Detail(key = "new-onboarding", title = "Onboarding")))

        tab("Onboarding").assertIsSelected()
        // The navigation item and the title bar.
        composeRule.onAllNodesWithText("Onboarding").assertCountEquals(2)
        composeRule.onNodeWithText("Details").assertExists()
    }

    @Test
    fun aCustomTabCarriesItsOwnIdentityAndContent() {
        val custom = FlagCompanionTab.Custom(id = "environment", title = "Environment", icon = Icons.Filled.Settings) {
            Column {
                Text(text = "${store.schema.flags.size} flags published")
                FlagRow(store.requireEntry("new-onboarding"))
            }
        }
        show(ready(), listOf(custom))

        composeRule.onNodeWithText("9 flags published").assertExists()
        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()

        composeRule.onNodeWithText("Overridden").assertExists()
    }

    // Status

    @Test
    fun connectingShowsAProgressIndicator() {
        composeRule.mainClock.autoAdvance = false
        show(FlagCompanionState.Connecting, FlagCompanionTab.Defaults)
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun theUnavailableViewBuilds() {
        show(FlagCompanionState.Unavailable("Run the host app."), FlagCompanionTab.Defaults)

        composeRule.onNodeWithText("No flags yet").assertExists()
        composeRule.onNodeWithText("Run the host app.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun anEmptyListExplainsItselfRatherThanRenderingNothing() {
        show(ready(), emptyList())

        composeRule.onNodeWithText("No tabs").assertExists()
        composeRule.onNodeWithText(
            "This companion was given an empty tab list, so there is nothing to show. Pass at least one, or omit " +
                "the argument for the default two.",
        ).assertExists()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    // Failures

    @Test
    fun theLastFailureIsABannerWithTryAgain() {
        show(FlagCompanionState.Ready(testStore(ScreenFixtures.companion), FlagBridgeException.Unavailable(null)), FlagCompanionTab.Defaults)

        composeRule.onNodeWithText("The host could not be reached. Open the host app once, then try again.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun aFailedWriteIsShownInTheSnackbar() {
        val source = FailingSource { FlagBridgeException.Rejected("the host is read-only") }
        val store = FlagEditingStore(ScreenFixtures.companion, source, writeDispatcher = Dispatchers.Main.immediate)
        show(FlagCompanionState.Ready(store, failure = null), listOf(FlagCompanionTab.Flags))

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithText("The host rejected the change: the host is read-only").assertExists()
        composeRule.onNodeWithContentDescription("New onboarding experience").assertIsOff()
    }

    // Navigation

    @Test
    fun eachTabKeepsItsOwnBackStack() {
        show(ready(), listOf(FlagCompanionTab.Flags, FlagCompanionTab.Overrides))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()

        tab("Overrides").performClick()
        composeRule.onNodeWithText("Nothing overridden").assertExists()
        tab("Flags").performClick()

        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Search flags").assertDoesNotExist()
    }

    @Test
    fun systemBackPopsTheTabsStack() {
        show(ready(), listOf(FlagCompanionTab.Flags))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText("Search flags").assertExists()
        composeRule.onNodeWithText("Apple Pay").assertDoesNotExist()
    }

    @Test
    fun theBackArrowPopsToo() {
        show(ready(), listOf(FlagCompanionTab.Flags))
        composeRule.onNodeWithText("Checkout").performClick()

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Search flags").assertExists()
    }

    @Test
    fun aRouteSurvivesRecreation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent(content(ready(), listOf(FlagCompanionTab.Flags)))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Search flags").assertDoesNotExist()
    }

    // Signals

    @Test
    fun leavingTheSignalsTabCancelsItsCountdown() {
        gate = CompletableDeferred()
        show(ready(), listOf(signals, FlagCompanionTab.Overrides))
        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true).assertExists()

        tab("Overrides").performClick()
        composeRule.waitForIdle()

        assertTrue(sendWasCancelled)
        tab("Signals").performClick()
        composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun aPendingSignalSurvivesRecreationInTheShell() {
        gate = CompletableDeferred()
        val state = ready()
        val shell: @Composable () -> Unit = {
            val owner = viewModel { SignalsViewModel(::send) }
            MaterialTheme {
                FlagCompanionContent(
                    state = state,
                    tabs = listOf(signals, FlagCompanionTab.Overrides),
                    signals = owner.signals,
                    onRetry = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.setContent(shell)
        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true).assertExists()

        // A rotation: the activity is rebuilt, and its content with it, while the ViewModel is kept.
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent(content = shell) }

        composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true).assertExists()
        assertFalse(sendWasCancelled)
        assertEquals(1, sent.size)
    }

    // Lifecycle

    @Test
    fun everyStartAfterTheFirstCallsOnForeground() {
        val owner = TestLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED
        var starts = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                OnEveryStartAfterTheFirst { starts++ }
            }
        }
        composeRule.runOnIdle { assertEquals(0, starts) }

        repeat(2) {
            composeRule.runOnIdle {
                owner.registry.currentState = Lifecycle.State.CREATED
                owner.registry.currentState = Lifecycle.State.STARTED
            }
        }

        composeRule.runOnIdle { assertEquals(2, starts) }
    }
}
