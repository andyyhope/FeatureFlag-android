# M3a Companion Logic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything the companion's screens need, as plain Kotlin, before any screen exists:
- `FlagValueText` and `FlagRecordList` in core, so an editor can read and write any value as text and any record list as rows (A1);
- the `featureflag-ui` module with Compose, plus `FlagEditorKind`, `FlagTreeNode` and `FlagSection` (A2);
- `FlagEditingStore`, the companion's model of one host (A3);
- `FlagCompanionSession` and `FlagCompanionViewModel`, which connect, reload a changed schema and surface refresh failures (A4);
- delayed and cancellable signals on the bridge, held by the host (A5);
- `FlagSignalGroup`, `FlagSignalDelay` and `FlagSignalsModel`, with the countdown and the recent attempts (A6).

**Architecture:**
- **Core gains two public codecs.** `FlagValueText` and `FlagRecordList` wrap core's internal JSON, number, date and base64 readers, so the UI module never parses anything itself and reads exactly what an import or the host reads.
- **The logic is JVM-only.** `ui/editing`, `ui/signals` and `ui/companion/FlagCompanionSession.kt` import no Android type. Their tests are plain JUnit over `SnapshotSource` and small fakes. Only `FlagCompanionViewModel.kt` touches Android (`AndroidViewModel`, `FlagBridgeSource`), and its test is Robolectric.
- **The store is stateless over its source.** Every read asks the source afresh; a `revision` counter, bumped by source changes and by every write, tells screens to recompose. Writes suspend on a dispatcher because a bridge source's writes block.
- **The host holds a signal's delay.** A delayed send carries `KEY_DELAY` and a ticket. The provider waits on a latch keyed by the ticket; `METHOD_CANCEL` opens it. The client sends the cancel when its coroutine is cancelled, so leaving the Signals screen withdraws the signal.

**Tech Stack:** As the M3 overview: Kotlin 2.2.10, AGP 9.1.1 with built-in Kotlin, kotlinx-coroutines 1.10.2, JUnit 4.13.2 + kotlin-test-junit, Turbine 1.2.1, Robolectric 4.16 (SDK 36), detekt 1.23.8. New in A2: Compose BOM 2026.01.00 with the compose compiler plugin 2.2.10, activity-compose 1.10.1, lifecycle 2.9.1, androidx.core 1.16.0, ZXing core 3.5.4.

**Contract:** [`2026-10-14-m3-overview.md`](2026-10-14-m3-overview.md). Every public and internal name below is the contract's. Where this plan had to choose something the contract leaves open, or found the contract wrong, it says so under "Contract issues found" at the end.

**Spec:** [`docs/superpowers/specs/2026-10-08-android-parity-design.md`](../specs/2026-10-08-android-parity-design.md), "Companion UI (M3)", "Modules" and "Spike S6 findings".

**Inputs:** `docs/superpowers/notes/2026-10-14-m3-ios-logic.md` (the iOS logic and the test-by-test port list) and `docs/superpowers/notes/2026-10-14-m3-kotlin-apis.md`. Where they disagree with the iOS 0.12.0 source, the source won; see "Contract issues found".

## Global Constraints

- Everything in the M3 overview's Global Constraints applies. In particular:
  - **Every Gradle command runs with** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, from the repository root. A fresh worktree needs `local.properties` with `sdk.dir=/Users/andyyhope/Library/Android/sdk`.
  - `explicitApi()` and `allWarningsAsErrors = true` hold for main and test sources. A test that calls `runCurrent()` opts in with `@OptIn(ExperimentalCoroutinesApi::class)` on its class, as core's `EnvironmentConfigurationTest` does.
  - `featureflag-ui`'s runtime dependencies are exactly the overview's list. Test dependencies come from `featureflag.android-library` plus `ui-test-junit4` and `ui-test-manifest`.
  - Logic tests (`ui/editing`, `ui/signals`, the session) are plain JUnit with no `@RunWith`. Robolectric tests carry `@RunWith(AndroidJUnit4::class)` and `@Config(sdk = [36])`.
  - Test names are camelCase sentences, with `// Section` comments, and each ported Swift test keeps its Swift name minus `test`.
- **Plan-specific:**
  - **detekt's ktlint rules wrap at 120 columns.** `PropertyWrapping`, `Wrapping` and `ParameterListWrapping` fire on a property, lambda or signature line past 120 even though `MaximumLineLength` is off. Every block below already respects this; keep it when editing.
  - **Thread names in tests carry a suffix.** Coroutine debugging is on in unit tests, so a thread named `flag-writes` reports `flag-writes @coroutine#15`. Tests compare with `startsWith`.
  - **No root build change.** `featureflag.compose` applies `org.jetbrains.kotlin.plugin.compose` from build-logic's classpath; the root `build.gradle.kts` needs no `apply false` alias for it (checked: `assembleDebug` and the Compose tests pass without one).
- **Definition of done for each task:** the named tests pass, and `./gradlew build detekt` is green (allWarningsAsErrors, explicitApi, detekt with formatting, and lint, which reports `No issues found.` for `featureflag-ui`, `featureflag-android` and `featureflag-bridge`). A5 also needs `./gradlew :featureflag-bridge:connectedDebugAndroidTest` green on `Pixel_7`.
- **Commits:** imperative sentence case, no type prefix, no trailing period, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

The overview's Review Focus items this plan owns, and the test that pins each one:

1. **A stored override the schema no longer accepts.** `FlagEditingStore.value` and `isOverridden` use the stored box only when it is of the declared type and, for an enum, one of its cases; `resetAll` clears every schema key that holds anything.
   - Pinned by A3 `aStoredValueOfTheWrongTypeReadsAsNotOverridden`, `aStoredValueOutsideAnEnumsCasesReadsAsNotOverridden` and `resettingEverythingClearsUnusableValuesToo` (`FlagEditingStoreEdgeCaseTest`). The last stores a wrong-typed value, a removed case and a record list with a repeated key, and checks all three are cleared while a key outside the schema survives.
   - The record-list case reads differently from the overview's wording; see "Contract issues found" 2.
2. **The host's schema changes while the companion is open.** Pinned by A4 `aChangedSchemaReplacesTheStore` (new store, old source closed), `anUnchangedSchemaKeepsTheStore` (same store, one `refresh()`, no new state) and `aFailedRefreshIsShownAndKeepsTheValues` (`Ready(store, failure)` with the override still read) in `FlagCompanionSessionTest`. `comingBackToAHostThatCannotBeReadShowsTheBanner` covers the foreground path to the banner.
3. **A write that the bridge refuses.** Pinned by A3 `aFailedWriteLeavesTheValueUnchanged`: the source throws `FlagBridgeException.TooLarge`, the store rethrows the same instance, the value and `isOverridden` are unchanged, and `revision` still bumps so the row reverts. B6 owns the snackbar half.
4. **Text the editor cannot read.** Pinned by A1 `numbersRejectAnythingThatIsNotOne` (locale commas, whitespace, suffixes, hex floats, overflow, non-ASCII digits), `nonFiniteNumbersAreRejected` (`nan`, `inf`, `infinity`, `1e400`, a float overflow, and non-finite numbers inside collections) and `malformedStructuredTextIsRejected` (fractional seconds, unpadded and URL-safe base64, an empty URL, JSON of the wrong shape) in `FlagValueTextTest`. B1 owns the field half.
5. **Cancelling during a countdown.** Pinned on the device by A5 `aCancelledDelayedSignalIsNotDelivered`: the host's `Record` handler appends a line to `filesDir/deliveries`, which both processes share; the test cancels a 3 s send after 1 s, waits past the delivery time, and checks the file was never written. Under Robolectric, A5 `cancellingADelayedSendWithdrawsItFromTheHost` (client sends `METHOD_CANCEL` with the send's ticket), `aCancelDuringTheDelayWithholdsTheSignal` and `aCancelThatOvertakesItsSignalStillWithholdsIt` (provider). In the model, A6 `cancellingDuringTheCountdownRecordsNothing`: the send coroutine is cancelled, nothing is recorded, and a late answer changes nothing.

---

## Seams this plan relies on

**M2, as merged at `351f4ef`:**
- `FlagBridgeClient` runs every binder call through its private `within(timeout) { … }` (a detached `async` on its own IO scope, awaited under `withTimeoutOrNull`) and `perform { client -> … }` (fresh unstable provider client, one retry on `DeadObjectException`). A5's `send` and the new `withdraw` reuse both. Cancelling the caller cancels only the `await`; the binder call itself runs on, which is why a cancel has to be sent to the host.
- `FakeBridgeProvider` is registered with `Robolectric.buildContentProvider(FakeBridgeProvider::class.java).create(authority).get()`. Robolectric runs an in-process provider's `call` on the calling thread, so a fake that blocks blocks the client's IO thread, not the test.
- `FlagBridgeProvider.elapsedRealtime` is the test seam for its clock, and `deliveryTimeout(extras, enteredAt)` is `internal`. A direct `provider.call(…)` from a Robolectric test runs on the main thread, which skips the startup barrier and makes `FlagSignalChannel.host.deliver` run handlers inline.
- `HostTestApplication` runs its observers in the `:host` process, and both processes share `filesDir` (the `DieOnce` marker already relies on it).
- `FlagBridgeSource` built with no provider answering: `register()` succeeds under Robolectric, the first refresh fails into `lastFailure` as `Unavailable`, and `refresh()` throws `Unavailable`. A4's `asCompanionSource` test relies on this.

**Core internals that A1 wraps** (all `internal` in `featureflag-core`, so reachable from `values/` and `records/`):
- `MessageText.double` / `.float`: Swift's `String(Double)` / `String(Float)` (`3.5`, `1.0`, `0.1`, `1e-05`, `1e+16`, `nan`, `-inf`).
- `StrictBase64.encode` / `.decode` (padded, standard alphabet, `""` is empty data), `IsoDate.format` / `.parse` (second precision, `Z` or an offset, no fractions).
- `JsonValue.Text(text).toBox(type)`, which for `Date` and `Url` is exactly what an import reads (`IsoDate`, and the `URL(string:)` port in `BoxJson.kt`).
- `FlagValueBox.storageIdentity` and `RecordJson.encode` / `.decode` / `.duplicateKey`.

**What M3b consumes from this plan:**
- `FlagCompanionViewModel.factory(hostPackage, timeout)`, its `session` and `send(signal, delay)`. B6 obtains it with `viewModel(key = hostPackage, factory = FlagCompanionViewModel.factory(hostPackage, timeout))`.
- `FlagCompanionSession.state`, `retry()`, `onForeground()` and `refresh()`; `FlagCompanionState`.
- `FlagEditingStore.revision`, which screens read to recompose, and its suspend writes, called from `rememberCoroutineScope()`.
- `FlagSignalsModel(send = viewModel::send, scope = …)` and `cancelPending()`, which B5 calls when the Signals screen leaves composition.

## How this plan was checked

Every code block below was run in a scratch copy of the worktree at `351f4ef`, task by task in plan order. A script then took each step's blocks from this document (whole files, and each `replace … with …` pair, whose anchor it found exactly once) and wrote them in order onto a second fresh copy; the result was byte-identical to the tree the code was developed in, and `./gradlew build detekt --rerun-tasks` passed on it.
- **Red steps.** A5 Step 2 was run on a copy staged at that step (A5's test edits over A4's tree) and failed with exactly the errors listed. A2 Step 2 was run on a copy holding only A2 Step 1's files: `assembleDebug` succeeded and the scaffold test passed. The other red steps fail on references the step itself introduces.
- **Unit tests.** A1: 34 (`FlagValueTextTest` 16, `FlagRecordListTest` 18). A2: 30. A3: 83. A4: 23. A5: 16 new (contract 1, client 7, provider 7, view model 1), with every existing bridge test still passing (`FlagBridgeClientTest` 36, `FlagBridgeProviderTest` 38, `FlagBridgeContractTest` 6). A6: 33. `featureflag-ui` ends with 170 tests.
- **Device.** `./gradlew :featureflag-bridge:connectedDebugAndroidTest` on the `Pixel_7` AVD (API 36): `Finished 20 tests` (17 cross-process, 3 ART), `aDelayedSignalIsHandledOnceItsDelayHasPassed` taking about 2 s and `aCancelledDelayedSignalIsNotDelivered` about 5 s.
- **Mutation.** With the client's `withdraw(ticket)` disabled, `aCancelledDelayedSignalIsNotDelivered` fails on the device with `the host delivered a cancelled signal`, and `cancellingADelayedSendWithdrawsItFromTheHost` fails under Robolectric.
- **Gate.** `./gradlew build detekt` green after every task; lint `No issues found.` in all three Android modules.

## Files this plan creates or changes

```
gradle/libs.versions.toml                                                       (A2)
build-logic/build.gradle.kts                                                    (A2)
build-logic/src/main/kotlin/ComposeConventionPlugin.kt                          (A2)
settings.gradle.kts                                                             (A2)
featureflag-core/src/main/kotlin/com/andyyhope/featureflag/
  values/FlagValueText.kt                                                       (A1)
  records/FlagRecordList.kt                                                     (A1)
featureflag-core/src/test/kotlin/com/andyyhope/featureflag/
  values/FlagValueTextTest.kt                                                   (A1)
  records/FlagRecordListTest.kt                                                 (A1)
featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/
  FlagBridgeContract.kt                                                         (A5)
  FlagBridgeClient.kt                                                           (A5)
featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/
  FakeBridgeProvider.kt, FlagBridgeContractTest.kt, FlagBridgeClientTest.kt     (A5)
featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt          (A5)
featureflag-bridge/src/test/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProviderTest.kt      (A5)
featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/
  HostTestApplication.kt, CrossProcessBridgeTest.kt                             (A5)
featureflag-ui/
  build.gradle.kts                                                              (A2)
  src/main/AndroidManifest.xml                                                  (A2)
  src/main/kotlin/com/andyyhope/featureflag/ui/
    editing/FlagEditorKind.kt, editing/FlagTreeNode.kt                          (A2)
    editing/FlagEditingStore.kt, editing/FlagRecordEditingException.kt         (A3)
    companion/FlagCompanionSession.kt                                           (A4)
    companion/FlagCompanionViewModel.kt                                         (A4; A5 adds send)
    signals/FlagSignalGroup.kt, signals/FlagSignalDelay.kt, signals/FlagSignalsModel.kt  (A6)
  src/test/resources/robolectric.properties                                     (A2)
  src/test/kotlin/com/andyyhope/featureflag/ui/
    ComposeScaffoldTest.kt                                                      (A2)
    editing/CompanionSchemas.kt, FlagEditorKindTest.kt, FlagTreeNodeTest.kt     (A2)
    editing/ScriptedSource.kt, FlagEditingStoreTest.kt, FlagEditingStoreEdgeCaseTest.kt,
      FlagArrayEditorTest.kt, FlagRecordEditorTest.kt, FlagRecordKeyEditorTest.kt,
      FlagQrCodeTextTest.kt                                                     (A3)
    companion/FakeCompanionSource.kt, FlagCompanionSessionTest.kt,
      FlagCompanionViewModelTest.kt                                             (A4; A5 adds a test)
    signals/ScriptedSend.kt, FlagSignalGroupTest.kt, FlagSignalDelayTest.kt,
      FlagSignalCountdownTest.kt, FlagSignalsModelTest.kt                       (A6)
```

---

### Task A1: `FlagValueText` and `FlagRecordList` in core

**Files:**
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueText.kt`
- Create: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordList.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueTextTest.kt`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordListTest.kt`

**Interfaces:**
- Consumes (core, internal): `MessageText.double(Double)`, `MessageText.float(Float)`, `StrictBase64.encode(ByteArray)` / `decode(String): ByteArray?`, `IsoDate.format(Instant)`, `JsonValue.Text(String).toBox(FlagValueType): FlagValueBox?`, `FlagValueJson.encode` / `decode`, `FlagValueBox.containsNonFiniteNumber`, `FlagValueBox.storageIdentity`, `RecordJson.encode` / `decode(String, shape)` / `duplicateKey(String, shape)` / `duplicateKey(records, shape)`.
- Produces:
```kotlin
// package com.andyyhope.featureflag.values
public object FlagValueText {
    public fun format(box: FlagValueBox): String
    public fun parse(text: String, type: FlagValueType): FlagValueBox?
    public fun empty(type: FlagValueType, now: Instant = Instant.now()): FlagValueBox
}
// package com.andyyhope.featureflag.records
public object FlagRecordList {
    public fun decode(box: FlagValueBox, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>?
    public fun encode(records: List<Map<String, FlagValueBox>>): FlagValueBox.String       // throws FlagSerializationException.NonFiniteNumber
    public fun duplicateKey(box: FlagValueBox, shape: List<FlagRecordField>): FlagValueBox?
    public fun duplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox?
    public fun emptyRecord(
        shape: List<FlagRecordField>,
        alongside: List<Map<String, FlagValueBox>> = emptyList(),
        now: Instant = Instant.now(),
    ): Map<String, FlagValueBox>
}
```

**Port targets:** the value-text half of `FlagEditorValueTests` (`everyTypeRendersAsText`, `collectionsRenderAsJson`, `urlsRenderWithoutEscapingTheirSlashes`, `booleansAcceptTheUsualSpellings`, `numbersRejectAnythingThatIsNotOne`, `stringsAcceptAnything`, `datesAndDataAndUrlsRoundTripThroughText`, `malformedStructuredTextIsRejected`, `collectionsRoundTripThroughText`), and the value-level halves of `FlagRecordEditorTests` (`textThatIsNotAListOfRecordsReadsAsUnreadableRatherThanEmpty`, `aNewRecordStartsWithEmptyFields` from `testAddingAppendsARecordOfEmptyFields`, `aNewRecordsEnumFieldStartsOnARealCase`, `whatTheEditorWritesIsWhatTheHostReads`) and `FlagRecordKeyEditorTests` (`addingTwiceMakesTwoRecordsTheHostCanRead`, `aThirdAddIsStillDistinct`, `aListWithADuplicateKeyReadsAsUnreadableToTheEditorToo`, `twoAddsOnADateKeyStayDistinctOnceStored`, `aRecordWithNoKeyHasNoDuplicate`, `aNestedListWithADuplicateKeyIsFoundInItsOwnBox`). The pinned `nonFiniteNumbersAreRejected` is new. A3 ports the store-level halves of the record tests.

`format` follows the contract: Swift's number description, padded base64, second-precision dates, and compact sorted JSON for collections, or `""` for a collection holding a non-finite number. `parse` follows it too, with two refinements listed under "Contract issues found" 5: `Date` and `Url` use the import's own readers, and a collection holding a non-finite number is refused.

- [ ] **Step 1: Write the failing tests**

**Create** `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueTextTest.kt`
```kotlin
package com.andyyhope.featureflag.values

import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// Ports the value-text half of Swift's FlagEditorValueTests, plus the refusals the companion adds.
class FlagValueTextTest {

    // Rendering

    @Test
    fun everyTypeRendersAsText() {
        assertEquals("true", FlagValueText.format(FlagValueBox.Bool(true)))
        assertEquals("false", FlagValueText.format(FlagValueBox.Bool(false)))
        assertEquals("-7", FlagValueText.format(FlagValueBox.Int(-7)))
        assertEquals("3.5", FlagValueText.format(FlagValueBox.Double(3.5)))
        assertEquals("2.5", FlagValueText.format(FlagValueBox.Float(2.5f)))
        assertEquals("hi", FlagValueText.format(FlagValueBox.String("hi")))
        assertEquals("AQI=", FlagValueText.format(FlagValueBox.Data(byteArrayOf(1, 2))))
        assertEquals("1970-01-01T00:00:00Z", FlagValueText.format(FlagValueBox.Date(Instant.EPOCH)))
    }

    @Test
    fun collectionsRenderAsJson() {
        assertEquals("[]", FlagValueText.format(FlagValueBox.Array(emptyList())))
        assertEquals("{}", FlagValueText.format(FlagValueBox.Dictionary(emptyMap())))
        assertEquals(
            "{\"a\":1,\"b\":2}",
            FlagValueText.format(FlagValueBox.Dictionary(mapOf("b" to FlagValueBox.Int(2), "a" to FlagValueBox.Int(1)))),
        )
    }

    @Test
    fun urlsRenderWithoutEscapingTheirSlashes() {
        assertEquals("https://a.example/x?y=z", FlagValueText.format(FlagValueBox.Url(URI("https://a.example/x?y=z"))))
        val inAList = FlagValueBox.Array(listOf(FlagValueBox.Url(URI("https://a.example/x"))))
        assertEquals("[\"https://a.example/x\"]", FlagValueText.format(inAList))
    }

    @Test
    fun numbersRenderAsSwiftDescribesThem() {
        assertEquals("1.0", FlagValueText.format(FlagValueBox.Double(1.0)))
        assertEquals("0.1", FlagValueText.format(FlagValueBox.Double(0.1)))
        assertEquals("1e-05", FlagValueText.format(FlagValueBox.Double(0.00001)))
        assertEquals("1e+16", FlagValueText.format(FlagValueBox.Double(1e16)))
        assertEquals("0.1", FlagValueText.format(FlagValueBox.Float(0.1f)))
        // Inside a collection a float is widened, as JSON writes it.
        assertEquals("[0.10000000149011612]", FlagValueText.format(FlagValueBox.Array(listOf(FlagValueBox.Float(0.1f)))))
    }

    @Test
    fun datesRenderToTheSecond() {
        assertEquals("2026-01-01T00:00:00Z", FlagValueText.format(FlagValueBox.Date(Instant.parse("2026-01-01T00:00:00.75Z"))))
    }

    @Test
    fun aCollectionHoldingANonFiniteNumberRendersAsEmptyRatherThanThrowing() {
        assertEquals("", FlagValueText.format(FlagValueBox.Array(listOf(FlagValueBox.Double(Double.NaN)))))
        assertEquals("", FlagValueText.format(FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Float(Float.POSITIVE_INFINITY)))))
        // A scalar still says what it holds.
        assertEquals("nan", FlagValueText.format(FlagValueBox.Double(Double.NaN)))
        assertEquals("-inf", FlagValueText.format(FlagValueBox.Double(Double.NEGATIVE_INFINITY)))
    }

    // Parsing

    @Test
    fun booleansAcceptTheUsualSpellings() {
        for (text in listOf("true", "TRUE", "yes", "1")) {
            assertEquals(FlagValueBox.Bool(true), FlagValueText.parse(text, FlagValueType.Bool), text)
        }
        for (text in listOf("false", "FALSE", "no", "0")) {
            assertEquals(FlagValueBox.Bool(false), FlagValueText.parse(text, FlagValueType.Bool), text)
        }
        assertNull(FlagValueText.parse("maybe", FlagValueType.Bool))
        assertNull(FlagValueText.parse(" true", FlagValueType.Bool))
    }

    @Test
    fun numbersRejectAnythingThatIsNotOne() {
        assertEquals(FlagValueBox.Int(-7), FlagValueText.parse("-7", FlagValueType.Int))
        assertEquals(FlagValueBox.Int(5), FlagValueText.parse("+5", FlagValueType.Int))
        assertNull(FlagValueText.parse("3.5", FlagValueType.Int))
        assertNull(FlagValueText.parse(" 3", FlagValueType.Int))
        assertNull(FlagValueText.parse("3 ", FlagValueType.Int))
        assertNull(FlagValueText.parse("3abc", FlagValueType.Int))
        assertNull(FlagValueText.parse("", FlagValueType.Int))
        assertNull(FlagValueText.parse("-", FlagValueType.Int))
        assertNull(FlagValueText.parse("9223372036854775808", FlagValueType.Int))
        assertNull(FlagValueText.parse("٣", FlagValueType.Int)) // ARABIC-INDIC DIGIT THREE
        assertEquals(FlagValueBox.Float(2.5f), FlagValueText.parse("2.5", FlagValueType.Float))
        assertEquals(FlagValueBox.Double(0.5), FlagValueText.parse(".5", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(5.0), FlagValueText.parse("5.", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(100000.0), FlagValueText.parse("1e5", FlagValueType.Double))
        assertEquals(FlagValueBox.Double(-1.0), FlagValueText.parse("-1", FlagValueType.Double))
        // Locale commas, whitespace, Kotlin suffixes and hex floats are not numbers here.
        for (text in listOf("3,5", " 3.5", "3.5 ", "3.5d", "3.5f", "0x1p3", "", ".", "1e", "e5")) {
            assertNull(FlagValueText.parse(text, FlagValueType.Double), text)
            assertNull(FlagValueText.parse(text, FlagValueType.Float), text)
        }
    }

    @Test
    fun nonFiniteNumbersAreRejected() {
        for (text in listOf("nan", "NaN", "inf", "-inf", "infinity", "Infinity", "1e400")) {
            assertNull(FlagValueText.parse(text, FlagValueType.Double), text)
            assertNull(FlagValueText.parse(text, FlagValueType.Float), text)
        }
        // A float that overflows to infinity, though a double holds it.
        assertNull(FlagValueText.parse("1e40", FlagValueType.Float))
        assertEquals(FlagValueBox.Double(1e40), FlagValueText.parse("1e40", FlagValueType.Double))
        // Inside a collection, where JSON reads -1e400 as an infinity and 1e39 narrows to one.
        assertNull(FlagValueText.parse("[-1e400]", FlagValueType.Array(FlagValueType.Double)))
        assertNull(FlagValueText.parse("{\"a\":1e39}", FlagValueType.Dictionary(FlagValueType.Float)))
    }

    @Test
    fun stringsAcceptAnything() {
        assertEquals(FlagValueBox.String(""), FlagValueText.parse("", FlagValueType.String))
        assertEquals(FlagValueBox.String("{not json}"), FlagValueText.parse("{not json}", FlagValueType.String))
        assertEquals(FlagValueBox.String("  spaced  "), FlagValueText.parse("  spaced  ", FlagValueType.String))
    }

    @Test
    fun datesAndDataAndUrlsRoundTripThroughText() {
        val cases = listOf(
            FlagValueBox.Date(Instant.ofEpochSecond(1_000)) to FlagValueType.Date,
            FlagValueBox.Data(byteArrayOf(0x01, 0xFF.toByte())) to FlagValueType.Data,
            FlagValueBox.Url(URI("https://a.example/x")) to FlagValueType.Url,
        )
        for ((box, type) in cases) {
            assertEquals(box, FlagValueText.parse(FlagValueText.format(box), type), "$type")
        }
    }

    @Test
    fun malformedStructuredTextIsRejected() {
        assertNull(FlagValueText.parse("yesterday", FlagValueType.Date))
        assertNull(FlagValueText.parse("!!!", FlagValueType.Data))
        assertNull(FlagValueText.parse("{}", FlagValueType.Array(FlagValueType.String)))
        assertNull(FlagValueText.parse("[1]", FlagValueType.Array(FlagValueType.String)))
        assertNull(FlagValueText.parse("[", FlagValueType.Array(FlagValueType.String)))
        // What the companion adds: fractional seconds, unpadded or URL-safe base64, an empty URL.
        assertNull(FlagValueText.parse("1970-01-01T00:00:00.5Z", FlagValueType.Date))
        assertNull(FlagValueText.parse("1970-01-01", FlagValueType.Date))
        assertNull(FlagValueText.parse(" 1970-01-01T00:00:00Z", FlagValueType.Date))
        assertNull(FlagValueText.parse("AQI", FlagValueType.Data))
        assertNull(FlagValueText.parse("AQ-_", FlagValueType.Data))
        assertNull(FlagValueText.parse("AQI= ", FlagValueType.Data))
        assertNull(FlagValueText.parse("", FlagValueType.Url))
        assertNull(FlagValueText.parse("{\"a\":\"b\"}", FlagValueType.Dictionary(FlagValueType.Int)))
    }

    @Test
    fun datesMayCarryAnOffsetButNothingFiner() {
        assertEquals(
            FlagValueBox.Date(Instant.parse("1970-01-01T00:00:00Z")),
            FlagValueText.parse("1970-01-01T10:00:00+10:00", FlagValueType.Date),
        )
    }

    @Test
    fun emptyTextIsEmptyData() {
        val box = assertIs<FlagValueBox.Data>(FlagValueText.parse("", FlagValueType.Data))
        assertContentEquals(ByteArray(0), box.value)
    }

    @Test
    fun collectionsRoundTripThroughText() {
        val cases = listOf(
            FlagValueBox.Array(listOf(FlagValueBox.String("a"), FlagValueBox.String("b"))) to
                FlagValueType.Array(FlagValueType.String),
            FlagValueBox.Array(emptyList()) to FlagValueType.Array(FlagValueType.Int),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Int(1))) to FlagValueType.Dictionary(FlagValueType.Int),
            FlagValueBox.Dictionary(emptyMap()) to FlagValueType.Dictionary(FlagValueType.Bool),
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Int(1))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Int)),
        )
        for ((box, type) in cases) {
            assertEquals(box, FlagValueText.parse(FlagValueText.format(box), type), "failed for ${type.typeName}")
        }
    }

    // Empty values

    @Test
    fun everyTypeHasAnEmptyValue() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(FlagValueBox.Bool(false), FlagValueText.empty(FlagValueType.Bool, now))
        assertEquals(FlagValueBox.Int(0), FlagValueText.empty(FlagValueType.Int, now))
        assertEquals(FlagValueBox.Double(0.0), FlagValueText.empty(FlagValueType.Double, now))
        assertEquals(FlagValueBox.Float(0f), FlagValueText.empty(FlagValueType.Float, now))
        assertEquals(FlagValueBox.String(""), FlagValueText.empty(FlagValueType.String, now))
        assertEquals(FlagValueBox.Data(ByteArray(0)), FlagValueText.empty(FlagValueType.Data, now))
        assertEquals(FlagValueBox.Date(now), FlagValueText.empty(FlagValueType.Date, now))
        assertEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueText.empty(FlagValueType.Url, now))
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.Int(0))),
            FlagValueText.empty(FlagValueType.Array(FlagValueType.Int), now),
        )
        assertEquals(FlagValueBox.Dictionary(emptyMap()), FlagValueText.empty(FlagValueType.Dictionary(FlagValueType.Int), now))
    }
}
```

**Create** `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordListTest.kt`
```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ports the value-level half of Swift's FlagRecordEditorTests and FlagRecordKeyEditorTests: what
// the editor reads, writes and adds, checked against the same reader the host uses.
class FlagRecordListTest {

    private val now = Instant.parse("2026-01-01T00:00:00.400Z")

    private val tier = FlagRecordField(
        name = "tier",
        type = FlagValueType.String,
        cases = listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")),
    )
    private val endpoint = listOf(
        FlagRecordField("name", FlagValueType.String),
        FlagRecordField("url", FlagValueType.Url),
        FlagRecordField("enabled", FlagValueType.Bool),
        FlagRecordField("weight", FlagValueType.Int),
        tier,
    )
    private val hop = listOf(FlagRecordField("host", FlagValueType.String, isKey = true))
    private val route = listOf(
        FlagRecordField("name", FlagValueType.String, isKey = true),
        FlagRecordField("hops", FlagValueType.String, defaultValue = FlagValueBox.String("[]"), fields = hop),
    )
    private val stamped = listOf(
        FlagRecordField("at", FlagValueType.Date, isKey = true),
        FlagRecordField("note", FlagValueType.String, defaultValue = FlagValueBox.String("")),
    )
    private val note = listOf(FlagRecordField("text", FlagValueType.String))

