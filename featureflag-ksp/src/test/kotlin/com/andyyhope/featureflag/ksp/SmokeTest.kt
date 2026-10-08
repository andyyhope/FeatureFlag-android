package com.andyyhope.featureflag.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class SmokeTest {
    @Test
    fun processorGeneratesMarkerUnderKctfork() {
        val compilation = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Sample.kt", "class Sample"))
            inheritClassPath = true
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += FeatureFlagProcessorProvider()
            }
        }

        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("${FeatureFlagProcessor.PACKAGE}.${FeatureFlagProcessor.MARKER}")
    }
}
