# M1c Pole and Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port FeatureFlag 0.12.0's value sources, `FlagPole` (resolution, overrides, change flows), JSON export/import with rollback, `FFQR1:` QR codes and schema publishing to Kotlin, byte-compatible with iOS.  **Architecture:** Sources hold a `@Volatile` immutable map and announce changes on a `MutableSharedFlow`. An internal `FlagResolver` walks the stack (the first source holding a box of the declared type wins) and is the `FlagLookup` the KSP-generated container reads through. `FlagPole` adds typed refs, default-filled resolutions, overrides and race-free flows: each flow subscribes to every source before it first suspends, then re-reads on every relevant change. Transport is a set of `FlagPole<*>` extension functions over `FlagPayload` (Apple-identical pretty JSON, all-or-nothing validation) and `FlagQRCode` (raw DEFLATE, base64url, stored fallback).  **Tech Stack:** Kotlin 2.2.10 (JVM), kotlinx-coroutines 1.10.2, `java.util.zip`, JUnit 4.13.2 + kotlin-test-junit, Turbine 1.2.1, KSP 2.2.10-2.0.2 (test-local `@FlagContainer`s), detekt 1.23.8, Swift 6.4 (`fixture-generator`).
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

1. **A busy collector during a write burst.** The main thread janks while more than 64 changes to other keys land, for example a remote payload or an import. The source's `DROP_OLDEST` buffer would drop that collector's own key change, and `flow(ref)` would stay stale. Pinned by C3 `aBusyCollectorStillSeesItsChangeAfterABurstOfOtherWrites`.
2. **Hand-edited import documents.** These include a number for a bool, `true` for an int, `null`, a string for a list, an int-enum value out of range, and an unknown key. Every problem, and the whole message, must match iOS byte for byte. Pinned by C4 `handEditedMistakesAreReportedExactlyAsAppleReportsThem`.
3. **QR text from a scanner or a clipboard.** As on iOS:
   - a trailing newline or space is `Corrupt`;
   - the standard `+/` alphabet and explicit `=` padding are accepted;
   - the prefix is case-sensitive.

   Pinned by C5 `scannedTextVariantsMatchApple`.
4. **"Reset all" on a stack with no writable source,** such as a remote-only pole. iOS finds nothing to clear and so never throws. It writes only the keys the top source holds, and Kotlin writes them in one batch. Pinned by C2 `removeAllOverridesWithNoWritableSourceIsANoOp` and `clearingEverythingOnlyWritesWhatWasSetInOneBatch`.
5. **Documents written by other tools.** As on iOS:
   - a UTF-8 byte-order mark is skipped;
   - a duplicated key keeps its first value;
   - `"formatVersion": true` reads as 1;
   - a missing or unreadable `exportedAt` becomes the epoch.

   Pinned by C4 `aByteOrderMarkIsSkipped`, `theFirstOfTwoDuplicateKeysWins`, `aBooleanFormatVersionReadsAsANumberLikeApple` and `aMissingOrUnreadableExportDateIsTheEpoch`.

---

## Seams this plan relies on (not fixed by the contract)

The contract names these items but does not spell them out. This plan uses the direct Swift-to-Kotlin port of each, the same reading M1d uses. If M1a or M1b chose differently, fix the call site and keep the asserted behaviour.

**How this plan was checked.** Every Kotlin block below was checked against a throwaway implementation of the M1a/M1b contract that follows these seams:
- **Compiled** with Kotlin 2.2.10. Main code used `-Xexplicit-api=strict -Werror -Xjdk-release=17`, and tests used `-Werror`.
- **Run.** All 196 tests pass, and the flow tests passed eight times in a row. They ran against fixtures reproduced from M1a's `TransportFixtures.swift`, using FeatureFlag 0.12.0.
- **Linted** with detekt 1.23.8, using the yewki config and `detekt-formatting`. It reported nothing.
- **Pinned.** Sending subscriptions to the collector's dispatcher makes only `aBusyCollectorStillSeesItsChangeAfterABurstOfOtherWrites` fail, as C3 Step 4 says.

Both Swift files compile under Swift 6.4 against FeatureFlag 0.12.0. `decode-kotlin-fixtures` read the Kotlin-written `fixtures-android/` and printed exactly the output given in C5 Step 4.

- **Packages follow directories,** as detekt's `InvalidPackageDeclaration` requires: `com.andyyhope.featureflag.values`, `.json`, `.records`, `.schema`, `.container`, `.sources`, `.pole`, `.transport`, `.errors`. The annotations are in `com.andyyhope.featureflag`. The golden model is in `com.andyyhope.featureflag.golden`: `GoldenFlags`, `GoldenFlagsSpec`, `GoldenTier`, `GoldenLevel`, `GoldenEndpoint`, `GoldenTarget` and the KSP-generated `GoldenEndpointFlagRecord`.
- **`FlagSchema` and `FlagSchema.Entry` are data classes with the Swift field names.** `FlagSchema(formatVersion, generatedAt, applicationName, description, flags, groups)`, and each entry of `flags` has `key`, `propertyPath`, `description`, `valueType`, `defaultValue`, `cases`, `remoteKey` and `recordShape`. This plan never names the entry type; it only reads those properties. It does not rely on `valueTypes`, `valueCases` or `recordShapes` existing, and builds its own first-wins maps.
- **`FlagSchemaException` has the subclasses `Malformed` and `NotPublished`.** `FlagSchema.write(directory)` creates missing directories, as Swift's `write(toDirectory:)` does.
- **`FlagSerializationException.NonFiniteNumber(key: FlagKey?)` exposes `public val key`.**
- **`JsonWriter.pretty` sorts object keys with `AppleKeyOrder`,** as `.sortedKeys` does. `JsonReader.parse` throws `JsonSyntaxException` for anything `JSONSerialization` rejects.
- **`RecordJson.decode(text, shape)` matches Swift's `FlagValueBox.recordValues(matching:)`,** as M1a Task A11 writes it.
  - It reads each field by `name`.
  - It does **not** check enum cases.
  - It returns `null` when two records share a key.
  - `RecordJson.duplicateKey(text, shape)` names that key for the "two with …" message.
- **Turbine is on `:featureflag-core`'s test classpath.** M1a Task A1's convention plugin adds it (with `kotlinx-coroutines-test`, which this plan never uses). Flow tests run under `runBlocking`, and threads come from `Executors`, so detekt's `InjectDispatcher` never fires in tests.
- **M1a's test helpers are reused:** `com.andyyhope.featureflag.Fixtures` (`bytes` and `text`) and `MessageText` (`quoted`, `arrayOf`, `objectWith` and `date`).
- **Test-local `@FlagContainer` interfaces in `featureflag-core/src/test` are processed by KSP,** which M1b wires as `kspTest`. Each one generates an `internal object <Name>Spec`.

## Fixtures this plan reads (M1a Task A2)

C4 and C5 read what M1a's `TransportFixtures.swift` writes. This plan asks nothing more of the generator.

- **`export/golden.json`** is `TransportFixtures.goldenPayload`: one override per flag, exported at 2026-01-01T09:41:00Z.
  - `GoldenOverrides.kt` (C4) mirrors that payload value for value, and **must stay identical to it**, as M1a's `RecordsGoldenTest` stays identical to `recordLists`.
  - Its `date-flag` is `1_767_260_460.75`. The export writes whole seconds, so reading the file back yields `GoldenOverrides.decodedValues`, which has the fraction dropped.
  - Reproduced with FeatureFlag 0.12.0 on this machine, the file is these 1,370 bytes, with no trailing newline:

```json
{
  "exportedAt" : "2026-01-01T09:41:00Z",
  "formatVersion" : 1,
  "values" : {
    "bool-flag" : true,
    "checkout.apple-pay" : false,
    "checkout.express.confirmation-delay" : 0.5,
    "checkout.express.one-tap" : true,
    "checkout.tier" : "free",
    "data-flag" : "3q2+7w==",
    "date-flag" : "2026-01-01T09:41:00Z",
    "double-flag" : 0.30000000000000004,
    "endpoints" : "[{\"kind\":\"enterprise\",\"name\":\"prod\",\"note\":\"primary \\\"quoted\\\" / slash\",\"targets\":\"[{\\\"host\\\":\\\"a.example\\\",\\\"port\\\":443},{\\\"host\\\":\\\"b.example\\\",\\\"port\\\":8443}]\",\"url\":\"https://prod.example.com/v1?x=1\",\"weight\":10},{\"kind\":\"pro\",\"name\":\"staging\",\"targets\":\"[]\",\"url\":\"https://staging.example.com\",\"weight\":0},{\"kind\":\"free\",\"name\":\"ünïcode\",\"note\":\"\",\"targets\":\"[]\",\"url\":\"https://example.com/%C3%BC\",\"weight\":-3}]",
    "float-flag" : 1.1000000238418579,
    "int-flag" : -7,
    "level" : 3,
    "limits" : {
      "A" : 1,
      "a9" : 9,
      "a10" : 10,
      "z" : 26
    },
    "markets" : [
      "AU",
      "NZ",
      "US"
    ],
    "matrix" : [
      [

      ],
      [
        true
      ]
    ],
    "string-flag" : "line1\nline2\t\"q\" / é 😀",
    "tier" : "enterprise",
    "url-flag" : "https://example.com/path?q=1&r=a%20b#frag",
    "use-https-only" : true
  }
}
```

- **`qr/<name>.json` + `qr/<name>.qr.txt`** hold the export JSON and Apple's code for `golden`, `single` (`["bool-flag": .bool(true)]`) and `stored`.
  - `stored` is a short, high-entropy `string-flag` document. Deflate cannot shrink it, so Apple stored it raw.
  - Every payload uses `GoldenFlags` kebab-case keys, so C5 also decodes each one against the golden schema.
  - The JDK 21 `Deflater(5, true)`, with C5's `max(n, 64)` rule, reproduced all three codes byte for byte, `stored` included.
- **`fixture-generator/Package.swift`** already declares `decode-kotlin-fixtures`, with `GoldenModel` and `FeatureFlag` as dependencies. C5 replaces only its stub `main.swift`.

## Files this plan creates

```
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/
  sources/FlagValueSource.kt  sources/SnapshotSource.kt
  pole/FlagResolution.kt  pole/FlagResolver.kt  pole/FlagPole.kt
  transport/FlagPayload.kt  transport/FlagQRCode.kt  transport/FlagPoleTransport.kt
  errors/FlagException.kt  errors/FlagImportException.kt  errors/FlagQRCodeException.kt
featureflag-core/src/test/kotlin/com/andyyhope/featureflag/
  sources/FlagValueSourceTest.kt  sources/SnapshotSourceTest.kt
  errors/FlagExceptionTest.kt  errors/FlagImportExceptionTest.kt  errors/FlagQRCodeExceptionTest.kt
  pole/TestSources.kt  pole/FlagResolverTest.kt  pole/FlagPoleTest.kt  pole/FlagPoleEdgeCaseTest.kt  pole/FlagPoleFlowTest.kt
  transport/TransportTestSupport.kt  transport/GoldenOverrides.kt  transport/FlagPayloadTest.kt
  transport/FlagPoleTransportTest.kt  transport/ExportGoldenTest.kt
  transport/FlagQRCodeTest.kt  transport/QRCodeFixturesTest.kt  transport/PublishSchemaTest.kt
fixture-generator/Sources/DecodeKotlinFixtures/main.swift      (replaces M1a's stub)
fixtures-android/{golden,empty,single}.json + .qr.txt          (generated once by QRCodeFixturesTest, then committed)
```

Every command block below runs from the repository root, `/Users/andyyhope/Development/feature-flags-android`.

### Swift tests ported, and the ones skipped

| Swift file | Ported to | Skipped, and why |
|---|---|---|
| `FlagPoleTests` | C2 `FlagPoleTest` (resolution, overrides); C3 `FlagPoleFlowTest` (the Combine tests become `changes` and `flow(ref)` tests) | none |
| `FlagPoleEdgeCaseTests` | C2 `FlagPoleEdgeCaseTest`, `FlagResolverTest` | `testADetachedFlagPublishesItsDefaultAndNothingElse`: Kotlin has no detached `Flag`, and `flagDefaults` is M1b's. From the `MinimalLookup` pair, only the read half is ported: `FlagLookup` has no `setOverride` or `changePublisher` in the contract. |
| `SourceEdgeCaseTests` | C1 `FlagValueSourceTest`, `SnapshotSourceTest`; C2 `FlagResolverTest` | The five `UserDefaultsSource` tests go to M2's `SharedPreferencesSource`. |
| `FlagPayloadTests` | C4 `FlagPoleTransportTest` | `testPLISTRoundTripsEveryOverride`: PLIST was dropped. |
| `FlagQRCodeTests` | C5 `FlagQRCodeTest` | `testProducesAScannableImage` and `testImageScaleIsHonoured`: Core Image. |
| `TransportEdgeCaseTests` | C4 `FlagPoleTransportTest`, C5 `FlagQRCodeTest`, C6 `PublishSchemaTest` | `testJSONParsedAsAPropertyListIsRejected` and `testAPropertyListThatIsNotADictionaryIsRejected`: PLIST. The two App Group tests are replaced by `FlagSchema.read` on a directory with no schema. |
| `AdversarialRegressionTests` | C4 (export of non-finite numbers, rollback, enum cases on import); C5 (QR of a non-finite number, scanned unknown case); C2 (`WriteAmplificationTests` clearing) | The two mapper tests belong to M1d's remote source. `testASchemaWithANonFiniteDefaultThrows` belongs to M1b's schema. `testDetectionLooksAllTheWayIntoNestedCollections` belongs to M1a's `containsNonFiniteNumber`. The three UserDefaults write-amplification tests go to M2. |

---

### Task C1: Sources, `SnapshotSource` and `FlagException`

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/sources/FlagValueSource.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/sources/SnapshotSource.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagException.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/sources/FlagValueSourceTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/sources/SnapshotSourceTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagExceptionTest.kt`

**Interfaces:**
- Consumes (M1a): `@JvmInline value class FlagKey(rawValue: String)` (its `toString()` is `rawValue`), `sealed class FlagValueType`, `sealed interface FlagValueBox` (structural `equals`).
- Produces:
```kotlin
public sealed interface FlagChange {
    public fun affects(key: FlagKey): Boolean
    public data object All : FlagChange
    public data class Keys(public val keys: Set<FlagKey>) : FlagChange
}
public interface FlagValueSource {
    public val sourceName: String
    public fun box(key: FlagKey, type: FlagValueType): FlagValueBox?
    public val changes: Flow<FlagChange>
}
public interface MutableFlagValueSource : FlagValueSource {
    public fun setBox(key: FlagKey, box: FlagValueBox?)
    public fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) { changes.forEach { (key, box) -> setBox(key, box) } }
}
public class SnapshotSource(name: String = "Snapshot", values: Map<FlagKey, FlagValueBox> = emptyMap()) : MutableFlagValueSource {
    override val sourceName: String
    public val values: Map<FlagKey, FlagValueBox>
    public fun replaceAll(values: Map<FlagKey, FlagValueBox>)
}
public sealed class FlagException(message: String) : RuntimeException(message) {
    public class NoMutableSource : FlagException
    public class UnsupportedValue(public val key: FlagKey) : FlagException
}
```

- [ ] **Step 1: Write the failing tests**

Turbine and `kotlinx-coroutines-test` are already on every module's test classpath: M1a Task A1's convention plugin (`featureflag.jvm-library`) adds both, so no build file changes here.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/sources/FlagValueSourceTest.kt`
```kotlin
package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagValueSourceTest {

    @Test
    fun aWholesaleChangeAffectsEveryKey() {
        assertTrue(FlagChange.All.affects(FlagKey("anything")))
    }

    @Test
    fun aKeyedChangeAffectsOnlyItsKeys() {
        val change = FlagChange.Keys(setOf(FlagKey("a"), FlagKey("b")))

        assertTrue(change.affects(FlagKey("a")))
        assertFalse(change.affects(FlagKey("c")))
    }

    @Test
    fun anEmptyKeyedChangeAffectsNothing() {
        assertFalse(FlagChange.Keys(emptySet()).affects(FlagKey("a")))
    }

    @Test
    fun setBoxesWritesEachEntryThroughSetBoxByDefault() {
        val source = RecordingSource()

        source.setBoxes(linkedMapOf(FlagKey("a") to FlagValueBox.Int(1), FlagKey("b") to null))

        assertEquals(listOf<Pair<FlagKey, FlagValueBox?>>(FlagKey("a") to FlagValueBox.Int(1), FlagKey("b") to null), source.writes)
    }

    private class RecordingSource : MutableFlagValueSource {
        val writes = mutableListOf<Pair<FlagKey, FlagValueBox?>>()
        override val sourceName: String = "recording"
        override val changes: Flow<FlagChange> = emptyFlow()

        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null

        override fun setBox(key: FlagKey, box: FlagValueBox?) {
            writes.add(key to box)
        }
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/sources/SnapshotSourceTest.kt`
```kotlin
package com.andyyhope.featureflag.sources

import app.cash.turbine.test
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotSourceTest {

    private val a = FlagKey("a")
    private val b = FlagKey("b")

    @Test
    fun aSnapshotCanBeSeededAtInit() {
        val source = SnapshotSource("seed", mapOf(a to FlagValueBox.Int(1)))

        assertEquals(FlagValueBox.Int(1), source.box(a, FlagValueType.Int))
        assertEquals("seed", source.sourceName)
    }

    @Test
    fun theDefaultNameIsTheSwiftOne() {
        assertEquals("Snapshot", SnapshotSource().sourceName)
    }

    @Test
    fun aSnapshotHandsBackWhateverItHoldsWhateverTheType() {
        // Type checking is the resolver's job.
        val source = SnapshotSource(values = mapOf(a to FlagValueBox.String("x")))

        assertEquals(FlagValueBox.String("x"), source.box(a, FlagValueType.Int))
    }

    @Test
    fun writingTheSameValueAgainEmitsNothing() = runBlocking<Unit> {
        // Otherwise every idle write would wake every subscriber.
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))

        source.changes.test {
            source.setBox(a, FlagValueBox.Int(1))
            expectNoEvents()

            source.setBox(a, FlagValueBox.Int(2))
            assertEquals(FlagChange.Keys(setOf(a)), awaitItem())
        }
    }

    @Test
    fun removingAValueEmitsAChange() = runBlocking<Unit> {
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))

        source.changes.test {
            source.setBox(a, null)
            assertEquals(FlagChange.Keys(setOf(a)), awaitItem())
        }
        assertNull(source.box(a, FlagValueType.Int))
    }

    @Test
    fun removingSomethingAbsentEmitsNothing() = runBlocking<Unit> {
        val source = SnapshotSource("local")

        source.changes.test {
            source.setBox(FlagKey("absent"), null)
            expectNoEvents()
        }
    }

    @Test
    fun replacingEverythingReportsOneWholesaleChange() = runBlocking<Unit> {
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))

        source.changes.test {
            source.replaceAll(mapOf(b to FlagValueBox.Int(2)))
            assertEquals(FlagChange.All, awaitItem())
            expectNoEvents()
        }
        assertNull(source.box(a, FlagValueType.Int))
        assertEquals(FlagValueBox.Int(2), source.box(b, FlagValueType.Int))
    }

    @Test
    fun valuesIsASnapshotThatLaterWritesDoNotChange() {
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))
        val before = source.values

        source.setBox(b, FlagValueBox.Int(2))

        assertEquals(mapOf(a to FlagValueBox.Int(1)), before)
        assertEquals(mapOf(a to FlagValueBox.Int(1), b to FlagValueBox.Int(2)), source.values)
    }

    @Test
    fun aSeedMapChangedAfterwardsDoesNotLeakIn() {
        val seed = mutableMapOf<FlagKey, FlagValueBox>(a to FlagValueBox.Int(1))
        val source = SnapshotSource(values = seed)

        seed[b] = FlagValueBox.Int(2)

        assertEquals(mapOf(a to FlagValueBox.Int(1)), source.values)
    }

    @Test
    fun aSnapshotIsSafeUnderConcurrentWrites() {
        val source = SnapshotSource("local")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 200).map { index ->
                Callable { source.setBox(FlagKey("key.${index % 8}"), FlagValueBox.Int(index.toLong())) }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(8, source.values.size)
    }

    @Test
    fun concurrentReadersNeverSeeALostOrTornWrite() {
        // Each writer owns one key, so the final value of every key is known. A write that
        // replaced the map without the lock would drop another thread's key.
        val source = SnapshotSource("local")
        val writers = 8
        val writesPerKey = 1_000
        val pool = Executors.newFixedThreadPool(writers + 4)
        try {
            val writeTasks = (0 until writers).map { index ->
                Callable {
                    val key = FlagKey("key.$index")
                    for (value in 0 until writesPerKey) source.setBox(key, FlagValueBox.Int(value.toLong()))
                }
            }
            val readTasks = (0 until 4).map { _ ->
                Callable {
                    repeat(writesPerKey) { _ ->
                        for (index in 0 until writers) {
                            val box = source.box(FlagKey("key.$index"), FlagValueType.Int)
                            assertTrue(box == null || box is FlagValueBox.Int, "torn read: $box")
                        }
                    }
                }
            }
            pool.invokeAll(writeTasks + readTasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val expected = (0 until writers).associate { FlagKey("key.$it") to FlagValueBox.Int(writesPerKey - 1L) }
        assertEquals(expected, source.values)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagExceptionTest.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class FlagExceptionTest {

    @Test
    fun writingWithNothingWritableSaysWhatToAdd() {
        assertEquals(
            "No source in the stack can be written to. Add a SharedPreferencesSource or a SnapshotSource — " +
                "a pole built only from read-only sources has nowhere to put an override.",
            FlagException.NoMutableSource().message,
        )
    }

    @Test
    fun aRefusedValueNamesTheFlag() {
        val error = FlagException.UnsupportedValue(FlagKey("page-size"))

        assertEquals(FlagKey("page-size"), error.key)
        assertEquals(
            "The source refused the value for 'page-size' because it cannot represent it. " +
                "The store accepts a narrower set of types than a flag can declare.",
            error.message,
        )
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.sources.*' --tests 'com.andyyhope.featureflag.errors.FlagExceptionTest'
```

