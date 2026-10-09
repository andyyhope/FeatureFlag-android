package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding

/** Supplies flag values to a container tree. A `FlagPole` is the production implementation. */
public interface FlagLookup {
    /** How property-name paths become the keys this lookup stores values under. */
    public val keyEncoding: KeyEncoding

    /** The value for [key], or null if nothing supplies one. [type] is the flag's declared type. */
    public fun box(key: FlagKey, type: FlagValueType): FlagValueBox?
}
