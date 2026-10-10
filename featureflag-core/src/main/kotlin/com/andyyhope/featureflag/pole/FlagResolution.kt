package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/**
 * Where a flag's value came from.
 *
 * @property sourceName the source that supplied the value, or null when nothing did and
 *   the compiled default applies.
 * @property box the value in effect. A pole fills in the compiled default here, so it is
 *   null only for a key the pole does not declare.
 */
public data class FlagResolution(
    public val key: FlagKey,
    public val sourceName: String?,
    public val box: FlagValueBox?,
) {

    /** Whether the compiled default is in effect. */
    public val isDefault: Boolean
        get() = sourceName == null
}
