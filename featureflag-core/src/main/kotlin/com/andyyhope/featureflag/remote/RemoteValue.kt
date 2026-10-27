package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.toBox
import java.time.Instant

/**
 * A decoded remote payload. Mappers work against this tree rather than raw bytes, so a
 * custom mapper is ordinary, testable Kotlin.
 *
 * [Data] and [Date] never come out of JSON; they exist for payloads a caller builds by hand.
 */
public sealed interface RemoteValue {

    public data object Null : RemoteValue

    public data class Bool(public val value: Boolean) : RemoteValue

    public data class Int(public val value: Long) : RemoteValue

    public data class Double(public val value: kotlin.Double) : RemoteValue

    public data class String(public val value: kotlin.String) : RemoteValue

    public class Data(public val value: ByteArray) : RemoteValue {
        override fun equals(other: Any?): Boolean = other is Data && value.contentEquals(other.value)

        override fun hashCode(): kotlin.Int = value.contentHashCode()

        override fun toString(): kotlin.String = "Data(${value.size} bytes)"
    }

    public data class Date(public val value: Instant) : RemoteValue

    public data class Array(public val value: List<RemoteValue>) : RemoteValue

    public data class Object(public val value: Map<kotlin.String, RemoteValue>) : RemoteValue

    /**
     * The value at a dot path such as `featureToggles.checkout.v2`, or `null` when nothing
     * is there. A number indexes into an array, so `experiments.0.enabled` works. Empty
     * segments are skipped, so `""` is the root.
     */
    public fun valueAt(path: kotlin.String): RemoteValue? {
        var current: RemoteValue = this
        for (component in path.split('.')) {
            if (component.isEmpty()) continue
            current = when (val node = current) {
                is Object -> node.value[component]
                // Swift's Int(_:) takes an optional sign and ASCII digits; Kotlin's toIntOrNull would
                // also accept Arabic-Indic and fullwidth ones.
                is Array -> component.takeIf { text -> text.removePrefix("+").removePrefix("-").all { it in '0'..'9' } }
                    ?.toIntOrNull()?.let { node.value.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current
    }

    public companion object {

        /**
         * Reads a JSON payload. JSON can only produce null, booleans, integers, doubles,
         * strings, arrays and objects.
         *
         * @throws RemoteOverrideException.Malformed when the bytes are not a JSON object or array.
         */
        public fun parseJson(bytes: ByteArray): RemoteValue {
            val json = try {
                JsonReader.parse(bytes)
            } catch (error: JsonSyntaxException) {
                throw RemoteOverrideException.Malformed("not valid JSON", error)
            }
            return json.toRemoteValue()
        }
    }
}

private fun JsonValue.toRemoteValue(): RemoteValue = when (this) {
    JsonValue.Null -> RemoteValue.Null
    is JsonValue.Bool -> RemoteValue.Bool(value)
    is JsonValue.Integer -> RemoteValue.Int(value)
    is JsonValue.Real -> RemoteValue.Double(value)
    is JsonValue.Text -> RemoteValue.String(value)
    is JsonValue.Array -> RemoteValue.Array(items.map { it.toRemoteValue() })
    is JsonValue.Object -> RemoteValue.Object(fields.mapValues { (_, child) -> child.toRemoteValue() })
}

/**
 * This value as a flag of [type], or `null` when it does not fit.
 *
 * The rule is strict, with one allowance: a whole number satisfies a double or float
 * flag, and a double narrows to a float. A string becomes data, a date or a URL exactly
 * as an imported document's string does.
 */
internal fun RemoteValue.box(type: FlagValueType): FlagValueBox? = when (this) {
    RemoteValue.Null -> null
    is RemoteValue.Bool -> if (type == FlagValueType.Bool) FlagValueBox.Bool(value) else null
    is RemoteValue.Int -> when (type) {
        FlagValueType.Int -> FlagValueBox.Int(value)
        FlagValueType.Double -> FlagValueBox.Double(value.toDouble())
        FlagValueType.Float -> FlagValueBox.Float(value.toFloat())
        else -> null
    }
    is RemoteValue.Double -> when (type) {
        FlagValueType.Double -> FlagValueBox.Double(value)
        FlagValueType.Float -> FlagValueBox.Float(value.toFloat())
        else -> null
    }
    is RemoteValue.String -> when (type) {
        FlagValueType.String -> FlagValueBox.String(value)
        FlagValueType.Data, FlagValueType.Date, FlagValueType.Url -> JsonValue.Text(value).toBox(type)
        else -> null
    }
    is RemoteValue.Data -> if (type == FlagValueType.Data) FlagValueBox.Data(value) else null
    is RemoteValue.Date -> if (type == FlagValueType.Date) FlagValueBox.Date(value) else null
    is RemoteValue.Array -> (type as? FlagValueType.Array)?.let { value.boxEach(it.element) }
    is RemoteValue.Object -> (type as? FlagValueType.Dictionary)?.let { value.boxEach(it.value) }
}

private fun List<RemoteValue>.boxEach(element: FlagValueType): FlagValueBox? {
    val boxes = ArrayList<FlagValueBox>(size)
    for (item in this) boxes += item.box(element) ?: return null
    return FlagValueBox.Array(boxes)
}

private fun Map<String, RemoteValue>.boxEach(valueType: FlagValueType): FlagValueBox? {
    val boxes = LinkedHashMap<String, FlagValueBox>(size)
    for ((key, item) in this) boxes[key] = item.box(valueType) ?: return null
    return FlagValueBox.Dictionary(boxes)
}

/** This value in a few characters, for a one-line message. Strings keep their quotes. */
internal val RemoteValue.shortDescription: String
    get() = when (this) {
        RemoteValue.Null -> "null"
        is RemoteValue.Bool -> value.toString()
        is RemoteValue.Int -> value.toString()
        is RemoteValue.Double -> MessageText.double(value)
        is RemoteValue.String -> MessageText.quoted(value)
        is RemoteValue.Data -> "${value.size} bytes"
        is RemoteValue.Date -> MessageText.date(value)
        is RemoteValue.Array -> MessageText.arrayOf(value.size)
        is RemoteValue.Object -> MessageText.objectWith(value.size)
    }
