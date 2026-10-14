package com.andyyhope.featureflag.ui.screens

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class ImportFailureTextTest {

    @Test
    fun aRejectedDocumentListsOneLinePerProblem() {
        val rejected = FlagImportException.Rejected(
            listOf(
                FlagImportProblem(FlagKey("max-items"), FlagImportProblem.Kind.TypeMismatch, "int", "\"lots\""),
                FlagImportProblem(FlagKey("nope"), FlagImportProblem.Kind.UnknownKey),
                FlagImportProblem(FlagKey("tier"), FlagImportProblem.Kind.UnknownCase, "free, pro", "\"gold\""),
            ),
        )

        assertEquals(
            "max-items: wrong type\nnope: not a flag in this app\ntier: not one of its choices",
            describeImportFailure(rejected),
        )
    }

    @Test
    fun aNewerDocumentNamesItsFormat() {
        assertEquals(
            "This document was written by a newer version (format 2).",
            describeImportFailure(FlagImportException.UnsupportedFormatVersion(2)),
        )
    }

    @Test
    fun aMalformedDocumentIsNotAFlagDocument() {
        assertEquals("That is not a flag document.", describeImportFailure(FlagImportException.Malformed("not a JSON object")))
    }

    @Test
    fun anythingElseIsItsOwnMessage() {
        assertEquals(
            "Not a flag code. A flag code starts with \"FFQR1:\".",
            describeImportFailure(FlagQRCodeException.UnrecognisedFormat()),
        )
    }
}
