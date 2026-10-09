package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType

/**
 * One field of a record, described well enough for an editor that has never seen the type it
 * came from.
 *
 * @property cases every value an enum field can take; `null`, not empty, when there are none.
 * @property defaultValue what a stored record missing this field is filled with. A required
 *   field with no default makes such a record unreadable.
 * @property fields the fields of each nested record, when this field is itself a record list.
 * @property isKey whether this field tells one record from another. At most one per shape.
 * @property isOptional whether the field may be absent; an absent or `null` value decodes to
 *   `null`, and a `null` value is left out of the stored form.
 * @property decodedName the key to read this field from in a remote payload, when it is not
 *   [name]. Decoding only: the stored form is always keyed by [name].
 */
public data class FlagRecordField(
    public val name: String,
    public val type: FlagValueType,
    public val cases: List<FlagValueBox>? = null,
    public val defaultValue: FlagValueBox? = null,
    public val fields: List<FlagRecordField>? = null,
    public val isKey: Boolean = false,
    public val isOptional: Boolean = false,
    public val decodedName: String? = null,
) {

    /** The key to read this field from in a payload: [decodedName] if set, else [name]. */
    public val payloadName: String get() = decodedName ?: name

    public companion object {

        /**
         * Describes a field from its codec: type, cases and nested shape come from [codec], and
         * [default] is boxed with it. An optional field never carries a default.
         */
        public fun <T : Any> of(
            name: String,
            codec: FlagCodec<T>,
            default: T? = null,
            isKey: Boolean = false,
            isOptional: Boolean = false,
            decodedName: String? = null,
        ): FlagRecordField = FlagRecordField(
            name = name,
            type = codec.type,
            cases = codec.cases,
            defaultValue = if (isOptional) null else default?.let(codec::encode),
            fields = codec.recordShape,
            isKey = isKey,
            isOptional = isOptional,
            decodedName = decodedName,
        )
    }
}
