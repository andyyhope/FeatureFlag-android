import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// Root build script. Module scripts opt in to the convention plugins from build-logic; declaring the
// Gradle plugins here (apply false) loads each one once, in the root classloader, for every module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
}

// Every module gets detekt from its convention plugin (featureflag.detekt). What no module can
// reach is analysed on the root project instead: `build-logic/` is an included build (never a
// subproject), and `*.gradle.kts` belong to no source set. Run the gate with `./gradlew detekt`.
apply(plugin = "io.gitlab.arturbosch.detekt")

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

dependencies.add("detektPlugins", libs.detekt.formatting)

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
