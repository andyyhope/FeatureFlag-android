package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey

// region snippet: sample-remote-mapper
/**
 * Reads both shapes this backend has ever sent: the dot-path one, by composing DotPathMapper,
 * and a list of `{ "flag", "enabled" }` experiments that no dot path can reach.
 */
object DemoRemoteMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val records = payload.valueAt("experiments") as? RemoteValue.Array
            ?: return DotPathMapper.map(payload, schema)
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (record in records.value) {
            val name = record.valueAt("flag") as? RemoteValue.String
            val enabled = record.valueAt("enabled") as? RemoteValue.Bool
            // Named by the key the schema publishes, so a typo here is reported as an unknown key.
            if (name != null && enabled != null) result[FlagKey(name.value)] = enabled
        }
        return result
    }
}
// endregion
