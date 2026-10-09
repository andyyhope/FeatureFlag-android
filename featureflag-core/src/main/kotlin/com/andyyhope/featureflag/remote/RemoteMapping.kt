package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

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
    val box = value.box(entry.valueType)
        ?: return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.TypeMismatch, entry.valueType.typeName, value.shortDescription),
        )
    val cases = entry.cases
    if (cases != null && box !in cases) {
        return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.UnknownCase, MessageText.caseList(cases), value.shortDescription),
        )
    }
    return Checked.Accepted(box)
}
