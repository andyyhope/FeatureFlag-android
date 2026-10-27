package docs.sync

/** A file the checker reads: its path from the repository root, with `/` separators, and its text. */
data class TextFile(val path: String, val text: String)

/** What the HTML comment on the line before a fence says about it. */
sealed interface Marker {
    /** `<!-- snippet: name -->`: the body equals the source region `name`. */
    data class Snippet(val name: String) : Marker

    /** `<!-- output: name -->`: the body equals the string `DocOutputs` registers under `name`. */
    data class Output(val name: String) : Marker

    /** `<!-- snippet-ignore: reason -->`: deliberately unchecked. */
    data class Ignore(val reason: String) : Marker
}

/** One fenced block: the line it opens on, its language, its body and its marker, if it has one. */
data class Fence(val file: String, val line: Int, val language: String, val body: String, val marker: Marker?)

/** A link to something in the repository, as written. */
data class Link(val file: String, val line: Int, val target: String)

/**
 * Reads one markdown file: its fences with their markers, its relative links, the anchors its
 * headings make, and whatever about its markup the checker cannot trust.
 *
 * A fence opens at column 0 with three or more backticks or tildes, and a marker is the whole line
 * immediately before it. Markers, links and headings inside a fence are not read. Anything it cannot
 * read reliably is a problem rather than skipped: an indented or quoted fence, and a link that wraps,
 * has a title, or is a reference.
 */
class MarkdownDocument(val file: TextFile) {

    private val fenceList = mutableListOf<Fence>()
    private val linkList = mutableListOf<Link>()
    private val anchorSet = linkedSetOf<String>()
    private val headingCounts = mutableMapOf<String, Int>()
    private val problemList = mutableListOf<String>()

    val fences: List<Fence> get() = fenceList
    val links: List<Link> get() = linkList
    val anchors: Set<String> get() = anchorSet
    val problems: List<String> get() = problemList

    init {
        val lines = file.text.replace("\r\n", "\n").split("\n")
        var marker: Pair<Int, Marker>? = null
        var index = 0
        while (index < lines.size) {
            val opening = FENCE.matchEntire(lines[index])
            if (opening != null) {
                index = readFence(lines, index, opening, marker?.second)
                marker = null
            } else {
                marker?.let { (line, _) -> problem(line, "the marker is not on the line directly before a fence") }
                marker = readMarker(lines[index], index + 1)
                if (marker == null) readProse(lines[index], index + 1)
                index += 1
            }
        }
        marker?.let { (line, _) -> problem(line, "the marker is not on the line directly before a fence") }
    }

    private fun readFence(lines: List<String>, start: Int, opening: MatchResult, marker: Marker?): Int {
        val delimiter = opening.groupValues[1]
        val language = opening.groupValues[2].trim().substringBefore(' ').lowercase()
        val close = (start + 1 until lines.size).firstOrNull { closes(lines[it], delimiter) }
        if (close == null) {
            problem(start + 1, "the fence is never closed")
            return lines.size
        }
        val body = lines.subList(start + 1, close).joinToString("\n")
        fenceList += Fence(file.path, start + 1, language, body, marker)
        return close + 1
    }

    private fun closes(line: String, delimiter: String): Boolean {
        val trimmed = line.trimEnd()
        return trimmed.length >= delimiter.length && trimmed.all { it == delimiter.first() }
    }

    private fun readMarker(line: String, number: Int): Pair<Int, Marker>? {
        val match = MARKER.matchEntire(line.trimEnd())
        if (match == null) {
            if (MARKER_LIKE.matches(line.trim())) problem(number, "'${line.trim()}' is not a marker: $MARKER_FORMS")
            return null
        }
        val (kind, argument) = match.destructured
        val value = argument.trim()
        val marker = when (kind) {
            "snippet" -> Marker.Snippet(value)
            "output" -> Marker.Output(value)
            else -> Marker.Ignore(value)
        }
        when {
            marker is Marker.Ignore && value.isEmpty() -> problem(number, "snippet-ignore needs a reason")
            marker !is Marker.Ignore && !NAME.matches(value) -> problem(number, "'$value' is not a name: $NAME_RULE")
            else -> Unit
        }
        return number to marker
    }

    private fun readProse(line: String, number: Int) {
        if (INDENTED_FENCE.matches(line)) problem(number, INDENTED_FENCE_MESSAGE)
        HEADING.matchEntire(line)?.let { addAnchor(slug(it.groupValues[1])) }
        // Innermost first, so an image inside a link, such as a badge, is read as both.
        var rest = line.replace(INLINE_CODE, "")
        var found = LINK.findAll(rest).toList()
        while (found.isNotEmpty()) {
            val targets = found.map { it.groupValues[1] }.filterNot { "://" in it || it.startsWith("mailto:") }
            targets.forEach { linkList += Link(file.path, number, it) }
            rest = rest.replace(LINK, "")
            found = LINK.findAll(rest).toList()
        }
        // What is left that still looks like a link is one the checker cannot follow, so it would go unchecked.
        if ("](" in rest || REFERENCE_LINK.matches(rest)) problem(number, UNREADABLE_LINK_MESSAGE)
    }

    // GitHub numbers a repeated heading's anchor: the second "Overview" is #overview-1.
    private fun addAnchor(base: String) {
        val seen = headingCounts.getOrDefault(base, 0)
        headingCounts[base] = seen + 1
        anchorSet += if (seen == 0) base else "$base-$seen"
    }

    private fun problem(line: Int, message: String) {
        problemList += "${file.path}:$line: $message"
    }

    companion object {
        /** Names of snippets and outputs. */
        val NAME = Regex("[a-z0-9-]+")
        const val NAME_RULE = "use [a-z0-9-]+"

        private const val MARKER_FORMS = "use <!-- snippet: x -->, <!-- output: x --> or <!-- snippet-ignore: why -->"
        private const val INDENTED_FENCE_MESSAGE = "a fence must start at column 0, or the checker cannot read it"
        private val MARKER = Regex("""<!-- (snippet|output|snippet-ignore): (.*) -->""")
        private val MARKER_LIKE = Regex("""<!--\s*(snippet|output|snippet-ignore)\b.*""")
        private val FENCE = Regex("""(`{3,}|~{3,})(.*)""")
        private const val UNREADABLE_LINK_MESSAGE = "a link the checker cannot read: keep it on one line, with no title"
        private val INDENTED_FENCE = Regex("""(\s+|\s*>.*?)(`{3,}|~{3,}).*""")
        private val REFERENCE_LINK = Regex("""\s*\[[^\]]+]:\s*\S.*""")
        private val HEADING = Regex("""#{1,6}\s+(.+?)(?:\s+#+)?\s*""")
        private val LINK = Regex("""\[[^\[\]]*]\(([^)\s]+)\)""")
        private val INLINE_CODE = Regex("`[^`]*`")

        /** The anchor GitHub gives a heading: lowercase, punctuation dropped, spaces as hyphens. */
        fun slug(heading: String): String = heading
            .lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')
    }
}
