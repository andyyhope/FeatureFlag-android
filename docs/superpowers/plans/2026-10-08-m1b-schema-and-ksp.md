# M1b Schema and KSP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port FeatureFlag 0.12.0's schema layer (`FlagDescriptor`, `FlagSchemaNode`, `FlagSchema` with Apple-identical JSON, forward-compatible parsing and atomic publishing) and its container wiring (`FlagLookup`, `FlagContainerSpec`, `FlagRef`) to `featureflag-core`, then replace the hand-written container and record code with a KSP processor whose output has exactly the same shape, and give every misuse of the annotations a diagnostic at the line where it is written.  **Architecture:** `@FlagContainer` interfaces carry their compiled defaults as default getters. For each one, `featureflag-ksp` writes `XRefs` (the typed `FlagRef` tree) and `XSpec : XRefs, FlagContainerSpec<X>` (descriptors, an `object : X` of defaults, and a private `Impl` that reads through a `FlagLookup` and falls back to `super<X>.prop`). For each `@FlagRecord` class it writes `RFlagRecord : FlagRecordCodec<R>`, reading field defaults off a prototype built with placeholders. Group specs and record codecs from other modules are found by name. A `Checks` pass runs first and reports every mistake at its declaration, so nothing invalid reaches generation.  **Tech Stack:** Kotlin 2.2.10 (JVM), KSP 2.2.10-2.0.2 (KSP2), KotlinPoet 2.2.0 + kotlinpoet-ksp, kctfork 0.8.0 (KSP2 harness), JUnit 4.13.2 + kotlin-test, detekt 1.23.8.
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

1. **A consumer module compiled for JVM 1.8 or 11** (still Android's common setting) must run generated code that never inlines core's JVM 17 bytecode, so nothing generated code calls may be `inline`. Pinned by `ContainerGenerationTest.aConsumerOnJvmTarget18RunsTheGeneratedCode` (Task B3); the kctfork harness also compiles every test at `jvmTarget = "1.8"`.
2. **A record whose constructor rejects the placeholders** (an `init { require(…) }`, or a default that reads another parameter) must fail with an `IllegalStateException` naming the record when the schema is built, not as an `ExceptionInInitializerError` when the spec object first loads. Pinned by `FlagRecordGenerationTest.aRecordThatRejectsItsPlaceholdersFailsWhenTheSchemaIsBuiltNotWhenItsSpecLoads` and `aConstructorThatRejectsThePlaceholdersFailsNamingTheRecord` (Task B4).
3. **A non-constant default getter** (`Instant.now()`, a counter) runs again on every read that falls back to it, where Swift evaluates `default:` once per container. Pinned by `GeneratedContainerTest.aDefaultGetterRunsOnEveryReadThatFallsBackToIt` (Task B3).
4. **An `Int`-keyed record list**: `records[1]` must stay positional. No `get(key: Int)` is generated, because `List.get(index: Int)` would always win. Pinned by `RecordGenerationTest.anIntKeyGetsNoLookupOperatorBecauseListGetWouldWin` and `FlagRecordGenerationTest.anIntKeyedListStillIndexesByPosition` (Task B4).
5. **A group or record type from a module that never applied the processor** is explained at the property, not as an unresolved `XSpec` in generated code. Pinned by `ContainerDiagnosticTest.aGroupFromAModuleWithoutTheProcessorIsExplained` and `RecordDiagnosticTest.aRecordFromAModuleWithoutTheProcessorIsExplained` (Task B5).

---

## What this plan relies on, and what it settled

### From M1a (as written in `2026-10-08-m1a-foundation.md`)

- **Packages follow directories:** `values`, `json`, `records`, `errors`, and from here `schema` and `container`. The annotations live in `com.andyyhope.featureflag`. `FlagValueBox.toJson()` and `JsonValue.toBox(type)` are internal functions in `com.andyyhope.featureflag.values` (`values/BoxJson.kt`).
- **Root `build.gradle.kts`** declares `alias(libs.plugins.ksp) apply false`, so a module applies KSP with `alias(libs.plugins.ksp)`. The catalog has `ksp-api`, `kotlinpoet`, `kotlinpoet-ksp`, `kctfork-core` and `kctfork-ksp`. Task B3 adds three KSP test artifacts.
- **`featureflag-ksp`** holds M1a's placeholder `FeatureFlagProcessor` inside `FeatureFlagProcessorProvider.kt` and a `SmokeTest.kt`. Task B3 replaces both.
- **The golden model** is in the core test sources, in `golden/GoldenModel.kt` (enums and records) and `golden/GoldenRecordCodecs.kt` (hand-written `GoldenEndpointFlagRecord` and `GoldenTargetFlagRecord`). This plan puts the containers in a new `golden/GoldenFlags.kt`. Task B4, not B3, annotates the records and deletes `GoldenRecordCodecs.kt`, because B3 generates only containers.
- **`Fixtures.text(path)` / `Fixtures.bytes(path)`** in package `com.andyyhope.featureflag` (core test sources) read `fixtures/`. Message fixtures are `messages/FlagSchemaError.<case>.txt`, and the schema goldens are `schema/golden-kebab.flag-schema.json` and `schema/golden-snake.flag-schema.json`.
- **`FlagCodecs.records(codec)`** reads `codec.shape` eagerly in M1a. Task B4 makes it a getter, so a record that fails to build its prototype fails when a schema is built, not while a spec object initialises.

### Measured while writing this plan (scratch builds, 2026-10-08)

Every code block below was compiled and run. The scratch project used M1a's exact layout: `build-logic` convention plugin, root `apply false`, configuration cache, the M1a JSON/record code extracted from its plan, and `kspTest(project(":featureflag-ksp"))`. Each task's end state was rebuilt in order (B2, B3, B4, B5), and each ended with `./gradlew build detekt` green. The schema goldens were produced by FeatureFlag 0.12.0 itself, from a SwiftPM package depending on `../feature-flags`. Both `GoldenSchemaTest` byte comparisons passed against the hand-written spec (B2) and against the generated one (B3 to B5). The three message fixtures matched Swift's `String(describing:)` output.

- **(a) `isAbstract()` works on KSP2 and `getter` does not.** For `val x: Boolean get() = false` it returns `false`; for `val y: Foo` and `abstract val v: Long` it returns `true`. An inferred `val w get() = "x"` is also `false`. In KSP2 `getter` is **non-null even for an abstract property** (its modifiers include `ABSTRACT`), so a `getter == null` test would wrongly treat every flag as having a default. Always use `com.google.devtools.ksp.isAbstract()`.
- **(b) The `super<X>.prop` fallback compiles inside a non-inline lambda and returns the interface default.** This was checked at runtime through the generated `Impl`, and at `jvmTarget = "1.8"`.
- **(c) A defaults object missing a getter fails to compile.** `val defaults: X = object : X {}`, where `X` has an abstract `val`, fails with `Class '<anonymous>' is not abstract and does not implement abstract member`. B5's diagnostic fires before that.
- **(d) kctfork 0.8.0's KSP2 harness works with KSP 2.2.10-2.0.2.** The KSP artifacts are pinned in tests. `logger.error(msg, node)` surfaces in `result.messages` as `e: [ksp] /abs/path/File.kt:<line>: <msg>`, with no column. The line is the line of the declaration's keyword (`class`, `val`, the parameter name), **not** of an annotation above it. kctfork defaults to `jvmTarget = "1.8"`. **Making `FlagLookup.read` `inline` breaks every consumer below JVM 17** ("Cannot inline bytecode built with JVM target 17 into bytecode that is being built with JVM target 1.8"), so it stays an ordinary function.
- **(e) A group interface, and a record, in a second Gradle module are resolved by naming convention.** `BINARY` annotations are visible on library classes, and `resolver.getClassDeclarationByName("…XSpec")` finds the other module's generated object. This was checked in a two-module Gradle build and in two-stage kctfork compiles.
- **(f) The prototype approach reads constructor defaults** (`weight = 1`, `kind = FREE`, `targets = FlagRecords()`). An `init { require(id != 0) }` makes it throw, and that is wrapped as `IllegalStateException` naming the record.
- **KotlinPoet emits explicit `public`**, so generated code satisfies `explicitApi()` in a consumer module. It turns a single `return …` body into an expression body, so `fromBoxes` binds a local first: an expression body cannot contain `?: return null`. It escapes `get` as `` `get` ``. Unused constructor parameters and unused private properties raise no K2 warning, so an empty container compiles under `allWarningsAsErrors`.
- **K2 does not warn** about an extension `FlagRecords<R>.get(key: Int)` shadowed by `List.get(index: Int)`. It silently never calls it. Hence Review Focus 4.

### Contract deviations (now part of the overview's contract)

1. `FlagSchema.of(spec, keyEncoding = KeyEncoding.Kebabcase, applicationName: String? = null, generatedAt = Instant.now())`. The parameter is **nullable with a `null` default**, not `String = ""`. Swift's `applicationName: String? = nil` omits the key, while an empty string writes `"applicationName" : ""`. A pole without a name must therefore pass `null` through, not `""`.
2. `FlagCodecs.records(codec).recordShape` is a **getter** (`get() = codec.shape`). Task B4 makes the change in M1a's `FlagCodecs.kt`.
3. `FlagSchemaException.UnsupportedFormatVersion(public val version: Long)`. Swift's `Int` is 64-bit; a literal such as `UnsupportedFormatVersion(9)` still compiles. `Malformed(reason: String, cause: Throwable? = null)` and `NotPublished(cause: Throwable? = null)` keep the cause of a failed read.
4. `FlagSchemaException.NotPublished`'s message keeps Swift's first two sentences and **drops the App Group clause**: "No schema has been published. The host app writes one with publishSchema(), so run it at least once." A message golden must not expect Swift's `FlagSchemaError.notPublished.txt` verbatim. `FlagSchemaExceptionTest` pins exactly that difference.
5. `FlagSchema` carries Swift's derived views as well: `duplicateKeys`, `duplicateKeyDescription`, `valueTypes`, `valueCases` and `recordShapes`. It also nests `FlagSchema.Entry` and `FlagSchema.Group`.
6. The `FlagLookup` and `FlagContainerSpec` interfaces land in Task B1, because `FlagSchema.of` needs them. `FlagLookup.read`, `FlagRef` and the annotations land in Task B2.
7. **Reserved property names** in a `@FlagContainer`: `flagDefaults`, `flagDescriptors`, `flagContainerDescription`, `createFlags`, `flagLookup` and `flagKeys`. The last two are members of the generated `Impl`.
8. **Nested declarations** get `_`-joined generated names: `Outer.Inner` produces `Outer_InnerRefs`, `Outer_InnerSpec` and `Outer_InnerFlagRecord`.
9. `FlagRef.defaultValue` is read lazily (`by lazy`), because refs are built while the spec object that owns the defaults is still initialising.

---

## File map

```
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/
  Annotations.kt                                   B2
  schema/FlagDescriptor.kt  schema/FlagSchema.kt   B1
  container/FlagLookup.kt                          B1 (interface), B2 (+ read)
  container/FlagContainerSpec.kt                   B1
  container/FlagRef.kt                             B2
  errors/FlagSchemaException.kt                    B1
  values/FlagCodecs.kt                             B4 modifies (records: lazy recordShape)
featureflag-core/src/test/kotlin/com/andyyhope/featureflag/
  schema/SchemaFixtures.kt FlagDescriptorTest.kt FlagSchemaExceptionTest.kt FlagSchemaTest.kt
         DuplicateKeyTest.kt FlagSchemaParseTest.kt FlagSchemaFileTest.kt          B1
  container/FlagLookupTest.kt FlagRefTest.kt FlagContainerTest.kt               B2
  container/EdgeCaseFlags.kt GeneratedContainerTest.kt                          B3
  golden/GoldenFlags.kt GoldenSchemaTest.kt                                     B2
  golden/GoldenSpecs.kt                                                         B2 creates, B3 deletes
  golden/GoldenModel.kt                                                         B4 modifies (annotations)
  golden/GoldenRecordCodecs.kt                                                  B4 deletes (M1a's)
  golden/GoldenRecordTest.kt  records/RecordFixtures.kt  records/FlagRecordGenerationTest.kt   B4
featureflag-core/build.gradle.kts                                               B3 (KSP on tests)
gradle/libs.versions.toml                                                       B3 (+3 KSP test libraries)
featureflag-ksp/build.gradle.kts                                                B3
featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/
  FeatureFlagProcessorProvider.kt  FeatureFlagProcessor.kt                      B3, B4, B5
  TypeMapping.kt  ContainerGenerator.kt                                         B3
  RecordGenerator.kt                                                            B4
  Diagnostics.kt                                                                B5
featureflag-ksp/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider   B3
featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/
  KspHarness.kt  ContainerGenerationTest.kt                                     B3 (B5 appends to the harness)
  RecordGenerationTest.kt                                                       B4
  ContainerDiagnosticTest.kt  RecordDiagnosticTest.kt                           B5
featureflag-ksp/src/test/resources/expansions/
  AppFlagsSpec.kt.txt  CheckoutFlagsSpec.kt.txt                                 B3
  EndpointFlagRecord.kt.txt                                                     B4
```

Every Gradle command below runs from `/Users/andyyhope/Development/feature-flags-android` and starts with the `JAVA_HOME` export.

---

### Task B1: Descriptors, `FlagSchema` and its errors

Ports `Schema/FlagDescriptor.swift`, `Schema/FlagSchema.swift` and the `FlagSchemaError` part of `ErrorMessages.swift`. Ports the assertions of `FlagSchemaTests`, `DuplicateKeyTests` and the schema half of `FlagContainerDescriptionTests`. A small hand-written spec (`SchemaFixtures.kt`) stands in for a container, because nothing generates one yet.

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagLookup.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagContainerSpec.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagDescriptor.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagSchemaException.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/SchemaFixtures.kt`, `…/schema/FlagDescriptorTest.kt`, `…/schema/FlagSchemaExceptionTest.kt`, `…/schema/FlagSchemaTest.kt`, `…/schema/DuplicateKeyTest.kt`, `…/schema/FlagSchemaParseTest.kt`, `…/schema/FlagSchemaFileTest.kt` (all under `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/`)

**Interfaces:**
- Consumes (M1a):
  - `FlagKey`, `FlagKeyPath(propertyNames)` with `.appending`, `.prepending` and `ROOT`;
  - `KeyEncoding.key(path)`, `.Kebabcase` and `.Snakecase`;
  - `FlagValueType` with `.typeName` and `FlagValueType.parse(name)`;
  - `FlagValueBox` with `.containsNonFiniteNumber`;
  - `FlagCodec<T>` (`type`, `cases`, `recordShape`, `encode`), `FlagCodecs`, `FlagStringEnum`, `FlagRecordField`;
  - internal `JsonValue`, `JsonReader.parse`, `JsonSyntaxException`, `JsonWriter.pretty`, `IsoDate.format`/`parse`, `FlagValueBox.toJson()` and `JsonValue.toBox(type)`;
  - `FlagSerializationException.NonFiniteNumber(key)`, and the test object `Fixtures`.
- Produces:
  - `public interface FlagLookup { public val keyEncoding: KeyEncoding; public fun box(key: FlagKey, type: FlagValueType): FlagValueBox? }`
  - `public interface FlagContainerSpec<F : Any> { public val flagContainerDescription: String?; public val flagDescriptors: List<FlagSchemaNode>; public val flagDefaults: F; public fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath = FlagKeyPath.ROOT): F }`
  - `public data class FlagDescriptor(propertyName, keyPath, description, valueType, defaultValue, cases = null, remoteKey = null, recordShape = null)` with `companion object { public fun <T> of(propertyName: String, description: String, remoteKey: String?, codec: FlagCodec<T>, default: T): FlagDescriptor }`
  - `public data class FlagGroupDescriptor(propertyName, keyPath, description, children: List<FlagSchemaNode>)`
  - `public sealed interface FlagSchemaNode { propertyName; keyPath; fun prefixed(component: String): FlagSchemaNode; data class Flag(descriptor); data class Group(group) }`
  - `public fun List<FlagSchemaNode>.flattened(): List<FlagDescriptor>`
  - `public data class FlagSchema(formatVersion: Int = 1, generatedAt: Instant = Instant.now(), applicationName: String? = null, description: String? = null, flags: List<Entry>, groups: List<Group> = emptyList())`, with:
    - nested `Entry(key, propertyPath, description, valueType, defaultValue, cases = null, remoteKey = null, recordShape = null)` and `Group(propertyPath, description)`;
    - `duplicateKeys`, `duplicateKeyDescription`, `valueTypes`, `valueCases` and `recordShapes`;
    - `jsonBytes()` and `write(directory: File): File`;
    - `companion object`: `FILE_NAME`, `CURRENT_FORMAT_VERSION`, `of(spec, keyEncoding, applicationName: String? = null, generatedAt)`, `parse(bytes)` and `read(directory)`.
  - `public sealed class FlagSchemaException`, with `UnsupportedFormatVersion(version: Long)`, `Malformed(reason: String, cause: Throwable? = null)` and `NotPublished(cause: Throwable? = null)`

- [ ] **Step 1: Write the failing descriptor tests and the schema test fixtures**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/SchemaFixtures.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagStringEnum

/** A one-off spec for schema tests: only the shape matters, so there are no flags to read. */
internal fun specOf(vararg nodes: FlagSchemaNode, description: String? = null): FlagContainerSpec<Any> =
    object : FlagContainerSpec<Any> {
        override val flagContainerDescription: String? = description
        override val flagDescriptors: List<FlagSchemaNode> = nodes.toList()
        override val flagDefaults: Any = Unit
        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Unit
    }

/** A `@Flag`, written the way generated code writes it. */
internal fun <T> flag(
    name: String,
    description: String,
    codec: FlagCodec<T>,
    default: T,
    remoteKey: String? = null,
): FlagSchemaNode = FlagSchemaNode.Flag(FlagDescriptor.of(name, description, remoteKey, codec, default))

/** A `@FlagGroup`, written the way generated code writes it: its children re-rooted under it. */
internal fun group(name: String, description: String, vararg children: FlagSchemaNode): FlagSchemaNode =
    FlagSchemaNode.Group(
        FlagGroupDescriptor(name, FlagKeyPath(listOf(name)), description, children.map { it.prefixed(name) }),
    )

internal enum class DemoTier(override val flagRawValue: String) : FlagStringEnum { FREE("free"), PRO("pro") }

/** Swift's `DemoFlags` from `Tests/FeatureFlagTests/Fixtures.swift`. */
internal val demoSpec: FlagContainerSpec<Any> = specOf(
    flag("newOnboarding", "New onboarding", FlagCodecs.boolean, false, "featureToggles.onboarding.v2"),
    flag("maxItems", "Maximum items", FlagCodecs.int, 10),
    group(
        "checkout",
        "Checkout",
        flag("applePay", "Apple Pay", FlagCodecs.boolean, false, "featureToggles.checkout.applePay"),
        group("express", "Express", flag("oneTap", "One tap", FlagCodecs.boolean, false)),
        flag("tier", "Tier", FlagCodecs.stringEnum(DemoTier.entries), DemoTier.FREE),
    ),
)
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagDescriptorTest.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlagDescriptorTest {
    @Test fun ofCarriesEverythingAnEditorNeeds() {
        val descriptor = FlagDescriptor.of(
            propertyName = "newOnboarding",
            description = "New onboarding",
            remoteKey = "featureToggles.onboarding.v2",
            codec = FlagCodecs.boolean,
            default = false,
        )
        assertEquals("newOnboarding", descriptor.propertyName)
        assertEquals(FlagKeyPath(listOf("newOnboarding")), descriptor.keyPath)
        assertEquals("New onboarding", descriptor.description)
        assertEquals(FlagValueType.Bool, descriptor.valueType)
        assertEquals(FlagValueBox.Bool(false), descriptor.defaultValue)
        assertEquals("featureToggles.onboarding.v2", descriptor.remoteKey)
        assertNull(descriptor.cases)
        assertNull(descriptor.recordShape)
    }

    @Test fun enumDescriptorListsItsCases() {
        val descriptor = FlagDescriptor.of("tier", "Tier", null, FlagCodecs.stringEnum(DemoTier.entries), DemoTier.FREE)
        assertEquals(FlagValueType.String, descriptor.valueType)
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), descriptor.cases)
    }

    @Test fun descriptorsKeepDeclarationOrder() {
        assertEquals(listOf("newOnboarding", "maxItems", "checkout"), demoSpec.flagDescriptors.map { it.propertyName })
    }

    @Test fun groupRerootsItsChildrenThroughEveryLevel() {
        val checkout = assertIs<FlagSchemaNode.Group>(demoSpec.flagDescriptors[2]).group
        assertEquals(FlagKeyPath(listOf("checkout")), checkout.keyPath)
        val applePay = assertIs<FlagSchemaNode.Flag>(checkout.children[0]).descriptor
        assertEquals(FlagKeyPath(listOf("checkout", "applePay")), applePay.keyPath)
        val express = assertIs<FlagSchemaNode.Group>(checkout.children[1]).group
        val oneTap = assertIs<FlagSchemaNode.Flag>(express.children[0]).descriptor
        assertEquals(FlagKeyPath(listOf("checkout", "express", "oneTap")), oneTap.keyPath)
    }

    @Test fun prefixingANodeRerootsItAndKeepsItsPropertyName() {
        val express = group("express", "Express", flag("oneTap", "One tap", FlagCodecs.boolean, false))
        val node = express.prefixed("checkout")
        assertEquals("express", node.propertyName)
        assertEquals(FlagKeyPath(listOf("checkout", "express")), node.keyPath)
    }

    @Test fun flattenedIsDepthFirstWithCompletePaths() {
        assertEquals(
            listOf(
                listOf("newOnboarding"), listOf("maxItems"), listOf("checkout", "applePay"),
                listOf("checkout", "express", "oneTap"), listOf("checkout", "tier"),
            ),
            demoSpec.flagDescriptors.flattened().map { it.keyPath.propertyNames },
        )
    }

    @Test fun aGroupMountedTwiceReportsTwoKeys() {
        val oneTap = flag("oneTap", "One tap", FlagCodecs.boolean, false)
        val spec = specOf(group("first", "First", oneTap), group("second", "Second", oneTap))
        assertEquals(
            listOf(listOf("first", "oneTap"), listOf("second", "oneTap")),
            spec.flagDescriptors.flattened().map { it.keyPath.propertyNames },
        )
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.schema.*' --console=plain
```

Expected: `BUILD FAILED` at `:featureflag-core:compileTestKotlin`, with `Unresolved reference 'FlagContainerSpec'`, `Unresolved reference 'FlagSchemaNode'` and `Unresolved reference 'FlagDescriptor'`.

- [ ] **Step 3: Write the lookup and spec interfaces and the descriptors**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagLookup.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding

/** Supplies flag values to a container tree. A `FlagPole` is the production implementation. */
public interface FlagLookup {
    /** How property-name paths become the keys this lookup stores values under. */
    public val keyEncoding: KeyEncoding

    /** The value for [key], or null if nothing supplies one. [type] is the flag's declared type. */
    public fun box(key: FlagKey, type: FlagValueType): FlagValueBox?
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagContainerSpec.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagKeyPath

/**
 * What the processor generates for each `@FlagContainer` interface `F`, as the object `FSpec`.
 * Everything a pole, a schema or a companion needs, without reflection.
 */
public interface FlagContainerSpec<F : Any> {
    /** What this set of flags is for, from `@FlagContainer(description = …)`; null when it says nothing. */
    public val flagContainerDescription: String?

    /** The container's shape, rooted at the container itself. */
    public val flagDescriptors: List<FlagSchemaNode>

    /** An instance whose every flag reads its compiled default. */
    public val flagDefaults: F

    /** An instance whose flags read through [lookup], with every key rooted at [prefix]. */
    public fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath = FlagKeyPath.ROOT): F
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagDescriptor.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType

/**
 * Everything about one flag that an editor or a remote mapper needs, available without an
 * instance of the container that declares it.
 */
public data class FlagDescriptor(
    public val propertyName: String,
    public val keyPath: FlagKeyPath,
    public val description: String,
    public val valueType: FlagValueType,
    public val defaultValue: FlagValueBox,
    public val cases: List<FlagValueBox>? = null,
    public val remoteKey: String? = null,
    public val recordShape: List<FlagRecordField>? = null,
) {
    internal fun prefixed(component: String): FlagDescriptor = copy(keyPath = keyPath.prepending(component))

    public companion object {
        /** The descriptor generated code writes for one `@Flag`, rooted at the container. */
        public fun <T> of(
            propertyName: String,
            description: String,
            remoteKey: String?,
            codec: FlagCodec<T>,
            default: T,
        ): FlagDescriptor = FlagDescriptor(
            propertyName = propertyName,
            keyPath = FlagKeyPath(listOf(propertyName)),
            description = description,
            valueType = codec.type,
            defaultValue = codec.encode(default),
            cases = codec.cases,
            remoteKey = remoteKey,
            recordShape = codec.recordShape,
        )
    }
}

/** A nested container, which namespaces its children and nothing more. */
public data class FlagGroupDescriptor(
    public val propertyName: String,
    public val keyPath: FlagKeyPath,
    public val description: String,
    public val children: List<FlagSchemaNode>,
) {
    internal fun prefixed(component: String): FlagGroupDescriptor = FlagGroupDescriptor(
        propertyName = propertyName,
        keyPath = keyPath.prepending(component),
        description = description,
        children = children.map { it.prefixed(component) },
    )
}

/** One entry in a container's shape: either a flag or a nested group. */
public sealed interface FlagSchemaNode {
    public val propertyName: String
    public val keyPath: FlagKeyPath

    /** Re-roots this node, and everything beneath it, under [component]. */
    public fun prefixed(component: String): FlagSchemaNode

    public data class Flag(public val descriptor: FlagDescriptor) : FlagSchemaNode {
        override val propertyName: String get() = descriptor.propertyName
        override val keyPath: FlagKeyPath get() = descriptor.keyPath
        override fun prefixed(component: String): FlagSchemaNode = Flag(descriptor.prefixed(component))
    }

