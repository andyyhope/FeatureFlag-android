package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.EnvironmentConfiguration
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DemoApp's flags, payloads, mapper and environment layering, played out without an app. */
class ScenarioTest {

    private class Stack(val pole: FlagPole<DemoFlags>, val remote: RemoteOverrideSource, val local: SnapshotSource)

    private fun makeStack(): Stack {
        val local = SnapshotSource("Companion")
        val remote = RemoteOverrideSource(DemoFlagsSpec, name = "Remote")
        return Stack(FlagPole(DemoFlagsSpec, listOf(local, remote)), remote, local)
    }

    private fun makeConfig(): EnvironmentConfiguration<DemoEnvironment> = EnvironmentConfiguration(
        DemoFlagsSpec,
        mapper = DemoRemoteMapper,
        localName = "Local",
        remoteName = "Remote",
        local = { LocalConfiguration.forEnvironment(it).data },
        remote = { RemoteConfiguration.forEnvironment(it).data },
    )

    private val bundled = listOf(RemoteConfiguration.production, RemoteConfiguration.staging, RemoteConfiguration.local)

    private fun key(raw: String) = FlagKey(raw)

    // RemoteConfigurationTests.swift

    @Test
    fun everyBundledPayloadIsValidJson() {
        val configurations = DemoEnvironment.entries.map { RemoteConfiguration.forEnvironment(it) }
        for (configuration in configurations + RemoteConfiguration.malformed) {
            RemoteValue.parseJson(configuration.data)
        }
    }

    @Test
    fun stagingReachesEveryFlagItClaimsTo() {
        val stack = makeStack()
        val result = stack.remote.apply(RemoteConfiguration.staging.data)

        assertEquals(emptyList(), result.absentKeys, "unmatched paths")
        assertEquals(listOf("checkout.apple-pay", "checkout.endpoint", "new-onboarding", "payment-methods").map(::FlagKey), result.appliedKeys)
        assertTrue(stack.pole.flags.newOnboarding)
        assertTrue(stack.pole.flags.checkout.applePay)
        assertEquals("staging.api.example.com", stack.pole.flags.checkout.endpoint.host)
    }

    @Test
    fun aPayloadReplacesTheWholeListOfRecords() {
        val stack = makeStack()
        assertEquals(3, stack.pole.flags.paymentMethods.size, "the compiled default")

        stack.remote.apply(RemoteConfiguration.local.data)

        assertEquals(listOf("Test card"), stack.pole.flags.paymentMethods.map { it.name })
        assertEquals(PaymentKind.Card, stack.pole.flags.paymentMethods.first().kind)
    }

