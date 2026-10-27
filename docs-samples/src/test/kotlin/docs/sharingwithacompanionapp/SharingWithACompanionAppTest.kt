package docs.sharingwithacompanionapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.publishSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SharingWithACompanionApp.md. Robolectric builds [ExampleApplication] and runs its onCreate first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = ExampleApplication::class)
class SharingWithACompanionAppTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sources = listOf(SnapshotSource())

    @Test
    fun theApplicationPublishesItsSchemaOnLaunch() {
        val application = ApplicationProvider.getApplicationContext<ExampleApplication>()

        val published = File(FlagBridgeContract.schemaDirectory(application), FlagSchema.FILE_NAME)

        assertTrue(published.isFile)
        assertEquals(application.pole.schema.flags, FlagSchema.parse(published.readBytes()).flags)
    }

    @Test
    fun aDirectoryCanBePublishedToForTestsAndTools() {
        val pole = FlagPole(AppFlagsSpec, sources = sources)
        val directory = folder.newFolder("published")

        // region snippet: sharing-publish-directory
        val file = pole.publishSchema(directory)
        // endregion

        assertEquals(File(directory, "flag-schema.json"), file)
    }

    @Test
    fun theSchemaCarriesEveryFlagAndGroup() {
        assertTrue(publishedSchema().contains("\"applicationName\" : \"Demo\""))
    }

    @Test
    fun theApplicationNameIsYours() {
        // region snippet: sharing-application-name
        val pole = FlagPole(AppFlagsSpec, sources = sources, applicationName = "Demo (Staging)")
        // endregion

        assertEquals("Demo (Staging)", pole.schema.applicationName)
        assertEquals(null, FlagPole(AppFlagsSpec, sources = sources).schema.applicationName)
    }

    @Test
    fun theSchemaReadsBack() {
        val directory = folder.newFolder("read")
        FlagPole(AppFlagsSpec, sources = sources).publishSchema(directory)

        // region snippet: sharing-read-schema
        val schema = FlagSchema.read(directory)

        val count = schema.flags.size
        val types = schema.valueTypes // Map<FlagKey, FlagValueType>, for validating a payload
        // endregion

        assertEquals(1, count)
        assertEquals(mapOf(FlagKey("checkout.google-pay") to FlagValueType.Bool), types)
    }
}
