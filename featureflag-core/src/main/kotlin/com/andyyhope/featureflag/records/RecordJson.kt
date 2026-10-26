package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.storageIdentity
import com.andyyhope.featureflag.values.toBox
import com.andyyhope.featureflag.values.toJson

/** The stored text of a record list, read and written through a shape alone, as `FlagRecord.swift` does. */
internal object RecordJson {

    /**
     * Compact JSON with sorted keys, one object per record. A nested record list is already a
     * string box, so it is written as a string inside its parent. Throws
     * [FlagSerializationException.NonFiniteNumber] for an infinity or a NaN.
     */
    fun encode(records: List<Map<String, FlagValueBox>>): String {
        if (records.any { record -> record.values.any { it.containsNonFiniteNumber } }) {
            throw FlagSerializationException.NonFiniteNumber(null)
        }
        val objects = records.map { record -> JsonValue.Object(record.mapValues { it.value.toJson() }) }
        return JsonWriter.compact(JsonValue.Array(objects))
    }

    /**
     * The records in [text], or `null` when it is not a JSON array of objects, when any record
     * cannot be read through [shape], or when two records share a key.
     *
     * Per field, by [FlagRecordField.name]: absent or `null` is left out when optional, filled
     * from the default when there is one, and otherwise fails the whole list; present but of the
     * wrong type always fails it. Nested lists are only checked to be strings here; their own
     * codec checks them when the record is built.
     */
    fun decode(text: String, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>? =
        parse(text, shape)?.takeIf { duplicateKey(it, shape) == null }

    /** The first key claimed by more than one record in [text], when that is what makes it unreadable. */
    fun duplicateKey(text: String, shape: List<FlagRecordField>): FlagValueBox? =
        parse(text, shape)?.let { duplicateKey(it, shape) }

    /** The first key claimed by more than one record, compared as stored rather than as held. */
    fun duplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? {
        val key = shape.firstOrNull { it.isKey }?.name ?: return null
        val seen = HashSet<String>()
        return records.mapNotNull { it[key] }.firstOrNull { !seen.add(it.storageIdentity) }
    }

    private fun parse(text: String, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>? {
        val parsed = try {
            JsonReader.parse(text.encodeToByteArray())
        } catch (ignored: JsonSyntaxException) {
            null
        }
        val objects = (parsed as? JsonValue.Array)?.items ?: return null
        return objects.map { item -> record(item as? JsonValue.Object ?: return null, shape) ?: return null }
    }

    private fun record(json: JsonValue.Object, shape: List<FlagRecordField>): Map<String, FlagValueBox>? {
        val boxes = LinkedHashMap<String, FlagValueBox>()
        for (field in shape) {
            val raw = json.fields[field.name]
            val box = when {
                raw != null && raw != JsonValue.Null -> raw.toBox(field.type) ?: return null
                field.isOptional -> null
                else -> field.defaultValue ?: return null
            }
            if (box != null) boxes[field.name] = box
        }
        return boxes
    }
}
