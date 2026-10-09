package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.FlagRecordProperty
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal enum class RemoteRecordTier(override val flagRawValue: String) : FlagStringEnum { Stable("stable"), Beta("beta") }

@FlagRecord
internal data class RemoteEndpoint(val name: String, val url: URI, val weight: Int, val tier: RemoteRecordTier)

@FlagRecord
internal data class RemoteNote(val text: String)

@FlagContainer
internal interface RemoteRecordFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<RemoteEndpoint>
        get() = FlagRecords(listOf(RemoteEndpoint("prod", URI("https://prod.example"), 10, RemoteRecordTier.Stable)))

    @Flag(description = "Notes")
    val notes: FlagRecords<RemoteNote> get() = FlagRecords()

    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false
}

@FlagRecord
internal data class ClusterTarget(val host: String, val port: Int)

@FlagRecord
internal data class Cluster(val name: String, val targets: FlagRecords<ClusterTarget>)

@FlagContainer
internal interface NestedFlags {
    @Flag(description = "Clusters", remoteKey = "config.clusters")
    val clusters: FlagRecords<Cluster> get() = FlagRecords()
}

@FlagRecord
internal data class DefaultedEndpoint(val name: String, val url: URI, val weight: Int = 1, val region: String = "au")

@FlagContainer
internal interface DefaultedFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<DefaultedEndpoint>
        get() = FlagRecords(listOf(DefaultedEndpoint("production", URI("https://prod.example"))))
}

@FlagRecord
internal data class PayMethod(
    @FlagRecordKey val name: String,
    @FlagRecordProperty(key = "apple_pay")
    val applePay: Boolean,
    val minimumSpend: Double? = null,
)

@FlagContainer
internal interface PayFlags {
    @Flag(description = "Payment methods", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<PayMethod> get() = FlagRecords()
}

@FlagRecord
internal data class FloatRatio(val name: String, val ratio: Float)

@FlagRecord
internal data class DoubleRatio(val name: String, val ratio: Double)

@FlagContainer
internal interface RatioFlags {
    @Flag(description = "Float ratios", remoteKey = "recs")
    val recs: FlagRecords<FloatRatio> get() = FlagRecords()

    @Flag(description = "Double ratios", remoteKey = "doubles")
    val doubles: FlagRecords<DoubleRatio> get() = FlagRecords()
}

@FlagRecord
internal data class KeyedEndpoint(@FlagRecordKey val name: String, val url: URI)

@FlagContainer
internal interface KeyedFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<KeyedEndpoint> get() = FlagRecords()
}

class FlagRecordRemoteTest {

    private val endpointsKey = FlagKey("endpoints")

    private fun json(text: String): ByteArray = text.toByteArray()

    /** `{"config": {"<field>": "<text>"}}`, with the list sent pre-serialised as a string. */
    private fun configString(field: String, text: String): RemoteValue =
        RemoteValue.Object(mapOf("config" to RemoteValue.Object(mapOf(field to RemoteValue.String(text)))))

    // The shape a backend actually sends

