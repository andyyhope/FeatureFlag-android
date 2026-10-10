# M2a Android Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Kotlin core its first Android half, and the core prerequisites it needs:
- convention plugins for Android libraries, detekt and the shared fixtures, and a new `:featureflag-android` module;
- `FlagValueJson`, a public box ↔ JSON-text codec;
- a hardened `FlagSchema.write` and a QR decompression plausibility check;
- `SharedPreferencesSource`, `FlagBridgeContract` with `publishSchema(context)`, and the host-side `FlagSignalChannel`.

**Architecture:** Core stays a plain JVM module and gains only `FlagValueJson` (and the internal `JsonReader.parseFragment` behind it) plus two hardening changes. `:featureflag-android` is an Android library with no manifest components and one runtime dependency, `api(project(":featureflag-core"))`. Its source stores booleans, longs, floats and strings natively and every other type as `FlagValueJson` text, and announces changes from one `OnSharedPreferenceChangeListener` through core's `FlagChangeEmitter`. The signal channel runs handlers on the main thread, inline when it is already there. Build logic moves out of the root `subprojects {}` block into three convention plugins that both the JVM and the Android plugin apply.

**Tech Stack:** Gradle 9.5.1; AGP 9.1.1 with built-in Kotlin; Kotlin 2.2.10; kotlinx-coroutines 1.10.2; JUnit 4.13.2 + kotlin-test-junit; Turbine 1.2.1; Robolectric 4.16; AndroidX Test (core 1.7.0, runner 1.7.0, rules 1.7.0, ext-junit 1.3.0); detekt 1.23.8 with `detekt-formatting`.

**Spec:** [`docs/superpowers/specs/2026-10-08-android-parity-design.md`](../specs/2026-10-08-android-parity-design.md). **Contract:** [`2026-10-10-m2-overview.md`](2026-10-10-m2-overview.md). Its names and signatures are binding; where this plan had to fill a gap the contract leaves open, the gap is listed under "Contract issues found" at the end.

## Global Constraints

- Everything in the M2 overview's Global Constraints applies. In particular:
  - **Every Gradle command runs with** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
  - Android library settings: `compileSdk = 36`, `minSdk = 26`, Java and Kotlin target 17, toolchain 21, `explicitApi()`, `allWarningsAsErrors = true`, `testOptions.unitTests.isIncludeAndroidResources = true`.
  - `featureflag-android` has exactly one runtime dependency, `api(project(":featureflag-core"))`, and uses framework APIs only.
  - Packages follow directories. Android unit tests use `@RunWith(AndroidJUnit4::class)` with `@Config(sdk = [36])`.
  - Commits are imperative sentence case, with no type prefix and no trailing period, and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - Definition of done: `./gradlew build detekt` is green, and every test the task names passes.
- **JDK APIs allowed in core** gain `java.nio.file.Files`, `java.nio.file.StandardCopyOption` and `java.nio.file.AtomicMoveNotSupportedException` (A3). Nothing else is added; A3 never imports `java.nio.file.Path`.
- **The Android SDK location** comes from `local.properties` (`sdk.dir=…`), which `.gitignore` already excludes. A1 Step 1 creates it if it is missing. It is never committed.
- **The first Robolectric run downloads** `android-all-instrumented` for SDK 36 from Maven Central, which took about two and a half minutes here. Later runs take seconds.
- **AGP 9 creates unit-test tasks for the debug build type only.** The Android unit tests run as `:featureflag-android:testDebugUnitTest`; there is no `testReleaseUnitTest`. `./gradlew build` runs them, and runs lint.
- Every command block below runs from the repository root.

## Review Focus

These are the inputs this plan's code must survive that no happy path exercises. Each is pinned by a named test.

