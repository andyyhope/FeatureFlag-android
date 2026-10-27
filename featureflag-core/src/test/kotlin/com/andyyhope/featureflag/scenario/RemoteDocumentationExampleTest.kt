package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.exportJson
import com.andyyhope.featureflag.transport.importJson
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// README.md, as written there

internal enum class ReadmeTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

@FlagContainer
internal interface ReadmeAppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @FlagGroup(description = "Checkout")
    val checkout: ReadmeCheckoutFlags
}

@FlagContainer
internal interface ReadmeCheckoutFlags {
    @Flag(description = "Offer Apple Pay")
    val applePay: Boolean get() = false

    @Flag(description = "Pricing tier to present")
    val tier: ReadmeTier get() = ReadmeTier.Free
}

@FlagContainer
internal interface NumberAllowanceFlags {
    @Flag(description = "Ratio", remoteKey = "cfg.ratio")
    val ratio: Double get() = 0.0

    @Flag(description = "Enabled", remoteKey = "cfg.enabled")
    val enabled: Boolean get() = false
}

// The DocC articles, as written there

@FlagContainer
internal interface DocsAppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: DocsCheckoutFlags
}

@FlagContainer
internal interface DocsCheckoutFlags {
    @Flag(description = "Offer Apple Pay", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = false

    @FlagGroup(description = "Express")
    val express: DocsExpressFlags
}

@FlagContainer
internal interface DocsExpressFlags {
    @Flag(description = "One-tap purchase")
    val oneTap: Boolean get() = false
}

@FlagContainer
internal interface DocsArrayPathFlags {
    @Flag(description = "First experiment", remoteKey = "experiments.0.enabled")
    val firstExperiment: Boolean get() = false
}

@FlagContainer
internal interface DocsExperimentFlags {
    @Flag(description = "Onboarding", remoteKey = "onboarding-v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Apple Pay", remoteKey = "apple-pay")
    val applePay: Boolean get() = false
}

internal enum class DocsTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro"), Enterprise("enterprise") }

internal enum class DocsRetryPolicy(override val flagRawValue: Long) : FlagIntEnum { None(0), Once(1), Aggressive(3) }

@FlagContainer
internal interface DocsEnumFlags {
    @Flag(description = "Pricing tier to present", remoteKey = "cfg.tier")
    val tier: DocsTier get() = DocsTier.Free

