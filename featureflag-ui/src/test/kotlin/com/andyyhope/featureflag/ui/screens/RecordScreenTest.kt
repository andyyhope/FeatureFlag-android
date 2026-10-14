package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()

    private fun show(store: FlagEditingStore, key: String, index: Int) {
        composeRule.setFlagContent {
            RecordScreen(store = store, key = key, trail = emptyList(), index = index, onOpen = { opened += it }, onBack = {})
        }
    }

    private fun FlagEditingStore.rows(key: String): List<Map<String, FlagValueBox>> = checkNotNull(records(requireEntry(key)))

    private fun twoRoutes() = testStore(
        ScreenFixtures.editors,
        mapOf(FlagKey("routes") to FlagValueBox.String("""[{"name":"a","hops":"[]"},{"name":"b","hops":"[]"}]""")),
    )

    private fun replace(current: String, with: String) {
        val field = composeRule.textFieldShowing(current)
        field.performClick()
        field.performTextReplacement(with)
        field.performImeAction()
        composeRule.waitForIdle()
    }

    @Test
    fun aRecordShowsEveryFieldWithItsType() {
        show(testStore(ScreenFixtures.editors), "endpoints", 0)

        // The title bar and the name field both say "production"; "url" is both a field and a type.
        composeRule.onAllNodesWithText("production").assertCountEquals(2)
        composeRule.onAllNodesWithText("url").assertCountEquals(2)
        composeRule.onAllNodesWithText("string").assertCountEquals(2)
        listOf("name", "enabled", "weight", "tier", "int", "bool").forEach { text ->
            composeRule.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun editingOneFieldLeavesEveryOtherRecordAlone() {
        val staging = ScreenFixtures.endpoint("staging", 7, "secondary")
        val store = testStore(
            ScreenFixtures.editors,
            mapOf(FlagKey("endpoints") to FlagRecordList.encode(listOf(staging, ScreenFixtures.endpoint("canary", 3)))),
        )
        show(store, "endpoints", 1)

        replace(current = "3", with = "99")

        val rows = store.rows("endpoints")
        assertEquals(staging, rows[0])
        assertEquals(FlagValueBox.Int(99), rows[1]["weight"])
        assertEquals(FlagValueBox.String("canary"), rows[1]["name"])
    }

    @Test
    fun aKeyThatAnotherRecordUsesIsRefused() {
        val store = twoRoutes()
        show(store, "routes", 1)

        replace(current = "b", with = "a")

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertExists()
        assertEquals(listOf(FlagValueBox.String("a"), FlagValueBox.String("b")), store.rows("routes").map { it["name"] })
    }

    @Test
    fun aKeyThatIsFreeIsAccepted() {
        val store = twoRoutes()
        show(store, "routes", 1)

        replace(current = "b", with = "c")

        assertEquals(listOf(FlagValueBox.String("a"), FlagValueBox.String("c")), store.rows("routes").map { it["name"] })
    }

    @Test
    fun anEnumFieldIsAPickerOfItsCases() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "endpoints", 0)

        composeRule.onNodeWithText("primary").performClick()
        composeRule.onNodeWithText("secondary").performClick()
        composeRule.waitForIdle()

        assertEquals(FlagValueBox.String("secondary"), store.rows("endpoints")[0]["tier"])
    }

    @Test
    fun aNestedListFieldOpensTheNestedList() {
        show(twoRoutes(), "routes", 0)

        composeRule.onNodeWithText("0 records").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Records("routes", listOf(RecordStep(0, "hops")))), opened)
    }
}
