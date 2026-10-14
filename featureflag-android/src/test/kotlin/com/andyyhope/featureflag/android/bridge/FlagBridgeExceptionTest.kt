package com.andyyhope.featureflag.android.bridge

import com.andyyhope.featureflag.errors.FlagSchemaException
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FlagBridgeExceptionTest {

    @Test
    fun notPublishedSaysWhatCoreSays() {
        assertEquals(FlagSchemaException.NotPublished().message, FlagBridgeException.NotPublished().message)
    }

    @Test
    fun rejectedCarriesTheHostsReason() {
        val error = FlagBridgeException.Rejected("'tier' must be one of free, pro.")

        assertEquals("'tier' must be one of free, pro.", error.reason)
        assertEquals("The host rejected the change: 'tier' must be one of free, pro.", error.message)
    }

    @Test
    fun tooLargeNamesTheSizeAndTheLimit() {
        val error = FlagBridgeException.TooLarge(600_000, FlagBridgeContract.MAX_PAYLOAD_BYTES)

        assertEquals(600_000, error.bytes)
        assertEquals(
            "The change is 600000 bytes; the bridge carries at most 524288. Send fewer overrides at once.",
            error.message,
        )
    }

    @Test
    fun unavailableKeepsItsCause() {
        val cause = IOException("binder died")
        val error = FlagBridgeException.Unavailable(cause)

        assertEquals("The host could not be reached. Open the host app once, then try again.", error.message)
        assertSame(cause, error.cause)
    }
}
