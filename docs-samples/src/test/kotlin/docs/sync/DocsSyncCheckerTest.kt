package docs.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DocsSync over fixture strings: each way the docs can drift from the code, before any real doc exists. */
class DocsSyncCheckerTest {

    private val greetingDoc = """
        # Greeting

        <!-- snippet: greeting -->
        ```kotlin
        val greeting = "hello"
        println(greeting)
        ```
    """.trimIndent()

    // Spliced in, so that DocsSyncTest, which scans this file too, does not read the fixture's markers as real ones.
    private val region = "// region"
    private val endregion = "// endregion"

    private val greetingSource = """
        class Greeting {
            fun run() {
                $region snippet: greeting
                val greeting = "hello"
                println(greeting)
                $endregion
            }
        }
    """.trimIndent()

    private val noQuotes = mapOf("docs/A.md" to "# Nothing quoted\n")

    private fun problems(
        markdown: Map<String, String>,
        sources: Map<String, String> = emptyMap(),
        outputs: Map<String, () -> String> = emptyMap(),
        versionName: String = "0.1.0-SNAPSHOT",
        existing: Set<String> = emptySet(),
    ): List<String> = DocsSync(
        markdown = markdown.map { (path, text) -> TextFile(path, text) },
        sources = sources.map { (path, text) -> TextFile(path, text) },
        outputs = outputs,
        versionName = versionName,
        fileExists = { it in existing },
    ).problems()

    private fun assertOneProblem(problems: List<String>, vararg fragments: String) {
        assertEquals(1, problems.size, problems.joinToString("\n"))
        for (fragment in fragments) {
            assertTrue(fragment in problems.single(), "'$fragment' is not in: ${problems.single()}")
        }
    }

    // Snippets

    @Test
    fun aSnippetMatchingItsIndentedRegionPasses() {
        val problems = problems(mapOf("docs/A.md" to greetingDoc), mapOf("src/Greeting.kt" to greetingSource))

        assertEquals(emptyList(), problems)
    }

    @Test
    fun windowsLineEndingsAndTrailingBlankLinesInARegionAreIgnored() {
        val body = "val greeting = \"hello\"\r\nprintln(greeting)\r\n\r\n"
        val source = "// region snippet: greeting\r\n$body// endregion\r\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to greetingDoc), mapOf("src/Greeting.kt" to source)))
    }

    @Test
    fun aSnippetWhoseRegionChangedShowsADiffWithBothLocations() {
        val changed = greetingSource.replace("\"hello\"", "\"goodbye\"")

        val problems = problems(mapOf("docs/A.md" to greetingDoc), mapOf("src/Greeting.kt" to changed))

        assertOneProblem(
            problems,
            "docs/A.md:4: the fence differs from region 'greeting' at src/Greeting.kt:3",
            "--- docs/A.md:4 (document)",
            "+++ src/Greeting.kt:3 (region)",
            "-val greeting = \"hello\"",
            "+val greeting = \"goodbye\"",
            " println(greeting)",
        )
    }

    @Test
    fun aSnippetWithNoRegionFails() {
        val problems = problems(mapOf("docs/A.md" to greetingDoc))

        assertOneProblem(problems, "docs/A.md:4: no source has the region 'snippet: greeting'")
    }

    @Test
    fun aRegionDefinedTwiceFails() {
        val sources = mapOf("src/Greeting.kt" to greetingSource, "src/Copy.kt" to greetingSource)

        assertOneProblem(
            problems(mapOf("docs/A.md" to greetingDoc), sources),
            "region 'greeting' is defined more than once",
            "src/Greeting.kt:3",
            "src/Copy.kt:3",
        )
    }

    @Test
    fun xmlRegionsAreReadToo() {
        val doc = "<!-- snippet: permission -->\n```xml\n<uses-permission android:name=\"a.b\" />\n```\n"
        val manifest = "<manifest>\n    <!-- region snippet: permission -->\n" +
            "    <uses-permission android:name=\"a.b\" />\n    <!-- endregion -->\n</manifest>\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc), mapOf("src/AndroidManifest.xml" to manifest)))
    }

