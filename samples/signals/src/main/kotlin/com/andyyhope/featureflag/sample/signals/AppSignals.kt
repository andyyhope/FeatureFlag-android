package com.andyyhope.featureflag.sample.signals

import com.andyyhope.featureflag.signals.FlagSignal

// Shared by the host, which observes them, and the companion, which sends them. A signal travels
// as its entry name, so both sides must be built from this one file.

// region snippet: sample-app-signal
/** Asks the host to redo or undo its remote configuration. */
public enum class AppSignal(override val signalDescription: String) : FlagSignal {
    RefetchRemoteConfiguration("Re-fetch remote configuration"),
    ClearRemoteConfiguration("Clear remote configuration"),
}
// endregion

/**
 * Asks the host to empty a cache. What PurgeEverything empties is rebuilt at launch, so the host
 * looks unchanged until it restarts, and the companion says "handled — relaunch to see it".
 */
public enum class CacheSignal(
    override val signalDescription: String,
    override val requiresRestart: Boolean = false,
) : FlagSignal {
    PurgeImageCache("Purge the image cache"),
    PurgeEverything("Purge everything", requiresRestart = true),
}
