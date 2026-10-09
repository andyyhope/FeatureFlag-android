package com.andyyhope.featureflag.json

/**
 * A parsed JSON document, keeping what `JSONSerialization` keeps.
 *
 * Numbers are classified the way `NSNumber` answers `as? Int`: [Integer] when the value is
 * integral and in `Long` range (so `3.0`, `1e2` and `-0.0` are all `Integer`), [Real] otherwise.
 */
internal sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Integer(val value: Long) : JsonValue
    data class Real(val value: Double) : JsonValue
    data class Text(val value: String) : JsonValue
    data class Array(val items: List<JsonValue>) : JsonValue

    /** Fields in document order. When a key repeats, the first occurrence wins. */
    data class Object(val fields: Map<String, JsonValue>) : JsonValue
}

internal class JsonSyntaxException(message: String) : IllegalArgumentException(message)