Expected: `BUILD FAILED`, with `compileTestKotlin` reporting `Unresolved reference 'FlagChange'`, `'SnapshotSource'` and `'FlagException'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/sources/FlagValueSource.kt`
```kotlin
package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow

/** What changed in a source. */
public sealed interface FlagChange {

    /** Whether someone watching [key] has to read it again. */
    public fun affects(key: FlagKey): Boolean

    /**
     * Every key should be read again. A source that cannot say more precisely what
     * changed, such as a whole remote payload landing, reports this.
     */
    public data object All : FlagChange {
        override fun affects(key: FlagKey): Boolean = true
    }

    /** Only these keys changed. */
    public data class Keys(public val keys: Set<FlagKey>) : FlagChange {
        override fun affects(key: FlagKey): Boolean = key in keys
    }
}

/**
 * Supplies flag values to a `FlagPole`.
 *
 * A pole holds an ordered stack of these and asks each in turn, so the order of the
 * stack is the precedence.
 *
 * Implementations must be safe to call from any thread, and [box] must be cheap: apps
 * read flags constantly, from whatever thread they happen to be on.
 */
public interface FlagValueSource {

    /** Identifies the source when explaining where a value came from. */
    public val sourceName: String

    /**
     * The value this source holds for [key], or null.
     *
     * [type] is the flag's declared type. A source stored in a loosely typed medium can
     * use it to decode exactly rather than guess.
     */
    public fun box(key: FlagKey, type: FlagValueType): FlagValueBox?

    /**
     * Emits whenever stored values change.
     *
     * A collector must be subscribed by the time `collect` first suspends, as it is for a
     * `SharedFlow`. A pole's flows rely on that so that they never miss a write made while
     * they subscribe.
     */
    public val changes: Flow<FlagChange>
}

/** A source whose values can change at runtime, such as a companion's override or an import. */
public interface MutableFlagValueSource : FlagValueSource {

    /** Stores [box] for [key], or removes the key when [box] is null. */
    public fun setBox(key: FlagKey, box: FlagValueBox?)

    /**
     * Stores several values. The default writes each one through [setBox]. A source that
     * can commit them together, such as one backed by preferences, should override this.
     */
    public fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        changes.forEach { (key, box) -> setBox(key, box) }
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/sources/SnapshotSource.kt`
```kotlin
package com.andyyhope.featureflag.sources

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An in-memory set of flag values.
 *
 * Useful as the top of a stack in tests and previews, as the landing place for an
 * imported payload, and as a way to capture the current state of a pole.
 *
 * Reads are lock-free: each one sees a single immutable map, which every write replaces
 * whole. Writes are serialised, and a write that changes nothing announces nothing.
 */
public class SnapshotSource(
    name: String = "Snapshot",
    values: Map<FlagKey, FlagValueBox> = emptyMap(),
) : MutableFlagValueSource {

    override val sourceName: String = name

    private val lock = Any()

    @Volatile
    private var storage: Map<FlagKey, FlagValueBox> = values.toMap()

    private val changeFlow = MutableSharedFlow<FlagChange>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<FlagChange> = changeFlow.asSharedFlow()

    /** Everything this snapshot holds, as of the moment it is read. */
    public val values: Map<FlagKey, FlagValueBox>
        get() = storage

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = storage[key]

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        val changed = synchronized(lock) {
            val current = storage
            if (current[key] == box) {
                false
            } else {
                storage = if (box == null) current - key else current + (key to box)
                true
            }
        }
        if (changed) changeFlow.tryEmit(FlagChange.Keys(setOf(key)))
    }

    /** Replaces the entire contents in one step, reporting a single change. */
    public fun replaceAll(values: Map<FlagKey, FlagValueBox>) {
        val replacement = values.toMap()
        synchronized(lock) { storage = replacement }
        changeFlow.tryEmit(FlagChange.All)
    }

    private companion object {
        const val CHANGE_BUFFER = 64
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagException.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey

/** A write the source stack could not take. */
public sealed class FlagException(message: String) : RuntimeException(message) {

    /** Nothing in the source stack can be written to. */
    public class NoMutableSource : FlagException(
        "No source in the stack can be written to. Add a SharedPreferencesSource or a SnapshotSource — " +
            "a pole built only from read-only sources has nowhere to put an override.",
    )

    /** A source refused a value it cannot represent. */
    public class UnsupportedValue(public val key: FlagKey) : FlagException(
        "The source refused the value for '$key' because it cannot represent it. " +
            "The store accepts a narrower set of types than a flag can declare.",
    )
}
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.sources.*' --tests 'com.andyyhope.featureflag.errors.FlagExceptionTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both commands. The first runs 17 tests, all passing.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/sources \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagException.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/sources \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagExceptionTest.kt
git commit -m "Add flag sources, SnapshotSource and FlagException" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task C2: `FlagResolver`, `FlagResolution` and `FlagPole`

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagResolution.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagResolver.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagPole.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/TestSources.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagResolverTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleEdgeCaseTest.kt`

**Interfaces:**
- Consumes:
  - C1: `FlagValueSource`, `MutableFlagValueSource`, `SnapshotSource`, `FlagException.NoMutableSource`.
  - M1a: `FlagValueBox.matches(type)`, `KeyEncoding.Kebabcase`/`Snakecase`, `FlagCodec<T>.type`/`encode`/`decode`.
  - M1b: `FlagContainerSpec<F>` (`flagDescriptors`, `createFlags(lookup, prefix = FlagKeyPath.ROOT)`), `FlagRef<T>` (`codec`, `defaultValue`, `key(encoding)`), `interface FlagLookup`, `fun <T> FlagLookup.read(key, codec, default: () -> T): T`, `FlagSchema.of(spec, keyEncoding, applicationName, generatedAt)`, the entries of `FlagSchema.flags` (`key`, `valueType`, `defaultValue`), `List<FlagSchemaNode>.flattened(): List<FlagDescriptor>`, and `GoldenFlagsSpec` in the test sources.
- Produces:
```kotlin
public data class FlagResolution(public val key: FlagKey, public val sourceName: String?, public val box: FlagValueBox?) {
    public val isDefault: Boolean
}
internal class FlagResolver(sources: List<FlagValueSource>, override val keyEncoding: KeyEncoding) : FlagLookup {
    fun resolution(key: FlagKey, type: FlagValueType): FlagResolution
    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox?
    fun setOverride(key: FlagKey, box: FlagValueBox?)             // top mutable source, else FlagException.NoMutableSource
    fun setOverrides(changes: Map<FlagKey, FlagValueBox?>)        // one setBoxes call on the top mutable source
    fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox?
}
public class FlagPole<F : Any>(public val spec: FlagContainerSpec<F>, sources: List<FlagValueSource>,
    public val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase, public val applicationName: String? = null) {
    public val sources: List<FlagValueSource>; public val flags: F; public val schema: FlagSchema
    public operator fun <T> get(ref: FlagRef<T>): T; public fun key(ref: FlagRef<*>): FlagKey
    public fun resolution(ref: FlagRef<*>): FlagResolution; public fun resolution(key: FlagKey, type: FlagValueType): FlagResolution
    public val descriptors: List<FlagDescriptor>; public val keys: List<FlagKey>; public val overrides: Map<FlagKey, FlagValueBox>
    public fun <T> setOverride(ref: FlagRef<T>, value: T); public fun setOverride(key: FlagKey, box: FlagValueBox?)
    public fun removeOverride(ref: FlagRef<*>); public fun removeAllOverrides(); public fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox?
}
```
`changes` and `flow(ref)` arrive in C3.

- [ ] **Step 1: Write the failing tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/TestSources.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** A source nothing can write to, standing in for a remote payload. */
internal class ReadOnlySource(
    override val sourceName: String,
    private val values: Map<FlagKey, FlagValueBox> = emptyMap(),
) : FlagValueSource {
    override val changes: Flow<FlagChange> = emptyFlow()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]
}

/** Counts every single write and records every batch, so a test can see how much a bulk operation touched. */
internal class CountingSource : MutableFlagValueSource {
    override val sourceName: String = "counting"
    override val changes: Flow<FlagChange> = emptyFlow()
    var writes: Int = 0
    val batches = mutableListOf<Map<FlagKey, FlagValueBox?>>()
    private val values = mutableMapOf<FlagKey, FlagValueBox>()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        writes += 1
        if (box == null) {
            values.remove(key)
        } else {
            values[key] = box
        }
    }

    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        batches.add(changes)
        super.setBoxes(changes)
    }
}

