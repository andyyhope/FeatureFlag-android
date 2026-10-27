package docs.sync

import java.io.File

/**
 * The files DocsSyncTest reads from the repository at [root]: the markdown a reader sees, and the
 * sources its fences quote. docs-samples/build.gradle.kts declares every tree read here, and the link
 * targets, as the test task's inputs; a new pattern here needs a new input there.
 */
class DocsRepository(private val root: File, val versionName: String) {

    /** The root README, every markdown file directly in docs, and the samples README, whichever exist. */
    val markdown: List<TextFile> by lazy {
        val docs = File(root, "docs").listFiles { file -> file.isFile && file.extension == "md" }.orEmpty()
        (listOf(File(root, "README.md")) + docs.sortedBy { it.name } + File(root, "samples/README.md"))
            .filter { it.isFile }
            .map(::read)
    }

    /** Kotlin, script and XML files: all of docs-samples/src, and each sample's src and build.gradle.kts. */
    val sources: List<TextFile> by lazy {
        val docsSamples = walk("docs-samples/src") { true }
        val samples = walk("samples") { path -> "/src/" in path || path.endsWith("/build.gradle.kts") }
        (docsSamples + samples).map(::read)
    }

    /** Whether a repository path names a file or a directory. */
    fun exists(path: String): Boolean = File(root, path).exists()

    private fun walk(directory: String, include: (String) -> Boolean): List<File> = File(root, directory)
        .walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" }
        .filter { it.isFile && it.extension in SOURCE_EXTENSIONS && include(relative(it)) }
        .sortedBy { relative(it) }
        .toList()

    private fun read(file: File): TextFile = TextFile(relative(file), file.readText())

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    companion object {
        private val SOURCE_EXTENSIONS = setOf("kt", "kts", "xml")

        /** The repository Gradle hands the test, as `featureflag.docs.root` and `featureflag.docs.versionName`. */
        fun fromSystemProperties(): DocsRepository {
            val root = checkNotNull(System.getProperty("featureflag.docs.root")) {
                "featureflag.docs.root is not set; run the tests through Gradle"
            }
            val versionName = checkNotNull(System.getProperty("featureflag.docs.versionName")) {
                "featureflag.docs.versionName is not set; run the tests through Gradle"
            }
            return DocsRepository(File(root), versionName)
        }
    }
}
