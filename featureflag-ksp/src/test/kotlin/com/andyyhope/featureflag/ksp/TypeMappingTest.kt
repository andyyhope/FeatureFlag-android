@file:OptIn(ExperimentalCompilerApi::class)

package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeMappingTest {
    /** Property name to the names of the files [TypeMapping] reports as originating for it. */
    private fun originatingFiles(vararg sources: SourceFile): Map<String, Set<String>> {
        val seen = mutableMapOf<String, Set<String>>()
        val recorder = SymbolProcessorProvider {
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val types = TypeMapping(resolver)
                    resolver.getNewFiles().flatMap { it.declarations }.filterIsInstance<KSClassDeclaration>()
                        .filter { it.simpleName.asString().endsWith("Flags") }
                        .forEach { container ->
                            container.getDeclaredProperties().forEach { property ->
                                val mapped = types.map(property.type.resolve()) as TypeMapping.Result.Supported
                                seen[property.simpleName.asString()] =
                                    mapped.originatingFiles.map { it.fileName }.toSet()
                            }
                        }
                    return emptyList()
                }
            }
        }
        val result = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
            configureKsp(useKsp2 = true) { symbolProcessorProviders += recorder }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return seen
    }

    @Test fun aTypealiasFileOriginatesEveryFlagTypedThroughIt() {
        val files = originatingFiles(
            source("Aliases.kt", "typealias Names = List<String>\ntypealias MoreNames = Names\ntypealias Key = String"),
            source(
                "Holder.kt",
                """
                interface HolderFlags {
                    val direct: Names
                    val nested: MoreNames
                    val keyed: Map<Key, Int>
                    val plain: List<String>
                }
                """,
            ),
        )
        assertTrue("Aliases.kt" in files.getValue("direct"), files.toString())
        assertTrue("Aliases.kt" in files.getValue("nested"), files.toString())
        assertTrue("Aliases.kt" in files.getValue("keyed"), files.toString())
        assertEquals(emptySet(), files.getValue("plain"))
    }

    @Test fun aHandWrittenRecordCodecFileOriginatesTheFlag() {
        val files = originatingFiles(
            source("Legacy.kt", "class Legacy\nobject LegacyFlagRecord"),
            source("Holder.kt", "interface HolderFlags {\n    val legacy: FlagRecords<Legacy>\n}"),
        )
        assertEquals(setOf("Legacy.kt"), files.getValue("legacy"))
        val split = originatingFiles(
            source("Record.kt", "class Legacy"),
            source("Codec.kt", "object LegacyFlagRecord"),
            source("Holder.kt", "interface HolderFlags {\n    val legacy: FlagRecords<Legacy>\n}"),
        )
        assertEquals(setOf("Record.kt", "Codec.kt"), split.getValue("legacy"))
    }
}
