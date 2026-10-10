package com.andyyhope.featureflag.records

/**
 * A list of records, stored as JSON text.
 *
 * The list is a string as far as storage, transport and validation are concerned; the shape
 * rides along in the schema, which is what lets a companion app render an editor for it.
 * Equal to any list with the same elements.
 */
public class FlagRecords<R>(public val values: List<R> = emptyList()) : List<R> by values {
    override fun equals(other: Any?): Boolean = other is List<*> && values == other
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "FlagRecords($values)"
}
