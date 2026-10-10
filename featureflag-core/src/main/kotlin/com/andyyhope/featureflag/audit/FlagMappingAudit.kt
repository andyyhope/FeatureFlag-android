package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.RecordJson
import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteMapping
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.remote.mapRemote
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * A check that a remote payload wires up the flags it should, run without an app.
 *
 * It reuses the validation `RemoteOverrideSource` applies, so a value it calls applied is
 * one a real apply would accept. What it adds is coverage in both directions: a flag
 * whose path found nothing ([absent]), and a value no flag reads ([unconsumed]).
 */
public class FlagMappingAudit(
    schema: FlagSchema,
    payload: RemoteValue,
    mapper: RemoteOverrideMapper = DotPathMapper,
) {

    /**
     * Audits a container against a JSON payload, decoding it the way the source would.
     *
     * @throws com.andyyhope.featureflag.errors.RemoteOverrideException.Malformed when the bytes are not JSON.
     */
    public constructor(
        spec: FlagContainerSpec<*>,
        json: ByteArray,
        mapper: RemoteOverrideMapper = DotPathMapper,
        keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    ) : this(FlagSchema.of(spec, keyEncoding), RemoteValue.parseJson(json), mapper)

    /** Flags whose `remoteKey` found a value that fits their type, sorted by key. */
    public val applied: List<FlagKey>

    /** Flags with a `remoteKey` the payload did not supply, sorted by key. Usually a typo in the key. */
    public val absent: List<FlagKey>

    /** Values that did not fit: the problems a real apply would reject, sorted by key. */
    public val mismatched: List<RemoteOverrideProblem>

    /**
     * Payload paths no flag reads, sorted. A path is claimed when a `remoteKey` equals it
     * or names an ancestor of it. This follows `DotPathMapper`, so under a custom mapper
     * it is advisory only.
     */
    public val unconsumed: List<String>

    /** Flags that declare no `remoteKey`, sorted by key. */
    public val notRemotelyOverridable: List<FlagKey>

    /** One comparison per applied flag against its compiled default, sorted by key. */
    public val defaults: List<FlagDefaultComparison>

    init {
        val mapping = schema.mapRemote(payload, mapper)
        val mismatchedKeys = mapping.problems.mapTo(HashSet()) { it.key }
        val appliedInOrder = ArrayList<FlagKey>()
        val absentInOrder = ArrayList<FlagKey>()
        val notRemote = ArrayList<FlagKey>()
        for (entry in schema.flags) {
            when {
                entry.remoteKey == null -> notRemote += entry.key
                entry.key in mapping.boxes -> appliedInOrder += entry.key
                // A mismatched flag did supply a value: it is a problem, not an absence.
                entry.key !in mismatchedKeys -> absentInOrder += entry.key
            }
        }
        applied = appliedInOrder.sortedBy { it.rawValue }
        absent = absentInOrder.sortedBy { it.rawValue }
        mismatched = mapping.problems.sortedBy { it.key.rawValue }
        notRemotelyOverridable = notRemote.sortedBy { it.rawValue }
        unconsumed = unconsumedPaths(payload, schema.flags.mapNotNull { it.remoteKey })
        defaults = comparisons(schema, mapping, appliedInOrder)
    }

    /** Applied flags whose config value equals the compiled default: the config could omit them. */
    public val matchesDefault: List<FlagKey> get() = defaults.filter { it.matchesDefault }.map { it.key }

    /** Applied flags whose config value differs from the compiled default. */
    public val changesDefault: List<FlagKey> get() = defaults.filterNot { it.matchesDefault }.map { it.key }

    /** Nothing absent and nothing mismatched. Unconsumed values are reported but do not fail. */
    public val isComplete: Boolean get() = isComplete(strict = false)

    /**
     * @param strict also require every payload value to be read by a flag.
     * @param ignoring path prefixes whose unconsumed values are allowed even under [strict].
     */
    public fun isComplete(strict: Boolean, ignoring: List<String> = emptyList()): Boolean {
        if (absent.isNotEmpty() || mismatched.isNotEmpty()) return false
        return !strict || unconsumed(ignoring).isEmpty()
    }

    /** The unconsumed paths left after dropping any path equal to, or under, an ignored prefix. */
    public fun unconsumed(ignoring: List<String>): List<String> {
        if (ignoring.isEmpty()) return unconsumed
        return unconsumed.filter { path -> ignoring.none { path == it || path.startsWith("$it.") } }
    }

    /** Throws the whole report when the payload is incomplete; silent otherwise. */
    public fun requireComplete(strict: Boolean = false, ignoring: List<String> = emptyList()) {
        if (!isComplete(strict, ignoring)) throw FlagMappingAuditException.Incomplete(description(strict, ignoring))
    }

    override fun toString(): String = description(strict = false, ignoring = emptyList())

    /** The full report. [strict] changes the verdict, and [ignoring] drops the excused paths. */
    public fun description(strict: Boolean, ignoring: List<String>): String {
        val shownUnconsumed = unconsumed(ignoring)
        val overridable = applied.size + absent.size + mismatched.size
        val lines = ArrayList<String>()
        lines += if (isComplete(strict, ignoring)) "Flag mapping audit — complete." else "Flag mapping audit — incomplete."
        if (absent.isNotEmpty()) {
            lines += "  absent (${absent.size}) — a flag declares this path, the payload has nothing there:"
            absent.forEach { lines += "    • $it" }
        }
        if (mismatched.isNotEmpty()) {
            lines += "  mismatched (${mismatched.size}):"
            mismatched.forEach { lines += "    • $it" }
        }
        if (shownUnconsumed.isNotEmpty()) {
            val note = if (strict) "" else " — usually fine, often backend metadata"
            lines += "  unconsumed (${shownUnconsumed.size})$note — no flag reads these:"
            shownUnconsumed.forEach { lines += "    • $it" }
        }
        lines += "  ${applied.size} of $overridable remotely-overridable flags applied."
        return lines.joinToString("\n")
    }

    /** The default-versus-config diff as a block: what changes, then what is restated. */
    public val defaultsDescription: String
        get() {
            if (defaults.isEmpty()) return "This config changes nothing — it supplies no value for any flag."
            val changes = defaults.filterNot { it.matchesDefault }
            val restated = defaults.filter { it.matchesDefault }
            val lines = arrayListOf("Default vs config:")
            if (changes.isEmpty()) {
                lines += "  every supplied value matches the compiled default."
            } else {
                lines += "  changes (${changes.size}):"
                for (change in changes) {
                    val rendered = change.toString().split("\n")
                    lines += "    • ${rendered.first()}"
                    rendered.drop(1).forEach { lines += "      $it" }
                }
            }
            if (restated.isNotEmpty()) {
                lines += "  restated (${restated.size}) — same as the default, could be omitted:"
                restated.forEach { lines += "    • ${it.key}" }
            }
            return lines.joinToString("\n")
        }
}

