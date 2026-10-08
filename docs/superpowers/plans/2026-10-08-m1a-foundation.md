# M1a Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Gradle build and the Swift golden-fixture generator, then port FeatureFlag 0.12.0's keys, value types, strict JSON reader, Apple-identical JSON writer, dates, base64, codecs and records runtime to `featureflag-core`, each pinned byte-for-byte against fixtures Apple's own code produced.

**Architecture:** A SwiftPM `fixture-generator/` drives the real FeatureFlag 0.12.0 (by URL) through its public API and writes every golden file to `fixtures/`; Kotlin tests read them through the `featureflag.fixtures` system property. `featureflag-core` grows bottom-up in four packages that mirror the contract's directories: `values` (keys, types, boxes, codecs), `json` (reader, writer, number/key/date/base64 formats), `records` (record shapes and their JSON text) and `errors`. Nothing above records (schema, pole, transport) is in scope.

**Tech Stack:** Gradle 9.5.1 (Kotlin DSL, included `build-logic`), Kotlin 2.2.10 on JDK 21 emitting Java 17 bytecode, JUnit 4 + kotlin-test, detekt 1.23.8 + detekt-formatting, Swift 6.4 / SwiftPM with FeatureFlag 0.12.0.

**Spec:** docs/superpowers/specs/2026-10-08-android-parity-design.md (+ contract docs/superpowers/plans/2026-10-08-m1-overview.md)

## Global Constraints

- Group `com.andyyhope.featureflag`. Base package `com.andyyhope.featureflag`. Version `0.1.0-SNAPSHOT` in `gradle.properties` (`GROUP`, `VERSION_NAME`).
- **Packages follow directories.** Each directory under `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/` is its own package: `com.andyyhope.featureflag.values`, `.json`, `.records`, `.errors`, `.schema`, `.container`, `.sources`, `.pole`, `.transport`, `.remote`, `.audit` and `.signals`. Only `Annotations.kt` (and M1a's `FeatureFlag.kt`) sit in the root package `com.andyyhope.featureflag`. Tests mirror the same packages, plus `com.andyyhope.featureflag.golden` and `.scenario`. detekt's `InvalidPackageDeclaration` enforces this. Every import, and every FQN the KSP processor writes into generated code, uses these packages.
- Modules: `:featureflag-core` (JVM), `:featureflag-ksp` (JVM), `:featureflag-test` (JVM). Android modules are M2, not M1.
- Gradle 9.5.1 (wrapper copied from `../yewki/android`, with no `gradle` binary needed). Kotlin 2.2.10. KSP **2.2.10-2.0.2**, which must match Kotlin; never use the standalone 2.3.x line. kotlinx-coroutines 1.10.2. JUnit 4.13.2 with `kotlin-test-junit`. Turbine 1.2.1. KotlinPoet 2.2.0 (+ `kotlinpoet-ksp`). kctfork **0.8.0** (`dev.zacsweers.kctfork:core` + `:ksp`, built on Kotlin 2.2.0 / KSP 2.2.0-2.0.2). detekt 1.23.8 with `detekt-formatting`, config copied from `../yewki/android/config/detekt/detekt.yml`.
- **JDK:** compile with `jvmToolchain(21)` and emit Java 17 bytecode (`jvmTarget = JVM_17`, `-Xjdk-release=17`). The daemon JVM is pinned to 21 by `gradle/gradle-daemon-jvm.properties`, copied from yewki. **Every Gradle command runs with** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. The system `java` is 26, which Gradle 9.5.1 does not run on, and no JDK 17 is installed.
- Every library module uses `explicitApi()` and `allWarningsAsErrors = true`.
- `featureflag-core` has exactly one runtime dependency: `api(kotlinx-coroutines-core)`. No kotlinx-serialization, no reflection, no Android.
- JDK APIs allowed in core: `java.time.Instant`, `java.util.GregorianCalendar`, `java.util.TimeZone`, `java.net.URI`, `java.math.BigDecimal`, `java.util.zip.Deflater`/`Inflater`, `java.io.File`.
- Error messages are ported **verbatim** from `Sources/FeatureFlag/ErrorMessages.swift`. Only Apple-specific nouns are reworded: UserDefaults becomes "the store", and messages for PLIST, App Group and Core Image are dropped along with those features.
- Golden fixtures live in `fixtures/` at the repo root. They are produced only by `fixture-generator/`, a SwiftPM package pinned to `FeatureFlag` **0.12.0** by URL, and are never hand-edited. Core tests read them via the system property `featureflag.fixtures`. The generator's case lists are M1a Task A2's; the remote, audit and message tables in M1d Tasks D2, D4 and D5 are the same lists, and a case is changed on both sides at once.
- `fixtures-android/` is committed. Tests only compare against it; `QRCodeFixturesTest` rewrites it only when Gradle runs with `-Pfeatureflag.writeAndroidFixtures=true` (M1c Task C5), so a plain build never writes into the source tree.
- A task that changes a file an earlier task created shows that change as an explicit **Modify** step with the exact code: a whole-file replacement, or an `In <path>, replace … with …` pair whose first block appears exactly once in the file.
- Commits use the conventional style already in the repo, and every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Before a task is called done: `./gradlew build detekt` is green, and every test the task names passes.

## Review Focus

1. **The device locale** (Turkish dotless i in key encoding; Arabic, Persian or Devanagari digits and Buddhist or Japanese calendars in dates and numbers) silently changing keys and wire text on real phones. Pinned by `FlagKeyTest.encodingIgnoresTheDeviceLocale` (Task A3) and `IsoDateTest.outputIgnoresTheDeviceLocale` (Task A8).
2. **Hand-edited or tool-written JSON** (a trailing comma, a UTF-8 byte order mark) that Apple accepts and a textbook-strict reader rejects, so a config applies on iOS and is refused whole on Android. Pinned by `JsonReaderTest.appleExtensionsAreAccepted` and the `json/parse.tsv` rows (Task A5).
3. **Backend URLs with spaces, quotes or non-ASCII paths**, which `URL(string:)` percent-encodes and `java.net.URI` throws on, again rejecting a whole payload on Android only. Pinned by `BoxJsonTest.stringsDataDatesAndUrlsComeOnlyFromValidText` and `UrlGoldenTest` (Task A9).
4. **"Never" dates** (`Instant.MAX`, `Instant.MIN`, far-future sentinels) as a date flag's default, which would throw `ArithmeticException` while writing a schema or export if formatting went through epoch milliseconds unclamped. Pinned by `IsoDateTest.theExtremesClampInsteadOfThrowing` (Task A8).
5. **Numeric edge payloads**: an integer ID above 2^53 sent to a double flag (Apple refuses it), `3.0` sent to an int flag (Apple accepts it), `1e39` sent to a float record key (becomes infinity and must not crash the duplicate-key check), and 100,000 nested `[` (must not overflow the stack). Pinned by `BoxJsonTest.doublesTakeIntegersOnlyWhenADoubleHoldsThemExactly` and `intsComeOnlyFromIntegralNumbersInRange` (Task A9), `RecordJsonTest.aFloatKeyThatOverflowsDoesNotCrashTheDuplicateCheck` (Task A11) and `JsonReaderTest.nestingStopsAt513` (Task A5).

---

## Conventions for every task

- **Packages follow directories.** `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/` is package `com.andyyhope.featureflag.values`; likewise `.json`, `.records` and `.errors`. detekt's `InvalidPackageDeclaration` enforces this, and later sub-plans import from these packages.
- **Commands** run from the repo root, `/Users/andyyhope/Development/feature-flags-android`, and every Gradle command is preceded by `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- **Lines stay at or under 120 characters.** detekt-formatting's wrapping rules fire past that even though `MaximumLineLength` is off.
- **Fixtures are never edited.** If a golden test fails, fix the Kotlin. `fixtures/` changes only by running `scripts/sync-fixtures.sh`.
- **Every code block below was compiled and run.** Tasks A1 to A11 were replayed in order in a fresh scratch project: each Step 2 failed as stated, and each Step 4 ended with `./gradlew build detekt` green (130 core tests at the end). The generator compiled against FeatureFlag 0.12.0 fetched from GitHub and wrote the 129 files listed in Task A2.

### Measured behaviour this plan adds to the contract

These come from probing FeatureFlag 0.12.0 on this machine while writing the plan. They extend or correct the overview's probe facts. Do not "fix" the code back to the overview's wording.

- **JSON reading** (`JsonReader`, Task A5): Apple also accepts one trailing comma (`[1,]`, `{"a":1,}`) and a leading UTF-8 byte order mark, rejects nesting deeper than 513, and reads a *negative* overflow (`-1e400`) as -Infinity while rejecting `1e400`. A float literal is `Integer` when integral and within [-2^63, 2^63], with 2^63 clamping to `Long.MAX_VALUE` exactly as `NSNumber as? Int` does. UTF-16/32 input, and literals Apple routes through `NSDecimalNumber` (over 17 significant digits, or beyond its exponent range), are documented deviations.
- **Box conversion** (`toBox`, Task A9): a JSON integer becomes a `Double` only when a double holds it exactly (`9007199254740993` is refused, like `NSNumber as? Double`). `-0.0` reads as `Integer(0)`, so a double flag gets `0.0` where Apple keeps `-0.0`; this is pinned as a deviation. URLs follow `URL(string:)`'s percent-encoding of invalid characters, checked against a new `url/parse.tsv` fixture.
- **Dates** (`IsoDate`, Task A8): years of 4 to 7 digits round-trip (Apple writes `10000-01-01T00:00:00Z`), year `0000` is 1 BC, a day in the 1582 cutover gap rolls forward as Apple's does, and formatting clamps to ICU's range (`5828963-12-20T00:00:00Z` and BC `5838270-09-20T00:00:00Z`).
- **Base64** (`StrictBase64`, Task A8): besides the overview's rule, Apple accepts a final quad `xx=x` (two bytes: the first byte and the last sextet) and any run of four or more `=` alone (one zero byte).
- **Fixture additions** (Task A2): `url/parse.tsv`; `remote/<case>.expected.json` also carries `"values"` (the applied boxes as JSON) and each problem's `"remoteKey"`; `audit/<case>.strict.txt` and `audit/<case>.defaults.txt`; messages are named `<SwiftErrorType>.<case>[.<variant>].txt`. The remote (22), audit (8) and message (27) case lists are exactly the tables in M1d Tasks D2, D4 and D5, which read them.

---

### Task A1: Gradle scaffold

**Files:**
- Copy (from `/Users/andyyhope/Development/yewki/android`, verbatim): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/gradle-daemon-jvm.properties`, `config/detekt/detekt.yml`
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt`, `featureflag-core/build.gradle.kts`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/FeatureFlag.kt`, `featureflag-ksp/build.gradle.kts`, `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessorProvider.kt`, `featureflag-test/build.gradle.kts`, `featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/TestFeatureFlags.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/SmokeTest.kt`, `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/SmokeTest.kt`, `featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/SmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - the convention plugin `id("featureflag.jvm-library")`, which applies Kotlin/JVM with `jvmToolchain(21)`, `jvmTarget = JVM_17`, `-Xjdk-release=17`, `explicitApi()` and `allWarningsAsErrors`, and adds `kotlin-test-junit`, `junit`, `kotlinx-coroutines-test` and `turbine` to `testImplementation`;
  - for every `Test` task, the system property `featureflag.fixtures` (the absolute path of `<root>/fixtures`), with that directory's files registered as a task input;
  - version-catalog accessors `libs.kotlinx.coroutines.core`, `libs.ksp.api`, `libs.kotlinpoet`, `libs.kotlinpoet.ksp`, `libs.kctfork.core`, `libs.kctfork.ksp`, `libs.detekt.formatting` and plugin aliases `libs.plugins.kotlin.jvm`, `libs.plugins.ksp`, `libs.plugins.detekt`;
  - modules `:featureflag-core` (with `api(libs.kotlinx.coroutines.core)` only), `:featureflag-ksp` and `:featureflag-test` (with `api(project(":featureflag-core"))`);
  - `public object FeatureFlag { public const val VERSION: String = "0.1.0-SNAPSHOT" }` in `com.andyyhope.featureflag`.

- [ ] **Step 1: Copy the wrapper and detekt config, and write the failing smoke tests**

```bash
cd /Users/andyyhope/Development/feature-flags-android
YEWKI=/Users/andyyhope/Development/yewki/android
mkdir -p gradle/wrapper config/detekt
cp "$YEWKI/gradlew" "$YEWKI/gradlew.bat" .
cp "$YEWKI"/gradle/wrapper/* gradle/wrapper/
cp "$YEWKI/gradle/gradle-daemon-jvm.properties" gradle/
cp "$YEWKI/config/detekt/detekt.yml" config/detekt/
```

The core smoke test also proves the fixtures property reaches tests, and that coroutine test tooling is on the classpath.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/SmokeTest.kt`:

```kotlin
package com.andyyhope.featureflag

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SmokeTest {
    @Test
    fun versionIsExposed() {
        assertEquals("0.1.0-SNAPSHOT", FeatureFlag.VERSION)
    }

    @Test
    fun fixturesPathIsPassedToTests() {
        assertNotNull(System.getProperty("featureflag.fixtures"))
    }

    @Test
    fun coroutineTestToolingIsOnTheClasspath() = runTest {
        flowOf(FeatureFlag.VERSION).test {
            assertEquals(FeatureFlag.VERSION, awaitItem())
            awaitComplete()
        }
    }
}
```

The KSP smoke test runs a placeholder processor under kctfork with KSP2, so the KSP + KotlinPoet + kctfork pipeline is proven before M1b depends on it.

`featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/SmokeTest.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class SmokeTest {
    @Test
    fun processorGeneratesMarkerUnderKctfork() {
        val compilation = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Sample.kt", "class Sample"))
            inheritClassPath = true
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += FeatureFlagProcessorProvider()
            }
        }

        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("${FeatureFlagProcessor.PACKAGE}.${FeatureFlagProcessor.MARKER}")
    }
}
```

`featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/SmokeTest.kt`:

```kotlin
package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.FeatureFlag
import org.junit.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun coreVersionMatchesCore() {
        assertEquals(FeatureFlag.VERSION, TestFeatureFlags.CORE_VERSION)
    }
}
```

- [ ] **Step 2: Run the build to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: FAIL with `Directory '/Users/andyyhope/Development/feature-flags-android' does not contain a Gradle build.`

- [ ] **Step 3: Write the build**

`.gitignore`:

```gitignore
# Gradle
.gradle/
build/

# Android Studio / IntelliJ
.idea/
*.iml

# Local config — never commit
local.properties

# OS
.DS_Store

# Kotlin build session caches
.kotlin/

# Swift fixture generator (SwiftPM build output + workspace state)
fixture-generator/.build/
fixture-generator/.swiftpm/
```

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "feature-flags-android"

include(":featureflag-core")
include(":featureflag-ksp")
include(":featureflag-test")
```

`gradle.properties`:

```properties
GROUP=com.andyyhope.featureflag
VERSION_NAME=0.1.0-SNAPSHOT

org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
# Build independent module tasks in parallel.
org.gradle.parallel=true
# Cache the configuration phase so repeat invocations skip re-configuring every module.
org.gradle.configuration-cache=true
kotlin.code.style=official
```

`gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.2.10"
# KSP version must track Kotlin (2.2.10).
ksp = "2.2.10-2.0.2"
kotlinxCoroutines = "1.10.2"
junit = "4.13.2"
turbine = "1.2.1"
kotlinpoet = "2.2.0"
kctfork = "0.8.0"
# detekt static analysis + its ktlint-backed formatting ruleset.
detekt = "1.23.8"

[libraries]
# Build-logic classpath (convention plugins).
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }

kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }

ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }
kotlinpoet = { module = "com.squareup:kotlinpoet", version.ref = "kotlinpoet" }
kotlinpoet-ksp = { module = "com.squareup:kotlinpoet-ksp", version.ref = "kotlinpoet" }

kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
junit = { module = "junit:junit", version.ref = "junit" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
kctfork-core = { module = "dev.zacsweers.kctfork:core", version.ref = "kctfork" }
kctfork-ksp = { module = "dev.zacsweers.kctfork:ksp", version.ref = "kctfork" }

detekt-formatting = { module = "io.gitlab.arturbosch.detekt:detekt-formatting", version.ref = "detekt" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

The root build applies detekt to every subproject, mirroring yewki's wiring without its custom rules module, and also runs detekt over `build-logic` and every `*.gradle.kts` on the root project.

`build.gradle.kts`:

```kotlin
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
```

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`:

```kotlin
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
```

`inputs.files(fixturesDir.asFileTree)` rather than `inputs.dir(...)`: a file tree is simply empty when `fixtures/` does not exist yet, so the build stays green before Task A2, and editing any fixture re-runs the tests.

`build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt`:

```kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** `featureflag.jvm-library` — baseline config for every Kotlin/JVM library module. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        // Build on JDK 21, emit bytecode + API surface for JDK 17 consumers.
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(JDK_TOOLCHAIN)
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-Xjdk-release=$JDK_RELEASE")
            }
        }
        // Keep javac on the same target as kotlinc so Kotlin's JVM-target validation passes.
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(JDK_RELEASE)
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf("kotlin-test-junit", "junit", "kotlinx-coroutines-test", "turbine").forEach { alias ->
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }

        // Shared on-disk fixtures (the repo-root `fixtures/` dir) are handed to tests by path, and
        // registered as a task input so editing a fixture re-runs the tests instead of leaving them
        // UP-TO-DATE. A file tree, not `inputs.dir(...)`: it is simply empty when the dir is absent.
        val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")
        tasks.withType<Test>().configureEach {
            systemProperty("featureflag.fixtures", fixturesDir.asFile.absolutePath)
            inputs.files(fixturesDir.asFileTree)
                .withPropertyName("featureflagFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    private companion object {
        const val JDK_TOOLCHAIN = 21
        const val JDK_RELEASE = 17
    }
}
```

`featureflag-core/build.gradle.kts`:

```kotlin
plugins {
    id("featureflag.jvm-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/FeatureFlag.kt`:

```kotlin
package com.andyyhope.featureflag

/** Entry point of the feature-flag runtime. */
public object FeatureFlag {
    /** The library version. */
    public const val VERSION: String = "0.1.0-SNAPSHOT"
}
```

`featureflag-ksp/build.gradle.kts`:

```kotlin
plugins {
    id("featureflag.jvm-library")
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
}
```

`featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessorProvider.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/** KSP entry point for the feature-flag code generator. */
public class FeatureFlagProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeatureFlagProcessor(environment.codeGenerator)
}

/** Placeholder processor: emits one marker object so the KSP + KotlinPoet pipeline is exercised. */
internal class FeatureFlagProcessor(private val codeGenerator: CodeGenerator) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!generated) {
            generated = true
            FileSpec.builder(PACKAGE, MARKER)
                .addType(TypeSpec.objectBuilder(MARKER).build())
                .build()
                .writeTo(codeGenerator, Dependencies(aggregating = false))
        }
        return emptyList()
    }

    internal companion object {
        const val PACKAGE = "com.andyyhope.featureflag.generated"
        const val MARKER = "FeatureFlagProcessorMarker"
    }
}
```

`featureflag-test/build.gradle.kts`:

```kotlin
plugins {
    id("featureflag.jvm-library")
}

dependencies {
    api(project(":featureflag-core"))
}
```

`featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/TestFeatureFlags.kt`:

```kotlin
package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.FeatureFlag

/** Test helpers for consumers of featureflag-core. */
public object TestFeatureFlags {
    /** The featureflag-core version these helpers were built against. */
    public const val CORE_VERSION: String = FeatureFlag.VERSION
}
```

- [ ] **Step 4: Run the build to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`, with 5 tests passing (core 3, ksp 1, test 1) and detekt reporting nothing. The first run downloads kctfork, KotlinPoet, Turbine and the Kotlin 2.2.0 compiler jars kctfork brings; that is expected. One Gradle 10 deprecation warning comes from detekt 1.23.8 itself (`ReportingExtension.file(String)`) and is not ours.

- [ ] **Step 5: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle config build-logic \
  featureflag-core featureflag-ksp featureflag-test
git commit -m "$(cat <<'EOF'
Add the Gradle scaffold, convention plugin and three modules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A2: Fixture generator

**Files:**
- Create: `fixture-generator/Package.swift`, `fixture-generator/Sources/GoldenModel/GoldenFlags.swift`, `fixture-generator/Sources/GenerateFixtures/main.swift`, `fixture-generator/Sources/GenerateFixtures/FixtureWriter.swift`, `fixture-generator/Sources/GenerateFixtures/ManifestFixtures.swift`, `fixture-generator/Sources/GenerateFixtures/JsonFixtures.swift`, `fixture-generator/Sources/GenerateFixtures/DateFixtures.swift`, `fixture-generator/Sources/GenerateFixtures/EncodingFixtures.swift`, `fixture-generator/Sources/GenerateFixtures/TransportFixtures.swift`, `fixture-generator/Sources/GenerateFixtures/RemoteFixtures.swift`, `fixture-generator/Sources/GenerateFixtures/MessageFixtures.swift`, `fixture-generator/Sources/DecodeKotlinFixtures/main.swift`, `scripts/sync-fixtures.sh`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/Fixtures.kt`
- Generated (by the script, then committed): `fixtures/` (129 files), `fixture-generator/Package.resolved`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/FixturesTest.kt`

**Interfaces:**
- Consumes: the `featureflag.fixtures` system property from Task A1.
- Produces:
  - the SwiftPM products `generate-fixtures <output-dir>` and `decode-kotlin-fixtures [fixtures-android-dir]` (a stub that M1c fills in), and the library target `GoldenModel` holding the contract's `GoldenFlags` verbatim;
  - `scripts/sync-fixtures.sh`, the only writer of `fixtures/`;
  - in the core test source set, package `com.andyyhope.featureflag`: `object Fixtures { fun bytes(path: String): ByteArray; fun text(path: String): String; fun tsv(path: String): List<List<String>>; fun lines(path: String): List<String>; fun unescape(field: String): String }`. `tsv` drops the final newline, splits rows on `\n` and fields on tab, and applies the TSV unescape rule. `lines` is for the `.txt` lists, which have no trailing newline;
  - every file in the contract's fixture layout table, including `url/parse.tsv`. The remote, audit, schema, export, QR and message files serve M1b to M1d: their names are the ones `GoldenSchemaTest` (B2), `ExportGoldenTest` and `QRCodeFixturesTest` (C4, C5), and `RemoteGoldenFixtureTest`, `AuditGoldenFixtureTest` and `ErrorMessageTest` (D2, D4, D5) read.

- [ ] **Step 1: Write the failing test and the fixture reader**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/Fixtures.kt`:

```kotlin
package com.andyyhope.featureflag

import java.io.File

/**
 * Reads the golden fixtures in the repo-root `fixtures/` directory, which Gradle passes to
 * every test task as the `featureflag.fixtures` system property.
 */
object Fixtures {

    private val root: File by lazy {
        val path = checkNotNull(System.getProperty("featureflag.fixtures")) {
            "featureflag.fixtures is not set; run the tests through Gradle"
        }
        File(path).also { check(it.isDirectory) { "No fixtures at $it; run scripts/sync-fixtures.sh" } }
    }

    fun bytes(path: String): ByteArray {
        val file = File(root, path)
        check(file.isFile) { "Missing fixture $path; run scripts/sync-fixtures.sh" }
        return file.readBytes()
    }

    fun text(path: String): String = bytes(path).decodeToString()

    /** Rows of a `.tsv` fixture, every field unescaped with [unescape]. */
    fun tsv(path: String): List<List<String>> {
        val text = text(path)
        if (text.isEmpty()) return emptyList()
        return text.removeSuffix("\n").split("\n").map { line -> line.split("\t").map(::unescape) }
    }

    /** Lines of a `.txt` fixture written without a trailing newline. */
    fun lines(path: String): List<String> = text(path).split("\n")

