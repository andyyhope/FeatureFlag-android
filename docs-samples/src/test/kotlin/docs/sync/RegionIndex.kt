package docs.sync

/**
 * A `// region snippet: name` … `// endregion` block (`<!-- region snippet: name -->` … `<!-- endregion -->`
 * in XML), with its body as a fence shows it: the lines strictly between the markers, their common
 * indent removed, trailing blank lines dropped. A `snippet-support:` region is never quoted.
 */
data class Region(val name: String, val file: String, val line: Int, val body: String, val support: Boolean)

/** Every snippet region in [sources], and whatever about their markers the checker cannot trust. */
class RegionIndex(sources: List<TextFile>) {

    private val regionList = mutableListOf<Region>()
    private val problemList = mutableListOf<String>()

    val regions: List<Region> get() = regionList
    val problems: List<String> get() = problemList

    init {
        for (source in sources) {
            syntaxOf(source.path)?.let { read(source, it) }
        }
        regionList.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            val places = duplicates.joinToString(" and ") { "${it.file}:${it.line}" }
            problemList += "region '$name' is defined more than once: $places"
        }
    }

    /** The regions called [name]: one, unless it is missing or defined twice. */
    fun named(name: String): List<Region> = regionList.filter { it.name == name }

    private fun read(source: TextFile, syntax: Syntax) {
        val lines = source.text.replace("\r\n", "\n").split("\n")
        val open = ArrayDeque<Open>()
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            val start = syntax.start.matchEntire(line)
            when {
                start != null -> {
                    val (kind, name) = start.destructured
                    if (open.any { it.name != null }) problem(source, index, "region '$name' is inside another region")
                    if (!MarkdownDocument.NAME.matches(name)) {
                        problem(source, index, "'$name' is not a name: ${MarkdownDocument.NAME_RULE}")
                    }
                    open.addLast(Open(index, name, support = kind == "snippet-support"))
                }
                syntax.end.matches(line) -> close(source, lines, index, open.removeLastOrNull())
                syntax.anyStart.matches(line) -> {
                    if (syntax.lookalike.matches(line)) problem(source, index, "'$line' is not a snippet region marker")
                    open.addLast(Open(index, name = null, support = false))
                }
                else -> Unit
            }
        }
        open.forEach { problem(source, it.index, "the region is never closed") }
    }

    private fun close(source: TextFile, lines: List<String>, index: Int, opened: Open?) {
        when {
            opened == null -> problem(source, index, "an endregion closes no region")
            opened.name != null -> regionList += Region(
                name = opened.name,
                file = source.path,
                line = opened.index + 1,
                body = body(lines.subList(opened.index + 1, index)),
                support = opened.support,
            )
            else -> Unit
        }
    }

    private fun problem(source: TextFile, index: Int, message: String) {
        problemList += "${source.path}:${index + 1}: $message"
    }

    /** A region marker that is open. A plain IDE `// region` has no [name]. */
    private class Open(val index: Int, val name: String?, val support: Boolean)

    private class Syntax(val start: Regex, val end: Regex, val anyStart: Regex, val lookalike: Regex)

    companion object {
        private val KOTLIN = Syntax(
            start = Regex("""// region (snippet|snippet-support): (\S+)"""),
            end = Regex("""// endregion"""),
            anyStart = Regex("""// region\b.*"""),
            lookalike = Regex("""//\s*region\s*snippet.*"""),
        )
        private val XML = Syntax(
            start = Regex("""<!-- region (snippet|snippet-support): (\S+) -->"""),
            end = Regex("""<!-- endregion -->"""),
            anyStart = Regex("""<!-- region\b.*"""),
            lookalike = Regex("""<!--\s*region\s*snippet.*"""),
        )

        private fun syntaxOf(path: String): Syntax? = when (path.substringAfterLast('.')) {
            "kt", "kts" -> KOTLIN
            "xml" -> XML
            else -> null
        }

        /** The lines with their common indent removed and trailing blank lines dropped. */
        fun body(lines: List<String>): String {
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
            return lines
                .map { if (it.isBlank()) "" else it.substring(indent) }
                .dropLastWhile { it.isEmpty() }
                .joinToString("\n")
        }
    }
}
