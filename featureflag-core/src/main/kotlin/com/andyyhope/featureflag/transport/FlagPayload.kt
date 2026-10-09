package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.RecordJson
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.toBox
import com.andyyhope.featureflag.values.toJson
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs

/**
 * A set of flag overrides, ready to be written to a file, shared, or carried in a QR code.
 *
 * Only overridden flags travel. Exporting every flag would mostly be a copy of the
 * defaults already compiled into the app, and would not fit in a QR code.
 */
public data class FlagPayload(
    public val values: Map<FlagKey, FlagValueBox>,
    public val exportedAt: Instant = Instant.now(),
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {

    /**
     * The document as JSON, byte-identical to an iOS export: two-space indent, sorted
     * keys, unescaped slashes, and dates to the second.
     *
     * @throws FlagSerializationException.NonFiniteNumber when a value holds an infinity or a NaN.
     */
    public fun jsonBytes(): ByteArray {
        val nonFinite = values.entries.firstOrNull { it.value.containsNonFiniteNumber }
        if (nonFinite != null) throw FlagSerializationException.NonFiniteNumber(nonFinite.key)

        val document = JsonValue.Object(
            mapOf(
                "exportedAt" to JsonValue.Text(IsoDate.format(exportedAt)),
                "formatVersion" to JsonValue.Integer(formatVersion.toLong()),
                "values" to JsonValue.Object(values.entries.associate { (key, box) -> key.rawValue to box.toJson() }),
            ),
        )
        return JsonWriter.pretty(document).encodeToByteArray()
    }

    public companion object {

        /** Bumped when the document's shape changes incompatibly. */
        public const val CURRENT_FORMAT_VERSION: Int = 1

        /**
         * Reads a document, checking every value against [schema]: unknown keys, values of
         * the wrong type, enum cases this build lacks, and record lists it cannot read.
         *
         * All or nothing: when anything is wrong, every problem is reported at once in
         * [FlagImportException.Rejected], sorted by key.
         */
        public fun decode(bytes: ByteArray, schema: FlagSchema): FlagPayload {
            val document = parseDocument(bytes)
            val version = formatVersion(document)
            val rawValues = document.fields["values"] as? JsonValue.Object
                ?: throw FlagImportException.Malformed("missing values")

            val rules = ImportRules(schema)
            val values = LinkedHashMap<FlagKey, FlagValueBox>()
            val problems = mutableListOf<FlagImportProblem>()
            for ((rawKey, raw) in rawValues.fields) {
                val key = FlagKey(rawKey)
                val box = rules.types[key]?.let { raw.toBox(it) }
                val problem = rules.problemWith(key, raw, box)
                if (problem != null) {
                    problems.add(problem)
                } else if (box != null) {
                    values[key] = box
                }
            }
            if (problems.isNotEmpty()) throw FlagImportException.Rejected(problems.sortedBy { it.key.rawValue })

            val exportedAt = (document.fields["exportedAt"] as? JsonValue.Text)?.let { IsoDate.parse(it.value) }
            return FlagPayload(values, exportedAt ?: Instant.EPOCH, version.toInt())
        }

        private fun parseDocument(bytes: ByteArray): JsonValue.Object {
            // JsonReader accepts what JSONSerialization accepts, a byte order mark and a trailing comma included.
            val parsed = try {
                JsonReader.parse(bytes)
            } catch (ignored: JsonSyntaxException) {
                null
            }
            return parsed as? JsonValue.Object ?: throw FlagImportException.Malformed("not a JSON object")
        }

        private fun formatVersion(document: JsonValue.Object): Long {
            val version = when (val raw = document.fields["formatVersion"]) {
                is JsonValue.Integer -> raw.value
                // Swift reads the field with `as? Int`, which turns an NSNumber `true` into 1.
                is JsonValue.Bool -> if (raw.value) 1L else 0L
                else -> throw FlagImportException.Malformed("missing formatVersion")
            }
            if (version != CURRENT_FORMAT_VERSION.toLong()) throw FlagImportException.UnsupportedFormatVersion(version)
            return version
        }
    }
}

/**
 * What a document is checked against, read from a schema. When a hand-made schema names
 * a key twice, the first entry wins, as in Swift's `valueTypes`, `valueCases` and
 * `recordShapes`.
 */
internal class ImportRules(schema: FlagSchema) {

    val types: Map<FlagKey, FlagValueType> = buildMap {
        for (entry in schema.flags) {
            if (entry.key !in this) put(entry.key, entry.valueType)
        }
    }

    private val cases: Map<FlagKey, List<FlagValueBox>> = buildMap {
        for (entry in schema.flags) {
            val entryCases = entry.cases
            if (!entryCases.isNullOrEmpty() && entry.key !in this) put(entry.key, entryCases)
        }
    }

    private val shapes: Map<FlagKey, List<FlagRecordField>> = buildMap {
        for (entry in schema.flags) {
            val shape = entry.recordShape
            if (!shape.isNullOrEmpty() && entry.key !in this) put(entry.key, shape)
        }
    }

    /** The one problem with [raw] for [key], or null when [box] (raw read as the declared type) can be applied. */
    fun problemWith(key: FlagKey, raw: JsonValue, box: FlagValueBox?): FlagImportProblem? {
        val type = types[key]
            ?: return FlagImportProblem(key, FlagImportProblem.Kind.UnknownKey, found = raw.importDescription())
        if (box == null) {
            return FlagImportProblem(key, FlagImportProblem.Kind.TypeMismatch, type.typeName, raw.importDescription())
        }
        val permitted = cases[key]
        if (permitted != null && box !in permitted) {
            return FlagImportProblem(key, FlagImportProblem.Kind.UnknownCase, permitted.caseListDescription(), raw.importDescription())
        }
        val shape = shapes[key] ?: return null
        return recordProblem(key, raw, box, shape)
    }
}

private const val NUMBER_DIGITS = 16

/**
 * A record list is a string, which any text satisfies, so being the right type is not
 * enough. The text must read as records of [shape], each with its own key.
 */
private fun recordProblem(
    key: FlagKey,
    raw: JsonValue,
    box: FlagValueBox,
    shape: List<FlagRecordField>,
): FlagImportProblem? {
    val text = (box as? FlagValueBox.String)?.value
    if (text != null && RecordJson.decode(text, shape) != null) return null
    val duplicate = text?.let { RecordJson.duplicateKey(it, shape) }
    val expected = if (duplicate == null) {
        "a list of records (${shape.joinToString(", ") { it.name }})"
    } else {
        "every record to have its own ${shape.firstOrNull { it.isKey }?.name ?: "key"}"
    }
    val found = if (duplicate == null) raw.importDescription() else "two with ${duplicate.shortDescription()}"
    return FlagImportProblem(key, FlagImportProblem.Kind.TypeMismatch, expected, found)
}

/** A parsed value in a few characters, as Swift's `flagShortDescription(of:)` writes it. */
private fun JsonValue.importDescription(): String = when (this) {
    is JsonValue.Text -> MessageText.quoted(value)
    is JsonValue.Bool -> value.toString()
    is JsonValue.Integer -> value.toString()
    is JsonValue.Real -> numberDescription(value)
    is JsonValue.Array -> MessageText.arrayOf(items.size)
    is JsonValue.Object -> MessageText.objectWith(fields.size)
    JsonValue.Null -> "<null>"
}

/**
 * Swift's `FlagValueBox.shortMessageDescription`. Doubles and floats use Kotlin's
 * `toString()`, which matches Swift's `String(_:)` for ordinary values. M1d Task D1
 * replaces this with `MessageText.short`, the exact port.
 */
private fun FlagValueBox.shortDescription(): String = when (this) {
    is FlagValueBox.String -> MessageText.quoted(value)
    is FlagValueBox.Bool -> value.toString()
    is FlagValueBox.Int -> value.toString()
    is FlagValueBox.Double -> value.toString()
    is FlagValueBox.Float -> value.toString()
    is FlagValueBox.Url -> MessageText.quoted(value.toString())
    is FlagValueBox.Date -> MessageText.date(value)
    is FlagValueBox.Data -> "${value.size} bytes"
    is FlagValueBox.Array -> MessageText.arrayOf(value.size)
    is FlagValueBox.Dictionary -> MessageText.objectWith(value.size)
}

/** Swift's `caseListDescription`: a string case bare, and an int case as `String(describing:)` writes it. */
private fun List<FlagValueBox>.caseListDescription(): String = joinToString(", ") { box ->
    when (box) {
        is FlagValueBox.String -> box.value
        is FlagValueBox.Int -> "int(${box.value})"
        else -> box.shortDescription()
    }
}

/**
 * NSNumber's description of a double, which is C's `%.16g` with trailing zeros removed.
 * This was checked against 7,019 values described by Foundation on this machine. JsonReader
 * reads `-1e400` as negative infinity, as JSONSerialization does, and NSNumber calls it `-inf`.
 */
private fun numberDescription(value: Double): String {
    if (value.isInfinite()) return if (value > 0.0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0.0) "-0" else "0"
    val rounded = BigDecimal(value).round(MathContext(NUMBER_DIGITS, RoundingMode.HALF_EVEN))
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent < -4 || exponent >= NUMBER_DIGITS) {
        val digits = rounded.unscaledValue().abs().toString().trimEnd('0')
        val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
        val sign = if (rounded.signum() < 0) "-" else ""
        val exponentSign = if (exponent < 0) "-" else "+"
        return "$sign${mantissa}e$exponentSign${abs(exponent).toString().padStart(2, '0')}"
    }
    return rounded.stripTrailingZeros().toPlainString()
}
