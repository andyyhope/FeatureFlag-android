import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// Root build script. Module scripts opt in to the convention plugins from build-logic; declaring the
// Gradle plugins here (apply false) loads each one once, in the root classloader, for every module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    // Applied, not apply-false: the root aggregates the modules' API docs into one site.
    alias(libs.plugins.dokka)
    // Applied, not apply-false: BCV configures the JVM modules' apiDump and apiCheck from the root.
    alias(libs.plugins.binary.compatibility.validator)
    // The Android modules apply it themselves; declaring it here loads it in the root classloader beside BCV.
    alias(libs.plugins.android.bcv.bridge) apply false
}

// The docs site: ./gradlew :dokkaGeneratePublicationHtml, into build/dokka/html. featureflag-ksp is left out,
// because no app calls it; it still publishes its own javadoc jar.
dependencies {
    dokka(project(":featureflag-core"))
    dokka(project(":featureflag-test"))
    dokka(project(":featureflag-android"))
    dokka(project(":featureflag-bridge"))
    dokka(project(":featureflag-ui"))
}

// The published API, frozen in featureflag-*/api/*.api. Update it on purpose, with the change that needs it:
// ./gradlew apiDump releaseApiDump
apiValidation {
    // Nothing published: the BOM has no classes, the samples are apps and a sample-only library, and
    // docs-samples is the docs' tests.
    ignoredProjects.addAll(listOf("featureflag-bom", "docs-samples", "signals", "host", "companion"))
    // Compose's holders for lambdas without captures: public in bytecode, internal in Kotlin, and renamed
    // whenever a screen's lambdas change.
    ignoredClasses.addAll(
        listOf(
            "BrowserScreenKt",
            "CompanionShellKt",
            "DetailScreenKt",
            "FlagControlsKt",
            "FlagRowKt",
            "FlagSectionHeaderKt",
            "FlagSheetKt",
            "FlagTextFieldKt",
            "ImportSheetKt",
            "ListEditorScreenKt",
            "OverridesScreenKt",
            "QrSheetKt",
            "RecordsScreenKt",
            "SignalsScreenKt",
            "StatusScreensKt",
        ).map { "com.andyyhope.featureflag.ui.screens.ComposableSingletons\$$it" },
    )
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
