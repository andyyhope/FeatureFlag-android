package com.andyyhope.featureflag.android.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
