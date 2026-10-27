package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.io.File

/**
 * Writes this pole's schema where a companion app can find it, as `flag-schema.json` in
 * [directory], creating the directory if needed. The write is atomic, so a reader never
 * sees half a file.
 *
 * The schema carries each flag's effective value beneath the overrides, as of this call (see
 * [FlagPole.schemaWithEffectiveValues]), so a companion shows what the app actually reads from
 * a bundled or remote config rather than the compiled default. The file therefore holds those
 * values, remote-config values included, as well as the shape of the flag tree: anything that
 * can read it, such as a companion through the bridge, can read them.
 *
 * Call this on launch: a companion has no other way to learn what flags exist, what they
 * are called, or what types they hold. Call it again whenever a source beneath the overrides
 * changes, such as after a remote config is applied.
 *
 * @return the file written.
 */
public fun FlagPole<*>.publishSchema(directory: File): File = schemaWithEffectiveValues().write(directory)

/**
 * Every flag some source supplies a value for, ready to serialise.
 *
 * This spans the whole stack, not just local overrides. A value a remote payload supplied
 * is exported too, and becomes a local override wherever the document is imported.
 */
public fun FlagPole<*>.exportPayload(): FlagPayload = FlagPayload(values = overrides)

/** The current overrides as JSON, byte-identical to an iOS export. */
public fun FlagPole<*>.exportJson(): ByteArray = exportPayload().jsonBytes()

/**
 * Applies a JSON document as overrides. Strict and all or nothing: any unknown key,
 * mistyped value, unknown enum case or unreadable record list rejects the whole document,
 * with every problem listed.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importJson(bytes: ByteArray): FlagPayload {
    val payload = FlagPayload.decode(bytes, schema)
    applyPayload(payload)
    return payload
}

/**
 * Applies an already-decoded payload as overrides in the top mutable source, as [importJson]
 * and [importQRCode] do once they have decoded one. It is not called `apply`, which reads as
 * the standard library's scope function and as `RemoteOverrideSource.apply`, which replaces a
 * remote layer rather than writing overrides.
 *
 * If a write throws partway, the writes already made are undone before the error
 * propagates. The undo is best effort: a source that has stopped accepting writes will
 * refuse the undo as well.
 */
public fun FlagPole<*>.applyPayload(payload: FlagPayload) {
    val types = ImportRules(schema).types
    val undo = ArrayList<Pair<FlagKey, FlagValueBox?>>()
    var completed = false
    try {
        for ((key, box) in payload.values) {
            val previous = types[key]?.let { currentOverride(key, it) }
            setOverride(key, box)
            undo.add(key to previous)
        }
        completed = true
    } finally {
        if (!completed) {
            for ((key, previous) in undo.asReversed()) {
                runCatching { setOverride(key, previous) }
            }
        }
    }
}

/**
 * The current overrides, encoded for a QR code.
 *
 * @throws com.andyyhope.featureflag.errors.FlagQRCodeException.PayloadTooLarge when they will not fit.
 */
public fun FlagPole<*>.qrCodeString(): String = FlagQRCode.encode(exportPayload())

/**
 * Applies a scanned flag code, all or nothing, exactly as [importJson] applies a document.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importQRCode(text: String): FlagPayload {
    val payload = FlagQRCode.decode(text, schema)
    applyPayload(payload)
    return payload
}
