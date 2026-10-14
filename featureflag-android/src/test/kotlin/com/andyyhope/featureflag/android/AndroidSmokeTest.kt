package com.andyyhope.featureflag.android

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AndroidSmokeTest {

    @Test
    fun robolectricRunsTheCompileSdkWithCoreOnTheClasspath() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(36, Build.VERSION.SDK_INT)
        assertTrue(context.noBackupFilesDir.isDirectory)
        assertEquals("a", FlagKey("a").rawValue)
    }
}
