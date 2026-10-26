# M1d Remote, Audit and Test Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port FeatureFlag 0.12.0's remote layer (`RemoteValue`, mappers, `RemoteOverrideSource`, `EnvironmentConfiguration`), its mapping audit and record diffs, the error-message goldens, the `featureflag-test` assertion, the `FlagSignal` interface and the DemoExamples scenarios to Kotlin. Every message and report must be byte-identical to Swift's.  **Architecture:** `RemoteValue` is a strict JSON tree, built on M1a's `JsonReader`. A `RemoteOverrideMapper` picks values out of it, and `FlagSchema.mapRemote` validates them. That one validation path is shared by `RemoteOverrideSource` (all-or-nothing, a `@Volatile` immutable map) and `FlagMappingAudit` (coverage plus default diff). `EnvironmentConfiguration` pairs two sources under an epoch lock. Swift's `description` rendering (`String(Double)`, `flagQuoted`, `String(describing:)` of a case) is added to M1a's `MessageText` (D1.1), the port's one value-rendering helper, and Swift-generated goldens pin it.  **Tech Stack:** Kotlin 2.2.10 (JVM), kotlinx-coroutines 1.10.2 + kotlinx-coroutines-test, JUnit 4.13.2 + kotlin-test-junit, KSP 2.2.10-2.0.2 (test-local `@FlagContainer`s), detekt 1.23.8.
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

1. **Flat dotted member names.** `{"featureToggles.onboarding.v2": true}` is common in Firebase-style flat configs. A dot path cannot reach it, and the audit then reports the flag absent but the value consumed. Pinned by D1 `aMemberNameContainingADotCannotBeReachedByAPath` and D4 `aFlatDottedMemberIsAbsentYetNotReportedAsUnconsumed`.
2. **An `int` wider than 32 bits** (epoch millis, byte counts). Apply and the audit accept it because iOS's `Int` is 64-bit, but a Kotlin `Int` flag reads it as its default. Pinned by D2 `anIntBeyondKotlinIntRangeIsAcceptedButAnIntFlagReadsItsDefault`.
3. **URL strings that need percent-encoding** (spaces, non-ASCII). iOS 17+ `URL(string:)` encodes and accepts them. A strict `java.net.URI` parse would reject the whole payload on Android only. Pinned by D1 `aUrlNeedingPercentEncodingBoxesTheWayIosDoes` and the remote golden `url-needing-encoding`.
4. **A record list sent pre-serialised as a string.** This mirrors an iOS 0.12.0 quirk. The string skips the enum, nested and custom-key handling. An unknown case is accepted and stored verbatim, and the flag then silently reads its default. A backend key such as `apple_pay` is never read. Pinned by D2 `aRecordListSentAsAStringSkipsCaseChecksMirroringIos0120` and `aRecordListSentAsAStringIsReadByPropertyNameMirroringIos0120`.
5. **A cancelled environment load**, either a ViewModel scope cleared mid-fetch or a loader's own `withTimeout`. It must propagate `CancellationException` and leave the remote layer cleared. It must never report `Failed`, and must never keep the previous environment's values. Pinned by D3 `cancellingALoadPropagatesAndLeavesTheRemoteLayerCleared` and `aLoaderThrowingCancellationIsRethrownRatherThanReportedAsFailed`.

---

## Seams this plan relies on (not fixed by the contract)

These are items from M1a–M1c that the contract names but does not spell out. This plan uses the direct Swift-to-Kotlin port of each. If an earlier sub-plan chose differently, fix the call site and keep the asserted behaviour.

- **Packages follow directories,** as detekt's `InvalidPackageDeclaration` requires. Examples: `com.andyyhope.featureflag.values`, `.json`, `.records`, `.schema`, `.container`, `.sources`, `.pole`, `.transport`, `.errors`, `.remote`, `.audit`, `.signals`. The annotations live in `com.andyyhope.featureflag`. The golden spec is `com.andyyhope.featureflag.golden.GoldenFlagsSpec`.
- **`FlagSchema` fields follow `FlagSchema.swift`.** `FlagSchema(formatVersion, generatedAt, applicationName, description, flags, groups)` and `FlagSchema.Entry(key, propertyPath, description, valueType, defaultValue, cases, remoteKey, recordShape)` are both data classes.
- **`SnapshotSource`'s first constructor parameter is its name**, so `SnapshotSource("Companion")`.
- **`RecordJson.decode(text, shape)` matches Swift's `FlagValueBox.recordValues(matching:)`:**
  - it reads each field by `name` (never `decodedName`);
  - it boxes values with `JsonValue.toBox`;
  - it fills declared defaults and skips optionals;
  - it does **not** check enum cases;
  - it returns `null` on a shared key.
  This plan disables the key check by passing `isKey = false` copies of the shape.
- **Error subclasses are classes named after the Swift cases**, with the Swift associated values as constructor parameters. Examples: `FlagImportException.UnsupportedFormatVersion(9)`, `FlagQRCodeException.Corrupt()`.
- **`kotlinx-coroutines-test` and Turbine are on every module's test classpath.** M1a Task A1's convention plugin adds both. M1c's flow tests use `runBlocking` and Turbine; D3's tests use `runTest`.
- **`featureflag-test` tests run without the KSP processor.** D6 hand-writes its one spec through the public contract.

### Files this plan creates
```
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/
  errors/RemoteOverrideException.kt
  remote/RemoteValue.kt  remote/RemoteOverrideMapper.kt  remote/RemoteMapping.kt  remote/RemoteOverrideSource.kt  remote/EnvironmentConfiguration.kt
  audit/FlagRecordDiff.kt  audit/FlagDefaultComparison.kt  audit/FlagMappingAudit.kt
  signals/FlagSignal.kt
featureflag-core/src/test/kotlin/com/andyyhope/featureflag/
  errors/MessageTextDescriptionTest.kt  errors/RemoteOverrideExceptionTest.kt  errors/ErrorMessageTest.kt
  remote/RemoteValueTest.kt  remote/RemoteOverrideSourceTest.kt  remote/FlagRecordRemoteTest.kt  remote/RemoteGoldenFixtureTest.kt  remote/EnvironmentConfigurationTest.kt
  audit/FlagRecordDiffLineTest.kt  audit/FlagDefaultComparisonTest.kt  audit/FlagMappingAuditTest.kt  audit/FlagDefaultDiffTest.kt  audit/FlagRecordDiffTest.kt  audit/AuditGoldenFixtureTest.kt
  signals/FlagSignalTest.kt
  scenario/DemoFlags.kt  scenario/DemoConfigurations.kt  scenario/ScenarioTest.kt  scenario/RemoteDocumentationExampleTest.kt
featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMapped.kt
featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMappedTest.kt
```

### Files this plan modifies (each shown as an explicit **Modify** step)
```
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt       D1.1 (M1a's; adds Swift's description rendering)
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt    D1.1 (M1c's; uses MessageText.short and caseList)
```

Every Gradle command block below starts with the `JAVA_HOME` export and runs from the repository root, `/Users/andyyhope/Development/feature-flags-android`. The last cycle of each task also runs `./gradlew build detekt`, because the Global Constraints define a task as done only when that is green. Every block below already passes detekt as written.

---

### Task D1: `RemoteValue`, Swift-style value text, and `RemoteOverrideException`

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt` (M1a Tasks A4 and A8 wrote it; D1.1 adds Swift's `description` rendering)
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt` (M1c Task C4 wrote it; D1.1 swaps its private copies for `MessageText.short` and `MessageText.caseList`)
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/RemoteOverrideException.kt`. The task list put this in D2, but `parseJson` already throws `Malformed`, so it lands here.
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteValue.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextDescriptionTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/RemoteOverrideExceptionTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteValueTest.kt`

**Interfaces:**
- Consumes (M1a):
  - `internal sealed interface JsonValue { Null; Bool(value); Integer(value: Long); Real(value: Double); Text(value: String); Array(items); Object(fields) }`
  - `internal class JsonSyntaxException`
  - `internal object JsonReader { fun parse(bytes: ByteArray): JsonValue }`
  - `internal object IsoDate { fun format(instant: Instant): String }`
  - `internal fun JsonValue.toBox(type: FlagValueType): FlagValueBox?`. Its `Url` branch is M1a Task A9's `parseUrl`, which already percent-encodes text the way iOS 17+ `URL(string:)` does (pinned by `url/parse.tsv`), so remote payloads and imports share it unchanged.
  - `internal object MessageText { fun quoted(text: String): String; fun arrayOf(count: Int): String; fun objectWith(count: Int): String; fun date(instant: Instant): String }` (Tasks A4, A8)
  - `FlagValueBox` and `FlagValueType` as in the contract, plus `@JvmInline value class FlagKey(rawValue)` whose `toString()` is `rawValue`.
- Consumes (M1c): `transport/FlagPayload.kt`, whose private `shortDescription()` and `caseListDescription()` D1.1 replaces.
- Produces:
```kotlin
internal object MessageText {                   // M1a's, plus:
    fun double(value: Double): String           // Swift String(Double)
    fun float(value: Float): String             // Swift String(Float)
    fun short(box: FlagValueBox): String        // Swift FlagValueBox.shortMessageDescription
    fun diffField(box: FlagValueBox): String    // Swift FlagValueBox.diffFieldDescription
    fun caseList(cases: List<FlagValueBox>): String   // Swift caseListDescription ("free, pro", "int(1), int(2)")
}
public data class RemoteOverrideProblem(val key: FlagKey, val remoteKey: String, val kind: Kind, val expected: String? = null, val found: String? = null) {
    public enum class Kind { TypeMismatch, UnknownCase, UnknownKey }
    override fun toString(): String                // Swift RemoteOverrideProblem.description
}
public sealed class RemoteOverrideException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    public class Malformed(public val reason: String, cause: Throwable? = null) : RemoteOverrideException
    public class Rejected(public val problems: List<RemoteOverrideProblem>) : RemoteOverrideException
}
public sealed interface RemoteValue {
    Null; Bool(value: Boolean); Int(value: Long); Double(value: Double); String(value: String); Data(value: ByteArray); Date(value: Instant); Array(value: List<RemoteValue>); Object(value: Map<String, RemoteValue>)
    public fun valueAt(path: String): RemoteValue?
    public companion object { public fun parseJson(bytes: ByteArray): RemoteValue }   // throws RemoteOverrideException.Malformed("not valid JSON")
}
internal fun RemoteValue.box(type: FlagValueType): FlagValueBox?
internal val RemoteValue.shortDescription: String
```

#### D1.1 Swift-style value text, in M1a's `MessageText`

Swift builds the remote, audit and diff messages with `String(value)` and `String(describing:)`, so a double reads `0.1` there, not the JSON writer's `0.10000000000000001`. There is one value-rendering helper in this port: M1a's `internal object MessageText` (`errors/MessageText.kt`, Tasks A4 and A8). This step adds Swift's `description` rendering to it, and points M1c's import messages at the shared copies.

- [ ] **Step 1: Write the failing test.** The expected strings were measured with `swiftc` on this machine against Swift 6.4 Foundation.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextDescriptionTest.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals

class MessageTextDescriptionTest {

