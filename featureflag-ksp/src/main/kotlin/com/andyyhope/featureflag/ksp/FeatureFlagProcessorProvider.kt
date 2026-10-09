package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Registered through META-INF/services, so `ksp(project(":featureflag-ksp"))` is all a module needs. */
public class FeatureFlagProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeatureFlagProcessor(environment.codeGenerator, environment.logger)
}
