plugins {
    id("featureflag.jvm-library")
    id("featureflag.publish")
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    // kctfork compiles test sources against core's annotations and runtime.
    testImplementation(project(":featureflag-core"))
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    // kctfork 0.8.0 was built on KSP 2.2.0-2.0.2; run the KSP that Kotlin 2.2.10 needs instead.
    testImplementation(libs.ksp.symbol.processing)
    testImplementation(libs.ksp.aa.embeddable)
    testImplementation(libs.ksp.common.deps)
}