    @Test
    fun doublesReadTheWaySwiftDescribesThem() {
        val cases = listOf(
            3.0 to "3.0",
            0.1 to "0.1",
            -0.0 to "-0.0",
            0.0 to "0.0",
            2.5 to "2.5",
            10.0 to "10.0",
            100.0 to "100.0",
            1234567.0 to "1234567.0",
            12345678.9 to "12345678.9",
            4.35 to "4.35",
            0.30000000000000004 to "0.30000000000000004",
            1.0000000000000002 to "1.0000000000000002",
            1e15 to "1000000000000000.0",
            9007199254740990.0 to "9007199254740990.0",
            9007199254740992.0 to "9007199254740992.0",
            9007199254740994.0 to "9.007199254740994e+15",
            9.5e15 to "9.5e+15",
            1e16 to "1e+16",
            -1e16 to "-1e+16",
            1.8014398509481984e16 to "1.8014398509481984e+16",
            1e19 to "1e+19",
            9.223372036854776e18 to "9.223372036854776e+18",
            1e21 to "1e+21",
            1e22 to "1e+22",
            1e100 to "1e+100",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            0.001 to "0.001",
            0.0001 to "0.0001",
            0.00011 to "0.00011",
            9.999e-5 to "9.999e-05",
            9.999999999999999e-05 to "9.999999999999999e-05",
            5e-5 to "5e-05",
            1e-5 to "1e-05",
            -1.5e-7 to "-1.5e-07",
            2.2250738585072014e-308 to "2.2250738585072014e-308",
            Double.MIN_VALUE to "5e-324",
            Double.POSITIVE_INFINITY to "inf",
            Double.NEGATIVE_INFINITY to "-inf",
            Double.NaN to "nan",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, MessageText.double(value), "String(Double) of ${value.toRawBits()}")
        }
    }

    @Test
    fun floatsReadTheWaySwiftDescribesThem() {
        val cases = listOf(
            0.1f to "0.1",
            1f to "1.0",
            3f to "3.0",
            100f to "100.0",
            12345.678f to "12345.678",
            1.0e7f to "10000000.0",
            16777215f to "16777215.0",
            16777216f to "16777216.0",
            16777218f to "1.6777218e+07",
            -16777218f to "-1.6777218e+07",
            33554432f to "3.3554432e+07",
            1e10f to "1e+10",
            Float.MAX_VALUE to "3.4028235e+38",
            0.0001f to "0.0001",
            9.999e-5f to "9.999e-05",
            1e-5f to "1e-05",
            Float.MIN_VALUE to "1e-45",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, MessageText.float(value), "String(Float) of ${value.toRawBits()}")
        }
    }

    @Test
    fun quotedEscapesAndCapsAtFortyCharacters() {
        assertEquals("\"short\"", MessageText.quoted("short"))
        assertEquals("\"line one\\nline two\"", MessageText.quoted("line one\nline two"))
        assertEquals("\"a\\\\b\\tc\\rd\"", MessageText.quoted("a\\b\tc\rd"))
        assertEquals("\"${"A".repeat(40)}\"", MessageText.quoted("A".repeat(40)))
        assertEquals("\"${"A".repeat(40)}…\"", MessageText.quoted("A".repeat(41)))
        assertEquals("\"${"A".repeat(40)}…\"", MessageText.quoted("A".repeat(200)))
    }

    @Test
    fun quotedCountsAnEscapeAsTwoCharacters() {
        // Swift caps the escaped text, so "\n" spends two of the forty.
        assertEquals("\"${"A".repeat(38)}\\n…\"", MessageText.quoted("A".repeat(38) + "\n" + "B"))
    }

    @Test
    fun shortDescribesEveryBoxOnOneLine() {
        assertEquals("\"hi\"", MessageText.short(FlagValueBox.String("hi")))
        assertEquals("true", MessageText.short(FlagValueBox.Bool(true)))
        assertEquals("10000000000", MessageText.short(FlagValueBox.Int(10_000_000_000)))
        assertEquals("3.0", MessageText.short(FlagValueBox.Double(3.0)))
        assertEquals("0.1", MessageText.short(FlagValueBox.Float(0.1f)))
        assertEquals("\"https://a.example/x\"", MessageText.short(FlagValueBox.Url(URI("https://a.example/x"))))
        assertEquals("2026-01-01T00:00:00Z", MessageText.short(FlagValueBox.Date(Instant.ofEpochSecond(1_767_225_600))))
        assertEquals("5 bytes", MessageText.short(FlagValueBox.Data(byteArrayOf(0, 1, 2, 3, 4))))
        assertEquals("an array of 2", MessageText.short(FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)))))
        assertEquals(
            "an object with 1 field(s)",
            MessageText.short(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1)))),
        )
    }

    @Test
    fun diffFieldIsGenerousAndDoesNotEscape() {
        assertEquals("\"line one\nline two\"", MessageText.diffField(FlagValueBox.String("line one\nline two")))
        assertEquals("\"${"B".repeat(80)}\"", MessageText.diffField(FlagValueBox.String("B".repeat(80))))
        assertEquals("\"${"B".repeat(80)}…\"", MessageText.diffField(FlagValueBox.String("B".repeat(81))))
        assertEquals("\"https://staging.a\"", MessageText.diffField(FlagValueBox.Url(URI("https://staging.a"))))
        assertEquals("10.0", MessageText.diffField(FlagValueBox.Double(10.0)))
        assertEquals("443", MessageText.diffField(FlagValueBox.Int(443)))
        assertEquals("false", MessageText.diffField(FlagValueBox.Bool(false)))
        assertEquals("3 bytes", MessageText.diffField(FlagValueBox.Data(byteArrayOf(1, 2, 3))))
        assertEquals("an array of 0", MessageText.diffField(FlagValueBox.Array(emptyList())))
    }

    @Test
    fun caseListNamesStringsBareAndOtherCasesTheWaySwiftReflectsThem() {
        assertEquals("free, pro", MessageText.caseList(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro"))))
        // Swift renders a non-string case with String(describing:), which is "int(1)".
        assertEquals(
            "int(1), int(2), int(3)",
            MessageText.caseList(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2), FlagValueBox.Int(3))),
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.errors.MessageTextDescriptionTest'
```
Expected: `BUILD FAILED`, with compilation failing on `Unresolved reference 'double'` (and `float`, `short`, `diffField`, `caseList`).

- [ ] **Step 3: Write the implementation**

**Modify** M1a's `MessageText.kt` (last written by Task A8). Replace the whole file with this version. `quoted`, `arrayOf`, `objectWith` and `date` are unchanged, so every message M1a to M1c already build is byte-identical; `double`, `float`, `short`, `diffField` and `caseList` are new.

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.values.FlagValueBox
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs

/**
 * The value-rendering helpers behind every error message, ported from `ErrorMessages.swift`.
 *
 * Each problem is a bullet, so a raw newline in a value would put the rest of it in column zero
 * where it reads as another bullet. Length is capped for the same reason.
 *
 * Numbers are rendered the way Swift's `description` renders them (`String(Double)`,
 * `String(describing:)`), not with the JSON writer. That is why a double reads `0.1` in a message
 * while the same value is exported as `0.10000000000000001`.
 */
internal object MessageText {

    private const val MAX_QUOTED_LENGTH = 40
    private const val DIFF_QUOTED_LIMIT = 80
    private const val DOUBLE_DIGITS = 17
    private const val FLOAT_DIGITS = 9
    private const val SMALLEST_DECIMAL_EXPONENT = -3

    // Swift switches to exponent form above the largest integer the significand holds exactly.
    private const val DOUBLE_DECIMAL_LIMIT = 9_007_199_254_740_992.0
    private const val FLOAT_DECIMAL_LIMIT = 16_777_216f

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
        return quote(escaped, MAX_QUOTED_LENGTH)
    }

    fun arrayOf(count: Int): String = "an array of $count"

    fun objectWith(count: Int): String = "an object with $count field(s)"

    fun date(instant: Instant): String = IsoDate.format(instant)

    /** Swift's `String(Double)`: the shortest digits that read back exactly. */
    fun double(value: Double): String = when {
        value.isNaN() -> "nan"
        value.isInfinite() -> if (value > 0.0) "inf" else "-inf"
        value == 0.0 -> if (1.0 / value < 0.0) "-0.0" else "0.0"
        else -> {
            val magnitude = abs(value)
            val digits = shortest(BigDecimal(magnitude), DOUBLE_DIGITS) { it.toDouble() == magnitude }
            sign(value < 0.0) + render(digits, exponential = magnitude > DOUBLE_DECIMAL_LIMIT)
        }
    }

    /** Swift's `String(Float)`: the same rules, with nine digits and a 2^24 threshold. */
    fun float(value: Float): String = when {
        value.isNaN() -> "nan"
        value.isInfinite() -> if (value > 0f) "inf" else "-inf"
        value == 0f -> if (1f / value < 0f) "-0.0" else "0.0"
        else -> {
            val magnitude = abs(value)
            val digits = shortest(BigDecimal(magnitude.toDouble()), FLOAT_DIGITS) { it.toFloat() == magnitude }
            sign(value < 0f) + render(digits, exponential = magnitude > FLOAT_DECIMAL_LIMIT)
        }
    }

    /** Swift's `FlagValueBox.shortMessageDescription`. */
    fun short(box: FlagValueBox): String = when (box) {
        is FlagValueBox.String -> quoted(box.value)
        is FlagValueBox.Bool -> box.value.toString()
        is FlagValueBox.Int -> box.value.toString()
        is FlagValueBox.Double -> double(box.value)
        is FlagValueBox.Float -> float(box.value)
        is FlagValueBox.Url -> quoted(box.value.toString())
        is FlagValueBox.Date -> date(box.value)
        is FlagValueBox.Data -> "${box.value.size} bytes"
        is FlagValueBox.Array -> arrayOf(box.value.size)
        is FlagValueBox.Dictionary -> objectWith(box.value.size)
    }

    /** Swift's `FlagValueBox.diffFieldDescription`: generous, because a diff exists to show values. */
    fun diffField(box: FlagValueBox): String = when (box) {
        is FlagValueBox.String -> quote(box.value, DIFF_QUOTED_LIMIT)
        is FlagValueBox.Url -> quote(box.value.toString(), DIFF_QUOTED_LIMIT)
        is FlagValueBox.Bool -> box.value.toString()
        is FlagValueBox.Int -> box.value.toString()
        is FlagValueBox.Double -> double(box.value)
        is FlagValueBox.Float -> float(box.value)
        is FlagValueBox.Date -> date(box.value)
        is FlagValueBox.Data -> "${box.value.size} bytes"
        is FlagValueBox.Array, is FlagValueBox.Dictionary -> short(box)
    }

    /**
     * Swift's `caseListDescription`. A string case is written bare. Any other case goes
     * through `String(describing:)`, which for the int cases an enum can carry is `int(1)`.
     */
    fun caseList(cases: List<FlagValueBox>): String = cases.joinToString(", ") { box ->
        when (box) {
            is FlagValueBox.String -> box.value
            is FlagValueBox.Int -> "int(${box.value})"
            // Cases only ever come from string- and int-backed enums.
            else -> short(box)
        }
    }

    /** Caps by code point, so a surrogate pair is never split. Swift counts grapheme clusters. */
    private fun quote(text: String, limit: Int): String {
        if (text.codePointCount(0, text.length) <= limit) return "\"$text\""
        return "\"${text.substring(0, text.offsetByCodePoints(0, limit))}…\""
    }

    private fun shortest(exact: BigDecimal, maxDigits: Int, roundTrips: (BigDecimal) -> Boolean): BigDecimal {
        for (digits in 1 until maxDigits) {
            val nearest = exact.round(MathContext(digits, RoundingMode.HALF_EVEN))
            if (roundTrips(nearest)) return nearest
            // Beside a power of two the rounding interval is lopsided, so the farther
            // neighbour can be the one that reads back.
            val down = exact.round(MathContext(digits, RoundingMode.DOWN))
            val other = if (nearest.compareTo(down) == 0) exact.round(MathContext(digits, RoundingMode.UP)) else down
            if (roundTrips(other)) return other
        }
        return exact.round(MathContext(maxDigits, RoundingMode.HALF_EVEN))
    }

    private fun render(number: BigDecimal, exponential: Boolean): String {
        val stripped = number.stripTrailingZeros()
        val digits = stripped.unscaledValue().toString()
        // The value is 0.<digits> × 10^point.
        val point = digits.length - stripped.scale()
        return if (exponential || point < SMALLEST_DECIMAL_EXPONENT) {
            exponentialForm(digits, point - 1)
        } else {
            decimalForm(digits, point)
        }
    }

    private fun decimalForm(digits: String, point: Int): String = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length) + ".0"
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }

    private fun exponentialForm(digits: String, exponent: Int): String {
        val mantissa = if (digits.length == 1) digits else digits.take(1) + "." + digits.drop(1)
        val sign = if (exponent < 0) "-" else "+"
        return mantissa + "e" + sign + abs(exponent).toString().padStart(2, '0')
    }

    private fun sign(negative: Boolean): String = if (negative) "-" else ""
}
```

**Modify** M1c Task C4's `FlagPayload.kt`, so import messages use these shared helpers instead of the private copies C4 wrote before they existed. `MessageText.short` is the exact port of Swift's `shortMessageDescription`; C4's copy used Kotlin's `toString()` for doubles. Three edits:

In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt`, replace

```kotlin
            return FlagImportProblem(key, FlagImportProblem.Kind.UnknownCase, permitted.caseListDescription(), raw.importDescription())
```

with

```kotlin
            return FlagImportProblem(key, FlagImportProblem.Kind.UnknownCase, MessageText.caseList(permitted), raw.importDescription())
```

In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt`, replace

```kotlin
    val found = if (duplicate == null) raw.importDescription() else "two with ${duplicate.shortDescription()}"
```

with

```kotlin
    val found = if (duplicate == null) raw.importDescription() else "two with ${MessageText.short(duplicate)}"
```

In `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt`, replace

```kotlin
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
```

with

```kotlin
/**
 * NSNumber's description of a double, which is C's `%.16g` with trailing zeros removed.
```

- [ ] **Step 4: Run it and confirm it passes.** Run the whole core suite, because M1c's import messages now go through the shared helpers.
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`. `MessageTextDescriptionTest` passes 7 tests, and every M1a to M1c message test still passes.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/MessageTextDescriptionTest.kt
git commit -m "Render numbers and quoted values the way Swift describes them" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D1.2 `RemoteOverrideProblem` and `RemoteOverrideException`

- [ ] **Step 1: Write the failing test**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/RemoteOverrideExceptionTest.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class RemoteOverrideExceptionTest {

    private val closing =
        "A remote payload is all or nothing, so one bad value leaves every other flag reading whatever sits below this source."

    @Test
    fun aMalformedPayloadSaysWhy() {
        assertEquals(
            "The remote payload could not be read: not valid JSON.",
            RemoteOverrideException.Malformed("not valid JSON").message,
        )
    }

    @Test
    fun aRejectionWithNoProblemsIsStillASentence() {
        assertEquals("Nothing was applied, and no problem was recorded.", RemoteOverrideException.Rejected(emptyList()).message)
    }

    @Test
    fun oneProblemIsSingular() {
        val problem =
            RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding", Kind.TypeMismatch, "bool", "\"yes\"")
        assertEquals(
            "Nothing was applied. 1 problem:\n" +
                "  • 'featureToggles.onboarding' → new-onboarding: expected bool, got \"yes\"\n" +
                closing,
            RemoteOverrideException.Rejected(listOf(problem)).message,
        )
    }

    @Test
    fun everyKindFallsBackToWordsWhenItHasNoValues() {
        val problems = listOf(
            RemoteOverrideProblem(FlagKey("a"), "x.a", Kind.TypeMismatch),
            RemoteOverrideProblem(FlagKey("b"), "x.b", Kind.UnknownCase),
            RemoteOverrideProblem(FlagKey("new-onbaording"), "new-onbaording", Kind.UnknownKey, found = "true"),
        )
        assertEquals(
            "Nothing was applied. 3 problems:\n" +
                "  • 'x.a' → a: expected another type\n" +
                "  • 'x.b' → b: that value is not one of this flag's cases\n" +
                "  • new-onbaording: no flag has this key, so the mapper that produced it is wrong\n" +
                closing,
            RemoteOverrideException.Rejected(problems).message,
        )
    }

    @Test
    fun anUnknownCaseNamesTheCasesThatWouldHaveWorked() {
        assertEquals(
            "'config.tier' → checkout-tier: \"gold\" is not one of free, pro",
            RemoteOverrideProblem(FlagKey("checkout-tier"), "config.tier", Kind.UnknownCase, "free, pro", "\"gold\"").toString(),
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.errors.RemoteOverrideExceptionTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'RemoteOverrideProblem'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/RemoteOverrideException.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey

/**
 * Something wrong with one value in a remote payload.
 *
 * `toString()` is the line a rejection report shows for it. Switch on [kind]; [expected]
 * and [found] are there for the message.
 */
public data class RemoteOverrideProblem(
    public val key: FlagKey,
    public val remoteKey: String,
    public val kind: Kind,
    /** What the flag would have accepted: a type name, or an enum's cases. */
    public val expected: String? = null,
    /** What the payload actually held, short enough to read in one line. */
    public val found: String? = null,
) {

    public enum class Kind {
        /** The value is not of the flag's declared type. */
        TypeMismatch,

        /** The value is not one of the enum's cases. */
        UnknownCase,

        /**
         * The mapper produced a key no flag has. `DotPathMapper` cannot cause this, so it
         * points at a custom mapper's bug rather than at anything the backend sent.
         */
        UnknownKey,
    }

    override fun toString(): String = when (kind) {
        Kind.TypeMismatch ->
            "'$remoteKey' → $key: expected ${expected ?: "another type"}" + (found?.let { ", got $it" } ?: "")
        Kind.UnknownCase ->
            "'$remoteKey' → $key: ${found ?: "that value"} is not one of " + (expected ?: "this flag's cases")
        Kind.UnknownKey ->
            "$key: no flag has this key, so the mapper that produced it is wrong"
    }
}

/** Why a remote payload was not applied. Nothing is ever half-applied. */
public sealed class RemoteOverrideException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /** The bytes could not be read as a payload; [reason] says why. */
    public class Malformed(public val reason: String, cause: Throwable? = null) :
        RemoteOverrideException("The remote payload could not be read: $reason.", cause)

    /** Every problem found, and nothing applied. */
    public class Rejected(public val problems: List<RemoteOverrideProblem>) :
        RemoteOverrideException(rejectionMessage(problems))
}

private fun rejectionMessage(problems: List<RemoteOverrideProblem>): String {
    if (problems.isEmpty()) return "Nothing was applied, and no problem was recorded."
    val noun = if (problems.size == 1) "problem" else "problems"
    val list = problems.joinToString("\n") { "  • $it" }
    return "Nothing was applied. ${problems.size} $noun:\n$list\n" +
        "A remote payload is all or nothing, so one bad value leaves every other " +
        "flag reading whatever sits below this source."
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.errors.RemoteOverrideExceptionTest'
```
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/RemoteOverrideException.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/RemoteOverrideExceptionTest.kt
git commit -m "Add RemoteOverrideException and RemoteOverrideProblem with the Swift prose" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D1.3 `RemoteValue`

- [ ] **Step 1: Write the failing test.** This ports `RemoteValueTests.swift`. The PLIST deserialising test is skipped, and native dates and data are built directly.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteValueTest.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RemoteValueTest {

    private fun decoded(json: String): RemoteValue = RemoteValue.parseJson(json.toByteArray())

    // Deserialising

    @Test
    fun booleansAreNotMistakenForNumbers() {
        val value = decoded("""{"a": true, "b": 1}""")
        assertEquals(RemoteValue.Bool(true), value.valueAt("a"))
        assertEquals(RemoteValue.Int(1), value.valueAt("b"))
    }

    @Test
    fun wholeAndFractionalNumbersAreDistinguished() {
        val value = decoded("""{"a": 1, "b": 1.5}""")
        assertEquals(RemoteValue.Int(1), value.valueAt("a"))
        assertEquals(RemoteValue.Double(1.5), value.valueAt("b"))
    }

    @Test
    fun nullBecomesNull() {
        assertEquals(RemoteValue.Null, decoded("""{"a": null}""").valueAt("a"))
    }

    @Test
    fun nestingIsPreserved() {
        assertEquals(
            RemoteValue.Array(listOf(RemoteValue.Int(1), RemoteValue.Int(2))),
            decoded("""{"a": {"b": [1, 2]}}""").valueAt("a.b"),
        )
    }

    @Test
    fun aWholeValuedRealArrivesAsAnInteger() {
        // JSONSerialization hands 2.0 and 1e2 over as integers, so iOS sees .int too.
        val value = decoded("""{"a": 2.0, "b": 1e2}""")
        assertEquals(RemoteValue.Int(2), value.valueAt("a"))
        assertEquals(RemoteValue.Int(100), value.valueAt("b"))
    }

    @Test
    fun anIntegerBeyondLongArrivesAsADouble() {
        assertEquals(RemoteValue.Double(1.0E19), decoded("""{"a": 10000000000000000000}""").valueAt("a"))
    }

    @Test
    fun malformedBytesAreRejectedAsMalformed() {
        val error = assertFailsWith<RemoteOverrideException.Malformed> { decoded("not json") }
        assertEquals("The remote payload could not be read: not valid JSON.", error.message)
    }

    @Test
    fun aTopLevelScalarIsMalformed() {
        assertFailsWith<RemoteOverrideException.Malformed> { decoded("true") }
    }

    // Paths

    @Test
    fun anEmptyPathReturnsTheRoot() {
        val value = decoded("""{"a": 1}""")
        assertEquals(value, value.valueAt(""))
    }

    @Test
    fun aMissingKeyReturnsNothing() {
        assertNull(decoded("""{"a": 1}""").valueAt("b"))
        assertNull(decoded("""{"a": 1}""").valueAt("a.b"))
    }

    @Test
    fun anIndexPastTheEndReturnsNothing() {
        assertNull(decoded("""{"a": [1]}""").valueAt("a.5"))
    }

    @Test
    fun aNegativeIndexReturnsNothing() {
        assertNull(decoded("""{"a": [1]}""").valueAt("a.-1"))
    }

    @Test
    fun aNonNumericComponentOnAnArrayReturnsNothing() {
        assertNull(decoded("""{"a": [1]}""").valueAt("a.first"))
    }

    @Test
    fun descendingIntoAScalarReturnsNothing() {
        assertNull(decoded("""{"a": 1}""").valueAt("a.b.c"))
    }

    @Test
    fun deepPathsResolve() {
        assertEquals(RemoteValue.Bool(true), decoded("""{"a": {"b": [{"c": {"d": true}}]}}""").valueAt("a.b.0.c.d"))
    }

    @Test
    fun emptySegmentsAreSkippedTheWaySwiftSplitSkipsThem() {
        val value = decoded("""{"a": {"b": 1}}""")
        assertEquals(RemoteValue.Int(1), value.valueAt("a..b"))
        assertEquals(RemoteValue.Int(1), value.valueAt(".a.b."))
    }

    @Test
    fun aSignedIndexIsReadTheWaySwiftIntParsesIt() {
        assertEquals(RemoteValue.Int(2), decoded("""{"a": [1, 2]}""").valueAt("a.+1"))
    }

    @Test
    fun aMemberNameContainingADotCannotBeReachedByAPath() {
        // Review Focus 1: a flat, Firebase-style key is split at its dots like any path.
        assertNull(decoded("""{"featureToggles.onboarding.v2": true}""").valueAt("featureToggles.onboarding.v2"))
    }

    // Boxing

    @Test
    fun stringsBecomeDatesDataAndUrlsWhereDeclared() {
        assertEquals(FlagValueBox.Date(Instant.EPOCH), RemoteValue.String("1970-01-01T00:00:00Z").box(FlagValueType.Date))
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), RemoteValue.String("AQI=").box(FlagValueType.Data))
        assertEquals(FlagValueBox.Url(URI("https://a.example")), RemoteValue.String("https://a.example").box(FlagValueType.Url))
    }

    @Test
    fun anUnparseableStringIsRejectedForEachStructuredType() {
        assertNull(RemoteValue.String("not a date").box(FlagValueType.Date))
        assertNull(RemoteValue.String("!!!not base64!!!").box(FlagValueType.Data))
    }

    @Test
    fun aUrlNeedingPercentEncodingBoxesTheWayIosDoes() {
        // Review Focus 3. These are iOS 17+ URL(string:) results, measured with swiftc.
        assertEquals(
            FlagValueBox.Url(URI("https://example.com/a%20b")),
            RemoteValue.String("https://example.com/a b").box(FlagValueType.Url),
        )
        assertEquals(
            FlagValueBox.Url(URI("https://example.com/%C3%BC")),
            RemoteValue.String("https://example.com/ü").box(FlagValueType.Url),
        )
        assertNull(RemoteValue.String("https://exa mple.com").box(FlagValueType.Url))
        assertNull(RemoteValue.String("").box(FlagValueType.Url))
    }

    @Test
    fun nullNeverBoxes() {
        for (type in listOf(FlagValueType.Bool, FlagValueType.Int, FlagValueType.String, FlagValueType.Array(FlagValueType.Int))) {
            assertNull(RemoteValue.Null.box(type))
        }
    }

    @Test
    fun wideningIsExactAndOnlyUpwards() {
        assertEquals(FlagValueBox.Double(1.0), RemoteValue.Int(1).box(FlagValueType.Double))
        assertEquals(FlagValueBox.Float(1f), RemoteValue.Int(1).box(FlagValueType.Float))
        assertNull(RemoteValue.Double(1.5).box(FlagValueType.Int))
        assertNull(RemoteValue.Double(1.0).box(FlagValueType.Int))
    }

    @Test
    fun aDoubleNarrowsToAFloatFlag() {
        assertEquals(FlagValueBox.Float(0.1f), RemoteValue.Double(0.1).box(FlagValueType.Float))
    }

    @Test
    fun booleansAndNumbersNeverConvertIntoEachOther() {
        assertNull(RemoteValue.Int(1).box(FlagValueType.Bool))
        assertNull(RemoteValue.Bool(true).box(FlagValueType.Int))
        assertNull(RemoteValue.String("true").box(FlagValueType.Bool))
    }

    @Test
    fun collectionsBoxElementByElement() {
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))),
            RemoteValue.Array(listOf(RemoteValue.Int(1), RemoteValue.Int(2))).box(FlagValueType.Array(FlagValueType.Int)),
        )
        assertNull(RemoteValue.Array(listOf(RemoteValue.Int(1), RemoteValue.String("x"))).box(FlagValueType.Array(FlagValueType.Int)))
    }

    @Test
    fun anEmptyCollectionBoxesAsAnyElementType() {
        assertEquals(FlagValueBox.Array(emptyList()), RemoteValue.Array(emptyList()).box(FlagValueType.Array(FlagValueType.Date)))
        assertEquals(
            FlagValueBox.Dictionary(emptyMap()),
            RemoteValue.Object(emptyMap()).box(FlagValueType.Dictionary(FlagValueType.Url)),
        )
    }

    @Test
    fun collectionsWidenTheirElementsToo() {
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.Double(1.0))),
            RemoteValue.Array(listOf(RemoteValue.Int(1))).box(FlagValueType.Array(FlagValueType.Double)),
        )
        assertEquals(
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Double(1.0))),
            RemoteValue.Object(mapOf("a" to RemoteValue.Int(1))).box(FlagValueType.Dictionary(FlagValueType.Double)),
        )
    }

    @Test
    fun nativeDatesAndDataBoxAsThemselves() {
        val moment = Instant.ofEpochSecond(1_000)
        assertEquals(FlagValueBox.Date(moment), RemoteValue.Date(moment).box(FlagValueType.Date))
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals(FlagValueBox.Data(bytes), RemoteValue.Data(bytes).box(FlagValueType.Data))
    }

    @Test
    fun nativeDatesAndDataDoNotSatisfyOtherTypes() {
        val moment = RemoteValue.Date(Instant.ofEpochSecond(1_000))
        for (type in listOf(FlagValueType.String, FlagValueType.Int, FlagValueType.Double, FlagValueType.Bool, FlagValueType.Data, FlagValueType.Url)) {
            assertNull(moment.box(type), "a date should not box as $type")
        }
        val bytes = RemoteValue.Data(byteArrayOf(1))
        for (type in listOf(FlagValueType.String, FlagValueType.Int, FlagValueType.Double, FlagValueType.Bool, FlagValueType.Date, FlagValueType.Url)) {
            assertNull(bytes.box(type), "data should not box as $type")
        }
    }

    // Describing

    @Test
    fun shortDescriptionsReadTheWaySwiftPrintsThem() {
        assertEquals("null", RemoteValue.Null.shortDescription)
        assertEquals("true", RemoteValue.Bool(true).shortDescription)
        assertEquals("7", RemoteValue.Int(7).shortDescription)
        assertEquals("1.5", RemoteValue.Double(1.5).shortDescription)
        assertEquals("1e+19", RemoteValue.Double(1.0E19).shortDescription)
        assertEquals("\"short\"", RemoteValue.String("short").shortDescription)
        assertEquals("3 bytes", RemoteValue.Data(byteArrayOf(1, 2, 3)).shortDescription)
        assertEquals("1970-01-01T00:16:40Z", RemoteValue.Date(Instant.ofEpochSecond(1_000)).shortDescription)
        assertEquals("an array of 2", RemoteValue.Array(listOf(RemoteValue.Null, RemoteValue.Null)).shortDescription)
        assertEquals("an object with 1 field(s)", RemoteValue.Object(mapOf("a" to RemoteValue.Null)).shortDescription)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.RemoteValueTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'RemoteValue'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteValue.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.toBox
import java.time.Instant

/**
 * A decoded remote payload. Mappers work against this tree rather than raw bytes, so a
 * custom mapper is ordinary, testable Kotlin.
 *
 * [Data] and [Date] never come out of JSON; they exist for payloads a caller builds by hand.
 */
public sealed interface RemoteValue {

    public data object Null : RemoteValue

    public data class Bool(public val value: Boolean) : RemoteValue

    public data class Int(public val value: Long) : RemoteValue

    public data class Double(public val value: kotlin.Double) : RemoteValue

    public data class String(public val value: kotlin.String) : RemoteValue

    public class Data(public val value: ByteArray) : RemoteValue {
        override fun equals(other: Any?): Boolean = other is Data && value.contentEquals(other.value)

        override fun hashCode(): kotlin.Int = value.contentHashCode()

        override fun toString(): kotlin.String = "Data(${value.size} bytes)"
    }

    public data class Date(public val value: Instant) : RemoteValue

    public data class Array(public val value: List<RemoteValue>) : RemoteValue

    public data class Object(public val value: Map<kotlin.String, RemoteValue>) : RemoteValue

    /**
     * The value at a dot path such as `featureToggles.checkout.v2`, or `null` when nothing
     * is there. A number indexes into an array, so `experiments.0.enabled` works. Empty
     * segments are skipped, so `""` is the root.
     */
    public fun valueAt(path: kotlin.String): RemoteValue? {
        var current: RemoteValue = this
        for (component in path.split('.')) {
            if (component.isEmpty()) continue
            current = when (val node = current) {
                is Object -> node.value[component]
                is Array -> component.toIntOrNull()?.let { node.value.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current
    }

    public companion object {

        /**
         * Reads a JSON payload. JSON can only produce null, booleans, integers, doubles,
         * strings, arrays and objects.
         *
         * @throws RemoteOverrideException.Malformed when the bytes are not a JSON object or array.
         */
        public fun parseJson(bytes: ByteArray): RemoteValue {
            val json = try {
                JsonReader.parse(bytes)
            } catch (error: JsonSyntaxException) {
                throw RemoteOverrideException.Malformed("not valid JSON", error)
            }
            return json.toRemoteValue()
        }
    }
}

private fun JsonValue.toRemoteValue(): RemoteValue = when (this) {
    JsonValue.Null -> RemoteValue.Null
    is JsonValue.Bool -> RemoteValue.Bool(value)
    is JsonValue.Integer -> RemoteValue.Int(value)
    is JsonValue.Real -> RemoteValue.Double(value)
    is JsonValue.Text -> RemoteValue.String(value)
    is JsonValue.Array -> RemoteValue.Array(items.map { it.toRemoteValue() })
    is JsonValue.Object -> RemoteValue.Object(fields.mapValues { (_, child) -> child.toRemoteValue() })
}

/**
 * This value as a flag of [type], or `null` when it does not fit.
 *
 * The rule is strict, with one allowance: a whole number satisfies a double or float
 * flag, and a double narrows to a float. A string becomes data, a date or a URL exactly
 * as an imported document's string does.
 */
internal fun RemoteValue.box(type: FlagValueType): FlagValueBox? = when (this) {
    RemoteValue.Null -> null
    is RemoteValue.Bool -> if (type == FlagValueType.Bool) FlagValueBox.Bool(value) else null
    is RemoteValue.Int -> when (type) {
        FlagValueType.Int -> FlagValueBox.Int(value)
        FlagValueType.Double -> FlagValueBox.Double(value.toDouble())
        FlagValueType.Float -> FlagValueBox.Float(value.toFloat())
        else -> null
    }
    is RemoteValue.Double -> when (type) {
        FlagValueType.Double -> FlagValueBox.Double(value)
        FlagValueType.Float -> FlagValueBox.Float(value.toFloat())
        else -> null
    }
    is RemoteValue.String -> when (type) {
        FlagValueType.String -> FlagValueBox.String(value)
        FlagValueType.Data, FlagValueType.Date, FlagValueType.Url -> JsonValue.Text(value).toBox(type)
        else -> null
    }
    is RemoteValue.Data -> if (type == FlagValueType.Data) FlagValueBox.Data(value) else null
    is RemoteValue.Date -> if (type == FlagValueType.Date) FlagValueBox.Date(value) else null
    is RemoteValue.Array -> (type as? FlagValueType.Array)?.let { value.boxEach(it.element) }
    is RemoteValue.Object -> (type as? FlagValueType.Dictionary)?.let { value.boxEach(it.value) }
}

private fun List<RemoteValue>.boxEach(element: FlagValueType): FlagValueBox? {
    val boxes = ArrayList<FlagValueBox>(size)
    for (item in this) boxes += item.box(element) ?: return null
    return FlagValueBox.Array(boxes)
}

private fun Map<String, RemoteValue>.boxEach(valueType: FlagValueType): FlagValueBox? {
    val boxes = LinkedHashMap<String, FlagValueBox>(size)
    for ((key, item) in this) boxes[key] = item.box(valueType) ?: return null
    return FlagValueBox.Dictionary(boxes)
}

/** This value in a few characters, for a one-line message. Strings keep their quotes. */
internal val RemoteValue.shortDescription: String
    get() = when (this) {
        RemoteValue.Null -> "null"
        is RemoteValue.Bool -> value.toString()
        is RemoteValue.Int -> value.toString()
        is RemoteValue.Double -> MessageText.double(value)
        is RemoteValue.String -> MessageText.quoted(value)
        is RemoteValue.Data -> "${value.size} bytes"
        is RemoteValue.Date -> IsoDate.format(value)
        is RemoteValue.Array -> "an array of ${value.size}"
        is RemoteValue.Object -> "an object with ${value.size} field(s)"
    }
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.RemoteValueTest'
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`, 31 tests passed. `aUrlNeedingPercentEncodingBoxesTheWayIosDoes` passes with no URL code here: `box` hands URL text to M1a's `JsonValue.toBox`, whose `parseUrl` (Task A9) already percent-encodes it as iOS 17+ `URL(string:)` does. If anything fails, fix it in `RemoteValue.kt`.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteValue.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteValueTest.kt
git commit -m "Add RemoteValue with dot paths and strict boxing" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task D2: Mappers, `mapRemote` validation, `RemoteOverrideSource`, remote goldens

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideMapper.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteMapping.kt`. D2.1 writes it, then D2.2 replaces it whole.
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSource.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSourceTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/FlagRecordRemoteTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteGoldenFixtureTest.kt`

**Interfaces:**
- Consumes:
  - `FlagSchema` (`flags: List<FlagSchema.Entry>`, `of(spec, keyEncoding)`, data-class `copy`). `FlagSchema.Entry` has `key`, `valueType`, `cases`, `remoteKey`, `recordShape`, `description`.
  - `FlagRecordField` (`name`, `type`, `cases`, `defaultValue`, `fields`, `isKey`, `isOptional`, `payloadName`, data-class `copy`).
  - `RecordJson.encode(records)` and `RecordJson.decode(text, shape)`.
  - `FlagValueBox.storageIdentity`.
  - `FlagValueSource` (`sourceName`, `box(key, type)`, `changes: Flow<FlagChange>`) and `FlagChange.All`.
  - `FlagContainerSpec<*>` and `KeyEncoding.Kebabcase`.
  - `FlagPole(spec, sources, keyEncoding)`: `flags`, `resolution(ref)`, `resolution(key, type)`, `setOverride(ref, value)`.
  - `SnapshotSource(name)` with `setBox(key, box)`.
  - `FlagException.NoMutableSource`.
  - `GoldenFlagsSpec`.
  - D1's `RemoteValue`, `box`, `shortDescription`, `MessageText` (with its D1.1 additions), `RemoteOverrideProblem` and `RemoteOverrideException`.
- Produces:
```kotlin
public fun interface RemoteOverrideMapper { public fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> }
public object DotPathMapper : RemoteOverrideMapper                       // skips flags with no remoteKey, missing paths, and RemoteValue.Null
internal class RemoteMapping(val boxes: Map<FlagKey, FlagValueBox>, val problems: List<RemoteOverrideProblem>)
internal fun FlagSchema.mapRemote(payload: RemoteValue, mapper: RemoteOverrideMapper): RemoteMapping
internal fun RemoteValue.recordBox(shape: List<FlagRecordField>): FlagValueBox?
internal fun RemoteValue.duplicateRecordKey(shape: List<FlagRecordField>): FlagValueBox?
public data class RemoteApplyResult(val appliedKeys: List<FlagKey>, val absentKeys: List<FlagKey>)   // applied sorted by rawValue; absent in schema order
public class RemoteOverrideSource(public val schema: FlagSchema, public val mapper: RemoteOverrideMapper = DotPathMapper, override val sourceName: String = "Remote") : FlagValueSource {
    public constructor(spec: FlagContainerSpec<*>, keyEncoding: KeyEncoding = KeyEncoding.Kebabcase, mapper: RemoteOverrideMapper = DotPathMapper, sourceName: String = "Remote")
    public fun apply(json: ByteArray): RemoteApplyResult      // throws RemoteOverrideException.Malformed / .Rejected (problems sorted by key)
    public fun apply(payload: RemoteValue): RemoteApplyResult // replaces everything, emits FlagChange.All
    public fun clear()                                        // emits FlagChange.All
}
```

#### D2.1 Dot paths, scalar validation and the source

- [ ] **Step 1: Write the failing test.** It ports `RemoteOverrideTests.swift`, except the PLIST tests. It also ports the remote cases from `AdversarialRegressionTests`, `DuplicateKeyTests` and `FlagPoleEdgeCaseTests`, and adds the Review Focus 2 pin.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSourceTest.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal enum class RemoteDemoTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

internal enum class RemoteLevel(override val flagRawValue: Long) : FlagIntEnum { One(1), Two(2), Three(3) }

@FlagContainer
internal interface RemoteDemoFlags {
    @Flag(description = "New onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Maximum items")
    val maxItems: Int get() = 10

    @FlagGroup(description = "Checkout")
    val checkout: RemoteDemoCheckoutFlags
}

@FlagContainer
internal interface RemoteDemoCheckoutFlags {
    @Flag(description = "Apple Pay", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = false

    @FlagGroup(description = "Express")
    val express: RemoteDemoExpressFlags

    @Flag(description = "Tier")
    val tier: RemoteDemoTier get() = RemoteDemoTier.Free
}

@FlagContainer
internal interface RemoteDemoExpressFlags {
    @Flag(description = "One tap")
    val oneTap: Boolean get() = false
}

@FlagContainer
internal interface IndexedFlags {
    @Flag(description = "Second experiment", remoteKey = "experiments.1.enabled")
    val secondExperiment: Boolean get() = false
}

@FlagContainer
internal interface NumericFlags {
    @Flag(description = "Count", remoteKey = "cfg.count")
    val count: Int get() = 0

    @Flag(description = "Big count", remoteKey = "cfg.bigCount")
    val bigCount: Long get() = 0L

    @Flag(description = "Ratio", remoteKey = "cfg.ratio")
    val ratio: Double get() = 0.0
}

@FlagContainer
internal interface EnumFlags {
    @Flag(description = "Tier", remoteKey = "cfg.tier")
    val tier: RemoteDemoTier get() = RemoteDemoTier.Free

    @Flag(description = "Level", remoteKey = "cfg.level")
    val level: RemoteLevel get() = RemoteLevel.One
}

/** A backend that sends a list of records rather than a nested object. */
private object RecordListMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val records = payload.valueAt("flags") as? RemoteValue.Array ?: return emptyMap()
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (record in records.value) {
            val name = record.valueAt("name") as? RemoteValue.String
            val value = record.valueAt("value")
            if (name != null && value != null) result[FlagKey(name.value)] = value
        }
        return result
    }
}

class RemoteOverrideSourceTest {

    private fun json(text: String): ByteArray = text.toByteArray()

    private fun makeSource() = RemoteOverrideSource(RemoteDemoFlagsSpec)

    // Dot paths

    @Test
    fun resolvesNestedDotPaths() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun resolvesArrayIndicesInAPath() {
        val source = RemoteOverrideSource(IndexedFlagsSpec)
        source.apply(json("""{"experiments": [{"enabled": false}, {"enabled": true}]}"""))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("second-experiment"), FlagValueType.Bool))
    }

    @Test
    fun flagsWithoutARemoteKeyAreNeverRemotelyOverridable() {
        val source = makeSource()
        source.apply(json("""{"max-items": 99, "maxItems": 99}"""))
        assertNull(source.box(FlagKey("max-items"), FlagValueType.Int))
    }

    @Test
    fun absentPathsAreReportedAndLeftAlone() {
        val source = makeSource()
        val result = source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        assertEquals(listOf(FlagKey("new-onboarding")), result.appliedKeys)
        assertEquals(listOf(FlagKey("checkout.apple-pay")), result.absentKeys)
        assertNull(source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun appliedKeysAreSortedAndAbsentKeysFollowTheSchema() {
        val source = makeSource()
        assertEquals(listOf(FlagKey("new-onboarding"), FlagKey("checkout.apple-pay")), source.apply(json("{}")).absentKeys)
        val both = source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}, "checkout": {"applePay": true}}}"""))
        assertEquals(listOf(FlagKey("checkout.apple-pay"), FlagKey("new-onboarding")), both.appliedKeys)
    }

    @Test
    fun aNullIsTreatedAsAbsent() {
        val result = makeSource().apply(json("""{"featureToggles": {"onboarding": {"v2": null}}}"""))
        assertEquals(emptyList(), result.appliedKeys)
        assertEquals(listOf(FlagKey("new-onboarding"), FlagKey("checkout.apple-pay")), result.absentKeys)
    }

    @Test
    fun applyingANewPayloadReplacesTheOldOne() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        source.apply(json("""{"featureToggles": {"checkout": {"applePay": true}}}"""))
        assertNull(source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun clearingRemovesEverything() {
        val source = makeSource()
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        source.clear()
        assertNull(source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    // Strict typing

    @Test
    fun rejectsAStringForABooleanAndAppliesNothing() {
        val source = makeSource()
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            source.apply(json("""{"featureToggles": {"onboarding": {"v2": "true"}, "checkout": {"applePay": true}}}"""))
        }
        assertEquals(
            listOf(
                RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding.v2", Kind.TypeMismatch, "bool", "\"true\""),
            ),
            error.problems,
        )
        assertNull(source.box(FlagKey("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun rejectsANumberForABoolean() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            makeSource().apply(json("""{"featureToggles": {"onboarding": {"v2": 1}}}"""))
        }
    }

    @Test
    fun acceptsAWholeNumberForADoubleFlag() {
        val source = RemoteOverrideSource(NumericFlagsSpec)
        source.apply(json("""{"cfg": {"ratio": 1}}"""))
        assertEquals(FlagValueBox.Double(1.0), source.box(FlagKey("ratio"), FlagValueType.Double))
    }

    @Test
    fun rejectsAFractionalNumberForAnIntegerFlag() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NumericFlagsSpec).apply(json("""{"cfg": {"count": 1.5}}"""))
        }
    }

    @Test
    fun rejectsAValueOutsideAnEnumsCases() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(EnumFlagsSpec).apply(json("""{"cfg": {"tier": "enterprise"}}"""))
        }
        assertEquals(listOf(Kind.UnknownCase), error.problems.map { it.kind })
    }

    @Test
    fun acceptsAValidEnumCase() {
        val source = RemoteOverrideSource(EnumFlagsSpec)
        source.apply(json("""{"cfg": {"tier": "pro"}}"""))
        assertEquals(FlagValueBox.String("pro"), source.box(FlagKey("tier"), FlagValueType.String))
    }

    @Test
    fun anIntEnumsCasesAreListedTheWaySwiftDescribesThem() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(EnumFlagsSpec).apply(json("""{"cfg": {"level": 9}}"""))
        }
        assertEquals("'cfg.level' → level: 9 is not one of int(1), int(2), int(3)", error.problems.single().toString())
    }

    @Test
    fun reportsEveryProblemAtOnce() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NumericFlagsSpec).apply(json("""{"cfg": {"count": 1.5, "ratio": "lots"}}"""))
        }
        assertEquals(setOf(FlagKey("count"), FlagKey("ratio")), error.problems.map { it.key }.toSet())
    }

    @Test
    fun problemsAreSortedByFlagKey() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            makeSource().apply(json("""{"featureToggles": {"onboarding": {"v2": 1}, "checkout": {"applePay": "x"}}}"""))
        }
        assertEquals(listOf(FlagKey("checkout.apple-pay"), FlagKey("new-onboarding")), error.problems.map { it.key })
    }

    @Test
    fun rejectsMalformedData() {
        assertFailsWith<RemoteOverrideException.Malformed> { makeSource().apply(json("not json")) }
    }

    @Test
    fun anIntBeyondKotlinIntRangeIsAcceptedButAnIntFlagReadsItsDefault() {
        // Review Focus 2. iOS's Int is 64-bit, so the schema type `int` admits 3_000_000_000
        // and apply accepts it. A Kotlin Int flag cannot hold it, so it reads its default
        // while its resolution still names the source. Declare Long for such values.
        val remote = RemoteOverrideSource(NumericFlagsSpec)
        remote.apply(json("""{"cfg": {"count": 3000000000, "bigCount": 3000000000}}"""))
        val pole = FlagPole(NumericFlagsSpec, listOf(remote))

        assertEquals(FlagValueBox.Int(3_000_000_000), remote.box(FlagKey("count"), FlagValueType.Int))
        assertEquals(0, pole.flags.count)
        assertEquals("Remote", pole.resolution(FlagKey("count"), FlagValueType.Int).sourceName)
        assertEquals(3_000_000_000L, pole.flags.bigCount)
    }

    // Custom mappers

    @Test
    fun aCustomMapperCanReshapeAnAwkwardPayload() {
        val source = RemoteOverrideSource(RemoteDemoFlagsSpec, mapper = RecordListMapper)
        source.apply(json("""{"flags": [{"name": "new-onboarding", "value": true}]}"""))
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun aMapperKeyNoFlagHasIsReportedNotIgnored() {
        val typo = RemoteOverrideMapper { _, _ -> mapOf(FlagKey("rat1o") to RemoteValue.Double(2.5)) }
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NumericFlagsSpec, mapper = typo).apply(json("""{"a": 1}"""))
        }
        assertEquals(listOf(RemoteOverrideProblem(FlagKey("rat1o"), "rat1o", Kind.UnknownKey, found = "2.5")), error.problems)
    }

    @Test
    fun theBuiltInMapperCannotProduceThatProblem() {
        RemoteOverrideSource(NumericFlagsSpec).apply(json("""{"cfg": {"ratio": 1.5}}"""))
    }

    // Precedence and the change stream

    @Test
    fun aLocalOverrideBeatsARemotePayload() {
        val local = SnapshotSource("local")
        val remote = makeSource()
        remote.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        val pole = FlagPole(RemoteDemoFlagsSpec, listOf(local, remote))
        assertTrue(pole.flags.newOnboarding)

        local.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(false))

        assertFalse(pole.flags.newOnboarding)
        assertEquals("local", pole.resolution(RemoteDemoFlagsSpec.newOnboarding).sourceName)
    }

    @Test
    fun applyingAPayloadEmitsAChange() = runTest {
        val source = makeSource()
        val change = async(start = CoroutineStart.UNDISPATCHED) { source.changes.first() }
        source.apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))
        assertEquals(FlagChange.All, change.await())
    }

    @Test
    fun clearingEmitsAChange() = runTest {
        val source = makeSource()
        val change = async(start = CoroutineStart.UNDISPATCHED) { source.changes.first() }
        source.clear()
        assertEquals(FlagChange.All, change.await())
    }

    @Test
    fun settingAnOverrideWithOnlyAReadOnlySourceReports() {
        val pole = FlagPole(RemoteDemoFlagsSpec, listOf(makeSource()))
        assertFailsWith<FlagException.NoMutableSource> { pole.setOverride(RemoteDemoFlagsSpec.newOnboarding, true) }
    }

    @Test
    fun writesSkipAReadOnlySourceAboveAMutableOne() {
        val local = SnapshotSource("local")
        val pole = FlagPole(RemoteDemoFlagsSpec, listOf(makeSource(), local))
        pole.setOverride(RemoteDemoFlagsSpec.newOnboarding, true)
        assertEquals(FlagValueBox.Bool(true), local.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun applyingAPayloadDoesNotTrapOnACollidingSchema() {
        // A schema handed in directly is not guaranteed well formed: the first entry for a key wins.
        val schema = FlagSchema.of(RemoteDemoFlagsSpec)
        val first = schema.flags.first()
        val colliding = schema.copy(flags = listOf(first, first.copy(description = "B")))

        val result = RemoteOverrideSource(colliding).apply(json("""{"featureToggles": {"onboarding": {"v2": true}}}"""))

        assertEquals(listOf(FlagKey("new-onboarding")), result.appliedKeys)
        assertEquals(emptyList(), result.absentKeys)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.RemoteOverrideSourceTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'RemoteOverrideSource'` and `Unresolved reference 'RemoteOverrideMapper'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideMapper.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey

/**
 * Turns a backend's payload into flag values.
 *
 * Values come back raw. Checking them against each flag's declared type is the source's
 * job, so a mapper never thinks about boxing. Implement this only when a payload's shape
 * cannot be addressed by a path. A mapper may throw [com.andyyhope.featureflag.errors.RemoteOverrideException.Malformed].
 */
public fun interface RemoteOverrideMapper {
    public fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue>
}

/**
 * Reads each flag's `remoteKey` as a dot path into the payload.
 *
 * A flag without a `remoteKey` is not remotely overridable at all. A path that finds
 * nothing, or finds `null`, is left out.
 */
public object DotPathMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (entry in schema.flags) {
            val found = entry.remoteKey?.let(payload::valueAt)
            if (found != null && found != RemoteValue.Null) result[entry.key] = found
        }
        return result
    }
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteMapping.kt` (scalar version, replaced in D2.2)
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/**
 * A payload turned into flag values, before anything is stored. The audit describes this
 * and the source stores it, so the two can never disagree.
 */
internal class RemoteMapping(
    val boxes: Map<FlagKey, FlagValueBox>,
    val problems: List<RemoteOverrideProblem>,
)

/** Runs the mapper and checks every value it produced. Every problem is collected, not just the first. */
internal fun FlagSchema.mapRemote(payload: RemoteValue, mapper: RemoteOverrideMapper): RemoteMapping {
    val mapped = mapper.map(payload, this)

    // First wins on a repeated key: a schema handed in directly may not be well formed.
    val entries = LinkedHashMap<FlagKey, FlagSchema.Entry>()
    for (entry in flags) {
        if (entry.key !in entries) entries[entry.key] = entry
    }

    val boxes = LinkedHashMap<FlagKey, FlagValueBox>()
    val problems = ArrayList<RemoteOverrideProblem>()
    for ((key, value) in mapped) {
        when (val checked = judge(key, value, entries[key])) {
            is Checked.Accepted -> boxes[key] = checked.box
            is Checked.Refused -> problems += checked.problem
        }
    }
    return RemoteMapping(boxes, problems)
}

private sealed interface Checked {
    class Accepted(val box: FlagValueBox) : Checked

    class Refused(val problem: RemoteOverrideProblem) : Checked
}

private fun judge(key: FlagKey, value: RemoteValue, entry: FlagSchema.Entry?): Checked {
    if (entry == null) {
        return Checked.Refused(RemoteOverrideProblem(key, key.rawValue, Kind.UnknownKey, found = value.shortDescription))
    }
    val remoteKey = entry.remoteKey ?: key.rawValue
    val box = value.box(entry.valueType)
        ?: return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.TypeMismatch, entry.valueType.typeName, value.shortDescription),
        )
    val cases = entry.cases
    if (cases != null && box !in cases) {
        return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.UnknownCase, MessageText.caseList(cases), value.shortDescription),
        )
    }
    return Checked.Accepted(box)
}
```

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSource.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What a successful apply did. */
public data class RemoteApplyResult(
    /** Flags this payload set, sorted by key. */
    public val appliedKeys: List<FlagKey>,
    /** Flags with a `remoteKey` the payload did not mention, in schema order. They fall through to the sources below. */
    public val absentKeys: List<FlagKey>,
)

/**
 * Holds the values from the most recent remote payload.
 *
 * It decodes and never fetches: the app gets the bytes however it likes and hands them
 * over. An apply is strict and all-or-nothing, and it replaces everything rather than
 * merging. Reads are lock-free against an immutable map.
 */
public class RemoteOverrideSource(
    public val schema: FlagSchema,
    public val mapper: RemoteOverrideMapper = DotPathMapper,
    override val sourceName: String = "Remote",
) : FlagValueSource {

    /** A source for a container, described with the key encoding its pole uses. */
    public constructor(
        spec: FlagContainerSpec<*>,
        keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
        mapper: RemoteOverrideMapper = DotPathMapper,
        sourceName: String = "Remote",
    ) : this(FlagSchema.of(spec, keyEncoding), mapper, sourceName)

    @Volatile
    private var values: Map<FlagKey, FlagValueBox> = emptyMap()

    // Every event is All, so collapsing a backlog loses nothing.
    private val changeEvents = MutableSharedFlow<FlagChange>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<FlagChange> = changeEvents.asSharedFlow()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    /**
     * Decodes a JSON payload and replaces everything this source holds.
     *
     * @throws RemoteOverrideException.Malformed when the bytes are not JSON.
     * @throws RemoteOverrideException.Rejected with every problem, sorted by key, when any value does not fit.
     */
    public fun apply(json: ByteArray): RemoteApplyResult = apply(RemoteValue.parseJson(json))

    /** Applies an already-decoded payload; see the [ByteArray] overload. */
    public fun apply(payload: RemoteValue): RemoteApplyResult {
        val mapping = schema.mapRemote(payload, mapper)
        if (mapping.problems.isNotEmpty()) {
            throw RemoteOverrideException.Rejected(mapping.problems.sortedBy { it.key.rawValue })
        }
        values = mapping.boxes
        changeEvents.tryEmit(FlagChange.All)
        return RemoteApplyResult(
            appliedKeys = mapping.boxes.keys.sortedBy { it.rawValue },
            absentKeys = schema.flags.filter { it.remoteKey != null && it.key !in mapping.boxes }.map { it.key },
        )
    }

    /** Drops every remote value, so flags fall through to the sources beneath. */
    public fun clear() {
        values = emptyMap()
        changeEvents.tryEmit(FlagChange.All)
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.RemoteOverrideSourceTest'
```
Expected: `BUILD SUCCESSFUL`, 28 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideMapper.kt featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteMapping.kt featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSource.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSourceTest.kt
git commit -m "Add DotPathMapper and an all-or-nothing RemoteOverrideSource" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D2.2 Record lists from a backend