    private val staging = mapOf(
        "name" to FlagValueBox.String("staging"),
        "url" to FlagValueBox.Url(URI("https://staging.example")),
        "enabled" to FlagValueBox.Bool(false),
        "weight" to FlagValueBox.Int(7),
        "tier" to FlagValueBox.String("secondary"),
    )
    private val canary = mapOf(
        "name" to FlagValueBox.String("canary"),
        "url" to FlagValueBox.Url(URI("https://canary.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(3),
        "tier" to FlagValueBox.String("primary"),
    )

    // Reading

    @Test
    fun textThatIsNotAListOfRecordsReadsAsUnreadableRatherThanEmpty() {
        assertNull(FlagRecordList.decode(FlagValueBox.String("{ not json"), endpoint))
        assertNull(FlagRecordList.decode(FlagValueBox.String("{}"), endpoint))
        assertNull(FlagRecordList.decode(FlagValueBox.String("[{\"name\":\"a\"}]"), endpoint))
        assertNull(FlagRecordList.decode(FlagValueBox.Int(1), endpoint))
        assertEquals(emptyList(), FlagRecordList.decode(FlagValueBox.String("[]"), endpoint))
    }

    @Test
    fun aListWithADuplicateKeyReadsAsUnreadableToTheEditorToo() {
        val box = FlagValueBox.String("[{\"name\":\"a\",\"hops\":\"[]\"},{\"name\":\"a\",\"hops\":\"[]\"}]")

        assertNull(FlagRecordList.decode(box, route))
        assertEquals(FlagValueBox.String("a"), FlagRecordList.duplicateKey(box, route))
    }

    @Test
    fun aDuplicateIsOnlyReportedForAnOtherwiseReadableList() {
        assertNull(FlagRecordList.duplicateKey(FlagValueBox.String("{ not json"), route))
        assertNull(FlagRecordList.duplicateKey(FlagValueBox.String("[{\"name\":\"a\"},{\"name\":\"b\"}]"), route))
        assertNull(FlagRecordList.duplicateKey(FlagValueBox.Bool(true), route))
    }

    // Writing

    @Test
    fun whatTheEditorWritesIsWhatTheHostReads() {
        val written = FlagRecordList.encode(listOf(staging, canary))

        assertEquals(
            "[{\"enabled\":false,\"name\":\"staging\",\"tier\":\"secondary\",\"url\":\"https://staging.example\",\"weight\":7}," +
                "{\"enabled\":true,\"name\":\"canary\",\"tier\":\"primary\",\"url\":\"https://canary.example\",\"weight\":3}]",
            written.value,
        )
        assertEquals(listOf(staging, canary), FlagRecordList.decode(written, endpoint))
        assertEquals(listOf(staging, canary), RecordJson.decode(written.value, endpoint))
    }

    @Test
    fun aRecordHoldingANonFiniteNumberCannotBeWritten() {
        assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            FlagRecordList.encode(listOf(mapOf("ratio" to FlagValueBox.Double(Double.NaN))))
        }
    }

    @Test
    fun aRecordWithNoKeyHasNoDuplicate() {
        val same = mapOf("text" to FlagValueBox.String("same"))

        assertNull(FlagRecordList.duplicateKey(listOf(same, same), note))
    }

    @Test
    fun aDuplicateInTheRowsIsTheFirstKeySeenTwice() {
        val rows = listOf("a", "b", "b", "a").map { mapOf("name" to FlagValueBox.String(it)) }

        assertEquals(FlagValueBox.String("b"), FlagRecordList.duplicateKey(rows, route))
    }

    @Test
    fun aNestedListWithADuplicateKeyIsFoundInItsOwnBox() {
        val hostA = mapOf("host" to FlagValueBox.String("a"))
        val nested = FlagRecordList.encode(listOf(hostA, hostA))
        val outer = listOf(mapOf("name" to FlagValueBox.String("one"), "hops" to nested))

        // Top level only: the outer rows are distinct, and the nested box names its own duplicate.
        assertNull(FlagRecordList.duplicateKey(outer, route))
        assertEquals(FlagValueBox.String("a"), FlagRecordList.duplicateKey(nested, hop))
    }

    // Adding

    @Test
    fun aNewRecordStartsWithEmptyFields() {
        val record = FlagRecordList.emptyRecord(endpoint, now = now)

        assertEquals(FlagValueBox.String(""), record["name"])
        assertEquals(FlagValueBox.Url(URI("https://example.com")), record["url"])
        assertEquals(FlagValueBox.Bool(false), record["enabled"])
        assertEquals(FlagValueBox.Int(0), record["weight"])
        assertEquals(listOf("name", "url", "enabled", "weight", "tier"), record.keys.toList())
    }

    @Test
    fun aNewRecordsEnumFieldStartsOnARealCase() {
        val record = FlagRecordList.emptyRecord(endpoint, now = now)

        assertEquals(FlagValueBox.String("primary"), record["tier"])
        val read = assertNotNull(FlagRecordList.decode(FlagRecordList.encode(listOf(record)), endpoint))
        assertEquals(FlagValueBox.String("primary"), read.single()["tier"])
    }

    @Test
    fun aFieldsDefaultWinsOverItsCases() {
        val withDefault = listOf(tier.copy(defaultValue = FlagValueBox.String("secondary")))

        assertEquals(FlagValueBox.String("secondary"), FlagRecordList.emptyRecord(withDefault, now = now)["tier"])
    }

    @Test
    fun addingTwiceMakesTwoRecordsTheHostCanRead() {
        val first = FlagRecordList.emptyRecord(route, now = now)
        val second = FlagRecordList.emptyRecord(route, alongside = listOf(first), now = now)

        assertEquals(FlagValueBox.String(""), first["name"])
        assertEquals(FlagValueBox.String("2"), second["name"])
        assertEquals(2, FlagRecordList.decode(FlagRecordList.encode(listOf(first, second)), route)?.size)
    }

    @Test
    fun aThirdAddIsStillDistinct() {
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(route, alongside = records, now = now) }

        assertEquals(listOf("", "2", "3"), records.map { (it["name"] as FlagValueBox.String).value })
        assertEquals(3, FlagRecordList.decode(FlagRecordList.encode(records), route)?.size)
    }

    @Test
    fun twoAddsOnADateKeyStayDistinctOnceStored() {
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(stamped, alongside = records, now = now) }

        assertEquals(
            listOf(now, now.plusSeconds(1), now.plusSeconds(2)).map { FlagValueBox.Date(it) },
            records.map { it["at"] },
        )
        assertEquals(3, FlagRecordList.decode(FlagRecordList.encode(records), stamped)?.size)
    }

    @Test
    fun aDateTakenWithinTheSameSecondCountsAsTaken() {
        // Different in memory, the same once stored: the stored form keeps whole seconds.
        val taken = listOf(mapOf("at" to FlagValueBox.Date(Instant.parse("2026-01-01T00:00:00.900Z"))))

        assertEquals(FlagValueBox.Date(now.plusSeconds(1)), FlagRecordList.emptyRecord(stamped, alongside = taken, now = now)["at"])
    }

    @Test
    fun suffixesFollowTheKeysType() {
        val counter = FlagRecordField("id", FlagValueType.Int, isKey = true)
        val link = FlagRecordField("url", FlagValueType.Url, isKey = true)
        val named = FlagRecordField(
            name = "name",
            type = FlagValueType.String,
            defaultValue = FlagValueBox.String("new"),
            isKey = true,
        )

        assertEquals(FlagValueBox.Int(1), suffixed(counter, FlagValueBox.Int(0)))
        assertEquals(FlagValueBox.Url(URI("https://example.com-2")), suffixed(link, FlagValueBox.Url(URI("https://example.com"))))
        assertEquals(FlagValueBox.String("new 2"), suffixed(named, FlagValueBox.String("new")))
    }

    @Test
    fun theFirstFreeSuffixWins() {
        val taken = listOf("", "3").map { mapOf("name" to FlagValueBox.String(it)) }

        assertEquals(FlagValueBox.String("2"), FlagRecordList.emptyRecord(route, alongside = taken, now = now)["name"])
    }

    @Test
    fun aKeyThatTakesNoSuffixKeepsItsCollidingValue() {
        val flag = listOf(FlagRecordField("on", FlagValueType.Bool, isKey = true))
        val taken = listOf(mapOf("on" to FlagValueBox.Bool(false)))

        assertEquals(FlagValueBox.Bool(false), FlagRecordList.emptyRecord(flag, alongside = taken, now = now)["on"])
    }

    /** What a new record's key becomes beside one record already holding [taken]. */
    private fun suffixed(key: FlagRecordField, taken: FlagValueBox): FlagValueBox? =
        FlagRecordList.emptyRecord(listOf(key), alongside = listOf(mapOf(key.name to taken)), now = now)[key.name]
}
```

The record shapes are the Swift fixtures' `@FlagRecord` types as their macro publishes them: fields in declaration order, `isKey` on `@FlagRecordKey`, a nested list as a `string` field with `fields`, and `hops` defaulting to `"[]"` because Swift's `= []` gives it a default.

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests '*.FlagValueTextTest' --tests '*.FlagRecordListTest'
```

Expected: `BUILD FAILED`. `:featureflag-core:compileTestKotlin` reports `Unresolved reference 'FlagValueText'` and `Unresolved reference 'FlagRecordList'`.

- [ ] **Step 3: Write the two codecs**

**Create** `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueText.kt`
```kotlin
package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.errors.MessageText
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.StrictBase64
import java.net.URI
import java.time.Instant

/**
 * A box as the text an editor shows and reads back, as Swift's `displayString` and
 * `FlagValueBox(displayString:as:)` do.
 *
 * Not JSON for scalars: a string is shown unquoted, and a number in Swift's `description`
 * form. Collections are compact JSON, because there is no better single line for them.
 * Reading never guesses: text that is not exactly a value of the type is refused.
 */
public object FlagValueText {

    private val INTEGER = Regex("[+-]?[0-9]+")
    private val DECIMAL = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")
    private val TRUE_WORDS = setOf("true", "yes", "1")
    private val FALSE_WORDS = setOf("false", "no", "0")
    private const val PLACEHOLDER_URL = "https://example.com"

    /**
     * The text an editor shows: `true`, `-7`, `3.5`, `1e-05`, the string itself, padded base64,
     * `1970-01-01T00:00:00Z`, the URL's text, or compact JSON with sorted keys and unescaped
     * slashes for a collection. A collection holding a non-finite number, which JSON cannot
     * carry, is shown as the empty string instead of throwing.
     */
    public fun format(box: FlagValueBox): String = when (box) {
        is FlagValueBox.Bool -> box.value.toString()
        is FlagValueBox.Int -> box.value.toString()
        is FlagValueBox.Double -> MessageText.double(box.value)
        is FlagValueBox.Float -> MessageText.float(box.value)
        is FlagValueBox.String -> box.value
        is FlagValueBox.Data -> StrictBase64.encode(box.value)
        is FlagValueBox.Date -> IsoDate.format(box.value)
        is FlagValueBox.Url -> box.value.toString()
        is FlagValueBox.Array, is FlagValueBox.Dictionary ->
            if (box.containsNonFiniteNumber) "" else FlagValueJson.encode(box)
    }

    /**
     * The box [text] holds as [type], or null to refuse it. Nothing is trimmed.
     *
     * - Bool: `true`, `yes`, `1`, `false`, `no` or `0`, in any case.
     * - Int: an optional sign and ASCII digits, within `Long`.
     * - Double and Float: decimal digits with an optional fraction and exponent, finite once
     *   read. `nan`, `inf`, hex floats, a `d` or `f` suffix and locale commas are refused, and so
     *   is a float that overflows to infinity.
     * - String: anything.
     * - Data: padded standard base64, as an import reads it; the empty string is empty data.
     * - Date and Url: exactly as an import reads them (second-precision ISO 8601, and
     *   `URL(string:)`'s rules), so no fractional seconds and no empty URL.
     * - Array and Dictionary: JSON of that shape, holding only finite numbers.
     */
    public fun parse(text: String, type: FlagValueType): FlagValueBox? = when (type) {
        FlagValueType.Bool -> bool(text)
        FlagValueType.Int -> text.takeIf(INTEGER::matches)?.toLongOrNull()?.let { FlagValueBox.Int(it) }
        FlagValueType.Double ->
            text.takeIf(DECIMAL::matches)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { FlagValueBox.Double(it) }
        FlagValueType.Float ->
            text.takeIf(DECIMAL::matches)?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { FlagValueBox.Float(it) }
        FlagValueType.String -> FlagValueBox.String(text)
        FlagValueType.Data -> StrictBase64.decode(text)?.let { FlagValueBox.Data(it) }
        FlagValueType.Date, FlagValueType.Url -> JsonValue.Text(text).toBox(type)
        is FlagValueType.Array, is FlagValueType.Dictionary ->
            FlagValueJson.decode(text, type)?.takeUnless { it.containsNonFiniteNumber }
    }

    /**
     * What a new list element starts as: `false`, `0`, `0.0`, `""`, empty data, [now],
     * `https://example.com`, a one-element array of the element's own empty value, or `{}`.
     */
    public fun empty(type: FlagValueType, now: Instant = Instant.now()): FlagValueBox = when (type) {
        FlagValueType.Bool -> FlagValueBox.Bool(false)
        FlagValueType.Int -> FlagValueBox.Int(0)
        FlagValueType.Double -> FlagValueBox.Double(0.0)
        FlagValueType.Float -> FlagValueBox.Float(0f)
        FlagValueType.String -> FlagValueBox.String("")
        FlagValueType.Data -> FlagValueBox.Data(ByteArray(0))
        FlagValueType.Date -> FlagValueBox.Date(now)
        FlagValueType.Url -> FlagValueBox.Url(URI(PLACEHOLDER_URL))
        is FlagValueType.Array -> FlagValueBox.Array(listOf(empty(type.element, now)))
        is FlagValueType.Dictionary -> FlagValueBox.Dictionary(emptyMap())
    }

    private fun bool(text: String): FlagValueBox.Bool? = when (text.lowercase()) {
        in TRUE_WORDS -> FlagValueBox.Bool(true)
        in FALSE_WORDS -> FlagValueBox.Bool(false)
        else -> null
    }
}
```

**Create** `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordList.kt`
```kotlin
package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.storageIdentity
import java.time.Instant

/**
 * A record-list flag's value, which is a [FlagValueBox.String] holding a JSON array, as rows
 * of field boxes and back, through nothing but its shape.
 *
 * This is what a companion edits records with: it has the published shape and never the
 * host's record type. It reads and writes exactly as the host's `FlagCodecs.records` does.
 */
public object FlagRecordList {

    /**
     * The rows in [box], or null when it is not a string, is not a list of records [shape] can
     * read, or has two records sharing a key. The host reads such a value as its default.
     */
    public fun decode(box: FlagValueBox, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>? =
        (box as? FlagValueBox.String)?.let { RecordJson.decode(it.value, shape) }

    /**
     * Compact JSON with sorted keys, one object per record, as the host writes it.
     *
     * @throws FlagSerializationException.NonFiniteNumber when a field holds an infinity or a NaN.
     */
    public fun encode(records: List<Map<String, FlagValueBox>>): FlagValueBox.String =
        FlagValueBox.String(RecordJson.encode(records))

    /** The first key value two rows share, when that is the only thing wrong with [box]; null otherwise. */
    public fun duplicateKey(box: FlagValueBox, shape: List<FlagRecordField>): FlagValueBox? =
        (box as? FlagValueBox.String)?.let { RecordJson.duplicateKey(it.value, shape) }

    /** The first key value two of [records] share, compared as stored, at the top level only. */
    public fun duplicateKey(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>): FlagValueBox? =
        RecordJson.duplicateKey(records, shape)

    /**
     * A record to append to [alongside]: every field at its default, else its first case, else
     * [FlagValueText.empty] of its type.
     *
     * When the shape has a key and that value is already taken (compared as stored, so two
     * dates in the same second collide), suffixes 2 up to the number taken plus 2 are tried in
     * turn and the first free one wins: a string becomes `"2"` or `"<value> 2"`, an int its value
     * plus one, a date one second later, a URL `<url>-2`. A key type that takes no suffix, or a
     * list with no free suffix, keeps the colliding value, and the editor shows the collision.
     */
    public fun emptyRecord(
        shape: List<FlagRecordField>,
        alongside: List<Map<String, FlagValueBox>> = emptyList(),
        now: Instant = Instant.now(),
    ): Map<String, FlagValueBox> {
        val record = LinkedHashMap<String, FlagValueBox>()
        shape.forEach { field -> record[field.name] = field.emptyBox(now) }
        val key = shape.firstOrNull { it.isKey } ?: return record
        val taken = alongside.mapNotNull { it[key.name]?.storageIdentity }.toSet()
        val start = key.emptyBox(now)
        if (start.storageIdentity !in taken) return record
        val free = (2..taken.size + 2).asSequence()
            .map { start.withSuffix(it) }
            .takeWhile { it != null }
            .filterNotNull()
            .firstOrNull { it.storageIdentity !in taken }
        if (free != null) record[key.name] = free
        return record
    }

    private fun FlagRecordField.emptyBox(now: Instant): FlagValueBox =
        defaultValue ?: cases?.firstOrNull() ?: FlagValueText.empty(type, now)

    private fun FlagValueBox.withSuffix(suffix: Int): FlagValueBox? = when (this) {
        is FlagValueBox.String -> FlagValueBox.String(if (value.isEmpty()) "$suffix" else "$value $suffix")
        is FlagValueBox.Int -> FlagValueBox.Int(value + suffix - 1)
        is FlagValueBox.Date -> FlagValueBox.Date(value.plusSeconds(suffix - 1L))
        is FlagValueBox.Url -> FlagValueText.parse("$value-$suffix", FlagValueType.Url)
        else -> null
    }
}
```

Notes:
- The suffix search is a sequence that stops at the first type that takes no suffix (`takeWhile`), so detekt's `LoopWithTooManyJumpStatements` does not fire on a `for` with two `break`s. With string keys a free suffix always exists (there are `taken + 1` candidates and at most `taken − 1` of them can be taken), so "no free suffix" only arises for a type that takes none, such as `Bool`.
- `FlagValueText.parse` for an `Int` checks `[+-]?[0-9]+` before `toLongOrNull`, because `toLongOrNull` accepts non-ASCII digits (`"٣"`) that Swift's `Int(_:)` refuses.

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests '*.FlagValueTextTest' --tests '*.FlagRecordListTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `featureflag-core/build/test-results/test/TEST-com.andyyhope.featureflag.values.FlagValueTextTest.xml` shows `tests="16" skipped="0" failures="0" errors="0"`, and `TEST-com.andyyhope.featureflag.records.FlagRecordListTest.xml` shows `tests="18" … failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagValueText.kt \
  featureflag-core/src/main/kotlin/com/andyyhope/featureflag/records/FlagRecordList.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/values/FlagValueTextTest.kt \
  featureflag-core/src/test/kotlin/com/andyyhope/featureflag/records/FlagRecordListTest.kt
git commit -m "Add FlagValueText and FlagRecordList so an editor reads and writes values as the host does" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A2: The `featureflag-ui` module, `FlagEditorKind`, `FlagTreeNode` and `FlagSection`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/ComposeConventionPlugin.kt`
- Modify: `settings.gradle.kts`
- Create: `featureflag-ui/build.gradle.kts`
- Create: `featureflag-ui/src/main/AndroidManifest.xml`
- Create: `featureflag-ui/src/test/resources/robolectric.properties`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditorKind.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagTreeNode.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/ComposeScaffoldTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/CompanionSchemas.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditorKindTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagTreeNodeTest.kt`

**Interfaces:**
- Consumes: A1 `FlagRecordList.encode` (the record fixtures' defaults); core `FlagSchema`, `FlagSchema.Entry`, `FlagSchema.Group`, `FlagRecordField`, `FlagRecordCodec`, `FlagRecords`, `FlagCodecs.records`; `featureflag.android-library`.
- Produces:
```kotlin
// build-logic: plugin id "featureflag.compose", class ComposeConventionPlugin (applies org.jetbrains.kotlin.plugin.compose, LibraryExtension.buildFeatures.compose = true)
// catalog: libs.compose.compiler.gradle.plugin, libs.androidx.compose.{bom,ui,foundation,material3,material.icons.core,ui.tooling,ui.test.junit4,ui.test.manifest},
//          libs.androidx.activity.compose, libs.androidx.lifecycle.{runtime,viewmodel}.compose, libs.androidx.core, libs.zxing.core

// package com.andyyhope.featureflag.ui.editing
public sealed interface FlagEditorKind {
    public data object Toggle : FlagEditorKind
    public data object Integer : FlagEditorKind
    public data object Decimal : FlagEditorKind
    public data object Text : FlagEditorKind
    public data class Picker(public val cases: kotlin.collections.List<FlagValueBox>) : FlagEditorKind
    public data object Date : FlagEditorKind
    public data object Url : FlagEditorKind
    public data object Data : FlagEditorKind
    public data class List(public val element: FlagValueType) : FlagEditorKind
    public data class Records(public val fields: kotlin.collections.List<FlagRecordField>) : FlagEditorKind
    public data object Json : FlagEditorKind
    public val wantsWrappingEditor: Boolean
}
public val FlagSchema.Entry.editorKind: FlagEditorKind
public data class FlagTreeNode(
    public val path: List<String>, public val title: String?,
    public val flags: List<FlagSchema.Entry>, public val groups: List<FlagTreeNode>,
) {
    public val id: String
    public val allFlags: List<FlagSchema.Entry>
    public val isEmpty: Boolean
    public companion object { public fun of(schema: FlagSchema): FlagTreeNode }
}
public data class FlagSection(
    public val path: List<String>, public val title: String?, public val pathDescription: String, public val entries: List<FlagSchema.Entry>,
) { public val id: String }
```
- Test fixtures (`CompanionSchemas.kt`, used by A3 and A4): `internal object CompanionSchemas` with `generatedAt`, the schemas `companion`, `demoUi`, `tree`, `arrayEditor`, `recordEditor`, `keyedEditor`, `qr`, `described`, `plain`, the shapes `endpointShape`, `hopShape`, `routeShape`, `stampedShape`, `noteShape`, the record `production`, and `fun entry(key, path, description, default, type = …, cases = null, shape = null): FlagSchema.Entry`. Beside it: `Tier`, `Endpoint`, `EndpointCodec`, `RowsCodec`, `hostEndpoints(box)` and `hostRecords(box, shape)`, which decode a stored box exactly as the host's `FlagCodecs.records` does.

**Port targets:** the editor-kind half of `FlagEditorValueTests` (11 tests, from `dataGetsItsOwnEditor` to `aPickerIsNeverAWrappingEditor`) and all of `FlagTreeNodeTests` (10). Five more pin what the contract adds: `everyScalarTypeGetsItsControl`, `aRecordShapeGetsTheRecordEditor`, `casesWinOverARecordShape`, `anEmptyRecordShapeFallsBackToTheTypesEditor`, `recordsAreNeverAWrappingEditor`, plus `theRootHasNoTitle` and `aSectionIsIdentifiedByItsPath`. `FlagContainerDescriptionUITests` needs a store and a reload, so it moves to A3 and A4 (see "Contract issues found" 1).

- [ ] **Step 1: Add the module and its Compose convention**

**Modify** `gradle/libs.versions.toml`. Replace

```toml
# detekt static analysis + its ktlint-backed formatting ruleset.
detekt = "1.23.8"
```

with

```toml
# detekt static analysis + its ktlint-backed formatting ruleset.
detekt = "1.23.8"
# Compose for featureflag-ui, matching ../yewki/android. The BOM versions every androidx.compose artifact.
composeBom = "2026.01.00"
androidxActivity = "1.10.1"
androidxLifecycle = "2.9.1"
# FileProvider, for sharing the QR code's PNG.
androidxCore = "1.16.0"
# QR code generation only; the companion never scans.
zxing = "3.5.4"
```

**Modify** `gradle/libs.versions.toml`, second edit. Replace

```toml
detekt-gradle-plugin = { module = "io.gitlab.arturbosch.detekt:detekt-gradle-plugin", version.ref = "detekt" }
```

with

```toml
detekt-gradle-plugin = { module = "io.gitlab.arturbosch.detekt:detekt-gradle-plugin", version.ref = "detekt" }
compose-compiler-gradle-plugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
```

**Modify** `gradle/libs.versions.toml`, third edit. Replace

```toml
detekt-formatting = { module = "io.gitlab.arturbosch.detekt:detekt-formatting", version.ref = "detekt" }
```

with

```toml
detekt-formatting = { module = "io.gitlab.arturbosch.detekt:detekt-formatting", version.ref = "detekt" }

# featureflag-ui. The androidx.compose artifacts carry no version: the BOM sets them.
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons-core = { module = "androidx.compose.material:material-icons-core" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-core = { module = "androidx.core:core", version.ref = "androidxCore" }
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
```

**Modify** `build-logic/build.gradle.kts` (full replacement):
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
```

**Create** `build-logic/src/main/kotlin/ComposeConventionPlugin.kt`
```kotlin
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `featureflag.compose` — Compose for an Android library module. Apply it after
 * `featureflag.android-library`, which registers the `LibraryExtension` this configures.
 *
 * The compose compiler is a Kotlin compiler plugin released with Kotlin itself, so its version
 * is the catalog's `kotlin`. Compose libraries are added by the module, through the BOM.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures.compose = true
        }
    }
}
```

**Modify** `settings.gradle.kts`. Replace

```kotlin
include(":featureflag-bridge")
```

with

```kotlin
include(":featureflag-bridge")
include(":featureflag-ui")
```

**Create** `featureflag-ui/build.gradle.kts`
```kotlin
plugins {
    id("featureflag.android-library")
    id("featureflag.compose")
}

android {
    namespace = "com.andyyhope.featureflag.ui"
}

dependencies {
    // The companion's runtime: the bridge client, Compose Material 3, and what the screens share
    // and draw with. Nothing else ships to a companion app; see the M3 overview's Global Constraints.
    api(project(":featureflag-android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Robolectric Compose tests. The rest of the test stack comes from featureflag.android-library.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
```

**Create** `featureflag-ui/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- No components yet: B4 adds the FileProvider, and B7 the notification permissions. -->
<manifest />
```

**Create** `featureflag-ui/src/test/resources/robolectric.properties`
```properties
# Robolectric's default screen is 320x470 dp, which clips Compose content. A Pixel 7, in dp.
qualifiers=w411dp-h891dp
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/ComposeScaffoldTest.kt`
```kotlin
package com.andyyhope.featureflag.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The build the B tasks' screens rely on: the compose compiler runs on this module, Material 3
 * draws under Robolectric with ui-test-junit4, and robolectric.properties gives a phone-sized screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ComposeScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aComposableRendersAndRecomposesUnderRobolectric() {
        compose.setContent { MaterialTheme { Counter() } }

        compose.onNodeWithText("Tapped 0 times").assertIsDisplayed().performClick()

        compose.onNodeWithText("Tapped 1 times").assertIsDisplayed()
    }

    @Test
    fun theScreenIsPhoneSized() {
        val configuration = ApplicationProvider.getApplicationContext<Application>().resources.configuration

        assertEquals(411, configuration.screenWidthDp)
        assertEquals(891, configuration.screenHeightDp)
    }

    @Composable
    private fun Counter() {
        var taps by remember { mutableIntStateOf(0) }
        Text("Tapped $taps times", Modifier.fillMaxSize().clickable { taps++ })
    }
}
```

The scaffold test is the "trivial Compose-compiling file": its `@Composable Counter` goes through the compose compiler, Material 3 draws it under Robolectric, and the screen-size check proves `robolectric.properties` is read. It reads the size from `Resources.configuration` outside composition, because reading `LocalConfiguration.screenWidthDp` inside it trips Compose lint's `ConfigurationScreenWidthHeight` warning.

- [ ] **Step 2: Verify the scaffold**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:assembleDebug
./gradlew :featureflag-ui:testDebugUnitTest --tests '*.ComposeScaffoldTest'
```

Expected: `BUILD SUCCESSFUL` both times. `featureflag-ui/build/outputs/aar/featureflag-ui-debug.aar` exists, and `featureflag-ui/build/test-results/testDebugUnitTest/TEST-com.andyyhope.featureflag.ui.ComposeScaffoldTest.xml` shows `tests="2" … failures="0" errors="0"`. If `allWarningsAsErrors` fails on a Compose deprecation, use the replacement API (overview, "Reconciliation notes"); none fired in the check.

- [ ] **Step 3: Write the failing tests**

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/CompanionSchemas.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.net.URI
import java.time.Instant

/**
 * The Swift tests' `@FlagContainer` and `@FlagRecord` fixtures, rebuilt by hand as the schemas
 * their macros publish: the same keys, property paths, types, defaults, cases, groups and
 * descriptions. A companion only ever sees a schema, so these are exactly what it works from.
 */
internal object CompanionSchemas {

    val generatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** FlagEditingStoreTests' `CompanionFlags`. */
    val companion: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("new-onboarding", listOf("newOnboarding"), "New onboarding experience", FlagValueBox.Bool(false)),
            entry("max-items", listOf("maxItems"), "Maximum items", FlagValueBox.Int(10)),
            entry("checkout.apple-pay", listOf("checkout", "applePay"), "Apple Pay", FlagValueBox.Bool(false)),
            entry("checkout.ratio", listOf("checkout", "ratio"), "Ratio", FlagValueBox.Double(1.0)),
            entry(
                "checkout.tier",
                listOf("checkout", "tier"),
                "Tier",
                FlagValueBox.String("free"),
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry("checkout.launched-at", listOf("checkout", "launchedAt"), "Launched at", FlagValueBox.Date(Instant.EPOCH)),
            entry("checkout.endpoint", listOf("checkout", "endpoint"), "Endpoint", FlagValueBox.Url(URI("https://example.com"))),
            entry("checkout.express.label", listOf("checkout", "express", "label"), "Label", FlagValueBox.String("")),
            entry(
                "checkout.express.tags",
                listOf("checkout", "express", "tags"),
                "Tags",
                FlagValueBox.Array(emptyList()),
                type = FlagValueType.Array(FlagValueType.String),
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
        ),
    )

    /** FlagEditingStoreEdgeCaseTests' `DemoUIFlags`. */
    val demoUi: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("new-onboarding", listOf("newOnboarding"), "New onboarding", FlagValueBox.Bool(false)),
            entry("max-items", listOf("maxItems"), "Maximum items", FlagValueBox.Int(10)),
            entry("checkout.apple-pay", listOf("checkout", "applePay"), "Apple Pay", FlagValueBox.Bool(false)),
        ),
        groups = listOf(FlagSchema.Group(listOf("checkout"), "Checkout")),
    )

    /** FlagTreeNodeTests' `TreeFlags`, four levels deep. */
    val tree: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("top", listOf("top"), "Top", FlagValueBox.Bool(false)),
            entry("second", listOf("second"), "Second", FlagValueBox.Int(1)),
            entry("checkout.apple-pay", listOf("checkout", "applePay"), "Apple Pay", FlagValueBox.Bool(false)),
            entry("checkout.express.one-tap", listOf("checkout", "express", "oneTap"), "One tap", FlagValueBox.Bool(false)),
            entry(
                "checkout.express.confirmation.delay",
                listOf("checkout", "express", "confirmation", "delay"),
                "Delay",
                FlagValueBox.Double(3.0),
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
            FlagSchema.Group(listOf("checkout", "express", "confirmation"), "Confirmation"),
        ),
    )

    /** FlagArrayEditorTests' `ArrayEditorFlags`. */
    val arrayEditor: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry(
                "markets",
                listOf("markets"),
                "Markets",
                FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
                type = FlagValueType.Array(FlagValueType.String),
            ),
            entry(
                "counts",
                listOf("counts"),
                "Counts",
                FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))),
                type = FlagValueType.Array(FlagValueType.Int),
            ),
            entry(
                "launches",
                listOf("launches"),
                "Launch dates",
                FlagValueBox.Array(emptyList()),
                type = FlagValueType.Array(FlagValueType.Date),
            ),
            entry(
                "nested",
                listOf("nested"),
                "Nested",
                FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.String("a"))))),
                type = FlagValueType.Array(FlagValueType.Array(FlagValueType.String)),
            ),
            entry(
                "shares",
                listOf("shares"),
                "Shares",
                FlagValueBox.Dictionary(emptyMap()),
                type = FlagValueType.Dictionary(FlagValueType.Double),
            ),
            entry("name", listOf("name"), "Name", FlagValueBox.String("demo")),
        ),
    )

    // Record shapes, in declaration order, as @FlagRecord publishes them.

    /** FlagRecordEditorTests' `Endpoint`: no key. */
    val endpointShape: List<FlagRecordField> = listOf(
        FlagRecordField("name", FlagValueType.String),
        FlagRecordField("url", FlagValueType.Url),
        FlagRecordField("enabled", FlagValueType.Bool),
        FlagRecordField("weight", FlagValueType.Int),
        FlagRecordField(
            "tier",
            FlagValueType.String,
            cases = listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")),
        ),
    )

    /** FlagRecordKeyEditorTests' `Hop`, keyed by host. */
    val hopShape: List<FlagRecordField> = listOf(FlagRecordField("host", FlagValueType.String, isKey = true))

    /** FlagRecordKeyEditorTests' `Route`: keyed by name, with a nested list of hops that defaults to none. */
    val routeShape: List<FlagRecordField> = listOf(
        FlagRecordField("name", FlagValueType.String, isKey = true),
        FlagRecordField("hops", FlagValueType.String, defaultValue = FlagValueBox.String("[]"), fields = hopShape),
    )

    /** FlagRecordKeyEditorTests' `Stamped`, keyed by a date. */
    val stampedShape: List<FlagRecordField> = listOf(
        FlagRecordField("at", FlagValueType.Date, isKey = true),
        FlagRecordField("note", FlagValueType.String, defaultValue = FlagValueBox.String("")),
    )

    /** FlagRecordKeyEditorTests' `EditorNote`: no key. */
    val noteShape: List<FlagRecordField> = listOf(FlagRecordField("text", FlagValueType.String))

    val production: Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String("production"),
        "url" to FlagValueBox.Url(URI("https://prod.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(10),
        "tier" to FlagValueBox.String("primary"),
    )

    /** FlagRecordEditorTests' `RecordEditorFlags`. */
    val recordEditor: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("endpoints", listOf("endpoints"), "Endpoints", FlagRecordList.encode(listOf(production)), shape = endpointShape),
            entry("name", listOf("name"), "Name", FlagValueBox.String("Demo")),
        ),
    )

    /** FlagRecordKeyEditorTests' `KeyedEditorFlags`. */
    val keyedEditor: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("routes", listOf("routes"), "Routes", FlagValueBox.String("[]"), shape = routeShape),
            entry("notes", listOf("notes"), "Notes", FlagValueBox.String("[]"), shape = noteShape),
            entry("stamped", listOf("stamped"), "Stamped", FlagValueBox.String("[]"), shape = stampedShape),
        ),
    )

    /** FlagQRCodeTextTests' `QRFlags`. */
    val qr: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("page-size", listOf("pageSize"), "Items per page", FlagValueBox.Int(10)),
            entry("note", listOf("note"), "A note", FlagValueBox.String("")),
        ),
    )

    /** FlagContainerDescriptionUITests' `DescribedUIFlags`. */
    val described: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        description = "What the checkout team can turn on",
        flags = listOf(entry("new-onboarding", listOf("newOnboarding"), "Onboarding", FlagValueBox.Bool(false))),
    )

    /** FlagContainerDescriptionUITests' `PlainUIFlags`. */
    val plain: FlagSchema = described.copy(description = null)

    /** A flag of [default]'s type unless [type] says otherwise, as a hand-built schema states it. */
    fun entry(
        key: String,
        path: List<String>,
        description: String,
        default: FlagValueBox,
        type: FlagValueType = scalarType(default),
        cases: List<FlagValueBox>? = null,
        shape: List<FlagRecordField>? = null,
    ): FlagSchema.Entry = FlagSchema.Entry(
        key = FlagKey(key),
        propertyPath = path,
        description = description,
        valueType = type,
        defaultValue = default,
        cases = cases,
        recordShape = shape,
    )

    private fun scalarType(box: FlagValueBox): FlagValueType = when (box) {
        is FlagValueBox.Bool -> FlagValueType.Bool
        is FlagValueBox.Int -> FlagValueType.Int
        is FlagValueBox.Double -> FlagValueType.Double
        is FlagValueBox.Float -> FlagValueType.Float
        is FlagValueBox.String -> FlagValueType.String
        is FlagValueBox.Data -> FlagValueType.Data
        is FlagValueBox.Date -> FlagValueType.Date
        is FlagValueBox.Url -> FlagValueType.Url
        is FlagValueBox.Array, is FlagValueBox.Dictionary -> error("A collection's element type has to be given.")
    }
}

