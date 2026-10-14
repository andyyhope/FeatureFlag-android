package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonWriter

/**
 * One box as JSON value text, using the same rules as export/import, so a store or a wire can
 * carry any flag type.
 *
 * Dates are written to the second, data as padded base64, URLs as text, and a float is widened
 * to a double, exactly as an exported document writes them. Reading is as strict as import:
 * the text must be one JSON value of the declared type.
 */
public object FlagValueJson {

    /**
     * Compact JSON value text: `true`, `3`, `0.10000000000000001`, `"2026-01-01T00:00:00Z"`,
     * `[1,2]`, `{"a":"b"}`. Object keys are sorted as an export sorts them.
     *
     * @throws FlagSerializationException.NonFiniteNumber for an infinity or a NaN, which JSON
     *   cannot represent. Its key is null: a bare box does not know which flag it belongs to.
     */
    public fun encode(box: FlagValueBox): String = JsonWriter.compact(box.toJson())

    /**
     * The box [text] holds as [type], or null when [text] is not valid JSON or not that type.
     * Strict, as import is: there is no conversion except an integer read as a double or a
     * float, and a collection with one wrong element is rejected whole. Unlike a document, a
     * scalar may be the whole text.
     */
    public fun decode(text: String, type: FlagValueType): FlagValueBox? {
        val json = try {
            JsonReader.parseFragment(text)
        } catch (ignored: JsonSyntaxException) {
            return null
        }
        return json.toBox(type)
    }
}
