package com.andyyhope.featureflag.ui.signals

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long after the tap a signal is delivered. A delay lets the person switch to the host app
 * first, so it is in front and listening when the signal arrives. The host holds the delay.
 */
public enum class FlagSignalDelay(public val duration: Duration, public val label: String) {
    Instant(Duration.ZERO, "Instant"),
    Three(3.seconds, "3s"),
    Five(5.seconds, "5s"),
    Ten(10.seconds, "10s"),
}
