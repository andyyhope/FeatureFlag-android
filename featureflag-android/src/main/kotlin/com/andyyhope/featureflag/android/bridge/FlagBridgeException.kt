package com.andyyhope.featureflag.android.bridge

/** Why a companion's read or write through the bridge failed. */
public sealed class FlagBridgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** The host has not written its schema, so it cannot check overrides against it. */
    public class NotPublished :
        FlagBridgeException("No schema has been published. The host app writes one with publishSchema(), so run it at least once.")

    /** The host refused the change; [reason] is its explanation. Nothing was written. */
    public class Rejected(public val reason: String) : FlagBridgeException("The host rejected the change: $reason")

    /** The document is [bytes] long, past the [limit] one bridge call carries. */
    public class TooLarge(public val bytes: Int, public val limit: Int) :
        FlagBridgeException("The change is $bytes bytes; the bridge carries at most $limit. Send fewer overrides at once.")

    /** The host could not be reached: it is not installed, refused the connection, or died twice. */
    public class Unavailable(cause: Throwable?) :
        FlagBridgeException("The host could not be reached. Open the host app once, then try again.", cause)
}