// What the host reads: the Swift tests check writes through a FlagPole over the same store; these
// decode exactly as the host's FlagCodecs.records does, from a record type or from the shape alone.

internal enum class Tier(val rawValue: String) { Primary("primary"), Secondary("secondary") }

internal data class Endpoint(val name: String, val url: URI, val enabled: Boolean, val weight: Long, val tier: Tier)

/** A hand-written codec for [Endpoint], as KSP would generate it. */
internal object EndpointCodec : FlagRecordCodec<Endpoint> {
    override val shape: List<FlagRecordField> = CompanionSchemas.endpointShape
    override val keyField: String? = null

    override fun boxes(record: Endpoint): Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String(record.name),
        "url" to FlagValueBox.Url(record.url),
        "enabled" to FlagValueBox.Bool(record.enabled),
        "weight" to FlagValueBox.Int(record.weight),
        "tier" to FlagValueBox.String(record.tier.rawValue),
    )

    override fun fromBoxes(boxes: Map<String, FlagValueBox>): Endpoint? {
        return Endpoint(
            name = (boxes["name"] as? FlagValueBox.String)?.value ?: return null,
            url = (boxes["url"] as? FlagValueBox.Url)?.value ?: return null,
            enabled = (boxes["enabled"] as? FlagValueBox.Bool)?.value ?: return null,
            weight = (boxes["weight"] as? FlagValueBox.Int)?.value ?: return null,
            tier = Tier.entries.firstOrNull { FlagValueBox.String(it.rawValue) == boxes["tier"] } ?: return null,
        )
    }
}

/** A codec that keeps each record as its boxes, so the host's reader can be run from a shape alone. */
internal class RowsCodec(override val shape: List<FlagRecordField>) : FlagRecordCodec<Map<String, FlagValueBox>> {
    override val keyField: String? = shape.firstOrNull { it.isKey }?.name
    override fun boxes(record: Map<String, FlagValueBox>): Map<String, FlagValueBox> = record
    override fun fromBoxes(boxes: Map<String, FlagValueBox>): Map<String, FlagValueBox> = boxes
}

/** The endpoints the host's pole would read from [box], or null when it would fall back to its default. */
internal fun hostEndpoints(box: FlagValueBox?): FlagRecords<Endpoint>? =
    box?.let(FlagCodecs.records(EndpointCodec)::decode)

/** The records the host's pole would read from [box] through [shape], or null when it would use its default. */
internal fun hostRecords(
    box: FlagValueBox?,
    shape: List<FlagRecordField>,
): FlagRecords<Map<String, FlagValueBox>>? = box?.let(FlagCodecs.records(RowsCodec(shape))::decode)
```

Keys are the kebab-case of each property path, as `KeyEncoding.Kebabcase` makes them, and every flag is listed depth first in declaration order, as a published schema lists them.

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditorKindTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ports the editor-selection half of Swift's FlagEditorValueTests.
class FlagEditorKindTest {

    // Editor selection

    @Test
    fun dataGetsItsOwnEditor() {
        assertEquals(FlagEditorKind.Data, entry(FlagValueType.Data).editorKind)
    }

    @Test
    fun floatAndDoubleShareTheDecimalEditor() {
        assertEquals(FlagEditorKind.Decimal, entry(FlagValueType.Float).editorKind)
        assertEquals(FlagEditorKind.Decimal, entry(FlagValueType.Double).editorKind)
    }

    @Test
    fun anArrayOfScalarsGetsARowPerElement() {
        assertEquals(FlagEditorKind.List(FlagValueType.Int), entry(FlagValueType.Array(FlagValueType.Int)).editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.Date), entry(FlagValueType.Array(FlagValueType.Date)).editorKind)
    }

    @Test
    fun structuralCollectionsKeepTheJsonEditor() {
        assertEquals(FlagEditorKind.Json, entry(FlagValueType.Dictionary(FlagValueType.String)).editorKind)
        assertEquals(FlagEditorKind.Json, entry(FlagValueType.Array(FlagValueType.Array(FlagValueType.String))).editorKind)
        assertEquals(FlagEditorKind.Json, entry(FlagValueType.Array(FlagValueType.Dictionary(FlagValueType.Int))).editorKind)
    }

    @Test
    fun casesWinOverTheUnderlyingType() {
        // An Int-backed enum still gets a picker, not a number field.
        val cases = listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))

        assertEquals(FlagEditorKind.Picker(cases), entry(FlagValueType.Int, cases = cases).editorKind)
    }

    @Test
    fun anEmptyCaseListFallsBackToTheTypesEditor() {
        assertEquals(FlagEditorKind.Text, entry(FlagValueType.String, cases = emptyList()).editorKind)
    }

    @Test
    fun everyScalarTypeGetsItsControl() {
        assertEquals(FlagEditorKind.Toggle, entry(FlagValueType.Bool).editorKind)
        assertEquals(FlagEditorKind.Integer, entry(FlagValueType.Int).editorKind)
        assertEquals(FlagEditorKind.Text, entry(FlagValueType.String).editorKind)
        assertEquals(FlagEditorKind.Date, entry(FlagValueType.Date).editorKind)
        assertEquals(FlagEditorKind.Url, entry(FlagValueType.Url).editorKind)
    }

    @Test
    fun aRecordShapeGetsTheRecordEditor() {
        val shape = listOf(FlagRecordField("name", FlagValueType.String, isKey = true))

        assertEquals(FlagEditorKind.Records(shape), entry(FlagValueType.String, shape = shape).editorKind)
    }

    @Test
    fun casesWinOverARecordShape() {
        val cases = listOf(FlagValueBox.String("[]"))
        val shape = listOf(FlagRecordField("name", FlagValueType.String))

        assertEquals(FlagEditorKind.Picker(cases), entry(FlagValueType.String, cases = cases, shape = shape).editorKind)
    }

    @Test
    fun anEmptyRecordShapeFallsBackToTheTypesEditor() {
        assertEquals(FlagEditorKind.Text, entry(FlagValueType.String, shape = emptyList()).editorKind)
    }

    // The wrapping editor

    @Test
    fun dataIsEditedAsABlockRatherThanASingleLine() {
        val data = entry(FlagValueType.Data, FlagValueBox.Data(ByteArray(0)))

        assertEquals(FlagEditorKind.Data, data.editorKind)
        assertTrue(data.editorKind.wantsWrappingEditor)
    }

    @Test
    fun structuralCollectionsAlsoUseTheWrappingEditor() {
        for (type in listOf(FlagValueType.Dictionary(FlagValueType.Int), FlagValueType.Array(FlagValueType.Array(FlagValueType.String)))) {
            assertTrue(entry(type, FlagValueBox.Array(emptyList())).editorKind.wantsWrappingEditor, type.typeName)
        }
    }

    @Test
    fun aListDoesNotUseTheWrappingEditor() {
        assertFalse(entry(FlagValueType.Array(FlagValueType.String), FlagValueBox.Array(emptyList())).editorKind.wantsWrappingEditor)
    }

    @Test
    fun scalarsStayOnASingleLine() {
        val scalars = listOf(
            FlagValueType.String to FlagValueBox.String(""),
            FlagValueType.Int to FlagValueBox.Int(0),
            FlagValueType.Double to FlagValueBox.Double(0.0),
            FlagValueType.Float to FlagValueBox.Float(0f),
            FlagValueType.Url to FlagValueBox.Url(URI("https://a.example")),
            FlagValueType.Date to FlagValueBox.Date(Instant.EPOCH),
            FlagValueType.Bool to FlagValueBox.Bool(false),
        )
        for ((type, value) in scalars) {
            assertFalse(entry(type, value).editorKind.wantsWrappingEditor, "${type.typeName} should stay inline")
        }
    }

    @Test
    fun aPickerIsNeverAWrappingEditor() {
        val tier = entry(
            FlagValueType.String,
            FlagValueBox.String("free"),
            cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
        )

        assertFalse(tier.editorKind.wantsWrappingEditor)
    }

    @Test
    fun recordsAreNeverAWrappingEditor() {
        val shape = listOf(FlagRecordField("name", FlagValueType.String))

        assertFalse(entry(FlagValueType.String, shape = shape).editorKind.wantsWrappingEditor)
    }

    private fun entry(
        type: FlagValueType,
        default: FlagValueBox = FlagValueBox.String(""),
        cases: List<FlagValueBox>? = null,
        shape: List<FlagRecordField>? = null,
    ) = FlagSchema.Entry(
        key = FlagKey("a"),
        propertyPath = listOf("a"),
        description = "",
        valueType = type,
        defaultValue = default,
        cases = cases,
        recordShape = shape,
    )
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagTreeNodeTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ports Swift's FlagTreeNodeTests.
class FlagTreeNodeTest {

    private val tree = FlagTreeNode.of(CompanionSchemas.tree)

    @Test
    fun rootHoldsOnlyItsOwnFlags() {
        assertEquals(listOf("top", "second"), tree.flags.keys())
    }

    @Test
    fun rootListsItsDirectGroupsOnly() {
        assertEquals(listOf("Checkout"), tree.groups.map { it.title })
    }

    @Test
    fun aGroupHoldsItsOwnFlagsAndItsOwnSubgroups() {
        val checkout = tree.groups.first()

        assertEquals(listOf("checkout"), checkout.path)
        assertEquals(listOf("checkout.apple-pay"), checkout.flags.keys())
        assertEquals(listOf("Express"), checkout.groups.map { it.title })
    }

    @Test
    fun nestingGoesAsDeepAsTheContainer() {
        val express = tree.groups.first().groups.first()
        assertEquals(listOf("checkout", "express"), express.path)
        assertEquals(listOf("checkout.express.one-tap"), express.flags.keys())

        val deeper = express.groups.first()
        assertEquals(listOf("checkout", "express", "confirmation"), deeper.path)
        assertEquals(listOf("checkout.express.confirmation.delay"), deeper.flags.keys())
        assertTrue(deeper.groups.isEmpty())
    }

    @Test
    fun everyFlagAppearsExactlyOnceInTheWholeTree() {
        val all = tree.allFlags.keys()

        assertEquals(all.size, all.toSet().size, "a flag is in two places")
        assertEquals(CompanionSchemas.tree.flags.keys().toSet(), all.toSet())
    }

    @Test
    fun declarationOrderSurvives() {
        assertEquals(
            listOf("top", "second", "checkout.apple-pay", "checkout.express.one-tap", "checkout.express.confirmation.delay"),
            tree.allFlags.keys(),
        )
    }

    @Test
    fun identityIsThePath() {
        assertEquals("", tree.id)
        assertEquals("checkout", tree.groups.first().id)
        assertEquals("checkout.express", tree.groups.first().groups.first().id)
    }

    @Test
    fun theRootHasNoTitle() {
        assertEquals(null, tree.title)
        assertEquals(emptyList(), tree.path)
    }

    // Degenerate shapes

    @Test
    fun anEmptySchemaProducesAnEmptyRoot() {
        val empty = FlagTreeNode.of(FlagSchema(flags = emptyList()))

        assertTrue(empty.isEmpty)
        assertTrue(empty.flags.isEmpty())
        assertTrue(empty.groups.isEmpty())
    }

    @Test
    fun aGroupWithNoFlagsOfItsOwnStillCarriesItsChildren() {
        // "Checkout" holding only "Express" must not vanish, or its contents become unreachable.
        val node = FlagTreeNode.of(oneTapUnderTwoGroups)

        assertEquals(listOf("Checkout"), node.groups.map { it.title })
        assertTrue(node.groups[0].flags.isEmpty())
        assertEquals(listOf("Express"), node.groups[0].groups.map { it.title })
        assertEquals(1, node.allFlags.size)
        assertFalse(node.isEmpty)
    }

    @Test
    fun aGroupDeclaredWithNoFlagsAnywhereIsEmpty() {
        val schema = FlagSchema(flags = emptyList(), groups = listOf(FlagSchema.Group(listOf("hollow"), "Hollow")))

        assertTrue(FlagTreeNode.of(schema).isEmpty)
    }

    // Sections

    @Test
    fun aSectionIsIdentifiedByItsPath() {
        assertEquals("", FlagSection(emptyList(), null, "", emptyList()).id)
        assertEquals("checkout.express", FlagSection(listOf("checkout", "express"), "Express", "Checkout › Express", emptyList()).id)
    }

    private val oneTapUnderTwoGroups = FlagSchema(
        flags = listOf(
            FlagSchema.Entry(
                key = FlagKey("checkout.express.one-tap"),
                propertyPath = listOf("checkout", "express", "oneTap"),
                description = "",
                valueType = FlagValueType.Bool,
                defaultValue = FlagValueBox.Bool(false),
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
        ),
    )

    private fun List<FlagSchema.Entry>.keys(): List<String> = map { it.key.rawValue }
}
```

- [ ] **Step 4: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
```

Expected: `BUILD FAILED`. `:featureflag-ui:compileDebugUnitTestKotlin` reports `Unresolved reference 'FlagEditorKind'`, `Unresolved reference 'editorKind'`, `Unresolved reference 'FlagTreeNode'` and `Unresolved reference 'FlagSection'`.

- [ ] **Step 5: Write the editor kinds and the tree**

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditorKind.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType

/** Which control the editor shows for a flag. */
public sealed interface FlagEditorKind {
    public data object Toggle : FlagEditorKind
    public data object Integer : FlagEditorKind
    public data object Decimal : FlagEditorKind
    public data object Text : FlagEditorKind

    /** A choice between an enum's cases. */
    public data class Picker(public val cases: kotlin.collections.List<FlagValueBox>) : FlagEditorKind
    public data object Date : FlagEditorKind
    public data object Url : FlagEditorKind

    /** Base64, because there is nothing better to show for arbitrary bytes. */
    public data object Data : FlagEditorKind

    /** An array of scalars, edited a row at a time; [element] picks each row's control. */
    public data class List(public val element: FlagValueType) : FlagEditorKind

    /** A list of fixed-shape records, edited a record at a time through [fields]. */
    public data class Records(public val fields: kotlin.collections.List<FlagRecordField>) : FlagEditorKind

    /** Everything else structural (dictionaries, and arrays of collections), edited as JSON text. */
    public data object Json : FlagEditorKind

    /** True for [Data] and [Json]: a multi-line block with a copy button, not a single line. */
    public val wantsWrappingEditor: Boolean
        get() = this == Data || this == Json
}

/**
 * The control this flag is edited with, decided in this order: non-empty cases give a
 * [FlagEditorKind.Picker]; a non-empty record shape gives [FlagEditorKind.Records]; then the value
 * type, where an array of arrays or dictionaries and any dictionary are [FlagEditorKind.Json], and
 * any other array is a [FlagEditorKind.List].
 */
public val FlagSchema.Entry.editorKind: FlagEditorKind
    get() {
        cases?.takeIf { it.isNotEmpty() }?.let { return FlagEditorKind.Picker(it) }
        recordShape?.takeIf { it.isNotEmpty() }?.let { return FlagEditorKind.Records(it) }
        return when (val type = valueType) {
            FlagValueType.Bool -> FlagEditorKind.Toggle
            FlagValueType.Int -> FlagEditorKind.Integer
            FlagValueType.Double, FlagValueType.Float -> FlagEditorKind.Decimal
            FlagValueType.String -> FlagEditorKind.Text
            FlagValueType.Date -> FlagEditorKind.Date
            FlagValueType.Url -> FlagEditorKind.Url
            FlagValueType.Data -> FlagEditorKind.Data
            is FlagValueType.Array -> when (type.element) {
                is FlagValueType.Array, is FlagValueType.Dictionary -> FlagEditorKind.Json
                else -> FlagEditorKind.List(type.element)
            }
            is FlagValueType.Dictionary -> FlagEditorKind.Json
        }
    }
```

Inside `FlagEditorKind`, the nested `List` shadows `kotlin.collections.List`, so `Picker` and `Records` name the collection in full (see "Contract issues found" 3).

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagTreeNode.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema

/**
 * One level of the flag tree: the flags declared directly here, and the groups beneath.
 *
 * A published schema is flat: each flag carries the property path it sits at. An editor wants
 * the shape back, so a tree of any depth can be walked one screen at a time. Order follows the
 * schema, which follows declaration order. A flag whose parent group the schema does not list is
 * not reachable from the root.
 */
public data class FlagTreeNode(
    /** Property names from the root; empty for the root itself. */
    public val path: List<String>,
    /** The group's description, or null at the root. */
    public val title: String?,
    /** Flags declared directly at this level: those whose property path, less its last name, is [path]. */
    public val flags: List<FlagSchema.Entry>,
    /** The schema's groups one level below [path], in schema order. */
    public val groups: List<FlagTreeNode>,
) {

    /** [path] joined with dots; the root's is empty. */
    public val id: String
        get() = path.joinToString(".")

    /** Every flag at or below this node: this level's first, then each group's, depth first. */
    public val allFlags: List<FlagSchema.Entry>
        get() = flags + groups.flatMap { it.allFlags }

    /** Whether there is no flag here or anywhere beneath. */
    public val isEmpty: Boolean
        get() = flags.isEmpty() && groups.all { it.isEmpty }

    public companion object {

        /** Rebuilds the tree a schema flattened. */
        public fun of(schema: FlagSchema): FlagTreeNode = node(schema, path = emptyList(), title = null)

        private fun node(schema: FlagSchema, path: List<String>, title: String?): FlagTreeNode = FlagTreeNode(
            path = path,
            title = title,
            flags = schema.flags.filter { it.propertyPath.dropLast(1) == path },
            groups = schema.groups
                .filter { it.propertyPath.size == path.size + 1 && it.propertyPath.take(path.size) == path }
                .map { node(schema, it.propertyPath, it.description) },
        )
    }
}

/** One section of a flat, searchable list of flags: the ungrouped flags, or one group's own. */
public data class FlagSection(
    /** The group's property names; empty for the ungrouped flags at the root. */
    public val path: List<String>,
    /** The group's description, or null for the ungrouped section. */
    public val title: String?,
    /** Every ancestor group's description and this one's, joined with " › ", e.g. `Checkout › Express`. */
    public val pathDescription: String,
    public val entries: List<FlagSchema.Entry>,
) {

    /** [path] joined with dots; the ungrouped section's is empty. */
    public val id: String
        get() = path.joinToString(".")
}
```

- [ ] **Step 6: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. In `featureflag-ui/build/test-results/testDebugUnitTest/`: `FlagEditorKindTest` `tests="16"`, `FlagTreeNodeTest` `tests="12"`, `ComposeScaffoldTest` `tests="2"`, all `failures="0" errors="0"`. `featureflag-ui/build/reports/lint-results-debug.txt` says `No issues found.`

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml build-logic settings.gradle.kts featureflag-ui
git commit -m "Add the featureflag-ui module with Compose, the editor kinds and the flag tree" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A3: `FlagEditingStore` and `FlagRecordEditingException`

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagRecordEditingException.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditingStore.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/ScriptedSource.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditingStoreTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditingStoreEdgeCaseTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagArrayEditorTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagRecordEditorTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagRecordKeyEditorTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagQrCodeTextTest.kt`

**Interfaces:**
- Consumes: A1 `FlagValueText`, `FlagRecordList`; A2 `FlagTreeNode`, `FlagSection`, `editorKind`, `CompanionSchemas`; core `MutableFlagValueSource`, `SnapshotSource`, `FlagPayload(values).jsonBytes()`, `FlagPayload.decode(bytes, schema)`, `FlagQRCode.encode(payload)`, `FlagQRCode.decode(text, schema)`, `FlagQRCode.PREFIX`, `FlagImportException`, `FlagQRCodeException`, `FlagSerializationException.NonFiniteNumber`; `FlagBridgeException` (tests only, as the refusals a bridge throws).
- Produces: exactly the contract's `FlagEditingStore` and `FlagRecordEditingException`:
```kotlin
public class FlagRecordEditingException(public val field: String, public val value: FlagValueBox) : RuntimeException(/* the iOS message */)
public class FlagEditingStore(
    public val schema: FlagSchema,
    private val source: MutableFlagValueSource,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    public val revision: StateFlow<Long>
    public fun observeIn(scope: CoroutineScope): Job
    public val tree: FlagTreeNode
    public fun sections(query: String = ""): List<FlagSection>
    public fun entry(key: FlagKey): FlagSchema.Entry?
    public fun value(entry: FlagSchema.Entry): FlagValueBox
    public fun isOverridden(entry: FlagSchema.Entry): Boolean
    public val overriddenKeys: List<FlagKey>
    public fun overriddenCount(node: FlagTreeNode): Int
    public suspend fun setValue(entry: FlagSchema.Entry, box: FlagValueBox)
    public suspend fun reset(entry: FlagSchema.Entry)
    public suspend fun resetAll()
    public fun exportJson(): String
    public fun qrCode(): String
    public val copyableQrCode: String?
    public suspend fun import(text: String)
    public fun records(entry: FlagSchema.Entry): List<Map<String, FlagValueBox>>?
    public suspend fun setRecords(entry: FlagSchema.Entry, records: List<Map<String, FlagValueBox>>)
}
```
- Test helper `ScriptedSource(values)`: an in-memory `MutableFlagValueSource` recording `writes` (one map per call) and `writeThreads`, with a settable `failure` thrown before anything is stored, and `elsewhereWrites(key, box)` for a write made around the store.

**Port targets:**
- `FlagEditingStoreTests` (20), in `FlagEditingStoreTest`, plus `overriddenCountCountsEveryFlagBeneathANode`, `exportIsTheDocumentAnExportWrites`, `exportingAnInfinityOrANaNFails` and, from `FlagContainerDescriptionUITests`, `theStoreSurfacesTheHostsDescription` and `aHostWithoutOneSurfacesNothing` (25).
- `FlagEditingStoreEdgeCaseTests` (13), in `FlagEditingStoreEdgeCaseTest`, plus the pinned `resettingEverythingClearsUnusableValuesToo` and `aFailedWriteLeavesTheValueUnchanged`, and `resettingEverythingIsOneWriteOfWhatIsStored`, `resettingWhenNothingIsStoredWritesNothing`, `aFailedResetOrImportThrowsWhatTheSourceThrows`, `everyWriteBumpsTheRevision`, `writesRunOnTheWriteDispatcher`, `observingStopsWithItsJob`, `anImportIsOneWrite` and `pastedTextIsTrimmedFirst` (23).
- `FlagArrayEditorTests`' 9 [P] tests, in `FlagArrayEditorTest`.
- The store-level `FlagRecordEditorTests` (11) plus `aFlagWithoutAShapeHasNoRecords`, in `FlagRecordEditorTest` (12), and `FlagRecordKeyEditorTests` (9) plus `theRefusalSaysWhichFieldTellsRecordsApart`, in `FlagRecordKeyEditorTest` (10). Where Swift reads the write back through `FlagPole`, these decode it with `hostEndpoints` and `hostRecords`.
- `FlagQRCodeTextTests` (4), in `FlagQrCodeTextTest`; the 8 000-character note uses the Swift test's LCG byte for byte.

Choices the contract leaves open (each also under "Contract issues found" 12):
- `tree` is computed once, because `schema` is a `val`.
- A write bumps `revision` in a `finally`, so a failed write still tells the row to re-read.
- `resetAll` with nothing stored writes nothing; `observeIn` subscribes before it returns (`CoroutineStart.UNDISPATCHED`).
- `copyableQrCode` is also null when an override holds a non-finite number, as Swift's `try?` makes it.
- `import` decodes inside the write, on `writeDispatcher`, because inflating a QR code is not free.

- [ ] **Step 1: Write the failing tests**

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/ScriptedSource.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory source that records each write, and the thread it ran on, and can be told to
 * refuse writes the way a bridge does: whole, before anything is stored.
 */
internal class ScriptedSource(values: Map<FlagKey, FlagValueBox> = emptyMap()) : MutableFlagValueSource {

    private val snapshot = SnapshotSource("scripted", values)

    override val sourceName: String = "scripted"
    override val changes: Flow<FlagChange> = snapshot.changes

    /** Thrown by every write while set; nothing is stored. */
    @Volatile
    var failure: RuntimeException? = null

    /** Every write, in order, as one map per call. */
    val writes = CopyOnWriteArrayList<Map<FlagKey, FlagValueBox?>>()

    /** For each entry of [writes], the name of the thread it ran on. */
    val writeThreads = CopyOnWriteArrayList<String>()

    val values: Map<FlagKey, FlagValueBox> get() = snapshot.values

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = snapshot.box(key, type)

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        setBoxes(mapOf(key to box))
    }

    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        writes += changes
        writeThreads += Thread.currentThread().name
        failure?.let { throw it }
        changes.forEach { (key, box) -> snapshot.setBox(key, box) }
    }

    /** Writes as the host or another companion would, around the store. */
    fun elsewhereWrites(key: String, box: FlagValueBox?) {
        snapshot.setBox(FlagKey(key), box)
    }
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditingStoreTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagEditingStoreTests and the store half of FlagContainerDescriptionUITests. The
// companion never sees the host's types: everything here is driven by a schema and a source.
class FlagEditingStoreTest {

    private val source = SnapshotSource("local")
    private val store = FlagEditingStore(CompanionSchemas.companion, source, Dispatchers.Unconfined)

    // Sections

    @Test
    fun ungroupedFlagsComeFirstInAnUnnamedSection() {
        val section = store.sections()[0]

        assertNull(section.title)
        assertEquals(listOf("new-onboarding", "max-items"), section.entries.keys())
    }

    @Test
    fun eachGroupBecomesASectionLabelledByItsDescription() {
        assertEquals(listOf(null, "Checkout", "Express"), store.sections().map { it.title })
    }

    @Test
    fun nestedGroupSectionsAreLabelledWithTheirFullPath() {
        assertEquals("Checkout › Express", store.sections()[2].pathDescription)
        assertEquals("Checkout", store.sections()[1].pathDescription)
        assertEquals("checkout.express", store.sections()[2].id)
    }

    // Search

    @Test
    fun searchMatchesKeys() {
        assertEquals(listOf("checkout.apple-pay"), store.sections("apple").flatMap { it.entries }.keys())
    }

    @Test
    fun searchMatchesDescriptions() {
        assertEquals(listOf("new-onboarding"), store.sections("onboarding").flatMap { it.entries }.keys())
    }

    @Test
    fun searchIsCaseInsensitive() {
        assertEquals(1, store.sections("APPLE").flatMap { it.entries }.size)
    }

    @Test
    fun emptySectionsAreDroppedWhileSearching() {
        assertEquals(listOf("Checkout"), store.sections("apple").map { it.title })
    }

    // Values

    @Test
    fun aFlagWithNoOverrideShowsItsDefault() {
        val entry = entry("max-items")

        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
    }

