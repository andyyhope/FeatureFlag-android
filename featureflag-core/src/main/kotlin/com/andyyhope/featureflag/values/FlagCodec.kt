package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.records.FlagRecordField

/**
 * Converts a Kotlin value to and from its [FlagValueBox].
 *
 * Decoding is strict: a box holding the wrong case yields `null` rather than coercing.
 */
public interface FlagCodec<T> {

    /** The type this value reports to schemas and to remote payload validation. */
    public val type: FlagValueType

    /** Every value an enum can take, for editors to offer a picker. `null` when there are none. */
    public val cases: List<FlagValueBox>? get() = null

    /** The fields of each record, when this codec reads a list of records. */
    public val recordShape: List<FlagRecordField>? get() = null

    public fun encode(value: T): FlagValueBox

    public fun decode(box: FlagValueBox): T?
}
