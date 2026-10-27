package docs.troubleshooting

import org.junit.Test
import kotlin.test.assertTrue

/** docs/Troubleshooting.md quotes the messages a companion shows; DocsSyncTest compares them word for word. */
class TroubleshootingTest {

    @Test
    fun eachMessageNamesWhatToDo() {
        assertTrue(usesPermissionMissingMessage().contains("<uses-permission android:name=\"com.example.app."))
        assertTrue(hostNotFoundMessage().contains("featureflag-bridge"))
        assertTrue(permissionDeniedMessage().contains("knownSigner"))
        assertTrue(notPublishedMessage().isNotEmpty())
    }
}
