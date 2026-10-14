plugins {
    id("featureflag.jvm-library")
    alias(libs.plugins.ksp)
    id("featureflag.publish")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // The processor runs over the test sources only: core itself declares no flags.
    kspTest(project(":featureflag-ksp"))
}
