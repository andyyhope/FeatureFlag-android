package docs.recordflags

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What docs/RecordFlags.md says a backend can send for `config.endpoints`. */
val endpointsPayload = """
    { "config": { "endpoints": [
        { "name": "staging", "url": "https://staging.example", "weight": 7 }
    ] } }
""".trimIndent()

/** A backend whose field names are not the record's, and which leaves one out. */
val renamedFieldsPayload = """
    { "config": { "paymentOptions": [
        { "name": "card", "google_pay": false },
        { "name": "wallet", "google_pay": true, "minimumSpend": 10 }
    ] } }
""".trimIndent()

/** One record with a nested list, as a backend writes it. */
val nestedRecord = """
    { "name": "Visa", "limits": [ { "currency": "AUD", "maximum": 500 } ] }
""".trimIndent()

/** The problem a list with two records keyed "staging" is refused with. */
fun duplicateRecordKeyProblem(): String {
    val payload = """{"config": {"endpoints": [{"name": "staging", "url": "https://a.example"},""" +
        """ {"name": "staging", "url": "https://b.example"}]}}"""
    val rejected = assertFailsWith<RemoteOverrideException.Rejected> {
        RemoteOverrideSource(EndpointFlagsSpec).apply(payload.encodeToByteArray())
    }
    return rejected.problems.single().toString()
}

/** How a list of one record is stored. */
fun storedRecordText(): String {
    val source = SnapshotSource()
    val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
    pole.setOverride(AppFlagsSpec.paymentMethods, FlagRecords(listOf(visa)))
    return (source.values.getValue(FlagKey("payment-methods")) as FlagValueBox.String).value
}

/** The schema a host with only [AppFlags] publishes. */
fun recordSchema(): String =
    FlagSchema.of(AppFlagsSpec, generatedAt = Instant.parse("2026-01-01T00:00:00Z")).jsonBytes().decodeToString()

private val visa = PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0)

/** docs/RecordFlags.md. */
class RecordFlagsTest {

    private val source = SnapshotSource()
    private val shown = mutableListOf<String>()

    private fun show(name: String) {
        shown += name
    }

    @Test
    fun readingGivesYourOwnTypeBack() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))

        // region snippet: records-reading
        for (method in pole.flags.paymentMethods.filter { it.enabled }) {
            show(method.name)
        }
        // endregion

        assertEquals(listOf("Visa", "Google Pay"), shown)
    }

    @Test
    fun aRecordIsFoundByItsKey() {
        val remote = RemoteOverrideSource(EndpointFlagsSpec)
        val pole = FlagPole(EndpointFlagsSpec, sources = listOf(remote))
        remote.apply(endpointsPayload.encodeToByteArray())

        // region snippet: records-find-by-key
        val staging = pole.flags.endpoints.firstOrNull { it.name == "staging" }?.url
        // endregion

        assertEquals(URI("https://staging.example"), staging)
    }

    @Test
    fun twoRecordsWithOneKeyAreRefused() {
        assertTrue(duplicateRecordKeyProblem().contains("expected every record to have its own name"))
    }

    @Test
    fun aListIsStoredAsJsonText() {
        assertEquals("""[{"enabled":true,"kind":"card","minimumSpend":0,"name":"Visa"}]""", storedRecordText())
        assertTrue(recordSchema().contains("\"recordShape\""))
    }

    @Test
    fun aRecordFieldMayBeRenamedOrLeftOut() {
        val remote = RemoteOverrideSource(BackendFlagsSpec)
        val pole = FlagPole(BackendFlagsSpec, sources = listOf(remote))

        remote.apply(renamedFieldsPayload.encodeToByteArray())

        assertEquals(
            listOf(PaymentOption("card", googlePay = false, minimumSpend = null), PaymentOption("wallet", true, 10.0)),
            pole.flags.paymentOptions.values,
        )
    }

    @Test
    fun aFieldCanBeAListOfRecords() {
        val remote = RemoteOverrideSource(BackendFlagsSpec)
        val pole = FlagPole(BackendFlagsSpec, sources = listOf(remote))

        remote.apply("""{"config": {"providers": [$nestedRecord]}}""".encodeToByteArray())

        assertEquals(listOf(SpendLimit("AUD", 500.0)), pole.flags.providers.single().limits.values)
    }

    @Test
    fun aFieldAddedLaterIsFilledFromItsDefault() {
        val pole = FlagPole(BackendFlagsSpec, sources = listOf(source))
        source.setBox(FlagKey("mirrors"), FlagValueBox.String("""[{"name":"eu","url":"https://eu.example"}]"""))

        assertEquals(listOf(Mirror("eu", URI("https://eu.example"), weight = 1)), pole.flags.mirrors.values)
    }

    @Test
    fun aListThatCannotBeReadFallsBackToTheDefault() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
        source.setBox(FlagKey("payment-methods"), FlagValueBox.String("""[{"name":"Visa"}]"""))

        // region snippet: records-resolution
        val stored = pole.resolution(AppFlagsSpec.paymentMethods)
        // endregion

        assertEquals(AppFlagsSpec.paymentMethods.defaultValue, pole.flags.paymentMethods)
        assertEquals(FlagValueBox.String("""[{"name":"Visa"}]"""), stored.box)
    }
}
