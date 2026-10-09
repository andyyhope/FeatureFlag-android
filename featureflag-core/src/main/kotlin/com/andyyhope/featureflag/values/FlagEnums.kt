package com.andyyhope.featureflag.values

/** An enum stored as a string other than its `name`. */
public interface FlagStringEnum {
    public val flagRawValue: String
}

/** An enum stored as an integer. */
public interface FlagIntEnum {
    public val flagRawValue: Long
}