    // Unmarked fences

    @Test
    fun anUnmarkedKotlinKtsXmlOrJsonFenceFails() {
        for (language in listOf("kotlin", "kts", "xml", "json")) {
            val doc = "# Title\n\n```$language\nsomething\n```\n"

            assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:3: a '$language' fence needs a marker")
        }
    }

    @Test
    fun shellConsoleAndPlainTextFencesNeedNoMarker() {
        val doc = listOf("sh", "bash", "console", "", "text").joinToString("\n\n") { "```$it\nls\n```" }

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    @Test
    fun anIgnoredFenceIsNotCompared() {
        val doc = "<!-- snippet-ignore: shows a mistake on purpose -->\n```kotlin\nval x: String = 1\n```\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    @Test
    fun anIgnoreWithNoReasonFails() {
        val doc = "<!-- snippet-ignore:  -->\n```kotlin\nval x = 1\n```\n"

        assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:1: snippet-ignore needs a reason")
    }

    @Test
    fun aMarkerThatIsNotDirectlyBeforeAFenceFails() {
        val doc = "<!-- snippet: greeting -->\n\n```kotlin\nval greeting = \"hello\"\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc))

        assertEquals(
            listOf(
                "docs/A.md:1: the marker is not on the line directly before a fence",
                "docs/A.md:3: a 'kotlin' fence needs a marker",
            ),
            problems,
        )
    }

    @Test
    fun aMisspelledMarkerOrAnInvalidNameFails() {
        val misspelled = problems(mapOf("docs/A.md" to "<!--snippet: greeting-->\n```text\nx\n```\n"))
        val badName = problems(mapOf("docs/A.md" to "<!-- snippet: Greeting_1 -->\n```text\nx\n```\n"))

        assertOneProblem(misspelled, "docs/A.md:1: '<!--snippet: greeting-->' is not a marker")
        assertTrue(badName.any { "docs/A.md:1: 'Greeting_1' is not a name" in it }, badName.joinToString("\n"))
    }

    @Test
    fun anIndentedOrUnclosedFenceFails() {
        val indented = problems(mapOf("docs/A.md" to "- a list\n    ```kotlin\n    val x = 1\n    ```\n"))
        val unclosed = problems(mapOf("docs/A.md" to "```text\nnever closed\n"))

        assertEquals(
            listOf(
                "docs/A.md:2: a fence must start at column 0, or the checker cannot read it",
                "docs/A.md:4: a fence must start at column 0, or the checker cannot read it",
            ),
            indented,
        )
        assertOneProblem(unclosed, "docs/A.md:1: the fence is never closed")
    }

    @Test
    fun aFenceInABlockquoteFails() {
        val problems = problems(mapOf("docs/A.md" to "> Note:\n> ```kotlin\n> val x = 1\n> ```\n"))

        assertEquals(
            listOf(
                "docs/A.md:2: a fence must start at column 0, or the checker cannot read it",
                "docs/A.md:4: a fence must start at column 0, or the checker cannot read it",
            ),
            problems,
        )
    }

    // Orphans

    @Test
    fun aRegionNobodyQuotesFails() {
        val problems = problems(noQuotes, mapOf("src/Greeting.kt" to greetingSource))

        assertOneProblem(problems, "src/Greeting.kt:3: no document quotes region 'greeting'")
    }

    @Test
    fun aSupportRegionIsNeverAnOrphan() {
        val source = "// region snippet-support: fixture\nval fixture = 1\n// endregion\n"

        assertEquals(emptyList(), problems(noQuotes, mapOf("src/Fixture.kt" to source)))
    }

    @Test
    fun plainIdeRegionsAreNotSnippets() {
        val source = "// region Helpers\nfun helper() = 1\n// endregion\n"

        assertEquals(emptyList(), problems(noQuotes, mapOf("src/Helpers.kt" to source)))
    }

    // Outputs

    @Test
    fun anOutputMatchingItsValuePasses() {
        val doc = "<!-- output: report -->\n```text\nline one\nline two\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc), outputs = mapOf("report" to { "line one\nline two\n" }))

        assertEquals(emptyList(), problems)
    }

    @Test
    fun anOutputThatNoLongerMatchesFails() {
        val doc = "<!-- output: report -->\n```text\nline one\nline two\n```\n"

        assertOneProblem(
            problems(mapOf("docs/A.md" to doc), outputs = mapOf("report" to { "line one\nline 2" })),
            "docs/A.md:2: the fence differs from output 'report'",
            "-line two",
            "+line 2",
        )
    }

    @Test
    fun anUnregisteredOrUnshownOutputFails() {
        val doc = "<!-- output: report -->\n```text\nx\n```\n"

        assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:2: DocOutputs registers no output 'report'")
        assertOneProblem(
            problems(noQuotes, outputs = mapOf("report" to { "x" })),
            "DocOutputs: no document shows output 'report'",
        )
    }

    @Test
    fun anOutputThatThrowsFails() {
        val doc = "<!-- output: report -->\n```text\nx\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc), outputs = mapOf("report" to { error("no report") }))

        assertOneProblem(problems, "docs/A.md:2: computing output 'report' threw", "no report")
    }

    // Unbalanced markers

    @Test
    fun anEndregionWithNoRegionFails() {
        val problems = problems(noQuotes, mapOf("src/A.kt" to "val a = 1\n// endregion\n"))

        assertOneProblem(problems, "src/A.kt:2: an endregion closes no region")
    }

    @Test
    fun aRegionThatIsNeverClosedFails() {
        val problems = problems(noQuotes, mapOf("src/A.kt" to "// region snippet: open\nval a = 1\n"))

        assertOneProblem(problems, "src/A.kt:1: the region is never closed")
    }

    @Test
    fun aSnippetRegionInsideAnotherFails() {
        val source = "// region snippet: outer\n// region snippet: inner\nval a = 1\n// endregion\n// endregion\n"
        val doc = "<!-- snippet: inner -->\n```kotlin\nval a = 1\n```\n\n" +
            "<!-- snippet: outer -->\n```kotlin\n// region snippet: inner\nval a = 1\n// endregion\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc), mapOf("src/A.kt" to source))

        assertOneProblem(problems, "src/A.kt:2: region 'inner' is inside another region")
    }

    @Test
    fun aMisspelledRegionMarkerFails() {
        val problems = problems(noQuotes, mapOf("src/A.kt" to "// region snippet:greeting\nval a = 1\n// endregion\n"))

        assertOneProblem(problems, "src/A.kt:1: '// region snippet:greeting' is not a snippet region marker")
    }

    // Links

    @Test
    fun relativeLinksToFilesAndHeadingsResolve() {
        val readme = "# Read me\n\n[![Licence](LICENSE)](LICENSE)\n\n" +
            "[Nesting](docs/DeclaringFlags.md#nesting-flaggroups), " +
            "[values](docs/FlagValues.md),\n[the licence](LICENSE), [the top](#read-me) and " +
            "[a site](https://example.com/missing).\n"
        val declaring = "# Declaring flags\n\n## Nesting `@FlagGroup`s\n\nBack to [the readme](../README.md#read-me).\n"
        val markdown = mapOf(
            "README.md" to readme,
            "docs/DeclaringFlags.md" to declaring,
            "docs/FlagValues.md" to "# Values\n",
        )

        assertEquals(emptyList(), problems(markdown, existing = setOf("LICENSE")))
    }

    @Test
    fun aLinkToAMissingFileFails() {
        val problems = problems(mapOf("docs/A.md" to "See [B](B.md).\n"))

        assertOneProblem(problems, "docs/A.md:1: 'B.md' points to docs/B.md, which does not exist")
    }

    @Test
    fun aLinkToAMissingHeadingFails() {
        val problems = problems(mapOf("docs/A.md" to "See [B](B.md#missing).\n", "docs/B.md" to "# Present\n"))

        assertOneProblem(problems, "docs/A.md:1: 'B.md#missing' names a heading docs/B.md does not have")
    }

    @Test
    fun aRepeatedHeadingGetsANumberedAnchor() {
        val doc = "# Overview\n\n## Overview\n\nSee [the second](#overview-1) and [no third](#overview-2).\n"

        assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:5: '#overview-2' names a heading")
    }

    @Test
    fun aLinkIntoThePlanningNotesFails() {
        val doc = "See [the plan](superpowers/plans/x.md).\n"

        val problems = problems(mapOf("docs/A.md" to doc), existing = setOf("docs/superpowers/plans/x.md"))

        assertOneProblem(problems, "points into docs/superpowers/")
    }

    @Test
    fun aLinkTheCheckerCannotReadFails() {
        val wrapped = "See [the other\ndoc](B.md).\n"
        val titled = "See [B](B.md \"B\").\n"
        val reference = "See [B][b].\n\n[b]: B.md\n"

        assertOneProblem(problems(mapOf("docs/A.md" to wrapped)), "docs/A.md:2: a link the checker cannot read")
        assertOneProblem(problems(mapOf("docs/A.md" to titled)), "docs/A.md:1: a link the checker cannot read")
        assertOneProblem(problems(mapOf("docs/A.md" to reference)), "docs/A.md:3: a link the checker cannot read")
    }

    @Test
    fun aHeadingsClosingHashesAreNotPartOfItsAnchor() {
        val doc = "## Closing hashes ##\n\nSee [it](#closing-hashes).\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    @Test
    fun linksInsideFencesAndInlineCodeAreNotFollowed() {
        val doc = "Write `[x](missing.md)` to link.\n\n```text\n[x](missing.md)\n```\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    // Install coordinates

    @Test
    fun anInstallCoordinateMustNameTheReleaseVersion() {
        val line = "implementation(\"com.andyyhope.featureflag:featureflag-core:0.1.0\")"
        val source = "// region snippet: install\n$line\n// endregion\n"
        val doc = "<!-- snippet: install -->\n```kts\n$line\n```\n"

        val current = problems(mapOf("docs/A.md" to doc), mapOf("src/install.kts" to source))
        val stale = problems(
            mapOf("docs/A.md" to doc.replace("0.1.0", "0.0.9")),
            mapOf("src/install.kts" to source.replace("0.1.0", "0.0.9")),
        )

        assertEquals(emptyList(), current)
        assertOneProblem(stale, "docs/A.md:2: 'featureflag-core:0.0.9' should name the release version, 0.1.0")
    }

    @Test
    fun anInstallCoordinateMustNameAPublishedArtifact() {
        val doc = "```sh\necho com.andyyhope.featureflag:featureflag-compose:0.1.0\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc))

        assertOneProblem(problems, "docs/A.md:1: 'featureflag-compose' is not a published artifact")
    }

    // Helpers

    @Test
    fun headingsSlugTheWayGitHubDoes() {
        assertEquals("what-flagrecord-writes", MarkdownDocument.slug("What `@FlagRecord` writes"))
        assertEquals("1-add-the-dependencies", MarkdownDocument.slug("1. Add the dependencies"))
        assertEquals("a--b", MarkdownDocument.slug("A — B"))
    }

    @Test
    fun relativePathsResolveAgainstTheLinkingFile() {
        assertEquals("docs/B.md", DocsSync.resolve("docs/A.md", "./B.md"))
        assertEquals("README.md", DocsSync.resolve("docs/A.md", "../README.md"))
        assertEquals(null, DocsSync.resolve("docs/A.md", "../../outside.md"))
    }
}
