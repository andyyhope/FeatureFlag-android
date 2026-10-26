package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlagImportExceptionTest {

    private val pageSize = FlagKey("page-size")
    private val tier = FlagKey("tier")
    private val schema = FlagSchema(flags = emptyList())

    @Test
    fun aTypeMismatchNamesTheFlagTheTypeAndTheValue() {
        assertEquals(
            "page-size: expected int, got \"big\"",
            FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch, "int", "\"big\"").toString(),
        )
        assertEquals("page-size: expected another type", FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch).toString())
    }

    @Test
    fun anUnknownCaseListsTheCasesThisBuildHas() {
        assertEquals(
            "tier: \"gold\" is not one of free, pro",
            FlagImportProblem(tier, FlagImportProblem.Kind.UnknownCase, "free, pro", "\"gold\"").toString(),
        )
        assertEquals(
            "tier: that value is not one of this flag's cases",
            FlagImportProblem(tier, FlagImportProblem.Kind.UnknownCase).toString(),
        )
    }

    @Test
    fun anUnknownKeySaysWhereTheDocumentCameFrom() {
        assertEquals(
            "nope: no flag has this key. The document came from a different app, " +
                "or from a build where the flag was named differently",
            FlagImportProblem(FlagKey("nope"), FlagImportProblem.Kind.UnknownKey, found = "1").toString(),
        )
    }

    @Test
    fun aRejectionListsEveryProblemAndSaysNothingWasApplied() {
        val error = FlagImportException.Rejected(
            listOf(
                FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch, "int", "\"big\""),
                FlagImportProblem(tier, FlagImportProblem.Kind.UnknownCase, "free, pro", "\"gold\""),
            ),
        )

        assertEquals(
            """
            Nothing was applied. 2 problems:
              • page-size: expected int, got "big"
              • tier: "gold" is not one of free, pro
            Import is all or nothing, so no flag was changed.
            """.trimIndent(),
            error.message,
        )
    }

    @Test
    fun aSingleProblemIsSingular() {
        val error = FlagImportException.Rejected(
            listOf(FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch, "int", "\"big\"")),
        )

        assertEquals(
            "Nothing was applied. 1 problem:\n  • page-size: expected int, got \"big\"\n" +
                "Import is all or nothing, so no flag was changed.",
            error.message,
        )
    }

    @Test
    fun aRejectionWithNoProblemsStillReadsAsASentence() {
        assertEquals("Nothing was applied, and no problem was recorded.", FlagImportException.Rejected(emptyList()).message)
    }

    @Test
    fun anUnsupportedDocumentVersionSaysWhichBuildIsBehind() {
        val error = FlagImportException.UnsupportedFormatVersion(9)

        assertEquals(9L, error.version)
        assertEquals(
            "The document is format version 9; this build understands 1. Update whichever app is older.",
            error.message,
        )
    }

    @Test
    fun aMalformedDocumentSaysWhatWasWrongWithIt() {
        val error = FlagImportException.Malformed("missing values")

        assertEquals("missing values", error.reason)
        assertEquals("The document could not be read: missing values.", error.message)
    }

    private fun decodeMalformed(json: String): FlagImportException.Malformed =
        assertFailsWith { FlagPayload.decode(json.encodeToByteArray(), schema) }

    @Test
    fun aDocumentThatIsNotJsonKeepsTheReadersError() {
        val error = decodeMalformed("{ nope")

        assertEquals("not a JSON object", error.reason)
        assertIs<JsonSyntaxException>(error.cause)
    }

    @Test
    fun aMalformedDocumentThatIsValidJsonHasNoCause() = assertNull(decodeMalformed("[]").cause)
}
