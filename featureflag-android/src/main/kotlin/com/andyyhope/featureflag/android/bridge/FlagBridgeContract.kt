package com.andyyhope.featureflag.android.bridge

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The names a host's bridge provider and a companion's client agree on: where the provider
 * lives, what it is called with, and what it answers.
 *
 * Every name is part of the wire protocol. Host and companion builds from different releases
 * must still agree on them, so they change only together with [PROTOCOL].
 */
public object FlagBridgeContract {
    /** The bridge protocol version, answered by [METHOD_HELLO]. */
    public const val PROTOCOL: Int = 1

    /**
     * The host's preferences file that holds companion overrides. The host's pole reads it
     * through `SharedPreferencesSource.companion(context)`, and the bridge provider writes it.
     */
    public const val PREFERENCES_NAME: String = "com.andyyhope.featureflag.companion"

    /** The directory, under `Context.noBackupFilesDir`, that `publishSchema(context)` writes into. */
    public const val SCHEMA_DIRECTORY: String = "featureflag"

    /**
     * The largest payload either side sends, in bytes. A `Bundle` crossing processes shares a
     * 1 MiB binder buffer with every other transaction in flight, so this is half of it.
     */
    public const val MAX_PAYLOAD_BYTES: Int = 512 * 1024

    public const val METHOD_HELLO: String = "hello"
    public const val METHOD_OVERRIDES: String = "overrides"
    public const val METHOD_APPLY: String = "apply"
    public const val METHOD_SIGNAL: String = "signal"

    /**
     * Ends a delayed [METHOD_SIGNAL]'s wait without delivering it; its argument is the signal's
     * [KEY_TICKET]. Answers [STATUS_OK] whether or not that ticket was waiting.
     */
    public const val METHOD_CANCEL: String = "cancel"

    /** `Int`: the [PROTOCOL] the host speaks. */
    public const val KEY_PROTOCOL: String = "protocol"

    /** `String`: one of the `STATUS_` constants. */
    public const val KEY_STATUS: String = "status"

    /** `String?`: human-readable detail for a status other than [STATUS_OK]. */
    public const val KEY_MESSAGE: String = "message"

    /** `ByteArray`: a `FlagPayload` as export-format JSON. */
    public const val KEY_PAYLOAD: String = "payload"

    /** `Array<String>`: the keys whose override is removed. */
    public const val KEY_REMOVE: String = "remove"

    /** `Boolean`: whether the host ran a handler for a signal. */
    public const val KEY_HANDLED: String = "handled"

    /** `Int`: the payload's size in bytes, sent with [STATUS_TOO_LARGE]. */
    public const val KEY_SIZE: String = "size"

    /**
     * `Long`: sent with [METHOD_SIGNAL], the moment the client stops waiting, in
     * `SystemClock.elapsedRealtime()` milliseconds. Both processes read the same clock. A host
     * that finds no deadline treats the client as an older one and applies its default wait.
     */
    public const val KEY_DEADLINE: String = "deadline"

    /**
     * `Long`: sent with [METHOD_SIGNAL], how many milliseconds the host waits before it delivers.
     * Absent or not positive delivers at once; the host clamps it to [MAX_DELAY]. The host holds
     * the delay, so a companion frozen in the background cannot stall it.
     */
    public const val KEY_DELAY: String = "delay"

    /** `String`: sent with a delayed [METHOD_SIGNAL], unique per send; [METHOD_CANCEL] names it. */
    public const val KEY_TICKET: String = "ticket"

    /** The longest [KEY_DELAY] a host waits. */
    public val MAX_DELAY: Duration = 60.seconds

    public const val STATUS_OK: String = "ok"
    public const val STATUS_NOT_PUBLISHED: String = "not-published"
    public const val STATUS_REJECTED: String = "rejected"
    public const val STATUS_TOO_LARGE: String = "too-large"

    /** The provider authority of the host installed as [hostPackage]. */
    public fun authority(hostPackage: String): String = "$hostPackage.featureflag"

    /** The signature permission that guards the provider of the host installed as [hostPackage]. */
    public fun permission(hostPackage: String): String = "$hostPackage.permission.FEATURE_FLAG_BRIDGE"

    /**
     * `content://<authority>/schema`: the published schema file, opened read-only. `publishSchema(context)`
     * also notifies it, so a companion can observe it to read a republished schema at once.
     */
    public fun schemaUri(hostPackage: String): Uri = Uri.parse("content://${authority(hostPackage)}/schema")

    /** `content://<authority>/overrides`: observed for changes to the host's companion overrides. */
    public fun overridesUri(hostPackage: String): Uri = Uri.parse("content://${authority(hostPackage)}/overrides")

    /** Where this app's schema is published, and where its provider looks for it. */
    public fun schemaDirectory(context: Context): File = File(context.noBackupFilesDir, SCHEMA_DIRECTORY)
}
