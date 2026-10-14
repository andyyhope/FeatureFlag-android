package com.andyyhope.featureflag.android.transport

import android.content.Context
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.transport.publishSchema
import java.io.File

/**
 * Writes the schema where `FlagBridgeProvider` serves it: `flag-schema.json` in
 * [FlagBridgeContract.schemaDirectory], under `noBackupFilesDir`, so a restored backup never
 * brings back another build's schema. The write is atomic.
 *
 * Call this on every launch, in `Application.onCreate`: a companion has no other way to learn
 * what flags exist, and the provider waits for `onCreate` to finish before it answers.
 *
 * @return the file written.
 */
public fun FlagPole<*>.publishSchema(context: Context): File =
    publishSchema(FlagBridgeContract.schemaDirectory(context))
