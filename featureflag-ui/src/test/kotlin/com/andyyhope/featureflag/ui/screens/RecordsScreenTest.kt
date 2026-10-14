package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagRecordEditingException
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()
    private val failures = mutableListOf<Exception>()

    private fun show(store: FlagEditingStore, key: String, trail: List<RecordStep> = emptyList()) {
        composeRule.setFlagContent(onFailure = { failures += it }) {
            RecordsScreen(store = store, key = key, trail = trail, onOpen = { opened += it }, onBack = {})
        }
    }

    private fun FlagEditingStore.names(key: String): List<String> =
        checkNotNull(records(requireEntry(key))).map { (it["name"] as FlagValueBox.String).value }

    private fun twoEndpoints() = testStore(
        ScreenFixtures.editors,
        mapOf(
            FlagKey("endpoints") to FlagRecordList.encode(
                listOf(ScreenFixtures.endpoint("staging", 7, "secondary"), ScreenFixtures.endpoint("canary", 3)),
            ),
        ),
    )

    private fun twoRoutes() = testStore(
        ScreenFixtures.editors,
        mapOf(FlagKey("routes") to FlagValueBox.String("""[{"name":"a","hops":"[]"},{"name":"b","hops":"[]"}]""")),
    )

    private fun act(row: Int, action: String) {
        composeRule.onAllNodesWithContentDescription("Actions")[row].performClick()
        composeRule.onNodeWithText(action).performClick()
        composeRule.waitForIdle()
    }

    // Reading

    @Test
    fun eachRecordIsSummarisedByItsTitleAndItsOtherFields() {
        show(testStore(ScreenFixtures.editors), "endpoints")

        composeRule.onNodeWithText("Endpoints").assertExists()
        composeRule.onNodeWithText("1 record").assertExists()
        composeRule.onNodeWithText("production").assertExists()
        composeRule.onNodeWithText("url: https://production.example  enabled: true  weight: 10  tier: primary").assertExists()
        composeRule.onNodeWithText("Each record has 5 fields: name, url, enabled, weight, tier.").assertExists()
    }

    @Test
    fun aListWithADuplicateKeySaysWhichKey() {
        val raw = """[{"name":"a","hops":"[]"},{"name":"a","hops":"[]"}]"""
        show(testStore(ScreenFixtures.editors, mapOf(FlagKey("routes") to FlagValueBox.String(raw))), "routes")

        composeRule.onNodeWithText(
            "Two records share the same name, a — it is what tells one record from another, so they cannot.",
        ).assertExists()
        composeRule.onNodeWithText(raw).assertExists()
        composeRule.onNodeWithText(
            "The app cannot read it either, so it is using its default. Reset the flag to edit it here.",
        ).assertExists()
    }

    @Test
    fun textThatIsNotAListOfRecordsSaysSo() {
        show(testStore(ScreenFixtures.editors, mapOf(FlagKey("endpoints") to FlagValueBox.String("{ not json"))), "endpoints")

        composeRule.onNodeWithText("This value is not a list of records.").assertExists()
        composeRule.onNodeWithText("{ not json").assertExists()
    }

    @Test
    fun tappingARecordOpensIt() {
        show(testStore(ScreenFixtures.editors), "endpoints")

        composeRule.onNodeWithText("production").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Record("endpoints", emptyList(), 0)), opened)
    }

    // Changing the list

    @Test
    fun addingAppendsARecordOfEmptyFields() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "endpoints")

        composeRule.onNodeWithText("Add").performClick()

        composeRule.onNodeWithText("Untitled").assertExists()
        val added = checkNotNull(store.records(store.requireEntry("endpoints"))).last()
        assertEquals(FlagValueBox.String(""), added["name"])
        assertEquals(FlagValueBox.Int(0), added["weight"])
        assertEquals(FlagValueBox.String("primary"), added["tier"])
    }

    @Test
    fun duplicatingPutsTheCopyRightAfterTheOriginal() {
        val store = twoEndpoints()
        show(store, "endpoints")

        act(row = 0, action = "Duplicate")

        assertEquals(listOf("staging", "staging", "canary"), store.names("endpoints"))
    }

    @Test
    fun duplicatingInAKeyedListIsRefusedAndReported() {
        val store = twoRoutes()
        show(store, "routes")

        act(row = 0, action = "Duplicate")

        assertIs<FlagRecordEditingException>(failures.single())
        assertEquals(listOf("a", "b"), store.names("routes"))
    }

    @Test
    fun movingARecordUpWritesTheNewOrder() {
        val store = twoEndpoints()
        show(store, "endpoints")

        act(row = 1, action = "Move up")

        assertEquals(listOf("canary", "staging"), store.names("endpoints"))
    }

    @Test
    fun deletingARecordLeavesTheRest() {
        val store = twoEndpoints()
        show(store, "endpoints")

        act(row = 0, action = "Delete")

        assertEquals(listOf("canary"), store.names("endpoints"))
    }

    // Nested lists

    @Test
    fun aNestedListIsTitledByItsField() {
        val raw = """[{"name":"a","hops":"[{\"host\":\"x\"}]"}]"""
        show(testStore(ScreenFixtures.editors, mapOf(FlagKey("routes") to FlagValueBox.String(raw))), "routes", listOf(RecordStep(0, "hops")))

        composeRule.onNodeWithText("hops").assertExists()
        composeRule.onNodeWithText("1 record").assertExists()
        composeRule.onNodeWithText("x").assertExists()
    }

    @Test
    fun addingToANestedListWritesThroughTheParent() {
        val raw = """[{"name":"a","hops":"[{\"host\":\"x\"}]"}]"""
        val store = testStore(ScreenFixtures.editors, mapOf(FlagKey("routes") to FlagValueBox.String(raw)))
        show(store, "routes", listOf(RecordStep(0, "hops")))

        composeRule.onNodeWithText("Add").performClick()
        composeRule.waitForIdle()

        val route = checkNotNull(store.records(store.requireEntry("routes"))).single()
        val hops = checkNotNull(
            FlagRecordList.decode(checkNotNull(route["hops"]), ScreenFixtures.routeFields[1].fields.orEmpty()),
        )
        assertEquals(listOf(FlagValueBox.String("x"), FlagValueBox.String("")), hops.map { it["host"] })
    }
}