    @Test
    fun settingAValueMarksTheFlagAsOverridden() = runBlocking<Unit> {
        val entry = entry("max-items")

        store.setValue(entry, FlagValueBox.Int(3))

        assertEquals(FlagValueBox.Int(3), store.value(entry))
        assertTrue(store.isOverridden(entry))
    }

    @Test
    fun resettingRestoresTheDefault() = runBlocking<Unit> {
        val entry = entry("max-items")
        store.setValue(entry, FlagValueBox.Int(3))

        store.reset(entry)

        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
    }

    @Test
    fun resetAllClearsEveryOverride() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))
        store.setValue(entry("new-onboarding"), FlagValueBox.Bool(true))

        store.resetAll()

        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun overriddenKeysReportsWhatHasBeenChanged() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))

        assertEquals(listOf(FlagKey("max-items")), store.overriddenKeys)
    }

    @Test
    fun overriddenCountCountsEveryFlagBeneathANode() = runBlocking<Unit> {
        store.setValue(entry("checkout.apple-pay"), FlagValueBox.Bool(true))
        store.setValue(entry("checkout.express.label"), FlagValueBox.String("Go"))

        val checkout = store.tree.groups.single()
        assertEquals(2, store.overriddenCount(checkout))
        assertEquals(1, store.overriddenCount(checkout.groups.single()))
        assertEquals(2, store.overriddenCount(store.tree))
    }

    // Choosing an editor

    @Test
    fun eachTypeGetsASuitableEditor() {
        assertEquals(FlagEditorKind.Toggle, entry("new-onboarding").editorKind)
        assertEquals(FlagEditorKind.Integer, entry("max-items").editorKind)
        assertEquals(FlagEditorKind.Decimal, entry("checkout.ratio").editorKind)
        assertEquals(FlagEditorKind.Text, entry("checkout.express.label").editorKind)
        assertEquals(FlagEditorKind.Date, entry("checkout.launched-at").editorKind)
        assertEquals(FlagEditorKind.Url, entry("checkout.endpoint").editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.String), entry("checkout.express.tags").editorKind)
    }

    @Test
    fun anEnumGetsAPickerOfItsCases() {
        assertEquals(
            FlagEditorKind.Picker(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro"))),
            entry("checkout.tier").editorKind,
        )
    }

    // Text editing

    @Test
    fun valuesRenderAsEditableText() {
        assertEquals("3", FlagValueText.format(FlagValueBox.Int(3)))
        assertEquals("hi", FlagValueText.format(FlagValueBox.String("hi")))
        assertEquals("https://a.example", FlagValueText.format(FlagValueBox.Url(URI("https://a.example"))))
        assertEquals("[\"a\",\"b\"]", FlagValueText.format(FlagValueBox.Array(listOf(FlagValueBox.String("a"), FlagValueBox.String("b")))))
    }

    @Test
    fun editedTextIsParsedBackAccordingToTheDeclaredType() {
        assertEquals(FlagValueBox.Int(3), FlagValueText.parse("3", FlagValueType.Int))
        assertEquals(FlagValueBox.Double(3.5), FlagValueText.parse("3.5", FlagValueType.Double))
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.String("a"))),
            FlagValueText.parse("[\"a\"]", FlagValueType.Array(FlagValueType.String)),
        )
    }

    @Test
    fun unparseableTextIsRejectedRatherThanGuessed() {
        assertNull(FlagValueText.parse("lots", FlagValueType.Int))
        assertNull(FlagValueText.parse("not json", FlagValueType.Array(FlagValueType.String)))
        assertNull(FlagValueText.parse("", FlagValueType.Int))
    }

    // Transport

    @Test
    fun exportCarriesOnlyOverrides() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))

        val exported = FlagPayload.decode(store.exportJson().encodeToByteArray(), CompanionSchemas.companion)

        assertEquals(mapOf(FlagKey("max-items") to FlagValueBox.Int(3)), exported.values)
    }

    @Test
    fun exportIsTheDocumentAnExportWrites() = runBlocking<Unit> {
        store.setValue(entry("max-items"), FlagValueBox.Int(3))

        val json = store.exportJson()

        assertTrue(json.startsWith("{\n  \"exportedAt\" : \""), json)
        assertTrue(json.endsWith("\"formatVersion\" : 1,\n  \"values\" : {\n    \"max-items\" : 3\n  }\n}"), json)
    }

    @Test
    fun exportingAnInfinityOrANaNFails() {
        source.setBox(FlagKey("checkout.ratio"), FlagValueBox.Double(Double.NaN))

        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { store.exportJson() }

        assertEquals(FlagKey("checkout.ratio"), error.key)
        assertNull(store.copyableQrCode)
    }

    @Test
    fun importAppliesOverrides() = runBlocking<Unit> {
        val other = FlagEditingStore(CompanionSchemas.companion, SnapshotSource("other"), Dispatchers.Unconfined)
        other.setValue(other.entry(FlagKey("max-items"))!!, FlagValueBox.Int(3))

        store.import(other.exportJson())

        assertEquals(FlagValueBox.Int(3), store.value(entry("max-items")))
    }

    @Test
    fun qrCodeRoundTripsThroughTheStore() = runBlocking<Unit> {
        store.setValue(entry("new-onboarding"), FlagValueBox.Bool(true))
        val other = FlagEditingStore(CompanionSchemas.companion, SnapshotSource("other"), Dispatchers.Unconfined)

        other.import(store.qrCode())

        assertEquals(listOf(FlagKey("new-onboarding")), other.overriddenKeys)
    }

    // The host's description

    @Test
    fun theStoreSurfacesTheHostsDescription() {
        val described = FlagEditingStore(CompanionSchemas.described, SnapshotSource("s"))

        assertEquals("What the checkout team can turn on", described.schema.description)
    }

    @Test
    fun aHostWithoutOneSurfacesNothing() {
        assertNull(FlagEditingStore(CompanionSchemas.plain, SnapshotSource("s")).schema.description)
    }

    private fun entry(key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun List<FlagSchema.Entry>.keys(): List<String> = map { it.key.rawValue }
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditingStoreEdgeCaseTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Ports Swift's FlagEditingStoreEdgeCaseTests, plus what a bridge adds: writes that fail, and
// stored values the host's schema no longer accepts.
class FlagEditingStoreEdgeCaseTest {

    // Sections

    @Test
    fun aSchemaWithNoGroupsIsOneUnnamedSection() {
        val store = store(FlagSchema(flags = listOf(bool("a"), bool("b"))))

        assertEquals(1, store.sections().size)
        assertNull(store.sections()[0].title)
        assertEquals(2, store.sections()[0].entries.size)
    }

    @Test
    fun anEmptySchemaHasNoSections() {
        assertTrue(store(FlagSchema(flags = emptyList())).sections().isEmpty())
    }

    @Test
    fun aGroupHoldingOnlyOtherGroupsShowsNoEmptySection() {
        // "Checkout" contains nothing directly, so it would render as a blank heading.
        val store = store(
            FlagSchema(
                flags = listOf(bool("checkout.express.one-tap", listOf("checkout", "express", "oneTap"))),
                groups = listOf(
                    FlagSchema.Group(listOf("checkout"), "Checkout"),
                    FlagSchema.Group(listOf("checkout", "express"), "Express"),
                ),
            ),
        )

        assertEquals(listOf("Express"), store.sections().map { it.title })
        assertEquals("Checkout › Express", store.sections()[0].pathDescription)
    }

    @Test
    fun searchingForSomethingAbsentLeavesNoSections() {
        assertTrue(store().sections("zzzz").isEmpty())
    }

    @Test
    fun searchIgnoresSurroundingWhitespace() {
        val matches = store().sections("   apple   ").flatMap { it.entries }

        assertEquals(listOf(FlagKey("checkout.apple-pay")), matches.map { it.key })
    }

    // Overrides

    @Test
    fun resettingEverythingLeavesForeignKeysAlone() = runBlocking<Unit> {
        // A companion's source can hold more than this schema's flags.
        val source = ScriptedSource(mapOf(FlagKey("unrelated.key") to FlagValueBox.String("someone else's")))
        val store = store(source = source)

        store.setValue(entry(store, "new-onboarding"), FlagValueBox.Bool(true))
        store.resetAll()

        assertTrue(store.overriddenKeys.isEmpty())
        assertEquals(FlagValueBox.String("someone else's"), source.values[FlagKey("unrelated.key")])
    }

    @Test
    fun aStoredValueOfTheWrongTypeReadsAsNotOverridden() {
        // A stale or hand-edited value shows the default, not a broken control.
        val source = ScriptedSource()
        val store = store(source = source)
        val entry = entry(store, "max-items")

        source.elsewhereWrites("max-items", FlagValueBox.String("nonsense"))

        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun aStoredValueOutsideAnEnumsCasesReadsAsNotOverridden() {
        // The host cannot represent it either, so it falls back to the default. The editor must
        // agree, or it shows a picker with nothing selected.
        val source = ScriptedSource()
        val store = store(FlagSchema(flags = listOf(tier)), source)
        val entry = entry(store, "tier")

        source.elsewhereWrites("tier", FlagValueBox.String("enterprise"))
        assertEquals(FlagValueBox.String("free"), store.value(entry))
        assertFalse(store.isOverridden(entry))

        source.elsewhereWrites("tier", FlagValueBox.String("pro"))
        assertTrue(store.isOverridden(entry))
    }

    @Test
    fun resettingEverythingClearsUnusableValuesToo() = runBlocking<Unit> {
        // After a host update: a value of the wrong type, a case that no longer exists, and a
        // record list with a repeated key. None reads as overridden; all of them are cleared.
        val routes = CompanionSchemas.keyedEditor.flags.first()
        val schema = FlagSchema(flags = CompanionSchemas.demoUi.flags + tier + routes)
        val stale = mapOf(
            FlagKey("max-items") to FlagValueBox.String("nonsense"),
            FlagKey("tier") to FlagValueBox.String("enterprise"),
            FlagKey("routes") to FlagValueBox.String("[{\"name\":\"a\"},{\"name\":\"a\"}]"),
            FlagKey("unrelated.key") to FlagValueBox.Bool(true),
        )
        val source = ScriptedSource(stale)
        val store = store(schema, source)
        assertFalse(store.isOverridden(entry(store, "max-items")))
        assertFalse(store.isOverridden(entry(store, "tier")))
        assertNull(store.records(routes))

        store.resetAll()

        assertEquals(mapOf(FlagKey("unrelated.key") to FlagValueBox.Bool(true)), source.values)
        assertEquals(FlagValueBox.Int(10), store.value(entry(store, "max-items")))
    }

    @Test
    fun resettingEverythingIsOneWriteOfWhatIsStored() = runBlocking<Unit> {
        val source = ScriptedSource(
            mapOf(FlagKey("max-items") to FlagValueBox.Int(3), FlagKey("new-onboarding") to FlagValueBox.Bool(true)),
        )

        store(source = source).resetAll()

        assertEquals(listOf(mapOf<FlagKey, FlagValueBox?>(FlagKey("new-onboarding") to null, FlagKey("max-items") to null)), source.writes)
    }

    @Test
    fun resettingWhenNothingIsStoredWritesNothing() = runBlocking<Unit> {
        val source = ScriptedSource()

        store(source = source).resetAll()

        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun entryLookupForAnUnknownKeyReturnsNothing() {
        assertNull(store().entry(FlagKey("not-a-flag")))
    }

    // Failed writes

    @Test
    fun aFailedWriteLeavesTheValueUnchanged() = runBlocking<Unit> {
        val source = ScriptedSource()
        val store = store(source = source)
        val entry = entry(store, "max-items")
        val refusal = FlagBridgeException.TooLarge(600_000, 524_288)
        source.failure = refusal

        val thrown = assertFailsWith<FlagBridgeException.TooLarge> { store.setValue(entry, FlagValueBox.Int(3)) }

        assertSame(refusal, thrown)
        assertEquals(FlagValueBox.Int(10), store.value(entry))
        assertFalse(store.isOverridden(entry))
        // The row that showed the edit is told to read the real value again.
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun aFailedResetOrImportThrowsWhatTheSourceThrows() = runBlocking<Unit> {
        val source = ScriptedSource(mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        val store = store(source = source)
        source.failure = FlagBridgeException.Unavailable(null)

        assertFailsWith<FlagBridgeException.Unavailable> { store.reset(entry(store, "max-items")) }
        assertFailsWith<FlagBridgeException.Unavailable> { store.resetAll() }
        assertFailsWith<FlagBridgeException.Unavailable> { store.import("{\"formatVersion\":1,\"values\":{\"max-items\":4}}") }
        assertEquals(FlagValueBox.Int(3), store.value(entry(store, "max-items")))
    }

    // Writes

    @Test
    fun everyWriteBumpsTheRevision() = runBlocking<Unit> {
        val store = store()
        val entry = entry(store, "max-items")

        store.setValue(entry, FlagValueBox.Int(3))
        store.reset(entry)
        store.import("{\"formatVersion\":1,\"values\":{}}")

        assertEquals(3L, store.revision.value)
    }

    @Test
    fun writesRunOnTheWriteDispatcher() = runBlocking<Unit> {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "flag-writes") }
        try {
            val source = ScriptedSource()
            val store = FlagEditingStore(CompanionSchemas.demoUi, source, executor.asCoroutineDispatcher())

            store.setValue(entry(store, "max-items"), FlagValueBox.Int(3))
            store.resetAll()

            // Coroutine debugging, on in tests, appends " @coroutine#n" to the name.
            assertEquals(2, source.writeThreads.size)
            assertTrue(source.writeThreads.all { it.startsWith("flag-writes") }, "${source.writeThreads}")
        } finally {
            executor.shutdown()
        }
    }

    // Live updates

    @Test
    fun anEditFromAnotherProcessRefreshesTheEditor() = runBlocking<Unit> {
        // Stands in for the host app, or a second companion, writing to the same store.
        val source = ScriptedSource()
        val store = store(source = source)
        val observing = store.observeIn(this)

        store.revision.test {
            assertEquals(0L, awaitItem())
            source.elsewhereWrites("new-onboarding", FlagValueBox.Bool(true))
            assertEquals(1L, awaitItem())
        }
        observing.cancelAndJoin()
    }

    @Test
    fun observingStopsWithItsJob() = runBlocking<Unit> {
        val source = ScriptedSource()
        val store = store(source = source)

        store.observeIn(this).cancelAndJoin()
        source.elsewhereWrites("new-onboarding", FlagValueBox.Bool(true))
        yield()

        assertEquals(0L, store.revision.value)
    }

    // Import

    @Test
    fun importingSomethingForeignIsRejectedWithEveryProblem() = runBlocking<Unit> {
        val source = ScriptedSource()
        val store = store(source = source)

        val error = assertFailsWith<FlagImportException.Rejected> {
            store.import("{\"formatVersion\":1,\"values\":{\"nope\":1,\"max-items\":\"lots\"}}")
        }

        assertEquals(setOf(FlagKey("nope"), FlagKey("max-items")), error.problems.map { it.key }.toSet())
        assertTrue(store.overriddenKeys.isEmpty())
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun importingAQrCodeThatIsNotOneIsRejected() = runBlocking<Unit> {
        // The prefix decides: with it, the text is read as a code, and this one does not decompress.
        assertFailsWith<FlagQRCodeException.Corrupt> { store().import("FFQR1:hello") }
        // Without it, the text is read as a document, and is not one either.
        val error = assertFailsWith<FlagImportException.Malformed> { store().import("hello") }
        assertEquals("not a JSON object", error.reason)
    }

    @Test
    fun anImportIsOneWrite() = runBlocking<Unit> {
        val source = ScriptedSource()

        store(source = source).import("{\"formatVersion\":1,\"values\":{\"max-items\":4,\"new-onboarding\":true}}")

        assertEquals(1, source.writes.size)
        assertEquals(
            mapOf<FlagKey, FlagValueBox?>(FlagKey("max-items") to FlagValueBox.Int(4), FlagKey("new-onboarding") to FlagValueBox.Bool(true)),
            source.writes.single(),
        )
    }

    @Test
    fun pastedTextIsTrimmedFirst() = runBlocking<Unit> {
        val sending = store()
        sending.setValue(entry(sending, "max-items"), FlagValueBox.Int(5))
        val receiving = store()

        receiving.import("  \n${sending.qrCode()}\n ")

        assertEquals(FlagValueBox.Int(5), receiving.value(entry(receiving, "max-items")))
    }

    @Test
    fun exportingNothingStillProducesAValidDocument() {
        val payload = FlagPayload.decode(store().exportJson().encodeToByteArray(), CompanionSchemas.demoUi)

        assertEquals(0, payload.values.size)
    }

    // Helpers

    private val tier = FlagSchema.Entry(
        key = FlagKey("tier"),
        propertyPath = listOf("tier"),
        description = "Tier",
        valueType = FlagValueType.String,
        defaultValue = FlagValueBox.String("free"),
        cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
    )

    private fun store(schema: FlagSchema = CompanionSchemas.demoUi, source: ScriptedSource = ScriptedSource()) =
        FlagEditingStore(schema, source, Dispatchers.Unconfined)

    private fun entry(store: FlagEditingStore, key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun bool(key: String, path: List<String> = listOf(key)) = FlagSchema.Entry(
        key = FlagKey(key),
        propertyPath = path,
        description = "",
        valueType = FlagValueType.Bool,
        defaultValue = FlagValueBox.Bool(false),
    )
}
```

`importingAQrCodeThatIsNotOneIsRejected` expects `Corrupt` for `FFQR1:hello` and `Malformed` for `hello`, not Swift's `unrecognisedFormat`; see "Contract issues found" 7.

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagArrayEditorTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports the logic of Swift's FlagArrayEditorTests: an array edited a row at a time, each edit
// writing the whole array back.
class FlagArrayEditorTest {

    private val store = FlagEditingStore(CompanionSchemas.arrayEditor, SnapshotSource("shared"), Dispatchers.Unconfined)

    // Which flags get the list editor

    @Test
    fun anArrayOfScalarsGetsAListEditor() {
        assertEquals(FlagEditorKind.List(FlagValueType.String), entry("markets").editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.Int), entry("counts").editorKind)
        assertEquals(FlagEditorKind.List(FlagValueType.Date), entry("launches").editorKind)
    }

    @Test
    fun structuralValuesStayAsJson() {
        assertEquals(FlagEditorKind.Json, entry("nested").editorKind)
        assertEquals(FlagEditorKind.Json, entry("shares").editorKind)
    }

    @Test
    fun scalarsAreUnaffected() {
        assertEquals(FlagEditorKind.Text, entry("name").editorKind)
    }

    // Adding, changing, removing

    @Test
    fun addingAppendsAnEmptyElementOfTheRightType() = runBlocking<Unit> {
        val markets = entry("markets")
        store.setValue(markets, FlagValueBox.Array(listOf(FlagValueBox.String("AU"))))

        store.setValue(markets, FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueText.empty(FlagValueType.String))))

        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String(""))), store.value(markets))
    }

    @Test
    fun everyTypeHasSomethingToAdd() {
        assertEquals(FlagValueBox.Bool(false), FlagValueText.empty(FlagValueType.Bool))
        assertEquals(FlagValueBox.Int(0), FlagValueText.empty(FlagValueType.Int))
        assertEquals(FlagValueBox.Double(0.0), FlagValueText.empty(FlagValueType.Double))
        assertEquals(FlagValueBox.String(""), FlagValueText.empty(FlagValueType.String))
        assertEquals(FlagValueBox.Data(ByteArray(0)), FlagValueText.empty(FlagValueType.Data))
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.Int(0))), FlagValueText.empty(FlagValueType.Array(FlagValueType.Int)))
        assertEquals(FlagValueBox.Dictionary(emptyMap()), FlagValueText.empty(FlagValueType.Dictionary(FlagValueType.Int)))
        // A URL has no empty form that is still a URL, so it gets a placeholder.
        assertEquals(FlagValueBox.Url(URI("https://example.com")), FlagValueText.empty(FlagValueType.Url))
    }

    @Test
    fun editingAnElementWritesTheWholeArrayBack() = runBlocking<Unit> {
        val counts = entry("counts")
        store.setValue(counts, ints(1, 2, 3))
        val values = (store.value(counts) as FlagValueBox.Array).value.toMutableList()

        values[1] = FlagValueBox.Int(20)
        store.setValue(counts, FlagValueBox.Array(values))

        assertEquals(ints(1, 20, 3), store.value(counts))
    }

    @Test
    fun anEmptyArrayIsStillAnOverride() = runBlocking<Unit> {
        // Removing every element leaves an empty array, which is a value, not the same as no override.
        val markets = entry("markets")

        store.setValue(markets, FlagValueBox.Array(emptyList()))
        assertTrue(store.isOverridden(markets))
        assertEquals(FlagValueBox.Array(emptyList()), store.value(markets))

        store.reset(markets)
        assertFalse(store.isOverridden(markets))
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))), store.value(markets))
    }

    // Elements round-trip through their text

    @Test
    fun eachElementTypeSurvivesItsDisplayString() {
        val cases = listOf(
            FlagValueType.String to FlagValueBox.String("AU"),
            FlagValueType.Int to FlagValueBox.Int(-3),
            FlagValueType.Double to FlagValueBox.Double(2.5),
            FlagValueType.Float to FlagValueBox.Float(1.5f),
            FlagValueType.Url to FlagValueBox.Url(URI("https://a.example/x")),
            FlagValueType.Data to FlagValueBox.Data(byteArrayOf(0x01, 0x02)),
        )
        for ((type, box) in cases) {
            assertEquals(box, FlagValueText.parse(FlagValueText.format(box), type), "${type.typeName} should survive its text")
        }
    }

    @Test
    fun anUneditableElementIsRejectedRatherThanStored() {
        assertNull(FlagValueText.parse("not a number", FlagValueType.Int))
        assertNull(FlagValueText.parse("", FlagValueType.Url))
    }

    private fun entry(key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun ints(vararg values: Long) = FlagValueBox.Array(values.map { FlagValueBox.Int(it) })
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagRecordEditorTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ports Swift's FlagRecordEditorTests: a row per record, a control per field, and the four verbs
// a list needs. Where Swift reads the result back through a FlagPole, these decode it with the
// host's own record codec.
class FlagRecordEditorTest {

    private val source = SnapshotSource("shared")
    private val store = FlagEditingStore(CompanionSchemas.recordEditor, source, Dispatchers.Unconfined)
    private val endpoints get() = assertNotNull(store.entry(FlagKey("endpoints")))

    // Which flags get the record editor

    @Test
    fun aRecordFlagGetsTheRecordEditor() {
        assertEquals(FlagEditorKind.Records(CompanionSchemas.endpointShape), endpoints.editorKind)
    }

    @Test
    fun aPlainStringFlagIsStillJustText() {
        assertEquals(FlagEditorKind.Text, assertNotNull(store.entry(FlagKey("name"))).editorKind)
    }

    // Reading

    @Test
    fun theDefaultRecordsAreReadableBeforeAnythingIsOverridden() {
        val records = assertNotNull(store.records(endpoints))

        assertEquals(1, records.size)
        assertEquals(FlagValueBox.String("production"), records.first()["name"])
    }

    @Test
    fun textThatIsNotAListOfRecordsReadsAsUnreadableRatherThanEmpty() = runBlocking<Unit> {
        // Empty and unreadable differ: one is a list you emptied, the other a value the host
        // already ignores. Showing "0 records" for the second would invite an add that does nothing.
        store.setValue(endpoints, FlagValueBox.String("{ not json"))

        assertNull(store.records(endpoints))
    }

    @Test
    fun aFlagWithoutAShapeHasNoRecords() {
        assertNull(store.records(assertNotNull(store.entry(FlagKey("name")))))
    }

    // The four verbs

    @Test
    fun addingAppendsARecordOfEmptyFields() = runBlocking<Unit> {
        val records = assertNotNull(store.records(endpoints))

        store.setRecords(endpoints, records + FlagRecordList.emptyRecord(CompanionSchemas.endpointShape))

        val updated = assertNotNull(store.records(endpoints))
        assertEquals(2, updated.size)
        assertEquals(FlagValueBox.String(""), updated.last()["name"])
        assertEquals(FlagValueBox.Int(0), updated.last()["weight"])
    }

    @Test
    fun aNewRecordsEnumFieldStartsOnARealCase() = runBlocking<Unit> {
        // An empty string is not a Tier, so a record added with one would make the host refuse the
        // whole list and fall back to its default.
        store.setRecords(endpoints, listOf(FlagRecordList.emptyRecord(CompanionSchemas.endpointShape)))

        val read = assertNotNull(hostEndpoints(source.box(FlagKey("endpoints"), FlagValueType.String)))
        assertEquals(1, read.size)
        assertEquals(Tier.Primary, read.first().tier)
    }

    @Test
    fun duplicatingPutsTheCopyRightAfterTheOriginal() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records.add(1, records[0])
        store.setRecords(endpoints, records)

        assertEquals(listOf("staging", "staging", "canary"), names())
    }

    @Test
    fun reorderingIsWrittenBackInTheNewOrder() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records.add(0, records.removeAt(1))
        store.setRecords(endpoints, records)

        assertEquals(listOf("canary", "staging"), names())
    }

    @Test
    fun removingLeavesTheRest() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records.removeAt(0)
        store.setRecords(endpoints, records)

        assertEquals(listOf("canary"), names())
    }

    @Test
    fun editingOneFieldLeavesEveryOtherRecordAlone() = runBlocking<Unit> {
        store.setRecords(endpoints, listOf(staging, canary))
        val records = assertNotNull(store.records(endpoints)).toMutableList()

        records[1] = records[1] + ("weight" to FlagValueBox.Int(99))
        store.setRecords(endpoints, records)

        val updated = assertNotNull(store.records(endpoints))
        assertEquals(staging, updated[0])
        assertEquals(FlagValueBox.Int(99), updated[1]["weight"])
        assertEquals(FlagValueBox.String("canary"), updated[1]["name"])
    }

    // Writing something the host can read

    @Test
    fun whatTheEditorWritesIsWhatTheHostReads() = runBlocking<Unit> {
        // The whole point: the companion has only the shape; the host has the type.
        store.setRecords(endpoints, listOf(staging, canary))

        val read = assertNotNull(hostEndpoints(source.box(FlagKey("endpoints"), FlagValueType.String)))
        assertEquals(listOf("staging", "canary"), read.map { it.name })
        assertEquals(listOf(7L, 3L), read.map { it.weight })
        assertEquals(Tier.Secondary, read.first().tier)
    }

    private fun names(): List<String> =
        assertNotNull(store.records(endpoints)).map { (it["name"] as FlagValueBox.String).value }

    private val staging = mapOf(
        "name" to FlagValueBox.String("staging"),
        "url" to FlagValueBox.Url(URI("https://staging.example")),
        "enabled" to FlagValueBox.Bool(false),
        "weight" to FlagValueBox.Int(7),
        "tier" to FlagValueBox.String("secondary"),
    )

    private val canary = mapOf(
        "name" to FlagValueBox.String("canary"),
        "url" to FlagValueBox.Url(URI("https://canary.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(3),
        "tier" to FlagValueBox.String("primary"),
    )
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagRecordKeyEditorTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ports Swift's FlagRecordKeyEditorTests: the companion cannot write a list the host will refuse,
// so a key the host enforces is enforced here too, including by "Add" pressed twice.
class FlagRecordKeyEditorTest {

    private val source = SnapshotSource("shared")
    private val store = FlagEditingStore(CompanionSchemas.keyedEditor, source, Dispatchers.Unconfined)
    private val routes get() = entry("routes")

    @Test
    fun addingTwiceMakesTwoRecordsTheHostCanRead() = runBlocking<Unit> {
        store.setRecords(routes, listOf(FlagRecordList.emptyRecord(CompanionSchemas.routeShape)))
        val records = assertNotNull(store.records(routes)).toMutableList()

        records += FlagRecordList.emptyRecord(CompanionSchemas.routeShape, alongside = records)
        store.setRecords(routes, records)

        val read = assertNotNull(hostRead("routes", CompanionSchemas.routeShape), "the host refused the list")
        assertEquals(2, read.size)
        assertEquals(2, read.map { it["name"] }.toSet().size, "both records share a name")
    }

    @Test
    fun aThirdAddIsStillDistinct() = runBlocking<Unit> {
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(CompanionSchemas.routeShape, alongside = records) }

        store.setRecords(routes, records)

        assertEquals(3, hostRead("routes", CompanionSchemas.routeShape)?.size)
    }

    @Test
    fun anEditThatWouldCollideIsRefused() = runBlocking<Unit> {
        store.setRecords(routes, listOf(named("a"), named("b")))
        val records = assertNotNull(store.records(routes)).toMutableList()

        records[1] = records[1] + ("name" to FlagValueBox.String("a"))
        val refusal = assertFailsWith<FlagRecordEditingException> { store.setRecords(routes, records) }

        assertEquals("name", refusal.field)
        assertEquals(FlagValueBox.String("a"), refusal.value)
        assertEquals(listOf("a", "b"), names(), "the refused write should have changed nothing")
    }

    @Test
    fun theRefusalSaysWhichFieldTellsRecordsApart() {
        assertEquals(
            "Another record already has that name. It is what tells one record from another, so two cannot share it.",
            FlagRecordEditingException("name", FlagValueBox.String("a")).message,
        )
    }

    @Test
    fun anEditThatDoesNotCollideIsAccepted() = runBlocking<Unit> {
        store.setRecords(routes, listOf(named("a"), named("b")))
        val records = assertNotNull(store.records(routes)).toMutableList()

        records[1] = records[1] + ("name" to FlagValueBox.String("c"))
        store.setRecords(routes, records)

        assertEquals(listOf("a", "c"), names())
    }

    @Test
    fun aRecordWithNoKeyIsUnaffected() = runBlocking<Unit> {
        val same = mapOf("text" to FlagValueBox.String("same"))

        store.setRecords(entry("notes"), listOf(same, same))

        assertEquals(listOf(same, same), store.records(entry("notes")))
    }

    @Test
    fun aListWithADuplicateKeyReadsAsUnreadableToTheEditorToo() = runBlocking<Unit> {
        // The host refuses it, so the editor must agree, and must say which rule was broken.
        store.setValue(routes, FlagValueBox.String("[{\"name\":\"a\",\"hops\":\"[]\"},{\"name\":\"a\",\"hops\":\"[]\"}]"))

        assertNull(store.records(routes))
        assertEquals(FlagValueBox.String("a"), FlagRecordList.duplicateKey(store.value(routes), assertNotNull(routes.recordShape)))
    }

    @Test
    fun twoAddsOnADateKeyStayDistinctOnceStored() = runBlocking<Unit> {
        // Dates are held to the nanosecond and stored to the second, so records added within one
        // second must still differ once stored.
        val stamped = entry("stamped")
        val records = mutableListOf<Map<String, FlagValueBox>>()
        repeat(3) { records += FlagRecordList.emptyRecord(CompanionSchemas.stampedShape, alongside = records) }

        store.setRecords(stamped, records)

        assertEquals(3, hostRead("stamped", CompanionSchemas.stampedShape)?.size, "the host refused the list")
    }

    @Test
    fun aNestedListWithADuplicateKeyIsRefused() = runBlocking<Unit> {
        // The store must not write what the host will not read at any depth: a repeated key in a
        // nested list makes the whole outer flag unreadable.
        val nested = FlagRecordList.encode(listOf(hop("a"), hop("a")))

        val refusal = assertFailsWith<FlagRecordEditingException> {
            store.setRecords(routes, listOf(mapOf("name" to FlagValueBox.String("one"), "hops" to nested)))
        }

        assertEquals("host", refusal.field)
        assertNull(source.box(FlagKey("routes"), FlagValueType.String))
    }

    @Test
    fun aNestedListWithDistinctKeysIsFine() = runBlocking<Unit> {
        val nested = FlagRecordList.encode(listOf(hop("a"), hop("b")))

        store.setRecords(routes, listOf(mapOf("name" to FlagValueBox.String("one"), "hops" to nested)))

        assertEquals(1, hostRead("routes", CompanionSchemas.routeShape)?.size)
    }

    private fun entry(key: String): FlagSchema.Entry = assertNotNull(store.entry(FlagKey(key)), key)

    private fun hostRead(key: String, shape: List<FlagRecordField>) =
        hostRecords(source.box(FlagKey(key), FlagValueType.String), shape)

    private fun names(): List<String> =
        assertNotNull(store.records(routes)).map { (it["name"] as FlagValueBox.String).value }

    private fun named(name: String) = mapOf("name" to FlagValueBox.String(name), "hops" to FlagValueBox.String("[]"))

    private fun hop(host: String) = mapOf("host" to FlagValueBox.String(host))
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/editing/FlagQrCodeTextTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagQRCodeTextTests: a device that cannot scan still gets the code as text.
class FlagQrCodeTextTest {

    @Test
    fun theCopiedCodeIsSomethingTheImportFieldAccepts() = runBlocking<Unit> {
        val sending = store("sending")
        sending.setValue(pageSize(sending), FlagValueBox.Int(42))
        val receiving = store("receiving")

        receiving.import(sending.qrCode())

        assertEquals(FlagValueBox.Int(42), receiving.value(pageSize(receiving)))
    }

    @Test
    fun theCodeIsPlainTextAPersonCanPaste() = runBlocking<Unit> {
        val store = store("sending")
        store.setValue(pageSize(store), FlagValueBox.Int(1))

        val code = store.qrCode()

        assertTrue(code.startsWith("FFQR1:"), code)
        assertFalse(code.contains(" "), "a code with spaces in it does not survive a paste")
    }

    @Test
    fun copyingIsOfferedOnlyWhenThereIsACodeToCopy() = runBlocking<Unit> {
        // Nothing overridden is not a failure, but nor is it a code worth copying.
        val store = store("empty")
        assertNull(store.copyableQrCode)

        store.setValue(pageSize(store), FlagValueBox.Int(7))

        assertEquals(store.qrCode(), store.copyableQrCode)
    }

    @Test
    fun aPayloadTooLargeToEncodeOffersNothingRatherThanThrowingIntoTheView() = runBlocking<Unit> {
        val store = store("huge")
        val note = assertNotNull(store.entry(FlagKey("note")))
        // Incompressible on purpose: the payload is deflated before it is encoded.
        store.setValue(note, FlagValueBox.String(incompressibleString(8_000)))

        assertFailsWith<FlagQRCodeException.PayloadTooLarge> { store.qrCode() }
        assertNull(store.copyableQrCode)
    }

    private fun store(name: String) = FlagEditingStore(CompanionSchemas.qr, SnapshotSource(name), Dispatchers.Unconfined)

    private fun pageSize(store: FlagEditingStore) = assertNotNull(store.entry(FlagKey("page-size")))

    /** The Swift test's generator, byte for byte: a 64-bit LCG, each byte the state's bits 33 to 40. */
    private fun incompressibleString(count: Int): String {
        var state = 0x9E37_79B9_7F4A_7C15uL
        val bytes = ByteArray(count) {
            state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
            (state shr 33).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
```

Expected: `BUILD FAILED`. `:featureflag-ui:compileDebugUnitTestKotlin` reports `Unresolved reference 'FlagEditingStore'` and `Unresolved reference 'FlagRecordEditingException'`, then the store's members.

- [ ] **Step 3: Write the store**

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagRecordEditingException.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.values.FlagValueBox

/**
 * A record write the editor refused: two records would share [value] in the key [field]. The
 * host refuses such a list and reads its default instead, so it is never written.
 */
public class FlagRecordEditingException(
    public val field: String,
    public val value: FlagValueBox,
) : RuntimeException(
    "Another record already has that $field. It is what tells one record from another, so two cannot share it.",
)
```

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing/FlagEditingStore.kt`
```kotlin
package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.transport.FlagQRCode
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The companion's model of one host: plain Kotlin over a schema and a source.
 *
 * Reads are synchronous and in memory, and each one asks the source afresh. Writes suspend on
 * [writeDispatcher], because a bridge source's writes are blocking calls into the host, and throw
 * whatever the source throws (for a bridge, `FlagBridgeException`). A write that fails changes
 * nothing, and still bumps [revision] so a screen that showed the edit shows the real value again.
 *
 * A store is bound to its [schema]. When the host publishes a different one, the companion's
 * session builds a new store over a new source.
 */
public class FlagEditingStore(
    public val schema: FlagSchema,
    private val source: MutableFlagValueSource,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val revisionState = MutableStateFlow(0L)

    /** Bumps on every source change and after every write, failed or not; screens read it to recompose. Starts at 0. */
    public val revision: StateFlow<Long> = revisionState.asStateFlow()

    /**
     * Collects the source's changes into [revision] until [scope] ends or the job is cancelled.
     * Subscribed before it returns, so a change made straight afterwards is not missed. The
     * session calls it once.
     */
    public fun observeIn(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        source.changes.collect { bump() }
    }

    // Structure

    /** The flag tree, for walking groups one screen at a time. */
    public val tree: FlagTreeNode = FlagTreeNode.of(schema)

    /**
     * The flags as sections, filtered by [query]: trimmed, then a case-insensitive substring of
     * a flag's key or description. The ungrouped flags come first in a section with no title,
     * then one section per group, in schema order, holding the group's own matching flags. A
     * section with nothing in it is left out.
     */
    public fun sections(query: String = ""): List<FlagSection> {
        val needle = query.trim().lowercase()
        val matches = { entry: FlagSchema.Entry ->
            needle.isEmpty() ||
                entry.key.rawValue.lowercase().contains(needle) ||
                entry.description.lowercase().contains(needle)
        }
        val sections = mutableListOf<FlagSection>()
        val ungrouped = schema.flags.filter { it.propertyPath.size == 1 && matches(it) }
        if (ungrouped.isNotEmpty()) sections += FlagSection(emptyList(), null, "", ungrouped)
        for (group in schema.groups) {
            val entries = schema.flags.filter { it.propertyPath.dropLast(1) == group.propertyPath && matches(it) }
            if (entries.isEmpty()) continue
            sections += FlagSection(group.propertyPath, group.description, pathDescription(group.propertyPath), entries)
        }
        return sections
    }

    /** The first flag with [key], or null. */
    public fun entry(key: FlagKey): FlagSchema.Entry? = schema.flags.firstOrNull { it.key == key }

    // Values

    /** The value in effect: the stored override when the host would use it, otherwise the default. */
    public fun value(entry: FlagSchema.Entry): FlagValueBox = usableOverride(entry) ?: entry.defaultValue

    /** Whether the flag holds an override the host would use. */
    public fun isOverridden(entry: FlagSchema.Entry): Boolean = usableOverride(entry) != null

    /** Every overridden flag's key, in schema order. */
    public val overriddenKeys: List<FlagKey>
        get() = schema.flags.filter(::isOverridden).map { it.key }

    /** How many flags at or below [node] are overridden. */
    public fun overriddenCount(node: FlagTreeNode): Int = node.allFlags.count(::isOverridden)

    // Writes

    /** Stores [box] for [entry]. The caller has already made it the flag's type. */
    public suspend fun setValue(entry: FlagSchema.Entry, box: FlagValueBox) {
        write { source.setBox(entry.key, box) }
    }

    /** Clears [entry]'s override, so it falls back to the host's default. */
    public suspend fun reset(entry: FlagSchema.Entry) {
        write { source.setBox(entry.key, null) }
    }

    /**
     * One write clearing every schema key that holds anything, whether the host would use it or
     * not: a value of the wrong type or an old case is still in the host's store. Keys outside
     * the schema are left alone, and so are keys that hold nothing. With nothing to clear, nothing
     * is written.
     */
    public suspend fun resetAll() {
        val stored = schema.flags.filter { source.box(it.key, it.valueType) != null }
        if (stored.isEmpty()) return
        write { source.setBoxes(stored.associate { it.key to null }) }
    }

    // Transport

    /**
     * The usable overrides as an export document: pretty JSON with sorted keys, as iOS writes it.
     *
     * @throws FlagSerializationException.NonFiniteNumber when an override holds an infinity or a NaN.
     */
    public fun exportJson(): String = FlagPayload(overriddenValues()).jsonBytes().decodeToString()

    /**
     * The usable overrides as a flag code, `FFQR1:` and then plain text with no spaces.
     *
     * @throws FlagQRCodeException.PayloadTooLarge when they will not fit in one QR code.
     * @throws FlagSerializationException.NonFiniteNumber when an override holds an infinity or a NaN.
     */
    public fun qrCode(): String = FlagQRCode.encode(FlagPayload(overriddenValues()))

    /** [qrCode], or null when nothing is overridden or there is no code to make: a view wants a button or none. */
    public val copyableQrCode: String?
        get() {
            if (overriddenKeys.isEmpty()) return null
            return try {
                qrCode()
            } catch (ignored: FlagQRCodeException.PayloadTooLarge) {
                null
            } catch (ignored: FlagSerializationException.NonFiniteNumber) {
                null
            }
        }

    /**
     * Applies pasted text: a flag code when, once trimmed, it starts with `FFQR1:`, otherwise an
     * export document. All or nothing: it is checked against [schema] first, then written in one
     * `setBoxes`, which a bridge sends as one host commit.
     *
     * @throws FlagImportException when the document is unreadable or any value is refused.
     * @throws FlagQRCodeException when a code is not one or cannot be decompressed.
     */
    public suspend fun import(text: String) {
        write {
            val trimmed = text.trim()
            val payload = if (trimmed.startsWith(FlagQRCode.PREFIX)) {
                FlagQRCode.decode(trimmed, schema)
            } else {
                FlagPayload.decode(trimmed.encodeToByteArray(), schema)
            }
            source.setBoxes(payload.values)
        }
    }

    // Records

    /** The rows of a record-list flag, or null when it has no shape or its value is not a list the host can read. */
    public fun records(entry: FlagSchema.Entry): List<Map<String, FlagValueBox>>? =
        entry.recordShape?.let { FlagRecordList.decode(value(entry), it) }

    /**
     * Writes [records] back as the text the host reads. Refuses, writing nothing, a list in which
     * two records share a key at any depth, because the host would refuse it and read its default.
     *
     * @throws FlagRecordEditingException for a repeated key.
     * @throws FlagSerializationException.NonFiniteNumber when a field holds an infinity or a NaN.
     */
    public suspend fun setRecords(entry: FlagSchema.Entry, records: List<Map<String, FlagValueBox>>) {
        entry.recordShape?.let { checkKeys(records, it) }
        setValue(entry, FlagRecordList.encode(records))
    }

    // Helpers

    /**
     * The stored value, but only when the host would use it: of the declared type and, for an
     * enum, one of its cases. A stale or hand-edited value reads as no override at all.
     */
    private fun usableOverride(entry: FlagSchema.Entry): FlagValueBox? {
        val box = source.box(entry.key, entry.valueType) ?: return null
        if (!box.matches(entry.valueType)) return null
        val cases = entry.cases
        if (!cases.isNullOrEmpty() && box !in cases) return null
        return box
    }

    private fun overriddenValues(): Map<FlagKey, FlagValueBox> {
        val values = LinkedHashMap<FlagKey, FlagValueBox>()
        for (entry in schema.flags) {
            if (entry.key in values) continue
            usableOverride(entry)?.let { values[entry.key] = it }
        }
        return values
    }

    /** Every ancestor group's description and the group's own, missing ones skipped. */
    private fun pathDescription(path: List<String>): String = (1..maxOf(path.size, 1))
        .mapNotNull { depth -> schema.groups.firstOrNull { it.propertyPath == path.take(depth) }?.description }
        .joinToString(" › ")

    private suspend fun write(block: () -> Unit) {
        try {
            withContext(writeDispatcher) { block() }
        } finally {
            bump()
        }
    }

    private fun bump() {
        revisionState.update { it + 1 }
    }

    private companion object {

        /**
         * Refuses a repeated key at any depth. A nested list is read through the same rule as the
         * outer one, so a repeated key inside a record makes the whole flag unreadable.
         */
        fun checkKeys(records: List<Map<String, FlagValueBox>>, shape: List<FlagRecordField>) {
            FlagRecordList.duplicateKey(records, shape)?.let { throw refusal(shape, it) }
            for (field in shape) {
                val nested = field.fields ?: continue
                for (box in records.mapNotNull { it[field.name] }) {
                    // Asked for the duplicate rather than the rows: reading them applies this very
                    // rule and hands back nothing, so the check would look straight past it.
                    FlagRecordList.duplicateKey(box, nested)?.let { throw refusal(nested, it) }
                    FlagRecordList.decode(box, nested)?.let { checkKeys(it, nested) }
                }
            }
        }

        fun refusal(shape: List<FlagRecordField>, value: FlagValueBox): FlagRecordEditingException =
            FlagRecordEditingException(shape.firstOrNull { it.isKey }?.name ?: "key", value)
    }
}
```

`checkKeys` throws from two places, which is detekt's `ThrowsCount` limit; keep the shared `refusal(…)` builder rather than adding a third `throw`.

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times, `113 tests completed` for `featureflag-ui`: `FlagEditingStoreTest` 25, `FlagEditingStoreEdgeCaseTest` 23, `FlagArrayEditorTest` 9, `FlagRecordEditorTest` 12, `FlagRecordKeyEditorTest` 10, `FlagQrCodeTextTest` 4, and A2's 30, all `failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src
git commit -m "Add FlagEditingStore, the companion's model of one host over a schema and a source" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A4: `FlagCompanionSession` and `FlagCompanionViewModel`

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionSession.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModel.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FakeCompanionSource.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionSessionTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`

**Interfaces:**
- Consumes: A3 `FlagEditingStore(schema, source, writeDispatcher)`, `observeIn`, `revision`; A2 `CompanionSchemas`; M2 `FlagBridgeClient(context, hostPackage, timeout)`, `connect()`, `source(schema)`, `FlagBridgeSource` (`lastFailure`, `refresh()`, `close()`), `FlagBridgeConnection` (`Connected(schema)`, `message`), `FlagBridgeException`; lifecycle `AndroidViewModel`, `viewModelScope`, `viewModelFactory`/`initializer`, `ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY`.
- Produces (all `internal`):
```kotlin
// package com.andyyhope.featureflag.ui.companion
internal interface CompanionSource : MutableFlagValueSource, AutoCloseable {
    val lastFailure: StateFlow<FlagBridgeException?>
    fun refresh()
}
internal sealed interface FlagCompanionState {
    data object Connecting : FlagCompanionState
    data class Ready(val store: FlagEditingStore, val failure: FlagBridgeException?) : FlagCompanionState
    data class Unavailable(val message: String) : FlagCompanionState
}
internal class FlagCompanionSession(
    private val connect: suspend () -> FlagBridgeConnection,
    private val openSource: suspend (FlagSchema) -> CompanionSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val state: StateFlow<FlagCompanionState>
    fun start(); fun retry(); fun onForeground(); fun refresh(); fun close()
}
internal class FlagCompanionViewModel(application: Application, hostPackage: String, timeout: Duration) : AndroidViewModel(application) {
    val session: FlagCompanionSession                                   // started in init, closed in onCleared
    companion object { fun factory(hostPackage: String, timeout: Duration): ViewModelProvider.Factory }
}
internal fun FlagBridgeSource.asCompanionSource(): CompanionSource      // in FlagCompanionViewModel.kt
```
  A5 adds `suspend fun send(signal: FlagSignal, delay: Duration): Boolean` to the view model.
- Test helper `FakeCompanionSource(schema, values)`: a `CompanionSource` over a `SnapshotSource`, whose `refresh()` publishes and throws `refreshFailure` (or clears `lastFailure`), recording `refreshThreads`; `closed`; `hostWrites(key, box)`.

**Port targets:** `FlagSchemaReloadTests` (5) and the reload test of `FlagContainerDescriptionUITests`, recast for a session that reconnects:
- `testRefreshingPicksUpAFlagThatDidNotExistBefore` → the pinned `aChangedSchemaReplacesTheStore`;
- `testRefreshingTellsSwiftUISomethingChanged` → `aChangedSchemaIsAnnouncedOnce` (exactly one new `Ready`);
- `testRefreshingWhenNothingChangedDoesNotChurnTheView` → the pinned `anUnchangedSchemaKeepsTheStore` (no new state);
- `testAHostThatHasStoppedPublishingLeavesTheLastSchemaInPlace` → same name;
- `testAStoreBuiltWithoutAReloaderIsUnaffected` → `aSessionThatIsNeverForegroundedNeverReconnects`;
- `testARefreshPicksUpADescriptionAddedByTheHost` → same name.

Plus the pinned `aFailedRefreshIsShownAndKeepsTheValues`, and the connection states, retry, banner, close and threading (20 in `FlagCompanionSessionTest`), and three Robolectric tests of the view model.

Choices the contract leaves open (each under "Contract issues found" 11):
- Ready, and the reconnect is not `Connected`: keep the store and `refresh()`, so the banner shows why. Swift's "a host that stopped publishing leaves the last schema" behaves the same.
- A changed schema whose source cannot open: close the old source and show `Unavailable(message)`.
- One connection attempt at a time: `start`, `retry` and `onForeground` while one is running do nothing more.
- The store's write dispatcher is the session's `ioDispatcher`, so tests drive both with one test dispatcher.

- [ ] **Step 1: Write the failing tests**

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FakeCompanionSource.kt`
```kotlin
package com.andyyhope.featureflag.ui.companion

import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A bridge source in memory, for the session's tests. Its refresh does what a bridge source's
 * does: on failure it keeps its values, publishes the failure and throws it; on success it clears it.
 */
internal class FakeCompanionSource(
    val schema: FlagSchema,
    values: Map<FlagKey, FlagValueBox> = emptyMap(),
) : CompanionSource {

    private val snapshot = SnapshotSource("fake", values)
    private val failures = MutableStateFlow<FlagBridgeException?>(null)

    override val sourceName: String = "fake"
    override val changes: Flow<FlagChange> = snapshot.changes
    override val lastFailure: StateFlow<FlagBridgeException?> = failures

    /** What the next refreshes throw; null succeeds. */
    @Volatile
    var refreshFailure: FlagBridgeException? = null

    /** The name of the thread each refresh ran on. */
    val refreshThreads = CopyOnWriteArrayList<String>()

    @Volatile
    var closed = false
        private set

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = snapshot.box(key, type)

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        snapshot.setBox(key, box)
    }

    override fun refresh() {
        refreshThreads += Thread.currentThread().name
        val failure = refreshFailure
        failures.value = failure
        if (failure != null) throw failure
    }

    override fun close() {
        closed = true
    }

    /** A change the host makes, as the bridge's observer would deliver it. */
    fun hostWrites(key: String, box: FlagValueBox) {
        snapshot.setBox(FlagKey(key), box)
    }
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionSessionTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.companion

import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.editing.CompanionSchemas
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The session's states and its schema reload, which ports Swift's FlagSchemaReloadTests and the
// refresh half of FlagContainerDescriptionUITests: there a store re-read its schema; here the
// session reconnects and swaps the store when the host's schema changed.
// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class FlagCompanionSessionTest {

    private val schema = FlagSchema(generatedAt = CompanionSchemas.generatedAt, flags = listOf(flag("one")))
    private val grown = schema.copy(flags = listOf(flag("one"), flag("two")))

    // Connecting

    @Test
    fun startingConnectsAndBuildsAStore() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        assertEquals(FlagCompanionState.Connecting, session.state.value)

        session.start()
        runCurrent()

        val ready = assertIs<FlagCompanionState.Ready>(session.state.value)
        assertEquals(schema, ready.store.schema)
        assertNull(ready.failure)
        assertEquals(listOf(schema), host.opened.map { it.schema })
    }

    @Test
    fun startingTwiceConnectsOnce() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)

        session.start()
        session.start()
        runCurrent()

        assertEquals(1, host.connects)
    }

    @Test
    fun aHostThatIsNotConnectedIsUnavailableWithItsMessage() = runTest {
        val missing = FlagBridgeConnection.HostNotFound("com.example.host")
        val session = session(Host(missing))

        session.start()
        runCurrent()

        assertEquals(FlagCompanionState.Unavailable(missing.message), session.state.value)
    }

    @Test
    fun aSourceThatCannotOpenIsUnavailableWithItsMessage() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        host.openFailure = FlagBridgeException.Unavailable(SecurityException("Permission Denial"))
        val session = session(host)

        session.start()
        runCurrent()

        assertEquals(
            FlagCompanionState.Unavailable("The host could not be reached. Open the host app once, then try again."),
            session.state.value,
        )
    }

    @Test
    fun retryingFromUnavailableConnectsAgain() = runTest {
        val host = Host(FlagBridgeConnection.NotPublished, FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        assertIs<FlagCompanionState.Unavailable>(session.state.value)

        session.retry()
        assertEquals(FlagCompanionState.Connecting, session.state.value)
        runCurrent()

        assertIs<FlagCompanionState.Ready>(session.state.value)
        assertEquals(2, host.connects)
    }

    @Test
    fun retryingWhileReadyDoesNothing() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.retry()
        runCurrent()

        assertEquals(1, host.connects)
    }

    @Test
    fun comingBackWhileUnavailableRetries() = runTest {
        val host = Host(FlagBridgeConnection.NotPublished, FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.onForeground()
        runCurrent()

        assertIs<FlagCompanionState.Ready>(session.state.value)
    }

    // Coming back to the foreground

    @Test
    fun aChangedSchemaReplacesTheStore() = runTest {
        // The host was reinstalled with a new flag while the companion stayed open.
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.onForeground()
        runCurrent()

        val after = assertIs<FlagCompanionState.Ready>(session.state.value)
        assertNotSame(before.store, after.store)
        assertEquals(listOf("one", "two"), after.store.schema.flags.map { it.key.rawValue })
        assertEquals(listOf(schema, grown), host.opened.map { it.schema })
        assertTrue(host.opened[0].closed, "the old source outlived its store")
        assertFalse(host.opened[1].closed)
    }

    @Test
    fun aChangedSchemaIsAnnouncedOnce() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()

        session.state.test {
            val before = assertIs<FlagCompanionState.Ready>(awaitItem())
            session.onForeground()

            val after = assertIs<FlagCompanionState.Ready>(awaitItem())
            assertNotSame(before.store, after.store)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun anUnchangedSchemaKeepsTheStore() = runTest {
        // The screens keep their state: same store, overrides re-read instead.
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.state.test {
            awaitItem()
            session.onForeground()
            runCurrent()
            expectNoEvents()
        }

        assertSame(before, session.state.value)
        assertEquals(2, host.connects)
        assertEquals(1, host.opened.single().refreshThreads.size)
        assertFalse(host.opened.single().closed)
    }

    @Test
    fun aHostThatHasStoppedPublishingLeavesTheLastSchemaInPlace() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.NotPublished)
        val session = session(host)
        session.start()
        runCurrent()
        val before = assertIs<FlagCompanionState.Ready>(session.state.value)

        session.onForeground()
        runCurrent()

        assertSame(before.store, assertIs<FlagCompanionState.Ready>(session.state.value).store)
        assertEquals(1, host.opened.size)
    }

    @Test
    fun aSessionThatIsNeverForegroundedNeverReconnects() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()

        host.opened.single().hostWrites("one", FlagValueBox.Bool(true))
        runCurrent()

        assertEquals(1, host.connects)
        assertEquals(schema, assertIs<FlagCompanionState.Ready>(session.state.value).store.schema)
    }

    @Test
    fun aRefreshPicksUpADescriptionAddedByTheHost() = runTest {
        val host = Host(
            FlagBridgeConnection.Connected(CompanionSchemas.plain),
            FlagBridgeConnection.Connected(CompanionSchemas.described),
        )
        val session = session(host)
        session.start()
        runCurrent()
        assertNull(assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.description)

        session.onForeground()
        runCurrent()

        assertEquals(
            "What the checkout team can turn on",
            assertIs<FlagCompanionState.Ready>(session.state.value).store.schema.description,
        )
    }

    @Test
    fun aChangedSchemaWhoseSourceCannotOpenIsUnavailable() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema), FlagBridgeConnection.Connected(grown))
        val session = session(host)
        session.start()
        runCurrent()

        host.openFailure = FlagBridgeException.Unavailable(null)
        session.onForeground()
        runCurrent()

        assertIs<FlagCompanionState.Unavailable>(session.state.value)
        assertTrue(host.opened.single().closed)
    }

    // The banner

    @Test
    fun aFailedRefreshIsShownAndKeepsTheValues() = runTest {
        val overrides = mapOf(FlagKey("one") to FlagValueBox.Bool(true))
        val host = Host(FlagBridgeConnection.Connected(schema), values = overrides)
        val session = session(host)
        session.start()
        runCurrent()
        val store = assertIs<FlagCompanionState.Ready>(session.state.value).store
        val failure = FlagBridgeException.Unavailable(null)
        host.opened.single().refreshFailure = failure

        session.refresh()
        runCurrent()

        assertEquals(FlagCompanionState.Ready(store, failure), session.state.value)
        assertEquals(FlagValueBox.Bool(true), store.value(store.schema.flags.single()))
        assertTrue(store.isOverridden(store.schema.flags.single()))
    }

    @Test
    fun aRefreshThatSucceedsClearsTheBanner() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val source = host.opened.single()
        source.refreshFailure = FlagBridgeException.NotPublished()
        session.refresh()
        runCurrent()

        source.refreshFailure = null
        session.refresh()
        runCurrent()

        assertNull(assertIs<FlagCompanionState.Ready>(session.state.value).failure)
    }

    @Test
    fun comingBackToAHostThatCannotBeReadShowsTheBanner() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        host.opened.single().refreshFailure = FlagBridgeException.Unavailable(null)

        session.onForeground()
        runCurrent()

        assertIs<FlagBridgeException.Unavailable>(assertIs<FlagCompanionState.Ready>(session.state.value).failure)
    }

    // The store

    @Test
    fun theStoreFollowsItsSource() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()
        val store = assertIs<FlagCompanionState.Ready>(session.state.value).store

        host.opened.single().hostWrites("one", FlagValueBox.Bool(true))
        runCurrent()

        assertEquals(1L, store.revision.value)
    }

    @Test
    fun closingClosesTheSource() = runTest {
        val host = Host(FlagBridgeConnection.Connected(schema))
        val session = session(host)
        session.start()
        runCurrent()

        session.close()

        assertTrue(host.opened.single().closed)
    }

    @Test
    fun refreshesRunOnTheIoDispatcher() = runBlocking<Unit> {
        val io = Executors.newSingleThreadExecutor { Thread(it, "session-io") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = Host(FlagBridgeConnection.Connected(schema))
            val session = FlagCompanionSession(host::connect, host::open, scope, io.asCoroutineDispatcher())
            session.start()
            withTimeout(5.seconds) { session.state.first { it is FlagCompanionState.Ready } }

            session.refresh()
            val threads = host.opened.single().refreshThreads
            withTimeout(5.seconds) { while (threads.isEmpty()) delay(10) }

            // Coroutine debugging, on in tests, appends " @coroutine#n" to the name.
            assertTrue(threads.single().startsWith("session-io"), "$threads")
        } finally {
            scope.cancel()
            io.shutdown()
        }
    }

    // Helpers

    /** A host that answers each connect with the next of [connections], then keeps giving the last. */
    private class Host(vararg connections: FlagBridgeConnection, val values: Map<FlagKey, FlagValueBox> = emptyMap()) {
        private val answers = ArrayDeque(connections.toList())
        var connects = 0
        val opened = mutableListOf<FakeCompanionSource>()
        var openFailure: FlagBridgeException? = null

        suspend fun connect(): FlagBridgeConnection {
            connects++
            return if (answers.size > 1) answers.removeFirst() else answers.first()
        }

        suspend fun open(schema: FlagSchema): CompanionSource {
            openFailure?.let { throw it }
            return FakeCompanionSource(schema, values).also(opened::add)
        }
    }

    private fun TestScope.session(host: Host) =
        FlagCompanionSession(host::connect, host::open, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun flag(key: String) = CompanionSchemas.entry(key, listOf(key), key, FlagValueBox.Bool(false))
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.companion

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.ui.editing.CompanionSchemas
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagCompanionViewModelTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val host = "com.example.host"

    @Test
    fun theViewModelConnectsAtOnceAndSaysWhyItCannot() {
        // The unit-test app does not request the host's bridge permission, the first thing connect checks.
        val viewModel = FlagCompanionViewModel(context, host, 1.seconds)

        val state = awaitSettled(viewModel.session)

        assertEquals(
            FlagCompanionState.Unavailable(FlagBridgeConnection.UsesPermissionMissing(FlagBridgeContract.permission(host)).message),
            state,
        )
    }

    @Test
    fun theFactoryMakesOneViewModelPerHostPackage() {
        val store = ViewModelStore()
        val extras = MutableCreationExtras()
        extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = context
        val provider = ViewModelProvider.create(store, FlagCompanionViewModel.factory(host, 1.seconds), extras)

        val first = provider[host, FlagCompanionViewModel::class]

        assertSame(first, provider[host, FlagCompanionViewModel::class])
        assertNotSame(first, provider["com.example.other", FlagCompanionViewModel::class])
        store.clear()
    }

    @Test
    fun aBridgeSourceSeenAsACompanionSourceSharesItsFailureAndItsReads() = runBlocking<Unit> {
        // No provider answers: the source starts empty, with the failed first read as its failure.
        val bridge = FlagBridgeClient(context, host).source(CompanionSchemas.demoUi)
        val source = bridge.asCompanionSource()
        try {
            assertSame(bridge.lastFailure, source.lastFailure)
            assertIs<FlagBridgeException.Unavailable>(source.lastFailure.value)
            assertNull(source.box(FlagKey("max-items"), FlagValueType.Int))
            assertEquals(bridge.sourceName, source.sourceName)
            assertFailsWith<FlagBridgeException.Unavailable> { source.refresh() }
        } finally {
            source.close()
        }
    }

    /** Runs the main looper until the session has left Connecting; connect hops to IO and back. */
    private fun awaitSettled(session: FlagCompanionSession): FlagCompanionState {
        val deadline = System.currentTimeMillis() + 5_000
        while (session.state.value == FlagCompanionState.Connecting && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return session.state.value
    }
}
```

The view model test needs no host: the unit-test app does not request `com.example.host.permission.FEATURE_FLAG_BRIDGE`, which is the first thing `connect()` checks, so the session settles on `Unavailable` with `UsesPermissionMissing`'s message. `viewModelScope` runs on `Dispatchers.Main`, Robolectric's paused main looper, so `awaitSettled` idles it.

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
```

Expected: `BUILD FAILED`. `:featureflag-ui:compileDebugUnitTestKotlin` reports `Unresolved reference 'CompanionSource'`, `Unresolved reference 'FlagCompanionSession'`, `Unresolved reference 'FlagCompanionState'`, `Unresolved reference 'FlagCompanionViewModel'` and `Unresolved reference 'asCompanionSource'`.

- [ ] **Step 3: Write the session and the view model**

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionSession.kt`
```kotlin
package com.andyyhope.featureflag.ui.companion

import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the session needs from a bridge source: a writable source that can be re-read and closed. */
internal interface CompanionSource : MutableFlagValueSource, AutoCloseable {
    /** Why the latest re-read failed, or null when it succeeded. */
    val lastFailure: StateFlow<FlagBridgeException?>

    /** Re-reads the host's overrides. Blocking; throws [FlagBridgeException], which also lands in [lastFailure]. */
    fun refresh()
}

/** Where the companion is with its host. */
internal sealed interface FlagCompanionState {
    /** Connecting, or opening a source for the schema the host published. */
    data object Connecting : FlagCompanionState

    /** Connected: [store] edits the host; [failure] is the latest re-read's, shown as a banner over the last good values. */
    data class Ready(val store: FlagEditingStore, val failure: FlagBridgeException?) : FlagCompanionState

    /** Not connected, or connected but the source could not be opened; [message] says why. */
    data class Unavailable(val message: String) : FlagCompanionState
}

/**
 * One host, from first connection to close: connects, opens a source for the schema the host
 * published, builds a [FlagEditingStore] over it, and keeps it current.
 *
 * Coming back to the foreground reconnects. A host that now publishes a different schema gets a
 * new source and a new store (a source is bound to the schema it was opened with); the same
 * schema keeps the store, so the screens keep their state, and re-reads the overrides instead.
 *
 * Plain Kotlin. Its state changes on [scope]; blocking source calls run on [ioDispatcher], which
 * is also the store's write dispatcher. Call it from one thread, the main thread in the app.
 */
internal class FlagCompanionSession(
    private val connect: suspend () -> FlagBridgeConnection,
    private val openSource: suspend (FlagSchema) -> CompanionSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private class Open(val source: CompanionSource, val store: FlagEditingStore, val jobs: List<Job>)

    private val stateFlow = MutableStateFlow<FlagCompanionState>(FlagCompanionState.Connecting)
    val state: StateFlow<FlagCompanionState> = stateFlow.asStateFlow()

    private var open: Open? = null
    private var work: Job? = null
    private var started = false

    /** Connects. Only the first call does anything. */
    fun start() {
        if (started) return
        started = true
        attempt { connectAndOpen() }
    }

    /** From [FlagCompanionState.Unavailable]: shows Connecting and connects again. Otherwise does nothing. */
    fun retry() {
        if (state.value !is FlagCompanionState.Unavailable) return
        stateFlow.value = FlagCompanionState.Connecting
        attempt { connectAndOpen() }
    }

    /**
     * The app came back to the foreground, after the first time. Ready: reconnects, and replaces
     * the store when the schema differs, or re-reads the overrides when it does not (or when the
     * host cannot be reached, which then shows in the banner). Unavailable: retries.
     */
    fun onForeground() {
        when (state.value) {
            is FlagCompanionState.Ready -> attempt { reconnect() }
            is FlagCompanionState.Unavailable -> retry()
            FlagCompanionState.Connecting -> Unit
        }
    }

    /** The banner's "Try again": re-reads the overrides; a failure stays in the banner. */
    fun refresh() {
        val source = open?.source ?: return
        scope.launch { refresh(source) }
    }

    /** Stops connecting and closes the source. */
    fun close() {
        work?.cancel()
        work = null
        dispose(open)
        open = null
    }

    /** One connection attempt at a time: a second asked for while one runs is the same attempt. */
    private fun attempt(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = scope.launch { block() }
    }

    private suspend fun connectAndOpen() {
        when (val connection = connect()) {
            is FlagBridgeConnection.Connected -> openStore(connection.schema)
            else -> stateFlow.value = FlagCompanionState.Unavailable(connection.message.orEmpty())
        }
    }

    private suspend fun reconnect() {
        val current = open ?: return
        val connection = connect()
        if (connection is FlagBridgeConnection.Connected && connection.schema != current.store.schema) {
            openStore(connection.schema)
        } else {
            refresh(current.source)
        }
    }

    private suspend fun openStore(schema: FlagSchema) {
        val source = try {
            openSource(schema)
        } catch (error: FlagBridgeException) {
            dispose(open)
            open = null
            stateFlow.value = FlagCompanionState.Unavailable(error.message.orEmpty())
            return
        }
        val store = FlagEditingStore(schema, source, ioDispatcher)
        val failures = scope.launch {
            source.lastFailure.collect { failure ->
                if (open?.store === store) stateFlow.value = FlagCompanionState.Ready(store, failure)
            }
        }
        val previous = open
        open = Open(source, store, listOf(store.observeIn(scope), failures))
        dispose(previous)
        stateFlow.value = FlagCompanionState.Ready(store, source.lastFailure.value)
    }

    private suspend fun refresh(source: CompanionSource) {
        withContext(ioDispatcher) {
            try {
                source.refresh()
            } catch (ignored: FlagBridgeException) {
                // Published through lastFailure, which the banner shows.
            }
        }
    }

    private fun dispose(open: Open?) {
        open ?: return
        open.jobs.forEach { it.cancel() }
        open.source.close()
    }
}
```

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModel.kt`
```kotlin
package com.andyyhope.featureflag.ui.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import kotlin.time.Duration

/**
 * The companion's connection to one host, kept across configuration changes: a
 * [FlagBridgeClient] and a [FlagCompanionSession] running in [viewModelScope], started at once
 * and closed with the view model. `FlagCompanion` keys it by host package.
 */
internal class FlagCompanionViewModel(
    application: Application,
    hostPackage: String,
    timeout: Duration,
) : AndroidViewModel(application) {

    private val client = FlagBridgeClient(application, hostPackage, timeout)

    val session: FlagCompanionSession = FlagCompanionSession(
        connect = client::connect,
        openSource = { schema -> client.source(schema).asCompanionSource() },
        scope = viewModelScope,
    )

    init {
        session.start()
    }

    override fun onCleared() {
        session.close()
    }

    companion object {
        /** Builds the view model for [hostPackage] from the application in the creation extras. */
        fun factory(hostPackage: String, timeout: Duration): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FlagCompanionViewModel(
                    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    hostPackage,
                    timeout,
                )
            }
        }
    }
}

/** This bridge source as the session sees it. Lives here, not beside the session, which stays free of Android types. */
internal fun FlagBridgeSource.asCompanionSource(): CompanionSource {
    val bridge = this
    return object : CompanionSource, MutableFlagValueSource by bridge {
        override val lastFailure = bridge.lastFailure

        override fun refresh() {
            bridge.refresh()
        }

        override fun close() {
            bridge.close()
        }
    }
}
```

`asCompanionSource` delegates `MutableFlagValueSource` to the bridge source, so `setBoxes` stays the bridge's single `METHOD_APPLY` rather than the interface's default loop.

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `FlagCompanionSessionTest` `tests="20"` and `FlagCompanionViewModelTest` `tests="3"`, both `failures="0" errors="0"`; 136 `featureflag-ui` tests in all.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src
git commit -m "Add the companion session, which reconnects on return and replaces the store when the schema changed" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A5: Delayed and cancellable signals on the bridge

**Files:**
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt`
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClient.kt`
- Modify: `featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt`
- Modify: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModel.kt`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FakeBridgeProvider.kt`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt`
- Test: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClientTest.kt`
- Test: `featureflag-bridge/src/test/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProviderTest.kt`
- Test: `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/HostTestApplication.kt`
- Test: `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/CrossProcessBridgeTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`

**Interfaces:**
- Consumes: M2 `FlagBridgeClient.within` / `perform`, `FlagBridgeProvider.deliveryTimeout` / `elapsedRealtime` / `awaitStartup`, `FlagSignalChannel.host.deliver`, `FakeBridgeProvider`; A4 `FlagCompanionViewModel`.
- Produces:
```kotlin
// FlagBridgeContract
public const val METHOD_CANCEL: String = "cancel"   // arg = ticket; STATUS_OK whether or not it was waiting
public const val KEY_DELAY: String = "delay"        // Long ms; absent or ≤ 0 = at once; the host clamps to MAX_DELAY
public const val KEY_TICKET: String = "ticket"      // String, unique per delayed send
public val MAX_DELAY: Duration = 60.seconds
// FlagBridgeClient
public suspend fun send(signal: FlagSignal, timeout: Duration = this.timeout, delay: Duration = Duration.ZERO): Boolean
// FlagBridgeProvider (featureflag-bridge)
internal fun signalDelay(extras: Bundle?): Duration  // KEY_DELAY clamped to 0..MAX_DELAY; the test seam for the clamp
// FlagCompanionViewModel (featureflag-ui)
suspend fun send(signal: FlagSignal, delay: Duration): Boolean           // client.send(signal, delay = delay)
// FakeBridgeProvider (test): var delayReached: CountDownLatch?; waits out KEY_DELAY in real time; METHOD_CANCEL opens the ticket's wait
// HostTestApplication (androidTest): BridgeTestSignal.Record, observed; its handler appends "Record\n" to filesDir/DELIVERIES_LOG ("deliveries")
```

How the pieces fit:
- **Client.** `deadline = elapsedRealtime + delay + timeout` (saturated), and the call waits `delay + timeout`. A delay of at least 1 ms adds `KEY_DELAY` (whole milliseconds) and a fresh UUID `KEY_TICKET`; a zero or negative delay sends only `KEY_DEADLINE`, exactly as M2 did. A `CancellationException` out of the wait runs `withdraw(ticket)` under `NonCancellable`: one `METHOD_CANCEL` call through `perform`, inside `within(2.seconds)`, every failure swallowed. Then it rethrows.
- **Provider.** `METHOD_CANCEL` is answered before the startup barrier, as an expired signal is: it needs nothing the app sets up. After the barrier, a signal with a positive `signalDelay` waits on its ticket's latch until `enteredAt + delay`, so a cold host's startup counts toward the delay. A cancel opens the latch: the signal answers `handled = false` and is never delivered. Otherwise it delivers with `deliveryTimeout(extras, enteredAt)`, read after the wait, so the remaining budget is `deadline − now − margin` and the delay is not spent twice. The ticket leaves the map in `finally`.
- **A cancel can overtake its signal.** The two calls run on different binder threads, and the signal may be held at the startup barrier. A cancel for a ticket that is not waiting therefore leaves its opened latch in the map, and the signal finds it on arrival. The map keeps at most 64, dropping the oldest. This is the one behaviour beyond the contract; see "Contract issues found" 8.

**Tests.** 16 unit tests and 2 device tests:
- `FlagBridgeContractTest`: `aHostWaitsAtMostAMinuteBeforeDeliveringASignal`, and `theWireNamesAreStable` extended with `cancel`, `delay` and `ticket`.
- `FlagBridgeClientTest` (7): `aSignalWithoutADelaySendsOnlyItsDeadline`, `aDelayedSignalCarriesItsDelayATicketAndALaterDeadline`, `eachDelayedSendHasItsOwnTicket`, `aDelayedSignalIsGivenItsDelayOnTopOfItsTimeout`, `cancellingADelayedSendWithdrawsItFromTheHost`, `cancellingAnUndelayedSendWithdrawsNothing`, `aWithdrawalTheHostCannotTakeIsStillACancellation`.
- `FlagBridgeProviderTest` (7): `aDelayedSignalIsDeliveredOnceItsDelayHasPassed`, `aCancelDuringTheDelayWithholdsTheSignal`, `aCancelThatOvertakesItsSignalStillWithholdsIt`, `aCancelForATicketNobodyHoldsIsStillOk`, `aCancelDoesNotWaitForStartup`, `aDelayIsClampedToAMinute`, `theDelayCountsFromWhenTheSignalArrived`.
- `FlagCompanionViewModelTest`: `aSignalToAHostThatCannotBeReachedIsNotHandled`.
- `CrossProcessBridgeTest` (device): `aDelayedSignalIsHandledOnceItsDelayHasPassed` and the pinned `aCancelledDelayedSignalIsNotDelivered`.

- [ ] **Step 1: Teach the fake host delays and cancels, and write the failing unit tests**

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FakeBridgeProvider.kt`. Replace

```kotlin
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
```

with

```kotlin
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FakeBridgeProvider.kt`, second edit. Replace

```kotlin
    private var heldReplyGate: CountDownLatch? = null
```

with

```kotlin
    /** Opened when a delayed signal starts its wait. */
    @Volatile
    var delayReached: CountDownLatch? = null

    /** Each delayed signal's cancellation, by ticket, opened by a cancel call. */
    private val cancellations = ConcurrentHashMap<String, CountDownLatch>()

    private var heldReplyGate: CountDownLatch? = null
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FakeBridgeProvider.kt`, third edit. Replace

```kotlin
            FlagBridgeContract.METHOD_SIGNAL -> Bundle().apply {
                putBoolean(FlagBridgeContract.KEY_HANDLED, arg in handledSignals)
            }
            else -> throw IllegalArgumentException("Unknown bridge method '$method'.")
```

with

```kotlin
            FlagBridgeContract.METHOD_SIGNAL -> Bundle().apply {
                putBoolean(FlagBridgeContract.KEY_HANDLED, waitOutDelay(extras) && arg in handledSignals)
            }
            FlagBridgeContract.METHOD_CANCEL -> {
                cancellation(arg.orEmpty()).countDown()
                status(FlagBridgeContract.STATUS_OK)
            }
            else -> throw IllegalArgumentException("Unknown bridge method '$method'.")
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FakeBridgeProvider.kt`, fourth edit. Replace

```kotlin
    /** Changes the host's overrides as the host's own code would, and tells observers. */
```

with

```kotlin
    /**
     * Waits out a delayed signal's [FlagBridgeContract.KEY_DELAY] in real time, as the host does;
     * false when a cancel for its ticket ends the wait first.
     */
    private fun waitOutDelay(extras: Bundle?): Boolean {
        val delay = extras?.getLong(FlagBridgeContract.KEY_DELAY) ?: 0L
        if (delay <= 0) return true
        val cancelled = cancellation(extras?.getString(FlagBridgeContract.KEY_TICKET).orEmpty())
        delayReached?.countDown()
        return !cancelled.await(delay, TimeUnit.MILLISECONDS)
    }

    private fun cancellation(ticket: String): CountDownLatch = cancellations.computeIfAbsent(ticket) { CountDownLatch(1) }

    /** Changes the host's overrides as the host's own code would, and tells observers. */
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt`. Replace

```kotlin
import kotlin.test.assertTrue
```

with

```kotlin
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt`, second edit. Replace

```kotlin
    @Test
    fun theWireNamesAreStable() {
```

with

```kotlin
    @Test
    fun aHostWaitsAtMostAMinuteBeforeDeliveringASignal() {
        assertEquals(60.seconds, FlagBridgeContract.MAX_DELAY)
    }

    @Test
    fun theWireNamesAreStable() {
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContractTest.kt`, third edit. Replace

```kotlin
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
            listOf("protocol", "status", "message", "payload", "remove", "handled", "size", "deadline"),
            listOf(
                FlagBridgeContract.KEY_PROTOCOL,
                FlagBridgeContract.KEY_STATUS,
                FlagBridgeContract.KEY_MESSAGE,
                FlagBridgeContract.KEY_PAYLOAD,
                FlagBridgeContract.KEY_REMOVE,
                FlagBridgeContract.KEY_HANDLED,
                FlagBridgeContract.KEY_SIZE,
                FlagBridgeContract.KEY_DEADLINE,
            ),
        )
```

with

```kotlin
        assertEquals(
            listOf("hello", "overrides", "apply", "signal", "cancel"),
            listOf(
                FlagBridgeContract.METHOD_HELLO,
                FlagBridgeContract.METHOD_OVERRIDES,
                FlagBridgeContract.METHOD_APPLY,
                FlagBridgeContract.METHOD_SIGNAL,
                FlagBridgeContract.METHOD_CANCEL,
            ),
        )
        assertEquals(
            listOf("protocol", "status", "message", "payload", "remove", "handled", "size", "deadline", "delay", "ticket"),
            listOf(
                FlagBridgeContract.KEY_PROTOCOL,
                FlagBridgeContract.KEY_STATUS,
                FlagBridgeContract.KEY_MESSAGE,
                FlagBridgeContract.KEY_PAYLOAD,
                FlagBridgeContract.KEY_REMOVE,
                FlagBridgeContract.KEY_HANDLED,
                FlagBridgeContract.KEY_SIZE,
                FlagBridgeContract.KEY_DEADLINE,
                FlagBridgeContract.KEY_DELAY,
                FlagBridgeContract.KEY_TICKET,
            ),
        )
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClientTest.kt`. Replace

```kotlin
import kotlinx.coroutines.runBlocking
import org.junit.After
```

with

```kotlin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClientTest.kt`, second edit. Replace

```kotlin
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
```

with

```kotlin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
```

**Modify** `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClientTest.kt`, third edit. Replace

```kotlin
        val deadlines = bridge.extrasSeen.map { it!!.getLong(FlagBridgeContract.KEY_DEADLINE) }
        assertEquals(2, deadlines.size)
        assertEquals(deadlines[0], deadlines[1])
    }

    // Helpers
```

with

```kotlin
        val deadlines = bridge.extrasSeen.map { it!!.getLong(FlagBridgeContract.KEY_DEADLINE) }
        assertEquals(2, deadlines.size)
        assertEquals(deadlines[0], deadlines[1])
    }

    // send, delayed

    @Test
    fun aSignalWithoutADelaySendsOnlyItsDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")

        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge, delay = Duration.ZERO))

        assertEquals(setOf(FlagBridgeContract.KEY_DEADLINE), bridge.extrasSeen.single()!!.keySet())
    }

    @Test
    fun aDelayedSignalCarriesItsDelayATicketAndALaterDeadline() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        val timeout = 7.seconds
        val delay = 300.milliseconds

        val before = SystemClock.elapsedRealtime()
        assertTrue(FlagBridgeClient(context, host).send(TestSignal.Purge, timeout, delay))
        val after = SystemClock.elapsedRealtime()

        val extras = bridge.extrasSeen.single()!!
        assertEquals(300L, extras.getLong(FlagBridgeContract.KEY_DELAY))
        assertTrue(extras.getString(FlagBridgeContract.KEY_TICKET).orEmpty().isNotEmpty())
        val budget = (timeout + delay).inWholeMilliseconds
        val deadline = extras.getLong(FlagBridgeContract.KEY_DEADLINE)
        assertTrue(deadline in (before + budget)..(after + budget), "deadline $deadline")
    }

    @Test
    fun eachDelayedSendHasItsOwnTicket() = runBlocking<Unit> {
        val bridge = installBridge()
        val client = FlagBridgeClient(context, host)

        client.send(TestSignal.Purge, delay = 10.milliseconds)
        client.send(TestSignal.Purge, delay = 10.milliseconds)

        val tickets = bridge.extrasSeen.map { it!!.getString(FlagBridgeContract.KEY_TICKET) }
        assertEquals(2, tickets.toSet().size, "$tickets")
    }

    @Test
    fun aDelayedSignalIsGivenItsDelayOnTopOfItsTimeout() = runBlocking<Unit> {
        // The host waits 500 ms before it answers; a 200 ms timeout alone would have given up.
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")

        val (handled, waited) = measureTimedValue {
            FlagBridgeClient(context, host).send(TestSignal.Purge, timeout = 200.milliseconds, delay = 500.milliseconds)
        }

        assertTrue(handled)
        assertTrue(waited >= 500.milliseconds, "waited $waited")
    }

    @Test
    fun cancellingADelayedSendWithdrawsItFromTheHost() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        val waiting = CountDownLatch(1)
        bridge.delayReached = waiting

        // Undispatched, so the call is on its way before this thread blocks on the latch.
        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            FlagBridgeClient(context, host).send(TestSignal.Purge, delay = 10.seconds)
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        val (_, took) = measureTimedValue { send.cancelAndJoin() }

        val ticket = bridge.extrasSeen.first()!!.getString(FlagBridgeContract.KEY_TICKET)
        assertTrue(send.isCancelled)
        assertEquals(listOf("signal Purge", "cancel $ticket"), bridge.calls)
        assertTrue(took < 5.seconds, "took $took")
    }

    @Test
    fun cancellingAnUndelayedSendWithdrawsNothing() = runBlocking<Unit> {
        val bridge = installBridge()
        bridge.handledSignals = setOf("Purge")
        bridge.stall = stall

        val send = launch { FlagBridgeClient(context, host).send(TestSignal.Purge, timeout = 10.seconds) }
        while (bridge.calls.isEmpty()) delay(10)
        send.cancelAndJoin()
        stall.countDown()

        assertEquals(listOf("signal Purge"), bridge.calls)
    }

    @Test
    fun aWithdrawalTheHostCannotTakeIsStillACancellation() = runBlocking<Unit> {
        val bridge = installBridge()
        val waiting = CountDownLatch(1)
        bridge.delayReached = waiting

        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            FlagBridgeClient(context, host).send(TestSignal.Purge, delay = 1.seconds)
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        bridge.failure = IllegalStateException("The host is going away.")
        send.cancelAndJoin()

        assertTrue(send.isCancelled)
    }

    // Helpers
```

The cancelling tests launch the send `CoroutineStart.UNDISPATCHED`: `runBlocking`'s event loop runs on the test thread, so a plain `launch` would not start until the test thread stopped blocking on the latch.

**Modify** `featureflag-bridge/src/test/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProviderTest.kt`. Replace

```kotlin
        assertEquals(30.seconds, provider.deliveryTimeout(deadlineExtras(Long.MAX_VALUE), enteredAt = 10_000L))
        assertEquals(Duration.ZERO, provider.deliveryTimeout(deadlineExtras(Long.MIN_VALUE), enteredAt = 10_000L))
    }
```

with

```kotlin
        assertEquals(30.seconds, provider.deliveryTimeout(deadlineExtras(Long.MAX_VALUE), enteredAt = 10_000L))
        assertEquals(Duration.ZERO, provider.deliveryTimeout(deadlineExtras(Long.MIN_VALUE), enteredAt = 10_000L))
    }

    // Delayed signals

    @Test
    fun aDelayedSignalIsDeliveredOnceItsDelayHasPassed() {
        val purges = AtomicInteger()
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { purges.incrementAndGet() }
        try {
            provider.elapsedRealtime = ::realClock
            val started = realClock()

            // On the main thread, so delivery runs inline once the wait is over.
            val reply = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", delayedExtras(300, "t-1", realClock() + 5_300))

            assertTrue(reply.getBoolean(FlagBridgeContract.KEY_HANDLED))
            assertTrue(realClock() - started >= 300, "delivered after ${realClock() - started} ms")
            assertEquals(1, purges.get())
        } finally {
            observation.close()
        }
    }

    @Test
    fun aCancelDuringTheDelayWithholdsTheSignal() {
        val purges = AtomicInteger()
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { purges.incrementAndGet() }
        try {
            startUp()
            val answer = AtomicReference<Pair<Boolean, Long>>()
            val done = CountDownLatch(1)
            thread {
                val started = realClock()
                val reply = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", delayedExtras(5_000, "t-2", realClock() + 7_000))
                answer.set(reply.getBoolean(FlagBridgeContract.KEY_HANDLED) to (realClock() - started))
                done.countDown()
            }
            Thread.sleep(200)

            val cancelled = provider.call(FlagBridgeContract.METHOD_CANCEL, "t-2", null)

            assertEquals(FlagBridgeContract.STATUS_OK, cancelled.getString(FlagBridgeContract.KEY_STATUS))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(answer.get().first)
            assertTrue(answer.get().second < 2_000, "waited ${answer.get().second} ms")
            assertEquals(0, purges.get())
        } finally {
            observation.close()
        }
    }

    @Test
    fun aCancelThatOvertakesItsSignalStillWithholdsIt() {
        // The two calls cross on different binder threads, so the cancel can arrive first.
        val purges = AtomicInteger()
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { purges.incrementAndGet() }
        try {
            provider.elapsedRealtime = ::realClock
            provider.call(FlagBridgeContract.METHOD_CANCEL, "t-3", null)
            val started = realClock()

            val reply = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", delayedExtras(5_000, "t-3", realClock() + 7_000))

            assertFalse(reply.getBoolean(FlagBridgeContract.KEY_HANDLED))
            assertTrue(realClock() - started < 1_000, "waited ${realClock() - started} ms")
            assertEquals(0, purges.get())
        } finally {
            observation.close()
        }
    }

    @Test
    fun aCancelForATicketNobodyHoldsIsStillOk() {
        val unknown = provider.call(FlagBridgeContract.METHOD_CANCEL, "nobody", null)
        val nameless = provider.call(FlagBridgeContract.METHOD_CANCEL, null, null)

        assertEquals(FlagBridgeContract.STATUS_OK, unknown.getString(FlagBridgeContract.KEY_STATUS))
        assertEquals(FlagBridgeContract.STATUS_OK, nameless.getString(FlagBridgeContract.KEY_STATUS))
    }

    @Test
    fun aCancelDoesNotWaitForStartup() {
        // The main looper never runs, as on a cold host: a cancel needs nothing onCreate sets up.
        val answered = CountDownLatch(1)
        thread { provider.call(FlagBridgeContract.METHOD_CANCEL, "t-4", null).also { answered.countDown() } }

        assertTrue(answered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun aDelayIsClampedToAMinute() {
        assertEquals(60.seconds, provider.signalDelay(Bundle().apply { putLong(FlagBridgeContract.KEY_DELAY, Long.MAX_VALUE) }))
        assertEquals(Duration.ZERO, provider.signalDelay(Bundle().apply { putLong(FlagBridgeContract.KEY_DELAY, -5L) }))
        assertEquals(Duration.ZERO, provider.signalDelay(deadlineExtras(5_000)))
        assertEquals(Duration.ZERO, provider.signalDelay(null))
        assertEquals(3.seconds, provider.signalDelay(Bundle().apply { putLong(FlagBridgeContract.KEY_DELAY, 3_000L) }))
    }

    @Test
    fun theDelayCountsFromWhenTheSignalArrived() {
        // A cold host's startup wait is part of the delay, not added to it: the signal arrives at
        // 10 000 and the wait begins at 10 250, so 50 ms of its 300 ms delay are left.
        val observation = FlagSignalChannel.host.observe(listOf(TestSignal.Purge)) { }
        try {
            val arrival = ArrayDeque(listOf(10_000L))
            provider.elapsedRealtime = { arrival.removeFirstOrNull() ?: 10_250L }
            val started = realClock()

            val reply = provider.call(FlagBridgeContract.METHOD_SIGNAL, "Purge", delayedExtras(300, "t-5", deadline = 60_000L))

            assertTrue(reply.getBoolean(FlagBridgeContract.KEY_HANDLED))
            assertTrue(realClock() - started < 250, "waited ${realClock() - started} ms")
        } finally {
            observation.close()
        }
    }
```

**Modify** `featureflag-bridge/src/test/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProviderTest.kt`, second edit. Replace

```kotlin
    private fun deadlineExtras(deadline: Long) = Bundle().apply { putLong(FlagBridgeContract.KEY_DEADLINE, deadline) }
```

with

```kotlin
    private fun deadlineExtras(deadline: Long) = Bundle().apply { putLong(FlagBridgeContract.KEY_DEADLINE, deadline) }

    private fun delayedExtras(delay: Long, ticket: String, deadline: Long) = deadlineExtras(deadline).apply {
        putLong(FlagBridgeContract.KEY_DELAY, delay)
        putString(FlagBridgeContract.KEY_TICKET, ticket)
    }
```

The provider tests call from the Robolectric main thread where they want delivery inline, and from a `thread { }` where they want the wait to block off the main thread, as in M2's signal tests. Each uses `realClock` for `elapsedRealtime`, because the wait is real time.

**Modify** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`. Replace

```kotlin
    /** Runs the main looper until the session has left Connecting; connect hops to IO and back. */
```

with

```kotlin
    @Test
    fun aSignalToAHostThatCannotBeReachedIsNotHandled() = runBlocking<Unit> {
        val viewModel = FlagCompanionViewModel(context, host, 1.seconds)

        assertFalse(viewModel.send(Ping.Ping, 1.seconds))
        assertFalse(viewModel.send(Ping.Ping, Duration.ZERO))
    }

    private enum class Ping : FlagSignal { Ping }

    /** Runs the main looper until the session has left Connecting; connect hops to IO and back. */
```

**Modify** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`, second edit. Replace

```kotlin
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.ui.editing.CompanionSchemas
```

with

```kotlin
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.editing.CompanionSchemas
```

**Modify** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`, third edit. Replace

```kotlin
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
```

with

```kotlin
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
```

**Modify** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModelTest.kt`, fourth edit. Replace

```kotlin
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
```

with

```kotlin
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
```

- [ ] **Step 2: Run the unit tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest :featureflag-bridge:testDebugUnitTest :featureflag-ui:testDebugUnitTest --continue
```

Expected: `BUILD FAILED` with three compile failures:
- `:featureflag-android:compileDebugUnitTestKotlin`: `Unresolved reference 'KEY_DELAY'`, `'KEY_TICKET'`, `'METHOD_CANCEL'` and `'MAX_DELAY'`, `No parameter with name 'delay' found.`, and `Too many arguments for 'suspend fun send(signal: FlagSignal, timeout: Duration = ...): Boolean'.`
- `:featureflag-bridge:compileDebugUnitTestKotlin`: `Unresolved reference 'METHOD_CANCEL'`, `'KEY_DELAY'`, `'KEY_TICKET'` and `'signalDelay'`.
- `:featureflag-ui:compileDebugUnitTestKotlin`: `Unresolved reference 'send'`.

- [ ] **Step 3: Add the wire names, the client's delay and withdrawal, and the host's wait**

**Modify** `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt` (full replacement):
```kotlin
package com.andyyhope.featureflag.android.bridge

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Ends a delayed [METHOD_SIGNAL]'s wait without delivering it; its argument is the signal's
     * [KEY_TICKET]. Answers [STATUS_OK] whether or not that ticket was waiting.
     */
    public const val METHOD_CANCEL: String = "cancel"

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

    /**
     * `Long`: sent with [METHOD_SIGNAL], the moment the client stops waiting, in
     * `SystemClock.elapsedRealtime()` milliseconds. Both processes read the same clock. A host
     * that finds no deadline treats the client as an older one and applies its default wait.
     */
    public const val KEY_DEADLINE: String = "deadline"

    /**
     * `Long`: sent with [METHOD_SIGNAL], how many milliseconds the host waits before it delivers.
     * Absent or not positive delivers at once; the host clamps it to [MAX_DELAY]. The host holds
     * the delay, so a companion frozen in the background cannot stall it.
     */
    public const val KEY_DELAY: String = "delay"

    /** `String`: sent with a delayed [METHOD_SIGNAL], unique per send; [METHOD_CANCEL] names it. */
    public const val KEY_TICKET: String = "ticket"

    /** The longest [KEY_DELAY] a host waits. */
    public val MAX_DELAY: Duration = 60.seconds

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

`PROTOCOL` stays 1: no host has shipped (overview, deviation 2).

**Modify** `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClient.kt` (full replacement):
```kotlin
package com.andyyhope.featureflag.android.bridge

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A companion's connection to one host app's FeatureFlag bridge.
 *
 * The companion needs `<uses-permission android:name="<hostPackage>.permission.FEATURE_FLAG_BRIDGE" />`
 * in its manifest, and must be signed with the host's key (or listed in its knownSigner permission).
 * Every operation acquires its own unstable provider client and closes it afterwards, so the
 * companion survives the host dying; a call that finds the host dead is retried once.
 */
public class FlagBridgeClient(
    context: Context,
    public val hostPackage: String,
    public val timeout: Duration = 5.seconds,
) {

    private val context: Context = context.applicationContext ?: context
    private val authority = FlagBridgeContract.authority(hostPackage)
    private val permission = FlagBridgeContract.permission(hostPackage)

    // Blocking binder calls run here, detached from the caller, so a timeout returns on time
    // even while the call itself is still waiting for the host.
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val resolver: ContentResolver get() = context.contentResolver

    /** Checks everything a companion needs, in the order it is worth fixing, and loads the host's schema. Never throws. */
    public suspend fun connect(): FlagBridgeConnection {
        val packageManager = context.packageManager
        val requested = packageInfo(packageManager, context.packageName, PackageManager.GET_PERMISSIONS)?.requestedPermissions
        if (requested == null || permission !in requested) return FlagBridgeConnection.UsesPermissionMissing(permission)
        packageInfo(packageManager, hostPackage, 0) ?: return FlagBridgeConnection.HostNotFound(hostPackage)
        // Only a host that includes featureflag-bridge defines the permission, so without it the bridge is missing.
        if (!definesPermission(packageManager)) return FlagBridgeConnection.BridgeNotInstalled(hostPackage)
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return permissionDenied()
        return within(timeout) { handshake() } ?: FlagBridgeConnection.Unresponsive(hostPackage, timeout)
    }

    /**
     * Asks the host to deliver [signal] after [delay], and waits up to [delay] plus [timeout] for
     * its answer: true when the host ran a handler; false on no observer, timeout, or an
     * unreachable host. Never throws except [CancellationException].
     *
     * The host holds the delay, so a companion frozen in the background cannot stall it. A delayed
     * send carries a fresh ticket; cancelling the calling coroutine while it waits asks the host,
     * best effort, to withdraw that ticket, and then rethrows. A delay of zero sends exactly what an
     * undelayed send always has.
     *
     * A send that timed out, or an undelayed one that was cancelled, may still be delivered, and a
     * host that dies after running the handler may run it again on the retry. The host is given the
     * caller's remaining budget, so a cold host's startup wait does not push a slow handler past it.
     */
    public suspend fun send(
        signal: FlagSignal,
        timeout: Duration = this.timeout,
        delay: Duration = Duration.ZERO,
    ): Boolean {
        // Fixed before the first attempt: a retry after the host died has only what is left of the same budget.
        val now = SystemClock.elapsedRealtime()
        val wait = delay.coerceAtLeast(Duration.ZERO).inWholeMilliseconds
        // Duration arithmetic saturates, so an infinite timeout stays infinite.
        val budget = timeout + wait.milliseconds
        val budgetMillis = budget.inWholeMilliseconds
        // Saturated: Duration.INFINITE is Long.MAX_VALUE milliseconds, which would wrap into the past.
        val deadline = if (budgetMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + budgetMillis
        val ticket = if (wait > 0) UUID.randomUUID().toString() else null
        val extras = Bundle().apply {
            putLong(FlagBridgeContract.KEY_DEADLINE, deadline)
            if (ticket != null) {
                putLong(FlagBridgeContract.KEY_DELAY, wait)
                putString(FlagBridgeContract.KEY_TICKET, ticket)
            }
        }
        try {
            return within(budget) {
                try {
                    perform { client -> client.call(FlagBridgeContract.METHOD_SIGNAL, signal.name, extras) }
                        ?.getBoolean(FlagBridgeContract.KEY_HANDLED) ?: false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    false
                }
            } ?: false
        } catch (cancelled: CancellationException) {
            if (ticket != null) withdraw(ticket)
            throw cancelled
        }
    }

    /** Asks the host to drop the delayed signal [ticket] names. Best effort: it cannot be cancelled, and never throws. */
    private suspend fun withdraw(ticket: String) {
        withContext(NonCancellable) {
            within(CANCEL_TIMEOUT) {
                try {
                    perform { client -> client.call(FlagBridgeContract.METHOD_CANCEL, ticket, null) }
                } catch (ignored: Exception) {
                    // The host is gone or refused: either way, nothing more can be done for this send.
                }
                Unit
            }
        }
    }

    /**
     * A live view of the host's companion overrides, validated against [schema]. Close it when done.
     * Suspends while it registers an observer and reads the overrides once, on a background thread,
     * so it is safe to call from the main thread. The source's writes and [FlagBridgeSource.refresh]
     * still block: make those off the main thread. Construction is not interrupted, so a call cancelled
     * meanwhile stays suspended until it finishes (up to the host's startup barrier), then closes the
     * new source and throws [CancellationException]. Throws [FlagBridgeException.Unavailable] when the host's provider cannot be found or the
     * companion may not observe it. A first read that fails leaves the source empty instead.
     */
    public suspend fun source(schema: FlagSchema, name: String = "Companion"): FlagBridgeSource {
        // Not interrupted mid-way: a half-built source would leave its thread and observer behind.
        // withContext discards its result when the caller was cancelled meanwhile, so the source is
        // handed out through a variable to close it in that case too. The failure travels as a value
        // so the coroutines machinery does not wrap it on the way out.
        var built: Result<FlagBridgeSource>? = null
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                built = runCatching { FlagBridgeSource(this@FlagBridgeClient, schema, name) }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            built?.getOrNull()?.close()
            throw cancelled
        }
        return checkNotNull(built).getOrThrow()
    }

    /**
     * Runs [operation] on a fresh unstable client for the host's provider and closes it. A
     * [DeadObjectException] means the host died mid-call; it is retried once with a new client,
     * which starts the host again. Returns null when the host has no provider. Blocking.
     */
    internal fun <T> perform(operation: (ContentProviderClient) -> T): T? {
        var attempt = 0
        while (true) {
            val client = resolver.acquireUnstableContentProviderClient(authority) ?: return null
            try {
                return operation(client)
            } catch (died: DeadObjectException) {
                if (++attempt == ATTEMPTS) throw died
            } finally {
                client.close()
            }
        }
    }

    /** One bridge method, blocking; the reply, or null when the host has no provider. */
    internal fun call(method: String, extras: Bundle? = null): Bundle? =
        perform { client -> client.call(method, null, extras) }

    private suspend fun <T : Any> within(timeout: Duration, block: () -> T): T? {
        val result = calls.async { block() }
        return withTimeoutOrNull(timeout) { result.await() }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever the host throws over binder must become a status.
    private fun handshake(): FlagBridgeConnection = try {
        perform { client ->
            val protocol = client.call(FlagBridgeContract.METHOD_HELLO, null, null)?.getInt(FlagBridgeContract.KEY_PROTOCOL)
            if (protocol != FlagBridgeContract.PROTOCOL) {
                FlagBridgeConnection.Incompatible(
                    "The host speaks bridge protocol $protocol; this companion speaks ${FlagBridgeContract.PROTOCOL}. " +
                        "Update whichever app is older.",
                )
            } else {
                schema(client)
            }
        } ?: FlagBridgeConnection.BridgeNotInstalled(hostPackage)
    } catch (ignored: SecurityException) {
        permissionDenied()
    } catch (ignored: RemoteException) {
        // The host died twice in a row: as far as the companion can tell, it is not answering.
        FlagBridgeConnection.Unresponsive(hostPackage, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RuntimeException) {
        FlagBridgeConnection.Incompatible(
            "'$hostPackage' failed while answering the bridge: ${error.message ?: error.javaClass.simpleName}. " +
                "Update whichever app is older, then try again.",
        )
    }

    private fun schema(client: ContentProviderClient): FlagBridgeConnection = try {
        val descriptor = client.openFile(FlagBridgeContract.schemaUri(hostPackage), "r")
            ?: throw FileNotFoundException("The host served no schema.")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        FlagBridgeConnection.Connected(FlagSchema.parse(bytes))
    } catch (ignored: FileNotFoundException) {
        FlagBridgeConnection.NotPublished
    } catch (ignored: IOException) {
        FlagBridgeConnection.Unresponsive(hostPackage, timeout)
    } catch (error: FlagSchemaException) {
        FlagBridgeConnection.Incompatible(error.message.orEmpty())
    }

    private fun definesPermission(packageManager: PackageManager): Boolean = try {
        packageManager.getPermissionInfo(permission, 0)
        true
    } catch (ignored: PackageManager.NameNotFoundException) {
        false
    }

    private fun permissionDenied(): FlagBridgeConnection.PermissionDenied {
        val packageManager = context.packageManager
        return FlagBridgeConnection.PermissionDenied(
            hostPackage = hostPackage,
            hostCertificates = certificates(packageManager, hostPackage),
            companionCertificates = certificates(packageManager, context.packageName),
        )
    }

    private fun certificates(packageManager: PackageManager, packageName: String): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo(packageManager, packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo(packageManager, packageName, PackageManager.GET_SIGNATURES)?.signatures
        }
        return signatures.orEmpty().map(::fingerprint)
    }

    private companion object {
        const val ATTEMPTS = 2

        /** How long a withdrawal may take; the coroutine that asked for it is already cancelled. */
        val CANCEL_TIMEOUT = 2.seconds

        @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) needs API 33.
        fun packageInfo(packageManager: PackageManager, packageName: String, flags: Int): PackageInfo? = try {
            packageManager.getPackageInfo(packageName, flags)
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }

        const val HEX_DIGITS = "0123456789ABCDEF"

        /** SHA-256 of the certificate, uppercase hex with no separators, as apksigner prints it. */
        fun fingerprint(signature: Signature): String = buildString {
            for (byte in MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) {
                val unsigned = byte.toInt() and 0xFF
                append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0xF])
            }
        }
    }
}
```

Only `send`, the new `withdraw`, `CANCEL_TIMEOUT` and two imports (`java.util.UUID`, `Duration.Companion.milliseconds`) differ from M2.

**Modify** `featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt` (full replacement):
```kotlin
package com.andyyhope.featureflag.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lets a companion app signed with the host's key read the host's schema, read and write its
 * companion overrides, observe them, and send it signals.
 *
 * Merged into the host by depending on `featureflag-bridge`. It needs no registration: it serves
 * the schema `publishSchema(context)` writes, the overrides in the file
 * [SharedPreferencesSource.companion] reads, and the signals [FlagSignalChannel.host] delivers.
 *
 * A host cannot use the bridge on itself: the provider refuses callers in its own process, so the
 * host reads and writes through its pole.
 */
public class FlagBridgeProvider : ContentProvider() {

    private lateinit var source: SharedPreferencesSource
    private lateinit var preferences: SharedPreferences
    private lateinit var overridesUri: Uri

    // Strongly held: SharedPreferences keeps its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.contentResolver?.notifyChange(overridesUri, null)
    }

    @Volatile
    private var started = false

    /** How long the first answer waits for the main looper. Tests shorten it. */
    internal var startupTimeout: Duration = STARTUP_TIMEOUT

    /**
     * Each delayed signal's cancellation, by ticket: opened by [FlagBridgeContract.METHOD_CANCEL],
     * removed when the signal stops waiting. A cancel for a signal that never arrives, or already
     * finished, stays; the oldest go once there are more than [MAX_CANCELLATIONS].
     */
    private val cancellations = object : LinkedHashMap<String, CountDownLatch>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CountDownLatch>?): Boolean =
            size > MAX_CANCELLATIONS
    }

    override fun onCreate(): Boolean {
        val context = checkNotNull(context)
        source = SharedPreferencesSource.companion(context)
        preferences = context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE)
        overridesUri = FlagBridgeContract.overridesUri(context.packageName)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /** The clock a signal's deadline is read against; the same one the client reads. Tests replace it. */
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val enteredAt = elapsedRealtime()
        val context = checkNotNull(context)
        // The manifest permission already keeps other apps from opening the provider; this is defence in depth.
        context.enforceCallingPermission(
            FlagBridgeContract.permission(context.packageName),
            "Only apps signed with the host's key may use the FeatureFlag bridge",
        )
        // A client that has already given up gets its answer before the startup barrier holds a binder thread for it.
        if (method == FlagBridgeContract.METHOD_SIGNAL && deadlineExpired(extras, enteredAt)) {
            return Bundle().apply { putBoolean(FlagBridgeContract.KEY_HANDLED, false) }
        }
        // A cancel needs nothing the app's onCreate sets up, so it does not wait for it either.
        if (method == FlagBridgeContract.METHOD_CANCEL) return cancel(arg)
        awaitStartup()
        return when (method) {
            FlagBridgeContract.METHOD_HELLO -> Bundle().apply {
                putInt(FlagBridgeContract.KEY_PROTOCOL, FlagBridgeContract.PROTOCOL)
            }
            FlagBridgeContract.METHOD_OVERRIDES -> overrides(context)
            FlagBridgeContract.METHOD_APPLY -> apply(context, extras)
            FlagBridgeContract.METHOD_SIGNAL -> Bundle().apply {
                // The wait comes first: the deadline already includes it, so the budget is not spent twice.
                val delivered = waitOutDelay(extras, enteredAt) &&
                    deliver(arg.orEmpty(), deliveryTimeout(extras, enteredAt))
                putBoolean(FlagBridgeContract.KEY_HANDLED, delivered)
            }
            else -> throw IllegalArgumentException("Unknown bridge method '$method'.")
        }
    }

    // No budget left: deliver would still queue the task, and a handler could run for a client told false.
    private fun deliver(name: String, timeout: Duration): Boolean =
        timeout > Duration.ZERO && FlagSignalChannel.host.deliver(name, timeout)

    /** How long a signal asks the host to wait before delivering it: its [FlagBridgeContract.KEY_DELAY], clamped. */
    internal fun signalDelay(extras: Bundle?): Duration =
        (extras?.getLong(FlagBridgeContract.KEY_DELAY) ?: 0L)
            .coerceIn(0L, FlagBridgeContract.MAX_DELAY.inWholeMilliseconds)
            .milliseconds

    /**
     * Holds a delayed signal until its delay, counted from when it arrived, has passed. False when
     * a [FlagBridgeContract.METHOD_CANCEL] for its ticket came first, before or during the wait.
     */
    private fun waitOutDelay(extras: Bundle?, enteredAt: Long): Boolean {
        val delay = signalDelay(extras)
        if (delay <= Duration.ZERO) return true
        val ticket = extras?.getString(FlagBridgeContract.KEY_TICKET)
        // A delayed signal without a ticket cannot be cancelled, but still waits.
        val cancelled = ticket?.let(::cancellation) ?: CountDownLatch(1)
        try {
            val remaining = enteredAt + delay.inWholeMilliseconds - elapsedRealtime()
            if (remaining > 0) cancelled.await(remaining, TimeUnit.MILLISECONDS)
            return cancelled.count > 0
        } finally {
            if (ticket != null) synchronized(cancellations) { cancellations.remove(ticket) }
        }
    }

    /**
     * Withdraws the delayed signal [ticket] names. A cancel can overtake its signal on the way in, so
     * one for a ticket that is not waiting yet is kept, and the signal finds it when it arrives.
     */
    private fun cancel(ticket: String?): Bundle {
        if (ticket != null) cancellation(ticket).countDown()
        return status(FlagBridgeContract.STATUS_OK)
    }

    private fun cancellation(ticket: String): CountDownLatch =
        synchronized(cancellations) { cancellations.getOrPut(ticket) { CountDownLatch(1) } }

    private fun deadlineExpired(extras: Bundle?, enteredAt: Long): Boolean =
        extras != null && extras.containsKey(FlagBridgeContract.KEY_DEADLINE) &&
            extras.getLong(FlagBridgeContract.KEY_DEADLINE) <= enteredAt

    /**
     * How long a signal's handlers may take. A client states when it stops waiting, so the host
     * gives it what is left less [REPLY_MARGIN] for the reply's trip back, clamped so a garbage
     * deadline cannot hold a binder thread. An older client states nothing: it gets the default,
     * counted from [enteredAt] so a startup wait is not added on top.
     */
    internal fun deliveryTimeout(extras: Bundle?, enteredAt: Long): Duration {
        val now = elapsedRealtime()
        if (extras == null || !extras.containsKey(FlagBridgeContract.KEY_DEADLINE)) {
            return (FlagSignalChannel.DELIVERY_TIMEOUT - (now - enteredAt).milliseconds).coerceAtLeast(Duration.ZERO)
        }
        val deadline = extras.getLong(FlagBridgeContract.KEY_DEADLINE)
        // Compared first so an absurdly early deadline cannot overflow the subtraction.
        if (deadline <= now) return Duration.ZERO
        val remaining = deadline - now - REPLY_MARGIN.inWholeMilliseconds
        return remaining.coerceIn(0L, MAX_DELIVERY.inWholeMilliseconds).milliseconds
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        awaitStartup()
        val context = checkNotNull(context)
        if (uri.path != SCHEMA_PATH) throw FileNotFoundException("The bridge serves no file at $uri.")
        val file = File(FlagBridgeContract.schemaDirectory(context), FlagSchema.FILE_NAME)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * A cold-started host can receive its first call on a binder thread while `Application.onCreate`
     * is still running (S6). Once the main looper has run one task posted from here, `onCreate` has
     * returned, so the schema is published and every signal observer is registered.
     */
    private fun awaitStartup() {
        if (started) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val ran = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { ran.countDown() }
            ran.await(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
        started = true
    }

    private fun overrides(context: Context): Bundle {
        val schema = schema(context) ?: return status(FlagBridgeContract.STATUS_NOT_PUBLISHED)
        val bytes = FlagPayload(acceptedOverrides(schema)).jsonBytes()
        if (bytes.size > FlagBridgeContract.MAX_PAYLOAD_BYTES) {
            return status(FlagBridgeContract.STATUS_TOO_LARGE).apply { putInt(FlagBridgeContract.KEY_SIZE, bytes.size) }
        }
        return status(FlagBridgeContract.STATUS_OK).apply { putByteArray(FlagBridgeContract.KEY_PAYLOAD, bytes) }
    }

    /**
     * Every stored override the schema accepts. The file belongs to the host, so its own code may
     * have left a value a strict import would reject; that value is left out rather than failing the
     * whole document. One pass drops every rejected key, so the second always decodes.
     */
    private fun acceptedOverrides(schema: FlagSchema): Map<FlagKey, FlagValueBox> {
        var values = schema.valueTypes.mapNotNull { (key, type) ->
            source.box(key, type)?.takeUnless { it.containsNonFiniteNumber }?.let { key to it }
        }.toMap()
        repeat(DECODE_ATTEMPTS) {
            val problems = try {
                FlagPayload.decode(FlagPayload(values).jsonBytes(), schema)
                return values
            } catch (rejected: FlagImportException.Rejected) {
                rejected.problems
            }
            values = values - problems.map { it.key }.toSet()
        }
        return values
    }

    private fun apply(context: Context, extras: Bundle?): Bundle {
        val schema = schema(context) ?: return status(FlagBridgeContract.STATUS_NOT_PUBLISHED)
        val payload = try {
            extras?.getByteArray(FlagBridgeContract.KEY_PAYLOAD)?.let { FlagPayload.decode(it, schema) }
        } catch (error: FlagImportException) {
            return rejected(error.message.orEmpty())
        }
        val removals = extras?.getStringArray(FlagBridgeContract.KEY_REMOVE).orEmpty().map(::FlagKey)
        val unknown = removals.filter { it !in schema.valueTypes }
        if (unknown.isNotEmpty()) return rejected(unknownRemovalMessage(unknown))

        val changes = LinkedHashMap<FlagKey, FlagValueBox?>()
        payload?.values?.let(changes::putAll)
        removals.forEach { changes[it] = null }
        try {
            source.setBoxes(changes)
        } catch (error: FlagException.UnsupportedValue) {
            // Strict import reads -1e400 as an infinity, which the store cannot hold. Nothing was written.
            return rejected(error.message.orEmpty())
        }
        // setBoxes commits nothing, and so notifies nothing, when the values already match. The companion
        // orders its refresh replies on every successful apply being announced, so announce it here.
        context.contentResolver.notifyChange(overridesUri, null)
        return status(FlagBridgeContract.STATUS_OK)
    }

    private fun schema(context: Context): FlagSchema? = try {
        FlagSchema.read(FlagBridgeContract.schemaDirectory(context))
    } catch (ignored: FlagSchemaException) {
        // Missing, or unreadable: either way there is nothing to check overrides against.
        null
    }

    private fun status(status: String) = Bundle().apply { putString(FlagBridgeContract.KEY_STATUS, status) }

    private fun rejected(message: String) = status(FlagBridgeContract.STATUS_REJECTED).apply {
        putString(FlagBridgeContract.KEY_MESSAGE, message)
    }

    private fun unknownRemovalMessage(keys: List<FlagKey>): String {
        val names = keys.map { "'${it.rawValue}'" }
        val listed = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " or " + names.last()
        val them = if (names.size == 1) "it" else "them"
        return "The host's schema has no flag $listed, so it cannot remove $them. Reconnect to load the current schema."
    }

    private companion object {
        val STARTUP_TIMEOUT = 10.seconds
        val REPLY_MARGIN = 100.milliseconds
        val MAX_DELIVERY = 30.seconds
        const val SCHEMA_PATH = "/schema"
        const val DECODE_ATTEMPTS = 2

        // Far more than the binder threads that can be waiting at once.
        const val MAX_CANCELLATIONS = 64
    }
}
```

Only the cancel short-cut and the signal branch of `call`, the new `deliver`, `signalDelay`, `waitOutDelay`, `cancel` and `cancellation`, the `cancellations` map and `MAX_CANCELLATIONS` differ from M2.

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModel.kt`. Replace

```kotlin
    init {
        session.start()
    }

    override fun onCleared() {
```

with

```kotlin
    init {
        session.start()
    }

    /**
     * Asks the host to deliver [signal] after [delay], within the client's timeout on top: what the
     * signals model sends with. Cancelling the caller withdraws a delayed signal from the host.
     */
    suspend fun send(signal: FlagSignal, delay: Duration): Boolean = client.send(signal, delay = delay)

    override fun onCleared() {
```

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionViewModel.kt`, second edit. Replace

```kotlin
import com.andyyhope.featureflag.android.bridge.FlagBridgeSource
import com.andyyhope.featureflag.sources.MutableFlagValueSource
```

with

```kotlin
import com.andyyhope.featureflag.android.bridge.FlagBridgeSource
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.sources.MutableFlagValueSource
```

- [ ] **Step 4: Run the unit tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-android:testDebugUnitTest :featureflag-bridge:testDebugUnitTest :featureflag-ui:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. `FlagBridgeContractTest` `tests="6"`, `FlagBridgeClientTest` `tests="36"`, `FlagBridgeProviderTest` `tests="38"`, `FlagCompanionViewModelTest` `tests="4"`, all `failures="0" errors="0"`.

- [ ] **Step 5: Add the device tests, with a host that records what it delivers**

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/HostTestApplication.kt`. Replace

```kotlin
            BridgeTestSignal.DieOnce,
            BridgeTestSignal.Slow,
        )
        observation = FlagSignalChannel.host.observe(observed) { signal ->
            when (signal) {
                BridgeTestSignal.Explode -> error("The Explode handler always fails.")
                // Kills this process mid-call the first time, as a host that crashes or is reclaimed would.
                BridgeTestSignal.DieOnce -> if (File(filesDir, DIE_ONCE_MARKER).createNewFile()) Process.killProcess(Process.myPid())
                BridgeTestSignal.Slow -> Thread.sleep(SLOW_HANDLER_MILLIS)
                else -> Unit
            }
        }
```

with

```kotlin
            BridgeTestSignal.DieOnce,
            BridgeTestSignal.Slow,
            BridgeTestSignal.Record,
        )
        observation = FlagSignalChannel.host.observe(observed) { signal ->
            when (signal) {
                BridgeTestSignal.Explode -> error("The Explode handler always fails.")
                // Kills this process mid-call the first time, as a host that crashes or is reclaimed would.
                BridgeTestSignal.DieOnce -> if (File(filesDir, DIE_ONCE_MARKER).createNewFile()) Process.killProcess(Process.myPid())
                BridgeTestSignal.Slow -> Thread.sleep(SLOW_HANDLER_MILLIS)
                // Leaves a trace the tests, in the other process, can read: one line per delivery.
                BridgeTestSignal.Record -> File(filesDir, DELIVERIES_LOG).appendText("${signal.name}\n")
                else -> Unit
            }
        }
```

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/HostTestApplication.kt`, second edit. Replace

```kotlin
        /** Present once DieOnce has killed a host; the tests delete it first. Both processes share filesDir. */
        const val DIE_ONCE_MARKER = "die-once"
```

with

```kotlin
        /** Present once DieOnce has killed a host; the tests delete it first. Both processes share filesDir. */
        const val DIE_ONCE_MARKER = "die-once"

        /** A line per Record the host's handler ran; the tests delete it first. */
        const val DELIVERIES_LOG = "deliveries"
```

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/HostTestApplication.kt`, third edit. Replace

```kotlin
/** The signals the host observes, plus one it does not. */
enum class BridgeTestSignal : FlagSignal { Purge, Explode, DieOnce, Slow, Unobserved }
```

with

```kotlin
/** The signals the host observes, plus one it does not. */
enum class BridgeTestSignal : FlagSignal { Purge, Explode, DieOnce, Slow, Record, Unobserved }
```

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/CrossProcessBridgeTest.kt`. Replace

```kotlin
import kotlinx.coroutines.runBlocking
import org.junit.After
```

with

```kotlin
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
```

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/CrossProcessBridgeTest.kt`, second edit. Replace

```kotlin
import kotlin.time.Duration.Companion.seconds
```

with

```kotlin
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
```

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/CrossProcessBridgeTest.kt`, third edit. Replace

```kotlin
        assertTrue(client.send(BridgeTestSignal.Slow, timeout = 10.seconds))
    }
```

with

```kotlin
        assertTrue(client.send(BridgeTestSignal.Slow, timeout = 10.seconds))
    }

    // Delayed signals

    @Test
    fun aDelayedSignalIsHandledOnceItsDelayHasPassed() = runBlocking<Unit> {
        deliveries.delete()
        client.connect()

        val (handled, waited) = measureTimedValue { client.send(BridgeTestSignal.Record, delay = 2.seconds) }

        assertTrue(handled)
        assertTrue(waited >= 2.seconds, "answered after $waited")
        assertEquals(listOf("Record"), deliveries.readLines())
    }

    @Test
    fun aCancelledDelayedSignalIsNotDelivered() = runBlocking<Unit> {
        deliveries.delete()
        client.connect()
        val pid = hostPid()

        val send = launch { client.send(BridgeTestSignal.Record, delay = 3.seconds) }
        delay(1.seconds)
        send.cancelAndJoin()
        // Past the moment the host would have delivered it.
        delay(4.seconds)

        assertFalse(deliveries.exists(), "the host delivered a cancelled signal")
        assertEquals(pid, hostPid())
        // The host is still answering signals.
        assertTrue(client.send(BridgeTestSignal.Record))
        assertEquals(listOf("Record"), deliveries.readLines())
    }
```

**Modify** `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/CrossProcessBridgeTest.kt`, fourth edit. Replace

```kotlin
    private fun source(): FlagBridgeSource = runBlocking { client.source(schema) }.also(opened::add)
```

with

```kotlin
    private fun source(): FlagBridgeSource = runBlocking { client.source(schema) }.also(opened::add)

    /** What the host's Record handler wrote; both processes share filesDir. */
    private val deliveries: File get() = File(context.filesDir, HostTestApplication.DELIVERIES_LOG)
```

`aCancelledDelayedSignalIsNotDelivered` is what the host observably did, not what the client was told: the `Record` handler, in the `:host` process, is the only writer of `filesDir/deliveries`. The test cancels a 3 s send after 1 s, waits 4 s more, past the moment the host would have delivered it, and checks the file does not exist. It then checks the host is the same process and still delivers, so a crash cannot pass for a withheld signal.

- [ ] **Step 6: Start the emulator and run the device suite**

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

If `emulator-5554	device` is not listed, start the AVD in the background and wait for it to boot:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_7 -no-snapshot-save -no-boot-anim &
~/Library/Android/sdk/platform-tools/adb wait-for-device
until [ "$(~/Library/Android/sdk/platform-tools/adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
```

Then:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-bridge:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, `Finished 20 tests on Pixel_7(AVD) - 16` (17 `CrossProcessBridgeTest`, 3 `ArtNumberDescriptionTest`). In `featureflag-bridge/build/outputs/androidTest-results/connected/debug/*.xml`, `aDelayedSignalIsHandledOnceItsDelayHasPassed` takes about 2 s and `aCancelledDelayedSignalIsNotDelivered` about 5 s. To see the device test bite, make `send`'s `if (ticket != null) withdraw(ticket)` never run: `aCancelledDelayedSignalIsNotDelivered` then fails with `the host delivered a cancelled signal`. Put it back before the gate.

- [ ] **Step 7: Run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL`, and `No issues found.` in the lint reports of `featureflag-android`, `featureflag-bridge` and `featureflag-ui`.

- [ ] **Step 8: Commit**

```bash
git add featureflag-android featureflag-bridge featureflag-ui/src
git commit -m "Let the host hold a signal's delay and withdraw it when the send is cancelled" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A6: `FlagSignalGroup`, `FlagSignalDelay` and `FlagSignalsModel`

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalGroup.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalDelay.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalsModel.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/ScriptedSend.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalGroupTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalDelayTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalCountdownTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalsModelTest.kt`

**Interfaces:**
- Consumes: core `FlagSignal` (`name`, `signalDescription`, `requiresRestart`); A5's `send(signal, delay)` contract, through the model's `send` parameter (B5 passes `FlagCompanionViewModel::send`).
- Produces: exactly the contract's signals API:
```kotlin
// package com.andyyhope.featureflag.ui.signals
public enum class FlagSignalGroupDisplay { Automatic, Nested, Flat }
public class FlagSignalGroup(public val title: String, public val signals: List<FlagSignal>, public val display: FlagSignalGroupDisplay = FlagSignalGroupDisplay.Automatic) {
    public val id: String
    internal val isNested: Boolean
    public companion object { public const val AUTOMATIC_NESTING_THRESHOLD: Int = 6 }
}
internal fun List<FlagSignalGroup>.duplicateSignalName(): String?
public enum class FlagSignalDelay(public val duration: Duration, public val label: String) { Instant, Three, Five, Ten }
internal data class FlagSignalCountdown(val start: Instant, val duration: Duration) { fun progress(at: Instant): Float; val end: Instant }
internal data class FlagSignalAttempt(val signal: FlagSignal, val handled: Boolean, val at: Instant) { val outcome: String }
internal class FlagSignalsModel(
    private val send: suspend (signal: FlagSignal, delay: Duration) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    val delay: MutableStateFlow<FlagSignalDelay>
    val pending: StateFlow<FlagSignal?>; val countdown: StateFlow<FlagSignalCountdown?>
    val inFlight: StateFlow<FlagSignal?>; val history: StateFlow<List<FlagSignalAttempt>>
    fun isDisabled(signal: FlagSignal): Boolean
    fun tapped(signal: FlagSignal)
    fun cancelPending()
    companion object { const val HISTORY_LIMIT: Int = 5 }
}
```
- Test helper `ScriptedSend`: records `calls` as `(signal, delay)`, makes each call wait for `answer(handled)`, and counts `withdrawn` (calls cancelled while waiting).

**Port targets:** `FlagSignalGroupTests`' [P] tests except two (9 in `FlagSignalGroupTest`, with `aNameRepeatedWithinOneGroupIsDetectedToo` and `aGroupIsIdentifiedByItsTitle` added); all of `FlagSignalDelayTests` (4); all of `FlagSignalCountdownTests` (7, with `theIntervalRunsFromTheTapToWhenTheSignalFires` as `theCountdownEndsWhenTheSignalFires` because the contract has `end`, not an interval), plus `aCountdownOfNoTimeIsAlreadyFull` (8). `testTheThresholdCanBeMoved` is not portable and `testTheGroupedTabDescriptor` moves to B6; see "Contract issues found" 6. `FlagSignalsModelTest` (12) pins the model, including `cancellingDuringTheCountdownRecordsNothing`.

The model calls `send(signal, delay)` once at the tap and never sleeps itself: the host waits. `cancelPending` cancels only the pending (delayed) job; an instant send already in flight runs to its answer, as on iOS. After `send` returns, the job checks `ensureActive()` before touching state, so a send cancelled as it returned never clears a newer send's countdown.

- [ ] **Step 1: Write the failing tests**

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/ScriptedSend.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

/**
 * The bridge's send, scripted: each call is recorded and waits for the test to [answer] it, as the
 * host does once its delay has passed. A cancelled call is counted, as the client would withdraw it.
 */
internal class ScriptedSend {

    private val answers = Channel<Boolean>(Channel.UNLIMITED)

    /** Every call, in order, with the delay it asked the host for. */
    val calls = mutableListOf<Pair<FlagSignal, Duration>>()

    /** How many calls were cancelled while they waited. */
    var withdrawn = 0
        private set

    suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
        calls += signal to delay
        try {
            return answers.receive()
        } catch (cancelled: CancellationException) {
            withdrawn++
            throw cancelled
        }
    }

    /** The host's answer to the oldest call still waiting. */
    fun answer(handled: Boolean) {
        answers.trySend(handled)
    }
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalGroupTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports Swift's FlagSignalGroupTests: filing signals into groups, and deciding which earn a screen.
class FlagSignalGroupTest {

    private enum class SmallSignal(override val signalDescription: String, override val requiresRestart: Boolean) : FlagSignal {
        SignOut("Sign out", requiresRestart = false),
        SwapDependency("Swap dependency", requiresRestart = true),
    }

    private enum class CollidingSignal : FlagSignal { SignOut }

    private data class Named(override val name: String) : FlagSignal

    private val six = ('a'..'f').map { Named("$it") }
    private val seven = ('a'..'g').map { Named("$it") }

    // Automatic nesting

    @Test
    fun aSmallGroupStaysFlat() {
        val group = FlagSignalGroup("Session", SmallSignal.entries)

        assertEquals(2, group.signals.size)
        assertFalse(group.isNested)
    }

    @Test
    fun aGroupNestsOnceItPassesTheThreshold() {
        // Six fit beside the delay picker; a seventh is where a list starts to bury it.
        assertEquals(6, FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD)
        assertFalse(FlagSignalGroup("Six", six).isNested)
        assertTrue(FlagSignalGroup("Seven", seven).isNested)
    }

    @Test
    fun anExplicitDisplayOverridesTheCount() {
        assertTrue(FlagSignalGroup("Session", SmallSignal.entries, FlagSignalGroupDisplay.Nested).isNested)
        assertFalse(FlagSignalGroup("Seven", seven, FlagSignalGroupDisplay.Flat).isNested)
    }

    // Contents

    @Test
    fun aGroupCarriesEachSignalsDescriptionAndRestartFlag() {
        val group = FlagSignalGroup("Session", SmallSignal.entries)

        val signOut = group.signals.first { it.name == "SignOut" }
        assertEquals("Sign out", signOut.signalDescription)
        assertFalse(signOut.requiresRestart)
        assertTrue(group.signals.first { it.name == "SwapDependency" }.requiresRestart)
    }

    @Test
    fun groupsKeepTheOrderTheyAreGivenAndSoDoTheirSignals() {
        val groups = listOf(FlagSignalGroup("Session", SmallSignal.entries), FlagSignalGroup("Six", six))

        assertEquals(listOf("Session", "Six"), groups.map { it.title })
        assertEquals(listOf("SignOut", "SwapDependency"), groups[0].signals.map { it.name })
    }

    @Test
    fun aGroupIsIdentifiedByItsTitle() {
        val group = FlagSignalGroup("Session", SmallSignal.entries)

        assertEquals("Session", group.id)
        assertEquals(FlagSignalGroupDisplay.Automatic, group.display)
    }

    // Collisions

    @Test
    fun twoGroupsSharingANameAreDetected() {
        // A signal travels as its name and nothing else, so one name in two groups fires both observers.
        val groups = listOf(FlagSignalGroup("A", SmallSignal.entries), FlagSignalGroup("B", CollidingSignal.entries))

        assertEquals("SignOut", groups.duplicateSignalName())
    }

    @Test
    fun aNameRepeatedWithinOneGroupIsDetectedToo() {
        assertEquals("a", listOf(FlagSignalGroup("Twice", listOf(Named("a"), Named("b"), Named("a")))).duplicateSignalName())
    }

    @Test
    fun distinctGroupsDoNotCollide() {
        val groups = listOf(FlagSignalGroup("Session", SmallSignal.entries), FlagSignalGroup("Six", six))

        assertNull(groups.duplicateSignalName())
        assertNull(emptyList<FlagSignalGroup>().duplicateSignalName())
    }
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalDelayTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Ports Swift's FlagSignalDelayTests.
class FlagSignalDelayTest {

    @Test
    fun theOfferedDelaysAreInstantThreeFiveAndTen() {
        assertEquals(listOf(Duration.ZERO, 3.seconds, 5.seconds, 10.seconds), FlagSignalDelay.entries.map { it.duration })
    }

    @Test
    fun instantIsLabelledAsSuchAndTheRestInSeconds() {
        assertEquals(listOf("Instant", "3s", "5s", "10s"), FlagSignalDelay.entries.map { it.label })
    }

    @Test
    fun instantIsTheOnlyZeroDelay() {
        // Zero sends at once; anything else counts down, and can be cancelled.
        assertEquals(listOf(FlagSignalDelay.Instant), FlagSignalDelay.entries.filter { it.duration == Duration.ZERO })
    }

    @Test
    fun delaysAreDistinctAndAscending() {
        val durations = FlagSignalDelay.entries.map { it.duration }

        assertEquals(durations.sorted(), durations)
        assertEquals(durations.size, durations.toSet().size)
    }
}
```

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalCountdownTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Ports Swift's FlagSignalCountdownTests: the ring is drawn from the clock, so how far round it is
// at a given moment is plain arithmetic.
// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class FlagSignalCountdownTest {

    private enum class CountdownSignal : FlagSignal { Purge }

    private val start = Instant.parse("2001-01-01T00:16:40Z")

    @Test
    fun theRingIsEmptyWhenTheCountdownStarts() {
        assertEquals(0f, FlagSignalCountdown(start, 5.seconds).progress(start))
    }

    @Test
    fun theRingIsHalfwayHalfwayThrough() {
        assertEquals(0.5f, FlagSignalCountdown(start, 10.seconds).progress(start.plusSeconds(5)))
    }

    @Test
    fun theRingIsFullOnceTheDelayHasPassed() {
        // A companion back from the background after the send shows a full ring.
        assertEquals(1f, FlagSignalCountdown(start, 3.seconds).progress(start.plusSeconds(30)))
    }

    @Test
    fun theRingNeverRunsBackwards() {
        assertEquals(0f, FlagSignalCountdown(start, 3.seconds).progress(start.minusSeconds(1)))
    }

    @Test
    fun theCountdownEndsWhenTheSignalFires() {
        assertEquals(start.plusSeconds(5), FlagSignalCountdown(start, 5.seconds).end)
    }

    @Test
    fun aCountdownOfNoTimeIsAlreadyFull() {
        assertEquals(1f, FlagSignalCountdown(start, Duration.ZERO).progress(start))
    }

    @Test
    fun schedulingASignalStartsACountdownForTheChosenDelay() = runTest {
        val model = FlagSignalsModel(ScriptedSend()::send, backgroundScope) { start }
        model.delay.value = FlagSignalDelay.Five

        model.tapped(CountdownSignal.Purge)
        runCurrent()

        val countdown = assertNotNull(model.countdown.value)
        assertEquals(CountdownSignal.Purge, model.pending.value)
        assertEquals(5.seconds, countdown.duration)
        assertTrue(countdown.start >= start)
        model.cancelPending()
    }

    @Test
    fun cancellingClearsTheCountdown() = runTest {
        val model = FlagSignalsModel(ScriptedSend()::send, backgroundScope) { start }
        model.delay.value = FlagSignalDelay.Three

        model.tapped(CountdownSignal.Purge)
        model.tapped(CountdownSignal.Purge)
        runCurrent()

        assertNull(model.pending.value)
        assertNull(model.countdown.value)
    }
}
```

The Swift fixture's start, `Date(timeIntervalSinceReferenceDate: 1_000)`, is `2001-01-01T00:16:40Z`.

**Create** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalsModelTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The send loop of Swift's FlagSignalsModel, with the delay handed to the host: one send at a time,
// a cancellable countdown, and the five latest results.
// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class FlagSignalsModelTest {

    private enum class TestSignal(override val requiresRestart: Boolean = false) : FlagSignal {
        Purge,
        Refetch,
        SwapDependency(requiresRestart = true),
    }

    private val host = ScriptedSend()
    private var now = Instant.parse("2026-10-14T09:00:00Z")

    // Sending at once

    @Test
    fun theDelayStartsAtInstant() = runTest {
        assertEquals(FlagSignalDelay.Instant, model().delay.value)
    }

    @Test
    fun anInstantSendIsInFlightUntilItsAnswer() = runTest {
        val model = model()

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertEquals(TestSignal.Purge, model.inFlight.value)
        assertNull(model.pending.value)
        assertEquals(listOf<Pair<FlagSignal, Duration>>(TestSignal.Purge to Duration.ZERO), host.calls)

        host.answer(true)
        runCurrent()

        assertNull(model.inFlight.value)
        assertEquals(listOf(FlagSignalAttempt(TestSignal.Purge, handled = true, at = now)), model.history.value)
    }

    @Test
    fun whileASendIsInFlightEverySignalIsDisabled() = runTest {
        val model = model()

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertTrue(model.isDisabled(TestSignal.Purge))
        assertTrue(model.isDisabled(TestSignal.Refetch))
        model.tapped(TestSignal.Refetch)
        runCurrent()
        assertEquals(1, host.calls.size)
    }

    // Sending after a delay

    @Test
    fun aDelayedSendAsksTheHostOnceWithItsDelay() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Five

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertEquals(listOf<Pair<FlagSignal, Duration>>(TestSignal.Purge to 5.seconds), host.calls)
        assertEquals(TestSignal.Purge, model.pending.value)
        assertEquals(FlagSignalCountdown(now, 5.seconds), model.countdown.value)
        assertNull(model.inFlight.value)
    }

    @Test
    fun aDelayedSendClearsItsCountdownAndRecordsTheAnswer() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Three
        model.tapped(TestSignal.SwapDependency)
        runCurrent()
        now = now.plusSeconds(3)

        host.answer(true)
        runCurrent()

        assertNull(model.pending.value)
        assertNull(model.countdown.value)
        val attempt = model.history.value.single()
        assertEquals(FlagSignalAttempt(TestSignal.SwapDependency, handled = true, at = now), attempt)
        assertEquals("handled — relaunch to see it", attempt.outcome)
    }

    @Test
    fun cancellingDuringTheCountdownRecordsNothing() = runTest {
        // Leaving the tab cancels too. The host must not deliver it, so the send itself is cancelled.
        val model = model()
        model.delay.value = FlagSignalDelay.Ten
        model.tapped(TestSignal.Purge)
        runCurrent()

        model.cancelPending()
        runCurrent()

        assertNull(model.pending.value)
        assertNull(model.countdown.value)
        assertTrue(model.history.value.isEmpty())
        assertEquals(1, host.withdrawn)
        // A late answer for the cancelled send changes nothing.
        host.answer(true)
        runCurrent()
        assertTrue(model.history.value.isEmpty())
    }

    @Test
    fun tappingThePendingSignalAgainCancelsIt() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Three
        model.tapped(TestSignal.Purge)
        runCurrent()

        model.tapped(TestSignal.Purge)
        runCurrent()

        assertNull(model.pending.value)
        assertEquals(1, host.withdrawn)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun whileOneSignalCountsDownTheOthersAreDisabled() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Five
        model.tapped(TestSignal.Purge)
        runCurrent()

        assertFalse(model.isDisabled(TestSignal.Purge), "the pending signal stays enabled, to cancel it")
        assertTrue(model.isDisabled(TestSignal.Refetch))
        model.tapped(TestSignal.Refetch)
        runCurrent()
        assertEquals(TestSignal.Purge, model.pending.value)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun cancellingTheCountdownLeavesAnInstantSendAlone() = runTest {
        val model = model()
        model.tapped(TestSignal.Purge)
        runCurrent()

        model.cancelPending()
        host.answer(false)
        runCurrent()

        assertEquals(0, host.withdrawn)
        assertEquals(listOf(false), model.history.value.map { it.handled })
    }

    @Test
    fun aSignalCanBeSentAgainOnceItsCountdownIsCancelled() = runTest {
        val model = model()
        model.delay.value = FlagSignalDelay.Three
        model.tapped(TestSignal.Purge)
        runCurrent()
        model.cancelPending()

        model.tapped(TestSignal.Purge)
        runCurrent()
        host.answer(true)
        runCurrent()

        assertEquals(2, host.calls.size)
        assertEquals(listOf(true), model.history.value.map { it.handled })
        assertNull(model.pending.value)
    }

    // History

    @Test
    fun historyIsNewestFirstAndKeepsFive() = runTest {
        val model = model()
        repeat(6) { index ->
            now = now.plusSeconds(1)
            model.tapped(if (index % 2 == 0) TestSignal.Purge else TestSignal.Refetch)
            runCurrent()
            host.answer(index != 5)
            runCurrent()
        }

        val history = model.history.value
        assertEquals(FlagSignalsModel.HISTORY_LIMIT, history.size)
        assertEquals(
            listOf(TestSignal.Refetch, TestSignal.Purge, TestSignal.Refetch, TestSignal.Purge, TestSignal.Refetch),
            history.map { it.signal },
        )
        assertEquals(listOf(false, true, true, true, true), history.map { it.handled })
        assertEquals(history.map { it.at }.sortedDescending(), history.map { it.at })
    }

    @Test
    fun eachOutcomeSaysWhatHappened() {
        assertEquals("handled — relaunch to see it", FlagSignalAttempt(TestSignal.SwapDependency, true, now).outcome)
        assertEquals("handled", FlagSignalAttempt(TestSignal.Purge, true, now).outcome)
        assertEquals("no response", FlagSignalAttempt(TestSignal.Purge, false, now).outcome)
        assertEquals("no response", FlagSignalAttempt(TestSignal.SwapDependency, false, now).outcome)
    }

    private fun TestScope.model() = FlagSignalsModel(host::send, backgroundScope) { now }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
```

Expected: `BUILD FAILED`. `:featureflag-ui:compileDebugUnitTestKotlin` reports `Unresolved reference 'FlagSignalGroup'`, `'FlagSignalGroupDisplay'`, `'duplicateSignalName'`, `'FlagSignalDelay'`, `'FlagSignalCountdown'`, `'FlagSignalAttempt'` and `'FlagSignalsModel'`.

- [ ] **Step 3: Write the signals types and the model**

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalGroup.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal

/** How a signal group is laid out on the Signals screen. */
public enum class FlagSignalGroupDisplay {
    /** Inline while it is small, on a screen of its own once it has more than [FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD]. */
    Automatic,

    /** Always on a screen of its own, reached from a row. */
    Nested,

    /** Always inline, however many signals it has. */
    Flat,
}

/**
 * A titled set of signals for the Signals screen, usually one enum's entries:
 * `FlagSignalGroup("Session", AppSignal.entries)`. Signals keep the order they are given.
 */
public class FlagSignalGroup(
    public val title: String,
    public val signals: List<FlagSignal>,
    public val display: FlagSignalGroupDisplay = FlagSignalGroupDisplay.Automatic,
) {

    /** The group's [title], which is what tells one group from another. */
    public val id: String
        get() = title

    /** Whether the group gets a screen of its own rather than a section beside the delay picker. */
    internal val isNested: Boolean
        get() = when (display) {
            FlagSignalGroupDisplay.Nested -> true
            FlagSignalGroupDisplay.Flat -> false
            FlagSignalGroupDisplay.Automatic -> signals.size > AUTOMATIC_NESTING_THRESHOLD
        }

    public companion object {
        /** Six fit beside the delay picker; a seventh is where a list starts to bury it. */
        public const val AUTOMATIC_NESTING_THRESHOLD: Int = 6
    }
}

/**
 * The first signal name seen twice, across every group or within one, else null. A signal travels
 * as its name and nothing else, so two with one name would fire both of the host's observers.
 */
internal fun List<FlagSignalGroup>.duplicateSignalName(): String? {
    val seen = HashSet<String>()
    return flatMap { it.signals }.map { it.name }.firstOrNull { !seen.add(it) }
}
```

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalDelay.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long after the tap a signal is delivered. A delay lets the person switch to the host app
 * first, so it is in front and listening when the signal arrives. The host holds the delay.
 */
public enum class FlagSignalDelay(public val duration: Duration, public val label: String) {
    Instant(Duration.ZERO, "Instant"),
    Three(3.seconds, "3s"),
    Five(5.seconds, "5s"),
    Ten(10.seconds, "10s"),
}
```

**Create** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalsModel.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A delayed send's countdown, drawn from the clock rather than animated. */
internal data class FlagSignalCountdown(val start: Instant, val duration: Duration) {

    /** When the host delivers the signal. */
    val end: Instant
        get() = start + duration.toJavaDuration()

    /** How far round the ring is at [at]: 0 at the tap, 1 once the delay has passed, never outside 0 to 1. */
    fun progress(at: Instant): Float {
        if (duration <= Duration.ZERO) return 1f
        val elapsed = java.time.Duration.between(start, at).toNanos().toDouble()
        return (elapsed / duration.inWholeNanoseconds).coerceIn(0.0, 1.0).toFloat()
    }
}

/** One send and what came of it, for the Recent list. */
internal data class FlagSignalAttempt(val signal: FlagSignal, val handled: Boolean, val at: Instant) {

    /** "handled — relaunch to see it" for a signal that needs a relaunch, "handled", or "no response". */
    val outcome: String
        get() = when {
            handled && signal.requiresRestart -> "handled — relaunch to see it"
            handled -> "handled"
            else -> "no response"
        }
}

/**
 * The Signals screen's state. One send at a time: an instant one is in flight until the host
 * answers; a delayed one is pending, with a countdown, until the host delivers it and answers.
 *
 * A delayed send asks the host once, at the tap, and the host waits. Tapping the pending signal
 * again, or leaving the screen, cancels the send's coroutine, which withdraws it from the host.
 * Plain Kotlin; call it from one thread, the main thread in the app.
 */
internal class FlagSignalsModel(
    private val send: suspend (signal: FlagSignal, delay: Duration) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {

    /** The delay the next tap uses. */
    val delay: MutableStateFlow<FlagSignalDelay> = MutableStateFlow(FlagSignalDelay.Instant)

    private val pendingState = MutableStateFlow<FlagSignal?>(null)
    private val countdownState = MutableStateFlow<FlagSignalCountdown?>(null)
    private val inFlightState = MutableStateFlow<FlagSignal?>(null)
    private val historyState = MutableStateFlow<List<FlagSignalAttempt>>(emptyList())

    /** The signal counting down to delivery, if any. */
    val pending: StateFlow<FlagSignal?> = pendingState.asStateFlow()

    /** The pending signal's countdown. */
    val countdown: StateFlow<FlagSignalCountdown?> = countdownState.asStateFlow()

    /** The instant send waiting for the host's answer, if any. */
    val inFlight: StateFlow<FlagSignal?> = inFlightState.asStateFlow()

    /** The latest attempts, newest first, at most [HISTORY_LIMIT]. */
    val history: StateFlow<List<FlagSignalAttempt>> = historyState.asStateFlow()

    private var pendingJob: Job? = null

    /** Whether [signal]'s button is disabled: another send is under way. The pending signal stays enabled, to cancel it. */
    fun isDisabled(signal: FlagSignal): Boolean {
        val pending = pending.value
        return inFlight.value != null || (pending != null && pending != signal)
    }

    /** A tap on [signal]: cancels it when it is the pending one, otherwise sends it with the chosen [delay]. */
    fun tapped(signal: FlagSignal) {
        if (pending.value == signal) {
            cancelPending()
            return
        }
        if (isDisabled(signal)) return
        val chosen = delay.value.duration
        if (chosen > Duration.ZERO) schedule(signal, chosen) else sendNow(signal)
    }

    /** Cancels the pending send, which withdraws it from the host, and records nothing. */
    fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
        pendingState.value = null
        countdownState.value = null
    }

    private fun sendNow(signal: FlagSignal) {
        inFlightState.value = signal
        scope.launch {
            val handled = send(signal, Duration.ZERO)
            record(signal, handled)
            inFlightState.value = null
        }
    }

    private fun schedule(signal: FlagSignal, duration: Duration) {
        pendingState.value = signal
        countdownState.value = FlagSignalCountdown(clock(), duration)
        pendingJob = scope.launch {
            val handled = send(signal, duration)
            // A send cancelled as it returned must not clear a newer one's state.
            ensureActive()
            pendingJob = null
            pendingState.value = null
            countdownState.value = null
            record(signal, handled)
        }
    }

    private fun record(signal: FlagSignal, handled: Boolean) {
        val attempt = FlagSignalAttempt(signal, handled, clock())
        historyState.update { (listOf(attempt) + it).take(HISTORY_LIMIT) }
    }

    companion object {
        /** How many attempts Recent shows. */
        const val HISTORY_LIMIT: Int = 5
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `FlagSignalGroupTest` `tests="9"`, `FlagSignalDelayTest` `tests="4"`, `FlagSignalCountdownTest` `tests="8"`, `FlagSignalsModelTest` `tests="12"`, all `failures="0" errors="0"`; `170 tests completed` for `featureflag-ui`. No file under `ui/editing`, `ui/signals` or `ui/companion/FlagCompanionSession.kt` imports `android.*` or `androidx.*`:

```bash
grep -rln -e '^import android\.' -e '^import androidx\.' featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/editing featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/companion/FlagCompanionSession.kt
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src
git commit -m "Add signal groups, delays and the signals model with its countdown and recent attempts" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Contract issues found

Each item is something the contract gets wrong, leaves open, or cannot do as written. The closest faithful version is what this plan implements. None renames a contract name.

1. **`FlagContainerDescriptionUITests` cannot be A2's.** All three tests read `store.schema.description`, and the third refreshes the schema. A2 has neither a store nor a reload, so the first two are A3's (`theStoreSurfacesTheHostsDescription`, `aHostWithoutOneSurfacesNothing`) and the third is A4's (`aRefreshPicksUpADescriptionAddedByTheHost`, through `onForeground`).
2. **Review Focus 1's record-list case contradicts `value()` and a [P] port.** The overview says a record list with a duplicate key should read as the default, not "Overridden". But `value()` is defined as "the usable override (right type, and in cases when there are cases)", and the [P] test `aListWithADuplicateKeyReadsAsUnreadableToTheEditorToo` needs `value()` to return the stored text so that `duplicateKey(value(entry), shape)` names the duplicate.
   - This plan keeps the contract's `value()`: the stored text is the value, `records()` is null, and `resetAll` clears it. A3 `resettingEverythingClearsUnusableValuesToo` pins the clearing. B3 shows "Two records share…" with a reset, as iOS does.
   - Over the bridge, the provider already leaves such a value out of `overrides` (M2 `aStoredValueTheSchemaRejectsIsLeftOutOfOverrides`: import rules refuse a record list with a repeated key), so a companion row reads the default there. Only a local `SnapshotSource` can show the case.
3. **`FlagEditorKind.Picker(val cases: List<FlagValueBox>)` does not compile as written.** Inside `FlagEditorKind`, `List` is the nested `FlagEditorKind.List`. `Picker` is written `kotlin.collections.List<FlagValueBox>`, as the contract already writes `Records`.
4. **`FlagRecordEditingException(...) : RuntimeException()` would have no message.** The contract gives the message in the KDoc; the class passes it to `RuntimeException(message)` so `.message` (which the snackbar shows) carries it. The constructor is unchanged.
5. **`FlagValueText.parse` uses the import's readers for `Date` and `Url`, and refuses non-finite numbers in collections.**
   - `Date` goes through `IsoDate.parse`, which also accepts `z`, `±HHMM` and four- to seven-digit years. `Url` goes through core's `URL(string:)` port, which percent-encodes spaces and the like as iOS 17 does, rather than "parseable as `java.net.URI`" (`a b` would fail that). Both are what an import, and the host, read.
   - JSON reads `-1e400` as negative infinity, and `1e39` narrows to a float infinity, so `FlagValueJson.decode` alone would let a non-finite number into an array or dictionary. `parse` refuses such a collection, as deviation 7 intends.
6. **Two `FlagSignalGroupTests` do not port to A6.** `testTheThresholdCanBeMoved` mutates the threshold, which the contract makes a `const`; it is dropped (`display = Nested` is the way to nest a small group). `testTheGroupedTabDescriptor` needs `FlagCompanionTab.Signals`, which is B6's; B6 should port it.
7. **`testImportingAQRCodeThatIsNotOneIsRejected` expects `unrecognisedFormat`, which `import(text)` can never throw.** The store chooses by prefix: `FFQR1:hello` is read as a code and is `FlagQRCodeException.Corrupt`; `hello` is read as a document and is `FlagImportException.Malformed("not a JSON object")`. The port expects those. B4's import sheet maps both, by the iOS rules, to their messages.
8. **A cancel can overtake its signal, and the contract's map would lose it.** The provider "registers the ticket's latch in a map" after the startup barrier, and "`METHOD_CANCEL` with an unknown ticket answers `STATUS_OK`". A cancel that arrives first, because the signal is held at the startup barrier or the two calls cross on different binder threads, would find no latch and the signal would then be delivered: Review Focus 5's failure.
   - Fix: a cancel for an unknown ticket leaves an opened latch in the map, so the signal finds itself cancelled when it arrives. It still answers `STATUS_OK`. The map holds at most 64 entries, dropping the oldest, so cancels for signals that never arrive cannot grow it without bound.
   - `METHOD_CANCEL` is also answered before the startup barrier, as an expired signal is, so it is not held behind a cold host's `onCreate`.
   - Pinned by `aCancelThatOvertakesItsSignalStillWithholdsIt` and `aCancelDoesNotWaitForStartup`.
9. **The provider counts the delay from when the signal arrived.** The contract says it "waits for `delay`" after the startup barrier. This plan waits until `enteredAt + delay`, so a cold host's startup counts toward the delay and the signal still fires when the companion's countdown ends. Pinned by `theDelayCountsFromWhenTheSignalArrived`.
10. **`FlagCompanionViewModel`'s members are not in the contract.** It says only "owns a `FlagBridgeClient`, a session in `viewModelScope`, and `send()` for the signals model". This plan adds, all `internal`:
    - `val session`;
    - `companion object { fun factory(hostPackage: String, timeout: Duration): ViewModelProvider.Factory }`, for `viewModel(key = hostPackage, factory = …)`;
    - `suspend fun send(signal: FlagSignal, delay: Duration): Boolean`. It arrives in A5, not A4, because the client's `delay` parameter is A5's.
11. **Session behaviour the contract leaves open:**
    - Ready, and the reconnect is not `Connected`: keep the store and call `refresh()`, so the banner shows why. Swift keeps the last schema when its reloader returns nil (`aHostThatHasStoppedPublishingLeavesTheLastSchemaInPlace`).
    - A changed schema whose source cannot open: close the old source and show `Unavailable(error.message)` (`aChangedSchemaWhoseSourceCannotOpenIsUnavailable`).
    - One connection attempt at a time: a second `start`, `retry` or `onForeground` while one runs does nothing (`startingTwiceConnectsOnce`).
    - The store's `writeDispatcher` is the session's `ioDispatcher`.
    - `openSource` failures are caught only as `FlagBridgeException`, which is what `FlagBridgeClient.source` throws; anything else is a bug and propagates.
12. **Store behaviour the contract leaves open:**
    - `tree` is computed once, because `schema` is immutable.
    - `revision` bumps after every write, failed or not, so a refused edit's row reverts (Review Focus 3).
    - `resetAll` with nothing stored writes nothing; `observeIn` subscribes before it returns.
    - `copyableQrCode` is also null when an override holds a non-finite number (Swift's `try?`), not only for `PayloadTooLarge`.
13. **Swift's "P+core" record tests read back through a `FlagPole`.** `featureflag-ui` has no KSP, so `CompanionSchemas.kt` defines a hand-written `EndpointCodec` and a shape-only `RowsCodec`, and the tests decode with `FlagCodecs.records(codec)`, which is the host's own reader. No survey note was found wrong against the 0.12.0 source.

## Self-review

**Spec and contract coverage, per task:**
- A1: every `FlagValueText` and `FlagRecordList` signature, with each rule in their KDoc pinned by a test: Swift number forms (`numbersRenderAsSwiftDescribesThem`), non-finite collections rendering as `""`, every refusal in Review Focus 4, the suffix rules (`suffixesFollowTheKeysType`, `theFirstFreeSuffixWins`, `aKeyThatTakesNoSuffixKeepsItsCollidingValue`) and storage-identity collision (`aDateTakenWithinTheSameSecondCountsAsTaken`).
- A2: the module and convention exactly as the Global Constraints list them (the dependency block names the overview's list and nothing else at runtime), `robolectric.properties` checked by `theScreenIsPhoneSized`, every `FlagEditorKind` case and the mapping order, `FlagTreeNode` and `FlagSection`, and the hand-built fixtures for A3 and A4.
- A3: every `FlagEditingStore` member, each with at least one test; import as one `setBoxes`; reset-all as one `setBoxes` over schema keys only; writes on `writeDispatcher`; Review Focus 1 and 3.
- A4: `CompanionSource`, the three states, `start`, `retry`, `onForeground` (both branches), `refresh`, `close`, `asCompanionSource`, and Review Focus 2. "`refresh()` on `ON_START`" from M2's carried items is `onForeground`'s same-schema branch; B6 calls it.
- A5: `KEY_DELAY`, `KEY_TICKET`, `METHOD_CANCEL`, `MAX_DELAY`, the client's deadline, ticket and withdrawal, the provider's clamp, wait, cancel, `finally` removal, unknown-ticket `STATUS_OK`, and the expired-deadline fast path (M2's `anExpiredDeadlineDoesNotWaitForStartup` still passes); the fake; the device test with an observable host.
- A6: every signals type and member, the default delay, at most five attempts newest first, the three outcomes, `isDisabled`, `tapped` (cancel, instant, delayed), `cancelPending`, and Review Focus 5.

**Placeholder scan:** every step's code is a whole file or an exact `replace … with …` pair, generated from the files that passed the checks above. There is no "similar to", no elided body and no "add error handling". The only prose instruction to edit code is A5 Step 6's optional mutation, which says exactly what to disable and to restore it.

**Type consistency across tasks:**
- A1's `FlagRecordList.emptyRecord(shape, alongside, now)` and `FlagValueText.empty(type, now)` are what A3's tests call; A3's store uses `FlagRecordList.decode` / `encode` / `duplicateKey` with the same `List<Map<String, FlagValueBox>>` row type.
- A2's `FlagTreeNode.of` and `FlagSection(path, title, pathDescription, entries)` are what A3 builds; `CompanionSchemas` members used in A3 and A4 (`companion`, `demoUi`, `arrayEditor`, `recordEditor`, `keyedEditor`, `qr`, `described`, `plain`, the shapes, `entry(...)`, `hostEndpoints`, `hostRecords`) are all defined in A2's file.
- A4 builds `FlagEditingStore(schema, source, ioDispatcher)` with A3's constructor; `FlagCompanionState.Ready(store, failure)` carries A3's type.
- A5's `FlagBridgeClient.send(signal, timeout, delay)` is what A5's `FlagCompanionViewModel.send(signal, delay)` calls, and its shape `suspend (FlagSignal, Duration) -> Boolean` is A6's `FlagSignalsModel.send` parameter.
- `FlagSignalDelay.Instant` is qualified wherever `java.time.Instant` is in scope (`FlagSignalsModel.kt` imports `java.time.Instant` and writes `FlagSignalDelay.Instant`).
