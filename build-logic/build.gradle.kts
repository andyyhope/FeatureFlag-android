plugins {
    `kotlin-dsl`
}

// Gradle plugin artifacts the convention plugins configure. AGP 9 ships built-in Kotlin, so
// applying com.android.library also wires Kotlin for the Android modules.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "featureflag.jvm-library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "featureflag.android-library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "featureflag.android-application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("detekt") {
            id = "featureflag.detekt"
            implementationClass = "DetektConventionPlugin"
        }
        register("fixtures") {
            id = "featureflag.fixtures"
            implementationClass = "FixturesConventionPlugin"
        }
        register("compose") {
            id = "featureflag.compose"
            implementationClass = "ComposeConventionPlugin"
        }
    }
}
