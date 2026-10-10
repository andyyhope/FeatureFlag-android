package com.andyyhope.featureflag.signals

/**
 * An instruction a companion app can send to its host, such as re-fetching the remote
 * config or purging a cache.
 *
 * Declare signals as an enum. Its own `name` satisfies [name], and the host gets an
 * exhaustive `when`. Signals carry no payload: state belongs in flags, which the companion
 * can already edit. The channel that delivers them arrives with `featureflag-android` (M2).
 */
public interface FlagSignal {

    /** The identifier sent over the wire. An enum's own `name` satisfies this. */
    public val name: String

    /** What a companion puts on the button. Defaults to [name]. */
    public val signalDescription: String get() = name

    /**
     * Whether the host must be relaunched before this takes effect. Defaults to `false`.
     * When it is true, a companion can say "handled — relaunch to see it" rather than "handled".
     */
    public val requiresRestart: Boolean get() = false
}
