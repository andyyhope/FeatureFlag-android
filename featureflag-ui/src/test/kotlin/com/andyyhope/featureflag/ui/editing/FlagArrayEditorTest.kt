package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports the logic of Swift's FlagArrayEditorTests: an array edited a row at a time, each edit
// writing the whole array back.
class FlagArrayEditorTest {

    private val store = FlagEditingStore(CompanionSchemas.arrayEditor, SnapshotSource("shared"), Dispatchers.Unconfined)

    // Which flags get the list editor

    @Test
    fun anArrayOfScalarsGetsAListEditor() {
        assertEquals(FlagEditorKind.List(FlagValueType.String), entry("markets").editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.Int), entry("counts").editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.Date), entry("launches").editorKind)
    }

    @Test
    fun structuralValuesStayAsJson() {
        assertEquals(FlagEditorKind.Json, entry("nested").editorKind)
        assertEquals(FlagEditorKind.Json, entry("shares").editorKind)
    }

    @Test
    fun scalarsAreUnaffected() {
        assertEquals(FlagEditorKind.Text, entry("name").editorKind)
    }

    // Adding, changing, removing

    @Test
    fun addingAppendsAnEmptyElementOfTheRightType() = runBlocking<Unit> {
        val markets = entry("markets")
        store.setValue(markets, FlagValueBox.Array(listOf(FlagValueBox.String("AU"))))

        store.setValue(markets, FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueText.empty(FlagValueType.String))))

        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String(""))), store.value(markets))
    }

    @Test
    fun everyTypeHasSomethingToAdd() {
        assertEquals(FlagValueBox.Bool(false), FlagValueText.empty(FlagValueType.Bool))
        assertEquals(FlagValueBox.Int(0), FlagValueText.empty(FlagValueType.Int))
        assertEquals(FlagValueBox.Double(0.0), FlagValueText.empty(FlagValueType.Double))
        assertEquals(FlagValueBox.String(""), FlagValueText.empty(FlagValueType.String))
        assertEquals(FlagValueBox.Data(ByteArray(0)), FlagValueText.empty(FlagValueType.Data))
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.Int(0))), FlagValueText.empty(FlagValueType.Array(FlagValueType.Int)))
        assertEquals(FlagValueBox.Dictionary(emptyMap()), FlagValueText.empty(FlagValueType.Dictionary(FlagValueType.Int)))
        // A URL has no empty form that is still a URL, so it gets a placeholder.
        assertEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueText.empty(FlagValueType.Url))
    }

    @Test
    fun editingAnElementWritesTheWholeArrayBack() = runBlocking<Unit> {
        val counts = entry("counts")
        store.setValue(counts, ints(1, 2, 3))
        val values = (store.value(counts) as FlagValueBox.Array).value.toMutableList()

        values[1] = FlagValueBox.Int(20)
        store.setValue(counts, FlagValueBox.Array(values))

        assertEquals(ints(1, 20, 3), store.value(counts))
    }

    @Test
    fun anEmptyArrayIsStillAnOverride() = runBlocking<Unit> {
        // Removing every element leaves an empty array, which is a value, not the same as no override.
        val markets = entry("markets")

        store.setValue(markets, FlagValueBox.Array(emptyList()))
        assertTrue(store.isOverridden(markets))
        assertEquals(FlagValueBox.Array(emptyList()), store.value(markets))

        store.reset(markets)
        assertFalse(store.isOverridden(markets))
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))), store.value(markets))
    }

    // Elements round-trip through their text

    @Test
    fun eachElementTypeSurvivesItsDisplayString() {
        val cases = listOf(
            FlagValueType.String to FlagValueBox.String("AU"),
            FlagValueType.Int to FlagValueBox.Int(-3),
            FlagValueType.Double to FlagValueBox.Double(2.5),
            FlagValueType.Float to FlagValueBox.Float(1.5f),
            FlagValueType.Url to FlagValueBox.Url(URI("https://a.example/x")),
            FlagValueType.Data to FlagValueBox.Data(byteArrayOf(0x01, 0x02)),
        )
        for ((type, box) in cases) {
            assertEquals(box, FlagValueText.parse(FlagValueText.format(box), type), "${type.typeName} should survive its text")
        }
    }

    @Test
    fun anUneditableElementIsRejectedRatherThanStored() {
        assertNull(FlagValueText.parse("not a number", FlagValueType.Int))
        assertNull(FlagValueText.parse("", FlagValueType.Url))
    }

    private fun entry(key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun ints(vararg values: Long) = FlagValueBox.Array(values.map { FlagValueBox.Int(it) })
}
