package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** Generates `XRefs` and `XSpec` for every `@FlagContainer` interface. */
internal class FeatureFlagProcessor(private val codeGenerator: CodeGenerator) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val containers = ContainerGenerator(resolver, codeGenerator)
        symbols(resolver, Names.FLAG_CONTAINER, deferred).forEach { containers.generate(containers.parse(it)) }
        return deferred
    }

    /** Declarations carrying [annotation] that resolve; the rest wait for a later round. */
    private fun symbols(
        resolver: Resolver,
        annotation: String,
        deferred: MutableList<KSAnnotated>,
    ): List<KSClassDeclaration> {
        val (ready, waiting) = resolver.getSymbolsWithAnnotation(annotation).partition { it.validate() }
        deferred += waiting
        return ready.filterIsInstance<KSClassDeclaration>()
    }
}