    @Test
    fun eachEnvironmentBringsItsOwnPaymentMethods() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.production.data)
        assertEquals(listOf("Visa", "Mastercard"), stack.pole.flags.paymentMethods.map { it.name })

        stack.remote.apply(RemoteConfiguration.staging.data)
        assertEquals(listOf("Visa", "Apple Pay", "Bank transfer"), stack.pole.flags.paymentMethods.map { it.name })
        assertEquals(10.0, stack.pole.flags.paymentMethods.last().minimumSpend)
    }

    @Test
    fun aPayloadCanCarryANestedListOfRecords() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.staging.data)
        val transfer = stack.pole.flags.paymentMethods.first { it.name == "Bank transfer" }
        assertEquals(listOf("AUD", "NZD"), transfer.limits.map { it.currency })
        assertEquals(5000.0, transfer.limits.first().maximum)
    }

    @Test
    fun aPayloadThatOmitsANestedListGetsTheDeclaredDefault() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.production.data)
        assertEquals(listOf("Visa", "Mastercard"), stack.pole.flags.paymentMethods.map { it.name })
        assertTrue(stack.pole.flags.paymentMethods.first().limits.isEmpty())
    }

    @Test
    fun everyBundledPayloadSendsRecordsThisBuildCanRead() {
        for (configuration in bundled) makeStack().remote.apply(configuration.data)
    }

    @Test
    fun productionTurnsThingsOff() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.staging.data)
        assertTrue(stack.pole.flags.checkout.applePay)

        val result = stack.remote.apply(RemoteConfiguration.production.data)

        assertEquals(emptyList(), result.absentKeys)
        assertFalse(stack.pole.flags.checkout.applePay)
        assertFalse(stack.pole.flags.newOnboarding)
    }

    @Test
    fun theMalformedPayloadIsRejectedAndAppliesNothing() {
        val stack = makeStack()
        val error =
            assertFailsWith<RemoteOverrideException.Rejected> { stack.remote.apply(RemoteConfiguration.malformed.data) }
        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })
        assertEquals(listOf("featureToggles.onboarding.v2"), error.problems.map { it.remoteKey })
        assertFalse(stack.pole.flags.checkout.applePay, "all-or-nothing means nothing")
    }

    @Test
    fun onlyTheMalformedPayloadIsLabelledBroken() {
        assertTrue(RemoteConfiguration.malformed.isDeliberatelyBroken)
        for (environment in DemoEnvironment.entries) {
            assertFalse(RemoteConfiguration.forEnvironment(environment).isDeliberatelyBroken)
        }
    }

    @Test
    fun aCompanionOverrideSurvivesARemotePayload() {
        val stack = makeStack()
        stack.local.setBox(key("checkout.apple-pay"), FlagValueBox.Bool(true))
        stack.remote.apply(RemoteConfiguration.production.data)

        assertTrue(stack.pole.flags.checkout.applePay, "a hand-set override outranks the backend")
        assertEquals("Companion", stack.pole.resolution(key("checkout.apple-pay"), FlagValueType.Bool).sourceName)

        stack.local.setBox(key("checkout.apple-pay"), null)
        assertFalse(stack.pole.flags.checkout.applePay)
        assertEquals("Remote", stack.pole.resolution(key("checkout.apple-pay"), FlagValueType.Bool).sourceName)
    }

    // DemoRemoteMapperTests.swift

    @Test
    fun theDefaultMapperFindsNothingInARecordList() {
        val plain = RemoteOverrideSource(DemoFlagsSpec, name = "Remote")
        val result = plain.apply(RemoteConfiguration.experiments.data)
        assertTrue(result.appliedKeys.isEmpty())
        assertNull(plain.box(key("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun theCustomMapperReshapesTheSamePayload() {
        val source = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper, name = "Remote")
        val result = source.apply(RemoteConfiguration.experiments.data)
        assertEquals(setOf(key("new-onboarding"), key("checkout.apple-pay")), result.appliedKeys.toSet())
        assertEquals(FlagValueBox.Bool(true), source.box(key("new-onboarding"), FlagValueType.Bool))
        assertEquals(FlagValueBox.Bool(true), source.box(key("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun theOrdinaryDotPathPayloadsStillApply() {
        for (configuration in bundled) {
            val result = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper).apply(configuration.data)
            assertFalse(result.appliedKeys.isEmpty(), "${configuration.name} should still apply through the dot paths")
        }
    }

    @Test
    fun aMisspelledFlagIsReportedRatherThanIgnored() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper).apply(RemoteConfiguration.experimentsWithATypo.data)
        }
        assertEquals(listOf(Kind.UnknownKey), error.problems.map { it.kind })
        assertEquals(listOf(key("new-onbaording")), error.problems.map { it.key })
    }

    @Test
    fun nothingIsAppliedWhenOneRecordIsWrong() {
        val source = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper)
        assertFailsWith<RemoteOverrideException.Rejected> { source.apply(RemoteConfiguration.experimentsWithATypo.data) }
        assertNull(source.box(key("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun aRecordMissingItsFieldsIsSkippedRatherThanCrashing() {
        val result = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper)
            .apply("""{"experiments": [{"flag": "new-onboarding"}, {"enabled": true}]}""".toByteArray())
        assertTrue(result.appliedKeys.isEmpty())
    }

    // EnvironmentDrivenConfigTests.swift

    @Test
    fun theEnvironmentFlagIsNotRemotelyOverridable() {
        val environment = FlagSchema.of(DemoFlagsSpec).flags.first { it.key == key("environment") }
        assertNull(environment.remoteKey, "giving environment a remoteKey lets a payload choose which payload gets fetched")
    }

    @Test
    fun aPayloadCannotChangeTheEnvironmentEvenIfItTries() {
        val stack = makeStack()
        stack.remote.apply("""{"environment": "staging", "featureToggles": {"onboarding": {"v2": true}}}""".toByteArray())
        assertEquals(DemoEnvironment.Production, stack.pole.flags.environment, "the backend does not get a vote")
        assertTrue(stack.pole.flags.newOnboarding, "the rest of the payload still applies")
    }

    @Test
    fun everyEnvironmentHasAPayloadThatMatchesEveryFlagItClaims() {
        for (environment in DemoEnvironment.entries) {
            val result = makeStack().remote.apply(RemoteConfiguration.forEnvironment(environment).data)
            assertEquals(emptyList(), result.absentKeys, "${environment.flagRawValue}: unmatched dot paths")
        }
    }

    @Test
    fun switchingEnvironmentSwitchesTheBackend() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.forEnvironment(DemoEnvironment.Production).data)
        assertEquals("api.example.com", stack.pole.flags.checkout.endpoint.host)
        assertFalse(stack.pole.flags.checkout.applePay)

        stack.local.setBox(key("environment"), FlagValueBox.String("staging"))
        stack.remote.apply(RemoteConfiguration.forEnvironment(stack.pole.flags.environment).data)

        assertEquals(DemoEnvironment.Staging, stack.pole.flags.environment)
        assertEquals("staging.api.example.com", stack.pole.flags.checkout.endpoint.host)
        assertTrue(stack.pole.flags.checkout.applePay)
    }

    @Test
    fun clearingFirstFallsBackToDefaultsRatherThanStaleValues() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.forEnvironment(DemoEnvironment.Staging).data)
        assertEquals("staging.api.example.com", stack.pole.flags.checkout.endpoint.host)

        stack.remote.clear()

        assertEquals("api.example.com", stack.pole.flags.checkout.endpoint.host, "the compiled default, not staging's leftover value")
        assertTrue(stack.pole.resolution(key("checkout.endpoint"), FlagValueType.Url).isDefault)
    }

    @Test
    fun anOverrideSurvivesAnEnvironmentSwitch() {
        val stack = makeStack()
        stack.local.setBox(key("checkout.apple-pay"), FlagValueBox.Bool(true))
        stack.remote.apply(RemoteConfiguration.forEnvironment(DemoEnvironment.Production).data)
        assertTrue(stack.pole.flags.checkout.applePay)
        assertEquals("Companion", stack.pole.resolution(key("checkout.apple-pay"), FlagValueType.Bool).sourceName)

        stack.local.setBox(key("checkout.apple-pay"), null)

        assertFalse(stack.pole.flags.checkout.applePay, "production's value shows through once cleared")
    }

    // EnvironmentLayeringTests.swift

    @Test
    fun stagingLayersRemoteOverLocalOverDefault() = runTest {
        val config = makeConfig()
        val pole = FlagPole(DemoFlagsSpec, config.sources)

        assertTrue(config.load(DemoEnvironment.Staging).isComplete)

        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)
        assertEquals("Remote", pole.resolution(key("checkout.endpoint"), FlagValueType.Url).sourceName)
        assertEquals(10, pole.flags.pageSize)
        assertTrue(pole.resolution(key("page-size"), FlagValueType.Int).isDefault)
    }

    @Test
    fun clearingRemoteFallsBackToTheLocalLayerNotTheDefault() = runTest {
        val config = makeConfig()
        val pole = FlagPole(DemoFlagsSpec, config.sources)
        config.load(DemoEnvironment.Staging)
        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)

        config.remoteSource.clear()

        assertEquals("staging-local.example", pole.flags.checkout.endpoint.host)
        assertEquals("Local", pole.resolution(key("checkout.endpoint"), FlagValueType.Url).sourceName)
    }

    @Test
    fun switchingEnvironmentReplacesBothLayers() = runTest {
        val config = makeConfig()
        val pole = FlagPole(DemoFlagsSpec, config.sources)
        config.load(DemoEnvironment.Staging)
        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)

        config.load(DemoEnvironment.Local)

        assertEquals("localhost", pole.flags.checkout.endpoint.host)
    }

    // ConfigurationAuditTests.swift

    @Test
    fun everyBundledPayloadWiresUpEveryFlagItProvides() {
        for (configuration in bundled) {
            val audit = FlagMappingAudit(DemoFlagsSpec, configuration.data)
            assertTrue(audit.isComplete, "${configuration.name}:\n$audit")
            assertEquals(emptyList(), audit.absent, configuration.name)
        }
    }

    @Test
    fun everyBundledPayloadIsFullyConsumed() {
        // Swift calls XCTAssertFlagsFullyMapped(strict: true). featureflag-test depends on core,
        // so core's own tests assert the same verdict directly.
        for (configuration in bundled) FlagMappingAudit(DemoFlagsSpec, configuration.data).requireComplete(strict = true)
    }

    @Test
    fun theRecordListSubtreeCountsAsConsumed() {
        val audit = FlagMappingAudit(DemoFlagsSpec, RemoteConfiguration.staging.data)
        assertTrue(key("payment-methods") in audit.applied)
        assertEquals(emptyList(), audit.unconsumed.filter { "paymentMethods" in it })
    }

    @Test
    fun aMisspelledPathIsCaughtAsAbsentAndUnconsumed() {
        val broken = RemoteConfiguration.staging.json.replace("\"apiEndpoint\"", "\"api_endpoint\"")
        val audit = FlagMappingAudit(DemoFlagsSpec, broken.toByteArray())
        assertTrue(key("checkout.endpoint") in audit.absent, "$audit")
        assertTrue("config.api_endpoint" in audit.unconsumed, "$audit")
        assertFalse(audit.isComplete)
    }
}
