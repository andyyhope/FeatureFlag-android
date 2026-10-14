package com.andyyhope.featureflag.sample.host

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.sample.signals.AppSignal
import com.andyyhope.featureflag.sample.signals.CacheSignal
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A signal can start the host's process, and the bridge provider answers once Application.onCreate
 * returns. So everything a companion relies on must be done by then. None of these tests idles the
 * main looper: they see exactly what onCreate left behind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DemoApplicationTest {

    private val app: DemoApplication = ApplicationProvider.getApplicationContext()

    // What onCreate leaves behind

    @Test
    fun onCreatePublishesTheSchemaWhereTheProviderServesIt() {
        val published = FlagSchema.read(FlagBridgeContract.schemaDirectory(app))

        assertEquals("Demo", published.applicationName)
        assertEquals(app.model.pole.schema.flags, published.flags)
        assertEquals(app.model.pole.schema.groups, published.groups)
    }

    @Test
    fun onCreateRegistersAnObserverForEverySignal() {
        val signals: List<FlagSignal> = AppSignal.entries + CacheSignal.entries
        for (signal in signals) {
            // On the main thread deliver() runs the handlers inline.
            assertTrue(FlagSignalChannel.host.deliver(signal.name), "${signal.name} was not handled")
            assertEquals(signal.signalDescription, app.model.state.value.lastSignal)
        }
        assertEquals(1, app.model.state.value.purgedCaches)
        assertTrue(app.model.state.value.awaitingRelaunch)
    }

    @Test
    fun onCreateHasLoadedTheEnvironmentsPayloadBeforeItReturns() {
        assertEquals(RemoteConfiguration.production, app.model.state.value.appliedConfiguration)
        val endpoint = app.model.values.value.provenance.first { row -> row.key == "checkout.endpoint" }
        assertEquals("Remote", endpoint.sourceName)
        assertEquals(
            "Companion",
            app.model.pole.sources.first().sourceName,
            "the companion's file outranks every layer",
        )
    }
}
