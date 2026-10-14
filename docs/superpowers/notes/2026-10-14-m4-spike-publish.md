# Publish spike findings (vanniktech 0.37.0 + Dokka 2.2.0, Gradle 9.5.1 / AGP 9.1.1 / Kotlin 2.2.10)

Working copy: scratchpad/spike-publish (git archive of m4-samples HEAD + the changes below). Everything worked first try; no errors hit.
`-Dmaven.repo.local=...` IS honoured (real ~/.m2/repository/com/andyyhope never created, so no cleanup needed).
`gpg` is not installed on this machine; the throwaway key was made with a BouncyCastle Java program (scratchpad/keygen/KeyGen.java, run with Gradle's own bcpg-1.84 jars).

## Files changed (exact)
gradle/libs.versions.toml:
- versions: `vanniktechPublish = "0.37.0"`, `dokka = "2.2.0"`
- libraries: `vanniktech-publish-plugin = { module = "com.vanniktech:gradle-maven-publish-plugin", version.ref = "vanniktechPublish" }`,
  `dokka-gradle-plugin = { module = "org.jetbrains.dokka:dokka-gradle-plugin", version.ref = "dokka" }`
- plugins: `vanniktech-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechPublish" }`, `dokka = { id = "org.jetbrains.dokka", version.ref = "dokka" }`

build-logic/build.gradle.kts: `implementation(libs.vanniktech.publish.plugin)`, `implementation(libs.dokka.gradle.plugin)`;
`register("publish") { id = "featureflag.publish"; implementationClass = "PublishConventionPlugin" }`

settings.gradle.kts: `include(":featureflag-bom")`

Each of the 6 modules: `id("featureflag.publish")` as the LAST entry of plugins {} (core: after `alias(libs.plugins.ksp)`; ui: after featureflag.compose).

Root build.gradle.kts:
```kotlin
plugins {
    // ...existing apply-false entries...
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.dokka)          // NOT apply false: root aggregates
}
dependencies {
    listOf("core", "ksp", "test", "android", "bridge", "ui").forEach { dokka(project(":featureflag-$it")) }
}
```

featureflag-bom/build.gradle.kts:
```kotlin
plugins {
    `java-platform`
    id("featureflag.publish")
}
dependencies {
    constraints {
        listOf("core", "ksp", "test", "android", "bridge", "ui").forEach { api(project(":featureflag-$it")) }
    }
}
```

build-logic/src/main/kotlin/PublishConventionPlugin.kt (essential; the real file is formatted multi-line):
```kotlin
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        if (!pluginManager.hasPlugin("java-platform")) {
            pluginManager.apply("org.jetbrains.dokka")
        }
        pluginManager.apply("com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set(project.name)
                description.set("Feature flags for Android: ${project.name}")
                inceptionYear.set("2026")
                url.set(REPO_URL)
                licenses { license { name.set("The MIT License"); url.set("https://opensource.org/licenses/MIT"); distribution.set("repo") } }
                developers { developer { id.set("andyyhope"); name.set("Andy Hope"); url.set("https://github.com/andyyhope/") } }
                scm {
                    url.set(REPO_URL)
                    connection.set("scm:git:git://github.com/andyyhope/FeatureFlag-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/andyyhope/FeatureFlag-android.git")
                }
            }
        }
    }
    private companion object { const val REPO_URL = "https://github.com/andyyhope/FeatureFlag-android/" }
}
```
Imports: com.vanniktech.maven.publish.MavenPublishBaseExtension, org.gradle.api.Plugin/Project, org.gradle.kotlin.dsl.configure.
`publishToMavenCentral()` was NOT called (not needed for Maven Local); add it for the Central release step.
The repo has no LICENSE file (git archive showed none); add the MIT one.

## publishToMavenLocal (0.1.0-SNAPSHOT: 2m37s cold, OK; -PVERSION_NAME=0.1.0 with no key: OK, 4s)
Per artifact (same shape for both versions):
- featureflag-core, -ksp, -test: .jar, -sources.jar, -javadoc.jar, .pom, .module
- featureflag-android, -bridge, -ui: .aar, -sources.jar, -javadoc.jar, .pom, .module
- featureflag-bom: .pom + .module ONLY (java-platform, packaging pom; no sources/javadoc jar). Dokka correctly skipped via hasPlugin guard.
Android single variant: .module variants are releaseVariantReleaseApiPublication / RuntimePublication / SourcePublication, i.e. `release` is picked automatically; the aar contains classes.jar.
Javadoc jars are Dokka-generated HTML and non-empty (core 729 files / 6.9 MB, android 205 files, ksp 71 files).

POM (bridge): packaging aar; name, description, url, inceptionYear; MIT licence (+distribution repo); developer andyyhope / Andy Hope; scm connection, developerConnection, url;
deps: featureflag-android compile (from `api`), kotlin-stdlib 2.2.10 compile.
ksp POM: kotlin-stdlib compile; symbol-processing-api, kotlinpoet-jvm, kotlinpoet-ksp runtime (from `implementation`).
BOM POM: `<packaging>pom</packaging>`, `<dependencyManagement>` listing the six modules at the version, plus licence/scm/developers.
All POMs carry the standard "do_not_remove: published-with-gradle-metadata" comment.

## Signing
Throwaway RSA-2048 key, passphrase "dummy"; env `ORG_GRADLE_PROJECT_signingInMemoryKey=<armored key>` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=dummy`. KeyId is NOT needed for a single-key ring.
`-PVERSION_NAME=0.1.0`: BUILD SUCCESSFUL, 32 .asc files: pom, module, jar/aar, sources, javadoc for all 6 modules, plus pom + module for the BOM.
Config cache ON (default): entry stored on the first signed run and "Configuration cache entry reused" on the second: signing does NOT break the config cache.
Without a key and with a release version, the conditional signAllPublications() keeps publishToMavenLocal working (unsigned).

## Dokka aggregate
`./gradlew :dokkaGeneratePublicationHtml` OK (13s cold), no Dokka warnings or errors in the log. #4415 not hit (publish plugin applied last), #4499 avoided (no source-set name referenced).
build/dokka/html has featureflag-{android,bridge,core,ksp,test,ui}/. Real public class names: bridge has `FlagBridgeProvider` (-flag-bridge-provider/), ui has `FlagCompanion` (-flag-companion.html) plus FlagCompanionScope / FlagCompanionTab. Android-module pages present.
KSP internals: the processor classes are `internal`, so Dokka already omits them; only the public `FeatureFlagProcessorProvider` is listed.
Suppression test: `dokka { dokkaSourceSets.configureEach { suppress.set(true) } }` in featureflag-ksp removes its pages from the aggregate but ALSO makes its published javadoc jar empty (manifest only, 2 entries). Reverted. To keep ksp out of the docs site, drop it from the root `dokka(...)` list instead.

## build detekt
`./gradlew build detekt` green (46s) with all of this applied; root detekt lints PublishConventionPlugin.kt and the bom script. Second run: "Configuration cache entry reused", 1s.

## Gotchas
- Gradle prints "Deprecated Gradle features were used ... incompatible with Gradle 10" (not investigated which plugin).
- BOM has no sources/javadoc jar; Central acceptance of a pom-packaging BOM is still unverified (expected fine).
- Apply featureflag.publish last in each module (Dokka ordering, #4415).
