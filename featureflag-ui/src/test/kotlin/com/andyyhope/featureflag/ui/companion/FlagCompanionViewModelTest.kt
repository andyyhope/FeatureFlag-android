package com.andyyhope.featureflag.ui.companion

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.editing.CompanionSchemas
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagCompanionViewModelTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val host = "com.example.host"

    @Test
    fun theViewModelConnectsAtOnceAndSaysWhyItCannot() {
        // The unit-test app does not request the host's bridge permission, the first thing connect checks.
        val viewModel = FlagCompanionViewModel(context, host, 1.seconds)

        val state = awaitSettled(viewModel.session)

        assertEquals(
            FlagCompanionState.Unavailable(FlagBridgeConnection.UsesPermissionMissing(FlagBridgeContract.permission(host)).message),
            state,
        )
    }

    @Test
    fun theFactoryMakesOneViewModelPerHostPackage() {
        val store = ViewModelStore()
        val extras = MutableCreationExtras()
        extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = context
        val provider = ViewModelProvider.create(store, FlagCompanionViewModel.factory(host, 1.seconds), extras)

        val first = provider[host, FlagCompanionViewModel::class]

        assertSame(first, provider[host, FlagCompanionViewModel::class])
        assertNotSame(first, provider["com.example.other", FlagCompanionViewModel::class])
        store.clear()
    }

    @Test
    fun aBridgeSourceSeenAsACompanionSourceSharesItsFailureAndItsReads() = runBlocking<Unit> {
        // No provider answers: the source starts empty, with the failed first read as its failure.
        val bridge = FlagBridgeClient(context, host).source(CompanionSchemas.demoUi)
        val source = bridge.asCompanionSource()
        try {
            assertSame(bridge.lastFailure, source.lastFailure)
            assertIs<FlagBridgeException.Unavailable>(source.lastFailure.value)
            assertNull(source.box(FlagKey("max-items"), FlagValueType.Int))
            assertEquals(bridge.sourceName, source.sourceName)
            assertFailsWith<FlagBridgeException.Unavailable> { source.refresh() }
        } finally {
            source.close()
        }
    }

    @Test
    fun aSignalToAHostThatCannotBeReachedIsNotHandled() = runBlocking<Unit> {
        val viewModel = FlagCompanionViewModel(context, host, 1.seconds)

        assertFalse(viewModel.send(Ping.Ping, 1.seconds))
        assertFalse(viewModel.send(Ping.Ping, Duration.ZERO))
    }

    @Test
    fun theViewModelOwnsOneSignalsModel() {
        val viewModel = FlagCompanionViewModel(context, host, 1.seconds)

        assertSame(viewModel.signals, viewModel.signals)
        assertEquals(FlagSignalDelay.Instant, viewModel.signals.delay.value)
    }

    @Test
    fun theSchemaWatchHearsEachAnnouncementUntilClosed() {
        // publishSchema(context) in the host notifies this URI; FlagBridgeContract.schemaUri is the shared name.
        var heard = 0
        val watch = checkNotNull(watchSchema(context.contentResolver, host) { heard++ })
        val schemaUri = FlagBridgeContract.schemaUri(host)

        context.contentResolver.notifyChange(schemaUri, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, heard)

        watch.close()
        context.contentResolver.notifyChange(schemaUri, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, heard)
    }

    @Test
    fun theSchemaWatchIgnoresOtherHosts() {
        var heard = 0
        val watch = checkNotNull(watchSchema(context.contentResolver, host) { heard++ })
        try {
            context.contentResolver.notifyChange(FlagBridgeContract.schemaUri("com.example.other"), null)
            context.contentResolver.notifyChange(FlagBridgeContract.overridesUri(host), null)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, heard)
        } finally {
            watch.close()
        }
    }

    private enum class Ping : FlagSignal { Ping }

    /** Runs the main looper until the session has left Connecting; connect hops to IO and back. */
    private fun awaitSettled(session: FlagCompanionSession): FlagCompanionState {
        val deadline = System.currentTimeMillis() + 5_000
        while (session.state.value == FlagCompanionState.Connecting && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return session.state.value
    }
}
