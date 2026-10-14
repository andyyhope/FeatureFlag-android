package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal

/** How a signal group is laid out on the Signals screen. */
public enum class FlagSignalGroupDisplay {
    /** Inline while it is small, on a screen of its own once it has more than [FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD]. */
    Automatic,

    /** Always on a screen of its own, reached from a row. */
    Nested,

    /** Always inline, however many signals it has. */
    Flat,
}

/**
 * A titled set of signals for the Signals screen, usually one enum's entries:
 * `FlagSignalGroup("Session", AppSignal.entries)`. Signals keep the order they are given.
 */
public class FlagSignalGroup(
    public val title: String,
    public val signals: List<FlagSignal>,
    public val display: FlagSignalGroupDisplay = FlagSignalGroupDisplay.Automatic,
) {

    /** The group's [title], which is what tells one group from another. */
    public val id: String
        get() = title

    /** Whether the group gets a screen of its own rather than a section beside the delay picker. */
    internal val isNested: Boolean
        get() = when (display) {
            FlagSignalGroupDisplay.Nested -> true
            FlagSignalGroupDisplay.Flat -> false
            FlagSignalGroupDisplay.Automatic -> signals.size > AUTOMATIC_NESTING_THRESHOLD
        }

    public companion object {
        /** Six fit beside the delay picker; a seventh is where a list starts to bury it. */
        public const val AUTOMATIC_NESTING_THRESHOLD: Int = 6
    }
}

/**
 * The first signal name seen twice, across every group or within one, else null. A signal travels
 * as its name and nothing else, so two with one name would fire both of the host's observers.
 */
internal fun List<FlagSignalGroup>.duplicateSignalName(): String? {
    val seen = HashSet<String>()
    return flatMap { it.signals }.map { it.name }.firstOrNull { !seen.add(it) }
}