    @Flag(description = "Retry policy")
    val retryPolicy: DocsRetryPolicy get() = DocsRetryPolicy.Once
}

internal enum class DocsPaymentKind(override val flagRawValue: String) : FlagStringEnum { Card("card"), Wallet("wallet") }

@FlagRecord
internal data class DocsPaymentMethod(
    val name: String,
    val kind: DocsPaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
internal interface DocsRecordFlags {
    @Flag(description = "Payment methods offered at checkout", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<DocsPaymentMethod>
        get() = FlagRecords(
            listOf(
                DocsPaymentMethod("Visa", DocsPaymentKind.Card, enabled = true, minimumSpend = 0.0),
                DocsPaymentMethod("Apple Pay", DocsPaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
            ),
        )
}

/** The custom mapper sample from RemoteOverrides.md. */
private object ExperimentListMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val experiments = payload.valueAt("experiments") as? RemoteValue.Array
            ?: throw RemoteOverrideException.Malformed("expected an experiments array")
        val states = HashMap<String, Boolean>()
        for (experiment in experiments.value) {
            val name = experiment.valueAt("name") as? RemoteValue.String
            val state = experiment.valueAt("state") as? RemoteValue.String
            if (name != null && state != null) states[name.value] = state.value == "on"
        }
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (entry in schema.flags) {
            val state = entry.remoteKey?.let { states[it] } ?: continue
            result[entry.key] = RemoteValue.Bool(state)
        }
        return result
    }
}

/** A mapper with the typo the article warns about. */
private val docsTypoMapper = RemoteOverrideMapper { _, _ -> mapOf(FlagKey("new-onbaording") to RemoteValue.Bool(true)) }

class RemoteDocumentationExampleTest {

    private fun json(text: String): ByteArray = text.toByteArray()

    private val onboardingOn = """{"featureToggles": {"onboarding": {"v2": true}}}"""

    // README.md

    @Test
    fun theQuickStartReadsThroughAStackWithARemoteSource() {
        val local = SnapshotSource("local")
        val pole = FlagPole(ReadmeAppFlagsSpec, listOf(local, RemoteOverrideSource(ReadmeAppFlagsSpec)))
        assertFalse(pole.flags.checkout.applePay)

        local.setBox(FlagKey("checkout.apple-pay"), FlagValueBox.Bool(true))

        assertTrue(pole.flags.checkout.applePay)
    }

    @Test
    fun theRemoteOverrideSampleApplies() {
        val remote = RemoteOverrideSource(ReadmeAppFlagsSpec)
        val pole = FlagPole(ReadmeAppFlagsSpec, listOf(remote))
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun remoteValidationIsStrictAsDocumented() {
        val remote = RemoteOverrideSource(ReadmeAppFlagsSpec)
        val pole = FlagPole(ReadmeAppFlagsSpec, listOf(remote))
        assertFailsWith<RemoteOverrideException.Rejected> {
            remote.apply(json("""{"featureToggles": {"onboarding": {"v2": "true"}}}"""))
        }
        assertFalse(pole.flags.newOnboarding)
    }

    @Test
    fun theDocumentedNumberAllowanceHolds() {
        val remote = RemoteOverrideSource(NumberAllowanceFlagsSpec)
        val pole = FlagPole(NumberAllowanceFlagsSpec, listOf(remote))
        remote.apply(json("""{"cfg": {"ratio": 1}}"""))
        assertEquals(1.0, pole.flags.ratio, "a whole number should satisfy a Double flag")
        assertFailsWith<RemoteOverrideException.Rejected>("a quoted true is still not a boolean") {
            remote.apply(json("""{"cfg": {"enabled": "true"}}"""))
        }
    }

    // RemoteOverrides.md

    @Test
    fun theRemoteDotPathSample() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun aPathCanIndexIntoAnArray() {
        val remote = RemoteOverrideSource(DocsArrayPathFlagsSpec)
        val pole = FlagPole(DocsArrayPathFlagsSpec, listOf(remote))
        remote.apply(json("""{"experiments": [{"enabled": true}]}"""))
        assertTrue(pole.flags.firstExperiment)
    }

    @Test
    fun aFlagWithoutARemoteKeyIsNotRemotelyOverridable() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        val result = remote.apply(json("""{"page-size": 50}"""))
        assertFalse(FlagKey("page-size") in result.appliedKeys)
        assertFalse(FlagKey("page-size") in result.absentKeys)
        assertEquals(20, pole.flags.pageSize)
    }

    @Test
    fun applyReportsAppliedAndAbsentKeys() {
        val result = RemoteOverrideSource(DocsAppFlagsSpec).apply(json(onboardingOn))
        assertEquals(listOf(FlagKey("new-onboarding")), result.appliedKeys)
        assertEquals(listOf(FlagKey("checkout.apple-pay")), result.absentKeys)
    }

    @Test
    fun applyingReplacesEverythingTheSourceHeld() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)

        remote.apply(json("""{"featureToggles": {}}"""))
        assertFalse(pole.flags.newOnboarding, "a flag the new payload omits stops being overridden")

        remote.apply(json(onboardingOn))
        remote.clear()
        assertFalse(pole.flags.newOnboarding)
    }

