package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.schema.FlagSchema

/** Why a published schema could not be used. Messages are ported from Swift's `FlagSchemaError`. */
public sealed class FlagSchemaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** The document is from a newer (or older) build than this one understands. */
    public class UnsupportedFormatVersion(public val version: Long) : FlagSchemaException(
        "The schema is format version $version; this build understands ${FlagSchema.CURRENT_FORMAT_VERSION}. " +
            "Update whichever app is older.",
    )

    /** The document is not a schema at all, or one this build cannot trust. */
    public class Malformed(public val reason: String, cause: Throwable? = null) :
        FlagSchemaException("The schema could not be read: $reason.", cause)

    /** Nothing has been written where the schema should be. */
    public class NotPublished(cause: Throwable? = null) : FlagSchemaException(
        "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
        cause,
    )
}
