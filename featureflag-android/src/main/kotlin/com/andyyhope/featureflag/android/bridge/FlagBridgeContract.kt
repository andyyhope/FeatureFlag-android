package com.andyyhope.featureflag.android.bridge

/** The names a host's bridge provider and a companion's client agree on. */
public object FlagBridgeContract {
    /**
     * The host's preferences file that holds companion overrides. The host's pole reads it
     * through `SharedPreferencesSource.companion(context)`, and the bridge provider writes it.
     */
    public const val PREFERENCES_NAME: String = "com.andyyhope.featureflag.companion"
}
