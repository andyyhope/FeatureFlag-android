package docs.sendingsignals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** docs/SendingSignals.md. Robolectric builds [ExampleApplication] and runs its onCreate first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = ExampleApplication::class)
class SendingSignalsTest {

    private val application = ApplicationProvider.getApplicationContext<ExampleApplication>()
    private val context: Context = application
    private val received = mutableListOf<String>()

    private fun handleConfig(signal: ConfigSignal) {
        received += signal.name
    }

    private fun handleCache(signal: CacheSignal) {
        received += signal.name
    }

    @After
    fun stopObserving() {
        application.close()
    }

    @Test
    fun theHostHandlesEachSignalItObserves() {
        assertTrue(FlagSignalChannel.host.deliver("PurgeImageCache"))
        assertTrue(FlagSignalChannel.host.deliver("SignOut"))
        assertTrue(FlagSignalChannel.host.deliver("RefetchRemoteConfiguration"))

        assertEquals(0, application.imageCache.entries)
        assertFalse(application.session.isSignedIn)
        assertEquals(1, application.refetches)
    }

    @Test
    fun aSignalNobodyObservesIsNotHandled() {
        assertFalse(FlagSignalChannel.host.deliver("PurgeEverything"))
    }

    @Test
    fun eachGroupIsObservedOnItsOwn() {
        // region snippet: signals-observe-groups
        val config = FlagSignalChannel.host.observe(ConfigSignal.entries) { signal -> handleConfig(signal) }
        val caches = FlagSignalChannel.host.observe(CacheSignal.entries) { signal -> handleCache(signal) }
        // endregion

        FlagSignalChannel.host.deliver("ClearRemoteConfiguration")
        FlagSignalChannel.host.deliver("PurgeEverything")
        config.close()
        caches.close()

        assertEquals(listOf("ClearRemoteConfiguration", "PurgeEverything"), received)
    }

    @Test
    fun aSignalSaysWhatItIsAndWhetherItNeedsARelaunch() {
        assertEquals("Re-fetch remote configuration", ConfigSignal.RefetchRemoteConfiguration.signalDescription)
        assertEquals("PurgeImageCache", CacheSignal.PurgeImageCache.signalDescription)
        assertTrue(CacheSignal.PurgeEverything.requiresRestart)
        assertFalse(CacheSignal.PurgeImageCache.requiresRestart)
    }

    @Test
    fun aSendToAHostThatIsNotInstalledIsNotHandled() = runTest {
        // region snippet: signals-send
        val client = FlagBridgeClient(context, hostPackage = "com.example.app")

        val handled = client.send(AppSignal.RefetchRemoteConfiguration)
        // endregion

        // region snippet: signals-send-delayed
        val handledLater = client.send(CacheSignal.PurgeImageCache, delay = 3.seconds)
        // endregion

        assertFalse(handled)
        assertFalse(handledLater)
    }
}
