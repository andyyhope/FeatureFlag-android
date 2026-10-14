package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagEditingStoreTests and the store half of FlagContainerDescriptionUITests. The
// companion never sees the host's types: everything here is driven by a schema and a source.
class FlagEditingStoreTest {

    private val source = SnapshotSource("local")
    private val store = FlagEditingStore(CompanionSchemas.companion, source, Dispatchers.Unconfined)

    // Sections

    @Test
    fun ungroupedFlagsComeFirstInAnUnnamedSection() {
        val section = store.sections()[0]

        assertNull(section.title)
        assertEquals(listOf("new-onboarding", "max-items"), section.entries.keys())
    }

    @Test
    fun eachGroupBecomesASectionLabelledByItsDescription() {
        assertEquals(listOf(null, "Checkout", "Express"), store.sections().map { it.title })
    }

    @Test
    fun nestedGroupSectionsAreLabelledWithTheirFullPath() {
        assertEquals("Checkout › Express", store.sections()[2].pathDescription)
        assertEquals("Checkout", store.sections()[1].pathDescription)
        assertEquals("checkout.express", store.sections()[2].id)
    }

    // Search

    @Test
    fun searchMatchesKeys() {
        assertEquals(listOf("checkout.apple-pay"), store.sections("apple").flatMap { it.entries }.keys())
    }

    @Test
    fun searchMatchesDescriptions() {
        assertEquals(listOf("new-onboarding"), store.sections("onboarding").flatMap { it.entries }.keys())
    }

    @Test
    fun searchIsCaseInsensitive() {
        assertEquals(1, store.sections("APPLE").flatMap { it.entries }.size)
    }

    @Test
    fun emptySectionsAreDroppedWhileSearching() {
        assertEquals(listOf("Checkout"), store.sections("apple").map { it.title })
    }

    // Values

    @Test
    fun aFlagWithNoOverrideShowsItsDefault() {
        val entry = entry("max-items")

        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
    }

    @Test
    fun settingAValueMarksTheFlagAsOverridden() = runBlocking<Unit> {
        val entry = entry("max-items")

        store.setValue(entry, FlagValueBox.Int(3))

        assertEquals(FlagValueBox.Int(3), store.value(entry))
        assertTrue(store.isOverridden(entry))
    }

    @Test
    fun resettingRestoresTheDefault() = runBlocking<Unit> {
        val entry = entry("max-items")
        store.setValue(entry, FlagValueBox.Int(3))

        store.reset(entry)

        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
    }