- [ ] **Step 1: Write the failing test.** It ports `FlagRecordRemoteTests.swift` (except the PLIST test) and the remote cases of `FlagNestedRecordTests`, `FlagRecordDefaultTests`, `FlagRecordOptionalAndKeyTests` and `FlagRecordKeyTests`. It also adds the Review Focus 4 pins.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/FlagRecordRemoteTest.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.FlagRecordProperty
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal enum class RemoteRecordTier(override val flagRawValue: String) : FlagStringEnum { Stable("stable"), Beta("beta") }

@FlagRecord
internal data class RemoteEndpoint(val name: String, val url: URI, val weight: Int, val tier: RemoteRecordTier)

@FlagRecord
internal data class RemoteNote(val text: String)

@FlagContainer
internal interface RemoteRecordFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<RemoteEndpoint>
        get() = FlagRecords(listOf(RemoteEndpoint("prod", URI("https://prod.example"), 10, RemoteRecordTier.Stable)))

    @Flag(description = "Notes")
    val notes: FlagRecords<RemoteNote> get() = FlagRecords()

    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false
}

@FlagRecord
internal data class ClusterTarget(val host: String, val port: Int)

@FlagRecord
internal data class Cluster(val name: String, val targets: FlagRecords<ClusterTarget>)

@FlagContainer
internal interface NestedFlags {
    @Flag(description = "Clusters", remoteKey = "config.clusters")
    val clusters: FlagRecords<Cluster> get() = FlagRecords()
}

@FlagRecord
internal data class DefaultedEndpoint(val name: String, val url: URI, val weight: Int = 1, val region: String = "au")

@FlagContainer
internal interface DefaultedFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<DefaultedEndpoint>
        get() = FlagRecords(listOf(DefaultedEndpoint("production", URI("https://prod.example"))))
}

@FlagRecord
internal data class PayMethod(
    @FlagRecordKey val name: String,
    @FlagRecordProperty(key = "apple_pay")
    val applePay: Boolean,
    val minimumSpend: Double? = null,
)

@FlagContainer
internal interface PayFlags {
    @Flag(description = "Payment methods", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<PayMethod> get() = FlagRecords()
}

@FlagRecord
internal data class KeyedEndpoint(@FlagRecordKey val name: String, val url: URI)

@FlagContainer
internal interface KeyedFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<KeyedEndpoint> get() = FlagRecords()
}

class FlagRecordRemoteTest {

    private val endpointsKey = FlagKey("endpoints")

    private fun json(text: String): ByteArray = text.toByteArray()

    /** `{"config": {"<field>": "<text>"}}`, with the list sent pre-serialised as a string. */
    private fun configString(field: String, text: String): RemoteValue =
        RemoteValue.Object(mapOf("config" to RemoteValue.Object(mapOf(field to RemoteValue.String(text)))))

    // The shape a backend actually sends

