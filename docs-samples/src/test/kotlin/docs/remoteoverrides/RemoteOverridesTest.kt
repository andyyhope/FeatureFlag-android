package docs.remoteoverrides

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The payload docs/RemoteOverrides.md uses to show a dot path. */
val onboardingPayload = """
    {
      "featureToggles": {
        "onboarding": { "v2": true }
      }
    }
""".trimIndent()

/** A path indexing into an array. */
val arrayPathPayload = """
    { "experiments": [ { "enabled": true } ] }
""".trimIndent()

/** A payload no path can address. */
val experimentsPayload = """
    {
      "experiments": [
        { "name": "onboarding-v2", "state": "on" },
        { "name": "google-pay", "state": "off" }
      ]
    }
""".trimIndent()

/** What a payload with three bad values is refused with. */
fun rejectionReport(): String {
    val payload = """{"featureToggles": {"onboarding": {"v2": "yes"}},""" +
        """ "config": {"tier": "gold", "pageSize": [1, 2, 3]}}"""
    return assertFailsWith<RemoteOverrideException.Rejected> {
        RemoteOverrideSource(AppFlagsSpec).apply(payload.encodeToByteArray())
    }.message.orEmpty()
}

/** Stands in for whatever the app fetches its configuration with. */
class HttpClient(private val responses: Map<URI, String>) {
    fun get(url: URI): ByteArray = responses.getValue(url).encodeToByteArray()
}

/** docs/RemoteOverrides.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RemoteOverridesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val local = SnapshotSource()
    private val configUrl = URI("https://config.example.com/flags.json")
    private val httpClient = HttpClient(mapOf(configUrl to onboardingPayload))

    @Test
    fun theSourceDecodesWhatTheAppFetched() {
        // region snippet: remote-overview
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(local, remote))

        val bytes = httpClient.get(configUrl)
        remote.apply(bytes)
        // endregion

        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun aPathCanIndexIntoAnArray() {
        val remote = RemoteOverrideSource(AppFlagsSpec)

        remote.apply(arrayPathPayload.encodeToByteArray())

        assertTrue(FlagPole(AppFlagsSpec, sources = listOf(remote)).flags.firstExperiment)
    }

    @Test
    fun aSuccessfulApplySaysWhatItSetAndWhatItMissed() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val bytes = onboardingPayload.encodeToByteArray()

        // region snippet: remote-apply-result
        val result = remote.apply(bytes)

        val applied = result.appliedKeys // flags this payload set
        val absent = result.absentKeys // flags with a remoteKey the payload did not mention
        // endregion

        assertEquals(listOf(FlagKey("new-onboarding")), applied)
        assertEquals(listOf("page-size", "checkout-tier", "first-experiment", "rollout-share").map(::FlagKey), absent)
    }

    @Test
    fun applyingReplacesEverything() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote))
        remote.apply(onboardingPayload.encodeToByteArray())

        remote.apply(arrayPathPayload.encodeToByteArray())

        assertFalse(pole.flags.newOnboarding)
        remote.clear()
        assertFalse(pole.flags.firstExperiment)
    }

    @Test
    fun oneBadValueRejectsTheWholePayload() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote))
        val bytes = """{"featureToggles": {"onboarding": {"v2": true}}, "config": {"tier": "gold"}}""".toByteArray()

        // region snippet: remote-strict
        try {
            remote.apply(bytes)
        } catch (rejected: RemoteOverrideException.Rejected) {
            for (problem in rejected.problems) {
                Log.w("Flags", "${problem.remoteKey}: ${problem.kind}") // TypeMismatch, UnknownCase or UnknownKey
            }
        }
        // endregion

        assertFalse(pole.flags.newOnboarding)
        assertEquals("config.tier: UnknownCase", ShadowLog.getLogsForTag("Flags").single().msg)
        assertTrue(rejectionReport().startsWith("Nothing was applied. 3 problems:"))
    }

    @Test
    fun aWholeNumberSatisfiesADoubleFlagButAQuotedTrueIsNotABoolean() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote))

        remote.apply("""{"config": {"rolloutShare": 1}}""".encodeToByteArray())

        assertEquals(1.0, pole.flags.rolloutShare)
        assertFailsWith<RemoteOverrideException.Rejected> {
            remote.apply("""{"featureToggles": {"onboarding": {"v2": "true"}}}""".encodeToByteArray())
        }
    }

    @Test
    fun aMapperReadsWhatNoPathCan() {
        // region snippet: remote-mapper-source
        val remote = RemoteOverrideSource(ExperimentFlagsSpec, mapper = ExperimentListMapper)
        // endregion
        val pole = FlagPole(ExperimentFlagsSpec, sources = listOf(remote))

        remote.apply(experimentsPayload.encodeToByteArray())

        assertTrue(pole.flags.newOnboarding)
        assertFalse(pole.flags.googlePay)
    }

    @Test
    fun aHandSetValueSurvivesTheNextRefresh() {
        val sharedPreferences = SharedPreferencesSource.companion(context)
        val remote = RemoteOverrideSource(AppFlagsSpec)

        // region snippet: remote-precedence
        val pole = FlagPole(AppFlagsSpec, sources = listOf(sharedPreferences, remote))
        // endregion

        pole.setOverride(AppFlagsSpec.newOnboarding, false)
        remote.apply(onboardingPayload.encodeToByteArray())
        assertFalse(pole.flags.newOnboarding)
        sharedPreferences.close()
    }

    @Test
    fun theSourceAndThePoleShareAKeyEncoding() {
        // region snippet: remote-key-encoding
        val remote = RemoteOverrideSource(AppFlagsSpec, keyEncoding = KeyEncoding.Snakecase)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote), keyEncoding = KeyEncoding.Snakecase)
        // endregion

        remote.apply(onboardingPayload.encodeToByteArray())

        assertTrue(pole.flags.newOnboarding)
    }
}