    public data class Group(public val group: FlagGroupDescriptor) : FlagSchemaNode {
        override val propertyName: String get() = group.propertyName
        override val keyPath: FlagKeyPath get() = group.keyPath
        override fun prefixed(component: String): FlagSchemaNode = Group(group.prefixed(component))
    }
}

/** Every flag in the tree, depth first, in declaration order, with complete key paths. */
public fun List<FlagSchemaNode>.flattened(): List<FlagDescriptor> = flatMap { node ->
    when (node) {
        is FlagSchemaNode.Flag -> listOf(node.descriptor)
        is FlagSchemaNode.Group -> node.group.children.flattened()
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.schema.*' --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times, and `FlagDescriptorTest` passes all 7 tests.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagLookup.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagContainerSpec.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagDescriptor.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/SchemaFixtures.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagDescriptorTest.kt
git commit -m "$(cat <<'EOF'
Add flag descriptors, schema nodes and the container spec interfaces

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Write the failing schema tests**

These cover:
- the Swift ports (keys, editor metadata, remote keys, groups, round trip, unknown version);
- the duplicate-key rules: a hand-built schema stays inspectable, `of(spec)` fails at once, and a published document is malformed;
- the forward-compatibility rules, which are listed in `FlagSchemaParseTest`;
- omitting rather than nulling absent optionals;
- atomic publishing, and the three message fixtures.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaExceptionTest.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.errors.FlagSchemaException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Messages ported verbatim from ErrorMessages.swift, pinned against the text Swift itself produced. */
class FlagSchemaExceptionTest {
    @Test fun anUnsupportedVersionSaysWhichAppToUpdate() = assertEquals(
        Fixtures.text("messages/FlagSchemaError.unsupportedFormatVersion.txt"),
        FlagSchemaException.UnsupportedFormatVersion(9).message,
    )

    @Test fun aMalformedSchemaSaysWhy() = assertEquals(
        Fixtures.text("messages/FlagSchemaError.malformed.txt"),
        FlagSchemaException.Malformed("missing formatVersion").message,
    )

    @Test fun notPublishedDropsOnlyTheAppGroupClause() {
        val swift = Fixtures.text("messages/FlagSchemaError.notPublished.txt")
        val kotlin = FlagSchemaException.NotPublished().message.orEmpty()
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            kotlin,
        )
        assertTrue("App Group" in swift, swift)
        assertEquals(swift.substringBefore(" — and check"), kotlin.removeSuffix("."))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaTest.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagSchemaTest {
    private val exotic = specOf(
        flag("tags", "Tags", FlagCodecs.list(FlagCodecs.string), listOf("a")),
        flag("launchedAt", "Launched at", FlagCodecs.instant, Instant.EPOCH),
        flag("limits", "Limits", FlagCodecs.map(FlagCodecs.int), emptyMap()),
    )

    private fun text(schema: FlagSchema) = schema.jsonBytes().toString(Charsets.UTF_8)

    @Test fun schemaListsEveryFlagWithResolvedKeys() {
        assertEquals(
            listOf("new-onboarding", "max-items", "checkout.apple-pay", "checkout.express.one-tap", "checkout.tier"),
            FlagSchema.of(demoSpec).flags.map { it.key.rawValue },
        )
    }

    @Test fun schemaUsesTheGivenKeyEncoding() {
        val schema = FlagSchema.of(demoSpec, keyEncoding = KeyEncoding.Snakecase)
        assertTrue(schema.flags.any { it.key == FlagKey("checkout.apple_pay") })
    }

    @Test fun schemaCarriesEditorMetadata() {
        val tier = FlagSchema.of(demoSpec).flags.single { it.key == FlagKey("checkout.tier") }
        assertEquals("Tier", tier.description)
        assertEquals(FlagValueType.String, tier.valueType)
        assertEquals(FlagValueBox.String("free"), tier.defaultValue)
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), tier.cases)
        assertEquals(listOf("checkout", "tier"), tier.propertyPath)
    }

    @Test fun schemaCarriesRemoteKeysWhereDeclared() {
        val flags = FlagSchema.of(demoSpec).flags
        assertEquals("featureToggles.onboarding.v2", flags.single { it.key == FlagKey("new-onboarding") }.remoteKey)
        assertNull(flags.single { it.key == FlagKey("max-items") }.remoteKey)
    }

    @Test fun schemaDescribesGroupsSoTheEditorCanLabelSections() {
        assertEquals(
            listOf(
                FlagSchema.Group(listOf("checkout"), "Checkout"),
                FlagSchema.Group(listOf("checkout", "express"), "Express"),
            ),
            FlagSchema.of(demoSpec).groups,
        )
    }

    @Test fun schemaCarriesTheContainerDescription() {
        val described = specOf(
            flag("newOnboarding", "Onboarding", FlagCodecs.boolean, false),
            description = "Everything the checkout team can turn on",
        )
        assertEquals("Everything the checkout team can turn on", FlagSchema.of(described).description)
        val reread = FlagSchema.parse(FlagSchema.of(described).jsonBytes())
        assertEquals("Everything the checkout team can turn on", reread.description)
        assertNull(FlagSchema.parse(FlagSchema.of(demoSpec).jsonBytes()).description)
        assertEquals(listOf(FlagKey("new-onboarding")), FlagSchema.of(described).flags.map { it.key })
    }

    @Test fun schemaRoundTripsThroughJson() {
        val generatedAt = Instant.ofEpochSecond(1_767_225_600)
        val schema = FlagSchema.of(demoSpec, applicationName = "Demo", generatedAt = generatedAt)
        val decoded = FlagSchema.parse(schema.jsonBytes())
        assertEquals(schema, decoded)
        assertEquals("Demo", decoded.applicationName)
    }

    @Test fun collectionAndDateTypesSurviveTheRoundTrip() {
        val schema = FlagSchema.of(exotic)
        val decoded = FlagSchema.parse(schema.jsonBytes())
        assertEquals(schema.flags, decoded.flags)
        val tags = decoded.flags.single { it.key == FlagKey("tags") }
        assertEquals(FlagValueType.Array(FlagValueType.String), tags.valueType)
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.String("a"))), tags.defaultValue)
        assertEquals(FlagValueType.Date, decoded.flags.single { it.key == FlagKey("launched-at") }.valueType)
    }

    @Test fun absentOptionalsAreOmittedNotWrittenAsNull() {
        val json = text(FlagSchema.of(demoSpec, generatedAt = Instant.EPOCH))
        assertFalse("applicationName" in json, json)
        assertFalse("\"description\" : null" in json, json)
        assertFalse("null" in json, json)
        assertTrue("\"formatVersion\" : 1" in json, json)
        assertTrue("\"generatedAt\" : \"1970-01-01T00:00:00Z\"" in json, json)
    }

    @Test fun aNonFiniteDefaultIsRefusedNamingTheFlag() {
        val schema = FlagSchema.of(specOf(flag("ratio", "Ratio", FlagCodecs.double, Double.NaN)))
        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { schema.jsonBytes() }
        assertEquals(FlagKey("ratio"), error.key)
    }

    @Test fun decodingRejectsAnUnknownFormatVersion() {
        val json = text(FlagSchema.of(demoSpec)).replace("\"formatVersion\" : 1", "\"formatVersion\" : 99")
        val error = assertFailsWith<FlagSchemaException.UnsupportedFormatVersion> {
            FlagSchema.parse(json.toByteArray())
        }
        assertEquals(99L, error.version)
        assertEquals(
            "The schema is format version 99; this build understands 1. Update whichever app is older.",
            error.message,
        )
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/DuplicateKeyTest.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two property names that differ only in an acronym's case collapse to one kebabcase key. */
class DuplicateKeyTest {
    private fun entry(path: String, description: String) = FlagSchema.Entry(
        key = FlagKey("use-https-only"),
        propertyPath = listOf(path),
        description = description,
        valueType = FlagValueType.Bool,
        defaultValue = FlagValueBox.Bool(false),
    )

    /** Built by hand, so a collision can be inspected rather than only crashed into. */
    private val colliding = FlagSchema(flags = listOf(entry("useHTTPSOnly", "A"), entry("useHttpsOnly", "B")))

    private val duplicateFree = specOf(
        flag("oneTap", "One", FlagCodecs.boolean, false),
        flag("useHTTPSOnly", "Two", FlagCodecs.boolean, false),
    )

    @Test fun aCollisionIsReportedWithEveryPropertyThatCausedIt() {
        assertEquals(
            mapOf(FlagKey("use-https-only") to listOf(listOf("useHTTPSOnly"), listOf("useHttpsOnly"))),
            colliding.duplicateKeys,
        )
    }

    @Test fun aHealthySchemaReportsNoDuplicates() {
        assertTrue(FlagSchema.of(duplicateFree).duplicateKeys.isEmpty())
        assertNull(FlagSchema.of(duplicateFree).duplicateKeyDescription)
    }

    @Test fun theReportNamesThePropertyPathsNotJustTheKey() {
        assertEquals(
            "'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly",
            colliding.duplicateKeyDescription,
        )
    }

    @Test fun valueTypesAndCasesDoNotTrapOnACollision() {
        assertEquals(FlagValueType.Bool, colliding.valueTypes[FlagKey("use-https-only")])
        assertTrue(colliding.valueCases.isEmpty())
        assertTrue(colliding.recordShapes.isEmpty())
    }

    @Test fun describingAContainerWithACollisionFailsAtOnce() {
        val spec = specOf(
            flag("useHTTPSOnly", "A", FlagCodecs.boolean, false),
            flag("useHttpsOnly", "B", FlagCodecs.boolean, false),
        )
        val error = assertFailsWith<IllegalStateException> { FlagSchema.of(spec) }
        assertEquals(
            "Flags must resolve to unique keys, but 'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly. " +
                "Rename one of the properties, or use a KeyEncoding that tells them apart.",
            error.message,
        )
    }

    @Test fun aPublishedSchemaWithADuplicateKeyIsRejected() {
        val json = """
            {"formatVersion":1,"flags":[
              {"key":"a","valueType":"bool","defaultValue":false},
              {"key":"a","valueType":"bool","defaultValue":true}
            ]}
        """.trimIndent()
        val error = assertFailsWith<FlagSchemaException.Malformed> { FlagSchema.parse(json.toByteArray()) }
        assertEquals("The schema could not be read: more than one flag uses the key 'a'.", error.message)
    }

    @Test fun aPublishedSchemaWithoutDuplicatesStillDecodes() {
        assertEquals(2, FlagSchema.parse(FlagSchema.of(duplicateFree).jsonBytes()).flags.size)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaParseTest.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What a companion does with a document from an older or newer host. */
class FlagSchemaParseTest {
    private fun parse(json: String) = FlagSchema.parse(json.trimIndent().toByteArray())

    private fun malformed(json: String) = assertFailsWith<FlagSchemaException.Malformed> { parse(json) }.message

    @Test fun theSmallestDocumentFillsEveryGap() {
        val schema = parse("""{"formatVersion":1,"flags":[{"key":"one","valueType":"bool","defaultValue":false}]}""")
        val one = schema.flags.single()
        assertEquals(listOf("one"), one.propertyPath)
        assertEquals("", one.description)
        assertEquals(Instant.EPOCH, schema.generatedAt)
        assertNull(schema.applicationName)
        assertEquals(emptyList(), schema.groups)
    }

    @Test fun aDocumentFromBeforeDescriptionsExistedStillDecodes() {
        val schema = parse(
            """
            {
              "formatVersion": 1,
              "generatedAt": "2026-01-01T00:00:00.000Z",
              "flags": [
                { "key": "one", "propertyPath": ["one"], "description": "One",
                  "valueType": "bool", "defaultValue": false }
              ],
              "groups": []
            }
            """,
        )
        assertNull(schema.description)
        assertEquals(1, schema.flags.size)
    }

    @Test fun unknownFieldsAreIgnoredEverywhere() {
        val schema = parse(
            """
            {"formatVersion":1,"future":{"x":1},"flags":[{"key":"a","valueType":"bool","defaultValue":true,"hint":"new",
              "recordShape":[{"name":"n","type":"string","later":true}]}],
             "groups":[{"propertyPath":["g"],"description":"G","colour":"red"}]}
            """,
        )
        assertEquals(listOf(FlagRecordField("n", FlagValueType.String)), schema.flags.single().recordShape)
        assertEquals(listOf(FlagSchema.Group(listOf("g"), "G")), schema.groups)
    }

    @Test fun whatCannotBeDecodedInsideAFlagIsDropped() {
        val schema = parse(
            """
            {"formatVersion":1,"flags":[{"key":"t","valueType":"string","defaultValue":"a",
              "cases":["a", 2, "b"],
              "recordShape":[{"name":"ok","type":"int","default":"wrong"},{"name":"bad","type":"vector"},
                {"type":"int"}]}],
             "groups":[{"description":"no path"},{"propertyPath":["kept"]}]}
            """,
        )
        val flag = schema.flags.single()
        assertEquals(listOf(FlagValueBox.String("a"), FlagValueBox.String("b")), flag.cases)
        assertEquals(listOf(FlagRecordField("ok", FlagValueType.Int)), flag.recordShape)
        assertEquals(listOf(FlagSchema.Group(listOf("kept"), "")), schema.groups)
    }

    @Test fun aListHoldingSomethingOtherThanObjectsIsDroppedWhole() {
        val schema = parse(
            """
            {"formatVersion":1,"flags":[{"key":"t","valueType":"string","defaultValue":"a","propertyPath":["x", 1],
              "recordShape":[{"name":"ok","type":"int"}, 3]}],"groups":[{"propertyPath":["g"]}, "g"]}
            """,
        )
        assertEquals(listOf("t"), schema.flags.single().propertyPath)
        assertNull(schema.flags.single().recordShape)
        assertEquals(emptyList(), schema.groups)
    }

    @Test fun nestedRecordShapesAndFlagsRoundTrip() {
        val schema = parse(
            """
            {"formatVersion":1,"flags":[{"key":"e","valueType":"string","defaultValue":"[]","recordShape":[
              {"name":"name","type":"string","isKey":true},
              {"name":"note","type":"string","isOptional":true,"decodedName":"memo"},
              {"name":"targets","type":"string","default":"[]","fields":[{"name":"port","type":"int"}]}]}]}
            """,
        )
        val shape = schema.flags.single().recordShape!!
        assertEquals(true, shape[0].isKey)
        assertEquals(true, shape[1].isOptional)
        assertEquals("memo", shape[1].decodedName)
        assertEquals(listOf(FlagRecordField("port", FlagValueType.Int)), shape[2].fields)
        assertEquals(schema, FlagSchema.parse(schema.jsonBytes()))
    }

    @Test fun anUnknownValueTypeMakesTheDocumentMalformed() {
        assertEquals(
            "The schema could not be read: flag entry is missing a key or value type.",
            malformed("""{"formatVersion":1,"flags":[{"key":"a","valueType":"vector","defaultValue":[]}]}"""),
        )
        assertEquals(
            "The schema could not be read: flag entry is missing a key or value type.",
            malformed("""{"formatVersion":1,"flags":[{"valueType":"bool","defaultValue":true}]}"""),
        )
    }

    @Test fun aMistypedDefaultMakesTheDocumentMalformed() {
        assertEquals(
            "The schema could not be read: flag 'a' has a default that is not a bool.",
            malformed("""{"formatVersion":1,"flags":[{"key":"a","valueType":"bool","defaultValue":"yes"}]}"""),
        )
        assertEquals(
            "The schema could not be read: flag 'a' has a default that is not a int.",
            malformed("""{"formatVersion":1,"flags":[{"key":"a","valueType":"int"}]}"""),
        )
    }

    @Test fun theDocumentItselfMustBeAnObjectWithAVersionAndFlags() {
        assertEquals("The schema could not be read: not a JSON object.", malformed("""[1, 2]"""))
        assertEquals("The schema could not be read: not a JSON object.", malformed("""{"formatVersion":"""))
        assertEquals("The schema could not be read: missing formatVersion.", malformed("""{"flags":[]}"""))
        assertEquals(
            "The schema could not be read: missing formatVersion.",
            malformed("""{"formatVersion":"1","flags":[]}"""),
        )
        assertEquals("The schema could not be read: missing flags.", malformed("""{"formatVersion":1}"""))
        assertEquals("The schema could not be read: missing flags.", malformed("""{"formatVersion":1,"flags":[1]}"""))
    }

    @Test fun anEmptyFlagListIsAValidSchema() {
        assertEquals(emptyList(), parse("""{"formatVersion":1,"flags":[]}""").flags)
        val one = parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"bool","defaultValue":true}]}""")
        assertEquals(FlagKey("a"), one.flags.single().key)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema/FlagSchemaFileTest.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun readingReportsAMissingSchemaClearly() {
        val error = assertFailsWith<FlagSchemaException.NotPublished> { FlagSchema.read(directory) }
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            error.message,
        )
    }
}
```

- [ ] **Step 7: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.schema.*' --console=plain
```

Expected: `BUILD FAILED` at `:featureflag-core:compileTestKotlin`, with `Unresolved reference 'FlagSchema'` and `Unresolved reference 'FlagSchemaException'`.

- [ ] **Step 8: Write the exception and the schema**

The forward-compatibility rules follow `FlagSchema.init(jsonObject:)` exactly:
- **Ignored:** unknown fields.
- **Malformed:**
  - a top level that is not an object;
  - a `formatVersion` that is missing or not an integer;
  - a `flags` that is missing or holds a non-object;
  - an entry without a string `key`, or with an unknown `valueType`;
  - a `defaultValue` that is missing or mistyped;
  - two entries sharing a key (the alphabetically first is named).
- **Unsupported:** a `formatVersion` other than 1.
- **Dropped:** undecodable `cases`, record fields that fail to read, and groups without a string-array `propertyPath`. A `recordShape` or `groups` array holding any non-object is dropped whole, as Swift's `as? [[String: Any]]` is.
- **Defaulted:** a missing `propertyPath` becomes `[key]`, a missing `description` becomes `""`, and a missing or unparseable `generatedAt` becomes the epoch.

Writing replaces the file with a rename from a temporary file in the same directory. On POSIX that rename is atomic.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagSchemaException.kt`:

```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.schema.FlagSchema

/** Why a published schema could not be used. Messages are ported from Swift's `FlagSchemaError`. */
public sealed class FlagSchemaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** The document is from a newer (or older) build than this one understands. */
    public class UnsupportedFormatVersion(public val version: Long) : FlagSchemaException(
        "The schema is format version $version; this build understands ${FlagSchema.CURRENT_FORMAT_VERSION}. " +
            "Update whichever app is older.",
    )

    /** The document is not a schema at all, or one this build cannot trust. */
    public class Malformed(public val reason: String, cause: Throwable? = null) :
        FlagSchemaException("The schema could not be read: $reason.", cause)

    /** Nothing has been written where the schema should be. */
    public class NotPublished(cause: Throwable? = null) : FlagSchemaException(
        "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
        cause,
    )
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt`:

```kotlin
package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import com.andyyhope.featureflag.values.toBox
import com.andyyhope.featureflag.values.toJson
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * A description of a host app's flag tree, complete enough for a companion app that has never seen
 * the host's types to render an editor for it. Byte-compatible with Swift's `FlagSchema` JSON.
 */
public data class FlagSchema(
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
    public val generatedAt: Instant = Instant.now(),
    /** Which app published this, for a companion that can see more than one. */
    public val applicationName: String? = null,
    /** What this set of flags is for, as the container describes itself. */
    public val description: String? = null,
    /** Every flag, depth first, in declaration order. */
    public val flags: List<Entry>,
    /** Every group, so the editor can label its sections. */
    public val groups: List<Group> = emptyList(),
) {
    /** One flag, keyed as it is stored. */
    public data class Entry(
        public val key: FlagKey,
        public val propertyPath: List<String>,
        public val description: String,
        public val valueType: FlagValueType,
        public val defaultValue: FlagValueBox,
        public val cases: List<FlagValueBox>? = null,
        public val remoteKey: String? = null,
        public val recordShape: List<FlagRecordField>? = null,
    )

    /** One nested container. Groups namespace their children and nothing else. */
    public data class Group(public val propertyPath: List<String>, public val description: String)

    /** Flags that resolve to the same key, mapped to the property paths that produced them. */
    public val duplicateKeys: Map<FlagKey, List<List<String>>> get() = duplicateKeys(flags)

    /** Every collision as one readable clause, or null when there are none. */
    public val duplicateKeyDescription: String?
        get() = duplicateKeys.takeIf { it.isNotEmpty() }
            ?.entries
            ?.sortedBy { it.key.rawValue }
            ?.joinToString("; ") { (key, paths) ->
                "'${key.rawValue}' is claimed by ${paths.joinToString(" and ") { it.joinToString(".") }}"
            }

    /** The declared type of every flag. Tolerates a duplicated key: the first wins. */
    public val valueTypes: Map<FlagKey, FlagValueType>
        get() = buildMap { flags.forEach { putIfAbsent(it.key, it.valueType) } }

    /** The permitted values of every enum flag. Flags that are not enums are absent. */
    public val valueCases: Map<FlagKey, List<FlagValueBox>>
        get() = buildMap {
            flags.forEach { entry -> entry.cases?.takeIf { it.isNotEmpty() }?.let { putIfAbsent(entry.key, it) } }
        }

    /** The record shape of every record flag. Flags that hold no records are absent. */
    public val recordShapes: Map<FlagKey, List<FlagRecordField>>
        get() = buildMap {
            flags.forEach { entry -> entry.recordShape?.takeIf { it.isNotEmpty() }?.let { putIfAbsent(entry.key, it) } }
        }

    /** The document, exactly as Apple's `JSONSerialization` writes it. */
    public fun jsonBytes(): ByteArray {
        // A non-finite default has no JSON spelling; name the flag rather than fail somewhere deeper.
        flags.firstOrNull { it.defaultValue.containsNonFiniteNumber }?.let {
            throw FlagSerializationException.NonFiniteNumber(it.key)
        }
        return JsonWriter.pretty(toJson()).toByteArray(Charsets.UTF_8)
    }

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

    private fun toJson(): JsonValue = JsonValue.Object(
        buildMap {
            put("formatVersion", JsonValue.Integer(formatVersion.toLong()))
            put("generatedAt", JsonValue.Text(IsoDate.format(generatedAt)))
            put("flags", JsonValue.Array(flags.map { it.toJson() }))
            put("groups", JsonValue.Array(groups.map { it.toJson() }))
            applicationName?.let { put("applicationName", JsonValue.Text(it)) }
            description?.let { put("description", JsonValue.Text(it)) }
        },
    )

    public companion object {
        public const val FILE_NAME: String = "flag-schema.json"
        public const val CURRENT_FORMAT_VERSION: Int = 1

        /**
         * Describes [spec] without building it. Two flags that encode to one key is a declaration
         * mistake with no runtime remedy, so it fails here, on the first launch of the build that has it.
         */
        public fun of(
            spec: FlagContainerSpec<*>,
            keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
            applicationName: String? = null,
            generatedAt: Instant = Instant.now(),
        ): FlagSchema {
            val flags = mutableListOf<Entry>()
            val groups = mutableListOf<Group>()
            collect(spec.flagDescriptors, keyEncoding, flags, groups)
            val schema = FlagSchema(
                generatedAt = generatedAt,
                applicationName = applicationName,
                description = spec.flagContainerDescription,
                flags = flags,
                groups = groups,
            )
            check(schema.duplicateKeyDescription == null) {
                "Flags must resolve to unique keys, but ${schema.duplicateKeyDescription}. " +
                    "Rename one of the properties, or use a KeyEncoding that tells them apart."
            }
            return schema
        }

        /** Reads a published document. Unknown fields are ignored; what cannot be trusted is refused. */
        public fun parse(bytes: ByteArray): FlagSchema {
            val root = try {
                JsonReader.parse(bytes)
            } catch (e: JsonSyntaxException) {
                malformed("not a JSON object", e)
            }
            val fields = (root as? JsonValue.Object)?.fields ?: malformed("not a JSON object")
            val version = (fields["formatVersion"] as? JsonValue.Integer)?.value ?: malformed("missing formatVersion")
            if (version != CURRENT_FORMAT_VERSION.toLong()) throw FlagSchemaException.UnsupportedFormatVersion(version)
            val entries = (objects(fields["flags"]) ?: malformed("missing flags")).map(::entry)
            duplicateKeys(entries).keys.minByOrNull { it.rawValue }?.let {
                malformed("more than one flag uses the key '$it'")
            }
            return FlagSchema(
                formatVersion = version.toInt(),
                generatedAt = text(fields["generatedAt"])?.let(IsoDate::parse) ?: Instant.EPOCH,
                applicationName = text(fields["applicationName"]),
                description = text(fields["description"]),
                flags = entries,
                groups = objects(fields["groups"]).orEmpty().mapNotNull(::group),
            )
        }

        /** Reads the schema a host published into [directory]. */
        public fun read(directory: File): FlagSchema {
            val bytes = try {
                File(directory, FILE_NAME).readBytes()
            } catch (e: IOException) {
                throw FlagSchemaException.NotPublished(e)
            }
            return parse(bytes)
        }

        private fun collect(
            nodes: List<FlagSchemaNode>,
            encoding: KeyEncoding,
            flags: MutableList<Entry>,
            groups: MutableList<Group>,
        ) {
            nodes.forEach { node ->
                when (node) {
                    is FlagSchemaNode.Flag -> with(node.descriptor) {
                        flags += Entry(
                            key = encoding.key(keyPath),
                            propertyPath = keyPath.propertyNames,
                            description = description,
                            valueType = valueType,
                            defaultValue = defaultValue,
                            cases = cases,
                            remoteKey = remoteKey,
                            recordShape = recordShape,
                        )
                    }
                    is FlagSchemaNode.Group -> {
                        groups += Group(node.group.keyPath.propertyNames, node.group.description)
                        collect(node.group.children, encoding, flags, groups)
                    }
                }
            }
        }

        private fun duplicateKeys(flags: List<Entry>): Map<FlagKey, List<List<String>>> =
            flags.groupBy({ it.key }, { it.propertyPath }).filterValues { it.size > 1 }

        private fun entry(fields: Map<String, JsonValue>): Entry {
            val key = text(fields["key"])
            val typeName = text(fields["valueType"])
            val type = typeName?.let(FlagValueType::parse)
            if (key == null || typeName == null || type == null) malformed("flag entry is missing a key or value type")
            val default = fields["defaultValue"]?.toBox(type) ?: malformed("flag '$key' has a default that is not a $typeName")
            return Entry(
                key = FlagKey(key),
                propertyPath = strings(fields["propertyPath"]) ?: listOf(key),
                description = text(fields["description"]) ?: "",
                valueType = type,
                defaultValue = default,
                cases = (fields["cases"] as? JsonValue.Array)?.items?.mapNotNull { it.toBox(type) },
                remoteKey = text(fields["remoteKey"]),
                recordShape = objects(fields["recordShape"])?.mapNotNull(::recordField),
            )
        }

        private fun recordField(fields: Map<String, JsonValue>): FlagRecordField? {
            val name = text(fields["name"]) ?: return null
            val type = text(fields["type"])?.let(FlagValueType::parse) ?: return null
            return FlagRecordField(
                name = name,
                type = type,
                cases = (fields["cases"] as? JsonValue.Array)?.items?.mapNotNull { it.toBox(type) },
                defaultValue = fields["default"]?.toBox(type),
                fields = objects(fields["fields"])?.mapNotNull(::recordField),
                isKey = (fields["isKey"] as? JsonValue.Bool)?.value ?: false,
                isOptional = (fields["isOptional"] as? JsonValue.Bool)?.value ?: false,
                decodedName = text(fields["decodedName"]),
            )
        }

        private fun group(fields: Map<String, JsonValue>): Group? {
            val path = strings(fields["propertyPath"]) ?: return null
            return Group(path, text(fields["description"]) ?: "")
        }

        private fun malformed(reason: String, cause: Throwable? = null): Nothing =
            throw FlagSchemaException.Malformed(reason, cause)

        private fun text(value: JsonValue?): String? = (value as? JsonValue.Text)?.value

        /** Every item a string, or null: a list with anything else in it is not a list of strings. */
        private fun strings(value: JsonValue?): List<String>? =
            (value as? JsonValue.Array)?.items?.map { text(it) ?: return null }

        /** Every item an object, or null, as Swift's `as? [[String: Any]]` does. */
        private fun objects(value: JsonValue?): List<Map<String, JsonValue>>? =
            (value as? JsonValue.Array)?.items?.map { (it as? JsonValue.Object)?.fields ?: return null }
    }
}

private fun FlagSchema.Entry.toJson(): JsonValue = JsonValue.Object(
    buildMap {
        put("key", JsonValue.Text(key.rawValue))
        put("propertyPath", JsonValue.Array(propertyPath.map { JsonValue.Text(it) }))
        put("description", JsonValue.Text(description))
        put("valueType", JsonValue.Text(valueType.typeName))
        put("defaultValue", defaultValue.toJson())
        cases?.let { cases -> put("cases", JsonValue.Array(cases.map { it.toJson() })) }
        remoteKey?.let { put("remoteKey", JsonValue.Text(it)) }
        recordShape?.let { shape -> put("recordShape", JsonValue.Array(shape.map { it.toJson() })) }
    },
)

/** `isKey` and `isOptional` are written only when set, so an older shape reads the same. */
private fun FlagRecordField.toJson(): JsonValue = JsonValue.Object(
    buildMap {
        put("name", JsonValue.Text(name))
        put("type", JsonValue.Text(type.typeName))
        cases?.let { cases -> put("cases", JsonValue.Array(cases.map { it.toJson() })) }
        defaultValue?.let { put("default", it.toJson()) }
        fields?.let { fields -> put("fields", JsonValue.Array(fields.map { it.toJson() })) }
        if (isKey) put("isKey", JsonValue.Bool(true))
        if (isOptional) put("isOptional", JsonValue.Bool(true))
        decodedName?.let { put("decodedName", JsonValue.Text(it)) }
    },
)

private fun FlagSchema.Group.toJson(): JsonValue = JsonValue.Object(
    mapOf(
        "propertyPath" to JsonValue.Array(propertyPath.map { JsonValue.Text(it) }),
        "description" to JsonValue.Text(description),
    ),
)
```

- [ ] **Step 9: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.schema.*' --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. The schema package passes 42 tests: `FlagDescriptorTest` 7, `FlagSchemaExceptionTest` 3, `FlagSchemaTest` 11, `DuplicateKeyTest` 7, `FlagSchemaParseTest` 10 and `FlagSchemaFileTest` 4.

- [ ] **Step 10: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagSchemaException.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/schema
git commit -m "$(cat <<'EOF'
Add FlagSchema with Apple-identical JSON, forward-compatible parsing and atomic publishing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task B2: `FlagRef`, `FlagLookup.read`, the annotations and the hand-written golden spec

`golden/GoldenSpecs.kt` is written by hand in **exactly** the shape `featureflag-ksp` generates. Compare it with `featureflag-ksp/src/test/resources/expansions/AppFlagsSpec.kt.txt` in Task B3. The members, names and expressions are the same; only formatting, explicit `public` modifiers and KotlinPoet's `kotlin.*` imports differ. Task B3 deletes it and lets the processor write it. Every test here must stay green across that swap. `FlagContainerTest` ports `FlagContainerTests` and `FlagContainerMacroTests` onto the golden container, with a `SnapshotSource`-free stub lookup.

The generated shape, for a container `X`:
- **`open class XRefs(prefix: FlagKeyPath)`:** one `val name: FlagRef<T> = FlagRef(prefix.appending("name"), "name", description, remoteKey, codec) { XSpec.flagDefaults.name }` per flag, and one `val group: GRefs = GRefs(prefix.appending("group"))` per group.
- **`object XSpec : XRefs(FlagKeyPath.ROOT), FlagContainerSpec<X>`**, which holds:
  - `flagContainerDescription`;
  - `flagDefaults`, an `object : X` overriding only the groups;
  - `flagDescriptors`, a `lazy` list of `FlagSchemaNode.Flag(FlagDescriptor.of(…, name.codec, flagDefaults.name))` and `FlagSchemaNode.Group(FlagGroupDescriptor(…, GSpec.flagDescriptors.map { it.prefixed("group") }))`;
  - `createFlags(lookup, prefix) = Impl(lookup, prefix)`.
- **`private class Impl`:** reads `flagLookup.read(flagKeys.name, XSpec.name.codec) { super<X>.name }`. It builds each group with `GSpec.createFlags(flagLookup, flagPrefix.appending("group"))`.
- **`private class Keys`:** encodes each key once per instance.

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagLookup.kt` (adds `read`)
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagRef.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/Annotations.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenFlags.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSpecs.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagLookupTest.kt`, `…/container/FlagRefTest.kt`, `…/container/FlagContainerTest.kt`, `…/golden/GoldenSchemaTest.kt`

**Interfaces:**
- Consumes:
  - from B1: `FlagLookup`, `FlagContainerSpec`, `FlagDescriptor.of`, `FlagGroupDescriptor`, `FlagSchemaNode`, `FlagSchema.of`/`parse`/`jsonBytes`;
  - from M1a: `FlagCodecs`, and in the test sources `GoldenTier`, `GoldenLevel`, `GoldenEndpoint`, `GoldenTarget`, `GoldenEndpointFlagRecord` and `Fixtures`.
- Produces:
  - `public fun <T> FlagLookup.read(key: FlagKey, codec: FlagCodec<T>, default: () -> T): T`. It is deliberately not `inline` (see measured fact (d)).
  - `public class FlagRef<T>(keyPath: FlagKeyPath, propertyName: String, description: String, remoteKey: String?, codec: FlagCodec<T>, defaultProvider: () -> T)`, with `val defaultValue: T` (lazy) and `fun key(encoding: KeyEncoding): FlagKey`.
  - In package `com.andyyhope.featureflag`, all `@Retention(BINARY)`:
    - `@FlagContainer(description = "")`, on `CLASS`;
    - `@Flag(description, remoteKey = "")`, on `PROPERTY`;
    - `@FlagGroup(description)`, on `PROPERTY`;
    - `@FlagRecord`, on `CLASS`;
    - `@FlagRecordKey`, on `VALUE_PARAMETER`;
    - `@FlagRecordProperty(key)`, on `VALUE_PARAMETER`.
  - In the test sources:
    - the interfaces `GoldenFlags`, `GoldenCheckout` and `GoldenExpress`;
    - `GoldenFlagsRefs`/`GoldenFlagsSpec`, `GoldenCheckoutRefs`/`GoldenCheckoutSpec` and `GoldenExpressRefs`/`GoldenExpressSpec`.

- [ ] **Step 1: Write the failing lookup and ref tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagLookupTest.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagLookupTest {
    /** Records what it was asked for and answers from [boxes], ignoring the type, as Swift's StubLookup does. */
    private class RecordingLookup(private val boxes: Map<FlagKey, FlagValueBox> = emptyMap()) : FlagLookup {
        val asked = mutableListOf<Pair<FlagKey, FlagValueType>>()
        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? {
            asked += key to type
            return boxes[key]
        }
    }

    private val key = FlagKey("max-items")

    @Test fun readDecodesAStoredBox() {
        assertEquals(3, RecordingLookup(mapOf(key to FlagValueBox.Int(3))).read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readFallsBackToTheDefaultWhenNothingIsStored() {
        assertEquals(10, RecordingLookup().read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readFallsBackToTheDefaultWhenTheStoredValueIsTheWrongType() {
        val lookup = RecordingLookup(mapOf(key to FlagValueBox.String("nonsense")))
        assertEquals(10, lookup.read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readFallsBackToTheDefaultWhenTheStoredValueWillNotDecode() {
        // The right type, but outside what a Kotlin Int holds: a malformed store never crashes the app.
        val lookup = RecordingLookup(mapOf(key to FlagValueBox.Int(Long.MAX_VALUE)))
        assertEquals(10, lookup.read(key, FlagCodecs.int) { 10 })
    }

    @Test fun readAsksForTheCodecsType() {
        val lookup = RecordingLookup()
        lookup.read(key, FlagCodecs.list(FlagCodecs.string)) { emptyList<String>() }
        assertEquals(key, lookup.asked.single().first)
        assertEquals(FlagValueType.Array(FlagValueType.String), lookup.asked.single().second)
    }

    @Test fun theDefaultIsOnlyEvaluatedWhenItIsNeeded() {
        var evaluated = 0
        RecordingLookup(mapOf(key to FlagValueBox.Int(3))).read(key, FlagCodecs.int) {
            evaluated++
            10
        }
        assertEquals(0, evaluated)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagRefTest.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagRefTest {
    @Test fun keyEncodesThePathWithTheGivenEncoding() {
        val path = FlagKeyPath(listOf("checkout", "applePay"))
        val ref = FlagRef(path, "applePay", "Apple Pay", null, FlagCodecs.boolean) { false }
        assertEquals(FlagKey("checkout.apple-pay"), ref.key(KeyEncoding.Kebabcase))
        assertEquals(FlagKey("checkout.apple_pay"), ref.key(KeyEncoding.Snakecase))
    }

    @Test fun theDefaultIsReadOnFirstUseAndOnlyOnce() {
        var reads = 0
        val ref = FlagRef(FlagKeyPath(listOf("maxItems")), "maxItems", "Maximum items", null, FlagCodecs.int) {
            reads++
            10
        }
        assertEquals(0, reads)
        assertEquals(10, ref.defaultValue)
        assertEquals(10, ref.defaultValue)
        assertEquals(1, reads)
    }

    @Test fun describesItselfByPath() {
        val ref = FlagRef(FlagKeyPath(listOf("checkout", "tier")), "tier", "Tier", null, FlagCodecs.string) { "free" }
        assertEquals("FlagRef(checkout.tier)", ref.toString())
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.container.*' --console=plain
```

Expected: `BUILD FAILED` at `:featureflag-core:compileTestKotlin`, with `Unresolved reference 'read'` and `Unresolved reference 'FlagRef'`.

- [ ] **Step 3: Add `read` and `FlagRef`**

**Modify:** Replace `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagLookup.kt` with:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding

/** Supplies flag values to a container tree. A `FlagPole` is the production implementation. */
public interface FlagLookup {
    /** How property-name paths become the keys this lookup stores values under. */
    public val keyEncoding: KeyEncoding

    /** The value for [key], or null if nothing supplies one. [type] is the flag's declared type. */
    public fun box(key: FlagKey, type: FlagValueType): FlagValueBox?
}

/**
 * Reads one flag: the stored box decoded by [codec], or [default] when nothing is stored or what is
 * stored will not decode. A malformed store never crashes a running app.
 */
public fun <T> FlagLookup.read(key: FlagKey, codec: FlagCodec<T>, default: () -> T): T =
    box(key, codec.type)?.let(codec::decode) ?: default()
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagRef.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * A typed handle to one flag, reached through the generated tree: `AppFlagsSpec.checkout.googlePay`.
 * The Kotlin counterpart of Swift's `$flag` projected value.
 */
public class FlagRef<T>(
    public val keyPath: FlagKeyPath,
    public val propertyName: String,
    public val description: String,
    public val remoteKey: String?,
    public val codec: FlagCodec<T>,
    defaultProvider: () -> T,
) {
    /**
     * The compiled default. Read on first use, because generated refs are built while the spec
     * object that owns the defaults is still initialising.
     */
    public val defaultValue: T by lazy(defaultProvider)

    /** The storage key under [encoding]. */
    public fun key(encoding: KeyEncoding): FlagKey = encoding.key(keyPath)

    override fun toString(): String = "FlagRef(${keyPath.propertyNames.joinToString(".")})"
}
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.container.*' --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `FlagLookupTest` passes 6 tests and `FlagRefTest` 3.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagLookup.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/container/FlagRef.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagLookupTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagRefTest.kt
git commit -m "$(cat <<'EOF'
Add FlagRef and FlagLookup.read

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Write the failing container and golden-schema tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagContainerTest.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.golden.GoldenCheckoutSpec
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports Swift's FlagContainerTests and FlagContainerMacroTests onto the golden container. Task B2
 * runs these against a hand-written GoldenFlagsSpec; from Task B3 the processor writes it, and
 * every assertion here must hold for both.
 */
class FlagContainerTest {
    private class StubLookup(
        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
        private val boxes: Map<String, FlagValueBox> = emptyMap(),
    ) : FlagLookup {
        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = boxes[key.rawValue]
    }

    private fun flags(vararg boxes: Pair<String, FlagValueBox>) =
        GoldenFlagsSpec.createFlags(StubLookup(boxes = boxes.toMap()))

    // Reading values

    @Test fun aFlagFallsBackToItsDefaultWhenNothingIsStored() {
        val flags = flags()
        assertFalse(flags.boolFlag)
        assertEquals(42, flags.intFlag)
        assertEquals(listOf("AU", "NZ"), flags.markets)
        assertContentEquals(byteArrayOf(0, 1, 2, 254.toByte(), 255.toByte()), flags.dataFlag)
        assertEquals(URI("https://prod.example.com"), flags.endpoints.single().url)
    }

    @Test fun aFlagReadsAStoredValue() {
        assertTrue(flags("bool-flag" to FlagValueBox.Bool(true)).boolFlag)
        assertEquals(GoldenTier.ENTERPRISE, flags("tier" to FlagValueBox.String("enterprise")).tier)
    }

    @Test fun aFlagFallsBackToItsDefaultWhenTheStoredValueIsTheWrongType() {
        assertEquals(42, flags("int-flag" to FlagValueBox.String("nonsense")).intFlag)
    }

    @Test fun aFlagFallsBackToItsDefaultWhenTheStoredValueWillNotDecode() {
        assertEquals(42, flags("int-flag" to FlagValueBox.Int(Long.MAX_VALUE)).intFlag)
        assertEquals(GoldenTier.FREE, flags("tier" to FlagValueBox.String("platinum")).tier)
    }

    @Test fun theDefaultsInstanceReadsEveryCompiledDefault() {
        val defaults = GoldenFlagsSpec.flagDefaults
        assertEquals(0.1, defaults.doubleFlag)
        assertEquals(GoldenTier.PRO, defaults.checkout.tier)
        assertEquals(3.0, defaults.checkout.express.confirmationDelay)
    }

    // Nesting

    @Test fun aNestedGroupNamespacesItsKeys() {
        assertFalse(flags("checkout.apple-pay" to FlagValueBox.Bool(false)).checkout.applePay)
    }

    @Test fun aDeeplyNestedGroupNamespacesItsKeys() {
        assertTrue(flags("checkout.express.one-tap" to FlagValueBox.Bool(true)).checkout.express.oneTap)
    }

    @Test fun aChildValueIsIndependentOfItsParent() {
        val flags = flags(
            "checkout.express.one-tap" to FlagValueBox.Bool(true),
            "checkout.apple-pay" to FlagValueBox.Bool(false),
        )
        assertTrue(flags.checkout.express.oneTap)
        assertFalse(flags.checkout.applePay)
    }

    @Test fun aPrefixRootsEveryKeyBeneathIt() {
        val lookup = StubLookup(boxes = mapOf("mounted.checkout.express.one-tap" to FlagValueBox.Bool(true)))
        assertTrue(GoldenFlagsSpec.createFlags(lookup, FlagKeyPath(listOf("mounted"))).checkout.express.oneTap)
    }

    // Key encoding

    @Test fun keyEncodingComesFromTheLookup() {
        val lookup = StubLookup(KeyEncoding.Snakecase, mapOf("checkout.apple_pay" to FlagValueBox.Bool(false)))
        assertFalse(GoldenFlagsSpec.createFlags(lookup).checkout.applePay)
    }

    // Refs, the counterpart of Swift's projected value

    @Test fun refsExposeTheResolvedKey() {
        assertEquals(FlagKey("bool-flag"), GoldenFlagsSpec.boolFlag.key(KeyEncoding.Kebabcase))
        assertEquals(FlagKey("use-https-only"), GoldenFlagsSpec.useHTTPSOnly.key(KeyEncoding.Kebabcase))
        assertEquals(
            FlagKey("checkout.express.one-tap"),
            GoldenFlagsSpec.checkout.express.oneTap.key(KeyEncoding.Kebabcase),
        )
    }

    @Test fun refsExposeDeclarationMetadata() {
        val ref = GoldenFlagsSpec.boolFlag
        assertEquals("boolFlag", ref.propertyName)
        assertEquals("A boolean", ref.description)
        assertEquals(false, ref.defaultValue)
        assertEquals("toggles.boolean", ref.remoteKey)
        assertEquals(GoldenTier.PRO, GoldenFlagsSpec.checkout.tier.defaultValue)
    }

    @Test fun refsReportNoRemoteKeyWhenUndeclared() {
        assertNull(GoldenFlagsSpec.floatFlag.remoteKey)
    }

    // Descriptors

    @Test fun descriptorsListFlagsInDeclarationOrder() {
        assertEquals(
            listOf(
                "boolFlag", "intFlag", "doubleFlag", "floatFlag", "stringFlag", "dataFlag", "dateFlag", "urlFlag",
                "useHTTPSOnly", "markets", "matrix", "limits", "tier", "level", "endpoints", "checkout",
            ),
            GoldenFlagsSpec.flagDescriptors.map { it.propertyName },
        )
    }

    @Test fun aFlagDescriptorCarriesEverythingAnEditorNeeds() {
        val descriptor = assertIs<FlagSchemaNode.Flag>(GoldenFlagsSpec.flagDescriptors[0]).descriptor
        assertEquals(FlagKeyPath(listOf("boolFlag")), descriptor.keyPath)
        assertEquals("A boolean", descriptor.description)
        assertEquals(FlagValueType.Bool, descriptor.valueType)
        assertEquals(FlagValueBox.Bool(false), descriptor.defaultValue)
        assertEquals("toggles.boolean", descriptor.remoteKey)
        assertNull(descriptor.cases)
    }

    @Test fun aGroupDescriptorRerootsItsChildrenThroughEveryLevel() {
        val checkout = assertIs<FlagSchemaNode.Group>(GoldenFlagsSpec.flagDescriptors.last()).group
        assertEquals("Checkout", checkout.description)
        val express = assertIs<FlagSchemaNode.Group>(checkout.children[2]).group
        val oneTap = assertIs<FlagSchemaNode.Flag>(express.children[0]).descriptor
        assertEquals(FlagKeyPath(listOf("checkout", "express", "oneTap")), oneTap.keyPath)
    }

    @Test fun anEnumDescriptorListsItsCases() {
        val checkout = assertIs<FlagSchemaNode.Group>(GoldenFlagsSpec.flagDescriptors.last()).group
        val tier = assertIs<FlagSchemaNode.Flag>(checkout.children[1]).descriptor
        assertEquals(FlagValueType.String, tier.valueType)
        assertEquals(listOf("free", "pro", "enterprise").map { FlagValueBox.String(it) }, tier.cases)
    }

    @Test fun aContainerDescribesItselfOnlyWhenItSaysSomething() {
        assertEquals("Every flag type, for cross-platform golden fixtures", GoldenFlagsSpec.flagContainerDescription)
        assertNull(GoldenCheckoutSpec.flagContainerDescription)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSchemaTest.kt`:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The golden container's schema, byte for byte against what FeatureFlag 0.12.0 wrote. */
class GoldenSchemaTest {
    private val generatedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun schema(encoding: KeyEncoding) = FlagSchema.of(GoldenFlagsSpec, encoding, "Golden", generatedAt)

    @Test fun theKebabcaseSchemaIsByteIdenticalToApples() = assertEquals(
        Fixtures.text("schema/golden-kebab.flag-schema.json"),
        schema(KeyEncoding.Kebabcase).jsonBytes().decodeToString(),
    )

    @Test fun theSnakecaseSchemaIsByteIdenticalToApples() = assertEquals(
        Fixtures.text("schema/golden-snake.flag-schema.json"),
        schema(KeyEncoding.Snakecase).jsonBytes().decodeToString(),
    )

    @Test fun applesDocumentReadsBackAsTheSameSchema() {
        val kebab = FlagSchema.parse(Fixtures.bytes("schema/golden-kebab.flag-schema.json"))
        val snake = FlagSchema.parse(Fixtures.bytes("schema/golden-snake.flag-schema.json"))
        assertEquals(schema(KeyEncoding.Kebabcase), kebab)
        assertEquals(schema(KeyEncoding.Snakecase), snake)
    }
}
```

- [ ] **Step 7: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.container.*' --tests 'com.andyyhope.featureflag.golden.*' --console=plain
```

Expected: `BUILD FAILED` at `:featureflag-core:compileTestKotlin`, with `Unresolved reference 'GoldenFlagsSpec'` and `Unresolved reference 'GoldenCheckoutSpec'`.

- [ ] **Step 8: Write the annotations, the golden containers and their hand-written spec**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/Annotations.kt`:

````kotlin
package com.andyyhope.featureflag

/**
 * Declares a set of flags. Apply it to an interface; featureflag-ksp generates `XSpec`, the
 * [com.andyyhope.featureflag.container.FlagContainerSpec] a pole is built from, and `XRefs`, the
 * typed [com.andyyhope.featureflag.container.FlagRef] tree.
 *
 * ```kotlin
 * @FlagContainer(description = "Everything the checkout team can turn on")
 * interface AppFlags {
 *     @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
 *     val newOnboarding: Boolean get() = false
 *
 *     @FlagGroup(description = "Checkout")
 *     val checkout: CheckoutFlags
 * }
 * ```
 *
 * @property description what this set of flags is for, shown by the companion. Empty says nothing.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagContainer(val description: String = "")

/**
 * Declares one flag. The property's default getter is its compiled default: the value it falls back
 * to when no source supplies one, or what is stored will not decode.
 *
 * @property description what the flag does, for the companion app.
 * @property remoteKey dot path into a remote payload. Empty means the flag is not remotely overridable.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Flag(val description: String, val remoteKey: String = "")

/**
 * Nests another `@FlagContainer`, namespacing its keys under this property's name. A group has no
 * value of its own and never gates the flags beneath it.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagGroup(val description: String)

/**
 * Makes a class something a flag can hold a list of, as `FlagRecords<R>`. Every primary-constructor
 * property is a field, in declaration order; a nullable one is optional, and a constructor default is
 * the field's default. featureflag-ksp generates `RFlagRecord`, its codec.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagRecord

/** Marks the field that tells one record from another. A record has one at most. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagRecordKey

/**
 * Reads a field from a payload under [key] instead of its property name. Decode-only: the record's
 * stored form stays keyed by the property name.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagRecordProperty(val key: String)
````

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenFlags.kt`:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.records.FlagRecords
import java.net.URI
import java.time.Instant

// The containers of fixture-generator/Sources/GoldenModel/GoldenFlags.swift; the enums and records
// are in GoldenModel.kt. Property names, descriptions, defaults, remote keys and declaration order
// must match Swift exactly: the schema golden is compared byte for byte.

@FlagContainer(description = "Every flag type, for cross-platform golden fixtures")
interface GoldenFlags {
    @Flag(description = "A boolean", remoteKey = "toggles.boolean")
    val boolFlag: Boolean get() = false

    @Flag(description = "An integer", remoteKey = "numbers.int")
    val intFlag: Int get() = 42

    @Flag(description = "A double", remoteKey = "numbers.double")
    val doubleFlag: Double get() = 0.1

    @Flag(description = "A float")
    val floatFlag: Float get() = 0.1f

    @Flag(description = "A string", remoteKey = "strings.plain")
    val stringFlag: String get() = "hello/world \"quoted\""

    @Flag(description = "Some bytes")
    val dataFlag: ByteArray get() = byteArrayOf(0, 1, 2, 254.toByte(), 255.toByte())

    @Flag(description = "A date", remoteKey = "dates.launch")
    val dateFlag: Instant get() = Instant.ofEpochSecond(1_767_225_600)

    @Flag(description = "A URL", remoteKey = "config.endpoint")
    val urlFlag: URI get() = URI("https://example.com/a/b?c=d")

    @Flag(description = "Uses an acronym")
    val useHTTPSOnly: Boolean get() = false

    @Flag(description = "Markets", remoteKey = "config.markets")
    val markets: List<String> get() = listOf("AU", "NZ")

    @Flag(description = "Nested arrays")
    val matrix: List<List<Boolean>> get() = listOf(listOf(true), listOf(false, true))

    @Flag(description = "Limits by name")
    val limits: Map<String, Int> get() = mapOf("b" to 2, "a" to 1, "v10" to 10, "v2" to 2)

    @Flag(description = "A string enum", remoteKey = "tier")
    val tier: GoldenTier get() = GoldenTier.FREE

    @Flag(description = "An int enum")
    val level: GoldenLevel get() = GoldenLevel.TWO

    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<GoldenEndpoint>
        get() = FlagRecords(listOf(GoldenEndpoint(name = "prod", url = URI("https://prod.example.com"))))

    @FlagGroup(description = "Checkout")
    val checkout: GoldenCheckout
}

@FlagContainer
interface GoldenCheckout {
    @Flag(description = "Offer Apple Pay", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = true

    @Flag(description = "Tier at checkout")
    val tier: GoldenTier get() = GoldenTier.PRO

    @FlagGroup(description = "Express checkout")
    val express: GoldenExpress
}

@FlagContainer
interface GoldenExpress {
    @Flag(description = "One-tap purchase")
    val oneTap: Boolean get() = false

    @Flag(description = "Seconds before confirming")
    val confirmationDelay: Double get() = 3.0
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSpecs.kt`:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagGroupDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import java.net.URI
import java.time.Instant

// Written by hand in exactly the shape featureflag-ksp generates for a @FlagContainer (compare
// featureflag-ksp/src/test/resources/expansions/AppFlagsSpec.kt.txt). Task B3 deletes this file and
// lets the processor write it; every test that uses these names must stay green across that swap.

open class GoldenFlagsRefs(prefix: FlagKeyPath) {
    val boolFlag: FlagRef<Boolean> = FlagRef(
        prefix.appending("boolFlag"),
        "boolFlag",
        "A boolean",
        "toggles.boolean",
        FlagCodecs.boolean,
    ) { GoldenFlagsSpec.flagDefaults.boolFlag }

    val intFlag: FlagRef<Int> = FlagRef(
        prefix.appending("intFlag"),
        "intFlag",
        "An integer",
        "numbers.int",
        FlagCodecs.int,
    ) { GoldenFlagsSpec.flagDefaults.intFlag }

    val doubleFlag: FlagRef<Double> = FlagRef(
        prefix.appending("doubleFlag"),
        "doubleFlag",
        "A double",
        "numbers.double",
        FlagCodecs.double,
    ) { GoldenFlagsSpec.flagDefaults.doubleFlag }

    val floatFlag: FlagRef<Float> = FlagRef(
        prefix.appending("floatFlag"),
        "floatFlag",
        "A float",
        null,
        FlagCodecs.float,
    ) { GoldenFlagsSpec.flagDefaults.floatFlag }

    val stringFlag: FlagRef<String> = FlagRef(
        prefix.appending("stringFlag"),
        "stringFlag",
        "A string",
        "strings.plain",
        FlagCodecs.string,
    ) { GoldenFlagsSpec.flagDefaults.stringFlag }

    val dataFlag: FlagRef<ByteArray> = FlagRef(
        prefix.appending("dataFlag"),
        "dataFlag",
        "Some bytes",
        null,
        FlagCodecs.byteArray,
    ) { GoldenFlagsSpec.flagDefaults.dataFlag }

    val dateFlag: FlagRef<Instant> = FlagRef(
        prefix.appending("dateFlag"),
        "dateFlag",
        "A date",
        "dates.launch",
        FlagCodecs.instant,
    ) { GoldenFlagsSpec.flagDefaults.dateFlag }

    val urlFlag: FlagRef<URI> = FlagRef(
        prefix.appending("urlFlag"),
        "urlFlag",
        "A URL",
        "config.endpoint",
        FlagCodecs.uri,
    ) { GoldenFlagsSpec.flagDefaults.urlFlag }

    val useHTTPSOnly: FlagRef<Boolean> = FlagRef(
        prefix.appending("useHTTPSOnly"),
        "useHTTPSOnly",
        "Uses an acronym",
        null,
        FlagCodecs.boolean,
    ) { GoldenFlagsSpec.flagDefaults.useHTTPSOnly }

    val markets: FlagRef<List<String>> = FlagRef(
        prefix.appending("markets"),
        "markets",
        "Markets",
        "config.markets",
        FlagCodecs.list(FlagCodecs.string),
    ) { GoldenFlagsSpec.flagDefaults.markets }

    val matrix: FlagRef<List<List<Boolean>>> = FlagRef(
        prefix.appending("matrix"),
        "matrix",
        "Nested arrays",
        null,
        FlagCodecs.list(FlagCodecs.list(FlagCodecs.boolean)),
    ) { GoldenFlagsSpec.flagDefaults.matrix }

    val limits: FlagRef<Map<String, Int>> = FlagRef(
        prefix.appending("limits"),
        "limits",
        "Limits by name",
        null,
        FlagCodecs.map(FlagCodecs.int),
    ) { GoldenFlagsSpec.flagDefaults.limits }

    val tier: FlagRef<GoldenTier> = FlagRef(
        prefix.appending("tier"),
        "tier",
        "A string enum",
        "tier",
        FlagCodecs.stringEnum(GoldenTier.entries),
    ) { GoldenFlagsSpec.flagDefaults.tier }

    val level: FlagRef<GoldenLevel> = FlagRef(
        prefix.appending("level"),
        "level",
        "An int enum",
        null,
        FlagCodecs.intEnum(GoldenLevel.entries),
    ) { GoldenFlagsSpec.flagDefaults.level }

    val endpoints: FlagRef<FlagRecords<GoldenEndpoint>> = FlagRef(
        prefix.appending("endpoints"),
        "endpoints",
        "Endpoints",
        "config.endpoints",
        FlagCodecs.records(GoldenEndpointFlagRecord),
    ) { GoldenFlagsSpec.flagDefaults.endpoints }

    val checkout: GoldenCheckoutRefs = GoldenCheckoutRefs(prefix.appending("checkout"))
}

object GoldenFlagsSpec : GoldenFlagsRefs(FlagKeyPath.ROOT), FlagContainerSpec<GoldenFlags> {
    override val flagContainerDescription: String? = "Every flag type, for cross-platform golden fixtures"

    override val flagDefaults: GoldenFlags = object : GoldenFlags {
        override val checkout: GoldenCheckout
            get() = GoldenCheckoutSpec.flagDefaults
    }

    override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
            FlagSchemaNode.Flag(
                FlagDescriptor.of("boolFlag", "A boolean", "toggles.boolean", boolFlag.codec, flagDefaults.boolFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("intFlag", "An integer", "numbers.int", intFlag.codec, flagDefaults.intFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "doubleFlag",
                    "A double",
                    "numbers.double",
                    doubleFlag.codec,
                    flagDefaults.doubleFlag,
                ),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("floatFlag", "A float", null, floatFlag.codec, flagDefaults.floatFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("stringFlag", "A string", "strings.plain", stringFlag.codec, flagDefaults.stringFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("dataFlag", "Some bytes", null, dataFlag.codec, flagDefaults.dataFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("dateFlag", "A date", "dates.launch", dateFlag.codec, flagDefaults.dateFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("urlFlag", "A URL", "config.endpoint", urlFlag.codec, flagDefaults.urlFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "useHTTPSOnly",
                    "Uses an acronym",
                    null,
                    useHTTPSOnly.codec,
                    flagDefaults.useHTTPSOnly,
                ),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("markets", "Markets", "config.markets", markets.codec, flagDefaults.markets),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("matrix", "Nested arrays", null, matrix.codec, flagDefaults.matrix),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("limits", "Limits by name", null, limits.codec, flagDefaults.limits),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("tier", "A string enum", "tier", tier.codec, flagDefaults.tier),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("level", "An int enum", null, level.codec, flagDefaults.level),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "endpoints",
                    "Endpoints",
                    "config.endpoints",
                    endpoints.codec,
                    flagDefaults.endpoints,
                ),
            ),
            FlagSchemaNode.Group(
                FlagGroupDescriptor(
                    "checkout",
                    FlagKeyPath(listOf("checkout")),
                    "Checkout",
                    GoldenCheckoutSpec.flagDescriptors.map { it.prefixed("checkout") },
                ),
            ),
        )
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): GoldenFlags = Impl(lookup, prefix)

    private class Impl(private val flagLookup: FlagLookup, flagPrefix: FlagKeyPath) : GoldenFlags {
        private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

        override val boolFlag: Boolean
            get() = flagLookup.read(flagKeys.boolFlag, GoldenFlagsSpec.boolFlag.codec) { super<GoldenFlags>.boolFlag }

        override val intFlag: Int
            get() = flagLookup.read(flagKeys.intFlag, GoldenFlagsSpec.intFlag.codec) { super<GoldenFlags>.intFlag }

        override val doubleFlag: Double
            get() = flagLookup.read(flagKeys.doubleFlag, GoldenFlagsSpec.doubleFlag.codec) {
                super<GoldenFlags>.doubleFlag
            }

        override val floatFlag: Float
            get() = flagLookup.read(flagKeys.floatFlag, GoldenFlagsSpec.floatFlag.codec) {
                super<GoldenFlags>.floatFlag
            }

        override val stringFlag: String
            get() = flagLookup.read(flagKeys.stringFlag, GoldenFlagsSpec.stringFlag.codec) {
                super<GoldenFlags>.stringFlag
            }

        override val dataFlag: ByteArray
            get() = flagLookup.read(flagKeys.dataFlag, GoldenFlagsSpec.dataFlag.codec) { super<GoldenFlags>.dataFlag }

        override val dateFlag: Instant
            get() = flagLookup.read(flagKeys.dateFlag, GoldenFlagsSpec.dateFlag.codec) { super<GoldenFlags>.dateFlag }

        override val urlFlag: URI
            get() = flagLookup.read(flagKeys.urlFlag, GoldenFlagsSpec.urlFlag.codec) { super<GoldenFlags>.urlFlag }

        override val useHTTPSOnly: Boolean
            get() = flagLookup.read(flagKeys.useHTTPSOnly, GoldenFlagsSpec.useHTTPSOnly.codec) {
                super<GoldenFlags>.useHTTPSOnly
            }

        override val markets: List<String>
            get() = flagLookup.read(flagKeys.markets, GoldenFlagsSpec.markets.codec) { super<GoldenFlags>.markets }

        override val matrix: List<List<Boolean>>
            get() = flagLookup.read(flagKeys.matrix, GoldenFlagsSpec.matrix.codec) { super<GoldenFlags>.matrix }

        override val limits: Map<String, Int>
            get() = flagLookup.read(flagKeys.limits, GoldenFlagsSpec.limits.codec) { super<GoldenFlags>.limits }

        override val tier: GoldenTier
            get() = flagLookup.read(flagKeys.tier, GoldenFlagsSpec.tier.codec) { super<GoldenFlags>.tier }

        override val level: GoldenLevel
            get() = flagLookup.read(flagKeys.level, GoldenFlagsSpec.level.codec) { super<GoldenFlags>.level }

        override val endpoints: FlagRecords<GoldenEndpoint>
            get() = flagLookup.read(flagKeys.endpoints, GoldenFlagsSpec.endpoints.codec) {
                super<GoldenFlags>.endpoints
            }

        override val checkout: GoldenCheckout =
            GoldenCheckoutSpec.createFlags(flagLookup, flagPrefix.appending("checkout"))
    }

    private class Keys(encoding: KeyEncoding, prefix: FlagKeyPath) {
        val boolFlag: FlagKey = encoding.key(prefix.appending("boolFlag"))

        val intFlag: FlagKey = encoding.key(prefix.appending("intFlag"))

        val doubleFlag: FlagKey = encoding.key(prefix.appending("doubleFlag"))

        val floatFlag: FlagKey = encoding.key(prefix.appending("floatFlag"))

        val stringFlag: FlagKey = encoding.key(prefix.appending("stringFlag"))

        val dataFlag: FlagKey = encoding.key(prefix.appending("dataFlag"))

        val dateFlag: FlagKey = encoding.key(prefix.appending("dateFlag"))

        val urlFlag: FlagKey = encoding.key(prefix.appending("urlFlag"))

        val useHTTPSOnly: FlagKey = encoding.key(prefix.appending("useHTTPSOnly"))

        val markets: FlagKey = encoding.key(prefix.appending("markets"))

        val matrix: FlagKey = encoding.key(prefix.appending("matrix"))

        val limits: FlagKey = encoding.key(prefix.appending("limits"))

        val tier: FlagKey = encoding.key(prefix.appending("tier"))

        val level: FlagKey = encoding.key(prefix.appending("level"))

        val endpoints: FlagKey = encoding.key(prefix.appending("endpoints"))
    }
}

open class GoldenCheckoutRefs(prefix: FlagKeyPath) {
    val applePay: FlagRef<Boolean> = FlagRef(
        prefix.appending("applePay"),
        "applePay",
        "Offer Apple Pay",
        "featureToggles.checkout.applePay",
        FlagCodecs.boolean,
    ) { GoldenCheckoutSpec.flagDefaults.applePay }

    val tier: FlagRef<GoldenTier> = FlagRef(
        prefix.appending("tier"),
        "tier",
        "Tier at checkout",
        null,
        FlagCodecs.stringEnum(GoldenTier.entries),
    ) { GoldenCheckoutSpec.flagDefaults.tier }

    val express: GoldenExpressRefs = GoldenExpressRefs(prefix.appending("express"))
}

object GoldenCheckoutSpec : GoldenCheckoutRefs(FlagKeyPath.ROOT), FlagContainerSpec<GoldenCheckout> {
    override val flagContainerDescription: String? = null

    override val flagDefaults: GoldenCheckout = object : GoldenCheckout {
        override val express: GoldenExpress
            get() = GoldenExpressSpec.flagDefaults
    }

    override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "applePay",
                    "Offer Apple Pay",
                    "featureToggles.checkout.applePay",
                    applePay.codec,
                    flagDefaults.applePay,
                ),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("tier", "Tier at checkout", null, tier.codec, flagDefaults.tier),
            ),
            FlagSchemaNode.Group(
                FlagGroupDescriptor(
                    "express",
                    FlagKeyPath(listOf("express")),
                    "Express checkout",
                    GoldenExpressSpec.flagDescriptors.map { it.prefixed("express") },
                ),
            ),
        )
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): GoldenCheckout = Impl(lookup, prefix)

    private class Impl(private val flagLookup: FlagLookup, flagPrefix: FlagKeyPath) : GoldenCheckout {
        private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

        override val applePay: Boolean
            get() = flagLookup.read(flagKeys.applePay, GoldenCheckoutSpec.applePay.codec) {
                super<GoldenCheckout>.applePay
            }

        override val tier: GoldenTier
            get() = flagLookup.read(flagKeys.tier, GoldenCheckoutSpec.tier.codec) { super<GoldenCheckout>.tier }

        override val express: GoldenExpress =
            GoldenExpressSpec.createFlags(flagLookup, flagPrefix.appending("express"))
    }

    private class Keys(encoding: KeyEncoding, prefix: FlagKeyPath) {
        val applePay: FlagKey = encoding.key(prefix.appending("applePay"))

        val tier: FlagKey = encoding.key(prefix.appending("tier"))
    }
}

open class GoldenExpressRefs(prefix: FlagKeyPath) {
    val oneTap: FlagRef<Boolean> = FlagRef(
        prefix.appending("oneTap"),
        "oneTap",
        "One-tap purchase",
        null,
        FlagCodecs.boolean,
    ) { GoldenExpressSpec.flagDefaults.oneTap }

    val confirmationDelay: FlagRef<Double> = FlagRef(
        prefix.appending("confirmationDelay"),
        "confirmationDelay",
        "Seconds before confirming",
        null,
        FlagCodecs.double,
    ) { GoldenExpressSpec.flagDefaults.confirmationDelay }
}

object GoldenExpressSpec : GoldenExpressRefs(FlagKeyPath.ROOT), FlagContainerSpec<GoldenExpress> {
    override val flagContainerDescription: String? = null

    override val flagDefaults: GoldenExpress = object : GoldenExpress {}

    override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
            FlagSchemaNode.Flag(
                FlagDescriptor.of("oneTap", "One-tap purchase", null, oneTap.codec, flagDefaults.oneTap),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "confirmationDelay",
                    "Seconds before confirming",
                    null,
                    confirmationDelay.codec,
                    flagDefaults.confirmationDelay,
                ),
            ),
        )
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): GoldenExpress = Impl(lookup, prefix)

    private class Impl(private val flagLookup: FlagLookup, flagPrefix: FlagKeyPath) : GoldenExpress {
        private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

        override val oneTap: Boolean
            get() = flagLookup.read(flagKeys.oneTap, GoldenExpressSpec.oneTap.codec) { super<GoldenExpress>.oneTap }

        override val confirmationDelay: Double
            get() = flagLookup.read(flagKeys.confirmationDelay, GoldenExpressSpec.confirmationDelay.codec) {
                super<GoldenExpress>.confirmationDelay
            }
    }

    private class Keys(encoding: KeyEncoding, prefix: FlagKeyPath) {
        val oneTap: FlagKey = encoding.key(prefix.appending("oneTap"))

        val confirmationDelay: FlagKey = encoding.key(prefix.appending("confirmationDelay"))
    }
}
```

- [ ] **Step 9: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.container.*' --tests 'com.andyyhope.featureflag.golden.*' --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `FlagContainerTest` passes 18 tests and `GoldenSchemaTest` 3, so the kebab and snake schemas are byte-identical to `fixtures/schema/golden-*.flag-schema.json`. M1a's golden and record tests also still pass. If a byte comparison fails, the diff names the field: fix the Kotlin mirror or `FlagSchema`, never the fixture.

- [ ] **Step 10: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/Annotations.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/FlagContainerTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenFlags.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSpecs.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSchemaTest.kt
git commit -m "$(cat <<'EOF'
Add the flag annotations and a hand-written golden spec matching Apple's schema byte for byte

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task B3: The `featureflag-ksp` processor for `@FlagContainer` interfaces

The processor runs on KSP2 and writes with KotlinPoet through an isolating `CodeGenerator`. Its originating files are the container's own file plus the files of every group, enum and record type it reads. Group specs are referenced by name (`GSpec`, `GRefs`), so a group may live in another module. A `FlagRecords<R>` flag uses `RFlagRecord`. That codec is generated in the module that declares `R`, or written by hand; core's golden codecs are still hand-written until Task B4. This task generates only valid input. Task B5 adds the checks that reject everything else before it gets here.

Type mapping (`TypeMapping.kt`):

| Kotlin | `FlagCodecs` expression | Record placeholder (B4) |
|---|---|---|
| `Boolean` | `FlagCodecs.boolean` | `false` |
| `Int` | `FlagCodecs.int` | `0` |
| `Long` | `FlagCodecs.long` | `0L` |
| `Double` | `FlagCodecs.double` | `0.0` |
| `Float` | `FlagCodecs.float` | `0f` |
| `String` | `FlagCodecs.string` | `""` |
| `ByteArray` | `FlagCodecs.byteArray` | `ByteArray(0)` |
| `java.time.Instant` | `FlagCodecs.instant` | `Instant.EPOCH` |
| `java.net.URI` | `FlagCodecs.uri` | `URI("")` |
| `List<T>` | `FlagCodecs.list(<T>)` | `emptyList()` |
| `Map<String, T>` | `FlagCodecs.map(<T>)` | `emptyMap()` |
| enum `E` | `FlagCodecs.stringEnum(E.entries)` if `E : FlagStringEnum`, `intEnum` if `E : FlagIntEnum`, else `enumByName` | `E.entries.first()` |
| `FlagRecords<R>` | `FlagCodecs.records(RFlagRecord)` | `FlagRecords()` |

Typealiases are expanded. A nullable type, a non-`String` map key, or anything else is unsupported, and Task B5 reports it.

**Files:**
- Modify: `gradle/libs.versions.toml` (three KSP test libraries), `featureflag-ksp/build.gradle.kts`, `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessorProvider.kt` (replaces M1a's placeholder), `featureflag-core/build.gradle.kts`
- Create: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessor.kt`, `…/ksp/TypeMapping.kt`, `…/ksp/ContainerGenerator.kt`, `featureflag-ksp/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
- Delete: `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/SmokeTest.kt` (M1a's placeholder test), `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSpecs.kt`
- Test: `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/KspHarness.kt`, `…/ksp/ContainerGenerationTest.kt`, `featureflag-ksp/src/test/resources/expansions/AppFlagsSpec.kt.txt`, `…/expansions/CheckoutFlagsSpec.kt.txt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/EdgeCaseFlags.kt`, `…/container/GeneratedContainerTest.kt`

**Interfaces:**
- Consumes: B1/B2 public API (by name only: the processor has no compile dependency on core), M1a's catalog aliases `libs.ksp.api`, `libs.kotlinpoet`, `libs.kotlinpoet.ksp`, `libs.kctfork.core`, `libs.kctfork.ksp`, `libs.plugins.ksp`.
- Produces:
  - `public class FeatureFlagProcessorProvider : SymbolProcessorProvider`, registered in `META-INF/services`.
  - For each `@FlagContainer` interface `X` in package `p`, the file `p/XSpec.kt`, holding `XRefs` and `XSpec` in the shape B2 wrote by hand. They are `internal` when `X` (or an enclosing declaration) is internal, `public` otherwise.
  - Internal helpers:
    - `Names`, with `Names.generatedName(declaration, suffix)`;
    - `KSAnnotated.hasAnnotation`, `KSAnnotated.annotationArgument` and `KSType.expanded()`;
    - `TypeMapping(resolver).map(type): TypeMapping.Result`, which is `Supported(codec, placeholder, originatingFiles)`, `Unsupported(record)` or `MissingRecordCodec(record, codec)`;
    - `KSClassDeclaration.generatedVisibility()`, `ContainerGenerator(resolver, codeGenerator)` and `FeatureFlagProcessor(codeGenerator)`.
  - The test helpers `compile(vararg sources, classpath)`, `source(name, body)`, `Compiled` and `Reported`.

- [ ] **Step 1: Point the KSP tests at core, and write the failing processor tests**

**Modify** `gradle/libs.versions.toml` (M1a Task A1's catalog): add the three libraries after the existing `ksp-api` line. In `gradle/libs.versions.toml`, replace

```toml
ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }
```

with

```toml
ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }
ksp-symbol-processing = { module = "com.google.devtools.ksp:symbol-processing", version.ref = "ksp" }
ksp-aa-embeddable = { module = "com.google.devtools.ksp:symbol-processing-aa-embeddable", version.ref = "ksp" }
ksp-common-deps = { module = "com.google.devtools.ksp:symbol-processing-common-deps", version.ref = "ksp" }
```

**Modify:** Replace `featureflag-ksp/build.gradle.kts` with:

```kotlin
plugins {
    id("featureflag.jvm-library")
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
```

Delete M1a's placeholder test:

```bash
git rm featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/SmokeTest.kt
```

`featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/KspHarness.kt`:

```kotlin
@file:OptIn(ExperimentalCompilerApi::class)

package com.andyyhope.featureflag.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One `logger.error` the processor reported: the file's name, the line, and the message. */
internal data class Reported(val file: String, val line: Int, val message: String)

internal class Compiled(private val result: JvmCompilationResult) {
    val succeeded: Boolean get() = result.exitCode == KotlinCompilation.ExitCode.OK
    val outputDirectory: File get() = result.outputDirectory
    val classLoader: ClassLoader get() = result.classLoader

    /** Only the errors and warnings, for an assertion message a person can read. */
    val problems: String
        get() = result.messages.lines().filter { it.startsWith("e:") || it.startsWith("w:") }.joinToString("\n")

    /** Every error the processor reported, in the order it reported them. */
    val errors: List<Reported>
        get() = result.messages.lineSequence()
            .mapNotNull { REPORTED.matchEntire(it.trim()) }
            .map { Reported(File(it.groupValues[1]).name, it.groupValues[2].toInt(), it.groupValues[3]) }
            .toList()

    /** Generated file name to its text. */
    val generated: Map<String, String>
        get() = result.sourcesGeneratedBySymbolProcessor.associate { it.name to it.readText() }

    /** The generated [file] equals `src/test/resources/expansions/<file>.txt`, ignoring trailing whitespace. */
    fun assertMatchesSnapshot(file: String) {
        assertTrue(succeeded, problems)
        val expected = Compiled::class.java.getResource("/expansions/$file.txt")?.readText()?.trimEnd()
        assertEquals(expected, generated[file]?.trimEnd(), "update the snapshot only if the change is intended")
    }

    private companion object {
        val REPORTED = Regex("""^e: \[ksp] (.+):(\d+): (.*)$""")
    }
}

/**
 * Runs the processor over [sources] with KSP2, then compiles everything with warnings as errors, for
 * JVM 1.8: the lowest target a consumer may use, so generated code never inlines core's 17 bytecode.
 */
internal fun compile(
    vararg sources: SourceFile,
    classpath: List<File> = emptyList(),
): Compiled = Compiled(
    KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        classpaths = classpaths + classpath
        jvmTarget = "1.8"
        allWarningsAsErrors = true
        messageOutputStream = OutputStream.nullOutputStream()
        configureKsp(useKsp2 = true) { symbolProcessorProviders += FeatureFlagProcessorProvider() }
    }.compile(),
)

/**
 * A Kotlin source in package `app` with every annotation imported. The header is nine lines, so
 * the first line of [body] is line 10 of the file.
 */
internal fun source(name: String, body: String): SourceFile = SourceFile.kotlin(
    name,
    """
    |package app
    |
    |import com.andyyhope.featureflag.Flag
    |import com.andyyhope.featureflag.FlagContainer
    |import com.andyyhope.featureflag.FlagGroup
    |import com.andyyhope.featureflag.FlagRecord
    |import com.andyyhope.featureflag.FlagRecordKey
    |import com.andyyhope.featureflag.FlagRecordProperty
    |import com.andyyhope.featureflag.records.FlagRecords
    |
    """.trimMargin() + body.trimIndent() + "\n",
)
```

`featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/ContainerGenerationTest.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContainerGenerationTest {
    private val appFlags = compile(
        source(
            "AppFlags.kt",
            """
            enum class Tier { FREE, PRO }

            @FlagContainer(description = "Everything the checkout team can turn on")
            interface AppFlags {
                @Flag(description = "New onboarding", remoteKey = "featureToggles.onboarding.v2")
                val newOnboarding: Boolean get() = false

                @Flag(description = "Maximum items")
                val maxItems: Int get() = 10

                @FlagGroup(description = "Checkout")
                val checkout: CheckoutFlags

                val notAFlag: String get() = "plain"
            }

            @FlagContainer
            interface CheckoutFlags {
                @Flag(description = "Apple Pay")
                val applePay: Boolean get() = false

                @Flag(description = "Tier")
                val tier: Tier get() = Tier.FREE
            }
            """,
        ),
    )

    @Test fun appFlagsSpecMatchesItsSnapshot() = appFlags.assertMatchesSnapshot("AppFlagsSpec.kt")

    @Test fun checkoutFlagsSpecMatchesItsSnapshot() = appFlags.assertMatchesSnapshot("CheckoutFlagsSpec.kt")

    @Test fun aConsumerOnJvmTarget18RunsTheGeneratedCode() {
        // Android modules often still target 1.8 or 11. Generated code must not inline anything from
        // core, whose bytecode is 17, and the super<X> fallback must work from a non-inline lambda.
        val compiled = compile(
            source(
                "Probe.kt",
                """
                import com.andyyhope.featureflag.container.FlagLookup
                import com.andyyhope.featureflag.values.FlagKey
                import com.andyyhope.featureflag.values.FlagValueBox
                import com.andyyhope.featureflag.values.FlagValueType
                import com.andyyhope.featureflag.values.KeyEncoding

                @FlagContainer
                interface ProbeFlags {
                    @Flag(description = "Maximum items")
                    val maxItems: Int get() = 10
                }

                fun probe(): Int = ProbeFlagsSpec.createFlags(
                    object : FlagLookup {
                        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
                        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null
                    },
                ).maxItems
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        assertEquals(10, compiled.classLoader.loadClass("app.ProbeKt").getMethod("probe").invoke(null))
    }

    @Test fun generatedCodeSurvivesShadowedNames() {
        // A host app is likely to declare its own Flag, FlagRef or Keys. Generated code imports every
        // name it uses explicitly, and explicit imports beat declarations in the same package.
        val compiled = compile(
            SourceFile.kotlin(
                "ShadowedFlags.kt",
                """
                package app

                import com.andyyhope.featureflag.FlagContainer
                import com.andyyhope.featureflag.FlagGroup

                annotation class Flag(val description: String)
                class FlagRef
                class FlagKey
                class FlagKeyPath
                class FlagCodecs
                class FlagLookup
                class FlagDescriptor
                class FlagGroupDescriptor
                class FlagSchemaNode
                class FlagContainerSpec
                class KeyEncoding
                class Keys
                class Impl
                fun read(): Nothing = error("shadowed")

                @FlagContainer
                interface ShadowedFlags {
                    @com.andyyhope.featureflag.Flag(description = "Title")
                    val title: String get() = "none"

                    @FlagGroup(description = "Checkout")
                    val checkout: ShadowedCheckoutFlags
                }

                @FlagContainer
                interface ShadowedCheckoutFlags {
                    @com.andyyhope.featureflag.Flag(description = "Apple Pay")
                    val applePay: Boolean get() = false
                }
                """.trimIndent(),
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
    }

    @Test fun anInternalContainerGetsInternalDeclarations() {
        val compiled = compile(
            source(
                "InternalFlags.kt",
                """
                @FlagContainer
                internal interface InternalFlags {
                    @Flag(description = "x")
                    val x: Boolean get() = true
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        val text = compiled.generated.getValue("InternalFlagsSpec.kt")
        assertTrue("internal open class InternalFlagsRefs(" in text, text)
        assertTrue("internal object InternalFlagsSpec :" in text, text)
    }

    @Test fun aNestedContainerIsNamedAfterItsOuterDeclaration() {
        val compiled = compile(
            source(
                "Outer.kt",
                """
                interface Outer {
                    @FlagContainer
                    interface Inner {
                        @Flag(description = "x")
                        val x: Boolean get() = true
                    }
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        assertEquals(setOf("Outer_InnerSpec.kt"), compiled.generated.keys)
    }

    @Test fun aHandWrittenRecordCodecIsFoundByName() {
        val compiled = compile(
            source(
                "Legacy.kt",
                """
                import com.andyyhope.featureflag.records.FlagRecordCodec
                import com.andyyhope.featureflag.records.FlagRecordField
                import com.andyyhope.featureflag.values.FlagCodecs
                import com.andyyhope.featureflag.values.FlagValueBox

                data class Legacy(val name: String)

                object LegacyFlagRecord : FlagRecordCodec<Legacy> {
                    override val shape: List<FlagRecordField> = listOf(FlagRecordField.of("name", FlagCodecs.string))
                    override val keyField: String? = null
                    override fun boxes(record: Legacy): Map<String, FlagValueBox> =
                        mapOf("name" to FlagCodecs.string.encode(record.name))
                    override fun fromBoxes(boxes: Map<String, FlagValueBox>): Legacy? =
                        boxes["name"]?.let(FlagCodecs.string::decode)?.let(::Legacy)
                }

                @FlagContainer
                interface LegacyFlags {
                    @Flag(description = "Legacy records")
                    val legacy: FlagRecords<Legacy> get() = FlagRecords()
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        assertTrue("FlagCodecs.records(LegacyFlagRecord)" in compiled.generated.getValue("LegacyFlagsSpec.kt"))
    }

    @Test fun aGroupFromAnotherModuleIsFoundByName() {
        val library = compile(
            source(
                "SharedFlags.kt",
                """
                @FlagContainer
                interface SharedFlags {
                    @Flag(description = "Shared")
                    val shared: Boolean get() = true
                }
                """,
            ),
        )
        assertTrue(library.succeeded, library.problems)
        val app = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    @FlagGroup(description = "Shared")
                    val group: SharedFlags
                }
                """,
            ),
            classpath = listOf(library.outputDirectory),
        )
        assertTrue(app.succeeded, app.problems)
        assertEquals(setOf("AppFlagsSpec.kt"), app.generated.keys)
        val spec = app.classLoader.loadClass("app.AppFlagsSpec").getField("INSTANCE").get(null)
        val defaults = spec.javaClass.getMethod("getFlagDefaults").invoke(spec)
        val group = defaults.javaClass.getMethod("getGroup").invoke(defaults)
        assertEquals(true, group.javaClass.getMethod("getShared").invoke(group))
    }
}
```

The two expansion snapshots. These are KotlinPoet's own formatting, written byte for byte:

```bash
mkdir -p featureflag-ksp/src/test/resources/expansions
cat > featureflag-ksp/src/test/resources/expansions/AppFlagsSpec.kt.txt <<'SNAPSHOT'
package app

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagGroupDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.listOf
import kotlin.lazy

public open class AppFlagsRefs(
  prefix: FlagKeyPath,
) {
  public val newOnboarding: FlagRef<Boolean> =
      FlagRef(prefix.appending("newOnboarding"), "newOnboarding", "New onboarding", "featureToggles.onboarding.v2", FlagCodecs.boolean) { AppFlagsSpec.flagDefaults.newOnboarding }

  public val maxItems: FlagRef<Int> =
      FlagRef(prefix.appending("maxItems"), "maxItems", "Maximum items", null, FlagCodecs.int) { AppFlagsSpec.flagDefaults.maxItems }

  public val checkout: CheckoutFlagsRefs = CheckoutFlagsRefs(prefix.appending("checkout"))
}

public object AppFlagsSpec : AppFlagsRefs(FlagKeyPath.ROOT), FlagContainerSpec<AppFlags> {
  override val flagContainerDescription: String? = "Everything the checkout team can turn on"

  override val flagDefaults: AppFlags = object : AppFlags {
    override val checkout: CheckoutFlags
      get() = CheckoutFlagsSpec.flagDefaults
  }

  override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
          FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "New onboarding", "featureToggles.onboarding.v2", newOnboarding.codec, flagDefaults.newOnboarding)),
          FlagSchemaNode.Flag(FlagDescriptor.of("maxItems", "Maximum items", null, maxItems.codec, flagDefaults.maxItems)),
          FlagSchemaNode.Group(FlagGroupDescriptor("checkout", FlagKeyPath(listOf("checkout")), "Checkout", CheckoutFlagsSpec.flagDescriptors.map { it.prefixed("checkout") })),
        )
      }

  override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): AppFlags = Impl(lookup, prefix)

  private class Impl(
    private val flagLookup: FlagLookup,
    flagPrefix: FlagKeyPath,
  ) : AppFlags {
    private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

    override val newOnboarding: Boolean
      get() = flagLookup.read(flagKeys.newOnboarding, AppFlagsSpec.newOnboarding.codec) { super<AppFlags>.newOnboarding }

    override val maxItems: Int
      get() = flagLookup.read(flagKeys.maxItems, AppFlagsSpec.maxItems.codec) { super<AppFlags>.maxItems }

    override val checkout: CheckoutFlags =
        CheckoutFlagsSpec.createFlags(flagLookup, flagPrefix.appending("checkout"))
  }

  private class Keys(
    encoding: KeyEncoding,
    prefix: FlagKeyPath,
  ) {
    public val newOnboarding: FlagKey = encoding.key(prefix.appending("newOnboarding"))

    public val maxItems: FlagKey = encoding.key(prefix.appending("maxItems"))
  }
}
SNAPSHOT
cat > featureflag-ksp/src/test/resources/expansions/CheckoutFlagsSpec.kt.txt <<'SNAPSHOT'
package app

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.listOf
import kotlin.lazy

