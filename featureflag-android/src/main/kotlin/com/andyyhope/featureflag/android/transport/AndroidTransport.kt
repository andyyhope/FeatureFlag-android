package com.andyyhope.featureflag.android.transport

import android.content.Context
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.transport.publishSchema
import java.io.File

/**
 * Writes the schema where `FlagBridgeProvider` serves it: `flag-schema.json` in
 * [FlagBridgeContract.schemaDirectory], under `noBackupFilesDir`, so a restored backup never
 * brings back another build's schema. The write is atomic. It carries each flag's effective
 * value beneath the overrides, as of this call.
 *
 * Then it announces the new schema on [FlagBridgeContract.schemaUri], so a companion that is
 * open reads it again at once. A build without the bridge has no provider to announce on, and
 * skips that.
 *
 * Call this on every launch, in `Application.onCreate`: a companion has no other way to learn
 * what flags exist, and the provider waits for `onCreate` to finish before it answers. Call it
 * again whenever a source beneath the overrides changes, such as after a remote config is applied.
 *
 * @return the file written.
 */
public fun FlagPole<*>.publishSchema(context: Context): File {
    val file = publishSchema(FlagBridgeContract.schemaDirectory(context))
    try {
        context.contentResolver.notifyChange(FlagBridgeContract.schemaUri(context.packageName), null)
    } catch (ignored: SecurityException) {
        // No provider has the authority: a build without featureflag-bridge, so no companion is listening.
    }
    return file
}
