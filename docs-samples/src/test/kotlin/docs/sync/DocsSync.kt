package docs.sync

/**
 * Checks that the docs say what the code does: every fence a reader could copy is byte-identical to
 * a region of compiled, tested code or to a value a test computed, every region is quoted somewhere,
 * every relative link resolves, and every install coordinate names the version being released.
 *
 * Pure: it reads only what it is given, so its tests run over fixture strings.
 *
 * @param fileExists whether a repository path names a file or directory, for links to anything that
 *   is not one of [markdown].
 */
class DocsSync(
    markdown: List<TextFile>,
    sources: List<TextFile>,
    private val outputs: Map<String, () -> String>,
    versionName: String,
    private val fileExists: (String) -> Boolean,
) {

    private val documents = markdown.map(::MarkdownDocument)
    private val regions = RegionIndex(sources)
    private val releaseVersion = versionName.removeSuffix("-SNAPSHOT")

    /** Everything wrong, each as `path:line: what`, or empty. */
    fun problems(): List<String> {
        val fences = documents.flatMap { it.fences }
        return documents.flatMap { it.problems } +
            regions.problems +
            fences.flatMap(::check) +
            orphans(fences) +
            documents.flatMap { it.links }.mapNotNull(::checkLink)
    }

    private fun check(fence: Fence): List<String> {
        val where = "${fence.file}:${fence.line}"
        val problem = when (val marker = fence.marker) {
            null -> "$where: a '${fence.language}' fence needs a marker".takeUnless { fence.language in UNMARKED }
            is Marker.Snippet -> compareSnippet(where, fence, marker.name)
            is Marker.Output -> compareOutput(where, fence, marker.name)
            is Marker.Ignore -> null
        }
        return listOfNotNull(problem) + checkCoordinates(where, fence.body)
    }

    private fun compareSnippet(where: String, fence: Fence, name: String): String? {
        val region = regions.named(name).firstOrNull() ?: return "$where: no source has the region 'snippet: $name'"
        if (region.body == fence.body) return null
        val source = "${region.file}:${region.line}"
        return "$where: the fence differs from region '$name' at $source\n" +
            LineDiff.unified(fence.body, region.body, "$where (document)", "$source (region)")
    }

    private fun compareOutput(where: String, fence: Fence, name: String): String? {
        val compute = outputs[name] ?: return "$where: DocOutputs registers no output '$name'"
        val actual = runCatching { compute() }.getOrElse { return "$where: computing output '$name' threw $it" }
        val expected = actual.replace("\r\n", "\n").split("\n").dropLastWhile { it.isBlank() }.joinToString("\n")
        if (expected == fence.body) return null
        return "$where: the fence differs from output '$name'\n" +
            LineDiff.unified(fence.body, expected, "$where (document)", "DocOutputs['$name'] (computed)")
    }

    private fun checkCoordinates(where: String, body: String): List<String> = COORDINATE.findAll(body).mapNotNull {
        val (artifact, version) = it.destructured
        when {
            artifact !in ARTIFACTS -> "$where: '$artifact' is not a published artifact"
            version != releaseVersion -> "$where: '$artifact:$version' should name the release version, $releaseVersion"
            else -> null
        }
    }.toList()

    private fun orphans(fences: List<Fence>): List<String> {
        val snippets = fences.mapNotNull { (it.marker as? Marker.Snippet)?.name }.toSet()
        val shown = fences.mapNotNull { (it.marker as? Marker.Output)?.name }.toSet()
        val regionOrphans = regions.regions.filter { !it.support && it.name !in snippets }.map {
            "${it.file}:${it.line}: no document quotes region '${it.name}'; quote it, or call it snippet-support"
        }
        val outputOrphans = outputs.keys.filter { it !in shown }.map { "DocOutputs: no document shows output '$it'" }
        return regionOrphans + outputOrphans
    }

    private fun checkLink(link: Link): String? {
        val where = "${link.file}:${link.line}"
        val path = link.target.substringBefore('#')
        val anchor = link.target.substringAfter('#', missingDelimiterValue = "").ifEmpty { null }
        val target = if (path.isEmpty()) link.file else resolve(link.file, path)
        val document = documents.firstOrNull { it.file.path == target }
        return when {
            target == null -> "$where: '${link.target}' points outside the repository"
            target.startsWith("docs/superpowers/") -> "$where: '${link.target}' points into docs/superpowers/"
            document != null && anchor != null && anchor !in document.anchors ->
                "$where: '${link.target}' names a heading $target does not have"
            document != null || fileExists(target) -> null
            else -> "$where: '${link.target}' points to $target, which does not exist"
        }
    }

    companion object {
        /** Fences that need no marker: shell, console and plain text. */
        val UNMARKED = setOf("", "text", "sh", "bash", "console")

        /** The seven published artifacts. */
        val ARTIFACTS = setOf(
            "featureflag-core",
            "featureflag-ksp",
            "featureflag-test",
            "featureflag-android",
            "featureflag-bridge",
            "featureflag-ui",
            "featureflag-bom",
        )

        /** A versioned coordinate in either group: Maven's, or JitPack's. A versionless one, as the BOM allows, is not one. */
        private val COORDINATE =
            Regex("""(?:com\.andyyhope\.featureflag|com\.github\.andyyhope\.FeatureFlag-android):([A-Za-z0-9._-]+):([^"'\s)]+)""")

        /** [relative] resolved against the directory of [from], or null if it climbs out of the repository. */
        fun resolve(from: String, relative: String): String? {
            val segments = from.split('/').dropLast(1).toMutableList()
            for (part in relative.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                    else -> segments += part
                }
            }
            return segments.joinToString("/")
        }
    }
}
