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

    /**
     * Property name to what [TypeMapping] makes of its type. Originating files are dropped, since a
     * `KSFile` cannot be read once compilation ends; [originatingFiles] checks those.
     */
    private fun mapped(vararg sources: SourceFile): Map<String, TypeMapping.Result> {
        val seen = mutableMapOf<String, TypeMapping.Result>()
        val recorder = SymbolProcessorProvider {
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val types = TypeMapping(resolver)
                    resolver.getNewFiles().flatMap { it.declarations }.filterIsInstance<KSClassDeclaration>()
                        .filter { it.simpleName.asString().endsWith("Flags") }
                        .forEach { container ->
                            container.getDeclaredProperties().forEach { property ->
                                val mapped = types.map(property.type.resolve())
                                seen[property.simpleName.asString()] =
                                    if (mapped is TypeMapping.Result.Supported) {
                                        mapped.copy(originatingFiles = emptyList())
                                    } else {
                                        mapped
                                    }
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

    @Test fun aGenericTypealiasIsExpandedWithItsArguments() {
        val results = mapped(
            source(
                "Aliases.kt",
                "typealias Named<T> = List<T>\ntypealias Table<T> = Map<String, List<T>>\ntypealias Chained<T> = Named<T>",
            ),
            source(
                "Holder.kt",
                """
                interface HolderFlags {
                    val named: Named<String>
                    val table: Table<Int>
                    val chained: Chained<String>
                    val directNamed: List<String>
                    val directTable: Map<String, List<Int>>
                }
                """,
            ),
        )
        fun codec(name: String): String {
            val result = results.getValue(name)
            assertTrue(result is TypeMapping.Result.Supported, "$name: $result")
            return result.codec.toString()
        }
        assertEquals(codec("directNamed"), codec("named"))
        assertEquals(codec("directTable"), codec("table"))
        assertEquals(codec("directNamed"), codec("chained"))
    }

    @Test fun aNullableRecordReachedThroughATypealiasIsUnsupported() {
        val results = mapped(
            source(
                "Endpoint.kt",
                "@FlagRecord data class Endpoint(@FlagRecordKey val name: String)\ntypealias MaybeEndpoint = Endpoint?",
            ),
            source("Holder.kt", "interface HolderFlags {\n    val endpoints: FlagRecords<MaybeEndpoint>\n}"),
        )
        assertEquals(TypeMapping.Result.Unsupported(null), results.getValue("endpoints"))
    }

    @Test fun unsupportedTypesSayWhichRecordIfAny() {
        val results = mapped(
            source(
                "Endpoint.kt",
                "@FlagRecord data class Endpoint(@FlagRecordKey val name: String)\n" +
                    "typealias Named<T> = List<T>\ntypealias Opt<T> = List<T>?",
            ),
            source(
                "Holder.kt",
                """
                interface HolderFlags {
                    val opt: Opt<String>
                    val star: Named<*>
                    val set: Set<String>
                    val intKeyed: Map<Int, String>
                    val uuid: java.util.UUID
                    val endpoint: Endpoint
                }
                """,
            ),
        )
        assertEquals(TypeMapping.Result.Unsupported(null), results.getValue("opt"))
        assertEquals(TypeMapping.Result.Unsupported(null), results.getValue("star"))
        assertEquals(TypeMapping.Result.Unsupported(null), results.getValue("set"))
        assertEquals(TypeMapping.Result.Unsupported(null), results.getValue("intKeyed"))
        assertEquals(TypeMapping.Result.Unsupported(null), results.getValue("uuid"))
        assertEquals(TypeMapping.Result.Unsupported("Endpoint"), results.getValue("endpoint"))
    }
}
