package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey

/**
 * Turns a backend's payload into flag values.
 *
 * Values come back raw. Checking them against each flag's declared type is the source's
 * job, so a mapper never thinks about boxing. Implement this only when a payload's shape
 * cannot be addressed by a path. A mapper may throw [com.andyyhope.featureflag.errors.RemoteOverrideException.Malformed].
 */
public fun interface RemoteOverrideMapper {
    public fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue>
}

/**
 * Reads each flag's `remoteKey` as a dot path into the payload.
 *
 * A flag without a `remoteKey` is not remotely overridable at all. A path that finds
 * nothing, or finds `null`, is left out.
 */
public object DotPathMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (entry in schema.flags) {
            val found = entry.remoteKey?.let(payload::valueAt)
            if (found != null && found != RemoteValue.Null) result[entry.key] = found
        }
        return result
    }
}