1. **The store holds a foreign or mistyped value** (the overview's Review Focus 2). The preferences file belongs to the host, so the host's own code may have written the same key with `putInt`, a raw string or a string set.
   - A type-guided read returns `null`, so resolution falls through.
   - A write replaces it with a value of the right class.
   - Pinned by A4 `aValueStoredWithAnotherTypeReadsAsNothing`.
2. **A garbled QR scan that claims a huge length.** The decoder used to allocate whatever the 4-byte prefix claimed, up to 50,000,000 bytes, before inflating a single byte. Pinned by A3 `aDeflateBombIsRefusedBeforeItsOutputIsAllocated`, which measures the allocation, and by `aCodeClaimingExactlyTheCapStillDecodes`, which proves a genuine 50,000,000-byte code still passes the new check.
3. **Two processes or threads publishing the schema at once.** A cold-started host and a racing `Application.onCreate` both call `publishSchema`. Losing the `mkdirs()` race must not fail the launch. Pinned by A3 `aDirectoryAnotherWriterCreatedFirstIsNotAnError` and `concurrentWritersAllSucceedAndLeaveOneCompleteFile`.
4. **A main thread that is busy when a signal arrives.** The provider's binder thread must not wait forever, and a signal reported as not handled must not run later. Pinned by A6 `deliverReturnsFalseAfterTheTimeoutWhenTheMainThreadIsBlocked`.
5. **`clear()` on the companion file** reports a `null` key on API 30 and later. Pinned by A4 `clearingTheFileEmitsAWholesaleChange`.

---

## Seams this plan relies on (not fixed by the contract)

**How this plan was checked.** Every task below was executed in order, in a throwaway `git worktree` of this repository at `a61dec7`, by pasting this plan's code exactly. For each task:
- the "watch it fail" step was run and failed exactly as the step says (the messages quoted there are copied from those runs);
- the task's own test command then passed;
- `./gradlew build detekt` was green, with `allWarningsAsErrors`, `explicitApi()`, detekt and `detekt-formatting` all active, and Android lint reporting "No issues found".

The last check was `./gradlew clean build detekt :featureflag-android:assembleRelease --no-build-cache`: `BUILD SUCCESSFUL`, with 821 tests and no failures (core 707, of which 18 are new; ksp 47; test 7; android 60). The Android unit tests and the A3 tests were then rerun five more times with `--rerun`; all five runs passed. A1 Step 6 (detekt reads `src/androidTest/kotlin`) was checked with the probe file that step describes. Finally, a script rebuilt all 28 files this plan touches from `a61dec7` plus this document's own blocks (whole files, and every `replace … with …` pair, each found exactly once), and the result matched the passing tree byte for byte. Nothing here was run on a device or an emulator, and nothing was published.

- **Robolectric runs each test on the main thread.** `Looper.getMainLooper().isCurrentThread` is true on the test thread. Two consequences:
  - `SharedPreferencesImpl` calls change listeners inline during a `commit()` made on the main thread, and posts them to the main looper otherwise. The A4 tests still call `shadowOf(Looper.getMainLooper()).idle()` after each write, as the contract asks; it is harmless when nothing is posted, and required for any write a later task makes from another thread.
  - A6 tests that need a busy or free main thread deliver from a background executor and decide whether the test thread idles the main looper.
- **`SharedPreferencesImpl`'s typed getters throw `ClassCastException`** when a key holds another class (`(Long) mMap.get(key)`). A4's type-guided reads rely on that. `putInt` stores an `Integer`, so `getLong` on it throws, which is what `aValueStoredWithAnotherTypeReadsAsNothing` pins.
- **`clear()` reports a `null` key** under Robolectric 4.16 at SDK 36 with this module's default test manifest. No `targetSdk` had to be set.
- **detekt's default `source`** is `src/{main,test}/{java,kotlin}`. The Android plugin replaces it with `src/main/kotlin`, `src/test/kotlin` and `src/androidTest/kotlin`. The JVM modules keep the default.
- **Build-logic plugins are loaded once, in the root classloader.** The root `plugins {}` block declares `com.android.library`, `kotlin.jvm`, `ksp` and detekt with `apply false`; build-logic depends on the same versions through the catalog. The root `build.gradle.kts` therefore still compiles against detekt's classes.
- **M1 code used as it is:**
  - `FlagChangeEmitter` (public; `emit` never suspends; a collector registers before its first suspension);
  - `FlagException.UnsupportedValue(key)` (A4 throws it for a non-finite double);
  - `FlagSerializationException.NonFiniteNumber(key: FlagKey?)` with `public val key`;
  - `FlagSchema.write`, `jsonBytes`, `read`, `FILE_NAME`; `FlagPole<*>.publishSchema(directory: File)` in `com.andyyhope.featureflag.transport`;
  - `FlagRef(keyPath, propertyName, description, remoteKey, codec, defaultProvider)`, `FlagDescriptor.of(…)`, `FlagSchemaNode.Flag`, `FlagContainerSpec`, which the Android tests use to build small specs by hand;
  - internal `BoxJson` (`toJson`, `toBox`), `JsonWriter.compact`, `JsonReader`, `JsonSyntaxException`, and core's test helpers `demoSpec`, `specOf` (in `schema/SchemaFixtures.kt`).

## Files this plan creates or modifies

```
gradle/libs.versions.toml                                   (A1: modified)
gradle.properties                                           (A1: modified; android.useAndroidX=true)
settings.gradle.kts                                         (A1: modified; include :featureflag-android)
build.gradle.kts                                            (A1: modified; subprojects {} removed)
build-logic/build.gradle.kts                                (A1: modified)
build-logic/src/main/kotlin/
  JvmLibraryConventionPlugin.kt                             (A1: modified)
  AndroidLibraryConventionPlugin.kt  DetektConventionPlugin.kt  FixturesConventionPlugin.kt   (A1)
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/
  values/FlagValueJson.kt                                   (A2)
  json/JsonReader.kt                                        (A2: modified)
  schema/FlagSchema.kt                                      (A3: modified)
  transport/FlagQRCode.kt                                   (A3: modified)
featureflag-core/src/test/kotlin/com/andyyhope/featureflag/
  values/FlagValueJsonTest.kt                               (A2)
  json/JsonReaderTest.kt                                    (A2: modified)
  schema/FlagSchemaFileTest.kt                              (A3: modified)
  transport/FlagQRCodeTest.kt                               (A3: modified)
featureflag-android/
  build.gradle.kts  src/main/AndroidManifest.xml            (A1)
  src/main/kotlin/com/andyyhope/featureflag/android/
    sources/SharedPreferencesSource.kt                      (A4)
    bridge/FlagBridgeContract.kt                            (A4: PREFERENCES_NAME only; A5: complete)
    transport/AndroidTransport.kt                           (A5)
    signals/FlagSignalChannel.kt                            (A6)
  src/test/kotlin/com/andyyhope/featureflag/android/
    AndroidSmokeTest.kt                                     (A1)
    sources/SharedPreferencesSourceTest.kt                  (A4)
    bridge/FlagBridgeContractTest.kt                        (A5)
    transport/AndroidTransportTest.kt                       (A5)
    signals/FlagSignalChannelTest.kt                        (A6)
local.properties                                            (A1, only if missing; never committed)
```

### Swift tests ported, and the ones skipped

| Swift test (0.12.0) | Ported to | Notes |
|---|---|---|
| `UserDefaultsSourceTests` (10 tests) | A4 `SharedPreferencesSourceTest` | `testStoresPrimitivesAsThemselves` adds a float and checks the double is JSON text. `testStoresURLAsAString` becomes `storesUrlAsJsonText`. |
| `SourceEdgeCaseTests` "UserDefaultsSource" | A4 | `testAnAppGroupSourceNamesItselfForDiagnostics` becomes `theNameIsTheSourceName` (plus `companion()`'s default). `testAnInvalidStoredURLIsRejected` also rejects JSON `""`. Dropped: `testAnAppGroupSourceIsNilWhenTheGroupIsUnavailable` (no App Group). |
| `AdversarialRegressionTests.WriteAmplificationTests`, the three UserDefaults tests | A4 `anIdenticalWriteAnnouncesNothing`, `removingSomethingAbsentAnnouncesNothing`, `aFlagChangingTypeIsStillRewritten` | Plus `anIdenticalWriteDoesNotTouchTheFile`, which counts editors. |
| `DarwinNotificationTests.testOneSourceSeesAnotherSourceWriteToTheSharedSuite` | A4 `oneSourceSeesAnotherSourceWriteToTheSameFile` | Android announces `Keys`, not iOS's `all`. The two Darwin-only tests are dropped. |
| `FlagSignalChannelTests` (12 tests) | A6 `FlagSignalChannelTest` | Reworded as the overview says. Dropped: `testAChannelIsNilWhenTheAppGroupIsUnavailable`. |
| `MultipleObserverTests` (6 tests) | A6 `FlagSignalChannelTest` | `testAnObserverStillRunsEachSignalOnlyOnce` has no Darwin bell to ring twice; it checks that one `deliver` runs the matching observer once and the other observer not at all. |

---

### Task A1: Convention plugins and the `:featureflag-android` module

**Files:**
- Modify (full replacement): `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt`, `build.gradle.kts`
- Modify: `settings.gradle.kts`, `gradle.properties`
- Create: `build-logic/src/main/kotlin/DetektConventionPlugin.kt`, `build-logic/src/main/kotlin/FixturesConventionPlugin.kt`, `build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
- Create: `featureflag-android/build.gradle.kts`, `featureflag-android/src/main/AndroidManifest.xml`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/AndroidSmokeTest.kt`
- Local only: `local.properties`

**Interfaces:**
- Consumes (M1): `featureflag.jvm-library`, the root detekt setup, `config/detekt/detekt.yml`, `FlagKey`.
- Produces:
  - plugin ids `featureflag.android-library`, `featureflag.detekt`, `featureflag.fixtures`; `featureflag.jvm-library` now applies the last two;
  - catalog entries `agp`, `robolectric`, `androidx-test-core`, `androidx-test-runner`, `androidx-test-rules`, `androidx-test-ext-junit`, `android-gradle-plugin`, `detekt-gradle-plugin`, and the plugin alias `android-library`;
  - module `:featureflag-android`, namespace `com.andyyhope.featureflag.android`, with an empty manifest.
  - Every Android library module gets, for unit tests: kotlin-test-junit, junit, coroutines-test, Turbine, Robolectric, androidx-test core and ext-junit; and for instrumented tests: kotlin-test-junit, junit, coroutines-test, Turbine, androidx-test core, runner, rules and ext-junit, with `AndroidJUnitRunner`.

- [ ] **Step 1: Point Gradle at the Android SDK**

If `local.properties` does not exist at the repository root, create it with this one line (it is gitignored):

```
sdk.dir=/Users/andyyhope/Library/Android/sdk
```

- [ ] **Step 2: Write the failing smoke test**

`featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/AndroidSmokeTest.kt`
```kotlin
package com.andyyhope.featureflag.android

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AndroidSmokeTest {

    @Test
    fun robolectricRunsTheCompileSdkWithCoreOnTheClasspath() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(36, Build.VERSION.SDK_INT)
        assertTrue(context.noBackupFilesDir.isDirectory)
        assertEquals("a", FlagKey("a").rawValue)
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest
```

Expected: `BUILD FAILED`, with `Cannot locate tasks that match ':featureflag-android:testDebugUnitTest' as project 'featureflag-android' not found in root project 'feature-flags-android'.`

- [ ] **Step 4: Add the catalog entries and the convention plugins**

Modify (full replacement) `gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.2.10"
# KSP version must track Kotlin (2.2.10).
ksp = "2.2.10-2.0.2"
# Android Gradle Plugin. AGP 9 ships built-in Kotlin, so Android modules never apply kotlin-android.
agp = "9.1.1"
kotlinxCoroutines = "1.10.2"
junit = "4.13.2"
turbine = "1.2.1"
kotlinpoet = "2.2.0"
kctfork = "0.8.0"
# Robolectric 4.16 is the first to run SDK 36, and it needs a JDK 21 test JVM.
robolectric = "4.16"
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"
androidxTestExtJunit = "1.3.0"
# detekt static analysis + its ktlint-backed formatting ruleset.
detekt = "1.23.8"

[libraries]
# Build-logic classpath (convention plugins).
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
android-gradle-plugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
detekt-gradle-plugin = { module = "io.gitlab.arturbosch.detekt:detekt-gradle-plugin", version.ref = "detekt" }

kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }

ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }
ksp-symbol-processing = { module = "com.google.devtools.ksp:symbol-processing", version.ref = "ksp" }
ksp-aa-embeddable = { module = "com.google.devtools.ksp:symbol-processing-aa-embeddable", version.ref = "ksp" }
ksp-common-deps = { module = "com.google.devtools.ksp:symbol-processing-common-deps", version.ref = "ksp" }
kotlinpoet = { module = "com.squareup:kotlinpoet", version.ref = "kotlinpoet" }
kotlinpoet-ksp = { module = "com.squareup:kotlinpoet-ksp", version.ref = "kotlinpoet" }

kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
junit = { module = "junit:junit", version.ref = "junit" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
kctfork-core = { module = "dev.zacsweers.kctfork:core", version.ref = "kctfork" }
kctfork-ksp = { module = "dev.zacsweers.kctfork:ksp", version.ref = "kctfork" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidxTestRules" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }

detekt-formatting = { module = "io.gitlab.arturbosch.detekt:detekt-formatting", version.ref = "detekt" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
android-library = { id = "com.android.library", version.ref = "agp" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

Modify (full replacement) `build-logic/build.gradle.kts`:
```kotlin
plugins {
    `kotlin-dsl`
}

// Gradle plugin artifacts the convention plugins configure. AGP 9 ships built-in Kotlin, so
// applying com.android.library also wires Kotlin for the Android modules.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
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
        register("detekt") {
            id = "featureflag.detekt"
            implementationClass = "DetektConventionPlugin"
        }
        register("fixtures") {
            id = "featureflag.fixtures"
            implementationClass = "FixturesConventionPlugin"
        }
    }
}
```

`build-logic/src/main/kotlin/DetektConventionPlugin.kt`
```kotlin
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * `featureflag.detekt` — detekt plus its ktlint-backed formatting rules, with the shared
 * `config/detekt/detekt.yml`. Run the gate with `./gradlew detekt`.
 *
 * detekt reads `src/{main,test}/{java,kotlin}` by default. A module with more source sets
 * names them through [DetektExtension.source], as `featureflag.android-library` does.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            // `rootDir` is a plain File, so this stays configuration-cache safe.
            config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("detektPlugins", libs.findLibrary("detekt-formatting").get())

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
}
```

`build-logic/src/main/kotlin/FixturesConventionPlugin.kt`
```kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * `featureflag.fixtures` — hands the shared on-disk fixtures to every `Test` task, JVM and
 * Android unit tests alike (AGP's unit-test tasks are `Test` tasks too).
 *
 * The repo-root `fixtures/` directory goes to tests by path, as the `featureflag.fixtures`
 * system property, and is registered as a task input so editing a fixture re-runs the tests
 * instead of leaving them UP-TO-DATE. `fixtures-android/` holds the Kotlin-written files Swift
 * decodes; tests compare against them, and rewrite them only when Gradle runs with
 * `-Pfeatureflag.writeAndroidFixtures=true`. Both are file trees rather than `inputs.dir(...)`,
 * so a missing directory is simply empty.
 */
class FixturesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")
        val androidFixturesDir = rootProject.layout.projectDirectory.dir("fixtures-android")
        val writeAndroidFixtures = providers.gradleProperty("featureflag.writeAndroidFixtures").orElse("false").get()
        tasks.withType<Test>().configureEach {
            systemProperty("featureflag.fixtures", fixturesDir.asFile.absolutePath)
            systemProperty("featureflag.writeAndroidFixtures", writeAndroidFixtures)
            inputs.files(fixturesDir.asFileTree)
                .withPropertyName("featureflagFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(androidFixturesDir.asFileTree)
                .withPropertyName("featureflagAndroidFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}
```

Modify (full replacement) `build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt`. The fixture block moves to `FixturesConventionPlugin`, unchanged:
```kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** `featureflag.jvm-library` — baseline config for every Kotlin/JVM library module. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("featureflag.detekt")
        pluginManager.apply("featureflag.fixtures")

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
    }

    private companion object {
        const val JDK_TOOLCHAIN = 21
        const val JDK_RELEASE = 17
    }
}
```

`build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
```kotlin
import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `featureflag.android-library` — baseline config for every Android library module.
 *
 * AGP 9 wires Kotlin itself (built-in Kotlin), so this never applies `kotlin-android`; it
 * configures the Kotlin extension AGP registers. Unit tests run under Robolectric on a
 * JDK 21 test JVM, which Robolectric 4.16 needs for SDK 36.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("featureflag.detekt")
        pluginManager.apply("featureflag.fixtures")

        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
            }
        }

        // Build on JDK 21, emit bytecode for JDK 17, exactly as featureflag.jvm-library does.
        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(JDK_TOOLCHAIN)
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }

        // detekt's default source is src/{main,test}/{java,kotlin}. With built-in Kotlin it
        // cannot discover Android source sets, so name every one the gate must read.
        extensions.configure<DetektExtension> {
            source.setFrom(
                files(
                    "src/main/kotlin",
                    "src/test/kotlin",
                    "src/androidTest/kotlin",
                ),
            )
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf(
            "kotlin-test-junit",
            "junit",
            "kotlinx-coroutines-test",
            "turbine",
            "robolectric",
            "androidx-test-core",
            "androidx-test-ext-junit",
        ).forEach { alias ->
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }
        listOf(
            "kotlin-test-junit",
            "junit",
            "kotlinx-coroutines-test",
            "turbine",
            "androidx-test-core",
            "androidx-test-runner",
            "androidx-test-rules",
            "androidx-test-ext-junit",
        ).forEach { alias ->
            dependencies.add("androidTestImplementation", libs.findLibrary(alias).get())
        }
    }

    private companion object {
        const val COMPILE_SDK = 36
        const val MIN_SDK = 26
        const val JDK_TOOLCHAIN = 21
    }
}
```

- [ ] **Step 5: Slim the root build, and add the module**

Modify (full replacement) `build.gradle.kts`. The `subprojects {}` block goes; the root keeps detekt over `build-logic/` and every `*.gradle.kts`:
```kotlin
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
```

In `settings.gradle.kts`, replace:
```kotlin
include(":featureflag-test")
```
with:
```kotlin
include(":featureflag-test")
include(":featureflag-android")
```

In `gradle.properties`, replace:
```properties
kotlin.code.style=official
```
with:
```properties
kotlin.code.style=official
# The Android modules' test dependencies (AndroidX Test) are AndroidX artifacts.
android.useAndroidX=true
```

`featureflag-android/build.gradle.kts`
```kotlin
plugins {
    id("featureflag.android-library")
}

android {
    namespace = "com.andyyhope.featureflag.android"
}

dependencies {
    // The only runtime dependency: framework APIs only, no AndroidX at runtime.
    api(project(":featureflag-core"))
}
```

`featureflag-android/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- No components: a host opts into the exported bridge provider through featureflag-bridge. -->
<manifest />
```

- [ ] **Step 6: Run the smoke test, then check detekt reads the instrumented-test sources**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; one test, `robolectricRunsTheCompileSdkWithCoreOnTheClasspath`, passes. The first run downloads the SDK 36 Robolectric jar.

Then prove the gate reads `src/androidTest/kotlin`, which has no sources yet. Create this probe file:

`featureflag-android/src/androidTest/kotlin/com/andyyhope/featureflag/android/DetektProbe.kt`
```kotlin
package com.andyyhope.featureflag.android

import java.io.File

class DetektProbe
```

```bash
./gradlew :featureflag-android:detekt
```

Expected: `BUILD FAILED`, with `…/src/androidTest/kotlin/com/andyyhope/featureflag/android/DetektProbe.kt:3:1: Unused import [NoUnusedImports]`. Then delete the probe and its now-empty directories:

```bash
rm featureflag-android/src/androidTest/kotlin/com/andyyhope/featureflag/android/DetektProbe.kt
rmdir -p featureflag-android/src/androidTest/kotlin/com/andyyhope/featureflag/android
```

- [ ] **Step 7: Run the whole gate and the release build**

```bash
./gradlew build detekt :featureflag-android:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. This runs every M1 test unchanged, `:featureflag-android:lintDebug` ("No issues found"), detekt on the root, every module and the new module, and writes `featureflag-android/build/outputs/aar/featureflag-android-release.aar`. The only deprecation warning is the pre-existing one from the detekt plugin (`ReportingExtension.file(String)`).

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml gradle.properties settings.gradle.kts build.gradle.kts \
  build-logic/build.gradle.kts \
  build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt \
  build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt \
  build-logic/src/main/kotlin/DetektConventionPlugin.kt \
  build-logic/src/main/kotlin/FixturesConventionPlugin.kt \
  featureflag-android/build.gradle.kts featureflag-android/src/main/AndroidManifest.xml \
  featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/AndroidSmokeTest.kt
git commit -m "Move detekt and fixtures into convention plugins and add featureflag-android" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A2: `FlagValueJson` and `JsonReader.parseFragment`

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueJson.kt`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonReader.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueJsonTest.kt`
- Modify (test): `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderTest.kt`

**Interfaces:**
- Consumes (M1, internal): `FlagValueBox.toJson()`, `JsonValue.toBox(type)`, `JsonWriter.compact`, `JsonReader`, `JsonSyntaxException`; `FlagSerializationException.NonFiniteNumber`.
- Produces:
```kotlin
// package com.andyyhope.featureflag.values
public object FlagValueJson {
    public fun encode(box: FlagValueBox): String                       // throws FlagSerializationException.NonFiniteNumber(null)
    public fun decode(text: String, type: FlagValueType): FlagValueBox?
}
// package com.andyyhope.featureflag.json
internal object JsonReader {
    fun parse(bytes: ByteArray): JsonValue                             // unchanged
    fun parseFragment(text: String): JsonValue                         // any value may be the root
}
```

- [ ] **Step 1: Write the failing tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueJsonTest.kt`
```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlagValueJsonTest {

    // Encoding

    @Test
    fun encodingWritesCompactJsonValueText() {
        assertEquals("true", FlagValueJson.encode(FlagValueBox.Bool(true)))
        assertEquals("3", FlagValueJson.encode(FlagValueBox.Int(3)))
        assertEquals("0.10000000000000001", FlagValueJson.encode(FlagValueBox.Double(0.1)))
        assertEquals("1.1000000238418579", FlagValueJson.encode(FlagValueBox.Float(1.1f)))
        assertEquals("\"a \\\"b\\\" / c\"", FlagValueJson.encode(FlagValueBox.String("a \"b\" / c")))
        assertEquals("\"AQI=\"", FlagValueJson.encode(FlagValueBox.Data(byteArrayOf(1, 2))))
        assertEquals(
            "\"2026-01-01T00:00:00Z\"",
            FlagValueJson.encode(FlagValueBox.Date(Instant.parse("2026-01-01T00:00:00.75Z"))),
        )
        assertEquals("\"https://example.com/a?b=c\"", FlagValueJson.encode(FlagValueBox.Url(URI("https://example.com/a?b=c"))))
        assertEquals("[1,2]", FlagValueJson.encode(FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)))))
        assertEquals(
            "{\"a\":\"b\",\"b\":[]}",
            FlagValueJson.encode(
                FlagValueBox.Dictionary(mapOf("b" to FlagValueBox.Array(emptyList()), "a" to FlagValueBox.String("b"))),
            ),
        )
    }

    @Test
    fun aNonFiniteNumberCannotBeEncoded() {
        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            FlagValueJson.encode(FlagValueBox.Array(listOf(FlagValueBox.Double(Double.NaN))))
        }

        assertNull(error.key)
    }

    // Decoding

    @Test
    fun everyTypeRoundTrips() {
        val cases = listOf(
            FlagValueBox.Bool(true) to FlagValueType.Bool,
            FlagValueBox.Int(-7) to FlagValueType.Int,
            FlagValueBox.Double(0.1) to FlagValueType.Double,
            FlagValueBox.Float(1.1f) to FlagValueType.Float,
            FlagValueBox.String("line1\nline2 é 😀") to FlagValueType.String,
            FlagValueBox.Data(byteArrayOf(0x01, 0x02)) to FlagValueType.Data,
            FlagValueBox.Date(Instant.ofEpochSecond(1_000)) to FlagValueType.Date,
            FlagValueBox.Url(URI("https://example.com/a?b=c")) to FlagValueType.Url,
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Bool(true))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Bool)),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.String("x"))) to FlagValueType.Dictionary(FlagValueType.String),
            FlagValueBox.Array(emptyList()) to FlagValueType.Array(FlagValueType.String),
            FlagValueBox.Dictionary(emptyMap()) to FlagValueType.Dictionary(FlagValueType.Int),
        )

        for ((box, type) in cases) {
            assertEquals(box, FlagValueJson.decode(FlagValueJson.encode(box), type), "round trip of $box as $type")
        }
    }

    @Test
    fun aScalarMayBeTheWholeText() {
        assertEquals(FlagValueBox.Int(3), FlagValueJson.decode("3", FlagValueType.Int))
        assertEquals(FlagValueBox.Bool(false), FlagValueJson.decode(" \nfalse\t", FlagValueType.Bool))
        assertEquals(FlagValueBox.String("x"), FlagValueJson.decode("\"x\"", FlagValueType.String))
    }

    @Test
    fun anIntegerWidensToADoubleButNothingElseConverts() {
        assertEquals(FlagValueBox.Double(3.0), FlagValueJson.decode("3", FlagValueType.Double))
        assertNull(FlagValueJson.decode("1.5", FlagValueType.Int))
        assertNull(FlagValueJson.decode("1", FlagValueType.Bool))
        assertNull(FlagValueJson.decode("true", FlagValueType.Int))
        assertNull(FlagValueJson.decode("\"3\"", FlagValueType.Int))
    }

    @Test
    fun textOfAnotherTypeDecodesToNothing() {
        assertNull(FlagValueJson.decode("\"not a date\"", FlagValueType.Date))
        assertNull(FlagValueJson.decode("\"not base64!\"", FlagValueType.Data))
        assertNull(FlagValueJson.decode("\"\"", FlagValueType.Url))
        assertNull(FlagValueJson.decode("{\"a\":1}", FlagValueType.Array(FlagValueType.Int)))
    }

    @Test
    fun aPartlyWrongCollectionIsRejectedWhole() {
        assertNull(FlagValueJson.decode("[1,\"two\"]", FlagValueType.Array(FlagValueType.Int)))
        assertNull(FlagValueJson.decode("{\"a\":1,\"b\":null}", FlagValueType.Dictionary(FlagValueType.Int)))
    }

    @Test
    fun textThatIsNotJsonDecodesToNothing() {
        for (text in listOf("", " ", "tru", "[1,", "1 2", "'x'", "x", "\"unterminated", "1e400")) {
            assertNull(FlagValueJson.decode(text, FlagValueType.Int), "decoding <$text>")
        }
    }
}
```

In `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderTest.kt`, replace:
```kotlin
    @Test
    fun escapesDecodeToTheirCharacters() {
        val text = (parse("""["\/\b\f\n\r\t\"\\Aé😀"]""") as JsonValue.Array).items.single()
        assertEquals(JsonValue.Text("/\b\u000C\n\r\t\"\\Aé😀"), text)
    }
}
```
with:
```kotlin
    @Test
    fun escapesDecodeToTheirCharacters() {
        val text = (parse("""["\/\b\f\n\r\t\"\\Aé😀"]""") as JsonValue.Array).items.single()
        assertEquals(JsonValue.Text("/\b\u000C\n\r\t\"\\Aé😀"), text)
    }

    // Fragments

    @Test
    fun aFragmentMayBeAnyValue() {
        assertEquals(JsonValue.Integer(3), JsonReader.parseFragment("3"))
        assertEquals(JsonValue.Real(0.5), JsonReader.parseFragment(" 0.5\n"))
        assertEquals(JsonValue.Text("x"), JsonReader.parseFragment("\"x\""))
        assertEquals(JsonValue.Bool(true), JsonReader.parseFragment("true"))
        assertEquals(JsonValue.Null, JsonReader.parseFragment("null"))
        assertEquals(JsonValue.Array(listOf(JsonValue.Integer(1))), JsonReader.parseFragment("[1,]"))
    }

    @Test
    fun aFragmentFollowsTheSameGrammar() {
        for (text in listOf("", "  ", "tru", "1 2", "\"a", "01", "1e400", "[1", "{\"a\":1}x")) {
            assertFailsWith<JsonSyntaxException>("parsing <$text>") { JsonReader.parseFragment(text) }
        }
    }

    @Test
    fun aDocumentStillRejectsAScalarAtTheTop() {
        // JSONSerialization without .fragmentsAllowed, which import and the schema rely on.
        assertFailsWith<JsonSyntaxException> { parse("3") }
        assertFailsWith<JsonSyntaxException> { parse("\"x\"") }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.values.FlagValueJsonTest' --tests 'com.andyyhope.featureflag.json.JsonReaderTest'
```

Expected: `BUILD FAILED` in `:featureflag-core:compileTestKotlin`, with `Unresolved reference 'parseFragment'.` (JsonReaderTest) and `Unresolved reference 'FlagValueJson'.` (FlagValueJsonTest).

- [ ] **Step 3: Add `parseFragment`**

In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonReader.kt`, replace:
```kotlin
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
```
with:
```kotlin
        return Parser(text.removePrefix(BYTE_ORDER_MARK.toString())).document()
    }

    /**
     * Reads one JSON value of any kind, as `JSONSerialization` does with `.fragmentsAllowed`.
     * The grammar is the same as [parse]'s; only the root may also be a scalar. [text] is
     * already a string, so there is no byte order mark to skip.
     */
    fun parseFragment(text: String): JsonValue = Parser(text).fragment()

    private class Parser(private val text: String) {
        private var index = 0
        private var depth = 0

        fun document(): JsonValue {
            skipWhitespace()
            if (peek() != '[' && peek() != '{') fail("The top level must be an array or an object")
            return fragment()
        }

        fun fragment(): JsonValue {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            if (index != text.length) fail("Unexpected text after the document")
            return value
        }
```

- [ ] **Step 4: Add `FlagValueJson`**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueJson.kt`
```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonWriter

/**
 * One box as JSON value text, using the same rules as export/import, so a store or a wire can
 * carry any flag type.
 *
 * Dates are written to the second, data as padded base64, URLs as text, and a float is widened
 * to a double, exactly as an exported document writes them. Reading is as strict as import:
 * the text must be one JSON value of the declared type.
 */
public object FlagValueJson {

    /**
     * Compact JSON value text: `true`, `3`, `0.10000000000000001`, `"2026-01-01T00:00:00Z"`,
     * `[1,2]`, `{"a":"b"}`. Object keys are sorted as an export sorts them.
     *
     * @throws FlagSerializationException.NonFiniteNumber for an infinity or a NaN, which JSON
     *   cannot represent. Its key is null: a bare box does not know which flag it belongs to.
     */
    public fun encode(box: FlagValueBox): String = JsonWriter.compact(box.toJson())

    /**
     * The box [text] holds as [type], or null when [text] is not valid JSON or not that type.
     * Strict, as import is: there is no conversion except an integer read as a double or a
     * float, and a collection with one wrong element is rejected whole. Unlike a document, a
     * scalar may be the whole text.
     */
    public fun decode(text: String, type: FlagValueType): FlagValueBox? {
        val json = try {
            JsonReader.parseFragment(text)
        } catch (ignored: JsonSyntaxException) {
            return null
        }
        return json.toBox(type)
    }
}
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.values.FlagValueJsonTest' --tests 'com.andyyhope.featureflag.json.JsonReaderTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both. `FlagValueJsonTest` runs 8 tests and `JsonReaderTest` 11 (3 new), all passing. `JsonReaderGoldenTest` is unchanged and still passes, which shows `parse` accepts and rejects exactly what it did.

- [ ] **Step 6: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueJson.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonReader.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueJsonTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/JsonReaderTest.kt
git commit -m "Add FlagValueJson for one box as JSON value text" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A3: `FlagSchema.write` hardening and the QR plausibility check

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagQRCode.kt`
- Modify (test, full replacement): `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaFileTest.kt`
- Modify (test): `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagQRCodeTest.kt`

**Interfaces:**
- Consumes (M1): `FlagSchema.write(directory)`, `FILE_NAME`, `jsonBytes()`, `read`; core test helpers `demoSpec`, `specOf`; `FlagQRCode.compress`/`decompress` (internal); `FlagQRCodeException.Corrupt`.
- Produces (signatures unchanged, behaviour hardened):
  - `FlagSchema.write(directory: File): File`: losing a `mkdirs()` race is not an error; `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` with a plain `REPLACE_EXISTING` fallback; a failure throws `IOException` naming the target and carrying the cause; the temporary file never outlives the call.
  - `FlagQRCode.decompress` rejects `expected > MAX_DECLARED_LENGTH` or `expected > body.size * MAX_DEFLATE_RATIO + MAX_DEFLATE_SLACK` (1032, 64) before allocating, so `decode` throws `FlagQRCodeException.Corrupt`.

- [ ] **Step 1: Write the failing tests**

Modify (full replacement) `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaFileTest.kt`. The first four tests and the last are M1's, unchanged:
```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlagSchemaFileTest {
    private val directory: File = Files.createTempDirectory("flag-schema").toFile()

    @AfterTest fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test fun writingPublishesASchemaTheCompanionCanRead() {
        val schema = FlagSchema.of(demoSpec)
        val file = schema.write(directory)
        assertEquals(File(directory, "flag-schema.json"), file)
        assertContentEquals(schema.jsonBytes(), file.readBytes())
        assertEquals(schema.flags.map { it.key }, FlagSchema.read(directory).flags.map { it.key })
    }

    @Test fun writingCreatesAMissingDirectory() {
        val nested = File(directory, "a/b")
        FlagSchema.of(demoSpec).write(nested)
        assertTrue(File(nested, FlagSchema.FILE_NAME).isFile)
    }

    @Test fun writingAgainReplacesTheEarlierSchemaAndLeavesNoTemporaryFile() {
        FlagSchema.of(specOf()).write(directory)
        FlagSchema.of(demoSpec).write(directory)
        assertEquals(5, FlagSchema.read(directory).flags.size)
        assertEquals(listOf(FlagSchema.FILE_NAME), directory.list()!!.toList())
    }

    @Test fun aDirectoryAnotherWriterCreatedFirstIsNotAnError() {
        // mkdirs() returns false when someone else made the directory between the check and the call.
        val raced = object : File(directory, "raced") {
            override fun mkdirs(): Boolean {
                super.mkdirs()
                return false
            }
        }

        val schema = FlagSchema.of(demoSpec)

        val file = schema.write(raced)

        assertContentEquals(schema.jsonBytes(), file.readBytes())
        assertEquals(listOf(FlagSchema.FILE_NAME), raced.list()!!.toList())
    }

    @Test fun aDirectoryThatCannotBeMadeNamesTheTarget() {
        val blocker = File(directory, "not-a-directory").apply { writeText("x") }
        val target = File(File(blocker, "schema"), FlagSchema.FILE_NAME)

        val error = assertFailsWith<IOException> { FlagSchema.of(demoSpec).write(File(blocker, "schema")) }

        assertTrue(error.message.orEmpty().contains(target.path), "message should name the target: ${error.message}")
    }

    @Test fun aFailedReplacementNamesTheTargetKeepsTheCauseAndLeavesNoTemporaryFile() {
        // A directory squatting on the file name cannot be replaced by a file.
        val target = File(directory, FlagSchema.FILE_NAME)
        File(target, "occupied").apply { parentFile.mkdirs() }.writeText("x")

        val error = assertFailsWith<IOException> { FlagSchema.of(demoSpec).write(directory) }

        assertTrue(error.message.orEmpty().contains(target.path), "message should name the target: ${error.message}")
        assertNotNull(error.cause, "the underlying failure should be carried")
        assertEquals(listOf(FlagSchema.FILE_NAME), directory.list()!!.toList())
    }

    @Test fun concurrentWritersAllSucceedAndLeaveOneCompleteFile() {
        val schema = FlagSchema.of(demoSpec)
        val expected = schema.jsonBytes()
        val target = File(directory, "fresh/nested")
        val writers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writers)
        try {
            val results = (1..writers).map {
                pool.submit<Unit> {
                    start.await()
                    repeat(25) { schema.write(target) }
                }
            }
            start.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertContentEquals(expected, File(target, FlagSchema.FILE_NAME).readBytes())
        assertEquals(listOf(FlagSchema.FILE_NAME), target.list()!!.toList())
    }

    @Test fun readingReportsAMissingSchemaClearly() {
        val error = assertFailsWith<FlagSchemaException.NotPublished> { FlagSchema.read(directory) }
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            error.message,
        )
    }
}
```

In `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagQRCodeTest.kt`, replace:
```kotlin
import org.junit.Test
import kotlin.test.assertContentEquals
```
with:
```kotlin
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertContentEquals
```

In the same file, replace:
```kotlin
    private fun code(bytes: ByteArray): String = FlagQRCode.PREFIX + FlagQRCode.base64UrlEncode(bytes)
```
with:
```kotlin
    private fun code(bytes: ByteArray): String = FlagQRCode.PREFIX + FlagQRCode.base64UrlEncode(bytes)

    private fun lengthPrefix(length: Int): ByteArray =
        byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())

    /** Bytes the current thread allocated while [block] ran, as HotSpot counts them. */
    private fun bytesAllocatedBy(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }
```

In the same file, replace:
```kotlin
    @Test
    fun scannedCodeStillValidatesAgainstTheSchema() {
```
with:
```kotlin
    @Test
    fun aDeflateBombIsRefusedBeforeItsOutputIsAllocated() {
        // A garbled scan claiming 40 MB from a 1 KB body: no deflate stream expands that far,
        // so the claim is refused before a 40 MB buffer is ever allocated.
        val body = FlagQRCode.compress(ByteArray(1_000_000)).let { it.copyOfRange(4, it.size) }
        val bomb = lengthPrefix(40_000_000) + body

        val allocated = bytesAllocatedBy { assertNull(FlagQRCode.decompress(bomb)) }

        assertTrue(allocated < 4_000_000, "allocated $allocated bytes for an implausible claim")
    }

    @Test
    fun aClaimDeflateCouldReachIsStillInflated() {
        // A million zero bytes deflate to under 1 KB, close to deflate's 1032:1 ceiling.
        val compressed = FlagQRCode.compress(ByteArray(1_000_000))

        assertTrue(compressed.size < 1_100, "zeros should deflate near the ceiling, got ${compressed.size}")
        assertContentEquals(ByteArray(1_000_000), FlagQRCode.decompress(compressed))
    }

    @Test
    fun aCodeClaimingExactlyTheCapStillDecodes() {
        // 50,000,000 zero bytes deflate to about 48.6 KB, within the plausibility bound.
        val compressed = FlagQRCode.compress(ByteArray(50_000_000))
        val body = compressed.copyOfRange(4, compressed.size)

        assertEquals(50_000_000, FlagQRCode.decompress(compressed)?.size)
        // One byte past the cap is refused, however plausible the body.
        assertNull(FlagQRCode.decompress(lengthPrefix(50_000_001) + body))
    }

    @Test
    fun scannedCodeStillValidatesAgainstTheSchema() {
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.schema.FlagSchemaFileTest' --tests 'com.andyyhope.featureflag.transport.FlagQRCodeTest'
```

Expected: `40 tests completed, 4 failed`:
- `aDirectoryAnotherWriterCreatedFirstIsNotAnError`: `java.io.IOException: Could not create …/raced`.
- `aDirectoryThatCannotBeMadeNamesTheTarget`: `AssertionError: message should name the target: Could not create …/not-a-directory/schema`.
- `aFailedReplacementNamesTheTargetKeepsTheCauseAndLeavesNoTemporaryFile`: `AssertionError: the underlying failure should be carried`.
- `aDeflateBombIsRefusedBeforeItsOutputIsAllocated`: `AssertionError: allocated 40001240 bytes for an implausible claim` (the exact count may differ by a few bytes).

`concurrentWritersAllSucceedAndLeaveOneCompleteFile`, `aClaimDeflateCouldReachIsStillInflated` and `aCodeClaimingExactlyTheCapStillDecodes` already pass: the first because losing the race needs unlucky timing, the other two because they guard the new check against being too strict.

- [ ] **Step 3: Harden `write`**

In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt`, replace:
```kotlin
import java.io.File
import java.io.IOException
import java.time.Instant
```
with:
```kotlin
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
```

In the same file, replace:
```kotlin
    /** Writes [FILE_NAME] into [directory], creating it if needed, replacing any earlier one atomically. */
    public fun write(directory: File): File {
        val bytes = jsonBytes()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val target = File(directory, FILE_NAME)
        val temporary = File.createTempFile(FILE_NAME, ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) throw IOException("Could not replace $target")
        } finally {
            temporary.delete()
        }
        return target
    }
```
with:
```kotlin
    /**
     * Writes [FILE_NAME] into [directory], creating it if needed, replacing any earlier one
     * atomically, so a reader never sees half a file. Safe to call from several threads or
     * processes at once: each writes its own temporary file and the last move wins.
     *
     * @throws IOException naming the target file, carrying the underlying failure.
     */
    public fun write(directory: File): File {
        val bytes = jsonBytes()
        val target = File(directory, FILE_NAME)
        // mkdirs() also returns false when another writer created the directory first.
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not write $target, because $directory is not a directory and could not be created.")
        }
        var temporary: File? = null
        try {
            temporary = File.createTempFile(FILE_NAME, ".tmp", directory)
            temporary.writeBytes(bytes)
            replace(temporary, target)
        } catch (e: IOException) {
            throw IOException("Could not write $target.", e)
        } finally {
            temporary?.delete()
        }
        return target
    }

    /** Moves [source] over [target] atomically where the file system can, and plainly where it cannot. */
    private fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (ignored: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
```

- [ ] **Step 4: Add the plausibility check**

In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagQRCode.kt`, replace:
```kotlin
    private const val MAX_DECLARED_LENGTH = 50_000_000L
```
with:
```kotlin
    private const val MAX_DECLARED_LENGTH = 50_000_000L

    // DEFLATE's ceiling: a 258-byte match coded in two bits expands at most 1032 to 1. The
    // slack covers the few bytes of block header a tiny stream spends before any match.
    private const val MAX_DEFLATE_RATIO = 1032L
    private const val MAX_DEFLATE_SLACK = 64L
```

In the same file, replace:
```kotlin
        if (expected == 0L) return if (body.isEmpty()) ByteArray(0) else null
        // A corrupt length must not turn into an implausible allocation.
        if (expected > MAX_DECLARED_LENGTH) return null
        val output
```
with:
```kotlin
        if (expected == 0L) return if (body.isEmpty()) ByteArray(0) else null
        // A corrupt length must not turn into an implausible allocation: refuse a claim above
        // the cap, or beyond what deflate could expand this body to. A stored body claims its
        // own length, so it always passes.
        if (expected > MAX_DECLARED_LENGTH) return null
        if (expected > body.size.toLong() * MAX_DEFLATE_RATIO + MAX_DEFLATE_SLACK) return null
        val output
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.schema.FlagSchemaFileTest' --tests 'com.andyyhope.featureflag.transport.FlagQRCodeTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both. `FlagSchemaFileTest` runs 8 tests and `FlagQRCodeTest` 32, all passing. Every QR golden (`QRCodeFixturesTest`) and every publish test still passes. Measured here: 50,000,000 zero bytes deflate to 48,604 bytes at level 5, which the bound admits with about 0.3% to spare.

- [ ] **Step 6: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagQRCode.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaFileTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagQRCodeTest.kt
git commit -m "Harden FlagSchema.write and refuse implausible QR code lengths" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A4: `SharedPreferencesSource`

**Files:**
- Create: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSource.kt`
- Create: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt` (only `PREFERENCES_NAME`; A5 completes it)
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSourceTest.kt`

**Interfaces:**
- Consumes: A2 `FlagValueJson`; M1 `MutableFlagValueSource`, `FlagChange`, `FlagChangeEmitter`, `FlagException.UnsupportedValue`, `FlagSerializationException.NonFiniteNumber`, `FlagPole`, `FlagRef`, `FlagDescriptor.of`, `FlagSchemaNode.Flag`, `FlagContainerSpec`, `FlagCodecs.boolean`.
- Produces:
```kotlin
// package com.andyyhope.featureflag.android.sources
public class SharedPreferencesSource(
    private val preferences: SharedPreferences,
    override val sourceName: String = "SharedPreferences",
) : MutableFlagValueSource, AutoCloseable {
    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox?
    override val changes: Flow<FlagChange>
    override fun setBox(key: FlagKey, box: FlagValueBox?)
    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>)   // one editor, one commit(); throws FlagException.UnsupportedValue
    public fun refresh()
    override fun close()
    public companion object {
        public fun companion(context: Context, name: String = "Companion"): SharedPreferencesSource
    }
}
// package com.andyyhope.featureflag.android.bridge (A5 adds the rest)
public object FlagBridgeContract {
    public const val PREFERENCES_NAME: String = "com.andyyhope.featureflag.companion"
}
```

- [ ] **Step 1: Write the failing tests**

`featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSourceTest.kt`
```kotlin
package com.andyyhope.featureflag.android.sources

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SharedPreferencesSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences: SharedPreferences = context.getSharedPreferences("flags-under-test", Context.MODE_PRIVATE)

    private fun makeSource(): SharedPreferencesSource = SharedPreferencesSource(preferences, "local")

    /** Runs the listener callbacks the framework posted to the main thread. */
    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Natural storage (UserDefaultsSourceTests)

    @Test
    fun storesPrimitivesAsThemselves() {
        // Anything that opens the file, such as the host's own code, reads plain values.
        val source = makeSource()
        source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
        source.setBox(FlagKey("flag.int"), FlagValueBox.Int(7))
        source.setBox(FlagKey("flag.float"), FlagValueBox.Float(2.5f))
        source.setBox(FlagKey("flag.string"), FlagValueBox.String("hi"))
        source.setBox(FlagKey("flag.double"), FlagValueBox.Double(1.5))

        assertEquals(true, preferences.all["flag.bool"])
        assertEquals(7L, preferences.all["flag.int"])
        assertEquals(2.5f, preferences.all["flag.float"])
        assertEquals("hi", preferences.all["flag.string"])
        // A double has no SharedPreferences type, so it is stored as JSON text.
        assertEquals("1.5", preferences.all["flag.double"])
    }

    @Test
    fun storesUrlAsJsonText() {
        makeSource().setBox(FlagKey("flag.url"), FlagValueBox.Url(URI("https://example.com")))

        assertEquals("\"https://example.com\"", preferences.all["flag.url"])
    }

    // Round trips

    @Test
    fun everySupportedTypeRoundTrips() {
        val source = makeSource()
        val cases = listOf(
            FlagValueBox.Bool(true) to FlagValueType.Bool,
            FlagValueBox.Int(-7) to FlagValueType.Int,
            FlagValueBox.Double(3.5) to FlagValueType.Double,
            FlagValueBox.Float(2.5f) to FlagValueType.Float,
            FlagValueBox.String("hello") to FlagValueType.String,
            FlagValueBox.Data(byteArrayOf(0x01, 0x02)) to FlagValueType.Data,
            FlagValueBox.Date(Instant.ofEpochSecond(1_000)) to FlagValueType.Date,
            FlagValueBox.Url(URI("https://example.com/a?b=c")) to FlagValueType.Url,
            FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))) to FlagValueType.Array(FlagValueType.Int),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.String("x"))) to FlagValueType.Dictionary(FlagValueType.String),
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Bool(true))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Bool)),
        )

        for ((index, case) in cases.withIndex()) {
            val (box, type) = case
            val key = FlagKey("round.trip.$index")
            source.setBox(key, box)
            assertEquals(box, source.box(key, type), "failed for $type")
        }
    }

    @Test
    fun readingWithTheWrongTypeYieldsNothing() {
        val source = makeSource()
        source.setBox(FlagKey("flag.value"), FlagValueBox.String("not a number"))

        assertNull(source.box(FlagKey("flag.value"), FlagValueType.Int))
        assertNull(source.box(FlagKey("flag.value"), FlagValueType.Date))
    }

    @Test
    fun numericTypesDoNotSilentlyConvert() {
        // Truncating 3.5 into an Int flag, or reading 1 as true, would be a silent wrong answer
        // rather than a fall-through to the next source.
        val source = makeSource()

        source.setBox(FlagKey("flag.double"), FlagValueBox.Double(3.5))
        assertNull(source.box(FlagKey("flag.double"), FlagValueType.Int))
        assertNull(source.box(FlagKey("flag.double"), FlagValueType.Float))

        source.setBox(FlagKey("flag.int"), FlagValueBox.Int(1))
        assertNull(source.box(FlagKey("flag.int"), FlagValueType.Bool))
        assertNull(source.box(FlagKey("flag.int"), FlagValueType.Float))
        assertNull(source.box(FlagKey("flag.int"), FlagValueType.Double))

        source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
        assertNull(source.box(FlagKey("flag.bool"), FlagValueType.Int))
    }

    @Test
    fun aMissingKeyYieldsNothing() {
        assertNull(makeSource().box(FlagKey("absent"), FlagValueType.Bool))
    }

    @Test
    fun settingNullRemovesTheValue() {
        val source = makeSource()
        source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
        source.setBox(FlagKey("flag.bool"), null)

        assertNull(source.box(FlagKey("flag.bool"), FlagValueType.Bool))
        assertFalse(preferences.contains("flag.bool"))
    }

    @Test
    fun adoptsValuesWrittenByOtherCode() {
        // A team migrating from hand-rolled SharedPreferences flags finds them working.
        preferences.edit().putBoolean("already.here", true).commit()

        assertEquals(FlagValueBox.Bool(true), makeSource().box(FlagKey("already.here"), FlagValueType.Bool))
    }

    @Test
    fun aValueStoredWithAnotherTypeReadsAsNothing() {
        // Review Focus 2: the file belongs to the host, whose own code may use the same key.
        preferences.edit()
            .putInt("count", 3)
            .putString("limit", "lots")
            .putStringSet("tags", setOf("a"))
            .putString("launch", "not json")
            .commit()
        val source = makeSource()

        assertNull(source.box(FlagKey("count"), FlagValueType.Int), "an Integer is not the Long an int flag stores")
        assertNull(source.box(FlagKey("limit"), FlagValueType.Int))
        assertNull(source.box(FlagKey("tags"), FlagValueType.String))
        assertNull(source.box(FlagKey("launch"), FlagValueType.Date))

        // Writing the flag replaces the foreign value with one of the right type.
        source.setBox(FlagKey("count"), FlagValueBox.Int(3))
        assertEquals(3L, preferences.all["count"])
        assertEquals(FlagValueBox.Int(3), source.box(FlagKey("count"), FlagValueType.Int))
    }

    @Test
    fun readingNeverCopiesTheWholeStore() {
        val source = SharedPreferencesSource(NoGetAllPreferences(preferences))
        source.setBox(FlagKey("flag.date"), FlagValueBox.Date(Instant.ofEpochSecond(1_000)))

        assertEquals(FlagValueBox.Date(Instant.ofEpochSecond(1_000)), source.box(FlagKey("flag.date"), FlagValueType.Date))
    }

    // Naming (SourceEdgeCaseTests)

    @Test
    fun theNameIsTheSourceName() {
        assertEquals("local", makeSource().sourceName)
        assertEquals("SharedPreferences", SharedPreferencesSource(preferences).sourceName)
        assertEquals("Companion", SharedPreferencesSource.companion(context).sourceName)
        assertEquals("Overrides", SharedPreferencesSource.companion(context, "Overrides").sourceName)
    }

    @Test
    fun theCompanionSourceUsesTheWellKnownFile() {
        SharedPreferencesSource.companion(context).setBox(FlagKey("a"), FlagValueBox.Bool(true))

        val file = context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertEquals(true, file.all["a"])
    }

    @Test
    fun emptyCollectionsRoundTrip() {
        val source = makeSource()
        source.setBox(FlagKey("empty.array"), FlagValueBox.Array(emptyList()))
        source.setBox(FlagKey("empty.dictionary"), FlagValueBox.Dictionary(emptyMap()))

        assertEquals(FlagValueBox.Array(emptyList()), source.box(FlagKey("empty.array"), FlagValueType.Array(FlagValueType.String)))
        assertEquals(
            FlagValueBox.Dictionary(emptyMap()),
            source.box(FlagKey("empty.dictionary"), FlagValueType.Dictionary(FlagValueType.Int)),
        )
    }

    @Test
    fun aPartlyWrongCollectionIsRejectedWhole() {
        preferences.edit().putString("mixed", "[1,\"two\"]").commit()

        assertNull(makeSource().box(FlagKey("mixed"), FlagValueType.Array(FlagValueType.Int)))
    }

    @Test
    fun anInvalidStoredUrlIsRejected() {
        preferences.edit().putLong("not.a.url", 42).putString("empty.url", "\"\"").commit()
        val source = makeSource()

        assertNull(source.box(FlagKey("not.a.url"), FlagValueType.Url))
        assertNull(source.box(FlagKey("empty.url"), FlagValueType.Url))
    }

    @Test
    fun aNonFiniteDoubleIsRefusedAndNothingIsWritten() {
        val source = makeSource()

        val error = assertFailsWith<FlagException.UnsupportedValue> {
            source.setBoxes(mapOf(FlagKey("fine") to FlagValueBox.Bool(true), FlagKey("nan") to FlagValueBox.Double(Double.NaN)))
        }

        assertEquals(FlagKey("nan"), error.key)
        assertTrue(preferences.all.isEmpty())
    }

    // Change notification

    @Test
    fun writingEmitsAChangeForThatKey() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("flag.bool"))), awaitItem())
        }
    }

    @Test
    fun refreshEmitsAWholesaleChange() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.refresh()
            assertEquals(FlagChange.All, awaitItem())
        }
    }

    // Write amplification (AdversarialRegressionTests)

    @Test
    fun anIdenticalWriteAnnouncesNothing() = runBlocking<Unit> {
        val source = makeSource()
        source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
        idleMainLooper()

        source.changes.test {
            source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
            idleMainLooper()
            expectNoEvents()

            source.setBox(FlagKey("a"), FlagValueBox.Bool(false))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("a"))), awaitItem())
        }
    }

    @Test
    fun anIdenticalWriteDoesNotTouchTheFile() {
        val counting = CountingPreferences(preferences)
        val source = SharedPreferencesSource(counting)
        source.setBox(FlagKey("a"), FlagValueBox.Date(Instant.ofEpochSecond(1_000)))

        source.setBox(FlagKey("a"), FlagValueBox.Date(Instant.ofEpochSecond(1_000)))
        source.setBox(FlagKey("absent"), null)

        assertEquals(1, counting.edits)
    }

    @Test
    fun removingSomethingAbsentAnnouncesNothing() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.setBox(FlagKey("never.set"), null)
            idleMainLooper()
            expectNoEvents()
        }
    }

    @Test
    fun aFlagChangingTypeIsStillRewritten() = runBlocking<Unit> {
        // 1 and true are different values, whatever an untyped comparison would say.
        val source = makeSource()
        source.setBox(FlagKey("a"), FlagValueBox.Int(1))
        idleMainLooper()

        source.changes.test {
            source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("a"))), awaitItem())
        }
        assertEquals(true, preferences.all["a"])
    }

    // Two sources over one file (DarwinNotificationTests)

    @Test
    fun oneSourceSeesAnotherSourceWriteToTheSameFile() = runBlocking<Unit> {
        // Stands in for the provider writing on the companion's behalf while the host's pole reads.
        val host = SharedPreferencesSource.companion(context, "host")
        val companion = SharedPreferencesSource.companion(context, "companion")

        host.changes.test {
            companion.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(true))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("new-onboarding"))), awaitItem())
        }
        assertEquals(FlagValueBox.Bool(true), host.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    // Android only

    @Test
    fun setBoxesCommitsOnceAndLeavesEveryKeyWritten() = runBlocking<Unit> {
        preferences.edit().putBoolean("removed", true).commit()
        val counting = CountingPreferences(preferences)
        val source = SharedPreferencesSource(counting)

        source.changes.test {
            source.setBoxes(
                mapOf(
                    FlagKey("a") to FlagValueBox.Bool(true),
                    FlagKey("b") to FlagValueBox.Int(2),
                    FlagKey("c") to FlagValueBox.Array(listOf(FlagValueBox.String("x"))),
                    FlagKey("removed") to null,
                ),
            )
            idleMainLooper()

            val announced = mutableSetOf<FlagKey>()
            while (announced.size < 4) announced += (awaitItem() as FlagChange.Keys).keys
            assertEquals(setOf(FlagKey("a"), FlagKey("b"), FlagKey("c"), FlagKey("removed")), announced)
        }
        assertEquals(1, counting.edits)
        assertEquals(1, counting.commits)
        assertEquals(0, counting.applies)
        assertEquals(mapOf("a" to true, "b" to 2L, "c" to "[\"x\"]"), preferences.all)
    }

    @Test
    fun clearingTheFileEmitsAWholesaleChange() = runBlocking<Unit> {
        val source = makeSource()
        source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
        idleMainLooper()

        source.changes.test {
            preferences.edit().clear().commit()
            idleMainLooper()
            assertEquals(FlagChange.All, awaitItem())
        }
        assertNull(source.box(FlagKey("a"), FlagValueType.Bool))
    }

    @Test
    fun afterCloseNothingIsEmitted() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.close()
            source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
            preferences.edit().putBoolean("b", true).commit()
            idleMainLooper()
            expectNoEvents()
        }
        // Closing stops announcements only; the source still reads and writes.
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("a"), FlagValueType.Bool))
    }

    @Test
    fun aPoleOverTheCompanionFileResolvesAnOverrideWrittenByASecondInstance() = runBlocking<Unit> {
        val pole = FlagPole(OnboardingSpec, listOf(SharedPreferencesSource.companion(context)))
        val writer = SharedPreferencesSource.companion(context)

        pole.flow(OnboardingSpec.newOnboarding).test {
            assertFalse(awaitItem())

            writer.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(true))
            idleMainLooper()

            assertTrue(awaitItem())
        }
        assertTrue(pole[OnboardingSpec.newOnboarding])
        assertEquals("Companion", pole.resolution(OnboardingSpec.newOnboarding).sourceName)
    }

    /** A one-flag container, written by hand: the Android modules do not run KSP. */
    private object OnboardingSpec : FlagContainerSpec<Any> {
        val newOnboarding: FlagRef<Boolean> =
            FlagRef(FlagKeyPath(listOf("newOnboarding")), "newOnboarding", "New onboarding", null, FlagCodecs.boolean) { false }

        override val flagContainerDescription: String? = null
        override val flagDescriptors: List<FlagSchemaNode> = listOf(
            FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "New onboarding", null, FlagCodecs.boolean, false)),
        )
        override val flagDefaults: Any = Any()

        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Any()
    }

    /** Counts editors and how each one is finished. */
    private class CountingPreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var edits = 0
        var commits = 0
        var applies = 0

        override fun edit(): SharedPreferences.Editor {
            edits++
            return CountingEditor(real.edit())
        }

        private inner class CountingEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                commits++
                return editor.commit()
            }

            override fun apply() {
                applies++
                editor.apply()
            }
        }
    }

    /** Fails the test if anything copies the whole file. */
    private class NoGetAllPreferences(real: SharedPreferences) : SharedPreferences by real {
        override fun getAll(): MutableMap<String, *> = throw AssertionError("box() must not copy the whole store")
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest --tests 'com.andyyhope.featureflag.android.sources.SharedPreferencesSourceTest'
```

Expected: `BUILD FAILED` in `:featureflag-android:compileDebugUnitTestKotlin`, starting with `SharedPreferencesSourceTest.kt:9:42 Unresolved reference 'FlagBridgeContract'.` and followed by `Unresolved reference 'SharedPreferencesSource'.`

- [ ] **Step 3: Add the preferences file name**

`featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt`. A5 replaces this file with the complete contract:
```kotlin
package com.andyyhope.featureflag.android.bridge

/** The names a host's bridge provider and a companion's client agree on. */
public object FlagBridgeContract {
    /**
     * The host's preferences file that holds companion overrides. The host's pole reads it
     * through `SharedPreferencesSource.companion(context)`, and the bridge provider writes it.
     */
    public const val PREFERENCES_NAME: String = "com.andyyhope.featureflag.companion"
}
```

- [ ] **Step 4: Add the source**

`featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSource.kt`
```kotlin
package com.andyyhope.featureflag.android.sources

import android.content.Context
import android.content.SharedPreferences
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueJson
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow

/**
 * Flag values in a [SharedPreferences] file, keyed by [FlagKey.rawValue] with no prefix.
 *
 * Booleans, ints (as `Long`), floats and strings are stored as themselves, so the host's own
 * code and tools that open the file read plain values. Every other type is stored as its
 * JSON value text, written by [FlagValueJson].
 *
 * Reads are type-guided: a value stored with another class, or JSON text of another type,
 * reads as nothing, so resolution falls through to the next source. There is no numeric
 * conversion. A write that changes nothing is skipped. Every change, including one made by
 * other code or by another source over the same file, is announced through one listener,
 * which the framework calls on the main thread.
 */
public class SharedPreferencesSource(
    private val preferences: SharedPreferences,
    override val sourceName: String = "SharedPreferences",
) : MutableFlagValueSource, AutoCloseable {

    private val emitter = FlagChangeEmitter()

    private val lock = Any()

    // Held strongly here: SharedPreferences keeps its listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A null key is clear() on API 30 and later: anything may have changed.
        emitter.emit(if (key == null) FlagChange.All else FlagChange.Keys(setOf(FlagKey(key))))
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? {
        val name = key.rawValue
        // One key through its typed getter, never a copy of the whole file. A getter throws
        // ClassCastException when the key holds another class.
        if (!preferences.contains(name)) return null
        return try {
            when (type) {
                FlagValueType.Bool -> FlagValueBox.Bool(preferences.getBoolean(name, false))
                FlagValueType.Int -> FlagValueBox.Int(preferences.getLong(name, 0L))
                FlagValueType.Float -> FlagValueBox.Float(preferences.getFloat(name, 0f))
                FlagValueType.String -> preferences.getString(name, null)?.let { FlagValueBox.String(it) }
                FlagValueType.Double,
                FlagValueType.Data,
                FlagValueType.Date,
                FlagValueType.Url,
                is FlagValueType.Array,
                is FlagValueType.Dictionary,
                -> preferences.getString(name, null)?.let { FlagValueJson.decode(it, type) }
            }
        } catch (ignored: ClassCastException) {
            null
        }
    }

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        setBoxes(mapOf(key to box))
    }

    /**
     * Writes every change with one editor and one `commit()`, so the file never holds half of
     * them and the write is on disk when this returns. Changes that leave a key as it is are
     * left out; when none is left, the file is not touched at all.
     *
     * @throws FlagException.UnsupportedValue for a double holding an infinity or a NaN, which
     *   JSON text cannot represent. Nothing is written.
     */
    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        // Encode everything first, so a value that cannot be stored fails before any write.
        val values = changes.mapValues { (key, box) -> box?.let { storedForm(key, it) } }
        synchronized(lock) {
            val edits = values.filter { (key, value) ->
                if (value == null) preferences.contains(key.rawValue) else !holds(key.rawValue, value)
            }
            if (edits.isEmpty()) return
            val editor = preferences.edit()
            for ((key, value) in edits) {
                when (value) {
                    null -> editor.remove(key.rawValue)
                    is Boolean -> editor.putBoolean(key.rawValue, value)
                    is Long -> editor.putLong(key.rawValue, value)
                    is Float -> editor.putFloat(key.rawValue, value)
                    else -> editor.putString(key.rawValue, value as String)
                }
            }
            editor.commit()
        }
    }

    /** Announces [FlagChange.All], so every flag is read again. For values written by something other than a listener-notified editor. */
    public fun refresh() {
        emitter.emit(FlagChange.All)
    }

    /** Unregisters the preferences listener. The source still reads and writes, but announces nothing. */
    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Whether [name] already holds [value]: the same stored class and an equal value. */
    private fun holds(name: String, value: Any): Boolean {
        if (!preferences.contains(name)) return false
        return try {
            when (value) {
                is Boolean -> preferences.getBoolean(name, false) == value
                is Long -> preferences.getLong(name, 0L) == value
                // Bitwise, so 0.0 and -0.0 differ and NaN equals itself, as they are stored.
                is Float -> preferences.getFloat(name, 0f).toRawBits() == value.toRawBits()
                else -> preferences.getString(name, null) == value
            }
        } catch (ignored: ClassCastException) {
            false
        }
    }

    /** What [box] is stored as: a Boolean, Long, Float or String. */
    private fun storedForm(key: FlagKey, box: FlagValueBox): Any = when (box) {
        is FlagValueBox.Bool -> box.value
        is FlagValueBox.Int -> box.value
        is FlagValueBox.Float -> box.value
        is FlagValueBox.String -> box.value
        else -> try {
            FlagValueJson.encode(box)
        } catch (ignored: FlagSerializationException.NonFiniteNumber) {
            throw FlagException.UnsupportedValue(key)
        }
    }

    public companion object {
        /**
         * The source a companion edits: the well-known file [FlagBridgeContract.PREFERENCES_NAME],
         * opened `MODE_PRIVATE`. Put it at the top of the host pole's sources.
         */
        public fun companion(context: Context, name: String = "Companion"): SharedPreferencesSource =
            SharedPreferencesSource(context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE), name)
    }
}
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
./gradlew :featureflag-android:testDebugUnitTest --tests 'com.andyyhope.featureflag.android.sources.SharedPreferencesSourceTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both. `SharedPreferencesSourceTest` runs 27 tests, all passing, including `aValueStoredWithAnotherTypeReadsAsNothing` and `clearingTheFileEmitsAWholesaleChange`.

- [ ] **Step 6: Commit**

```bash
git add featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSource.kt \
  featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt \
  featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSourceTest.kt
git commit -m "Add SharedPreferencesSource with type-guided reads and one-commit writes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A5: `FlagBridgeContract` and `publishSchema(context)`

**Files:**
- Modify (full replacement): `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt`
- Create: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/transport/AndroidTransport.kt`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/transport/AndroidTransportTest.kt`

**Interfaces:**
- Consumes: A4 `FlagBridgeContract.PREFERENCES_NAME`; A3 `FlagSchema.write`; M1 `FlagPole<*>.publishSchema(directory: File)`, `FlagSchema.read`, `jsonBytes`, `FILE_NAME`.
- Produces: the overview's `FlagBridgeContract`, exactly, and
```kotlin
// package com.andyyhope.featureflag.android.transport
public fun FlagPole<*>.publishSchema(context: Context): File
```

- [ ] **Step 1: Write the failing tests**

`featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt`
```kotlin
package com.andyyhope.featureflag.android.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagBridgeContractTest {

    // Names derived from the host's package

    @Test
    fun theAuthorityAndPermissionFollowTheHostPackage() {
        assertEquals("com.example.app.featureflag", FlagBridgeContract.authority("com.example.app"))
        assertEquals(
            "com.example.app.debug.permission.FEATURE_FLAG_BRIDGE",
            FlagBridgeContract.permission("com.example.app.debug"),
        )
    }

    @Test
    fun theUrisAreContentUrisUnderTheAuthority() {
        val schema = FlagBridgeContract.schemaUri("com.example.app")
        val overrides = FlagBridgeContract.overridesUri("com.example.app")

        assertEquals("content://com.example.app.featureflag/schema", schema.toString())
        assertEquals("content://com.example.app.featureflag/overrides", overrides.toString())
        assertEquals("content", schema.scheme)
        assertEquals("com.example.app.featureflag", overrides.authority)
        assertEquals(listOf("overrides"), overrides.pathSegments)
    }

    // Where the schema lives

    @Test
    fun theSchemaDirectoryIsUnderNoBackupFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(File(context.noBackupFilesDir, "featureflag"), FlagBridgeContract.schemaDirectory(context))
    }

    // Wire constants

    @Test
    fun aPayloadFitsWellInsideOneBinderTransaction() {
        // The binder buffer is 1 MiB per process, shared by every transaction in flight.
        assertEquals(512 * 1024, FlagBridgeContract.MAX_PAYLOAD_BYTES)
        assertTrue(FlagBridgeContract.MAX_PAYLOAD_BYTES * 2 <= 1024 * 1024)
    }

    @Test
    fun theWireNamesAreStable() {
        // Host and companion builds from different releases must still agree on these.
        assertEquals(1, FlagBridgeContract.PROTOCOL)
        assertEquals("com.andyyhope.featureflag.companion", FlagBridgeContract.PREFERENCES_NAME)
        assertEquals(
            listOf("hello", "overrides", "apply", "signal"),
            listOf(
                FlagBridgeContract.METHOD_HELLO,
                FlagBridgeContract.METHOD_OVERRIDES,
                FlagBridgeContract.METHOD_APPLY,
                FlagBridgeContract.METHOD_SIGNAL,
            ),
        )
        assertEquals(
            listOf("protocol", "status", "message", "payload", "remove", "handled", "size"),
            listOf(
                FlagBridgeContract.KEY_PROTOCOL,
                FlagBridgeContract.KEY_STATUS,
                FlagBridgeContract.KEY_MESSAGE,
                FlagBridgeContract.KEY_PAYLOAD,
                FlagBridgeContract.KEY_REMOVE,
                FlagBridgeContract.KEY_HANDLED,
                FlagBridgeContract.KEY_SIZE,
            ),
        )
        assertEquals(
            listOf("ok", "not-published", "rejected", "too-large"),
            listOf(
                FlagBridgeContract.STATUS_OK,
                FlagBridgeContract.STATUS_NOT_PUBLISHED,
                FlagBridgeContract.STATUS_REJECTED,
                FlagBridgeContract.STATUS_TOO_LARGE,
            ),
        )
    }
}
```

`featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/transport/AndroidTransportTest.kt`
```kotlin
package com.andyyhope.featureflag.android.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AndroidTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pole(keyEncoding: KeyEncoding = KeyEncoding.Kebabcase): FlagPole<Any> =
        FlagPole(DemoSpec, emptyList(), keyEncoding, applicationName = "Demo")

    // Publishing

    @Test
    fun publishingWritesTheSchemaBytesUnderNoBackupFiles() {
        val pole = pole()

        val file = pole.publishSchema(context)

        assertEquals(File(File(context.noBackupFilesDir, "featureflag"), FlagSchema.FILE_NAME), file)
        assertContentEquals(pole.schema.jsonBytes(), file.readBytes())
    }

    @Test
    fun theProviderReadsWhatWasPublished() {
        val pole = pole()
        pole.publishSchema(context)

        val read = FlagSchema.read(FlagBridgeContract.schemaDirectory(context))

        assertEquals(pole.schema.flags, read.flags)
        assertEquals("Demo", read.applicationName)
    }

    @Test
    fun publishingOnEveryLaunchReplacesTheFile() {
        pole().publishSchema(context)
        val snake = pole(KeyEncoding.Snakecase)

        val file = snake.publishSchema(context)

        assertContentEquals(snake.schema.jsonBytes(), file.readBytes())
        assertEquals(listOf(FlagSchema.FILE_NAME), FlagBridgeContract.schemaDirectory(context).list()!!.toList())
    }

    /** Two flags, written by hand: the Android modules do not run KSP. */
    private object DemoSpec : FlagContainerSpec<Any> {
        override val flagContainerDescription: String = "Demo flags"
        override val flagDescriptors: List<FlagSchemaNode> = listOf(
            FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "New onboarding", null, FlagCodecs.boolean, false)),
            FlagSchemaNode.Flag(FlagDescriptor.of("maxItems", "Maximum items", "limits.maxItems", FlagCodecs.int, 10)),
        )
        override val flagDefaults: Any = Any()

        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Any()
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest --tests 'com.andyyhope.featureflag.android.bridge.FlagBridgeContractTest' --tests 'com.andyyhope.featureflag.android.transport.AndroidTransportTest'
```

Expected: `BUILD FAILED` in `:featureflag-android:compileDebugUnitTestKotlin`, with `Unresolved reference 'authority'.`, `'permission'`, `'schemaUri'`, `'overridesUri'`, `'schemaDirectory'`, `'MAX_PAYLOAD_BYTES'`, `'PROTOCOL'`, the `METHOD_`, `KEY_` and `STATUS_` names, and `'publishSchema'` with a `Context` argument.

- [ ] **Step 3: Complete the contract**

Modify (full replacement) `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt`:
```kotlin
package com.andyyhope.featureflag.android.bridge

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The names a host's bridge provider and a companion's client agree on: where the provider
 * lives, what it is called with, and what it answers.
 *
 * Every name is part of the wire protocol. Host and companion builds from different releases
 * must still agree on them, so they change only together with [PROTOCOL].
 */
public object FlagBridgeContract {
    /** The bridge protocol version, answered by [METHOD_HELLO]. */
    public const val PROTOCOL: Int = 1

    /**
     * The host's preferences file that holds companion overrides. The host's pole reads it
     * through `SharedPreferencesSource.companion(context)`, and the bridge provider writes it.
     */
    public const val PREFERENCES_NAME: String = "com.andyyhope.featureflag.companion"

    /** The directory, under `Context.noBackupFilesDir`, that `publishSchema(context)` writes into. */
    public const val SCHEMA_DIRECTORY: String = "featureflag"

    /**
     * The largest payload either side sends, in bytes. A `Bundle` crossing processes shares a
     * 1 MiB binder buffer with every other transaction in flight, so this is half of it.
     */
    public const val MAX_PAYLOAD_BYTES: Int = 512 * 1024

    public const val METHOD_HELLO: String = "hello"
    public const val METHOD_OVERRIDES: String = "overrides"
    public const val METHOD_APPLY: String = "apply"
    public const val METHOD_SIGNAL: String = "signal"

    /** `Int`: the [PROTOCOL] the host speaks. */
    public const val KEY_PROTOCOL: String = "protocol"

    /** `String`: one of the `STATUS_` constants. */
    public const val KEY_STATUS: String = "status"

    /** `String?`: human-readable detail for a status other than [STATUS_OK]. */
    public const val KEY_MESSAGE: String = "message"

    /** `ByteArray`: a `FlagPayload` as export-format JSON. */
    public const val KEY_PAYLOAD: String = "payload"

    /** `Array<String>`: the keys whose override is removed. */
    public const val KEY_REMOVE: String = "remove"

    /** `Boolean`: whether the host ran a handler for a signal. */
    public const val KEY_HANDLED: String = "handled"

    /** `Int`: the payload's size in bytes, sent with [STATUS_TOO_LARGE]. */
    public const val KEY_SIZE: String = "size"

    public const val STATUS_OK: String = "ok"
    public const val STATUS_NOT_PUBLISHED: String = "not-published"
    public const val STATUS_REJECTED: String = "rejected"
    public const val STATUS_TOO_LARGE: String = "too-large"

    /** The provider authority of the host installed as [hostPackage]. */
    public fun authority(hostPackage: String): String = "$hostPackage.featureflag"

    /** The signature permission that guards the provider of the host installed as [hostPackage]. */
    public fun permission(hostPackage: String): String = "$hostPackage.permission.FEATURE_FLAG_BRIDGE"

    /** `content://<authority>/schema`: the published schema file, opened read-only. */
    public fun schemaUri(hostPackage: String): Uri = Uri.parse("content://${authority(hostPackage)}/schema")

    /** `content://<authority>/overrides`: observed for changes to the host's companion overrides. */
    public fun overridesUri(hostPackage: String): Uri = Uri.parse("content://${authority(hostPackage)}/overrides")

    /** Where this app's schema is published, and where its provider looks for it. */
    public fun schemaDirectory(context: Context): File = File(context.noBackupFilesDir, SCHEMA_DIRECTORY)
}
```

- [ ] **Step 4: Add `publishSchema(context)`**

`featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/transport/AndroidTransport.kt`
```kotlin
package com.andyyhope.featureflag.android.transport

