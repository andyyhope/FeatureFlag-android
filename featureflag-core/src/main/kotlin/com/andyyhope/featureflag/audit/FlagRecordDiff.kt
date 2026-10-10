package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.storageIdentity

/** What happened to one record between a flag's compiled default and a config's value. */
public data class FlagRecordDiff(
    /** The record's key value when the record has a key, otherwise its position: `[0]`, `[1]`, … */
    public val identifier: String,
    public val change: Change,
) {

    public sealed interface Change {
        /** In the config, not the default. */
        public data object Added : Change

        /** In the default, not the config. */
        public data object Removed : Change

        /** In both, with these fields differing. Never empty. */
        public data class Changed(public val fields: List<FlagFieldDiff>) : Change
    }

    /** One line when added or removed. A changed record gets its name, then one line per field. */
    internal val line: String
        get() = when (val kind = change) {
            Change.Added -> "+ $identifier"
            Change.Removed -> "- $identifier"
            is Change.Changed -> (listOf("~ $identifier:") + kind.fields.map(::fieldLine)).joinToString("\n")
        }

    internal companion object {

        /** Pairs records by key when the shape has one, otherwise by position. */
        fun diff(
            defaults: List<Map<String, FlagValueBox>>,
            incoming: List<Map<String, FlagValueBox>>,
            shape: List<FlagRecordField>,
        ): List<FlagRecordDiff> {
            val key = shape.firstOrNull { it.isKey }?.name
            return if (key != null) diffByKey(defaults, incoming, shape, key) else diffByIndex(defaults, incoming, shape)
        }
    }
}

/** One field of one record that differs. `null` is an optional field left unset on that side. */
public data class FlagFieldDiff(
    public val field: String,
    public val defaultValue: FlagValueBox?,
    public val incomingValue: FlagValueBox?,
)

// An unset optional field reads "unset" rather than a blank, so "minimumSpend: unset → 10.0" says what happened.
private fun fieldLine(diff: FlagFieldDiff): String =
    "    ${diff.field}: ${describe(diff.defaultValue)} → ${describe(diff.incomingValue)}"

private fun describe(box: FlagValueBox?): String = box?.let(MessageText::diffField) ?: "unset"

private fun diffByKey(
    defaults: List<Map<String, FlagValueBox>>,
    incoming: List<Map<String, FlagValueBox>>,
    shape: List<FlagRecordField>,
    key: String,
): List<FlagRecordDiff> {
    // The key value plain, not quoted: it names the record rather than being one of the values diffed.
    fun identity(record: Map<String, FlagValueBox>): String = when (val box = record[key]) {
        is FlagValueBox.String -> box.value
        null -> "?"
        else -> MessageText.diffField(box)
    }

    fun id(record: Map<String, FlagValueBox>): String = record[key]?.storageIdentity ?: ""

    val incomingById = LinkedHashMap<String, Map<String, FlagValueBox>>()
    for (record in incoming) incomingById.getOrPut(id(record)) { record }
    val defaultIds = defaults.mapTo(HashSet()) { id(it) }

    val diffs = ArrayList<FlagRecordDiff>()
    // Removed and changed, in the default's order.
    for (record in defaults) {
        val match = incomingById[id(record)]
        if (match == null) {
            diffs += FlagRecordDiff(identity(record), FlagRecordDiff.Change.Removed)
        } else {
            val fields = fieldDiffs(record, match, shape)
            if (fields.isNotEmpty()) diffs += FlagRecordDiff(identity(record), FlagRecordDiff.Change.Changed(fields))
        }
    }
    // Added, in the config's order.
    for (record in incoming) {
        if (id(record) !in defaultIds) diffs += FlagRecordDiff(identity(record), FlagRecordDiff.Change.Added)
    }
    return diffs
}

private fun diffByIndex(
    defaults: List<Map<String, FlagValueBox>>,
    incoming: List<Map<String, FlagValueBox>>,
    shape: List<FlagRecordField>,
): List<FlagRecordDiff> {
    val diffs = ArrayList<FlagRecordDiff>()
    for (index in 0 until maxOf(defaults.size, incoming.size)) {
        val id = "[$index]"
        val base = defaults.getOrNull(index)
        val other = incoming.getOrNull(index)
        if (base != null && other != null) {
            val fields = fieldDiffs(base, other, shape)
            if (fields.isNotEmpty()) diffs += FlagRecordDiff(id, FlagRecordDiff.Change.Changed(fields))
        } else if (base != null) {
            diffs += FlagRecordDiff(id, FlagRecordDiff.Change.Removed)
        } else {
            diffs += FlagRecordDiff(id, FlagRecordDiff.Change.Added)
        }
    }
    return diffs
}

// A missing key is an optional field left unset, and unset against a value is a change worth showing.
private fun fieldDiffs(
    base: Map<String, FlagValueBox>,
    incoming: Map<String, FlagValueBox>,
    shape: List<FlagRecordField>,
): List<FlagFieldDiff> = shape.mapNotNull { field ->
    val before = base[field.name]
    val after = incoming[field.name]
    if (before == after) null else FlagFieldDiff(field.name, before, after)
}