    @Test
    fun resetAllClearsEveryOverride() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))
        store.setValue(entry("new-onboarding"), FlagValueBox.Bool(true))

        store.resetAll()

        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun overriddenKeysReportsWhatHasBeenChanged() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))

        assertEquals(listOf(FlagKey("max-items")), store.overriddenKeys)
    }

    @Test
    fun overriddenCountCountsEveryFlagBeneathANode() = runBlocking<Unit> {
        store.setValue(entry("checkout.apple-pay"), FlagValueBox.Bool(true))
        store.setValue(entry("checkout.express.label"), FlagValueBox.String("Go"))

        val checkout = store.tree.groups.single()
        assertEquals(2, store.overriddenCount(checkout))
        assertEquals(1, store.overriddenCount(checkout.groups.single()))
        assertEquals(2, store.overriddenCount(store.tree))
    }

    // Choosing an editor

    @Test
    fun eachTypeGetsASuitableEditor() {
        assertEquals(FlagEditorKind.Toggle, entry("new-onboarding").editorKind)
        assertEquals(FlagEditorKind.Integer, entry("max-items").editorKind)
        assertEquals(FlagEditorKind.Decimal, entry("checkout.ratio").editorKind)
        assertEquals(FlagEditorKind.Text, entry("checkout.express.label").editorKind)
        assertEquals(FlagEditorKind.Date, entry("checkout.launched-at").editorKind)
        assertEquals(FlagEditorKind.Url, entry("checkout.endpoint").editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.String), entry("checkout.express.tags").editorKind)
    }

    @Test
    fun anEnumGetsAPickerOfItsCases() {
        assertEquals(
            FlagEditorKind.Picker(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro"))),
            entry("checkout.tier").editorKind,
        )
    }

    // Text editing

    @Test
    fun valuesRenderAsEditableText() {
        assertEquals("3", FlagValueText.format(FlagValueBox.Int(3)))
        assertEquals("hi", FlagValueText.format(FlagValueBox.String("hi")))
        assertEquals("https://a.example", FlagValueText.format(FlagValueBox.Url(URI("https://a.example"))))
        assertEquals("[\"a\",\"b\"]", FlagValueText.format(FlagValueBox.Array(listOf(FlagValueBox.String("a"), FlagValueBox.String("b")))))
    }

    @Test
    fun editedTextIsParsedBackAccordingToTheDeclaredType() {
        assertEquals(FlagValueBox.Int(3), FlagValueText.parse("3", FlagValueType.Int))
        assertEquals(FlagValueBox.Double(3.5), FlagValueText.parse("3.5", FlagValueType.Double))
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.String("a"))),
            FlagValueText.parse("[\"a\"]", FlagValueType.Array(FlagValueType.String)),
        )
    }

    @Test
    fun unparseableTextIsRejectedRatherThanGuessed() {
        assertNull(FlagValueText.parse("lots", FlagValueType.Int))
        assertNull(FlagValueText.parse("not json", FlagValueType.Array(FlagValueType.String)))
        assertNull(FlagValueText.parse("", FlagValueType.Int))
    }

    // Transport

    @Test
    fun exportCarriesOnlyOverrides() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))

        val exported = FlagPayload.decode(store.exportJson().encodeToByteArray(), CompanionSchemas.companion)

        assertEquals(mapOf(FlagKey("max-items") to FlagValueBox.Int(3)), exported.values)
    }

    @Test
    fun exportIsTheDocumentAnExportWrites() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))

        val json = store.exportJson()

        assertTrue(json.startsWith("{\n  \"exportedAt\" : \""), json)
        assertTrue(json.endsWith("\"formatVersion\" : 1,\n  \"values\" : {\n    \"max-items\" : 3\n  }\n}"), json)
    }

    @Test
    fun exportingAnInfinityOrANaNFails() {
        source.setBox(FlagKey("checkout.ratio"), FlagValueBox.Double(Double.NaN))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { store.exportJson() }

        assertEquals(FlagKey("checkout.ratio"), error.key)
        assertNull(store.copyableQrCode)
    }

    @Test
    fun importAppliesOverrides() = runBlocking<Unit> {
        val other = FlagEditingStore(CompanionSchemas.companion, SnapshotSource("other"), Dispatchers.Unconfined)
        other.setValue(other.entry(FlagKey("max-items"))!!, FlagValueBox.Int(3))

        store.import(other.exportJson())

        assertEquals(FlagValueBox.Int(3), store.value(entry("max-items")))
    }

    @Test
    fun qrCodeRoundTripsThroughTheStore() = runBlocking<Unit> {
        store.setValue(entry("new-onboarding"), FlagValueBox.Bool(true))
        val other = FlagEditingStore(CompanionSchemas.companion, SnapshotSource("other"), Dispatchers.Unconfined)

        other.import(store.qrCode())

        assertEquals(listOf(FlagKey("new-onboarding")), other.overriddenKeys)
    }

    // The host's description

    @Test
    fun theStoreSurfacesTheHostsDescription() {
        val described = FlagEditingStore(CompanionSchemas.described, SnapshotSource("s"))

        assertEquals("What the checkout team can turn on", described.schema.description)
    }

    @Test
    fun aHostWithoutOneSurfacesNothing() {
        assertNull(FlagEditingStore(CompanionSchemas.plain, SnapshotSource("s")).schema.description)
    }

    private fun entry(key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun List<FlagSchema.Entry>.keys(): List<String> = map { it.key.rawValue }
}