import android.content.Context
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.transport.publishSchema
import java.io.File

/**
 * Writes the schema where `FlagBridgeProvider` serves it: `flag-schema.json` in
 * [FlagBridgeContract.schemaDirectory], under `noBackupFilesDir`, so a restored backup never
 * brings back another build's schema. The write is atomic.
 *
 * Call this on every launch, in `Application.onCreate`: a companion has no other way to learn
 * what flags exist, and the provider waits for `onCreate` to finish before it answers.
 *
 * @return the file written.
 */
public fun FlagPole<*>.publishSchema(context: Context): File =
    publishSchema(FlagBridgeContract.schemaDirectory(context))
```

The expression body is wrapped after `=` because detekt's `ParameterListWrapping` rejects it on one line.

- [ ] **Step 5: Run the tests and watch them pass**

```bash
./gradlew :featureflag-android:testDebugUnitTest --tests 'com.andyyhope.featureflag.android.bridge.FlagBridgeContractTest' --tests 'com.andyyhope.featureflag.android.transport.AndroidTransportTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both. `FlagBridgeContractTest` runs 5 tests and `AndroidTransportTest` 3, all passing; `SharedPreferencesSourceTest` still runs 27.

- [ ] **Step 6: Commit**

```bash
git add featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt \
  featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/transport/AndroidTransport.kt \
  featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt \
  featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/transport/AndroidTransportTest.kt
git commit -m "Add FlagBridgeContract and publishSchema(context)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A6: `FlagSignalChannel`

**Files:**
- Create: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/signals/FlagSignalChannel.kt`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/signals/FlagSignalChannelTest.kt`

**Interfaces:**
- Consumes: M1 `FlagSignal` (`name`, `signalDescription`, `requiresRestart`).
- Produces:
```kotlin
// package com.andyyhope.featureflag.android.signals
public class FlagSignalChannel internal constructor(private val mainThread: Executor?) {   // null = run inline (tests)
    public fun <S : FlagSignal> observe(signals: List<S>, handler: (S) -> Unit): AutoCloseable
    public fun deliver(name: String, timeout: Duration = DELIVERY_TIMEOUT): Boolean
    public companion object {
        public val host: FlagSignalChannel
        public val DELIVERY_TIMEOUT: Duration       // 4.seconds
    }
}
```

- [ ] **Step 1: Write the failing tests**

`featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/signals/FlagSignalChannelTest.kt`
```kotlin
package com.andyyhope.featureflag.android.signals

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private enum class TestSignal : FlagSignal {
    Refetch,
    PurgeCache,
    DumpDiagnostics,
    ;

    override val signalDescription: String
        get() = when (this) {
            Refetch -> "Re-fetch remote configuration"
            PurgeCache -> "Purge cached responses"
            DumpDiagnostics -> "Write diagnostics"
        }
}