    @Test
    fun aListOfObjectsBecomesAListOfRecords() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        val result = remote.apply(
            json(
                """
                { "config": { "endpoints": [
                    { "name": "staging", "url": "https://staging.example", "weight": 7, "tier": "beta" },
                    { "name": "canary", "url": "https://canary.example", "weight": 3, "tier": "stable" }
                ] } }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(endpointsKey), result.appliedKeys)

        val pole = FlagPole(RemoteRecordFlagsSpec, listOf(remote))
        assertEquals(listOf("staging", "canary"), pole.flags.endpoints.map { it.name })
        assertEquals(listOf(7, 3), pole.flags.endpoints.map { it.weight })
        assertEquals(RemoteRecordTier.Beta, pole.flags.endpoints.first().tier)
    }

    @Test
    fun theStoredFormIsCanonicalJsonText() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        remote.apply(json("""{"config": {"endpoints": [{"weight": 7, "url": "https://staging.example", "tier": "beta", "name": "staging"}]}}"""))
        assertEquals(
            FlagValueBox.String("""[{"name":"staging","tier":"beta","url":"https://staging.example","weight":7}]"""),
            remote.box(endpointsKey, FlagValueType.String),
        )
    }

    @Test
    fun anEmptyListIsAppliedRatherThanIgnored() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        remote.apply(json("""{ "config": { "endpoints": [] } }"""))
        assertEquals(emptyList(), FlagPole(RemoteRecordFlagsSpec, listOf(remote)).flags.endpoints.toList())
    }

    // Strictness survives

    @Test
    fun aFieldOfTheWrongTypeRejectsTheWholePayload() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(
                json(
                    """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.example", "weight": "seven", "tier": "beta" } ] }, "featureToggles": { "onboarding": true } }""",
                ),
            )
        }
        assertEquals(listOf(endpointsKey), error.problems.map { it.key })
        assertEquals(Kind.TypeMismatch, error.problems.first().kind)
    }

    @Test
    fun aMissingFieldRejectsTheWholePayloadAndNamesTheShape() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(json("""{ "config": { "endpoints": [ { "name": "partial" } ] } }"""))
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected a list of records (name, url, weight, tier), got an array of 1",
            error.problems.single().toString(),
        )
    }

    @Test
    fun anEnumFieldTheAppCannotRepresentRejectsThePayload() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(
                json("""{ "config": { "endpoints": [ { "name": "x", "url": "https://x.example", "weight": 1, "tier": "experimental" } ] } }"""),
            )
        }
    }

    @Test
    fun somethingThatIsNotAListAtAllIsRejected() {
        // Swift's `recordShape.map { … } ?? box(as:)` keeps the shape's nil. It never falls back to storing the text.
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(RemoteRecordFlagsSpec).apply(json("""{ "config": { "endpoints": "not a list" } }"""))
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected a list of records (name, url, weight, tier), got \"not a list\"",
            error.problems.single().toString(),
        )
    }

    // Nothing else changed

    @Test
    fun ordinaryFlagsStillWorkAlongside() {
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)
        val result = remote.apply(
            json("""{ "config": { "endpoints": [ { "name": "one", "url": "https://one.example", "weight": 1, "tier": "stable" } ] }, "featureToggles": { "onboarding": true } }"""),
        )
        assertEquals(listOf(endpointsKey, FlagKey("new-onboarding")), result.appliedKeys)
        assertTrue(FlagPole(RemoteRecordFlagsSpec, listOf(remote)).flags.newOnboarding)
    }

    @Test
    fun aRecordFlagWithNoRemoteKeyIsNotRemotelyOverridable() {
        val result = RemoteOverrideSource(RemoteRecordFlagsSpec).apply(json("""{ "config": { "notes": [ { "text": "nope" } ] } }"""))
        assertFalse(FlagKey("notes") in result.appliedKeys)
    }

    // Nested lists

    @Test
    fun aBackendMaySendANestedListAsRealJsonRatherThanAsAString() {
        val remote = RemoteOverrideSource(NestedFlagsSpec)
        remote.apply(
            json(
                """
                { "config": { "clusters": [
                    { "name": "ap", "targets": [
                        { "host": "a.example", "port": 443 },
                        { "host": "b.example", "port": 8443 }
                    ] }
                ] } }
                """.trimIndent(),
            ),
        )
        val cluster = FlagPole(NestedFlagsSpec, listOf(remote)).flags.clusters.first()
        assertEquals("ap", cluster.name)
        assertEquals(listOf("a.example", "b.example"), cluster.targets.map { it.host })
    }

    @Test
    fun aWrongFieldInsideANestedRecordRejectsTheWholePayload() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(NestedFlagsSpec).apply(
                json("""{ "config": { "clusters": [ { "name": "ap", "targets": [ { "host": "a", "port": "https" } ] } ] } }"""),
            )
        }
    }

    @Test
    fun aNestedListMayStillArriveAsAString() {
        val remote = RemoteOverrideSource(NestedFlagsSpec)
        remote.apply(json("""{ "config": { "clusters": [ { "name": "ap", "targets": "[{\"host\":\"a\",\"port\":1}]" } ] } }"""))
        assertEquals(listOf("a"), FlagPole(NestedFlagsSpec, listOf(remote)).flags.clusters.first().targets.map { it.host })
    }

    @Test
    fun aNestedListSentAsSomethingOtherThanRecordsRejectsThePayload() {
        for (targets in listOf("\"not records\"", "5", "{}")) {
            assertFailsWith<RemoteOverrideException.Rejected>(targets) {
                RemoteOverrideSource(NestedFlagsSpec).apply(json("""{"config": {"clusters": [{"name": "ap", "targets": $targets}]}}"""))
            }
        }
    }

    // Defaults

    @Test
    fun aRemotePayloadMayOmitADefaultedField() {
        val remote = RemoteOverrideSource(DefaultedFlagsSpec)
        remote.apply(json("""{ "config": { "endpoints": [ { "name": "from-backend", "url": "https://backend.example" } ] } }"""))
        val endpoint = FlagPole(DefaultedFlagsSpec, listOf(remote)).flags.endpoints.first()
        assertEquals(1, endpoint.weight)
        assertEquals("au", endpoint.region)
    }

    @Test
    fun aRemotePayloadStillCannotOmitAFieldWithNoDefault() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DefaultedFlagsSpec).apply(json("""{ "config": { "endpoints": [ { "url": "https://backend.example" } ] } }"""))
        }
    }

    // Optional fields and custom keys

    @Test
    fun anOmittedOptionalFieldDecodesAsNull() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "card", "apple_pay": false } ] } }"""))
        val method = FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first()
        assertEquals("card", method.name)
        assertNull(method.minimumSpend)
    }

    @Test
    fun anExplicitNullOptionalFieldDecodesAsNull() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "card", "apple_pay": false, "minimumSpend": null } ] } }"""))
        assertNull(FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first().minimumSpend)
    }

    @Test
    fun aPresentOptionalFieldDecodesToItsValue() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "apple_pay": true, "minimumSpend": 10.5 } ] } }"""))
        assertEquals(10.5, FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first().minimumSpend)
    }

    @Test
    fun anOptionalFieldOfTheWrongTypeStillRejects() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(PayFlagsSpec).apply(
                json("""{ "config": { "paymentMethods": [ { "name": "card", "apple_pay": false, "minimumSpend": "lots" } ] } }"""),
            )
        }
    }

    @Test
    fun aCustomKeyIsReadFromThePayload() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "apple_pay": true } ] } }"""))
        assertTrue(FlagPole(PayFlagsSpec, listOf(remote)).flags.paymentMethods.first().applePay)
    }

    @Test
    fun thePropertyNameIsNotReadWhenACustomKeyIsSet() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(PayFlagsSpec).apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "applePay": true } ] } }"""))
        }
    }

    @Test
    fun theStoredFormIsKeyedByPropertyNameWithUnsetOptionalsLeftOut() {
        val remote = RemoteOverrideSource(PayFlagsSpec)
        remote.apply(json("""{ "config": { "paymentMethods": [ { "name": "wallet", "apple_pay": true } ] } }"""))
        assertEquals(
            FlagValueBox.String("""[{"applePay":true,"name":"wallet"}]"""),
            remote.box(FlagKey("payment-methods"), FlagValueType.String),
        )
    }

    // Keys

    @Test
    fun aRemotePayloadWithADuplicateKeyIsRejectedAndSaysSo() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(KeyedFlagsSpec).apply(
                json("""{ "config": { "endpoints": [ { "name": "staging", "url": "https://one.example" }, { "name": "staging", "url": "https://two.example" } ] } }"""),
            )
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected every record to have its own name, got two with \"staging\"",
            error.problems.single().toString(),
        )
    }

    @Test
    fun aDuplicateKeyInAListSentAsAStringIsNamedToo() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(KeyedFlagsSpec).apply(
                configString("endpoints", """[{"name":"a","url":"https://one.example"},{"name":"a","url":"https://two.example"}]"""),
            )
        }
        assertEquals(
            "'config.endpoints' → endpoints: expected every record to have its own name, got two with \"a\"",
            error.problems.single().toString(),
        )
    }

    // The pre-serialised string quirk (Review Focus 4)

    /**
     * Mirrors FeatureFlag iOS 0.12.0 (`RemoteValue.recordBox`, string branch). This is to
     * be fixed upstream, on both sides together, and then this test flips.
     *
     * A list sent already serialised as a string is checked only as stored text:
     * - fields are read by property name;
     * - enum cases are not checked, and nested lists are not recursed into;
     * - the text is stored verbatim.
     *
     * So an unknown case is applied, and the flag then silently reads its default. The
     * shared remote golden `records-as-string` depends on this.
     */
    @Test
    fun aRecordListSentAsAStringSkipsCaseChecksMirroringIos0120() {
        val text = """[{"name":"x","tier":"experimental","url":"https://x.example","weight":1}]"""
        val remote = RemoteOverrideSource(RemoteRecordFlagsSpec)

        val result = remote.apply(configString("endpoints", text))

        assertEquals(listOf(endpointsKey), result.appliedKeys)
        assertEquals(FlagValueBox.String(text), remote.box(endpointsKey, FlagValueType.String))
        assertEquals(RemoteRecordFlagsSpec.endpoints.defaultValue, FlagPole(RemoteRecordFlagsSpec, listOf(remote)).flags.endpoints)
    }

    /**
     * Mirrors iOS 0.12.0, like the test above, and is to be fixed upstream with it. The
     * string form is read by property name. A backend that pre-serialises with its own key
     * (`apple_pay`) is refused, even though the same list sent as JSON is accepted.
     */
    @Test
    fun aRecordListSentAsAStringIsReadByPropertyNameMirroringIos0120() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(PayFlagsSpec).apply(configString("paymentMethods", """[{"name":"wallet","apple_pay":true}]"""))
        }
        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })

        val byPropertyName = RemoteOverrideSource(PayFlagsSpec).apply(configString("paymentMethods", """[{"applePay":true,"name":"wallet"}]"""))
        assertEquals(listOf(FlagKey("payment-methods")), byPropertyName.appliedKeys)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.FlagRecordRemoteTest'
```
Expected: `BUILD FAILED`, with failing tests such as `aListOfObjectsBecomesAListOfRecords`. Each throws `RemoteOverrideException$Rejected`, because the scalar mapping treats a record flag as a plain string.

- [ ] **Step 3: Write the implementation.** Replace the whole of `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteMapping.kt` with:
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.RecordJson
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.storageIdentity

/**
 * A payload turned into flag values, before anything is stored. The audit describes this
 * and the source stores it, so the two can never disagree.
 */
internal class RemoteMapping(
    val boxes: Map<FlagKey, FlagValueBox>,
    val problems: List<RemoteOverrideProblem>,
)

/** Runs the mapper and checks every value it produced. Every problem is collected, not just the first. */
internal fun FlagSchema.mapRemote(payload: RemoteValue, mapper: RemoteOverrideMapper): RemoteMapping {
    val mapped = mapper.map(payload, this)

    // First wins on a repeated key: a schema handed in directly may not be well formed.
    val entries = LinkedHashMap<FlagKey, FlagSchema.Entry>()
    for (entry in flags) {
        if (entry.key !in entries) entries[entry.key] = entry
    }

    val boxes = LinkedHashMap<FlagKey, FlagValueBox>()
    val problems = ArrayList<RemoteOverrideProblem>()
    for ((key, value) in mapped) {
        when (val checked = judge(key, value, entries[key])) {
            is Checked.Accepted -> boxes[key] = checked.box
            is Checked.Refused -> problems += checked.problem
        }
    }
    return RemoteMapping(boxes, problems)
}

private sealed interface Checked {
    class Accepted(val box: FlagValueBox) : Checked

    class Refused(val problem: RemoteOverrideProblem) : Checked
}

private fun judge(key: FlagKey, value: RemoteValue, entry: FlagSchema.Entry?): Checked {
    if (entry == null) {
        return Checked.Refused(RemoteOverrideProblem(key, key.rawValue, Kind.UnknownKey, found = value.shortDescription))
    }
    val remoteKey = entry.remoteKey ?: key.rawValue
    // A record flag stores text, so its published shape decides, never its type. Swift's
    // `recordShape.map { … } ?? box(as:)` keeps the shape's nil, so there is no fallback to `box`.
    val shape = entry.recordShape
    val box = (if (shape != null) value.recordBox(shape) else value.box(entry.valueType))
        ?: return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.TypeMismatch, entry.expectedDescription(value), entry.foundDescription(value)),
        )
    val cases = entry.cases
    if (cases != null && box !in cases) {
        return Checked.Refused(
            RemoteOverrideProblem(key, remoteKey, Kind.UnknownCase, MessageText.caseList(cases), value.shortDescription),
        )
    }
    return Checked.Accepted(box)
}

/**
 * This value as a record flag's stored text, or `null` when it is not a list this build
 * can read.
 *
 * Every field must be present (or defaulted, or optional) and hold its declared type, an
 * enum field must name a known case, and keys must be distinct. Fields are read by their
 * custom key and stored by property name, as canonical JSON text.
 */
internal fun RemoteValue.recordBox(shape: List<FlagRecordField>): FlagValueBox? {
    if (this is RemoteValue.String) {
        // Mirrors iOS 0.12.0, to be fixed upstream. A list already serialised as text is
        // checked only as stored text: read by property name, no case check, nested lists
        // not recursed. It is then stored verbatim. Pinned by
        // FlagRecordRemoteTest.aRecordListSentAsAStringSkipsCaseChecksMirroringIos0120.
        return if (RecordJson.decode(value, shape) != null) FlagValueBox.String(value) else null
    }
    if (this !is RemoteValue.Array) return null
    val records = ArrayList<Map<String, FlagValueBox>>(value.size)
    for (item in value) records += (item as? RemoteValue.Object)?.recordFields(shape) ?: return null
    if (firstDuplicateKey(records, shape) != null) return null
    return FlagValueBox.String(RecordJson.encode(records))
}

private fun RemoteValue.Object.recordFields(shape: List<FlagRecordField>): Map<String, FlagValueBox>? {
    val boxes = LinkedHashMap<String, FlagValueBox>(shape.size)
    for (field in shape) {
        val raw = value[field.payloadName]?.takeUnless { it == RemoteValue.Null }
        if (raw == null) {
            // Absent or null. An optional field is left out of the stored form. A required
            // one takes its declared default, or the record cannot be read.
            if (!field.isOptional) boxes[field.name] = field.defaultValue ?: return null
        } else {
            // A nested list recurses rather than being read as one string. Again there is
            // no fallback to `box`, so it is not stored as text.
            val nested = field.fields
            val box = (if (nested != null) raw.recordBox(nested) else raw.box(field.type)) ?: return null
            val cases = field.cases
            if (cases != null && box !in cases) return null
            boxes[field.name] = box
        }
    }
    return boxes
}

/** The first key value two records share, compared as stored. `null` when all are distinct or the shape names no key. */
private fun firstDuplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? {
    val keyName = shape.firstOrNull { it.isKey }?.name ?: return null
    val seen = HashSet<String>()
    for (record in records) {
        val value = record[keyName] ?: continue
        if (!seen.add(value.storageIdentity)) return value
    }
    return null
}

/** The duplicate key in a payload, when that is what made it unusable. Used only to say so. */
internal fun RemoteValue.duplicateRecordKey(shape: List<FlagRecordField>): FlagValueBox? {
    if (this is RemoteValue.String) {
        // Parsed as stored but with the uniqueness rule switched off, so the duplicate can be named.
        val records = RecordJson.decode(value, shape.map { it.copy(isKey = false) }) ?: return null
        return firstDuplicateKey(records, shape)
    }
    if (this !is RemoteValue.Array) return null
    val records = ArrayList<Map<String, FlagValueBox>>(value.size)
    for (item in value) {
        val fields = (item as? RemoteValue.Object)?.value ?: return null
        records += shape.mapNotNull { field -> fields[field.payloadName]?.box(field.type)?.let { field.name to it } }.toMap()
    }
    return firstDuplicateKey(records, shape)
}

/** What a record flag wanted. A list that parses but repeats a key failed the uniqueness rule, not the shape. */
private fun FlagSchema.Entry.expectedDescription(value: RemoteValue): String {
    val shape = recordShape ?: return valueType.typeName
    val keyName = shape.firstOrNull { it.isKey }?.name
    if (keyName != null && value.duplicateRecordKey(shape) != null) return "every record to have its own $keyName"
    return "a list of records (${shape.joinToString(", ") { it.name }})"
}

private fun FlagSchema.Entry.foundDescription(value: RemoteValue): String {
    val duplicate = recordShape?.let { value.duplicateRecordKey(it) } ?: return value.shortDescription
    return "two with ${MessageText.short(duplicate)}"
}
```

- [ ] **Step 4: Run it and confirm it passes.** Run both remote suites.
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.*'
```
Expected: `BUILD SUCCESSFUL`. `FlagRecordRemoteTest` passes 26 tests, and `RemoteOverrideSourceTest` and `RemoteValueTest` stay green.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteMapping.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/FlagRecordRemoteTest.kt
git commit -m "Validate record lists from a backend against their published shape" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D2.3 Remote goldens

M1a Task A2's generator (`RemoteFixtures.swift`) writes `fixtures/remote/<case>.payload.json` and `<case>.expected.json` by applying each payload to `RemoteOverrideSource(GoldenFlags.self)` on iOS. **This table is the generator's case list**, row for row; change both together. The golden remote keys are:
- `toggles.boolean` (bool)
- `numbers.int`
- `numbers.double`
- `strings.plain`
- `dates.launch`
- `config.endpoint` (url)
- `config.markets` (`[String]`)
- `tier` (`free|pro|enterprise`)
- `config.endpoints` (`GoldenEndpoint` records)
- `featureToggles.checkout.applePay`

| case | payload.json |
|---|---|
| `every-remote-flag` | `{"toggles":{"boolean":true},"numbers":{"int":7,"double":2.5},"strings":{"plain":"remote"},"dates":{"launch":"2026-06-01T09:30:00Z"},"config":{"endpoint":"https://remote.example/x","markets":["AU","NZ","GB"],"endpoints":[{"name":"edge","url":"https://edge.example","memo":"from backend","targets":[{"host":"a.example","port":443}]}]},"tier":"pro","featureToggles":{"checkout":{"applePay":false}}}` |
| `empty-object` | `{}` |
| `nulls-are-absent` | `{"toggles":{"boolean":null},"tier":"enterprise"}` |
| `whole-numbers` | `{"numbers":{"int":3.0,"double":2}}` |
| `type-mismatch` | `{"toggles":{"boolean":"true"}}` |
| `unknown-case` | `{"tier":"platinum"}` |
| `every-problem-sorted` | `{"numbers":{"int":1.5},"strings":{"plain":7},"tier":"gold","config":{"markets":["AU",1]}}` |
| `long-string-value` | `{"toggles":{"boolean":"line one\nline two, and then a good deal more text than fits"}}` |
| `integer-overflow` | `{"numbers":{"int":10000000000000000000}}` |
| `object-for-string` | `{"strings":{"plain":{"a":1}}}` |
| `bad-date` | `{"dates":{"launch":"2026-01-01"}}` |
| `offset-date` | `{"dates":{"launch":"2026-01-01T10:00:00+10:00"}}` |
| `url-needing-encoding` | `{"config":{"endpoint":"https://example.com/a b/ü"}}` |
| `records-natural` | `{"config":{"endpoints":[{"name":"prod","url":"https://prod.example.com"},{"name":"edge","url":"https://edge.example","weight":3,"memo":"note","kind":"enterprise","targets":[{"host":"a","port":1}]}]}}` |
| `records-as-string` | `{"config":{"endpoints":"[{\"name\":\"x\",\"url\":\"https://x.example\",\"kind\":\"platinum\"}]"}}` |
| `records-duplicate-key` | `{"config":{"endpoints":[{"name":"prod","url":"https://a.example"},{"name":"prod","url":"https://b.example"}]}}` |
| `records-missing-field` | `{"config":{"endpoints":[{"name":"x"}]}}` |
| `records-unknown-case` | `{"config":{"endpoints":[{"name":"x","url":"https://x.example","kind":"platinum"}]}}` |
| `records-mixed-forms` | `{"config":{"endpoints":[{"name":"a","url":"https://a.example"},{"name":"b","url":"https://b.example","weight":5,"memo":null,"kind":"enterprise","targets":"[{\"host\":\"h\",\"port\":1}]"},{"name":"c","url":"https://c.example","note":"ignored: read from memo","targets":[{"host":"x","port":2},{"host":"y","port":3}]}]}}` |
| `records-wrong-field-type` | `{"config":{"endpoints":[{"name":"a","url":"https://a.example","weight":"heavy"}]}}` |
| `not-json` | `not json` (deliberately invalid) |
| `top-level-scalar` | `true` |

Expected-file semantics (M1a Task A2's `RemoteFixtures.expected`):
- A successful apply writes `{"applied":[appliedKeys],"absent":[absentKeys],"problems":[],"values":{key: box.jsonValue}}`, in `RemoteApplyResult` order. `values` holds each applied flag's stored box as JSON.
- Any `RemoteOverrideError` writes `{"rejected":"<String(describing: error)>","problems":[…]}`. For `.rejected` each problem is `{"key","remoteKey","kind","message"}` in thrown order, where `kind` is the Swift case name and `message` is `String(describing: problem)`. For `.malformed` the list is empty.

- [ ] **Step 1: Write the failing test**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteGoldenFixtureTest.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.toJson
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val REQUIRED_REMOTE_CASES = listOf(
    "every-remote-flag", "empty-object", "nulls-are-absent", "whole-numbers", "type-mismatch", "unknown-case",
    "every-problem-sorted", "long-string-value", "integer-overflow", "object-for-string", "bad-date", "offset-date",
    "url-needing-encoding", "records-natural", "records-as-string", "records-duplicate-key", "records-missing-field",
    "records-unknown-case", "records-mixed-forms", "records-wrong-field-type", "not-json", "top-level-scalar",
)

/** Every remote payload the iOS generator applied to `GoldenFlags` lands the same way here. */
class RemoteGoldenFixtureTest {

    private val directory =
        File(requireNotNull(System.getProperty("featureflag.fixtures")) { "featureflag.fixtures is not set" }, "remote")

    private val valueTypes = FlagSchema.of(GoldenFlagsSpec).valueTypes

    private val cases: List<String>
        get() = directory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".payload.json") }
            .map { it.removeSuffix(".payload.json") }
            .sorted()

    @Test
    fun theGeneratorWroteEveryCaseThisPortRelies() {
        val missing = REQUIRED_REMOTE_CASES - cases.toSet()
        assertTrue(missing.isEmpty(), "missing remote goldens: $missing")
    }

    @Test
    fun everyPayloadAppliesExactlyAsItDidOnIos() {
        for (case in cases) assertCase(case)
    }

    private fun assertCase(case: String) {
        val payload = File(directory, "$case.payload.json").readBytes()
        val expected = JsonReader.parse(File(directory, "$case.expected.json").readBytes()) as JsonValue.Object
        val rejected = expected.fields["rejected"] as? JsonValue.Text
        val problems = (expected.fields["problems"] as? JsonValue.Array)?.items.orEmpty()
        val source = RemoteOverrideSource(GoldenFlagsSpec)

        if (rejected != null) {
            val error = assertFailsWith<RemoteOverrideException>(case) { source.apply(payload) }
            assertEquals(rejected.value, error.message, case)
            if (problems.isNotEmpty()) {
                val expectedLines = problems.map { problem ->
                    val fields = (problem as JsonValue.Object).fields
                    listOf("key", "remoteKey", "kind", "message").map { (fields.getValue(it) as JsonValue.Text).value }
                }
                val actualLines = assertIs<RemoteOverrideException.Rejected>(error, case).problems.map { problem ->
                    val kind = problem.kind.name.replaceFirstChar { it.lowercase() }
                    listOf(problem.key.rawValue, problem.remoteKey, kind, problem.toString())
                }
                assertEquals(expectedLines, actualLines, case)
            }
            return
        }
        val result = source.apply(payload)
        assertEquals(texts(expected.fields["applied"]), result.appliedKeys.map { it.rawValue }, case)
        assertEquals(texts(expected.fields["absent"]), result.absentKeys.map { it.rawValue }, case)
        val values = (expected.fields["values"] as JsonValue.Object).fields
        for ((key, json) in values) {
            val type = assertNotNull(valueTypes[FlagKey(key)], "$case: $key")
            val box = assertNotNull(source.box(FlagKey(key), type), "$case: $key holds nothing")
            assertEquals(JsonWriter.compact(json), JsonWriter.compact(box.toJson()), "$case: $key")
        }
    }

    private fun texts(value: JsonValue?): List<String> =
        (value as JsonValue.Array).items.map { (it as JsonValue.Text).value }
}
```

- [ ] **Step 2: Run it.** This is a golden cross-check of D2.1 and D2.2, so it passes as soon as it is written; there is no implementation step.
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ls fixtures/remote | head -50
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.RemoteGoldenFixtureTest'
```
Expected: the `ls` lists the 22 payload/expected pairs from the table, and `BUILD SUCCESSFUL` with 2 tests passed. A failure names a case and shows a diff: it is a parity bug in `RemoteMapping.kt` (problem text and order), `RemoteValue.kt` (boxing), `MessageText.kt` (number and quote text) or M1a's `parseUrl` (`url-needing-encoding`), never in the fixture. A missing case means `fixtures/` is older than M1a Task A2's generator: run `scripts/sync-fixtures.sh`.

- [ ] **Step 3: Run the remote suite and the build**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.*'
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**
```bash
git add featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteGoldenFixtureTest.kt
git commit -m "Check remote payloads against the iOS goldens" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task D3: `EnvironmentConfiguration`

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/EnvironmentConfiguration.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/EnvironmentConfigurationTest.kt`

**Interfaces:**
- Consumes:
  - D2: `RemoteOverrideSource(schema, mapper, sourceName)` with `apply(json)` and `clear()`; `RemoteOverrideMapper`; `DotPathMapper`.
  - `FlagSchema.of(spec, keyEncoding)`, `FlagContainerSpec<*>`, `KeyEncoding.Kebabcase` and `FlagValueSource`.
  - `FlagPole`, `SnapshotSource(name)` and `FlagPole.setOverride(ref, value)`.
  - `kotlinx.coroutines.test`: `runTest`, `StandardTestDispatcher` and `runCurrent`.
- Produces:
```kotlin
public class EnvironmentConfiguration<E>(
    schema: FlagSchema, mapper: RemoteOverrideMapper = DotPathMapper, localName: String = "Local", remoteName: String = "Remote",
    local: (E) -> ByteArray?, remote: suspend (E) -> ByteArray? = { null },
) {
    public constructor(spec: FlagContainerSpec<*>, keyEncoding: KeyEncoding = KeyEncoding.Kebabcase, mapper: RemoteOverrideMapper = DotPathMapper,
        localName: String = "Local", remoteName: String = "Remote", local: (E) -> ByteArray?, remote: suspend (E) -> ByteArray? = { null })
    public val localSource: RemoteOverrideSource
    public val remoteSource: RemoteOverrideSource
    public val sources: List<FlagValueSource>            // [remoteSource, localSource]
    public suspend fun load(environment: E): LoadOutcome // CancellationException propagates
    public fun fetchRemote(load: suspend (E) -> ByteArray?)
}
public data class LoadOutcome(public val local: LayerOutcome, public val remote: LayerOutcome) { public val isComplete: Boolean }
public sealed interface LayerOutcome {
    public data object Applied; public data object Absent; public data class Failed(public val error: Exception); public data object Superseded
    public val isApplied: Boolean; public val isComplete: Boolean
}
```
Swift's `format:` parameter is dropped, because PLIST is dropped.

- [ ] **Step 1: Write the failing test.** It ports `EnvironmentConfigurationTests.swift` and adds the Review Focus 5 pins.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/EnvironmentConfigurationTest.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@FlagContainer
internal interface EnvFlags {
    @Flag(description = "Page size", remoteKey = "pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Beta", remoteKey = "beta")
    val beta: Boolean get() = false

    @Flag(description = "Label", remoteKey = "label")
    val label: String get() = "prod"
}

// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentConfigurationTest {

    private enum class Env { Staging, Production }

    private class Offline : Exception("offline")

    private fun bytes(json: String): ByteArray = json.toByteArray()

    // Layering and precedence

    @Test
    fun remoteWinsOverLocalWhichWinsOverTheDefault() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { bytes("""{ "pageSize": 20, "label": "local" }""") },
            remote = { bytes("""{ "pageSize": 25 }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        config.load(Env.Staging)

        assertEquals(25, pole.flags.pageSize, "remote wins where both set it")
        assertEquals("local", pole.flags.label, "local wins where remote is silent")
        assertFalse(pole.flags.beta, "neither set it: the compiled default")
    }

    @Test
    fun provenanceNamesTheLayerEachValueCameFrom() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { bytes("""{ "label": "local" }""") },
            remote = { bytes("""{ "pageSize": 25 }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)
        config.load(Env.Staging)

        assertEquals("Remote", pole.resolution(FlagKey("page-size"), FlagValueType.Int).sourceName)
        assertEquals("Local", pole.resolution(FlagKey("label"), FlagValueType.String).sourceName)
        assertTrue(pole.resolution(FlagKey("beta"), FlagValueType.Bool).isDefault)
    }

    @Test
    fun theSourcesAreRemoteThenLocal() {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { null })
        assertEquals(listOf(config.remoteSource, config.localSource), config.sources)
        assertEquals(listOf("Remote", "Local"), config.sources.map { it.sourceName })
    }

    // Switching environments

    @Test
    fun switchingClearsThePreviousEnvironmentsValues() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { env -> if (env == Env.Staging) bytes("""{ "label": "staging-local" }""") else null },
            remote = { env -> if (env == Env.Staging) bytes("""{ "pageSize": 25 }""") else bytes("""{ "beta": true }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        config.load(Env.Staging)
        assertEquals(25, pole.flags.pageSize)
        assertEquals("staging-local", pole.flags.label)

        config.load(Env.Production)

        assertEquals(10, pole.flags.pageSize, "staging's remote value is gone")
        assertEquals("prod", pole.flags.label, "staging's local value is gone")
        assertTrue(pole.flags.beta, "production's remote value is applied")
    }

    // A layer that is absent or fails

    @Test
    fun anAbsentLocalLayerLeavesTheRemoteAndDefaults() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { bytes("""{ "pageSize": 25 }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertEquals(LayerOutcome.Absent, outcome.local)
        assertTrue(outcome.remote.isApplied)
        assertEquals(25, pole.flags.pageSize)
        assertEquals("prod", pole.flags.label)
    }

    @Test
    fun aFailedRemoteFetchFallsBackToTheLocalLayer() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { bytes("""{ "pageSize": 20, "label": "local" }""") },
            remote = { throw Offline() },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertTrue(outcome.local.isApplied)
        assertIs<Offline>(assertIs<LayerOutcome.Failed>(outcome.remote).error)
        assertFalse(outcome.isComplete)
        assertEquals(20, pole.flags.pageSize, "the local layer stands in for the missing remote")
    }

    @Test
    fun aFailedRemoteDoesNotLeaveStaleValuesFromThePreviousEnvironment() = runTest {
        var online = true
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { env ->
                if (env == Env.Production && !online) throw Offline()
                bytes("""{ "pageSize": 25 }""")
            },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        config.load(Env.Staging)
        assertEquals(25, pole.flags.pageSize)

        online = false
        config.load(Env.Production)

        assertEquals(10, pole.flags.pageSize, "a failed fetch falls to defaults, not stale values")
    }

    @Test
    fun aMalformedLayerIsReportedAndDoesNotApply() = runTest {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("{ not json") })
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertIs<LayerOutcome.Failed>(outcome.local)
        assertEquals(10, pole.flags.pageSize)
    }

    @Test
    fun onlyAFailureMakesALoadIncomplete() {
        assertTrue(LoadOutcome(LayerOutcome.Applied, LayerOutcome.Absent).isComplete)
        assertTrue(LoadOutcome(LayerOutcome.Superseded, LayerOutcome.Applied).isComplete)
        assertFalse(LoadOutcome(LayerOutcome.Applied, LayerOutcome.Failed(IllegalStateException("x"))).isComplete)
        assertTrue(LayerOutcome.Applied.isApplied)
        assertFalse(LayerOutcome.Superseded.isApplied)
    }

    // Overlapping loads

    @Test
    fun aStaleLoadDoesNotClobberANewerOne() = runTest(StandardTestDispatcher()) {
        // Staging's fetch is slow, and production starts while it waits. Production must win
        // even though staging resolves last.
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { env ->
                if (env == Env.Staging) delay(60)
                bytes(if (env == Env.Staging) """{ "pageSize": 25 }""" else """{ "pageSize": 99 }""")
            },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val stale = async { config.load(Env.Staging) }
        delay(10)
        val winner = async { config.load(Env.Production) }
        val staleOutcome = stale.await()
        winner.await()

        assertEquals(99, pole.flags.pageSize, "production won; staging's late fetch was dropped")
        assertEquals(LayerOutcome.Superseded, staleOutcome.remote)
    }

    @Test
    fun aSupersededLoadWhoseFetchFindsNothingReportsAbsent() = runTest(StandardTestDispatcher()) {
        // Swift checks for "nothing to load" before it checks for a newer load.
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { env ->
                if (env == Env.Staging) {
                    delay(60)
                    null
                } else {
                    bytes("""{ "pageSize": 99 }""")
                }
            },
        )

        val stale = async { config.load(Env.Staging) }
        delay(10)
        config.load(Env.Production)

        assertEquals(LayerOutcome.Absent, stale.await().remote)
    }

    // Cancellation (Review Focus 5)

    @Test
    fun cancellingALoadPropagatesAndLeavesTheRemoteLayerCleared() = runTest(StandardTestDispatcher()) {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { null }, remote = { awaitCancellation() })
        val pole = FlagPole(EnvFlagsSpec, config.sources)
        config.remoteSource.apply(bytes("""{ "pageSize": 25 }"""))

        val loading = launch { config.load(Env.Staging) }
        runCurrent()
        loading.cancel()
        loading.join()

        assertTrue(loading.isCancelled)
        assertEquals(10, pole.flags.pageSize, "the layer was cleared before the fetch and stays cleared")
    }

    @Test
    fun aLoaderThrowingCancellationIsRethrownRatherThanReportedAsFailed() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { throw CancellationException("the caller gave up") },
        )
        assertFailsWith<CancellationException> { config.load(Env.Staging) }
    }

    // A remote loader that depends on the local layer

    @Test
    fun aLateBoundRemoteLoaderRunsAfterLocalIsApplied() = runTest {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("""{ "label": "from-local" }""") })
        val pole = FlagPole(EnvFlagsSpec, config.sources)
        var seenLabel: String? = null
        config.fetchRemote {
            seenLabel = pole.flags.label
            bytes("""{ "pageSize": 25 }""")
        }

        val outcome = config.load(Env.Staging)

        assertEquals("from-local", seenLabel, "the loader runs after the local layer is applied")
        assertTrue(outcome.remote.isApplied)
        assertEquals(25, pole.flags.pageSize, "the bytes the loader returned were applied")
    }

    @Test
    fun aLateBoundRemoteLoaderResolvesThroughLayersAboveLocal() = runTest {
        val companion = SnapshotSource("By hand")
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("""{ "label": "from-local" }""") })
        val pole = FlagPole(EnvFlagsSpec, listOf(companion) + config.sources)
        pole.setOverride(EnvFlagsSpec.label, "from-companion")
        var seenLabel: String? = null
        config.fetchRemote {
            seenLabel = pole.flags.label
            null
        }

        config.load(Env.Staging)

        assertEquals("from-companion", seenLabel, "resolution walks the whole stack")
    }

    @Test
    fun withNoRemoteLoaderTheRemoteLayerIsAbsent() = runTest {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("""{ "label": "local" }""") })
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertEquals(LayerOutcome.Absent, outcome.remote)
        assertEquals("local", pole.flags.label)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.EnvironmentConfigurationTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'EnvironmentConfiguration'` and `Unresolved reference 'LayerOutcome'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/EnvironmentConfiguration.kt`
```kotlin
package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.coroutines.cancellation.CancellationException

/**
 * Two config layers per environment, kept in step with the environment the app is in. The
 * local layer ships with the app; the remote one is fetched.
 *
 * Within the pair, remote beats local, and both beat the compiled default. [sources] hands
 * them back in that order, to splice into a pole above the defaults and below any by-hand
 * override.
 *
 * Loading an environment clears each layer before loading it. A layer whose loader
 * returns `null` or throws is left empty, so a failed fetch falls back to the local
 * config and then the defaults. It never leaves the previous environment's values in place.
 * The library does no networking and reads no files: the loaders supply the bytes.
 */
public class EnvironmentConfiguration<E>(
    schema: FlagSchema,
    mapper: RemoteOverrideMapper = DotPathMapper,
    localName: String = "Local",
    remoteName: String = "Remote",
    private val local: (E) -> ByteArray?,
    remote: suspend (E) -> ByteArray? = { null },
) {

    /** One for a container, described with the key encoding its pole uses. */
    public constructor(
        spec: FlagContainerSpec<*>,
        keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
        mapper: RemoteOverrideMapper = DotPathMapper,
        localName: String = "Local",
        remoteName: String = "Remote",
        local: (E) -> ByteArray?,
        remote: suspend (E) -> ByteArray? = { null },
    ) : this(FlagSchema.of(spec, keyEncoding), mapper, localName, remoteName, local, remote)

    /** The bundled layer: above the compiled defaults, below the remote layer. */
    public val localSource: RemoteOverrideSource = RemoteOverrideSource(schema, mapper, localName)

    /** The fetched layer, the higher of the pair. */
    public val remoteSource: RemoteOverrideSource = RemoteOverrideSource(schema, mapper, remoteName)

    /** Both layers in precedence order, remote first. */
    public val sources: List<FlagValueSource> get() = listOf(remoteSource, localSource)

    // A monotonic token, so a slow load cannot apply over a newer one. Guarded by lock, with remote.
    private val lock = Any()
    private var epoch = 0L
    private var remote: suspend (E) -> ByteArray? = remote

    /**
     * Loads both layers for an environment: local first, then remote. The outcome says what
     * happened to each, since one can succeed while the other does not.
     *
     * Cancellation propagates rather than being reported as [LayerOutcome.Failed]. A layer
     * cleared before the cancellation stays cleared.
     */
    public suspend fun load(environment: E): LoadOutcome {
        val (mine, remoteLoader) = synchronized(lock) {
            epoch += 1
            epoch to remote
        }
        val localOutcome = applyLayer(localSource, mine) { local(environment) }
        val remoteOutcome = applyLayer(remoteSource, mine) { remoteLoader(environment) }
        return LoadOutcome(localOutcome, remoteOutcome)
    }

    /**
     * Supplies the remote layer's bytes, replacing any loader given at construction.
     *
     * Set it after the pole exists when the fetch depends on a flag the local layer
     * carries. [load] applies the local layer before this runs, so that value is already
     * resolvable.
     */
    public fun fetchRemote(load: suspend (E) -> ByteArray?) {
        synchronized(lock) { remote = load }
    }

    private fun isCurrent(mine: Long): Boolean = synchronized(lock) { mine == epoch }

    // A loader is caller code and may throw anything; every failure becomes Failed except
    // cancellation, which must reach the caller.
    @Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
    private suspend fun applyLayer(
        source: RemoteOverrideSource,
        mine: Long,
        load: suspend () -> ByteArray?,
    ): LayerOutcome {
        source.clear()
        return try {
            val bytes = load() ?: return LayerOutcome.Absent
            // Re-checked after the await: a newer load may own the source now.
            if (!isCurrent(mine)) return LayerOutcome.Superseded
            source.apply(bytes)
            LayerOutcome.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LayerOutcome.Failed(error)
        }
    }
}

/** What became of each layer when an environment was loaded. */
public data class LoadOutcome(public val local: LayerOutcome, public val remote: LayerOutcome) {

    /** Both layers loaded as far as they could: applied, absent or superseded. False if either failed. */
    public val isComplete: Boolean get() = local.isComplete && remote.isComplete
}

/** What became of one config layer. */
public sealed interface LayerOutcome {

    /** The config was loaded and applied. */
    public data object Applied : LayerOutcome

    /** The loader returned `null`: there is no config of this kind for this environment. */
    public data object Absent : LayerOutcome

    /** The loader threw, or its config was rejected. The layer is left empty. */
    public data class Failed(public val error: Exception) : LayerOutcome

    /** A newer load started first and owns the layer now, so this result was dropped. */
    public data object Superseded : LayerOutcome

    public val isApplied: Boolean get() = this == Applied

    /** Not a failure: applied, legitimately absent, or dropped for a newer load. */
    public val isComplete: Boolean get() = this !is Failed
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.remote.EnvironmentConfigurationTest'
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`, 16 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/EnvironmentConfiguration.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/EnvironmentConfigurationTest.kt
git commit -m "Add EnvironmentConfiguration with superseded and cancelled loads" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task D4: `FlagMappingAudit`, `FlagDefaultComparison`, `FlagRecordDiff`

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiff.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagDefaultComparison.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagMappingAudit.kt`, which also holds `FlagMappingAuditException`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiffLineTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagDefaultComparisonTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagMappingAuditTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagDefaultDiffTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiffTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/AuditGoldenFixtureTest.kt`

**Interfaces:**
- Consumes:
  - D2: `FlagSchema.mapRemote(payload, mapper): RemoteMapping`, `RemoteOverrideMapper`, `DotPathMapper`.
  - D1: `RemoteValue.parseJson`, `MessageText.short`/`diffField`, `RemoteOverrideProblem`.
  - `RecordJson.decode(text, shape)`, `FlagValueBox.storageIdentity`, `FlagRecordField`, `FlagSchema.Entry.defaultValue`/`recordShape`/`remoteKey`, `FlagSchema.of`.
- Produces:
```kotlin
public data class FlagRecordDiff(val identifier: String, val change: Change) {
    public sealed interface Change { public data object Added; public data object Removed; public data class Changed(val fields: List<FlagFieldDiff>) }
    internal val line: String
    internal companion object { fun diff(defaults: List<Map<String, FlagValueBox>>, incoming: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): List<FlagRecordDiff> }
}
public data class FlagFieldDiff(val field: String, val defaultValue: FlagValueBox?, val incomingValue: FlagValueBox?)
public data class FlagDefaultComparison(val key: FlagKey, val defaultValue: FlagValueBox, val incomingValue: FlagValueBox, val records: List<FlagRecordDiff>? = null) {
    public val matchesDefault: Boolean; override fun toString(): String
}
public class FlagMappingAudit(schema: FlagSchema, payload: RemoteValue, mapper: RemoteOverrideMapper = DotPathMapper) {
    public constructor(spec: FlagContainerSpec<*>, json: ByteArray, mapper: RemoteOverrideMapper = DotPathMapper, keyEncoding: KeyEncoding = KeyEncoding.Kebabcase)
    public val applied: List<FlagKey>; public val absent: List<FlagKey>; public val mismatched: List<RemoteOverrideProblem>
    public val unconsumed: List<String>; public val notRemotelyOverridable: List<FlagKey>; public val defaults: List<FlagDefaultComparison>
    public val matchesDefault: List<FlagKey>; public val changesDefault: List<FlagKey>
    public val isComplete: Boolean
    public fun isComplete(strict: Boolean, ignoring: List<String> = emptyList()): Boolean
    public fun unconsumed(ignoring: List<String>): List<String>
    public fun requireComplete(strict: Boolean = false, ignoring: List<String> = emptyList())   // throws FlagMappingAuditException.Incomplete
    public fun description(strict: Boolean, ignoring: List<String>): String
    override fun toString(): String                                   // description(strict = false, ignoring = emptyList())
    public val defaultsDescription: String
}
public sealed class FlagMappingAuditException(message: String) : RuntimeException(message) { public class Incomplete(public val report: String) }
```

Check on Swift text: the audit, the diff lines and the comparisons render numbers with Swift's `description`. That gives `5.0`, `0.1` and `1e+19`, never the JSON writer's `0.10000000000000001`. So every number goes through `MessageText.double`/`float` (D1.1).

#### D4.1 Record diffs

- [ ] **Step 1: Write the failing test.** It includes the diff case from `FlagRecordOptionalAndKeyTests` and the pairing rules from `FlagRecordDiff.swift`.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiffLineTest.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlagRecordDiffLineTest {

    private val payShape = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String, isKey = true),
        FlagRecordField(name = "applePay", type = FlagValueType.Bool, decodedName = "apple_pay"),
        FlagRecordField(name = "minimumSpend", type = FlagValueType.Double, isOptional = true),
    )

    private val endpointShape = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String, isKey = true),
        FlagRecordField(name = "url", type = FlagValueType.Url),
    )

    private val portShape = listOf(
        FlagRecordField(name = "host", type = FlagValueType.String),
        FlagRecordField(name = "port", type = FlagValueType.Int),
    )

    private fun card(minimumSpend: Double? = null): Map<String, FlagValueBox> = buildMap {
        put("name", FlagValueBox.String("card"))
        put("applePay", FlagValueBox.Bool(false))
        if (minimumSpend != null) put("minimumSpend", FlagValueBox.Double(minimumSpend))
    }

    private fun endpoint(name: String, url: String): Map<String, FlagValueBox> =
        mapOf("name" to FlagValueBox.String(name), "url" to FlagValueBox.Url(URI(url)))

    private fun port(host: String, number: Long): Map<String, FlagValueBox> =
        mapOf("host" to FlagValueBox.String(host), "port" to FlagValueBox.Int(number))

    @Test
    fun aDiffShowsAnOptionalFieldGainingAValue() {
        val diff = FlagRecordDiff.diff(listOf(card()), listOf(card(minimumSpend = 10.0)), payShape).single()

        val changed = assertIs<FlagRecordDiff.Change.Changed>(diff.change)
        assertEquals(listOf(FlagFieldDiff("minimumSpend", null, FlagValueBox.Double(10.0))), changed.fields)
        assertEquals("~ card:\n    minimumSpend: unset → 10.0", diff.line)
    }

    @Test
    fun keyedRecordsAreRemovedOrChangedInTheDefaultsOrderThenAddedInTheConfigsOrder() {
        val diffs = FlagRecordDiff.diff(
            listOf(endpoint("staging", "https://staging.a"), endpoint("prod", "https://prod.a")),
            listOf(endpoint("canary", "https://canary.a"), endpoint("staging", "https://staging.b"), endpoint("edge", "https://edge.a")),
            endpointShape,
        )
        assertEquals(
            listOf("~ staging:\n    url: \"https://staging.a\" → \"https://staging.b\"", "- prod", "+ canary", "+ edge"),
            diffs.map { it.line },
        )
    }

    @Test
    fun anUnchangedOrMerelyReorderedKeyedListHasNoDiffs() {
        val a = endpoint("a", "https://a.example")
        val b = endpoint("b", "https://b.example")
        assertEquals(emptyList(), FlagRecordDiff.diff(listOf(a, b), listOf(a, b), endpointShape))
        assertEquals(emptyList(), FlagRecordDiff.diff(listOf(a, b), listOf(b, a), endpointShape))
    }

    @Test
    fun keylessRecordsPairByPosition() {
        assertEquals(
            listOf("~ [0]:\n    port: 80 → 443", "+ [1]"),
            FlagRecordDiff.diff(listOf(port("a", 80)), listOf(port("a", 443), port("b", 80)), portShape).map { it.line },
        )
        assertEquals(listOf("- [0]"), FlagRecordDiff.diff(listOf(port("a", 80)), emptyList(), portShape).map { it.line })
    }

    @Test
    fun aRecordIsNamedByItsKeyBareOrByItsDiffTextOrByAQuestionMark() {
        val idShape = listOf(FlagRecordField(name = "id", type = FlagValueType.Int, isKey = true))
        val added = FlagRecordDiff.diff(emptyList(), listOf(mapOf("id" to FlagValueBox.Int(7))), idShape)
        assertEquals(listOf("+ 7"), added.map { it.line })
        assertEquals(listOf("+ ?"), FlagRecordDiff.diff(emptyList(), listOf(emptyMap()), idShape).map { it.line })
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.FlagRecordDiffLineTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'FlagRecordDiff'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiff.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.storageIdentity

/** What happened to one record between a flag's compiled default and a config's value. */
public data class FlagRecordDiff(
    /** The record's key value when the record has a key, otherwise its position: `[0]`, `[1]`, … */
    public val identifier: String,
    public val change: Change,
) {

    public sealed interface Change {
        /** In the config, not the default. */
        public data object Added : Change

        /** In the default, not the config. */
        public data object Removed : Change

        /** In both, with these fields differing. Never empty. */
        public data class Changed(public val fields: List<FlagFieldDiff>) : Change
    }

    /** One line when added or removed. A changed record gets its name, then one line per field. */
    internal val line: String
        get() = when (val kind = change) {
            Change.Added -> "+ $identifier"
            Change.Removed -> "- $identifier"
            is Change.Changed -> (listOf("~ $identifier:") + kind.fields.map(::fieldLine)).joinToString("\n")
        }

    internal companion object {

        /** Pairs records by key when the shape has one, otherwise by position. */
        fun diff(
            defaults: List<Map<String, FlagValueBox>>,
            incoming: List<Map<String, FlagValueBox>>,
            shape: List<FlagRecordField>,
        ): List<FlagRecordDiff> {
            val key = shape.firstOrNull { it.isKey }?.name
            return if (key != null) diffByKey(defaults, incoming, shape, key) else diffByIndex(defaults, incoming, shape)
        }
    }
}

/** One field of one record that differs. `null` is an optional field left unset on that side. */
public data class FlagFieldDiff(
    public val field: String,
    public val defaultValue: FlagValueBox?,
    public val incomingValue: FlagValueBox?,
)

// An unset optional field reads "unset" rather than a blank, so "minimumSpend: unset → 10.0" says what happened.
private fun fieldLine(diff: FlagFieldDiff): String =
    "    ${diff.field}: ${describe(diff.defaultValue)} → ${describe(diff.incomingValue)}"

private fun describe(box: FlagValueBox?): String = box?.let(MessageText::diffField) ?: "unset"

private fun diffByKey(
    defaults: List<Map<String, FlagValueBox>>,
    incoming: List<Map<String, FlagValueBox>>,
    shape: List<FlagRecordField>,
    key: String,
): List<FlagRecordDiff> {
    // The key value plain, not quoted: it names the record rather than being one of the values diffed.
    fun identity(record: Map<String, FlagValueBox>): String = when (val box = record[key]) {
        is FlagValueBox.String -> box.value
        null -> "?"
        else -> MessageText.diffField(box)
    }

    fun id(record: Map<String, FlagValueBox>): String = record[key]?.storageIdentity ?: ""

    val incomingById = LinkedHashMap<String, Map<String, FlagValueBox>>()
    for (record in incoming) incomingById.getOrPut(id(record)) { record }
    val defaultIds = defaults.mapTo(HashSet()) { id(it) }

    val diffs = ArrayList<FlagRecordDiff>()
    // Removed and changed, in the default's order.
    for (record in defaults) {
        val match = incomingById[id(record)]
        if (match == null) {
            diffs += FlagRecordDiff(identity(record), FlagRecordDiff.Change.Removed)
        } else {
            val fields = fieldDiffs(record, match, shape)
            if (fields.isNotEmpty()) diffs += FlagRecordDiff(identity(record), FlagRecordDiff.Change.Changed(fields))
        }
    }
    // Added, in the config's order.
    for (record in incoming) {
        if (id(record) !in defaultIds) diffs += FlagRecordDiff(identity(record), FlagRecordDiff.Change.Added)
    }
    return diffs
}

private fun diffByIndex(
    defaults: List<Map<String, FlagValueBox>>,
    incoming: List<Map<String, FlagValueBox>>,
    shape: List<FlagRecordField>,
): List<FlagRecordDiff> {
    val diffs = ArrayList<FlagRecordDiff>()
    for (index in 0 until maxOf(defaults.size, incoming.size)) {
        val id = "[$index]"
        val base = defaults.getOrNull(index)
        val other = incoming.getOrNull(index)
        if (base != null && other != null) {
            val fields = fieldDiffs(base, other, shape)
            if (fields.isNotEmpty()) diffs += FlagRecordDiff(id, FlagRecordDiff.Change.Changed(fields))
        } else if (base != null) {
            diffs += FlagRecordDiff(id, FlagRecordDiff.Change.Removed)
        } else {
            diffs += FlagRecordDiff(id, FlagRecordDiff.Change.Added)
        }
    }
    return diffs
}

// A missing key is an optional field left unset, and unset against a value is a change worth showing.
private fun fieldDiffs(
    base: Map<String, FlagValueBox>,
    incoming: Map<String, FlagValueBox>,
    shape: List<FlagRecordField>,
): List<FlagFieldDiff> = shape.mapNotNull { field ->
    val before = base[field.name]
    val after = incoming[field.name]
    if (before == after) null else FlagFieldDiff(field.name, before, after)
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.FlagRecordDiffLineTest'
```
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiff.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiffLineTest.kt
git commit -m "Diff record lists by key or position with Swift's diff lines" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D4.2 Default comparisons

- [ ] **Step 1: Write the failing test**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagDefaultComparisonTest.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagDefaultComparisonTest {

    @Test
    fun aRestatedValueSaysItMatches() {
        val comparison = FlagDefaultComparison(FlagKey("page-size"), FlagValueBox.Int(10), FlagValueBox.Int(10))
        assertTrue(comparison.matchesDefault)
        assertEquals("page-size: 10 — matches the default", comparison.toString())
    }

    @Test
    fun aScalarChangeShowsTheDefaultBecomingTheIncomingValue() {
        val comparison = FlagDefaultComparison(FlagKey("ratio"), FlagValueBox.Double(3.0), FlagValueBox.Double(2.5))
        assertFalse(comparison.matchesDefault)
        assertEquals("ratio: 3.0 → 2.5", comparison.toString())
    }

    @Test
    fun scalarValuesUseTheOneLineForm() {
        assertEquals(
            "label: \"a\" → \"line one\\nline two\"",
            FlagDefaultComparison(FlagKey("label"), FlagValueBox.String("a"), FlagValueBox.String("line one\nline two")).toString(),
        )
    }

    @Test
    fun aRecordFlagWithNoRecordChangesWasReordered() {
        val comparison =
            FlagDefaultComparison(FlagKey("endpoints"), FlagValueBox.String("[1]"), FlagValueBox.String("[2]"), emptyList())
        assertEquals("endpoints: records reordered", comparison.toString())
    }

    @Test
    fun aRecordFlagThatMatchesStillSaysSo() {
        val comparison =
            FlagDefaultComparison(FlagKey("endpoints"), FlagValueBox.String("[]"), FlagValueBox.String("[]"), emptyList())
        assertEquals("endpoints: \"[]\" — matches the default", comparison.toString())
    }

    @Test
    fun aRecordFlagListsEachRecordIndentedUnderTheKey() {
        val records = listOf(
            FlagRecordDiff(
                "staging",
                FlagRecordDiff.Change.Changed(
                    listOf(FlagFieldDiff("url", FlagValueBox.Url(URI("https://staging.a")), FlagValueBox.Url(URI("https://staging.b")))),
                ),
            ),
            FlagRecordDiff("prod", FlagRecordDiff.Change.Removed),
        )
        val comparison =
            FlagDefaultComparison(FlagKey("endpoints"), FlagValueBox.String("[1]"), FlagValueBox.String("[2]"), records)
        assertEquals(
            "endpoints:\n  ~ staging:\n      url: \"https://staging.a\" → \"https://staging.b\"\n  - prod",
            comparison.toString(),
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.FlagDefaultComparisonTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'FlagDefaultComparison'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagDefaultComparison.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox

/** How one flag's compiled default compares to the value a config supplies for it. */
public data class FlagDefaultComparison(
    public val key: FlagKey,
    /** The value compiled into the app. */
    public val defaultValue: FlagValueBox,
    /** The value the config supplied, as a real apply would store it. */
    public val incomingValue: FlagValueBox,
    /** For a record flag, the per-record breakdown, which is empty when only the order changed. `null` for a scalar flag. */
    public val records: List<FlagRecordDiff>? = null,
) {

    /** Whether the config restates the compiled default rather than changing it. */
    public val matchesDefault: Boolean get() = defaultValue == incomingValue

    override fun toString(): String {
        if (matchesDefault) return "$key: ${MessageText.short(incomingValue)} — matches the default"
        val diffs = records ?: return "$key: ${MessageText.short(defaultValue)} → ${MessageText.short(incomingValue)}"
        // Different, yet nothing added, removed or edited: only the order is left.
        if (diffs.isEmpty()) return "$key: records reordered"
        val body = diffs.joinToString("\n") { diff -> diff.line.split("\n").joinToString("\n") { "  $it" } }
        return "$key:\n$body"
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.FlagDefaultComparisonTest'
```
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagDefaultComparison.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagDefaultComparisonTest.kt
git commit -m "Add FlagDefaultComparison with the Swift description" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D4.3 The mapping audit

- [ ] **Step 1: Write the failing tests.** These are three files porting `FlagMappingAuditTests`, `FlagDefaultDiffTests` and `FlagRecordDiffTests`. Exact report texts were derived line by line from `FlagMappingAudit.swift`. Review Focus 1 is pinned here too.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagMappingAuditTest.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal enum class AuditTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

@FlagContainer
internal interface AuditFlags {
    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Tier", remoteKey = "config.tier")
    val checkoutTier: AuditTier get() = AuditTier.Free

    @Flag(description = "Not remotely overridable")
    val localOnly: String get() = "x"
}

@FlagRecord
internal data class AuditEndpoint(@FlagRecordKey val name: String, val url: URI)

@FlagContainer
internal interface RecordAuditFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<AuditEndpoint> get() = FlagRecords()
}

class FlagMappingAuditTest {

    private val complete = """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" } }"""

    private fun audit(json: String) = FlagMappingAudit(AuditFlagsSpec, json.toByteArray())

    private fun keys(vararg raw: String): List<FlagKey> = raw.map { FlagKey(it) }

    // Forward coverage

    @Test
    fun aCompletePayloadHasNothingAbsentAndIsComplete() {
        val audit = audit(complete)
        assertEquals(emptyList(), audit.absent)
        assertEquals(emptyList(), audit.mismatched)
        assertEquals(keys("checkout-tier", "new-onboarding", "page-size"), audit.applied)
        assertTrue(audit.isComplete)
    }

    @Test
    fun aTypoInARemoteKeyShowsAsAnAbsentFlag() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""")
        assertEquals(keys("page-size"), audit.absent)
        assertFalse(audit.isComplete)
    }

    @Test
    fun aFlatDottedMemberIsAbsentYetNotReportedAsUnconsumed() {
        // Review Focus 1: the member's name equals the remoteKey, so it is "claimed", yet no path reaches it.
        val audit = audit("""{ "featureToggles.onboarding": true, "config": { "pageSize": 25, "tier": "pro" } }""")
        assertEquals(keys("new-onboarding"), audit.absent)
        assertEquals(emptyList(), audit.unconsumed)
    }

    // Reverse coverage

    @Test
    fun aValueNoFlagReadsIsListedAsUnconsumed() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }""")
        assertEquals(listOf("meta.version"), audit.unconsumed)
    }

    @Test
    fun unconsumedValuesDoNotFailTheDefaultAudit() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }""")
        assertTrue(audit.isComplete)
        assertFalse(audit.isComplete(strict = true))
        assertTrue(audit.isComplete(strict = true, ignoring = listOf("meta")))
    }

    @Test
    fun anIgnoredPrefixMatchesWholePathSegmentsOnly() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }""")
        assertEquals(listOf("meta.version"), audit.unconsumed(ignoring = listOf("met")))
        assertEquals(emptyList(), audit.unconsumed(ignoring = listOf("meta.version")))
    }

    @Test
    fun strictModeCatchesATypoThatLenientCoverageMisses() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""")
        assertEquals(keys("page-size"), audit.absent)
        assertEquals(listOf("config.page_size"), audit.unconsumed)
    }

    @Test
    fun aRecordFlagCoversEveryLeafBeneathItsPath() {
        val audit = FlagMappingAudit(
            RecordAuditFlagsSpec,
            """{ "config": { "endpoints": [ { "name": "a", "url": "https://a.example" }, { "name": "b", "url": "https://b.example" } ] } }""".toByteArray(),
        )
        assertEquals(emptyList(), audit.unconsumed, "the record flag reads the whole list")
        assertEquals(keys("endpoints"), audit.applied)
    }

    // Mismatches are collected, not thrown

    @Test
    fun everyTypeMismatchIsReportedAtOnce() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": "yes" }, "config": { "pageSize": "lots", "tier": "pro" } }""")
        assertEquals(setOf(FlagKey("new-onboarding"), FlagKey("page-size")), audit.mismatched.map { it.key }.toSet())
        assertFalse(audit.isComplete)
    }

    @Test
    fun anEnumCaseThisBuildLacksIsAMismatch() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "gold" } }""")
        assertEquals(keys("checkout-tier"), audit.mismatched.map { it.key })
        assertEquals(Kind.UnknownCase, audit.mismatched.first().kind)
    }

    // Flags with no remoteKey

    @Test
    fun aFlagWithNoRemoteKeyIsNeitherAppliedNorAbsent() {
        val audit = audit(complete)
        assertEquals(keys("local-only"), audit.notRemotelyOverridable)
        assertFalse(FlagKey("local-only") in audit.applied)
        assertFalse(FlagKey("local-only") in audit.absent)
    }

    // Reporting

    @Test
    fun theDescriptionNamesEveryProblem() {
        val text = audit("""{ "config": { "page_size": 25, "tier": "gold" }, "meta": { "version": 7 } }""").toString()
        assertEquals(
            """
            Flag mapping audit — incomplete.
              absent (2) — a flag declares this path, the payload has nothing there:
                • new-onboarding
                • page-size
              mismatched (1):
                • 'config.tier' → checkout-tier: "gold" is not one of free, pro
              unconsumed (2) — usually fine, often backend metadata — no flag reads these:
                • config.page_size
                • meta.version
              0 of 3 remotely-overridable flags applied.
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("com.andyyhope"), text)
    }

    @Test
    fun aStrictDescriptionDropsTheNoteAndHonoursIgnoredPrefixes() {
        val audit = audit(
            """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 }, "extra": 1 }""",
        )
        assertEquals(
            """
            Flag mapping audit — incomplete.
              unconsumed (1) — no flag reads these:
                • extra
              3 of 3 remotely-overridable flags applied.
            """.trimIndent(),
            audit.description(strict = true, ignoring = listOf("meta")),
        )
        assertEquals(
            """
            Flag mapping audit — complete.
              unconsumed (1) — usually fine, often backend metadata — no flag reads these:
                • extra
              3 of 3 remotely-overridable flags applied.
            """.trimIndent(),
            audit.description(strict = false, ignoring = listOf("meta")),
        )
    }

    @Test
    fun requireCompleteThrowsTheReportWhenIncomplete() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""")
        val error = assertFailsWith<FlagMappingAuditException.Incomplete> { audit.requireComplete() }
        assertEquals(audit.toString(), error.message)
        assertEquals(audit.toString(), error.report)
    }

    @Test
    fun requireCompleteIsSilentWhenComplete() {
        audit(complete).requireComplete()
    }

    // Degenerate payloads

    @Test
    fun anEmptyPayloadReportsNothingUnconsumedNotABlankPath() {
        val audit = audit("{}")
        assertEquals(emptyList(), audit.unconsumed)
        assertFalse(audit.toString().contains("• \n"), audit.toString())
    }

    @Test
    fun aRootLevelArrayDoesNotProduceLeadingDotPaths() {
        assertEquals(listOf("0", "1"), audit("[1, 2]").unconsumed)
    }

    @Test
    fun aNestedEmptyContainerIsStillNamed() {
        assertEquals(listOf("meta"), audit("""{"meta": {}}""").unconsumed)
    }

    // Bad input

    @Test
    fun malformedJsonThrowsRatherThanAuditing() {
        assertFailsWith<RemoteOverrideException.Malformed> { audit("{ not json") }
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagDefaultDiffTest.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@FlagRecord
internal data class DefaultDiffEndpoint(@FlagRecordKey val name: String, val url: URI)

@FlagContainer
internal interface RecordDefaultDiffFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<DefaultDiffEndpoint>
        get() = FlagRecords(listOf(DefaultDiffEndpoint("a", URI("https://a.example"))))
}

@FlagContainer
internal interface DiffFlags {
    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Ratio", remoteKey = "config.ratio")
    val ratio: Double get() = 3.0
}

class FlagDefaultDiffTest {

    private fun audit(json: String) = FlagMappingAudit(DiffFlagsSpec, json.toByteArray())

    private fun keys(vararg raw: String): List<FlagKey> = raw.map { FlagKey(it) }

    @Test
    fun aValueEqualToTheDefaultIsAMatch() {
        val audit = audit("""{ "featureToggles": { "onboarding": false }, "config": { "pageSize": 99 } }""")
        assertEquals(keys("new-onboarding"), audit.matchesDefault)
        assertEquals(keys("page-size"), audit.changesDefault)
    }

    @Test
    fun everyComparisonCarriesBothValues() {
        val diff = audit("""{ "config": { "pageSize": 99 } }""").defaults.first { it.key == FlagKey("page-size") }
        assertEquals(FlagValueBox.Int(10), diff.defaultValue)
        assertEquals(FlagValueBox.Int(99), diff.incomingValue)
        assertFalse(diff.matchesDefault)
    }

    @Test
    fun onlyFlagsTheConfigSuppliedAppearInTheDiff() {
        assertEquals(keys("page-size"), audit("""{ "config": { "pageSize": 99 } }""").defaults.map { it.key })
    }

    @Test
    fun aValueMatchingTheDefaultDoesNotFailTheAudit() {
        val audit =
            audit("""{ "featureToggles": { "onboarding": false }, "config": { "pageSize": 10, "ratio": 3.0 } }""")
        assertEquals(keys("new-onboarding", "page-size", "ratio"), audit.matchesDefault)
        assertEquals(emptyList(), audit.changesDefault)
        assertTrue(audit.isComplete, "restating a default is not a coverage failure")
    }

    @Test
    fun aWholeNumberSentForADoubleStillMatchesADoubleDefault() {
        assertEquals(keys("ratio"), audit("""{ "config": { "ratio": 3 } }""").matchesDefault)
    }

    @Test
    fun aRecordListRestatedExactlyMatchesItsDefault() {
        val audit = FlagMappingAudit(
            RecordDefaultDiffFlagsSpec,
            """{"config":{"endpoints":[{"name":"a","url":"https://a.example"}]}}""".toByteArray(),
        )
        assertEquals(keys("endpoints"), audit.matchesDefault)
    }

    @Test
    fun recordFieldOrderInTheConfigDoesNotCreateAFalseChange() {
        val audit = FlagMappingAudit(
            RecordDefaultDiffFlagsSpec,
            """{"config":{"endpoints":[{"url":"https://a.example","name":"a"}]}}""".toByteArray(),
        )
        assertEquals(emptyList(), audit.changesDefault)
    }

    @Test
    fun theDiffDescriptionShowsChangesAndMatches() {
        val text = audit("""{ "featureToggles": { "onboarding": false }, "config": { "pageSize": 99, "ratio": 2.5 } }""").defaultsDescription
        assertEquals(
            """
            Default vs config:
              changes (2):
                • page-size: 10 → 99
                • ratio: 3.0 → 2.5
              restated (1) — same as the default, could be omitted:
                • new-onboarding
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aConfigThatRestatesEverythingSaysSo() {
        assertEquals(
            """
            Default vs config:
              every supplied value matches the compiled default.
              restated (1) — same as the default, could be omitted:
                • page-size
            """.trimIndent(),
            audit("""{ "config": { "pageSize": 10 } }""").defaultsDescription,
        )
    }

    @Test
    fun theDiffOfAConfigThatChangesNothingSaysSo() {
        val audit = audit("{}")
        assertEquals(emptyList(), audit.defaults)
        assertEquals("This config changes nothing — it supplies no value for any flag.", audit.defaultsDescription)
    }
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiffTest.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@FlagRecord
internal data class DiffEndpoint(@FlagRecordKey val name: String, val url: URI, val weight: Int)