    /** The TSV escaping rule shared with the Swift generator: `\\`, `\t`, `\n` and `\u00xx`. */
    fun unescape(field: String): String = buildString {
        var index = 0
        while (index < field.length) {
            val char = field[index]
            if (char != '\\') {
                append(char)
                index++
                continue
            }
            when (val escaped = field[index + 1]) {
                '\\' -> append('\\')
                't' -> append('\t')
                'n' -> append('\n')
                'u' -> append(field.substring(index + 2, index + 6).toInt(16).toChar())
                else -> error("Unknown TSV escape \\$escaped in $field")
            }
            index += if (field[index + 1] == 'u') 6 else 2
        }
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/FixturesTest.kt`:

```kotlin
package com.andyyhope.featureflag

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixturesTest {

    @Test
    fun manifestNamesTheReferenceVersion() {
        assertTrue(Fixtures.text("manifest.json").contains("\"featureFlagVersion\" : \"0.12.0\""))
    }

    @Test
    fun tsvFieldsAreUnescaped() {
        assertEquals("a\\b\tc\nd\u0001e", Fixtures.unescape("a\\\\b\\tc\\nd\\u0001e"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.FixturesTest' --console=plain
```

Expected: FAIL. `manifestNamesTheReferenceVersion` throws `IllegalStateException: No fixtures at /Users/andyyhope/Development/feature-flags-android/fixtures; run scripts/sync-fixtures.sh`.

- [ ] **Step 3: Write the generator, then run it**

The package depends on FeatureFlag by URL, pinned exactly, so the iOS repo is never touched.

`fixture-generator/Package.swift`:

```swift
// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "fixture-generator",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "generate-fixtures", targets: ["GenerateFixtures"]),
        .executable(name: "decode-kotlin-fixtures", targets: ["DecodeKotlinFixtures"]),
    ],
    dependencies: [
        .package(url: "https://github.com/andyyhope/FeatureFlag.git", exact: "0.12.0"),
    ],
    targets: [
        .target(
            name: "GoldenModel",
            dependencies: [.product(name: "FeatureFlag", package: "FeatureFlag")]
        ),
        .executableTarget(
            name: "GenerateFixtures",
            dependencies: ["GoldenModel", .product(name: "FeatureFlag", package: "FeatureFlag")]
        ),
        .executableTarget(
            name: "DecodeKotlinFixtures",
            dependencies: ["GoldenModel", .product(name: "FeatureFlag", package: "FeatureFlag")]
        ),
    ]
)
```

`GoldenFlags.swift` is the contract's golden model, verbatim. It compiles unchanged against 0.12.0.

`fixture-generator/Sources/GoldenModel/GoldenFlags.swift`:

```swift
import FeatureFlag
import Foundation

@FlagContainer(description: "Every flag type, for cross-platform golden fixtures")
public struct GoldenFlags {
    @Flag(default: false, description: "A boolean", remoteKey: "toggles.boolean") public var boolFlag: Bool
    @Flag(default: 42, description: "An integer", remoteKey: "numbers.int") public var intFlag: Int
    @Flag(default: 0.1, description: "A double", remoteKey: "numbers.double") public var doubleFlag: Double
    @Flag(default: Float(0.1), description: "A float") public var floatFlag: Float
    @Flag(default: "hello/world \"quoted\"", description: "A string", remoteKey: "strings.plain") public var stringFlag: String
    @Flag(default: Data([0, 1, 2, 254, 255]), description: "Some bytes") public var dataFlag: Data
    @Flag(default: Date(timeIntervalSince1970: 1_767_225_600), description: "A date", remoteKey: "dates.launch") public var dateFlag: Date
    @Flag(default: URL(string: "https://example.com/a/b?c=d")!, description: "A URL", remoteKey: "config.endpoint") public var urlFlag: URL
    @Flag(default: false, description: "Uses an acronym") public var useHTTPSOnly: Bool
    @Flag(default: ["AU", "NZ"], description: "Markets", remoteKey: "config.markets") public var markets: [String]
    @Flag(default: [[true], [false, true]], description: "Nested arrays") public var matrix: [[Bool]]
    @Flag(default: ["b": 2, "a": 1, "v10": 10, "v2": 2], description: "Limits by name") public var limits: [String: Int]
    @Flag(default: GoldenTier.free, description: "A string enum", remoteKey: "tier") public var tier: GoldenTier
    @Flag(default: GoldenLevel.two, description: "An int enum") public var level: GoldenLevel
    @Flag(default: [GoldenEndpoint(name: "prod", url: URL(string: "https://prod.example.com")!)],
          description: "Endpoints", remoteKey: "config.endpoints") public var endpoints: FlagRecords<GoldenEndpoint>
    @FlagGroup(description: "Checkout") public var checkout: GoldenCheckout
}
@FlagContainer public struct GoldenCheckout {
    @Flag(default: true, description: "Offer Apple Pay", remoteKey: "featureToggles.checkout.applePay") public var applePay: Bool
    @Flag(default: GoldenTier.pro, description: "Tier at checkout") public var tier: GoldenTier
    @FlagGroup(description: "Express checkout") public var express: GoldenExpress
}
@FlagContainer public struct GoldenExpress {
    @Flag(default: false, description: "One-tap purchase") public var oneTap: Bool
    @Flag(default: 3.0, description: "Seconds before confirming") public var confirmationDelay: Double
}
public enum GoldenTier: String, FlagValue, CaseIterable, FlagValueCases { case free, pro, enterprise }
public enum GoldenLevel: Int, FlagValue, CaseIterable, FlagValueCases { case one = 1, two = 2, three = 3 }
@FlagRecord public struct GoldenEndpoint {
    @FlagRecordKey public var name: String
    public var url: URL
    public var weight: Int = 1
    @FlagRecordProperty(key: "memo") public var note: String?
    public var kind: GoldenTier = .free
    public var targets: FlagRecords<GoldenTarget> = []
}
@FlagRecord public struct GoldenTarget { public var host: String; public var port: Int }
```

The entry point, and the writer shared by every fixture file. `tsvEscape` is the TSV rule that `Fixtures.unescape` reverses.

`fixture-generator/Sources/GenerateFixtures/main.swift`:

```swift
import Foundation

// Writes every golden fixture the Kotlin port is tested against, using only the public
// API of FeatureFlag 0.12.0 and Foundation. Run through scripts/sync-fixtures.sh.

let arguments = CommandLine.arguments.dropFirst()
guard let outputPath = arguments.first else {
    FileHandle.standardError.write(Data("usage: generate-fixtures <output-directory>\n".utf8))
    exit(64)
}

var writer = FixtureWriter(root: URL(fileURLWithPath: outputPath, isDirectory: true))
ManifestFixtures.write(into: &writer)
JsonFixtures.write(into: &writer)
DateFixtures.write(into: &writer)
EncodingFixtures.write(into: &writer)
TransportFixtures.write(into: &writer)
RemoteFixtures.write(into: &writer)
MessageFixtures.write(into: &writer)
print("Wrote \(writer.written.count) fixture files to \(writer.root.path)")
```

`fixture-generator/Sources/GenerateFixtures/FixtureWriter.swift`:

```swift
import Foundation

/// Writes fixture files under one root and remembers what it wrote.
struct FixtureWriter {
    let root: URL
    private(set) var written: [String] = []

    init(root: URL) {
        self.root = root
    }

    mutating func write(_ relativePath: String, data: Data) {
        let url = root.appendingPathComponent(relativePath)
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try data.write(to: url, options: .atomic)
        } catch {
            fatalError("could not write \(relativePath): \(error)")
        }
        written.append(relativePath)
    }

    mutating func write(_ relativePath: String, text: String) {
        write(relativePath, data: Data(text.utf8))
    }

    /// One row per line, every line ending in `\n`, fields escaped by `tsvEscape`.
    mutating func writeTSV(_ relativePath: String, rows: [[String]]) {
        write(relativePath, text: rows.map { $0.map(tsvEscape).joined(separator: "\t") + "\n" }.joined())
    }
}

/// The TSV escaping rule shared with `Fixtures.kt`: `\` → `\\`, tab → `\t`, newline → `\n`,
/// any other C0 control → `\u00xx` (lowercase hex).
func tsvEscape(_ text: String) -> String {
    var out = ""
    for scalar in text.unicodeScalars {
        switch scalar {
        case "\\": out += "\\\\"
        case "\t": out += "\\t"
        case "\n": out += "\\n"
        default:
            if scalar.value < 0x20 {
                out += String(format: "\\u%04x", scalar.value)
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    return out
}

/// What FeatureFlag writes for records and what JsonWriter.compact must reproduce.
func appleCompact(_ object: Any) -> String {
    do {
        let data = try JSONSerialization.data(
            withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed]
        )
        return String(decoding: data, as: UTF8.self)
    } catch {
        fatalError("could not serialise \(object): \(error)")
    }
}

/// What FeatureFlag writes for schemas and exports and what JsonWriter.pretty must reproduce.
func applePretty(_ object: Any) -> Data {
    do {
        return try JSONSerialization.data(
            withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
    } catch {
        fatalError("could not serialise \(object): \(error)")
    }
}

func hexString(_ bytes: some Sequence<UInt8>) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
}

func bitsHex(_ value: Double) -> String {
    String(format: "%016llx", value.bitPattern)
}

/// SplitMix64, so every "random" case list is identical on every run.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// Keeps the first occurrence of each element, in order.
func unique<T: Hashable>(_ items: [T]) -> [T] {
    var seen = Set<T>()
    return items.filter { seen.insert($0).inserted }
}
```

`fixture-generator/Sources/GenerateFixtures/ManifestFixtures.swift`:

```swift
import Foundation

enum ManifestFixtures {
    static func write(into writer: inout FixtureWriter) {
        let manifest: [String: Any] = [
            "featureFlagVersion": "0.12.0",
            "swift": swiftVersion,
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "generatedBy": "fixture-generator",
        ]
        writer.write("manifest.json", data: applePretty(manifest))
    }

    private static var swiftVersion: String {
        #if compiler(>=6.5)
            return "6.5+"
        #elseif compiler(>=6.4)
            return "6.4"
        #elseif compiler(>=6.3)
            return "6.3"
        #elseif compiler(>=6.2)
            return "6.2"
        #elseif compiler(>=6.1)
            return "6.1"
        #else
            return "6.0"
        #endif
    }
}
```

The JSON fixtures: about 1,000 doubles (probe values, powers of ten, subnormals, nextUp/nextDown boundaries, Float-widened values and seeded random bit patterns), every number literal from the probes, every C0 control and the other string edge cases, about 700 keys for the sort order (all printable ASCII, the measured tie cases, realistic flag keys, every pair over a 13-character alphabet, and 500 seeded random keys), the pretty-print structure, and every accept/reject case for parsing. `number-parse.tsv` records `as? Int` as `integer <value>`, otherwise `real <16 hex digits of NSNumber.doubleValue>`.

`fixture-generator/Sources/GenerateFixtures/JsonFixtures.swift`:

```swift
import Foundation

/// json/*: number formatting, number classification, string escaping, key order,
/// pretty printing and parse acceptance — everything JsonReader/JsonWriter copy.
enum JsonFixtures {
    static func write(into writer: inout FixtureWriter) {
        writeNumbers(into: &writer)
        writeNumberParse(into: &writer)
        writeStrings(into: &writer)
        writeKeyOrder(into: &writer)
        writer.write("json/pretty.json", data: applePretty(prettyStructure))
        writeParse(into: &writer)
    }

    // MARK: - numbers.tsv

    static func writeNumbers(into writer: inout FixtureWriter) {
        var values: [Double] = [
            0, -0.0, 3, 0.1, 1e16, 1e-5, 1e21, 5e-324, Double(Float(0.1)),
            1, -1, 0.5, 2.5, -2.5, 100, 1e15, 1e17, 1.5e16, 9.999999999999999e16,
            99_999_999_999_999_990, 0.0001, 0.00001, 0.000123, 5e-5,
            1_234_567_890_123_456_789, 123_456_789.123, 0.1 + 0.2, 1.0 / 3, 2.0 / 3,
            Double.pi, M_E, 9_007_199_254_740_992, 9_007_199_254_740_993,
            9_223_372_036_854_775_808, 18_446_744_073_709_551_616, 1e300, -1e300, 1e-300,
            Double.greatestFiniteMagnitude, -Double.greatestFiniteMagnitude,
            Double.leastNormalMagnitude, Double.leastNonzeroMagnitude, -Double.leastNonzeroMagnitude,
            2.225_073_858_507_200_9e-308, 2 * Double.leastNonzeroMagnitude,
            (1e17).nextDown, (1e16).nextDown, (1e16).nextUp, (1e-4).nextDown, (1e-4).nextUp,
            (1e21).nextDown, (1e22).nextUp, 1e23, 8.41e21, 5e-310, 4.35, 0.035, 1.005, 2.675,
            123e18, -0.000_001, 1e-7, 7e-10,
        ]
        for exponent in -10...22 {
            values.append(Double("1e\(exponent)")!)
            values.append(-Double("1e\(exponent)")!)
        }
        let floats: [Float] = [
            0.1, 1.0 / 3, Float.pi, .greatestFiniteMagnitude, .leastNonzeroMagnitude,
            .leastNormalMagnitude, 16_777_217, 1e-10, 3.4e38, -0.3, 0.7, 1e10, 123.456,
        ]
        values += floats.map(Double.init)

        var random = SeededGenerator(seed: 0x5EED_0001)
        var bitPatterns = 0
        while bitPatterns < 400 {
            let value = Double(bitPattern: random.next())
            if value.isFinite { values.append(value); bitPatterns += 1 }
        }
        for _ in 0..<200 { values.append(Double(random.next() % 1_000_000_000) / 1000) }
        for _ in 0..<100 { values.append(Double(Int64(bitPattern: random.next()) >> (random.next() % 64))) }
        for _ in 0..<100 {
            values.append(Double(random.next() % 10_000) * pow(10, Double(Int(random.next() % 40) - 20)))
        }
        for _ in 0..<100 { values.append(Double(Float(bitPattern: UInt32(truncatingIfNeeded: random.next()) & 0x7F7F_FFFF))) }

        let rows = unique(values.map(\.bitPattern)).map(Double.init(bitPattern:)).map { value -> [String] in
            let array = appleCompact([value])
            return [bitsHex(value), String(array.dropFirst().dropLast())]
        }
        writer.writeTSV("json/numbers.tsv", rows: rows)
    }

    // MARK: - number-parse.tsv

    static let numberLiterals: [String] = [
        "0", "-0", "1", "-1", "42", "3.0", "1e2", "2.0e0", "1E2", "1e+2", "1.0e0", "0e0", "1E-0",
        "-0.0", "0.0", "-0e5", "-0.0e-0", "1e-400", "-1e-400", "0.1", "1.5", "-2.5", "123.456e-2",
        "1.0000000000000002", "0.30000000000000004", "1.5e3", "2e-1", "1e-7", "123e-20", "0.000001",
        "1e19", "10000000000000000000", "9223372036854775807", "9223372036854775808",
        "-9223372036854775808", "-9223372036854775809", "18446744073709551615", "18446744073709551616",
        "123456789012345678901234567890", "-123456789012345678901234567890", "100000000000000000000000",
        "9007199254740992", "9007199254740993", "-9007199254740993", "9007199254740993.0",
        "4611686018427387904", "4611686018427387905", "16777217",
        "9.2233720368547758E18", "-9.2233720368547758E18", "9.223372036854777e18", "-9.223372036854777e18",
        "4.5e15", "1e300", "1e308", "1.7976931348623157e308", "1.7976931348623158e308",
        "1.7976931348623159e308", "1e309", "1E400", "-1E400", "2.5e-324", "4.9e-324",
        "2.4703282292062327e-324", "2.4703282292062328e-324", "2.2250738585072011e-308",
        "2.2250738585072012e-308", "3.4028235e38", "3.5e38", "1e39",
        "1" + String(repeating: "0", count: 400), "0." + String(repeating: "0", count: 400) + "1",
        "1.", ".5", "+1", "01", "-01", "00", "-", "1e", "1e+", "0x10", "Infinity", "-Infinity", "NaN",
        "1.e5", "1e5.5", "--1", "1_000",
    ]

    static func writeNumberParse(into writer: inout FixtureWriter) {
        let rows = numberLiterals.map { literal -> [String] in
            guard
                let parsed = try? JSONSerialization.jsonObject(with: Data("[\(literal)]".utf8)) as? [Any],
                let number = parsed.first as? NSNumber
            else { return [literal, "reject", "-"] }
            // `as? Int` is exactly what FlagValueBox(jsonValue:as: .int) does.
            if let integer = parsed.first as? Int { return [literal, "integer", String(integer)] }
            return [literal, "real", bitsHex(number.doubleValue)]
        }
        writer.writeTSV("json/number-parse.tsv", rows: rows)
    }

    // MARK: - strings

    static var stringCases: [[UInt32]] {
        var cases: [[UInt32]] = [[]]
        cases += (0x00...0x1F).map { [UInt32($0)] }
        cases += [
            [0x7F], [0x80], [0x9F], [0xA0], [0xE9], [0x2028], [0x2029], [0xFEFF], [0xFFFD], [0xFFFF],
            [0x1F600], [0x10FFFF], [0x22], [0x5C], [0x2F], [0x27], [0x3C, 0x2F, 0x73, 0x3E],
            Array("a\"b\\c/d".unicodeScalars.map(\.value)),
            Array("line1\nline2\r\n\ttab\u{0}nul\u{1F}us\u{7F}del".unicodeScalars.map(\.value)),
            Array("hello/world \"quoted\"".unicodeScalars.map(\.value)),
            Array("ünïcödé ✓ 日本語 👩‍👩‍👧".unicodeScalars.map(\.value)),
            Array("\\u0041 is not an escape".unicodeScalars.map(\.value)),
        ]
        return cases
    }

    static func writeStrings(into writer: inout FixtureWriter) {
        let cases = stringCases
        let lines = cases.map { $0.map { String($0, radix: 16) }.joined(separator: " ") }
        writer.write("json/strings.codepoints.txt", text: lines.joined(separator: "\n"))
        let strings = cases.map { scalars in
            String(String.UnicodeScalarView(scalars.map { Unicode.Scalar($0)! }))
        }
        writer.write("json/strings.json", text: appleCompact(strings))
    }

    // MARK: - key order

    static var orderKeys: [String] {
        var keys: [String] = (0x20...0x7E).map { String(UnicodeScalar(UInt8($0))) }
        keys += [
            "a1b", "a1B", "a01b", "a01B", "a001b", "A1b", "ab", "aB", "Ab", "AB",
            "x", "x0", "x00", "x000", "x1", "x01", "a", "a-", "a--", "a-a", "aa",
            "foo", "foo2", "foo2bar", "foo10", "9", "99999999999999999999", "100000000000000000000",
            "0", "00", "000", "007", "7", "07", "1.5", "1.05", "1.50", "1-5", "1_5", "1 5",
            "item9", "item10", "item010", "item09", "Item9", "ITEM10", "v2", "v10", "V2", "v1.2.10", "v1.2.9",
            "use-https-only", "use_https_only", "useHTTPSOnly", "checkout.apple-pay", "checkout.tier",
            "checkout.express.one-tap", "checkout.express.confirmation-delay", "checkout_express",
            "featureToggles.onboarding.v2", "max-items", "new-onboarding", "v2-enabled", "enable2-fa",
            "a.b", "a_b", "a-b", "a b", "A.b", "a.B", "1password", "_private", "-dash", "~tilde", "$dollar",
            "#hash", "@at", "a@b", "a#b", "a$b", "a~b", "a|b", "a=b", "a+b", "a^b", "a`b", "a%b", "a&b",
            "a\\b", "a/b", "a*b", "a}b", "a{b", "a]b", "a[b", "a)b", "a(b", "a\"b", "a'b", "a.b.c",
            "a!b", "a?b", "a:b", "a;b", "a,b", "a<b", "a>b", "zz", "ZZ", "zZ", "Zz", "z9", "z09", "z9z",
        ]
        let pairAlphabet = [" ", "_", "-", ".", "0", "1", "9", "a", "A", "z", "Z", "$", "~"]
        for first in pairAlphabet { for second in pairAlphabet { keys.append(first + second) } }

        var random = SeededGenerator(seed: 0x5EED_0002)
        let alphabet = Array("aAbB0019_-. $~zZ")
        for _ in 0..<500 {
            let length = Int(random.next() % 8) + 1
            keys.append(String((0..<length).map { _ in alphabet[Int(random.next() % UInt64(alphabet.count))] }))
        }
        return unique(keys)
    }

    static func writeKeyOrder(into writer: inout FixtureWriter) {
        let keys = orderKeys
        writer.write("json/key-order.keys.txt", text: keys.joined(separator: "\n"))
        let object = Dictionary(uniqueKeysWithValues: keys.map { ($0, 0) })
        writer.write("json/key-order.json", text: appleCompact(object))
    }

    // MARK: - pretty.json

    /// Built identically by JsonWriterGoldenTest.prettyStructure() on the Kotlin side.
    static var prettyStructure: [String: Any] {
        [
            "array": [1, 2.5, "three", true, false, NSNull(), [Any](), [String: Any](), [[Any]()], [[String: Any]()]] as [Any],
            "emptyArray": [Any](),
            "emptyObject": [String: Any](),
            "nested": [
                "deeper": ["deepest": [Any](), "value": -0.0] as [String: Any],
                "list": [["a": 1], [String: Any]()] as [Any],
            ] as [String: Any],
            "numbers": [0, -1, 1e21, 0.1, 5e-324, Int.max, Int.min, 3.0] as [Any],
            "strings": ["", "/", "\"\\", "\u{1}\t\n", "é😀"],
            "Z": "capital",
            "z": NSNull(),
            "item10": 10,
            "item9": 9,
        ]
    }

    // MARK: - parse.tsv

    static var parseInputs: [String] {
        let deepArrays = { (depth: Int) in String(repeating: "[", count: depth) + String(repeating: "]", count: depth) }
        let deepObjects = { (depth: Int) in
            String(repeating: "{\"a\":", count: depth - 1) + "{}" + String(repeating: "}", count: depth - 1)
        }
        return [
            "[]", "{}", "[ ]", "{ }", " [1] ", "\t\n\r[1]\t\n\r", "\u{FEFF}[1]", "\u{FEFF} {}",
            "[1,]", "{\"a\":1,}", "[ 1 , ]", "[,]", "[1,,]", "{,}", "{\"a\":1,,}", "[1] x", "[1][2]",
            "", "   ", "\u{0C}[1]", "\u{A0}[1]", "[1]\u{0}", "3", "\"x\"", "true", "null",
            "[NaN]", "[01]", "[1.]", "[-]", "[+1]", "[.5]", "[1E400]", "[Infinity]",
            "[\"\\ud800\"]", "[\"\\udc00\"]", "[\"\\ud800x\"]", "[\"\\ud800\\u0041\"]", "[\"\\ud83d\\ude00\"]",
            "[\"\\/\\b\\f\\n\\r\\t\\\"\\\\\\u0041\\u00e9\"]", "[\"\\u0000\\u001F\\u007f\"]",
            "[\"a\tb\"]", "[\"a\nb\"]", "[\"a\u{01}b\"]", "[\"a\u{7F}b\"]", "[\"\u{2028}\u{2029}\"]",
            "[\"\\x\"]", "[\"\\U0041\"]", "[\"\\u004\"]", "[\"\\u00G0\"]", "[\"unterminated]", "[\"a\" \"b\"]",
            "{\"a\":1,\"a\":2}", "{\"b\":1,\"a\":2,\"b\":3}", "{\"a\":{\"x\":1},\"a\":[2]}",
            "[true,false,null]", "[tru]", "[True]", "[nul]", "{1:2}", "{\"a\" 1}", "{\"a\":}", "{\"a\"}",
            "[1 2]", "// c\n[1]", "[/*c*/1]", "['a']", "{a:1}", "[1,2", "{\"a\":1", "]", "}", "[}", "{]",
            "{\"z\":{\"b\":[1,{\"y\":2,\"x\":1}],\"a\":null},\"y\":[]}",
            "[1.5,-2,1e2,0.1,0,-0,1E-2,5e-324,1.7976931348623157e308]",
            "{\"\":0,\"key with space\":\"v\",\"slash/key\":\"a/b\"}",
            "[[[[[]]]],{\"a\":{\"b\":{}}}]",
            deepArrays(512), deepArrays(513), deepArrays(514), deepObjects(513), deepObjects(514),
            "{\"a\":" + deepArrays(512) + "}", "{\"a\":" + deepArrays(513) + "}",
        ]
    }

    static func writeParse(into writer: inout FixtureWriter) {
        let rows = parseInputs.map { input -> [String] in
            guard let object = try? JSONSerialization.jsonObject(with: Data(input.utf8)) else {
                return [input, "reject"]
            }
            return [input, appleCompact(object)]
        }
        writer.writeTSV("json/parse.tsv", rows: rows)
    }
}
```

The date fixtures. `format.tsv` writes the epoch as Swift prints a `Double`. `parse.tsv` holds the canonical forms, every lenient form Apple accepts, and forms both sides reject.

`fixture-generator/Sources/GenerateFixtures/DateFixtures.swift`:

```swift
import Foundation

/// dates/*: the formatter every FeatureFlag JSON representation shares
/// (ISO8601DateFormatter, .withInternetDateTime).
enum DateFixtures {
    static let formatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func write(into writer: inout FixtureWriter) {
        var epochs: [Double] = [
            0, 0.5, -0.5, 1.999, -1, -1.5, 1, 59.999, 86_399, 86_400, 951_782_400, 1_709_164_800,
            1_767_225_600, 1_767_260_460, 1_767_260_460.75, 1_700_000_000, 2_147_483_647, 2_147_483_648,
            -2_208_988_800, -2_203_891_200, -14_825_894_400,
            -62_135_769_600, -62_135_769_601, -62_135_596_800, -62_167_392_000, -62_198_755_200,
            -12_219_292_800, -12_219_292_801, -12_219_379_200, -12_220_156_800, -12_218_860_800,
            -43_776_460_800, 253_402_300_799, 253_402_300_800, 3_093_496_444_800,
            Date.distantPast.timeIntervalSince1970, Date.distantFuture.timeIntervalSince1970,
            1e12, -1e12, 3e11, 1e14, -1e14, 1e15, -1e15, 1e16, -1e16,
            183_882_168_921_600, 183_882_168_921_599, 183_882_168_921_601,
            -184_303_902_528_000, -184_303_902_528_001,
        ]
        var random = SeededGenerator(seed: 0x5EED_0003)
        for _ in 0..<300 {
            epochs.append(Double(Int64(random.next() % 40_000_000_000) - 30_000_000_000) + Double(random.next() % 1000) / 1000)
        }
        let formatRows = unique(epochs).map { epoch in
            ["\(epoch)", formatter.string(from: Date(timeIntervalSince1970: epoch))]
        }
        writer.writeTSV("dates/format.tsv", rows: formatRows)

        var inputs = canonicalParseInputs + lenientParseInputs + rejectedParseInputs
        inputs += formatRows.map { $0[1] }
        let parseRows = unique(inputs).map { text -> [String] in
            guard let date = formatter.date(from: text) else { return [text, "nil"] }
            return [text, String(Int64(date.timeIntervalSince1970))]
        }
        writer.writeTSV("dates/parse.tsv", rows: parseRows)
    }

    /// Inside the canonical subset Kotlin accepts.
    static let canonicalParseInputs = [
        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00z", "2026-01-01T09:41:00Z", "1970-01-01T00:00:00Z",
        "1969-12-31T23:59:59Z", "2026-01-01T00:00:00+00:00", "2026-01-01T00:00:00-00:00",
        "2026-01-01T00:00:00+0000", "2026-01-01T00:00:00-0000", "2026-01-01T10:00:00+10:00",
        "2026-01-01T00:00:00+05:30", "2026-01-01T00:00:00+0530", "2026-01-01T00:00:00-03:30",
        "2026-01-01T00:00:00+23:59", "2026-01-01T00:00:00-23:59", "2026-01-01T00:00:00+14:00",
        "2026-12-31T23:59:59Z", "2024-02-29T00:00:00Z", "2000-02-29T12:00:00Z", "1500-02-29T00:00:00Z",
        "0001-01-01T00:00:00Z", "0000-01-01T00:00:00Z", "9999-12-31T23:59:59Z", "1582-10-04T23:59:59Z",
        "1582-10-15T00:00:00Z", "1582-10-10T00:00:00Z", "1582-10-05T00:00:00Z", "1582-10-14T23:59:59Z",
        "0582-10-10T00:00:00Z", "1900-01-01T00:00:00Z", "2026-04-30T00:00:00Z", "2026-06-30T00:00:00Z",
        "10000-01-01T00:00:00Z", "00001-01-01T00:00:00Z", "02026-01-01T00:00:00Z", "33658-09-27T01:46:40Z",
        "123456-01-01T00:00:00Z", "5828963-12-20T00:00:00Z",
    ]

    /// Apple accepts these; Kotlin's canonical subset does not (APPLE_ONLY_DATE_FORMS).
    static let lenientParseInputs = [
        "2026-1-1T0:0:0Z", "2026-01-01T00:00:00+10", "2026-01-01T00:00:00+5:30", "2026-01-01T00:00:00GMT",
        "2026-01-01T00:00:00UTC", "2026-01-01T00:00:00 Z", " 2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z ",
        "2026-02-30T00:00:00Z", "2026-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2026-04-31T00:00:00Z",
        "2026-01-01T24:00:00Z", "2026-01-01T00:00:00+99:99", "2026-01-01T00:00:00+24:00",
        "2026-01-01T00:00:00+00:60", "2026-01-01T00:00:00+1", "2026-01-01T00:00:00+100",
        "2026-01-01T00:00:00+10000", "2026-01-01T00:00:00+10:00:00", "2026-01-01T00:00:00 +10:00",
        "-0001-01-01T00:00:00Z", "２０２６-01-01T00:00:00Z", "1000000000-12-31T23:59:59Z",
        "2026-01-01T00:00:00+10:0", "2026-01-01T00:00:00Z\n", "2026-01-01T00:00:00ZZ", "2026/01/01T00:00:00Z",
        "999-01-01T00:00:00Z", "2026-01-01T00:00:00+10:",
    ]

    /// Rejected by both.
    static let rejectedParseInputs = [
        "", "2026-01-01T00:00:00", "2026-01-01 00:00:00Z", "2026-01-01t00:00:00Z", "2026-01-01T00:00:00.123Z",
        "2026-01-01T00:00:00.5Z", "2026-13-01T00:00:00Z", "2026-00-01T00:00:00Z", "2026-01-00T00:00:00Z",
        "2026-01-32T00:00:00Z", "2026-01-01T23:59:60Z", "2026-01-01T00:60:00Z", "2026-01-01T25:00:00Z",
        "+2026-01-01T00:00:00Z", "20260101T000000Z", "2026-01-01T00:00Z", "2026-01-01", "not a date",
        "2026-01-01T00:00:00+", "2026-01-01X00:00:00Z", "2026-01-01T00:00:00.Z",
    ]
}
```

Key encoding (every example from `KeyEncodingEdgeCaseTests` and `FlagKeyTests`, plus GoldenFlags' property names and more shapes), base64 (every string of length 0 to 6 over `A / = g`, the probe cases and 200 seeded round-trips), and `URL(string:)`.

`fixture-generator/Sources/GenerateFixtures/EncodingFixtures.swift`:

```swift
import FeatureFlag
import Foundation

/// keys/, base64/ and url/: key encoding, Data(base64Encoded:) and URL(string:).
enum EncodingFixtures {
    static func write(into writer: inout FixtureWriter) {
        writeKeyEncoding(into: &writer)
        writeBase64(into: &writer)
        writeURLs(into: &writer)
    }

    // MARK: - keys/key-encoding.tsv

    static let propertyNames = [
        // FlagKeyTests and KeyEncodingEdgeCaseTests
        "newOnboarding", "checkout", "enableAPI", "apiKey", "useHTTPSOnly", "checkoutV2", "express", "oneTap",
        "applePay", "a", "A", "", "API", "NewOnboarding", "v2Enabled", "enable2FA", "legacy_name",
        // GoldenFlags
        "boolFlag", "intFlag", "doubleFlag", "floatFlag", "stringFlag", "dataFlag", "dateFlag", "urlFlag",
        "markets", "matrix", "limits", "tier", "level", "endpoints", "confirmationDelay",
        // More shapes
        "URLSession", "HTTPSURL", "iOS", "isOK", "aB", "ABc", "x1Y2", "__a", "a__b", "ID", "userID", "IDs",
        "PDFViewer", "XY", "x", "maxItems", "featureToggles", "v2", "V2", "V2Beta", "version2Point1",
        "already-kebab", "snake_Case", "ÄpfelÖl", "naïveCafé", "straßeGroß", "ΑλφαΒήτα", "日本Go",
        "isİstanbul", "a1B2c3", "HTML5Parser", "x2Y", "ABC123def", "lowerUPPERLower",
    ]

    static func writeKeyEncoding(into writer: inout FixtureWriter) {
        let rows = unique(propertyNames).map { name in
            [
                name,
                KeyEncoding.kebabcase.key(for: FlagKeyPath([name])).rawValue,
                KeyEncoding.snakecase.key(for: FlagKeyPath([name])).rawValue,
            ]
        }
        writer.writeTSV("keys/key-encoding.tsv", rows: rows)
    }

    // MARK: - base64/decode.tsv

    static func writeBase64(into writer: inout FixtureWriter) {
        var inputs = [
            "", "YQ", "YQ=", "YQ==", "YR==", "YWI=", "YWJ=", "YWJj", "aGk=", "aGk==", "aGk===", "QUJD====",
            "Zm9v", "Zm9vYg", "Zm9vYg==", "Zm9vYmE=", "Zm9vYmE", "Zm9vYmFy", " Zm9v", "Zm9v ", "Zm 9v",
            "Zm9v\n", "Zm9v\r\n", "Zm9v\t", "Zm-_", "Zm+/", "Zm9v=Zm9v", "Zm9v====Zm9v", "=Zm9v", "Z===",
            "Zg=", "Zg===", "Zg=====", "Zm8=", "Zm9=v", "AA=A", "//=/", "/w=/", "====", "=", "==", "===",
            "AAECAwQFBgcICQ==", "AAEC/v8=", "3q2+7w==", "w6k=", "ÿÿÿÿ", "YWJj\u{0}", "YW=j", "Y===",
            "YQ==YQ==", "YQ==\u{0}", "Y Q = =", "-_-_", "QUJD", "QUJDRA", "QUJDRA=", "QUJDRA==",
        ]
        let alphabet = ["A", "/", "=", "g"]
        var frontier = [""]
        for _ in 1...6 {
            frontier = frontier.flatMap { prefix in alphabet.map { prefix + $0 } }
            inputs += frontier
        }
        var random = SeededGenerator(seed: 0x5EED_0004)
        for _ in 0..<200 {
            let bytes = (0..<Int(random.next() % 40)).map { _ in UInt8(truncatingIfNeeded: random.next()) }
            inputs.append(Data(bytes).base64EncodedString())
        }
        let rows = unique(inputs).map { input -> [String] in
            [input, Data(base64Encoded: input).map(hexString) ?? "nil"]
        }
        writer.writeTSV("base64/decode.tsv", rows: rows)
    }

    // MARK: - url/parse.tsv (URL(string:) as FlagValueBox(jsonValue:as: .url) uses it)

    static let urlInputs = [
        "", " ", "a b", "https://example.com", "https://example.com/a/b?c=d", "https://example.com/a b",
        "https://例え.jp/パス", "http://[::1]:8080/x", "%", "https://example.com/%zz", "not a url", "://",
        "mailto:x@y.z", "http://a.com/ü", "#frag", "?q", "a/b", "https://exa mple.com",
        "https://example.com/a?b=c d#e f", "https://example.com/\"q\"", "https://example.com/<x>",
        "https://example.com/a|b", "https://example.com/a\\b", "https://example.com/{x}", "https://example.com/^",
        "https://example.com/`", "http://a.com:abc/", "http://a.com:99999/", "http://[zz]/",
        "https://example.com/a%20b", "HTTPS://Example.COM/A", "https://example.com/a\tb", "https://example.com/a\nb",
        " https://example.com", "https://example.com ", "file:///tmp/x", "urn:isbn:0451450523",
        "https://user:pa ss@example.com/", "https://example.com/😀", "https://example.com/path?q=1&r=a%20b#frag",
        "https://prod.example.com", "https://example.com:443/", "ftp://ftp.example.com/file.txt",
        "data:text/plain;base64,SGk=", "https://example.com/a%2Fb", "//example.com/path", "/relative/path",
        "relative", "https://", "https:", "http://a.com/%E2%82%AC", "http://a.com/€", "https://example.com/#",
        "https://example.com/?", "custom-scheme://host/path", "1http://x", "a:b:c", "https://a.com/[x]",
    ]

    static func writeURLs(into writer: inout FixtureWriter) {
        let rows = urlInputs.map { input -> [String] in
            [input, URL(string: input)?.absoluteString ?? "nil"]
        }
        writer.writeTSV("url/parse.tsv", rows: rows)
    }
}
```

The portable documents for M1b and M1c: both schemas, the export of one override per flag, three record lists, and three QR codes. The `stored` QR case is found by a seeded search for a payload deflate cannot shrink, and the generator proves Apple really stored it uncompressed.

`fixture-generator/Sources/GenerateFixtures/TransportFixtures.swift`:

```swift
import FeatureFlag
import Foundation
import GoldenModel

/// schema/, export/, records/ and qr/: the portable documents, byte for byte.
enum TransportFixtures {
    /// 2026-01-01T00:00:00Z
    static let schemaDate = Date(timeIntervalSince1970: 1_767_225_600)
    /// 2026-01-01T09:41:00Z
    static let exportDate = Date(timeIntervalSince1970: 1_767_260_460)

    static func write(into writer: inout FixtureWriter) {
        writeSchemas(into: &writer)
        writer.write("export/golden.json", data: encoded(goldenPayload))
        writeRecords(into: &writer)
        writeQRCodes(into: &writer)
    }

    // MARK: - Records

    static func endpoint(
        _ name: String, _ url: String, weight: Int = 1, note: String? = nil, kind: GoldenTier = .free,
        targets: [GoldenTarget] = []
    ) -> GoldenEndpoint {
        var boxes: [String: FlagValueBox] = [
            "name": .string(name), "url": .url(URL(string: url)!), "weight": .int(weight),
            "kind": kind.box, "targets": FlagRecords(targets).box,
        ]
        if let note { boxes["note"] = .string(note) }
        guard let record = GoldenEndpoint(flagRecordBoxes: boxes) else { fatalError("bad endpoint \(name)") }
        return record
    }

    static func target(_ host: String, _ port: Int) -> GoldenTarget {
        guard let record = GoldenTarget(flagRecordBoxes: ["host": .string(host), "port": .int(port)]) else {
            fatalError("bad target \(host)")
        }
        return record
    }

    /// Mirrored by RecordsGoldenTest on the Kotlin side.
    static var recordLists: [(String, [GoldenEndpoint])] {
        [
            ("default", [endpoint("prod", "https://prod.example.com")]),
            ("empty", []),
            ("full", [
                endpoint(
                    "prod", "https://prod.example.com/v1?x=1", weight: 10, note: "primary \"quoted\" / slash",
                    kind: .enterprise, targets: [target("a.example", 443), target("b.example", 8443)]
                ),
                endpoint("staging", "https://staging.example.com", weight: 0, kind: .pro),
                endpoint("ünïcode", "https://example.com/%C3%BC", weight: -3, note: ""),
            ]),
        ]
    }

    static func recordText(_ endpoints: [GoldenEndpoint]) -> String {
        guard case let .string(text) = FlagRecords(endpoints).box else { fatalError("records box as text") }
        return text
    }

    static func writeRecords(into writer: inout FixtureWriter) {
        for (name, endpoints) in recordLists {
            writer.write("records/\(name).txt", text: recordText(endpoints))
        }
    }

    // MARK: - Schema

    static func writeSchemas(into writer: inout FixtureWriter) {
        for (name, encoding) in [("kebab", KeyEncoding.kebabcase), ("snake", KeyEncoding.snakecase)] {
            let schema = FlagSchema(
                GoldenFlags.self, keyEncoding: encoding, applicationName: "Golden", generatedAt: schemaDate
            )
            do {
                writer.write("schema/golden-\(name).flag-schema.json", data: try schema.jsonData())
            } catch {
                fatalError("schema \(name): \(error)")
            }
        }
    }

    // MARK: - Export

    /// One override for every flag in GoldenFlags, every type represented. Kebab-case keys.
    static var goldenPayload: FlagPayload {
        FlagPayload(
            values: [
                "bool-flag": .bool(true),
                "int-flag": .int(-7),
                "double-flag": .double(0.30000000000000004),
                "float-flag": .float(1.1),
                "string-flag": .string("line1\nline2\t\"q\" / é 😀"),
                "data-flag": .data(Data([0xDE, 0xAD, 0xBE, 0xEF])),
                "date-flag": .date(Date(timeIntervalSince1970: 1_767_260_460.75)),
                "url-flag": .url(URL(string: "https://example.com/path?q=1&r=a%20b#frag")!),
                "use-https-only": .bool(true),
                "markets": .array([.string("AU"), .string("NZ"), .string("US")]),
                "matrix": .array([.array([]), .array([.bool(true)])]),
                "limits": .dictionary(["z": .int(26), "a10": .int(10), "a9": .int(9), "A": .int(1)]),
                "tier": .string("enterprise"),
                "level": .int(3),
                "endpoints": .string(recordText(recordLists[2].1)),
                "checkout.apple-pay": .bool(false),
                "checkout.tier": .string("free"),
                "checkout.express.one-tap": .bool(true),
                "checkout.express.confirmation-delay": .double(0.5),
            ],
            exportedAt: exportDate
        )
    }

    static func encoded(_ payload: FlagPayload) -> Data {
        do {
            return try payload.encoded(as: .json)
        } catch {
            fatalError("export: \(error)")
        }
    }

    // MARK: - QR

    static func writeQRCodes(into writer: inout FixtureWriter) {
        let single = FlagPayload(values: ["bool-flag": .bool(true)], exportedAt: exportDate)
        var cases: [(String, FlagPayload)] = [("golden", goldenPayload), ("single", single)]

        // Apple stores the JSON uncompressed when deflate cannot make it smaller, which
        // only happens for short, high-entropy documents. Search a seeded family of
        // candidates for the first one that takes that path, and prove it did.
        var random = SeededGenerator(seed: 0x5EED_0005)
        var stored: FlagPayload?
        search: for length in 1...160 {
            for _ in 0..<8 {
                let scalars = (0..<length).map { _ -> Unicode.Scalar in
                    var value: UInt32
                    repeat { value = UInt32(0x800 + random.next() % (0xFFFE - 0x800)) } while (0xD800...0xDFFF).contains(value)
                    return Unicode.Scalar(value)!
                }
                let candidate = FlagPayload(
                    values: ["string-flag": .string(String(String.UnicodeScalarView(scalars)))], exportedAt: exportDate
                )
                if isStored(try! FlagQRCode.encode(candidate), json: encoded(candidate)) {
                    stored = candidate
                    break search
                }
            }
        }
        guard let stored else { fatalError("no stored-fallback QR candidate found") }
        cases.append(("stored", stored))

        for (name, payload) in cases {
            writer.write("qr/\(name).json", data: encoded(payload))
            do {
                writer.write("qr/\(name).qr.txt", text: try FlagQRCode.encode(payload))
            } catch {
                fatalError("qr \(name): \(error)")
            }
        }
        if isStored(try! FlagQRCode.encode(goldenPayload), json: encoded(goldenPayload)) {
            fatalError("the golden QR payload was expected to compress")
        }
    }

    /// Whether `code` carries `json` verbatim after its 4-byte length prefix.
    static func isStored(_ code: String, json: Data) -> Bool {
        var base64 = String(code.dropFirst(FlagQRCode.prefix.count))
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        if base64.count % 4 != 0 { base64 += String(repeating: "=", count: 4 - base64.count % 4) }
        guard let bytes = Data(base64Encoded: base64) else { fatalError("bad QR base64") }
        return bytes.dropFirst(4) == json
    }
}
```

Remote payloads and audits for M1d. The case names and payloads are the tables in M1d Tasks D2 (remote goldens) and D4 (audit goldens), which read these files; change a case on both sides at once. Every remote case is applied to `RemoteOverrideSource(GoldenFlags.self)`, and every audit case goes through `FlagMappingAudit(GoldenFlags.self, applying:)`, both with the default kebab-case keys and `DotPathMapper`. The payloads are raw strings, so `\n` and `\"` reach the JSON parser as JSON escapes.

`fixture-generator/Sources/GenerateFixtures/RemoteFixtures.swift`:

```swift
import FeatureFlag
import Foundation
import GoldenModel

/// remote/ and audit/: RemoteOverrideSource and FlagMappingAudit against GoldenFlags
/// with the default (kebab-case) key encoding and DotPathMapper.
enum RemoteFixtures {
    /// Every remote flag GoldenFlags declares, each given a value its type accepts.
    static let everyRemoteFlag = #"{"toggles":{"boolean":true},"numbers":{"int":7,"double":2.5},"strings":{"plain":"remote"},"dates":{"launch":"2026-06-01T09:30:00Z"},"config":{"endpoint":"https://remote.example/x","markets":["AU","NZ","GB"],"endpoints":[{"name":"edge","url":"https://edge.example","memo":"from backend","targets":[{"host":"a.example","port":443}]}]},"tier":"pro","featureToggles":{"checkout":{"applePay":false}}}"#

    /// M1d Task D2's remote golden table, in its order.
    static let remoteCases: [(String, String)] = [
        ("every-remote-flag", everyRemoteFlag),
        ("empty-object", #"{}"#),
        ("nulls-are-absent", #"{"toggles":{"boolean":null},"tier":"enterprise"}"#),
        ("whole-numbers", #"{"numbers":{"int":3.0,"double":2}}"#),
        ("type-mismatch", #"{"toggles":{"boolean":"true"}}"#),
        ("unknown-case", #"{"tier":"platinum"}"#),
        ("every-problem-sorted", #"{"numbers":{"int":1.5},"strings":{"plain":7},"tier":"gold","config":{"markets":["AU",1]}}"#),
        ("long-string-value", #"{"toggles":{"boolean":"line one\nline two, and then a good deal more text than fits"}}"#),
        ("integer-overflow", #"{"numbers":{"int":10000000000000000000}}"#),
        ("object-for-string", #"{"strings":{"plain":{"a":1}}}"#),
        ("bad-date", #"{"dates":{"launch":"2026-01-01"}}"#),
        ("offset-date", #"{"dates":{"launch":"2026-01-01T10:00:00+10:00"}}"#),
        ("url-needing-encoding", #"{"config":{"endpoint":"https://example.com/a b/ü"}}"#),
        ("records-natural", #"{"config":{"endpoints":[{"name":"prod","url":"https://prod.example.com"},{"name":"edge","url":"https://edge.example","weight":3,"memo":"note","kind":"enterprise","targets":[{"host":"a","port":1}]}]}}"#),
        ("records-as-string", #"{"config":{"endpoints":"[{\"name\":\"x\",\"url\":\"https://x.example\",\"kind\":\"platinum\"}]"}}"#),
        ("records-duplicate-key", #"{"config":{"endpoints":[{"name":"prod","url":"https://a.example"},{"name":"prod","url":"https://b.example"}]}}"#),
        ("records-missing-field", #"{"config":{"endpoints":[{"name":"x"}]}}"#),
        ("records-unknown-case", #"{"config":{"endpoints":[{"name":"x","url":"https://x.example","kind":"platinum"}]}}"#),
        ("records-mixed-forms", #"{"config":{"endpoints":[{"name":"a","url":"https://a.example"},{"name":"b","url":"https://b.example","weight":5,"memo":null,"kind":"enterprise","targets":"[{\"host\":\"h\",\"port\":1}]"},{"name":"c","url":"https://c.example","note":"ignored: read from memo","targets":[{"host":"x","port":2},{"host":"y","port":3}]}]}}"#),
        ("records-wrong-field-type", #"{"config":{"endpoints":[{"name":"a","url":"https://a.example","weight":"heavy"}]}}"#),
        ("not-json", #"not json"#),
        ("top-level-scalar", #"true"#),
    ]

    /// M1d Task D4's audit golden table, in its order.
    static let auditCases: [(String, String)] = [
        ("complete", everyRemoteFlag),
        ("incomplete", #"{"toggles":{"boolean":"yes"},"numbers":{"int":42},"tier":"gold","config":{"page_size":25},"meta":{"version":7}}"#),
        ("empty-object", #"{}"#),
        ("root-array", #"[1,2]"#),
        ("nested-empty", #"{"meta":{}}"#),
        ("restated", #"{"toggles":{"boolean":false},"numbers":{"int":42,"double":0.1},"strings":{"plain":"hello/world \"quoted\""},"dates":{"launch":"2026-01-01T00:00:00Z"},"config":{"endpoint":"https://example.com/a/b?c=d","markets":["AU","NZ"],"endpoints":[{"name":"prod","url":"https://prod.example.com"}]},"tier":"free","featureToggles":{"checkout":{"applePay":true}}}"#),
        ("record-changes", #"{"config":{"endpoints":[{"name":"prod","url":"https://prod.example.com","weight":5,"kind":"pro","memo":"hello"},{"name":"canary","url":"https://canary.example"}]}}"#),
        ("scalar-changes", #"{"numbers":{"int":10000000000,"double":0.30000000000000004},"strings":{"plain":"a much longer string than forty characters\nwith a newline"},"dates":{"launch":"2026-06-01T09:30:00Z"},"config":{"endpoint":"https://remote.example/x","markets":["AU"]}}"#),
    ]

    static func write(into writer: inout FixtureWriter) {
        let schema = FlagSchema(GoldenFlags.self)
        for (name, payload) in remoteCases {
            writer.write("remote/\(name).payload.json", text: payload)
            writer.write("remote/\(name).expected.json", data: applePretty(expected(payload, schema: schema)))
        }
        for (name, payload) in auditCases {
            writer.write("audit/\(name).payload.json", text: payload)
            do {
                let audit = try FlagMappingAudit(GoldenFlags.self, applying: Data(payload.utf8))
                writer.write("audit/\(name).txt", text: audit.description)
                writer.write("audit/\(name).strict.txt", text: audit.description(strict: true, ignoring: ["meta"]))
                writer.write("audit/\(name).defaults.txt", text: audit.defaultsDescription)
            } catch {
                fatalError("audit \(name): \(error)")
            }
        }
    }

    static func expected(_ payload: String, schema: FlagSchema) -> [String: Any] {
        let source = RemoteOverrideSource(GoldenFlags.self)
        do {
            let result = try source.apply(Data(payload.utf8), format: .json)
            var values = [String: Any]()
            for key in result.appliedKeys {
                guard let type = schema.valueTypes[key], let box = source.box(for: key, as: type) else {
                    fatalError("applied key \(key) has no value")
                }
                values[key.rawValue] = box.jsonValue
            }
            return [
                "applied": result.appliedKeys.map(\.rawValue),
                "absent": result.absentKeys.map(\.rawValue),
                "problems": [Any](),
                "values": values,
            ]
        } catch let error as RemoteOverrideError {
            var problems = [[String: Any]]()
            if case let .rejected(list) = error {
                problems = list.map { problem in
                    [
                        "key": problem.key.rawValue,
                        "remoteKey": problem.remoteKey,
                        "kind": String(describing: problem.kind),
                        "message": String(describing: problem),
                    ]
                }
            }
            return ["rejected": String(describing: error), "problems": problems]
        } catch {
            fatalError("unexpected error \(error)")
        }
    }
}
```

Every error that public API can construct, named `<ErrorType>.<case>[.<variant>]`. The values are M1d Task D5's message table, which reads these files; M1a Task A4 and M1b Task B1 read the serialization and schema ones. The three `.decoded` cases are a real import failure and a real remote failure, which exercise the value-rendering helpers: quoting, the 40-character cap, counts, enum case lists and duplicate record keys.

`fixture-generator/Sources/GenerateFixtures/MessageFixtures.swift`:

```swift
import FeatureFlag
import Foundation
import GoldenModel

/// messages/<ErrorType>.<case>[.<variant>].txt: every error FeatureFlag can be made to
/// throw through public API, as String(describing:).
enum MessageFixtures {
    static func write(into writer: inout FixtureWriter) {
        let messages: [(String, any Error)] = [
            ("FlagSerializationError.nonFiniteNumber", FlagSerializationError.nonFiniteNumber("ratio")),
            ("FlagSchemaError.unsupportedFormatVersion", FlagSchemaError.unsupportedFormatVersion(9)),
            ("FlagSchemaError.malformed", FlagSchemaError.malformed("missing formatVersion")),
            ("FlagSchemaError.notPublished", FlagSchemaError.notPublished),
            ("FlagImportError.malformed", FlagImportError.malformed("not valid JSON")),
            ("FlagImportError.unsupportedFormatVersion", FlagImportError.unsupportedFormatVersion(9)),
            ("FlagImportError.rejected.empty", FlagImportError.rejected([])),
            ("FlagImportError.rejected.one", FlagImportError.rejected([
                FlagImportProblem(key: "page-size", kind: .typeMismatch, expected: "int", found: "\"big\""),
            ])),
            ("FlagImportError.rejected.unknownKey", FlagImportError.rejected([
                FlagImportProblem(key: "nope", kind: .unknownKey),
            ])),
            ("FlagImportError.rejected.bare", FlagImportError.rejected([
                FlagImportProblem(key: "a", kind: .typeMismatch),
                FlagImportProblem(key: "b", kind: .unknownCase),
                FlagImportProblem(key: "c", kind: .unknownKey),
            ])),
            ("FlagImportError.rejected.decoded", decodeError()),
            ("FlagQRCodeError.payloadTooLarge.one", FlagQRCodeError.payloadTooLarge(bytes: 3400, limit: 2900, overrideCount: 1)),
            ("FlagQRCodeError.payloadTooLarge.many", FlagQRCodeError.payloadTooLarge(bytes: 3400, limit: 2900, overrideCount: 12)),
            ("FlagQRCodeError.unrecognisedFormat", FlagQRCodeError.unrecognisedFormat),
            ("FlagQRCodeError.corrupt", FlagQRCodeError.corrupt),
            ("FlagQRCodeError.imageGenerationFailed", FlagQRCodeError.imageGenerationFailed),
            ("FlagSignalError.notAcknowledged", FlagSignalError.notAcknowledged),
            ("FlagSignalError.unavailableAppGroup", FlagSignalError.unavailableAppGroup("group.com.example")),
            ("FlagError.noMutableSource", FlagError.noMutableSource),
            ("FlagError.unsupportedValue", FlagError.unsupportedValue("checkout.apple-pay")),
            ("RemoteOverrideError.malformed", RemoteOverrideError.malformed("not valid JSON")),
            ("RemoteOverrideError.rejected.empty", RemoteOverrideError.rejected([])),
            ("RemoteOverrideError.rejected.one", RemoteOverrideError.rejected([
                RemoteOverrideProblem(key: "new-onboarding", remoteKey: "featureToggles.onboarding", kind: .typeMismatch,
                                      expected: "bool", found: "\"yes\""),
            ])),
            ("RemoteOverrideError.rejected.bare", RemoteOverrideError.rejected([
                RemoteOverrideProblem(key: "a", remoteKey: "x.a", kind: .typeMismatch),
                RemoteOverrideProblem(key: "b", remoteKey: "x.b", kind: .unknownCase),
                RemoteOverrideProblem(key: "new-onbaording", remoteKey: "new-onbaording", kind: .unknownKey),
            ])),
            ("RemoteOverrideError.rejected.documented", RemoteOverrideError.rejected([
                RemoteOverrideProblem(key: "checkout-tier", remoteKey: "config.tier", kind: .unknownCase,
                                      expected: "free, pro", found: "\"gold\""),
                RemoteOverrideProblem(key: "new-onboarding", remoteKey: "featureToggles.onboarding", kind: .typeMismatch,
                                      expected: "bool", found: "\"yes\""),
                RemoteOverrideProblem(key: "page-size", remoteKey: "config.pageSize", kind: .typeMismatch,
                                      expected: "int", found: "an array of 3"),
            ])),
            ("RemoteOverrideError.rejected.decoded", remoteError()),
            ("FlagMappingAuditError.incomplete", auditError()),
        ]
        for (name, error) in messages {
            writer.write("messages/\(name).txt", text: String(describing: error))
        }
    }

    /// A real import failure, so the value-rendering helpers (quoting, truncation, counts,
    /// enum case lists, duplicate record keys) appear in the message.
    static func decodeError() -> any Error {
        let schema = FlagSchema(GoldenFlags.self)
        let document = """
            { "formatVersion": 1, "exportedAt": "2026-01-01T00:00:00Z", "values": {
                "int-flag": "a very long string with a \\n newline, a \\t tab and a \\\\ backslash in it",
                "bool-flag": 1,
                "double-flag": "1.5",
                "unknown-flag": [1, 2],
                "tier": "platinum",
                "level": 7,
                "limits": { "a": "x" },
                "markets": { "a": 1, "b": 2 },
                "date-flag": "2026-13-01T00:00:00Z",
                "endpoints": "[{\\"name\\":\\"a\\",\\"url\\":\\"https://a.example\\"},{\\"name\\":\\"a\\",\\"url\\":\\"https://b.example\\"}]",
                "checkout.tier": true,
                "url-flag": "",
                "data-flag": "not base64!"
            } }
            """
        do {
            _ = try FlagPayload.decode(
                Data(document.utf8), as: .json, valueTypes: schema.valueTypes, cases: schema.valueCases,
                recordShapes: schema.recordShapes
            )
            fatalError("decode was expected to fail")
        } catch {
            return error
        }
    }

    static func remoteError() -> any Error {
        let payload = """
            {
              "toggles": { "boolean": "true" },
              "numbers": { "int": 1.5, "double": [1] },
              "strings": { "plain": null },
              "dates": { "launch": 1767225600 },
              "config": { "endpoint": { "a": 1 }, "markets": [1, 2, 3],
                "endpoints": [ { "name": "a", "url": "https://a.example" }, { "name": "a", "url": "https://b.example" } ] },
              "tier": "Platinum Plus Ultra Extreme Deluxe Edition Max Pro",
              "featureToggles": { "checkout": { "applePay": 0 } }
            }
            """
        do {
            try RemoteOverrideSource(GoldenFlags.self).apply(Data(payload.utf8), format: .json)
            fatalError("apply was expected to fail")
        } catch {
            return error
        }
    }

    /// What `requireComplete()` throws for a payload that supplies nothing.
    static func auditError() -> any Error {
        do {
            try FlagMappingAudit(GoldenFlags.self, applying: Data("{}".utf8)).requireComplete()
            fatalError("an empty payload was expected to be incomplete")
        } catch {
            return error
        }
    }
}
```

`fixture-generator/Sources/DecodeKotlinFixtures/main.swift`:

```swift
import Foundation

// Decodes what the Kotlin port writes to fixtures-android/ (QR codes and exports) with
// FeatureFlag 0.12.0, proving Swift can read Kotlin's output. Filled in by M1c; until then it
// only reports that there is nothing to decode.

let directory = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "fixtures-android")
let entries = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
let files = entries.filter { $0.hasPrefix(".") == false }

guard files.isEmpty else {
    FileHandle.standardError.write(Data("""
        decode-kotlin-fixtures: \(directory.path) holds \(files.count) file(s), but decoding arrives in M1c.

        """.utf8))
    exit(1)
}

print("usage: decode-kotlin-fixtures [fixtures-android-directory]")
print("Nothing to decode: \(directory.path) is empty or missing.")
exit(0)
```

`scripts/sync-fixtures.sh`:

```bash
#!/usr/bin/env bash
# Regenerates fixtures/ from FeatureFlag 0.12.0 through fixture-generator/.
# fixtures/ is only ever written by this script; never edit it by hand.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

swift run --package-path "$root/fixture-generator" generate-fixtures "$staging/fixtures"

rm -rf "$root/fixtures"
mv "$staging/fixtures" "$root/fixtures"
echo "fixtures/ now holds $(find "$root/fixtures" -type f | wc -l | tr -d ' ') files."
```

Run it. The first run fetches FeatureFlag 0.12.0 and swift-syntax from GitHub and builds them, which can take several minutes on a cold cache. Run it in the background and check on it rather than waiting blind.

```bash
chmod +x scripts/sync-fixtures.sh
./scripts/sync-fixtures.sh
swift run --package-path fixture-generator decode-kotlin-fixtures fixtures-android
```

Expected: the script ends with `fixtures/ now holds 129 files.` and the stub prints `usage: decode-kotlin-fixtures [fixtures-android-directory]` then `Nothing to decode: …/fixtures-android is empty or missing.`, exiting 0. `find fixtures -type f | sort` lists exactly:

```text
fixtures/audit/complete.defaults.txt
fixtures/audit/complete.payload.json
fixtures/audit/complete.strict.txt
fixtures/audit/complete.txt
fixtures/audit/empty-object.defaults.txt
fixtures/audit/empty-object.payload.json
fixtures/audit/empty-object.strict.txt
fixtures/audit/empty-object.txt
fixtures/audit/incomplete.defaults.txt
fixtures/audit/incomplete.payload.json
fixtures/audit/incomplete.strict.txt
fixtures/audit/incomplete.txt
fixtures/audit/nested-empty.defaults.txt
fixtures/audit/nested-empty.payload.json
fixtures/audit/nested-empty.strict.txt
fixtures/audit/nested-empty.txt
fixtures/audit/record-changes.defaults.txt
fixtures/audit/record-changes.payload.json
fixtures/audit/record-changes.strict.txt
fixtures/audit/record-changes.txt
fixtures/audit/restated.defaults.txt
fixtures/audit/restated.payload.json
fixtures/audit/restated.strict.txt
fixtures/audit/restated.txt
fixtures/audit/root-array.defaults.txt
fixtures/audit/root-array.payload.json
fixtures/audit/root-array.strict.txt
fixtures/audit/root-array.txt
fixtures/audit/scalar-changes.defaults.txt
fixtures/audit/scalar-changes.payload.json
fixtures/audit/scalar-changes.strict.txt
fixtures/audit/scalar-changes.txt
fixtures/base64/decode.tsv
fixtures/dates/format.tsv
fixtures/dates/parse.tsv
fixtures/export/golden.json
fixtures/json/key-order.json
fixtures/json/key-order.keys.txt
fixtures/json/number-parse.tsv
fixtures/json/numbers.tsv
fixtures/json/parse.tsv
fixtures/json/pretty.json
fixtures/json/strings.codepoints.txt
fixtures/json/strings.json
fixtures/keys/key-encoding.tsv
fixtures/manifest.json
fixtures/messages/FlagError.noMutableSource.txt
fixtures/messages/FlagError.unsupportedValue.txt
fixtures/messages/FlagImportError.malformed.txt
fixtures/messages/FlagImportError.rejected.bare.txt
fixtures/messages/FlagImportError.rejected.decoded.txt
fixtures/messages/FlagImportError.rejected.empty.txt
fixtures/messages/FlagImportError.rejected.one.txt
fixtures/messages/FlagImportError.rejected.unknownKey.txt
fixtures/messages/FlagImportError.unsupportedFormatVersion.txt
fixtures/messages/FlagMappingAuditError.incomplete.txt
fixtures/messages/FlagQRCodeError.corrupt.txt
fixtures/messages/FlagQRCodeError.imageGenerationFailed.txt
fixtures/messages/FlagQRCodeError.payloadTooLarge.many.txt
fixtures/messages/FlagQRCodeError.payloadTooLarge.one.txt
fixtures/messages/FlagQRCodeError.unrecognisedFormat.txt
fixtures/messages/FlagSchemaError.malformed.txt
fixtures/messages/FlagSchemaError.notPublished.txt
fixtures/messages/FlagSchemaError.unsupportedFormatVersion.txt
fixtures/messages/FlagSerializationError.nonFiniteNumber.txt
fixtures/messages/FlagSignalError.notAcknowledged.txt
fixtures/messages/FlagSignalError.unavailableAppGroup.txt
fixtures/messages/RemoteOverrideError.malformed.txt
fixtures/messages/RemoteOverrideError.rejected.bare.txt
fixtures/messages/RemoteOverrideError.rejected.decoded.txt
fixtures/messages/RemoteOverrideError.rejected.documented.txt
fixtures/messages/RemoteOverrideError.rejected.empty.txt
fixtures/messages/RemoteOverrideError.rejected.one.txt
fixtures/qr/golden.json
fixtures/qr/golden.qr.txt
fixtures/qr/single.json
fixtures/qr/single.qr.txt
fixtures/qr/stored.json
fixtures/qr/stored.qr.txt
fixtures/records/default.txt
fixtures/records/empty.txt
fixtures/records/full.txt
fixtures/remote/bad-date.expected.json
fixtures/remote/bad-date.payload.json
fixtures/remote/empty-object.expected.json
fixtures/remote/empty-object.payload.json
fixtures/remote/every-problem-sorted.expected.json
fixtures/remote/every-problem-sorted.payload.json
fixtures/remote/every-remote-flag.expected.json
fixtures/remote/every-remote-flag.payload.json
fixtures/remote/integer-overflow.expected.json
fixtures/remote/integer-overflow.payload.json
fixtures/remote/long-string-value.expected.json
fixtures/remote/long-string-value.payload.json
fixtures/remote/not-json.expected.json
fixtures/remote/not-json.payload.json
fixtures/remote/nulls-are-absent.expected.json
fixtures/remote/nulls-are-absent.payload.json
fixtures/remote/object-for-string.expected.json
fixtures/remote/object-for-string.payload.json
fixtures/remote/offset-date.expected.json
fixtures/remote/offset-date.payload.json
fixtures/remote/records-as-string.expected.json
fixtures/remote/records-as-string.payload.json
fixtures/remote/records-duplicate-key.expected.json
fixtures/remote/records-duplicate-key.payload.json
fixtures/remote/records-missing-field.expected.json
fixtures/remote/records-missing-field.payload.json
fixtures/remote/records-mixed-forms.expected.json
fixtures/remote/records-mixed-forms.payload.json
fixtures/remote/records-natural.expected.json
fixtures/remote/records-natural.payload.json
fixtures/remote/records-unknown-case.expected.json
fixtures/remote/records-unknown-case.payload.json
fixtures/remote/records-wrong-field-type.expected.json
fixtures/remote/records-wrong-field-type.payload.json
fixtures/remote/top-level-scalar.expected.json
fixtures/remote/top-level-scalar.payload.json
fixtures/remote/type-mismatch.expected.json
fixtures/remote/type-mismatch.payload.json
fixtures/remote/unknown-case.expected.json
fixtures/remote/unknown-case.payload.json
fixtures/remote/url-needing-encoding.expected.json
fixtures/remote/url-needing-encoding.payload.json
fixtures/remote/whole-numbers.expected.json
fixtures/remote/whole-numbers.payload.json
fixtures/schema/golden-kebab.flag-schema.json
fixtures/schema/golden-snake.flag-schema.json
fixtures/url/parse.tsv
```

Sanity checks against the probe facts:
- the third row of `json/numbers.tsv` is `4008000000000000`, a tab, then `3`; the fourth is `3fb999999999999a`, a tab, then `0.10000000000000001`;
- `json/pretty.json` has `"emptyArray" : [` followed by an empty line and `  ],`;
- `dates/format.tsv` has a row `-0.5`, a tab, then `1969-12-31T23:59:59Z`.

Running the script twice gives byte-identical output; every random case list is seeded.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `FixturesTest` has 2 passing tests.

- [ ] **Step 5: Commit**

```bash
git add fixture-generator/Package.swift fixture-generator/Package.resolved fixture-generator/Sources scripts fixtures \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/Fixtures.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/FixturesTest.kt
git commit -m "$(cat <<'EOF'
Add the Swift fixture generator and the golden fixtures it writes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A3: Keys and key encoding

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagKey.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/KeyEncoding.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagKeyTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/KeyEncodingGoldenTest.kt`

**Interfaces:**
- Consumes: `Fixtures.tsv(path)` (Task A2).
- Produces (package `com.andyyhope.featureflag.values`):
  - `@JvmInline public value class FlagKey(public val rawValue: String) { override fun toString(): String }`
  - `public data class FlagKeyPath(public val propertyNames: List<String>) { public fun appending(propertyName: String): FlagKeyPath; public fun prepending(propertyName: String): FlagKeyPath; public companion object { public val ROOT: FlagKeyPath } }`
  - `public class KeyEncoding(public val separator: String = ".", public val transform: (String) -> String) { public fun key(path: FlagKeyPath): FlagKey; public companion object { public val Kebabcase: KeyEncoding; public val Snakecase: KeyEncoding; public val Verbatim: KeyEncoding; internal fun splitWords(name: String): List<String> } }`

- [ ] **Step 1: Write the failing tests**

Ported from `FlagKeyTests` and `KeyEncodingEdgeCaseTests`, plus the Turkish-locale pin from Review Focus 1. Swift's `JSONEncoder`/`JSONDecoder` round trips of `FlagKey` have no Kotlin counterpart; `FlagKey` is a plain string wherever M1c writes one.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagKeyTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Ported from FlagKeyTests and KeyEncodingEdgeCaseTests. */
class FlagKeyTest {

    private fun kebab(name: String): String = KeyEncoding.Kebabcase.key(FlagKeyPath(listOf(name))).rawValue

    // Paths

    @Test
    fun rootPathIsEmpty() {
        assertEquals(emptyList(), FlagKeyPath.ROOT.propertyNames)
    }

    @Test
    fun appendingBuildsAPathWithoutMutatingTheOriginal() {
        val base = FlagKeyPath.ROOT.appending("checkout")
        val path = base.appending("applePay")
        assertEquals(listOf("checkout", "applePay"), path.propertyNames)
        assertEquals(listOf("checkout"), base.propertyNames)
    }

    @Test
    fun prependingPutsAComponentAtTheFrontWithoutMutatingTheOriginal() {
        val base = FlagKeyPath(listOf("express", "oneTap"))
        assertEquals(listOf("checkout", "express", "oneTap"), base.prepending("checkout").propertyNames)
        assertEquals(listOf("express", "oneTap"), base.propertyNames)
    }

    // Kebab-case

    @Test
    fun kebabcaseSplitsWordsAsSwiftDoes() {
        assertEquals("new-onboarding", kebab("newOnboarding"))
        assertEquals("checkout", kebab("checkout"))
        assertEquals("enable-api", kebab("enableAPI"))
        assertEquals("api-key", kebab("apiKey"))
        assertEquals("use-https-only", kebab("useHTTPSOnly"))
        assertEquals("checkout-v2", kebab("checkoutV2"))
        assertEquals("api", kebab("API"))
        assertEquals("new-onboarding", kebab("NewOnboarding"))
        assertEquals("v2-enabled", kebab("v2Enabled"))
        assertEquals("enable2-fa", kebab("enable2FA"))
        assertEquals("legacy_name", kebab("legacy_name"))
    }

    @Test
    fun splittingHandlesTheSmallestNames() {
        assertEquals(listOf("a"), KeyEncoding.splitWords("a"))
        assertEquals(listOf("A"), KeyEncoding.splitWords("A"))
        assertEquals(emptyList(), KeyEncoding.splitWords(""))
        assertEquals(listOf("API"), KeyEncoding.splitWords("API"))
    }

    @Test
    fun nestedComponentsJoinWithADot() {
        val path = FlagKeyPath.ROOT.appending("checkout").appending("express").appending("oneTap")
        assertEquals(FlagKey("checkout.express.one-tap"), KeyEncoding.Kebabcase.key(path))
        assertEquals(FlagKey(""), KeyEncoding.Kebabcase.key(FlagKeyPath.ROOT))
        assertEquals(FlagKey("one-tap"), KeyEncoding.Kebabcase.key(FlagKeyPath(listOf("oneTap"))))
    }

    @Test
    fun encodingIgnoresTheDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("enable-api", kebab("enableAPI"))
            assertEquals("user-id", kebab("userID"))
            assertEquals("checkout.is_in", KeyEncoding.Snakecase.key(FlagKeyPath(listOf("checkout", "isIN"))).rawValue)
        } finally {
            Locale.setDefault(saved)
        }
    }

    // Other encodings

    @Test
    fun snakecaseAndVerbatim() {
        val path = FlagKeyPath.ROOT.appending("checkout").appending("applePay")
        assertEquals(FlagKey("checkout.apple_pay"), KeyEncoding.Snakecase.key(path))
        assertEquals(FlagKey("checkout.applePay"), KeyEncoding.Verbatim.key(path))
    }

    @Test
    fun customEncodingsAreHonoured() {
        val path = FlagKeyPath(listOf("checkout", "applePay"))
        assertEquals(FlagKey("checkout/applePay"), KeyEncoding(separator = "/") { it }.key(path))
        assertEquals(FlagKey("CHECKOUT.APPLEPAY"), KeyEncoding { it.uppercase() }.key(path))
        assertEquals(FlagKey("ab"), KeyEncoding(separator = "") { it }.key(FlagKeyPath(listOf("a", "b"))))
        assertEquals(FlagKey("a::b"), KeyEncoding(separator = "::") { it }.key(FlagKeyPath(listOf("a", "b"))))
    }

    // Keys

    @Test
    fun keysAreValuesThatDescribeThemselvesAsTheirRawValue() {
        val storage = mutableMapOf(FlagKey("a.b") to 1)
        assertEquals(1, storage[FlagKey("a.b")])
        assertEquals(FlagKey("a.b"), FlagKey("a.b"))
        assertNotEquals(FlagKey("a.b"), FlagKey("a.c"))
        assertEquals("checkout.apple-pay", FlagKey("checkout.apple-pay").toString())
        assertEquals("checkout.apple-pay", "${FlagKey("checkout.apple-pay")}")
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/KeyEncodingGoldenTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.test.assertEquals

class KeyEncodingGoldenTest {

    @Test
    fun everyPropertyNameEncodesAsAppleDoes() {
        val rows = Fixtures.tsv("keys/key-encoding.tsv")
        for ((name, kebab, snake) in rows) {
            val path = FlagKeyPath(listOf(name))
            assertEquals(kebab, KeyEncoding.Kebabcase.key(path).rawValue, "kebab-case of '$name'")
            assertEquals(snake, KeyEncoding.Snakecase.key(path).rawValue, "snake_case of '$name'")
        }
        assertEquals(true, rows.size > 60)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.values.*' --console=plain
```

Expected: FAIL at compilation with `FlagKeyTest.kt:11:47 Unresolved reference 'KeyEncoding'.`

- [ ] **Step 3: Write the implementation**

`splitWords` walks code points, so `Character.isUpperCase`/`isLowerCase` see the same Unicode properties Swift's `Character.isUppercase` uses (the golden includes `ÄpfelÖl`, Greek, CJK and `İ`). `lowercase()` is locale-invariant.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagKey.kt`:

```kotlin
package com.andyyhope.featureflag.values

/**
 * The string a value source actually stores a flag under.
 *
 * Keys are produced by applying a [KeyEncoding] to a [FlagKeyPath]; they are not written by
 * hand except when reading an existing store.
 */
@JvmInline
public value class FlagKey(public val rawValue: String) {
    override fun toString(): String = rawValue
}

/**
 * The position of a flag in its container tree, recorded as raw property names.
 *
 * Generated code emits paths rather than finished keys because key encoding is configured at
 * runtime on the pole, and compile-time metadata cannot know it.
 */
public data class FlagKeyPath(public val propertyNames: List<String>) {

    public fun appending(propertyName: String): FlagKeyPath = FlagKeyPath(propertyNames + propertyName)

    public fun prepending(propertyName: String): FlagKeyPath = FlagKeyPath(listOf(propertyName) + propertyNames)

    public companion object {
        public val ROOT: FlagKeyPath = FlagKeyPath(emptyList())
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/KeyEncoding.kt`:

```kotlin
package com.andyyhope.featureflag.values

/**
 * Turns a [FlagKeyPath] into the [FlagKey] a value source stores.
 *
 * Each path component is transformed individually, then joined with [separator], so nesting
 * always survives the transform intact.
 */
public class KeyEncoding(
    public val separator: String = ".",
    public val transform: (String) -> String,
) {

    public fun key(path: FlagKeyPath): FlagKey = FlagKey(path.propertyNames.joinToString(separator) { transform(it) })

    public companion object {

        /** `checkout.express.oneTap` becomes `checkout.express.one-tap`. The default. */
        public val Kebabcase: KeyEncoding = joinedWords("-")

        /** `checkout.express.oneTap` becomes `checkout.express.one_tap`. */
        public val Snakecase: KeyEncoding = joinedWords("_")

        /** Property names are used exactly as written. */
        public val Verbatim: KeyEncoding = KeyEncoding { it }

        private fun joinedWords(joiner: String): KeyEncoding =
            KeyEncoding { name -> splitWords(name).joinToString(joiner) { it.lowercase() } }

        /**
         * Splits a camel-cased identifier into words, exactly as FeatureFlag 0.12.0 does.
         *
         * A word starts at an uppercase character that either follows a non-uppercase character
         * (`applePay` → `apple`, `Pay`) or ends a run of them (`useHTTPSOnly` → `use`, `HTTPS`,
         * `Only`). Digits stay attached to the word they trail (`checkoutV2` → `checkout`, `V2`).
         */
        internal fun splitWords(name: String): List<String> {
            val codePoints = name.codePoints().toArray()
            val words = mutableListOf<String>()
            val current = StringBuilder()
            for ((index, codePoint) in codePoints.withIndex()) {
                if (index > 0 && Character.isUpperCase(codePoint)) {
                    val previousIsUpper = Character.isUpperCase(codePoints[index - 1])
                    val nextIsLower = index + 1 < codePoints.size && Character.isLowerCase(codePoints[index + 1])
                    if (!previousIsUpper || nextIsLower) {
                        words += current.toString()
                        current.setLength(0)
                    }
                }
                current.appendCodePoint(codePoint)
            }
            if (current.isNotEmpty()) words += current.toString()
            return words
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `FlagKeyTest` (10) and `KeyEncodingGoldenTest` (1, covering 66 property names) pass.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values
git commit -m "$(cat <<'EOF'
Port FlagKey, FlagKeyPath and KeyEncoding

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A4: Value types, boxes and the serialization error

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueType.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueBox.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagSerializationException.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueTypeTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueBoxTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagSerializationExceptionTest.kt`

**Interfaces:**
- Consumes: `FlagKey` (Task A3); `Fixtures.text` (Task A2).
- Produces:
  - `public sealed class FlagValueType` with `data object Bool, Int, Double, Float, String, Data, Date, Url`, `data class Array(public val element: FlagValueType)`, `data class Dictionary(public val value: FlagValueType)`, `public val typeName: kotlin.String`, `public companion object { public fun parse(name: kotlin.String): FlagValueType? }`
  - `public sealed interface FlagValueBox` with `data class Bool(value: Boolean)`, `Int(value: Long)`, `Double(value: kotlin.Double)`, `Float(value: kotlin.Float)`, `String(value: kotlin.String)`, `class Data(value: ByteArray)` (content equality), `data class Date(value: Instant)`, `class Url(value: URI)` (equality by `toString()`), `data class Array(value: List<FlagValueBox>)`, `data class Dictionary(value: Map<kotlin.String, FlagValueBox>)`, `public fun matches(type: FlagValueType): Boolean`, `public val containsNonFiniteNumber: Boolean`
  - `internal object MessageText { fun quoted(text: String): String; fun arrayOf(count: Int): String; fun objectWith(count: Int): String }` (package `com.andyyhope.featureflag.errors`; Task A8 adds `date`)
  - `public sealed class FlagSerializationException(message: String) : RuntimeException(message) { public class NonFiniteNumber(public val key: FlagKey?) }`

- [ ] **Step 1: Write the failing tests**

Ported from `FlagValueTypeTests` (names, parsing and matching) and `FlagRecordTests` (non-finite detection). `doublesCompareAsKotlinDataClassesDo` pins a documented difference: Swift compares `.double` with IEEE `==`.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueTypeTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from FlagValueTypeTests (names and parsing). */
class FlagValueTypeTest {

    @Test
    fun everyPrimitiveHasAName() {
        val expected = listOf(
            FlagValueType.Bool to "bool", FlagValueType.Int to "int", FlagValueType.Double to "double",
            FlagValueType.Float to "float", FlagValueType.String to "string", FlagValueType.Data to "data",
            FlagValueType.Date to "date", FlagValueType.Url to "url",
        )
        for ((type, name) in expected) assertEquals(name, type.typeName)
    }

    @Test
    fun collectionNamesCarryAndNestTheirElementType() {
        assertEquals("array<string>", FlagValueType.Array(FlagValueType.String).typeName)
        assertEquals("dictionary<int>", FlagValueType.Dictionary(FlagValueType.Int).typeName)
        assertEquals(
            "array<dictionary<array<bool>>>",
            FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Array(FlagValueType.Bool))).typeName,
        )
    }

    @Test
    fun everyNameRoundTrips() {
        val types = listOf(
            FlagValueType.Bool, FlagValueType.Int, FlagValueType.Double, FlagValueType.Float, FlagValueType.String,
            FlagValueType.Data, FlagValueType.Date, FlagValueType.Url,
            FlagValueType.Array(FlagValueType.String), FlagValueType.Dictionary(FlagValueType.Int),
            FlagValueType.Array(FlagValueType.Array(FlagValueType.Bool)),
            FlagValueType.Dictionary(FlagValueType.Dictionary(FlagValueType.Date)),
            FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Array(FlagValueType.Url))),
        )
        for (type in types) assertEquals(type, FlagValueType.parse(type.typeName), "failed for $type")
    }

    @Test
    fun unknownAndMalformedNamesAreRejected() {
        val names = listOf(
            "", "banana", "Bool", "INT", "array", "dictionary",
            "array<>", "array<banana>", "array<int", "array int>", "dictionary<>", "dictionary<banana>", "array<<int>>",
        )
        for (name in names) assertNull(FlagValueType.parse(name), "should reject $name")
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueBoxTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Ported from FlagValueTypeTests (matching) and FlagRecordTests (non-finite detection). */
class FlagValueBoxTest {

    @Test
    fun aBoxMatchesItsOwnTypeAndNoOther() {
        assertTrue(FlagValueBox.Bool(true).matches(FlagValueType.Bool))
        assertTrue(FlagValueBox.Int(1).matches(FlagValueType.Int))
        assertTrue(FlagValueBox.Url(URI("https://a.example")).matches(FlagValueType.Url))
        assertFalse(FlagValueBox.Int(1).matches(FlagValueType.Bool))
        assertFalse(FlagValueBox.Double(1.0).matches(FlagValueType.Int))
        assertFalse(FlagValueBox.String("a").matches(FlagValueType.Url))
    }

    @Test
    fun anEmptyCollectionMatchesAnyCollectionOfThatShape() {
        assertTrue(FlagValueBox.Array(emptyList()).matches(FlagValueType.Array(FlagValueType.Bool)))
        val dictionaries = FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Int))
        assertTrue(FlagValueBox.Array(emptyList()).matches(dictionaries))
        assertTrue(FlagValueBox.Dictionary(emptyMap()).matches(FlagValueType.Dictionary(FlagValueType.String)))
    }

    @Test
    fun aCollectionDoesNotMatchAScalarOrTheOtherShape() {
        val bools = FlagValueBox.Array(listOf(FlagValueBox.Bool(true)))
        assertFalse(bools.matches(FlagValueType.Bool))
        assertFalse(FlagValueBox.Bool(true).matches(FlagValueType.Array(FlagValueType.Bool)))
        assertFalse(bools.matches(FlagValueType.Dictionary(FlagValueType.Bool)))
    }

    @Test
    fun oneWrongElementFailsTheWholeCollectionAllTheWayDown() {
        val mixed = FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.String("a")))
        assertFalse(mixed.matches(FlagValueType.Array(FlagValueType.Int)))
        assertFalse(
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1), "b" to FlagValueBox.Bool(true)))
                .matches(FlagValueType.Dictionary(FlagValueType.Int)),
        )
        val nestedInts = FlagValueType.Array(FlagValueType.Array(FlagValueType.Int))
        assertTrue(FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Int(1))))).matches(nestedInts))
        val nestedStrings = FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.String("a")))))
        assertFalse(nestedStrings.matches(nestedInts))
    }

    @Test
    fun nonFiniteNumbersAreFoundAnywhere() {
        assertTrue(FlagValueBox.Double(Double.POSITIVE_INFINITY).containsNonFiniteNumber)
        assertTrue(FlagValueBox.Float(Float.NaN).containsNonFiniteNumber)
        assertTrue(FlagValueBox.Array(listOf(FlagValueBox.Double(Double.NaN))).containsNonFiniteNumber)
        val dictionary = FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Float(Float.NEGATIVE_INFINITY)))
        assertTrue(dictionary.containsNonFiniteNumber)
        assertFalse(FlagValueBox.Double(0.0).containsNonFiniteNumber)
        assertFalse(FlagValueBox.Float(-1.5f).containsNonFiniteNumber)
        assertFalse(FlagValueBox.Int(Long.MAX_VALUE).containsNonFiniteNumber)
    }

    @Test
    fun dataAndUrlCompareByContent() {
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), FlagValueBox.Data(byteArrayOf(1, 2)))
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)).hashCode(), FlagValueBox.Data(byteArrayOf(1, 2)).hashCode())
        assertNotEquals(FlagValueBox.Data(byteArrayOf(1, 2)), FlagValueBox.Data(byteArrayOf(2, 1)))
        assertEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueBox.Url(URI("https://example.com")))
        assertNotEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueBox.Url(URI("https://example.com/")))
        assertEquals(FlagValueBox.Date(Instant.ofEpochSecond(1000)), FlagValueBox.Date(Instant.ofEpochSecond(1000)))
    }

    @Test
    fun doublesCompareAsKotlinDataClassesDo() {
        // Documented difference: Swift's FlagValueBox uses IEEE ==, so there 0.0 == -0.0 and NaN != NaN.
        assertNotEquals(FlagValueBox.Double(0.0), FlagValueBox.Double(-0.0))
        assertEquals(FlagValueBox.Double(Double.NaN), FlagValueBox.Double(Double.NaN))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextTest.kt`:

```kotlin
package com.andyyhope.featureflag.errors

import org.junit.Test
import kotlin.test.assertEquals

class MessageTextTest {

    @Test
    fun quotingEscapesWhatWouldBreakALine() {
        assertEquals("\"a\\\\b\\nc\\rd\\te\"", MessageText.quoted("a\\b\nc\rd\te"))
        assertEquals("\"\"", MessageText.quoted(""))
    }

    @Test
    fun quotingCapsAtFortyCharactersAfterEscaping() {
        val forty = "x".repeat(40)
        assertEquals("\"$forty\"", MessageText.quoted(forty))
        assertEquals("\"$forty…\"", MessageText.quoted(forty + "y"))
        assertEquals("\"${"\\n".repeat(20)}…\"", MessageText.quoted("\n".repeat(21)))
        assertEquals("\"${"😀".repeat(40)}…\"", MessageText.quoted("😀".repeat(41)))
    }

    @Test
    fun collectionsAreDescribedInWords() {
        assertEquals("an array of 3", MessageText.arrayOf(3))
        assertEquals("an object with 1 field(s)", MessageText.objectWith(1))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagSerializationExceptionTest.kt`:

```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class FlagSerializationExceptionTest {

    @Test
    fun nonFiniteNumberMessageMatchesSwiftVerbatim() {
        assertEquals(
            Fixtures.text("messages/FlagSerializationError.nonFiniteNumber.txt"),
            FlagSerializationException.NonFiniteNumber(FlagKey("ratio")).message,
        )
    }

    @Test
    fun withoutAKeyTheMessageSaysAValue() {
        assertEquals(
            "A value holds an infinity or a NaN, and JSON can represent neither. " +
                "Use a finite value, or a sentinel the flag's type already has.",
            FlagSerializationException.NonFiniteNumber(null).message,
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
```

Expected: FAIL at compilation with `FlagSerializationExceptionTest.kt:14:13 Unresolved reference 'FlagSerializationException'.`

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueType.kt`:

```kotlin
package com.andyyhope.featureflag.values

/**
 * The type a flag declares, independent of any particular value.
 *
 * Descriptors carry this so that a companion app can render the right editor, and so that
 * remote payloads can be validated strictly before anything is applied.
 */
public sealed class FlagValueType {
    public data object Bool : FlagValueType()
    public data object Int : FlagValueType()
    public data object Double : FlagValueType()
    public data object Float : FlagValueType()
    public data object String : FlagValueType()
    public data object Data : FlagValueType()
    public data object Date : FlagValueType()
    public data object Url : FlagValueType()
    public data class Array(public val element: FlagValueType) : FlagValueType()
    public data class Dictionary(public val value: FlagValueType) : FlagValueType()

    /** The type's name as it appears in a published schema, e.g. `bool`, `array<string>`, `dictionary<int>`. */
    public val typeName: kotlin.String
        get() = when (this) {
            Bool -> "bool"
            Int -> "int"
            Double -> "double"
            Float -> "float"
            String -> "string"
            Data -> "data"
            Date -> "date"
            Url -> "url"
            is Array -> "array<${element.typeName}>"
            is Dictionary -> "dictionary<${value.typeName}>"
        }

    public companion object {

        /** Parses a name produced by [typeName], or returns `null` for anything else. */
        public fun parse(name: kotlin.String): FlagValueType? = when (name) {
            "bool" -> Bool
            "int" -> Int
            "double" -> Double
            "float" -> Float
            "string" -> String
            "data" -> Data
            "date" -> Date
            "url" -> Url
            else ->
                nested(name, "array")?.let(::parse)?.let(::Array)
                    ?: nested(name, "dictionary")?.let(::parse)?.let(::Dictionary)
        }

        /** The contents of `prefix<...>`, or `null` if the name is not that shape. */
        private fun nested(name: kotlin.String, prefix: kotlin.String): kotlin.String? {
            val opening = "$prefix<"
            if (!name.startsWith(opening) || !name.endsWith(">")) return null
            return name.substring(opening.length, name.length - 1)
        }
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueBox.kt`:

```kotlin
package com.andyyhope.featureflag.values

import java.net.URI
import java.time.Instant

/**
 * The canonical boxed representation of a flag value.
 *
 * Every storage and transport mechanism reads and writes this one type, so each supported
 * Kotlin type needs only a single conversion to be usable everywhere.
 *
 * Equality is Kotlin data-class equality: `Double(0.0) != Double(-0.0)` and `Double(NaN) ==
 * Double(NaN)`, where Swift's `FlagValueBox` compares with IEEE `==`.
 */
public sealed interface FlagValueBox {
    public data class Bool(public val value: Boolean) : FlagValueBox
    public data class Int(public val value: Long) : FlagValueBox
    public data class Double(public val value: kotlin.Double) : FlagValueBox
    public data class Float(public val value: kotlin.Float) : FlagValueBox
    public data class String(public val value: kotlin.String) : FlagValueBox

    /** Bytes. Equal when the contents are equal. */
    public class Data(public val value: ByteArray) : FlagValueBox {
        override fun equals(other: Any?): Boolean = other is Data && value.contentEquals(other.value)
        override fun hashCode(): kotlin.Int = value.contentHashCode()
        override fun toString(): kotlin.String = "Data(${value.size} bytes)"
    }

    public data class Date(public val value: Instant) : FlagValueBox

    /** A URL. Equal when the text is equal, as Swift's `URL` is. */
    public class Url(public val value: URI) : FlagValueBox {
        override fun equals(other: Any?): Boolean = other is Url && value.toString() == other.value.toString()
        override fun hashCode(): kotlin.Int = value.toString().hashCode()
        override fun toString(): kotlin.String = "Url($value)"
    }

    public data class Array(public val value: List<FlagValueBox>) : FlagValueBox
    public data class Dictionary(public val value: Map<kotlin.String, FlagValueBox>) : FlagValueBox

    /**
     * Whether this box holds the type a flag declared.
     *
     * An empty collection matches any collection of that shape.
     */
    public fun matches(type: FlagValueType): Boolean = when (this) {
        is Bool -> type == FlagValueType.Bool
        is Int -> type == FlagValueType.Int
        is Double -> type == FlagValueType.Double
        is Float -> type == FlagValueType.Float
        is String -> type == FlagValueType.String
        is Data -> type == FlagValueType.Data
        is Date -> type == FlagValueType.Date
        is Url -> type == FlagValueType.Url
        is Array -> type is FlagValueType.Array && value.all { it.matches(type.element) }
        is Dictionary -> type is FlagValueType.Dictionary && value.values.all { it.matches(type.value) }
    }

    /** Whether this value holds an infinity or a NaN, anywhere inside it. JSON can represent neither. */
    public val containsNonFiniteNumber: Boolean
        get() = when (this) {
            is Double -> !value.isFinite()
            is Float -> !value.isFinite()
            is Array -> value.any { it.containsNonFiniteNumber }
            is Dictionary -> value.values.any { it.containsNonFiniteNumber }
            else -> false
        }
}
```

`MessageText.quoted` ports `flagQuoted` from `ErrorMessages.swift`. It counts code points where Swift counts grapheme clusters, because `BreakIterator` is outside the allowed JDK APIs; the KDoc says so.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt`:

```kotlin
package com.andyyhope.featureflag.errors

/**
 * The value-rendering helpers behind every error message, ported from `ErrorMessages.swift`.
 *
 * Each problem is a bullet, so a raw newline in a value would put the rest of it in column zero
 * where it reads as another bullet. Length is capped for the same reason.
 */
internal object MessageText {

    private const val MAX_QUOTED_LENGTH = 40

    /**
     * A value as one short, single-line, quoted fragment: `\`, newline, carriage return and tab
     * are escaped, and anything over 40 characters is cut to 40 plus `…`.
     *
     * Swift counts grapheme clusters; this counts code points, so a value whose 40th character
     * is part of a combining sequence or an emoji ZWJ sequence is cut at a different place.
     */
    fun quoted(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        if (escaped.codePointCount(0, escaped.length) <= MAX_QUOTED_LENGTH) return "\"$escaped\""
        return "\"${escaped.substring(0, escaped.offsetByCodePoints(0, MAX_QUOTED_LENGTH))}…\""
    }

    fun arrayOf(count: Int): String = "an array of $count"

    fun objectWith(count: Int): String = "an object with $count field(s)"
}
```

The message is verbatim from `ErrorMessages.swift` when a key is known. Swift's case always has a key; the contract's `FlagKey?` allows code that has none (`toJson`, `RecordJson.encode`) to throw it too, saying "A value" instead.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagSerializationException.kt`:

```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey

/** Something that cannot be written out. */
public sealed class FlagSerializationException(message: String) : RuntimeException(message) {

    /**
     * A value holds an infinity or a NaN, which JSON cannot represent. [key] names the flag when
     * the code that found it knows which one it was.
     */
    public class NonFiniteNumber(public val key: FlagKey?) : FlagSerializationException(nonFiniteMessage(key))
}

private fun nonFiniteMessage(key: FlagKey?): String {
    val subject = if (key == null) "A value" else "'${key.rawValue}'"
    return "$subject holds an infinity or a NaN, and JSON can represent neither. " +
        "Use a finite value, or a sentinel the flag's type already has."
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `FlagValueTypeTest` (4), `FlagValueBoxTest` (7), `MessageTextTest` (3) and `FlagSerializationExceptionTest` (2) pass.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Port FlagValueType, FlagValueBox and FlagSerializationException

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A5: Strict JSON reader

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonValue.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonReader.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderGoldenTest.kt`

**Interfaces:**
- Consumes: `Fixtures.tsv` (Task A2).
- Produces (package `com.andyyhope.featureflag.json`, all internal):
  - `internal sealed interface JsonValue { data object Null; data class Bool(val value: Boolean); data class Integer(val value: Long); data class Real(val value: Double); data class Text(val value: String); data class Array(val items: List<JsonValue>); data class Object(val fields: Map<String, JsonValue>) }` (`Object.fields` is a `LinkedHashMap` in document order, and the first duplicate wins)
  - `internal class JsonSyntaxException(message: String) : IllegalArgumentException(message)`
  - `internal object JsonReader { fun parse(bytes: ByteArray): JsonValue }` (throws `JsonSyntaxException`; the top level must be an array or an object)

- [ ] **Step 1: Write the failing tests**

`JsonReaderGoldenTest` checks acceptance against every row of `json/parse.tsv`; Task A7 adds the re-serialisation check once the writer exists. It checks number classification against `json/number-parse.tsv`, with one listed `NSDecimalNumber` deviation.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonReaderTest {

    private fun parse(text: String): JsonValue = JsonReader.parse(text.encodeToByteArray())

    private fun number(literal: String): JsonValue = (parse("[$literal]") as JsonValue.Array).items.single()

    @Test
    fun readsEveryKindOfValue() {
        val parsed = parse("""{"a":[true,false,null,1,1.5,"x"],"b":{}}""")
        assertEquals(
            JsonValue.Object(
                mapOf(
                    "a" to JsonValue.Array(
                        listOf(
                            JsonValue.Bool(true), JsonValue.Bool(false), JsonValue.Null, JsonValue.Integer(1),
                            JsonValue.Real(1.5), JsonValue.Text("x"),
                        ),
                    ),
                    "b" to JsonValue.Object(emptyMap()),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun theFirstOfARepeatedKeyWinsAndKeepsItsPlace() {
        val fields = (parse("""{"b":1,"a":2,"b":3}""") as JsonValue.Object).fields
        assertEquals(listOf("b", "a"), fields.keys.toList())
        assertEquals(JsonValue.Integer(1), fields["b"])
    }

    @Test
    fun numbersAreClassifiedAsNSNumberAnswersAsInt() {
        assertEquals(JsonValue.Integer(3), number("3.0"))
        assertEquals(JsonValue.Integer(100), number("1e2"))
        assertEquals(JsonValue.Integer(0), number("-0"))
        assertEquals(JsonValue.Integer(0), number("-0.0"))
        assertEquals(JsonValue.Integer(Long.MAX_VALUE), number("9.2233720368547758E18"))
        assertEquals(JsonValue.Real(1.0E19), number("10000000000000000000"))
        assertEquals(JsonValue.Real(0.1), number("0.1"))
        assertEquals(JsonValue.Real(Double.NEGATIVE_INFINITY), number("-1e400"))
    }

    @Test
    fun appleExtensionsAreAccepted() {
        assertEquals(JsonValue.Array(listOf(JsonValue.Integer(1))), parse("[1,]"))
        assertEquals(JsonValue.Object(mapOf("a" to JsonValue.Integer(1))), parse("{\"a\":1,}"))
        val withByteOrderMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x5B, 0x5D)
        assertEquals(JsonValue.Array(emptyList()), JsonReader.parse(withByteOrderMark))
    }

    @Test
    fun nestingStopsAt513() {
        parse("[".repeat(513) + "]".repeat(513))
        assertFailsWith<JsonSyntaxException> { parse("[".repeat(514) + "]".repeat(514)) }
        assertFailsWith<JsonSyntaxException> { parse("[".repeat(100_000) + "]".repeat(100_000)) }
    }

    @Test
    fun invalidUtf8IsRejected() {
        val documents = listOf(
            byteArrayOf(0x5B, 0x22, 0xFF.toByte(), 0x22, 0x5D),
            byteArrayOf(0x5B, 0x22, 0xC0.toByte(), 0x80.toByte(), 0x22, 0x5D),
            byteArrayOf(0x5B, 0x22, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x22, 0x5D),
            byteArrayOf(0x5B, 0x22, 0xE2.toByte(), 0x82.toByte(), 0x22, 0x5D),
        )
        for (document in documents) assertFailsWith<JsonSyntaxException> { JsonReader.parse(document) }
    }

    @Test
    fun escapesDecodeToTheirCharacters() {
        val text = (parse("""["\/\b\f\n\r\t\"\\Aé😀"]""") as JsonValue.Array).items.single()
        assertEquals(JsonValue.Text("/\b\u000C\n\r\t\"\\Aé😀"), text)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderGoldenTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonReaderGoldenTest {

    @Test
    fun acceptsAndRejectsExactlyWhatAppleDoes() {
        val rows = Fixtures.tsv("json/parse.tsv")
        for ((input, expected) in rows) {
            val parsed = runCatching { JsonReader.parse(input.encodeToByteArray()) }
            if (expected == "reject") {
                assertTrue(parsed.isFailure, "Apple rejects ${input.take(80)}, Kotlin accepted it")
            } else {
                val error = parsed.exceptionOrNull()
                assertTrue(parsed.isSuccess, "Apple accepts ${input.take(80)}, Kotlin rejected it: $error")
            }
        }
    }

    @Test
    fun classifiesNumbersAsAppleDoes() {
        val mismatches = mutableListOf<String>()
        for ((literal, kind, canonical) in Fixtures.tsv("json/number-parse.tsv")) {
            val actual = runCatching {
                (JsonReader.parse("[$literal]".encodeToByteArray()) as JsonValue.Array).items.single()
            }
            val description = when (val value = actual.getOrNull()) {
                null -> "reject\t-"
                is JsonValue.Integer -> "integer\t${value.value}"
                is JsonValue.Real -> "real\t%016x".format(value.value.toRawBits())
                else -> "unexpected $value"
            }
            if (description != "$kind\t$canonical" && literal !in KNOWN_NUMBER_DEVIATIONS) {
                mismatches += "${literal.take(40)}: Apple $kind $canonical, Kotlin $description"
            }
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun knownNumberDeviationsReallyDeviate() {
        for (literal in KNOWN_NUMBER_DEVIATIONS) {
            val row = Fixtures.tsv("json/number-parse.tsv").single { it[0] == literal }
            val actual = runCatching { JsonReader.parse("[$literal]".encodeToByteArray()) }.getOrNull()
            assertTrue(row[1] == "reject" || actual == null, "deviation list is stale for ${literal.take(40)}")
        }
    }

    @Test
    fun utf16IsADocumentedDeviation() {
        // Apple detects UTF-16 by its byte order mark and accepts this; Kotlin reads UTF-8 only.
        assertFailsWith<JsonSyntaxException> {
            JsonReader.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '['.code.toByte(), 0, ']'.code.toByte(), 0))
        }
    }

    private companion object {
        /** Literals Apple routes through NSDecimalNumber, whose limits Kotlin does not copy. */
        val KNOWN_NUMBER_DEVIATIONS = setOf("0." + "0".repeat(400) + "1")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.json.*' --console=plain
```

Expected: FAIL at compilation with `JsonReaderGoldenTest.kt:15:40 Unresolved reference 'JsonReader'.`

- [ ] **Step 3: Write the implementation**

A recursive-descent parser over the decoded string. Depth is capped at Apple's 513, so hostile input fails fast instead of overflowing the stack. `fail` returns `Nothing`, which keeps detekt's `ThrowsCount` quiet.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonValue.kt`:

```kotlin
package com.andyyhope.featureflag.json

/**
 * A parsed JSON document, keeping what `JSONSerialization` keeps.
 *
 * Numbers are classified the way `NSNumber` answers `as? Int`: [Integer] when the value is
 * integral and in `Long` range (so `3.0`, `1e2` and `-0.0` are all `Integer`), [Real] otherwise.
 */
internal sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Integer(val value: Long) : JsonValue
    data class Real(val value: Double) : JsonValue
    data class Text(val value: String) : JsonValue
    data class Array(val items: List<JsonValue>) : JsonValue

    /** Fields in document order. When a key repeats, the first occurrence wins. */
    data class Object(val fields: Map<String, JsonValue>) : JsonValue
}

internal class JsonSyntaxException(message: String) : IllegalArgumentException(message)
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonReader.kt`:

```kotlin
package com.andyyhope.featureflag.json

import kotlin.math.floor

/**
 * A strict JSON reader that accepts and rejects exactly what Apple's `JSONSerialization`
 * does for UTF-8 input (measured on macOS 27; see `fixtures/json/parse.tsv`).
 *
 * - The top level must be an array or an object; surrounding space, tab, CR and LF are allowed,
 *   and so is a leading UTF-8 byte order mark.
 * - One trailing comma is allowed after the last element of an array or object.
 * - Containers nest at most 513 deep.
 * - When an object repeats a key, the first value wins.
 * - Positive overflow (`1e400`) is rejected; negative overflow (`-1e400`) reads as -Infinity.
 *
 * Documented deviations: UTF-16/UTF-32 input is rejected (Apple detects and accepts it), and
 * literals with more significant digits than a `Double` carries are rounded correctly here,
 * where Apple's `NSDecimalNumber` path can be off by an ulp or reject very long literals.
 */
internal object JsonReader {

    private const val MAX_DEPTH = 513

    fun parse(bytes: ByteArray): JsonValue {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: throw JsonSyntaxException("The data is not valid UTF-8.")
        return Parser(text.removePrefix(BYTE_ORDER_MARK.toString())).document()
    }

    private class Parser(private val text: String) {
        private var index = 0
        private var depth = 0

        fun document(): JsonValue {
            skipWhitespace()
            val value = when (peek()) {
                '[' -> array()
                '{' -> obj()
                else -> fail("The top level must be an array or an object")
            }
            skipWhitespace()
            if (index != text.length) fail("Unexpected text after the document")
            return value
        }

        private fun value(): JsonValue = when (val char = peek()) {
            '[' -> array()
            '{' -> obj()
            '"' -> JsonValue.Text(string())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (char == '-' || char in '0'..'9') number() else fail("Unexpected character")
        }

        private fun array(): JsonValue {
            enter()
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
            } else {
                while (true) {
                    items += value()
                    skipWhitespace()
                    if (endOfContainer(']')) break
                }
            }
            depth--
            return JsonValue.Array(items)
        }

        private fun obj(): JsonValue {
            enter()
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
            } else {
                while (true) {
                    if (peek() != '"') fail("Expected a string key")
                    val key = string()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    val value = value()
                    if (!fields.containsKey(key)) fields[key] = value
                    skipWhitespace()
                    if (endOfContainer('}')) break
                }
            }
            depth--
            return JsonValue.Object(fields)
        }

        private fun enter() {
            index++
            depth++
            if (depth > MAX_DEPTH) fail("Too deeply nested")
        }

        /** After an element: `,` (optionally followed by the closing bracket), or the closing bracket. */
        private fun endOfContainer(closing: Char): Boolean = when (peek()) {
            ',' -> {
                index++
                skipWhitespace()
                if (peek() == closing) {
                    index++
                    true
                } else {
                    false
                }
            }
            closing -> {
                index++
                true
            }
            else -> fail("Expected ',' or '$closing'")
        }

        private fun string(): String {
            index++
            val out = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> escape(out)
                    char < ' ' -> fail("Unescaped control character in a string")
                    else -> out.append(char)
                }
            }
        }

        private fun escape(out: StringBuilder) {
            when (next()) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> unicodeEscape(out)
                else -> fail("Invalid escape")
            }
        }

        private fun unicodeEscape(out: StringBuilder) {
            val unit = hex4()
            when {
                Character.isHighSurrogate(unit) -> {
                    if (next() != '\\' || next() != 'u') fail("Unpaired surrogate")
                    val low = hex4()
                    if (!Character.isLowSurrogate(low)) fail("Unpaired surrogate")
                    out.append(unit).append(low)
                }
                Character.isLowSurrogate(unit) -> fail("Unpaired surrogate")
                else -> out.append(unit)
            }
        }

        private fun hex4(): Char {
            var value = 0
            repeat(4) {
                val digit = Character.digit(next(), 16)
                if (digit < 0) fail("Invalid \\u escape")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun number(): JsonValue {
            val start = index
            if (peek() == '-') index++
            when {
                peek() == '0' -> index++
                peek() in '1'..'9' -> skipDigits()
                else -> fail("Invalid number")
            }
            var integerLiteral = true
            if (peek() == '.') {
                integerLiteral = false
                index++
                if (peek() !in '0'..'9') fail("Invalid number")
                skipDigits()
            }
            if (peek() == 'e' || peek() == 'E') {
                integerLiteral = false
                index++
                if (peek() == '+' || peek() == '-') index++
                if (peek() !in '0'..'9') fail("Invalid number")
                skipDigits()
            }
            return classify(text.substring(start, index), integerLiteral)
        }

        private fun classify(literal: String, integerLiteral: Boolean): JsonValue {
            if (integerLiteral) literal.toLongOrNull()?.let { return JsonValue.Integer(it) }
            val value = literal.toDouble()
            if (value == Double.POSITIVE_INFINITY || (integerLiteral && value.isInfinite())) {
                fail("Number out of range")
            }
            // `as? Int` on an NSNumber holding a double: integral and within [-2^63, 2^63],
            // where 2^63 itself clamps to Long.MAX_VALUE exactly as Swift's bridge does.
            val integral = !integerLiteral && value == floor(value) && value in LONG_MIN_AS_DOUBLE..TWO_TO_63
            return if (integral) JsonValue.Integer(value.toLong()) else JsonValue.Real(value)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("Invalid literal")
            index += word.length
            return value
        }

        private fun expect(char: Char) {
            if (peek() != char) fail("Expected '$char'")
            index++
        }

        private fun skipDigits() {
            while (peek() in '0'..'9') index++
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in WHITESPACE) index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else END

        private fun next(): Char {
            if (index >= text.length) fail("Unexpected end of data")
            return text[index++]
        }

        private fun fail(reason: String): Nothing = throw JsonSyntaxException("$reason at offset $index.")
    }

    private const val END = '\u0000'
    private val BYTE_ORDER_MARK = Char(0xFEFF)
    private const val WHITESPACE = " \t\n\r"
    private const val TWO_TO_63 = 9.223372036854775808E18
    private const val LONG_MIN_AS_DOUBLE = -9.223372036854775808E18
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `JsonReaderTest` (7) and `JsonReaderGoldenTest` (4) pass.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Add the strict JSON reader that accepts what JSONSerialization accepts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A6: Apple number formatting

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/AppleNumberFormat.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/AppleNumberFormatTest.kt`

**Interfaces:**
- Consumes: `Fixtures.tsv` (Task A2).
- Produces: `internal object AppleNumberFormat { fun format(value: Double): String }` (C `%.17g` of the exact binary value, trailing zeros stripped, exponent `e+XX`/`e-XX` with at least two digits, `-0` for negative zero; `require(value.isFinite())`).

- [ ] **Step 1: Write the failing test**

About 1,000 Apple goldens, the probe-fact table, and the round-trip property over a million random bit patterns (about 2 s).

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/AppleNumberFormatTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleNumberFormatTest {

    @Test
    fun matchesEveryAppleGolden() {
        val mismatches = Fixtures.tsv("json/numbers.tsv").mapNotNull { (bits, expected) ->
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16))
            val actual = AppleNumberFormat.format(value)
            if (actual == expected) null else "$bits: Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun probeFactsHold() {
        assertEquals("3", AppleNumberFormat.format(3.0))
        assertEquals("0.10000000000000001", AppleNumberFormat.format(0.1))
        assertEquals("-0", AppleNumberFormat.format(-0.0))
        assertEquals("10000000000000000", AppleNumberFormat.format(1e16))
        assertEquals("1.0000000000000001e-05", AppleNumberFormat.format(1e-5))
        assertEquals("1e+21", AppleNumberFormat.format(1e21))
        assertEquals("4.9406564584124654e-324", AppleNumberFormat.format(Double.MIN_VALUE))
        assertEquals("0.10000000149011612", AppleNumberFormat.format(0.1f.toDouble()))
    }

    @Test
    fun refusesNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> { AppleNumberFormat.format(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { AppleNumberFormat.format(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun aMillionRandomDoublesReadBackExactly() {
        val random = Random(20261008)
        repeat(1_000_000) {
            val value = Double.fromBits(random.nextLong())
            if (value.isFinite()) {
                val text = AppleNumberFormat.format(value)
                assertEquals(value.toRawBits(), text.toDouble().toRawBits(), "$value was written as $text")
            }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.json.AppleNumberFormatTest' --console=plain
```

Expected: FAIL at compilation with `AppleNumberFormatTest.kt:15:26 Unresolved reference 'AppleNumberFormat'.`

- [ ] **Step 3: Write the implementation**

`BigDecimal(value)` is the exact binary value. Rounding it to 17 significant digits with `HALF_EVEN` is what C's `printf` does, and the exponent is taken after rounding (`9.9999999999999999e16` becomes `1e+17`), as `%g` specifies.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/AppleNumberFormat.kt`:

```kotlin
package com.andyyhope.featureflag.json

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Formats a double the way `JSONSerialization` writes one: C's `printf("%.17g")` of the exact
 * binary value, with trailing zeros (and a trailing `.`) removed.
 *
 * `3.0` → `3`, `0.1` → `0.10000000000000001`, `-0.0` → `-0`, `1e21` → `1e+21`,
 * `1e-5` → `1.0000000000000001e-05`. Java's own `%.17g` is not used: it rounds a decimal
 * approximation rather than the exact binary value, and gets `0.1` wrong.
 */
internal object AppleNumberFormat {

    private const val SIGNIFICANT_DIGITS = 17
    private const val LOWEST_FIXED_EXPONENT = -4
    private val PRECISION = MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN)

    fun format(value: Double): String {
        require(value.isFinite()) { "JSON cannot represent $value" }
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val rounded = BigDecimal(value).round(PRECISION)
        val exponent = rounded.precision() - rounded.scale() - 1
        return if (exponent < LOWEST_FIXED_EXPONENT || exponent >= SIGNIFICANT_DIGITS) {
            scientific(rounded, exponent)
        } else {
            stripTrailingZeros(rounded.toPlainString())
        }
    }

    private fun scientific(rounded: BigDecimal, exponent: Int): String {
        val digits = rounded.unscaledValue().abs().toString().trimEnd('0')
        val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
        val sign = if (rounded.signum() < 0) "-" else ""
        val exponentSign = if (exponent < 0) '-' else '+'
        return "$sign${mantissa}e$exponentSign${abs(exponent).toString().padStart(2, '0')}"
    }

    private fun stripTrailingZeros(plain: String): String =
        if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `AppleNumberFormatTest` (4) passes.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Format doubles exactly as JSONSerialization writes them

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A7: Key order and the JSON writer

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/AppleKeyOrder.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonWriter.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/AppleKeyOrderTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonWriterTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonWriterGoldenTest.kt`

**Interfaces:**
- Consumes: `JsonValue`, `JsonReader.parse` (Task A5); `AppleNumberFormat.format` (Task A6); `Fixtures.text`, `Fixtures.lines`, `Fixtures.tsv` (Task A2).
- Produces:
  - `internal object AppleKeyOrder : Comparator<String>`
  - `internal object JsonWriter { fun pretty(value: JsonValue): String; fun compact(value: JsonValue): String }` (pretty means `.prettyPrinted, .sortedKeys, .withoutEscapingSlashes`, and compact means `.sortedKeys, .withoutEscapingSlashes`; neither has a trailing newline)

- [ ] **Step 1: Write the failing tests**

`JsonWriterGoldenTest.prettyStructure()` must stay identical to `JsonFixtures.prettyStructure` in the generator. When the key-order golden fails, the test prints the first five keys where Apple's order and Kotlin's diverge.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/AppleKeyOrderTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.test.assertEquals

/** The measured probe facts; `JsonWriterGoldenTest` checks ~700 keys against Apple. */
class AppleKeyOrderTest {

    private fun assertSorted(vararg keys: String) {
        assertEquals(keys.toList(), keys.reversed().sortedWith(AppleKeyOrder))
        assertEquals(keys.toList(), keys.toList().shuffled(kotlin.random.Random(1)).sortedWith(AppleKeyOrder))
    }

    @Test
    fun punctuationThenDigitsThenLetters() {
        assertSorted(
            " ", "_", "-", ",", ";", ":", "!", "?", ".", "'", "\"", "(", ")", "[", "]", "{", "}",
            "@", "*", "/", "\\", "&", "#", "%", "`", "^", "+", "<", "=", ">", "|", "~", "$",
            "0", "9", "a", "Z",
        )
    }

    @Test
    fun digitRunsCompareByValue() {
        assertSorted("9", "99999999999999999999", "100000000000000000000")
        assertSorted("item9", "item10")
    }

    @Test
    fun aProperPrefixSortsFirst() {
        assertSorted("a", "a-", "a--", "a-a", "aa")
        assertSorted("foo", "foo2", "foo2bar", "foo10")
    }

    @Test
    fun tiesBreakOnCaseThenLeadingZerosAtTheFirstDifference() {
        assertSorted("a1b", "a1B", "a01b", "a01B", "a001b", "A1b")
        assertSorted("ab", "aB", "Ab", "AB")
        assertSorted("x", "x0", "x00", "x000", "x1", "x01")
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonWriterTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.test.assertEquals

class JsonWriterTest {

    private val emptyArray = JsonValue.Array(emptyList())
    private val emptyObject = JsonValue.Object(emptyMap())

    /** U+2028, which Apple writes raw. */
    private val lineSeparator = Char(0x2028)

    @Test
    fun emptyContainersFollowApplesPrettyLayout() {
        assertEquals("[\n\n]", JsonWriter.pretty(emptyArray))
        assertEquals("{\n  \"a\" : [\n\n  ]\n}", JsonWriter.pretty(JsonValue.Object(mapOf("a" to emptyArray))))
        val unsorted = JsonValue.Object(mapOf("b" to emptyObject, "a" to emptyArray))
        assertEquals("{\"a\":[],\"b\":{}}", JsonWriter.compact(unsorted))
    }

    @Test
    fun stringsEscapeOnlyWhatAppleEscapes() {
        val text = JsonValue.Text("\"\\/\b\t\n\u000C\r\u000B\u001F\u007F${lineSeparator}é")
        val expected = "[\"\\\"\\\\/\\b\\t\\n\\f\\r\\u000b\\u001f\u007F${lineSeparator}é\"]"
        assertEquals(expected, JsonWriter.compact(JsonValue.Array(listOf(text))))
    }

    @Test
    fun numbersAreWrittenAsAppleWritesThem() {
        val numbers = JsonValue.Array(
            listOf(JsonValue.Integer(-7), JsonValue.Real(3.0), JsonValue.Real(0.1), JsonValue.Real(-0.0)),
        )
        assertEquals("[-7,3,0.10000000000000001,-0]", JsonWriter.compact(numbers))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonWriterGoldenTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.test.assertEquals

class JsonWriterGoldenTest {

    @Test
    fun sortsKeysAsAppleDoes() {
        val keys = Fixtures.lines("json/key-order.keys.txt")
        val expected = Fixtures.text("json/key-order.json")
        val actual = JsonWriter.compact(JsonValue.Object(keys.associateWith { JsonValue.Integer(0) }))
        if (actual != expected) {
            val appleOrder = (JsonReader.parse(expected.encodeToByteArray()) as JsonValue.Object).fields.keys.toList()
            val kotlinOrder = keys.sortedWith(AppleKeyOrder)
            val firstDifference = appleOrder.indices.first { appleOrder[it] != kotlinOrder[it] }
            assertEquals(
                appleOrder.subList(firstDifference, minOf(firstDifference + 5, appleOrder.size)),
                kotlinOrder.subList(firstDifference, minOf(firstDifference + 5, kotlinOrder.size)),
                "first difference at index $firstDifference",
            )
        }
        assertEquals(expected, actual)
    }

    @Test
    fun escapesStringsAsAppleDoes() {
        val strings = Fixtures.lines("json/strings.codepoints.txt").map { line ->
            if (line.isEmpty()) "" else line.split(" ").joinToString("") { String(Character.toChars(it.toInt(16))) }
        }
        val actual = JsonWriter.compact(JsonValue.Array(strings.map { JsonValue.Text(it) }))
        assertEquals(Fixtures.text("json/strings.json"), actual)
    }

    @Test
    fun prettyPrintsAsAppleDoes() {
        assertEquals(Fixtures.text("json/pretty.json"), JsonWriter.pretty(prettyStructure()))
    }

    @Test
    fun reserialisesEveryAcceptedDocumentAsAppleDoes() {
        val mismatches = Fixtures.tsv("json/parse.tsv").filter { it[1] != "reject" }.mapNotNull { (input, expected) ->
            val actual = JsonWriter.compact(JsonReader.parse(input.encodeToByteArray()))
            if (actual == expected) null else "${input.take(60)}: Apple ${expected.take(60)}, Kotlin ${actual.take(60)}"
        }
        assertEquals(emptyList(), mismatches)
    }

    /** Built identically by `JsonFixtures.prettyStructure` in the Swift generator. */
    private fun prettyStructure(): JsonValue {
        val emptyArray = JsonValue.Array(emptyList())
        val emptyObject = JsonValue.Object(emptyMap())
        return JsonValue.Object(
            linkedMapOf(
                "array" to JsonValue.Array(
                    listOf(
                        JsonValue.Integer(1), JsonValue.Real(2.5), JsonValue.Text("three"), JsonValue.Bool(true),
                        JsonValue.Bool(false), JsonValue.Null, emptyArray, emptyObject,
                        JsonValue.Array(listOf(emptyArray)), JsonValue.Array(listOf(emptyObject)),
                    ),
                ),
                "emptyArray" to emptyArray,
                "emptyObject" to emptyObject,
                "nested" to JsonValue.Object(
                    linkedMapOf(
                        "deeper" to JsonValue.Object(
                            linkedMapOf("deepest" to emptyArray, "value" to JsonValue.Real(-0.0)),
                        ),
                        "list" to JsonValue.Array(
                            listOf(JsonValue.Object(linkedMapOf("a" to JsonValue.Integer(1))), emptyObject),
                        ),
                    ),
                ),
                "numbers" to JsonValue.Array(
                    listOf(
                        JsonValue.Integer(0), JsonValue.Integer(-1), JsonValue.Real(1e21), JsonValue.Real(0.1),
                        JsonValue.Real(Double.MIN_VALUE), JsonValue.Integer(Long.MAX_VALUE),
                        JsonValue.Integer(Long.MIN_VALUE),
                        JsonValue.Real(3.0),
                    ),
                ),
                "strings" to JsonValue.Array(
                    listOf("", "/", "\"\\", "\u0001\t\n", "é😀").map { JsonValue.Text(it) },
                ),
                "Z" to JsonValue.Text("capital"),
                "z" to JsonValue.Null,
                "item10" to JsonValue.Integer(10),
                "item9" to JsonValue.Integer(9),
            ),
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.json.*' --console=plain
```

Expected: FAIL at compilation with `AppleKeyOrderTest.kt:10:64 Unresolved reference 'AppleKeyOrder'.`

- [ ] **Step 3: Write the implementation**

The comparator is the ASCII algorithm from the probe facts. It matches Apple on all ~700 golden keys, including 500 seeded random ones mixing case, digits and punctuation.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/AppleKeyOrder.kt`:

```kotlin
package com.andyyhope.featureflag.json

/**
 * The key order `JSONSerialization` uses for `.sortedKeys`: ICU root collation with numeric
 * ordering, case-insensitive, with a forced tie-break. It is independent of the device locale.
 *
 * For ASCII, keys compare element by element, where an element is one punctuation character,
 * one run of digits, or one letter:
 * - punctuation (including space) < digit runs < letters;
 * - punctuation follows ICU's root order, [PUNCTUATION];
 * - digit runs compare by numeric value, at any length;
 * - letters compare case-insensitively;
 * - a proper prefix sorts first.
 *
 * When every element is equal ignoring case and leading zeros, a second left-to-right pass
 * decides at the first element that differs: lowercase before uppercase, fewer leading zeros
 * before more.
 *
 * Deviation: any character outside printable ASCII is treated as a letter that sorts after
 * `z`, ordered by code point. Deterministic, but ICU orders such keys differently.
 */
internal object AppleKeyOrder : Comparator<String> {

    private const val PUNCTUATION = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$"

    private const val PUNCTUATION_CATEGORY = 0
    private const val DIGIT_CATEGORY = 1
    private const val LETTER_CATEGORY = 2

    override fun compare(a: String, b: String): Int {
        val left = elements(a)
        val right = elements(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val primary = primary(left[i], right[i])
            if (primary != 0) return primary
        }
        if (left.size != right.size) return left.size.compareTo(right.size)
        for (i in left.indices) {
            val tie = tieBreak(left[i], right[i])
            if (tie != 0) return tie
        }
        return 0
    }

    private fun elements(key: String): List<String> {
        val elements = mutableListOf<String>()
        var index = 0
        while (index < key.length) {
            val start = index
            if (key[index] in '0'..'9') {
                while (index < key.length && key[index] in '0'..'9') index++
            } else {
                index += Character.charCount(key.codePointAt(index))
            }
            elements += key.substring(start, index)
        }
        return elements
    }

    private fun category(element: String): Int {
        val first = element[0]
        return when {
            first in '0'..'9' -> DIGIT_CATEGORY
            first in 'a'..'z' || first in 'A'..'Z' -> LETTER_CATEGORY
            first in PUNCTUATION -> PUNCTUATION_CATEGORY
            else -> LETTER_CATEGORY
        }
    }

    private fun primary(a: String, b: String): Int {
        val categories = category(a).compareTo(category(b))
        if (categories != 0) return categories
        return when (category(a)) {
            PUNCTUATION_CATEGORY -> PUNCTUATION.indexOf(a[0]).compareTo(PUNCTUATION.indexOf(b[0]))
            DIGIT_CATEGORY -> compareNumerically(a.trimStart('0'), b.trimStart('0'))
            else -> letterWeight(a).compareTo(letterWeight(b))
        }
    }

    private fun compareNumerically(a: String, b: String): Int =
        if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

    /** ASCII letters by their lowercase form; anything else after `z`, by code point. */
    private fun letterWeight(element: String): Int {
        val codePoint = element.codePointAt(0)
        return when (codePoint.toChar()) {
            in 'a'..'z' -> codePoint
            in 'A'..'Z' -> codePoint + ('a' - 'A')
            else -> 'z'.code + 1 + codePoint
        }
    }

    private fun tieBreak(a: String, b: String): Int = when {
        a == b -> 0
        category(a) == DIGIT_CATEGORY -> a.length.compareTo(b.length)
        a[0].isLowerCase() && b[0].isUpperCase() -> -1
        a[0].isUpperCase() && b[0].isLowerCase() -> 1
        else -> a.compareTo(b)
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonWriter.kt`:

```kotlin
package com.andyyhope.featureflag.json

/**
 * Writes JSON byte-for-byte as `JSONSerialization` does with `.sortedKeys` and
 * `.withoutEscapingSlashes`, compact or with `.prettyPrinted` as well.
 *
 * - Pretty output indents by two spaces, writes `"key" : value`, and has no trailing newline.
 *   An empty container is the opening bracket, a newline, an empty line, then the closing
 *   bracket at the current indent.
 * - Keys are sorted with [AppleKeyOrder]. Numbers are written by [AppleNumberFormat].
 * - Strings escape `"`, `\`, `\b \t \n \f \r` and other C0 controls as lowercase `\u00xx`;
 *   `/`, DEL, U+2028, U+2029 and all non-ASCII are written raw.
 */
internal object JsonWriter {

    fun pretty(value: JsonValue): String = StringBuilder().also { it.writePretty(value, 0) }.toString()

    fun compact(value: JsonValue): String = StringBuilder().also { it.writeCompact(value) }.toString()

    private fun StringBuilder.writeCompact(value: JsonValue) {
        when (value) {
            is JsonValue.Array -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    writeCompact(item)
                }
                append(']')
            }
            is JsonValue.Object -> {
                append('{')
                value.fields.keys.sortedWith(AppleKeyOrder).forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    writeString(key)
                    append(':')
                    writeCompact(value.fields.getValue(key))
                }
                append('}')
            }
            else -> writeScalar(value)
        }
    }

    private fun StringBuilder.writePretty(value: JsonValue, depth: Int) {
        when (value) {
            is JsonValue.Array -> {
                append('[')
                if (value.items.isEmpty()) {
                    append("\n\n").indent(depth)
                } else {
                    value.items.forEachIndexed { index, item ->
                        append(if (index > 0) ",\n" else "\n").indent(depth + 1)
                        writePretty(item, depth + 1)
                    }
                    append('\n').indent(depth)
                }
                append(']')
            }
            is JsonValue.Object -> {
                append('{')
                if (value.fields.isEmpty()) {
                    append("\n\n").indent(depth)
                } else {
                    value.fields.keys.sortedWith(AppleKeyOrder).forEachIndexed { index, key ->
                        append(if (index > 0) ",\n" else "\n").indent(depth + 1)
                        writeString(key)
                        append(" : ")
                        writePretty(value.fields.getValue(key), depth + 1)
                    }
                    append('\n').indent(depth)
                }
                append('}')
            }
            else -> writeScalar(value)
        }
    }

    private fun StringBuilder.indent(depth: Int): StringBuilder = apply { repeat(depth) { append("  ") } }

    private fun StringBuilder.writeScalar(value: JsonValue) {
        when (value) {
            JsonValue.Null -> append("null")
            is JsonValue.Bool -> append(value.value)
            is JsonValue.Integer -> append(value.value)
            is JsonValue.Real -> append(AppleNumberFormat.format(value.value))
            is JsonValue.Text -> writeString(value.value)
            is JsonValue.Array, is JsonValue.Object -> error("containers are written by the caller")
        }
    }

    private fun StringBuilder.writeString(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else ->
                    if (char < ' ') {
                        append("\\u00").append(HEX[char.code shr 4]).append(HEX[char.code and 0xF])
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `AppleKeyOrderTest` (4), `JsonWriterTest` (3) and `JsonWriterGoldenTest` (4) pass. That includes byte equality with `json/key-order.json`, `json/strings.json` and `json/pretty.json`, and re-serialising every accepted row of `json/parse.tsv` exactly as Apple did.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Write JSON byte-for-byte as JSONSerialization does, keys in Apple order

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A8: Dates and base64

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/IsoDate.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/StrictBase64.kt`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt` (adds `date`), `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/IsoDateTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/StrictBase64Test.kt`

**Interfaces:**
- Consumes: `AppleNumberFormat.format` (Task A6, in the locale test); `MessageText` (Task A4); `Fixtures.tsv` (Task A2).
- Produces:
  - `internal object IsoDate { fun format(instant: Instant): String; fun parse(text: String): Instant? }`
  - `internal object StrictBase64 { fun encode(bytes: ByteArray): String; fun decode(text: String): ByteArray? }`
  - `MessageText.date(instant: Instant): String`

- [ ] **Step 1: Write the failing tests**

`APPLE_ONLY_DATE_FORMS` lists every Apple-accepted input that Kotlin's canonical subset refuses, and a test proves Apple accepts each one, so the list cannot go stale. `MessageTextTest` is replaced whole: the A4 version plus a date test.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/IsoDateTest.kt`:

```kotlin
package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import java.time.Instant
import java.util.Locale
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoDateTest {

    @Test
    fun formatsEveryDateAsAppleDoes() {
        val mismatches = Fixtures.tsv("dates/format.tsv").mapNotNull { (epoch, expected) ->
            val seconds = epoch.toDouble()
            val whole = floor(seconds)
            val actual = IsoDate.format(Instant.ofEpochSecond(whole.toLong(), ((seconds - whole) * 1e9).toLong()))
            if (actual == expected) null else "$epoch: Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun parsesEveryDateAsAppleDoesExceptTheLenientForms() {
        val mismatches = Fixtures.tsv("dates/parse.tsv").mapNotNull { (text, expected) ->
            val actual = IsoDate.parse(text)?.epochSecond?.toString() ?: "nil"
            val wanted = if (text in APPLE_ONLY_DATE_FORMS) "nil" else expected
            if (actual == wanted) null else "'$text': Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun everyAppleOnlyFormReallyIsAcceptedByApple() {
        val apple = Fixtures.tsv("dates/parse.tsv").associate { it[0] to it[1] }
        for (form in APPLE_ONLY_DATE_FORMS) {
            assertTrue(apple[form] != null && apple[form] != "nil", "'$form' is not Apple-only")
        }
    }

    @Test
    fun probeFactsHold() {
        assertEquals("1970-01-01T00:00:01Z", IsoDate.format(Instant.ofEpochMilli(1_999)))
        assertEquals("1969-12-31T23:59:59Z", IsoDate.format(Instant.ofEpochMilli(-500)))
        assertEquals("0001-01-01T00:00:00Z", IsoDate.format(Instant.ofEpochSecond(-62_135_769_600)))
        assertEquals("1582-10-04T00:00:00Z", IsoDate.format(Instant.ofEpochSecond(-12_219_379_200)))
        assertEquals("10000-01-01T00:00:00Z", IsoDate.format(Instant.ofEpochSecond(253_402_300_800)))
        assertNull(IsoDate.parse("2026-01-01T00:00:00.123Z"))
        assertNull(IsoDate.parse("2026-01-01T00:00:00"))
        assertNull(IsoDate.parse("2026-01-01 00:00:00Z"))
    }

    @Test
    fun theExtremesClampInsteadOfThrowing() {
        assertEquals("5828963-12-20T00:00:00Z", IsoDate.format(Instant.MAX))
        assertEquals("5838270-09-20T00:00:00Z", IsoDate.format(Instant.MIN))
        assertEquals(183_882_168_921_600, IsoDate.parse(IsoDate.format(Instant.MAX))?.epochSecond)
    }

    @Test
    fun outputIgnoresTheDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-ca-buddhist", "ja-JP-u-ca-japanese")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("2026-01-01T09:41:00Z", IsoDate.format(Instant.ofEpochSecond(1_767_260_460)), tag)
                assertEquals(1_767_260_460, IsoDate.parse("2026-01-01T09:41:00Z")?.epochSecond, tag)
                assertEquals("0.10000000000000001", AppleNumberFormat.format(0.1), tag)
                assertEquals("1e+21", AppleNumberFormat.format(1e21), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    private companion object {
        /** Apple's lenient ISO 8601 forms, which Kotlin's canonical subset refuses. */
        val APPLE_ONLY_DATE_FORMS = setOf(
            "2026-1-1T0:0:0Z", "2026-01-01T00:00:00+10", "2026-01-01T00:00:00+5:30", "2026-01-01T00:00:00GMT",
            "2026-01-01T00:00:00UTC", "2026-01-01T00:00:00 Z", " 2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z ",
            "2026-02-30T00:00:00Z", "2026-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2026-04-31T00:00:00Z",
            "2026-01-01T24:00:00Z", "2026-01-01T00:00:00+99:99", "2026-01-01T00:00:00+24:00",
            "2026-01-01T00:00:00+00:60", "2026-01-01T00:00:00+1", "2026-01-01T00:00:00+100",
            "2026-01-01T00:00:00+10000", "2026-01-01T00:00:00+10:00:00", "2026-01-01T00:00:00 +10:00",
            "-0001-01-01T00:00:00Z", "２０２６-01-01T00:00:00Z", "1000000000-12-31T23:59:59Z",
            "2026-01-01T00:00:00+10:0", "2026-01-01T00:00:00Z\n", "2026-01-01T00:00:00ZZ", "2026/01/01T00:00:00Z",
            "999-01-01T00:00:00Z", "2026-01-01T00:00:00+10:",
        )
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/StrictBase64Test.kt`:

```kotlin
package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrictBase64Test {

    private fun hex(bytes: ByteArray?): String = bytes?.joinToString("") { "%02x".format(it) } ?: "nil"

    @Test
    fun decodesExactlyAsAppleDoes() {
        val mismatches = Fixtures.tsv("base64/decode.tsv").mapNotNull { (input, expected) ->
            val actual = hex(StrictBase64.decode(input))
            if (actual == expected) null else "'$input': Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun probeFactsHold() {
        assertEquals("61", hex(StrictBase64.decode("YR==")))
        assertEquals("6869", hex(StrictBase64.decode("aGk===")))
        assertEquals("", hex(StrictBase64.decode("")))
        for (rejected in listOf("YQ", "Y Q==", "Zm-_", "YQ==\n", "Y===")) {
            assertNull(StrictBase64.decode(rejected), rejected)
        }
    }

    @Test
    fun encodesStandardPaddedBase64AndRoundTrips() {
        assertEquals("", StrictBase64.encode(ByteArray(0)))
        assertEquals("AAEC/v8=", StrictBase64.encode(byteArrayOf(0, 1, 2, -2, -1)))
        val deadBeef = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals("3q2+7w==", StrictBase64.encode(deadBeef))
        val random = Random(64)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            assertContentEquals(bytes, StrictBase64.decode(StrictBase64.encode(bytes)))
        }
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextTest.kt`:

```kotlin
package com.andyyhope.featureflag.errors

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class MessageTextTest {

    @Test
    fun quotingEscapesWhatWouldBreakALine() {
        assertEquals("\"a\\\\b\\nc\\rd\\te\"", MessageText.quoted("a\\b\nc\rd\te"))
        assertEquals("\"\"", MessageText.quoted(""))
    }

    @Test
    fun quotingCapsAtFortyCharactersAfterEscaping() {
        val forty = "x".repeat(40)
        assertEquals("\"$forty\"", MessageText.quoted(forty))
        assertEquals("\"$forty…\"", MessageText.quoted(forty + "y"))
        assertEquals("\"${"\\n".repeat(20)}…\"", MessageText.quoted("\n".repeat(21)))
        assertEquals("\"${"😀".repeat(40)}…\"", MessageText.quoted("😀".repeat(41)))
    }

    @Test
    fun collectionsAreDescribedInWords() {
        assertEquals("an array of 3", MessageText.arrayOf(3))
        assertEquals("an object with 1 field(s)", MessageText.objectWith(1))
    }

    @Test
    fun datesAreDescribedAsTheyAreWritten() {
        assertEquals("2026-01-01T00:00:00Z", MessageText.date(Instant.ofEpochSecond(1_767_225_600)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
```

Expected: FAIL at compilation with `MessageTextTest.kt:32:58 Unresolved reference 'date'.` and `IsoDateTest.kt:19:26 Unresolved reference 'IsoDate'.`

- [ ] **Step 3: Write the implementation**

`IsoDate` uses `GregorianCalendar` (hybrid Julian/Gregorian, 1582-10-15 cutover) in UTC. It builds text with `padStart` and never `String.format`, so the device locale cannot change a digit. Parsing validates the day against the hybrid calendar's own month length, and leniency rolls a cutover-gap day forward as Apple does.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/IsoDate.kt`:

```kotlin
package com.andyyhope.featureflag.json

import java.time.Instant
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Second-precision internet date-time, as `ISO8601DateFormatter` with `.withInternetDateTime`
 * writes and reads it.
 *
 * Formatting writes `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC on the hybrid Julian/Gregorian calendar
 * (1582-10-15 cutover), floors to the second, writes the era year zero-padded to four digits,
 * and clamps to ICU's supported range exactly as Apple does.
 *
 * Parsing accepts only the canonical subset: `yyyy-MM-ddTHH:mm:ss` (a four- to seven-digit
 * year) followed by `Z`, `z`, `±HH:MM` or `±HHMM`, with every field range-checked (no
 * rollover, no leap second, offsets up to ±23:59). Year `0000` is 1 BC, as Apple reads it.
 * Apple's lenient forms — single-digit fields, `GMT`, surrounding spaces, `24:00:00`,
 * `2026-02-30` and the rest — are a documented deviation.
 */
internal object IsoDate {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val CANONICAL = Regex("""(\d{4,7})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(Z|z|[+-]\d{2}:?\d{2})""")

    /** ICU's `MIN_MILLIS` and `MAX_MILLIS`, in seconds. Apple clamps every date to these. */
    private const val MIN_SECONDS = -184_303_902_528_000L
    private const val MAX_SECONDS = 183_882_168_921_600L

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3600
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val MAX_SECOND = 59
    private const val MONTHS = 12

    fun format(instant: Instant): String {
        val seconds = instant.epochSecond.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val calendar = GregorianCalendar(UTC).apply { timeInMillis = seconds * MILLIS_PER_SECOND }
        return buildString {
            append(calendar.get(Calendar.YEAR).toString().padStart(4, '0')).append('-')
            append(two(calendar.get(Calendar.MONTH) + 1)).append('-')
            append(two(calendar.get(Calendar.DAY_OF_MONTH))).append('T')
            append(two(calendar.get(Calendar.HOUR_OF_DAY))).append(':')
            append(two(calendar.get(Calendar.MINUTE))).append(':')
            append(two(calendar.get(Calendar.SECOND))).append('Z')
        }
    }

    fun parse(text: String): Instant? {
        val groups = CANONICAL.matchEntire(text)?.groupValues ?: return null
        val year = groups[1].toInt()
        val month = groups[2].toInt()
        val day = groups[3].toInt()
        val hour = groups[4].toInt()
        val minute = groups[5].toInt()
        val second = groups[6].toInt()
        val offset = offsetSeconds(groups[7]) ?: return null
        val timeIsValid = hour <= MAX_HOUR && minute <= MAX_MINUTE && second <= MAX_SECOND
        if (month !in 1..MONTHS || day < 1 || !timeIsValid) return null

        val calendar = GregorianCalendar(UTC).apply {
            clear()
            set(Calendar.ERA, if (year == 0) GregorianCalendar.BC else GregorianCalendar.AD)
            set(Calendar.YEAR, if (year == 0) 1 else year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        if (day > calendar.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, second)
        return Instant.ofEpochSecond(Math.floorDiv(calendar.timeInMillis, MILLIS_PER_SECOND) - offset)
    }

    private fun offsetSeconds(zone: String): Long? {
        if (zone == "Z" || zone == "z") return 0
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = digits.substring(2, 4).toInt()
        if (hours > MAX_HOUR || minutes > MAX_MINUTE) return null
        val magnitude = hours.toLong() * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE
        return if (zone[0] == '-') -magnitude else magnitude
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')
}
```

`java.util.Base64` is outside the allowed JDK APIs, and its decoder disagrees with Apple's anyway, so both directions are written out.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/StrictBase64.kt`:

```kotlin
package com.andyyhope.featureflag.json

/**
 * Standard, padded base64 — `Data.base64EncodedString()` to write and exactly what
 * `Data(base64Encoded:)` accepts to read (see `fixtures/base64/decode.tsv`).
 *
 * Reading rejects whitespace, the URL-safe alphabet, a body of length 1 mod 4, and missing
 * padding. It accepts extra trailing padding, non-zero trailing bits, and two Apple quirks:
 * a final quad of the form `xx=x` decodes to two bytes (the first byte and the last sextet),
 * and a string of four or more `=` and nothing else decodes to a single zero byte.
 */
internal object StrictBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val PAD = '='
    private const val QUAD = 4
    private const val MIN_PADDING_ONLY = 4

    private val VALUES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String = buildString {
        for (start in bytes.indices step 3) {
            val b0 = bytes[start].toInt() and 0xFF
            val b1 = if (start + 1 < bytes.size) bytes[start + 1].toInt() and 0xFF else 0
            val b2 = if (start + 2 < bytes.size) bytes[start + 2].toInt() and 0xFF else 0
            append(ALPHABET[b0 shr 2])
            append(ALPHABET[(b0 and 0x03) shl 4 or (b1 shr 4)])
            append(if (start + 1 < bytes.size) ALPHABET[(b1 and 0x0F) shl 2 or (b2 shr 6)] else PAD)
            append(if (start + 2 < bytes.size) ALPHABET[b2 and 0x3F] else PAD)
        }
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.any { it != PAD && (it.code >= VALUES.size || VALUES[it.code] < 0) }) return null
        val body = text.trimEnd(PAD)
        val padding = text.length - body.length
        if (body.isEmpty()) return if (padding >= MIN_PADDING_ONLY) ByteArray(1) else null

        val remainder = body.length % QUAD
        val innerPad = body.indexOf(PAD)
        val quirkyQuad = innerPad >= 0 && remainder == 0 && innerPad == body.length - 2
        val paddingIsEnough = when (remainder) {
            0 -> true
            2 -> padding >= 2
            3 -> padding >= 1
            else -> false
        }
        if ((innerPad >= 0 && !quirkyQuad) || !paddingIsEnough) return null

        val out = ArrayList<Byte>(body.length / QUAD * 3 + 2)
        var start = 0
        while (start < body.length) {
            val end = minOf(start + QUAD, body.length)
            decodeGroup(body.substring(start, end), out)
            start = end
        }
        return out.toByteArray()
    }

    private fun decodeGroup(group: String, out: MutableList<Byte>) {
        val c0 = VALUES[group[0].code]
        val c1 = VALUES[group[1].code]
        out += (c0 shl 2 or (c1 shr 4)).toByte()
        when {
            group.length == 4 && group[2] == PAD -> out += VALUES[group[3].code].toByte()
            group.length >= 3 -> {
                val c2 = VALUES[group[2].code]
                out += ((c1 and 0x0F) shl 4 or (c2 shr 2)).toByte()
                if (group.length == 4) out += ((c2 and 0x03) shl 6 or VALUES[group[3].code]).toByte()
            }
        }
    }
}
```

Replace `MessageText.kt` with:

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt`:

```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.json.IsoDate
import java.time.Instant

/**
 * The value-rendering helpers behind every error message, ported from `ErrorMessages.swift`.
 *
 * Each problem is a bullet, so a raw newline in a value would put the rest of it in column zero
 * where it reads as another bullet. Length is capped for the same reason.
 */
internal object MessageText {

    private const val MAX_QUOTED_LENGTH = 40

    /**
     * A value as one short, single-line, quoted fragment: `\`, newline, carriage return and tab
     * are escaped, and anything over 40 characters is cut to 40 plus `…`.
     *
     * Swift counts grapheme clusters; this counts code points, so a value whose 40th character
     * is part of a combining sequence or an emoji ZWJ sequence is cut at a different place.
     */
    fun quoted(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        if (escaped.codePointCount(0, escaped.length) <= MAX_QUOTED_LENGTH) return "\"$escaped\""
        return "\"${escaped.substring(0, escaped.offsetByCodePoints(0, MAX_QUOTED_LENGTH))}…\""
    }

    fun arrayOf(count: Int): String = "an array of $count"

    fun objectWith(count: Int): String = "an object with $count field(s)"

    fun date(instant: Instant): String = IsoDate.format(instant)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `IsoDateTest` (6), `StrictBase64Test` (3) and `MessageTextTest` (4) pass. That includes every row of `dates/format.tsv`, `dates/parse.tsv` and `base64/decode.tsv`.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Port ISO 8601 dates and Data(base64Encoded:) exactly

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A9: Boxes to and from JSON

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/BoxJson.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/BoxJsonTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/UrlGoldenTest.kt`

**Interfaces:**
- Consumes: `FlagValueBox`, `FlagValueType` (Task A4); `FlagSerializationException.NonFiniteNumber` (Task A4); `JsonValue`, `JsonReader` (Task A5); `JsonWriter.compact` (Task A7); `IsoDate`, `StrictBase64` (Task A8).
- Produces (package `com.andyyhope.featureflag.values`):
  - `internal fun FlagValueBox.toJson(): JsonValue` (throws `FlagSerializationException.NonFiniteNumber(null)`)
  - `internal fun JsonValue.toBox(type: FlagValueType): FlagValueBox?`
  - `internal val FlagValueBox.storageIdentity: String` (`JsonWriter.compact(toJson())`, or the box's `toString()` when it holds a non-finite number, so the duplicate-key check never throws)

- [ ] **Step 1: Write the failing tests**

`BoxJsonTest` ports the rules of `FlagValueBox+JSON.swift` and the round trips of `FlagValueBoxTests`, and pins the `-0.0` deviation. `UrlGoldenTest` checks `url/parse.tsv`, with the five known deviations listed.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/BoxJsonTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Ported from FlagValueBox+JSON.swift's rules and FlagValueBoxTests' round trips. */
class BoxJsonTest {

    private fun json(text: String): JsonValue =
        (JsonReader.parse("[$text]".encodeToByteArray()) as JsonValue.Array).items.single()

    private fun box(text: String, type: FlagValueType): FlagValueBox? = json(text).toBox(type)

    @Test
    fun everyTypeRoundTripsThroughJson() {
        val cases = listOf(
            FlagValueBox.Bool(true) to FlagValueType.Bool,
            FlagValueBox.Int(-7) to FlagValueType.Int,
            FlagValueBox.Double(3.5) to FlagValueType.Double,
            FlagValueBox.Float(3.5f) to FlagValueType.Float,
            FlagValueBox.String("hello") to FlagValueType.String,
            FlagValueBox.Data(byteArrayOf(1, 2)) to FlagValueType.Data,
            FlagValueBox.Date(Instant.ofEpochSecond(1000)) to FlagValueType.Date,
            FlagValueBox.Url(URI("https://example.com")) to FlagValueType.Url,
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Int(1))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Int)),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Bool(false))) to
                FlagValueType.Dictionary(FlagValueType.Bool),
        )
        for ((box, type) in cases) assertEquals(box, box.toJson().toBox(type), "round trip of $box")
    }

    @Test
    fun valuesBecomeTheJsonAppleWrites() {
        assertEquals(JsonValue.Real(0.10000000149011612), FlagValueBox.Float(0.1f).toJson())
        assertEquals(JsonValue.Text("AAEC/v8="), FlagValueBox.Data(byteArrayOf(0, 1, 2, -2, -1)).toJson())
        val date = FlagValueBox.Date(Instant.ofEpochMilli(1_767_260_460_750))
        assertEquals(JsonValue.Text("2026-01-01T09:41:00Z"), date.toJson())
        val url = FlagValueBox.Url(URI("https://example.com/a?b=c"))
        assertEquals(JsonValue.Text("https://example.com/a?b=c"), url.toJson())
    }

    @Test
    fun nonFiniteNumbersCannotBecomeJson() {
        assertFailsWith<FlagSerializationException.NonFiniteNumber> { FlagValueBox.Double(Double.NaN).toJson() }
        assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            FlagValueBox.Array(listOf(FlagValueBox.Float(Float.POSITIVE_INFINITY))).toJson()
        }
    }

    @Test
    fun booleansAndNumbersAreNeverConfused() {
        assertEquals(FlagValueBox.Bool(true), box("true", FlagValueType.Bool))
        assertNull(box("1", FlagValueType.Bool))
        assertNull(box("true", FlagValueType.Int))
        assertNull(box("true", FlagValueType.Double))
        assertNull(box("\"true\"", FlagValueType.Bool))
    }

    @Test
    fun intsComeOnlyFromIntegralNumbersInRange() {
        assertEquals(FlagValueBox.Int(3), box("3", FlagValueType.Int))
        assertEquals(FlagValueBox.Int(3), box("3.0", FlagValueType.Int))
        assertEquals(FlagValueBox.Int(100), box("1e2", FlagValueType.Int))
        assertNull(box("1.5", FlagValueType.Int))
        assertNull(box("1e19", FlagValueType.Int))
        assertNull(box("\"3\"", FlagValueType.Int))
    }

    @Test
    fun doublesTakeIntegersOnlyWhenADoubleHoldsThemExactly() {
        assertEquals(FlagValueBox.Double(3.0), box("3", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(9007199254740992.0), box("9007199254740992", FlagValueType.Double))
        assertNull(box("9007199254740993", FlagValueType.Double))
        assertNull(box("9223372036854775807", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(-9.223372036854775808E18), box("-9223372036854775808", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(1.0E19), box("10000000000000000000", FlagValueType.Double))
    }

    @Test
    fun floatsNarrowAsSwiftsFloatInitialiserDoes() {
        assertEquals(FlagValueBox.Float(0.1f), box("0.1", FlagValueType.Float))
        assertEquals(FlagValueBox.Float(16777216f), box("16777217", FlagValueType.Float))
        assertEquals(FlagValueBox.Float(Float.POSITIVE_INFINITY), box("1e39", FlagValueType.Float))
    }

    @Test
    fun negativeZeroIsADocumentedDeviation() {
        // `-0.0` reads as Integer(0), so a double flag gets 0.0; Apple keeps -0.0 and writes "-0".
        assertEquals(FlagValueBox.Double(0.0), box("-0.0", FlagValueType.Double))
        assertEquals(FlagValueBox.Int(0), box("-0.0", FlagValueType.Int))
    }

    @Test
    fun stringsDataDatesAndUrlsComeOnlyFromValidText() {
        assertEquals(FlagValueBox.String("a"), box("\"a\"", FlagValueType.String))
        assertNull(box("1", FlagValueType.String))
        assertEquals(FlagValueBox.Data(byteArrayOf(0x61)), box("\"YQ==\"", FlagValueType.Data))
        assertNull(box("\"YQ\"", FlagValueType.Data))
        val newYear = FlagValueBox.Date(Instant.ofEpochSecond(1_767_225_600))
        assertEquals(newYear, box("\"2026-01-01T00:00:00Z\"", FlagValueType.Date))
        assertNull(box("\"2026-01-01\"", FlagValueType.Date))
        assertNull(box("1767225600", FlagValueType.Date))
        assertNull(box("\"\"", FlagValueType.Url))
        val encoded = FlagValueBox.Url(URI("https://example.com/a%20b"))
        assertEquals(encoded, box("\"https://example.com/a b\"", FlagValueType.Url))
    }

    @Test
    fun collectionsAreAllOrNothing() {
        val ints = FlagValueType.Array(FlagValueType.Int)
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))), box("[1,2]", ints))
        assertNull(box("[1,\"2\"]", ints))
        assertNull(box("{\"a\":1}", ints))
        val dictionary = FlagValueType.Dictionary(FlagValueType.Bool)
        assertEquals(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Bool(true))), box("{\"a\":true}", dictionary))
        assertNull(box("{\"a\":true,\"b\":1}", dictionary))
        assertEquals(FlagValueBox.Array(emptyList()), box("[]", FlagValueType.Array(FlagValueType.Url)))
    }

    @Test
    fun storageIdentityComparesValuesAsTheyAreStored() {
        val early = FlagValueBox.Date(Instant.ofEpochMilli(1_000_100))
        val late = FlagValueBox.Date(Instant.ofEpochMilli(1_000_900))
        assertNotEquals<FlagValueBox>(early, late)
        assertEquals(early.storageIdentity, late.storageIdentity)
        assertEquals("\"a\"", FlagValueBox.String("a").storageIdentity)
        assertNotEquals(FlagValueBox.String("1").storageIdentity, FlagValueBox.Int(1).storageIdentity)
        val infinite = FlagValueBox.Float(Float.POSITIVE_INFINITY)
        assertEquals(infinite.toString(), infinite.storageIdentity)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/UrlGoldenTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.json.JsonValue
import org.junit.Test
import kotlin.test.assertEquals

class UrlGoldenTest {

    @Test
    fun readsUrlsAsAppleDoesExceptTheKnownDeviations() {
        val mismatches = Fixtures.tsv("url/parse.tsv").mapNotNull { (input, expected) ->
            val box = JsonValue.Text(input).toBox(FlagValueType.Url) as FlagValueBox.Url?
            val actual = box?.value?.toString() ?: "nil"
            val wanted = KNOWN_URL_DEVIATIONS[input] ?: expected
            if (actual == wanted) null else "'$input': Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    private companion object {
        /** Inputs where Kotlin differs from `URL(string:)`, and what Kotlin produces instead. */
        val KNOWN_URL_DEVIATIONS = mapOf(
            // Apple converts a non-ASCII host to punycode; there is no IDNA here.
            "https://例え.jp/パス" to "nil",
            // java.net.URI refuses an empty authority, an empty scheme-specific part and a
            // bracketed host that is not an IP literal; Apple keeps all three as written.
            "://" to "nil",
            "https://" to "nil",
            "https:" to "nil",
            "http://[zz]/" to "nil",
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.values.*' --console=plain
```

Expected: FAIL at compilation with `BoxJsonTest.kt:20:84 Unresolved reference 'toBox'.`

- [ ] **Step 3: Write the implementation**

`exactDouble` reproduces `NSNumber as? Double`: a `Long` converts only when the double holds it exactly. `Long.MAX_VALUE` is excluded explicitly, because `2^63.toLong()` clamps back to it. `parseUrl` splits the URL with RFC 3986's own regex, percent-encodes per component what `URL(string:)` would, and hands the result to `java.net.URI`.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/BoxJson.kt`:

```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.json.StrictBase64
import java.net.URI

/**
 * The JSON representation of this value, as `FlagValueBox.jsonValue` writes it.
 *
 * JSON has no date, data or URL, and the schema already records each flag's type, so these
 * become plain strings: dates as second-precision ISO 8601, data as padded base64, URLs as text.
 * A float is widened to a double. Throws [FlagSerializationException.NonFiniteNumber] for an
 * infinity or a NaN, which JSON cannot represent.
 */
internal fun FlagValueBox.toJson(): JsonValue = when (this) {
    is FlagValueBox.Bool -> JsonValue.Bool(value)
    is FlagValueBox.Int -> JsonValue.Integer(value)
    is FlagValueBox.Double -> finiteReal(value)
    is FlagValueBox.Float -> finiteReal(value.toDouble())
    is FlagValueBox.String -> JsonValue.Text(value)
    is FlagValueBox.Data -> JsonValue.Text(StrictBase64.encode(value))
    is FlagValueBox.Date -> JsonValue.Text(IsoDate.format(value))
    is FlagValueBox.Url -> JsonValue.Text(value.toString())
    is FlagValueBox.Array -> JsonValue.Array(value.map { it.toJson() })
    is FlagValueBox.Dictionary -> JsonValue.Object(value.mapValues { it.value.toJson() })
}

private fun finiteReal(value: Double): JsonValue {
    if (!value.isFinite()) throw FlagSerializationException.NonFiniteNumber(null)
    return JsonValue.Real(value)
}

/**
 * Rebuilds a box from JSON, guided by the flag's declared type, exactly as
 * `FlagValueBox(jsonValue:as:)` does. Returns `null` when the value cannot be read as that type.
 *
 * - `Bool` only from a JSON boolean; `Int` only from an [JsonValue.Integer].
 * - `Double` and `Float` from a [JsonValue.Real], or from an [JsonValue.Integer] that a double
 *   represents exactly (`9007199254740993` is refused, as `NSNumber as? Double` refuses it).
 *   `Float` then narrows the double as Swift's `Float(_:)` does.
 * - `Data` through [StrictBase64], `Date` through [IsoDate], `Url` as `URL(string:)` reads it.
 * - Collections are all or nothing.
 */
internal fun JsonValue.toBox(type: FlagValueType): FlagValueBox? {
    return when (type) {
        FlagValueType.Bool -> (this as? JsonValue.Bool)?.let { FlagValueBox.Bool(it.value) }
        FlagValueType.Int -> (this as? JsonValue.Integer)?.let { FlagValueBox.Int(it.value) }
        FlagValueType.Double -> exactDouble()?.let { FlagValueBox.Double(it) }
        FlagValueType.Float -> exactDouble()?.let { FlagValueBox.Float(it.toFloat()) }
        FlagValueType.String -> text()?.let { FlagValueBox.String(it) }
        FlagValueType.Data -> text()?.let(StrictBase64::decode)?.let { FlagValueBox.Data(it) }
        FlagValueType.Date -> text()?.let(IsoDate::parse)?.let { FlagValueBox.Date(it) }
        FlagValueType.Url -> text()?.let(::parseUrl)?.let { FlagValueBox.Url(it) }
        is FlagValueType.Array -> {
            val items = (this as? JsonValue.Array)?.items ?: return null
            FlagValueBox.Array(items.map { it.toBox(type.element) ?: return null })
        }
        is FlagValueType.Dictionary -> {
            val fields = (this as? JsonValue.Object)?.fields ?: return null
            FlagValueBox.Dictionary(fields.mapValues { it.value.toBox(type.value) ?: return null })
        }
    }
}

/**
 * This value as its stored form identifies it, for deciding whether two record keys collide.
 *
 * Two values are the same key when they are written the same way, which is not always the same
 * as being equal in memory: a date carries more precision than the wire format keeps. A value
 * JSON cannot write (a non-finite number) is identified by its own description instead of
 * throwing, as Swift's `String(describing:)` would.
 */
internal val FlagValueBox.storageIdentity: String
    get() = if (containsNonFiniteNumber) toString() else JsonWriter.compact(toJson())

private fun JsonValue.text(): String? = (this as? JsonValue.Text)?.value

private fun JsonValue.exactDouble(): Double? = when (this) {
    is JsonValue.Real -> value
    is JsonValue.Integer -> value.toDouble().takeIf { value != Long.MAX_VALUE && it.toLong() == value }
    else -> null
}

// URL(string:)

private const val URL_SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@"
private const val SCHEME_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-."
private const val HEX_DIGITS = "0123456789ABCDEF"
private val URL_PARTS =
    Regex("""(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?""", RegexOption.DOT_MATCHES_ALL)

/**
 * Reads a URL the way `URL(string:)` does on Apple platforms since macOS 14 / iOS 17: characters
 * that are not allowed where they appear (spaces, quotes, `<>`, `{}`, `|`, `\`, `^`, `` ` ``,
 * controls, non-ASCII, and `%` not followed by two hex digits) are percent-encoded as UTF-8
 * rather than refused. The empty string, an invalid scheme, and a host or port containing such
 * characters are refused.
 *
 * Deviations, pinned by `UrlGoldenTest`: Apple converts a non-ASCII host to punycode, which this
 * refuses, and `java.net.URI` refuses a few shapes Apple accepts (an empty authority, `[zz]`).
 */
private fun parseUrl(text: String): URI? {
    if (text.isEmpty()) return null
    val parts = URL_PARTS.matchEntire(text)?.groups ?: return null
    val scheme = parts[1]?.value
    if (scheme != null && (!scheme[0].isAsciiLetter() || scheme.any { it !in SCHEME_CHARACTERS })) return null
    val authority = parts[2]?.value?.let(::encodeAuthority)
    if (parts[2] != null && authority == null) return null
    val rebuilt = buildString {
        if (scheme != null) append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        append(percentEncode(parts[3]?.value.orEmpty(), extra = "/"))
        parts[4]?.let { append('?').append(percentEncode(it.value, extra = "/?")) }
        parts[5]?.let { append('#').append(percentEncode(it.value, extra = "/?")) }
    }
    return runCatching { URI(rebuilt) }.getOrNull()
}

private fun encodeAuthority(authority: String): String? {
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) percentEncode(authority.substring(0, at), extra = "") + "@" else ""
    val hostAndPort = authority.substring(at + 1)
    if (hostAndPort.startsWith("[")) return userInfo + hostAndPort
    val colon = hostAndPort.lastIndexOf(':')
    val host = if (colon >= 0) hostAndPort.substring(0, colon) else hostAndPort
    val port = if (colon >= 0) hostAndPort.substring(colon + 1) else ""
    val hostIsValid = host.all { it in URL_SAFE && it != ':' && it != '@' || it == '%' }
    if (!hostIsValid || port.any { it !in '0'..'9' }) return null
    return userInfo + hostAndPort
}

private fun percentEncode(component: String, extra: String): String = buildString {
    var index = 0
    while (index < component.length) {
        val char = component[index]
        val validEscape = char == '%' && index + 2 < component.length &&
            component[index + 1].isHexDigit() && component[index + 2].isHexDigit()
        when {
            char in URL_SAFE || char in extra || validEscape -> append(char)
            else -> {
                val codePoint = component.codePointAt(index)
                for (byte in String(Character.toChars(codePoint)).encodeToByteArray()) {
                    val unsigned = byte.toInt() and 0xFF
                    append('%').append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0xF])
                }
                index += Character.charCount(codePoint) - 1
            }
        }
        index++
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `BoxJsonTest` (11) and `UrlGoldenTest` (1) pass.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Convert boxes to and from JSON with FlagValueBox+JSON's rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A10: Codecs and enums

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagEnums.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodec.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordField.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagCodecsTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueCollectionTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordFieldTest.kt`

`FlagRecordField` is created here rather than in Task A11 because `FlagCodec.recordShape` names it and `FlagRecordField.of` takes a `FlagCodec`. Neither file compiles without the other.

**Interfaces:**
- Consumes: `FlagValueBox`, `FlagValueType` (Task A4).
- Produces:
  - `public interface FlagStringEnum { public val flagRawValue: String }`, `public interface FlagIntEnum { public val flagRawValue: Long }`
  - `public interface FlagCodec<T> { public val type: FlagValueType; public val cases: List<FlagValueBox>? get() = null; public val recordShape: List<FlagRecordField>? get() = null; public fun encode(value: T): FlagValueBox; public fun decode(box: FlagValueBox): T? }`
  - `public object FlagCodecs` with `boolean`, `int` (type `Int`; decodes `null` outside `Int` range), `long`, `double`, `float`, `string`, `byteArray` (copies both ways), `instant`, `uri`, `fun <T> list(element: FlagCodec<T>): FlagCodec<List<T>>`, `fun <T> map(value: FlagCodec<T>): FlagCodec<Map<String, T>>`, `fun <E : Enum<E>> enumByName(entries: List<E>): FlagCodec<E>`, `fun <E> stringEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagStringEnum`, `fun <E> intEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagIntEnum`. Every enum codec publishes `cases`; list and map codecs publish neither cases nor a record shape.
  - `public data class FlagRecordField(name, type, cases = null, defaultValue = null, fields = null, isKey = false, isOptional = false, decodedName = null) { public val payloadName: String; public companion object { public fun <T : Any> of(name: String, codec: FlagCodec<T>, default: T? = null, isKey: Boolean = false, isOptional: Boolean = false, decodedName: String? = null): FlagRecordField } }` (package `com.andyyhope.featureflag.records`; an optional field never carries a default)

- [ ] **Step 1: Write the failing tests**

Ported from `FlagValueBoxTests` (boxing, strict unboxing, declared types) and `FlagValueCollectionTests`. Swift's "a non-`CaseIterable` enum has no cases" test is replaced by "every enum surfaces its cases", the spec's deliberate difference.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagCodecsTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from FlagValueBoxTests. */
class FlagCodecsTest {

    @Test
    fun primitivesBoxToTheExpectedCaseAndRoundTrip() {
        assertEquals(FlagValueBox.Bool(true), FlagCodecs.boolean.encode(true))
        assertEquals(false, FlagCodecs.boolean.decode(FlagCodecs.boolean.encode(false)))
        assertEquals(FlagValueBox.Int(42), FlagCodecs.int.encode(42))
        assertEquals(-7, FlagCodecs.int.decode(FlagCodecs.int.encode(-7)))
        assertEquals(FlagValueBox.Int(Long.MAX_VALUE), FlagCodecs.long.encode(Long.MAX_VALUE))
        assertEquals(FlagValueBox.Double(3.5), FlagCodecs.double.encode(3.5))
        assertEquals(3.5, FlagCodecs.double.decode(FlagCodecs.double.encode(3.5)))
        assertEquals(FlagValueBox.Float(3.5f), FlagCodecs.float.encode(3.5f))
        assertEquals(3.5f, FlagCodecs.float.decode(FlagCodecs.float.encode(3.5f)))
        assertEquals(FlagValueBox.String("hello"), FlagCodecs.string.encode("hello"))
        assertEquals("hello", FlagCodecs.string.decode(FlagCodecs.string.encode("hello")))
        val bytes = FlagCodecs.byteArray
        assertContentEquals(byteArrayOf(1, 2), bytes.decode(bytes.encode(byteArrayOf(1, 2))))
        val date = Instant.ofEpochSecond(1000)
        assertEquals(FlagValueBox.Date(date), FlagCodecs.instant.encode(date))
        assertEquals(date, FlagCodecs.instant.decode(FlagCodecs.instant.encode(date)))
        val url = URI("https://example.com")
        assertEquals(FlagValueBox.Url(url), FlagCodecs.uri.encode(url))
        assertEquals(url, FlagCodecs.uri.decode(FlagCodecs.uri.encode(url)))
    }

    @Test
    fun unboxingIsStrict() {
        assertNull(FlagCodecs.boolean.decode(FlagValueBox.Int(1)))
        assertNull(FlagCodecs.int.decode(FlagValueBox.String("1")))
        assertNull(FlagCodecs.string.decode(FlagValueBox.Bool(true)))
        assertNull(FlagCodecs.double.decode(FlagValueBox.Int(1)))
        assertNull(FlagCodecs.float.decode(FlagValueBox.Double(1.0)))
    }

    @Test
    fun intIsSixtyFourBitOnTheWireAndRefusesWhatItCannotHold() {
        assertEquals(FlagValueType.Int, FlagCodecs.int.type)
        assertEquals(Int.MAX_VALUE, FlagCodecs.int.decode(FlagValueBox.Int(Int.MAX_VALUE.toLong())))
        assertNull(FlagCodecs.int.decode(FlagValueBox.Int(Int.MAX_VALUE + 1L)))
        assertNull(FlagCodecs.int.decode(FlagValueBox.Int(Long.MIN_VALUE)))
        assertEquals(Long.MIN_VALUE, FlagCodecs.long.decode(FlagValueBox.Int(Long.MIN_VALUE)))
    }

    @Test
    fun primitivesDeclareTheirValueType() {
        assertEquals(FlagValueType.Bool, FlagCodecs.boolean.type)
        assertEquals(FlagValueType.Int, FlagCodecs.long.type)
        assertEquals(FlagValueType.Double, FlagCodecs.double.type)
        assertEquals(FlagValueType.Float, FlagCodecs.float.type)
        assertEquals(FlagValueType.String, FlagCodecs.string.type)
        assertEquals(FlagValueType.Data, FlagCodecs.byteArray.type)
        assertEquals(FlagValueType.Date, FlagCodecs.instant.type)
        assertEquals(FlagValueType.Url, FlagCodecs.uri.type)
        assertNull(FlagCodecs.string.cases)
        assertNull(FlagCodecs.string.recordShape)
    }

    @Test
    fun byteArraysAreCopiedSoAStoredValueCannotChange() {
        val bytes = byteArrayOf(1, 2)
        val box = FlagCodecs.byteArray.encode(bytes)
        bytes[0] = 9
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), box)
        FlagCodecs.byteArray.decode(box)!![1] = 9
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), box)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueCollectionTest.kt`:

```kotlin
package com.andyyhope.featureflag.values

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from FlagValueCollectionTests. Every Kotlin enum publishes its cases (a documented difference). */
class FlagValueCollectionTest {

    private enum class Tier(override val flagRawValue: String) : FlagStringEnum { FREE("free"), PRO("pro") }

    private enum class Level(override val flagRawValue: Long) : FlagIntEnum { LOW(1), HIGH(2) }

    private enum class Plain { ALPHA, BETA }

    private val tier = FlagCodecs.stringEnum(Tier.entries)
    private val level = FlagCodecs.intEnum(Level.entries)

    // Arrays

    @Test
    fun arrayBoxesEachElementAndRoundTrips() {
        val bools = FlagCodecs.list(FlagCodecs.boolean)
        val expected = FlagValueBox.Array(listOf(FlagValueBox.Bool(true), FlagValueBox.Bool(false)))
        assertEquals(expected, bools.encode(listOf(true, false)))
        val strings = FlagCodecs.list(FlagCodecs.string)
        assertEquals(listOf("a", "b", "c"), strings.decode(strings.encode(listOf("a", "b", "c"))))
        val ints = FlagCodecs.list(FlagCodecs.int)
        assertEquals(emptyList(), ints.decode(ints.encode(emptyList())))
        val nested = FlagCodecs.list(FlagCodecs.list(FlagCodecs.int))
        assertEquals(listOf(listOf(1, 2), listOf(3)), nested.decode(nested.encode(listOf(listOf(1, 2), listOf(3)))))
    }

    @Test
    fun arrayRejectsAnElementOfTheWrongTypeAndReportsItsElementType() {
        val mixed = FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.String("2")))
        assertNull(FlagCodecs.list(FlagCodecs.int).decode(mixed))
        assertEquals(FlagValueType.Array(FlagValueType.Bool), FlagCodecs.list(FlagCodecs.boolean).type)
        assertNull(FlagCodecs.list(tier).cases)
    }

    // Dictionaries

    @Test
    fun dictionaryBoxesEachValueAndRoundTrips() {
        val ints = FlagCodecs.map(FlagCodecs.int)
        assertEquals(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1))), ints.encode(mapOf("a" to 1)))
        assertEquals(mapOf("a" to 1, "b" to 2), ints.decode(ints.encode(mapOf("a" to 1, "b" to 2))))
    }

    @Test
    fun dictionaryRejectsAValueOfTheWrongTypeAndReportsItsValueType() {
        val mixed = FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1), "b" to FlagValueBox.Bool(true)))
        assertNull(FlagCodecs.map(FlagCodecs.int).decode(mixed))
        assertEquals(FlagValueType.Dictionary(FlagValueType.Double), FlagCodecs.map(FlagCodecs.double).type)
    }

    // Enums

    @Test
    fun stringBackedEnumBoxesAsItsRawValueAndRoundTrips() {
        assertEquals(FlagValueBox.String("pro"), tier.encode(Tier.PRO))
        assertEquals(Tier.PRO, tier.decode(tier.encode(Tier.PRO)))
        assertEquals(FlagValueType.String, tier.type)
    }

    @Test
    fun intBackedEnumBoxesAsItsRawValueAndRoundTrips() {
        assertEquals(FlagValueBox.Int(2), level.encode(Level.HIGH))
        assertEquals(Level.HIGH, level.decode(level.encode(Level.HIGH)))
        assertEquals(FlagValueType.Int, level.type)
    }

    @Test
    fun enumByNameUsesTheConstantName() {
        val plain = FlagCodecs.enumByName(Plain.entries)
        assertEquals(FlagValueBox.String("BETA"), plain.encode(Plain.BETA))
        assertEquals(Plain.ALPHA, plain.decode(FlagValueBox.String("ALPHA")))
        assertEquals(listOf(FlagValueBox.String("ALPHA"), FlagValueBox.String("BETA")), plain.cases)
    }

    @Test
    fun enumRejectsAnUnknownRawValueOrAMismatchedBox() {
        assertNull(tier.decode(FlagValueBox.String("enterprise")))
        assertNull(tier.decode(FlagValueBox.Int(1)))
        assertNull(level.decode(FlagValueBox.Int(3)))
        assertNull(level.decode(FlagValueBox.String("1")))
    }

    @Test
    fun everyEnumSurfacesItsCasesForEditors() {
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), tier.cases)
        assertEquals(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)), level.cases)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordFieldTest.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlagRecordFieldTest {

    private enum class Tier(override val flagRawValue: String) : FlagStringEnum {
        PRIMARY("primary"),
        SECONDARY("secondary"),
    }

    @Test
    fun ofTakesTypeAndCasesFromTheCodecAndBoxesTheDefault() {
        val field = FlagRecordField.of("tier", FlagCodecs.stringEnum(Tier.entries), default = Tier.PRIMARY)
        assertEquals(FlagValueType.String, field.type)
        assertEquals(listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")), field.cases)
        assertEquals(FlagValueBox.String("primary"), field.defaultValue)
    }

    @Test
    fun aFieldWithNoCasesSaysSoRatherThanSayingNone() {
        assertNull(FlagRecordField.of("name", FlagCodecs.string).cases)
        assertNull(FlagRecordField.of("name", FlagCodecs.string).defaultValue)
    }

    @Test
    fun anOptionalFieldNeverCarriesADefault() {
        val field = FlagRecordField.of("minimumSpend", FlagCodecs.double, default = 1.0, isOptional = true)
        assertNull(field.defaultValue)
        assertEquals(true, field.isOptional)
    }

    @Test
    fun payloadNameIsTheDecodedNameWhenThereIsOne() {
        val renamed = FlagRecordField.of("applePay", FlagCodecs.boolean, decodedName = "apple_pay")
        assertEquals("apple_pay", renamed.payloadName)
        assertEquals("applePay", FlagRecordField.of("applePay", FlagCodecs.boolean).payloadName)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
```

Expected: FAIL at compilation with `FlagRecordFieldTest.kt:3:41 Unresolved reference 'FlagCodecs'.`

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagEnums.kt`:

```kotlin
package com.andyyhope.featureflag.values

/** An enum stored as a string other than its `name`. */
public interface FlagStringEnum {
    public val flagRawValue: String
}

/** An enum stored as an integer. */
public interface FlagIntEnum {
    public val flagRawValue: Long
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodec.kt`:

```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.records.FlagRecordField

/**
 * Converts a Kotlin value to and from its [FlagValueBox].
 *
 * Decoding is strict: a box holding the wrong case yields `null` rather than coercing.
 */
public interface FlagCodec<T> {

    /** The type this value reports to schemas and to remote payload validation. */
    public val type: FlagValueType

    /** Every value an enum can take, for editors to offer a picker. `null` when there are none. */
    public val cases: List<FlagValueBox>? get() = null

    /** The fields of each record, when this codec reads a list of records. */
    public val recordShape: List<FlagRecordField>? get() = null

    public fun encode(value: T): FlagValueBox

    public fun decode(box: FlagValueBox): T?
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt`:

```kotlin
package com.andyyhope.featureflag.values

import java.net.URI
import java.time.Instant

/** The codec for every supported value type. */
public object FlagCodecs {

    public val boolean: FlagCodec<Boolean> =
        codec(FlagValueType.Bool, { FlagValueBox.Bool(it) }) { (it as? FlagValueBox.Bool)?.value }

    /** Stored as a 64-bit `int`, like Swift's `Int`. A stored value outside `Int` range decodes to `null`. */
    public val int: FlagCodec<Int> = codec(FlagValueType.Int, { FlagValueBox.Int(it.toLong()) }) { box ->
        (box as? FlagValueBox.Int)?.value?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    public val long: FlagCodec<Long> =
        codec(FlagValueType.Int, { FlagValueBox.Int(it) }) { (it as? FlagValueBox.Int)?.value }

    public val double: FlagCodec<Double> =
        codec(FlagValueType.Double, { FlagValueBox.Double(it) }) { (it as? FlagValueBox.Double)?.value }

    public val float: FlagCodec<Float> =
        codec(FlagValueType.Float, { FlagValueBox.Float(it) }) { (it as? FlagValueBox.Float)?.value }

    public val string: FlagCodec<String> =
        codec(FlagValueType.String, { FlagValueBox.String(it) }) { (it as? FlagValueBox.String)?.value }

    /** Copies on the way in and out, so a stored value cannot be changed through an array someone kept. */
    public val byteArray: FlagCodec<ByteArray> =
        codec(FlagValueType.Data, { FlagValueBox.Data(it.copyOf()) }) { (it as? FlagValueBox.Data)?.value?.copyOf() }

    public val instant: FlagCodec<Instant> =
        codec(FlagValueType.Date, { FlagValueBox.Date(it) }) { (it as? FlagValueBox.Date)?.value }

    public val uri: FlagCodec<URI> =
        codec(FlagValueType.Url, { FlagValueBox.Url(it) }) { (it as? FlagValueBox.Url)?.value }

    /** A list, decoded all or nothing: one element of the wrong type fails the whole list. */
    public fun <T> list(element: FlagCodec<T>): FlagCodec<List<T>> = codec(
        FlagValueType.Array(element.type),
        { values -> FlagValueBox.Array(values.map(element::encode)) },
    ) { box ->
        (box as? FlagValueBox.Array)?.value?.map { element.decode(it) ?: return@codec null }
    }

    /** A map with string keys, decoded all or nothing. */
    public fun <T> map(value: FlagCodec<T>): FlagCodec<Map<String, T>> = codec(
        FlagValueType.Dictionary(value.type),
        { values -> FlagValueBox.Dictionary(values.mapValues { value.encode(it.value) }) },
    ) { box ->
        (box as? FlagValueBox.Dictionary)?.value?.mapValues { value.decode(it.value) ?: return@codec null }
    }

    /** An enum stored as its `name`. Publishes every name as a case. */
    public fun <E : Enum<E>> enumByName(entries: List<E>): FlagCodec<E> =
        enumCodec(FlagValueType.String, entries) { FlagValueBox.String(it.name) }

    /** An enum stored as its [FlagStringEnum.flagRawValue]. */
    public fun <E> stringEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagStringEnum =
        enumCodec(FlagValueType.String, entries) { FlagValueBox.String(it.flagRawValue) }

    /** An enum stored as its [FlagIntEnum.flagRawValue]. */
    public fun <E> intEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagIntEnum =
        enumCodec(FlagValueType.Int, entries) { FlagValueBox.Int(it.flagRawValue) }

    private fun <T> codec(
        valueType: FlagValueType,
        encoder: (T) -> FlagValueBox,
        decoder: (FlagValueBox) -> T?,
    ): FlagCodec<T> = object : FlagCodec<T> {
        override val type: FlagValueType = valueType
        override fun encode(value: T): FlagValueBox = encoder(value)
        override fun decode(box: FlagValueBox): T? = decoder(box)
    }

    private fun <E> enumCodec(valueType: FlagValueType, entries: List<E>, toBox: (E) -> FlagValueBox): FlagCodec<E> {
        val byBox = entries.associateBy(toBox)
        val allCases = entries.map(toBox)
        return object : FlagCodec<E> {
            override val type: FlagValueType = valueType
            override val cases: List<FlagValueBox> = allCases
            override fun encode(value: E): FlagValueBox = toBox(value)
            override fun decode(box: FlagValueBox): E? = byBox[box]
        }
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordField.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType

/**
 * One field of a record, described well enough for an editor that has never seen the type it
 * came from.
 *
 * @property cases every value an enum field can take; `null`, not empty, when there are none.
 * @property defaultValue what a stored record missing this field is filled with. A required
 *   field with no default makes such a record unreadable.
 * @property fields the fields of each nested record, when this field is itself a record list.
 * @property isKey whether this field tells one record from another. At most one per shape.
 * @property isOptional whether the field may be absent; an absent or `null` value decodes to
 *   `null`, and a `null` value is left out of the stored form.
 * @property decodedName the key to read this field from in a remote payload, when it is not
 *   [name]. Decoding only: the stored form is always keyed by [name].
 */
public data class FlagRecordField(
    public val name: String,
    public val type: FlagValueType,
    public val cases: List<FlagValueBox>? = null,
    public val defaultValue: FlagValueBox? = null,
    public val fields: List<FlagRecordField>? = null,
    public val isKey: Boolean = false,
    public val isOptional: Boolean = false,
    public val decodedName: String? = null,
) {

    /** The key to read this field from in a payload: [decodedName] if set, else [name]. */
    public val payloadName: String get() = decodedName ?: name

    public companion object {

        /**
         * Describes a field from its codec: type, cases and nested shape come from [codec], and
         * [default] is boxed with it. An optional field never carries a default.
         */
        public fun <T : Any> of(
            name: String,
            codec: FlagCodec<T>,
            default: T? = null,
            isKey: Boolean = false,
            isOptional: Boolean = false,
            decodedName: String? = null,
        ): FlagRecordField = FlagRecordField(
            name = name,
            type = codec.type,
            cases = codec.cases,
            defaultValue = if (isOptional) null else default?.let(codec::encode),
            fields = codec.recordShape,
            isKey = isKey,
            isOptional = isOptional,
            decodedName = decodedName,
        )
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `FlagCodecsTest` (5), `FlagValueCollectionTest` (9) and `FlagRecordFieldTest` (4) pass.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Add FlagCodec, the built-in codecs, enum codecs and FlagRecordField

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task A11: Records runtime

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordCodec.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecords.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/RecordJson.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenModel.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenRecordCodecs.kt`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt` (adds `records`)
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordJsonTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordsCodecTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordsGoldenTest.kt`

**Interfaces:**
- Consumes: `FlagRecordField`, `FlagCodecs`, `FlagCodec` (Task A10); `toJson`, `toBox`, `storageIdentity` (Task A9); `JsonReader` (Task A5); `JsonWriter` (Task A7); `FlagSerializationException.NonFiniteNumber` (Task A4); `Fixtures.text` (Task A2).
- Produces:
  - `public interface FlagRecordCodec<R> { public val shape: List<FlagRecordField>; public val keyField: String?; public fun boxes(record: R): Map<String, FlagValueBox>; public fun fromBoxes(boxes: Map<String, FlagValueBox>): R? }`
  - `public class FlagRecords<R>(public val values: List<R> = emptyList()) : List<R> by values` (equal to any list with the same elements)
  - `internal object RecordJson { fun encode(records: List<Map<String, FlagValueBox>>): String; fun decode(text: String, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>?; fun duplicateKey(text: String, shape: List<FlagRecordField>): FlagValueBox?; fun duplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? }`. The two `duplicateKey` overloads are internal helpers M1c's import and M1d's remote messages need ("two with …").
  - on `FlagCodecs`: `public fun <R> records(codec: FlagRecordCodec<R>): FlagCodec<FlagRecords<R>>` (type `String`, `recordShape = codec.shape`)
  - in the core test source set, package `com.andyyhope.featureflag.golden`: `enum class GoldenTier`, `enum class GoldenLevel`, `data class GoldenEndpoint`, `data class GoldenTarget` exactly as the contract's Kotlin mirror, plus the hand-written `object GoldenEndpointFlagRecord : FlagRecordCodec<GoldenEndpoint>` and `object GoldenTargetFlagRecord : FlagRecordCodec<GoldenTarget>`. **M1b Task B4 deletes `GoldenRecordCodecs.kt`** once KSP generates objects with these names (B3 generates only containers).

- [ ] **Step 1: Write the failing tests and the golden model**

`RecordJsonTest` ports every assertion from `FlagRecordTests`, `FlagRecordKeyTests`, `FlagRecordDefaultTests`, `FlagRecordOptionalAndKeyTests` and `FlagNestedRecordTests` that does not need a later type. The rest are ported with their types: those that go through `FlagPole` or `SnapshotSource`, import, or the schema in M1b/M1c; those that go through `RemoteOverrideSource` (`decodedName`, a nested list sent as real JSON, a duplicate key "says so") in M1d. Swift's `FlagRecords` key subscript has no contract counterpart and is not ported.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenModel.kt`:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

// The Kotlin mirror of fixture-generator/Sources/GoldenModel/GoldenFlags.swift (records and enums).

enum class GoldenTier(override val flagRawValue: String) : FlagStringEnum {
    FREE("free"),
    PRO("pro"),
    ENTERPRISE("enterprise"),
}

enum class GoldenLevel(override val flagRawValue: Long) : FlagIntEnum { ONE(1), TWO(2), THREE(3) }

data class GoldenEndpoint(
    val name: String,
    val url: URI,
    val weight: Int = 1,
    val note: String? = null,
    val kind: GoldenTier = GoldenTier.FREE,
    val targets: FlagRecords<GoldenTarget> = FlagRecords(),
)

data class GoldenTarget(val host: String, val port: Int)
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenRecordCodecs.kt`:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox

// Hand-written stand-ins for what KSP generates in M1b. M1b Task B4 deletes this file when the
// processor starts generating GoldenEndpointFlagRecord and GoldenTargetFlagRecord.

object GoldenTargetFlagRecord : FlagRecordCodec<GoldenTarget> {
    override val shape: List<FlagRecordField> = listOf(
        FlagRecordField.of("host", FlagCodecs.string),
        FlagRecordField.of("port", FlagCodecs.int),
    )
    override val keyField: String? = null

    override fun boxes(record: GoldenTarget): Map<String, FlagValueBox> = mapOf(
        "host" to FlagCodecs.string.encode(record.host),
        "port" to FlagCodecs.int.encode(record.port),
    )

    override fun fromBoxes(boxes: Map<String, FlagValueBox>): GoldenTarget? {
        val host = boxes["host"]?.let(FlagCodecs.string::decode) ?: return null
        val port = boxes["port"]?.let(FlagCodecs.int::decode) ?: return null
        return GoldenTarget(host, port)
    }
}

object GoldenEndpointFlagRecord : FlagRecordCodec<GoldenEndpoint> {
    private val tier = FlagCodecs.stringEnum(GoldenTier.entries)
    private val targets = FlagCodecs.records(GoldenTargetFlagRecord)

    override val shape: List<FlagRecordField> = listOf(
        FlagRecordField.of("name", FlagCodecs.string, isKey = true),
        FlagRecordField.of("url", FlagCodecs.uri),
        FlagRecordField.of("weight", FlagCodecs.int, default = 1),
        FlagRecordField.of("note", FlagCodecs.string, isOptional = true, decodedName = "memo"),
        FlagRecordField.of("kind", tier, default = GoldenTier.FREE),
        FlagRecordField.of("targets", targets, default = FlagRecords()),
    )
    override val keyField: String? = "name"

    override fun boxes(record: GoldenEndpoint): Map<String, FlagValueBox> = buildMap {
        put("name", FlagCodecs.string.encode(record.name))
        put("url", FlagCodecs.uri.encode(record.url))
        put("weight", FlagCodecs.int.encode(record.weight))
        record.note?.let { put("note", FlagCodecs.string.encode(it)) }
        put("kind", tier.encode(record.kind))
        put("targets", targets.encode(record.targets))
    }

    override fun fromBoxes(boxes: Map<String, FlagValueBox>): GoldenEndpoint? {
        val name = boxes["name"]?.let(FlagCodecs.string::decode) ?: return null
        val url = boxes["url"]?.let(FlagCodecs.uri::decode) ?: return null
        val weight = boxes["weight"]?.let(FlagCodecs.int::decode) ?: return null
        val kind = boxes["kind"]?.let(tier::decode) ?: return null
        val targets = boxes["targets"]?.let(targets::decode) ?: return null
        val note = boxes["note"]?.let(FlagCodecs.string::decode)
        return GoldenEndpoint(name, url, weight, note, kind, targets)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordJsonTest.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from FlagRecordTests, FlagRecordKeyTests, FlagRecordDefaultTests,
 * FlagRecordOptionalAndKeyTests and FlagNestedRecordTests: everything that reads or writes the
 * stored text. The cases that need a FlagPole, an import or a RemoteOverrideSource are ported
 * with those types in M1c and M1d.
 */
class RecordJsonTest {

    private enum class Tier(override val flagRawValue: String) : FlagStringEnum {
        PRIMARY("primary"),
        SECONDARY("secondary"),
    }

    private val tier = FlagCodecs.stringEnum(Tier.entries)

    /** FlagRecordTests' Endpoint: no key, no defaults. */
    private val endpointShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string),
        FlagRecordField.of("url", FlagCodecs.uri),
        FlagRecordField.of("enabled", FlagCodecs.boolean),
        FlagRecordField.of("weight", FlagCodecs.int),
        FlagRecordField.of("expires", FlagCodecs.instant),
        FlagRecordField.of("tier", tier),
    )

    private fun endpoint(name: String, enabled: Boolean, weight: Int, tier: Tier): Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String(name),
        "url" to FlagValueBox.Url(URI("https://$name.example")),
        "enabled" to FlagValueBox.Bool(enabled),
        "weight" to FlagValueBox.Int(weight.toLong()),
        "expires" to FlagValueBox.Date(Instant.ofEpochSecond(1_700_000_000)),
        "tier" to FlagValueBox.String(tier.flagRawValue),
    )

    private val staging = endpoint("staging", enabled = false, weight = 7, tier = Tier.SECONDARY)
    private val canary = endpoint("canary", enabled = true, weight = 3, tier = Tier.SECONDARY)

    /** FlagRecordKeyTests' Endpoint: keyed by name. */
    private val keyedShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string, isKey = true),
        FlagRecordField.of("url", FlagCodecs.uri),
    )

    /** FlagRecordDefaultTests' Endpoint: weight and region have defaults. */
    private val defaultedShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string),
        FlagRecordField.of("url", FlagCodecs.uri),
        FlagRecordField.of("weight", FlagCodecs.int, default = 1),
        FlagRecordField.of("region", FlagCodecs.string, default = "au"),
    )

    /** FlagRecordOptionalAndKeyTests' PaymentMethod. */
    private val paymentShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string, isKey = true),
        FlagRecordField.of("applePay", FlagCodecs.boolean, decodedName = "apple_pay"),
        FlagRecordField.of("minimumSpend", FlagCodecs.double, isOptional = true),
    )

    // Wire format

    @Test
    fun datesAndUrlsAreWrittenTheWayEveryOtherFlagWritesThem() {
        val json = RecordJson.encode(listOf(endpoint("prod", enabled = true, weight = 10, tier = Tier.PRIMARY)))
        assertTrue(json.contains("\"expires\":\"2023-11-14T22:13:20Z\""), json)
        assertTrue(json.contains("\"url\":\"https://prod.example\""), json)
    }

    @Test
    fun theStoredFormIsPlainJsonWithSortedKeys() {
        assertEquals(
            """[{"enabled":true,"expires":"2023-11-14T22:13:20Z","name":"canary","tier":"secondary",""" +
                """"url":"https://canary.example","weight":3}]""",
            RecordJson.encode(listOf(canary)),
        )
        val parsed = JsonReader.parse(RecordJson.encode(listOf(canary)).encodeToByteArray()) as JsonValue.Array
        assertEquals(JsonValue.Integer(3), (parsed.items.single() as JsonValue.Object).fields["weight"])
    }

    // Reading through the shape alone

    @Test
    fun storedTextCanBeReadBackFromTheShapeAlone() {
        val records = assertNotNull(RecordJson.decode(RecordJson.encode(listOf(staging, canary)), endpointShape))
        assertEquals(2, records.size)
        assertEquals(FlagValueBox.String("staging"), records.first()["name"])
        assertEquals(FlagValueBox.Int(7), records.first()["weight"])
        assertEquals(FlagValueBox.String("secondary"), records.last()["tier"])
    }

    @Test
    fun aListWithAnIncompleteRecordCannotBeRead() {
        assertNull(RecordJson.decode("""[{"name":"partial","enabled":true}]""", endpointShape))
    }

    @Test
    fun textThatIsNotARecordListCannotBeRead() {
        assertNull(RecordJson.decode("{ not json", endpointShape))
        assertNull(RecordJson.decode("total garbage", endpointShape))
        assertNull(RecordJson.decode("""{"name":"a"}""", endpointShape))
        assertNull(RecordJson.decode("""[1]""", endpointShape))
    }

    @Test
    fun aFieldOfTheWrongTypeFailsTheWholeList() {
        fun list(enabled: String) = """[{"name":"bad","url":"https://x.example","enabled":$enabled,""" +
            """"weight":1,"expires":"2023-11-14T22:13:20Z","tier":"primary"}]"""
        assertEquals(1, RecordJson.decode(list("true"), endpointShape)?.size)
        assertNull(RecordJson.decode(list("\"yes\""), endpointShape))
    }

    @Test
    fun anEmptyListIsAValueLikeAnyOther() {
        assertEquals("[]", RecordJson.encode(emptyList()))
        assertEquals(emptyList(), RecordJson.decode("[]", endpointShape))
    }

    // Numbers JSON cannot write

    @Test
    fun aNonFiniteFieldIsRefusedRatherThanWritten() {
        val records = listOf(
            mapOf("name" to FlagValueBox.String("fine"), "weight" to FlagValueBox.Int(1)),
            mapOf("name" to FlagValueBox.String("bad"), "ratio" to FlagValueBox.Double(Double.POSITIVE_INFINITY)),
        )
        assertFailsWith<FlagSerializationException.NonFiniteNumber> { RecordJson.encode(records) }
        assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            RecordJson.encode(listOf(mapOf("ratio" to FlagValueBox.Float(Float.NaN))))
        }
    }

    @Test
    fun ordinaryNumbersAreNotMistakenForNonFiniteOnes() {
        val record = mapOf(
            "a" to FlagValueBox.Double(0.0),
            "b" to FlagValueBox.Float(-1.5f),
            "c" to FlagValueBox.Int(Long.MAX_VALUE),
        )
        val records = listOf(record)
        assertEquals("""[{"a":0,"b":-1.5,"c":9223372036854775807}]""", RecordJson.encode(records))
    }

    // Keys

    @Test
    fun aDuplicateKeyMakesTheListUnreadable() {
        val text = """[{"name":"a","url":"https://one.example"},{"name":"a","url":"https://two.example"}]"""
        assertNull(RecordJson.decode(text, keyedShape))
        assertEquals(FlagValueBox.String("a"), RecordJson.duplicateKey(text, keyedShape))
    }

    @Test
    fun distinctKeysAreFineAndARecordWithNoKeyAllowsIdenticalRecords() {
        val text = """[{"name":"a","url":"https://one.example"},{"name":"b","url":"https://two.example"}]"""
        assertEquals(2, RecordJson.decode(text, keyedShape)?.size)
        assertNull(RecordJson.duplicateKey(text, keyedShape))
        val noteShape = listOf(FlagRecordField.of("text", FlagCodecs.string))
        assertEquals(2, RecordJson.decode("""[{"text":"same"},{"text":"same"}]""", noteShape)?.size)
    }

    @Test
    fun keysCompareAsTheyAreStoredNotAsTheyAreHeld() {
        val shape = listOf(FlagRecordField.of("at", FlagCodecs.instant, isKey = true))
        val records = listOf(
            mapOf("at" to FlagValueBox.Date(Instant.ofEpochMilli(1_000_100))),
            mapOf("at" to FlagValueBox.Date(Instant.ofEpochMilli(1_000_900))),
        )
        assertNotNull(RecordJson.duplicateKey(records, shape))
    }

    @Test
    fun aFloatKeyThatOverflowsDoesNotCrashTheDuplicateCheck() {
        val shape = listOf(FlagRecordField.of("ratio", FlagCodecs.float, isKey = true))
        assertNull(RecordJson.decode("""[{"ratio":1e39},{"ratio":1e40}]""", shape))
        assertEquals(1, RecordJson.decode("""[{"ratio":1e39}]""", shape)?.size)
    }

    // Defaults

    @Test
    fun aRecordMissingADefaultedFieldIsFilledRatherThanRejected() {
        val text = """[{"name":"legacy","url":"https://legacy.example"}]"""
        val records = assertNotNull(RecordJson.decode(text, defaultedShape))
        assertEquals(FlagValueBox.Int(1), records.single()["weight"])
        assertEquals(FlagValueBox.String("au"), records.single()["region"])
    }

    @Test
    fun aRecordMissingAFieldWithNoDefaultIsStillRejected() {
        assertNull(RecordJson.decode("""[{"url":"https://nameless.example"}]""", defaultedShape))
    }

    @Test
    fun aFieldThatIsPresentButWrongIsRejectedEvenWithADefault() {
        assertNull(RecordJson.decode("""[{"name":"bad","url":"https://x.example","weight":"heavy"}]""", defaultedShape))
    }

    @Test
    fun anExplicitNullIsTreatedAsAbsent() {
        val records = RecordJson.decode("""[{"name":"n","url":"https://x.example","weight":null}]""", defaultedShape)
        assertEquals(FlagValueBox.Int(1), records?.single()?.get("weight"))
    }

    // Optional fields and custom keys

    @Test
    fun anAbsentOrNullOptionalFieldIsLeftOut() {
        val texts = listOf(
            """[{"name":"card","applePay":false}]""",
            """[{"name":"card","applePay":false,"minimumSpend":null}]""",
        )
        for (text in texts) {
            val record = RecordJson.decode(text, paymentShape)?.single()
            assertEquals(mapOf("name" to FlagValueBox.String("card"), "applePay" to FlagValueBox.Bool(false)), record)
        }
    }

    @Test
    fun aPresentOptionalFieldDecodesAndAWrongOneStillRejects() {
        val present = RecordJson.decode("""[{"name":"w","applePay":true,"minimumSpend":10.5}]""", paymentShape)
        assertEquals(FlagValueBox.Double(10.5), present?.single()?.get("minimumSpend"))
        assertNull(RecordJson.decode("""[{"name":"w","applePay":true,"minimumSpend":"lots"}]""", paymentShape))
    }

    @Test
    fun theStoredFormIsKeyedByPropertyNameAndOmitsNilOptionals() {
        val json = RecordJson.encode(
            listOf(mapOf("name" to FlagValueBox.String("wallet"), "applePay" to FlagValueBox.Bool(true))),
        )
        assertTrue(json.contains("applePay"))
        assertFalse(json.contains("apple_pay"))
        assertFalse(json.contains("minimumSpend"))
        val backendKey = """[{"name":"wallet","apple_pay":true}]"""
        assertNull(RecordJson.decode(backendKey, paymentShape), "the stored form is read by name")
    }

    // Nested lists

    @Test
    fun aNestedListIsWrittenAsAStringInsideItsParent() {
        val target = mapOf("host" to FlagValueBox.String("a"), "port" to FlagValueBox.Int(1))
        val targets = FlagValueBox.String(RecordJson.encode(listOf(target)))
        val json = RecordJson.encode(listOf(mapOf("name" to FlagValueBox.String("ap"), "targets" to targets)))
        assertEquals("""[{"name":"ap","targets":"[{\"host\":\"a\",\"port\":1}]"}]""", json)
    }

    @Test
    fun aNestedListCanBeReadFromItsFieldBoxWithTheShapeAlone() {
        val targetShape = listOf(
            FlagRecordField.of("host", FlagCodecs.string),
            FlagRecordField.of("port", FlagCodecs.int),
        )
        val clusterShape = listOf(
            FlagRecordField.of("name", FlagCodecs.string),
            FlagRecordField(name = "targets", type = FlagValueType.String, fields = targetShape),
        )
        val text = """[{"name":"ap","targets":"[{\"host\":\"a\",\"port\":1}]"}]"""
        val cluster = RecordJson.decode(text, clusterShape)?.single()
        val targets = (cluster?.get("targets") as FlagValueBox.String).value
        val nested = RecordJson.decode(targets, assertNotNull(clusterShape[1].fields))?.single()
        assertEquals(FlagValueBox.String("a"), nested?.get("host"))
        assertEquals(FlagValueBox.Int(1), nested?.get("port"))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordsCodecTest.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.golden.GoldenEndpoint
import com.andyyhope.featureflag.golden.GoldenEndpointFlagRecord
import com.andyyhope.featureflag.golden.GoldenTarget
import com.andyyhope.featureflag.golden.GoldenTargetFlagRecord
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** FlagCodecs.records with the hand-written GoldenEndpoint codec. */
class FlagRecordsCodecTest {

    private val codec = FlagCodecs.records(GoldenEndpointFlagRecord)

    @Test
    fun aRecordListIsAStringToAnybodyWhoDoesNotKnowBetter() {
        assertEquals(FlagValueType.String, codec.type)
        assertEquals(GoldenEndpointFlagRecord.shape, codec.recordShape)
        assertNull(codec.cases)
    }

    @Test
    fun theShapeCarriesKeyDefaultsCasesAndNestedFields() {
        val shape = GoldenEndpointFlagRecord.shape.associateBy { it.name }
        assertEquals(
            listOf("name", "url", "weight", "note", "kind", "targets"),
            GoldenEndpointFlagRecord.shape.map { it.name },
        )
        assertEquals(true, shape.getValue("name").isKey)
        assertEquals(false, shape.getValue("url").isKey)
        assertEquals(FlagValueBox.Int(1), shape.getValue("weight").defaultValue)
        assertNull(shape.getValue("name").defaultValue)
        assertEquals("memo", shape.getValue("note").payloadName)
        assertEquals(3, shape.getValue("kind").cases?.size)
        assertEquals(FlagValueBox.String("[]"), shape.getValue("targets").defaultValue)
        assertEquals(listOf("host", "port"), shape.getValue("targets").fields?.map { it.name })
        assertEquals(listOf(FlagValueType.String, FlagValueType.Int), shape.getValue("targets").fields?.map { it.type })
        assertNull(shape.getValue("name").fields)
    }

    @Test
    fun aNestedListRoundTripsThroughTheBox() {
        val targets = FlagRecords(listOf(GoldenTarget("a", 1), GoldenTarget("b", 2)))
        val value = FlagRecords(listOf(GoldenEndpoint("ap", URI("https://ap.example"), targets = targets)))
        assertEquals(value, codec.decode(codec.encode(value)))
        assertEquals(listOf("a", "b"), codec.decode(codec.encode(value))?.single()?.targets?.map { it.host })
    }

    @Test
    fun anOptionalNilRoundTripsThroughTheBox() {
        val value = FlagRecords(listOf(GoldenEndpoint("card", URI("https://card.example"), note = null)))
        assertEquals(value, codec.decode(codec.encode(value)))
    }

    @Test
    fun aWrongNestedRecordFailsTheWholeList() {
        val text = """[{"name":"a","url":"https://a.example","targets":"[{\"host\":\"a\",\"port\":\"https\"}]"}]"""
        assertNull(codec.decode(FlagValueBox.String(text)))
    }

    @Test
    fun recordsBuiltFromBoxesAloneMatchWhatTheTypeWouldHaveWritten() {
        val canary = GoldenEndpoint("canary", URI("https://canary.example"), kind = GoldenTier.PRO)
        val staging = GoldenEndpoint("staging", URI("https://staging.example"))
        val built = FlagValueBox.String(RecordJson.encode(listOf(canary, staging).map(GoldenEndpointFlagRecord::boxes)))
        assertEquals(built, codec.encode(FlagRecords(listOf(canary, staging))))
    }

    @Test
    fun aValueThatIsNotEvenTextIsNotRecords() {
        assertNull(codec.decode(FlagValueBox.Int(3)))
        assertNull(FlagCodecs.records(GoldenTargetFlagRecord).decode(FlagValueBox.String("{ not json")))
    }

    @Test
    fun flagRecordsEqualsAnyListWithTheSameElements() {
        val targets = listOf(GoldenTarget("a", 1))
        assertEquals<List<GoldenTarget>>(targets, FlagRecords(targets))
        assertEquals<List<GoldenTarget>>(FlagRecords(targets), targets)
        assertEquals(targets.hashCode(), FlagRecords(targets).hashCode())
        assertEquals(1, FlagRecords(targets).size)
    }
}
```

`RecordsGoldenTest.lists` must stay identical to `TransportFixtures.recordLists` in the generator.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordsGoldenTest.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.golden.GoldenEndpoint
import com.andyyhope.featureflag.golden.GoldenEndpointFlagRecord
import com.andyyhope.featureflag.golden.GoldenTarget
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals

class RecordsGoldenTest {

    private val codec = FlagCodecs.records(GoldenEndpointFlagRecord)

    /** Built identically by `TransportFixtures.recordLists` in the Swift generator. */
    private val lists = mapOf(
        "default" to listOf(GoldenEndpoint("prod", URI("https://prod.example.com"))),
        "empty" to emptyList(),
        "full" to listOf(
            GoldenEndpoint(
                "prod", URI("https://prod.example.com/v1?x=1"), weight = 10, note = "primary \"quoted\" / slash",
                kind = GoldenTier.ENTERPRISE,
                targets = FlagRecords(listOf(GoldenTarget("a.example", 443), GoldenTarget("b.example", 8443))),
            ),
            GoldenEndpoint("staging", URI("https://staging.example.com"), weight = 0, kind = GoldenTier.PRO),
            GoldenEndpoint("ünïcode", URI("https://example.com/%C3%BC"), weight = -3, note = ""),
        ),
    )

    @Test
    fun writesEveryListByteForByteAsSwiftDoes() {
        for ((name, endpoints) in lists) {
            val expected = FlagValueBox.String(Fixtures.text("records/$name.txt"))
            assertEquals(expected, codec.encode(FlagRecords(endpoints)), name)
        }
    }

    @Test
    fun readsEverySwiftListBack() {
        for ((name, endpoints) in lists) {
            val decoded = codec.decode(FlagValueBox.String(Fixtures.text("records/$name.txt")))
            assertEquals<List<GoldenEndpoint>?>(endpoints, decoded, name)
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
```

Expected: FAIL at compilation with `GoldenModel.kt:3:42 Unresolved reference 'FlagRecords'.`

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordCodec.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.values.FlagValueBox

/**
 * Converts a record to and from its fields' boxes. Generated by KSP as `RFlagRecord` for each
 * `@FlagRecord` class `R`; written by hand only when the processor cannot be used.
 */
public interface FlagRecordCodec<R> {

    /** The fields, in declaration order. */
    public val shape: List<FlagRecordField>

    /** The name of the field that tells one record from another, if the record names one. */
    public val keyField: String?

    /** Each field's value, keyed by field name. A `null` optional field is left out. */
    public fun boxes(record: R): Map<String, FlagValueBox>

    /** Rebuilds a record, or returns `null` if a required field is missing or holds the wrong type. */
    public fun fromBoxes(boxes: Map<String, FlagValueBox>): R?
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecords.kt`:

```kotlin
package com.andyyhope.featureflag.records

/**
 * A list of records, stored as JSON text.
 *
 * The list is a string as far as storage, transport and validation are concerned; the shape
 * rides along in the schema, which is what lets a companion app render an editor for it.
 * Equal to any list with the same elements.
 */
public class FlagRecords<R>(public val values: List<R> = emptyList()) : List<R> by values {
    override fun equals(other: Any?): Boolean = other is List<*> && values == other
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "FlagRecords($values)"
}
```

`RecordJson.decode` reads each field by `name`, never `decodedName`, exactly as `parsedRecords` does. `decodedName` applies only to remote payloads (M1d). Nested lists are checked only for being strings here; the nested codec checks them when `fromBoxes` builds the record.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/RecordJson.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.storageIdentity
import com.andyyhope.featureflag.values.toBox
import com.andyyhope.featureflag.values.toJson

/** The stored text of a record list, read and written through a shape alone, as `FlagRecord.swift` does. */
internal object RecordJson {

    /**
     * Compact JSON with sorted keys, one object per record. A nested record list is already a
     * string box, so it is written as a string inside its parent. Throws
     * [FlagSerializationException.NonFiniteNumber] for an infinity or a NaN.
     */
    fun encode(records: List<Map<String, FlagValueBox>>): String {
        if (records.any { record -> record.values.any { it.containsNonFiniteNumber } }) {
            throw FlagSerializationException.NonFiniteNumber(null)
        }
        val objects = records.map { record -> JsonValue.Object(record.mapValues { it.value.toJson() }) }
        return JsonWriter.compact(JsonValue.Array(objects))
    }

    /**
     * The records in [text], or `null` when it is not a JSON array of objects, when any record
     * cannot be read through [shape], or when two records share a key.
     *
     * Per field, by [FlagRecordField.name]: absent or `null` is left out when optional, filled
     * from the default when there is one, and otherwise fails the whole list; present but of the
     * wrong type always fails it. Nested lists are only checked to be strings here; their own
     * codec checks them when the record is built.
     */
    fun decode(text: String, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>? =
        parse(text, shape)?.takeIf { duplicateKey(it, shape) == null }

    /** The first key claimed by more than one record in [text], when that is what makes it unreadable. */
    fun duplicateKey(text: String, shape: List<FlagRecordField>): FlagValueBox? =
        parse(text, shape)?.let { duplicateKey(it, shape) }

    /** The first key claimed by more than one record, compared as stored rather than as held. */
    fun duplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? {
        val key = shape.firstOrNull { it.isKey }?.name ?: return null
        val seen = HashSet<String>()
        return records.mapNotNull { it[key] }.firstOrNull { !seen.add(it.storageIdentity) }
    }

    private fun parse(text: String, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>? {
        val parsed = runCatching { JsonReader.parse(text.encodeToByteArray()) }.getOrNull()
        val objects = (parsed as? JsonValue.Array)?.items ?: return null
        return objects.map { item -> record(item as? JsonValue.Object ?: return null, shape) ?: return null }
    }

    private fun record(json: JsonValue.Object, shape: List<FlagRecordField>): Map<String, FlagValueBox>? {
        val boxes = LinkedHashMap<String, FlagValueBox>()
        for (field in shape) {
            val raw = json.fields[field.name]
            val box = when {
                raw != null && raw != JsonValue.Null -> raw.toBox(field.type) ?: return null
                field.isOptional -> null
                else -> field.defaultValue ?: return null
            }
            if (box != null) boxes[field.name] = box
        }
        return boxes
    }
}
```

Replace `FlagCodecs.kt` with this version, which adds `records`:

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt`:

```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.records.RecordJson
import java.net.URI
import java.time.Instant

/** The codec for every supported value type. */
public object FlagCodecs {

    public val boolean: FlagCodec<Boolean> =
        codec(FlagValueType.Bool, { FlagValueBox.Bool(it) }) { (it as? FlagValueBox.Bool)?.value }

    /** Stored as a 64-bit `int`, like Swift's `Int`. A stored value outside `Int` range decodes to `null`. */
    public val int: FlagCodec<Int> = codec(FlagValueType.Int, { FlagValueBox.Int(it.toLong()) }) { box ->
        (box as? FlagValueBox.Int)?.value?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    public val long: FlagCodec<Long> =
        codec(FlagValueType.Int, { FlagValueBox.Int(it) }) { (it as? FlagValueBox.Int)?.value }

    public val double: FlagCodec<Double> =
        codec(FlagValueType.Double, { FlagValueBox.Double(it) }) { (it as? FlagValueBox.Double)?.value }

    public val float: FlagCodec<Float> =
        codec(FlagValueType.Float, { FlagValueBox.Float(it) }) { (it as? FlagValueBox.Float)?.value }

    public val string: FlagCodec<String> =
        codec(FlagValueType.String, { FlagValueBox.String(it) }) { (it as? FlagValueBox.String)?.value }

    /** Copies on the way in and out, so a stored value cannot be changed through an array someone kept. */
    public val byteArray: FlagCodec<ByteArray> =
        codec(FlagValueType.Data, { FlagValueBox.Data(it.copyOf()) }) { (it as? FlagValueBox.Data)?.value?.copyOf() }

    public val instant: FlagCodec<Instant> =
        codec(FlagValueType.Date, { FlagValueBox.Date(it) }) { (it as? FlagValueBox.Date)?.value }

    public val uri: FlagCodec<URI> =
        codec(FlagValueType.Url, { FlagValueBox.Url(it) }) { (it as? FlagValueBox.Url)?.value }

    /** A list, decoded all or nothing: one element of the wrong type fails the whole list. */
    public fun <T> list(element: FlagCodec<T>): FlagCodec<List<T>> = codec(
        FlagValueType.Array(element.type),
        { values -> FlagValueBox.Array(values.map(element::encode)) },
    ) { box ->
        (box as? FlagValueBox.Array)?.value?.map { element.decode(it) ?: return@codec null }
    }

    /** A map with string keys, decoded all or nothing. */
    public fun <T> map(value: FlagCodec<T>): FlagCodec<Map<String, T>> = codec(
        FlagValueType.Dictionary(value.type),
        { values -> FlagValueBox.Dictionary(values.mapValues { value.encode(it.value) }) },
    ) { box ->
        (box as? FlagValueBox.Dictionary)?.value?.mapValues { value.decode(it.value) ?: return@codec null }
    }

    /** An enum stored as its `name`. Publishes every name as a case. */
    public fun <E : Enum<E>> enumByName(entries: List<E>): FlagCodec<E> =
        enumCodec(FlagValueType.String, entries) { FlagValueBox.String(it.name) }

    /** An enum stored as its [FlagStringEnum.flagRawValue]. */
    public fun <E> stringEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagStringEnum =
        enumCodec(FlagValueType.String, entries) { FlagValueBox.String(it.flagRawValue) }

    /** An enum stored as its [FlagIntEnum.flagRawValue]. */
    public fun <E> intEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagIntEnum =
        enumCodec(FlagValueType.Int, entries) { FlagValueBox.Int(it.flagRawValue) }

    /**
     * A list of records, stored as JSON text in a `string` flag. Decoding is all or nothing: one
     * unreadable record, or two sharing a key, fails the whole list.
     */
    public fun <R> records(codec: FlagRecordCodec<R>): FlagCodec<FlagRecords<R>> = object : FlagCodec<FlagRecords<R>> {
        override val type: FlagValueType = FlagValueType.String
        override val recordShape: List<FlagRecordField> = codec.shape

        override fun encode(value: FlagRecords<R>): FlagValueBox =
            FlagValueBox.String(RecordJson.encode(value.map(codec::boxes)))

        override fun decode(box: FlagValueBox): FlagRecords<R>? {
            val text = (box as? FlagValueBox.String)?.value ?: return null
            val records = RecordJson.decode(text, codec.shape) ?: return null
            return FlagRecords(records.map { codec.fromBoxes(it) ?: return null })
        }
    }

    private fun <T> codec(
        valueType: FlagValueType,
        encoder: (T) -> FlagValueBox,
        decoder: (FlagValueBox) -> T?,
    ): FlagCodec<T> = object : FlagCodec<T> {
        override val type: FlagValueType = valueType
        override fun encode(value: T): FlagValueBox = encoder(value)
        override fun decode(box: FlagValueBox): T? = decoder(box)
    }

    private fun <E> enumCodec(valueType: FlagValueType, entries: List<E>, toBox: (E) -> FlagValueBox): FlagCodec<E> {
        val byBox = entries.associateBy(toBox)
        val allCases = entries.map(toBox)
        return object : FlagCodec<E> {
            override val type: FlagValueType = valueType
            override val cases: List<FlagValueBox> = allCases
            override fun encode(value: E): FlagValueBox = toBox(value)
            override fun decode(box: FlagValueBox): E? = byBox[box]
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL`. `RecordJsonTest` (22), `FlagRecordsCodecTest` (8) and `RecordsGoldenTest` (2) pass, so `records/default.txt`, `records/empty.txt` and `records/full.txt` match byte for byte. `featureflag-core` now runs 130 tests.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src
git commit -m "$(cat <<'EOF'
Add the records runtime: FlagRecords, FlagRecordCodec and record JSON

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```
