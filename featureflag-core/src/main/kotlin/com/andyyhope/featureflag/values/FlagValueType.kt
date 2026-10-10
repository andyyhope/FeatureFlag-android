package com.andyyhope.featureflag.values

/**
 * The type a flag declares, independent of any particular value.
 *
 * Descriptors carry this so that a companion app can render the right editor, and so that
 * remote payloads can be validated strictly before anything is applied.
 */
public sealed class FlagValueType {
    public data object Bool : FlagValueType()
    public data object Int : FlagValueType()
    public data object Double : FlagValueType()
    public data object Float : FlagValueType()
    public data object String : FlagValueType()
    public data object Data : FlagValueType()
    public data object Date : FlagValueType()
    public data object Url : FlagValueType()
    public data class Array(public val element: FlagValueType) : FlagValueType()
    public data class Dictionary(public val value: FlagValueType) : FlagValueType()

    /** The type's name as it appears in a published schema, e.g. `bool`, `array<string>`, `dictionary<int>`. */
    public val typeName: kotlin.String
        get() = when (this) {
            Bool -> "bool"
            Int -> "int"
            Double -> "double"
            Float -> "float"
            String -> "string"
            Data -> "data"
            Date -> "date"
            Url -> "url"
            is Array -> "array<${element.typeName}>"
            is Dictionary -> "dictionary<${value.typeName}>"
        }

    public companion object {

        /** Parses a name produced by [typeName], or returns `null` for anything else. */
        public fun parse(name: kotlin.String): FlagValueType? = when (name) {
            "bool" -> Bool
            "int" -> Int
            "double" -> Double
            "float" -> Float
            "string" -> String
            "data" -> Data
            "date" -> Date
            "url" -> Url
            else ->
                nested(name, "array")?.let(::parse)?.let(::Array)
                    ?: nested(name, "dictionary")?.let(::parse)?.let(::Dictionary)
        }

        /** The contents of `prefix<...>`, or `null` if the name is not that shape. */
        private fun nested(name: kotlin.String, prefix: kotlin.String): kotlin.String? {
            val opening = "$prefix<"
            if (!name.startsWith(opening) || !name.endsWith(">")) return null
            return name.substring(opening.length, name.length - 1)
        }
    }
}
