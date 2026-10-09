package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/** How one flag's compiled default compares to the value a config supplies for it. */
public data class FlagDefaultComparison(
    public val key: FlagKey,
    /** The value compiled into the app. */
    public val defaultValue: FlagValueBox,
    /** The value the config supplied, as a real apply would store it. */
    public val incomingValue: FlagValueBox,
    /** For a record flag, the per-record breakdown, which is empty when only the order changed. `null` for a scalar flag. */
    public val records: List<FlagRecordDiff>? = null,
) {

    /** Whether the config restates the compiled default rather than changing it. */
    public val matchesDefault: Boolean get() = defaultValue == incomingValue

    override fun toString(): String {
        if (matchesDefault) return "$key: ${MessageText.short(incomingValue)} — matches the default"
        val diffs = records ?: return "$key: ${MessageText.short(defaultValue)} → ${MessageText.short(incomingValue)}"
        // Different, yet nothing added, removed or edited: only the order is left.
        if (diffs.isEmpty()) return "$key: records reordered"
        val body = diffs.joinToString("\n") { diff -> diff.line.split("\n").joinToString("\n") { "  $it" } }
        return "$key:\n$body"
    }
}
