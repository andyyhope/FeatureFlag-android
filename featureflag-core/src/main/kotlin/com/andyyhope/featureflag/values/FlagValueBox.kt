package com.andyyhope.featureflag.values

import java.net.URI
import java.time.Instant

/**
 * The canonical boxed representation of a flag value.
 *
 * Every storage and transport mechanism reads and writes this one type, so each supported
 * Kotlin type needs only a single conversion to be usable everywhere.
 *
 * Equality is Kotlin data-class equality: `Double(0.0) != Double(-0.0)` and `Double(NaN) ==
 * Double(NaN)`, where Swift's `FlagValueBox` compares with IEEE `==`.
 */
public sealed interface FlagValueBox {
    public data class Bool(public val value: Boolean) : FlagValueBox
    public data class Int(public val value: Long) : FlagValueBox
    public data class Double(public val value: kotlin.Double) : FlagValueBox
    public data class Float(public val value: kotlin.Float) : FlagValueBox
    public data class String(public val value: kotlin.String) : FlagValueBox

    /** Bytes. Equal when the contents are equal. */
    public class Data(public val value: ByteArray) : FlagValueBox {
        override fun equals(other: Any?): Boolean = other is Data && value.contentEquals(other.value)
        override fun hashCode(): kotlin.Int = value.contentHashCode()
        override fun toString(): kotlin.String = "Data(${value.size} bytes)"
    }

    public data class Date(public val value: Instant) : FlagValueBox

    /** A URL. Equal when the text is equal, as Swift's `URL` is. */
    public class Url(public val value: URI) : FlagValueBox {
        override fun equals(other: Any?): Boolean = other is Url && value.toString() == other.value.toString()
        override fun hashCode(): kotlin.Int = value.toString().hashCode()
        override fun toString(): kotlin.String = "Url($value)"
    }

    public data class Array(public val value: List<FlagValueBox>) : FlagValueBox
    public data class Dictionary(public val value: Map<kotlin.String, FlagValueBox>) : FlagValueBox

    /**
     * Whether this box holds the type a flag declared.
     *
     * An empty collection matches any collection of that shape.
     */
    public fun matches(type: FlagValueType): Boolean = when (this) {
        is Bool -> type == FlagValueType.Bool
        is Int -> type == FlagValueType.Int
        is Double -> type == FlagValueType.Double
        is Float -> type == FlagValueType.Float
        is String -> type == FlagValueType.String
        is Data -> type == FlagValueType.Data
        is Date -> type == FlagValueType.Date
        is Url -> type == FlagValueType.Url
        is Array -> type is FlagValueType.Array && value.all { it.matches(type.element) }
        is Dictionary -> type is FlagValueType.Dictionary && value.values.all { it.matches(type.value) }
    }

    /** Whether this value holds an infinity or a NaN, anywhere inside it. JSON can represent neither. */
    public val containsNonFiniteNumber: Boolean
        get() = when (this) {
            is Double -> !value.isFinite()
            is Float -> !value.isFinite()
            is Array -> value.any { it.containsNonFiniteNumber }
            is Dictionary -> value.values.any { it.containsNonFiniteNumber }
            else -> false
        }
}
