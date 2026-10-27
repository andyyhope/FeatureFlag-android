package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sample's flags, payloads and mapper, checked the way featureflag-core's ScenarioTest checks
 * its copy. A mistyped dot path fails silently as "the backend sent nothing", so every payload is
 * held against the real flag tree.
 */
class ScenarioTest {

    private class Stack(val pole: FlagPole<AppFlags>, val remote: RemoteOverrideSource, val companion: SnapshotSource)

    private fun makeStack(): Stack {
        val companion = SnapshotSource("Companion")
        val remote = RemoteOverrideSource(AppFlagsSpec, name = "Remote")
        return Stack(FlagPole(AppFlagsSpec, listOf(companion, remote)), remote, companion)
    }

    private val bundled = listOf(
        RemoteConfiguration.production,
        RemoteConfiguration.staging,
        RemoteConfiguration.local,
    )

    private fun key(raw: String) = FlagKey(raw)

    // The Google Pay rename

    @Test
    fun googlePayIsDeclaredUnderItsAndroidName() {
        val entry = FlagSchema.of(AppFlagsSpec).flags.first { it.key == key("checkout.google-pay") }
        assertEquals("featureToggles.checkout.googlePay", entry.remoteKey)
        assertEquals("Offer Google Pay at checkout", entry.description)
        assertEquals(
            listOf("Visa", "Google Pay", "Bank transfer"),
            makeStack().pole.flags.paymentMethods.map { it.name },
        )
    }

    @Test
    fun noPayloadStillSaysApplePay() {
        val remote = listOf(
            RemoteConfiguration.production,
            RemoteConfiguration.staging,
            RemoteConfiguration.local,
            RemoteConfiguration.malformed,
            RemoteConfiguration.experiments,
            RemoteConfiguration.experimentsWithATypo,
        ).map { it.json }
        val local = DemoEnvironment.entries.map { LocalConfiguration.forEnvironment(it).json }
        for (json in remote + local) assertFalse(json.contains("apple", ignoreCase = true), json)
        assertTrue("checkout.google-pay" in RemoteConfiguration.experiments.json)
    }

    // RemoteConfigurationTests.swift

    @Test
    fun everyBundledPayloadIsValidJson() {
        for (configuration in bundled + RemoteConfiguration.malformed) {
            assertIs<RemoteValue.Object>(RemoteValue.parseJson(configuration.data), configuration.name)
        }
    }

    @Test
    fun stagingReachesEveryFlagItClaimsTo() {
        val stack = makeStack()
        val result = stack.remote.apply(RemoteConfiguration.staging.data)

        assertEquals(emptyList(), result.absentKeys, "unmatched paths")
        assertEquals(
            listOf("checkout.endpoint", "checkout.google-pay", "new-onboarding", "payment-methods").map(::FlagKey),
            result.appliedKeys,
        )
        assertTrue(stack.pole.flags.newOnboarding)
        assertTrue(stack.pole.flags.checkout.googlePay)
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
        assertEquals(
            listOf("Visa", "Google Pay", "Bank transfer"),
            stack.pole.flags.paymentMethods.map { it.name },
        )
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
    fun theMalformedPayloadIsRejectedAndAppliesNothing() {
        val stack = makeStack()
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            stack.remote.apply(RemoteConfiguration.malformed.data)
        }
        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })
        assertEquals(listOf("featureToggles.onboarding.v2"), error.problems.map { it.remoteKey })
        assertFalse(stack.pole.flags.checkout.googlePay, "all-or-nothing means nothing")
    }

    @Test
    fun aCompanionOverrideSurvivesARemotePayload() {
        val stack = makeStack()
        stack.companion.setBox(key("checkout.google-pay"), FlagValueBox.Bool(true))
        stack.remote.apply(RemoteConfiguration.production.data)

        assertTrue(stack.pole.flags.checkout.googlePay, "a hand-set override outranks the backend")
        assertEquals("Companion", stack.pole.resolution(key("checkout.google-pay"), FlagValueType.Bool).sourceName)

        stack.companion.setBox(key("checkout.google-pay"), null)
        assertFalse(stack.pole.flags.checkout.googlePay)
        assertEquals("Remote", stack.pole.resolution(key("checkout.google-pay"), FlagValueType.Bool).sourceName)
    }

    // DemoRemoteMapperTests.swift

    @Test
    fun theDefaultMapperFindsNothingInARecordList() {
        val plain = RemoteOverrideSource(AppFlagsSpec, name = "Remote")
        val result = plain.apply(RemoteConfiguration.experiments.data)
        assertTrue(result.appliedKeys.isEmpty())
        assertNull(plain.box(key("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun theCustomMapperReshapesTheSamePayload() {
        val source = RemoteOverrideSource(AppFlagsSpec, mapper = DemoRemoteMapper, name = "Remote")
        val result = source.apply(RemoteConfiguration.experiments.data)
        assertEquals(setOf(key("new-onboarding"), key("checkout.google-pay")), result.appliedKeys.toSet())
        assertEquals(FlagValueBox.Bool(true), source.box(key("checkout.google-pay"), FlagValueType.Bool))
    }

    @Test
    fun aMisspelledFlagIsReportedRatherThanIgnored() {
        val source = RemoteOverrideSource(AppFlagsSpec, mapper = DemoRemoteMapper)
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            source.apply(RemoteConfiguration.experimentsWithATypo.data)
        }
        assertEquals(listOf(Kind.UnknownKey), error.problems.map { it.kind })
        assertEquals(listOf(key("new-onbaording")), error.problems.map { it.key })
        assertNull(source.box(key("checkout.google-pay"), FlagValueType.Bool), "nothing applied")
    }

    // EnvironmentDrivenConfigTests.swift

    @Test
    fun theEnvironmentFlagIsNotRemotelyOverridable() {
        val environment = FlagSchema.of(AppFlagsSpec).flags.first { it.key == key("environment") }
        assertNull(
            environment.remoteKey,
            "giving environment a remoteKey lets a payload choose which payload gets fetched",
        )
    }

    @Test
    fun aPayloadCannotChangeTheEnvironmentEvenIfItTries() {
        val stack = makeStack()
        stack.remote.apply(
            """{"environment": "staging", "featureToggles": {"onboarding": {"v2": true}}}""".toByteArray(),
        )
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

    // EnvironmentLayeringTests.swift

    @Test
    fun stagingLayersRemoteOverLocalOverDefault() = runTest {
        val config = demoConfiguration()
        val pole = FlagPole(AppFlagsSpec, config.sources)

        assertTrue(config.load(DemoEnvironment.Staging).isComplete)

        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)
        assertEquals("Remote", pole.resolution(key("checkout.endpoint"), FlagValueType.Url).sourceName)
        assertEquals(10, pole.flags.pageSize)
        assertTrue(pole.resolution(key("page-size"), FlagValueType.Int).isDefault)
    }

    // ConfigurationAuditTests.swift

    @Test
    fun everyBundledPayloadWiresUpEveryFlagItProvides() {
        for (configuration in bundled) {
            val audit = FlagMappingAudit(AppFlagsSpec, configuration.data)
            assertTrue(audit.isComplete, "${configuration.name}:\n$audit")
            assertEquals(emptyList(), audit.absent, configuration.name)
        }
    }
}