    @Test
    fun rejectionReportsEveryProblemAndAppliesNothing() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            remote.apply(json("""{"featureToggles": {"onboarding": {"v2": "yes"}, "checkout": {"applePay": 1}}}"""))
        }
        assertEquals(2, error.problems.size, "every problem is reported, not just the first")
        assertEquals(setOf(Kind.TypeMismatch), error.problems.map { it.kind }.toSet())
        assertFalse(pole.flags.newOnboarding)
        assertFalse(pole.flags.checkout.applePay)
    }

    @Test
    fun theCustomMapperSample() {
        val remote = RemoteOverrideSource(DocsExperimentFlagsSpec, mapper = ExperimentListMapper)
        val pole = FlagPole(DocsExperimentFlagsSpec, listOf(remote))
        remote.apply(
            json(
                """
                {
                  "experiments": [
                    { "name": "onboarding-v2", "state": "on" },
                    { "name": "apple-pay",     "state": "off" }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertTrue(pole.flags.newOnboarding)
        assertFalse(pole.flags.applePay)
    }

    @Test
    fun theCustomMapperMayRefuseAPayloadItCannotRead() {
        val error = assertFailsWith<RemoteOverrideException.Malformed> {
            RemoteOverrideSource(DocsExperimentFlagsSpec, mapper = ExperimentListMapper).apply(json("{}"))
        }
        assertEquals("The remote payload could not be read: expected an experiments array.", error.message)
    }

    @Test
    fun aMapperEmittingAnUnknownKeyIsReported() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DocsAppFlagsSpec, mapper = docsTypoMapper).apply(json("{}"))
        }
        assertEquals(listOf(Kind.UnknownKey), error.problems.map { it.kind })
    }

    @Test
    fun theRemoteSourceNeedsTheSameKeyEncoding() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec, keyEncoding = KeyEncoding.Snakecase)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote), keyEncoding = KeyEncoding.Snakecase)
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)

        val mismatched = RemoteOverrideSource(DocsAppFlagsSpec)
        val error = assertFailsWith<IllegalArgumentException> {
            FlagPole(DocsAppFlagsSpec, listOf(mismatched), keyEncoding = KeyEncoding.Snakecase)
        }
        assertEquals(
            "RemoteOverrideSource 'Remote' describes 'new-onboarding', which this pole does not have. " +
                "Build it with the pole's keyEncoding.",
            error.message,
        )
    }

    // FlagValues.md

    @Test
    fun anUnknownEnumCaseIsRejected() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DocsEnumFlagsSpec).apply(json("""{"cfg": {"tier": "platinum"}}"""))
        }
        assertEquals(listOf(Kind.UnknownCase), error.problems.map { it.kind })
    }

    // ExportingAndImporting.md

    @Test
    fun anImportedValueOutranksALaterRemotePayload() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(SnapshotSource(), remote))
        pole.importJson(json("""{"formatVersion": 1, "values": {"new-onboarding": true}}"""))
        remote.apply(json("""{"featureToggles": {"onboarding": {"v2": false}}}"""))
        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun exportCarriesValuesARemotePayloadSupplied() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(SnapshotSource(), remote))
        remote.apply(json(onboardingOn))
        val exported = pole.exportJson().decodeToString()
        assertTrue(exported.contains("new-onboarding"), exported)
    }

    // RecordFlags.md

    @Test
    fun theRecordArticleRemotePayloadApplies() {
        val remote = RemoteOverrideSource(DocsRecordFlagsSpec)
        remote.apply(
            json("""{ "config": { "paymentMethods": [ { "name": "Bank transfer", "kind": "card", "enabled": true, "minimumSpend": 10 } ] } }"""),
        )
        val methods = FlagPole(DocsRecordFlagsSpec, listOf(remote)).flags.paymentMethods
        assertEquals(listOf("Bank transfer"), methods.map { it.name })
    }

    @Test
    fun theRecordArticleRemotePayloadIsAllOrNothing() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DocsRecordFlagsSpec).apply(json("""{ "config": { "paymentMethods": [ { "name": "Partial" } ] } }"""))
        }
    }
}
