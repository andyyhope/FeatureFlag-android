package com.andyyhope.featureflag.sample.host

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.sample.signals.CacheSignal
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
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

/**
 * The host screen, one section at a time, over a model whose companion source is a SnapshotSource.
 * The model's coroutines run on the Robolectric main thread, which the compose rule idles.
 *
 * The screen is hosted in DemoActivity, replacing its content: an app module's unit tests run against
 * the app's own merged manifest, which has no ui-test-manifest ComponentActivity.
 *
 * performScrollToNode brings its target only just into view, so each test asserts only on nodes at
 * or above its last scroll target.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DemoScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<DemoActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val demo = TestDemo(scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun show() {
        demo.model.start()
        composeRule.activity.setContent { MaterialTheme { DemoScreen(demo.model) } }
    }

    private fun scrollTo(text: String, substring: Boolean = false) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
    }

    /** The label [label] in a row whose value is [value]. */
    private fun row(label: String, value: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(label) and hasAnySibling(hasText(value)))

    private fun segment(title: String): SemanticsNodeInteraction = composeRule.onNode(hasText(title) and isSelectable())

    // Environment

    @Test
    fun theTitleIsDemoAndEachEnvironmentIsASegment() {
        show()

        composeRule.onNodeWithText("Demo").assertIsDisplayed()
        composeRule.onNodeWithText("Environment").assertIsDisplayed()
        segment("Production").assertIsSelected()
        segment("Staging").assertIsNotSelected()
        segment("Local").assertIsNotSelected()
        row("Payload applied", "Production").assertIsDisplayed()
    }

    @Test
    fun choosingAnEnvironmentLoadsItsPayload() {
        show()

        segment("Staging").performClick()

        segment("Staging").assertIsSelected()
        row("Payload applied", "Staging").assertIsDisplayed()
        assertEquals(FlagValueBox.String("staging"), demo.companion.box(FlagKey("environment"), FlagValueType.String))
    }

    // Live flag values

    @Test
    fun liveValuesShowWhatTheAppReads() {
        show()
        scrollTo("One tap")

        row("New onboarding", "false").assertExists()
        row("Page size", "10").assertExists()
        row("Markets", "AU, NZ").assertExists()
        row("Google Pay", "false").assertExists()
        row("Tier", "free").assertExists()
        row("Endpoint", "api.example.com").assertExists()
        row("One tap", "false").assertExists()
    }

    @Test
    fun aCompanionOverrideShowsUpLive() {
        show()

        demo.set("checkout.google-pay", FlagValueBox.Bool(true))
        scrollTo("Google Pay")

        row("Google Pay", "true").assertExists()
    }

    // Payment methods

    @Test
    fun paymentMethodsShowOneRowPerRecord() {
        show()
        scrollTo("Payment methods")
        composeRule.onNodeWithText("Payment methods").assertExists()

        scrollTo("Mastercard")
        row("Visa", "card · min 0").assertExists()
        row("Mastercard", "on").assertExists()
        composeRule.onAllNodesWithText("card · min 0").assertCountEquals(2)
    }

    @Test
    fun aRecordWithNoNameIsShownAsUntitled() {
        // One blank name beside a named record. Two blank names would share the @FlagRecordKey, which
        // makes the list unreadable (DemoModelTest.twoRecordsSharingANameReadAsTheCompiledDefault).
        show()

        demo.set(
            "payment-methods",
            FlagValueBox.String(
                """[{"name":"","kind":"wallet","enabled":false,"minimumSpend":2.5},""" +
                    """{"name":"Gift card","kind":"card","enabled":true,"minimumSpend":1.5}]""",
            ),
        )
        scrollTo("Untitled")

        composeRule.onAllNodesWithText("Untitled").assertCountEquals(1)
        row("Untitled", "wallet · min 2.5").assertExists()
        composeRule.onNode(hasText("off") and hasAnySibling(hasText("wallet · min 2.5"))).assertExists()
        scrollTo("card · min 1.5")
        composeRule.onNode(hasText("on") and hasAnySibling(hasText("card · min 1.5"))).assertExists()
    }

    // Rejecting a bad payload

    @Test
    fun theMalformedPayloadIsRejectedWithEveryProblem() {
        show()
        scrollTo("Malformed payload")

        composeRule.onNodeWithText("Malformed payload").performClick()
        scrollTo("Rejected, nothing applied")

        composeRule.onNodeWithText("featureToggles.onboarding.v2 — wrong type for new-onboarding").assertExists()
        composeRule.onNodeWithText("Clear remote payload").assertDoesNotExist()
    }

    @Test
    fun theExperimentsPayloadAppliesAndCanBeCleared() {
        show()
        scrollTo("List of experiments")

        composeRule.onNodeWithText("List of experiments").performClick()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        row("Payload applied", "List of experiments").assertIsDisplayed()

        scrollTo("Clear remote payload")
        composeRule.onNodeWithText("Clear remote payload").performClick()

        composeRule.onNodeWithText("Clear remote payload").assertDoesNotExist()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        composeRule.onNodeWithText("Payload applied").assertDoesNotExist()
        scrollTo("checkout.endpoint")
        row("checkout.endpoint", "Local").assertExists()
    }

    // Where each value came from

    @Test
    fun everyFlagSaysWhichSourceSuppliedIt() {
        show()

        demo.set("checkout.google-pay", FlagValueBox.Bool(true))

        scrollTo("Where each value came from")
        scrollTo("checkout.google-pay")
        row("checkout.google-pay", "Companion").assertExists()
        scrollTo("checkout.endpoint")
        row("checkout.endpoint", "Remote").assertExists()
        scrollTo("page-size")
        row("page-size", "default").assertExists()
        scrollTo("checkout.express.confirmation-delay")
        row("checkout.express.confirmation-delay", "default").assertExists()
    }

    // Signals from the companion

    @Test
    fun signalsShowTheLastOneAndTheRelaunchNote() {
        show()
        scrollTo("Caches purged")
        row("Last signal received", "—").assertExists()
        row("Caches purged", "0").assertExists()

        demo.model.handle(CacheSignal.PurgeImageCache)
        demo.model.handle(CacheSignal.PurgeEverything)
        scrollTo("Everything was purged.", substring = true)

        row("Last signal received", "Purge everything").assertExists()
        row("Caches purged", "1").assertExists()
        composeRule.onNodeWithText("Signals from the companion").assertExists()
    }

    // Flows

    @Test
    fun theFlowCounterCountsOnboardingChanges() {
        show()

        demo.set("new-onboarding", FlagValueBox.Bool(true))
        scrollTo("new-onboarding changes seen")

        composeRule.onNodeWithText("Flows").assertExists()
        row("new-onboarding changes seen", "1").assertExists()
    }
}
