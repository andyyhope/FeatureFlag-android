package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** Generates `XRefs`/`XSpec` for every `@FlagContainer` and `RFlagRecord` for every `@FlagRecord`. */
internal class FeatureFlagProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val checks = Checks(resolver, logger)
        val deferred = mutableListOf<KSAnnotated>()

        val recordCandidates = symbols(resolver, Names.FLAG_RECORD, deferred)
        val containerCandidates = symbols(resolver, Names.FLAG_CONTAINER, deferred)
        val validRecords = recordCandidates.filter { checks.record(it) }
        val shapely = containerCandidates.filter { checks.container(it) }
        val cyclic = checks.cycles(containerCandidates)
        val validContainers = shapely.filterNot { it in cyclic }

        // A declaration whose generated code names a withheld one would not compile: withhold it too,
        // without a diagnostic of its own, until nothing changes.
        val all = recordCandidates + containerCandidates
        var records = validRecords
        var containers = validContainers
        while (true) {
            val kept = (records + containers).mapNotNull { it.qualifiedName?.asString() }.toSet()
            val withheld = all.mapNotNull { it.qualifiedName?.asString() }.filter { it !in kept }.toSet()
            val nextRecords = records.filter { checks.dependencies(it).none(withheld::contains) }
            val nextContainers = containers.filter { checks.dependencies(it).none(withheld::contains) }
            if (nextRecords.size == records.size && nextContainers.size == containers.size) break
            records = nextRecords
            containers = nextContainers
        }

        val recordGenerator = RecordGenerator(resolver, codeGenerator)
        records.forEach { recordGenerator.generate(recordGenerator.parse(it)) }
        val containerGenerator = ContainerGenerator(resolver, codeGenerator)
        containers.forEach { containerGenerator.generate(containerGenerator.parse(it)) }
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