/** Stands in for an older host build that knows fewer signals. */
private enum class NarrowSignal : FlagSignal {
    Refetch,
}

private enum class GroupedCacheSignal : FlagSignal {
    PurgeImages,
}

private enum class GroupedSessionSignal : FlagSignal {
    SignOut,
}

private enum class RestartingSignal : FlagSignal {
    SwapDependency,
    Harmless,
    ;

    override val requiresRestart: Boolean get() = this == SwapDependency
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagSignalChannelTest {

    /** Runs handlers on the calling thread, which is the main thread under Robolectric. */
    private val channel = FlagSignalChannel(null)

    private val background = Executors.newSingleThreadExecutor()

    /** Posts to the main looper, as FlagSignalChannel.host does. */
    private val mainThread = Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    @After
    fun shutDown() {
        background.shutdownNow()
    }

    /** Runs main-looper tasks until [future] completes: the main thread is free to run handlers. */
    private fun <T> idleMainLooperUntilDone(future: Future<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    // Delivery (FlagSignalChannelTests)

    @Test
    fun aSignalReachesAnObserver() {
        val seen = mutableListOf<TestSignal>()
        channel.observe(TestSignal.entries) { seen += it }

        channel.deliver(TestSignal.Refetch.name)

        assertEquals(listOf(TestSignal.Refetch), seen)
    }

    @Test
    fun eachSignalArrivesAsItself() {
        val seen = mutableListOf<TestSignal>()
        channel.observe(TestSignal.entries) { seen += it }

        for (signal in TestSignal.entries) channel.deliver(signal.name)

        assertEquals(TestSignal.entries.toList(), seen)
    }

    @Test
    fun nothingArrivesAfterTheObserverIsClosed() {
        var count = 0
        val observer = channel.observe(TestSignal.entries) { count++ }
        observer.close()

        assertFalse(channel.deliver(TestSignal.Refetch.name))
        assertEquals(0, count)
    }

    // Delivered once

    @Test
    fun oneDeliverRunsAHandlerOnce() {
        var count = 0
        channel.observe(TestSignal.entries) { count++ }

        channel.deliver(TestSignal.Refetch.name)

        assertEquals(1, count)
    }

    @Test
    fun anObserverRegisteredAfterADeliverIsNotCalledForIt() {
        // A signal is not queued: a host that starts later must not act on it.
        channel.deliver(TestSignal.Refetch.name)
        var count = 0

        channel.observe(TestSignal.entries) { count++ }

        assertEquals(0, count)
    }

    // Acknowledgement

    @Test
    fun deliverReturnsTrueWhenTheHostIsListening() {
        channel.observe(TestSignal.entries) { }

        assertTrue(channel.deliver(TestSignal.Refetch.name))
    }

    @Test
    fun deliverReturnsFalseWhenNothingIsListening() {
        assertFalse(channel.deliver(TestSignal.Refetch.name))
    }

    @Test
    fun aSignalThisBuildCannotRepresentIsNotHandled() {
        // A newer companion sending a signal an older host does not have.
        var count = 0
        channel.observe(NarrowSignal.entries) { count++ }

        assertFalse(channel.deliver(TestSignal.PurgeCache.name))
        assertEquals(0, count)
    }

    @Test
    fun anUnknownSignalDoesNotBlockTheNextValidOne() {
        val seen = mutableListOf<NarrowSignal>()
        channel.observe(NarrowSignal.entries) { seen += it }

        channel.deliver(TestSignal.PurgeCache.name)

        assertTrue(channel.deliver(TestSignal.Refetch.name))
        assertEquals(listOf(NarrowSignal.Refetch), seen)
    }

    // Declaring signals

    @Test
    fun signalsDescribeThemselvesForAButton() {
        assertEquals("Re-fetch remote configuration", TestSignal.Refetch.signalDescription)
        // Without an explicit description, the name stands in.
        assertEquals("Refetch", NarrowSignal.Refetch.signalDescription)
    }

    @Test
    fun theSignalListIsDiscoverable() {
        assertEquals(listOf("Refetch", "PurgeCache", "DumpDiagnostics"), TestSignal.entries.map { it.name })
    }

    // More than one observer (MultipleObserverTests)

    @Test
    fun eachObserverReceivesItsOwnSignals() {
        val caches = mutableListOf<GroupedCacheSignal>()
        val sessions = mutableListOf<GroupedSessionSignal>()
        channel.observe(GroupedCacheSignal.entries) { caches += it }
        channel.observe(GroupedSessionSignal.entries) { sessions += it }

        channel.deliver(GroupedSessionSignal.SignOut.name)
        channel.deliver(GroupedCacheSignal.PurgeImages.name)

        assertEquals(listOf(GroupedCacheSignal.PurgeImages), caches)
        assertEquals(listOf(GroupedSessionSignal.SignOut), sessions)
    }

    @Test
    fun anObserverStillRunsEachSignalOnlyOnce() {
        var caches = 0
        var sessions = 0
        channel.observe(GroupedCacheSignal.entries) { caches++ }
        channel.observe(GroupedSessionSignal.entries) { sessions++ }

        channel.deliver(GroupedCacheSignal.PurgeImages.name)

        assertEquals(1, caches)
        assertEquals(0, sessions)
    }

    @Test
    fun aSignalNoObserverCanRepresentIsNotHandled() {
        var count = 0
        channel.observe(GroupedCacheSignal.entries) { count++ }

        assertFalse(channel.deliver(GroupedSessionSignal.SignOut.name))
        assertEquals(0, count)
    }

    // requiresRestart

    @Test
    fun aSignalDoesNotRequireARestartUnlessItSaysSo() {
        assertFalse(GroupedCacheSignal.PurgeImages.requiresRestart)
    }

    @Test
    fun aSignalCanSayItNeedsARestart() {
        assertTrue(RestartingSignal.SwapDependency.requiresRestart)
        assertFalse(RestartingSignal.Harmless.requiresRestart)
    }

    @Test
    fun requiringARestartDoesNotChangeDelivery() {
        val seen = mutableListOf<RestartingSignal>()
        channel.observe(RestartingSignal.entries) { seen += it }

        assertTrue(channel.deliver(RestartingSignal.SwapDependency.name))
        assertEquals(listOf(RestartingSignal.SwapDependency), seen)
    }

    // Android only

    @Test
    fun twoObserversListingOneNameBothRun() {
        var first = 0
        var second = 0
        channel.observe(listOf(TestSignal.Refetch)) { first++ }
        channel.observe(TestSignal.entries) { second++ }

        assertTrue(channel.deliver(TestSignal.Refetch.name))
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun aThrowingHandlerIsNotHandledAndDoesNotEscape() {
        ShadowLog.clear()
        channel.observe(TestSignal.entries) { error("cache is locked") }

        assertFalse(channel.deliver(TestSignal.PurgeCache.name))

        val logged = ShadowLog.getLogsForTag("FeatureFlag").single()
        assertEquals("cache is locked", logged.throwable.message)
    }

    @Test
    fun aThrowingHandlerDoesNotStopTheOthers() {
        var ran = 0
        channel.observe(TestSignal.entries) { error("cache is locked") }
        channel.observe(TestSignal.entries) { ran++ }

        assertTrue(channel.deliver(TestSignal.PurgeCache.name))
        assertEquals(1, ran)
    }

    @Test
    fun deliverFromABackgroundThreadRunsTheHandlerOnTheMainThread() {
        val channel = FlagSignalChannel(mainThread)
        var handlerThread: Thread? = null
        channel.observe(TestSignal.entries) { handlerThread = Thread.currentThread() }

        val handled = idleMainLooperUntilDone(background.submit<Boolean> { channel.deliver(TestSignal.Refetch.name) })

        assertTrue(handled)
        assertSame(Looper.getMainLooper().thread, handlerThread)
    }

    @Test
    fun deliverReturnsFalseAfterTheTimeoutWhenTheMainThreadIsBlocked() {
        val channel = FlagSignalChannel(mainThread)
        var count = 0
        channel.observe(TestSignal.entries) { count++ }

        // The test thread is the main thread. Not idling its looper stands in for a main
        // thread busy elsewhere for longer than the timeout.
        val handled = background.submit<Boolean> { channel.deliver(TestSignal.Refetch.name, 100.milliseconds) }
            .get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(handled)
        assertEquals(0, count, "a delivery that timed out must not run later")
    }

    @Test
    fun deliverOnTheMainThreadRunsInline() {
        // Posting and waiting would deadlock: the main thread would wait for itself.
        val channel = FlagSignalChannel(mainThread)
        var count = 0
        channel.observe(TestSignal.entries) { count++ }

        assertTrue(channel.deliver(TestSignal.Refetch.name))
        assertEquals(1, count)
    }

    @Test
    fun theHostChannelRunsHandlersOnTheMainThread() {
        var count = 0
        val observer = FlagSignalChannel.host.observe(TestSignal.entries) { count++ }
        try {
            assertTrue(FlagSignalChannel.host.deliver(TestSignal.Refetch.name))
            assertEquals(1, count)
        } finally {
            observer.close()
        }
        assertEquals(4.seconds, FlagSignalChannel.DELIVERY_TIMEOUT)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest --tests 'com.andyyhope.featureflag.android.signals.FlagSignalChannelTest'
```

Expected: `BUILD FAILED` in `:featureflag-android:compileDebugUnitTestKotlin`, starting with `FlagSignalChannelTest.kt:64:27 Unresolved reference 'FlagSignalChannel'.`, followed by `Unresolved reference 'it'.` in the handler lambdas whose type can no longer be inferred.

- [ ] **Step 3: Add the channel**

`featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/signals/FlagSignalChannel.kt`
```kotlin
package com.andyyhope.featureflag.android.signals

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andyyhope.featureflag.signals.FlagSignal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands signals a companion sends to the host app's handlers, on the main thread.
 *
 * A host registers its handlers on [host] in `Application.onCreate`, and the bridge provider
 * calls [deliver] for each signal that arrives. A signal is delivered exactly once, to every
 * observer whose list names it, and only to observers registered when it arrives: nothing
 * is queued for a host that starts listening later.
 *
 * @param mainThread runs the handlers; null runs them inline, on the delivering thread.
 */
public class FlagSignalChannel internal constructor(private val mainThread: Executor?) {

    private val observers = CopyOnWriteArrayList<Observer<*>>()

    /**
     * Runs [handler] on the main thread for every delivered signal named in [signals]. Hold the
     * result; `close()` stops delivery.
     */
    public fun <S : FlagSignal> observe(signals: List<S>, handler: (S) -> Unit): AutoCloseable {
        val observer = Observer(signals.associateBy { it.name }, handler)
        observers.add(observer)
        return AutoCloseable {
            observer.closed = true
            observers.remove(observer)
        }
    }

    /**
     * Delivers the signal named [name] to every observer whose list contains it, waiting up to
     * [timeout] for the handlers.
     *
     * Returns true when at least one handler ran to completion. Called by FlagBridgeProvider;
     * safe from any thread. When called on the main thread it runs the handlers inline (no
     * deadlock). A delivery that times out is withdrawn, so its handlers do not run later.
     */
    public fun deliver(name: String, timeout: Duration = DELIVERY_TIMEOUT): Boolean {
        val matching = observers.filter { it.handles(name) }
        if (matching.isEmpty()) return false
        // Every matching handler runs, even after one has already succeeded.
        val run = { matching.count { it.run(name) } > 0 }
        if (mainThread == null || Looper.getMainLooper().isCurrentThread) return run()
        val task = FutureTask(run)
        mainThread.execute(task)
        return try {
            task.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (ignored: TimeoutException) {
            task.cancel(false)
            false
        }
    }

    private class Observer<S : FlagSignal>(private val signals: Map<String, S>, private val handler: (S) -> Unit) {
        @Volatile
        var closed = false

        fun handles(name: String): Boolean = name in signals

        /** Runs the handler for [name]. False when it is closed, or the handler threw. */
        @Suppress("TooGenericExceptionCaught") // A handler is app code; whatever it throws must not reach the provider.
        fun run(name: String): Boolean {
            val signal = signals[name] ?: return false
            if (closed) return false
            return try {
                handler(signal)
                true
            } catch (e: Exception) {
                Log.w(TAG, "The handler for the signal '$name' threw, so it was not handled.", e)
                false
            }
        }
    }

    public companion object {
        private const val TAG = "FeatureFlag"

        /** How long [deliver] waits for the handlers by default: 4 seconds, under the client's 5-second default. */
        public val DELIVERY_TIMEOUT: Duration = 4.seconds

        /** The host process's channel. Register observers in `Application.onCreate`. */
        public val host: FlagSignalChannel =
            FlagSignalChannel { command -> Handler(Looper.getMainLooper()).post(command) }
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
./gradlew :featureflag-android:testDebugUnitTest --tests 'com.andyyhope.featureflag.android.signals.FlagSignalChannelTest'
./gradlew build detekt :featureflag-android:assembleRelease
```

Expected: `BUILD SUCCESSFUL` from both. `FlagSignalChannelTest` runs 24 tests, all passing; `:featureflag-android` runs 60 unit tests in all.

- [ ] **Step 5: Commit**

```bash
git add featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/signals/FlagSignalChannel.kt \
  featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/signals/FlagSignalChannelTest.kt
git commit -m "Add FlagSignalChannel for host-side signal delivery" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Contract issues found

None of these renames anything in the contract. Each is a gap the contract leaves open, or a statement in it that turned out to be imprecise, with what this plan does about it.

1. **A4 needs `FlagBridgeContract.PREFERENCES_NAME` before A5 creates `FlagBridgeContract`.** The contract has `SharedPreferencesSource.companion(context)` use `FlagBridgeContract.PREFERENCES_NAME`, but the file structure assigns `bridge/FlagBridgeContract.kt` to A5, which runs later. A4 therefore creates the object with only that constant, and A5 replaces the file with the complete contract. The constant's name, value and package are the contract's.
2. **What `SharedPreferencesSource.setBox`/`setBoxes` throw for a non-finite double is unspecified.** The storage table sends a `Double` through `FlagValueJson.encode`, which throws `FlagSerializationException.NonFiniteNumber(null)`, naming no flag. This plan encodes every value before writing anything and throws `FlagException.UnsupportedValue(key)`, which names the flag, so nothing is written (`aNonFiniteDoubleIsRefusedAndNothingIsWritten`). A non-finite `Float` is still stored natively, since `putFloat` can hold it. A reviewer should confirm this choice. **It reaches B1:** `FlagPayload.decode` accepts `-1e400` for a double flag as negative infinity (core's `aNegativeOverflowIsMinusInfinityLikeApple`), so a companion `apply` carrying it passes validation and then makes `setBoxes` throw `UnsupportedValue`. B1 should catch it and answer `STATUS_REJECTED` with its message, or the provider's `call` throws into the host.
3. **A timed-out `deliver` is not specified further.** The contract says `deliver` returns false after the timeout, but not whether the queued handlers may still run once the main thread frees up. Running them would report "not handled" for a signal that was in fact handled later. This plan withdraws the task (`FutureTask.cancel(false)`), so they never run; a handler already running when the timeout fires finishes, but `deliver` has already returned false (`deliverReturnsFalseAfterTheTimeoutWhenTheMainThreadIsBlocked`).
4. **"A Robolectric test must idle the main looper before expecting an emission" is only half right.** Robolectric runs the test on the main thread, and `SharedPreferencesImpl` calls listeners inline when `commit()` runs on the main thread. Idling matters only for a commit made on another thread, where the callback is posted. The A4 tests idle anyway, as the contract asks. A later Robolectric test that commits from a background thread must idle the main looper, or it will see no emission.
5. **`FlagSchema.write`: "A failure throws an `IOException` that … carries the cause"** cannot hold when the directory cannot be created, because `mkdirs()` reports only `false`, with no exception to carry. This plan names the target and the directory in that message, with a null cause (`aDirectoryThatCannotBeMadeNamesTheTarget`), and carries the cause for every other failure.
6. **`FlagSignalChannel.host`** is commented in the contract as `FlagSignalChannel(Handler(Looper.getMainLooper())::post)`. This plan uses a lambda that creates the `Handler` on each post, which behaves the same and does not capture a `Handler` in a static initialiser.
7. **AGP 9 runs unit tests for the debug variant only.** The contract's commands are unaffected, but any later task that names `testReleaseUnitTest` will find no such task.
