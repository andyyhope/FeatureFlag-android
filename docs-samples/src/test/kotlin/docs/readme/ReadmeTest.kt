package docs.readme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** README.md's own samples. The rest of its samples are the articles', tested beside them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadmeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var googlePayButtons = 0

    private fun showGooglePayButton() {
        googlePayButtons += 1
    }

    @Test
    fun theQuickStartReadsThroughTheStack() {
        // region snippet: readme-pole
        val pole = FlagPole(
            AppFlagsSpec,
            sources = listOf(
                SharedPreferencesSource.companion(context), // the companion app's edits
                RemoteOverrideSource(AppFlagsSpec), // your backend
            ),
        )

        if (pole.flags.checkout.googlePay) {
            showGooglePayButton()
        }
        // endregion

        assertEquals(0, googlePayButtons)

        // region snippet: readme-resolution
        val sourceName = pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion", or null for the default
        // endregion

        assertNull(sourceName)
        pole.setOverride(AppFlagsSpec.newOnboarding, true)
        assertEquals("Companion", pole.resolution(AppFlagsSpec.newOnboarding).sourceName)
        pole.sources.filterIsInstance<SharedPreferencesSource>().forEach(SharedPreferencesSource::close)
    }
}