@FlagRecord
internal data class DiffPort(val host: String, val port: Int)

@FlagContainer
internal interface RecordDiffFlags {
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<DiffEndpoint>
        get() = FlagRecords(
            listOf(DiffEndpoint("staging", URI("https://staging.a"), 1), DiffEndpoint("prod", URI("https://prod.a"), 3)),
        )

    @Flag(description = "Ports", remoteKey = "config.ports")
    val ports: FlagRecords<DiffPort> get() = FlagRecords(listOf(DiffPort("a", 80)))

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10
}

class FlagRecordDiffTest {

    private fun audit(json: String) = FlagMappingAudit(RecordDiffFlagsSpec, json.toByteArray())

    private fun endpoints(audit: FlagMappingAudit): FlagDefaultComparison = audit.defaults.first {
        it.key == FlagKey("endpoints")
    }

    @Test
    fun aChangedFieldIsNamedWithBothValues() {
        val audit =
            audit("""{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.b", "weight": 1 } ] } }""")
        val record = endpoints(audit).records.orEmpty().first { it.identifier == "staging" }
        val fields = assertIs<FlagRecordDiff.Change.Changed>(record.change).fields
        val url = fields.first { it.field == "url" }
        assertEquals(FlagValueBox.Url(URI("https://staging.a")), url.defaultValue)
        assertEquals(FlagValueBox.Url(URI("https://staging.b")), url.incomingValue)
        assertNull(fields.firstOrNull { it.field == "weight" }, "an unchanged field is not listed")
    }

    @Test
    fun aRecordOnlyInTheConfigIsAdded() {
        val audit = audit(
            """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 }, { "name": "canary", "url": "https://canary.a", "weight": 2 } ] } }""",
        )
        assertEquals(FlagRecordDiff.Change.Added, endpoints(audit).records.orEmpty().first { it.identifier == "canary" }.change)
    }

    @Test
    fun aRecordOnlyInTheDefaultIsRemoved() {
        val audit =
            audit("""{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 } ] } }""")
        assertEquals(FlagRecordDiff.Change.Removed, endpoints(audit).records.orEmpty().first { it.identifier == "prod" }.change)
    }

    @Test
    fun aKeylessRecordListPairsByIndex() {
        val ports = audit("""{ "config": { "ports": [ { "host": "a", "port": 443 } ] } }""").defaults.first {
            it.key == FlagKey("ports")
        }
        val record = ports.records.orEmpty().first()
        assertEquals("[0]", record.identifier)
        val fields = assertIs<FlagRecordDiff.Change.Changed>(record.change).fields
        assertEquals(FlagValueBox.Int(443), fields.first { it.field == "port" }.incomingValue)
    }

    @Test
    fun aReorderedListSaysSoRatherThanDumpingTheBlob() {
        val audit = audit(
            """{ "config": { "endpoints": [ { "name": "prod", "url": "https://prod.a", "weight": 3 }, { "name": "staging", "url": "https://staging.a", "weight": 1 } ] } }""",
        )
        assertEquals(emptyList(), endpoints(audit).records, "no record changed, only order")
        assertTrue(FlagKey("endpoints") in audit.changesDefault, "order is a real change")
        assertEquals("Default vs config:\n  changes (1):\n    • endpoints: records reordered", audit.defaultsDescription)
    }

