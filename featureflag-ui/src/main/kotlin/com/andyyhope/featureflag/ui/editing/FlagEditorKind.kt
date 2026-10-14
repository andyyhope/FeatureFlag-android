package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType

/** Which control the editor shows for a flag. */
public sealed interface FlagEditorKind {
    public data object Toggle : FlagEditorKind
    public data object Integer : FlagEditorKind
    public data object Decimal : FlagEditorKind
    public data object Text : FlagEditorKind

    /** A choice between an enum's cases. */
    public data class Picker(public val cases: kotlin.collections.List<FlagValueBox>) : FlagEditorKind
    public data object Date : FlagEditorKind
    public data object Url : FlagEditorKind

    /** Base64, because there is nothing better to show for arbitrary bytes. */
    public data object Data : FlagEditorKind

    /** An array of scalars, edited a row at a time; [element] picks each row's control. */
    public data class List(public val element: FlagValueType) : FlagEditorKind

    /** A list of fixed-shape records, edited a record at a time through [fields]. */
    public data class Records(public val fields: kotlin.collections.List<FlagRecordField>) : FlagEditorKind

    /** Everything else structural (dictionaries, and arrays of collections), edited as JSON text. */
    public data object Json : FlagEditorKind

    /** True for [Data] and [Json]: a multi-line block with a copy button, not a single line. */
    public val wantsWrappingEditor: Boolean
        get() = this == Data || this == Json
}

/**
 * The control this flag is edited with, decided in this order: non-empty cases give a
 * [FlagEditorKind.Picker]; a non-empty record shape gives [FlagEditorKind.Records]; then the value
 * type, where an array of arrays or dictionaries and any dictionary are [FlagEditorKind.Json], and
 * any other array is a [FlagEditorKind.List].
 */
public val FlagSchema.Entry.editorKind: FlagEditorKind
    get() {
        cases?.takeIf { it.isNotEmpty() }?.let { return FlagEditorKind.Picker(it) }
        recordShape?.takeIf { it.isNotEmpty() }?.let { return FlagEditorKind.Records(it) }
        return when (val type = valueType) {
            FlagValueType.Bool -> FlagEditorKind.Toggle
            FlagValueType.Int -> FlagEditorKind.Integer
            FlagValueType.Double, FlagValueType.Float -> FlagEditorKind.Decimal
            FlagValueType.String -> FlagEditorKind.Text
            FlagValueType.Date -> FlagEditorKind.Date
            FlagValueType.Url -> FlagEditorKind.Url
            FlagValueType.Data -> FlagEditorKind.Data
            is FlagValueType.Array -> when (type.element) {
                is FlagValueType.Array, is FlagValueType.Dictionary -> FlagEditorKind.Json
                else -> FlagEditorKind.List(type.element)
            }
            is FlagValueType.Dictionary -> FlagEditorKind.Json
        }
    }