public open class CheckoutFlagsRefs(
  prefix: FlagKeyPath,
) {
  public val applePay: FlagRef<Boolean> =
      FlagRef(prefix.appending("applePay"), "applePay", "Apple Pay", null, FlagCodecs.boolean) { CheckoutFlagsSpec.flagDefaults.applePay }

  public val tier: FlagRef<Tier> =
      FlagRef(prefix.appending("tier"), "tier", "Tier", null, FlagCodecs.enumByName(Tier.entries)) { CheckoutFlagsSpec.flagDefaults.tier }
}

public object CheckoutFlagsSpec : CheckoutFlagsRefs(FlagKeyPath.ROOT),
    FlagContainerSpec<CheckoutFlags> {
  override val flagContainerDescription: String? = null

  override val flagDefaults: CheckoutFlags = object : CheckoutFlags {
  }

  override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
          FlagSchemaNode.Flag(FlagDescriptor.of("applePay", "Apple Pay", null, applePay.codec, flagDefaults.applePay)),
          FlagSchemaNode.Flag(FlagDescriptor.of("tier", "Tier", null, tier.codec, flagDefaults.tier)),
        )
      }

  override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): CheckoutFlags = Impl(lookup, prefix)

  private class Impl(
    private val flagLookup: FlagLookup,
    flagPrefix: FlagKeyPath,
  ) : CheckoutFlags {
    private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

    override val applePay: Boolean
      get() = flagLookup.read(flagKeys.applePay, CheckoutFlagsSpec.applePay.codec) { super<CheckoutFlags>.applePay }

    override val tier: Tier
      get() = flagLookup.read(flagKeys.tier, CheckoutFlagsSpec.tier.codec) { super<CheckoutFlags>.tier }
  }

  private class Keys(
    encoding: KeyEncoding,
    prefix: FlagKeyPath,
  ) {
    public val applePay: FlagKey = encoding.key(prefix.appending("applePay"))

    public val tier: FlagKey = encoding.key(prefix.appending("tier"))
  }
}
SNAPSHOT
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ksp:test --console=plain
```

Expected: `BUILD FAILED` from `:featureflag-ksp:test`. Seven of `ContainerGenerationTest`'s 8 tests fail, because M1a's placeholder processor generates only its marker object. For example, `appFlagsSpecMatchesItsSnapshot` fails with `expected:<package app …> but was:<null>`. Only `generatedCodeSurvivesShadowedNames` passes, because those sources compile without any generated code.

- [ ] **Step 3: Write the processor**

The new provider file no longer contains M1a's placeholder `FeatureFlagProcessor`; the real one goes in its own file.

**Modify:** Replace `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessorProvider.kt` with:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Registered through META-INF/services, so `ksp(project(":featureflag-ksp"))` is all a module needs. */
public class FeatureFlagProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeatureFlagProcessor(environment.codeGenerator)
}
```

`featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessor.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** Generates `XRefs` and `XSpec` for every `@FlagContainer` interface. */
internal class FeatureFlagProcessor(private val codeGenerator: CodeGenerator) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val containers = ContainerGenerator(resolver, codeGenerator)
        symbols(resolver, Names.FLAG_CONTAINER, deferred).forEach { containers.generate(containers.parse(it)) }
        return deferred
    }

    /** Declarations carrying [annotation] that resolve; the rest wait for a later round. */
    private fun symbols(
        resolver: Resolver,
        annotation: String,
        deferred: MutableList<KSAnnotated>,
    ): List<KSClassDeclaration> {
        val (ready, waiting) = resolver.getSymbolsWithAnnotation(annotation).partition { it.validate() }
        deferred += waiting
        return ready.filterIsInstance<KSClassDeclaration>()
    }
}
```

`featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/TypeMapping.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName

/** Every core name generated code refers to, in one place. */
internal object Names {
    private const val ROOT = "com.andyyhope.featureflag"
    private const val VALUES = "$ROOT.values"
    private const val RECORDS = "$ROOT.records"
    private const val SCHEMA = "$ROOT.schema"
    private const val CONTAINER = "$ROOT.container"

    const val FLAG_CONTAINER: String = "$ROOT.FlagContainer"
    const val FLAG: String = "$ROOT.Flag"
    const val FLAG_GROUP: String = "$ROOT.FlagGroup"
    const val FLAG_RECORD: String = "$ROOT.FlagRecord"
    const val FLAG_RECORD_KEY: String = "$ROOT.FlagRecordKey"
    const val FLAG_RECORD_PROPERTY: String = "$ROOT.FlagRecordProperty"
    const val FLAG_STRING_ENUM: String = "$VALUES.FlagStringEnum"
    const val FLAG_INT_ENUM: String = "$VALUES.FlagIntEnum"
    const val FLAG_RECORDS_NAME: String = "$RECORDS.FlagRecords"

    val FLAG_CODECS: ClassName = ClassName(VALUES, "FlagCodecs")
    val FLAG_CODEC: ClassName = ClassName(VALUES, "FlagCodec")
    val FLAG_KEY: ClassName = ClassName(VALUES, "FlagKey")
    val FLAG_KEY_PATH: ClassName = ClassName(VALUES, "FlagKeyPath")
    val FLAG_VALUE_BOX: ClassName = ClassName(VALUES, "FlagValueBox")
    val KEY_ENCODING: ClassName = ClassName(VALUES, "KeyEncoding")
    val FLAG_RECORDS: ClassName = ClassName(RECORDS, "FlagRecords")
    val FLAG_RECORD_CODEC: ClassName = ClassName(RECORDS, "FlagRecordCodec")
    val FLAG_RECORD_FIELD: ClassName = ClassName(RECORDS, "FlagRecordField")
    val FLAG_DESCRIPTOR: ClassName = ClassName(SCHEMA, "FlagDescriptor")
    val FLAG_GROUP_DESCRIPTOR: ClassName = ClassName(SCHEMA, "FlagGroupDescriptor")
    val FLAG_SCHEMA_NODE: ClassName = ClassName(SCHEMA, "FlagSchemaNode")
    val FLAG_LOOKUP: ClassName = ClassName(CONTAINER, "FlagLookup")
    val FLAG_CONTAINER_SPEC: ClassName = ClassName(CONTAINER, "FlagContainerSpec")
    val FLAG_REF: ClassName = ClassName(CONTAINER, "FlagRef")
    val READ: MemberName = MemberName(CONTAINER, "read")
    val LAZY: MemberName = MemberName("kotlin", "lazy")
    val LIST_OF: MemberName = MemberName("kotlin.collections", "listOf")
    val BUILD_MAP: MemberName = MemberName("kotlin.collections", "buildMap")
    val INSTANT: ClassName = ClassName("java.time", "Instant")
    val URI: ClassName = ClassName("java.net", "URI")

    /** `Outer.Inner` becomes `Outer_Inner`, so a nested declaration's generated name is still unique. */
    fun generatedName(declaration: KSClassDeclaration, suffix: String): ClassName {
        val simpleNames = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }
            .map { it.simpleName.asString() }
            .toList()
            .asReversed()
        return ClassName(declaration.packageName.asString(), simpleNames.joinToString("_") + suffix)
    }
}

/** Whether this declaration carries the annotation named by [qualifiedName]. */
internal fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean = annotations.any {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

/** The value of [argument] on the annotation named [qualifiedName], or null when absent. */
internal fun KSAnnotated.annotationArgument(qualifiedName: String, argument: String): Any? = annotations
    .firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
    ?.arguments
    ?.firstOrNull { it.name?.asString() == argument }
    ?.value

/** Expands typealiases until a class remains, keeping nullability. */
internal fun KSType.expanded(): KSType {
    var type = this
    while (true) {
        val alias = type.declaration as? KSTypeAlias ?: return type
        val target = alias.type.resolve()
        type = if (type.isMarkedNullable) target.makeNullable() else target
    }
}

/** How one flag or record-field type becomes a `FlagCodecs` expression. */
internal class TypeMapping(private val resolver: Resolver) {

    sealed interface Result {
        /** [placeholder] is the value a record prototype passes for a field with no default. */
        data class Supported(
            val codec: CodeBlock,
            val placeholder: CodeBlock,
            val originatingFiles: List<KSFile>,
        ) : Result

        /** [record] names the `@FlagRecord` class when the type is one, or a list of one. */
        data class Unsupported(val record: String?) : Result

        data class MissingRecordCodec(val record: String, val codec: String) : Result
    }

    /** Maps a non-null [type]; nullability is the caller's decision. */
    fun map(type: KSType): Result {
        val expanded = type.expanded()
        if (expanded.isMarkedNullable) return Result.Unsupported(null)
        val declaration = expanded.declaration as? KSClassDeclaration ?: return Result.Unsupported(null)
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> scalar("boolean", "false")
            "kotlin.Int" -> scalar("int", "0")
            "kotlin.Long" -> scalar("long", "0L")
            "kotlin.Double" -> scalar("double", "0.0")
            "kotlin.Float" -> scalar("float", "0f")
            "kotlin.String" -> scalar("string", "\"\"")
            "kotlin.ByteArray" -> scalar("byteArray", "ByteArray(0)")
            "java.time.Instant" -> Result.Supported(
                CodeBlock.of("%T.instant", Names.FLAG_CODECS),
                CodeBlock.of("%T.EPOCH", Names.INSTANT),
                emptyList(),
            )
            "java.net.URI" -> Result.Supported(
                CodeBlock.of("%T.uri", Names.FLAG_CODECS),
                CodeBlock.of("%T(%S)", Names.URI, ""),
                emptyList(),
            )
            "kotlin.collections.List" -> collection(expanded.arguments[0].type?.resolve(), "list", "emptyList()")
            "kotlin.collections.Map" -> dictionary(expanded)
            Names.FLAG_RECORDS_NAME -> records(expanded.arguments[0].type?.resolve())
            else -> named(declaration)
        }
    }

    private fun scalar(codec: String, placeholder: String) = Result.Supported(
        CodeBlock.of("%T.%N", Names.FLAG_CODECS, codec),
        CodeBlock.of(placeholder),
        emptyList(),
    )

    private fun collection(element: KSType?, function: String, placeholder: String): Result {
        if (element == null) return Result.Unsupported(null)
        return when (val inner = map(element)) {
            is Result.Supported -> Result.Supported(
                CodeBlock.of("%T.%N(%L)", Names.FLAG_CODECS, function, inner.codec),
                CodeBlock.of(placeholder),
                inner.originatingFiles,
            )
            else -> inner
        }
    }

    private fun dictionary(type: KSType): Result {
        val key = type.arguments[0].type?.resolve()?.expanded()
        if (key == null || key.isMarkedNullable || key.declaration.qualifiedName?.asString() != "kotlin.String") {
            return Result.Unsupported(null)
        }
        return collection(type.arguments[1].type?.resolve(), "map", "emptyMap()")
    }

    /**
     * `FlagRecords<R>` needs `RFlagRecord`. It is generated when `R` is a `@FlagRecord` in this module;
     * otherwise it has to exist already, written by hand or generated in the module that declares `R`.
     */
    private fun records(element: KSType?): Result {
        val record = element?.expanded()?.declaration as? KSClassDeclaration ?: return Result.Unsupported(null)
        if (element.isMarkedNullable) return Result.Unsupported(null)
        val codec = Names.generatedName(record, "FlagRecord")
        val annotated = record.hasAnnotation(Names.FLAG_RECORD)
        val generatedHere = annotated && record.origin == Origin.KOTLIN
        val exists = resolver.getClassDeclarationByName(resolver.getKSNameFromString(codec.canonicalName)) != null
        return when {
            generatedHere || exists -> Result.Supported(
                CodeBlock.of("%T.records(%T)", Names.FLAG_CODECS, codec),
                CodeBlock.of("%T()", Names.FLAG_RECORDS),
                listOfNotNull(record.containingFile),
            )
            annotated -> Result.MissingRecordCodec(record.simpleName.asString(), codec.simpleName)
            else -> Result.Unsupported(null)
        }
    }

    private fun named(declaration: KSClassDeclaration): Result {
        if (declaration.hasAnnotation(Names.FLAG_RECORD)) return Result.Unsupported(declaration.simpleName.asString())
        if (declaration.classKind != ClassKind.ENUM_CLASS) return Result.Unsupported(null)
        val supertypes = declaration.getAllSuperTypes().mapNotNull { it.declaration.qualifiedName?.asString() }.toSet()
        val function = when {
            Names.FLAG_STRING_ENUM in supertypes -> "stringEnum"
            Names.FLAG_INT_ENUM in supertypes -> "intEnum"
            else -> "enumByName"
        }
        val enumName = declaration.toClassName()
        return Result.Supported(
            CodeBlock.of("%T.%N(%T.entries)", Names.FLAG_CODECS, function, enumName),
            CodeBlock.of("%T.entries.first()", enumName),
            listOfNotNull(declaration.containingFile),
        )
    }
}
```

`featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/ContainerGenerator.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** One `@FlagContainer` interface, read for generation. */
internal class ContainerModel(
    val name: ClassName,
    val refs: ClassName,
    val spec: ClassName,
    val description: String?,
    val visibility: KModifier,
    val members: List<ContainerMember>,
    val originatingFiles: List<KSFile>,
)

internal sealed interface ContainerMember {
    val name: String
    val description: String

    class Flag(
        override val name: String,
        override val description: String,
        val remoteKey: String?,
        val type: TypeName,
        val codec: CodeBlock,
    ) : ContainerMember

    class Group(
        override val name: String,
        override val description: String,
        val type: ClassName,
        val refs: ClassName,
        val spec: ClassName,
    ) : ContainerMember
}

/**
 * Writes `XRefs` and `XSpec` for one `@FlagContainer` interface `X`. Everything it reads has already
 * passed [Checks.container], so an unsupported type here is a bug in the checks, not in the input.
 */
internal class ContainerGenerator(resolver: Resolver, private val codeGenerator: CodeGenerator) {

    private val types = TypeMapping(resolver)

    fun parse(declaration: KSClassDeclaration): ContainerModel {
        val files = mutableListOf<KSFile>()
        declaration.containingFile?.let(files::add)
        val members = declaration.getDeclaredProperties().mapNotNull { property ->
            when {
                property.hasAnnotation(Names.FLAG_GROUP) -> group(property, files)
                property.hasAnnotation(Names.FLAG) -> flag(property, files)
                else -> null
            }
        }.toList()
        val description = declaration.annotationArgument(Names.FLAG_CONTAINER, "description") as? String
        return ContainerModel(
            name = declaration.toClassName(),
            refs = Names.generatedName(declaration, "Refs"),
            spec = Names.generatedName(declaration, "Spec"),
            description = description?.takeIf { it.isNotEmpty() },
            visibility = declaration.generatedVisibility(),
            members = members,
            originatingFiles = files.distinct(),
        )
    }

    private fun flag(property: KSPropertyDeclaration, files: MutableList<KSFile>): ContainerMember.Flag {
        val type = property.type.resolve()
        val mapped = types.map(type) as TypeMapping.Result.Supported
        files += mapped.originatingFiles
        return ContainerMember.Flag(
            name = property.simpleName.asString(),
            description = property.annotationArgument(Names.FLAG, "description") as? String ?: "",
            remoteKey = (property.annotationArgument(Names.FLAG, "remoteKey") as? String)?.takeIf { it.isNotEmpty() },
            type = type.toTypeName(),
            codec = mapped.codec,
        )
    }

    private fun group(property: KSPropertyDeclaration, files: MutableList<KSFile>): ContainerMember.Group {
        val target = property.type.resolve().expanded().declaration as KSClassDeclaration
        target.containingFile?.let(files::add)
        return ContainerMember.Group(
            name = property.simpleName.asString(),
            description = property.annotationArgument(Names.FLAG_GROUP, "description") as? String ?: "",
            type = target.toClassName(),
            refs = Names.generatedName(target, "Refs"),
            spec = Names.generatedName(target, "Spec"),
        )
    }

    fun generate(model: ContainerModel) {
        FileSpec.builder(model.spec)
            .addType(refs(model))
            .addType(spec(model))
            .build()
            .writeTo(codeGenerator, aggregating = false, originatingKSFiles = model.originatingFiles)
    }

    private fun refs(model: ContainerModel): TypeSpec {
        val properties = model.members.map { member ->
            when (member) {
                is ContainerMember.Flag -> flagRef(model, member)
                is ContainerMember.Group -> PropertySpec.builder(member.name, member.refs)
                    .initializer("%T(prefix.appending(%S))", member.refs, member.name)
                    .build()
            }
        }
        return TypeSpec.classBuilder(model.refs)
            .addModifiers(model.visibility, KModifier.OPEN)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("prefix", Names.FLAG_KEY_PATH).build())
            .addProperties(properties)
            .build()
    }

    /** `FlagRef(path, name, description, remoteKey, codec) { XSpec.flagDefaults.name }`. */
    private fun flagRef(model: ContainerModel, member: ContainerMember.Flag): PropertySpec =
        PropertySpec.builder(member.name, Names.FLAG_REF.parameterizedBy(member.type))
            .initializer(
                "%T(prefix.appending(%S), %S, %S, %S, %L) { %T.flagDefaults.%N }",
                Names.FLAG_REF, member.name, member.name, member.description, member.remoteKey,
                member.codec, model.spec, member.name,
            )
            .build()

    private fun spec(model: ContainerModel): TypeSpec {
        val flags = model.members.filterIsInstance<ContainerMember.Flag>()
        return TypeSpec.objectBuilder(model.spec)
            .addModifiers(model.visibility)
            .superclass(model.refs)
            .addSuperclassConstructorParameter("%T.ROOT", Names.FLAG_KEY_PATH)
            .addSuperinterface(Names.FLAG_CONTAINER_SPEC.parameterizedBy(model.name))
            .addProperty(
                PropertySpec.builder("flagContainerDescription", STRING.copy(nullable = true), KModifier.OVERRIDE)
                    .initializer("%S", model.description)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("flagDefaults", model.name, KModifier.OVERRIDE)
                    .initializer("%L", defaults(model))
                    .build(),
            )
            .addProperty(
                PropertySpec
                    .builder("flagDescriptors", LIST.parameterizedBy(Names.FLAG_SCHEMA_NODE), KModifier.OVERRIDE)
                    .delegate(descriptors(model))
                    .build(),
            )
            .addFunction(
                FunSpec.builder("createFlags")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("lookup", Names.FLAG_LOOKUP)
                    .addParameter("prefix", Names.FLAG_KEY_PATH)
                    .returns(model.name)
                    .addStatement("return Impl(lookup, prefix)")
                    .build(),
            )
            .addType(impl(model, flags))
            .apply { if (flags.isNotEmpty()) addType(keys(flags)) }
            .build()
    }

    /** `object : X`, overriding only the groups: a flag without a default getter fails to compile here. */
    private fun defaults(model: ContainerModel): TypeSpec = TypeSpec.anonymousClassBuilder()
        .addSuperinterface(model.name)
        .addProperties(
            model.members.filterIsInstance<ContainerMember.Group>().map { group ->
                PropertySpec.builder(group.name, group.type, KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return %T.flagDefaults", group.spec).build())
                    .build()
            },
        )
        .build()

    private fun descriptors(model: ContainerModel): CodeBlock {
        val body = CodeBlock.builder().beginControlFlow("%M", Names.LAZY).add("%M(\n", Names.LIST_OF).indent()
        model.members.forEach { member ->
            when (member) {
                is ContainerMember.Flag -> body.add(
                    "%T.Flag(%T.of(%S, %S, %S, %N.codec, flagDefaults.%N)),\n",
                    Names.FLAG_SCHEMA_NODE, Names.FLAG_DESCRIPTOR, member.name, member.description,
                    member.remoteKey, member.name, member.name,
                )
                is ContainerMember.Group -> body.add(
                    "%T.Group(%T(%S, %T(%M(%S)), %S, %T.flagDescriptors.map { it.prefixed(%S) })),\n",
                    Names.FLAG_SCHEMA_NODE, Names.FLAG_GROUP_DESCRIPTOR, member.name, Names.FLAG_KEY_PATH,
                    Names.LIST_OF, member.name, member.description, member.spec, member.name,
                )
            }
        }
        return body.unindent().add(")\n").endControlFlow().build()
    }

    /** Reads through the lookup, falling back to the interface's own default getter via `super<X>`. */
    private fun impl(model: ContainerModel, flags: List<ContainerMember.Flag>): TypeSpec {
        val builder = TypeSpec.classBuilder("Impl")
            .addModifiers(KModifier.PRIVATE)
            .addSuperinterface(model.name)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("flagLookup", Names.FLAG_LOOKUP)
                    .addParameter("flagPrefix", Names.FLAG_KEY_PATH)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("flagLookup", Names.FLAG_LOOKUP, KModifier.PRIVATE)
                    .initializer("flagLookup")
                    .build(),
            )
        if (flags.isNotEmpty()) {
            builder.addProperty(
                PropertySpec.builder("flagKeys", KEYS, KModifier.PRIVATE)
                    .initializer("%T(flagLookup.keyEncoding, flagPrefix)", KEYS)
                    .build(),
            )
        }
        model.members.forEach { member ->
            val property = when (member) {
                is ContainerMember.Flag -> PropertySpec.builder(member.name, member.type, KModifier.OVERRIDE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement(
                                "return flagLookup.%M(flagKeys.%N, %T.%N.codec) { super<%T>.%N }",
                                Names.READ, member.name, model.spec, member.name, model.name, member.name,
                            )
                            .build(),
                    )
                is ContainerMember.Group -> PropertySpec.builder(member.name, member.type, KModifier.OVERRIDE)
                    .initializer("%T.createFlags(flagLookup, flagPrefix.appending(%S))", member.spec, member.name)
            }
            builder.addProperty(property.build())
        }
        return builder.build()
    }

    /** Each flag's key, encoded once per instance rather than on every read. */
    private fun keys(flags: List<ContainerMember.Flag>): TypeSpec = TypeSpec.classBuilder(KEYS)
        .addModifiers(KModifier.PRIVATE)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("encoding", Names.KEY_ENCODING)
                .addParameter("prefix", Names.FLAG_KEY_PATH)
                .build(),
        )
        .addProperties(
            flags.map {
                PropertySpec.builder(it.name, Names.FLAG_KEY)
                    .initializer("encoding.key(prefix.appending(%S))", it.name)
                    .build()
            },
        )
        .build()

    private companion object {
        val KEYS: ClassName = ClassName("", "Keys")
    }
}

/** `internal` when this declaration, or one enclosing it, is internal; `public` otherwise. */
internal fun KSClassDeclaration.generatedVisibility(): KModifier {
    val internal = generateSequence(this) { it.parentDeclaration as? KSClassDeclaration }
        .any { Modifier.INTERNAL in it.modifiers }
    return if (internal) KModifier.INTERNAL else KModifier.PUBLIC
}
```

