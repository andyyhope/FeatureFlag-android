package docs.flagvalues

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.importJson
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/FlagValues.md. */
class FlagValuesTest {

    @Test
    fun everyBuiltInTypeReadsItsDefault() {
        val flags = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource())).flags

        assertFalse(flags.enabled)
        assertEquals(20, flags.pageSize)
        assertEquals(5_000_000_000, flags.quota)
        assertEquals(0.5, flags.rolloutShare)
        assertEquals(1.0f, flags.scale)
        assertEquals("beta", flags.channel)
        assertContentEquals(ByteArray(0), flags.payload)
        assertEquals(Instant.EPOCH, flags.launchesAt)
        assertEquals(URI("https://example.com"), flags.endpoint)
    }

    @Test
    fun intAndLongAreBothStoredAsInt() {
        val types = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource())).schema.valueTypes

        assertEquals(FlagValueType.Int, types[FlagKey("page-size")])
        assertEquals(FlagValueType.Int, types[FlagKey("quota")])
        assertEquals(FlagValueType.Url, types[FlagKey("endpoint")])
    }

    @Test
    fun collectionsRecordsAndEnumsAreDeclaredLikeAnyOtherFlag() {
        val pole = FlagPole(CollectionFlagsSpec, sources = listOf(SnapshotSource()))

        assertEquals(emptyList(), pole.flags.markets)
        assertEquals(emptyMap(), pole.flags.rolloutByMarket)
        assertEquals(emptyList(), pole.flags.endpoints)
        assertEquals(Tier.Free, pole.flags.tier)
        assertEquals(RetryPolicy.Once, pole.flags.retryPolicy)
    }

    @Test
    fun everyEnumPublishesItsCases() {
        val cases = FlagPole(CollectionFlagsSpec, sources = listOf(SnapshotSource())).schema.valueCases

        assertEquals(listOf("Free", "Pro", "Enterprise").map(FlagValueBox::String), cases[FlagKey("tier")])
        assertEquals(listOf("free", "pro", "enterprise").map(FlagValueBox::String), cases[FlagKey("stored-tier")])
        assertEquals(listOf(0L, 1L, 3L).map(FlagValueBox::Int), cases[FlagKey("retry-policy")])
    }

    @Test
    fun aCaseThisBuildDoesNotHaveIsRefused() {
        val pole = FlagPole(CollectionFlagsSpec, sources = listOf(SnapshotSource()))

        val rejected = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson("""{"formatVersion": 1, "values": {"stored-tier": "platinum"}}""".encodeToByteArray())
        }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownCase), rejected.problems.map { it.kind })
        assertEquals(StoredTier.Free, pole.flags.storedTier)
    }

    @Test
    fun aTypeOfYourOwnWrapsOneTheProcessorKnows() {
        val pole = FlagPole(PricingFlagsSpec, sources = listOf(SnapshotSource()))

        assertEquals(Percentage(0.1), pole.flags.discount)
    }

    @Test
    fun boxesCanBeReadAndWrittenDirectly() {
        // region snippet: values-boxes
        val box = FlagValueBox.Bool(true)
        val isBool = box.matches(FlagValueType.Bool) // true
        val value = FlagCodecs.boolean.decode(box) // true
        // endregion

        assertTrue(isBool)
        assertEquals(true, value)
    }
}
