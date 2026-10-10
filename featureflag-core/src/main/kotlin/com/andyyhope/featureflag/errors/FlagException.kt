package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey

/** A write the source stack could not take. */
public sealed class FlagException(message: String) : RuntimeException(message) {

    /** Nothing in the source stack can be written to. */
    public class NoMutableSource : FlagException(
        "No source in the stack can be written to. Add a SharedPreferencesSource or a SnapshotSource — " +
            "a pole built only from read-only sources has nowhere to put an override.",
    )

    /** A source refused a value it cannot represent. */
    public class UnsupportedValue(public val key: FlagKey) : FlagException(
        "The source refused the value for '$key' because it cannot represent it. " +
            "The store accepts a narrower set of types than a flag can declare.",
    )
}
