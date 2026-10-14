package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.sample.signals.AppSignal
import com.andyyhope.featureflag.sample.signals.CacheSignal
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS DemoModel's behaviour, driven the way the screen and the signal observers drive it. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class DemoModelTest {

    // Environment

    @Test
    fun startingLoadsTheCurrentEnvironmentsPayload() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        runCurrent()

        assertEquals(RemoteConfiguration.production, demo.model.state.value.appliedConfiguration)
        assertNull(demo.model.state.value.rejection)
        assertEquals("api.example.com", demo.model.values.value.endpointHost)
        assertEquals("Remote", demo.source("checkout.endpoint"))
    }

    @Test
    fun choosingAnEnvironmentWritesTheCompanionSourceAndLoadsItsPayload() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        runCurrent()

        demo.model.selectEnvironment(DemoEnvironment.Staging)
        runCurrent()

        assertEquals(RemoteConfiguration.staging, demo.model.state.value.appliedConfiguration)
        assertEquals(DemoEnvironment.Staging, demo.model.values.value.environment)
        assertEquals("Companion", demo.source("environment"))
        assertTrue(demo.model.values.value.googlePay)
        assertEquals("staging.api.example.com", demo.model.values.value.endpointHost)
        assertEquals(
            listOf("Visa", "Google Pay", "Bank transfer"),
            demo.model.values.value.paymentMethods.map { it.name },
        )
    }

    @Test
    fun anAbsentRemotePayloadLeavesTheLocalLayerShowing() = runTest {
        val demo = TestDemo(backgroundScope, demoConfiguration(remote = { null }))
        demo.model.start()
        runCurrent()

        assertNull(demo.model.state.value.appliedConfiguration)
        assertNull(demo.model.state.value.rejection)
        assertEquals("Local", demo.source("checkout.endpoint"))
        assertEquals("default", demo.source("new-onboarding"))
    }

    @Test
    fun aFailedFetchIsShownAsTheRejection() = runTest {
        val demo = TestDemo(backgroundScope, demoConfiguration(remote = { throw IOException("The network is down") }))
        demo.model.start()
        runCurrent()

        assertNull(demo.model.state.value.appliedConfiguration)
        assertEquals("The network is down", demo.model.state.value.rejection)
        assertEquals("Local", demo.source("checkout.endpoint"), "the local config, never the last environment's values")
    }

    @Test
    fun aCompanionOverrideSurvivesAnEnvironmentSwitch() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        demo.set("checkout.google-pay", FlagValueBox.Bool(false))

        demo.model.selectEnvironment(DemoEnvironment.Staging)
        runCurrent()

        assertFalse(demo.model.values.value.googlePay, "staging says true, the companion says false")
        assertEquals("Companion", demo.source("checkout.google-pay"))

        demo.set("checkout.google-pay", null)
        runCurrent()

        assertTrue(demo.model.values.value.googlePay, "staging's value shows through once cleared")
        assertEquals("Remote", demo.source("checkout.google-pay"))
    }

    // Applying a payload by hand

    @Test
    fun theExperimentsPayloadIsReshapedByTheCustomMapper() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()

        demo.model.applyPayload(RemoteConfiguration.experiments)
        runCurrent()

        assertEquals(RemoteConfiguration.experiments, demo.model.state.value.appliedConfiguration)
        assertTrue(demo.model.values.value.newOnboarding)
        assertTrue(demo.model.values.value.googlePay)
        assertEquals("Remote", demo.source("new-onboarding"))
    }

    @Test
    fun theMalformedPayloadIsRejectedInFullWithItsReason() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()

        demo.model.applyPayload(RemoteConfiguration.malformed)
        runCurrent()

        assertNull(demo.model.state.value.appliedConfiguration)
        assertEquals("featureToggles.onboarding.v2 — wrong type for new-onboarding", demo.model.state.value.rejection)
        assertFalse(demo.model.values.value.googlePay, "the valid googlePay beside it is not applied either")
    }

    @Test
    fun theMisspelledPayloadNamesTheUnknownKey() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()

        demo.model.applyPayload(RemoteConfiguration.experimentsWithATypo)

        assertEquals("new-onbaording — no such flag in this app", demo.model.state.value.rejection)
    }

    @Test
    fun clearingTheRemotePayloadFallsBackToTheLocalLayer() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()

        demo.model.clearRemote()
        runCurrent()

        assertNull(demo.model.state.value.appliedConfiguration)
        assertEquals("Local", demo.source("checkout.endpoint"))
        assertEquals("default", demo.source("new-onboarding"))
    }

    @Test
    fun anUnreadablePayloadIsDescribedByItsMessage() {
        assertEquals(
            "The remote payload could not be read: not valid JSON.",
            describe(RemoteOverrideException.Malformed("not valid JSON")),
        )
    }

    // Signals from the companion

    @Test
    fun refetchReappliesTheCurrentEnvironmentsPayload() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        demo.model.applyPayload(RemoteConfiguration.experiments)

        demo.model.handle(AppSignal.RefetchRemoteConfiguration)
        runCurrent()

        assertEquals("Re-fetch remote configuration", demo.model.state.value.lastSignal)
        assertEquals(RemoteConfiguration.production, demo.model.state.value.appliedConfiguration)
        assertFalse(demo.model.values.value.newOnboarding)
    }

    @Test
    fun clearingByASignalClearsTheRemoteLayer() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()

        demo.model.handle(AppSignal.ClearRemoteConfiguration)
        runCurrent()

        assertEquals("Clear remote configuration", demo.model.state.value.lastSignal)
        assertNull(demo.model.state.value.appliedConfiguration)
        assertEquals("Local", demo.source("checkout.endpoint"))
    }

    @Test
    fun purgingTheImageCacheCountsEachTime() = runTest {
        val demo = TestDemo(backgroundScope)

        demo.model.handle(CacheSignal.PurgeImageCache)
        demo.model.handle(CacheSignal.PurgeImageCache)

        assertEquals(2, demo.model.state.value.purgedCaches)
        assertEquals("Purge the image cache", demo.model.state.value.lastSignal)
        assertFalse(demo.model.state.value.awaitingRelaunch)
    }

    @Test
    fun purgingEverythingWaitsForARelaunch() = runTest {
        val demo = TestDemo(backgroundScope)

        demo.model.handle(CacheSignal.PurgeEverything)

        assertTrue(demo.model.state.value.awaitingRelaunch)
        assertEquals("Purge everything", demo.model.state.value.lastSignal)
        assertEquals(0, demo.model.state.value.purgedCaches)
    }

    // Flows and provenance

    @Test
    fun onboardingChangesAreCountedAsTheyArriveNotOnStart() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        runCurrent()
        assertEquals(0, demo.model.state.value.onboardingChanges)

        demo.set("new-onboarding", FlagValueBox.Bool(true))
        runCurrent()
        assertEquals(1, demo.model.state.value.onboardingChanges)

        demo.set("new-onboarding", null)
        runCurrent()
        assertEquals(2, demo.model.state.value.onboardingChanges, "back to production's false")
    }

    @Test
    fun whereEachValueCameFromListsEveryFlagInSchemaOrder() = runTest {
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        runCurrent()

        val rows = demo.model.values.value.provenance
        assertEquals(demo.model.pole.schema.flags.map { it.key.rawValue }, rows.map { it.key })
        assertEquals(Provenance("environment", "default", isDefault = true), rows.first())
        assertEquals(
            Provenance("checkout.google-pay", "Remote", isDefault = false),
            rows.first { it.key == "checkout.google-pay" },
        )
        assertEquals("default", demo.source("checkout.express.confirmation-delay"))
    }

    @Test
    fun twoRecordsSharingANameReadAsTheCompiledDefault() = runTest {
        // name is the @FlagRecordKey: a list with a repeated key is unreadable, so the flag reads its
        // compiled default (not the Remote layer beneath), while provenance still names the Companion.
        // The companion refuses to write such a list; this pins what happens if one arrives anyway.
        val demo = TestDemo(backgroundScope)
        demo.model.start()
        runCurrent()

        demo.set(
            "payment-methods",
            FlagValueBox.String(
                """[{"name":"","kind":"wallet","enabled":false,"minimumSpend":2.5},""" +
                    """{"name":"","kind":"card","enabled":true,"minimumSpend":1.5}]""",
            ),
        )
        runCurrent()

        assertEquals(
            listOf("Visa", "Google Pay", "Bank transfer"),
            demo.model.values.value.paymentMethods.map { it.name },
        )
        assertEquals("Companion", demo.source("payment-methods"))
    }
}