`featureflag-ksp/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`:

```text
com.andyyhope.featureflag.ksp.FeatureFlagProcessorProvider
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ksp:test --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `ContainerGenerationTest` passes all 8 tests. If a snapshot test fails after an intended change, the assertion prints the generated text: update the `.kt.txt` file only when the new text is right.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml featureflag-ksp
git commit -m "$(cat <<'EOF'
Generate container specs and typed ref trees with featureflag-ksp

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Write the failing generated-container tests in core**

These port the containers of `MacroEdgeCaseTests`, the "computed and initialised properties are left alone" case of `FlagContainerMacroTests`, and `FlagContainerDescriptionTests`. They also add Review Focus 3: a default getter runs on every fallback read. `@com.andyyhope.featureflag.Flag` in `QualifiedFlags` is the Kotlin form of Swift's `ShadowingTests`. A host with its own `Flag` writes the annotation qualified, and the processor matches annotations by their resolved name.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/EdgeCaseFlags.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup

// Ports of the containers in Swift's MacroEdgeCaseTests, FlagContainerMacroTests and
// FlagContainerDescriptionTests, generated by featureflag-ksp.

@FlagContainer
interface NoFlags

@FlagContainer
interface OneTapFlags {
    @Flag(description = "One tap")
    val oneTap: Boolean get() = false
}

@FlagContainer
interface GroupsOnlyFlags {
    @FlagGroup(description = "Express")
    val express: OneTapFlags
}

@FlagContainer
interface TwiceFlags {
    @FlagGroup(description = "First")
    val first: OneTapFlags

    @FlagGroup(description = "Second")
    val second: OneTapFlags
}

@FlagContainer
interface DeepFlags {
    @FlagGroup(description = "A")
    val a: DeepB
}

@FlagContainer
interface DeepB {
    @FlagGroup(description = "B")
    val b: DeepC
}

@FlagContainer
interface DeepC {
    @FlagGroup(description = "C")
    val c: DeepD
}

@FlagContainer
interface DeepD {
    @Flag(description = "Deep")
    val deep: Boolean get() = false
}

fun makeDefault(): Int = listOf(1, 2, 3).sum()

typealias Names = List<String>

@FlagContainer
interface ComplexDefaultsFlags {
    @Flag(description = "From a function")
    val computedDefault: Int get() = makeDefault()

    @Flag(description = "From a list")
    val listDefault: List<String> get() = listOf("a", "b")

    @Flag(description = "From a map")
    val mapDefault: Map<String, Int> get() = mapOf("x" to 1)

    @Flag(description = "From a nested list")
    val nestedDefault: List<List<String>> get() = listOf(listOf("a"))

    @Flag(description = "Through a typealias")
    val aliased: Names get() = listOf("z")
}

@FlagContainer
interface QualifiedFlags {
    @com.andyyhope.featureflag.Flag(description = "Stamp")
    val stamp: java.time.Instant get() = java.time.Instant.EPOCH
}

@FlagContainer
interface MixedFlags {
    @Flag(description = "Only flag")
    val onlyFlag: Boolean get() = false

    val constant: Int get() = 5

    val computed: Int get() = constant * 2

    fun method(): Int = constant * 3

    companion object {
        const val STATIC_VALUE: String = "static"
    }
}

@FlagContainer(description = "Everything the checkout team can turn on")
internal interface DescribedFlags {
    @Flag(description = "Onboarding")
    val newOnboarding: Boolean get() = false
}

/** Counts how often [CountingFlags.counted]'s default getter runs. */
object DefaultReads {
    var count: Int = 0
}

@FlagContainer
interface CountingFlags {
    @Flag(description = "Counted")
    val counted: Int get() = ++DefaultReads.count
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/GeneratedContainerTest.kt`:

```kotlin
package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.flattened
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shapes of container a real codebase produces sooner or later, generated rather than hand-written. */
class GeneratedContainerTest {
    private val nothingStored = object : FlagLookup {
        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null
    }

    @Test fun aContainerWithNoFlagsAtAllWorks() {
        NoFlagsSpec.createFlags(nothingStored)
        assertTrue(NoFlagsSpec.flagDescriptors.isEmpty())
    }

    @Test fun aContainerOfNothingButGroupsWorks() {
        assertEquals(1, GroupsOnlyFlagsSpec.flagDescriptors.size)
        assertEquals(listOf("oneTap"), GroupsOnlyFlagsSpec.flagDescriptors.flattened().map { it.propertyName })
        assertEquals(FlagKey("express.one-tap"), GroupsOnlyFlagsSpec.express.oneTap.key(KeyEncoding.Kebabcase))
    }

    @Test fun deeplyNestedGroupsKeepNesting() {
        assertEquals(FlagKey("a.b.c.deep"), DeepFlagsSpec.a.b.c.deep.key(KeyEncoding.Kebabcase))
        assertEquals(
            listOf(listOf("a", "b", "c", "deep")),
            DeepFlagsSpec.flagDescriptors.flattened().map { it.keyPath.propertyNames },
        )
        assertEquals(false, DeepFlagsSpec.createFlags(nothingStored).a.b.c.deep)
    }

    @Test fun theSameGroupTypeCanBeMountedTwice() {
        assertEquals(FlagKey("first.one-tap"), TwiceFlagsSpec.first.oneTap.key(KeyEncoding.Kebabcase))
        assertEquals(FlagKey("second.one-tap"), TwiceFlagsSpec.second.oneTap.key(KeyEncoding.Kebabcase))
        assertEquals(
            listOf(FlagKey("first.one-tap"), FlagKey("second.one-tap")),
            TwiceFlagsSpec.flagDescriptors.flattened().map { KeyEncoding.Kebabcase.key(it.keyPath) },
        )
    }

    @Test fun complexDefaultExpressionsAreCarriedThrough() {
        val flags = ComplexDefaultsFlagsSpec.createFlags(nothingStored)
        assertEquals(6, flags.computedDefault)
        assertEquals(listOf("a", "b"), flags.listDefault)
        assertEquals(mapOf("x" to 1), flags.mapDefault)
        assertEquals(listOf(listOf("a")), flags.nestedDefault)
        assertEquals(listOf("z"), flags.aliased)
    }

    @Test fun complexDefaultsAppearInDescriptors() {
        val descriptors = ComplexDefaultsFlagsSpec.flagDescriptors.flattened()
        assertEquals(FlagValueBox.Int(6), descriptors.single { it.propertyName == "computedDefault" }.defaultValue)
        assertEquals(
            FlagValueType.Array(FlagValueType.Array(FlagValueType.String)),
            descriptors.single { it.propertyName == "nestedDefault" }.valueType,
        )
        val aliased = descriptors.single { it.propertyName == "aliased" }
        assertEquals(FlagValueType.Array(FlagValueType.String), aliased.valueType)
    }

    @Test fun fullyQualifiedTypesAndAnnotationsWork() {
        assertEquals(FlagKey("stamp"), QualifiedFlagsSpec.stamp.key(KeyEncoding.Kebabcase))
        assertEquals(FlagValueType.Date, QualifiedFlagsSpec.flagDescriptors.flattened().single().valueType)
        assertEquals(Instant.EPOCH, QualifiedFlagsSpec.createFlags(nothingStored).stamp)
    }

    @Test fun nonFlagMembersAreLeftEntirelyAlone() {
        val flags = MixedFlagsSpec.createFlags(nothingStored)
        assertEquals(5, flags.constant)
        assertEquals(10, flags.computed)
        assertEquals(15, flags.method())
        assertEquals("static", MixedFlags.STATIC_VALUE)
        assertEquals(1, MixedFlagsSpec.flagDescriptors.size)
    }

    @Test fun aContainerPublishesItsDescriptionAndOneWithoutSaysNothing() {
        assertEquals("Everything the checkout team can turn on", DescribedFlagsSpec.flagContainerDescription)
        assertEquals("Everything the checkout team can turn on", FlagSchema.of(DescribedFlagsSpec).description)
        assertNull(OneTapFlagsSpec.flagContainerDescription)
    }

    @Test fun aPrefixRootsEveryKeyBeneathIt() {
        val lookup = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? =
                FlagValueBox.Bool(true).takeIf { key == FlagKey("mounted.express.one-tap") }
        }
        assertTrue(GroupsOnlyFlagsSpec.createFlags(lookup, FlagKeyPath(listOf("mounted"))).express.oneTap)
    }

    @Test fun aDefaultGetterRunsOnEveryReadThatFallsBackToIt() {
        // Unlike Swift, where `default:` is evaluated once per container, a Kotlin default is a getter:
        // a non-constant one (Instant.now(), a counter) gives a fresh value on every fallback read.
        DefaultReads.count = 0
        val flags = CountingFlagsSpec.createFlags(nothingStored)
        assertEquals(1, flags.counted)
        assertEquals(2, flags.counted)
        val stored = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = FlagValueBox.Int(7)
        }
        assertEquals(7, CountingFlagsSpec.createFlags(stored).counted)
        assertEquals(2, DefaultReads.count)
    }
}
```

- [ ] **Step 7: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
```

Expected: `BUILD FAILED` at `:featureflag-core:compileTestKotlin`, with `Unresolved reference 'NoFlagsSpec'`, `Unresolved reference 'GroupsOnlyFlagsSpec'` and `Unresolved reference 'CountingFlagsSpec'`.

- [ ] **Step 8: Run the processor over core's tests, and delete the hand-written golden spec**

**Modify:** Replace `featureflag-core/build.gradle.kts` with:

```kotlin
plugins {
    id("featureflag.jvm-library")
    alias(libs.plugins.ksp)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // The processor runs over the test sources only: core itself declares no flags.
    kspTest(project(":featureflag-ksp"))
}
```

```bash
git rm featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenSpecs.kt
```

The processor now writes `GoldenFlagsSpec`, `GoldenCheckoutSpec` and `GoldenExpressSpec` into `featureflag-core/build/generated/ksp/test/kotlin/com/andyyhope/featureflag/golden/`. They use M1a's hand-written `GoldenEndpointFlagRecord` through `FlagCodecs.records(GoldenEndpointFlagRecord)`.

- [ ] **Step 9: Run every test and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `GeneratedContainerTest` passes 11 tests. `FlagContainerTest` (18) and `GoldenSchemaTest` (3) now pass against the generated spec, so the generated golden schema is still byte-identical to Apple's.

- [ ] **Step 10: Commit**

```bash
git add featureflag-core/build.gradle.kts \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/EdgeCaseFlags.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/container/GeneratedContainerTest.kt
git commit -m "$(cat <<'EOF'
Generate core's test containers, including the golden spec, with featureflag-ksp

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task B4: `@FlagRecord` codecs

For each `@FlagRecord` class `R`, the processor writes `public object RFlagRecord : FlagRecordCodec<R>` from the primary constructor (`internal` when `R` is).

- **Fields:** every constructor property, in declaration order, keyed by the property name. A nullable property is an optional field, so it has no default, is left out of the boxes when null, and reads as null when absent. `@FlagRecordKey` sets `isKey` and `keyField`, and `@FlagRecordProperty(key)` sets `decodedName`.
- **Defaults:** a non-optional field with a constructor default takes it from a prototype. The prototype is built by `prototype()`, which passes placeholders (the table in Task B3) for the parameters that have no default, and reads every default off the result.
- **Failure:** if the constructor rejects the placeholders, the shape's first read throws `IllegalStateException` naming the record. The shape is lazy, so that read is when the schema is built.
- **Nested records:** a `FlagRecords` field carries its codec's `recordShape` as `fields`.
- **Key lookup:** when `R` names a key, the processor also writes `operator fun FlagRecords<R>.get(key: K): R?`, comparing stored forms the way Swift's subscript compares boxes. It skips this when the key is an `Int`, because `List.get(index: Int)` would always win.

The documented limit: a default that reads another constructor parameter sees that parameter's placeholder.

