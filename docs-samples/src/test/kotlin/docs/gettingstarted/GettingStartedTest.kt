package docs.gettingstarted

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.pole.FlagPole
import docs.sync.DocsSync
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/GettingStarted.md, and the install block README.md shares with it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GettingStartedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shown = mutableListOf<String>()

    private fun showNewOnboarding() {
        shown += "onboarding"
    }

    private fun addGooglePayButton() {
        shown += "google-pay"
    }

    @Test
    fun aPoleReadsEveryFlagThroughItsSources() {
        // region snippet: getting-started-pole
        val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource(preferences)))

        if (pole.flags.newOnboarding) {
            showNewOnboarding()
        }

        if (pole.flags.checkout.googlePay) {
            addGooglePayButton()
        }
        // endregion

        assertEquals(emptyList(), shown)
        assertEquals(20, pole.flags.pageSize)
        preferences.edit().putBoolean("checkout.google-pay", true).commit()
        assertTrue(pole.flags.checkout.googlePay)
    }

    @Test
    fun theInstallBlocksNameOnlyPublishedArtifacts() {
        val coordinate = Regex("""com\.andyyhope\.featureflag:([a-z-]+)""")
        val blocks = listOf(
            "/docs/gettingstarted/app-dependencies.kts",
            "/docs/buildingacompanionapp/companion-dependencies.kts",
            "/docs/thebridge/host-dependencies.kts",
        ).map { checkNotNull(javaClass.getResource(it)).readText() }

        val artifacts = blocks.flatMap { block -> coordinate.findAll(block).map { it.groupValues[1] } }.toSet()

        assertTrue(DocsSync.ARTIFACTS.containsAll(artifacts), "$artifacts")
        val version = checkNotNull(System.getProperty("featureflag.docs.versionName")).removeSuffix("-SNAPSHOT")
        assertTrue(blocks.first().contains("ksp(platform(\"com.andyyhope.featureflag:featureflag-bom:$version\"))"))
    }
}
