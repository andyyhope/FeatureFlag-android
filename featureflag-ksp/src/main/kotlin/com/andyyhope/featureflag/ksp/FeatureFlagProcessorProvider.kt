package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/** KSP entry point for the feature-flag code generator. */
public class FeatureFlagProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeatureFlagProcessor(environment.codeGenerator)
}

/** Placeholder processor: emits one marker object so the KSP + KotlinPoet pipeline is exercised. */
internal class FeatureFlagProcessor(private val codeGenerator: CodeGenerator) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!generated) {
            generated = true
            FileSpec.builder(PACKAGE, MARKER)
                .addType(TypeSpec.objectBuilder(MARKER).build())
                .build()
                .writeTo(codeGenerator, Dependencies(aggregating = false))
        }
        return emptyList()
    }

    internal companion object {
        const val PACKAGE = "com.andyyhope.featureflag.generated"
        const val MARKER = "FeatureFlagProcessorMarker"
    }
}
