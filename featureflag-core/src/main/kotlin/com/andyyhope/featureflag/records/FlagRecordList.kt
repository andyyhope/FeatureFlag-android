package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.storageIdentity
import java.time.Instant

/**
 * A record-list flag's value, which is a [FlagValueBox.String] holding a JSON array, as rows
 * of field boxes and back, through nothing but its shape.
 *
 * This is what a companion edits records with: it has the published shape and never the
 * host's record type. It reads and writes exactly as the host's `FlagCodecs.records` does.
 */
public object FlagRecordList {

    /**
     * The rows in [box], or null when it is not a string, is not a list of records [shape] can
     * read, or has two records sharing a key. The host reads such a value as its default.
     */
    public fun decode(box: FlagValueBox, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>? =
        (box as? FlagValueBox.String)?.let { RecordJson.decode(it.value, shape) }

    /**
     * Compact JSON with sorted keys, one object per record, as the host writes it.
     *
     * @throws FlagSerializationException.NonFiniteNumber when a field holds an infinity or a NaN.
     */
    public fun encode(records: List<Map<String, FlagValueBox>>): FlagValueBox.String =
        FlagValueBox.String(RecordJson.encode(records))

    /** The first key value two rows share, when that is the only thing wrong with [box]; null otherwise. */
    public fun duplicateKey(box: FlagValueBox, shape: List<FlagRecordField>): FlagValueBox? =
        (box as? FlagValueBox.String)?.let { RecordJson.duplicateKey(it.value, shape) }

    /** The first key value two of [records] share, compared as stored, at the top level only. */
    public fun duplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? =
        RecordJson.duplicateKey(records, shape)

    /**
     * A record to append to [alongside]: every field at its default, else its first case, else
     * [FlagValueText.empty] of its type.
     *
     * When the shape has a key and that value is already taken (compared as stored, so two
     * dates in the same second collide), suffixes 2 up to the number taken plus 2 are tried in
     * turn and the first free one wins: a string becomes `"2"` or `"<value> 2"`, an int its value
     * plus one, a date one second later, a URL `<url>-2`. A key type that takes no suffix, or a
     * list with no free suffix, keeps the colliding value, and the editor shows the collision.
     */
    public fun emptyRecord(
        shape: List<FlagRecordField>,
        alongside: List<Map<String, FlagValueBox>> = emptyList(),
        now: Instant = Instant.now(),
    ): Map<String, FlagValueBox> {
        val record = LinkedHashMap<String, FlagValueBox>()
        shape.forEach { field -> record[field.name] = field.emptyBox(now) }
        val key = shape.firstOrNull { it.isKey } ?: return record
        val taken = alongside.mapNotNull { it[key.name]?.storageIdentity }.toSet()
        val start = key.emptyBox(now)
        if (start.storageIdentity !in taken) return record
        val free = (2..taken.size + 2).asSequence()
            .map { start.withSuffix(it) }
            .takeWhile { it != null }
            .filterNotNull()
            .firstOrNull { it.storageIdentity !in taken }
        if (free != null) record[key.name] = free
        return record
    }

    private fun FlagRecordField.emptyBox(now: Instant): FlagValueBox =
        defaultValue ?: cases?.firstOrNull() ?: FlagValueText.empty(type, now)

    private fun FlagValueBox.withSuffix(suffix: Int): FlagValueBox? = when (this) {
        is FlagValueBox.String -> FlagValueBox.String(if (value.isEmpty()) "$suffix" else "$value $suffix")
        is FlagValueBox.Int -> FlagValueBox.Int(value + suffix - 1)
        is FlagValueBox.Date -> FlagValueBox.Date(value.plusSeconds(suffix - 1L))
        is FlagValueBox.Url -> FlagValueText.parse("$value-$suffix", FlagValueType.Url)
        else -> null
    }
}
