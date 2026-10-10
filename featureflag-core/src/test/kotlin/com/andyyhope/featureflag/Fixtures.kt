package com.andyyhope.featureflag

import java.io.File

/**
 * Reads the golden fixtures in the repo-root `fixtures/` directory, which Gradle passes to
 * every test task as the `featureflag.fixtures` system property.
 */
object Fixtures {

    private val root: File by lazy {
        val path = checkNotNull(System.getProperty("featureflag.fixtures")) {
            "featureflag.fixtures is not set; run the tests through Gradle"
        }
        File(path).also { check(it.isDirectory) { "No fixtures at $it; run scripts/sync-fixtures.sh" } }
    }

    fun bytes(path: String): ByteArray {
        val file = File(root, path)
        check(file.isFile) { "Missing fixture $path; run scripts/sync-fixtures.sh" }
        return file.readBytes()
    }

    fun text(path: String): String = bytes(path).decodeToString()

    /** Rows of a `.tsv` fixture, every field unescaped with [unescape]. */
    fun tsv(path: String): List<List<String>> {
        val text = text(path)
        if (text.isEmpty()) return emptyList()
        return text.removeSuffix("\n").split("\n").map { line -> line.split("\t").map(::unescape) }
    }

    /** Lines of a `.txt` fixture written without a trailing newline. */
    fun lines(path: String): List<String> = text(path).split("\n")

    /** The TSV escaping rule shared with the Swift generator: `\\`, `\t`, `\n` and `\u00xx`. */
    fun unescape(field: String): String = buildString {
        var index = 0
        while (index < field.length) {
            val char = field[index]
            if (char != '\\') {
                append(char)
                index++
                continue
            }
            when (val escaped = field[index + 1]) {
                '\\' -> append('\\')
                't' -> append('\t')
                'n' -> append('\n')
                'u' -> append(field.substring(index + 2, index + 6).toInt(16).toChar())
                else -> error("Unknown TSV escape \\$escaped in $field")
            }
            index += if (field[index + 1] == 'u') 6 else 2
        }
    }
}
