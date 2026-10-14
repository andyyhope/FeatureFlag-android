package docs.sync

import docs.DocOutputs
import org.junit.Test
import kotlin.test.assertTrue

/** The repository's docs against the code they quote. DocsSyncCheckerTest pins every way this can fail. */
class DocsSyncTest {

    private val repository = DocsRepository.fromSystemProperties()

    @Test
    fun everyDocumentMatchesTheCodeItQuotes() {
        val problems = DocsSync(
            markdown = repository.markdown,
            sources = repository.sources,
            outputs = DocOutputs.all,
            versionName = repository.versionName,
            fileExists = repository::exists,
        ).problems()

        assertTrue(problems.isEmpty(), "The docs have drifted from the code:\n\n" + problems.joinToString("\n\n"))
    }

    @Test
    fun theScanFindsTheCheckerItself() {
        // A wrong root would scan nothing and pass, so prove the walk reached this module's sources.
        assertTrue(repository.sources.any { it.path == "docs-samples/src/test/kotlin/docs/sync/DocsSync.kt" })
    }
}