/** Runs [task] [times] times across eight threads, and rethrows the first failure. */
internal fun runConcurrently(times: Int, task: (Int) -> Unit) {
    val pool = Executors.newFixedThreadPool(8)
    try {
        pool.invokeAll((0 until times).map { index -> Callable { task(index) } }).forEach { it.get() }
    } finally {
        pool.shutdownNow()
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagResolverTest.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlagResolverTest {

    private val maxItems = FlagKey("max-items")

    @Test
    fun anEmptySourceStackAlwaysReportsTheDefault() {
        val resolution = FlagResolver(emptyList(), KeyEncoding.Kebabcase).resolution(FlagKey("anything"), FlagValueType.Bool)

        assertTrue(resolution.isDefault)
        assertNull(resolution.sourceName)
        assertNull(resolution.box)
    }

    @Test
    fun resolutionWalksPastEverySourceThatCannotAnswer() {
        val third = SnapshotSource("third", mapOf(maxItems to FlagValueBox.Int(3)))
        val sources = listOf(SnapshotSource("first"), SnapshotSource("second"), third)
        val resolver = FlagResolver(sources, KeyEncoding.Kebabcase)

        assertEquals(FlagResolution(maxItems, "third", FlagValueBox.Int(3)), resolver.resolution(maxItems, FlagValueType.Int))
    }

    @Test
    fun aStoredValueOfTheWrongTypeIsSkippedNotSurfaced() {
        val local = SnapshotSource("local", mapOf(maxItems to FlagValueBox.String("nonsense")))
        val remote = SnapshotSource("remote", mapOf(maxItems to FlagValueBox.Int(3)))
        val resolver = FlagResolver(listOf(local, remote), KeyEncoding.Kebabcase)

        assertEquals("remote", resolver.resolution(maxItems, FlagValueType.Int).sourceName)
        assertEquals(FlagValueBox.Int(3), resolver.box(maxItems, FlagValueType.Int))
    }

    @Test
    fun anEmptyCollectionMatchesAnyElementType() {
        val markets = FlagKey("markets")
        val local = SnapshotSource("local", mapOf(markets to FlagValueBox.Array(emptyList())))

        val resolution = FlagResolver(listOf(local), KeyEncoding.Kebabcase).resolution(markets, FlagValueType.Array(FlagValueType.String))

        assertEquals("local", resolution.sourceName)
    }

    @Test
    fun writesGoToTheFirstMutableSourceAndCurrentOverrideReadsOnlyIt() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom", mapOf(maxItems to FlagValueBox.Int(3)))
        val resolver = FlagResolver(listOf(ReadOnlySource("remote"), top, bottom), KeyEncoding.Kebabcase)

        assertNull(resolver.currentOverride(maxItems, FlagValueType.Int))

        // Whatever the top source holds, even of the wrong type: undoing an import needs exactly that.
        resolver.setOverride(maxItems, FlagValueBox.String("raw"))

        assertEquals(FlagValueBox.String("raw"), resolver.currentOverride(maxItems, FlagValueType.Int))
        assertEquals(FlagValueBox.String("raw"), top.values[maxItems])
    }

    @Test
    fun withNothingWritableWritesThrowAndCurrentOverrideIsNull() {
        val remote = ReadOnlySource("remote", mapOf(maxItems to FlagValueBox.Int(3)))
        val resolver = FlagResolver(listOf(remote), KeyEncoding.Kebabcase)

        assertFailsWith<FlagException.NoMutableSource> { resolver.setOverride(maxItems, FlagValueBox.Int(1)) }
        assertFailsWith<FlagException.NoMutableSource> { resolver.setOverrides(mapOf(maxItems to null)) }
        assertNull(resolver.currentOverride(maxItems, FlagValueType.Int))
    }

    @Test
    fun theResolverCarriesItsKeyEncoding() {
        assertSame(KeyEncoding.Snakecase, FlagResolver(emptyList(), KeyEncoding.Snakecase).keyEncoding)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleTest.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Kotlin type of `intFlag` (Int or Long) is M1b's choice, so these tests compare `.toLong()`.
class FlagPoleTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val applePay = FlagKey("checkout.apple-pay")
    private val oneTap = FlagKey("checkout.express.one-tap")

    // Resolution

    @Test
    fun readsTheCompiledDefaultWhenNoSourceHasAValue() {
        val pole = FlagPole(GoldenFlagsSpec, emptyList())

        assertFalse(pole.flags.boolFlag)
        assertEquals(42L, pole.flags.intFlag.toLong())
        assertEquals(GoldenTier.PRO, pole.flags.checkout.tier)
    }

    @Test
    fun readsFromASource() {
        val local = SnapshotSource("local")
        local.setBox(boolFlag, FlagValueBox.Bool(true))

        assertTrue(FlagPole(GoldenFlagsSpec, listOf(local)).flags.boolFlag)
    }

    @Test
    fun localOverrideBeatsRemote() {
        // The precedence that makes the companion trustworthy: a value set by hand stays
        // set until it is explicitly cleared.
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(false)))
        val remote = SnapshotSource("remote", mapOf(boolFlag to FlagValueBox.Bool(true)))

        assertFalse(FlagPole(GoldenFlagsSpec, listOf(local, remote)).flags.boolFlag)
    }

    @Test
    fun remoteAppliesWhereThereIsNoLocalOverride() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val remote = SnapshotSource("remote", mapOf(intFlag to FlagValueBox.Int(3)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local, remote))

        assertEquals(3L, pole.flags.intFlag.toLong())
        assertTrue(pole.flags.boolFlag)
    }

    @Test
    fun fallsThroughWhenTheHighestSourceHoldsTheWrongType() {
        val local = SnapshotSource("local", mapOf(intFlag to FlagValueBox.String("nonsense")))
        val remote = SnapshotSource("remote", mapOf(intFlag to FlagValueBox.Int(3)))

        assertEquals(3L, FlagPole(GoldenFlagsSpec, listOf(local, remote)).flags.intFlag.toLong())
    }

    @Test
    fun nestedFlagsResolveThroughThePole() {
        val local = SnapshotSource("local", mapOf(oneTap to FlagValueBox.Bool(true)))

        assertTrue(FlagPole(GoldenFlagsSpec, listOf(local)).flags.checkout.express.oneTap)
    }

    @Test
    fun theIndexOperatorReadsAFlagWithoutGoingThroughFlags() {
        val local = SnapshotSource("local", mapOf(intFlag to FlagValueBox.Int(4)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        assertEquals(4L, pole[GoldenFlagsSpec.intFlag].toLong())
        assertFalse(pole[GoldenFlagsSpec.checkout.express.oneTap])
    }

    @Test
    fun keyEncodingIsConfigurable() {
        val local = SnapshotSource("local", mapOf(FlagKey("checkout.apple_pay") to FlagValueBox.Bool(false)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local), keyEncoding = KeyEncoding.Snakecase)

        assertFalse(pole.flags.checkout.applePay)
        assertEquals(FlagKey("checkout.apple_pay"), pole.key(GoldenFlagsSpec.checkout.applePay))
    }

    // Explaining a value

    @Test
    fun resolutionNamesTheWinningSource() {
        val remote = SnapshotSource("remote", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local"), remote))

        val resolution = pole.resolution(GoldenFlagsSpec.boolFlag)

        assertEquals("remote", resolution.sourceName)
        assertEquals(FlagValueBox.Bool(true), resolution.box)
        assertFalse(resolution.isDefault)
    }

    @Test
    fun resolutionReportsTheCompiledDefault() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        val resolution = pole.resolution(GoldenFlagsSpec.boolFlag)

        assertNull(resolution.sourceName)
        assertTrue(resolution.isDefault)
        assertEquals(FlagValueBox.Bool(false), resolution.box)
    }

    @Test
    fun resolutionSkipsASourceHoldingTheWrongType() {
        val local = SnapshotSource("local", mapOf(intFlag to FlagValueBox.String("nonsense")))
        val remote = SnapshotSource("remote", mapOf(intFlag to FlagValueBox.Int(3)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local, remote))

        assertEquals("remote", pole.resolution(GoldenFlagsSpec.intFlag).sourceName)
    }

    // Overrides

    @Test
    fun overridesListsOnlyFlagsSomeSourceSupplies() {
        val values = mapOf(boolFlag to FlagValueBox.Bool(true), applePay to FlagValueBox.Bool(true))
        val local = SnapshotSource("local", values)

        assertEquals(
            mapOf(boolFlag to FlagValueBox.Bool(true), applePay to FlagValueBox.Bool(true)),
            FlagPole(GoldenFlagsSpec, listOf(local)).overrides,
        )
    }

    @Test
    fun overridesSpanTheWholeStackButOnlyDeclaredFlags() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val remoteValues = mapOf(intFlag to FlagValueBox.Int(3), FlagKey("not-declared") to FlagValueBox.Int(1))
        val remote = ReadOnlySource("remote", remoteValues)

        assertEquals(
            mapOf(boolFlag to FlagValueBox.Bool(true), intFlag to FlagValueBox.Int(3)),
            FlagPole(GoldenFlagsSpec, listOf(local, remote)).overrides,
        )
    }

    @Test
    fun overridesIsEmptyWhenEverythingIsDefault() {
        assertTrue(FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local"))).overrides.isEmpty())
    }

    @Test
    fun settingAnOverrideThroughTheRefTakesEffect() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertTrue(pole.flags.boolFlag)
    }

    @Test
    fun removingAnOverrideRestoresTheDefault() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        pole.removeOverride(GoldenFlagsSpec.boolFlag)

        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun removeAllOverridesTouchesOnlyKnownFlags() {
        val unrelated = FlagKey("someone-elses-key")
        val values = mapOf(boolFlag to FlagValueBox.Bool(true), unrelated to FlagValueBox.String("unrelated"))
        val local = SnapshotSource("local", values)
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.removeAllOverrides()

        assertFalse(pole.flags.boolFlag)
        assertEquals(FlagValueBox.String("unrelated"), local.box(unrelated, FlagValueType.String))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleEdgeCaseTest.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenLevel
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPoleEdgeCaseTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val tier = FlagKey("tier")
    private val level = FlagKey("level")

    // Writing with nothing to write to

    @Test
    fun settingAnOverrideWithNoMutableSourceReports() {
        val pole = FlagPole(GoldenFlagsSpec, emptyList())

        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(GoldenFlagsSpec.boolFlag, true) }
    }

    @Test
    fun settingAnOverrideWithOnlyAReadOnlySourceReports() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(ReadOnlySource("remote")))

        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(GoldenFlagsSpec.boolFlag, true) }
        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(boolFlag, FlagValueBox.Bool(true)) }
        assertFailsWith<FlagException.NoMutableSource> { pole.removeOverride(GoldenFlagsSpec.boolFlag) }
    }

    @Test
    fun writesGoToTheHighestPriorityMutableSource() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")

        FlagPole(GoldenFlagsSpec, listOf(top, bottom)).setOverride(GoldenFlagsSpec.boolFlag, true)

        assertEquals(FlagValueBox.Bool(true), top.values[boolFlag])
        assertNull(bottom.values[boolFlag])
    }

    @Test
    fun writesSkipAReadOnlySourceAboveAMutableOne() {
        val local = SnapshotSource("local")

        FlagPole(GoldenFlagsSpec, listOf(ReadOnlySource("remote"), local)).setOverride(GoldenFlagsSpec.boolFlag, true)

        assertEquals(FlagValueBox.Bool(true), local.values[boolFlag])
    }

    @Test
    fun settingANullBoxRemovesTheOverride() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.setOverride(boolFlag, null)

        assertTrue(local.values.isEmpty())
        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun currentOverrideReadsOnlyTheWritableSource() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val remote = ReadOnlySource("remote", mapOf(boolFlag to FlagValueBox.Bool(false)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(remote, top, bottom))

        assertNull(pole.currentOverride(boolFlag, FlagValueType.Bool))

        top.setBox(boolFlag, FlagValueBox.String("not a bool"))
        assertEquals(FlagValueBox.String("not a bool"), pole.currentOverride(boolFlag, FlagValueType.Bool))
        assertNull(FlagPole(GoldenFlagsSpec, listOf(remote)).currentOverride(boolFlag, FlagValueType.Bool))
    }

    // Describing the tree

    @Test
    fun descriptorsAreFlattenedDepthFirst() {
        assertEquals(
            listOf(
                "boolFlag", "intFlag", "doubleFlag", "floatFlag", "stringFlag", "dataFlag", "dateFlag", "urlFlag",
                "useHTTPSOnly", "markets", "matrix", "limits", "tier", "level", "endpoints",
                "applePay", "tier", "oneTap", "confirmationDelay",
            ),
            FlagPole(GoldenFlagsSpec, emptyList()).descriptors.map { it.propertyName },
        )
    }

    @Test
    fun flattenedDescriptorsKeepTheirFullKeyPaths() {
        val paths = FlagPole(GoldenFlagsSpec, emptyList()).descriptors.map { it.keyPath.propertyNames }

        assertEquals(listOf("boolFlag"), paths.first())
        assertEquals(
            listOf(
                listOf("checkout", "applePay"),
                listOf("checkout", "tier"),
                listOf("checkout", "express", "oneTap"),
                listOf("checkout", "express", "confirmationDelay"),
            ),
            paths.takeLast(4),
        )
    }

    @Test
    fun keysMatchTheConfiguredEncoding() {
        assertTrue(FlagKey("checkout.apple-pay") in FlagPole(GoldenFlagsSpec, emptyList()).keys)
        assertTrue(FlagKey("use-https-only") in FlagPole(GoldenFlagsSpec, emptyList()).keys)
        assertTrue(FlagKey("checkout.apple_pay") in FlagPole(GoldenFlagsSpec, emptyList(), KeyEncoding.Snakecase).keys)
    }

    @Test
    fun everyDeclaredFlagAppearsExactlyOnce() {
        val keys = FlagPole(GoldenFlagsSpec, emptyList()).keys

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(19, keys.size)
    }

    @Test
    fun theSchemaCarriesTheApplicationNameOrNothing() {
        assertNull(FlagPole(GoldenFlagsSpec, emptyList()).schema.applicationName)
        assertEquals("Golden", FlagPole(GoldenFlagsSpec, emptyList(), applicationName = "Golden").schema.applicationName)
    }

    @Test
    fun theSchemaDescribesTheSpecWithThePolesEncoding() {
        val pole = FlagPole(GoldenFlagsSpec, emptyList(), KeyEncoding.Snakecase, "Golden")

        assertEquals(FlagSchema.of(GoldenFlagsSpec, KeyEncoding.Snakecase, "Golden").flags, pole.schema.flags)
        assertEquals(pole.schema.flags.map { it.key }, pole.keys)
    }

    @Test
    fun sourcesKeepTheirOrder() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")

        assertEquals(listOf(top, bottom), FlagPole(GoldenFlagsSpec, listOf(top, bottom)).sources)
    }

    // Clearing

    @Test
    fun removeAllOverridesOnlyClearsTheTopMutableSource() {
        // Writes go to one source, so clearing does too. A value in a lower source is not
        // an override this pole put there.
        val top = SnapshotSource("top", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val bottom = SnapshotSource("bottom", mapOf(intFlag to FlagValueBox.Int(3)))

        FlagPole(GoldenFlagsSpec, listOf(top, bottom)).removeAllOverrides()

        assertTrue(top.values.isEmpty())
        assertEquals(FlagValueBox.Int(3), bottom.values[intFlag])
    }

    @Test
    fun clearingEverythingOnlyWritesWhatWasSetInOneBatch() {
        // Writing null to every declared flag would turn one reset into hundreds of
        // cross-process broadcasts.
        val source = CountingSource()
        val pole = FlagPole(GoldenFlagsSpec, listOf(source))
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        source.writes = 0

        pole.removeAllOverrides()

        assertEquals(1, source.writes)
        assertEquals(listOf(mapOf<FlagKey, FlagValueBox?>(boolFlag to null)), source.batches)
        assertTrue(pole.overrides.isEmpty())
    }

    @Test
    fun clearingNothingWritesNothing() {
        val source = CountingSource()

        FlagPole(GoldenFlagsSpec, listOf(source)).removeAllOverrides()

        assertEquals(0, source.writes)
        assertTrue(source.batches.isEmpty())
    }

    @Test
    fun removeAllOverridesWithNoWritableSourceIsANoOp() {
        // Review focus 4. Swift finds nothing to clear, so it never reaches the write that would throw.
        val remote = ReadOnlySource("remote", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(remote))

        pole.removeAllOverrides()

        assertTrue(pole.flags.boolFlag)
    }

    // Values the codec cannot read

    @Test
    fun aBoxOfTheRightTypeThatWillNotDecodeReadsTheDefaultButNamesItsSource() {
        val values = mapOf(tier to FlagValueBox.String("platinum"), level to FlagValueBox.Int(99))
        val local = SnapshotSource("local", values)
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        assertEquals(GoldenTier.FREE, pole.flags.tier)
        assertEquals(GoldenTier.FREE, pole[GoldenFlagsSpec.tier])
        assertEquals(GoldenLevel.TWO, pole[GoldenFlagsSpec.level])
        assertEquals(FlagResolution(tier, "local", FlagValueBox.String("platinum")), pole.resolution(GoldenFlagsSpec.tier))
        assertEquals(FlagResolution(level, "local", FlagValueBox.Int(99)), pole.resolution(level, FlagValueType.Int))
    }

    @Test
    fun anUndecodableTopValueMasksAValidOneBelowIt() {
        // The ported resolver rule: the first box of the declared type wins, even one the
        // codec then cannot read. A stale case from a newer build hides the remote value.
        val top = SnapshotSource("top", mapOf(tier to FlagValueBox.String("platinum")))
        val bottom = SnapshotSource("bottom", mapOf(tier to FlagValueBox.String("pro")))
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        assertEquals(GoldenTier.FREE, pole.flags.tier)
        assertEquals("top", pole.resolution(GoldenFlagsSpec.tier).sourceName)
    }

    // Reading concurrently

    @Test
    fun readingFromManyThreadsAtOnceIsSafe() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local", mapOf(intFlag to FlagValueBox.Int(42)))))

        runConcurrently(200) {
            assertEquals(42L, pole.flags.intFlag.toLong())
            assertFalse(pole.flags.checkout.express.oneTap)
        }
    }

    @Test
    fun readingWhileAnotherThreadWritesIsSafe() {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        // Only even values are ever written, and the default, 42, is even too.
        runConcurrently(200) { index ->
            if (index % 2 == 0) {
                local.setBox(intFlag, FlagValueBox.Int(index.toLong()))
            } else {
                assertEquals(0L, pole.flags.intFlag.toLong() % 2)
            }
        }
    }

    // Lookups and resolution by key

    @Test
    fun aMinimalLookupNeedsOnlyTwoMembers() {
        // Previews and tests should not have to implement change publishing or writing.
        val lookup = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase

            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = if (key == intFlag) FlagValueBox.Int(99) else null
        }
        val flags = GoldenFlagsSpec.createFlags(lookup)

        assertEquals(99L, flags.intFlag.toLong())
        assertFalse(flags.boolFlag)
    }

    @Test
    fun resolutionByKeyMatchesTheTypedForm() {
        val local = SnapshotSource("local", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        val byKey = pole.resolution(boolFlag, FlagValueType.Bool)

        assertEquals(pole.resolution(GoldenFlagsSpec.boolFlag), byKey)
        assertEquals("local", byKey.sourceName)
    }

    @Test
    fun resolutionByKeyFillsInTheCompiledDefault() {
        val resolution = FlagPole(GoldenFlagsSpec, emptyList()).resolution(intFlag, FlagValueType.Int)

        assertTrue(resolution.isDefault)
        assertEquals(FlagValueBox.Int(42), resolution.box, "should report the value in effect")
    }

    @Test
    fun resolutionByAnUndeclaredKeyHasNoValueInEffect() {
        val nope = FlagKey("nope")

        assertEquals(FlagResolution(nope, null, null), FlagPole(GoldenFlagsSpec, emptyList()).resolution(nope, FlagValueType.Bool))
    }

    @Test
    fun theTypedAndKeyedDefaultsAgree() {
        // The typed form encodes the ref's default; the keyed form reads the schema's.
        val pole = FlagPole(GoldenFlagsSpec, emptyList())

        assertEquals(pole.resolution(GoldenFlagsSpec.endpoints), pole.resolution(FlagKey("endpoints"), FlagValueType.String))
        assertEquals(pole.resolution(GoldenFlagsSpec.dataFlag), pole.resolution(FlagKey("data-flag"), FlagValueType.Data))
        assertEquals(
            pole.resolution(GoldenFlagsSpec.checkout.express.confirmationDelay),
            pole.resolution(FlagKey("checkout.express.confirmation-delay"), FlagValueType.Double),
        )
    }

    @Test
    fun everyFlagCanBeExplainedInALoop() {
        // The point of the keyed form: a diagnostics screen over the whole schema.
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        val explained = pole.schema.flags.map { pole.resolution(it.key, it.valueType) }

        assertEquals(19, explained.size)
        assertTrue(explained.all { it.box != null }, "every flag has a value in effect")
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.pole.*'
```

Expected: `BUILD FAILED`, with `compileTestKotlin` reporting `Unresolved reference 'FlagPole'`, `'FlagResolver'` and `'FlagResolution'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagResolution.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/**
 * Where a flag's value came from.
 *
 * @property sourceName the source that supplied the value, or null when nothing did and
 *   the compiled default applies.
 * @property box the value in effect. A pole fills in the compiled default here, so it is
 *   null only for a key the pole does not declare.
 */
public data class FlagResolution(
    public val key: FlagKey,
    public val sourceName: String?,
    public val box: FlagValueBox?,
) {

    /** Whether the compiled default is in effect. */
    public val isDefault: Boolean
        get() = sourceName == null
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagResolver.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * Walks the source stack to answer "what is this flag's value, and who supplied it?".
 *
 * Kept apart from [FlagPole] so the pole can hand a fully formed lookup to its container
 * as it is built.
 */
internal class FlagResolver(
    private val sources: List<FlagValueSource>,
    override val keyEncoding: KeyEncoding,
) : FlagLookup {

    private val topMutableSource: MutableFlagValueSource? =
        sources.firstNotNullOfOrNull { it as? MutableFlagValueSource }

    /**
     * The first source holding a value of the declared type wins. A value of the wrong
     * type is skipped rather than surfaced, so a stale or hand-edited store degrades to
     * the next source instead of to a crash.
     */
    fun resolution(key: FlagKey, type: FlagValueType): FlagResolution {
        for (source in sources) {
            val box = source.box(key, type)
            if (box != null && box.matches(type)) return FlagResolution(key, source.sourceName, box)
        }
        return FlagResolution(key, sourceName = null, box = null)
    }

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = resolution(key, type).box

    /** Writes to the highest-priority source that accepts writes. */
    fun setOverride(key: FlagKey, box: FlagValueBox?) {
        writableSource().setBox(key, box)
    }

    /** Writes several values to the highest-priority mutable source, in one `setBoxes` call. */
    fun setOverrides(changes: Map<FlagKey, FlagValueBox?>) {
        writableSource().setBoxes(changes)
    }

    /**
     * What the source writes go to currently holds, whatever its type. Undoing a partly
     * applied import has to read back exactly that source, not the resolved value.
     */
    fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox? = topMutableSource?.box(key, type)

    private fun writableSource(): MutableFlagValueSource = topMutableSource ?: throw FlagException.NoMutableSource()
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagPole.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.flattened
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * Reads feature flags, resolving each one through an ordered stack of sources, as in
 * `FlagPole(AppFlagsSpec, sources = listOf(local, remote)).flags.checkout.googlePay`.
 *
 * The order of [sources] is the precedence. Anything no source supplies falls back to
 * the flag's compiled default. Reads are lock-free and safe from any thread.
 *
 * @param keyEncoding how property-name paths become storage keys. Changing it renames
 *   every key, so choose it once.
 * @param applicationName what a companion app calls this app. It goes into [schema]; null
 *   leaves the name out, as Swift does for an app without a display name.
 */
public class FlagPole<F : Any>(
    public val spec: FlagContainerSpec<F>,
    sources: List<FlagValueSource>,
    public val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    public val applicationName: String? = null,
) {

    /** The source stack, highest priority first. */
    public val sources: List<FlagValueSource> = sources.toList()

    private val resolver = FlagResolver(this.sources, keyEncoding)

    /** The flag tree, read through the source stack. */
    public val flags: F = spec.createFlags(resolver)

    /** This pole's flags, described so a companion app can render them without the host's types. Built once. */
    public val schema: FlagSchema = FlagSchema.of(spec, keyEncoding = keyEncoding, applicationName = applicationName)

    /** Every flag this pole knows about, flattened out of the container tree, depth first. */
    public val descriptors: List<FlagDescriptor>
        get() = spec.flagDescriptors.flattened()

    /** The key each flag resolves against, in declaration order. */
    public val keys: List<FlagKey>
        get() = schema.flags.map { it.key }

    /**
     * Every declared flag some source supplies a value for.
     *
     * This spans the whole stack, not just local overrides: a value a remote payload
     * supplied is included, and exporting therefore carries it too.
     */
    public val overrides: Map<FlagKey, FlagValueBox>
        get() = buildMap {
            for (entry in schema.flags) {
                val box = resolver.box(entry.key, entry.valueType)
                if (box != null) put(entry.key, box)
            }
        }

    /** A flag's current value, read without going through [flags]. */
    public operator fun <T> get(ref: FlagRef<T>): T = resolver.read(key(ref), ref.codec) { ref.defaultValue }

    /** The storage key [ref] resolves against under this pole's [keyEncoding]. */
    public fun key(ref: FlagRef<*>): FlagKey = ref.key(keyEncoding)

    /**
     * Which source supplied a flag's current value, and what it was: the answer to "why
     * is this flag false?". When nothing did, `box` is the compiled default.
     */
    public fun resolution(ref: FlagRef<*>): FlagResolution {
        val flagKey = key(ref)
        val resolution = resolver.resolution(flagKey, ref.codec.type)
        return if (resolution.isDefault) FlagResolution(flagKey, sourceName = null, box = ref.defaultBox()) else resolution
    }

    /**
     * Which source supplied a value, addressed by what a schema entry carries, so a
     * diagnostics screen can explain every flag in a loop.
     */
    public fun resolution(key: FlagKey, type: FlagValueType): FlagResolution {
        val resolution = resolver.resolution(key, type)
        if (!resolution.isDefault) return resolution
        val fallback = schema.flags.firstOrNull { it.key == key }?.defaultValue
        return FlagResolution(key, sourceName = null, box = fallback)
    }

    /** Writes an override to the highest-priority mutable source. */
    public fun <T> setOverride(ref: FlagRef<T>, value: T) {
        resolver.setOverride(key(ref), ref.codec.encode(value))
    }

    /** Writes an already-boxed override, or removes it when [box] is null. */
    public fun setOverride(key: FlagKey, box: FlagValueBox?) {
        resolver.setOverride(key, box)
    }

    /** Clears a flag's override, restoring whatever the sources beneath say. */
    public fun removeOverride(ref: FlagRef<*>) {
        resolver.setOverride(key(ref), null)
    }

    /**
     * Clears the override of every declared flag, in one batch.
     *
     * Only declared flags are touched, so unrelated values sharing the store are left
     * alone. Flags with no override are skipped rather than written blindly, so a pole
     * with no mutable source has nothing to clear and does not throw.
     */
    public fun removeAllOverrides() {
        val cleared = schema.flags
            .filter { resolver.currentOverride(it.key, it.valueType) != null }
            .map { it.key }
            .associateWith<FlagKey, FlagValueBox?> { null }
        if (cleared.isNotEmpty()) resolver.setOverrides(cleared)
    }

    /** What the mutable source holds for [key], ignoring the sources beneath it. Used to undo an import. */
    public fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox? = resolver.currentOverride(key, type)
}

private fun <T> FlagRef<T>.defaultBox(): FlagValueBox = codec.encode(defaultValue)
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.pole.*'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both commands. The first runs 51 tests: 7 in `FlagResolverTest`, 17 in `FlagPoleTest` and 27 in `FlagPoleEdgeCaseTest`.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole
git commit -m "Add FlagPole with source resolution and overrides" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task C3: Change flows: `changes` and `flow(ref)`

**Files:**
- Modify (full replacement): `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagResolver.kt`
- Modify (full replacement): `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagPole.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleFlowTest.kt`

**Interfaces:**
- Consumes: C1 `FlagValueSource.changes`, `FlagChange.affects`; C2 `FlagResolver`, `FlagPole`.
- Produces:
```kotlin
// FlagPole
public val changes: Flow<FlagChange>              // every source merged; cold; delivered on the writer's thread
public fun <T> flow(ref: FlagRef<T>): Flow<T>     // current value, then each distinct new one; never completes
// FlagResolver (internal)
val changes: Flow<FlagChange>
fun ticks(key: FlagKey): Flow<Unit>               // one tick at once, then one per change that affects key (conflated)
```

The structure is the race-free pattern from the overview. It replaces `channelFlow` with `flow { coroutineScope { … } }`, because `channelFlow` runs its block in a separately dispatched producer.
- **Subscribe first.** Every source is subscribed with `launch(Dispatchers.Unconfined, start = UNDISPATCHED)`, inside the collector's own frame. Every subscription therefore exists before `collect` first suspends. That is what makes `changes` safe to collect and then write to straight away, as Turbine's `test {}` does.
- **Then tick.** `flow(ref)` sends its initial tick after subscribing, so a write made during subscription is either seen by the first read or followed by a tick.
- **Keep up.** The subscriptions run `Unconfined`, so each change is filtered on the writer's thread and lands in a `CONFLATED` channel. A collector that is busy cannot fall behind the source's 64-slot `DROP_OLDEST` buffer, which is Review Focus 1.
- **Then map.** Each tick resolves the box, `distinctUntilChanged` drops repeated boxes, and the result is decoded or falls back to the default. A final `distinctUntilChanged` drops a repeat of the same value, such as an unreadable box that still reads as the default.

- [ ] **Step 1: Write the failing tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleFlowTest.kt`
```kotlin
package com.andyyhope.featureflag.pole

import app.cash.turbine.test
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class FlagPoleFlowTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val stringFlag = FlagKey("string-flag")
    private val tier = FlagKey("tier")

    // flow(ref)

    @Test
    fun aFlowEmitsTheCurrentValueImmediately() = runBlocking<Unit> {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun aFlowEmitsWhenItsValueChanges() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            local.setBox(boolFlag, FlagValueBox.Bool(true))
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun aFlowFollowsAnUpdateFromEachSource() = runBlocking<Unit> {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        pole.flow(GoldenFlagsSpec.stringFlag).test {
            assertEquals("hello/world \"quoted\"", awaitItem())
            bottom.setBox(stringFlag, FlagValueBox.String("from bottom"))
            assertEquals("from bottom", awaitItem())
            top.setBox(stringFlag, FlagValueBox.String("from top"))
            assertEquals("from top", awaitItem())
            top.setBox(stringFlag, null)
            assertEquals("from bottom", awaitItem())
        }
    }

    @Test
    fun aFlowIgnoresUnrelatedKeys() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            local.setBox(intFlag, FlagValueBox.Int(5))
            expectNoEvents()
        }
    }

    @Test
    fun anUnrelatedChangeDoesNotEvenCauseARead() = runBlocking<Unit> {
        val local = SnapshotSource("local")

        FlagResolver(listOf(local), KeyEncoding.Kebabcase).ticks(boolFlag).test {
            awaitItem()
            local.setBox(intFlag, FlagValueBox.Int(5))
            expectNoEvents()
            local.setBox(boolFlag, FlagValueBox.Bool(true))
            awaitItem()
        }
    }

    @Test
    fun aChangeThatLeavesTheValueAloneEmitsNothing() = runBlocking<Unit> {
        val top = SnapshotSource("top", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val bottom = SnapshotSource("bottom")
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(true, awaitItem())
            bottom.setBox(boolFlag, FlagValueBox.Bool(false))
            top.replaceAll(mapOf(boolFlag to FlagValueBox.Bool(true)))
            expectNoEvents()
        }
    }

    @Test
    fun aValueTheCodecCannotReadEmitsNothingWhileTheDefaultStaysInEffect() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.tier).test {
            assertEquals(GoldenTier.FREE, awaitItem())
            local.setBox(tier, FlagValueBox.String("platinum"))
            expectNoEvents()
            local.setBox(tier, FlagValueBox.String("pro"))
            assertEquals(GoldenTier.PRO, awaitItem())
        }
    }

    @Test
    fun aWholesaleChangeIsReadAgain() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            local.replaceAll(mapOf(boolFlag to FlagValueBox.Bool(true)))
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun aFlowUsesThePolesKeyEncoding() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local), keyEncoding = KeyEncoding.Snakecase)

        pole.flow(GoldenFlagsSpec.checkout.applePay).test {
            assertEquals(true, awaitItem())
            local.setBox(FlagKey("checkout.apple_pay"), FlagValueBox.Bool(false))
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun aFlowOnAnEmptyStackEmitsTheDefaultAndStaysOpen() = runBlocking<Unit> {
        FlagPole(GoldenFlagsSpec, emptyList()).flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            expectNoEvents()
        }
    }

    // changes

    @Test
    fun changesFiresWhenASourceChanges() = runBlocking<Unit> {
        // The port of objectWillChange: subscribe, write at once, see it.
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.changes.test {
            local.setBox(boolFlag, FlagValueBox.Bool(true))
            assertEquals(FlagChange.Keys(setOf(boolFlag)), awaitItem())
        }
    }

    @Test
    fun changesMergesEverySourceAndPassesWholesaleChangesThrough() = runBlocking<Unit> {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        pole.changes.test {
            bottom.setBox(intFlag, FlagValueBox.Int(1))
            assertEquals(FlagChange.Keys(setOf(intFlag)), awaitItem())
            top.replaceAll(emptyMap())
            assertEquals(FlagChange.All, awaitItem())
        }
    }

    // Races

    @Test
    fun writesRacingWithSubscriptionNeverLoseTheFinalValue() {
        val pool = Executors.newFixedThreadPool(2)
        val writerThread = pool.asCoroutineDispatcher()
        try {
            runBlocking<Unit> {
                repeat(200) { round ->
                    val local = SnapshotSource("local")
                    val pole = FlagPole(GoldenFlagsSpec, listOf(local))
                    val writer = launch(writerThread) {
                        for (value in 1..20) local.setBox(stringFlag, FlagValueBox.String("v$value"))
                    }

                    val last = withTimeout(5_000) { pole.flow(GoldenFlagsSpec.stringFlag).first { it == "v20" } }

                    assertEquals("v20", last, "round $round")
                    writer.join()
                }
            }
        } finally {
            writerThread.close()
        }
    }

    @Test
    fun aBusyCollectorStillSeesItsChangeAfterABurstOfOtherWrites() {
        // Review focus 1. The collector's only thread is blocked while its own key changes
        // and then 200 other keys change. A subscription running on that thread would
        // fall more than 64 changes behind, and the source would drop the one that matters.
        val executor = Executors.newSingleThreadExecutor()
        val busyThread = executor.asCoroutineDispatcher()
        try {
            runBlocking<Unit> {
                val local = SnapshotSource("local")
                val pole = FlagPole(GoldenFlagsSpec, listOf(local))
                val seen = Channel<Boolean>(Channel.UNLIMITED)
                val collector = launch(busyThread) { pole.flow(GoldenFlagsSpec.boolFlag).collect { seen.send(it) } }
                assertEquals(false, withTimeout(5_000) { seen.receive() })

                val blocked = CountDownLatch(1)
                val release = CountDownLatch(1)
                executor.execute {
                    blocked.countDown()
                    release.await()
                }
                blocked.await()
                local.setBox(boolFlag, FlagValueBox.Bool(true))
                repeat(200) { local.setBox(intFlag, FlagValueBox.Int(it.toLong())) }
                release.countDown()

                assertEquals(true, withTimeout(5_000) { seen.receive() })
                collector.cancel()
            }
        } finally {
            busyThread.close()
        }
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.pole.FlagPoleFlowTest'
```

Expected: `BUILD FAILED`, with `compileTestKotlin` reporting `Unresolved reference 'flow'`, `'changes'` and `'ticks'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagResolver.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Walks the source stack to answer "what is this flag's value, and who supplied it?",
 * and watches the stack for changes.
 *
 * Kept apart from [FlagPole] so the pole can hand a fully formed lookup to its container
 * as it is built.
 */
internal class FlagResolver(
    private val sources: List<FlagValueSource>,
    override val keyEncoding: KeyEncoding,
) : FlagLookup {

    private val topMutableSource: MutableFlagValueSource? =
        sources.firstNotNullOfOrNull { it as? MutableFlagValueSource }

    /**
     * Every change every source reports, merged. Every source is subscribed before
     * `collect` first suspends. A collector more than 64 changes behind loses the
     * oldest, as with the sources themselves.
     */
    val changes: Flow<FlagChange> = flow {
        coroutineScope {
            val pending = Channel<FlagChange>(CHANGE_BUFFER, BufferOverflow.DROP_OLDEST)
            subscribe { change -> pending.trySend(change) }
            while (true) emit(pending.receive())
        }
    }

    /**
     * One tick straight away, then one whenever a change may affect [key]. Ticks are
     * conflated, so a reader that is busy reads once for any number of changes and never
     * misses the last one.
     */
    fun ticks(key: FlagKey): Flow<Unit> = flow {
        coroutineScope {
            val pending = Channel<Unit>(Channel.CONFLATED)
            subscribe { change -> if (change.affects(key)) pending.trySend(Unit) }
            pending.trySend(Unit)
            while (true) emit(pending.receive())
        }
    }

    /**
     * The first source holding a value of the declared type wins. A value of the wrong
     * type is skipped rather than surfaced, so a stale or hand-edited store degrades to
     * the next source instead of to a crash.
     */
    fun resolution(key: FlagKey, type: FlagValueType): FlagResolution {
        for (source in sources) {
            val box = source.box(key, type)
            if (box != null && box.matches(type)) return FlagResolution(key, source.sourceName, box)
        }
        return FlagResolution(key, sourceName = null, box = null)
    }

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = resolution(key, type).box

    /** Writes to the highest-priority source that accepts writes. */
    fun setOverride(key: FlagKey, box: FlagValueBox?) {
        writableSource().setBox(key, box)
    }

    /** Writes several values to the highest-priority mutable source, in one `setBoxes` call. */
    fun setOverrides(changes: Map<FlagKey, FlagValueBox?>) {
        writableSource().setBoxes(changes)
    }

    /**
     * What the source writes go to currently holds, whatever its type. Undoing a partly
     * applied import has to read back exactly that source, not the resolved value.
     */
    fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox? = topMutableSource?.box(key, type)

    private fun writableSource(): MutableFlagValueSource = topMutableSource ?: throw FlagException.NoMutableSource()

    /**
     * Subscribes to every source before returning. `UNDISPATCHED` registers each
     * subscription now, in the caller's frame. `Unconfined` then runs [onChange] on the
     * writer's thread, so a busy collector never leaves changes queued in a source's
     * bounded buffer. That makes the dispatcher part of the mechanism, not a choice to inject.
     */
    @Suppress("InjectDispatcher")
    private fun CoroutineScope.subscribe(onChange: (FlagChange) -> Unit) {
        for (source in sources) {
            launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                source.changes.collect { change -> onChange(change) }
            }
        }
    }

    private companion object {
        const val CHANGE_BUFFER = 64
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagPole.kt`
```kotlin
package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.flattened
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Reads feature flags, resolving each one through an ordered stack of sources, as in
 * `FlagPole(AppFlagsSpec, sources = listOf(local, remote)).flags.checkout.googlePay`, and
 * watches one with `pole.flow(AppFlagsSpec.checkout.googlePay)`.
 *
 * The order of [sources] is the precedence. Anything no source supplies falls back to
 * the flag's compiled default. Reads are lock-free and safe from any thread, and no flow
 * here hops to the main thread.
 *
 * @param keyEncoding how property-name paths become storage keys. Changing it renames
 *   every key, so choose it once.
 * @param applicationName what a companion app calls this app. It goes into [schema]; null
 *   leaves the name out, as Swift does for an app without a display name.
 */
public class FlagPole<F : Any>(
    public val spec: FlagContainerSpec<F>,
    sources: List<FlagValueSource>,
    public val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    public val applicationName: String? = null,
) {

    /** The source stack, highest priority first. */
    public val sources: List<FlagValueSource> = sources.toList()

    private val resolver = FlagResolver(this.sources, keyEncoding)

    /** The flag tree, read through the source stack. */
    public val flags: F = spec.createFlags(resolver)

    /** This pole's flags, described so a companion app can render them without the host's types. Built once. */
    public val schema: FlagSchema = FlagSchema.of(spec, keyEncoding = keyEncoding, applicationName = applicationName)

    /**
     * Every change any source reports: cold, delivered on the thread that made the write.
     * A collector more than 64 changes behind loses the oldest. Watch one flag with [flow]
     * instead, which never loses its last change.
     */
    public val changes: Flow<FlagChange>
        get() = resolver.changes

    /** Every flag this pole knows about, flattened out of the container tree, depth first. */
    public val descriptors: List<FlagDescriptor>
        get() = spec.flagDescriptors.flattened()

    /** The key each flag resolves against, in declaration order. */
    public val keys: List<FlagKey>
        get() = schema.flags.map { it.key }

    /**
     * Every declared flag some source supplies a value for.
     *
     * This spans the whole stack, not just local overrides: a value a remote payload
     * supplied is included, and exporting therefore carries it too.
     */
    public val overrides: Map<FlagKey, FlagValueBox>
        get() = buildMap {
            for (entry in schema.flags) {
                val box = resolver.box(entry.key, entry.valueType)
                if (box != null) put(entry.key, box)
            }
        }

    /** A flag's current value, read without going through [flags]. */
    public operator fun <T> get(ref: FlagRef<T>): T = resolver.read(key(ref), ref.codec) { ref.defaultValue }

    /**
     * A flag's value now, then each time it changes to something different.
     *
     * Every source is subscribed before the first value is read, so a write made while
     * collection starts is never lost. Values arrive on the collector's context, and the
     * flow never completes.
     */
    public fun <T> flow(ref: FlagRef<T>): Flow<T> {
        val flagKey = key(ref)
        val type = ref.codec.type
        return resolver.ticks(flagKey)
            .map { resolver.box(flagKey, type) }
            .distinctUntilChanged()
            .map { box -> box?.let { ref.codec.decode(it) } ?: ref.defaultValue }
            .distinctUntilChanged()
    }

    /** The storage key [ref] resolves against under this pole's [keyEncoding]. */
    public fun key(ref: FlagRef<*>): FlagKey = ref.key(keyEncoding)

    /**
     * Which source supplied a flag's current value, and what it was: the answer to "why
     * is this flag false?". When nothing did, `box` is the compiled default.
     */
    public fun resolution(ref: FlagRef<*>): FlagResolution {
        val flagKey = key(ref)
        val resolution = resolver.resolution(flagKey, ref.codec.type)
        return if (resolution.isDefault) FlagResolution(flagKey, sourceName = null, box = ref.defaultBox()) else resolution
    }

    /**
     * Which source supplied a value, addressed by what a schema entry carries, so a
     * diagnostics screen can explain every flag in a loop.
     */
    public fun resolution(key: FlagKey, type: FlagValueType): FlagResolution {
        val resolution = resolver.resolution(key, type)
        if (!resolution.isDefault) return resolution
        val fallback = schema.flags.firstOrNull { it.key == key }?.defaultValue
        return FlagResolution(key, sourceName = null, box = fallback)
    }

    /** Writes an override to the highest-priority mutable source. */
    public fun <T> setOverride(ref: FlagRef<T>, value: T) {
        resolver.setOverride(key(ref), ref.codec.encode(value))
    }

    /** Writes an already-boxed override, or removes it when [box] is null. */
    public fun setOverride(key: FlagKey, box: FlagValueBox?) {
        resolver.setOverride(key, box)
    }

    /** Clears a flag's override, restoring whatever the sources beneath say. */
    public fun removeOverride(ref: FlagRef<*>) {
        resolver.setOverride(key(ref), null)
    }

    /**
     * Clears the override of every declared flag, in one batch.
     *
     * Only declared flags are touched, so unrelated values sharing the store are left
     * alone. Flags with no override are skipped rather than written blindly, so a pole
     * with no mutable source has nothing to clear and does not throw.
     */
    public fun removeAllOverrides() {
        val cleared = schema.flags
            .filter { resolver.currentOverride(it.key, it.valueType) != null }
            .map { it.key }
            .associateWith<FlagKey, FlagValueBox?> { null }
        if (cleared.isNotEmpty()) resolver.setOverrides(cleared)
    }

    /** What the mutable source holds for [key], ignoring the sources beneath it. Used to undo an import. */
    public fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox? = resolver.currentOverride(key, type)
}

private fun <T> FlagRef<T>.defaultBox(): FlagValueBox = codec.encode(defaultValue)
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.pole.*'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both commands. The first runs 65 tests, the 14 in `FlagPoleFlowTest` among them.

Two more checks:
- **Pin the dispatcher.** Temporarily delete `Dispatchers.Unconfined, ` from the `launch` call in `subscribe`, so the subscription inherits the collector's blocked thread. `aBusyCollectorStillSeesItsChangeAfterABurstOfOtherWrites` must then fail with a `TimeoutCancellationException`. Revert the change.
- **Flakiness.** Run the flow tests five times. All five runs must pass:

```bash
for run in 1 2 3 4 5; do ./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.pole.FlagPoleFlowTest' --rerun -q || break; done
```

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleFlowTest.kt
git commit -m "Add race-free change flows to FlagPole" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task C4: `FlagPayload`, `FlagImportException` and JSON transport

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagImportException.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagImportExceptionTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/TransportTestSupport.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/GoldenOverrides.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagPayloadTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransportTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/ExportGoldenTest.kt`

**Interfaces:**
- Consumes:
  - M1a: `JsonValue`, `JsonReader.parse` (it accepts a byte order mark and a trailing comma, and reads `-1e400` as negative infinity), `JsonSyntaxException`, `JsonWriter.pretty`, `IsoDate.format`/`parse`, `FlagValueBox.toJson()`, `JsonValue.toBox(type)`, `FlagValueBox.containsNonFiniteNumber`, `FlagValueType.typeName`, `RecordJson.decode`, `RecordJson.duplicateKey(text, shape)`, `MessageText`, `FlagCodecs.records`, `FlagSerializationException.NonFiniteNumber`.
  - M1b: `FlagSchema.of`, `FlagSchema.flags` entries (`key`, `valueType`, `cases`, `recordShape`), and the test sources' `GoldenFlagsSpec` and `GoldenEndpointFlagRecord`.
  - C1 and C2: `SnapshotSource`, `MutableFlagValueSource`, `FlagPole`, and the test helper `ReadOnlySource`.
- Produces:
```kotlin
public data class FlagImportProblem(public val key: FlagKey, public val kind: Kind, public val expected: String? = null, public val found: String? = null) {
    public enum class Kind { UnknownKey, TypeMismatch, UnknownCase }
    override fun toString(): String                                   // Swift FlagImportProblem.description
}
public sealed class FlagImportException(message: String) : RuntimeException(message) {
    public class Malformed(public val reason: String) : FlagImportException
    public class UnsupportedFormatVersion(public val version: Long) : FlagImportException   // Long: Swift's Int is 64-bit
    public class Rejected(public val problems: List<FlagImportProblem>) : FlagImportException
}
public data class FlagPayload(public val values: Map<FlagKey, FlagValueBox>, public val exportedAt: Instant = Instant.now(),
    public val formatVersion: Int = CURRENT_FORMAT_VERSION) {
    public fun jsonBytes(): ByteArray
    public companion object { public const val CURRENT_FORMAT_VERSION: Int = 1; public fun decode(bytes: ByteArray, schema: FlagSchema): FlagPayload }
}
internal class ImportRules(schema: FlagSchema) { val types: Map<FlagKey, FlagValueType>; fun problemWith(key, raw, box): FlagImportProblem? }
// transport/FlagPoleTransport.kt, package com.andyyhope.featureflag.transport
public fun FlagPole<*>.exportPayload(): FlagPayload
public fun FlagPole<*>.exportJson(): ByteArray
public fun FlagPole<*>.importJson(bytes: ByteArray): FlagPayload        // returns the applied payload
public fun FlagPole<*>.apply(payload: FlagPayload)                     // rolls back already-written keys if a later write throws
```

- [ ] **Step 1: Write the failing tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagImportExceptionTest.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class FlagImportExceptionTest {

    private val pageSize = FlagKey("page-size")
    private val tier = FlagKey("tier")

    @Test
    fun aTypeMismatchNamesTheFlagTheTypeAndTheValue() {
        assertEquals(
            "page-size: expected int, got \"big\"",
            FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch, "int", "\"big\"").toString(),
        )
        assertEquals("page-size: expected another type", FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch).toString())
    }

    @Test
    fun anUnknownCaseListsTheCasesThisBuildHas() {
        assertEquals(
            "tier: \"gold\" is not one of free, pro",
            FlagImportProblem(tier, FlagImportProblem.Kind.UnknownCase, "free, pro", "\"gold\"").toString(),
        )
        assertEquals(
            "tier: that value is not one of this flag's cases",
            FlagImportProblem(tier, FlagImportProblem.Kind.UnknownCase).toString(),
        )
    }

    @Test
    fun anUnknownKeySaysWhereTheDocumentCameFrom() {
        assertEquals(
            "nope: no flag has this key. The document came from a different app, " +
                "or from a build where the flag was named differently",
            FlagImportProblem(FlagKey("nope"), FlagImportProblem.Kind.UnknownKey, found = "1").toString(),
        )
    }

    @Test
    fun aRejectionListsEveryProblemAndSaysNothingWasApplied() {
        val error = FlagImportException.Rejected(
            listOf(
                FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch, "int", "\"big\""),
                FlagImportProblem(tier, FlagImportProblem.Kind.UnknownCase, "free, pro", "\"gold\""),
            ),
        )

        assertEquals(
            """
            Nothing was applied. 2 problems:
              • page-size: expected int, got "big"
              • tier: "gold" is not one of free, pro
            Import is all or nothing, so no flag was changed.
            """.trimIndent(),
            error.message,
        )
    }

    @Test
    fun aSingleProblemIsSingular() {
        val error = FlagImportException.Rejected(
            listOf(FlagImportProblem(pageSize, FlagImportProblem.Kind.TypeMismatch, "int", "\"big\"")),
        )

        assertEquals(
            "Nothing was applied. 1 problem:\n  • page-size: expected int, got \"big\"\n" +
                "Import is all or nothing, so no flag was changed.",
            error.message,
        )
    }

    @Test
    fun aRejectionWithNoProblemsStillReadsAsASentence() {
        assertEquals("Nothing was applied, and no problem was recorded.", FlagImportException.Rejected(emptyList()).message)
    }

    @Test
    fun anUnsupportedDocumentVersionSaysWhichBuildIsBehind() {
        val error = FlagImportException.UnsupportedFormatVersion(9)

        assertEquals(9L, error.version)
        assertEquals(
            "The document is format version 9; this build understands 1. Update whichever app is older.",
            error.message,
        )
    }

    @Test
    fun aMalformedDocumentSaysWhatWasWrongWithIt() {
        val error = FlagImportException.Malformed("missing values")

        assertEquals("missing values", error.reason)
        assertEquals("The document could not be read: missing values.", error.message)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/TransportTestSupport.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.KeyEncoding
import java.io.File
import java.time.Instant

/** The GoldenFlags schema, exactly as the fixture generator builds it. */
internal val goldenSchema: FlagSchema = FlagSchema.of(
    GoldenFlagsSpec,
    keyEncoding = KeyEncoding.Kebabcase,
    applicationName = "Golden",
    generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

/** `fixtures/` at the repository root, as Gradle hands it to the test JVM. */
internal fun fixturesDirectory(): File {
    val path = checkNotNull(System.getProperty("featureflag.fixtures")) {
        "featureflag.fixtures is not set; run the tests through Gradle"
    }
    return File(path)
}

/** Bytes no compressor can shrink, from the generator the Swift tests use, so both sides see the same input. */
internal fun incompressibleBytes(count: Int, seed: ULong): ByteArray {
    var state = seed
    return ByteArray(count) {
        state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
        (state shr 33).toByte()
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/GoldenOverrides.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.golden.GoldenEndpoint
import com.andyyhope.featureflag.golden.GoldenEndpointFlagRecord
import com.andyyhope.featureflag.golden.GoldenTarget
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.net.URI
import java.time.Instant

/**
 * The override set behind `fixtures/export/golden.json` and `fixtures/qr/golden.*`.
 *
 * It must stay identical to `TransportFixtures.goldenPayload` in the Swift generator, as
 * `RecordsGoldenTest` stays identical to `recordLists`.
 */
internal object GoldenOverrides {

    val exportedAt: Instant = Instant.ofEpochSecond(1_767_260_460)

    /** `TransportFixtures.recordLists`' "full" list. */
    val endpoints: FlagRecords<GoldenEndpoint> = FlagRecords(
        listOf(
            GoldenEndpoint(
                "prod",
                URI("https://prod.example.com/v1?x=1"),
                weight = 10,
                note = "primary \"quoted\" / slash",
                kind = GoldenTier.ENTERPRISE,
                targets = FlagRecords(listOf(GoldenTarget("a.example", 443), GoldenTarget("b.example", 8443))),
            ),
            GoldenEndpoint("staging", URI("https://staging.example.com"), weight = 0, kind = GoldenTier.PRO),
            GoldenEndpoint("\u00fcn\u00efcode", URI("https://example.com/%C3%BC"), weight = -3, note = ""),
        ),
    )

    val values: Map<FlagKey, FlagValueBox> = linkedMapOf(
        FlagKey("bool-flag") to FlagValueBox.Bool(true),
        FlagKey("int-flag") to FlagValueBox.Int(-7),
        FlagKey("double-flag") to FlagValueBox.Double(0.30000000000000004),
        FlagKey("float-flag") to FlagValueBox.Float(1.1f),
        FlagKey("string-flag") to FlagValueBox.String("line1\nline2\t\"q\" / \u00e9 \ud83d\ude00"),
        FlagKey("data-flag") to FlagValueBox.Data(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())),
        FlagKey("date-flag") to FlagValueBox.Date(Instant.ofEpochMilli(1_767_260_460_750)),
        FlagKey("url-flag") to FlagValueBox.Url(URI("https://example.com/path?q=1&r=a%20b#frag")),
        FlagKey("use-https-only") to FlagValueBox.Bool(true),
        FlagKey("markets") to FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"), FlagValueBox.String("US"))),
        FlagKey("matrix") to FlagValueBox.Array(listOf(FlagValueBox.Array(emptyList()), FlagValueBox.Array(listOf(FlagValueBox.Bool(true))))),
        FlagKey("limits") to FlagValueBox.Dictionary(
            mapOf("z" to FlagValueBox.Int(26), "a10" to FlagValueBox.Int(10), "a9" to FlagValueBox.Int(9), "A" to FlagValueBox.Int(1)),
        ),
        FlagKey("tier") to FlagValueBox.String("enterprise"),
        FlagKey("level") to FlagValueBox.Int(3),
        FlagKey("endpoints") to FlagCodecs.records(GoldenEndpointFlagRecord).encode(endpoints),
        FlagKey("checkout.apple-pay") to FlagValueBox.Bool(false),
        FlagKey("checkout.tier") to FlagValueBox.String("free"),
        FlagKey("checkout.express.one-tap") to FlagValueBox.Bool(true),
        FlagKey("checkout.express.confirmation-delay") to FlagValueBox.Double(0.5),
    )

    /** What reading the export back yields: the document carries the date to the second. */
    val decodedValues: Map<FlagKey, FlagValueBox> =
        values + (FlagKey("date-flag") to FlagValueBox.Date(Instant.ofEpochSecond(1_767_260_460)))
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagPayloadTest.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `fixtures/qr/single.json`, measured with FeatureFlag 0.12.0: `["bool-flag": .bool(true)]` exported at 1_767_260_460. */
private val SINGLE_EXPORT = """
    {
      "exportedAt" : "2026-01-01T09:41:00Z",
      "formatVersion" : 1,
      "values" : {
        "bool-flag" : true
      }
    }
""".trimIndent()

/** Measured with FeatureFlag 0.12.0: an empty object is its braces around an empty line. */
private val EMPTY_EXPORT = """
    {
      "exportedAt" : "2026-01-01T09:41:00Z",
      "formatVersion" : 1,
      "values" : {

      }
    }
""".trimIndent()

/** FeatureFlag 0.12.0's `String(describing:)` of the rejection in `handEditedMistakesAreReportedExactlyAsAppleReportsThem`. */
private val NINE_PROBLEMS = """
    Nothing was applied. 9 problems:
      • bool-flag: expected bool, got 1
      • double-flag: expected double, got <null>
      • int-flag: expected int, got true
      • level: 99 is not one of int(1), int(2), int(3)
      • limits: expected dictionary<int>, got an array of 2
      • markets: expected array<string>, got "NZ"
      • nope: no flag has this key. The document came from a different app, or from a build where the flag was named differently
      • string-flag: expected string, got 1.5
      • tier: "platinum" is not one of free, pro, enterprise
    Import is all or nothing, so no flag was changed.
""".trimIndent()

class FlagPayloadTest {

    private val boolFlag = FlagKey("bool-flag")
    private val endpoints = FlagKey("endpoints")

    private fun decode(json: String): FlagPayload = FlagPayload.decode(json.encodeToByteArray(), goldenSchema)

    private fun rejection(json: String): FlagImportException.Rejected =
        assertFailsWith<FlagImportException.Rejected> { decode(json) }

    // Writing

    @Test
    fun anExportIsByteIdenticalToApple() {
        val payload = FlagPayload(mapOf(boolFlag to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt)

        assertEquals(SINGLE_EXPORT, payload.jsonBytes().decodeToString())
    }

    @Test
    fun anEmptyExportIsByteIdenticalToApple() {
        assertEquals(EMPTY_EXPORT, FlagPayload(emptyMap(), GoldenOverrides.exportedAt).jsonBytes().decodeToString())
    }

    @Test
    fun aNonFiniteValueIsRefusedAndNamed() {
        val payload = FlagPayload(mapOf(FlagKey("double-flag") to FlagValueBox.Double(Double.NaN)))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { payload.jsonBytes() }

        assertEquals(FlagKey("double-flag"), error.key)
    }

    // Reading

    @Test
    fun anAppleExportDecodes() {
        assertEquals(FlagPayload(mapOf(boolFlag to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt), decode(SINGLE_EXPORT))
    }

    @Test
    fun handEditedMistakesAreReportedExactlyAsAppleReportsThem() {
        // Review focus 2. Every problem is found, sorted by key, and worded as iOS words it.
        val error = rejection(
            """{"formatVersion":1,"values":{"bool-flag":1,"int-flag":true,"double-flag":null,"string-flag":1.5,"markets":"NZ","limits":[1,2],"level":99,"tier":"platinum","nope":1e300}}""",
        )

        assertEquals(NINE_PROBLEMS, error.message)
        assertEquals(
            FlagImportProblem(FlagKey("nope"), FlagImportProblem.Kind.UnknownKey, found = "1e+300"),
            error.problems.single { it.key == FlagKey("nope") },
        )
    }

    @Test
    fun aByteOrderMarkIsSkipped() {
        // Review focus 5. JSONSerialization skips a UTF-8 BOM, which some editors write; M1a's JsonReader does too.
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SINGLE_EXPORT.encodeToByteArray()

        assertEquals(decode(SINGLE_EXPORT), FlagPayload.decode(marked, goldenSchema))
    }

    @Test
    fun theFirstOfTwoDuplicateKeysWins() {
        val payload = decode("""{"formatVersion":1,"values":{"bool-flag":true,"bool-flag":false}}""")

        assertEquals(mapOf(boolFlag to FlagValueBox.Bool(true)), payload.values)
    }

    @Test
    fun aBooleanFormatVersionReadsAsANumberLikeApple() {
        // Swift reads the field with `as? Int`, and an NSNumber `true` answers 1.
        assertEquals(1, decode("""{"formatVersion":true,"values":{}}""").formatVersion)
    }

    @Test
    fun aNegativeOverflowIsMinusInfinityLikeApple() {
        // Measured: JSONSerialization reads -1e400 as -inf. A double flag takes it, and a message names it "-inf".
        assertEquals(
            FlagValueBox.Double(Double.NEGATIVE_INFINITY),
            decode("""{"formatVersion":1,"values":{"double-flag":-1e400}}""").values[FlagKey("double-flag")],
        )
        val error = rejection("""{"formatVersion":1,"values":{"bool-flag":-1e400}}""")

        assertEquals("bool-flag: expected bool, got -inf", error.problems.single().toString())
    }

    @Test
    fun aFalseFormatVersionIsVersionZero() {
        val error = assertFailsWith<FlagImportException.UnsupportedFormatVersion> {
            decode("""{"formatVersion":false,"values":{}}""")
        }

        assertEquals(0L, error.version)
    }

    @Test
    fun aHugeFormatVersionIsNamedInFull() {
        val error = assertFailsWith<FlagImportException.UnsupportedFormatVersion> {
            decode("""{"formatVersion":5000000000,"values":{}}""")
        }

        assertEquals(
            "The document is format version 5000000000; this build understands 1. Update whichever app is older.",
            error.message,
        )
    }

    @Test
    fun aFormatVersionThatIsNotAWholeNumberIsMissing() {
        for (version in listOf("1.5", "\"1\"", "null")) {
            val error = assertFailsWith<FlagImportException.Malformed>(version) {
                decode("""{"formatVersion":$version,"values":{}}""")
            }
            assertEquals("The document could not be read: missing formatVersion.", error.message)
        }
    }

    @Test
    fun aMissingOrUnreadableExportDateIsTheEpoch() {
        assertEquals(Instant.EPOCH, decode("""{"formatVersion":1,"values":{}}""").exportedAt)
        assertEquals(Instant.EPOCH, decode("""{"formatVersion":1,"exportedAt":"garbage","values":{}}""").exportedAt)
        assertEquals(Instant.EPOCH, decode("""{"formatVersion":1,"exportedAt":"2026-01-01T09:41:00.5Z","values":{}}""").exportedAt)
    }

    @Test
    fun aDocumentThatIsNotAnObjectIsMalformed() {
        for (text in listOf("[]", "not json", "")) {
            val error = assertFailsWith<FlagImportException.Malformed>(text) { decode(text) }
            assertEquals("not a JSON object", error.reason)
        }
    }

    @Test
    fun aDocumentWithoutValuesIsMalformed() {
        val error = assertFailsWith<FlagImportException.Malformed> { decode("""{"formatVersion":1,"values":[]}""") }

        assertEquals("missing values", error.reason)
    }

    @Test
    fun aDuplicateRecordKeySaysWhichKey() {
        val error = rejection(
            """{"formatVersion":1,"values":{"endpoints":"[{\"name\":\"a\",\"url\":\"https://one.example\"},{\"name\":\"a\",\"url\":\"https://two.example\"}]"}}""",
        )

        assertEquals(
            "endpoints: expected every record to have its own name, got two with \"a\"",
            error.problems.single().toString(),
        )
    }

    @Test
    fun textThatIsNotARecordListSaysWhichFieldsWereExpected() {
        val error = rejection("""{"formatVersion":1,"values":{"endpoints":"nonsense"}}""")

        assertEquals(
            "endpoints: expected a list of records (name, url, weight, note, kind, targets), got \"nonsense\"",
            error.problems.single().toString(),
        )
    }

    @Test
    fun aRecordMissingARequiredFieldIsRejected() {
        val error = rejection("""{"formatVersion":1,"values":{"endpoints":"[{\"name\":\"half\"}]"}}""")

        assertEquals(FlagImportProblem.Kind.TypeMismatch, error.problems.single().kind)
        assertEquals("\"[{\"name\":\"half\"}]\"", error.problems.single().found)
    }

    @Test
    fun aRecordListSentAsAnArrayIsATypeMismatch() {
        val error = rejection("""{"formatVersion":1,"values":{"endpoints":[]}}""")

        assertEquals("endpoints: expected string, got an array of 0", error.problems.single().toString())
    }

    @Test
    fun aRecordFieldHoldingAnUnknownEnumCaseIsAcceptedLikeApple() {
        // FeatureFlag 0.12.0 checks a record list's shape, not its enum cases. The flag then
        // reads its default. Measured; mirrors the spec's documented record-list quirk.
        val payload = decode(
            """{"formatVersion":1,"values":{"endpoints":"[{\"name\":\"a\",\"url\":\"https://one.example\",\"kind\":\"platinum\"}]"}}""",
        )

        assertEquals(setOf(endpoints), payload.values.keys)
    }

    @Test
    fun aValidRecordListIsAccepted() {
        val records = GoldenOverrides.values.getValue(endpoints)
        val payload = FlagPayload(mapOf(endpoints to records), GoldenOverrides.exportedAt)

        assertEquals(payload, FlagPayload.decode(payload.jsonBytes(), goldenSchema))
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransportTest.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.pole.ReadOnlySource
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPoleTransportTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val ratio = FlagKey("ratio")
    private val enabled = FlagKey("enabled")

    private fun goldenPole(): FlagPole<GoldenFlags> = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

    private fun transportPole(): FlagPole<TransportFlags> = FlagPole(TransportFlagsSpec, listOf(SnapshotSource("local")))

    private fun document(json: String): ByteArray = json.encodeToByteArray()

    private fun exportedValues(pole: FlagPole<*>): Map<String, JsonValue> {
        val exported = JsonReader.parse(pole.exportJson()) as JsonValue.Object
        return (exported.fields["values"] as JsonValue.Object).fields
    }

    // Export

    @Test
    fun exportCarriesOnlyOverriddenFlags() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertEquals(mapOf(boolFlag to FlagValueBox.Bool(true)), pole.exportPayload().values)
    }

    @Test
    fun exportOfAnUntouchedPoleIsEmpty() {
        assertTrue(goldenPole().exportPayload().values.isEmpty())
    }

    @Test
    fun exportedJsonIsFlatAndHandEditable() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        pole.setOverride(GoldenFlagsSpec.intFlag, 3)

        val exported = JsonReader.parse(pole.exportJson()) as JsonValue.Object
        val values = (exported.fields["values"] as JsonValue.Object).fields

        assertEquals(JsonValue.Bool(true), values["bool-flag"])
        assertEquals(JsonValue.Integer(3), values["int-flag"])
        assertEquals(JsonValue.Integer(1), exported.fields["formatVersion"])
    }

    @Test
    fun datesDataAndUrlsExportAsPlainStrings() {
        // No "$type" wrappers: the schema already says what each key is.
        val pole = transportPole()
        pole.setOverride(TransportFlagsSpec.launchedAt, Instant.EPOCH)
        pole.setOverride(TransportFlagsSpec.endpoint, URI("https://example.com"))
        pole.setOverride(TransportFlagsSpec.blob, byteArrayOf(0x01, 0x02))

        val values = exportedValues(pole)

        assertEquals(JsonValue.Text("1970-01-01T00:00:00Z"), values["launched-at"])
        assertEquals(JsonValue.Text("https://example.com"), values["endpoint"])
        assertEquals(JsonValue.Text("AQI="), values["blob"])
    }

    @Test
    fun exportedJsonDoesNotEscapeSlashes() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.urlFlag, URI("https://staging.example.com/v3"))

        val json = pole.exportJson().decodeToString()

        assertTrue(json.contains("https://staging.example.com/v3"), json)
        assertFalse(json.contains("\\/"), "slashes should not be escaped")
    }

    // Round trips

    @Test
    fun jsonRoundTripsEveryOverride() {
        val source = transportPole()
        source.setOverride(TransportFlagsSpec.launchedAt, Instant.ofEpochSecond(1_000))
        source.setOverride(TransportFlagsSpec.endpoint, URI("https://example.com/a?b=c"))
        source.setOverride(TransportFlagsSpec.blob, byteArrayOf(0x01, 0xFF.toByte()))
        source.setOverride(TransportFlagsSpec.tags, listOf("x", "y"))
        source.setOverride(TransportFlagsSpec.limits, mapOf("a" to 1))
        source.setOverride(TransportFlagsSpec.ratio, 2.5f)

        val destination = transportPole()
        destination.importJson(source.exportJson())

        assertEquals(source.overrides, destination.overrides)
    }

    @Test
    fun aFloatThatDecimalCannotHoldExactlyRoundTrips() {
        val source = transportPole()
        source.setOverride(TransportFlagsSpec.ratio, 0.1f)

        assertEquals(JsonValue.Real(0.10000000149011612), exportedValues(source)["ratio"])

        val destination = transportPole()
        destination.importJson(source.exportJson())

        assertEquals(0.1f, destination.flags.ratio)
    }

    @Test
    fun enumOverridesRoundTrip() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.checkout.tier, GoldenTier.ENTERPRISE)

        val destination = goldenPole()
        destination.importJson(source.exportJson())

        assertEquals(GoldenTier.ENTERPRISE, destination.flags.checkout.tier)
    }

    @Test
    fun aRecordListRoundTripsIntoTheTypedFlag() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.endpoints, GoldenOverrides.endpoints)

        val destination = goldenPole()
        destination.importJson(source.exportJson())

        assertEquals(GoldenOverrides.endpoints, destination.flags.endpoints)
    }

    @Test
    fun escapingChangeDoesNotBreakTheRoundTrip() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.urlFlag, URI("https://a.example/x/y?z=1/2"))

        val destination = goldenPole()
        destination.importJson(source.exportJson())

        assertEquals(source.overrides, destination.overrides)
    }

    @Test
    fun exportingAndImportingAnEmptySetRoundTrips() {
        val pole = goldenPole()

        pole.importJson(pole.exportJson())

        assertTrue(pole.overrides.isEmpty())
    }

    // Import is strict and all or nothing

    @Test
    fun importReportsWhatItApplied() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.boolFlag, true)
        source.setOverride(GoldenFlagsSpec.intFlag, 3)

        val applied = goldenPole().importJson(source.exportJson())

        assertEquals(listOf(boolFlag, intFlag), applied.values.keys.sortedBy { it.rawValue })
    }

    @Test
    fun importRejectsAnUnknownKeyAndAppliesNothing() {
        val pole = goldenPole()

        val error = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson(document("""{"formatVersion": 1, "values": {"bool-flag": true, "not-a-flag": 1}}"""))
        }

        assertEquals(listOf(FlagImportProblem(FlagKey("not-a-flag"), FlagImportProblem.Kind.UnknownKey, found = "1")), error.problems)
        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun importRejectsAMistypedValueAndAppliesNothing() {
        val pole = goldenPole()

        val error = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson(document("""{"formatVersion": 1, "values": {"bool-flag": true, "int-flag": "lots"}}"""))
        }

        assertEquals(listOf(FlagImportProblem(intFlag, FlagImportProblem.Kind.TypeMismatch, "int", "\"lots\"")), error.problems)
        assertFalse(pole.flags.boolFlag)
    }

    @Test
    fun importReportsEveryProblemAtOnce() {
        val error = assertFailsWith<FlagImportException.Rejected> {
            goldenPole().importJson(document("""{"formatVersion": 1, "values": {"not-a-flag": 1, "int-flag": "lots"}}"""))
        }

        assertEquals(
            listOf(
                FlagImportProblem(intFlag, FlagImportProblem.Kind.TypeMismatch, "int", "\"lots\""),
                FlagImportProblem(FlagKey("not-a-flag"), FlagImportProblem.Kind.UnknownKey, found = "1"),
            ),
            error.problems,
        )
    }

    @Test
    fun importRejectsAnUnknownFormatVersion() {
        val error = assertFailsWith<FlagImportException.UnsupportedFormatVersion> {
            goldenPole().importJson(document("""{"formatVersion": 99, "values": {}}"""))
        }

        assertEquals(99L, error.version)
    }

    @Test
    fun importRejectsMalformedData() {
        assertFailsWith<FlagImportException.Malformed> { goldenPole().importJson(document("not json")) }
    }

    @Test
    fun aPayloadMissingItsValuesIsRejected() {
        val error = assertFailsWith<FlagImportException.Malformed> {
            goldenPole().importJson(document("""{"formatVersion": 1}"""))
        }

        assertEquals("missing values", error.reason)
    }

    @Test
    fun aPayloadMissingItsVersionIsRejected() {
        val error = assertFailsWith<FlagImportException.Malformed> {
            goldenPole().importJson(document("""{"values": {}}"""))
        }

        assertEquals("missing formatVersion", error.reason)
    }

    @Test
    fun anEmptyPayloadIsValidAndAppliesNothing() {
        val pole = goldenPole()

        val applied = pole.importJson(document("""{"formatVersion": 1, "values": {}}"""))

        assertTrue(applied.values.isEmpty())
        assertTrue(pole.overrides.isEmpty())
    }

    @Test
    fun importingLeavesUnmentionedOverridesAlone() {
        // Import applies what it carries. It is not a wholesale replacement.
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        pole.importJson(document("""{"formatVersion": 1, "values": {"int-flag": 3}}"""))

        assertTrue(pole.flags.boolFlag)
        assertEquals(3L, pole.flags.intFlag.toLong())
    }

    @Test
    fun importedValuesLandInTheTopMutableSourceAndOutrankTheRest() {
        val local = SnapshotSource("local")
        val remote = ReadOnlySource("remote", mapOf(boolFlag to FlagValueBox.Bool(false)))
        val pole = FlagPole(GoldenFlagsSpec, listOf(local, remote))

        pole.importJson(document("""{"formatVersion": 1, "values": {"bool-flag": true}}"""))

        assertEquals(FlagValueBox.Bool(true), local.values[boolFlag])
        assertTrue(pole.flags.boolFlag)
    }

    @Test
    fun importingAnEnumCaseThisBuildLacksIsRejected() {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        val error = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson(document("""{"formatVersion": 1, "values": {"tier": "platinum"}}"""))
        }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownCase), error.problems.map { it.kind })
        assertEquals(listOf(FlagKey("tier")), error.problems.map { it.key })
        assertNull(local.values[FlagKey("tier")], "nothing is stored")
        assertEquals(GoldenTier.FREE, pole.flags.tier)
    }

    @Test
    fun importingAKnownEnumCaseStillWorks() {
        val pole = goldenPole()

        pole.importJson(document("""{"formatVersion": 1, "values": {"tier": "pro"}}"""))

        assertEquals(GoldenTier.PRO, pole.flags.tier)
    }

    // Numbers JSON cannot write

    @Test
    fun exportingAnInfiniteDoubleThrowsRatherThanCrashing() {
        val local = SnapshotSource("local", mapOf(ratio to FlagValueBox.Double(Double.POSITIVE_INFINITY)))
        val pole = FlagPole(EdgeFlagsSpec, listOf(local))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { pole.exportJson() }

        assertEquals(ratio, error.key)
    }

    @Test
    fun exportingANaNThrows() {
        val local = SnapshotSource("local", mapOf(ratio to FlagValueBox.Double(Double.NaN)))
        val pole = FlagPole(EdgeFlagsSpec, listOf(local))

        assertFailsWith<FlagSerializationException.NonFiniteNumber> { pole.exportJson() }
    }

    @Test
    fun aNonFiniteNumberNestedInACollectionIsCaught() {
        val ratios = FlagKey("ratios")
        val nested = FlagValueBox.Array(listOf(FlagValueBox.Double(1.0), FlagValueBox.Double(Double.POSITIVE_INFINITY)))
        val pole = FlagPole(EdgeFlagsSpec, listOf(SnapshotSource("local", mapOf(ratios to nested))))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { pole.exportJson() }

        assertEquals(ratios, error.key)
    }

    @Test
    fun finiteNumbersAreUnaffected() {
        val pole = FlagPole(EdgeFlagsSpec, listOf(SnapshotSource("local", mapOf(ratio to FlagValueBox.Double(1.5)))))

        assertTrue(pole.exportJson().decodeToString().contains("\"ratio\" : 1.5"))
    }

    // Import used to apply part of a payload

    @Test
    fun aWriteFailingPartwayUndoesTheRest() {
        val source = RefusingSource(refusedKey = enabled)
        val pole = FlagPole(EdgeFlagsSpec, listOf(source))

        assertFailsWith<FlagException.UnsupportedValue> {
            pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":true}}"""))
        }

        assertTrue(source.storedKeys.isEmpty(), "left behind: ${source.storedKeys}")
        assertEquals(0.0, pole.flags.ratio)
        assertFalse(pole.flags.enabled)
    }

    @Test
    fun rollbackRestoresAPreviousOverrideRatherThanClearingIt() {
        val source = RefusingSource(refusedKey = enabled)
        source.setBox(ratio, FlagValueBox.Double(9.5))
        val pole = FlagPole(EdgeFlagsSpec, listOf(source))

        assertFailsWith<FlagException.UnsupportedValue> {
            pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":false}}"""))
        }

        assertEquals(9.5, pole.flags.ratio, "the earlier override should be restored, not cleared")
    }

    @Test
    fun rollbackIsBestEffortWhenTheSourceRefusesEverything() {
        // A store that has run out of room refuses the undo as well.
        val source = RefusingSource(refusedKey = enabled, refusesEverythingAfterwards = true)
        val pole = FlagPole(EdgeFlagsSpec, listOf(source))

        assertFailsWith<FlagException.UnsupportedValue> {
            pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":true}}"""))
        }

        assertTrue(pole.flags.ratio == 1.5 || pole.flags.ratio == 0.0)
    }

    @Test
    fun aSuccessfulImportStillAppliesEverything() {
        val pole = FlagPole(EdgeFlagsSpec, listOf(SnapshotSource("local")))

        val applied = pole.importJson(document("""{"formatVersion":1,"values":{"ratio":1.5,"enabled":true}}"""))

        assertEquals(listOf(enabled, ratio), applied.values.keys.sortedBy { it.rawValue })
        assertEquals(1.5, pole.flags.ratio)
        assertTrue(pole.flags.enabled)
    }
}

@FlagContainer
internal interface TransportFlags {

    @Flag(description = "Launched at")
    val launchedAt: Instant get() = Instant.EPOCH

    @Flag(description = "Endpoint")
    val endpoint: URI get() = URI("https://default.example")

    @Flag(description = "Blob")
    val blob: ByteArray get() = ByteArray(0)

    @Flag(description = "Tags")
    val tags: List<String> get() = emptyList()

    @Flag(description = "Limits")
    val limits: Map<String, Int> get() = emptyMap()

    @Flag(description = "Ratio")
    val ratio: Float get() = 1.0f
}

@FlagContainer
internal interface EdgeFlags {

    @Flag(description = "Ratio", remoteKey = "cfg.ratio")
    val ratio: Double get() = 0.0

    @Flag(description = "Enabled")
    val enabled: Boolean get() = false

    @Flag(description = "Ratios")
    val ratios: List<Double> get() = emptyList()
}

/** Refuses one key, standing in for a store that cannot represent a value. Optionally refuses everything after that. */
private class RefusingSource(
    private val refusedKey: FlagKey,
    private val refusesEverythingAfterwards: Boolean = false,
) : MutableFlagValueSource {
    override val sourceName: String = "refusing"
    override val changes: Flow<FlagChange> = emptyFlow()
    private val values = LinkedHashMap<FlagKey, FlagValueBox>()
    private var hasRefused = false

    val storedKeys: List<FlagKey>
        get() = values.keys.sortedBy { it.rawValue }

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        if (refusesEverythingAfterwards && hasRefused) throw FlagException.UnsupportedValue(key)
        if (key == refusedKey && box != null) {
            hasRefused = true
            throw FlagException.UnsupportedValue(key)
        }
        if (box == null) {
            values.remove(key)
        } else {
            values[key] = box
        }
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/ExportGoldenTest.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenLevel
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals

/** `fixtures/export/golden.json` is FeatureFlag 0.12.0's export of [GoldenOverrides]. */
class ExportGoldenTest {

    private val path = "export/golden.json"

    @Test
    fun theAppleExportDecodesAndReEncodesByteForByte() {
        val bytes = Fixtures.bytes(path)

        assertEquals(bytes.decodeToString(), FlagPayload.decode(bytes, goldenSchema).jsonBytes().decodeToString())
    }

    @Test
    fun theAppleExportCarriesTheDocumentedOverrideSet() {
        val decoded = FlagPayload.decode(Fixtures.bytes(path), goldenSchema)

        assertEquals(GoldenOverrides.exportedAt, decoded.exportedAt)
        assertEquals(GoldenOverrides.decodedValues, decoded.values)
    }

    @Test
    fun kotlinExportsTheOverrideSetByteForByte() {
        // Includes a date with a fractional second, which the export floors as iOS does.
        val payload = FlagPayload(GoldenOverrides.values, GoldenOverrides.exportedAt)

        assertEquals(Fixtures.text(path), payload.jsonBytes().decodeToString())
    }

    @Test
    fun aPoleExportsTheSameDocument() {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))
        GoldenOverrides.values.forEach { (key, box) -> pole.setOverride(key, box) }

        val exported = pole.exportPayload().copy(exportedAt = GoldenOverrides.exportedAt)

        assertEquals(Fixtures.text(path), exported.jsonBytes().decodeToString())
    }

    @Test
    fun typedOverridesBoxTheSameWayAsTheGoldenSet() {
        // int-flag and limits are left out: whether they are Int or Long in Kotlin is M1b's choice.
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        pole.setOverride(GoldenFlagsSpec.doubleFlag, 0.30000000000000004)
        pole.setOverride(GoldenFlagsSpec.floatFlag, 1.1f)
        pole.setOverride(GoldenFlagsSpec.stringFlag, "line1\nline2\t\"q\" / \u00e9 \ud83d\ude00")
        pole.setOverride(GoldenFlagsSpec.dataFlag, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        pole.setOverride(GoldenFlagsSpec.dateFlag, Instant.ofEpochMilli(1_767_260_460_750))
        pole.setOverride(GoldenFlagsSpec.urlFlag, URI("https://example.com/path?q=1&r=a%20b#frag"))
        pole.setOverride(GoldenFlagsSpec.useHTTPSOnly, true)
        pole.setOverride(GoldenFlagsSpec.markets, listOf("AU", "NZ", "US"))
        pole.setOverride(GoldenFlagsSpec.matrix, listOf(emptyList(), listOf(true)))
        pole.setOverride(GoldenFlagsSpec.tier, GoldenTier.ENTERPRISE)
        pole.setOverride(GoldenFlagsSpec.level, GoldenLevel.THREE)
        pole.setOverride(GoldenFlagsSpec.endpoints, GoldenOverrides.endpoints)
        pole.setOverride(GoldenFlagsSpec.checkout.applePay, false)
        pole.setOverride(GoldenFlagsSpec.checkout.tier, GoldenTier.FREE)
        pole.setOverride(GoldenFlagsSpec.checkout.express.oneTap, true)
        pole.setOverride(GoldenFlagsSpec.checkout.express.confirmationDelay, 0.5)

        val expected: Map<FlagKey, FlagValueBox> = GoldenOverrides.values - FlagKey("int-flag") - FlagKey("limits")
        assertEquals(expected, pole.overrides)
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.*' --tests 'com.andyyhope.featureflag.errors.FlagImportExceptionTest'
```

Expected: `BUILD FAILED`, with `compileTestKotlin` reporting `Unresolved reference 'FlagPayload'`, `'FlagImportException'`, `'FlagImportProblem'`, `'exportJson'` and `'importJson'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagImportException.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey

/**
 * Something wrong with one key in an incoming document.
 *
 * @property expected what the flag would have accepted: a type name, or an enum's cases.
 *   It is there for the message; switch on [kind].
 * @property found what the document held, short enough to read on one line.
 */
public data class FlagImportProblem(
    public val key: FlagKey,
    public val kind: Kind,
    public val expected: String? = null,
    public val found: String? = null,
) {

    public enum class Kind {
        /** No flag in this app has that key. */
        UnknownKey,

        /** The value is not of the flag's declared type, or is text that is not a readable record list. */
        TypeMismatch,

        /** The value is not one of the enum's cases. */
        UnknownCase,
    }

    override fun toString(): String = when (kind) {
        Kind.TypeMismatch -> "$key: expected ${expected ?: "another type"}" + (found?.let { ", got $it" } ?: "")
        Kind.UnknownCase -> "$key: ${found ?: "that value"} is not one of " + (expected ?: "this flag's cases")
        Kind.UnknownKey ->
            "$key: no flag has this key. The document came from a different app, " +
                "or from a build where the flag was named differently"
    }
}

/** Why a document or a scanned code was not applied. Nothing is applied when this is thrown. */
public sealed class FlagImportException(message: String) : RuntimeException(message) {

    public class Malformed(public val reason: String) : FlagImportException("The document could not be read: $reason.")

    public class UnsupportedFormatVersion(public val version: Long) : FlagImportException(
        "The document is format version $version; this build understands " +
            "${FlagPayload.CURRENT_FORMAT_VERSION}. Update whichever app is older.",
    )

    /** Every problem found, sorted by key. Import is all or nothing, so an app never runs on half a configuration. */
    public class Rejected(
        public val problems: List<FlagImportProblem>,
    ) : FlagImportException(rejectionMessage(problems))
}

private fun rejectionMessage(problems: List<FlagImportProblem>): String {
    if (problems.isEmpty()) return "Nothing was applied, and no problem was recorded."
    val count = "${problems.size} problem${if (problems.size == 1) "" else "s"}"
    val list = problems.joinToString("\n") { "  • $it" }
    return "Nothing was applied. $count:\n$list\nImport is all or nothing, so no flag was changed."
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.RecordJson
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.toBox
import com.andyyhope.featureflag.values.toJson
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs

/**
 * A set of flag overrides, ready to be written to a file, shared, or carried in a QR code.
 *
 * Only overridden flags travel. Exporting every flag would mostly be a copy of the
 * defaults already compiled into the app, and would not fit in a QR code.
 */
public data class FlagPayload(
    public val values: Map<FlagKey, FlagValueBox>,
    public val exportedAt: Instant = Instant.now(),
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {

    /**
     * The document as JSON, byte-identical to an iOS export: two-space indent, sorted
     * keys, unescaped slashes, and dates to the second.
     *
     * @throws FlagSerializationException.NonFiniteNumber when a value holds an infinity or a NaN.
     */
    public fun jsonBytes(): ByteArray {
        val nonFinite = values.entries.firstOrNull { it.value.containsNonFiniteNumber }
        if (nonFinite != null) throw FlagSerializationException.NonFiniteNumber(nonFinite.key)

        val document = JsonValue.Object(
            mapOf(
                "exportedAt" to JsonValue.Text(IsoDate.format(exportedAt)),
                "formatVersion" to JsonValue.Integer(formatVersion.toLong()),
                "values" to JsonValue.Object(values.entries.associate { (key, box) -> key.rawValue to box.toJson() }),
            ),
        )
        return JsonWriter.pretty(document).encodeToByteArray()
    }

    public companion object {

        /** Bumped when the document's shape changes incompatibly. */
        public const val CURRENT_FORMAT_VERSION: Int = 1

        /**
         * Reads a document, checking every value against [schema]: unknown keys, values of
         * the wrong type, enum cases this build lacks, and record lists it cannot read.
         *
         * All or nothing: when anything is wrong, every problem is reported at once in
         * [FlagImportException.Rejected], sorted by key.
         */
        public fun decode(bytes: ByteArray, schema: FlagSchema): FlagPayload {
            val document = parseDocument(bytes)
            val version = formatVersion(document)
            val rawValues = document.fields["values"] as? JsonValue.Object
                ?: throw FlagImportException.Malformed("missing values")

            val rules = ImportRules(schema)
            val values = LinkedHashMap<FlagKey, FlagValueBox>()
            val problems = mutableListOf<FlagImportProblem>()
            for ((rawKey, raw) in rawValues.fields) {
                val key = FlagKey(rawKey)
                val box = rules.types[key]?.let { raw.toBox(it) }
                val problem = rules.problemWith(key, raw, box)
                if (problem != null) {
                    problems.add(problem)
                } else if (box != null) {
                    values[key] = box
                }
            }
            if (problems.isNotEmpty()) throw FlagImportException.Rejected(problems.sortedBy { it.key.rawValue })

            val exportedAt = (document.fields["exportedAt"] as? JsonValue.Text)?.let { IsoDate.parse(it.value) }
            return FlagPayload(values, exportedAt ?: Instant.EPOCH, version.toInt())
        }

        private fun parseDocument(bytes: ByteArray): JsonValue.Object {
            // JsonReader accepts what JSONSerialization accepts, a byte order mark and a trailing comma included.
            val parsed = try {
                JsonReader.parse(bytes)
            } catch (ignored: JsonSyntaxException) {
                null
            }
            return parsed as? JsonValue.Object ?: throw FlagImportException.Malformed("not a JSON object")
        }

        private fun formatVersion(document: JsonValue.Object): Long {
            val version = when (val raw = document.fields["formatVersion"]) {
                is JsonValue.Integer -> raw.value
                // Swift reads the field with `as? Int`, which turns an NSNumber `true` into 1.
                is JsonValue.Bool -> if (raw.value) 1L else 0L
                else -> throw FlagImportException.Malformed("missing formatVersion")
            }
            if (version != CURRENT_FORMAT_VERSION.toLong()) throw FlagImportException.UnsupportedFormatVersion(version)
            return version
        }
    }
}

/**
 * What a document is checked against, read from a schema. When a hand-made schema names
 * a key twice, the first entry wins, as in Swift's `valueTypes`, `valueCases` and
 * `recordShapes`.
 */
internal class ImportRules(schema: FlagSchema) {

    val types: Map<FlagKey, FlagValueType> = buildMap {
        for (entry in schema.flags) {
            if (entry.key !in this) put(entry.key, entry.valueType)
        }
    }

    private val cases: Map<FlagKey, List<FlagValueBox>> = buildMap {
        for (entry in schema.flags) {
            val entryCases = entry.cases
            if (!entryCases.isNullOrEmpty() && entry.key !in this) put(entry.key, entryCases)
        }
    }

    private val shapes: Map<FlagKey, List<FlagRecordField>> = buildMap {
        for (entry in schema.flags) {
            val shape = entry.recordShape
            if (!shape.isNullOrEmpty() && entry.key !in this) put(entry.key, shape)
        }
    }

    /** The one problem with [raw] for [key], or null when [box] (raw read as the declared type) can be applied. */
    fun problemWith(key: FlagKey, raw: JsonValue, box: FlagValueBox?): FlagImportProblem? {
        val type = types[key]
            ?: return FlagImportProblem(key, FlagImportProblem.Kind.UnknownKey, found = raw.importDescription())
        if (box == null) {
            return FlagImportProblem(key, FlagImportProblem.Kind.TypeMismatch, type.typeName, raw.importDescription())
        }
        val permitted = cases[key]
        if (permitted != null && box !in permitted) {
            return FlagImportProblem(key, FlagImportProblem.Kind.UnknownCase, permitted.caseListDescription(), raw.importDescription())
        }
        val shape = shapes[key] ?: return null
        return recordProblem(key, raw, box, shape)
    }
}

private const val NUMBER_DIGITS = 16

/**
 * A record list is a string, which any text satisfies, so being the right type is not
 * enough. The text must read as records of [shape], each with its own key.
 */
private fun recordProblem(
    key: FlagKey,
    raw: JsonValue,
    box: FlagValueBox,
    shape: List<FlagRecordField>,
): FlagImportProblem? {
    val text = (box as? FlagValueBox.String)?.value
    if (text != null && RecordJson.decode(text, shape) != null) return null
    val duplicate = text?.let { RecordJson.duplicateKey(it, shape) }
    val expected = if (duplicate == null) {
        "a list of records (${shape.joinToString(", ") { it.name }})"
    } else {
        "every record to have its own ${shape.firstOrNull { it.isKey }?.name ?: "key"}"
    }
    val found = if (duplicate == null) raw.importDescription() else "two with ${duplicate.shortDescription()}"
    return FlagImportProblem(key, FlagImportProblem.Kind.TypeMismatch, expected, found)
}

/** A parsed value in a few characters, as Swift's `flagShortDescription(of:)` writes it. */
private fun JsonValue.importDescription(): String = when (this) {
    is JsonValue.Text -> MessageText.quoted(value)
    is JsonValue.Bool -> value.toString()
    is JsonValue.Integer -> value.toString()
    is JsonValue.Real -> numberDescription(value)
    is JsonValue.Array -> MessageText.arrayOf(items.size)
    is JsonValue.Object -> MessageText.objectWith(fields.size)
    JsonValue.Null -> "<null>"
}

/**
 * Swift's `FlagValueBox.shortMessageDescription`. Doubles and floats use Kotlin's
 * `toString()`, which matches Swift's `String(_:)` for ordinary values. M1d Task D1
 * replaces this with `MessageText.short`, the exact port.
 */
private fun FlagValueBox.shortDescription(): String = when (this) {
    is FlagValueBox.String -> MessageText.quoted(value)
    is FlagValueBox.Bool -> value.toString()
    is FlagValueBox.Int -> value.toString()
    is FlagValueBox.Double -> value.toString()
    is FlagValueBox.Float -> value.toString()
    is FlagValueBox.Url -> MessageText.quoted(value.toString())
    is FlagValueBox.Date -> MessageText.date(value)
    is FlagValueBox.Data -> "${value.size} bytes"
    is FlagValueBox.Array -> MessageText.arrayOf(value.size)
    is FlagValueBox.Dictionary -> MessageText.objectWith(value.size)
}

/** Swift's `caseListDescription`: a string case bare, and an int case as `String(describing:)` writes it. */
private fun List<FlagValueBox>.caseListDescription(): String = joinToString(", ") { box ->
    when (box) {
        is FlagValueBox.String -> box.value
        is FlagValueBox.Int -> "int(${box.value})"
        else -> box.shortDescription()
    }
}

/**
 * NSNumber's description of a double, which is C's `%.16g` with trailing zeros removed.
 * This was checked against 7,019 values described by Foundation on this machine. JsonReader
 * reads `-1e400` as negative infinity, as JSONSerialization does, and NSNumber calls it `-inf`.
 */
private fun numberDescription(value: Double): String {
    if (value.isInfinite()) return if (value > 0.0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0.0) "-0" else "0"
    val rounded = BigDecimal(value).round(MathContext(NUMBER_DIGITS, RoundingMode.HALF_EVEN))
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent < -4 || exponent >= NUMBER_DIGITS) {
        val digits = rounded.unscaledValue().abs().toString().trimEnd('0')
        val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
        val sign = if (rounded.signum() < 0) "-" else ""
        val exponentSign = if (exponent < 0) "-" else "+"
        return "$sign${mantissa}e$exponentSign${abs(exponent).toString().padStart(2, '0')}"
    }
    return rounded.stripTrailingZeros().toPlainString()
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/**
 * Every flag some source supplies a value for, ready to serialise.
 *
 * This spans the whole stack, not just local overrides. A value a remote payload supplied
 * is exported too, and becomes a local override wherever the document is imported.
 */
public fun FlagPole<*>.exportPayload(): FlagPayload = FlagPayload(values = overrides)

/** The current overrides as JSON, byte-identical to an iOS export. */
public fun FlagPole<*>.exportJson(): ByteArray = exportPayload().jsonBytes()

/**
 * Applies a JSON document as overrides. Strict and all or nothing: any unknown key,
 * mistyped value, unknown enum case or unreadable record list rejects the whole document,
 * with every problem listed.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importJson(bytes: ByteArray): FlagPayload {
    val payload = FlagPayload.decode(bytes, schema)
    apply(payload)
    return payload
}

/**
 * Applies an already-decoded payload as overrides in the top mutable source.
 *
 * If a write throws partway, the writes already made are undone before the error
 * propagates. The undo is best effort: a source that has stopped accepting writes will
 * refuse the undo as well.
 */
public fun FlagPole<*>.apply(payload: FlagPayload) {
    val types = ImportRules(schema).types
    val undo = ArrayList<Pair<FlagKey, FlagValueBox?>>()
    var completed = false
    try {
        for ((key, box) in payload.values) {
            val previous = types[key]?.let { currentOverride(key, it) }
            setOverride(key, box)
            undo.add(key to previous)
        }
        completed = true
    } finally {
        if (!completed) {
            for ((key, previous) in undo.asReversed()) {
                runCatching { setOverride(key, previous) }
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.*' --tests 'com.andyyhope.featureflag.errors.FlagImportExceptionTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both commands. The first runs 66 tests:
- 8 in `FlagImportExceptionTest`;
- 21 in `FlagPayloadTest`;
- 32 in `FlagPoleTransportTest`;
- 5 in `ExportGoldenTest`.

Suppose only the `ExportGoldenTest` cases that compare against `GoldenOverrides` fail, while `theAppleExportDecodesAndReEncodesByteForByte` passes. Then `GoldenOverrides.kt` has drifted from `TransportFixtures.goldenPayload`. Make `GoldenOverrides.kt` match the generator value for value. Do not edit `fixtures/` by hand.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagImportException.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagImportExceptionTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport
git commit -m "Add strict all-or-nothing JSON export and import" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task C5: `FFQR1:` QR codes, the Apple goldens and the reverse fixtures

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagQRCodeException.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagQRCode.kt`
- Modify (full replacement): `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
- Modify (full replacement of M1a's stub): `fixture-generator/Sources/DecodeKotlinFixtures/main.swift`
- Modify: `build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt` (M1a Task A1's): forwards `-Pfeatureflag.writeAndroidFixtures=true` to tests as a system property, and makes `fixtures-android/` a test input
- Create (generated once by `QRCodeFixturesTest` with `-Pfeatureflag.writeAndroidFixtures=true`, then committed): `fixtures-android/golden.json`, `fixtures-android/golden.qr.txt`, `fixtures-android/empty.json`, `fixtures-android/empty.qr.txt`, `fixtures-android/single.json`, `fixtures-android/single.qr.txt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagQRCodeExceptionTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagQRCodeTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/QRCodeFixturesTest.kt`

**Interfaces:**
- Consumes: C4 `FlagPayload`, `exportPayload`, `apply`; M1a `StrictBase64.encode`/`decode`; `java.util.zip.Deflater`/`Inflater`; the test helpers `goldenSchema`, `fixturesDirectory()`, `incompressibleBytes`, `GoldenOverrides`, and `EdgeFlagsSpec` from `FlagPoleTransportTest.kt`.
- Produces:
```kotlin
public sealed class FlagQRCodeException(message: String) : RuntimeException(message) {
    public class PayloadTooLarge(public val bytes: Int, public val limit: Int, public val overrideCount: Int) : FlagQRCodeException
    public class UnrecognisedFormat : FlagQRCodeException
    public class Corrupt : FlagQRCodeException
}
public object FlagQRCode {
    public const val PREFIX: String = "FFQR1:"; public const val MAX_ENCODED_LENGTH: Int = 2953
    public fun encode(payload: FlagPayload): String
    public fun decode(text: String, schema: FlagSchema): FlagPayload
    internal fun decodeJson(text: String): ByteArray
    internal fun compress(json: ByteArray): ByteArray            // 4-byte big-endian length + raw DEFLATE, or the input when deflate fills max(n, 64)
    internal fun decompress(data: ByteArray): ByteArray?
    internal fun base64UrlEncode(bytes: ByteArray): String
    internal fun base64UrlDecode(text: String): ByteArray?
}
public fun FlagPole<*>.qrCodeString(): String
public fun FlagPole<*>.importQRCode(text: String): FlagPayload      // returns the applied payload
```
- Produces (Swift): the `decode-kotlin-fixtures` executable. It exits 0 only if every `fixtures-android/*.json` decodes against the GoldenFlags schema and re-encodes to the same bytes, and every `*.qr.txt` decodes to its sibling `.json`.

**The encoder's stored fallback.** Apple stores the input when deflate's output would be *at least* `max(n, 64)` bytes, because its `compression_encode_buffer` needs one byte to spare. The overview's "more than" is off by one. Two things pin the measured rule: M1a's `stored` golden, whose deflate output is exactly n = 241 bytes, and `aDeflatedBodyThatWouldFillApplesBufferExactlyIsStored`.

The decoder copies Apple's `compression_decode_buffer` behaviour. That behaviour was measured on this machine and matched by the JDK:
- **A short declared length:** a stream longer than the declared length fills the buffer and is accepted, cut short.
- **Trailing bytes:** bytes after a complete stream are ignored.
- **A long declared length:** a stream shorter than the declared length is rejected, unless the body itself is exactly that long, which is the stored fallback.

- [ ] **Step 1: Write the failing tests**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagQRCodeExceptionTest.kt`
```kotlin
package com.andyyhope.featureflag.errors

import org.junit.Test
import kotlin.test.assertEquals

class FlagQRCodeExceptionTest {

    @Test
    fun anOversizedCodeSaysHowFarOverItIs() {
        val error = FlagQRCodeException.PayloadTooLarge(bytes = 3400, limit = 2900, overrideCount = 12)

        assertEquals(12, error.overrideCount)
        assertEquals(
            "12 overrides need 3400 characters and a QR code holds 2900. Reset a few, or export JSON instead.",
            error.message,
        )
    }

    @Test
    fun oneOverrideReadsAsSwiftWritesIt() {
        assertEquals(
            "1 override need 3400 characters and a QR code holds 2900. Reset a few, or export JSON instead.",
            FlagQRCodeException.PayloadTooLarge(bytes = 3400, limit = 2900, overrideCount = 1).message,
        )
    }

    @Test
    fun anUnrecognisedCodeSaysWhatAFlagCodeLooksLike() {
        assertEquals("Not a flag code. A flag code starts with \"FFQR1:\".", FlagQRCodeException.UnrecognisedFormat().message)
    }

    @Test
    fun aCorruptCodeBlamesThePartialScanItUsuallyIs() {
        assertEquals(
            "The code could not be decompressed, which usually means a partial scan. Try again with the whole code in frame.",
            FlagQRCodeException.Corrupt().message,
        )
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagQRCodeTest.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.json.StrictBase64
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `fixtures/qr/single.qr.txt`: FeatureFlag 0.12.0's code for `["bool-flag": .bool(true)]`, exported at 1_767_260_460. */
private const val APPLE_SINGLE =
    "FFQR1:AAAAbavmUlBQSq0oyC8qSU1xLFFSsFJQMjIwMtM1MASiEANLKxNDKwODKCUdkMK0_KLcxJKw1KLizPw8kFpDsHBZYk5pajGIXw3kAgWS8vNzdNNyEtNBYiVFpalA4VquWgA"

/** FeatureFlag 0.12.0's code for no overrides, exported at 1_767_260_460. Measured. */
private const val APPLE_EMPTY =
    "FFQR1:AAAAV6vmUlBQSq0oyC8qSU1xLFFSsFJQMjIwMtM1MASiEANLKxNDKwODKCUdkMK0_KLcxJKw1KLizPw8kFpDsHBZYk5pajGIX80F5Ndy1QIA"

class FlagQRCodeTest {

    private val singlePayload =
        FlagPayload(mapOf(FlagKey("bool-flag") to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt)

    private fun goldenPole(): FlagPole<GoldenFlags> = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

    private fun code(bytes: ByteArray): String = FlagQRCode.PREFIX + FlagQRCode.base64UrlEncode(bytes)

    // Wire format

    @Test
    fun kotlinEncodesExactlyWhatAppleEncodes() {
        assertEquals(APPLE_SINGLE, FlagQRCode.encode(singlePayload))
        assertEquals(APPLE_EMPTY, FlagQRCode.encode(FlagPayload(emptyMap(), GoldenOverrides.exportedAt)))
    }

    @Test
    fun appleCodesDecode() {
        assertEquals(singlePayload, FlagQRCode.decode(APPLE_SINGLE, goldenSchema))
        assertEquals(FlagPayload(emptyMap(), GoldenOverrides.exportedAt), FlagQRCode.decode(APPLE_EMPTY, goldenSchema))
    }

    @Test
    fun encodedStringCarriesAVersionedPrefix() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertTrue(pole.qrCodeString().startsWith("FFQR1:"))
    }

    @Test
    fun encodedStringIsSafeForAQRCode() {
        // base64url, so nothing a QR code or a URL would need escaped, and no padding.
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)

        assertTrue(Regex("FFQR1:[A-Za-z0-9_-]+").matches(pole.qrCodeString()))
    }

    // Round trips

    @Test
    fun overridesSurviveAScan() {
        val source = goldenPole()
        source.setOverride(GoldenFlagsSpec.boolFlag, true)
        source.setOverride(GoldenFlagsSpec.intFlag, 7)
        source.setOverride(GoldenFlagsSpec.checkout.tier, GoldenTier.ENTERPRISE)

        val destination = goldenPole()
        val applied = destination.importQRCode(source.qrCodeString())

        assertEquals(source.overrides, destination.overrides)
        assertEquals(source.overrides, applied.values)
    }

    @Test
    fun anEmptySetOfOverridesRoundTrips() {
        val destination = goldenPole()

        destination.importQRCode(goldenPole().qrCodeString())

        assertTrue(destination.overrides.isEmpty())
    }

    @Test
    fun compressionMakesRealisticPayloadsFit() {
        // Keys and JSON structure repeat heavily, which is what deflate is good at.
        val pole = FlagPole(ManyFlagsSpec, listOf(SnapshotSource("local")))
        val value = "a moderately long override value. ".repeat(8)
        val refs = listOf(
            ManyFlagsSpec.alpha, ManyFlagsSpec.bravo, ManyFlagsSpec.charlie, ManyFlagsSpec.delta,
            ManyFlagsSpec.echo, ManyFlagsSpec.foxtrot, ManyFlagsSpec.golf, ManyFlagsSpec.hotel,
            ManyFlagsSpec.india, ManyFlagsSpec.juliett, ManyFlagsSpec.kilo, ManyFlagsSpec.lima,
            ManyFlagsSpec.mike, ManyFlagsSpec.november, ManyFlagsSpec.oscar, ManyFlagsSpec.papa,
        )
        for (ref in refs) pole.setOverride(ref, value)

        assertTrue(pole.exportJson().size > FlagQRCode.MAX_ENCODED_LENGTH)
        assertTrue(pole.qrCodeString().length <= FlagQRCode.MAX_ENCODED_LENGTH)
    }

    // Size limit

    @Test
    fun anOversizedPayloadIsRefusedWithSomethingActionable() {
        val pole = FlagPole(BlobFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(BlobFlagsSpec.blob, incompressibleBytes(8_000, 0x2545_F491_4F6C_DD1DuL))

        val error = assertFailsWith<FlagQRCodeException.PayloadTooLarge> { pole.qrCodeString() }

        assertTrue(error.bytes > error.limit)
        assertEquals(2953, error.limit)
        assertEquals(1, error.overrideCount)
    }

    @Test
    fun aPayloadJustUnderTheLimitEncodes() {
        val pole = FlagPole(BigStringFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(BigStringFlagsSpec.blob, StrictBase64.encode(incompressibleBytes(1_800, 0x9E37_79B9_7F4A_7C15uL)))

        assertTrue(pole.qrCodeString().length <= FlagQRCode.MAX_ENCODED_LENGTH)
    }

    @Test
    fun aPayloadJustOverTheLimitIsRefused() {
        val pole = FlagPole(BigStringFlagsSpec, listOf(SnapshotSource("local")))
        pole.setOverride(BigStringFlagsSpec.blob, StrictBase64.encode(incompressibleBytes(3_000, 0x9E37_79B9_7F4A_7C15uL)))

        assertFailsWith<FlagQRCodeException.PayloadTooLarge> { pole.qrCodeString() }
    }

    @Test
    fun aQRCodeOfANonFiniteNumberThrows() {
        val local = SnapshotSource("local", mapOf(FlagKey("ratio") to FlagValueBox.Double(Double.POSITIVE_INFINITY)))

        assertFailsWith<FlagSerializationException.NonFiniteNumber> { FlagPole(EdgeFlagsSpec, listOf(local)).qrCodeString() }
    }

    // Rejecting bad input

    @Test
    fun decodingRejectsAStringThatIsNotAFlagCode() {
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { goldenPole().importQRCode("https://example.com") }
    }

    @Test
    fun decodingRejectsAFutureFormatVersion() {
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { goldenPole().importQRCode("FFQR9:abcdef") }
    }

    @Test
    fun anEmptyScannedStringIsRejected() {
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { FlagQRCode.decode("", goldenSchema) }
    }

    @Test
    fun decodingRejectsCorruptedContent() {
        assertFailsWith<FlagQRCodeException.Corrupt> { goldenPole().importQRCode("FFQR1:!!!not-base64!!!") }
    }

    @Test
    fun aPrefixWithNoBodyIsCorrupt() {
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode("FFQR1:", goldenSchema) }
    }

    @Test
    fun decodingRejectsTruncatedContent() {
        val pole = goldenPole()
        pole.setOverride(GoldenFlagsSpec.boolFlag, true)
        val truncated = pole.qrCodeString().dropLast(8)

        assertFailsWith<FlagQRCodeException.Corrupt> { goldenPole().importQRCode(truncated) }
    }

    @Test
    fun aCorruptLengthPrefixDoesNotAllocateWildly() {
        // Four 0xFF bytes claim a 4 GB payload. It must be refused, not attempted.
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode(code(byteArrayOf(-1, -1, -1, -1, 0)), goldenSchema) }
        // 50,000,001 bytes: one past the cap.
        val overCap = byteArrayOf(0x02, 0xFA.toByte(), 0xF0.toByte(), 0x81.toByte(), 0)
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode(code(overCap), goldenSchema) }
    }

    @Test
    fun scannedCodeStillValidatesAgainstTheSchema() {
        // A code from another app's build must not smuggle in unknown keys.
        val foreign = FlagPole(BlobFlagsSpec, listOf(SnapshotSource("local")))
        foreign.setOverride(BlobFlagsSpec.blob, byteArrayOf(0x01))

        assertFailsWith<FlagImportException.Rejected> { goldenPole().importQRCode(foreign.qrCodeString()) }
    }

    @Test
    fun aValidCodeWithForeignKeysIsRejectedNotIgnored() {
        val encoded = FlagQRCode.encode(FlagPayload(mapOf(FlagKey("not-a-flag") to FlagValueBox.Bool(true))))

        val error = assertFailsWith<FlagImportException.Rejected> { FlagQRCode.decode(encoded, goldenSchema) }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownKey), error.problems.map { it.kind })
    }

    @Test
    fun aScannedCodeWithAnUnknownCaseIsRejected() {
        val sender = FlagPole(WiderTierFlagsSpec, listOf(SnapshotSource("local")))
        sender.setOverride(WiderTierFlagsSpec.tier, WiderTier.PLATINUM)
        val receiver = goldenPole()

        val error = assertFailsWith<FlagImportException.Rejected> { receiver.importQRCode(sender.qrCodeString()) }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownCase), error.problems.map { it.kind })
        assertEquals(GoldenTier.FREE, receiver.flags.tier)
    }

    // Matching Apple at the edges

    @Test
    fun scannedTextVariantsMatchApple() {
        // Review focus 3: what a scanner or a clipboard hands over.
        assertEquals(singlePayload, FlagQRCode.decode(APPLE_SINGLE.replace('-', '+').replace('_', '/'), goldenSchema))
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode(APPLE_SINGLE + "\n", goldenSchema) }
        assertFailsWith<FlagQRCodeException.Corrupt> { FlagQRCode.decode("$APPLE_SINGLE ", goldenSchema) }
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { FlagQRCode.decode(" $APPLE_SINGLE", goldenSchema) }
        assertFailsWith<FlagQRCodeException.UnrecognisedFormat> { FlagQRCode.decode(APPLE_SINGLE.lowercase(), goldenSchema) }

        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        assertContentEquals(bytes, FlagQRCode.base64UrlDecode("-_8"))
        assertContentEquals(bytes, FlagQRCode.base64UrlDecode("+/8"))
        assertContentEquals(bytes, FlagQRCode.base64UrlDecode("-_8="))
        assertNull(FlagQRCode.base64UrlDecode("-_8\n"))
        assertNull(FlagQRCode.base64UrlDecode("A"))
    }

    @Test
    fun aDeclaredLengthShorterThanTheStreamTruncatesTheDocument() {
        // Apple's decoder fills exactly the declared length and stops, so the JSON is cut short.
        val compressed = FlagQRCode.compress(singlePayload.jsonBytes())
        val lying = byteArrayOf(0, 0, 0, 50) + compressed.copyOfRange(4, compressed.size)

        val error = assertFailsWith<FlagImportException.Malformed> { FlagQRCode.decode(code(lying), goldenSchema) }

        assertEquals("not a JSON object", error.reason)
    }

    @Test
    fun trailingBytesAfterACompleteStreamAreIgnoredLikeApple() {
        val padded = FlagQRCode.compress(singlePayload.jsonBytes()) + byteArrayOf(1, 2, 3)

        assertEquals(singlePayload, FlagQRCode.decode(code(padded), goldenSchema))
    }

    @Test
    fun aDeclaredLengthOfZeroMeansAnEmptyBody() {
        assertContentEquals(ByteArray(0), FlagQRCode.decompress(byteArrayOf(0, 0, 0, 0)))
        assertNull(FlagQRCode.decompress(byteArrayOf(0, 0, 0, 0, 1)))

        val error = assertFailsWith<FlagImportException.Malformed> {
            FlagQRCode.decode(code(byteArrayOf(0, 0, 0, 0)), goldenSchema)
        }

        assertEquals("not a JSON object", error.reason)
    }

    @Test
    fun aBodyStoredRawIsReadBack() {
        // iOS stores the JSON itself when deflate cannot fit it in max(n, 64) bytes.
        val json = singlePayload.jsonBytes()
        val stored = byteArrayOf(0, 0, (json.size shr 8).toByte(), json.size.toByte()) + json

        assertEquals(singlePayload, FlagQRCode.decode(code(stored), goldenSchema))
    }

    @Test
    fun incompressibleInputIsStoredRaw() {
        val input = incompressibleBytes(1_000, 1uL)

        val compressed = FlagQRCode.compress(input)

        assertContentEquals(byteArrayOf(0, 0, 0x03, 0xE8.toByte()) + input, compressed)
        assertContentEquals(input, FlagQRCode.decompress(compressed))
    }

    @Test
    fun smallInputKeepsItsDeflatedFormEvenWhenLarger() {
        // Apple's buffer is max(n, 64) bytes, so a few bytes deflate cannot shrink still fit.
        val input = incompressibleBytes(40, 2uL)

        val compressed = FlagQRCode.compress(input)
        val body = compressed.copyOfRange(4, compressed.size)

        assertTrue(body.size > input.size, "deflate cannot shrink random bytes")
        assertTrue(body.size < 64, "but the 64-byte buffer still has room, so they stay deflated")
        assertContentEquals(input, FlagQRCode.decompress(compressed))
    }

    @Test
    fun aDeflatedBodyThatWouldFillApplesBufferExactlyIsStored() {
        // Measured: these 59 bytes deflate to exactly 64, and Apple's 64-byte buffer then
        // refuses them, because its encoder needs one byte to spare. The first 58 deflate
        // to 63 and are kept.
        val filling = incompressibleBytes(59, 3uL)
        val fitting = incompressibleBytes(58, 3uL)

        assertContentEquals(byteArrayOf(0, 0, 0, 59) + filling, FlagQRCode.compress(filling))
        val kept = FlagQRCode.compress(fitting)
        assertEquals(4 + 63, kept.size)
        assertContentEquals(fitting, FlagQRCode.decompress(kept))
    }
}

