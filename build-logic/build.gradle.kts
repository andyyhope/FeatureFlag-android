plugins {
    `kotlin-dsl`
}

// Gradle plugin artifacts the convention plugins configure.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "featureflag.jvm-library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
