package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.toJson
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val REQUIRED_REMOTE_CASES = listOf(
    "every-remote-flag", "empty-object", "nulls-are-absent", "whole-numbers", "type-mismatch", "unknown-case",
    "every-problem-sorted", "long-string-value", "integer-overflow", "object-for-string", "bad-date", "offset-date",
    "url-needing-encoding", "records-natural", "records-as-string", "records-duplicate-key", "records-missing-field",
    "records-unknown-case", "records-mixed-forms", "records-wrong-field-type", "not-json", "top-level-scalar",
)

/** Every remote payload the iOS generator applied to `GoldenFlags` lands the same way here. */
class RemoteGoldenFixtureTest {

    private val directory =
        File(requireNotNull(System.getProperty("featureflag.fixtures")) { "featureflag.fixtures is not set" }, "remote")

    private val valueTypes = FlagSchema.of(GoldenFlagsSpec).valueTypes

    private val cases: List<String>
        get() = directory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".payload.json") }
            .map { it.removeSuffix(".payload.json") }
            .sorted()

    @Test
    fun theGeneratorWroteEveryCaseThisPortRelies() {
        val missing = REQUIRED_REMOTE_CASES - cases.toSet()
        assertTrue(missing.isEmpty(), "missing remote goldens: $missing")
    }

    @Test
    fun everyPayloadAppliesExactlyAsItDidOnIos() {
        for (case in cases) assertCase(case)
    }

    private fun assertCase(case: String) {
        val payload = File(directory, "$case.payload.json").readBytes()
        val expected = JsonReader.parse(File(directory, "$case.expected.json").readBytes()) as JsonValue.Object
        val rejected = expected.fields["rejected"] as? JsonValue.Text
        val problems = (expected.fields["problems"] as? JsonValue.Array)?.items.orEmpty()
        val source = RemoteOverrideSource(GoldenFlagsSpec)

        if (rejected != null) {
            val error = assertFailsWith<RemoteOverrideException>(case) { source.apply(payload) }
            assertEquals(rejected.value, error.message, case)
            if (problems.isNotEmpty()) {
                val expectedLines = problems.map { problem ->
                    val fields = (problem as JsonValue.Object).fields
                    listOf("key", "remoteKey", "kind", "message").map { (fields.getValue(it) as JsonValue.Text).value }
                }
                val actualLines = assertIs<RemoteOverrideException.Rejected>(error, case).problems.map { problem ->
                    val kind = problem.kind.name.replaceFirstChar { it.lowercase() }
                    listOf(problem.key.rawValue, problem.remoteKey, kind, problem.toString())
                }
                assertEquals(expectedLines, actualLines, case)
            }
            return
        }
        val result = source.apply(payload)
        assertEquals(texts(expected.fields["applied"]), result.appliedKeys.map { it.rawValue }, case)
        assertEquals(texts(expected.fields["absent"]), result.absentKeys.map { it.rawValue }, case)
        val values = (expected.fields["values"] as JsonValue.Object).fields
        for ((key, json) in values) {
            val type = assertNotNull(valueTypes[FlagKey(key)], "$case: $key")
            val box = assertNotNull(source.box(FlagKey(key), type), "$case: $key holds nothing")
            assertEquals(JsonWriter.compact(json), JsonWriter.compact(box.toJson()), "$case: $key")
        }
    }

    private fun texts(value: JsonValue?): List<String> =
        (value as JsonValue.Array).items.map { (it as JsonValue.Text).value }
}