@FlagContainer
internal interface BlobFlags {

    @Flag(description = "Blob")
    val blob: ByteArray get() = ByteArray(0)
}

@FlagContainer
internal interface BigStringFlags {

    @Flag(description = "Blob")
    val blob: String get() = ""
}

/** A build with a case the receiving build has never heard of. */
internal enum class WiderTier(override val flagRawValue: String) : FlagStringEnum {
    FREE("free"),
    PRO("pro"),
    ENTERPRISE("enterprise"),
    PLATINUM("platinum"),
}

@FlagContainer
internal interface WiderTierFlags {

    @Flag(description = "Tier")
    val tier: WiderTier get() = WiderTier.FREE
}

@FlagContainer
internal interface ManyFlags {

    @Flag(description = "Alpha")
    val alpha: String get() = ""

    @Flag(description = "Bravo")
    val bravo: String get() = ""

    @Flag(description = "Charlie")
    val charlie: String get() = ""

    @Flag(description = "Delta")
    val delta: String get() = ""

    @Flag(description = "Echo")
    val echo: String get() = ""

    @Flag(description = "Foxtrot")
    val foxtrot: String get() = ""

    @Flag(description = "Golf")
    val golf: String get() = ""

    @Flag(description = "Hotel")
    val hotel: String get() = ""

