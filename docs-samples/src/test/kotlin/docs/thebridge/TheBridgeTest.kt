package docs.thebridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.schema.FlagSchema
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/** docs/TheBridge.md: the manifests it shows, checked against the contract and the bridge's own manifest. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TheBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val problems = mutableListOf<String>()
    private var schema: FlagSchema? = null

    private fun show(schema: FlagSchema) {
        this.schema = schema
    }

    private fun showProblem(message: String) {
        problems += message
    }

    private fun parse(stream: InputStream): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(stream)

    private fun resource(name: String): Document =
        parse(checkNotNull(javaClass.getResourceAsStream("/docs/thebridge/$name")))

    private fun Document.element(tag: String): Element = getElementsByTagName(tag).item(0) as Element

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    @Test
    fun theCompanionRequestsTheHostsPermission() {
        val permission = resource("companion-manifest.xml").element("uses-permission").android("name")

        assertEquals(FlagBridgeContract.permission("com.example.app"), permission)
    }

    @Test
    fun theMergedManifestIsTheBridgesOwnForThatHost() {
        val shown = resource("merged-manifest.xml")
        val root = checkNotNull(System.getProperty("featureflag.docs.root"))
        val library = File(root, "featureflag-bridge/src/main/AndroidManifest.xml")
            .readText()
            .replace("\${applicationId}", "com.example.app")
        val actual = parse(library.byteInputStream())

        for (tag in listOf("permission", "provider")) {
            for (attribute in listOf("name", "protectionLevel", "authorities", "exported", "permission")) {
                val expected = actual.element(tag).android(attribute)
                assertEquals(expected, shown.element(tag).android(attribute), "$tag $attribute")
            }
        }
        assertEquals(FlagBridgeContract.authority("com.example.app"), shown.element("provider").android("authorities"))
    }

    // The knownSigner recipe is Android platform behaviour this library's tests do not exercise; this
    // only checks that the XML the doc shows parses and redeclares the bridge's permission.
    @Test
    fun theKnownSignerSnippetsParseAndNameTheBridgePermission() {
        val permission = resource("known-signer-manifest.xml").element("permission")

        assertEquals(FlagBridgeContract.permission("com.example.app"), permission.android("name"))
        val certificates = resource("known-certificates.xml").element("string-array")
        assertEquals("featureflag_companion_certificates", certificates.getAttribute("name"))
    }

    @Test
    fun aCompanionOfYourOwnConnectsWithTheClient() = runTest {
        // region snippet: bridge-connect
        val client = FlagBridgeClient(context, hostPackage = "com.example.app")

        when (val connection = client.connect()) {
            is FlagBridgeConnection.Connected -> show(connection.schema)
            else -> showProblem(connection.message.orEmpty())
        }
        // endregion

        // This test app declares no <uses-permission>, which is the first thing connect() checks.
        val missing = FlagBridgeConnection.UsesPermissionMissing(FlagBridgeContract.permission("com.example.app"))
        assertEquals(missing.message, problems.single())
        assertEquals(null, schema)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
