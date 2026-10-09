package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.RecordJson
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.storageIdentity

/**
 * A payload turned into flag values, before anything is stored. The audit describes this
 * and the source stores it, so the two can never disagree.
 */
internal class RemoteMapping(
    val boxes: Map<FlagKey, FlagValueBox>,
    val problems: List<RemoteOverrideProblem>,
)

/** Runs the mapper and checks every value it produced. Every problem is collected, not just the first. */
internal fun FlagSchema.mapRemote(payload: RemoteValue, mapper: RemoteOverrideMapper): RemoteMapping {
    val mapped = mapper.map(payload, this)

    // First wins on a repeated key: a schema handed in directly may not be well formed.
    val entries = LinkedHashMap<FlagKey, FlagSchema.Entry>()
    for (entry in flags) {
        if (entry.key !in entries) entries[entry.key] = entry
    }

    val boxes = LinkedHashMap<FlagKey, FlagValueBox>()
    val problems = ArrayList<RemoteOverrideProblem>()
    for ((key, value) in mapped) {
        when (val checked = judge(key, value, entries[key])) {
            is Checked.Accepted -> boxes[key] = checked.box
            is Checked.Refused -> problems += checked.problem
        }
    }
    return RemoteMapping(boxes, problems)
}

private sealed interface Checked {
    class Accepted(val box: FlagValueBox) : Checked

    class Refused(val problem: RemoteOverrideProblem) : Checked
}

private fun judge(key: FlagKey, value: RemoteValue, entry: FlagSchema.Entry?): Checked {
    if (entry == null) {
        return Checked.Refused(RemoteOverrideProblem(key, key.rawValue, Kind.UnknownKey, found = value.shortDescription))
    }
    val remoteKey = entry.remoteKey ?: key.rawValue
    // A record flag stores text, so its published shape decides, never its type. Swift's
    // `recordShape.map { … } ?? box(as:)` keeps the shape's nil, so there is no fallback to `box`.
    val shape = entry.recordShape
    val box = (if (shape != null) value.recordBox(shape) else value.box(entry.valueType))
        ?: return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.TypeMismatch, entry.expectedDescription(value), entry.foundDescription(value)),
        )
    val cases = entry.cases
    if (cases != null && box !in cases) {
        return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.UnknownCase, MessageText.caseList(cases), value.shortDescription),
        )
    }
    return Checked.Accepted(box)
}

/**
 * This value as a record flag's stored text, or `null` when it is not a list this build
 * can read.
 *
 * Every field must be present (or defaulted, or optional) and hold its declared type, an
 * enum field must name a known case, and keys must be distinct. Fields are read by their
 * custom key and stored by property name, as canonical JSON text.
 */
internal fun RemoteValue.recordBox(shape: List<FlagRecordField>): FlagValueBox? {
    if (this is RemoteValue.String) {
        // Mirrors iOS 0.12.0, to be fixed upstream. A list already serialised as text is
        // checked only as stored text: read by property name, no case check, nested lists
        // not recursed. It is then stored verbatim. Pinned by
        // FlagRecordRemoteTest.aRecordListSentAsAStringSkipsCaseChecksMirroringIos0120.
        return if (RecordJson.decode(value, shape) != null) FlagValueBox.String(value) else null
    }
    if (this !is RemoteValue.Array) return null
    val records = ArrayList<Map<String, FlagValueBox>>(value.size)
    for (item in value) records += (item as? RemoteValue.Object)?.recordFields(shape) ?: return null
    if (firstDuplicateKey(records, shape) != null) return null
    return FlagValueBox.String(RecordJson.encode(records))
}

private fun RemoteValue.Object.recordFields(shape: List<FlagRecordField>): Map<String, FlagValueBox>? {
    val boxes = LinkedHashMap<String, FlagValueBox>(shape.size)
    for (field in shape) {
        val raw = value[field.payloadName]?.takeUnless { it == RemoteValue.Null }
        if (raw == null) {
            // Absent or null. An optional field is left out of the stored form. A required
            // one takes its declared default, or the record cannot be read.
            if (!field.isOptional) boxes[field.name] = field.defaultValue ?: return null
        } else {
            // A nested list recurses rather than being read as one string. Again there is
            // no fallback to `box`, so it is not stored as text.
            val nested = field.fields
            val box = (if (nested != null) raw.recordBox(nested) else raw.box(field.type)) ?: return null
            val cases = field.cases
            if (cases != null && box !in cases) return null
            boxes[field.name] = box
        }
    }
    return boxes
}

/** The first key value two records share, compared as stored. `null` when all are distinct or the shape names no key. */
private fun firstDuplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? {
    val keyName = shape.firstOrNull { it.isKey }?.name ?: return null
    val seen = HashSet<String>()
    for (record in records) {
        val value = record[keyName] ?: continue
        if (!seen.add(value.storageIdentity)) return value
    }
    return null
}

/** The duplicate key in a payload, when that is what made it unusable. Used only to say so. */
internal fun RemoteValue.duplicateRecordKey(shape: List<FlagRecordField>): FlagValueBox? {
    if (this is RemoteValue.String) {
        // Parsed as stored but with the uniqueness rule switched off, so the duplicate can be named.
        val records = RecordJson.decode(value, shape.map { it.copy(isKey = false) }) ?: return null
        return firstDuplicateKey(records, shape)
    }
    if (this !is RemoteValue.Array) return null
    val records = ArrayList<Map<String, FlagValueBox>>(value.size)
    for (item in value) {
        val fields = (item as? RemoteValue.Object)?.value ?: return null
        records += shape.mapNotNull { field -> fields[field.payloadName]?.box(field.type)?.let { field.name to it } }.toMap()
    }
    return firstDuplicateKey(records, shape)
}

/** What a record flag wanted. A list that parses but repeats a key failed the uniqueness rule, not the shape. */
private fun FlagSchema.Entry.expectedDescription(value: RemoteValue): String {
    val shape = recordShape ?: return valueType.typeName
    val keyName = shape.firstOrNull { it.isKey }?.name
    if (keyName != null && value.duplicateRecordKey(shape) != null) return "every record to have its own $keyName"
    return "a list of records (${shape.joinToString(", ") { it.name }})"
}

private fun FlagSchema.Entry.foundDescription(value: RemoteValue): String {
    val duplicate = recordShape?.let { value.duplicateRecordKey(it) } ?: return value.shortDescription
    return "two with ${MessageText.short(duplicate)}"
}
