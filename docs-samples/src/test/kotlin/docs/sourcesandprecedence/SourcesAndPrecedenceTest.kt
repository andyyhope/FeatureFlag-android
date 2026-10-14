package docs.sourcesandprecedence

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.EnvironmentConfiguration
import com.andyyhope.featureflag.remote.LayerOutcome
import com.andyyhope.featureflag.remote.LoadOutcome
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/SourcesAndPrecedence.md. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SourcesAndPrecedenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val byHand = SnapshotSource(name = "By hand")
    private val api = ConfigApi(
        mapOf(
            "https://config.example.com/staging.json" to """{"featureToggles": {"onboarding": {"v2": true}}}""",
            "https://staging-config.example.com/staging.json" to """{"checkout": {"googlePay": true}}""",
        ),
    )
    private val bundled = mapOf(
        "production.json" to """{"checkout": {"googlePay": false}}""",
        "staging.json" to """{"checkout": {"remoteUrl": "https://staging-config.example.com"}}""",
    )
    private val opened = mutableListOf<SharedPreferencesSource>()

    private fun bundledConfig(name: String): ByteArray? = bundled[name]?.encodeToByteArray()

    @After
    fun closeSources() {
        opened.forEach(SharedPreferencesSource::close)
    }

    @Test
    fun theOrderOfTheStackIsThePrecedence() {
        // region snippet: sources-stack
        val pole = FlagPole(
            AppFlagsSpec,
            sources = listOf(
                SharedPreferencesSource.companion(context), // 1. the companion app
                RemoteOverrideSource(AppFlagsSpec), // 2. your backend
            ),
        )
        // 3. each flag's default getter
        // endregion
        opened += pole.sources.filterIsInstance<SharedPreferencesSource>()
        val remote = pole.sources[1] as RemoteOverrideSource

        remote.apply("""{"featureToggles": {"onboarding": {"v2": true}}}""".encodeToByteArray())
        pole.setOverride(AppFlagsSpec.newOnboarding, false)

        assertFalse(pole.flags.newOnboarding)
        assertEquals("Companion", pole.resolution(AppFlagsSpec.newOnboarding).sourceName)
    }

    @Test
    fun theSourcesThatShip() {
        // region snippet: sources-shared-preferences
        val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
        val own = SharedPreferencesSource(preferences) // a file of this app's own
        val shared = SharedPreferencesSource.companion(context) // the file a companion app edits
        // endregion
        opened += listOf(own, shared)

        assertEquals("SharedPreferences", own.sourceName)
        assertEquals("Companion", shared.sourceName)
    }

    @Test
    fun aSnapshotHoldsValuesInMemory() {
        // region snippet: sources-snapshot
        val local = SnapshotSource(name = "test")
        val pole = FlagPole(AppFlagsSpec, sources = listOf(local))

        local.setBox(FlagKey("checkout.google-pay"), FlagValueBox.Bool(true))
        val offersGooglePay = pole.flags.checkout.googlePay // true
        // endregion

        assertTrue(offersGooglePay)
    }

    @Test
    fun anEnvironmentConfigurationLayersRemoteOverLocal() = runTest {
        // region snippet: sources-environment-configuration
        val config = EnvironmentConfiguration<Environment>(
            AppFlagsSpec,
            local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // ships with the app
            remote = { environment -> api.fetchConfig(environment) }, // fetched, suspending
        )

        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

        // when the environment flag changes:
        val outcome = config.load(Environment.Staging) // local staging.json, then remote staging.json
        // endregion

        assertEquals(LoadOutcome(LayerOutcome.Applied, LayerOutcome.Applied), outcome)
        assertTrue(pole.flags.newOnboarding)
        assertEquals(URI("https://staging-config.example.com"), pole.flags.checkout.remoteUrl)
        assertEquals(listOf("Remote", "Local"), config.sources.map { it.sourceName })
    }

    @Test
    fun theEnvironmentFlagDrivesTheLoad() = runTest {
        val config = EnvironmentConfiguration<Environment>(
            AppFlagsSpec,
            local = { environment -> bundledConfig("${environment.flagRawValue}.json") },
            remote = { environment -> api.fetchConfig(environment) },
        )
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)
        val scope = backgroundScope

        // region snippet: sources-environment-flag
        scope.launch {
            pole.flow(AppFlagsSpec.environment).collectLatest { environment -> config.load(environment) }
        }
        // endregion
        runCurrent()
        assertFalse(pole.flags.newOnboarding)

        pole.setOverride(AppFlagsSpec.environment, Environment.Staging)
        runCurrent()

        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun theRemoteLoaderCanReadAFlagTheLocalLayerSets() = runTest {
        // region snippet: sources-fetch-remote
        val config = EnvironmentConfiguration<Environment>(
            AppFlagsSpec,
            local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // carries checkout.remoteUrl
        )
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

        config.fetchRemote { environment ->
            val url = pole.flags.checkout.remoteUrl // resolved: by hand, then local, then the default
            api.fetch(url, environment)
        }
        // endregion

        config.load(Environment.Staging)

        assertEquals(listOf("https://staging-config.example.com/staging.json"), api.requests)
        assertTrue(pole.flags.checkout.googlePay)
    }

    @Test
    fun overridesGoToTheFirstMutableSource() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand, RemoteOverrideSource(AppFlagsSpec)))

        // region snippet: sources-overrides
        pole.setOverride(AppFlagsSpec.newOnboarding, true)
        pole.removeOverride(AppFlagsSpec.newOnboarding)
        pole.removeAllOverrides()
        // endregion

        assertTrue(byHand.values.isEmpty())
        val readOnly = FlagPole(AppFlagsSpec, sources = listOf(RemoteOverrideSource(AppFlagsSpec)))
        assertFailsWith<FlagException.NoMutableSource> { readOnly.setOverride(AppFlagsSpec.newOnboarding, true) }
    }

    @Test
    fun aResolutionNamesTheSourceThatWon() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand))

        // region snippet: sources-resolution
        val resolution = pole.resolution(AppFlagsSpec.newOnboarding)

        val source = resolution.sourceName // "Companion", "Remote", or null for the compiled default
        val value = resolution.box // the value in effect
        val isDefault = resolution.isDefault // true when nothing overrode it
        // endregion

        assertNull(source)
        assertEquals(FlagValueBox.Bool(false), value)
        assertTrue(isDefault)
    }

    @Test
    fun everyFlagCanBeExplainedInALoop() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand))
        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)

        // region snippet: sources-resolution-loop
        for (entry in pole.schema.flags) {
            val resolution = pole.resolution(entry.key, entry.valueType)
            Log.d("Flags", "${entry.key}: ${resolution.sourceName ?: "default"}")
        }
        // endregion

        assertEquals(
            listOf(
                "new-onboarding: default",
                "environment: default",
                "checkout.google-pay: By hand",
                "checkout.remote-url: default",
            ),
            ShadowLog.getLogsForTag("Flags").map { it.msg },
        )
    }

    @Test
    fun aSourceOfYourOwn() {
        val source = RemoteConfigSource()
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))

        source.refresh(mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))

        assertTrue(pole.flags.newOnboarding)
        assertEquals("Remote Config", pole.resolution(AppFlagsSpec.newOnboarding).sourceName)
    }
}