    @Test
    fun aListOfObjectsBecomesAListOfRecords() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        val result = remote.apply(
            json(
                """
                { "config": { "endpoints": [
                    { "name": "staging", "url": "https://staging.example", "weight": 7, "tier": "beta" },
                    { "name": "canary", "url": "https://canary.example", "weight": 3, "tier": "stable" }
                ] } }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(endpointsKey), result.appliedKeys)

        val pole = FlagPole(RemoteRecordFlagsSpec, listOf(remote))
        assertEquals(listOf("staging", "canary"), pole.flags.endpoints.map { it.name })
        assertEquals(listOf(7, 3), pole.flags.endpoints.map { it.weight })
        assertEquals(RemoteRecordTier.Beta, pole.flags.endpoints.first().tier)
    }

    @Test
    fun theStoredFormIsCanonicalJsonText() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        remote.apply(json("""{"config": {"endpoints": [{"weight": 7, "url": "https://staging.example", "tier": "beta", "name": "staging"}]}}"""))
        assertEquals(
            FlagValueBox.String("""[{"name":"staging","tier":"beta","url":"https://staging.example","weight":7}]"""),
            remote.box(endpointsKey, FlagValueType.String),
        )
    }

    @Test
    fun anEmptyListIsAppliedRatherThanIgnored() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        remote.apply(json("""{ "config": { "endpoints": [] } }"""))
        assertEquals(emptyList(), FlagPole(RemoteRecordFlagsSpec, listOf(remote)).flags.endpoints.toList())
    }

    // Strictness survives

    @Test
    fun aFieldOfTheWrongTypeRejectsTheWholePayload() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(
                json(
                    """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.example", "weight": "seven", "tier": "beta" } ] }, "featureToggles": { "onboarding": true } }""",
                ),
            )
        }
        assertEquals(listOf(endpointsKey), error.problems.map { it.key })
        assertEquals(Kind.TypeMismatch, error.problems.first().kind)
    }

    @Test
    fun aMissingFieldRejectsTheWholePayloadAndNamesTheShape() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(json("""{ "config": { "endpoints": [ { "name": "partial" } ] } }"""))
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected a list of records (name, url, weight, tier), got an array of 1",
            error.problems.single().toString(),
        )
    }

    @Test
    fun anEnumFieldTheAppCannotRepresentRejectsThePayload() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(
                json("""{ "config": { "endpoints": [ { "name": "x", "url": "https://x.example", "weight": 1, "tier": "experimental" } ] } }"""),
            )
        }
    }

    @Test
    fun aFloatFieldThatOverflowsToInfinityRejectsThePayloadLikeAWrongType() {
        // 1e300 is a valid double, but narrows to Float.POSITIVE_INFINITY, which stored JSON cannot hold.
        val source = RemoteOverrideSource(RatioFlagsSpec)
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            source.apply(json("""{"recs":[{"name":"x","ratio":1e300}], "doubles":[{"name":"y","ratio":0.5}]}"""))
        }
        val wrongType = assertFailsWith<RemoteOverrideException.Rejected> {
            source.apply(json("""{"recs":[{"name":"x","ratio":"high"}]}"""))
        }

        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })
        assertEquals("'recs' → recs: expected a list of records (name, ratio), got an array of 1", error.problems.single().toString())
        assertEquals(wrongType.problems, error.problems)
        assertNull(source.box(FlagKey("doubles"), FlagValueType.String), "all or nothing: the valid flag was not applied")
    }

    @Test
    fun aDoubleFieldThatReadsAsInfinityRejectsThePayloadLikeAWrongType() {
        // -1e400 is beyond Double's range, so it reads as Double.NEGATIVE_INFINITY.
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RatioFlagsSpec).apply(json("""{"doubles":[{"name":"x","ratio":-1e400}]}"""))
        }

        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })
        assertEquals(
            "'doubles' → doubles: expected a list of records (name, ratio), got an array of 1",
            error.problems.single().toString(),
        )
    }

    @Test
    fun somethingThatIsNotAListAtAllIsRejected() {
        // Swift's `recordShape.map { … } ?? box(as:)` keeps the shape's nil. It never falls back to storing the text.
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(json("""{ "config": { "endpoints": "not a list" } }"""))
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected a list of records (name, url, weight, tier), got \"not a list\"",
            error.problems.single().toString(),
        )
    }

    // Nothing else changed

    @Test
    fun ordinaryFlagsStillWorkAlongside() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        val result = remote.apply(
            json("""{ "config": { "endpoints": [ { "name": "one", "url": "https://one.example", "weight": 1, "tier": "stable" } ] }, "featureToggles": { "onboarding": true } }"""),
        )
        assertEquals(listOf(endpointsKey, FlagKey("new-onboarding")), result.appliedKeys)
        assertTrue(FlagPole(RemoteRecordFlagsSpec, listOf(remote)).flags.newOnboarding)
    }

    @Test
    fun aRecordFlagWithNoRemoteKeyIsNotRemotelyOverridable() {
        val result = RemoteOverrideSource(RemoteRecordFlagsSpec).apply(json("""{ "config": { "notes": [ { "text": "nope" } ] } }"""))
        assertFalse(FlagKey("notes") in result.appliedKeys)
    }

    // Nested lists

    @Test
    fun aBackendMaySendANestedListAsRealJsonRatherThanAsAString() {
        val remote = RemoteOverrideSource(NestedFlagsSpec)
        remote.apply(
            json(
                """
                { "config": { "clusters": [
                    { "name": "ap", "targets": [
                        { "host": "a.example", "port": 443 },
                        { "host": "b.example", "port": 8443 }
                    ] }
                ] } }
                """.trimIndent(),
            ),
        )
        val cluster = FlagPole(NestedFlagsSpec, listOf(remote)).flags.clusters.first()
        assertEquals("ap", cluster.name)
        assertEquals(listOf("a.example", "b.example"), cluster.targets.map { it.host })
    }

    @Test
    fun aWrongFieldInsideANestedRecordRejectsTheWholePayload() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NestedFlagsSpec).apply(
                json("""{ "config": { "clusters": [ { "name": "ap", "targets": [ { "host": "a", "port": "https" } ] } ] } }"""),
            )
        }
    }

    @Test
    fun aNestedListMayStillArriveAsAString() {
        val remote = RemoteOverrideSource(NestedFlagsSpec)
        remote.apply(json("""{ "config": { "clusters": [ { "name": "ap", "targets": "[{\"host\":\"a\",\"port\":1}]" } ] } }"""))
        assertEquals(listOf("a"), FlagPole(NestedFlagsSpec, listOf(remote)).flags.clusters.first().targets.map { it.host })
    }

    @Test
    fun aNestedListSentAsSomethingOtherThanRecordsRejectsThePayload() {
        for (targets in listOf("\"not records\"", "5", "{}")) {
            assertFailsWith<RemoteOverrideException.Rejected>(targets) {
                RemoteOverrideSource(NestedFlagsSpec).apply(json("""{"config": {"clusters": [{"name": "ap", "targets": $targets}]}}"""))
            }
        }
    }

    // Defaults

    @Test
    fun aRemotePayloadMayOmitADefaultedField() {
        val remote = RemoteOverrideSource(DefaultedFlagsSpec)
        remote.apply(json("""{ "config": { "endpoints": [ { "name": "from-backend", "url": "https://backend.example" } ] } }"""))
        val endpoint = FlagPole(DefaultedFlagsSpec, listOf(remote)).flags.endpoints.first()
        assertEquals(1, endpoint.weight)
        assertEquals("au", endpoint.region)
    }

    @Test
    fun aRemotePayloadStillCannotOmitAFieldWithNoDefault() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DefaultedFlagsSpec).apply(json("""{ "config": { "endpoints": [ { "url": "https://backend.example" } ] } }"""))
        }
    }

    // Optional fields and custom keys

    @Test
    fun anOmittedOptionalFieldDecodesAsNull() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "card", "apple_pay": false } ] } }"""))
        val method = FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first()
        assertEquals("card", method.name)
        assertNull(method.minimumSpend)
    }

    @Test
    fun anExplicitNullOptionalFieldDecodesAsNull() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "card", "apple_pay": false, "minimumSpend": null } ] } }"""))
        assertNull(FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first().minimumSpend)
    }

    @Test
    fun aPresentOptionalFieldDecodesToItsValue() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "apple_pay": true, "minimumSpend": 10.5 } ] } }"""))
        assertEquals(10.5, FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first().minimumSpend)
    }

    @Test
    fun anOptionalFieldOfTheWrongTypeStillRejects() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(PayFlagsSpec).apply(
                json("""{ "config": { "paymentMethods": [ { "name": "card", "apple_pay": false, "minimumSpend": "lots" } ] } }"""),
            )
        }
    }

    @Test
    fun aCustomKeyIsReadFromThePayload() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "apple_pay": true } ] } }"""))
        assertTrue(FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first().applePay)
    }

    @Test
    fun thePropertyNameIsNotReadWhenACustomKeyIsSet() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(PayFlagsSpec).apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "applePay": true } ] } }"""))
        }
    }

    @Test
    fun theStoredFormIsKeyedByPropertyNameWithUnsetOptionalsLeftOut() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "apple_pay": true } ] } }"""))
        assertEquals(
            FlagValueBox.String("""[{"applePay":true,"name":"wallet"}]"""),
            remote.box(FlagKey("payment-methods"), FlagValueType.String),
        )
    }

    // Keys

    @Test
    fun aRemotePayloadWithADuplicateKeyIsRejectedAndSaysSo() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(KeyedFlagsSpec).apply(
                json("""{ "config": { "endpoints": [ { "name": "staging", "url": "https://one.example" }, { "name": "staging", "url": "https://two.example" } ] } }"""),
            )
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected every record to have its own name, got two with \"staging\"",
            error.problems.single().toString(),
        )
    }

    @Test
    fun aDuplicateKeyInAListSentAsAStringIsNamedToo() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(KeyedFlagsSpec).apply(
                configString("endpoints", """[{"name":"a","url":"https://one.example"},{"name":"a","url":"https://two.example"}]"""),
            )
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected every record to have its own name, got two with \"a\"",
            error.problems.single().toString(),
        )
    }

    // The pre-serialised string quirk (Review Focus 4)

    /**
     * Mirrors FeatureFlag iOS 0.12.0 (`RemoteValue.recordBox`, string branch). This is to
     * be fixed upstream, on both sides together, and then this test flips.
     *
     * A list sent already serialised as a string is checked only as stored text:
     * - fields are read by property name;
     * - enum cases are not checked, and nested lists are not recursed into;
     * - the text is stored verbatim.
     *
     * So an unknown case is applied, and the flag then silently reads its default. The
     * shared remote golden `records-as-string` depends on this.
     */
    @Test
    fun aRecordListSentAsAStringSkipsCaseChecksMirroringIos0120() {
        val text = """[{"name":"x","tier":"experimental","url":"https://x.example","weight":1}]"""
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)

        val result = remote.apply(configString("endpoints", text))

        assertEquals(listOf(endpointsKey), result.appliedKeys)
        assertEquals(FlagValueBox.String(text), remote.box(endpointsKey, FlagValueType.String))
        assertEquals(RemoteRecordFlagsSpec.endpoints.defaultValue, FlagPole(RemoteRecordFlagsSpec, listOf(remote)).flags.endpoints)
    }

    /**
     * Mirrors iOS 0.12.0, like the test above, and is to be fixed upstream with it. The
     * string form is read by property name. A backend that pre-serialises with its own key
     * (`apple_pay`) is refused, even though the same list sent as JSON is accepted.
     */
    @Test
    fun aRecordListSentAsAStringIsReadByPropertyNameMirroringIos0120() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(PayFlagsSpec).apply(configString("paymentMethods", """[{"name":"wallet","apple_pay":true}]"""))
        }
        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })

        val byPropertyName = RemoteOverrideSource(PayFlagsSpec).apply(configString("paymentMethods", """[{"applePay":true,"name":"wallet"}]"""))
        assertEquals(listOf(FlagKey("payment-methods")), byPropertyName.appliedKeys)
    }
}
