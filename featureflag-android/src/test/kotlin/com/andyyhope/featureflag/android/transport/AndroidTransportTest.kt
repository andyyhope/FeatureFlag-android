package com.andyyhope.featureflag.android.transport

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AndroidTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pole(keyEncoding: KeyEncoding = KeyEncoding.Kebabcase): FlagPole<Any> =
        FlagPole(DemoSpec, emptyList(), keyEncoding, applicationName = "Demo")

    // Publishing

    @Test
    fun publishingWritesTheSchemaBytesUnderNoBackupFiles() {
        val pole = pole()

        val file = pole.publishSchema(context)

        assertEquals(File(File(context.noBackupFilesDir, "featureflag"), FlagSchema.FILE_NAME), file)
        assertContentEquals(pole.schema.jsonBytes(), file.readBytes())
    }

    @Test
    fun theProviderReadsWhatWasPublished() {
        val pole = pole()
        pole.publishSchema(context)

        val read = FlagSchema.read(FlagBridgeContract.schemaDirectory(context))

        assertEquals(pole.schema.flags, read.flags)
        assertEquals("Demo", read.applicationName)
    }

    @Test
    fun publishingOnEveryLaunchReplacesTheFile() {
        pole().publishSchema(context)
        val snake = pole(KeyEncoding.Snakecase)

        val file = snake.publishSchema(context)

        assertContentEquals(snake.schema.jsonBytes(), file.readBytes())
        assertEquals(listOf(FlagSchema.FILE_NAME), FlagBridgeContract.schemaDirectory(context).list()!!.toList())
    }

    @Test
    fun publishingCarriesWhatAConfigSuppliesBeneathTheCompanionsOverrides() {
        // The companion's file overrides newOnboarding; the config supplies maxItems.
        val companion = SharedPreferencesSource.companion(context)
        companion.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(true))
        val config = RemoteOverrideSource(DemoSpec, name = "Environment (staging)")
        config.apply("""{"limits": {"maxItems": 25}}""".encodeToByteArray())
        val pole = FlagPole(DemoSpec, listOf(companion, config), applicationName = "Demo · Staging")

        val read = FlagSchema.read(pole.publishSchema(context).parentFile!!)

        val (onboarding, maxItems) = read.flags
        assertNull(onboarding.effectiveValue)
        assertEquals(FlagValueBox.Int(25), maxItems.effectiveValue)
        assertEquals("Environment (staging)", maxItems.effectiveSource)
    }

    @Test
    fun publishingAnnouncesTheSchemaToAnOpenCompanion() {
        val announced = mutableListOf<Uri?>()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                announced += uri
            }
        }
        val schemaUri = FlagBridgeContract.schemaUri(context.packageName)
        context.contentResolver.registerContentObserver(schemaUri, false, observer)
        try {
            pole().publishSchema(context)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf<Uri?>(schemaUri), announced)
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    /** Two flags, written by hand: the Android modules do not run KSP. */
    private object DemoSpec : FlagContainerSpec<Any> {
        override val flagContainerDescription: String = "Demo flags"
        override val flagDescriptors: List<FlagSchemaNode> = listOf(
            FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "New onboarding", null, FlagCodecs.boolean, false)),
            FlagSchemaNode.Flag(FlagDescriptor.of("maxItems", "Maximum items", "limits.maxItems", FlagCodecs.int, 10)),
        )
        override val flagDefaults: Any = Any()

        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Any()
    }
}
