import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// Root build script. Module scripts opt in to the convention plugins from build-logic; declaring the
// Gradle plugins here (apply false) loads each one once, in the root classloader, for every module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}

// detekt (+ its ktlint-backed formatting ruleset) on every module — the static-analysis + format gate.
// Shared config lives in config/detekt/detekt.yml. Run the gate with `./gradlew detekt`.
// Capture the catalog accessor at root scope — `libs` isn't resolvable inside the subprojects closure.
val detektFormatting = libs.detekt.formatting
val sharedDetektConfig = rootProject.files("config/detekt/detekt.yml")

// The Kotlin the `subprojects` block below cannot reach, analysed on the root project instead:
// `build-logic/` is an included build (never a subproject), and `*.gradle.kts` belong to no source set.
apply(plugin = "io.gitlab.arturbosch.detekt")

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    config.setFrom(sharedDetektConfig)
}

dependencies.add("detektPlugins", detektFormatting)

tasks.withType<Detekt>().configureEach {
    setSource(
        files(
            layout.projectDirectory.dir("build-logic/src/main/kotlin").asFileTree,
            fileTree(layout.projectDirectory) {
                include("**/*.gradle.kts")
                exclude("**/build/**", "**/.gradle/**", "**/.git/**")
            },
        ),
    )

    reports {
        html.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(false)
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(sharedDetektConfig)
    }

    dependencies.add("detektPlugins", detektFormatting)

    tasks.withType<Detekt>().configureEach {
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
            txt.required.set(false)
        }
    }
}