    @Test
    fun aRecordListRestatedExactlyHasNoRecordChanges() {
        val audit = audit(
            """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 }, { "name": "prod", "url": "https://prod.a", "weight": 3 } ] } }""",
        )
        assertEquals(emptyList(), endpoints(audit).records)
        assertTrue(FlagKey("endpoints") in audit.matchesDefault)
    }

    @Test
    fun aScalarFlagHasNoRecordBreakdown() {
        val audit = audit(
            """{ "config": { "pageSize": 99, "endpoints": [ { "name": "staging", "url": "https://staging.a", "weight": 1 }, { "name": "prod", "url": "https://prod.a", "weight": 3 } ] } }""",
        )
        assertNull(audit.defaults.first { it.key == FlagKey("page-size") }.records)
    }

    @Test
    fun theDescriptionShowsFieldsNotATruncatedBlob() {
        val text = audit(
            """{ "config": { "endpoints": [ { "name": "staging", "url": "https://staging.b", "weight": 1 }, { "name": "canary", "url": "https://canary.a", "weight": 9 } ] } }""",
        ).defaultsDescription
        assertEquals(
            """
            Default vs config:
              changes (1):
                • endpoints:
                    ~ staging:
                        url: "https://staging.a" → "https://staging.b"
                    - prod
                    + canary
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("\"url\":"), text)
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.*'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'FlagMappingAudit'` and `Unresolved reference 'FlagMappingAuditException'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagMappingAudit.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.RecordJson
import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteMapping
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.remote.mapRemote
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * A check that a remote payload wires up the flags it should, run without an app.
 *
 * It reuses the validation `RemoteOverrideSource` applies, so a value it calls applied is
 * one a real apply would accept. What it adds is coverage in both directions: a flag
 * whose path found nothing ([absent]), and a value no flag reads ([unconsumed]).
 */
public class FlagMappingAudit(
    schema: FlagSchema,
    payload: RemoteValue,
    mapper: RemoteOverrideMapper = DotPathMapper,
) {

    /**
     * Audits a container against a JSON payload, decoding it the way the source would.
     *
     * @throws com.andyyhope.featureflag.errors.RemoteOverrideException.Malformed when the bytes are not JSON.
     */
    public constructor(
        spec: FlagContainerSpec<*>,
        json: ByteArray,
        mapper: RemoteOverrideMapper = DotPathMapper,
        keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    ) : this(FlagSchema.of(spec, keyEncoding), RemoteValue.parseJson(json), mapper)

    /** Flags whose `remoteKey` found a value that fits their type, sorted by key. */
    public val applied: List<FlagKey>

    /** Flags with a `remoteKey` the payload did not supply, sorted by key. Usually a typo in the key. */
    public val absent: List<FlagKey>

    /** Values that did not fit: the problems a real apply would reject, sorted by key. */
    public val mismatched: List<RemoteOverrideProblem>

    /**
     * Payload paths no flag reads, sorted. A path is claimed when a `remoteKey` equals it
     * or names an ancestor of it. This follows `DotPathMapper`, so under a custom mapper
     * it is advisory only.
     */
    public val unconsumed: List<String>

    /** Flags that declare no `remoteKey`, sorted by key. */
    public val notRemotelyOverridable: List<FlagKey>

    /** One comparison per applied flag against its compiled default, sorted by key. */
    public val defaults: List<FlagDefaultComparison>

    init {
        val mapping = schema.mapRemote(payload, mapper)
        val mismatchedKeys = mapping.problems.mapTo(HashSet()) { it.key }
        val appliedInOrder = ArrayList<FlagKey>()
        val absentInOrder = ArrayList<FlagKey>()
        val notRemote = ArrayList<FlagKey>()
        for (entry in schema.flags) {
            when {
                entry.remoteKey == null -> notRemote += entry.key
                entry.key in mapping.boxes -> appliedInOrder += entry.key
                // A mismatched flag did supply a value: it is a problem, not an absence.
                entry.key !in mismatchedKeys -> absentInOrder += entry.key
            }
        }
        applied = appliedInOrder.sortedBy { it.rawValue }
        absent = absentInOrder.sortedBy { it.rawValue }
        mismatched = mapping.problems.sortedBy { it.key.rawValue }
        notRemotelyOverridable = notRemote.sortedBy { it.rawValue }
        unconsumed = unconsumedPaths(payload, schema.flags.mapNotNull { it.remoteKey })
        defaults = comparisons(schema, mapping, appliedInOrder)
    }

    /** Applied flags whose config value equals the compiled default: the config could omit them. */
    public val matchesDefault: List<FlagKey> get() = defaults.filter { it.matchesDefault }.map { it.key }

    /** Applied flags whose config value differs from the compiled default. */
    public val changesDefault: List<FlagKey> get() = defaults.filterNot { it.matchesDefault }.map { it.key }

    /** Nothing absent and nothing mismatched. Unconsumed values are reported but do not fail. */
    public val isComplete: Boolean get() = isComplete(strict = false)

    /**
     * @param strict also require every payload value to be read by a flag.
     * @param ignoring path prefixes whose unconsumed values are allowed even under [strict].
     */
    public fun isComplete(strict: Boolean, ignoring: List<String> = emptyList()): Boolean {
        if (absent.isNotEmpty() || mismatched.isNotEmpty()) return false
        return !strict || unconsumed(ignoring).isEmpty()
    }

    /** The unconsumed paths left after dropping any path equal to, or under, an ignored prefix. */
    public fun unconsumed(ignoring: List<String>): List<String> {
        if (ignoring.isEmpty()) return unconsumed
        return unconsumed.filter { path -> ignoring.none { path == it || path.startsWith("$it.") } }
    }

    /** Throws the whole report when the payload is incomplete; silent otherwise. */
    public fun requireComplete(strict: Boolean = false, ignoring: List<String> = emptyList()) {
        if (!isComplete(strict, ignoring)) throw FlagMappingAuditException.Incomplete(description(strict, ignoring))
    }

    override fun toString(): String = description(strict = false, ignoring = emptyList())

    /** The full report. [strict] changes the verdict, and [ignoring] drops the excused paths. */
    public fun description(strict: Boolean, ignoring: List<String>): String {
        val shownUnconsumed = unconsumed(ignoring)
        val overridable = applied.size + absent.size + mismatched.size
        val lines = ArrayList<String>()
        lines += if (isComplete(strict, ignoring)) "Flag mapping audit — complete." else "Flag mapping audit — incomplete."
        if (absent.isNotEmpty()) {
            lines += "  absent (${absent.size}) — a flag declares this path, the payload has nothing there:"
            absent.forEach { lines += "    • $it" }
        }
        if (mismatched.isNotEmpty()) {
            lines += "  mismatched (${mismatched.size}):"
            mismatched.forEach { lines += "    • $it" }
        }
        if (shownUnconsumed.isNotEmpty()) {
            val note = if (strict) "" else " — usually fine, often backend metadata"
            lines += "  unconsumed (${shownUnconsumed.size})$note — no flag reads these:"
            shownUnconsumed.forEach { lines += "    • $it" }
        }
        lines += "  ${applied.size} of $overridable remotely-overridable flags applied."
        return lines.joinToString("\n")
    }

    /** The default-versus-config diff as a block: what changes, then what is restated. */
    public val defaultsDescription: String
        get() {
            if (defaults.isEmpty()) return "This config changes nothing — it supplies no value for any flag."
            val changes = defaults.filterNot { it.matchesDefault }
            val restated = defaults.filter { it.matchesDefault }
            val lines = arrayListOf("Default vs config:")
            if (changes.isEmpty()) {
                lines += "  every supplied value matches the compiled default."
            } else {
                lines += "  changes (${changes.size}):"
                for (change in changes) {
                    val rendered = change.toString().split("\n")
                    lines += "    • ${rendered.first()}"
                    rendered.drop(1).forEach { lines += "      $it" }
                }
            }
            if (restated.isNotEmpty()) {
                lines += "  restated (${restated.size}) — same as the default, could be omitted:"
                restated.forEach { lines += "    • ${it.key}" }
            }
            return lines.joinToString("\n")
        }
}

/** A payload that did not wire up every flag it should. */
public sealed class FlagMappingAuditException(message: String) : RuntimeException(message) {

    /** [report] is the whole audit, exactly as `FlagMappingAudit.description` renders it. */
    public class Incomplete(public val report: String) : FlagMappingAuditException(report)
}

private fun comparisons(schema: FlagSchema, mapping: RemoteMapping, keys: List<FlagKey>): List<FlagDefaultComparison> {
    val entries = LinkedHashMap<FlagKey, FlagSchema.Entry>()
    for (entry in schema.flags) {
        if (entry.key !in entries) entries[entry.key] = entry
    }
    return keys.mapNotNull { key ->
        val incoming = mapping.boxes[key]
        val entry = entries[key]
        if (incoming == null || entry == null) null else comparison(key, entry, incoming)
    }.sortedBy { it.key.rawValue }
}

private fun comparison(key: FlagKey, entry: FlagSchema.Entry, incoming: FlagValueBox): FlagDefaultComparison {
    val records = entry.recordShape?.let { shape ->
        FlagRecordDiff.diff(entry.defaultValue.recordValues(shape), incoming.recordValues(shape), shape)
    }
    return FlagDefaultComparison(key, entry.defaultValue, incoming, records)
}

private fun FlagValueBox.recordValues(shape: List<FlagRecordField>): List<Map<String, FlagValueBox>> =
    (this as? FlagValueBox.String)?.let { RecordJson.decode(it.value, shape) }.orEmpty()

private fun unconsumedPaths(payload: RemoteValue, remoteKeys: List<String>): List<String> {
    val claims = remoteKeys.toSet()
    val leaves = ArrayList<String>()
    collectLeafPaths(payload, "", leaves)
    return leaves.filterNot { path -> path in claims || claims.any { path.startsWith("$it.") } }.sorted()
}

// Every scalar's dot path. A nested empty object or array is a leaf too, but the root never
// is: a bare or empty payload has no path to name.
private fun collectLeafPaths(value: RemoteValue, prefix: String, into: MutableList<String>) {
    when {
        value is RemoteValue.Object && value.value.isNotEmpty() ->
            value.value.forEach { (name, child) -> collectLeafPaths(child, if (prefix.isEmpty()) name else "$prefix.$name", into) }
        value is RemoteValue.Array && value.value.isNotEmpty() ->
            value.value.forEachIndexed { index, child -> collectLeafPaths(child, if (prefix.isEmpty()) "$index" else "$prefix.$index", into) }
        prefix.isNotEmpty() -> into += prefix
    }
}
```

- [ ] **Step 4: Run them and confirm they pass**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.*'
```
Expected: `BUILD SUCCESSFUL`. Results: `FlagMappingAuditTest` 19, `FlagDefaultDiffTest` 10, `FlagRecordDiffTest` 8, `FlagRecordDiffLineTest` 5, `FlagDefaultComparisonTest` 6.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagMappingAudit.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagMappingAuditTest.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagDefaultDiffTest.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/FlagRecordDiffTest.kt
git commit -m "Add FlagMappingAudit with coverage, default diffs and Swift reports" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D4.4 Audit goldens

M1a Task A2's generator (`RemoteFixtures.swift`) writes `fixtures/audit/<case>.payload.json`, together with `<case>.txt` (`String(describing: audit)`), `<case>.strict.txt` (`audit.description(strict: true, ignoring: ["meta"])`) and `<case>.defaults.txt` (`audit.defaultsDescription`). Each comes from `try FlagMappingAudit(GoldenFlags.self, applying: payload)`, with no trailing newline. `.defaults.txt` is the only golden for diff lines and Swift number text. **This table is the generator's audit case list**, row for row:

| case | payload.json |
|---|---|
| `complete` | the `every-remote-flag` payload from D2.3 |
| `incomplete` | `{"toggles":{"boolean":"yes"},"numbers":{"int":42},"tier":"gold","config":{"page_size":25},"meta":{"version":7}}` |
| `empty-object` | `{}` |
| `root-array` | `[1,2]` |
| `nested-empty` | `{"meta":{}}` |
| `restated` | `{"toggles":{"boolean":false},"numbers":{"int":42,"double":0.1},"strings":{"plain":"hello/world \"quoted\""},"dates":{"launch":"2026-01-01T00:00:00Z"},"config":{"endpoint":"https://example.com/a/b?c=d","markets":["AU","NZ"],"endpoints":[{"name":"prod","url":"https://prod.example.com"}]},"tier":"free","featureToggles":{"checkout":{"applePay":true}}}` |
| `record-changes` | `{"config":{"endpoints":[{"name":"prod","url":"https://prod.example.com","weight":5,"kind":"pro","memo":"hello"},{"name":"canary","url":"https://canary.example"}]}}` |
| `scalar-changes` | `{"numbers":{"int":10000000000,"double":0.30000000000000004},"strings":{"plain":"a much longer string than forty characters\nwith a newline"},"dates":{"launch":"2026-06-01T09:30:00Z"},"config":{"endpoint":"https://remote.example/x","markets":["AU"]}}` |

- [ ] **Step 1: Write the failing test**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/AuditGoldenFixtureTest.kt`
```kotlin
package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val REQUIRED_AUDIT_CASES = listOf(
    "complete", "incomplete", "empty-object", "root-array", "nested-empty", "restated", "record-changes", "scalar-changes",
)

/** Every audit report and default diff the iOS generator wrote for `GoldenFlags` reads the same here, byte for byte. */
class AuditGoldenFixtureTest {

    private val directory =
        File(requireNotNull(System.getProperty("featureflag.fixtures")) { "featureflag.fixtures is not set" }, "audit")

    private val cases: List<String>
        get() = directory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".payload.json") }
            .map { it.removeSuffix(".payload.json") }
            .sorted()

    private fun golden(name: String): String = File(directory, name).readText().removeSuffix("\n")

    @Test
    fun theGeneratorWroteEveryCaseThisPortRelies() {
        val missing = REQUIRED_AUDIT_CASES.filterNot { case ->
            case in cases && listOf("txt", "strict.txt", "defaults.txt").all { File(directory, "$case.$it").exists() }
        }
        assertTrue(missing.isEmpty(), "missing audit goldens (payload, .txt, .strict.txt and .defaults.txt): $missing")
    }

    @Test
    fun everyReportMatchesIos() {
        for (case in cases) {
            val audit = FlagMappingAudit(GoldenFlagsSpec, File(directory, "$case.payload.json").readBytes())
            assertEquals(golden("$case.txt"), audit.toString(), case)
            assertEquals(golden("$case.strict.txt"), audit.description(strict = true, ignoring = listOf("meta")), "$case strict")
            assertEquals(golden("$case.defaults.txt"), audit.defaultsDescription, "$case defaults")
        }
    }
}
```

- [ ] **Step 2: Run it.** This is a golden cross-check of D4.1 to D4.3, so it passes as soon as it is written; there is no implementation step.
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ls fixtures/audit
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.AuditGoldenFixtureTest'
```
Expected: the `ls` lists four files per case in the table (32), and `BUILD SUCCESSFUL` with 2 tests passed. A failure names a case and shows a text diff: it is a parity bug in `FlagMappingAudit.kt` (report layout), `FlagDefaultComparison.kt` or `FlagRecordDiff.kt` (diff lines) or `MessageText.kt` (numbers and quotes), never in the fixture. A missing case means `fixtures/` is older than M1a Task A2's generator: run `scripts/sync-fixtures.sh`.

- [ ] **Step 3: Run the audit suite and the build**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.audit.*'
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**
```bash
git add featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit/AuditGoldenFixtureTest.kt
git commit -m "Check audit reports and default diffs against the iOS goldens" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task D5: `ErrorMessageTest`, every message golden

**Files:**
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/ErrorMessageTest.kt`

**Interfaces:**
- Consumes. These are the M1a–M1c exception shapes, exactly as those plans define them. Each is a sealed `RuntimeException` whose `message` is the ported prose:
  - `FlagSerializationException.NonFiniteNumber(key: FlagKey?)` (M1a Task A4)
  - `FlagSchemaException.UnsupportedFormatVersion(version: Long)`, `.Malformed(reason: String, cause: Throwable? = null)` and `.NotPublished(cause: Throwable? = null)` (M1b Task B1)
  - `FlagImportException.Malformed(reason: String)`, `.UnsupportedFormatVersion(version: Long)` and `.Rejected(problems: List<FlagImportProblem>)`, with `FlagImportProblem(key: FlagKey, kind: FlagImportProblem.Kind, expected: String? = null, found: String? = null)` and `enum class Kind { UnknownKey, TypeMismatch, UnknownCase }` (M1c Task C4)
  - `FlagQRCodeException.PayloadTooLarge(bytes: Int, limit: Int, overrideCount: Int)`, `.UnrecognisedFormat()` and `.Corrupt()` (M1c Task C5)
  - `FlagException.NoMutableSource()` and `.UnsupportedValue(key: FlagKey)` (M1c Task C1)
  - `FlagPayload.decode(bytes, schema)` (M1c Task C4) and `FlagSchema.of(spec)` (M1b Task B1)
  - plus D1–D4.
- Produces: no production API.

M1a Task A2's generator (`MessageFixtures.swift`) writes `fixtures/messages/<name>.txt` as `String(describing: error)` of these Swift values, with no trailing newline. Names follow the generator's one scheme, `<SwiftErrorType>.<case>[.<variant>]`, which M1a Task A4 and M1b Task B1 also read. **This table is the generator's message list**, row for row:

| name | Swift value |
|---|---|
| `RemoteOverrideError.malformed` | `RemoteOverrideError.malformed("not valid JSON")` |
| `RemoteOverrideError.rejected.empty` | `RemoteOverrideError.rejected([])` |
| `RemoteOverrideError.rejected.one` | `.rejected([RemoteOverrideProblem(key: "new-onboarding", remoteKey: "featureToggles.onboarding", kind: .typeMismatch, expected: "bool", found: "\"yes\"")])` |
| `RemoteOverrideError.rejected.bare` | `.rejected([RemoteOverrideProblem(key: "a", remoteKey: "x.a", kind: .typeMismatch), RemoteOverrideProblem(key: "b", remoteKey: "x.b", kind: .unknownCase), RemoteOverrideProblem(key: "new-onbaording", remoteKey: "new-onbaording", kind: .unknownKey)])` |
| `RemoteOverrideError.rejected.documented` | `.rejected([(checkout-tier, config.tier, .unknownCase, "free, pro", "\"gold\""), (new-onboarding, featureToggles.onboarding, .typeMismatch, "bool", "\"yes\""), (page-size, config.pageSize, .typeMismatch, "int", "an array of 3")])` |
| `RemoteOverrideError.rejected.decoded` | the error `RemoteOverrideSource(GoldenFlags.self).apply(_:format:)` throws for `MessageFixtures.remoteError()`'s payload (`GOLDEN_REMOTE_PAYLOAD` below) |
| `FlagImportError.malformed` | `FlagImportError.malformed("not valid JSON")` |
| `FlagImportError.unsupportedFormatVersion` | `FlagImportError.unsupportedFormatVersion(9)` |
| `FlagImportError.rejected.empty` | `FlagImportError.rejected([])` |
| `FlagImportError.rejected.one` | `.rejected([FlagImportProblem(key: "page-size", kind: .typeMismatch, expected: "int", found: "\"big\"")])` |
| `FlagImportError.rejected.unknownKey` | `.rejected([FlagImportProblem(key: "nope", kind: .unknownKey)])` |
| `FlagImportError.rejected.bare` | `.rejected([FlagImportProblem(key: "a", kind: .typeMismatch), FlagImportProblem(key: "b", kind: .unknownCase), FlagImportProblem(key: "c", kind: .unknownKey)])` |
| `FlagImportError.rejected.decoded` | the error `FlagPayload.decode` throws for `MessageFixtures.decodeError()`'s document against the `GoldenFlags` schema (`GOLDEN_IMPORT_DOCUMENT` below) |
| `FlagSerializationError.nonFiniteNumber` | `FlagSerializationError.nonFiniteNumber("ratio")` |
| `FlagSchemaError.unsupportedFormatVersion` | `FlagSchemaError.unsupportedFormatVersion(9)` |
| `FlagSchemaError.malformed` | `FlagSchemaError.malformed("missing formatVersion")` |
| `FlagQRCodeError.payloadTooLarge.many` | `FlagQRCodeError.payloadTooLarge(bytes: 3400, limit: 2900, overrideCount: 12)` |
| `FlagQRCodeError.payloadTooLarge.one` | `FlagQRCodeError.payloadTooLarge(bytes: 3400, limit: 2900, overrideCount: 1)` |
| `FlagQRCodeError.unrecognisedFormat` | `FlagQRCodeError.unrecognisedFormat` |
| `FlagQRCodeError.corrupt` | `FlagQRCodeError.corrupt` |
| `FlagMappingAuditError.incomplete` | the error from `try FlagMappingAudit(GoldenFlags.self, applying: Data("{}".utf8)).requireComplete()` |
| `FlagSchemaError.notPublished`, `FlagError.noMutableSource`, `FlagError.unsupportedValue` | Android rewords these on purpose, so they are fragment-checked, not byte-compared |
| `FlagQRCodeError.imageGenerationFailed`, `FlagSignalError.notAcknowledged`, `FlagSignalError.unavailableAppGroup` | these features are dropped in M1, so they are ignored |

- [ ] **Step 1: Write the test**

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/ErrorMessageTest.kt`
```kotlin
package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.audit.FlagMappingAuditException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.remote.shortDescription
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal enum class MessageTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

@FlagRecord
internal data class MessageEndpoint(val name: String, val port: Int)

@FlagContainer
internal interface MessageFlags {
    @Flag(description = "Onboarding", remoteKey = "featureToggles.onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Page size", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Tier", remoteKey = "config.tier")
    val checkoutTier: MessageTier get() = MessageTier.Free

    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<MessageEndpoint> get() = FlagRecords()
}

private val onboardingMismatch =
    RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding", Kind.TypeMismatch, "bool", "\"yes\"")

/** The report quoted in RemoteOverrides.md, "What a rejection tells you". */
private val documentedProblems = listOf(
    RemoteOverrideProblem(FlagKey("checkout-tier"), "config.tier", Kind.UnknownCase, "free, pro", "\"gold\""),
    RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding", Kind.TypeMismatch, "bool", "\"yes\""),
    RemoteOverrideProblem(FlagKey("page-size"), "config.pageSize", Kind.TypeMismatch, "int", "an array of 3"),
)

/** `MessageFixtures.decodeError()`'s document, imported into `GoldenFlags` on both sides. */
private val GOLDEN_IMPORT_DOCUMENT = """
    { "formatVersion": 1, "exportedAt": "2026-01-01T00:00:00Z", "values": {
        "int-flag": "a very long string with a \n newline, a \t tab and a \\ backslash in it",
        "bool-flag": 1,
        "double-flag": "1.5",
        "unknown-flag": [1, 2],
        "tier": "platinum",
        "level": 7,
        "limits": { "a": "x" },
        "markets": { "a": 1, "b": 2 },
        "date-flag": "2026-13-01T00:00:00Z",
        "endpoints": "[{\"name\":\"a\",\"url\":\"https://a.example\"},{\"name\":\"a\",\"url\":\"https://b.example\"}]",
        "checkout.tier": true,
        "url-flag": "",
        "data-flag": "not base64!"
    } }
""".trimIndent()

/** `MessageFixtures.remoteError()`'s payload, applied to `GoldenFlags` on both sides. */
private val GOLDEN_REMOTE_PAYLOAD = """
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
""".trimIndent()

/** Messages Android words differently on purpose (Global Constraints). They are checked by fragment below. */
private val REWORDED = setOf("FlagSchemaError.notPublished", "FlagError.noMutableSource", "FlagError.unsupportedValue")

/** Messages for features M1 does not have. */
private val DROPPED =
    setOf("FlagQRCodeError.imageGenerationFailed", "FlagSignalError.notAcknowledged", "FlagSignalError.unavailableAppGroup")

/**
 * What a developer actually reads when something goes wrong: a port of ErrorMessageTests.swift,
 * plus a byte comparison against every Swift-generated message golden.
 */
class ErrorMessageTest {

    private val messagesDirectory =
        File(requireNotNull(System.getProperty("featureflag.fixtures")) { "featureflag.fixtures is not set" }, "messages")

    private val messageSchema = FlagSchema.of(MessageFlagsSpec)

    private fun importFailure(document: String, schema: FlagSchema = messageSchema): FlagImportException =
        assertFailsWith<FlagImportException> { FlagPayload.decode(document.toByteArray(), schema) }

    /** `message` and `localizedMessage` must both say it, and neither may leak a type name. */
    private fun assertSays(error: Throwable, vararg fragments: String) {
        for (candidate in listOf(error.message, error.localizedMessage)) {
            val message = assertNotNull(candidate)
            for (fragment in fragments) assertTrue(message.contains(fragment), "expected \"$fragment\" in: $message")
            assertFalse(message.contains("com.andyyhope"), "a type name leaked into: $message")
            assertFalse(message.contains("couldn’t be completed"), "a system placeholder leaked into: $message")
        }
    }

    /** The Kotlin exception for each golden, built the way the generator built the Swift one. */
    private val kotlinFor: Map<String, () -> Throwable> = mapOf(
        "RemoteOverrideError.malformed" to { RemoteOverrideException.Malformed("not valid JSON") },
        "RemoteOverrideError.rejected.empty" to { RemoteOverrideException.Rejected(emptyList()) },
        "RemoteOverrideError.rejected.one" to { RemoteOverrideException.Rejected(listOf(onboardingMismatch)) },
        "RemoteOverrideError.rejected.bare" to {
            RemoteOverrideException.Rejected(
                listOf(
                    RemoteOverrideProblem(FlagKey("a"), "x.a", Kind.TypeMismatch),
                    RemoteOverrideProblem(FlagKey("b"), "x.b", Kind.UnknownCase),
                    RemoteOverrideProblem(FlagKey("new-onbaording"), "new-onbaording", Kind.UnknownKey),
                ),
            )
        },
        "RemoteOverrideError.rejected.documented" to { RemoteOverrideException.Rejected(documentedProblems) },
        "RemoteOverrideError.rejected.decoded" to {
            assertFailsWith<RemoteOverrideException.Rejected> {
                RemoteOverrideSource(GoldenFlagsSpec).apply(GOLDEN_REMOTE_PAYLOAD.toByteArray())
            }
        },
        "FlagImportError.malformed" to { FlagImportException.Malformed("not valid JSON") },
        "FlagImportError.unsupportedFormatVersion" to { FlagImportException.UnsupportedFormatVersion(9) },
        "FlagImportError.rejected.empty" to { FlagImportException.Rejected(emptyList()) },
        "FlagImportError.rejected.one" to { importFailure("""{"formatVersion":1,"values":{"page-size":"big"}}""") },
        "FlagImportError.rejected.unknownKey" to { importFailure("""{"formatVersion":1,"values":{"nope":true}}""") },
        "FlagImportError.rejected.bare" to {
            FlagImportException.Rejected(
                listOf(
                    FlagImportProblem(FlagKey("a"), FlagImportProblem.Kind.TypeMismatch),
                    FlagImportProblem(FlagKey("b"), FlagImportProblem.Kind.UnknownCase),
                    FlagImportProblem(FlagKey("c"), FlagImportProblem.Kind.UnknownKey),
                ),
            )
        },
        "FlagImportError.rejected.decoded" to { importFailure(GOLDEN_IMPORT_DOCUMENT, FlagSchema.of(GoldenFlagsSpec)) },
        "FlagSerializationError.nonFiniteNumber" to { FlagSerializationException.NonFiniteNumber(FlagKey("ratio")) },
        "FlagSchemaError.unsupportedFormatVersion" to { FlagSchemaException.UnsupportedFormatVersion(9) },
        "FlagSchemaError.malformed" to { FlagSchemaException.Malformed("missing formatVersion") },
        "FlagQRCodeError.payloadTooLarge.many" to { FlagQRCodeException.PayloadTooLarge(3400, 2900, 12) },
        "FlagQRCodeError.payloadTooLarge.one" to { FlagQRCodeException.PayloadTooLarge(3400, 2900, 1) },
        "FlagQRCodeError.unrecognisedFormat" to { FlagQRCodeException.UnrecognisedFormat() },
        "FlagQRCodeError.corrupt" to { FlagQRCodeException.Corrupt() },
        "FlagMappingAuditError.incomplete" to {
            assertFailsWith<FlagMappingAuditException.Incomplete> {
                FlagMappingAudit(GoldenFlagsSpec, "{}".toByteArray()).requireComplete()
            }
        },
    )

    // Goldens

    @Test
    fun everyMessageGoldenIsAccountedFor() {
        val names = messagesDirectory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".txt") }
            .map { it.removeSuffix(".txt") }
            .toSet()
        val unaccounted = names - kotlinFor.keys - REWORDED - DROPPED
        assertTrue(unaccounted.isEmpty(), "goldens with no Kotlin counterpart: $unaccounted")
        val missing = kotlinFor.keys - names
        assertTrue(missing.isEmpty(), "Kotlin messages the generator did not write: $missing")
    }

    @Test
    fun everyMessageMatchesItsGoldenByteForByte() {
        for ((name, make) in kotlinFor) {
            assertEquals(File(messagesDirectory, "$name.txt").readText().removeSuffix("\n"), make().message, name)
        }
    }

    // Remote overrides

    @Test
    fun aRejectedRemotePayloadNamesTheValueAndWhatWasExpected() {
        assertSays(
            RemoteOverrideException.Rejected(listOf(onboardingMismatch)),
            "featureToggles.onboarding", "new-onboarding", "bool", "\"yes\"", "Nothing was applied",
        )
    }

    @Test
    fun aRejectedPayloadSaysHowManyProblemsThereWere() {
        val problems = listOf(
            RemoteOverrideProblem(FlagKey("a"), "x.a", Kind.TypeMismatch),
            RemoteOverrideProblem(FlagKey("b"), "x.b", Kind.TypeMismatch),
        )
        assertSays(RemoteOverrideException.Rejected(problems), "2 problems")
    }

    @Test
    fun anUnknownCaseListsTheOnesThatWouldHaveWorked() {
        val problem = RemoteOverrideProblem(FlagKey("tier"), "config.tier", Kind.UnknownCase, "free, pro", "\"gold\"")
        assertSays(RemoteOverrideException.Rejected(listOf(problem)), "\"gold\"", "free, pro")
    }

    @Test
    fun anUnknownKeyPointsAtTheMapperRatherThanTheBackend() {
        val problem = RemoteOverrideProblem(FlagKey("new-onbaording"), "new-onbaording", Kind.UnknownKey)
        assertSays(RemoteOverrideException.Rejected(listOf(problem)), "new-onbaording", "mapper")
    }

    @Test
    fun aMalformedPayloadSaysWhatWasWrongWithIt() {
        assertSays(RemoteOverrideException.Malformed("not valid JSON"), "not valid JSON")
    }

    // Values that fight the layout

    @Test
    fun aValueContainingNewlinesStaysOnOneLine() {
        val found = RemoteValue.String("line one\nline two").shortDescription
        val message = assertNotNull(
            RemoteOverrideException.Rejected(listOf(RemoteOverrideProblem(FlagKey("k"), "r", Kind.TypeMismatch, "int", found))).message,
        )
        assertEquals(1, message.split("\n").count { "•" in it })
        assertTrue(message.contains("line one\\nline two"))
    }

    @Test
    fun aLongValueSaysThatItWasCutShort() {
        val long = RemoteValue.String("A".repeat(200)).shortDescription
        assertTrue(long.endsWith("…\""), long)
        assertTrue(long.length < 60, long)
    }

    @Test
    fun aValueThatFitsIsNotMarkedAsCutShort() {
        assertEquals("\"short\"", RemoteValue.String("short").shortDescription)
    }

    @Test
    fun theSameIsTrueOfValuesFromADocument() {
        // Swift checks flagShortDescription(of:) directly; here the same rendering runs through a real import.
        val long = importFailure("""{"formatVersion":1,"values":{"page-size":"${"A".repeat(200)}"}}""")
        assertTrue(assertNotNull(long.message).contains("…\""), long.message)
        val multiline = importFailure("""{"formatVersion":1,"values":{"page-size":"line one\nline two"}}""")
        assertEquals(1, assertNotNull(multiline.message).split("\n").count { "•" in it })
    }

    // Degenerate input

    @Test
    fun aRejectionWithNoProblemsStillReadsAsASentence() {
        val remote = assertNotNull(RemoteOverrideException.Rejected(emptyList()).message)
        assertFalse(remote.contains("0 problems"), remote)
        assertFalse(remote.endsWith(":"), remote)
        val imported = assertNotNull(FlagImportException.Rejected(emptyList()).message)
        assertFalse(imported.contains("0 problems"), imported)
    }

    // Import

    @Test
    fun aRejectedImportNamesTheFlagAndTheProblem() {
        assertSays(importFailure("""{"formatVersion":1,"values":{"page-size":"big"}}"""), "page-size", "int", "\"big\"", "Nothing was applied")
    }

    @Test
    fun anUnsupportedDocumentVersionSaysWhichBuildIsBehind() {
        assertSays(importFailure("""{"formatVersion":9,"values":{}}"""), "9", "1")
    }

    // Schema

    @Test
    fun anUnpublishedSchemaSaysWhatToGoAndDo() {
        // Swift also names the App Group; Android has none, so M1c rewords that half.
        assertSays(FlagSchemaException.NotPublished(), "publishSchema")
    }

    @Test
    fun aMalformedSchemaSaysWhatWasWrongWithIt() {
        assertSays(FlagSchemaException.Malformed("missing formatVersion"), "missing formatVersion")
    }

    // QR codes

    @Test
    fun anOversizedCodeSaysHowFarOverItIs() {
        assertSays(FlagQRCodeException.PayloadTooLarge(3400, 2900, 12), "12", "3400", "2900")
    }

    @Test
    fun anUnrecognisedCodeSaysWhatAFlagCodeLooksLike() {
        assertSays(FlagQRCodeException.UnrecognisedFormat(), "FFQR1:")
    }

    @Test
    fun aCorruptCodeBlamesThePartialScanItUsuallyIs() {
        assertSays(FlagQRCodeException.Corrupt(), "scan")
    }

    // The messages the real paths actually produce

    @Test
    fun aRealRemoteRejectionNamesThePathTheValueAndTheType() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(MessageFlagsSpec).apply("""{"featureToggles":{"onboarding":"yes"}}""".toByteArray())
        }
        assertSays(error, "featureToggles.onboarding", "new-onboarding", "expected bool", "\"yes\"", "Nothing was applied")
    }

    @Test
    fun aRealUnknownCaseListsTheCasesThisBuildHas() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(MessageFlagsSpec).apply("""{"config":{"tier":"gold"}}""".toByteArray())
        }
        assertSays(error, "checkout-tier", "\"gold\"", "free, pro")
    }

    @Test
    fun theDocumentedRejectionReportIsWhatARealPayloadProduces() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(MessageFlagsSpec).apply(
                """{"featureToggles":{"onboarding":"yes"},"config":{"pageSize":[1,2,3],"tier":"gold"}}""".toByteArray(),
            )
        }
        assertEquals(documentedProblems, error.problems)
        assertEquals(
            "Nothing was applied. 3 problems:\n" +
                "  • 'config.tier' → checkout-tier: \"gold\" is not one of free, pro\n" +
                "  • 'featureToggles.onboarding' → new-onboarding: expected bool, got \"yes\"\n" +
                "  • 'config.pageSize' → page-size: expected int, got an array of 3\n" +
                "A remote payload is all or nothing, so one bad value leaves every other flag reading whatever sits below this source.",
            error.message,
        )
    }

    @Test
    fun aRealImportRejectionNamesTheFlagTheValueAndTheType() {
        assertSays(importFailure("""{"formatVersion":1,"values":{"page-size":"big"}}"""), "page-size", "expected int", "\"big\"")
    }

    @Test
    fun aRealRecordRejectionSaysWhichFieldsWereExpected() {
        assertSays(
            importFailure("""{"formatVersion":1,"values":{"endpoints":"nonsense"}}"""),
            "endpoints", "a list of records", "name, port", "\"nonsense\"",
        )
    }

    // Sources and serialisation

    @Test
    fun writingWithNothingWritableSaysWhatToAdd() {
        // Swift names UserDefaultsSource; Android's noun differs, and SnapshotSource is common to both.
        assertSays(FlagException.NoMutableSource(), "SnapshotSource")
    }

    @Test
    fun aRefusedValueNamesTheFlag() {
        assertSays(FlagException.UnsupportedValue(FlagKey("page-size")), "page-size")
    }

    @Test
    fun aNonFiniteNumberNamesTheFlagAndWhyJsonCannotTakeIt() {
        assertSays(FlagSerializationException.NonFiniteNumber(FlagKey("ratio")), "ratio", "JSON")
    }
}
```

- [ ] **Step 2: Run it**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ls fixtures/messages
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.errors.ErrorMessageTest'
```
Expected: `BUILD SUCCESSFUL`, 27 tests passed. The `ls` lists exactly the names in the table above. There is no implementation step: D1–D4 already implement the remote and audit messages, and M1a–M1c wrote the rest, so this task is the cross-check that none of them drifted from Swift's prose. A failure of `everyMessageMatchesItsGoldenByteForByte` names the golden and shows both strings; fix the string-building code of the exception it names (never the golden or this test), and add that file to the commit. A failure of `everyMessageGoldenIsAccountedFor` means `fixtures/messages` and the table above disagree: `fixtures/` is older than M1a Task A2's generator, so run `scripts/sync-fixtures.sh`.

- [ ] **Step 3: Run the whole build**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`, and the whole core suite stays green.

- [ ] **Step 4: Commit**
```bash
git add featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/ErrorMessageTest.kt
git commit -m "Check every error message against the Swift goldens" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task D6: `featureflag-test`: `assertFlagsFullyMapped`

**Files:**
- Create: `featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMapped.kt`
- Test: `featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMappedTest.kt`

**Interfaces:**
- Consumes:
  - D4: `FlagMappingAudit(spec, json, mapper, keyEncoding)`, `isComplete(strict, ignoring)` and `description(strict, ignoring)`.
  - `RemoteOverrideException`.
  - Public contract only, for the hand-written test spec: `FlagContainerSpec<F>`, `FlagLookup`, `FlagKeyPath`, `FlagSchemaNode.Flag`, `FlagDescriptor.of(propertyName, description, remoteKey, codec, default)`, `FlagCodecs.boolean`/`int`/`stringEnum`, `FlagStringEnum`.
- Produces (contract signature):
```kotlin
public fun assertFlagsFullyMapped(spec: FlagContainerSpec<*>, payload: ByteArray, mapper: RemoteOverrideMapper = DotPathMapper,
    keyEncoding: KeyEncoding = KeyEncoding.Kebabcase, strict: Boolean = false, ignoring: List<String> = emptyList())
// throws AssertionError("The payload could not be read: <message>") or AssertionError("\n" + audit.description(strict, ignoring))
```

- [ ] **Step 1: Write the failing test.** It ports `XCTAssertFlagsFullyMappedTests.swift` and adds the two failure texts.

`featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMappedTest.kt`
```kotlin
package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagStringEnum
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal enum class HelperTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

internal interface HelperFlags {
    val newOnboarding: Boolean
    val pageSize: Int
    val checkoutTier: HelperTier
}

/**
 * Written by hand because this module's tests do not run the KSP processor. An audit reads
 * only the descriptors, so the flags this creates are just the defaults.
 */
internal object HelperFlagsSpec : FlagContainerSpec<HelperFlags> {
    override val flagContainerDescription: String? = null

    override val flagDescriptors: List<FlagSchemaNode> = listOf(
        FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "Onboarding", "featureToggles.onboarding", FlagCodecs.boolean, false)),
        FlagSchemaNode.Flag(FlagDescriptor.of("pageSize", "Page size", "config.pageSize", FlagCodecs.int, 10)),
        FlagSchemaNode.Flag(
            FlagDescriptor.of("checkoutTier", "Tier", "config.tier", FlagCodecs.stringEnum(HelperTier.entries), HelperTier.Free),
        ),
    )

    override val flagDefaults: HelperFlags = object : HelperFlags {
        override val newOnboarding = false
        override val pageSize = 10
        override val checkoutTier = HelperTier.Free
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): HelperFlags = flagDefaults
}

class AssertFlagsFullyMappedTest {

    private val complete = """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" } }"""

    private val withMetadata =
        """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }"""

    @Test
    fun aCompletePayloadPasses() {
        assertFlagsFullyMapped(HelperFlagsSpec, complete.toByteArray())
    }

    @Test
    fun strictWithIgnoredMetadataPasses() {
        assertFlagsFullyMapped(HelperFlagsSpec, withMetadata.toByteArray(), strict = true, ignoring = listOf("meta"))
    }

    @Test
    fun unconsumedMetadataPassesUnlessStrict() {
        assertFlagsFullyMapped(HelperFlagsSpec, withMetadata.toByteArray())
        val error = assertFailsWith<AssertionError> {
            assertFlagsFullyMapped(HelperFlagsSpec, withMetadata.toByteArray(), strict = true)
        }
        assertEquals(
            "\nFlag mapping audit — incomplete.\n" +
                "  unconsumed (1) — no flag reads these:\n" +
                "    • meta.version\n" +
                "  3 of 3 remotely-overridable flags applied.",
            error.message,
        )
    }

    @Test
    fun anAbsentFlagFailsWithTheWholeReport() {
        val payload = """{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""".toByteArray()
        val error = assertFailsWith<AssertionError> { assertFlagsFullyMapped(HelperFlagsSpec, payload) }
        assertEquals("\n" + FlagMappingAudit(HelperFlagsSpec, payload), error.message)
        assertEquals(
            "\nFlag mapping audit — incomplete.\n" +
                "  absent (1) — a flag declares this path, the payload has nothing there:\n" +
                "    • page-size\n" +
                "  unconsumed (1) — usually fine, often backend metadata — no flag reads these:\n" +
                "    • config.page_size\n" +
                "  2 of 3 remotely-overridable flags applied.",
            error.message,
        )
    }

    @Test
    fun anUnreadablePayloadSaysSo() {
        val error =
            assertFailsWith<AssertionError> { assertFlagsFullyMapped(HelperFlagsSpec, "{ not json".toByteArray()) }
        assertEquals("The payload could not be read: The remote payload could not be read: not valid JSON.", error.message)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-test:test --tests 'com.andyyhope.featureflag.test.AssertFlagsFullyMappedTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'assertFlagsFullyMapped'`. If the errors are instead `Unresolved reference 'junit'`, `'test'` or `'audit'`, the module lacks its dependencies. Add these to `featureflag-test/build.gradle.kts` inside `dependencies { }`, using the aliases M1a's `gradle/libs.versions.toml` declares for JUnit 4.13.2 and kotlin-test-junit: `api(project(":featureflag-core"))`, plus `testImplementation` of each of those two. Then rerun.

- [ ] **Step 3: Write the implementation**

`featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMapped.kt`
```kotlin
package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * Fails with an [AssertionError] unless a payload wires up every flag it should. Any test
 * framework reports that as a failure: JUnit 4 or 5, kotlin.test, Kotest.
 *
 * ```kotlin
 * @Test fun stagingConfigMapsEveryFlag() =
 *     assertFlagsFullyMapped(AppFlagsSpec, File("staging.json").readBytes(), strict = true, ignoring = listOf("meta"))
 * ```
 *
 * The failure message is the whole audit, so a large config reports every problem in one run.
 *
 * @param strict also require every value in the payload to be read by a flag.
 * @param ignoring path prefixes whose unconsumed values are allowed under [strict].
 */
public fun assertFlagsFullyMapped(
    spec: FlagContainerSpec<*>,
    payload: ByteArray,
    mapper: RemoteOverrideMapper = DotPathMapper,
    keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    strict: Boolean = false,
    ignoring: List<String> = emptyList(),
) {
    val audit = try {
        FlagMappingAudit(spec, payload, mapper, keyEncoding)
    } catch (error: RemoteOverrideException) {
        throw AssertionError("The payload could not be read: ${error.message}", error)
    }
    if (!audit.isComplete(strict, ignoring)) {
        throw AssertionError("\n" + audit.description(strict, ignoring))
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-test:test
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit.** If Step 2 needed the dependency lines, also add `featureflag-test/build.gradle.kts`.
```bash
git add featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMapped.kt featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/AssertFlagsFullyMappedTest.kt
git commit -m "Add assertFlagsFullyMapped to featureflag-test" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task D7: `FlagSignal`, scenario ports, documentation examples, full verification

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/signals/FlagSignal.kt`
- Create (test support): `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/DemoFlags.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/DemoConfigurations.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/signals/FlagSignalTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/ScenarioTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/RemoteDocumentationExampleTest.kt`

**Interfaces:**
- Consumes:
  - D1–D4.
  - `FlagPole(spec, sources, keyEncoding)` with `flags`, `resolution(key, type)` and `setOverride`.
  - `SnapshotSource(name)` and `setBox(key, box)`.
  - `FlagSchema.of`.
  - M1c transport extensions `FlagPole.importJson(bytes)` and `FlagPole.exportJson()`, in package `com.andyyhope.featureflag.transport`.
- Produces:
```kotlin
public interface FlagSignal {
    public val name: String
    public val signalDescription: String get() = name
    public val requiresRestart: Boolean get() = false
}
```

#### D7.1 `FlagSignal`

- [ ] **Step 1: Write the failing test.** It covers the `SendingSignals.md` samples and `Examples/DemoApp/AppSignals.swift`.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/signals/FlagSignalTest.kt`
```kotlin
package com.andyyhope.featureflag.signals

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private enum class BareAppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    SignOut,
}

private enum class LabelledAppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    ;

    override val signalDescription: String
        get() = when (this) {
            RefetchRemoteConfiguration -> "Re-fetch remote config"
            PurgeImageCache -> "Purge image cache"
        }
}

/** The demo's second group: emptying everything takes hold only after a relaunch. */
private enum class CacheSignal : FlagSignal {
    PurgeImageCache,
    PurgeEverything,
    ;

    override val signalDescription: String
        get() = when (this) {
            PurgeImageCache -> "Purge the image cache"
            PurgeEverything -> "Purge everything"
        }

    override val requiresRestart: Boolean get() = this == PurgeEverything
}

class FlagSignalTest {

    @Test
    fun anEnumsOwnNameIsTheSignalAndItsDefaultLabel() {
        assertEquals("RefetchRemoteConfiguration", BareAppSignal.RefetchRemoteConfiguration.name)
        assertEquals("RefetchRemoteConfiguration", BareAppSignal.RefetchRemoteConfiguration.signalDescription)
        assertEquals(3, BareAppSignal.entries.size)
    }

    @Test
    fun aSignalCanCarryAReadableLabel() {
        assertEquals("Re-fetch remote config", LabelledAppSignal.RefetchRemoteConfiguration.signalDescription)
        assertEquals("Purge image cache", LabelledAppSignal.PurgeImageCache.signalDescription)
    }

    @Test
    fun aSignalNeedsNoRelaunchUnlessItSaysSo() {
        assertFalse(BareAppSignal.SignOut.requiresRestart)
        assertFalse(CacheSignal.PurgeImageCache.requiresRestart)
        assertTrue(CacheSignal.PurgeEverything.requiresRestart)
        assertEquals("Purge everything", CacheSignal.PurgeEverything.signalDescription)
    }

    @Test
    fun aSignalNeedNotBeAnEnum() {
        val signal = object : FlagSignal {
            override val name = "refetch"
        }
        assertEquals("refetch", signal.signalDescription)
        assertFalse(signal.requiresRestart)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.signals.FlagSignalTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'FlagSignal'`.

- [ ] **Step 3: Write the implementation**

`featureflag-core/src/main/kotlin/com/andyyhope/featureflag/signals/FlagSignal.kt`
```kotlin
package com.andyyhope.featureflag.signals

/**
 * An instruction a companion app can send to its host, such as re-fetching the remote
 * config or purging a cache.
 *
 * Declare signals as an enum. Its own `name` satisfies [name], and the host gets an
 * exhaustive `when`. Signals carry no payload: state belongs in flags, which the companion
 * can already edit. The channel that delivers them arrives with `featureflag-android` (M2).
 */
public interface FlagSignal {

    /** The identifier sent over the wire. An enum's own `name` satisfies this. */
    public val name: String

    /** What a companion puts on the button. Defaults to [name]. */
    public val signalDescription: String get() = name

    /**
     * Whether the host must be relaunched before this takes effect. Defaults to `false`.
     * When it is true, a companion can say "handled — relaunch to see it" rather than "handled".
     */
    public val requiresRestart: Boolean get() = false
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.signals.FlagSignalTest'
```
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/signals/FlagSignal.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/signals/FlagSignalTest.kt
git commit -m "Add the FlagSignal interface" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D7.2 The DemoApp scenario

- [ ] **Step 1: Write the failing test.** It ports all five `Tests/DemoExamplesTests` files, against a Kotlin port of `Examples/DemoApp` that Step 3 creates.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/ScenarioTest.kt`
```kotlin
package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.EnvironmentConfiguration
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DemoApp's flags, payloads, mapper and environment layering, played out without an app. */
class ScenarioTest {

    private class Stack(val pole: FlagPole<DemoFlags>, val remote: RemoteOverrideSource, val local: SnapshotSource)

    private fun makeStack(): Stack {
        val local = SnapshotSource("Companion")
        val remote = RemoteOverrideSource(DemoFlagsSpec, sourceName = "Remote")
        return Stack(FlagPole(DemoFlagsSpec, listOf(local, remote)), remote, local)
    }

    private fun makeConfig(): EnvironmentConfiguration<DemoEnvironment> = EnvironmentConfiguration(
        DemoFlagsSpec,
        mapper = DemoRemoteMapper,
        localName = "Local",
        remoteName = "Remote",
        local = { LocalConfiguration.forEnvironment(it).data },
        remote = { RemoteConfiguration.forEnvironment(it).data },
    )

    private val bundled = listOf(RemoteConfiguration.production, RemoteConfiguration.staging, RemoteConfiguration.local)

    private fun key(raw: String) = FlagKey(raw)

    // RemoteConfigurationTests.swift

    @Test
    fun everyBundledPayloadIsValidJson() {
        val configurations = DemoEnvironment.entries.map { RemoteConfiguration.forEnvironment(it) }
        for (configuration in configurations + RemoteConfiguration.malformed) {
            RemoteValue.parseJson(configuration.data)
        }
    }

    @Test
    fun stagingReachesEveryFlagItClaimsTo() {
        val stack = makeStack()
        val result = stack.remote.apply(RemoteConfiguration.staging.data)

        assertEquals(emptyList(), result.absentKeys, "unmatched paths")
        assertEquals(listOf("checkout.apple-pay", "checkout.endpoint", "new-onboarding", "payment-methods").map(::FlagKey), result.appliedKeys)
        assertTrue(stack.pole.flags.newOnboarding)
        assertTrue(stack.pole.flags.checkout.applePay)
        assertEquals("staging.api.example.com", stack.pole.flags.checkout.endpoint.host)
    }

    @Test
    fun aPayloadReplacesTheWholeListOfRecords() {
        val stack = makeStack()
        assertEquals(3, stack.pole.flags.paymentMethods.size, "the compiled default")

        stack.remote.apply(RemoteConfiguration.local.data)

        assertEquals(listOf("Test card"), stack.pole.flags.paymentMethods.map { it.name })
        assertEquals(PaymentKind.Card, stack.pole.flags.paymentMethods.first().kind)
    }

    @Test
    fun eachEnvironmentBringsItsOwnPaymentMethods() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.production.data)
        assertEquals(listOf("Visa", "Mastercard"), stack.pole.flags.paymentMethods.map { it.name })

        stack.remote.apply(RemoteConfiguration.staging.data)
        assertEquals(listOf("Visa", "Apple Pay", "Bank transfer"), stack.pole.flags.paymentMethods.map { it.name })
        assertEquals(10.0, stack.pole.flags.paymentMethods.last().minimumSpend)
    }

    @Test
    fun aPayloadCanCarryANestedListOfRecords() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.staging.data)
        val transfer = stack.pole.flags.paymentMethods.first { it.name == "Bank transfer" }
        assertEquals(listOf("AUD", "NZD"), transfer.limits.map { it.currency })
        assertEquals(5000.0, transfer.limits.first().maximum)
    }

