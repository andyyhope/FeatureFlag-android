package com.andyyhope.featureflag.ui.share

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagClipboardTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))

    private fun current(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun copyingPutsTheValueOnTheClipboard() {
        FlagClipboard.copy(context, """{"a":1,"b":["x","y"]}""")

        assertEquals("""{"a":1,"b":["x","y"]}""", current())
    }

    @Test
    fun copyingReplacesWhateverWasThereBefore() {
        FlagClipboard.copy(context, "first")
        FlagClipboard.copy(context, "second")

        assertEquals("second", current())
    }

    @Test
    fun copyingAnEmptyStringIsNotACrash() {
        FlagClipboard.copy(context, "")

        assertEquals("", current())
    }

    @Test
    fun copyingAnImagePutsItsUriOnTheClipboard() {
        val uri = Uri.parse("content://${context.packageName}.featureflag.files/featureflag/code.png")

        FlagClipboard.copyImage(context, uri)

        val clip = checkNotNull(clipboard.primaryClip)
        assertEquals(uri, clip.getItemAt(0).uri)
        assertTrue(clip.description.hasMimeType("image/png"))
    }
}
