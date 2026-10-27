package docs.buildingacompanionapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.publishSchema
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.signals.FlagSignalGroupDisplay
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/BuildingACompanionApp.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BuildingACompanionAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun publishedDirectory() = folder.newFolder("exported").also { directory ->
        FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource())).publishSchema(directory)
    }

    @Test
    fun theWholeAppStarts() {
        val activity = Robolectric.buildActivity(CompanionActivity::class.java).setup().get()

        assertFalse(activity.isFinishing)
    }

    @Test
    fun aCompanionWithEveryKindOfTabShowsWhyItCannotConnect() {
        composeRule.setContent { MaterialTheme { CompanionWithTabs() } }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun customTabsAndGroupsAreDescribedUpFront() {
        assertEquals("checkout", checkoutTab.id)
        assertEquals("Checkout", checkoutTab.title)
        assertEquals(FlagSignalGroupDisplay.Nested, caches.display)
    }

    @Test
    fun aStoreEditsAnySource() = runTest {
        val exportedDirectory = publishedDirectory()

        // region snippet: companion-store
        val schema = FlagSchema.read(exportedDirectory)
        val store = FlagEditingStore(schema, SnapshotSource(name = "Preview"))
        // endregion
        val entry = checkNotNull(store.entry(FlagKey("checkout.google-pay")))

        // region snippet: companion-store-api
        val tree = store.tree // the flag tree, rebuilt from the flat schema
        val sections = store.sections(query = "pay") // a flat, searchable grouping
        val overridden = store.overriddenKeys // what has been changed
        val badge = store.overriddenCount(tree) // for a badge on a group row

        val value = store.value(entry) // the value in effect
        val isOverridden = store.isOverridden(entry)
        store.setValue(entry, FlagValueBox.Bool(true)) // suspends: writes go to the source off the main thread
        store.reset(entry)
        store.resetAll()
        // endregion

        val found = sections.flatMap { section -> section.entries.map { it.key } }
        assertEquals(listOf(FlagKey("checkout.google-pay")), found)
        assertEquals(emptyList(), overridden)
        assertEquals(0, badge)
        assertEquals(FlagValueBox.Bool(false), value)
        assertFalse(isOverridden)
        assertFalse(store.isOverridden(entry))
    }

    @Test
    fun aStoreMovesOverridesOnAndOffTheDevice() = runTest {
        val store = FlagEditingStore(FlagSchema.read(publishedDirectory()), SnapshotSource())
        val entry = checkNotNull(store.entry(FlagKey("checkout.google-pay")))
        store.setValue(entry, FlagValueBox.Bool(true))

        // region snippet: companion-store-transport
        val json = store.exportJson()
        val code = store.qrCode()

        store.import(code) // a document or a scanned code, told apart by the FFQR1: prefix
        // endregion

        assertTrue(json.contains("\"checkout.google-pay\" : true"))
        assertTrue(store.isOverridden(entry))

        store.resetAll()
        assertFalse(store.isOverridden(entry))
        store.import(code)
        assertTrue(store.isOverridden(entry))
    }
}
