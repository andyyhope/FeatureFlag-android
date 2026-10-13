package com.andyyhope.featureflag.android.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagBridgeContractTest {

    // Names derived from the host's package

    @Test
    fun theAuthorityAndPermissionFollowTheHostPackage() {
        assertEquals("com.example.app.featureflag", FlagBridgeContract.authority("com.example.app"))
        assertEquals(
            "com.example.app.debug.permission.FEATURE_FLAG_BRIDGE",
            FlagBridgeContract.permission("com.example.app.debug"),
        )
    }

    @Test
    fun theUrisAreContentUrisUnderTheAuthority() {
        val schema = FlagBridgeContract.schemaUri("com.example.app")
        val overrides = FlagBridgeContract.overridesUri("com.example.app")

        assertEquals("content://com.example.app.featureflag/schema", schema.toString())
        assertEquals("content://com.example.app.featureflag/overrides", overrides.toString())
        assertEquals("content", schema.scheme)
        assertEquals("com.example.app.featureflag", overrides.authority)
        assertEquals(listOf("overrides"), overrides.pathSegments)
    }

    // Where the schema lives

    @Test
    fun theSchemaDirectoryIsUnderNoBackupFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(File(context.noBackupFilesDir, "featureflag"), FlagBridgeContract.schemaDirectory(context))
    }

    // Wire constants

    @Test
    fun aPayloadFitsWellInsideOneBinderTransaction() {
        // The binder buffer is 1 MiB per process, shared by every transaction in flight.
        assertEquals(512 * 1024, FlagBridgeContract.MAX_PAYLOAD_BYTES)
        assertTrue(FlagBridgeContract.MAX_PAYLOAD_BYTES * 2 <= 1024 * 1024)
    }

    @Test
    fun theWireNamesAreStable() {
        // Host and companion builds from different releases must still agree on these.
        assertEquals(1, FlagBridgeContract.PROTOCOL)
        assertEquals("com.andyyhope.featureflag.companion", FlagBridgeContract.PREFERENCES_NAME)
        assertEquals(
            listOf("hello", "overrides", "apply", "signal"),
            listOf(
                FlagBridgeContract.METHOD_HELLO,
                FlagBridgeContract.METHOD_OVERRIDES,
                FlagBridgeContract.METHOD_APPLY,
                FlagBridgeContract.METHOD_SIGNAL,
            ),
        )
        assertEquals(
            listOf("protocol", "status", "message", "payload", "remove", "handled", "size", "deadline"),
            listOf(
                FlagBridgeContract.KEY_PROTOCOL,
                FlagBridgeContract.KEY_STATUS,
                FlagBridgeContract.KEY_MESSAGE,
                FlagBridgeContract.KEY_PAYLOAD,
                FlagBridgeContract.KEY_REMOVE,
                FlagBridgeContract.KEY_HANDLED,
                FlagBridgeContract.KEY_SIZE,
                FlagBridgeContract.KEY_DEADLINE,
            ),
        )
        assertEquals(
            listOf("ok", "not-published", "rejected", "too-large"),
            listOf(
                FlagBridgeContract.STATUS_OK,
                FlagBridgeContract.STATUS_NOT_PUBLISHED,
                FlagBridgeContract.STATUS_REJECTED,
                FlagBridgeContract.STATUS_TOO_LARGE,
            ),
        )
    }
}