    @Test
    fun aPayloadThatOmitsANestedListGetsTheDeclaredDefault() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.production.data)
        assertEquals(listOf("Visa", "Mastercard"), stack.pole.flags.paymentMethods.map { it.name })
        assertTrue(stack.pole.flags.paymentMethods.first().limits.isEmpty())
    }

    @Test
    fun everyBundledPayloadSendsRecordsThisBuildCanRead() {
        for (configuration in bundled) makeStack().remote.apply(configuration.data)
    }

    @Test
    fun productionTurnsThingsOff() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.staging.data)
        assertTrue(stack.pole.flags.checkout.applePay)

        val result = stack.remote.apply(RemoteConfiguration.production.data)

        assertEquals(emptyList(), result.absentKeys)
        assertFalse(stack.pole.flags.checkout.applePay)
        assertFalse(stack.pole.flags.newOnboarding)
    }

    @Test
    fun theMalformedPayloadIsRejectedAndAppliesNothing() {
        val stack = makeStack()
        val error =
            assertFailsWith<RemoteOverrideException.Rejected> { stack.remote.apply(RemoteConfiguration.malformed.data) }
        assertEquals(listOf(Kind.TypeMismatch), error.problems.map { it.kind })
        assertEquals(listOf("featureToggles.onboarding.v2"), error.problems.map { it.remoteKey })
        assertFalse(stack.pole.flags.checkout.applePay, "all-or-nothing means nothing")
    }

    @Test
    fun onlyTheMalformedPayloadIsLabelledBroken() {
        assertTrue(RemoteConfiguration.malformed.isDeliberatelyBroken)
        for (environment in DemoEnvironment.entries) {
            assertFalse(RemoteConfiguration.forEnvironment(environment).isDeliberatelyBroken)
        }
    }

    @Test
    fun aCompanionOverrideSurvivesARemotePayload() {
        val stack = makeStack()
        stack.local.setBox(key("checkout.apple-pay"), FlagValueBox.Bool(true))
        stack.remote.apply(RemoteConfiguration.production.data)

        assertTrue(stack.pole.flags.checkout.applePay, "a hand-set override outranks the backend")
        assertEquals("Companion", stack.pole.resolution(key("checkout.apple-pay"), FlagValueType.Bool).sourceName)

        stack.local.setBox(key("checkout.apple-pay"), null)
        assertFalse(stack.pole.flags.checkout.applePay)
        assertEquals("Remote", stack.pole.resolution(key("checkout.apple-pay"), FlagValueType.Bool).sourceName)
    }

    // DemoRemoteMapperTests.swift

    @Test
    fun theDefaultMapperFindsNothingInARecordList() {
        val plain = RemoteOverrideSource(DemoFlagsSpec, sourceName = "Remote")
        val result = plain.apply(RemoteConfiguration.experiments.data)
        assertTrue(result.appliedKeys.isEmpty())
        assertNull(plain.box(key("new-onboarding"), FlagValueType.Bool))
    }

    @Test
    fun theCustomMapperReshapesTheSamePayload() {
        val source = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper, sourceName = "Remote")
        val result = source.apply(RemoteConfiguration.experiments.data)
        assertEquals(setOf(key("new-onboarding"), key("checkout.apple-pay")), result.appliedKeys.toSet())
        assertEquals(FlagValueBox.Bool(true), source.box(key("new-onboarding"), FlagValueType.Bool))
        assertEquals(FlagValueBox.Bool(true), source.box(key("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun theOrdinaryDotPathPayloadsStillApply() {
        for (configuration in bundled) {
            val result = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper).apply(configuration.data)
            assertFalse(result.appliedKeys.isEmpty(), "${configuration.name} should still apply through the dot paths")
        }
    }

    @Test
    fun aMisspelledFlagIsReportedRatherThanIgnored() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper).apply(RemoteConfiguration.experimentsWithATypo.data)
        }
        assertEquals(listOf(Kind.UnknownKey), error.problems.map { it.kind })
        assertEquals(listOf(key("new-onbaording")), error.problems.map { it.key })
    }

    @Test
    fun nothingIsAppliedWhenOneRecordIsWrong() {
        val source = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper)
        assertFailsWith<RemoteOverrideException.Rejected> { source.apply(RemoteConfiguration.experimentsWithATypo.data) }
        assertNull(source.box(key("checkout.apple-pay"), FlagValueType.Bool))
    }

    @Test
    fun aRecordMissingItsFieldsIsSkippedRatherThanCrashing() {
        val result = RemoteOverrideSource(DemoFlagsSpec, mapper = DemoRemoteMapper)
            .apply("""{"experiments": [{"flag": "new-onboarding"}, {"enabled": true}]}""".toByteArray())
        assertTrue(result.appliedKeys.isEmpty())
    }

    // EnvironmentDrivenConfigTests.swift

    @Test
    fun theEnvironmentFlagIsNotRemotelyOverridable() {
        val environment = FlagSchema.of(DemoFlagsSpec).flags.first { it.key == key("environment") }
        assertNull(environment.remoteKey, "giving environment a remoteKey lets a payload choose which payload gets fetched")
    }

    @Test
    fun aPayloadCannotChangeTheEnvironmentEvenIfItTries() {
        val stack = makeStack()
        stack.remote.apply("""{"environment": "staging", "featureToggles": {"onboarding": {"v2": true}}}""".toByteArray())
        assertEquals(DemoEnvironment.Production, stack.pole.flags.environment, "the backend does not get a vote")
        assertTrue(stack.pole.flags.newOnboarding, "the rest of the payload still applies")
    }

    @Test
    fun everyEnvironmentHasAPayloadThatMatchesEveryFlagItClaims() {
        for (environment in DemoEnvironment.entries) {
            val result = makeStack().remote.apply(RemoteConfiguration.forEnvironment(environment).data)
            assertEquals(emptyList(), result.absentKeys, "${environment.flagRawValue}: unmatched dot paths")
        }
    }

    @Test
    fun switchingEnvironmentSwitchesTheBackend() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.forEnvironment(DemoEnvironment.Production).data)
        assertEquals("api.example.com", stack.pole.flags.checkout.endpoint.host)
        assertFalse(stack.pole.flags.checkout.applePay)

        stack.local.setBox(key("environment"), FlagValueBox.String("staging"))
        stack.remote.apply(RemoteConfiguration.forEnvironment(stack.pole.flags.environment).data)

        assertEquals(DemoEnvironment.Staging, stack.pole.flags.environment)
        assertEquals("staging.api.example.com", stack.pole.flags.checkout.endpoint.host)
        assertTrue(stack.pole.flags.checkout.applePay)
    }

    @Test
    fun clearingFirstFallsBackToDefaultsRatherThanStaleValues() {
        val stack = makeStack()
        stack.remote.apply(RemoteConfiguration.forEnvironment(DemoEnvironment.Staging).data)
        assertEquals("staging.api.example.com", stack.pole.flags.checkout.endpoint.host)

        stack.remote.clear()

        assertEquals("api.example.com", stack.pole.flags.checkout.endpoint.host, "the compiled default, not staging's leftover value")
        assertTrue(stack.pole.resolution(key("checkout.endpoint"), FlagValueType.Url).isDefault)
    }

    @Test
    fun anOverrideSurvivesAnEnvironmentSwitch() {
        val stack = makeStack()
        stack.local.setBox(key("checkout.apple-pay"), FlagValueBox.Bool(true))
        stack.remote.apply(RemoteConfiguration.forEnvironment(DemoEnvironment.Production).data)
        assertTrue(stack.pole.flags.checkout.applePay)
        assertEquals("Companion", stack.pole.resolution(key("checkout.apple-pay"), FlagValueType.Bool).sourceName)

        stack.local.setBox(key("checkout.apple-pay"), null)

        assertFalse(stack.pole.flags.checkout.applePay, "production's value shows through once cleared")
    }

    // EnvironmentLayeringTests.swift

    @Test
    fun stagingLayersRemoteOverLocalOverDefault() = runTest {
        val config = makeConfig()
        val pole = FlagPole(DemoFlagsSpec, config.sources)

        assertTrue(config.load(DemoEnvironment.Staging).isComplete)

        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)
        assertEquals("Remote", pole.resolution(key("checkout.endpoint"), FlagValueType.Url).sourceName)
        assertEquals(10, pole.flags.pageSize)
        assertTrue(pole.resolution(key("page-size"), FlagValueType.Int).isDefault)
    }

    @Test
    fun clearingRemoteFallsBackToTheLocalLayerNotTheDefault() = runTest {
        val config = makeConfig()
        val pole = FlagPole(DemoFlagsSpec, config.sources)
        config.load(DemoEnvironment.Staging)
        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)

        config.remoteSource.clear()

        assertEquals("staging-local.example", pole.flags.checkout.endpoint.host)
        assertEquals("Local", pole.resolution(key("checkout.endpoint"), FlagValueType.Url).sourceName)
    }

    @Test
    fun switchingEnvironmentReplacesBothLayers() = runTest {
        val config = makeConfig()
        val pole = FlagPole(DemoFlagsSpec, config.sources)
        config.load(DemoEnvironment.Staging)
        assertEquals("staging.api.example.com", pole.flags.checkout.endpoint.host)

        config.load(DemoEnvironment.Local)

        assertEquals("localhost", pole.flags.checkout.endpoint.host)
    }

    // ConfigurationAuditTests.swift

    @Test
    fun everyBundledPayloadWiresUpEveryFlagItProvides() {
        for (configuration in bundled) {
            val audit = FlagMappingAudit(DemoFlagsSpec, configuration.data)
            assertTrue(audit.isComplete, "${configuration.name}:\n$audit")
            assertEquals(emptyList(), audit.absent, configuration.name)
        }
    }

    @Test
    fun everyBundledPayloadIsFullyConsumed() {
        // Swift calls XCTAssertFlagsFullyMapped(strict: true). featureflag-test depends on core,
        // so core's own tests assert the same verdict directly.
        for (configuration in bundled) FlagMappingAudit(DemoFlagsSpec, configuration.data).requireComplete(strict = true)
    }

    @Test
    fun theRecordListSubtreeCountsAsConsumed() {
        val audit = FlagMappingAudit(DemoFlagsSpec, RemoteConfiguration.staging.data)
        assertTrue(key("payment-methods") in audit.applied)
        assertEquals(emptyList(), audit.unconsumed.filter { "paymentMethods" in it })
    }

    @Test
    fun aMisspelledPathIsCaughtAsAbsentAndUnconsumed() {
        val broken = RemoteConfiguration.staging.json.replace("\"apiEndpoint\"", "\"api_endpoint\"")
        val audit = FlagMappingAudit(DemoFlagsSpec, broken.toByteArray())
        assertTrue(key("checkout.endpoint") in audit.absent, "$audit")
        assertTrue("config.api_endpoint" in audit.unconsumed, "$audit")
        assertFalse(audit.isComplete)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.scenario.ScenarioTest'
```
Expected: `BUILD FAILED`, with `Unresolved reference 'DemoFlagsSpec'`, `'RemoteConfiguration'`, `'LocalConfiguration'` and `'DemoRemoteMapper'`.

- [ ] **Step 3: Write the DemoApp port.** These are test sources.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/DemoFlags.kt`
```kotlin
package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

/** Which backend this build talks to. It has no remoteKey, so no payload can choose which payload gets fetched. */
internal enum class DemoEnvironment(override val flagRawValue: String) : FlagStringEnum {
    Production("production"),
    Staging("staging"),
    Local("local"),
}

internal enum class CheckoutTier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
    Enterprise("enterprise"),
}

internal enum class PaymentKind(override val flagRawValue: String) : FlagStringEnum {
    Card("card"),
    Wallet("wallet"),
    Transfer("transfer"),
}

/** A record nested inside another. */
@FlagRecord
internal data class SpendLimit(val currency: String, val maximum: Double)

@FlagRecord
internal data class PaymentMethod(
    @FlagRecordKey val name: String,
    val kind: PaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
    val limits: FlagRecords<SpendLimit> = FlagRecords(),
)

/**
 * A port of Examples/DemoApp/AppFlags.swift. `applePay` keeps its iOS name here; the M4
 * samples rename it to googlePay.
 */
@FlagContainer(description = "Everything this demo can turn on, and where each value came from")
internal interface DemoFlags {
    @Flag(description = "Which backend this build talks to")
    val environment: DemoEnvironment get() = DemoEnvironment.Production

    @Flag(description = "Show the redesigned onboarding flow", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "How many items to show per page")
    val pageSize: Int get() = 10

    @Flag(description = "Markets the app is live in")
    val markets: List<String> get() = listOf("AU", "NZ")

    @Flag(description = "Payment methods offered at checkout", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0),
                PaymentMethod("Apple Pay", PaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
                PaymentMethod("Bank transfer", PaymentKind.Transfer, enabled = false, minimumSpend = 50.0),
            ),
        )

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
internal interface CheckoutFlags {
    @Flag(description = "Offer Apple Pay at checkout", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = false

    @Flag(description = "Which pricing tier to present")
    val tier: CheckoutTier get() = CheckoutTier.Free

    @Flag(description = "Backend the app talks to", remoteKey = "config.apiEndpoint")
    val endpoint: URI get() = URI("https://api.example.com")

    @FlagGroup(description = "Express checkout")
    val express: ExpressFlags
}

@FlagContainer
internal interface ExpressFlags {
    @Flag(description = "Skip the review step")
    val oneTap: Boolean get() = false

    @Flag(description = "Seconds before the order is placed")
    val confirmationDelay: Double get() = 3.0
}
```

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/DemoConfigurations.kt`
```kotlin
package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey

/** A port of Examples/DemoApp/RemoteConfigurations.swift: payloads standing in for a fetch. */
internal data class RemoteConfiguration(
    val id: String,
    val name: String,
    val summary: String,
    val json: String,
    /** Expected to be rejected, so a UI can say so up front. */
    val isDeliberatelyBroken: Boolean,
) {
    val data: ByteArray get() = json.toByteArray()

    companion object {
        fun forEnvironment(environment: DemoEnvironment): RemoteConfiguration = when (environment) {
            DemoEnvironment.Production -> production
            DemoEnvironment.Staging -> staging
            DemoEnvironment.Local -> local
        }

        val production = RemoteConfiguration(
            id = "production",
            name = "Production",
            summary = "Conservative: new work off, live backend",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": false },
                    "checkout": { "applePay": false }
                  },
                  "config": {
                    "apiEndpoint": "https://api.example.com",
                    "paymentMethods": [
                      { "name": "Visa", "kind": "card", "enabled": true, "minimumSpend": 0 },
                      { "name": "Mastercard", "kind": "card", "enabled": true, "minimumSpend": 0 }
                    ]
                  }
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        val staging = RemoteConfiguration(
            id = "staging",
            name = "Staging",
            summary = "Everything on, pointed at the staging backend",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": true },
                    "checkout": { "applePay": true }
                  },
                  "config": {
                    "apiEndpoint": "https://staging.api.example.com",
                    "paymentMethods": [
                      { "name": "Visa", "kind": "card", "enabled": true, "minimumSpend": 0 },
                      { "name": "Apple Pay", "kind": "wallet", "enabled": true, "minimumSpend": 0 },
                      { "name": "Bank transfer", "kind": "transfer", "enabled": true, "minimumSpend": 10,
                        "limits": [
                          { "currency": "AUD", "maximum": 5000 },
                          { "currency": "NZD", "maximum": 5500 }
                        ] }
                    ]
                  }
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        val local = RemoteConfiguration(
            id = "local",
            name = "Local",
            summary = "Everything on, pointed at a machine on your desk",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": true },
                    "checkout": { "applePay": true }
                  },
                  "config": {
                    "apiEndpoint": "http://localhost:8080",
                    "paymentMethods": [
                      { "name": "Test card", "kind": "card", "enabled": true, "minimumSpend": 0 }
                    ]
                  }
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        /** One bad field; all-or-nothing means the valid applePay beside it is not applied either. */
        val malformed = RemoteConfiguration(
            id = "malformed",
            name = "Malformed payload",
            summary = """onboarding.v2 sends "yes" where a boolean belongs""",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": "yes" },
                    "checkout": { "applePay": true }
                  },
                  "config": { "apiEndpoint": "https://api.example.com" }
                }
            """.trimIndent(),
            isDeliberatelyBroken = true,
        )

        /** The flags arrive as a list, and a flag's name is a field rather than a key: no dot path reaches them. */
        val experiments = RemoteConfiguration(
            id = "experiments",
            name = "List of experiments",
            summary = "A shape no dot path can reach, reshaped by a custom mapper",
            json = """
                {
                  "experiments": [
                    { "flag": "new-onboarding", "enabled": true },
                    { "flag": "checkout.apple-pay", "enabled": true }
                  ]
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        /** The same shape with one flag misspelled, which only a custom mapper can produce. */
        val experimentsWithATypo = RemoteConfiguration(
            id = "experiments-typo",
            name = "List of experiments, misspelled",
            summary = "new-onbaording is not a flag this app has",
            json = """
                {
                  "experiments": [
                    { "flag": "new-onbaording", "enabled": true },
                    { "flag": "checkout.apple-pay", "enabled": true }
                  ]
                }
            """.trimIndent(),
            isDeliberatelyBroken = true,
        )
    }
}

/** A port of Examples/DemoApp/LocalConfiguration.swift: the config bundled for each environment. */
internal data class LocalConfiguration(val json: String) {
    val data: ByteArray get() = json.toByteArray()

    companion object {
        fun forEnvironment(environment: DemoEnvironment): LocalConfiguration = when (environment) {
            DemoEnvironment.Production -> production
            DemoEnvironment.Staging -> staging
            DemoEnvironment.Local -> local
        }

        val production = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "applePay": false } },
                  "config": { "apiEndpoint": "https://api.example.com" }
                }
            """.trimIndent(),
        )

        val staging = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "applePay": false } },
                  "config": { "apiEndpoint": "https://staging-local.example" }
                }
            """.trimIndent(),
        )

        val local = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "applePay": true } },
                  "config": { "apiEndpoint": "http://localhost:8080" }
                }
            """.trimIndent(),
        )
    }
}

/**
 * A port of DemoRemoteMapper.swift. It reads both shapes this backend has ever sent: the
 * dot-path one, by composing DotPathMapper, and a list of `{ "flag", "enabled" }` experiments.
 */
internal object DemoRemoteMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val records = payload.valueAt("experiments") as? RemoteValue.Array ?: return DotPathMapper.map(payload, schema)
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (record in records.value) {
            val name = record.valueAt("flag") as? RemoteValue.String
            val enabled = record.valueAt("enabled") as? RemoteValue.Bool
            // Named by the key the schema publishes, so a typo here is reported as an unknown key.
            if (name != null && enabled != null) result[FlagKey(name.value)] = enabled
        }
        return result
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.scenario.ScenarioTest'
```
Expected: `BUILD SUCCESSFUL`, 30 tests passed.

- [ ] **Step 5: Commit**
```bash
git add featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/DemoFlags.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/DemoConfigurations.kt featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/ScenarioTest.kt
git commit -m "Port the DemoApp scenarios: payloads, mapper, layering and audit" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D7.3 README and DocC remote examples

This is a characterisation port. All production code exists, so Step 2 is expected to pass. Any failure is a real parity bug, to be fixed at its source in D1–D4.

- [ ] **Step 1: Write the test.** It covers the remote samples in `READMEExampleTests.swift`, plus the RemoteOverrides, FlagValues (enum case), ExportingAndImporting (remote) and RecordFlags (remote) sections of `DocumentationExampleTests.swift`.

`featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/RemoteDocumentationExampleTest.kt`
```kotlin
package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.exportJson
import com.andyyhope.featureflag.transport.importJson
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// README.md, as written there

internal enum class ReadmeTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

@FlagContainer
internal interface ReadmeAppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @FlagGroup(description = "Checkout")
    val checkout: ReadmeCheckoutFlags
}

@FlagContainer
internal interface ReadmeCheckoutFlags {
    @Flag(description = "Offer Apple Pay")
    val applePay: Boolean get() = false

    @Flag(description = "Pricing tier to present")
    val tier: ReadmeTier get() = ReadmeTier.Free
}

@FlagContainer
internal interface NumberAllowanceFlags {
    @Flag(description = "Ratio", remoteKey = "cfg.ratio")
    val ratio: Double get() = 0.0

    @Flag(description = "Enabled", remoteKey = "cfg.enabled")
    val enabled: Boolean get() = false
}

// The DocC articles, as written there

@FlagContainer
internal interface DocsAppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: DocsCheckoutFlags
}

@FlagContainer
internal interface DocsCheckoutFlags {
    @Flag(description = "Offer Apple Pay", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = false

    @FlagGroup(description = "Express")
    val express: DocsExpressFlags
}

@FlagContainer
internal interface DocsExpressFlags {
    @Flag(description = "One-tap purchase")
    val oneTap: Boolean get() = false
}

@FlagContainer
internal interface DocsArrayPathFlags {
    @Flag(description = "First experiment", remoteKey = "experiments.0.enabled")
    val firstExperiment: Boolean get() = false
}

@FlagContainer
internal interface DocsExperimentFlags {
    @Flag(description = "Onboarding", remoteKey = "onboarding-v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Apple Pay", remoteKey = "apple-pay")
    val applePay: Boolean get() = false
}

internal enum class DocsTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro"), Enterprise("enterprise") }

internal enum class DocsRetryPolicy(override val flagRawValue: Long) : FlagIntEnum { None(0), Once(1), Aggressive(3) }

@FlagContainer
internal interface DocsEnumFlags {
    @Flag(description = "Pricing tier to present", remoteKey = "cfg.tier")
    val tier: DocsTier get() = DocsTier.Free

    @Flag(description = "Retry policy")
    val retryPolicy: DocsRetryPolicy get() = DocsRetryPolicy.Once
}

internal enum class DocsPaymentKind(override val flagRawValue: String) : FlagStringEnum { Card("card"), Wallet("wallet") }

@FlagRecord
internal data class DocsPaymentMethod(
    val name: String,
    val kind: DocsPaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
internal interface DocsRecordFlags {
    @Flag(description = "Payment methods offered at checkout", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<DocsPaymentMethod>
        get() = FlagRecords(
            listOf(
                DocsPaymentMethod("Visa", DocsPaymentKind.Card, enabled = true, minimumSpend = 0.0),
                DocsPaymentMethod("Apple Pay", DocsPaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
            ),
        )
}

/** The custom mapper sample from RemoteOverrides.md. */
private object ExperimentListMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val experiments = payload.valueAt("experiments") as? RemoteValue.Array
            ?: throw RemoteOverrideException.Malformed("expected an experiments array")
        val states = HashMap<String, Boolean>()
        for (experiment in experiments.value) {
            val name = experiment.valueAt("name") as? RemoteValue.String
            val state = experiment.valueAt("state") as? RemoteValue.String
            if (name != null && state != null) states[name.value] = state.value == "on"
        }
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (entry in schema.flags) {
            val state = entry.remoteKey?.let { states[it] } ?: continue
            result[entry.key] = RemoteValue.Bool(state)
        }
        return result
    }
}

/** A mapper with the typo the article warns about. */
private val docsTypoMapper = RemoteOverrideMapper { _, _ -> mapOf(FlagKey("new-onbaording") to RemoteValue.Bool(true)) }

class RemoteDocumentationExampleTest {

    private fun json(text: String): ByteArray = text.toByteArray()

    private val onboardingOn = """{"featureToggles": {"onboarding": {"v2": true}}}"""

    // README.md

    @Test
    fun theQuickStartReadsThroughAStackWithARemoteSource() {
        val local = SnapshotSource("local")
        val pole = FlagPole(ReadmeAppFlagsSpec, listOf(local, RemoteOverrideSource(ReadmeAppFlagsSpec)))
        assertFalse(pole.flags.checkout.applePay)

        local.setBox(FlagKey("checkout.apple-pay"), FlagValueBox.Bool(true))

        assertTrue(pole.flags.checkout.applePay)
    }

    @Test
    fun theRemoteOverrideSampleApplies() {
        val remote = RemoteOverrideSource(ReadmeAppFlagsSpec)
        val pole = FlagPole(ReadmeAppFlagsSpec, listOf(remote))
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun remoteValidationIsStrictAsDocumented() {
        val remote = RemoteOverrideSource(ReadmeAppFlagsSpec)
        val pole = FlagPole(ReadmeAppFlagsSpec, listOf(remote))
        assertFailsWith<RemoteOverrideException.Rejected> {
            remote.apply(json("""{"featureToggles": {"onboarding": {"v2": "true"}}}"""))
        }
        assertFalse(pole.flags.newOnboarding)
    }

    @Test
    fun theDocumentedNumberAllowanceHolds() {
        val remote = RemoteOverrideSource(NumberAllowanceFlagsSpec)
        val pole = FlagPole(NumberAllowanceFlagsSpec, listOf(remote))
        remote.apply(json("""{"cfg": {"ratio": 1}}"""))
        assertEquals(1.0, pole.flags.ratio, "a whole number should satisfy a Double flag")
        assertFailsWith<RemoteOverrideException.Rejected>("a quoted true is still not a boolean") {
            remote.apply(json("""{"cfg": {"enabled": "true"}}"""))
        }
    }

    // RemoteOverrides.md

    @Test
    fun theRemoteDotPathSample() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun aPathCanIndexIntoAnArray() {
        val remote = RemoteOverrideSource(DocsArrayPathFlagsSpec)
        val pole = FlagPole(DocsArrayPathFlagsSpec, listOf(remote))
        remote.apply(json("""{"experiments": [{"enabled": true}]}"""))
        assertTrue(pole.flags.firstExperiment)
    }

    @Test
    fun aFlagWithoutARemoteKeyIsNotRemotelyOverridable() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        val result = remote.apply(json("""{"page-size": 50}"""))
        assertFalse(FlagKey("page-size") in result.appliedKeys)
        assertFalse(FlagKey("page-size") in result.absentKeys)
        assertEquals(20, pole.flags.pageSize)
    }

    @Test
    fun applyReportsAppliedAndAbsentKeys() {
        val result = RemoteOverrideSource(DocsAppFlagsSpec).apply(json(onboardingOn))
        assertEquals(listOf(FlagKey("new-onboarding")), result.appliedKeys)
        assertEquals(listOf(FlagKey("checkout.apple-pay")), result.absentKeys)
    }

    @Test
    fun applyingReplacesEverythingTheSourceHeld() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)

        remote.apply(json("""{"featureToggles": {}}"""))
        assertFalse(pole.flags.newOnboarding, "a flag the new payload omits stops being overridden")

        remote.apply(json(onboardingOn))
        remote.clear()
        assertFalse(pole.flags.newOnboarding)
    }

    @Test
    fun rejectionReportsEveryProblemAndAppliesNothing() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote))
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            remote.apply(json("""{"featureToggles": {"onboarding": {"v2": "yes"}, "checkout": {"applePay": 1}}}"""))
        }
        assertEquals(2, error.problems.size, "every problem is reported, not just the first")
        assertEquals(setOf(Kind.TypeMismatch), error.problems.map { it.kind }.toSet())
        assertFalse(pole.flags.newOnboarding)
        assertFalse(pole.flags.checkout.applePay)
    }

    @Test
    fun theCustomMapperSample() {
        val remote = RemoteOverrideSource(DocsExperimentFlagsSpec, mapper = ExperimentListMapper)
        val pole = FlagPole(DocsExperimentFlagsSpec, listOf(remote))
        remote.apply(
            json(
                """
                {
                  "experiments": [
                    { "name": "onboarding-v2", "state": "on" },
                    { "name": "apple-pay",     "state": "off" }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertTrue(pole.flags.newOnboarding)
        assertFalse(pole.flags.applePay)
    }

    @Test
    fun theCustomMapperMayRefuseAPayloadItCannotRead() {
        val error = assertFailsWith<RemoteOverrideException.Malformed> {
            RemoteOverrideSource(DocsExperimentFlagsSpec, mapper = ExperimentListMapper).apply(json("{}"))
        }
        assertEquals("The remote payload could not be read: expected an experiments array.", error.message)
    }

    @Test
    fun aMapperEmittingAnUnknownKeyIsReported() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DocsAppFlagsSpec, mapper = docsTypoMapper).apply(json("{}"))
        }
        assertEquals(listOf(Kind.UnknownKey), error.problems.map { it.kind })
    }

    @Test
    fun theRemoteSourceNeedsTheSameKeyEncoding() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec, keyEncoding = KeyEncoding.Snakecase)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(remote), keyEncoding = KeyEncoding.Snakecase)
        remote.apply(json(onboardingOn))
        assertTrue(pole.flags.newOnboarding)

        val mismatched = RemoteOverrideSource(DocsAppFlagsSpec)
        val mismatchedPole = FlagPole(DocsAppFlagsSpec, listOf(mismatched), keyEncoding = KeyEncoding.Snakecase)
        mismatched.apply(json(onboardingOn))
        assertFalse(mismatchedPole.flags.newOnboarding, "a source keyed differently to its pole never gets asked for the key it holds")
    }

    // FlagValues.md

    @Test
    fun anUnknownEnumCaseIsRejected() {
        val error = assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DocsEnumFlagsSpec).apply(json("""{"cfg": {"tier": "platinum"}}"""))
        }
        assertEquals(listOf(Kind.UnknownCase), error.problems.map { it.kind })
    }

    // ExportingAndImporting.md

    @Test
    fun anImportedValueOutranksALaterRemotePayload() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(SnapshotSource(), remote))
        pole.importJson(json("""{"formatVersion": 1, "values": {"new-onboarding": true}}"""))
        remote.apply(json("""{"featureToggles": {"onboarding": {"v2": false}}}"""))
        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun exportCarriesValuesARemotePayloadSupplied() {
        val remote = RemoteOverrideSource(DocsAppFlagsSpec)
        val pole = FlagPole(DocsAppFlagsSpec, listOf(SnapshotSource(), remote))
        remote.apply(json(onboardingOn))
        val exported = pole.exportJson().decodeToString()
        assertTrue(exported.contains("new-onboarding"), exported)
    }

    // RecordFlags.md

    @Test
    fun theRecordArticleRemotePayloadApplies() {
        val remote = RemoteOverrideSource(DocsRecordFlagsSpec)
        remote.apply(
            json("""{ "config": { "paymentMethods": [ { "name": "Bank transfer", "kind": "card", "enabled": true, "minimumSpend": 10 } ] } }"""),
        )
        val methods = FlagPole(DocsRecordFlagsSpec, listOf(remote)).flags.paymentMethods
        assertEquals(listOf("Bank transfer"), methods.map { it.name })
    }

    @Test
    fun theRecordArticleRemotePayloadIsAllOrNothing() {
        assertFailsWith<RemoteOverrideException.Rejected> {
            RemoteOverrideSource(DocsRecordFlagsSpec).apply(json("""{ "config": { "paymentMethods": [ { "name": "Partial" } ] } }"""))
        }
    }
}
```

- [ ] **Step 2: Run it**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.scenario.RemoteDocumentationExampleTest'
```
Expected: `BUILD SUCCESSFUL`, 19 tests passed: the samples exercise only D1 to D6 and M1c, so there is no implementation step. A failure is a bug at its source, never a reason to weaken the test; for example, a failure in `exportCarriesValuesARemotePayloadSupplied` belongs to M1c's export walking the whole stack.

