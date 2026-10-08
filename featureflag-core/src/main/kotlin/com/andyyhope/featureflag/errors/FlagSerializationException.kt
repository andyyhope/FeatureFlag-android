package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey

/** Something that cannot be written out. */
public sealed class FlagSerializationException(message: String) : RuntimeException(message) {

    /**
     * A value holds an infinity or a NaN, which JSON cannot represent. [key] names the flag when
     * the code that found it knows which one it was.
     */
    public class NonFiniteNumber(public val key: FlagKey?) : FlagSerializationException(nonFiniteMessage(key))
}

private fun nonFiniteMessage(key: FlagKey?): String {
    val subject = if (key == null) "A value" else "'${key.rawValue}'"
    return "$subject holds an infinity or a NaN, and JSON can represent neither. " +
        "Use a finite value, or a sentinel the flag's type already has."
}
