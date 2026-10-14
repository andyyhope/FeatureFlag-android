package com.andyyhope.featureflag.ui.editing

import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Ports Swift's FlagEditingStoreEdgeCaseTests, plus what a bridge adds: writes that fail, and
// stored values the host's schema no longer accepts.
class FlagEditingStoreEdgeCaseTest {

    // Sections

    @Test
    fun aSchemaWithNoGroupsIsOneUnnamedSection() {
        val store = store(FlagSchema(flags = listOf(bool("a"), bool("b"))))

        assertEquals(1, store.sections().size)
        assertNull(store.sections()[0].title)
        assertEquals(2, store.sections()[0].entries.size)
    }

    @Test
    fun anEmptySchemaHasNoSections() {
        assertTrue(store(FlagSchema(flags = emptyList())).sections().isEmpty())
    }

    @Test
    fun aGroupHoldingOnlyOtherGroupsShowsNoEmptySection() {
        // "Checkout" contains nothing directly, so it would render as a blank heading.
        val store = store(
            FlagSchema(
                flags = listOf(bool("checkout.express.one-tap", listOf("checkout", "express", "oneTap"))),
                groups = listOf(
                    FlagSchema.Group(listOf("checkout"), "Checkout"),
                    FlagSchema.Group(listOf("checkout", "express"), "Express"),
                ),
            ),
        )

        assertEquals(listOf("Express"), store.sections().map { it.title })
        assertEquals("Checkout › Express", store.sections()[0].pathDescription)
    }

    @Test
    fun searchingForSomethingAbsentLeavesNoSections() {
        assertTrue(store().sections("zzzz").isEmpty())
    }

    @Test
    fun searchIgnoresSurroundingWhitespace() {
        val matches = store().sections("   apple   ").flatMap { it.entries }

        assertEquals(listOf(FlagKey("checkout.apple-pay")), matches.map { it.key })
    }

    // Overrides

    @Test
    fun resettingEverythingLeavesForeignKeysAlone() = runBlocking<Unit> {
        // A companion's source can hold more than this schema's flags.
        val source = ScriptedSource(mapOf(FlagKey("unrelated.key") to FlagValueBox.String("someone else's")))
        val store = store(source = source)

        store.setValue(entry(store, "new-onboarding"), FlagValueBox.Bool(true))
        store.resetAll()

        assertTrue(store.overriddenKeys.isEmpty())
        assertEquals(FlagValueBox.String("someone else's"), source.values[FlagKey("unrelated.key")])
    }