**Files:**
- Create: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/RecordGenerator.kt`
- Modify: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessor.kt`, `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt` (M1a's: `records` reads the shape lazily), `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenModel.kt` (M1a's: adds the record annotations)
- Delete: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenRecordCodecs.kt` (M1a's hand-written codecs)
- Test: `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/RecordGenerationTest.kt`, `featureflag-ksp/src/test/resources/expansions/EndpointFlagRecord.kt.txt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordFixtures.kt`, `…/records/FlagRecordGenerationTest.kt`, `…/golden/GoldenRecordTest.kt`

**Interfaces:**
- Consumes:
  - from B3: `Names`, `TypeMapping`, `hasAnnotation`, `annotationArgument` and `generatedVisibility()`;
  - from M1a: `FlagRecordCodec<R>`, `FlagRecordField.of(name, codec, default, isKey, isOptional, decodedName)`, `FlagRecords<R>` and `FlagCodecs.records`.
- Produces:
  - For each `@FlagRecord` class `R` in package `p`, the file `p/RFlagRecord.kt`, holding:
    - `object RFlagRecord : FlagRecordCodec<R>` with `keyField: String?`, a lazy `shape`, `boxes(record)` and `fromBoxes(boxes)`;
    - when `R` has a non-`Int` key of type `K`, `operator fun FlagRecords<R>.get(key: K): R?`.
  - Internal: `RecordGenerator(resolver, codeGenerator)`, `RecordModel`, `RecordField`.

- [ ] **Step 1: Write the failing processor tests**

`featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/RecordGenerationTest.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordGenerationTest {
    @Test fun endpointCodecMatchesItsSnapshot() = compile(
        source(
            "Endpoint.kt",
            """
            enum class Tier { FREE, PRO }

            @FlagRecord
            data class Endpoint(
                @FlagRecordKey val name: String,
                val weight: Int = 1,
                @FlagRecordProperty(key = "memo") val note: String? = null,
                val tier: Tier = Tier.FREE,
            )
            """,
        ),
    ).assertMatchesSnapshot("EndpointFlagRecord.kt")

    @Test fun aRecordWithoutDefaultsBuildsNoPrototype() {
        val compiled = compile(source("Target.kt", "@FlagRecord\ndata class Target(val host: String, val port: Int)"))
        assertTrue(compiled.succeeded, compiled.problems)
        val text = compiled.generated.getValue("TargetFlagRecord.kt")
        assertFalse("prototype" in text, text)
        assertFalse("operator fun" in text, text)
    }

    @Test fun anIntKeyGetsNoLookupOperatorBecauseListGetWouldWin() {
        val compiled = compile(source("Numbered.kt", "@FlagRecord\ndata class Numbered(@FlagRecordKey val id: Int)"))
        assertTrue(compiled.succeeded, compiled.problems)
        assertFalse("operator fun" in compiled.generated.getValue("NumberedFlagRecord.kt"))
    }

    @Test fun aRecordFromAnotherModuleIsFoundByName() {
        val library = compile(source("Remote.kt", "@FlagRecord\ndata class Remote(@FlagRecordKey val name: String)"))
        assertTrue(library.succeeded, library.problems)
        val app = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    @Flag(description = "Remotes")
                    val remotes: FlagRecords<Remote> get() = FlagRecords()
                }
                """,
            ),
            classpath = listOf(library.outputDirectory),
        )
        assertTrue(app.succeeded, app.problems)
        assertEquals(setOf("AppFlagsSpec.kt"), app.generated.keys)
    }
}
```

The expansion snapshot:

```bash
cat > featureflag-ksp/src/test/resources/expansions/EndpointFlagRecord.kt.txt <<'SNAPSHOT'
package app

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.buildMap
import kotlin.collections.listOf
import kotlin.lazy

public object EndpointFlagRecord : FlagRecordCodec<Endpoint> {
  private val nameCodec: FlagCodec<String> = FlagCodecs.string

  private val weightCodec: FlagCodec<Int> = FlagCodecs.int

  private val noteCodec: FlagCodec<String> = FlagCodecs.string

  private val tierCodec: FlagCodec<Tier> = FlagCodecs.enumByName(Tier.entries)

  override val keyField: String? = "name"

  override val shape: List<FlagRecordField> by lazy {
    val defaults = prototype()
    listOf(
      FlagRecordField.of("name", nameCodec, isKey = true),
      FlagRecordField.of("weight", weightCodec, default = defaults.weight),
      FlagRecordField.of("note", noteCodec, isOptional = true, decodedName = "memo"),
      FlagRecordField.of("tier", tierCodec, default = defaults.tier),
    )
  }

  override fun boxes(record: Endpoint): Map<String, FlagValueBox> = buildMap {
    put("name", nameCodec.encode(record.name))
    put("weight", weightCodec.encode(record.weight))
    record.note?.let { put("note", noteCodec.encode(it)) }
    put("tier", tierCodec.encode(record.tier))
  }

  override fun fromBoxes(boxes: Map<String, FlagValueBox>): Endpoint? {
    val record = Endpoint(
      name = boxes["name"]?.let(nameCodec::decode) ?: return null,
      weight = boxes["weight"]?.let(weightCodec::decode) ?: return null,
      note = boxes["note"]?.let(noteCodec::decode),
      tier = boxes["tier"]?.let(tierCodec::decode) ?: return null,
    )
    return record
  }

  private fun prototype(): Endpoint = try {
    Endpoint(name = "")
  } catch (e: Exception) {
    throw IllegalStateException("Could not read the field defaults of 'Endpoint': its constructor threw when 'EndpointFlagRecord' called it with placeholder values (false, 0, \"\", empty, an enum's first case) for the fields that have no default. Give those fields defaults the constructor accepts.", e)
  }
}

public operator fun FlagRecords<Endpoint>.`get`(key: String): Endpoint? {
  val codec = FlagCodecs.string
  val wanted = codec.encode(key)
  return firstOrNull { codec.encode(it.name) == wanted }
}
SNAPSHOT
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ksp:test --tests 'com.andyyhope.featureflag.ksp.RecordGenerationTest' --console=plain
```

Expected: `BUILD FAILED`, and all 4 tests fail:
- `endpointCodecMatchesItsSnapshot` fails with `but was:<null>`.
- The next two throw `NoSuchElementException: Key TargetFlagRecord.kt is missing in the map` (and the same for `NumberedFlagRecord.kt`).
- `aRecordFromAnotherModuleIsFoundByName` fails because the library compiled without its codec, so the app's `TypeMapping` cast fails.

- [ ] **Step 3: Write the record generator and run it**

`featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/RecordGenerator.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** One primary-constructor property of a `@FlagRecord` class. */
internal class RecordField(
    val name: String,
    val type: TypeName,
    val codec: CodeBlock,
    val placeholder: CodeBlock,
    val hasDefault: Boolean,
    val isKey: Boolean,
    val isOptional: Boolean,
    val decodedName: String?,
) {
    val codecProperty: String get() = "${name}Codec"

    /** Optional fields have no fallback: absence is null, as in Swift. */
    val readsDefault: Boolean get() = hasDefault && !isOptional
}

internal class RecordModel(
    val name: ClassName,
    val codec: ClassName,
    val visibility: KModifier,
    val fields: List<RecordField>,
    val originatingFiles: List<KSFile>,
) {
    val key: RecordField? get() = fields.firstOrNull { it.isKey }
}

/**
 * Writes `RFlagRecord : FlagRecordCodec<R>` for one `@FlagRecord` class `R`, plus `FlagRecords<R>[key]`
 * when `R` names a key. Everything it reads has already passed [Checks.record].
 */
internal class RecordGenerator(resolver: Resolver, private val codeGenerator: CodeGenerator) {

    private val types = TypeMapping(resolver)

    fun parse(declaration: KSClassDeclaration): RecordModel {
        val files = mutableListOf<KSFile>()
        declaration.containingFile?.let(files::add)
        val fields = checkNotNull(declaration.primaryConstructor).parameters.map { field(it, files) }
        return RecordModel(
            name = declaration.toClassName(),
            codec = Names.generatedName(declaration, "FlagRecord"),
            visibility = declaration.generatedVisibility(),
            fields = fields,
            originatingFiles = files.distinct(),
        )
    }

    private fun field(parameter: KSValueParameter, files: MutableList<KSFile>): RecordField {
        val type = parameter.type.resolve()
        val mapped = types.map(type.makeNotNullable()) as TypeMapping.Result.Supported
        files += mapped.originatingFiles
        return RecordField(
            name = checkNotNull(parameter.name).asString(),
            type = type.makeNotNullable().toTypeName(),
            codec = mapped.codec,
            placeholder = if (type.isMarkedNullable) CodeBlock.of("null") else mapped.placeholder,
            hasDefault = parameter.hasDefault,
            isKey = parameter.hasAnnotation(Names.FLAG_RECORD_KEY),
            isOptional = type.isMarkedNullable,
            decodedName = parameter.annotationArgument(Names.FLAG_RECORD_PROPERTY, "key") as? String,
        )
    }

    fun generate(model: RecordModel) {
        val file = FileSpec.builder(model.codec).addType(codec(model))
        val key = model.key
        // An Int key would never be reached: List's own get(index: Int) wins over an extension.
        if (key != null && key.type != INT) file.addFunction(lookup(model, key))
        file.build().writeTo(codeGenerator, aggregating = false, originatingKSFiles = model.originatingFiles)
    }

    private fun codec(model: RecordModel): TypeSpec {
        val needsPrototype = model.fields.any { it.readsDefault }
        val builder = TypeSpec.objectBuilder(model.codec)
            .addModifiers(model.visibility)
            .addSuperinterface(Names.FLAG_RECORD_CODEC.parameterizedBy(model.name))
        model.fields.forEach { field ->
            builder.addProperty(
                PropertySpec
                    .builder(field.codecProperty, Names.FLAG_CODEC.parameterizedBy(field.type), KModifier.PRIVATE)
                    .initializer(field.codec)
                    .build(),
            )
        }
        builder.addProperty(
            PropertySpec.builder("keyField", STRING.copy(nullable = true), KModifier.OVERRIDE)
                .initializer("%S", model.key?.name)
                .build(),
        )
        builder.addProperty(
            PropertySpec.builder("shape", LIST.parameterizedBy(Names.FLAG_RECORD_FIELD), KModifier.OVERRIDE)
                .delegate(shape(model, needsPrototype))
                .build(),
        )
        builder.addFunction(boxes(model))
        builder.addFunction(fromBoxes(model))
        if (needsPrototype) builder.addFunction(prototype(model))
        return builder.build()
    }

    private fun shape(model: RecordModel, needsPrototype: Boolean): CodeBlock {
        val body = CodeBlock.builder().beginControlFlow("%M", Names.LAZY)
        if (needsPrototype) body.addStatement("val defaults = prototype()")
        body.add("%M(\n", Names.LIST_OF).indent()
        model.fields.forEach { field ->
            val arguments = mutableListOf(CodeBlock.of("%S", field.name), CodeBlock.of("%N", field.codecProperty))
            if (field.readsDefault) arguments += CodeBlock.of("default = defaults.%N", field.name)
            if (field.isKey) arguments += CodeBlock.of("isKey = true")
            if (field.isOptional) arguments += CodeBlock.of("isOptional = true")
            field.decodedName?.let { arguments += CodeBlock.of("decodedName = %S", it) }
            body.add("%T.of(%L),\n", Names.FLAG_RECORD_FIELD, arguments.joinToCode())
        }
        return body.unindent().add(")\n").endControlFlow().build()
    }

    /** A null optional field is left out, as in Swift: there is no null to store. */
    private fun boxes(model: RecordModel): FunSpec {
        val body = CodeBlock.builder().beginControlFlow("return %M", Names.BUILD_MAP)
        model.fields.forEach { field ->
            if (field.isOptional) {
                body.addStatement(
                    "record.%N?.let { put(%S, %N.encode(it)) }",
                    field.name, field.name, field.codecProperty,
                )
            } else {
                body.addStatement("put(%S, %N.encode(record.%N))", field.name, field.codecProperty, field.name)
            }
        }
        return FunSpec.builder("boxes")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("record", model.name)
            .returns(MAP.parameterizedBy(STRING, Names.FLAG_VALUE_BOX))
            .addCode(body.endControlFlow().build())
            .build()
    }

    /** A record is all of its required fields or nothing; a missing optional one reads as null. */
    private fun fromBoxes(model: RecordModel): FunSpec {
        val body = CodeBlock.builder().add("val record = %T(\n", model.name).indent()
        model.fields.forEach { field ->
            val fallback = if (field.isOptional) "" else " ?: return null"
            body.add("%N = boxes[%S]?.let(%N::decode)$fallback,\n", field.name, field.name, field.codecProperty)
        }
        body.unindent().add(")\n").addStatement("return record")
        return FunSpec.builder("fromBoxes")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("boxes", MAP.parameterizedBy(STRING, Names.FLAG_VALUE_BOX))
            .returns(model.name.copy(nullable = true))
            .addCode(body.build())
            .build()
    }

    /** The record built with placeholders for the fields that have no default, so the rest read their defaults. */
    private fun prototype(model: RecordModel): FunSpec {
        val placeholders = model.fields
            .filterNot { it.hasDefault }
            .map { CodeBlock.of("%N = %L", it.name, it.placeholder) }
        val message = "Could not read the field defaults of '${model.name.simpleName}': its constructor threw when " +
            "'${model.codec.simpleName}' called it with placeholder values (false, 0, \"\", empty, an enum's first " +
            "case) for the fields that have no default. Give those fields defaults the constructor accepts."
        return FunSpec.builder("prototype")
            .addModifiers(KModifier.PRIVATE)
            .returns(model.name)
            .beginControlFlow("return try")
            .addStatement("%T(%L)", model.name, placeholders.joinToCode())
            .nextControlFlow("catch (e: %T)", EXCEPTION)
            .addStatement("throw %T(%S, e)", ILLEGAL_STATE, message)
            .endControlFlow()
            .build()
    }

    /** `records["prod"]`, comparing stored forms the way Swift's `FlagRecords[key]` does. */
    private fun lookup(model: RecordModel, key: RecordField): FunSpec = FunSpec.builder("get")
        .addModifiers(model.visibility, KModifier.OPERATOR)
        .receiver(Names.FLAG_RECORDS.parameterizedBy(model.name))
        .addParameter("key", key.type)
        .returns(model.name.copy(nullable = true))
        .addStatement("val codec = %L", key.codec)
        .addStatement("val wanted = codec.encode(key)")
        .addStatement("return firstOrNull { codec.encode(it.%N) == wanted }", key.name)
        .build()

    private companion object {
        val EXCEPTION: ClassName = ClassName("kotlin", "Exception")
        val ILLEGAL_STATE: ClassName = ClassName("kotlin", "IllegalStateException")
    }
}
```

**Modify:** Replace `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessor.kt` with:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** Generates `XRefs`/`XSpec` for every `@FlagContainer` and `RFlagRecord` for every `@FlagRecord`. */
internal class FeatureFlagProcessor(private val codeGenerator: CodeGenerator) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val records = RecordGenerator(resolver, codeGenerator)
        symbols(resolver, Names.FLAG_RECORD, deferred).forEach { records.generate(records.parse(it)) }
        val containers = ContainerGenerator(resolver, codeGenerator)
        symbols(resolver, Names.FLAG_CONTAINER, deferred).forEach { containers.generate(containers.parse(it)) }
        return deferred
    }

    /** Declarations carrying [annotation] that resolve; the rest wait for a later round. */
    private fun symbols(
        resolver: Resolver,
        annotation: String,
        deferred: MutableList<KSAnnotated>,
    ): List<KSClassDeclaration> {
        val (ready, waiting) = resolver.getSymbolsWithAnnotation(annotation).partition { it.validate() }
        deferred += waiting
        return ready.filterIsInstance<KSClassDeclaration>()
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ksp:test --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `RecordGenerationTest` passes 4 tests and `ContainerGenerationTest` still passes 8. Core is unaffected so far, because none of its records is annotated yet.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ksp
git commit -m "$(cat <<'EOF'
Generate record codecs with featureflag-ksp

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Write the failing record tests in core**

These port what `FlagRecordMacroTests`, `FlagRecordDefaultTests` and `FlagRecordOptionalAndKeyTests` assert about the generated code: fields, defaults, key, optionals, decoded name and the way back. They add Review Focus 2 and 4. `GoldenRecordTest` pins the golden codecs, which must answer the same whether written by hand or generated.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordFixtures.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.FlagRecordProperty
import java.time.Instant

// Ports of the records in Swift's FlagRecordMacroTests, FlagRecordDefaultTests and
// FlagRecordOptionalAndKeyTests, generated by featureflag-ksp.

enum class PaymentKind { CARD, WALLET }

@FlagRecord
data class PaymentMethod(
    @FlagRecordKey val name: String,
    val kind: PaymentKind,
    @FlagRecordProperty(key = "is_enabled") val enabled: Boolean = true,
    val minimumSpend: Double? = null,
)

@FlagRecord
data class Rollout(@FlagRecordKey val startsAt: Instant, var percent: Int = 10) {
    val label: String get() = "$percent% from $startsAt"
}

@FlagRecord
data class Picky(@FlagRecordKey val id: Int, val label: String = "x") {
    init {
        require(id != 0) { "id must not be zero" }
    }
}

@FlagContainer
interface PickyFlags {
    @Flag(description = "Picky records")
    val pickies: FlagRecords<Picky> get() = FlagRecords()
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordGenerationTest.kt`:

```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What featureflag-ksp writes for a @FlagRecord: a shape for the companion, a box per field, and the way back. */
class FlagRecordGenerationTest {
    private val card = PaymentMethod(name = "visa", kind = PaymentKind.CARD, minimumSpend = 5.0)

    @Test fun everyConstructorPropertyIsAFieldInDeclarationOrder() {
        assertEquals(listOf("name", "kind", "enabled", "minimumSpend"), PaymentMethodFlagRecord.shape.map { it.name })
        assertEquals(listOf("startsAt", "percent"), RolloutFlagRecord.shape.map { it.name })
    }

    @Test fun aFieldWrittenWithADefaultCarriesIt() {
        val enabled = PaymentMethodFlagRecord.shape.single { it.name == "enabled" }
        assertEquals(FlagValueBox.Bool(true), enabled.defaultValue)
        assertEquals(FlagValueBox.Int(10), RolloutFlagRecord.shape.single { it.name == "percent" }.defaultValue)
        assertNull(PaymentMethodFlagRecord.shape.single { it.name == "name" }.defaultValue)
    }

    @Test fun theKeyIsMarkedAndPublished() {
        assertEquals("name", PaymentMethodFlagRecord.keyField)
        assertEquals(listOf(true, false, false, false), PaymentMethodFlagRecord.shape.map { it.isKey })
    }

    @Test fun aNullableFieldIsOptionalWithNoDefault() {
        val spend = PaymentMethodFlagRecord.shape.single { it.name == "minimumSpend" }
        assertTrue(spend.isOptional)
        assertNull(spend.defaultValue)
        assertEquals(FlagValueType.Double, spend.type)
    }

    @Test fun aCustomKeyIsCarriedAsTheDecodedName() {
        val enabled = PaymentMethodFlagRecord.shape.single { it.name == "enabled" }
        assertEquals("is_enabled", enabled.decodedName)
        assertEquals("is_enabled", enabled.payloadName)
    }

    @Test fun anEnumFieldPublishesItsCases() {
        val kind = PaymentMethodFlagRecord.shape.single { it.name == "kind" }
        assertEquals(listOf(FlagValueBox.String("CARD"), FlagValueBox.String("WALLET")), kind.cases)
    }

    @Test fun boxesAreKeyedByPropertyNameAndLeaveOutANullOptional() {
        assertEquals(
            mapOf(
                "name" to FlagValueBox.String("visa"),
                "kind" to FlagValueBox.String("CARD"),
                "enabled" to FlagValueBox.Bool(true),
                "minimumSpend" to FlagValueBox.Double(5.0),
            ),
            PaymentMethodFlagRecord.boxes(card),
        )
        assertTrue("minimumSpend" !in PaymentMethodFlagRecord.boxes(card.copy(minimumSpend = null)))
    }

    @Test fun aRecordIsRebuiltFromItsBoxes() {
        val boxes = PaymentMethodFlagRecord.boxes(card)
        assertEquals(card, PaymentMethodFlagRecord.fromBoxes(boxes))
        assertEquals(card.copy(minimumSpend = null), PaymentMethodFlagRecord.fromBoxes(boxes - "minimumSpend"))
    }

    @Test fun aMissingOrMistypedRequiredFieldRefusesTheRecord() {
        val boxes = PaymentMethodFlagRecord.boxes(card)
        assertNull(PaymentMethodFlagRecord.fromBoxes(boxes - "kind"))
        assertNull(PaymentMethodFlagRecord.fromBoxes(boxes + ("enabled" to FlagValueBox.Int(1))))
    }

    @Test fun recordsAreFoundByAKeyOfAnyTypeButInt() {
        val at = Instant.ofEpochSecond(1_767_225_600)
        val rollouts = FlagRecords(listOf(Rollout(at, 50)))
        assertEquals(50, rollouts[at]?.percent)
        assertNull(rollouts[Instant.EPOCH])
        val cards = FlagRecords(listOf(card))
        assertEquals(card, cards["visa"])
    }

    @Test fun anIntKeyedListStillIndexesByPosition() {
        // An Int key gets no `get`: List's own get(index) would always win, so records[1] stays positional.
        val picky = FlagRecords(listOf(Picky(id = 7), Picky(id = 9)))
        assertEquals(9, picky[1].id)
    }

    @Test fun aConstructorThatRejectsThePlaceholdersFailsNamingTheRecord() {
        val error = assertFailsWith<IllegalStateException> { PickyFlagRecord.shape }
        assertEquals(
            "Could not read the field defaults of 'Picky': its constructor threw when 'PickyFlagRecord' called it " +
                "with placeholder values (false, 0, \"\", empty, an enum's first case) for the fields that have no " +
                "default. Give those fields defaults the constructor accepts.",
            error.message,
        )
        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test fun aRecordThatRejectsItsPlaceholdersFailsWhenTheSchemaIsBuiltNotWhenItsSpecLoads() {
        val nothingStored = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null
        }
        assertEquals(FlagRecords(), PickyFlagsSpec.createFlags(nothingStored).pickies)
        val error = assertFailsWith<IllegalStateException> { FlagSchema.of(PickyFlagsSpec) }
        assertTrue("'Picky'" in error.message.orEmpty(), error.message)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenRecordTest.kt`:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The golden record codecs, hand-written in B2 and generated from B4: both must give these answers. */
class GoldenRecordTest {
    private val tierCases = listOf("free", "pro", "enterprise").map { FlagValueBox.String(it) }

    /** An empty record list, as Swift stores it. */
    private val emptyRecordList = FlagValueBox.String("[]")

    @Test fun theEndpointShapeCarriesKeysDefaultsOptionalsAndNestedFields() {
        val targets = listOf(
            FlagRecordField("host", FlagValueType.String),
            FlagRecordField("port", FlagValueType.Int),
        )
        assertEquals(targets, GoldenTargetFlagRecord.shape)
        assertEquals(
            listOf(
                FlagRecordField("name", FlagValueType.String, isKey = true),
                FlagRecordField("url", FlagValueType.Url),
                FlagRecordField("weight", FlagValueType.Int, defaultValue = FlagValueBox.Int(1)),
                FlagRecordField("note", FlagValueType.String, isOptional = true, decodedName = "memo"),
                FlagRecordField("kind", FlagValueType.String, tierCases, defaultValue = FlagValueBox.String("free")),
                FlagRecordField("targets", FlagValueType.String, defaultValue = emptyRecordList, fields = targets),
            ),
            GoldenEndpointFlagRecord.shape,
        )
        assertEquals("name", GoldenEndpointFlagRecord.keyField)
        assertNull(GoldenTargetFlagRecord.keyField)
    }

    @Test fun aNilOptionalIsLeftOutAndAMissingOneReadsAsNil() {
        val endpoint = GoldenEndpoint(name = "prod", url = URI("https://prod.example.com"))
        val boxes = GoldenEndpointFlagRecord.boxes(endpoint)
        assertEquals(setOf("name", "url", "weight", "kind", "targets"), boxes.keys)
        val noted = GoldenEndpointFlagRecord.boxes(endpoint.copy(note = "memo text"))
        assertEquals(FlagValueBox.String("memo text"), noted["note"])
        assertNull(GoldenEndpointFlagRecord.fromBoxes(boxes - "url"))
    }

    @Test fun aTargetRoundTripsThroughItsBoxes() {
        val target = GoldenTarget("example.com", 443)
        assertEquals(target, GoldenTargetFlagRecord.fromBoxes(GoldenTargetFlagRecord.boxes(target)))
    }

    @Test fun recordsAreFoundByTheirKey() {
        val endpoints = FlagRecords(
            listOf(
                GoldenEndpoint("prod", URI("https://prod.example.com")),
                GoldenEndpoint("staging", URI("https://staging.example.com")),
            ),
        )
        assertEquals(URI("https://staging.example.com"), endpoints["staging"]?.url)
        assertNull(endpoints["missing"])
        assertEquals("prod", endpoints[0].name)
    }
}
```

- [ ] **Step 7: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
```

Expected: `BUILD FAILED` at `:featureflag-core:compileTestKotlin`, with two errors at `golden/GoldenRecordTest.kt`: `Argument type mismatch: actual type is 'String', but 'Int' was expected.` `endpoints["staging"]` has no `get(key: String)` yet, so it resolves to `List.get(index: Int)`.

- [ ] **Step 8: Annotate the golden records, delete their hand-written codecs, and read record shapes lazily**

**Modify:** Replace `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenModel.kt` with:

```kotlin
package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.FlagRecordProperty
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

// The Kotlin mirror of fixture-generator/Sources/GoldenModel/GoldenFlags.swift (records and enums).
// featureflag-ksp generates GoldenEndpointFlagRecord and GoldenTargetFlagRecord from these.

enum class GoldenTier(override val flagRawValue: String) : FlagStringEnum {
    FREE("free"),
    PRO("pro"),
    ENTERPRISE("enterprise"),
}

enum class GoldenLevel(override val flagRawValue: Long) : FlagIntEnum { ONE(1), TWO(2), THREE(3) }

@FlagRecord
data class GoldenEndpoint(
    @FlagRecordKey val name: String,
    val url: URI,
    val weight: Int = 1,
    @FlagRecordProperty(key = "memo") val note: String? = null,
    val kind: GoldenTier = GoldenTier.FREE,
    val targets: FlagRecords<GoldenTarget> = FlagRecords(),
)

@FlagRecord
data class GoldenTarget(val host: String, val port: Int)
```

```bash
git rm featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenRecordCodecs.kt
```

**Modify** M1a Task A11's `FlagCodecs.kt`, so a record codec's shape is read on use. In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt`, inside `records(codec)`, replace

```kotlin
        override val recordShape: List<FlagRecordField> = codec.shape
```

with

```kotlin
        // Read on use: a record whose prototype throws must fail when a schema is built, not while
        // the spec object that holds this codec initialises (which would be ExceptionInInitializerError).
        override val recordShape: List<FlagRecordField> get() = codec.shape
```

- [ ] **Step 9: Run every test and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times:
- `FlagRecordGenerationTest` passes 13 tests and `GoldenRecordTest` 4.
- M1a's `RecordJsonTest`, `FlagRecordsCodecTest` and `RecordsGoldenTest` now run against the generated golden codecs.
- `GoldenSchemaTest` still matches Apple byte for byte.

- [ ] **Step 10: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenModel.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/golden/GoldenRecordTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/RecordFixtures.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordGenerationTest.kt
git commit -m "$(cat <<'EOF'
Generate core's record codecs, including the golden ones, with featureflag-ksp

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task B5: Diagnostics

`Checks` runs before generation and reports at the declaration that is wrong, so the author never meets an error inside generated code. A container or record with any problem is not generated. A group cycle is reported on every group in it, and no container in the cycle is generated. Each test asserts the message and its `file:line`. The line is the line of the declaration's keyword, not of an annotation above it, and the body of `source(…)` starts at line 10.

**Ported from Swift** (`FlagContainerDiagnostic`, `FlagRecordDiagnostic`). The wording is kept, and only the syntax it quotes is Kotlin's:

| Swift | Kotlin message | Test |
|---|---|---|
| `structsOnly` | `'@FlagContainer' can only be applied to an interface` | `aClassIsRejected` |
| `unattributedStoredProperty` | `'<name>' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. Add '@FlagGroup(description = …)' …, '@Flag(description = …)' and a default getter …, or give it a getter if it is neither` | `aContainerPropertyWithoutFlagGroupIsDiagnosedWhereItIsWritten` |
| `recordListNeedsFlag` (unattributed, and on `@FlagGroup`) | `'<name>' holds a list of records, which is a flag's value rather than a nested container, so it needs '@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'` | `aRecordListWithoutAnAnnotationIsToldToUseFlag`, `aRecordListMarkedAsAGroupIsToldTheSameThing` |
| `defaultRequired` | `'@Flag' needs a 'default' value to fall back to: give '<name>' a getter, as in 'val <name>: <Type> get() = …'` | `aFlagWithoutADefaultGetterIsDiagnosed` |
| `optionalUnsupported` | verbatim | `anOptionalFlagIsRejectedWithAnExplanation` |
| record `structsOnly` | `'@FlagRecord' can only be applied to a class with a primary constructor` | `anInterfaceIsRejected`, `aClassWithoutAPrimaryConstructorIsRejected` |
| `fieldsRequired` | `'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor nothing to show` | `aRecordWithNoFieldsIsDiagnosed` |
| `oneKeyOnly` | verbatim | `twoFieldsMarkedAsTheKeyIsDiagnosed` |
| `keyCannotBeOptional` | verbatim | `anOptionalKeyIsDiagnosed` |
| the `unavailable` `FlagValue` conformance on a record | `a record is stored as a list — declare the flag as 'FlagRecords<R>' rather than 'R' or 'List<R>'` (and "the field" for a record field) | `aRecordDeclaredAsAFlagIsToldToUseFlagRecords`, `aRecordFieldHoldingARecordIsToldToUseFlagRecords` |

**Kotlin-only:** these cover a `var` flag, an extension or private flag, a group that is not a container, a group with a getter, an unsupported value type (with the record-specific wording above), an abstract function, the reserved names, the container's shape (private, type parameters, supertypes), a group cycle, a param that is not a property, a private record, an unsupported field type, an empty decoded name, and a group or record from a module without the processor. Each has a test named after it below.

**Dropped, because Kotlin already enforces them:**

| Swift diagnostic | Why it has no Kotlin counterpart |
|---|---|
| `typeAnnotationRequired` (containers) | KSP resolves an inferred type (`val x get() = false`). A property with neither a type nor a getter is already a compile error: "This property must either have a type annotation, be initialized or be delegated". |
| `storedPropertyRequired` | An interface property cannot have an initializer ("Property initializers are not allowed in interfaces"), and a getter is the default rather than a mistake. |
| `descriptionRequired` | `description` is a required annotation parameter, so kotlinc reports "No value passed for parameter 'description'". |
| `typeAnnotationRequired` (records) | Every constructor parameter declares its type. |
| `oneFieldPerLine` | Constructor parameters are declared one at a time; `var host: String, port: String` cannot be written. |

**Files:**
- Create: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/Diagnostics.kt`
- Modify: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessorProvider.kt`, `…/ksp/FeatureFlagProcessor.kt`, `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/KspHarness.kt` (appends `compileWithoutProcessor`)
- Test: `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/ContainerDiagnosticTest.kt`, `…/ksp/RecordDiagnosticTest.kt`

**Interfaces:**
- Consumes: from B3/B4, `Names`, `TypeMapping`, `expanded()`, `hasAnnotation`, `annotationArgument`, `ContainerGenerator` and `RecordGenerator`.
- Produces:
  - Internal: `object Diagnostics` (every message), `class Checks(resolver, logger)` with `container(declaration): Boolean`, `record(declaration): Boolean` and `cycles(containers): Set<KSClassDeclaration>`. Also `KSClassDeclaration.isHidden()`, `KSType.render()` and `KSType.isRecordList()`.
  - `FeatureFlagProcessor(codeGenerator, logger)`.
  - Test helper: `compileWithoutProcessor(vararg sources): File`.

- [ ] **Step 1: Write the failing diagnostic tests**

**Modify:** Append to `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/KspHarness.kt`:

```kotlin
/** Compiles [sources] without KSP, as a module that never applied the processor; returns its classes. */
internal fun compileWithoutProcessor(vararg sources: SourceFile): File {
    val result = KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        jvmTarget = "1.8"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
    check(result.exitCode == KotlinCompilation.ExitCode.OK) { result.messages }
    return result.outputDirectory
}
```

`featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/ContainerDiagnosticTest.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Misuse is explained where it is written, never as an error inside generated code. Ports
 * Swift's FlagContainerDiagnosticTests; the Kotlin-only cases follow. Body line 1 is file line 10.
 */
class ContainerDiagnosticTest {
    private fun errors(body: String): List<Reported> = compile(source("AppFlags.kt", body)).errors

    private fun at(line: Int, message: String) = Reported("AppFlags.kt", line, message)

    @Test fun aClassIsRejected() = assertEquals(
        listOf(at(11, "'@FlagContainer' can only be applied to an interface")),
        errors(
            """
            @FlagContainer
            class AppFlags
            """,
        ),
    )

    @Test fun aContainerPropertyWithoutFlagGroupIsDiagnosedWhereItIsWritten() = assertEquals(
        listOf(
            at(
                14,
                "'checkout' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. " +
                    "Add '@FlagGroup(description = …)' if it is a nested container, '@Flag(description = …)' and a " +
                    "default getter if it is a value, or give it a getter if it is neither",
            ),
        ),
        errors(
            """
            interface CheckoutFlags

            @FlagContainer
            interface AppFlags {
                val checkout: CheckoutFlags
            }
            """,
        ),
    )

    @Test fun aRecordListWithoutAnAnnotationIsToldToUseFlag() = assertEquals(
        listOf(
            at(
                15,
                "'endpoints' holds a list of records, which is a flag's value rather than a nested container, so it " +
                    "needs '@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(val name: String)

            @FlagContainer
            interface AppFlags {
                val endpoints: FlagRecords<Endpoint>
            }
            """,
        ),
    )

    @Test fun aRecordListMarkedAsAGroupIsToldTheSameThing() = assertEquals(
        listOf(
            at(
                16,
                "'endpoints' holds a list of records, which is a flag's value rather than a nested container, so it " +
                    "needs '@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(val name: String)

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Endpoints")
                val endpoints: FlagRecords<Endpoint>
            }
            """,
        ),
    )

    @Test fun propertiesWithGettersAndFunctionsWithBodiesAreLeftAlone() {
        val compiled = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    val notes: String get() = "scratch"
                    fun summary(): String = "none"
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
    }

    @Test fun aFlagWithoutADefaultGetterIsDiagnosed() = assertEquals(
        listOf(
            at(
                13,
                "'@Flag' needs a 'default' value to fall back to: give 'newOnboarding' a getter, as in " +
                    "'val newOnboarding: Boolean get() = …'",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "New onboarding")
                val newOnboarding: Boolean
            }
            """,
        ),
    )

    @Test fun anOptionalFlagIsRejectedWithAnExplanation() = assertEquals(
        listOf(
            at(
                13,
                "flags cannot be optional: a flag always has a value, because 'default' is what it falls back to. " +
                    "Use a sentinel the type already has, or an enum with a case for 'unset'",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "Endpoint")
                val endpoint: String? get() = null
            }
            """,
        ),
    )

    @Test fun aVarFlagIsRejected() = assertEquals(
        listOf(
            at(
                13,
                "'newOnboarding' must be a 'val': flags are read through a pole and changed with an override, " +
                    "never by assignment",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "New onboarding")
                var newOnboarding: Boolean
            }
            """,
        ),
    )

    @Test fun anExtensionFlagIsRejected() = assertEquals(
        listOf(at(13, "'shouted' must be a plain property the generated code can override: not private, and without a receiver")),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "Shouted")
                val String.shouted: Boolean get() = false
            }
            """,
        ),
    )

    @Test fun aGroupThatIsNotAContainerIsRejected() = assertEquals(
        listOf(at(15, "'checkout' is a '@FlagGroup', but 'CheckoutFlags' is not a '@FlagContainer' interface")),
        errors(
            """
            interface CheckoutFlags

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Checkout")
                val checkout: CheckoutFlags
            }
            """,
        ),
    )

    @Test fun aGroupWithAGetterIsRejected() = assertEquals(
        listOf(at(16, "'checkout' is a '@FlagGroup', so the generated code provides it: remove its getter")),
        errors(
            """
            @FlagContainer
            interface CheckoutFlags

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Checkout")
                val checkout: CheckoutFlags get() = error("unused")
            }
            """,
        ),
    )

    @Test fun anUnsupportedTypeIsNamed() = assertEquals(
        listOf(
            at(
                13,
                "'tags' has type 'Set<String>', which a flag cannot hold. Use Boolean, Int, Long, Double, Float, " +
                    "String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or FlagRecords of " +
                    "a '@FlagRecord' class",
            ),
            at(
                15,
                "'limits' has type 'Map<Int, String>', which a flag cannot hold. Use Boolean, Int, Long, Double, " +
                    "Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or " +
                    "FlagRecords of a '@FlagRecord' class",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "Tags")
                val tags: Set<String> get() = emptySet()
                @Flag(description = "Limits")
                val limits: Map<Int, String> get() = emptyMap()
            }
            """,
        ),
    )

    @Test fun aRecordDeclaredAsAFlagIsToldToUseFlagRecords() = assertEquals(
        listOf(
            at(
                16,
                "a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' " +
                    "or 'List<Endpoint>'",
            ),
            at(
                18,
                "a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' " +
                    "or 'List<Endpoint>'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(val name: String)

            @FlagContainer
            interface AppFlags {
                @Flag(description = "One")
                val endpoint: Endpoint get() = Endpoint("prod")
                @Flag(description = "Many")
                val endpoints: List<Endpoint> get() = emptyList()
            }
            """,
        ),
    )

    @Test fun anAbstractFunctionIsRejected() = assertEquals(
        listOf(
            at(
                12,
                "'refresh' is an abstract function, which the generated implementation cannot provide: " +
                    "'@FlagContainer' interfaces declare flags and groups as properties. Give it a body, or move it " +
                    "elsewhere",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                fun refresh()
            }
            """,
        ),
    )

    @Test fun reservedNamesAreRejected() = assertEquals(
        listOf("flagDefaults", "flagDescriptors", "flagContainerDescription", "createFlags", "flagLookup", "flagKeys")
            .mapIndexed { index, name ->
                at(13 + 2 * index, "'$name' is reserved for the code generated for 'AppFlags'. Rename the property")
            },
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "x")
                val flagDefaults: Boolean get() = false
                @Flag(description = "x")
                val flagDescriptors: Boolean get() = false
                @Flag(description = "x")
                val flagContainerDescription: Boolean get() = false
                @Flag(description = "x")
                val createFlags: Boolean get() = false
                @Flag(description = "x")
                val flagLookup: Boolean get() = false
                @Flag(description = "x")
                val flagKeys: Boolean get() = false
            }
            """,
        ),
    )

    @Test fun theContainerShapeIsChecked() = assertEquals(
        listOf(
            at(11, "'@FlagContainer' interface 'Hidden' cannot be private: the generated 'HiddenSpec' has to implement it"),
            at(
                14,
                "'@FlagContainer' interface 'Generic' cannot have type parameters: every flag's type has to be known " +
                    "when the schema is published",
            ),
            at(
                19,
                "'@FlagContainer' interface 'Extends' cannot extend other interfaces: declare its flags and groups " +
                    "directly, and nest shared ones with '@FlagGroup'",
            ),
        ),
        errors(
            """
            @FlagContainer
            private interface Hidden

            @FlagContainer
            interface Generic<T>

            interface Base

            @FlagContainer
            interface Extends : Base
            """,
        ),
    )

    @Test fun aGroupCycleIsReportedOnEveryGroupInIt() = assertEquals(
        listOf(
            at(13, "'@FlagGroup' 'b' makes 'A' contain itself (A → B → A), so its flags could never finish building"),
            at(19, "'@FlagGroup' 'a' makes 'B' contain itself (B → A → B), so its flags could never finish building"),
            at(25, "'@FlagGroup' 'c' makes 'C' contain itself (C → C), so its flags could never finish building"),
        ),
        errors(
            """
            @FlagContainer
            interface A {
                @FlagGroup(description = "B")
                val b: B
            }

            @FlagContainer
            interface B {
                @FlagGroup(description = "A")
                val a: A
            }

            @FlagContainer
            interface C {
                @FlagGroup(description = "C")
                val c: C
            }
            """,
        ),
    )

    @Test fun aGroupFromAModuleWithoutTheProcessorIsExplained() {
        val library = compileWithoutProcessor(
            source(
                "SharedFlags.kt",
                """
                @FlagContainer
                interface SharedFlags {
                    @Flag(description = "Shared")
                    val shared: Boolean get() = true
                }
                """,
            ),
        )
        assertEquals(
            listOf(
                at(
                    13,
                    "'shared' is a '@FlagGroup' of 'SharedFlags', but 'SharedFlagsSpec' does not exist: apply the " +
                        "featureflag-ksp processor to the module that declares 'SharedFlags'",
                ),
            ),
            compile(
                source(
                    "AppFlags.kt",
                    """
                    @FlagContainer
                    interface AppFlags {
                        @FlagGroup(description = "Shared")
                        val shared: SharedFlags
                    }
                    """,
                ),
                classpath = listOf(library),
            ).errors,
        )
    }
}
```

`featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/RecordDiagnosticTest.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ports Swift's FlagRecordMacroTests diagnostics; the Kotlin-only cases follow. Body line 1 is file line 10. */
class RecordDiagnosticTest {
    private fun errors(body: String): List<Reported> = compile(source("Endpoint.kt", body)).errors

    private fun at(line: Int, message: String) = Reported("Endpoint.kt", line, message)

    @Test fun anInterfaceIsRejected() = assertEquals(
        listOf(at(11, "'@FlagRecord' can only be applied to a class with a primary constructor")),
        errors(
            """
            @FlagRecord
            interface Endpoint
            """,
        ),
    )

    @Test fun aClassWithoutAPrimaryConstructorIsRejected() = assertEquals(
        listOf(at(11, "'@FlagRecord' can only be applied to a class with a primary constructor")),
        errors(
            """
            @FlagRecord
            class Endpoint {
                constructor(name: String)
            }
            """,
        ),
    )

    @Test fun aPrivateRecordIsRejected() = assertEquals(
        listOf(at(11, "'@FlagRecord' class 'Endpoint' cannot be private: the generated 'EndpointFlagRecord' has to construct it")),
        errors(
            """
            @FlagRecord
            private data class Endpoint(val name: String)
            """,
        ),
    )

    @Test fun aRecordWithNoFieldsIsDiagnosed() = assertEquals(
        listOf(
            at(
                11,
                "'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor " +
                    "nothing to show",
            ),
        ),
        errors(
            """
            @FlagRecord
            class Endpoint()
            """,
        ),
    )

    @Test fun aParameterThatIsNotAPropertyIsDiagnosed() = assertEquals(
        listOf(
            at(
                11,
                "'name' is a constructor parameter but not a property, so a record could not be written back out. " +
                    "Declare it 'val name'",
            ),
        ),
        errors(
            """
            @FlagRecord
            class Endpoint(name: String) {
                val upper: String = name.uppercase()
            }
            """,
        ),
    )

    @Test fun twoFieldsMarkedAsTheKeyIsDiagnosed() = assertEquals(
        listOf(
            at(
                11,
                "a record has one key at most, and name, region are all marked '@FlagRecordKey' — the key is the " +
                    "single field that tells one record from another",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                @FlagRecordKey val name: String,
                @FlagRecordKey val region: String,
            )
            """,
        ),
    )

    @Test fun anOptionalKeyIsDiagnosed() = assertEquals(
        listOf(
            at(
                12,
                "a record's key cannot be optional: it is the field that tells one record from another, so every " +
                    "record has to carry it",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                @FlagRecordKey val name: String?,
                val url: String,
            )
            """,
        ),
    )

    @Test fun anUnsupportedFieldTypeIsNamed() = assertEquals(
        listOf(
            at(
                12,
                "record field 'tags' has type 'Set<String>', which a record field cannot hold. Use Boolean, Int, " +
                    "Long, Double, Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of " +
                    "those, or FlagRecords of a '@FlagRecord' class; a nullable type makes the field optional",
            ),
            at(
                13,
                "record field 'maybe' has type 'List<String?>', which a record field cannot hold. Use Boolean, Int, " +
                    "Long, Double, Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of " +
                    "those, or FlagRecords of a '@FlagRecord' class; a nullable type makes the field optional",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                val tags: Set<String>,
                val maybe: List<String?>,
            )
            """,
        ),
    )

    @Test fun aRecordFieldHoldingARecordIsToldToUseFlagRecords() = assertEquals(
        listOf(
            at(
                15,
                "a record is stored as a list — declare the field as 'FlagRecords<Target>' rather than 'Target' or " +
                    "'List<Target>'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Target(val host: String)

            @FlagRecord
            data class Endpoint(
                val target: Target,
            )
            """,
        ),
    )

    @Test fun anEmptyDecodedNameIsDiagnosed() = assertEquals(
        listOf(
            at(
                12,
                "'@FlagRecordProperty' on 'name' needs a non-empty 'key': it is the name the field is read from in " +
                    "a payload",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                @FlagRecordProperty(key = "") val name: String,
            )
            """,
        ),
    )

    @Test fun aRecordFromAModuleWithoutTheProcessorIsExplained() {
        val library = compileWithoutProcessor(source("Remote.kt", "@FlagRecord\ndata class Remote(val name: String)"))
        assertEquals(
            listOf(
                Reported(
                    "AppFlags.kt",
                    13,
                    "'RemoteFlagRecord' does not exist: apply the featureflag-ksp processor to the module that " +
                        "declares 'Remote'",
                ),
            ),
            compile(
                source(
                    "AppFlags.kt",
                    """
                    @FlagContainer
                    interface AppFlags {
                        @Flag(description = "Remotes")
                        val remotes: FlagRecords<Remote> get() = FlagRecords()
                    }
                    """,
                ),
                classpath = listOf(library),
            ).errors,
        )
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ksp:test --tests '*DiagnosticTest' --console=plain
```

Expected: `BUILD FAILED`. 28 of the 29 tests fail, each with `expected:<[Reported(…)]> but was:<[]>`: the processor reports nothing, and the invalid code it generates fails to compile instead. Only `propertiesWithGettersAndFunctionsWithBodiesAreLeftAlone` passes.

- [ ] **Step 3: Write the checks and run them before generation**

`featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/Diagnostics.kt`:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin

/**
 * Every message the processor reports. The wording follows the Swift macros' diagnostics
 * (`FlagContainerDiagnostic`, `FlagRecordDiagnostic`) wherever the mistake is the same one.
 */
internal object Diagnostics {

    // Containers

    const val INTERFACES_ONLY: String = "'@FlagContainer' can only be applied to an interface"

    const val OPTIONAL_UNSUPPORTED: String =
        "flags cannot be optional: a flag always has a value, because 'default' is what it falls back to. " +
            "Use a sentinel the type already has, or an enum with a case for 'unset'"

    fun containerPrivate(name: String): String =
        "'@FlagContainer' interface '$name' cannot be private: the generated '${name}Spec' has to implement it"

    fun containerTypeParameters(name: String): String =
        "'@FlagContainer' interface '$name' cannot have type parameters: " +
            "every flag's type has to be known when the schema is published"

    fun containerSupertypes(name: String): String =
        "'@FlagContainer' interface '$name' cannot extend other interfaces: declare its flags and groups " +
            "directly, and nest shared ones with '@FlagGroup'"

    fun abstractFunction(name: String): String =
        "'$name' is an abstract function, which the generated implementation cannot provide: " +
            "'@FlagContainer' interfaces declare flags and groups as properties. Give it a body, or move it elsewhere"

    fun reservedName(name: String, container: String): String =
        "'$name' is reserved for the code generated for '$container'. Rename the property"

    fun unattributedProperty(name: String): String =
        "'$name' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. " +
            "Add '@FlagGroup(description = …)' if it is a nested container, '@Flag(description = …)' and a " +
            "default getter if it is a value, or give it a getter if it is neither"

    fun recordListNeedsFlag(name: String): String =
        "'$name' holds a list of records, which is a flag's value rather than a nested container, so it needs " +
            "'@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'"

    fun defaultRequired(name: String, type: String): String =
        "'@Flag' needs a 'default' value to fall back to: give '$name' a getter, as in 'val $name: $type get() = …'"

    fun mutableProperty(name: String): String =
        "'$name' must be a 'val': flags are read through a pole and changed with an override, never by assignment"

    fun propertyShape(name: String): String =
        "'$name' must be a plain property the generated code can override: not private, and without a receiver"

    fun groupNotAContainer(name: String, type: String): String =
        "'$name' is a '@FlagGroup', but '$type' is not a '@FlagContainer' interface"

    fun groupNotGenerated(name: String, type: String, spec: String): String =
        "'$name' is a '@FlagGroup' of '$type', but '$spec' does not exist: " +
            "apply the featureflag-ksp processor to the module that declares '$type'"

    fun groupHasGetter(name: String): String =
        "'$name' is a '@FlagGroup', so the generated code provides it: remove its getter"

    fun groupCycle(name: String, container: String, path: String): String =
        "'@FlagGroup' '$name' makes '$container' contain itself ($path), so its flags could never finish building"

    fun unsupportedFlagType(name: String, type: String): String =
        "'$name' has type '$type', which a flag cannot hold. Use Boolean, Int, Long, Double, Float, String, " +
            "ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or FlagRecords of a " +
            "'@FlagRecord' class"

    fun recordAsFlag(record: String): String =
        "a record is stored as a list — declare the flag as 'FlagRecords<$record>' rather than '$record' or " +
            "'List<$record>'"

    // Records

    const val RECORD_CLASS_ONLY: String = "'@FlagRecord' can only be applied to a class with a primary constructor"

    const val FIELDS_REQUIRED: String =
        "'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor nothing to show"

    const val KEY_CANNOT_BE_OPTIONAL: String =
        "a record's key cannot be optional: it is the field that tells one record from another, " +
            "so every record has to carry it"

    fun recordPrivate(name: String): String =
        "'@FlagRecord' class '$name' cannot be private: the generated '${name}FlagRecord' has to construct it"

    fun parameterNotProperty(name: String): String =
        "'$name' is a constructor parameter but not a property, so a record could not be written back out. " +
            "Declare it 'val $name'"

    fun oneKeyOnly(names: List<String>): String =
        "a record has one key at most, and ${names.joinToString(", ")} are all marked '@FlagRecordKey' — " +
            "the key is the single field that tells one record from another"

    fun unsupportedFieldType(name: String, type: String): String =
        "record field '$name' has type '$type', which a record field cannot hold. Use Boolean, Int, Long, Double, " +
            "Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or FlagRecords of " +
            "a '@FlagRecord' class; a nullable type makes the field optional"

    fun recordAsField(record: String): String =
        "a record is stored as a list — declare the field as 'FlagRecords<$record>' rather than '$record' or " +
            "'List<$record>'"

    fun recordNotGenerated(record: String, codec: String): String =
        "'$codec' does not exist: apply the featureflag-ksp processor to the module that declares '$record'"

    fun emptyDecodedName(name: String): String =
        "'@FlagRecordProperty' on '$name' needs a non-empty 'key': it is the name the field is read from in a payload"
}

/**
 * Every check that runs before generation. Each reports at the declaration that is wrong, so the
 * author never meets an error inside generated code. Returns false when anything was reported.
 */
internal class Checks(private val resolver: Resolver, private val logger: KSPLogger) {

    private val types = TypeMapping(resolver)

    fun container(declaration: KSClassDeclaration): Boolean {
        if (declaration.classKind != ClassKind.INTERFACE) return report(Diagnostics.INTERFACES_ONLY, declaration)
        val name = declaration.simpleName.asString()
        var valid = true
        if (declaration.isHidden()) valid = report(Diagnostics.containerPrivate(name), declaration)
        if (declaration.typeParameters.isNotEmpty()) {
            valid = report(Diagnostics.containerTypeParameters(name), declaration)
        }
        val supertypes = declaration.superTypes.map { it.resolve().declaration.qualifiedName?.asString() }
        if (supertypes.any { it != "kotlin.Any" }) valid = report(Diagnostics.containerSupertypes(name), declaration)
        declaration.getDeclaredFunctions().filter { it.isAbstract }.forEach {
            valid = report(Diagnostics.abstractFunction(it.simpleName.asString()), it)
        }
        declaration.getDeclaredProperties().forEach { if (!property(it, name)) valid = false }
        return valid
    }

    private fun property(property: KSPropertyDeclaration, container: String): Boolean {
        val name = property.simpleName.asString()
        val isFlag = property.hasAnnotation(Names.FLAG)
        val isGroup = property.hasAnnotation(Names.FLAG_GROUP)
        val type = property.type.resolve()
        return when {
            name in RESERVED -> report(Diagnostics.reservedName(name, container), property)
            !isFlag && !isGroup -> when {
                !property.isAbstract() -> true
                type.isRecordList() -> report(Diagnostics.recordListNeedsFlag(name), property)
                else -> report(Diagnostics.unattributedProperty(name), property)
            }
            property.isMutable -> report(Diagnostics.mutableProperty(name), property)
            property.extensionReceiver != null || property.isPrivate() ->
                report(Diagnostics.propertyShape(name), property)
            isGroup -> group(property, name, type)
            else -> flag(property, name, type)
        }
    }

    private fun group(property: KSPropertyDeclaration, name: String, type: KSType): Boolean {
        if (type.isRecordList()) return report(Diagnostics.recordListNeedsFlag(name), property)
        if (!property.isAbstract()) return report(Diagnostics.groupHasGetter(name), property)
        val target = type.expanded().declaration as? KSClassDeclaration
        val isContainer = target != null && !type.isMarkedNullable && target.classKind == ClassKind.INTERFACE &&
            target.hasAnnotation(Names.FLAG_CONTAINER)
        if (target == null || !isContainer) return report(Diagnostics.groupNotAContainer(name, type.render()), property)
        val spec = Names.generatedName(target, "Spec")
        val exists = target.origin == Origin.KOTLIN ||
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(spec.canonicalName)) != null
        if (!exists) {
            return report(Diagnostics.groupNotGenerated(name, target.simpleName.asString(), spec.simpleName), property)
        }
        return true
    }

    private fun flag(property: KSPropertyDeclaration, name: String, type: KSType): Boolean {
        if (type.isMarkedNullable) return report(Diagnostics.OPTIONAL_UNSUPPORTED, property)
        if (property.isAbstract()) return report(Diagnostics.defaultRequired(name, type.render()), property)
        return when (val mapped = types.map(type)) {
            is TypeMapping.Result.Supported -> true
            is TypeMapping.Result.Unsupported -> report(
                mapped.record?.let(Diagnostics::recordAsFlag) ?: Diagnostics.unsupportedFlagType(name, type.render()),
                property,
            )
            is TypeMapping.Result.MissingRecordCodec ->
                report(Diagnostics.recordNotGenerated(mapped.record, mapped.codec), property)
        }
    }

    fun record(declaration: KSClassDeclaration): Boolean {
        val constructor = declaration.primaryConstructor
        val concrete = declaration.classKind == ClassKind.CLASS &&
            Modifier.ABSTRACT !in declaration.modifiers && Modifier.SEALED !in declaration.modifiers
        if (!concrete || constructor == null) return report(Diagnostics.RECORD_CLASS_ONLY, declaration)
        val name = declaration.simpleName.asString()
        if (declaration.isHidden() || constructor.isPrivate()) {
            return report(Diagnostics.recordPrivate(name), declaration)
        }
        if (constructor.parameters.isEmpty()) return report(Diagnostics.FIELDS_REQUIRED, declaration)
        var valid = true
        constructor.parameters.forEach { if (!field(it)) valid = false }
        val keys = constructor.parameters.filter { it.hasAnnotation(Names.FLAG_RECORD_KEY) }
        if (keys.size > 1) {
            valid = report(Diagnostics.oneKeyOnly(keys.map { it.name?.asString().orEmpty() }), declaration)
        }
        return valid
    }

    private fun field(parameter: KSValueParameter): Boolean {
        val name = parameter.name?.asString().orEmpty()
        val type = parameter.type.resolve()
        val decodedName = parameter.annotationArgument(Names.FLAG_RECORD_PROPERTY, "key") as? String
        return when {
            !parameter.isVal && !parameter.isVar -> report(Diagnostics.parameterNotProperty(name), parameter)
            parameter.hasAnnotation(Names.FLAG_RECORD_KEY) && type.isMarkedNullable ->
                report(Diagnostics.KEY_CANNOT_BE_OPTIONAL, parameter)
            decodedName != null && decodedName.isEmpty() -> report(Diagnostics.emptyDecodedName(name), parameter)
            else -> when (val mapped = types.map(type.makeNotNullable())) {
                is TypeMapping.Result.Supported -> true
                is TypeMapping.Result.Unsupported -> report(
                    mapped.record?.let(Diagnostics::recordAsField)
                        ?: Diagnostics.unsupportedFieldType(name, type.render()),
                    parameter,
                )
                is TypeMapping.Result.MissingRecordCodec ->
                    report(Diagnostics.recordNotGenerated(mapped.record, mapped.codec), parameter)
            }
        }
    }

    /** Reports every `@FlagGroup` that leads back to its own container; returns the containers involved. */
    fun cycles(containers: List<KSClassDeclaration>): Set<KSClassDeclaration> {
        val byName = containers.associateBy { it.qualifiedName?.asString() }
        val cyclic = mutableSetOf<KSClassDeclaration>()
        for (container in containers) {
            for ((property, target) in groups(container)) {
                val path = pathBack(container, byName[target], byName, listOf(container)) ?: continue
                cyclic += container
                report(
                    Diagnostics.groupCycle(
                        property.simpleName.asString(),
                        container.simpleName.asString(),
                        path.joinToString(" → ") { it.simpleName.asString() },
                    ),
                    property,
                )
            }
        }
        return cyclic
    }

    private fun pathBack(
        start: KSClassDeclaration,
        next: KSClassDeclaration?,
        byName: Map<String?, KSClassDeclaration>,
        path: List<KSClassDeclaration>,
    ): List<KSClassDeclaration>? = when {
        next == null -> null
        next == start -> path + next
        next in path -> null
        else -> groups(next).firstNotNullOfOrNull { (_, target) ->
            pathBack(start, byName[target], byName, path + next)
        }
    }

    private fun groups(container: KSClassDeclaration): List<Pair<KSPropertyDeclaration, String?>> =
        container.getDeclaredProperties()
            .filter { it.hasAnnotation(Names.FLAG_GROUP) }
            .map { it to it.type.resolve().expanded().declaration.qualifiedName?.asString() }
            .toList()

    private fun report(message: String, at: KSNode): Boolean {
        logger.error(message, at)
        return false
    }

    private companion object {
        /** Members of the generated `XSpec` and its `Impl`, which a property of the same name would clash with. */
        val RESERVED: Set<String> = setOf(
            "flagDefaults", "flagDescriptors", "flagContainerDescription", "createFlags", "flagLookup", "flagKeys",
        )
    }
}

/** Private or protected here or on an enclosing declaration: generated code in another file cannot reach it. */
internal fun KSClassDeclaration.isHidden(): Boolean =
    generateSequence(this) { it.parentDeclaration as? KSClassDeclaration }.any { it.isPrivate() || it.isProtected() }

/** A type written the way a person would write it, for messages. */
internal fun KSType.render(): String {
    val arguments = arguments.joinToString(", ") { it.type?.resolve()?.render() ?: "*" }
    val base = declaration.simpleName.asString() + if (arguments.isEmpty()) "" else "<$arguments>"
    return if (isMarkedNullable) "$base?" else base
}

/** Whether this is `FlagRecords<…>`, in any spelling. */
internal fun KSType.isRecordList(): Boolean =
    expanded().declaration.qualifiedName?.asString() == Names.FLAG_RECORDS_NAME
```

**Modify:** Replace `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessorProvider.kt` with:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Registered through META-INF/services, so `ksp(project(":featureflag-ksp"))` is all a module needs. */
public class FeatureFlagProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeatureFlagProcessor(environment.codeGenerator, environment.logger)
}
```

**Modify:** Replace `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/FeatureFlagProcessor.kt` with:

```kotlin
package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** Generates `XRefs`/`XSpec` for every `@FlagContainer` and `RFlagRecord` for every `@FlagRecord`. */
internal class FeatureFlagProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val checks = Checks(resolver, logger)
        val deferred = mutableListOf<KSAnnotated>()

        val records = RecordGenerator(resolver, codeGenerator)
        for (declaration in symbols(resolver, Names.FLAG_RECORD, deferred)) {
            if (checks.record(declaration)) records.generate(records.parse(declaration))
        }

        val containers = ContainerGenerator(resolver, codeGenerator)
        val candidates = symbols(resolver, Names.FLAG_CONTAINER, deferred)
        val valid = candidates.filter { checks.container(it) }
        val cyclic = checks.cycles(candidates)
        valid.filterNot { it in cyclic }.forEach { containers.generate(containers.parse(it)) }
        return deferred
    }

    /** Declarations carrying [annotation] that resolve; the rest wait for a later round. */
    private fun symbols(
        resolver: Resolver,
        annotation: String,
        deferred: MutableList<KSAnnotated>,
    ): List<KSClassDeclaration> {
        val (ready, waiting) = resolver.getSymbolsWithAnnotation(annotation).partition { it.validate() }
        deferred += waiting
        return ready.filterIsInstance<KSClassDeclaration>()
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ksp:test --console=plain
./gradlew build detekt --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `featureflag-ksp` passes 41 tests: `ContainerGenerationTest` 8, `RecordGenerationTest` 4, `ContainerDiagnosticTest` 18 and `RecordDiagnosticTest` 11. Every core test still passes, because none of core's containers or records trips a check.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ksp
git commit -m "$(cat <<'EOF'
Report misuse of the flag annotations at the declaration, in the Swift macros' words

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

## Open risks

- **Recursive records** (`R` holding `FlagRecords<R>`) are not diagnosed. Building the shape would recurse without end, as it does in Swift. Add a record-cycle check alongside the group-cycle one if a user ever needs one.
- **Expansion snapshots are KotlinPoet's formatting.** A KotlinPoet upgrade can change whitespace and fail `*MatchesItsSnapshot`. Regenerate the three `.kt.txt` files and review the diff, rather than loosening the comparison.
- **Incremental KSP** relies on the originating files: the container, plus the source files of its groups, enums and records. A change to one of those re-runs the container's generation. A type that moves to another module is found by name, never by an originating file.
- **Cross-module groups and records need the processor in their own module.** Task B5 explains a missing one; it cannot be generated from outside.
