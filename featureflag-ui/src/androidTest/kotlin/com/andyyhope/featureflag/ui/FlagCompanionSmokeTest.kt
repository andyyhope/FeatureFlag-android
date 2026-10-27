package com.andyyhope.featureflag.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.ui.companion.FlagCompanionState
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.screens.FlagCompanionContent
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
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
import java.time.Instant
import kotlin.test.assertTrue

/**
 * The whole companion on a device, over an in-process store instead of a bridge. It walks every tab and
 * writes a screenshot of each to /data/local/tmp/featureflag-ui, which outlives the test APK's uninstall,
 * for the controller to pull and read.
 */
@RunWith(AndroidJUnit4::class)
class FlagCompanionSmokeTest {

    private enum class AppSignal(override val signalDescription: String) : FlagSignal {
        RefetchRemoteConfiguration("Re-fetch remote configuration"),
        ClearRemoteConfiguration("Clear remote configuration"),
    }

    private enum class CacheSignal(override val signalDescription: String, override val requiresRestart: Boolean) : FlagSignal {
        PurgeImageCache("Purge the image cache", requiresRestart = false),
        PurgeEverything("Purge everything", requiresRestart = true),
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The shell's signals model lives outside the composition, as it does in the ViewModel. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signals = FlagSignalsModel(send = { _, _ -> true }, scope = scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun entry(key: String, type: FlagValueType, default: FlagValueBox, description: String, cases: List<FlagValueBox>? = null) =
        FlagSchema.Entry(
            key = FlagKey(key),
            propertyPath = key.split("."),
            description = description,
            valueType = type,
            defaultValue = default,
            cases = cases,
        )

    private val schema = FlagSchema(
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        applicationName = "Demo",
        description = "What the checkout team can turn on",
        flags = listOf(
            entry(
                "environment",
                FlagValueType.String,
                FlagValueBox.String("production"),
                "Environment",
                cases = listOf(FlagValueBox.String("production"), FlagValueBox.String("staging")),
            ),
            entry("new-onboarding", FlagValueType.Bool, FlagValueBox.Bool(false), "New onboarding experience"),
            entry("max-items", FlagValueType.Int, FlagValueBox.Int(10), "Maximum items"),
            entry("checkout.apple-pay", FlagValueType.Bool, FlagValueBox.Bool(false), "Apple Pay"),
            entry(
                "checkout.tier",
                FlagValueType.String,
                FlagValueBox.String("free"),
                "Tier",
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry("checkout.launched-at", FlagValueType.Date, FlagValueBox.Date(Instant.EPOCH), "Launched at"),
            entry(
                "checkout.markets",
                FlagValueType.Array(FlagValueType.String),
                FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
                "Markets",
            ),
        ),
        groups = listOf(FlagSchema.Group(listOf("checkout"), "Checkout")),
    )

    private val tabs = listOf(
        FlagCompanionTab.Overrides,
        FlagCompanionTab.Signals(
            FlagSignalGroup("Configuration", AppSignal.entries),
            FlagSignalGroup("Caches", CacheSignal.entries),
        ),
        FlagCompanionTab.Flags,
        FlagCompanionTab.Detail(key = "environment", title = "Environment"),
    )

    @Test
    fun everyTabRendersOnADevice() {
        val store = FlagEditingStore(
            schema,
            SnapshotSource(values = mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true), FlagKey("max-items") to FlagValueBox.Int(25))),
        )
        composeRule.setContent {
            MaterialTheme {
                FlagCompanionContent(
                    state = FlagCompanionState.Ready(store, failure = null),
                    tabs = tabs,
                    signals = signals,
                    onRetry = {},
                    onRefresh = {},
                )
            }
        }
        shell("rm -rf $DIRECTORY")
        shell("mkdir -p $DIRECTORY")

        composeRule.onNodeWithText("2 changed").assertExists()
        capture("1-overrides")

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show QR code"))
        composeRule.onNodeWithText("Show QR code").performClick()
        composeRule.onNodeWithText("Scan to copy").assertExists()
        capture("2-qr-sheet")
        composeRule.onNodeWithText("Done").performClick()

        selectTab("Signals")
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        // The recent results follow both groups, so on a short screen their row is not composed until scrolled to.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("handled"))
        composeRule.onNodeWithText("handled").assertExists()
        capture("3-signals")

        selectTab("Flags")
        composeRule.onNodeWithText("Demo").assertExists()
        capture("4-flags")
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Checkout"))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()
        capture("5-flags-checkout")

        selectTab("Environment")
        composeRule.onNodeWithText("Production").assertExists()
        capture("6-environment")
    }

    private fun selectTab(title: String) {
        composeRule.onNode(hasText(title) and isSelectable()).performClick()
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        // Window animations (the sheet's dialog fading in) and ripples run outside the test's clock.
        SystemClock.sleep(SETTLE_MILLIS)
        shell("screencap -p $DIRECTORY/$name.png")
        assertTrue("$name.png" in shell("ls $DIRECTORY"), "screencap wrote no $name.png")
    }

    /** Runs [command] as the shell user, which can write /data/local/tmp, and waits for it to finish. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        const val DIRECTORY = "/data/local/tmp/featureflag-ui"
        const val SETTLE_MILLIS = 1_000L
    }
}