    @Test
    fun aStoredValueOfTheWrongTypeReadsAsNotOverridden() {
        // A stale or hand-edited value shows the default, not a broken control.
        val source = ScriptedSource()
        val store = store(source = source)
        val entry = entry(store, "max-items")

        source.elsewhereWrites("max-items", FlagValueBox.String("nonsense"))

        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun aStoredValueOutsideAnEnumsCasesReadsAsNotOverridden() {
        // The host cannot represent it either, so it falls back to the default. The editor must
        // agree, or it shows a picker with nothing selected.
        val source = ScriptedSource()
        val store = store(FlagSchema(flags = listOf(tier)), source)
        val entry = entry(store, "tier")

        source.elsewhereWrites("tier", FlagValueBox.String("enterprise"))
        assertEquals(FlagValueBox.String("free"), store.value(entry))
        assertFalse(store.isOverridden(entry))

        source.elsewhereWrites("tier", FlagValueBox.String("pro"))
        assertTrue(store.isOverridden(entry))
    }

    @Test
    fun resettingEverythingClearsUnusableValuesToo() = runBlocking<Unit> {
        // After a host update: a value of the wrong type, a case that no longer exists, and a
        // record list with a repeated key. None reads as overridden; all of them are cleared.
        val routes = CompanionSchemas.keyedEditor.flags.first()
        val schema = FlagSchema(flags = CompanionSchemas.demoUi.flags + tier + routes)
        val stale = mapOf(
            FlagKey("max-items") to FlagValueBox.String("nonsense"),
            FlagKey("tier") to FlagValueBox.String("enterprise"),
            FlagKey("routes") to FlagValueBox.String("[{\"name\":\"a\"},{\"name\":\"a\"}]"),
            FlagKey("unrelated.key") to FlagValueBox.Bool(true),
        )
        val source = ScriptedSource(stale)
        val store = store(schema, source)
        assertFalse(store.isOverridden(entry(store, "max-items")))
        assertFalse(store.isOverridden(entry(store, "tier")))
        assertNull(store.records(routes))

        store.resetAll()

        assertEquals(mapOf(FlagKey("unrelated.key") to FlagValueBox.Bool(true)), source.values)
        assertEquals(FlagValueBox.Int(10), store.value(entry(store, "max-items")))
    }

    @Test
    fun resettingEverythingIsOneWriteOfWhatIsStored() = runBlocking<Unit> {
        val source = ScriptedSource(
            mapOf(FlagKey("max-items") to FlagValueBox.Int(3), FlagKey("new-onboarding") to FlagValueBox.Bool(true)),
        )

        store(source = source).resetAll()

        assertEquals(listOf(mapOf<FlagKey, FlagValueBox?>(FlagKey("new-onboarding") to null, FlagKey("max-items") to null)), source.writes)
    }

    @Test
    fun resettingWhenNothingIsStoredWritesNothing() = runBlocking<Unit> {
        val source = ScriptedSource()

        store(source = source).resetAll()

        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun entryLookupForAnUnknownKeyReturnsNothing() {
        assertNull(store().entry(FlagKey("not-a-flag")))
    }

    // Failed writes

    @Test
    fun aFailedWriteLeavesTheValueUnchanged() = runBlocking<Unit> {
        val source = ScriptedSource()
        val store = store(source = source)
        val entry = entry(store, "max-items")
        val refusal = FlagBridgeException.TooLarge(600_000, 524_288)
        source.failure = refusal

        val thrown = assertFailsWith<FlagBridgeException.TooLarge> { store.setValue(entry, FlagValueBox.Int(3)) }

        assertSame(refusal, thrown)
        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
        // The row that showed the edit is told to read the real value again.
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun aFailedResetOrImportThrowsWhatTheSourceThrows() = runBlocking<Unit> {
        val source = ScriptedSource(mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        val store = store(source = source)
        source.failure = FlagBridgeException.Unavailable(null)

        assertFailsWith<FlagBridgeException.Unavailable> { store.reset(entry(store, "max-items")) }
        assertFailsWith<FlagBridgeException.Unavailable> { store.resetAll() }
        assertFailsWith<FlagBridgeException.Unavailable> { store.import("{\"formatVersion\":1,\"values\":{\"max-items\":4}}") }
        assertEquals(FlagValueBox.Int(3), store.value(entry(store, "max-items")))
    }

    // Writes

    @Test
    fun everyWriteBumpsTheRevision() = runBlocking<Unit> {
        val store = store()
        val entry = entry(store, "max-items")

        store.setValue(entry, FlagValueBox.Int(3))
        store.reset(entry)
        store.import("{\"formatVersion\":1,\"values\":{}}")

        assertEquals(3L, store.revision.value)
    }

    @Test
    fun writesRunOnTheWriteDispatcher() = runBlocking<Unit> {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "flag-writes") }
        try {
            val source = ScriptedSource()
            val store = FlagEditingStore(CompanionSchemas.demoUi, source, executor.asCoroutineDispatcher())

            store.setValue(entry(store, "max-items"), FlagValueBox.Int(3))
            store.resetAll()

            // Coroutine debugging, on in tests, appends " @coroutine#n" to the name.
            assertEquals(2, source.writeThreads.size)
            assertTrue(source.writeThreads.all { it.startsWith("flag-writes") }, "${source.writeThreads}")
        } finally {
            executor.shutdown()
        }
    }

    // Live updates

    @Test
    fun anEditFromAnotherProcessRefreshesTheEditor() = runBlocking<Unit> {
        // Stands in for the host app, or a second companion, writing to the same store.
        val source = ScriptedSource()
        val store = store(source = source)
        val observing = store.observeIn(this)

        store.revision.test {
            assertEquals(0L, awaitItem())
            source.elsewhereWrites("new-onboarding", FlagValueBox.Bool(true))
            assertEquals(1L, awaitItem())
        }
        observing.cancelAndJoin()
    }

    @Test
    fun observingStopsWithItsJob() = runBlocking<Unit> {
        val source = ScriptedSource()
        val store = store(source = source)

        store.observeIn(this).cancelAndJoin()
        source.elsewhereWrites("new-onboarding", FlagValueBox.Bool(true))
        yield()

        assertEquals(0L, store.revision.value)
    }

    // Import

    @Test
    fun importingSomethingForeignIsRejectedWithEveryProblem() = runBlocking<Unit> {
        val source = ScriptedSource()
        val store = store(source = source)

        val error = assertFailsWith<FlagImportException.Rejected> {
            store.import("{\"formatVersion\":1,\"values\":{\"nope\":1,\"max-items\":\"lots\"}}")
        }

        assertEquals(setOf(FlagKey("nope"), FlagKey("max-items")), error.problems.map { it.key }.toSet())
        assertTrue(store.overriddenKeys.isEmpty())
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun importingAQrCodeThatIsNotOneIsRejected() = runBlocking<Unit> {
        // The prefix decides: with it, the text is read as a code, and this one does not decompress.
        assertFailsWith<FlagQRCodeException.Corrupt> { store().import("FFQR1:hello") }
        // Without it, the text is read as a document, and is not one either.
        val error = assertFailsWith<FlagImportException.Malformed> { store().import("hello") }
        assertEquals("not a JSON object", error.reason)
    }

    @Test
    fun anImportIsOneWrite() = runBlocking<Unit> {
        val source = ScriptedSource()

        store(source = source).import("{\"formatVersion\":1,\"values\":{\"max-items\":4,\"new-onboarding\":true}}")

        assertEquals(1, source.writes.size)
        assertEquals(
            mapOf<FlagKey, FlagValueBox?>(FlagKey("max-items") to FlagValueBox.Int(4), FlagKey("new-onboarding") to FlagValueBox.Bool(true)),
            source.writes.single(),
        )
    }

    @Test
    fun pastedTextIsTrimmedFirst() = runBlocking<Unit> {
        val sending = store()
        sending.setValue(entry(sending, "max-items"), FlagValueBox.Int(5))
        val receiving = store()

        receiving.import("  \n${sending.qrCode()}\n ")

        assertEquals(FlagValueBox.Int(5), receiving.value(entry(receiving, "max-items")))
    }

    @Test
    fun exportingNothingStillProducesAValidDocument() {
        val payload = FlagPayload.decode(store().exportJson().encodeToByteArray(), CompanionSchemas.demoUi)

        assertEquals(0, payload.values.size)
    }

    // Helpers

    private val tier = FlagSchema.Entry(
        key = FlagKey("tier"),
        propertyPath = listOf("tier"),
        description = "Tier",
        valueType = FlagValueType.String,
        defaultValue = FlagValueBox.String("free"),
        cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
    )

    private fun store(schema: FlagSchema = CompanionSchemas.demoUi, source: ScriptedSource = ScriptedSource()) =
        FlagEditingStore(schema, source, Dispatchers.Unconfined)

    private fun entry(store: FlagEditingStore, key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun bool(key: String, path: List<String> = listOf(key)) = FlagSchema.Entry(
        key = FlagKey(key),
        propertyPath = path,
        description = "",
        valueType = FlagValueType.Bool,
        defaultValue = FlagValueBox.Bool(false),
    )
}
