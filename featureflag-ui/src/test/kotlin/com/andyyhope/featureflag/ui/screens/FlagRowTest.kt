package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.withEffective
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()
    private val failures = mutableListOf<Exception>()
    private lateinit var focusManager: FocusManager

    private fun show(store: FlagEditingStore, key: String) {
        val entry = store.requireEntry(key)
        composeRule.setFlagContent(onFailure = { failures += it }) {
            focusManager = LocalFocusManager.current
            FlagRow(store = store, entry = entry, onOpen = { opened += it })
        }
    }

    private fun edit(text: String) {
        val field = composeRule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextReplacement(text)
        field.performImeAction()
        composeRule.waitForIdle()
    }

    // Toggles, pickers and dates

    @Test
    fun aToggleWritesTheMomentItIsFlipped() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "new-onboarding")

        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()

        composeRule.onNodeWithContentDescription("New onboarding experience").assertIsOn()
        assertEquals(FlagValueBox.Bool(true), store.value(store.requireEntry("new-onboarding")))
    }

    @Test
    fun anOverriddenFlagShowsThePillAndItsArrowResetsIt() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store, "max-items")

        composeRule.onNodeWithText("Overridden").assertExists()
        composeRule.onNodeWithContentDescription("Reset to the app's default").performClick()

        composeRule.onNodeWithText("Overridden").assertDoesNotExist()
        assertFalse(store.isOverridden(store.requireEntry("max-items")))
    }

    @Test
    fun aFlagAConfigSuppliesShowsThatValueAndWhereItCameFrom() {
        val store = testStore(ScreenFixtures.companion.withEffective("new-onboarding" to FlagValueBox.Bool(true)))
        show(store, "new-onboarding")

        composeRule.onNodeWithContentDescription("New onboarding experience").assertIsOn()
        composeRule.onNodeWithText("From Environment (staging)").assertExists()
        composeRule.onNodeWithText("Overridden").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()

        // Now the companion's own value: the pill replaces the source.
        composeRule.onNodeWithContentDescription("New onboarding experience").assertIsOff()
        composeRule.onNodeWithText("Overridden").assertExists()
        composeRule.onNodeWithText("From Environment (staging)").assertDoesNotExist()
    }

    @Test
    fun aFlagOnItsDefaultNamesNoSource() {
        show(testStore(ScreenFixtures.companion), "new-onboarding")

        composeRule.onNode(hasText("From ", substring = true)).assertDoesNotExist()
    }

    @Test
    fun aPickerOffersEveryCaseAndWritesTheChoice() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.tier")

        composeRule.onNodeWithText("free").performClick()
        composeRule.onNodeWithText("pro").performClick()

        composeRule.onNodeWithText("pro").assertExists()
        assertEquals(FlagValueBox.String("pro"), store.value(store.requireEntry("checkout.tier")))
    }

    @Test
    fun aDateRowWritesThePickedDateAndTime() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.launched-at")
        val shown = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(Instant.EPOCH.atZone(ZoneId.systemDefault()))

        composeRule.onNode(hasText(shown) and hasClickAction()).performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitForIdle()

        val entry = store.requireEntry("checkout.launched-at")
        assertTrue(store.isOverridden(entry))
        assertEquals(FlagValueBox.Date(Instant.EPOCH), store.value(entry))
    }

    // Text fields

    @Test
    fun aNumberCommitsOnDone() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")

        edit("42")

        assertEquals(FlagValueBox.Int(42), store.value(store.requireEntry("max-items")))
    }

    @Test
    fun aFieldCommitsWhenFocusLeavesIt() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")
        val field = composeRule.onNode(hasSetTextAction())

        field.performClick()
        field.performTextReplacement("7")
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()

        assertEquals(FlagValueBox.Int(7), store.value(store.requireEntry("max-items")))
    }

    @Test
    fun anUnreadableEditRevertsAndWarns() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.ratio")

        edit("3,5")

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertExists()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("1.0")
        assertFalse(store.isOverridden(store.requireEntry("checkout.ratio")))

        edit("2.5")

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertDoesNotExist()
        assertEquals(FlagValueBox.Double(2.5), store.value(store.requireEntry("checkout.ratio")))
    }

    @Test
    fun anOutsideChangeDoesNotOverwriteAFocusedDraft() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")
        val field = composeRule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextReplacement("7")

        runBlocking { store.setValue(store.requireEntry("max-items"), FlagValueBox.Int(99)) }

        composeRule.onNode(hasSetTextAction()).assertTextEquals("7")
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()
        assertEquals(FlagValueBox.Int(7), store.value(store.requireEntry("max-items")))
    }

    @Test
    fun anOutsideChangeUpdatesAFieldThatIsNotFocused() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")

        runBlocking { store.setValue(store.requireEntry("max-items"), FlagValueBox.Int(99)) }

        composeRule.onNode(hasSetTextAction()).assertTextEquals("99")
    }

    @Test
    fun aResetWhileFocusedIsNotUndoneByLeavingTheField() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store, "max-items")
        val entry = store.requireEntry("max-items")

        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.onNodeWithContentDescription("Reset to the app's default").performClick()
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()

        assertFalse(store.isOverridden(entry))
        assertEquals(FlagValueBox.Int(10), store.value(entry))
        composeRule.onNode(hasSetTextAction()).assertTextEquals("10")
    }

    @Test
    fun anOutsideChangeWhileFocusedIsNotUndoneByLeavingTheField() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")
        val entry = store.requireEntry("max-items")

        composeRule.onNode(hasSetTextAction()).performClick()
        runBlocking { store.setValue(entry, FlagValueBox.Int(99)) }
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()

        assertEquals(FlagValueBox.Int(99), store.value(entry))
        composeRule.onNode(hasSetTextAction()).assertTextEquals("99")
    }

    @Test
    fun committingADraftEqualToTheValueInEffectWritesNothing() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")

        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()

        assertFalse(store.isOverridden(store.requireEntry("max-items")))
    }

    @Test
    fun aFailedWriteRevertsTheFieldAndIsReported() {
        val source = FailingSource { FlagBridgeException.Rejected("the host is read-only") }
        val store = FlagEditingStore(ScreenFixtures.companion, source, writeDispatcher = Dispatchers.Main.immediate)
        show(store, "max-items")

        edit("5")

        composeRule.onNode(hasSetTextAction()).assertTextEquals("10")
        assertIs<FlagBridgeException.Rejected>(failures.single())
    }

    // Blocks

    @Test
    fun aDataValueIsABlockWithACopyButton() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "logo")
        composeRule.onNode(hasSetTextAction()).assertTextEquals("AQI=")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("Copy value").performClick()
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithContentDescription("Copied").assertExists()
        assertEquals("AQI=", clipboardText())
        composeRule.mainClock.advanceTimeBy(COPIED_FEEDBACK_MILLIS + 100)
        composeRule.onNodeWithContentDescription("Copy value").assertExists()
    }

    // Lists and records

    @Test
    fun theFlagRowStillBuildsForAnArray() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        composeRule.onNodeWithText("""["AU","NZ"]""").assertExists()
        composeRule.onNodeWithText("2 strings").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.ListEditor("markets")), opened)
    }

    @Test
    fun aRecordListRowCountsItsRecordsAndNamesItsFields() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "endpoints")

        composeRule.onNodeWithText("name, url, enabled, weight, tier").assertExists()
        composeRule.onNodeWithText("1 record").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Records("endpoints")), opened)
    }

    @Test
    fun aRecordListThatCannotBeReadSaysWhy() {
        val store = testStore(
            ScreenFixtures.editors,
            mapOf(
                FlagKey("routes") to FlagValueBox.String("""[{"name":"a","hops":"[]"},{"name":"a","hops":"[]"}]"""),
                FlagKey("endpoints") to FlagValueBox.String("{ not json"),
            ),
        )
        composeRule.setFlagContent {
            Column {
                FlagRow(store = store, entry = store.requireEntry("routes"), onOpen = {})
                FlagRow(store = store, entry = store.requireEntry("endpoints"), onOpen = {})
            }
        }

        composeRule.onNodeWithText("Two records share a name").assertExists()
        composeRule.onNodeWithText("Unreadable").assertExists()
    }
}
