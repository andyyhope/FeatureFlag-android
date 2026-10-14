package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.transport.FlagQRCode
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The companion's model of one host: plain Kotlin over a schema and a source.
 *
 * Reads are synchronous and in memory, and each one asks the source afresh. Writes suspend on
 * [writeDispatcher], because a bridge source's writes are blocking calls into the host, and throw
 * whatever the source throws (for a bridge, `FlagBridgeException`). A write that fails changes
 * nothing, and still bumps [revision] so a screen that showed the edit shows the real value again.
 *
 * A store is bound to its [schema]. When the host publishes a different one, the companion's
 * session builds a new store over a new source.
 */
public class FlagEditingStore(
    public val schema: FlagSchema,
    private val source: MutableFlagValueSource,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val revisionState = MutableStateFlow(0L)

    /** Bumps on every source change and after every write, failed or not; screens read it to recompose. Starts at 0. */
    public val revision: StateFlow<Long> = revisionState.asStateFlow()

    /**
     * Collects the source's changes into [revision] until [scope] ends or the job is cancelled.
     * Subscribed before it returns, so a change made straight afterwards is not missed. The
     * session calls it once.
     */
    public fun observeIn(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        source.changes.collect { bump() }
    }

    // Structure

    /** The flag tree, for walking groups one screen at a time. */
    public val tree: FlagTreeNode = FlagTreeNode.of(schema)

    /**
     * The flags as sections, filtered by [query]: trimmed, then a case-insensitive substring of
     * a flag's key or description. The ungrouped flags come first in a section with no title,
     * then one section per group, in schema order, holding the group's own matching flags. A
     * section with nothing in it is left out.
     */
    public fun sections(query: String = ""): List<FlagSection> {
        val needle = query.trim().lowercase()
        val matches = { entry: FlagSchema.Entry ->
            needle.isEmpty() ||
                entry.key.rawValue.lowercase().contains(needle) ||
                entry.description.lowercase().contains(needle)
        }
        val sections = mutableListOf<FlagSection>()
        val ungrouped = schema.flags.filter { it.propertyPath.size == 1 && matches(it) }
        if (ungrouped.isNotEmpty()) sections += FlagSection(emptyList(), null, "", ungrouped)
        for (group in schema.groups) {
            val entries = schema.flags.filter { it.propertyPath.dropLast(1) == group.propertyPath && matches(it) }
            if (entries.isEmpty()) continue
            sections += FlagSection(group.propertyPath, group.description, pathDescription(group.propertyPath), entries)
        }
        return sections
    }

    /** The first flag with [key], or null. */
    public fun entry(key: FlagKey): FlagSchema.Entry? = schema.flags.firstOrNull { it.key == key }

    // Values

    /** The value in effect: the stored override when the host would use it, otherwise the default. */
    public fun value(entry: FlagSchema.Entry): FlagValueBox = usableOverride(entry) ?: entry.defaultValue

    /** Whether the flag holds an override the host would use. */
    public fun isOverridden(entry: FlagSchema.Entry): Boolean = usableOverride(entry) != null

    /** Every overridden flag's key, in schema order. */
    public val overriddenKeys: List<FlagKey>
        get() = schema.flags.filter(::isOverridden).map { it.key }

    /** How many flags at or below [node] are overridden. */
    public fun overriddenCount(node: FlagTreeNode): Int = node.allFlags.count(::isOverridden)

    // Writes

    /** Stores [box] for [entry]. The caller has already made it the flag's type. */
    public suspend fun setValue(entry: FlagSchema.Entry, box: FlagValueBox) {
        write { source.setBox(entry.key, box) }
    }

    /** Clears [entry]'s override, so it falls back to the host's default. */
    public suspend fun reset(entry: FlagSchema.Entry) {
        write { source.setBox(entry.key, null) }
    }

    /**
     * One write clearing every schema key that holds anything, whether the host would use it or
     * not: a value of the wrong type or an old case is still in the host's store. Keys outside
     * the schema are left alone, and so are keys that hold nothing. With nothing to clear, nothing
     * is written.
     */
    public suspend fun resetAll() {
        val stored = schema.flags.filter { source.box(it.key, it.valueType) != null }
        if (stored.isEmpty()) return
        write { source.setBoxes(stored.associate { it.key to null }) }
    }

    // Transport

    /**
     * The usable overrides as an export document: pretty JSON with sorted keys, as iOS writes it.
     *
     * @throws FlagSerializationException.NonFiniteNumber when an override holds an infinity or a NaN.
     */
    public fun exportJson(): String = FlagPayload(overriddenValues()).jsonBytes().decodeToString()

    /**
     * The usable overrides as a flag code, `FFQR1:` and then plain text with no spaces.
     *
     * @throws FlagQRCodeException.PayloadTooLarge when they will not fit in one QR code.
     * @throws FlagSerializationException.NonFiniteNumber when an override holds an infinity or a NaN.
     */
    public fun qrCode(): String = FlagQRCode.encode(FlagPayload(overriddenValues()))

    /** [qrCode], or null when nothing is overridden or there is no code to make: a view wants a button or none. */
    public val copyableQrCode: String?
        get() {
            if (overriddenKeys.isEmpty()) return null
            return try {
                qrCode()
            } catch (ignored: FlagQRCodeException.PayloadTooLarge) {
                null
            } catch (ignored: FlagSerializationException.NonFiniteNumber) {
                null
            }
        }

    /**
     * Applies pasted text: a flag code when, once trimmed, it starts with `FFQR1:`, otherwise an
     * export document. All or nothing: it is checked against [schema] first, then written in one
     * `setBoxes`, which a bridge sends as one host commit.
     *
     * @throws FlagImportException when the document is unreadable or any value is refused.
     * @throws FlagQRCodeException when a code is not one or cannot be decompressed.
     */
    public suspend fun import(text: String) {
        write {
            val trimmed = text.trim()
            val payload = if (trimmed.startsWith(FlagQRCode.PREFIX)) {
                FlagQRCode.decode(trimmed, schema)
            } else {
                FlagPayload.decode(trimmed.encodeToByteArray(), schema)
            }
            source.setBoxes(payload.values)
        }
    }

    // Records

    /** The rows of a record-list flag, or null when it has no shape or its value is not a list the host can read. */
    public fun records(entry: FlagSchema.Entry): List<Map<String, FlagValueBox>>? =
        entry.recordShape?.let { FlagRecordList.decode(value(entry), it) }

    /**
     * Writes [records] back as the text the host reads. Refuses, writing nothing, a list in which
     * two records share a key at any depth, because the host would refuse it and read its default.
     *
     * @throws FlagRecordEditingException for a repeated key.
     * @throws FlagSerializationException.NonFiniteNumber when a field holds an infinity or a NaN.
     */
    public suspend fun setRecords(entry: FlagSchema.Entry, records: List<Map<String, FlagValueBox>>) {
        entry.recordShape?.let { checkKeys(records, it) }
        setValue(entry, FlagRecordList.encode(records))
    }

    // Helpers

    /**
     * The stored value, but only when the host would use it: of the declared type and, for an
     * enum, one of its cases. A stale or hand-edited value reads as no override at all.
     */
    private fun usableOverride(entry: FlagSchema.Entry): FlagValueBox? {
        val box = source.box(entry.key, entry.valueType) ?: return null
        if (!box.matches(entry.valueType)) return null
        val cases = entry.cases
        if (!cases.isNullOrEmpty() && box !in cases) return null
        return box
    }

    private fun overriddenValues(): Map<FlagKey, FlagValueBox> {
        val values = LinkedHashMap<FlagKey, FlagValueBox>()
        for (entry in schema.flags) {
            if (entry.key in values) continue
            usableOverride(entry)?.let { values[entry.key] = it }
        }
        return values
    }

    /** Every ancestor group's description and the group's own, missing ones skipped. */
    private fun pathDescription(path: List<String>): String = (1..maxOf(path.size, 1))
        .mapNotNull { depth -> schema.groups.firstOrNull { it.propertyPath == path.take(depth) }?.description }
        .joinToString(" › ")

    private suspend fun write(block: () -> Unit) {
        try {
            withContext(writeDispatcher) { block() }
        } finally {
            bump()
        }
    }

    private fun bump() {
        revisionState.update { it + 1 }
    }

    private companion object {

        /**
         * Refuses a repeated key at any depth. A nested list is read through the same rule as the
         * outer one, so a repeated key inside a record makes the whole flag unreadable.
         */
        fun checkKeys(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>) {
            FlagRecordList.duplicateKey(records, shape)?.let { throw refusal(shape, it) }
            for (field in shape) {
                val nested = field.fields ?: continue
                for (box in records.mapNotNull { it[field.name] }) {
                    // Asked for the duplicate rather than the rows: reading them applies this very
                    // rule and hands back nothing, so the check would look straight past it.
                    FlagRecordList.duplicateKey(box, nested)?.let { throw refusal(nested, it) }
                    FlagRecordList.decode(box, nested)?.let { checkKeys(it, nested) }
                }
            }
        }

        fun refusal(shape: List<FlagRecordField>, value: FlagValueBox): FlagRecordEditingException =
            FlagRecordEditingException(shape.firstOrNull { it.isKey }?.name ?: "key", value)
    }
}
