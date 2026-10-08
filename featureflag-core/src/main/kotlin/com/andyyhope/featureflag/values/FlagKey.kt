package com.andyyhope.featureflag.values

/**
 * The string a value source actually stores a flag under.
 *
 * Keys are produced by applying a [KeyEncoding] to a [FlagKeyPath]; they are not written by
 * hand except when reading an existing store.
 */
@JvmInline
public value class FlagKey(public val rawValue: String) {
    override fun toString(): String = rawValue
}

/**
 * The position of a flag in its container tree, recorded as raw property names.
 *
 * Generated code emits paths rather than finished keys because key encoding is configured at
 * runtime on the pole, and compile-time metadata cannot know it.
 */
public data class FlagKeyPath(public val propertyNames: List<String>) {

    public fun appending(propertyName: String): FlagKeyPath = FlagKeyPath(propertyNames + propertyName)

    public fun prepending(propertyName: String): FlagKeyPath = FlagKeyPath(listOf(propertyName) + propertyNames)

    public companion object {
        public val ROOT: FlagKeyPath = FlagKeyPath(emptyList())
    }
}