- [ ] **Step 3: Run the scenario suite and the build**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests 'com.andyyhope.featureflag.scenario.*'
./gradlew build detekt
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**
```bash
git add featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/RemoteDocumentationExampleTest.kt
git commit -m "Run the README and DocC remote samples" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

#### D7.4 Full verification and coverage checklist

- [ ] **Step 1: Run the whole build, the Swift decode check, and confirm the tree is clean**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt
swift run --package-path fixture-generator decode-kotlin-fixtures fixtures-android
git status --short
```
Expected:
- **Gradle:** `BUILD SUCCESSFUL`, with no detekt finding and every test in all three modules passing: 718 tests (671 in `featureflag-core`, 41 in `featureflag-ksp`, 6 in `featureflag-test`). Every code block in M1a to M1d was written to pass detekt as given; never add a baseline entry.
- **Swift:** `All 6 Kotlin fixtures decode.`, exit 0.
- **`git status --short`:** prints nothing. The build compares against `fixtures/` and `fixtures-android/` and writes into neither.

- [ ] **Step 2: Walk the coverage checklist.** Each Swift test file in M1d's scope maps to a Kotlin test class. List the classes, then confirm each row's class exists and passed in Step 1's report (`featureflag-core/build/reports/tests/test/index.html`).
```bash
ls featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote featureflag-core/src/test/kotlin/com/andyyhope/featureflag/audit \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors featureflag-core/src/test/kotlin/com/andyyhope/featureflag/signals \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test
```

The Swift to Kotlin coverage table, with the goldens it pins, moved to [`docs/superpowers/notes/2026-10-25-swift-test-coverage.md`](../notes/2026-10-25-swift-test-coverage.md).