    @Flag(description = "India")
    val india: String get() = ""

    @Flag(description = "Juliett")
    val juliett: String get() = ""

    @Flag(description = "Kilo")
    val kilo: String get() = ""

    @Flag(description = "Lima")
    val lima: String get() = ""

    @Flag(description = "Mike")
    val mike: String get() = ""

    @Flag(description = "November")
    val november: String get() = ""

    @Flag(description = "Oscar")
    val oscar: String get() = ""

    @Flag(description = "Papa")
    val papa: String get() = ""
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/QRCodeFixturesTest.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both directions of the QR golden.
 *
 * - `fixtures/qr/<name>.qr.txt` is the code FeatureFlag 0.12.0 made for the export JSON in
 *   `<name>.json`: `golden`, `single`, and `stored`, which Apple stored uncompressed.
 * - `fixtures-android/` holds the codes this port makes. `decode-kotlin-fixtures` decodes them with Swift.
 */
class QRCodeFixturesTest {

    private val appleCodes = File(fixturesDirectory(), "qr")
    private val androidCodes = File(fixturesDirectory().parentFile, "fixtures-android")

    private fun appleFixtures(): List<Pair<String, File>> = appleCodes.listFiles().orEmpty()
        .filter { it.name.endsWith(".qr.txt") }
        .sortedBy { it.name }
        .map { it.name.removeSuffix(".qr.txt") to it }

