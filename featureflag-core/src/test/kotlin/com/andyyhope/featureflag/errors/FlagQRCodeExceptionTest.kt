package com.andyyhope.featureflag.errors

import org.junit.Test
import kotlin.test.assertEquals

class FlagQRCodeExceptionTest {

    @Test
    fun anOversizedCodeSaysHowFarOverItIs() {
        val error = FlagQRCodeException.PayloadTooLarge(bytes = 3400, limit = 2900, overrideCount = 12)

        assertEquals(12, error.overrideCount)
        assertEquals(
            "12 overrides need 3400 characters and a QR code holds 2900. Reset a few, or export JSON instead.",
            error.message,
        )
    }

    @Test
    fun oneOverrideReadsAsSwiftWritesIt() {
        assertEquals(
            "1 override need 3400 characters and a QR code holds 2900. Reset a few, or export JSON instead.",
            FlagQRCodeException.PayloadTooLarge(bytes = 3400, limit = 2900, overrideCount = 1).message,
        )
    }

    @Test
    fun anUnrecognisedCodeSaysWhatAFlagCodeLooksLike() {
        assertEquals("Not a flag code. A flag code starts with \"FFQR1:\".", FlagQRCodeException.UnrecognisedFormat().message)
    }

    @Test
    fun aCorruptCodeBlamesThePartialScanItUsuallyIs() {
        assertEquals(
            "The code could not be decompressed, which usually means a partial scan. Try again with the whole code in frame.",
            FlagQRCodeException.Corrupt().message,
        )
    }
}
