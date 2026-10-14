package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagQRCodeTextTests: a device that cannot scan still gets the code as text.
class FlagQrCodeTextTest {

    @Test
    fun theCopiedCodeIsSomethingTheImportFieldAccepts() = runBlocking<Unit> {
        val sending = store("sending")
        sending.setValue(pageSize(sending), FlagValueBox.Int(42))
        val receiving = store("receiving")

        receiving.import(sending.qrCode())

        assertEquals(FlagValueBox.Int(42), receiving.value(pageSize(receiving)))
    }

    @Test
    fun theCodeIsPlainTextAPersonCanPaste() = runBlocking<Unit> {
        val store = store("sending")
        store.setValue(pageSize(store), FlagValueBox.Int(1))

        val code = store.qrCode()

        assertTrue(code.startsWith("FFQR1:"), code)
        assertFalse(code.contains(" "), "a code with spaces in it does not survive a paste")
    }

    @Test
    fun copyingIsOfferedOnlyWhenThereIsACodeToCopy() = runBlocking<Unit> {
        // Nothing overridden is not a failure, but nor is it a code worth copying.
        val store = store("empty")
        assertNull(store.copyableQrCode)

        store.setValue(pageSize(store), FlagValueBox.Int(7))

        assertEquals(store.qrCode(), store.copyableQrCode)
    }

    @Test
    fun aPayloadTooLargeToEncodeOffersNothingRatherThanThrowingIntoTheView() = runBlocking<Unit> {
        val store = store("huge")
        val note = assertNotNull(store.entry(FlagKey("note")))
        // Incompressible on purpose: the payload is deflated before it is encoded.
        store.setValue(note, FlagValueBox.String(incompressibleString(8_000)))

        assertFailsWith<FlagQRCodeException.PayloadTooLarge> { store.qrCode() }
        assertNull(store.copyableQrCode)
    }

    private fun store(name: String) = FlagEditingStore(CompanionSchemas.qr, SnapshotSource(name), Dispatchers.Unconfined)

    private fun pageSize(store: FlagEditingStore) = assertNotNull(store.entry(FlagKey("page-size")))

    /** The Swift test's generator, byte for byte: a 64-bit LCG, each byte the state's bits 33 to 40. */
    private fun incompressibleString(count: Int): String {
        var state = 0x9E37_79B9_7F4A_7C15uL
        val bytes = ByteArray(count) {
            state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
            (state shr 33).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