    @Test
    fun everyAppleCodeDecodesToItsJson() {
        val fixtures = appleFixtures()
        assertTrue(fixtures.isNotEmpty(), "no QR fixtures in $appleCodes")

        for ((name, file) in fixtures) {
            val json = File(appleCodes, "$name.json").readText()
            val code = file.readText().trimEnd()

            assertEquals(json, FlagQRCode.decodeJson(code).decodeToString(), name)
            assertEquals(json, FlagQRCode.decode(code, goldenSchema).jsonBytes().decodeToString(), name)
        }
    }

    @Test
    fun kotlinEncodesEveryAppleCodeByteForByte() {
        // `stored` included: Kotlin must make the same deflate-or-store choice Apple made.
        for ((name, file) in appleFixtures()) {
            if (name in ZLIB_DIFFERENCES) continue
            val json = File(appleCodes, "$name.json").readBytes()

            assertEquals(file.readText().trimEnd(), FlagQRCode.PREFIX + FlagQRCode.base64UrlEncode(FlagQRCode.compress(json)), name)
        }
    }

    /**
     * `fixtures-android/` is committed, and `decode-kotlin-fixtures` decodes it with Swift. By default this
     * test only checks that the committed files are exactly what this port writes today. With
     * `-Pfeatureflag.writeAndroidFixtures=true` it rewrites them instead, so the source tree changes only
     * when someone asks it to.
     */
    @Test
    fun theKotlinFixturesSwiftDecodesAreCurrent() {
        val write = System.getProperty("featureflag.writeAndroidFixtures") == "true"
        if (write) androidCodes.mkdirs()
        // Decoded values, so each payload equals what reading its own code back yields.
        val payloads = linkedMapOf(
            "golden" to FlagPayload(GoldenOverrides.decodedValues, GoldenOverrides.exportedAt),
            "empty" to FlagPayload(emptyMap(), GoldenOverrides.exportedAt),
            "single" to FlagPayload(mapOf(FlagKey("bool-flag") to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt),
        )

        for ((name, payload) in payloads) {
            val code = FlagQRCode.encode(payload)
            val json = payload.jsonBytes().decodeToString()
            assertEquals(payload, FlagQRCode.decode(code, goldenSchema), name)

            val jsonFile = File(androidCodes, "$name.json")
            val codeFile = File(androidCodes, "$name.qr.txt")
            if (write) {
                jsonFile.writeText(json)
                codeFile.writeText(code)
            } else {
                val stale = "fixtures-android/ is stale; rerun with -Pfeatureflag.writeAndroidFixtures=true"
                assertTrue(jsonFile.isFile && codeFile.isFile, "$name: missing. $stale")
                assertEquals(json, jsonFile.readText(), "$name.json: $stale")
                assertEquals(code, codeFile.readText(), "$name.qr.txt: $stale")
            }
        }
    }

    private companion object {
        /**
         * Apple fixtures whose code this JDK's zlib does not reproduce, so they are only
         * decode-checked. Add a name here only when decoding passes and encoding does not,
         * and give the JDK vendor and version alongside it.
         */
        val ZLIB_DIFFERENCES: Set<String> = emptySet()
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.FlagQRCodeTest' --tests 'com.andyyhope.featureflag.transport.QRCodeFixturesTest' --tests 'com.andyyhope.featureflag.errors.FlagQRCodeExceptionTest'
```

Expected: `BUILD FAILED`, with `compileTestKotlin` reporting `Unresolved reference 'FlagQRCode'`, `'FlagQRCodeException'`, `'qrCodeString'` and `'importQRCode'`.

- [ ] **Step 3: Write the implementation**

**Modify** M1a Task A1's convention plugin, so a Gradle property can ask `QRCodeFixturesTest` to rewrite `fixtures-android/`, and so editing those files re-runs the tests. In `build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt`, replace

```kotlin
        val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")
        tasks.withType<Test>().configureEach {
            systemProperty("featureflag.fixtures", fixturesDir.asFile.absolutePath)
            inputs.files(fixturesDir.asFileTree)
                .withPropertyName("featureflagFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
```

with

```kotlin
        val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")
        // The Kotlin-written files Swift decodes. Tests compare against them, and rewrite them only
        // when run with -Pfeatureflag.writeAndroidFixtures=true.
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
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagQRCodeException.kt`
```kotlin
package com.andyyhope.featureflag.errors

/** Why a QR code could not be made or read. */
public sealed class FlagQRCodeException(message: String) : RuntimeException(message) {

    /** The payload will not fit in one QR code, even compressed. Carries the override count so the message can say what to remove. */
    public class PayloadTooLarge(
        public val bytes: Int,
        public val limit: Int,
        public val overrideCount: Int,
    ) : FlagQRCodeException(
        "$overrideCount override${if (overrideCount == 1) "" else "s"} need $bytes characters and a QR code holds $limit. " +
            "Reset a few, or export JSON instead.",
    )

    /** Not a flag code, or a version this build does not understand. */
    public class UnrecognisedFormat : FlagQRCodeException("Not a flag code. A flag code starts with \"FFQR1:\".")

    /** A flag code that could not be decompressed, which usually means a partial scan. */
    public class Corrupt : FlagQRCodeException(
        "The code could not be decompressed, which usually means a partial scan. Try again with the whole code in frame.",
    )
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagQRCode.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.json.StrictBase64
import com.andyyhope.featureflag.schema.FlagSchema
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Carries flag overrides in a QR code.
 *
 * The wire format is `FFQR1:`, then base64url without padding of a 4-byte big-endian
 * length followed by the raw DEFLATE of the export JSON. When deflate cannot fit the JSON
 * in `max(n, 64)` bytes, the JSON itself goes in its place. Codes made on iOS decode here
 * and codes made here decode on iOS. The compressed bytes match wherever both zlibs agree,
 * as the JDK's does.
 *
 * Only overrides travel, never the whole flag tree. No camera UI ships here: [decode]
 * takes whatever string a scanner produced.
 */
public object FlagQRCode {

    /** Identifies a flag code and its wire format version. */
    public const val PREFIX: String = "FFQR1:"

    /** The capacity of the largest QR code (version 40) at error correction level L, in byte mode. */
    public const val MAX_ENCODED_LENGTH: Int = 2953

    private const val DEFLATE_LEVEL = 5
    private const val MIN_CAPACITY = 64
    private const val LENGTH_BYTES = 4
    private const val MAX_DECLARED_LENGTH = 50_000_000L

    /**
     * Compresses and encodes a payload.
     *
     * @throws FlagQRCodeException.PayloadTooLarge when the result will not fit in one code.
     */
    public fun encode(payload: FlagPayload): String {
        val encoded = PREFIX + base64UrlEncode(compress(payload.jsonBytes()))
        if (encoded.length > MAX_ENCODED_LENGTH) {
            throw FlagQRCodeException.PayloadTooLarge(encoded.length, MAX_ENCODED_LENGTH, payload.values.size)
        }
        return encoded
    }

    /**
     * Decodes a scanned string and validates every value against [schema], exactly as an
     * imported document is validated. A code from another app's build is rejected, not
     * partly applied.
     */
    public fun decode(text: String, schema: FlagSchema): FlagPayload = FlagPayload.decode(decodeJson(text), schema)

    /** The export JSON a code carries. */
    internal fun decodeJson(text: String): ByteArray {
        if (!text.startsWith(PREFIX)) throw FlagQRCodeException.UnrecognisedFormat()
        return base64UrlDecode(text.substring(PREFIX.length))?.let { decompress(it) }
            ?: throw FlagQRCodeException.Corrupt()
    }

    /**
     * The length prefix, then the deflated bytes, or the input itself when deflate cannot
     * fit it. Apple's encoder writes into a buffer of `max(n, 64)` bytes and needs one byte
     * to spare: it gives up, and the input is stored, when the deflated bytes would fill
     * that buffer exactly or overflow it. This was measured, and the `stored` golden pins it.
     */
    internal fun compress(json: ByteArray): ByteArray {
        val deflated = deflate(json)
        val body = if (deflated.size >= maxOf(json.size, MIN_CAPACITY)) json else deflated
        return lengthPrefix(json.size) + body
    }

    internal fun decompress(data: ByteArray): ByteArray? {
        if (data.size < LENGTH_BYTES) return null
        val expected = (0 until LENGTH_BYTES).fold(0L) { length, index -> (length shl 8) or (data[index].toLong() and 0xFF) }
        val body = data.copyOfRange(LENGTH_BYTES, data.size)
        if (expected == 0L) return if (body.isEmpty()) ByteArray(0) else null
        // A corrupt length must not turn into an implausible allocation.
        if (expected > MAX_DECLARED_LENGTH) return null
        val output = ByteArray(expected.toInt())
        return when {
            inflate(body, output) == output.size -> output
            // Deflate could not shrink it when it was encoded, so it was stored as it is.
            body.size == output.size -> body
            else -> null
        }
    }

    /** base64url without padding: no character a QR code or a URL would need escaped. */
    internal fun base64UrlEncode(bytes: ByteArray): String =
        StrictBase64.encode(bytes).replace('+', '-').replace('/', '_').replace("=", "")

    /** The reverse, as Apple does it: map back to the standard alphabet, pad, and decode strictly. */
    internal fun base64UrlDecode(text: String): ByteArray? {
        val base64 = text.replace('-', '+').replace('_', '/')
        val remainder = base64.length % 4
        return StrictBase64.decode(if (remainder > 0) base64 + "=".repeat(4 - remainder) else base64)
    }

    private fun lengthPrefix(length: Int): ByteArray =
        byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(DEFLATE_LEVEL, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            var output = ByteArray(maxOf(input.size, MIN_CAPACITY))
            var size = 0
            while (!deflater.finished()) {
                if (size == output.size) output = output.copyOf(output.size * 2)
                size += deflater.deflate(output, size, output.size - size)
            }
            return output.copyOf(size)
        } finally {
            deflater.end()
        }
    }

    /**
     * Inflates into [output] until it is full, the stream ends, or the input runs out or
     * turns out not to be DEFLATE. Apple's decoder stops in the same places, so trailing
     * bytes are ignored and a short declared length cuts the output short.
     */
    private fun inflate(body: ByteArray, output: ByteArray): Int {
        val inflater = Inflater(true)
        try {
            inflater.setInput(body)
            var written = 0
            var progressing = true
            while (progressing && written < output.size) {
                val count = inflateStep(inflater, output, written)
                progressing = count > 0
                if (progressing) written += count
            }
            return written
        } finally {
            inflater.end()
        }
    }

    private fun inflateStep(inflater: Inflater, output: ByteArray, offset: Int): Int = try {
        inflater.inflate(output, offset, output.size - offset)
    } catch (ignored: DataFormatException) {
        -1
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/**
 * Every flag some source supplies a value for, ready to serialise.
 *
 * This spans the whole stack, not just local overrides. A value a remote payload supplied
 * is exported too, and becomes a local override wherever the document is imported.
 */
public fun FlagPole<*>.exportPayload(): FlagPayload = FlagPayload(values = overrides)

/** The current overrides as JSON, byte-identical to an iOS export. */
public fun FlagPole<*>.exportJson(): ByteArray = exportPayload().jsonBytes()

/**
 * Applies a JSON document as overrides. Strict and all or nothing: any unknown key,
 * mistyped value, unknown enum case or unreadable record list rejects the whole document,
 * with every problem listed.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importJson(bytes: ByteArray): FlagPayload {
    val payload = FlagPayload.decode(bytes, schema)
    apply(payload)
    return payload
}

/**
 * Applies an already-decoded payload as overrides in the top mutable source.
 *
 * If a write throws partway, the writes already made are undone before the error
 * propagates. The undo is best effort: a source that has stopped accepting writes will
 * refuse the undo as well.
 */
public fun FlagPole<*>.apply(payload: FlagPayload) {
    val types = ImportRules(schema).types
    val undo = ArrayList<Pair<FlagKey, FlagValueBox?>>()
    var completed = false
    try {
        for ((key, box) in payload.values) {
            val previous = types[key]?.let { currentOverride(key, it) }
            setOverride(key, box)
            undo.add(key to previous)
        }
        completed = true
    } finally {
        if (!completed) {
            for ((key, previous) in undo.asReversed()) {
                runCatching { setOverride(key, previous) }
            }
        }
    }
}

/**
 * The current overrides, encoded for a QR code.
 *
 * @throws com.andyyhope.featureflag.errors.FlagQRCodeException.PayloadTooLarge when they will not fit.
 */
public fun FlagPole<*>.qrCodeString(): String = FlagQRCode.encode(exportPayload())

/**
 * Applies a scanned flag code, all or nothing, exactly as [importJson] applies a document.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importQRCode(text: String): FlagPayload {
    val payload = FlagQRCode.decode(text, schema)
    apply(payload)
    return payload
}
```

Then the Swift side. First confirm the decoder target exists:

```bash
grep -n "DecodeKotlinFixtures\|decode-kotlin-fixtures" fixture-generator/Package.swift
```

Expected: the product line and the target line. If either is missing, add both:
- under `products:`, add `.executable(name: "decode-kotlin-fixtures", targets: ["DecodeKotlinFixtures"]),`;
- under `targets:`, add `.executableTarget(name: "DecodeKotlinFixtures", dependencies: ["GoldenModel", .product(name: "FeatureFlag", package: "FeatureFlag")]),`.

If the `GenerateFixtures` target names the FeatureFlag package differently, for example in the case of its identity, use its spelling.

Then replace the stub. This code compiles under Swift 6 language mode against FeatureFlag 0.12.0 and the golden model:

`fixture-generator/Sources/DecodeKotlinFixtures/main.swift`
```swift
// Decodes every file the Kotlin port wrote into fixtures-android/ with FeatureFlag 0.12.0,
// and exits non-zero if any of them fails.
//
//   swift run --package-path fixture-generator decode-kotlin-fixtures [fixtures-android]
//
// - `<name>.json` is a Kotlin export. It must decode against the GoldenFlags schema and
//   re-encode to the same bytes.
// - `<name>.qr.txt` is a Kotlin QR string. It must decode, and its payload must encode to
//   the bytes of the sibling `<name>.json`. Whether Apple's own QR string for that payload
//   is identical is printed but not required: zlib implementations may differ.

import FeatureFlag
import Foundation
import GoldenModel

func run() -> Int32 {
    let arguments = CommandLine.arguments
    let directory = URL(
        fileURLWithPath: arguments.count > 1 ? arguments[1] : "fixtures-android",
        isDirectory: true
    )
    let schema = FlagSchema(GoldenFlags.self, keyEncoding: .kebabcase, applicationName: "Golden")

    let names: [String]
    do {
        names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
    } catch {
        print("FAIL cannot list \(directory.path): \(error)")
        return 1
    }

    var failures = 0
    var checked = 0

    func fail(_ name: String, _ reason: String) {
        print("FAIL \(name): \(reason)")
        failures += 1
    }

    for name in names where name.hasSuffix(".json") {
        checked += 1
        do {
            let bytes = try Data(contentsOf: directory.appendingPathComponent(name))
            let payload = try FlagPayload.decode(
                bytes,
                as: .json,
                valueTypes: schema.valueTypes,
                cases: schema.valueCases,
                recordShapes: schema.recordShapes
            )
            if try payload.encoded(as: .json) == bytes {
                print("ok   \(name): \(payload.values.count) values, byte-identical")
            } else {
                fail(name, "decoded, but Apple re-encodes it to different bytes")
            }
        } catch {
            fail(name, "\(error)")
        }
    }

    for name in names where name.hasSuffix(".qr.txt") {
        checked += 1
        let stem = String(name.dropLast(".qr.txt".count))
        do {
            let scanned = try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
                .trimmingCharacters(in: .newlines)
            let expected = try Data(contentsOf: directory.appendingPathComponent("\(stem).json"))
            let payload = try FlagQRCode.decode(
                scanned,
                valueTypes: schema.valueTypes,
                cases: schema.valueCases,
                recordShapes: schema.recordShapes
            )
            guard try payload.encoded(as: .json) == expected else {
                fail(name, "decoded, but the payload does not encode to \(stem).json")
                continue
            }
            let appleString = try FlagQRCode.encode(payload)
            print("ok   \(name): decoded; Apple's own code is \(appleString == scanned ? "identical" : "different (allowed)")")
        } catch {
            fail(name, "\(error)")
        }
    }

    if checked == 0 {
        print("FAIL no fixtures in \(directory.path)")
        return 1
    }
    print(failures == 0 ? "All \(checked) Kotlin fixtures decode." : "\(failures) of \(checked) failed.")
    return failures == 0 ? 0 : 1
}

exit(run())
```

- [ ] **Step 4: Run the tests, generate the reverse fixtures once, and decode them with Swift**

First run the tests without the property. `theKotlinFixturesSwiftDecodesAreCurrent` fails, because `fixtures-android/` does not exist yet; everything else passes:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.FlagQRCodeTest' --tests 'com.andyyhope.featureflag.transport.QRCodeFixturesTest' --tests 'com.andyyhope.featureflag.errors.FlagQRCodeExceptionTest' --rerun
```

Expected: `BUILD FAILED`, with only `QRCodeFixturesTest > theKotlinFixturesSwiftDecodesAreCurrent` failing on `golden: missing. fixtures-android/ is stale; rerun with -Pfeatureflag.writeAndroidFixtures=true`.

Then generate the files, which is the only way they are ever written, and check that the plain build now passes against them:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.QRCodeFixturesTest' -Pfeatureflag.writeAndroidFixtures=true --rerun
./gradlew build detekt
ls fixtures-android
```

Expected:
- **Gradle:** `BUILD SUCCESSFUL` from both commands. Together the three test classes run 36 tests: 4 in `FlagQRCodeExceptionTest`, 29 in `FlagQRCodeTest` and 3 in `QRCodeFixturesTest`.
- **`ls`:** lists `empty.json empty.qr.txt golden.json golden.qr.txt single.json single.qr.txt`.
- **`fixtures-android/single.qr.txt`:** holds exactly the `APPLE_SINGLE` string from `FlagQRCodeTest`.
- **`git status --short fixtures-android`** shows the six new files and nothing else. A later plain `./gradlew build` never touches them.

If `kotlinEncodesEveryAppleCodeByteForByte` is the only failure, and `everyAppleCodeDecodesToItsJson` passes for the same fixture, then this JDK's zlib differs from Apple's:
- add the fixture's name to `ZLIB_DIFFERENCES`, with the JDK vendor and version in its comment;
- report it in the task hand-off.

Any other failure is a bug to fix.

Now decode the Kotlin-made files with FeatureFlag 0.12.0, and check that a bad file is refused:

```bash
swift run --package-path fixture-generator decode-kotlin-fixtures fixtures-android
bad=$(mktemp -d) && printf 'FFQR1:nope' > "$bad/bad.qr.txt" && swift run --package-path fixture-generator decode-kotlin-fixtures "$bad"; echo "exit $?"; rm -rf "$bad"
```

Expected from the first command, which exits 0:
```
ok   empty.json: 0 values, byte-identical
ok   golden.json: 19 values, byte-identical
ok   single.json: 1 values, byte-identical
ok   empty.qr.txt: decoded; Apple's own code is identical
ok   golden.qr.txt: decoded; Apple's own code is identical
ok   single.qr.txt: decoded; Apple's own code is identical
All 6 Kotlin fixtures decode.
```
Expected from the second: a line starting `FAIL bad.qr.txt:` (it names the missing `bad.json`), then `1 of 1 failed.` and `exit 1`.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagQRCodeException.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagQRCodeExceptionTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport \
  fixture-generator/Package.swift fixture-generator/Sources/DecodeKotlinFixtures/main.swift \
  build-logic/src/main/kotlin/JvmLibraryConventionPlugin.kt fixtures-android
git commit -m "Add FFQR1 QR codes that decode on both platforms" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task C6: `publishSchema(directory)`

**Files:**
- Modify (full replacement): `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/PublishSchemaTest.kt`

**Interfaces:**
- Consumes (M1b): `FlagSchema.write(directory: File): File` (atomic, creates missing directories, `FILE_NAME`), `FlagSchema.read(directory)`, `FlagSchema.parse(bytes)`, `FlagSchema.jsonBytes()`, `FlagSchema.FILE_NAME`, `FlagSchemaException.Malformed`/`NotPublished`, and `FlagContainerSpec` for a hand-written empty spec.
- Produces:
```kotlin
public fun FlagPole<*>.publishSchema(directory: File): File     // writes schema.jsonBytes() atomically to directory/flag-schema.json
```

- [ ] **Step 1: Write the failing test**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/PublishSchemaTest.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.temporal.ChronoUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishSchemaTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun pole(): FlagPole<GoldenFlags> = FlagPole(GoldenFlagsSpec, emptyList(), applicationName = "Golden")

    private fun parse(json: String): FlagSchema = FlagSchema.parse(json.encodeToByteArray())

    // Publishing

    @Test
    fun publishingWritesTheSchemaBytesWhereReadFindsThem() {
        val pole = pole()
        val directory = File(temporary.root, "nested/deeper")

        val file = pole.publishSchema(directory)

        assertEquals(File(directory, FlagSchema.FILE_NAME), file)
        assertContentEquals(pole.schema.jsonBytes(), file.readBytes())

        // The file carries the date to the second; everything else round-trips exactly.
        val read = FlagSchema.read(directory)
        assertEquals(pole.schema.generatedAt.truncatedTo(ChronoUnit.SECONDS), read.generatedAt)
        assertEquals(pole.schema, read.copy(generatedAt = pole.schema.generatedAt))
    }

    @Test
    fun publishingAgainReplacesTheFileAndLeavesNothingBehind() {
        val directory = temporary.newFolder("schema")
        pole().publishSchema(directory)
        val snake = FlagPole(GoldenFlagsSpec, emptyList(), KeyEncoding.Snakecase, "Golden")

        snake.publishSchema(directory)

        assertEquals(listOf(FlagSchema.FILE_NAME), directory.list().orEmpty().sorted())
        assertContentEquals(snake.schema.jsonBytes(), File(directory, FlagSchema.FILE_NAME).readBytes())
    }

    @Test
    fun publishingWhereNoDirectoryCanBeMadeThrows() {
        val blocker = temporary.newFile("not-a-directory")

        assertFails { pole().publishSchema(File(blocker, "schema")) }
    }

    @Test
    fun aPublishedSchemaDoesNotEscapeSlashes() {
        val text = pole().publishSchema(temporary.newFolder("slashes")).readText()

        assertTrue(text.contains("https://example.com/a/b?c=d"), text)
        assertFalse(text.contains("\\/"), "slashes should not be escaped")
    }

    @Test
    fun readingADirectoryWithNoSchemaSaysNothingWasPublished() {
        // Replaces Swift's two unknown-App-Group tests: Android has no App Group.
        assertFailsWith<FlagSchemaException.NotPublished> { FlagSchema.read(temporary.newFolder("empty")) }
    }

    // Malformed schemas (TransportEdgeCaseTests)

    @Test
    fun aSchemaMissingItsVersionIsRejected() {
        assertFailsWith<FlagSchemaException.Malformed> { parse("""{"flags": []}""") }
    }

    @Test
    fun aSchemaMissingItsFlagsIsRejected() {
        assertFailsWith<FlagSchemaException.Malformed> { parse("""{"formatVersion": 1}""") }
    }

    @Test
    fun aSchemaThatIsNotAnObjectIsRejected() {
        assertFailsWith<FlagSchemaException> { parse("[]") }
        assertFailsWith<FlagSchemaException> { parse("garbage") }
    }

    @Test
    fun anEntryWithAnUnknownTypeNameIsRejected() {
        assertFailsWith<FlagSchemaException> {
            parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"banana","defaultValue":1}]}""")
        }
    }

    @Test
    fun anEntryWhoseDefaultDoesNotMatchItsTypeIsRejected() {
        val error = assertFailsWith<FlagSchemaException.Malformed> {
            parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"int","defaultValue":"lots"}]}""")
        }

        assertTrue(error.message.orEmpty().contains("'a'"), "message should name the flag: ${error.message}")
    }

    @Test
    fun aSchemaWithNoGroupsDecodesFine() {
        val schema = parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"int","defaultValue":1}]}""")

        assertTrue(schema.groups.isEmpty())
        assertEquals(listOf("a"), schema.flags.first().propertyPath)
    }

    @Test
    fun anEmptyContainerProducesAnEmptySchema() {
        val schema = FlagSchema.of(EmptySpec)

        assertTrue(schema.flags.isEmpty())
        assertTrue(schema.groups.isEmpty())
    }

    /** A container with no flags. Swift's `EmptyFlags {}`, written by hand so no KSP edge case is involved. */
    private object EmptySpec : FlagContainerSpec<Any> {
        override val flagContainerDescription: String? = null
        override val flagDescriptors: List<FlagSchemaNode> = emptyList()
        override val flagDefaults: Any = Any()

        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Any()
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.PublishSchemaTest'
```

Expected: `BUILD FAILED`, with `compileTestKotlin` reporting `Unresolved reference 'publishSchema'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
```kotlin
package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.io.File

/**
 * Writes this pole's schema where a companion app can find it, as `flag-schema.json` in
 * [directory], creating the directory if needed. The write is atomic, so a reader never
 * sees half a file.
 *
 * Call this on launch: a companion has no other way to learn what flags exist, what they
 * are called, or what types they hold.
 *
 * @return the file written.
 */
public fun FlagPole<*>.publishSchema(directory: File): File = schema.write(directory)

/**
 * Every flag some source supplies a value for, ready to serialise.
 *
 * This spans the whole stack, not just local overrides. A value a remote payload supplied
 * is exported too, and becomes a local override wherever the document is imported.
 */
public fun FlagPole<*>.exportPayload(): FlagPayload = FlagPayload(values = overrides)

/** The current overrides as JSON, byte-identical to an iOS export. */
public fun FlagPole<*>.exportJson(): ByteArray = exportPayload().jsonBytes()

/**
 * Applies a JSON document as overrides. Strict and all or nothing: any unknown key,
 * mistyped value, unknown enum case or unreadable record list rejects the whole document,
 * with every problem listed.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importJson(bytes: ByteArray): FlagPayload {
    val payload = FlagPayload.decode(bytes, schema)
    apply(payload)
    return payload
}

/**
 * Applies an already-decoded payload as overrides in the top mutable source.
 *
 * If a write throws partway, the writes already made are undone before the error
 * propagates. The undo is best effort: a source that has stopped accepting writes will
 * refuse the undo as well.
 */
public fun FlagPole<*>.apply(payload: FlagPayload) {
    val types = ImportRules(schema).types
    val undo = ArrayList<Pair<FlagKey, FlagValueBox?>>()
    var completed = false
    try {
        for ((key, box) in payload.values) {
            val previous = types[key]?.let { currentOverride(key, it) }
            setOverride(key, box)
            undo.add(key to previous)
        }
        completed = true
    } finally {
        if (!completed) {
            for ((key, previous) in undo.asReversed()) {
                runCatching { setOverride(key, previous) }
            }
        }
    }
}

/**
 * The current overrides, encoded for a QR code.
 *
 * @throws com.andyyhope.featureflag.errors.FlagQRCodeException.PayloadTooLarge when they will not fit.
 */
public fun FlagPole<*>.qrCodeString(): String = FlagQRCode.encode(exportPayload())

/**
 * Applies a scanned flag code, all or nothing, exactly as [importJson] applies a document.
 *
 * @return the payload that was applied.
 */
public fun FlagPole<*>.importQRCode(text: String): FlagPayload {
    val payload = FlagQRCode.decode(text, schema)
    apply(payload)
    return payload
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.transport.PublishSchemaTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` from both commands. The first runs 12 tests, all passing.

The schema-parsing cases test M1b's `FlagSchema.parse` and `write` through this API. If one of them fails, the fault is in M1b's port. Fix it there so that it matches `FlagSchema.swift`, and keep these assertions.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/PublishSchemaTest.kt
git commit -m "Add publishSchema for companion apps" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Deviations from the contract and from Swift (for the overview's maintainer)

- **`FlagImportProblem.Kind`** has the entries `UnknownKey`, `TypeMismatch` and `UnknownCase`, in PascalCase like M1d's `RemoteOverrideProblem.Kind`.
- **`FlagImportException.UnsupportedFormatVersion(version: Long)`** is a `Long` because Swift's `Int` is 64-bit. iOS reports `5000000000` as is. A call site written as `UnsupportedFormatVersion(9)` still compiles.
- **The exceptions are classes, not objects,** so each throw has its own stack trace: `FlagException.NoMutableSource()`, `FlagQRCodeException.UnrecognisedFormat()` and `FlagQRCodeException.Corrupt()`. `imageGenerationFailed` is dropped along with Core Image.
- **Reworded messages:**
  - `NoMutableSource` says "Add a SharedPreferencesSource or a SnapshotSource", where Swift says `UserDefaultsSource`.
  - `UnsupportedValue` says "The store accepts…", where Swift says "Property list storage accepts…".
- **`FlagPole(applicationName = null)`** passes `null` to `FlagSchema.of`, so the schema leaves `applicationName` out, where Swift falls back to the bundle's display name. Android has no such name in core.
- **`apply(payload)` returns `Unit`.** Swift returns `FlagImportResult(appliedKeys)`. `importJson` and `importQRCode` return the applied `FlagPayload`, as the contract says.
- **`removeAllOverrides()` makes one `setBoxes` call,** where Swift writes key by key. With the default `setBoxes`, the behaviour is identical. It lets M2's preferences source clear everything in one commit.
- **`flow(ref)` adds a final value-level `distinctUntilChanged`.** It also uses `flow { coroutineScope { … } }` with `Unconfined` subscriptions instead of `channelFlow`, for the reasons given in C3.
- **The QR stored fallback** happens when deflate's output is at least `max(n, 64)` bytes, not more than it. This was measured: Apple's encoder needs one spare byte. M1a's `stored` golden fails under the "more than" reading.
- **Internal helpers added:** `ImportRules`, and `FlagQRCode.decodeJson`, `compress`, `decompress`, `base64UrlEncode` and `base64UrlDecode`.
- **Private message helpers in `FlagPayload.kt`:** `caseListDescription`, `shortDescription` and `numberDescription`. They are built on M1a's `MessageText`. M1d Task D1 adds `MessageText.caseList` and `MessageText.short` and deletes the first two in favour of them. `numberDescription`, NSNumber's `%.16g`, is used only by import messages.
- **Known message differences from iOS, all of them edge cases:**
  - An integer literal above `Long.MAX_VALUE` reads as a `Real`, so `found` is `1e+19` where iOS writes `10000000000000000000`.
  - `quoted` counts code points, where iOS counts grapheme clusters.
  - A duplicated record key of type double or float renders with Kotlin's `toString()` until M1d Task D1 switches it to `MessageText.short`, which matches Swift.
  - A case list of anything other than string or int cases uses the short description.
  - A UTF-16 document is rejected, where `JSONSerialization` would accept it.
