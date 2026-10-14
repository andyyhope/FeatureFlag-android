package com.andyyhope.featureflag.sample.companion

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one source of truth for the host id reaches both the manifest and BuildConfig. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CompanionManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Section: the host id

    @Test
    fun theCompanionAsksForTheSampleHostsBridgePermission() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val requested = info.requestedPermissions.orEmpty().toList()

        assertTrue(
            "com.andyyhope.featureflag.sample.host.permission.FEATURE_FLAG_BRIDGE" in requested,
            "requested: $requested",
        )
        assertTrue(FlagBridgeContract.permission(BuildConfig.HOST_PACKAGE) in requested)
    }

    @Test
    fun buildConfigNamesTheSampleHost() {
        assertEquals("com.andyyhope.featureflag.sample.host", BuildConfig.HOST_PACKAGE)
    }
}