/** A payload that did not wire up every flag it should. */
public sealed class FlagMappingAuditException(message: String) : RuntimeException(message) {

    /** [report] is the whole audit, exactly as `FlagMappingAudit.description` renders it. */
    public class Incomplete(public val report: String) : FlagMappingAuditException(report)
}

private fun comparisons(schema: FlagSchema, mapping: RemoteMapping, keys: List<FlagKey>): List<FlagDefaultComparison> {
    val entries = LinkedHashMap<FlagKey, FlagSchema.Entry>()
    for (entry in schema.flags) {
        if (entry.key !in entries) entries[entry.key] = entry
    }
    return keys.mapNotNull { key ->
        val incoming = mapping.boxes[key]
        val entry = entries[key]
        if (incoming == null || entry == null) null else comparison(key, entry, incoming)
    }.sortedBy { it.key.rawValue }
}

private fun comparison(key: FlagKey, entry: FlagSchema.Entry, incoming: FlagValueBox): FlagDefaultComparison {
    val records = entry.recordShape?.let { shape ->
        FlagRecordDiff.diff(entry.defaultValue.recordValues(shape), incoming.recordValues(shape), shape)
    }
    return FlagDefaultComparison(key, entry.defaultValue, incoming, records)
}

private fun FlagValueBox.recordValues(shape: List<FlagRecordField>): List<Map<String, FlagValueBox>> =
    (this as? FlagValueBox.String)?.let { RecordJson.decode(it.value, shape) }.orEmpty()

private fun unconsumedPaths(payload: RemoteValue, remoteKeys: List<String>): List<String> {
    val claims = remoteKeys.toSet()
    val leaves = ArrayList<String>()
    collectLeafPaths(payload, "", leaves)
    return leaves.filterNot { path -> path in claims || claims.any { path.startsWith("$it.") } }.sorted()
}

// Every scalar's dot path. A nested empty object or array is a leaf too, but the root never
// is: a bare or empty payload has no path to name.
private fun collectLeafPaths(value: RemoteValue, prefix: String, into: MutableList<String>) {
    when {
        value is RemoteValue.Object && value.value.isNotEmpty() ->
            value.value.forEach { (name, child) -> collectLeafPaths(child, if (prefix.isEmpty()) name else "$prefix.$name", into) }
        value is RemoteValue.Array && value.value.isNotEmpty() ->
            value.value.forEachIndexed { index, child -> collectLeafPaths(child, if (prefix.isEmpty()) "$index" else "$prefix.$index", into) }
        prefix.isNotEmpty() -> into += prefix
    }
}
