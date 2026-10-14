package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ports the editor-selection half of Swift's FlagEditorValueTests.
class FlagEditorKindTest {

    // Editor selection

    @Test
    fun dataGetsItsOwnEditor() {
        assertEquals(FlagEditorKind.Data, entry(FlagValueType.Data).editorKind)
    }

    @Test
    fun floatAndDoubleShareTheDecimalEditor() {
        assertEquals(FlagEditorKind.Decimal, entry(FlagValueType.Float).editorKind)
        assertEquals(FlagEditorKind.Decimal, entry(FlagValueType.Double).editorKind)
    }

    @Test
    fun anArrayOfScalarsGetsARowPerElement() {
        assertEquals(FlagEditorKind.List(FlagValueType.Int), entry(FlagValueType.Array(FlagValueType.Int)).editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.Date), entry(FlagValueType.Array(FlagValueType.Date)).editorKind)
    }

    @Test
    fun structuralCollectionsKeepTheJsonEditor() {
        assertEquals(FlagEditorKind.Json, entry(FlagValueType.Dictionary(FlagValueType.String)).editorKind)
        assertEquals(FlagEditorKind.Json, entry(FlagValueType.Array(FlagValueType.Array(FlagValueType.String))).editorKind)
        assertEquals(FlagEditorKind.Json, entry(FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Int))).editorKind)
    }

    @Test
    fun casesWinOverTheUnderlyingType() {
        // An Int-backed enum still gets a picker, not a number field.
        val cases = listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))

        assertEquals(FlagEditorKind.Picker(cases), entry(FlagValueType.Int, cases = cases).editorKind)
    }

    @Test
    fun anEmptyCaseListFallsBackToTheTypesEditor() {
        assertEquals(FlagEditorKind.Text, entry(FlagValueType.String, cases = emptyList()).editorKind)
    }

    @Test
    fun everyScalarTypeGetsItsControl() {
        assertEquals(FlagEditorKind.Toggle, entry(FlagValueType.Bool).editorKind)
        assertEquals(FlagEditorKind.Integer, entry(FlagValueType.Int).editorKind)
        assertEquals(FlagEditorKind.Text, entry(FlagValueType.String).editorKind)
        assertEquals(FlagEditorKind.Date, entry(FlagValueType.Date).editorKind)
        assertEquals(FlagEditorKind.Url, entry(FlagValueType.Url).editorKind)
    }

    @Test
    fun aRecordShapeGetsTheRecordEditor() {
        val shape = listOf(FlagRecordField("name", FlagValueType.String, isKey = true))

        assertEquals(FlagEditorKind.Records(shape), entry(FlagValueType.String, shape = shape).editorKind)
    }

    @Test
    fun casesWinOverARecordShape() {
        val cases = listOf(FlagValueBox.String("[]"))
        val shape = listOf(FlagRecordField("name", FlagValueType.String))

        assertEquals(FlagEditorKind.Picker(cases), entry(FlagValueType.String, cases = cases, shape = shape).editorKind)
    }

    @Test
    fun anEmptyRecordShapeFallsBackToTheTypesEditor() {
        assertEquals(FlagEditorKind.Text, entry(FlagValueType.String, shape = emptyList()).editorKind)
    }

    // The wrapping editor

    @Test
    fun dataIsEditedAsABlockRatherThanASingleLine() {
        val data = entry(FlagValueType.Data, FlagValueBox.Data(ByteArray(0)))

        assertEquals(FlagEditorKind.Data, data.editorKind)
        assertTrue(data.editorKind.wantsWrappingEditor)
    }

    @Test
    fun structuralCollectionsAlsoUseTheWrappingEditor() {
        for (type in listOf(FlagValueType.Dictionary(FlagValueType.Int), FlagValueType.Array(FlagValueType.Array(FlagValueType.String)))) {
            assertTrue(entry(type, FlagValueBox.Array(emptyList())).editorKind.wantsWrappingEditor, type.typeName)
        }
    }

    @Test
    fun aListDoesNotUseTheWrappingEditor() {
        assertFalse(entry(FlagValueType.Array(FlagValueType.String), FlagValueBox.Array(emptyList())).editorKind.wantsWrappingEditor)
    }

    @Test
    fun scalarsStayOnASingleLine() {
        val scalars = listOf(
            FlagValueType.String to FlagValueBox.String(""),
            FlagValueType.Int to FlagValueBox.Int(0),
            FlagValueType.Double to FlagValueBox.Double(0.0),
            FlagValueType.Float to FlagValueBox.Float(0f),
            FlagValueType.Url to FlagValueBox.Url(URI("https://a.example")),
            FlagValueType.Date to FlagValueBox.Date(Instant.EPOCH),
            FlagValueType.Bool to FlagValueBox.Bool(false),
        )
        for ((type, value) in scalars) {
            assertFalse(entry(type, value).editorKind.wantsWrappingEditor, "${type.typeName} should stay inline")
        }
    }

    @Test
    fun aPickerIsNeverAWrappingEditor() {
        val tier = entry(
            FlagValueType.String,
            FlagValueBox.String("free"),
            cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
        )

        assertFalse(tier.editorKind.wantsWrappingEditor)
    }

    @Test
    fun recordsAreNeverAWrappingEditor() {
        val shape = listOf(FlagRecordField("name", FlagValueType.String))

        assertFalse(entry(FlagValueType.String, shape = shape).editorKind.wantsWrappingEditor)
    }

    private fun entry(
        type: FlagValueType,
        default: FlagValueBox = FlagValueBox.String(""),
        cases: List<FlagValueBox>? = null,
        shape: List<FlagRecordField>? = null,
    ) = FlagSchema.Entry(
        key = FlagKey("a"),
        propertyPath = listOf("a"),
        description = "",
        valueType = type,
        defaultValue = default,
        cases = cases,
        recordShape = shape,
    )
}
