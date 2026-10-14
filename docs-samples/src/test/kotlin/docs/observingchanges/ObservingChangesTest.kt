package docs.observingchanges

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** docs/ObservingChanges.md. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ObservingChangesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val analytics = Analytics()
    private var reloads = 0

    private fun reloadPage() {
        reloads += 1
    }

    @Test
    fun aComposableFollowsAFlag() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
        composeRule.setContent { OnboardingEntry(pole) }
        composeRule.onNodeWithText("Welcome").assertExists()

        pole.setOverride(AppFlagsSpec.newOnboarding, true)

        composeRule.onNodeWithText("Welcome to the new onboarding").assertExists()
    }

    @Test
    fun aFlowSendsTheCurrentValueThenEachChange() = runTest {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
        val scope = backgroundScope

        // region snippet: observing-flow
        pole.flow(AppFlagsSpec.newOnboarding)
            .onEach { isEnabled -> analytics.setUserProperty("new_onboarding", isEnabled) }
            .launchIn(scope)
        // endregion
        runCurrent()
        assertEquals(false, analytics.properties["new_onboarding"])

        pole.setOverride(AppFlagsSpec.newOnboarding, true)
        runCurrent()

        assertEquals(true, analytics.properties["new_onboarding"])
    }

    @Test
    fun theCoarseStreamSaysWhichKeysChanged() = runTest {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
        val scope = backgroundScope

        // region snippet: observing-changes
        pole.changes
            .filter { change -> change.affects(pole.key(AppFlagsSpec.pageSize)) }
            .onEach { reloadPage() }
            .launchIn(scope)
        // endregion
        runCurrent()

        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
        pole.setOverride(AppFlagsSpec.pageSize, 50)
        runCurrent()

        assertEquals(1, reloads)
    }

    @Test
    fun aSourceCanBeAskedToAnnounceEverything() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val source = SharedPreferencesSource.companion(context)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
        pole.changes.onEach { reloadPage() }.launchIn(backgroundScope)
        runCurrent()

        // region snippet: observing-refresh
        source.refresh()
        // endregion
        runCurrent()

        assertEquals(1, reloads)
        source.close()
    }
}
