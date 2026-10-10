@file:OptIn(ExperimentalCompilerApi::class)

package com.andyyhope.featureflag.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One `logger.error` the processor reported: the file's name, the line, and the message. */
internal data class Reported(val file: String, val line: Int, val message: String)

internal class Compiled(private val result: JvmCompilationResult) {
    val succeeded: Boolean get() = result.exitCode == KotlinCompilation.ExitCode.OK
    val outputDirectory: File get() = result.outputDirectory
    val classLoader: ClassLoader get() = result.classLoader

    /** Only the errors and warnings, for an assertion message a person can read. */
    val problems: String
        get() = result.messages.lines().filter { it.startsWith("e:") || it.startsWith("w:") }.joinToString("\n")

    /**
     * Every error the processor reported, in the order it reported them. Fails on any other error,
     * which is what invalid generated code would produce.
     */
    val errors: List<Reported>
        get() {
            val others = result.messages.lines().filter { it.startsWith("e:") && !it.startsWith("e: [ksp]") && it != KSP_ABORTED }
            check(others.isEmpty()) { "errors outside the processor's diagnostics:\n" + others.joinToString("\n") }
            return reported()
        }

    /** Fails when a file was generated for any of [declarations]: `XRefs`, `XSpec` or `XFlagRecord`. */
    fun assertNothingGeneratedFor(vararg declarations: String) {
        val files = generated.keys
        val unexpected = declarations.flatMap { listOf("${it}Refs.kt", "${it}Spec.kt", "${it}FlagRecord.kt") }
            .filter { it in files }
        assertTrue(unexpected.isEmpty(), "generated for a faulty declaration: $unexpected")
    }

    private fun reported(): List<Reported> = result.messages.lineSequence()
        .mapNotNull { REPORTED.matchEntire(it.trim()) }
        .map { Reported(File(it.groupValues[1]).name, it.groupValues[2].toInt(), it.groupValues[3]) }
        .toList()

    /** Generated file name to its text. */
    val generated: Map<String, String>
        get() = result.sourcesGeneratedBySymbolProcessor.associate { it.name to it.readText() }

    /** The generated [file] equals `src/test/resources/expansions/<file>.txt`, ignoring trailing whitespace. */
    fun assertMatchesSnapshot(file: String) {
        assertTrue(succeeded, problems)
        val expected = Compiled::class.java.getResource("/expansions/$file.txt")?.readText()?.trimEnd()
        assertEquals(expected, generated[file]?.trimEnd(), "update the snapshot only if the change is intended")
    }

    private companion object {
        /** Added by KSP itself after the processor reported any error. */
        const val KSP_ABORTED = "e: Error occurred in KSP, check log for detail"
        val REPORTED = Regex("""^e: \[ksp] (.+):(\d+): (.*)$""")
    }
}

/**
 * Runs the processor over [sources] with KSP2, then compiles everything with warnings as errors, for
 * JVM 1.8: the lowest target a consumer may use, so generated code never inlines core's 17 bytecode.
 */
internal fun compile(
    vararg sources: SourceFile,
    classpath: List<File> = emptyList(),
): Compiled = Compiled(
    KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        classpaths = classpaths + classpath
        jvmTarget = "1.8"
        allWarningsAsErrors = true
        messageOutputStream = OutputStream.nullOutputStream()
        configureKsp(useKsp2 = true) { symbolProcessorProviders += FeatureFlagProcessorProvider() }
    }.compile(),
)

/**
 * A Kotlin source in package `app` with every annotation imported. The header is nine lines, so
 * the first line of [body] is line 10 of the file.
 */
internal fun source(name: String, body: String): SourceFile = SourceFile.kotlin(
    name,
    """
    |package app
    |
    |import com.andyyhope.featureflag.Flag
    |import com.andyyhope.featureflag.FlagContainer
    |import com.andyyhope.featureflag.FlagGroup
    |import com.andyyhope.featureflag.FlagRecord
    |import com.andyyhope.featureflag.FlagRecordKey
    |import com.andyyhope.featureflag.FlagRecordProperty
    |import com.andyyhope.featureflag.records.FlagRecords
    |
    """.trimMargin() + body.trimIndent() + "\n",
)

/** Compiles [sources] without KSP, as a module that never applied the processor; returns its classes. */
internal fun compileWithoutProcessor(vararg sources: SourceFile): File {
    val result = KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        jvmTarget = "1.8"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
    check(result.exitCode == KotlinCompilation.ExitCode.OK) { result.messages }
    return result.outputDirectory
}
