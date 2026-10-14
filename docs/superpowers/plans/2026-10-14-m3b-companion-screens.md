# M3b Companion Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the iOS companion's screens in Compose Material 3 on top of M3a's logic, and wrap them in the one public composable a companion app adds:
- the shared pieces every screen uses: `FlagStrings`, `FlagIcons`, `FlagTextField` and `FlagRow` for every `FlagEditorKind` (B1);
- the Flags browser, a group screen and the Detail screen (B2);
- the list editor, the records list and the record screen (B3);
- the Overrides screen with its JSON, share, QR sheet, reset-all confirmation and import sheet, plus `FlagFileProvider` (B4);
- the Signals screen with delays, the countdown ring and recent results (B5);
- `FlagCompanion`, `FlagCompanionTab` and `FlagCompanionScope`: tabs, one back stack per tab, status screens, the failure banner, the write-failure snackbar and an on-device smoke test with screenshots (B6);
- (cuttable) the countdown notification for a delayed signal (B7).

**Architecture:**
- **Screens read the store, never cache it.** Every composable that reads `FlagEditingStore` first calls `store.observeRevision()`, which collects `revision`. Lazy-list item data (values, counts, rows) is computed in the composable body and captured by the `LazyColumn` content, so a write or an outside change re-runs the list. `FlagRow` observes on its own, so it is safe inside any list.
- **Writes go through one `FlagWriter`.** `ProvideFlagWriter` puts a `FlagWriter` in a composition local. It launches every store write in a scope that outlives the screen, and hands any failure to a callback; the shell's callback shows the exception's message in its snackbar. A text field passes `onFailed` so it can put the value back.
- **Navigation is plain state.** A `FlagRoute` is a small sealed type with a text codec. The shell keeps one `List<FlagRoute>` per tab in a `rememberSaveable` map, pops with `BackHandler`, and wraps each screen in a `SaveableStateHolder` slot, so a search query or a pushed group survives tab switches and recreation. There is no navigation-compose.
- **Testable without a bridge.** Screens take a store, a `FlagSignalsModel` or callbacks. The shell's `internal fun FlagCompanionContent(state, tabs, send, onRetry, onRefresh)` takes a `FlagCompanionState`, so Robolectric drives every state with a `SnapshotSource`-backed store. The public `FlagCompanion` only adds the ViewModel, the lifecycle hook and the duplicate-id check.

**Tech Stack:** As the M3 overview: AGP 9.1.1 with built-in Kotlin, Kotlin 2.2.10, Compose BOM `2026.01.00` (Compose 1.10.1, Material3 1.4.0, material-icons-core 1.7.8), activity-compose 1.10.1, lifecycle 2.9.1, androidx.core 1.16.0, ZXing core 3.5.4, JUnit 4.13.2 + kotlin-test-junit, Robolectric 4.16 with `ui-test-junit4`, AndroidX Test, detekt 1.23.8.

**Spec:** [`docs/superpowers/specs/2026-10-08-android-parity-design.md`](../specs/2026-10-08-android-parity-design.md), "Companion UI (M3)".

**Contract:** [`2026-10-14-m3-overview.md`](2026-10-14-m3-overview.md). Every public name and signature below is the contract's. M3a ([`2026-10-14-m3a-companion-logic.md`](2026-10-14-m3a-companion-logic.md), A1–A6) runs first; this plan consumes only the contract's signatures for what it produces (see "Seams"). Where this plan had to choose something the contract leaves open, or found the contract wrong, it says so under "Contract issues found" at the end.

**Inputs:** `docs/superpowers/notes/2026-10-14-m3-ios-views.md` (screens and verbatim strings), `docs/superpowers/notes/2026-10-14-m3-ios-logic.md` (§1.9 per-screen logic, §4 signals, §5 the [V] tests) and `docs/superpowers/notes/2026-10-14-m3-kotlin-apis.md`. The iOS source at `/Users/andyyhope/Development/feature-flags` (tag 0.12.0) was read for layout: `FlagRowView.swift` (the row and `FlagTextField`), `FlagSignalsView.swift`, `CompanionViewTests.swift` and `QRCodeExportTests.swift`.

## Global Constraints

- Everything in the M3 overview's Global Constraints applies, in particular:
  - **Every Gradle command runs with** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, from the repository root.
  - `featureflag-ui`'s runtime dependencies are exactly the overview's list. This plan adds none. B6 adds Compose test dependencies to `androidTestImplementation` and `debugImplementation` only.
  - Packages follow directories. Screens live in `com.andyyhope.featureflag.ui.screens` and are all `internal`; the clipboard, QR image and file provider in `.ui.share`; the notifier in `.ui.signals`; `FlagStrings`, `FlagIcons` and the entry points in `com.andyyhope.featureflag.ui`.
  - Public API is only `FlagCompanion`, `FlagCompanionTab` (and its members), `FlagCompanionScope` and `FlagFileProvider`.
  - The library never wraps itself in a `MaterialTheme`. Green is `colorScheme.primary`, orange is `colorScheme.error`, a 15% tint is `colorScheme.primaryContainer`.
  - A composable never calls a store write outside a coroutine; it calls `LocalFlagWriter.current.write { … }`.
- **Compose test conventions (this plan's):**
  - Robolectric screen tests are `@RunWith(AndroidJUnit4::class)` and `@Config(sdk = [36])`, with `@get:Rule val composeRule = createComposeRule()` (or `createAndroidComposeRule<ComponentActivity>()` where a test needs the activity). `robolectric.properties` (A2) sets `qualifiers=w411dp-h891dp`.
  - Tests that render a QR bitmap add `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, because Robolectric's legacy graphics do not compress real PNGs.
  - A store under test is `testStore(schema, values)`: a `SnapshotSource` with `writeDispatcher = Dispatchers.Main.immediate`, so a write runs inline on the Robolectric main thread. (detekt's `InjectDispatcher` forbids `Dispatchers.IO`, `Default` and `Unconfined` outside default arguments; `Main` is allowed.)
  - Nodes are found by text or content description. Text fields are found with `hasSetTextAction()`, tabs with `hasText(title) and isSelectable()`. No production `testTag` is added.
  - Anything past the first screenful of a `LazyColumn` is reached with the `scrollTo(text)` helper (`performScrollToNode`).
  - A state that shows an indeterminate spinner, or a transient "Copied"/snackbar, is asserted with `composeRule.mainClock.autoAdvance = false` and explicit `advanceTimeByFrame()`/`advanceTimeBy(…)`.
  - Test names are camelCase sentences; `// Section` comments group them. A ported iOS [V] test keeps the iOS name without `test`.
- **Static checks the code below is written for:** `explicitApi()`, `allWarningsAsErrors` (no deprecated API in BOM 2026.01.00: `Icons.AutoMirrored.*`, `HorizontalDivider`, `KeyboardOptions(autoCorrectEnabled = …)`, `TextOverflow.MiddleEllipsis`, the system `ClipboardManager` rather than the deprecated `LocalClipboardManager`, `androidx.lifecycle.compose.LocalLifecycleOwner`), detekt (`InjectDispatcher`, `TooGenericExceptionCaught`, `UnusedParameter`, `SpreadOperator`, `UseOrEmpty`, ktlint import ordering) and Android lint.
  - Experimental Material3 APIs (`TopAppBar`, `TimePicker`, `rememberTimePickerState`) carry a targeted `@OptIn(ExperimentalMaterial3Api::class)`. Sheets are full-screen `Dialog`s, not the experimental `ModalBottomSheet`.
  - Failures are caught with `runCatching { … }.exceptionOrNull()`, rethrowing `CancellationException` and non-`Exception` throwables, so no `catch (e: Exception)` is needed.
- **Definition of done for each task:** the named tests pass, and `./gradlew build detekt` is green. B6 and B7 additionally need `./gradlew :featureflag-ui:connectedDebugAndroidTest` green on `Pixel_7` and the screenshots their task names pulled and read by the controller.
- **Commits:** imperative sentence case, no type prefix, no trailing period, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

The overview's items this plan owns, and where each is pinned:

1. **(Overview 3) A write that the bridge refuses.** The edit must not look applied, and the user must see why.
   - B6 `aFailedWriteIsShownInTheSnackbar`: a store over a source whose writes throw `FlagBridgeException.Rejected`; flipping a toggle shows "The host rejected the change: the host is read-only" in the shell's snackbar, and the switch stays off.
   - B1 `aFailedWriteRevertsTheFieldAndIsReported`: a text field whose write fails puts the value in effect back and reports the exception.
   - B3 `duplicatingInAKeyedListIsRefusedAndReported`: the store's `FlagRecordEditingException` reaches the failure callback (overview deviation 6), and the list is unchanged.
2. **(Overview 4) Text the editor cannot read.** B1 `anUnreadableEditRevertsAndWarns`: `3,5` in a decimal field is refused; the field shows `1.0` again with the warning ("Not a valid value for this flag"), nothing is written, and a later valid edit clears the warning. B3 `anUneditableElementIsRefusedAndWarns` does the same for a list element.
3. **(Overview 5, the UI half) Leaving the Signals tab during a countdown.** B5 `leavingTheTabCancelsThePendingSend` (the send coroutine is cancelled and nothing is recorded) and B6 `leavingTheSignalsTabCancelsItsCountdown` (switching tabs in the real shell does it).
4. **(Overview 2 and Carried from M2) A failed refresh is a banner, not a crash.** B6 `theLastFailureIsABannerWithTryAgain`.
5. **(Overview 1, the UI half) A stored value the schema no longer accepts.** B4 `aStoredValueTheSchemaRejectsIsNotListedAsAnOverride`.

Three UI-specific conditions this plan judges most likely to bite:

6. **A list that does not notice the store changed.** Strong skipping memoises a `LazyColumn` content lambda whose captures did not change, and a store read is not snapshot state, so an item can show a stale count forever. Every screen computes its item data in the composable body. Pinned by B2 `aGroupBadgeUpdatesWhileTheBrowserIsShown` (a write after the browser is shown makes the "1" badge appear) and B4 `theArrowPutsOneFlagBack`.
7. **A focused field losing the user's draft.** An outside change (the host, another process) must not overwrite what is being typed, but must reach a field nobody is typing in. Pinned by B1 `anOutsideChangeDoesNotOverwriteAFocusedDraft` and `anOutsideChangeUpdatesAFieldThatIsNotFocused`.
8. **Navigation state lost on recreation or tab switch.** Pinned by B6 `aRouteSurvivesRecreation` (`StateRestorationTester`) and `eachTabKeepsItsOwnBackStack`.

---

## Seams this plan relies on (from M3a)

M3a runs first. This plan uses exactly these contract signatures, quoted from the overview:

```kotlin
// featureflag-core (A1)
public object FlagValueText {
    public fun format(box: FlagValueBox): String
    public fun parse(text: String, type: FlagValueType): FlagValueBox?
    public fun empty(type: FlagValueType, now: Instant = Instant.now()): FlagValueBox
}
public object FlagRecordList {
    public fun decode(box: FlagValueBox, shape: List<FlagRecordField>): List<Map<String, FlagValueBox>>?
    public fun encode(records: List<Map<String, FlagValueBox>>): FlagValueBox.String
    public fun duplicateKey(box: FlagValueBox, shape: List<FlagRecordField>): FlagValueBox?
    public fun emptyRecord(shape: List<FlagRecordField>, alongside: List<Map<String, FlagValueBox>> = emptyList(), now: Instant = Instant.now()): Map<String, FlagValueBox>
}

// com.andyyhope.featureflag.ui.editing (A2, A3)
public sealed interface FlagEditorKind { Toggle, Integer, Decimal, Text, Picker(cases), Date, Url, Data, List(element), Records(fields), Json; val wantsWrappingEditor: Boolean }
public val FlagSchema.Entry.editorKind: FlagEditorKind
public data class FlagTreeNode(val path: List<String>, val title: String?, val flags: List<FlagSchema.Entry>, val groups: List<FlagTreeNode>) { val id: String; val allFlags: List<FlagSchema.Entry> }
public data class FlagSection(val path: List<String>, val title: String?, val pathDescription: String, val entries: List<FlagSchema.Entry>) { val id: String }
public class FlagRecordEditingException(public val field: String, public val value: FlagValueBox) : RuntimeException()
public class FlagEditingStore(schema: FlagSchema, source: MutableFlagValueSource, writeDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    val schema: FlagSchema; val revision: StateFlow<Long>; val tree: FlagTreeNode
    fun sections(query: String = ""): List<FlagSection>; fun entry(key: FlagKey): FlagSchema.Entry?
    fun value(entry): FlagValueBox; fun isOverridden(entry): Boolean; val overriddenKeys: List<FlagKey>; fun overriddenCount(node: FlagTreeNode): Int
    suspend fun setValue(entry, box); suspend fun reset(entry); suspend fun resetAll()
    fun exportJson(): String; fun qrCode(): String; val copyableQrCode: String?; suspend fun import(text: String)
    fun records(entry): List<Map<String, FlagValueBox>>?; suspend fun setRecords(entry, records)
}

// com.andyyhope.featureflag.ui.companion (A4), all internal
internal sealed interface FlagCompanionState { Connecting; Ready(store: FlagEditingStore, failure: FlagBridgeException?); Unavailable(message: String) }
internal class FlagCompanionSession(…) { val state: StateFlow<FlagCompanionState>; fun retry(); fun onForeground(); fun refresh() }
internal class FlagCompanionViewModel(application: Application, hostPackage: String, timeout: Duration) : AndroidViewModel(application)

// com.andyyhope.featureflag.ui.signals (A6)
public class FlagSignalGroup(title: String, signals: List<FlagSignal>, display: FlagSignalGroupDisplay = Automatic) { val title; val signals; val id: String; internal val isNested: Boolean }
internal fun List<FlagSignalGroup>.duplicateSignalName(): String?
public enum class FlagSignalDelay(val duration: Duration, val label: String) { Instant, Three, Five, Ten }
internal data class FlagSignalCountdown(val start: Instant, val duration: Duration) { fun progress(at: Instant): Float; val end: Instant }
internal data class FlagSignalAttempt(val signal: FlagSignal, val handled: Boolean, val at: Instant) { val outcome: String }
internal class FlagSignalsModel(send: suspend (FlagSignal, Duration) -> Boolean, scope: CoroutineScope, clock: () -> Instant = Instant::now) {
    val delay: MutableStateFlow<FlagSignalDelay>; val pending: StateFlow<FlagSignal?>; val countdown: StateFlow<FlagSignalCountdown?>
    val inFlight: StateFlow<FlagSignal?>; val history: StateFlow<List<FlagSignalAttempt>>
    fun isDisabled(signal: FlagSignal): Boolean; fun tapped(signal: FlagSignal); fun cancelPending()
}
```

Where the contract is silent, this plan assumes the following. If M3a chose differently, fix the call site and keep the asserted behaviour.

- **Build (A2).**
  - `featureflag-ui/build.gradle.kts` applies `featureflag.android-library` then `featureflag.compose`, declares the overview's runtime dependencies, and has `testImplementation(platform(libs.androidx.compose.bom))`, `testImplementation(libs.androidx.compose.ui.test.junit4)` and `testImplementation(libs.androidx.compose.ui.test.manifest)` (the overview's "Compose tests use `createComposeRule()` … with `ui-test-manifest` on the test classpath"). Its `dependencies {` line appears exactly once.
  - The catalog uses yewki's alias names: `libs.androidx.compose.bom`, `libs.androidx.compose.ui.test.junit4`, `libs.androidx.compose.ui.test.manifest`.
  - `featureflag-ui/src/main/AndroidManifest.xml` is the overview's "empty" manifest (no `<application>` element). B4 replaces it whole.
  - `featureflag-ui/src/test/resources/robolectric.properties` holds `qualifiers=w411dp-h891dp`.
- **Store (A3).**
  - `revision` changes after every successful write through the store, so a screen recomposes without `observeIn` in tests.
  - A failed write throws what the source throws and leaves `value(entry)` unchanged.
  - `FlagRecordEditingException.message` is the contract's message.
  - `records(entry)` returns a nested record-list field as the `FlagValueBox.String` holding its JSON array (string-in-string, as iOS).
- **Session and ViewModel (A4).** `FlagCompanionViewModel` exposes `internal val session: FlagCompanionSession`, started in its `init`, and `internal suspend fun send(signal: FlagSignal, delay: Duration): Boolean` (the bridge client's `send`). The contract names the class but not these members; see "Contract issues found".
- **Signals (A6).** `FlagSignalCountdown.duration` and `FlagSignalDelay.duration` are `kotlin.time.Duration`. With a delay, `FlagSignalsModel` sets `pending` and `countdown` (start = its clock) before it calls `send`, and clears both when `send` returns or the job is cancelled.

## Files this plan creates or changes

```
featureflag-ui/
  build.gradle.kts                                                     (B6: androidTest Compose dependencies)
  src/main/AndroidManifest.xml                                         (B4: FlagFileProvider; B7: notification permissions)
  src/main/res/xml/featureflag_file_paths.xml                          (B4)
  src/main/res/drawable/featureflag_ic_signal.xml                      (B7)
  src/main/kotlin/com/andyyhope/featureflag/ui/
    FlagStrings.kt                                                     (B1, every string)
    FlagIcons.kt                                                       (B1)
    FlagCompanion.kt                                                   (B6; B7 passes the timeout on)
    share/FlagClipboard.kt                                             (B1)
    share/FlagFileProvider.kt                                          (B4)
    share/FlagQrImage.kt                                               (B4)
    signals/FlagSignalNotifier.kt                                      (B7)
    screens/FlagWriter.kt                                              (B1)
    screens/FlagRoute.kt                                               (B1)
    screens/FlagSectionHeader.kt                                       (B1)
    screens/FlagTextField.kt                                           (B1)
    screens/FlagControls.kt                                            (B1)
    screens/FlagRow.kt                                                 (B1)
    screens/BrowserScreen.kt, GroupScreen.kt, DetailScreen.kt          (B2)
    screens/ListEditorScreen.kt, RecordsScreen.kt, RecordScreen.kt     (B3)
    screens/FlagSheet.kt, OverridesScreen.kt, ImportSheet.kt, QrSheet.kt   (B4)
    screens/SignalsScreen.kt                                           (B5)
    screens/StatusScreens.kt, CompanionShell.kt, TabNavigation.kt      (B6; B7 modifies the last two)
    screens/SignalCountdownNotification.kt                             (B7)
  src/test/kotlin/com/andyyhope/featureflag/ui/
    FlagIconsTest.kt                                                   (B1)
    FlagCompanionTabTest.kt                                            (B6)
    share/FlagClipboardTest.kt                                         (B1)
    share/FlagQrImageTest.kt                                           (B4)
    signals/FlagSignalNotifierTest.kt                                  (B7)
    screens/ScreenTestSupport.kt                                       (B1)
    screens/FlagRouteTest.kt, FlagRowTest.kt                           (B1)
    screens/BrowserScreenTest.kt, DetailScreenTest.kt                  (B2)
    screens/ListEditorScreenTest.kt, RecordsScreenTest.kt, RecordScreenTest.kt   (B3)
    screens/ImportFailureTextTest.kt, OverridesScreenTest.kt, ImportSheetTest.kt, QrSheetTest.kt   (B4)
    screens/SignalsScreenTest.kt                                       (B5)
    screens/CompanionShellTest.kt                                      (B6)
    screens/SignalCountdownNotificationTest.kt                         (B7)
  src/androidTest/kotlin/com/andyyhope/featureflag/ui/
    FlagCompanionSmokeTest.kt                                          (B6)
    FlagSignalNotifierDeviceTest.kt                                    (B7)
```

---
### Task B1: Shared pieces and `FlagRow`

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagStrings.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagIcons.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/share/FlagClipboard.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagWriter.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagRoute.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagSectionHeader.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagTextField.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagControls.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagRow.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ScreenTestSupport.kt` (fixtures and helpers every B task's tests use)
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/FlagRouteTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/FlagRowTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/share/FlagClipboardTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/FlagIconsTest.kt`

**Interfaces:**
- Consumes: A1 `FlagValueText`, `FlagRecordList.duplicateKey(box, shape)`, `FlagRecordList.encode` (fixtures); A2 `FlagEditorKind`, `editorKind`; A3 `FlagEditingStore` (`revision`, `value`, `isOverridden`, `records`, `setValue`, `reset`); core `FlagSchema`, `FlagRecordField`, `SnapshotSource`, `MutableFlagValueSource`; `FlagBridgeException.Rejected` (tests).
- Produces (all `internal`):

```kotlin
// com.andyyhope.featureflag.ui
internal object FlagStrings                                  // every user-visible string in M3b
internal object FlagIcons { Tune, FlagOutlined, FlagFilled, LayersOutlined, LayersFilled, QrCode, ContentCopy, Undo, Download }
// .ui.share
internal object FlagClipboard { fun copy(context: Context, text: String); fun copyImage(context: Context, uri: Uri) }
// .ui.screens
internal class FlagWriter(scope: CoroutineScope, onFailure: (Exception) -> Unit) { fun write(onFailed: () -> Unit = {}, block: suspend () -> Unit): Job }
internal val LocalFlagWriter: ProvidableCompositionLocal<FlagWriter>
@Composable internal fun ProvideFlagWriter(onFailure: (Exception) -> Unit, content: @Composable () -> Unit)
@Composable internal fun FlagEditingStore.observeRevision(): Long
internal fun TextStyle.monospaced(): TextStyle
internal sealed interface FlagRoute { Group(path), ListEditor(key), Records(key, trail), Record(key, trail, index), SignalGroup(title) }
internal data class RecordStep(val index: Int, val field: String)
internal object FlagRouteCodec { fun encode(route: FlagRoute): String; fun decode(text: String): FlagRoute }
@Composable internal fun FlagSectionHeader(text: String, modifier: Modifier = Modifier)
@Composable internal fun FlagSectionFooter(text: String, modifier: Modifier = Modifier)
@Composable internal fun FlagContentUnavailable(title: String, message: String, modifier: Modifier = Modifier)
@Composable internal fun FlagPill(text: String, modifier: Modifier = Modifier)
@Composable internal fun FlagNavigationRow(onClick: () -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = …, content: @Composable RowScope.() -> Unit)
@Composable internal fun FlagActionRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, destructive: Boolean = false)
@Composable internal fun FlagTopBar(title: String, onBack: (() -> Unit)? = null, navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {})
@Composable internal fun FlagTextField(value: String, onCommit: (String) -> Boolean, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, multiline: Boolean = false, revertKey: Int = 0)
@Composable internal fun FlagCopyButton(text: () -> String, idleDescription: String, modifier: Modifier = Modifier)
internal const val COPIED_FEEDBACK_MILLIS: Long
@Composable internal fun FlagPicker(selected: FlagValueBox, options: List<FlagValueBox>, onSelect: (FlagValueBox) -> Unit, modifier: Modifier = Modifier)
@Composable internal fun FlagDateTimeButton(instant: Instant, onPick: (Instant) -> Unit, modifier: Modifier = Modifier)
@Composable internal fun FlagRow(store: FlagEditingStore, entry: FlagSchema.Entry, onOpen: (FlagRoute) -> Unit, modifier: Modifier = Modifier)
internal fun listSummary(value: FlagValueBox, element: FlagValueType): String
internal fun recordSummary(store: FlagEditingStore, entry: FlagSchema.Entry, value: FlagValueBox): String
internal fun keyboardTypeFor(type: FlagValueType): KeyboardType
```

**Behaviour this task fixes (from `FlagRowView.swift`):**
- **Header:** description (body) over the key (monospaced caption). When overridden, the "Overridden" pill and a reset arrow (`contentDescription` "Reset to the app's default").
- **Controls:** toggle → `Switch` (its `contentDescription` is the description); picker → a dropdown of the cases' value text; date → a button showing the local date and time, opening a `DatePickerDialog`, then a `TimePicker` in an `AlertDialog` (seconds are kept); integer and decimal → number keyboards; url → URI keyboard; text → text keyboard; data and json → a wrapping code block with a copy button ("Copy value", then "Copied" for 1.4 s); list → a navigation row "N typeName(s)" (or "Edit") with a middle-truncated JSON preview; records → a navigation row "N record(s)", "Unreadable" or "Two records share a <key>", with the field names.
- **`FlagTextField`:** a local draft; commits on IME Done (by clearing focus) and when focus leaves; a parse failure reverts the draft and shows a warning icon (`contentDescription` "Not a valid value for this flag") until a later successful commit; while focused, an outside change does not overwrite the draft; unfocused, it does. A commit whose draft equals the value in effect writes nothing. A write that fails bumps `revertKey`, which puts the value back.

- [ ] **Step 1: Write the test support and the failing tests**

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ScreenTestSupport.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.net.URI
import java.time.Instant

/**
 * Hand-built schemas for the screen tests. They mirror the iOS fixtures (`CompanionFlags`,
 * `ArrayEditorFlags`, the record fixtures) by key, type, default, group and description.
 */
internal object ScreenFixtures {

    private val generatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    fun entry(
        key: String,
        type: FlagValueType,
        default: FlagValueBox,
        description: String = key,
        cases: List<FlagValueBox>? = null,
        remoteKey: String? = null,
        shape: List<FlagRecordField>? = null,
    ): FlagSchema.Entry = FlagSchema.Entry(
        key = FlagKey(key),
        propertyPath = key.split("."),
        description = description,
        valueType = type,
        defaultValue = default,
        cases = cases,
        remoteKey = remoteKey,
        recordShape = shape,
    )

    /** iOS CompanionFlags: two root flags, a Checkout group with five, and Checkout › Express with two. */
    val companion: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        applicationName = "Demo",
        description = "What the checkout team can turn on",
        flags = listOf(
            entry("new-onboarding", FlagValueType.Bool, FlagValueBox.Bool(false), "New onboarding experience"),
            entry("max-items", FlagValueType.Int, FlagValueBox.Int(10), "Maximum items"),
            entry("checkout.apple-pay", FlagValueType.Bool, FlagValueBox.Bool(false), "Apple Pay"),
            entry("checkout.ratio", FlagValueType.Double, FlagValueBox.Double(1.0), "Ratio"),
            entry(
                "checkout.tier",
                FlagValueType.String,
                FlagValueBox.String("free"),
                "Tier",
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry("checkout.launched-at", FlagValueType.Date, FlagValueBox.Date(Instant.EPOCH), "Launched at"),
            entry(
                "checkout.endpoint",
                FlagValueType.Url,
                FlagValueBox.Url(URI("https://example.com")),
                "Endpoint",
                remoteKey = "checkout_endpoint",
            ),
            entry("checkout.express.label", FlagValueType.String, FlagValueBox.String(""), "Label"),
            entry(
                "checkout.express.tags",
                FlagValueType.Array(FlagValueType.String),
                FlagValueBox.Array(emptyList()),
                "Tags",
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
        ),
    )

    /** iOS Endpoint record: no key field. */
    val endpointFields: List<FlagRecordField> = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String),
        FlagRecordField(name = "url", type = FlagValueType.Url),
        FlagRecordField(name = "enabled", type = FlagValueType.Bool),
        FlagRecordField(name = "weight", type = FlagValueType.Int),
        FlagRecordField(
            name = "tier",
            type = FlagValueType.String,
            cases = listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")),
        ),
    )

    /** iOS Route record: keyed by name, with a nested list of hops keyed by host. */
    val routeFields: List<FlagRecordField> = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String, isKey = true),
        FlagRecordField(
            name = "hops",
            type = FlagValueType.String,
            defaultValue = FlagValueBox.String("[]"),
            fields = listOf(FlagRecordField(name = "host", type = FlagValueType.String, isKey = true)),
        ),
    )

    fun endpoint(name: String, weight: Long = 10, tier: String = "primary"): Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String(name),
        "url" to FlagValueBox.Url(URI("https://$name.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(weight),
        "tier" to FlagValueBox.String(tier),
    )

    /** Lists, blocks and record lists (iOS ArrayEditorFlags and the record fixtures). */
    val editors: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry(
                "markets",
                FlagValueType.Array(FlagValueType.String),
                FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
                "Markets",
            ),
            entry(
                "counts",
                FlagValueType.Array(FlagValueType.Int),
                FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))),
                "Counts",
            ),
            entry("logo", FlagValueType.Data, FlagValueBox.Data(byteArrayOf(1, 2)), "Logo"),
            entry("limits", FlagValueType.Dictionary(FlagValueType.Int), FlagValueBox.Dictionary(emptyMap()), "Limits"),
            entry(
                "endpoints",
                FlagValueType.String,
                FlagRecordList.encode(listOf(endpoint("production"))),
                "Endpoints",
                shape = endpointFields,
            ),
            entry("routes", FlagValueType.String, FlagValueBox.String("[]"), "Routes", shape = routeFields),
        ),
    )

    /** One single-element list per scalar element type (iOS testAnElementRowBuildsForEveryScalar). */
    val elements: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("strings", FlagValueType.Array(FlagValueType.String), FlagValueBox.Array(listOf(FlagValueBox.String("a")))),
            entry("ints", FlagValueType.Array(FlagValueType.Int), FlagValueBox.Array(listOf(FlagValueBox.Int(1)))),
            entry("doubles", FlagValueType.Array(FlagValueType.Double), FlagValueBox.Array(listOf(FlagValueBox.Double(1.5)))),
            entry("bools", FlagValueType.Array(FlagValueType.Bool), FlagValueBox.Array(listOf(FlagValueBox.Bool(true)))),
            entry("dates", FlagValueType.Array(FlagValueType.Date), FlagValueBox.Array(listOf(FlagValueBox.Date(Instant.EPOCH)))),
            entry(
                "urls",
                FlagValueType.Array(FlagValueType.Url),
                FlagValueBox.Array(listOf(FlagValueBox.Url(URI("https://a.example")))),
            ),
            entry("blobs", FlagValueType.Array(FlagValueType.Data), FlagValueBox.Array(listOf(FlagValueBox.Data(byteArrayOf(1))))),
        ),
    )

    val empty: FlagSchema = FlagSchema(generatedAt = generatedAt, flags = emptyList())
}

/** A store whose writes run inline on the Robolectric main thread. */
internal fun testStore(
    schema: FlagSchema,
    values: Map<FlagKey, FlagValueBox> = emptyMap(),
): FlagEditingStore = FlagEditingStore(schema, SnapshotSource(values = values), writeDispatcher = Dispatchers.Main.immediate)

internal fun FlagEditingStore.requireEntry(key: String): FlagSchema.Entry =
    checkNotNull(entry(FlagKey(key))) { "The schema has no flag '$key'." }

/** Shows [content] the way the shell does: in the caller's theme, with a writer that reports to [onFailure]. */
internal fun ComposeContentTestRule.setFlagContent(
    onFailure: (Exception) -> Unit = { throw it },
    content: @Composable () -> Unit,
) {
    setContent { MaterialTheme { ProvideFlagWriter(onFailure = onFailure) { content() } } }
}

/** Brings the lazy-list item holding [text] into composition. */
internal fun ComposeContentTestRule.scrollTo(text: String) {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
}

internal fun clipboardText(): String? = ApplicationProvider.getApplicationContext<Context>()
    .getSystemService(ClipboardManager::class.java)
    ?.primaryClip
    ?.getItemAt(0)
    ?.text
    ?.toString()

/** A source every write to fails, as a host that refuses or has died would. Reads find nothing. */
internal class FailingSource(private val failure: () -> RuntimeException) : MutableFlagValueSource {
    override val sourceName: String = "Failing"
    override val changes: Flow<FlagChange> = emptyFlow()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        throw failure()
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/FlagRouteTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import org.junit.Test
import kotlin.test.assertEquals

class FlagRouteTest {

    @Test
    fun everyRouteSurvivesItsSavedForm() {
        val routes = listOf(
            FlagRoute.Group(listOf("checkout", "express")),
            FlagRoute.Group(emptyList()),
            FlagRoute.ListEditor("markets"),
            FlagRoute.Records("routes"),
            FlagRoute.Records("routes", listOf(RecordStep(0, "hops"), RecordStep(2, "legs"))),
            FlagRoute.Record("routes", listOf(RecordStep(1, "hops")), 3),
            FlagRoute.SignalGroup("Caches"),
        )

        routes.forEach { route -> assertEquals(route, FlagRouteCodec.decode(FlagRouteCodec.encode(route))) }
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/share/FlagClipboardTest.kt` (iOS `FlagPasteboardTests`, an [X] test portable on Android)
```kotlin
package com.andyyhope.featureflag.ui.share

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagClipboardTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))

    private fun current(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun copyingPutsTheValueOnTheClipboard() {
        FlagClipboard.copy(context, """{"a":1,"b":["x","y"]}""")

        assertEquals("""{"a":1,"b":["x","y"]}""", current())
    }

    @Test
    fun copyingReplacesWhateverWasThereBefore() {
        FlagClipboard.copy(context, "first")
        FlagClipboard.copy(context, "second")

        assertEquals("second", current())
    }

    @Test
    fun copyingAnEmptyStringIsNotACrash() {
        FlagClipboard.copy(context, "")

        assertEquals("", current())
    }

    @Test
    fun copyingAnImagePutsItsUriOnTheClipboard() {
        val uri = Uri.parse("content://${context.packageName}.featureflag.files/featureflag/code.png")

        FlagClipboard.copyImage(context, uri)

        val clip = checkNotNull(clipboard.primaryClip)
        assertEquals(uri, clip.getItemAt(0).uri)
        assertTrue(clip.description.hasMimeType("image/png"))
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/FlagIconsTest.kt`
```kotlin
package com.andyyhope.featureflag.ui

import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagIconsTest {

    @Test
    fun everyIconIsATwentyFourDpSquareWithPathsThatParse() {
        val icons = listOf(
            FlagIcons.Tune,
            FlagIcons.FlagOutlined,
            FlagIcons.FlagFilled,
            FlagIcons.LayersOutlined,
            FlagIcons.LayersFilled,
            FlagIcons.QrCode,
            FlagIcons.ContentCopy,
            FlagIcons.Undo,
            FlagIcons.Download,
        )

        icons.forEach { icon ->
            assertEquals(24.dp, icon.defaultWidth, icon.name)
            assertEquals(24.dp, icon.defaultHeight, icon.name)
            val paths = (0 until icon.root.size).map { icon.root[it] }.filterIsInstance<VectorPath>()
            assertTrue(paths.isNotEmpty() && paths.all { it.pathData.isNotEmpty() }, icon.name)
        }
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/FlagRowTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()
    private val failures = mutableListOf<Exception>()
    private lateinit var focusManager: FocusManager

    private fun show(store: FlagEditingStore, key: String) {
        val entry = store.requireEntry(key)
        composeRule.setFlagContent(onFailure = { failures += it }) {
            focusManager = LocalFocusManager.current
            FlagRow(store = store, entry = entry, onOpen = { opened += it })
        }
    }

    private fun edit(text: String) {
        val field = composeRule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextReplacement(text)
        field.performImeAction()
        composeRule.waitForIdle()
    }

    // Toggles, pickers and dates

    @Test
    fun aToggleWritesTheMomentItIsFlipped() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "new-onboarding")

        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()

        composeRule.onNodeWithContentDescription("New onboarding experience").assertIsOn()
        assertEquals(FlagValueBox.Bool(true), store.value(store.requireEntry("new-onboarding")))
    }

    @Test
    fun anOverriddenFlagShowsThePillAndItsArrowResetsIt() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store, "max-items")

        composeRule.onNodeWithText("Overridden").assertExists()
        composeRule.onNodeWithContentDescription("Reset to the app's default").performClick()

        composeRule.onNodeWithText("Overridden").assertDoesNotExist()
        assertFalse(store.isOverridden(store.requireEntry("max-items")))
    }

    @Test
    fun aPickerOffersEveryCaseAndWritesTheChoice() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.tier")

        composeRule.onNodeWithText("free").performClick()
        composeRule.onNodeWithText("pro").performClick()

        composeRule.onNodeWithText("pro").assertExists()
        assertEquals(FlagValueBox.String("pro"), store.value(store.requireEntry("checkout.tier")))
    }

    @Test
    fun aDateRowWritesThePickedDateAndTime() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.launched-at")
        val shown = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(Instant.EPOCH.atZone(ZoneId.systemDefault()))

        composeRule.onNode(hasText(shown) and hasClickAction()).performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitForIdle()

        val entry = store.requireEntry("checkout.launched-at")
        assertTrue(store.isOverridden(entry))
        assertEquals(FlagValueBox.Date(Instant.EPOCH), store.value(entry))
    }

    // Text fields

    @Test
    fun aNumberCommitsOnDone() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")

        edit("42")

        assertEquals(FlagValueBox.Int(42), store.value(store.requireEntry("max-items")))
    }

    @Test
    fun aFieldCommitsWhenFocusLeavesIt() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")
        val field = composeRule.onNode(hasSetTextAction())

        field.performClick()
        field.performTextReplacement("7")
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()

        assertEquals(FlagValueBox.Int(7), store.value(store.requireEntry("max-items")))
    }

    @Test
    fun anUnreadableEditRevertsAndWarns() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.ratio")

        edit("3,5")

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertExists()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("1.0")
        assertFalse(store.isOverridden(store.requireEntry("checkout.ratio")))

        edit("2.5")

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertDoesNotExist()
        assertEquals(FlagValueBox.Double(2.5), store.value(store.requireEntry("checkout.ratio")))
    }

    @Test
    fun anOutsideChangeDoesNotOverwriteAFocusedDraft() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")
        val field = composeRule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextReplacement("7")

        runBlocking { store.setValue(store.requireEntry("max-items"), FlagValueBox.Int(99)) }

        composeRule.onNode(hasSetTextAction()).assertTextEquals("7")
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()
        assertEquals(FlagValueBox.Int(7), store.value(store.requireEntry("max-items")))
    }

    @Test
    fun anOutsideChangeUpdatesAFieldThatIsNotFocused() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "max-items")

        runBlocking { store.setValue(store.requireEntry("max-items"), FlagValueBox.Int(99)) }

        composeRule.onNode(hasSetTextAction()).assertTextEquals("99")
    }

    @Test
    fun aFailedWriteRevertsTheFieldAndIsReported() {
        val source = FailingSource { FlagBridgeException.Rejected("the host is read-only") }
        val store = FlagEditingStore(ScreenFixtures.companion, source, writeDispatcher = Dispatchers.Main.immediate)
        show(store, "max-items")

        edit("5")

        composeRule.onNode(hasSetTextAction()).assertTextEquals("10")
        assertIs<FlagBridgeException.Rejected>(failures.single())
    }

    // Blocks

    @Test
    fun aDataValueIsABlockWithACopyButton() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "logo")
        composeRule.onNode(hasSetTextAction()).assertTextEquals("AQI=")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("Copy value").performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithContentDescription("Copied").assertExists()
        assertEquals("AQI=", clipboardText())
        composeRule.mainClock.advanceTimeBy(COPIED_FEEDBACK_MILLIS + 100)
        composeRule.onNodeWithContentDescription("Copy value").assertExists()
    }

    // Lists and records

    @Test
    fun theFlagRowStillBuildsForAnArray() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        composeRule.onNodeWithText("""["AU","NZ"]""").assertExists()
        composeRule.onNodeWithText("2 strings").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.ListEditor("markets")), opened)
    }

    @Test
    fun aRecordListRowCountsItsRecordsAndNamesItsFields() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "endpoints")

        composeRule.onNodeWithText("name, url, enabled, weight, tier").assertExists()
        composeRule.onNodeWithText("1 record").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Records("endpoints")), opened)
    }

    @Test
    fun aRecordListThatCannotBeReadSaysWhy() {
        val store = testStore(
            ScreenFixtures.editors,
            mapOf(
                FlagKey("routes") to FlagValueBox.String("""[{"name":"a","hops":"[]"},{"name":"a","hops":"[]"}]"""),
                FlagKey("endpoints") to FlagValueBox.String("{ not json"),
            ),
        )
        composeRule.setFlagContent {
            Column {
                FlagRow(store = store, entry = store.requireEntry("routes"), onOpen = {})
                FlagRow(store = store, entry = store.requireEntry("endpoints"), onOpen = {})
            }
        }

        composeRule.onNodeWithText("Two records share a name").assertExists()
        composeRule.onNodeWithText("Unreadable").assertExists()
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest
```

Expected: `BUILD FAILED` in `:featureflag-ui:compileDebugUnitTestKotlin`, with `Unresolved reference 'ProvideFlagWriter'`, `'FlagRoute'`, `'FlagRouteCodec'`, `'FlagRow'`, `'FlagClipboard'`, `'FlagIcons'` and `'COPIED_FEEDBACK_MILLIS'`.

- [ ] **Step 3: Implement**

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagStrings.kt`
```kotlin
package com.andyyhope.featureflag.ui

/**
 * Every word the companion shows. English only, as on iOS, and verbatim from the iOS companion
 * (docs/superpowers/notes/2026-10-14-m3-ios-views.md) unless the M3 contract gives Android prose.
 * The few strings iOS has no equivalent for are marked "Android".
 */
internal object FlagStrings {

    // Tabs
    const val OVERRIDES = "Overrides"
    const val FLAGS = "Flags"
    const val SIGNALS = "Signals"

    // Shell and status
    const val NO_FLAGS_YET = "No flags yet"
    const val TRY_AGAIN = "Try again"
    const val NO_TABS = "No tabs"
    const val NO_TABS_MESSAGE =
        "This companion was given an empty tab list, so there is nothing to show. " +
            "Pass at least one, or omit the argument for the default two."

    fun duplicateTabId(id: String): String =
        "A companion cannot have two tabs with the id '$id'. Give one of them a different id, or drop it."

    fun duplicateSignal(name: String): String =
        "More than one signal group defines '$name'. A signal travels as its raw value, " +
            "so it has to be unique across every group."

    // Android: actions iOS gets from the system (navigation, edit mode, swipe actions, Live Activities)
    const val BACK = "Back"
    const val ACTIONS = "Actions"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val DELETE = "Delete"
    const val NOTIFICATION_CHANNEL = "Signal countdowns"
    const val SENDING_IN = "Sending in"

    // Browser and groups
    const val FEATURE_FLAGS = "Feature Flags"
    const val SEARCH_FLAGS = "Search flags"
    const val GROUPS = "Groups"
    const val NO_FLAGS = "No flags"
    const val NO_FLAGS_MESSAGE = "The app has not published any flags yet."
    const val NO_MATCHES = "No matches"

    fun noMatches(query: String): String = "No flag matches “$query”."

    fun flagCount(count: Int): String = "$count flag${plural(count)}"

    // Rows
    const val OVERRIDDEN = "Overridden"
    const val RESET_TO_DEFAULT = "Reset to the app's default"
    const val NOT_A_VALID_VALUE = "Not a valid value for this flag"
    const val COPY_VALUE = "Copy value"
    const val COPIED = "Copied"
    const val EDIT = "Edit"
    const val UNREADABLE = "Unreadable"
    const val OK = "OK"
    const val CANCEL = "Cancel"

    fun listSummary(count: Int, typeName: String): String = "$count $typeName${plural(count)}"

    fun recordCount(count: Int): String = "$count record${plural(count)}"

    fun twoRecordsShareA(keyName: String): String = "Two records share a $keyName"

    // List editor
    const val ADD = "Add"

    fun itemCount(count: Int): String = "$count item${plural(count)}"

    fun listFooter(typeName: String): String =
        "Every element is a $typeName. Removing them all leaves an empty array, which is a value like any " +
            "other — reset the flag if you want the app's default back."

    // Records
    const val DUPLICATE = "Duplicate"
    const val UNTITLED = "Untitled"
    const val RECORD = "Record"
    const val MISSING_VALUE = "—"
    const val NOT_A_LIST_OF_RECORDS = "This value is not a list of records."
    const val UNREADABLE_FOOTER =
        "The app cannot read it either, so it is using its default. Reset the flag to edit it here."

    fun recordsFooter(names: List<String>): String =
        "Each record has ${names.size} field${plural(names.size)}: ${names.joinToString(", ")}."

    fun duplicateKeyReason(keyName: String, value: String): String =
        "Two records share the same $keyName, $value — it is what tells one record from another, so they cannot."

    // Detail
    const val DETAIL_FOOTER =
        "Anything you set here stays set. A value set by hand outranks whatever a backend sends, until you reset it."
    const val DETAILS = "Details"
    const val KEY = "Key"
    const val DEFAULT = "Default"
    const val REMOTELY_OVERRIDABLE = "Remotely overridable"
    const val NO = "No"
    const val NO_REMOTE_KEY_FOOTER =
        "No remote key, so nothing a payload contains can change this flag. For a flag that decides which " +
            "payload gets fetched, that is deliberate — otherwise the two drive each other."
    const val REMOTE_KEY_FOOTER = "This flag carries a remote key, so a payload can change it."
    const val NO_SUCH_FLAG_FOOTER =
        "Check the key as the schema publishes it — a nested flag is “checkout.apple-pay”, " +
            "not “applePay”."

    fun remoteKey(key: String): String = "Yes — $key"

    fun noSuchFlag(key: String): String = "This app publishes no flag called “$key”."

    // Overrides
    const val NOTHING_OVERRIDDEN = "Nothing overridden"
    const val NOTHING_OVERRIDDEN_MESSAGE = "Every flag is reporting the value the app was built with."
    const val OVERRIDES_FOOTER = "Tap the arrow to put a flag back to the app's default."
    const val JSON = "JSON"
    const val JSON_FOOTER =
        "Only overrides travel — the defaults are already in the app. This is what a QR code carries, compressed."
    const val COPY_JSON = "Copy JSON"
    const val SHOW_QR_CODE = "Show QR code"
    const val SHARE_JSON = "Share JSON"
    const val RESET_EVERY_OVERRIDE = "Reset every override"
    const val RESET_EVERY_OVERRIDE_TITLE = "Reset every override?"
    const val RESET_EVERY_OVERRIDE_MESSAGE = "Every flag goes back to the value the app was built with."
    const val RESET = "Reset"
    const val IMPORT_ELLIPSIS = "Import…"
    const val IMPORT_FOOTER =
        "Paste an exported document or a scanned code. Import is all or nothing: one unknown key or wrong " +
            "type rejects the whole thing."

    fun changedCount(count: Int): String = "$count changed"

    // Import sheet
    const val IMPORT = "Import"
    const val IMPORT_HELP = "Paste exported JSON, or the contents of a scanned flag code."
    const val NOT_A_FLAG_DOCUMENT = "That is not a flag document."

    fun notAFlagInThisApp(key: String): String = "$key: not a flag in this app"

    fun wrongType(key: String): String = "$key: wrong type"

    fun notOneOfItsChoices(key: String): String = "$key: not one of its choices"

    fun newerFormat(version: Long): String = "This document was written by a newer version (format $version)."

    // QR sheet
    const val SCAN_TO_COPY = "Scan to copy"
    const val DONE = "Done"
    const val COPY_IMAGE = "Copy image"
    const val IMAGE_COPIED = "Image copied"
    const val SAVE = "Save"
    const val COPY_CODE = "Copy code"
    const val CODE_COPIED = "Code copied"
    const val COULD_NOT_RENDER = "Could not render the code."

    /** iOS has no singular here. */
    fun qrCodeDescription(count: Int): String = "QR code containing $count overrides"

    /** iOS prints "(s)" literally. */
    fun overrideCount(count: Int): String = "$count override(s)"

    /** The view's wording, always plural, unlike core's FlagQRCodeException message. */
    fun qrTooLarge(count: Int, bytes: Int, limit: Int): String =
        "$count overrides need $bytes characters, and a QR code holds $limit. Reset a few, or export JSON instead."

    // Signals
    const val DELAY = "Delay"
    const val DELAY_FOOTER_INSTANT =
        "Sent the moment you tap. The app has to be running to hear it, and opening this one puts it in the background."
    const val DELAY_FOOTER_DELAYED =
        "Tap a signal, then switch to the app. It fires when the circle completes, by which time the app is in " +
            "front and listening."
    const val SIGNALS_FOOTER = "Signals carry no payload. State belongs in flags, which you can already edit."
    const val TAP_AGAIN_TO_CANCEL = "Tap again to cancel."
    const val TAKES_EFFECT_AFTER_A_RELAUNCH = "Takes effect after a relaunch"
    const val RECENT = "Recent"

    fun sendingIn(seconds: Long): String = "Sending in $seconds seconds. Tap to cancel."

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagIcons.kt`
```kotlin
package com.andyyhope.featureflag.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons material-icons-core lacks, drawn from Material Icons' path data
 * (https://github.com/google/material-design-icons, Apache License 2.0). Each is a 24 dp square on a
 * 24-unit viewport, filled black and tinted by `Icon`.
 */
internal object FlagIcons {

    /** The Overrides tab. Material's "tune" has a single form, so it serves selected and unselected alike. */
    val Tune: ImageVector by lazy {
        icon(
            "Filled.Tune",
            "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9z" +
                "M21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
        )
    }

    /** The Flags tab, unselected; also the status screens' symbol. */
    val FlagOutlined: ImageVector by lazy {
        icon("Outlined.Flag", "M12.36,6l0.4,2H18v6h-3.36l-0.4,-2H7V6H12.36M14,4H5v17h2v-7h5.6l0.4,2h7V6h-5.6L14,4L14,4z")
    }

    /** The Flags tab, selected. */
    val FlagFilled: ImageVector by lazy {
        icon("Filled.Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    }

    /** A Detail tab, unselected. */
    val LayersOutlined: ImageVector by lazy {
        icon(
            "Outlined.Layers",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z" +
                "M12,4.53L17.74,9 12,13.47 6.26,9 12,4.53z",
        )
    }

    /** A Detail tab, selected. */
    val LayersFilled: ImageVector by lazy {
        icon(
            "Filled.Layers",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
        )
    }

    /** "Show QR code". */
    val QrCode: ImageVector by lazy {
        icon(
            "Filled.QrCode",
            "M3,11h8V3H3V11zM5,5h4v4H5V5z",
            "M3,21h8v-8H3V21zM5,15h4v4H5V15z",
            "M13,3v8h8V3H13zM19,9h-4V5h4V9z",
            "M19,19h2v2h-2z",
            "M13,13h2v2h-2z",
            "M15,15h2v2h-2z",
            "M13,17h2v2h-2z",
            "M15,19h2v2h-2z",
            "M17,17h2v2h-2z",
            "M17,13h2v2h-2z",
            "M19,15h2v2h-2z",
        )
    }

    /** Every copy button. */
    val ContentCopy: ImageVector by lazy {
        icon(
            "Filled.ContentCopy",
            "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z",
        )
    }

    /** The reset arrow (iOS arrow.uturn.backward). */
    val Undo: ImageVector by lazy {
        icon(
            "Filled.Undo",
            "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 " +
                "3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
        )
    }

    /** "Import…" and the QR sheet's "Save" (iOS square.and.arrow.down). */
    val Download: ImageVector by lazy {
        icon("Filled.Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z")
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { path -> addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)) }
        }.build()
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/share/FlagClipboard.kt`
```kotlin
package com.andyyhope.featureflag.ui.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/** The system clipboard: plain text for values, JSON and codes; a content URI for the QR image. */
internal object FlagClipboard {

    private const val LABEL = "Feature flags"
    private const val PNG = "image/png"

    fun copy(context: Context, text: String) {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    /** A PNG the library's FlagFileProvider serves; whoever pastes it reads it through the URI. */
    fun copyImage(context: Context, uri: Uri) {
        clipboard(context).setPrimaryClip(ClipData(ClipDescription(LABEL, arrayOf(PNG)), ClipData.Item(uri)))
    }

    private fun clipboard(context: Context): ClipboardManager =
        checkNotNull(context.getSystemService(ClipboardManager::class.java)) { "This device has no clipboard." }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagWriter.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs store writes off the composition and reports what fails. A write suspends and can throw whatever
 * the source throws (for a bridge, FlagBridgeException), so no screen calls one directly.
 */
internal class FlagWriter(
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit,
) {

    /** Launches [block]. If it throws, [onFailed] runs first (so a field can put the value back), then the failure is reported. */
    fun write(onFailed: () -> Unit = {}, block: suspend () -> Unit): Job = scope.launch {
        val failure = runCatching { block() }.exceptionOrNull() ?: return@launch
        if (failure is CancellationException || failure !is Exception) throw failure
        onFailed()
        onFailure(failure)
    }
}

/** The screens' writer. The shell provides one that shows failures in its snackbar. */
internal val LocalFlagWriter = staticCompositionLocalOf<FlagWriter> {
    error("No FlagWriter is provided. Wrap the screen in ProvideFlagWriter.")
}

/** Provides a [FlagWriter] whose writes outlive the screen that started them. */
@Composable
internal fun ProvideFlagWriter(onFailure: (Exception) -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val latestOnFailure by rememberUpdatedState(onFailure)
    val writer = remember(scope) { FlagWriter(scope) { failure -> latestOnFailure(failure) } }
    CompositionLocalProvider(LocalFlagWriter provides writer, content = content)
}

/**
 * Reads the store's revision, so the caller recomposes whenever the host's values change. Every
 * composable that reads the store calls this first: a store read is not snapshot state.
 */
@Composable
internal fun FlagEditingStore.observeRevision(): Long = revision.collectAsState().value

/** Flag keys and values are read character by character, so they are set in a monospaced face. */
internal fun TextStyle.monospaced(): TextStyle = copy(fontFamily = FontFamily.Monospace)
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagRoute.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

/** A screen pushed onto a tab's back stack. Each saves as text, so a stack survives recreation. */
internal sealed interface FlagRoute {

    /** The group whose property path is [path]. */
    data class Group(val path: List<String>) : FlagRoute

    /** The list editor for the array flag [key]. */
    data class ListEditor(val key: String) : FlagRoute

    /** The records list at [trail] inside the record-list flag [key]: the flag's own list when [trail] is empty. */
    data class Records(val key: String, val trail: List<RecordStep> = emptyList()) : FlagRoute

    /** Record [index] of the list at [trail] inside the flag [key]. */
    data class Record(val key: String, val trail: List<RecordStep>, val index: Int) : FlagRoute

    /** A signal group laid out on its own screen. */
    data class SignalGroup(val title: String) : FlagRoute
}

/** One step into a nested records list: field [field] of the record at [index]. */
internal data class RecordStep(val index: Int, val field: String)

/** Routes as single strings, for rememberSaveable. */
internal object FlagRouteCodec {

    private const val SEPARATOR = "\u001F"
    private const val GROUP = "group"
    private const val LIST = "list"
    private const val RECORDS = "records"
    private const val RECORD = "record"
    private const val SIGNAL_GROUP = "signal-group"

    fun encode(route: FlagRoute): String = when (route) {
        is FlagRoute.Group -> listOf(GROUP) + route.path
        is FlagRoute.ListEditor -> listOf(LIST, route.key)
        is FlagRoute.Records -> listOf(RECORDS, route.key) + steps(route.trail)
        is FlagRoute.Record -> listOf(RECORD, route.key, route.index.toString()) + steps(route.trail)
        is FlagRoute.SignalGroup -> listOf(SIGNAL_GROUP, route.title)
    }.joinToString(SEPARATOR)

    fun decode(text: String): FlagRoute {
        val parts = text.split(SEPARATOR)
        return when (parts.first()) {
            GROUP -> FlagRoute.Group(parts.drop(1))
            LIST -> FlagRoute.ListEditor(parts[1])
            RECORDS -> FlagRoute.Records(parts[1], trail(parts.drop(2)))
            RECORD -> FlagRoute.Record(parts[1], trail(parts.drop(3)), parts[2].toInt())
            SIGNAL_GROUP -> FlagRoute.SignalGroup(parts[1])
            else -> error("Not a saved route: $text")
        }
    }

    private fun steps(trail: List<RecordStep>): List<String> = trail.flatMap { listOf(it.index.toString(), it.field) }

    private fun trail(parts: List<String>): List<RecordStep> =
        parts.chunked(2) { (index, field) -> RecordStep(index.toInt(), field) }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagSectionHeader.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings

/** A section's title, above its rows. */
@Composable
internal fun FlagSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A section's explanation, below its rows. */
@Composable
internal fun FlagSectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** iOS ContentUnavailableMessage: a title over a centred, secondary message. */
@Composable
internal fun FlagContentUnavailable(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A small tinted capsule: "Overridden", or a group's count of overrides. */
@Composable
internal fun FlagPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/** A row that pushes a screen: its content, then a chevron. The whole row is one click target. */
@Composable
internal fun FlagNavigationRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A full-width action: Add, Import…, or a destructive reset. */
@Composable
internal fun FlagActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
        }
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** A screen's title bar. [onBack] adds a back arrow; [navigation] replaces it, as a sheet's Cancel or Done does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlagTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            when {
                navigation != null -> navigation()
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = FlagStrings.BACK)
                }
            }
        },
        actions = actions,
    )
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagTextField.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.share.FlagClipboard
import kotlinx.coroutines.delay

/** How long a copy button shows its checkmark: long enough to read, short enough not to lie about the state. */
internal const val COPIED_FEEDBACK_MILLIS: Long = 1_400L

/**
 * A text editor for one value that commits only text that parses (iOS FlagTextField).
 * - It keeps a local draft, and commits on IME Done and when focus leaves. A draft equal to [value] commits nothing.
 * - [onCommit] returns false to refuse: the draft snaps back to [value], and a warning shows until a later commit succeeds.
 * - While focused, a change to [value] from elsewhere does not overwrite the draft; once unfocused, it does.
 * - Bumping [revertKey] (after a failed write) puts [value] back into an unfocused field.
 * - [multiline] draws a wrapping code block with a copy button, for data and JSON. It has no Done; only blur commits.
 */
@Composable
internal fun FlagTextField(
    value: String,
    onCommit: (String) -> Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    revertKey: Int = 0,
) {
    var draft by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }
    var isRejected by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val focusManager = LocalFocusManager.current

    // An outside change (another process, a reset, a failed write) reaches the field only while nobody is typing.
    LaunchedEffect(value, revertKey) {
        if (!isFocused) draft = value
    }

    fun commit() {
        if (draft == latestValue) {
            isRejected = false
            return
        }
        isRejected = !latestOnCommit(draft)
        if (isRejected) draft = latestValue
    }

    val focusTracking = Modifier.onFocusChanged { state ->
        if (isFocused && !state.isFocused) commit()
        isFocused = state.isFocused
    }
    val textStyle = MaterialTheme.typography.bodySmall.monospaced()
    val keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = keyboardType,
        imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
    )
    if (multiline) {
        FlagCodeBlock(
            draft = draft,
            onDraftChange = { draft = it },
            isRejected = isRejected,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            fieldModifier = focusTracking,
            modifier = modifier,
        )
    } else {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = modifier
                .fillMaxWidth()
                .then(focusTracking),
            textStyle = textStyle,
            trailingIcon = if (isRejected) {
                { RejectedIcon() }
            } else {
                null
            },
            keyboardOptions = keyboardOptions,
            // Done clears focus, and losing focus is what commits, so an edit is committed exactly once.
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
        )
    }
}

/** Presented as a code block, because that is what it is: a tinted ground and a hairline border mark the editable region. */
@Composable
private fun FlagCodeBlock(
    draft: String,
    onDraftChange: (String) -> Unit,
    isRejected: Boolean,
    textStyle: TextStyle,
    keyboardOptions: KeyboardOptions,
    fieldModifier: Modifier,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.04f))
                .border(width = 1.dp, color = colors.onSurface.copy(alpha = 0.12f), shape = shape)
                // Room on the right for the copy button, so base64 (which has no spaces to wrap at) does not run under it.
                .padding(start = 8.dp, end = 48.dp, top = 6.dp, bottom = 6.dp),
            textStyle = textStyle.copy(color = colors.onSurface),
            keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(colors.primary),
        )
        FlagCopyButton(
            text = { draft },
            idleDescription = FlagStrings.COPY_VALUE,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        if (isRejected) {
            RejectedIcon(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun RejectedIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = FlagStrings.NOT_A_VALID_VALUE,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.error,
    )
}

/** Copies [text] and shows a checkmark ("Copied") for [COPIED_FEEDBACK_MILLIS]. */
@Composable
internal fun FlagCopyButton(text: () -> String, idleDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCopied by remember { mutableStateOf(false) }
    LaunchedEffect(hasCopied) {
        if (hasCopied) {
            delay(COPIED_FEEDBACK_MILLIS)
            hasCopied = false
        }
    }
    IconButton(
        onClick = {
            FlagClipboard.copy(context, text())
            hasCopied = true
        },
        modifier = modifier,
    ) {
        if (hasCopied) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = FlagStrings.COPIED,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = FlagIcons.ContentCopy,
                contentDescription = idleDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagControls.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A menu of an enum's cases, each shown as its value text, raw (iOS menu Picker). */
@Composable
internal fun FlagPicker(
    selected: FlagValueBox,
    options: List<FlagValueBox>,
    onSelect: (FlagValueBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { isExpanded = true }) {
            Text(text = FlagValueText.format(selected))
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = FlagValueText.format(option)) },
                    onClick = {
                        isExpanded = false
                        if (option != selected) onSelect(option)
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private enum class DateTimeStep { Closed, Date, Time }

private val DateTimeText: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * The local date and time of [instant] (iOS compact DatePicker, date and time). Tapping it picks a date,
 * then a time; the seconds of [instant] are kept, as the iOS picker has no seconds either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlagDateTimeButton(instant: Instant, onPick: (Instant) -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val local = instant.atZone(zone)
    var step by remember { mutableStateOf(DateTimeStep.Closed) }
    var pickedDate by remember { mutableStateOf(local.toLocalDate()) }
    TextButton(
        onClick = {
            pickedDate = local.toLocalDate()
            step = DateTimeStep.Date
        },
        modifier = modifier,
    ) {
        Text(text = DateTimeText.format(local), style = MaterialTheme.typography.bodyMedium)
    }
    when (step) {
        DateTimeStep.Closed -> Unit
        DateTimeStep.Date -> {
            // The Material date picker speaks UTC midnights, whatever the device's zone.
            val state = rememberDatePickerState(
                initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { step = DateTimeStep.Closed },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let { millis ->
                                pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            }
                            step = DateTimeStep.Time
                        },
                    ) { Text(text = FlagStrings.OK) }
                },
                dismissButton = {
                    TextButton(onClick = { step = DateTimeStep.Closed }) { Text(text = FlagStrings.CANCEL) }
                },
            ) {
                DatePicker(state = state)
            }
        }
        DateTimeStep.Time -> {
            val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
            AlertDialog(
                onDismissRequest = { step = DateTimeStep.Closed },
                confirmButton = {
                    TextButton(
                        onClick = {
                            step = DateTimeStep.Closed
                            val time = LocalTime.of(state.hour, state.minute, local.second)
                            onPick(LocalDateTime.of(pickedDate, time).atZone(zone).toInstant())
                        },
                    ) { Text(text = FlagStrings.OK) }
                },
                dismissButton = {
                    TextButton(onClick = { step = DateTimeStep.Closed }) { Text(text = FlagStrings.CANCEL) }
                },
                text = { TimePicker(state = state) },
            )
        }
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagRow.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagEditorKind
import com.andyyhope.featureflag.ui.editing.editorKind
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant

/**
 * One flag, with the control its type calls for (iOS FlagRowView): its description and key, the
 * Overridden pill and reset arrow when overridden, then the editor. List and record flags push their
 * own screen through [onOpen].
 */
@Composable
internal fun FlagRow(
    store: FlagEditingStore,
    entry: FlagSchema.Entry,
    onOpen: (FlagRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val value = store.value(entry)
    var failedWrites by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlagRowHeader(
            entry = entry,
            isOverridden = store.isOverridden(entry),
            onReset = { writer.write { store.reset(entry) } },
        )
        when (val kind = entry.editorKind) {
            FlagEditorKind.Toggle -> TrailingControl {
                Switch(
                    checked = (value as? FlagValueBox.Bool)?.value ?: false,
                    onCheckedChange = { checked -> writer.write { store.setValue(entry, FlagValueBox.Bool(checked)) } },
                    modifier = Modifier.semantics { contentDescription = entry.description },
                )
            }
            is FlagEditorKind.Picker -> TrailingControl {
                FlagPicker(
                    selected = value,
                    options = kind.cases,
                    onSelect = { box -> writer.write { store.setValue(entry, box) } },
                )
            }
            FlagEditorKind.Date -> TrailingControl {
                FlagDateTimeButton(
                    instant = (value as? FlagValueBox.Date)?.value ?: Instant.now(),
                    onPick = { instant -> writer.write { store.setValue(entry, FlagValueBox.Date(instant)) } },
                )
            }
            FlagEditorKind.Integer, FlagEditorKind.Decimal, FlagEditorKind.Url, FlagEditorKind.Text, FlagEditorKind.Data, FlagEditorKind.Json -> FlagTextField(
                value = FlagValueText.format(value),
                onCommit = { text ->
                    // Refuse what does not parse rather than guess; the field snaps back and warns.
                    val box = FlagValueText.parse(text, entry.valueType)
                    if (box != null) writer.write(onFailed = { failedWrites++ }) { store.setValue(entry, box) }
                    box != null
                },
                keyboardType = keyboardTypeFor(entry.valueType),
                multiline = kind.wantsWrappingEditor,
                revertKey = failedWrites,
            )
            is FlagEditorKind.List -> FlagNavigationRow(onClick = { onOpen(FlagRoute.ListEditor(entry.key.rawValue)) }) {
                Text(
                    text = listSummary(value, kind.element),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Middle truncation keeps both brackets, which says it is a list and where it stops.
                Text(
                    text = FlagValueText.format(value),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.monospaced(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.MiddleEllipsis,
                    maxLines = 1,
                )
            }
            is FlagEditorKind.Records -> FlagNavigationRow(onClick = { onOpen(FlagRoute.Records(entry.key.rawValue)) }) {
                Text(
                    text = recordSummary(store, entry, value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = kind.fields.joinToString(", ") { it.name },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FlagRowHeader(entry: FlagSchema.Entry, isOverridden: Boolean, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = entry.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.key.rawValue,
                style = MaterialTheme.typography.labelSmall.monospaced(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isOverridden) {
            // The most useful thing to see at a glance: what has been changed from what the app ships with.
            FlagPill(text = FlagStrings.OVERRIDDEN)
            IconButton(onClick = onReset) {
                Icon(imageVector = FlagIcons.Undo, contentDescription = FlagStrings.RESET_TO_DEFAULT)
            }
        }
    }
}

/** Toggles, pickers and dates sit at the trailing edge, under the header. */
@Composable
private fun TrailingControl(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { content() }
}

/** "3 strings", "1 int", or "Edit" when the value in effect is not an array. */
internal fun listSummary(value: FlagValueBox, element: FlagValueType): String =
    (value as? FlagValueBox.Array)?.let { FlagStrings.listSummary(it.value.size, element.typeName) } ?: FlagStrings.EDIT

/** "3 records", or why there is no count: a shared key, or text that is not a list of records. */
internal fun recordSummary(store: FlagEditingStore, entry: FlagSchema.Entry, value: FlagValueBox): String {
    val records = store.records(entry)
    if (records != null) return FlagStrings.recordCount(records.size)
    val shape = entry.recordShape.orEmpty()
    val key = shape.firstOrNull { it.isKey }
    return if (key != null && FlagRecordList.duplicateKey(value, shape) != null) {
        FlagStrings.twoRecordsShareA(key.name)
    } else {
        FlagStrings.UNREADABLE
    }
}

/** Android has no numbers-and-punctuation keyboard, so numbers get the numeric ones; the parse is what decides. */
internal fun keyboardTypeFor(type: FlagValueType): KeyboardType = when (type) {
    FlagValueType.Int -> KeyboardType.Number
    FlagValueType.Double, FlagValueType.Float -> KeyboardType.Decimal
    FlagValueType.Url -> KeyboardType.Uri
    else -> KeyboardType.Text
}
```

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.*' --tests 'com.andyyhope.featureflag.ui.share.*' --tests 'com.andyyhope.featureflag.ui.FlagIconsTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. In `featureflag-ui/build/test-results/testDebugUnitTest/`: `FlagRowTest` `tests="14" failures="0"`, `FlagRouteTest` 1, `FlagClipboardTest` 4, `FlagIconsTest` 1 (20 tests).

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui
git commit -m "Add the companion's strings, icons, text field and row editor for every flag kind" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task B2: Browser, group and detail screens

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/BrowserScreen.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/GroupScreen.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/DetailScreen.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/BrowserScreenTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/DetailScreenTest.kt`

**Interfaces:**
- Consumes: B1 (`FlagRow`, `FlagRoute`, `FlagTopBar`, `FlagSectionHeader`, `FlagSectionFooter`, `FlagContentUnavailable`, `FlagPill`, `FlagNavigationRow`, `FlagActionRow`, `LocalFlagWriter`, `observeRevision`, test support); A2 `FlagTreeNode`, `FlagSection`, `FlagEditorKind.Picker`; A3 `tree`, `sections`, `overriddenCount`, `entry`, `value`, `isOverridden`, `setValue`, `reset`.
- Produces (all `internal`):

```kotlin
@Composable internal fun BrowserScreen(store: FlagEditingStore, onOpen: (FlagRoute) -> Unit, modifier: Modifier = Modifier)
@Composable internal fun GroupScreen(store: FlagEditingStore, path: List<String>, onOpen: (FlagRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun DetailScreen(store: FlagEditingStore, key: String, title: String?, onOpen: (FlagRoute) -> Unit, modifier: Modifier = Modifier)
@Composable internal fun FlagGroupLink(group: FlagTreeNode, overriddenCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier)
internal fun LazyListScope.treeItems(store: FlagEditingStore, node: FlagTreeNode, overriddenCounts: Map<String, Int>, onOpen: (FlagRoute) -> Unit)
internal fun overriddenCounts(store: FlagEditingStore, node: FlagTreeNode): Map<String, Int>
internal fun FlagTreeNode.find(path: List<String>): FlagTreeNode?
internal fun capitalized(text: String): String       // Swift's String.capitalized, for the Detail radio labels
```

**Behaviour this task fixes (iOS `FlagBrowserView`, `FlagGroupView`, `FlagDetailView`):**
- **Browser:** title `applicationName ?: "Feature Flags"`; a search field ("Search flags"). The schema description shows only while the search is empty. Empty search → tree mode: root flags, then (only if there are root groups) a "Groups" header (only when root has flags too) and one link per group. A non-empty search → `store.sections(query)` flattened: a header per titled section, `pathDescription` when the path is deeper than one. No sections → "No flags" / "The app has not published any flags yet." or "No matches" / "No flag matches “<query>”." (the query untrimmed).
- **Group link:** `title ?: id`, "N flag(s)" over `allFlags`, and a pill with the group's overridden count when it is above zero.
- **Group screen:** title `title ?: "Flags"`, a back arrow, the same tree layout.
- **Detail:** title `title ?: description ?: key`. An enum is a radio list (labels capitalised); any other kind is a `FlagRow`. Footer "Anything you set here…". A "Details" section: Key, Default, Remotely overridable ("Yes — <remoteKey>" or "No"), "Reset to the app's default" when overridden, and the remote-key footer. A missing key says "This app publishes no flag called “<key>”." with its footer.

- [ ] **Step 1: Write the failing tests**

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/BrowserScreenTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BrowserScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()
    private var backs = 0

    private fun showBrowser(store: FlagEditingStore) {
        composeRule.setFlagContent { BrowserScreen(store = store, onOpen = { opened += it }) }
    }

    private fun showGroup(store: FlagEditingStore, path: List<String>) {
        composeRule.setFlagContent {
            GroupScreen(store = store, path = path, onOpen = { opened += it }, onBack = { backs++ })
        }
    }

    private fun search(text: String) {
        composeRule.onNodeWithText("Search flags").performTextInput(text)
    }

    // The tree

    @Test
    fun rootFlagsComeFirstThenTheGroups() {
        showBrowser(testStore(ScreenFixtures.companion))

        composeRule.onNodeWithText("New onboarding experience").assertExists()
        composeRule.onNodeWithText("Maximum items").assertExists()
        composeRule.onNodeWithText("Groups").assertExists()
        composeRule.onNode(hasText("Checkout") and hasText("7 flags")).assertExists()
        composeRule.onNodeWithText("Apple Pay").assertDoesNotExist()
    }

    @Test
    fun theTitleIsTheApplicationNameOrFeatureFlags() {
        showBrowser(testStore(ScreenFixtures.companion.copy(applicationName = null)))

        composeRule.onNodeWithText("Feature Flags").assertExists()
    }

    @Test
    fun theSchemaDescriptionShowsUntilASearchStarts() {
        showBrowser(testStore(ScreenFixtures.companion))
        composeRule.onNodeWithText("What the checkout team can turn on").assertExists()

        search("apple")

        composeRule.onNodeWithText("What the checkout team can turn on").assertDoesNotExist()
    }

    @Test
    fun aGroupBadgeUpdatesWhileTheBrowserIsShown() {
        val store = testStore(ScreenFixtures.companion)
        showBrowser(store)
        composeRule.onNode(hasText("Checkout") and hasText("1")).assertDoesNotExist()

        runBlocking { store.setValue(store.requireEntry("checkout.apple-pay"), FlagValueBox.Bool(true)) }

        composeRule.onNode(hasText("Checkout") and hasText("1")).assertExists()
    }

    @Test
    fun tappingAGroupOpensIt() {
        showBrowser(testStore(ScreenFixtures.companion))

        composeRule.onNodeWithText("Checkout").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Group(listOf("checkout"))), opened)
    }

    // Searching

    @Test
    fun searchingFlattensTheTreeIntoSections() {
        showBrowser(testStore(ScreenFixtures.companion))

        search("express")

        composeRule.onNodeWithText("Checkout › Express").assertExists()
        composeRule.onNodeWithText("Label").assertExists()
        composeRule.onNodeWithText("Tags").assertExists()
        composeRule.onNodeWithText("Groups").assertDoesNotExist()
        composeRule.onNodeWithText("New onboarding experience").assertDoesNotExist()
    }

    @Test
    fun aMatchInAGroupIsHeadedByTheGroup() {
        showBrowser(testStore(ScreenFixtures.companion))

        search("apple")

        composeRule.onNodeWithText("Checkout").assertExists()
        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Ratio").assertDoesNotExist()
    }

    @Test
    fun aSearchWithNoMatchesSaysSo() {
        showBrowser(testStore(ScreenFixtures.companion))

        search("zzzz")

        composeRule.onNodeWithText("No matches").assertExists()
        composeRule.onNodeWithText("No flag matches “zzzz”.").assertExists()
    }

    @Test
    fun anEmptySchemaSaysNoFlags() {
        showBrowser(testStore(ScreenFixtures.empty))

        composeRule.onNodeWithText("No flags").assertExists()
        composeRule.onNodeWithText("The app has not published any flags yet.").assertExists()
    }

    // The group screen

    @Test
    fun theGroupScreenListsItsFlagsThenItsSubgroups() {
        showGroup(testStore(ScreenFixtures.companion), listOf("checkout"))

        composeRule.onNodeWithText("Checkout").assertExists()
        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Groups").assertExists()
        composeRule.onNode(hasText("Express") and hasText("2 flags")).assertExists()
    }

    @Test
    fun aGroupWithOnlySubgroupsHasNoGroupsHeader() {
        val schema = FlagSchema(
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            flags = listOf(
                ScreenFixtures.entry("checkout.express.one-tap", FlagValueType.Bool, FlagValueBox.Bool(false), "One tap"),
            ),
            groups = listOf(
                FlagSchema.Group(listOf("checkout"), "Checkout"),
                FlagSchema.Group(listOf("checkout", "express"), "Express"),
            ),
        )
        showGroup(testStore(schema), listOf("checkout"))

        composeRule.onNode(hasText("Express") and hasText("1 flag")).assertExists()
        composeRule.onNodeWithText("Groups").assertDoesNotExist()
    }

    @Test
    fun theBackArrowLeavesTheGroup() {
        showGroup(testStore(ScreenFixtures.companion), listOf("checkout"))

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/DetailScreenTest.kt` (iOS `testTheDetailViewBuildsForAFlagThatExists`, `testTheDetailViewBuildsForAKeyThatDoesNot`, `testADetailTabRendersTheFlagItNames`)
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(store: FlagEditingStore, key: String, title: String? = null) {
        composeRule.setFlagContent { DetailScreen(store = store, key = key, title = title, onOpen = {}) }
    }

    @Test
    fun theDetailViewBuildsForAFlagThatExists() {
        show(testStore(ScreenFixtures.companion), "new-onboarding")

        // The title bar, the section header and the row all name it.
        composeRule.onAllNodesWithText("New onboarding experience").assertCountEquals(3)
        composeRule.onNodeWithText("Details").assertExists()
        composeRule.onNodeWithText("Key").assertExists()
        composeRule.onNodeWithText(
            "Anything you set here stays set. A value set by hand outranks whatever a backend sends, until you reset it.",
        ).assertExists()
    }

    @Test
    fun theDetailViewBuildsForAKeyThatDoesNot() {
        show(testStore(ScreenFixtures.companion), "newOnboarding")

        composeRule.onNodeWithText("This app publishes no flag called “newOnboarding”.").assertExists()
        composeRule.onNodeWithText(
            "Check the key as the schema publishes it — a nested flag is “checkout.apple-pay”, " +
                "not “applePay”.",
        ).assertExists()
    }

    @Test
    fun aDetailTabRendersTheFlagItNames() {
        show(testStore(ScreenFixtures.companion), "new-onboarding", title = "Onboarding")

        composeRule.onNodeWithText("Onboarding").assertExists()
    }

    @Test
    fun anEnumIsARadioListOfItsCases() {
        val store = testStore(ScreenFixtures.companion)
        show(store, "checkout.tier")
        composeRule.onNodeWithText("Free").assertIsSelected()

        composeRule.onNodeWithText("Pro").performClick()

        composeRule.onNodeWithText("Pro").assertIsSelected()
        assertEquals(FlagValueBox.String("pro"), store.value(store.requireEntry("checkout.tier")))
    }

    @Test
    fun theDetailsNameTheKeyTheDefaultAndTheRemoteKey() {
        show(testStore(ScreenFixtures.companion), "checkout.endpoint")

        // The row and the Details section both show the key and the value.
        composeRule.onAllNodesWithText("checkout.endpoint").assertCountEquals(2)
        composeRule.onAllNodesWithText("https://example.com").assertCountEquals(2)
        composeRule.onNodeWithText("Yes — checkout_endpoint").assertExists()
        composeRule.onNodeWithText("This flag carries a remote key, so a payload can change it.").assertExists()
    }

    @Test
    fun aFlagWithoutARemoteKeySaysSo() {
        show(testStore(ScreenFixtures.companion), "max-items")

        composeRule.onNodeWithText("No").assertExists()
        composeRule.onNodeWithText(
            "No remote key, so nothing a payload contains can change this flag. For a flag that decides which " +
                "payload gets fetched, that is deliberate — otherwise the two drive each other.",
        ).assertExists()
    }

    @Test
    fun resetIsOfferedOnlyWhenOverridden() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store, "max-items")
        assertTrue(store.isOverridden(store.requireEntry("max-items")))

        composeRule.onNodeWithText("Reset to the app's default").performClick()

        composeRule.onNodeWithText("Reset to the app's default").assertDoesNotExist()
        assertFalse(store.isOverridden(store.requireEntry("max-items")))
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.BrowserScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.DetailScreenTest'
```

Expected: `BUILD FAILED` in `compileDebugUnitTestKotlin` with `Unresolved reference 'BrowserScreen'`, `'GroupScreen'` and `'DetailScreen'`.

- [ ] **Step 3: Implement**

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/BrowserScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagSection
import com.andyyhope.featureflag.ui.editing.FlagTreeNode

/**
 * The Flags tab (iOS FlagBrowserView): the schema as a tree of groups, or, while searching, flattened
 * into one section per group with a match.
 */
@Composable
internal fun BrowserScreen(store: FlagEditingStore, onOpen: (FlagRoute) -> Unit, modifier: Modifier = Modifier) {
    store.observeRevision()
    var query by rememberSaveable { mutableStateOf("") }
    val isSearching = query.isNotEmpty()
    val sections = store.sections(query)
    val tree = store.tree
    val counts = overriddenCounts(store, tree)
    val description = store.schema.description
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = store.schema.applicationName ?: FlagStrings.FEATURE_FLAGS)
        FlagSearchField(query = query, onQueryChange = { query = it })
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (description != null && !isSearching) {
                item(key = "description") { FlagSectionFooter(text = description) }
            }
            when {
                sections.isEmpty() -> item(key = "empty") {
                    if (isSearching) {
                        FlagContentUnavailable(title = FlagStrings.NO_MATCHES, message = FlagStrings.noMatches(query))
                    } else {
                        FlagContentUnavailable(title = FlagStrings.NO_FLAGS, message = FlagStrings.NO_FLAGS_MESSAGE)
                    }
                }
                isSearching -> sectionItems(store = store, sections = sections, onOpen = onOpen)
                else -> treeItems(store = store, node = tree, overriddenCounts = counts, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun FlagSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(text = FlagStrings.SEARCH_FLAGS) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        singleLine = true,
    )
}

/** Search results: a header for each titled section (the full path when nested), then its rows. */
private fun LazyListScope.sectionItems(store: FlagEditingStore, sections: List<FlagSection>, onOpen: (FlagRoute) -> Unit) {
    sections.forEach { section ->
        val title = section.title
        if (title != null) {
            item(key = "section:${section.id}") {
                FlagSectionHeader(text = if (section.path.size > 1) section.pathDescription else title)
            }
        }
        items(section.entries, key = { "flag:${it.key.rawValue}" }) { flag -> FlagRow(store = store, entry = flag, onOpen = onOpen) }
    }
}

/**
 * One level of the tree: its own flags, then a link per subgroup. The "Groups" header shows only when
 * the level has flags of its own, as on iOS. [overriddenCounts] is keyed by group id and computed by
 * the caller's composition, so a write updates the badges.
 */
internal fun LazyListScope.treeItems(
    store: FlagEditingStore,
    node: FlagTreeNode,
    overriddenCounts: Map<String, Int>,
    onOpen: (FlagRoute) -> Unit,
) {
    items(node.flags, key = { "flag:${it.key.rawValue}" }) { flag -> FlagRow(store = store, entry = flag, onOpen = onOpen) }
    if (node.groups.isEmpty()) return
    if (node.flags.isNotEmpty()) {
        item(key = "groups") { FlagSectionHeader(text = FlagStrings.GROUPS) }
    }
    items(node.groups, key = { "group:${it.id}" }) { group ->
        FlagGroupLink(
            group = group,
            overriddenCount = overriddenCounts[group.id] ?: 0,
            onClick = { onOpen(FlagRoute.Group(group.path)) },
        )
    }
}

/** Each direct subgroup's overridden count, by group id. */
internal fun overriddenCounts(store: FlagEditingStore, node: FlagTreeNode): Map<String, Int> =
    node.groups.associate { it.id to store.overriddenCount(it) }

/** A group: its title, how many flags it holds at any depth, and a badge when any are overridden. */
@Composable
internal fun FlagGroupLink(group: FlagTreeNode, overriddenCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlagNavigationRow(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = group.title ?: group.id, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = FlagStrings.flagCount(group.allFlags.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overriddenCount > 0) FlagPill(text = "$overriddenCount")
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/GroupScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagTreeNode

/** One group, pushed from the browser (iOS FlagGroupView). A group the schema no longer has shows an empty screen. */
@Composable
internal fun GroupScreen(
    store: FlagEditingStore,
    path: List<String>,
    onOpen: (FlagRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val node = store.tree.find(path)
    val counts = node?.let { overriddenCounts(store, it) }.orEmpty()
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = node?.title ?: FlagStrings.FLAGS, onBack = onBack)
        if (node != null) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                treeItems(store = store, node = node, overriddenCounts = counts, onOpen = onOpen)
            }
        }
    }
}

/** The node at [path] in this subtree, or null. */
internal fun FlagTreeNode.find(path: List<String>): FlagTreeNode? =
    if (this.path == path) this else groups.firstNotNullOfOrNull { it.find(path) }
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/DetailScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagEditorKind
import com.andyyhope.featureflag.ui.editing.editorKind
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueText

/**
 * One flag on its own tab (iOS FlagDetailView): an enum as a radio list, anything else as its row, then
 * what the flag is. A key the schema does not publish says so, since the usual mistake is a property name.
 */
@Composable
internal fun DetailScreen(
    store: FlagEditingStore,
    key: String,
    title: String?,
    onOpen: (FlagRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val entry = store.entry(FlagKey(key))
    val value = entry?.let { store.value(it) }
    val isOverridden = entry?.let { store.isOverridden(it) } ?: false
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = title ?: entry?.description ?: key)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (entry == null) {
                item(key = "missing") {
                    Text(
                        text = FlagStrings.noSuchFlag(key),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "missing-footer") { FlagSectionFooter(text = FlagStrings.NO_SUCH_FLAG_FOOTER) }
                return@LazyColumn
            }
            item(key = "header") { FlagSectionHeader(text = entry.description) }
            val kind = entry.editorKind
            if (kind is FlagEditorKind.Picker) {
                items(kind.cases, key = { "case:${FlagValueText.format(it)}" }) { option ->
                    FlagRadioRow(
                        label = capitalized(FlagValueText.format(option)),
                        isSelected = option == value,
                        onClick = { writer.write { store.setValue(entry, option) } },
                    )
                }
            } else {
                item(key = "row") { FlagRow(store = store, entry = entry, onOpen = onOpen) }
            }
            item(key = "footer") { FlagSectionFooter(text = FlagStrings.DETAIL_FOOTER) }
            item(key = "details") { FlagSectionHeader(text = FlagStrings.DETAILS) }
            item(key = "key") { DetailLine(label = FlagStrings.KEY, value = entry.key.rawValue) }
            item(key = "default") { DetailLine(label = FlagStrings.DEFAULT, value = FlagValueText.format(entry.defaultValue)) }
            item(key = "remote") {
                DetailLine(
                    label = FlagStrings.REMOTELY_OVERRIDABLE,
                    value = entry.remoteKey?.let { FlagStrings.remoteKey(it) } ?: FlagStrings.NO,
                )
            }
            if (isOverridden) {
                item(key = "reset") {
                    FlagActionRow(
                        text = FlagStrings.RESET_TO_DEFAULT,
                        onClick = { writer.write { store.reset(entry) } },
                        destructive = true,
                    )
                }
            }
            item(key = "remote-footer") {
                FlagSectionFooter(
                    text = if (entry.remoteKey == null) FlagStrings.NO_REMOTE_KEY_FOOTER else FlagStrings.REMOTE_KEY_FOOTER,
                )
            }
        }
    }
}

@Composable
private fun FlagRadioRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** Swift's `String.capitalized`: the first letter of each word upper case, the rest lower case. */
internal fun capitalized(text: String): String {
    val result = StringBuilder(text.length)
    var startsWord = true
    for (character in text) {
        result.append(if (startsWord) character.titlecase() else character.lowercase())
        startsWord = !character.isLetterOrDigit()
    }
    return result.toString()
}
```

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.BrowserScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.DetailScreenTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `BrowserScreenTest` `tests="12" failures="0"`, `DetailScreenTest` `tests="7" failures="0"` (19 tests).

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens
git commit -m "Add the flag browser with search, the group screen and the detail screen" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task B3: List editor, records list and record screen

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/ListEditorScreen.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/RecordsScreen.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/RecordScreen.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ListEditorScreenTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/RecordsScreenTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/RecordScreenTest.kt`

**Interfaces:**
- Consumes: B1 (`FlagTextField`, `FlagPicker`, `FlagDateTimeButton`, `FlagNavigationRow`, `FlagActionRow`, `FlagTopBar`, `FlagSectionHeader`, `FlagSectionFooter`, `keyboardTypeFor`, `FlagRoute.Records`, `FlagRoute.Record`, `RecordStep`, `LocalFlagWriter`, test support); A1 `FlagValueText.format/parse/empty`, `FlagRecordList.decode/encode/duplicateKey/emptyRecord`; A2 `FlagEditorKind.List`; A3 `value`, `isOverridden`, `setValue`, `reset`, `records`, `setRecords` (throws `FlagRecordEditingException`).
- Produces (all `internal`):

```kotlin
@Composable internal fun ListEditorScreen(store: FlagEditingStore, key: String, onBack: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun RecordsScreen(store: FlagEditingStore, key: String, trail: List<RecordStep>, onOpen: (FlagRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun RecordScreen(store: FlagEditingStore, key: String, trail: List<RecordStep>, index: Int, onOpen: (FlagRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun FlagRowActions(index: Int, count: Int, onMove: (Int) -> Unit, onDelete: () -> Unit, onDuplicate: (() -> Unit)? = null)
internal class RecordLevel(val fields: List<FlagRecordField>, val records: List<Map<String, FlagValueBox>>?, val raw: FlagValueBox)
internal fun FlagEditingStore.recordLevel(entry: FlagSchema.Entry, trail: List<RecordStep>): RecordLevel?
internal suspend fun FlagEditingStore.setRecordLevel(entry: FlagSchema.Entry, trail: List<RecordStep>, records: List<Map<String, FlagValueBox>>)
internal fun recordTitle(record: Map<String, FlagValueBox>, fields: List<FlagRecordField>): String
internal fun recordDetail(record: Map<String, FlagValueBox>, fields: List<FlagRecordField>): String
internal fun emptyFieldValue(field: FlagRecordField): FlagValueBox
internal fun <T> List<T>.replacing(index: Int, item: T): List<T>     // and removing, moving, duplicating
```

**Reorder without extra dependencies.** iOS uses `EditButton` with drag handles and swipe actions. Here each row has an "Actions" menu (`Icons.Filled.MoreVert`, `contentDescription` "Actions") with "Move up", "Move down", "Duplicate" (records only) and "Delete". The first row's "Move up" and the last row's "Move down" are disabled.

**Behaviour this task fixes (iOS `FlagArrayEditorView`, `FlagRecordsEditorView`):**
- **List editor:** title = the flag's description, back arrow. Header "N item(s)"; one row per element (bool → a switch beside its "true"/"false" text; date → the date-time button; anything else → a single-line `FlagTextField` parsing as the element type); "Add" appends `FlagValueText.empty(element)`; footer "Every element is a <type>…"; "Reset to the app's default" when overridden. Every change writes the whole array.
- **Records list:** title = the description (or, nested, the field's name). Readable → header "N record(s)", a row per record (title = key field's value text, else the first field's; "Untitled" when empty; "Record" when missing; detail = the other fields as `name: value` joined by two spaces, a nested list as its readable count, a missing field as "—"); footer "Each record has N field(s): …"; "Add" appends `FlagRecordList.emptyRecord(fields, alongside = current)`. Unreadable → the reason (a shared key names it; otherwise "This value is not a list of records."), the raw text, and the "The app cannot read it either…" footer. "Reset to the app's default" on the flag's own list when overridden. Every action re-reads the current records first, so it never applies to a stale copy.
- **Record screen:** title = the record's title. One row per field: name over its type name; a nested list → a navigation row ("N record(s)" or "Unreadable"); cases → the picker; bool → a switch; date → the date-time button; anything else → a text field. A key field refuses a value another record already uses (the warning shows, nothing is written).
- **A refused write is reported.** "Duplicate" on a keyed list makes the store throw `FlagRecordEditingException`; the writer reports it (the shell shows it in the snackbar) and nothing changes. iOS silently does nothing (overview deviation 6).

- [ ] **Step 1: Write the failing tests**

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ListEditorScreenTest.kt` (iOS `testTheEditorBuilds`, `testAnElementRowBuildsForEveryScalar`)
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ListEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(store: FlagEditingStore, key: String) {
        composeRule.setFlagContent { ListEditorScreen(store = store, key = key, onBack = {}) }
    }

    private fun FlagEditingStore.elements(key: String): List<FlagValueBox> =
        (value(requireEntry(key)) as FlagValueBox.Array).value

    private fun openActions(row: Int) {
        composeRule.onAllNodesWithContentDescription("Actions")[row].performClick()
    }

    // Building

    @Test
    fun theEditorBuilds() {
        show(testStore(ScreenFixtures.editors), "markets")

        composeRule.onNodeWithText("Markets").assertExists()
        composeRule.onNodeWithText("2 items").assertExists()
        composeRule.onNodeWithText(
            "Every element is a string. Removing them all leaves an empty array, which is a value like any " +
                "other — reset the flag if you want the app's default back.",
        ).assertExists()
    }

    @Test
    fun anElementRowBuildsForEveryScalar() {
        val store = testStore(ScreenFixtures.elements)
        var key by mutableStateOf("strings")
        composeRule.setFlagContent { ListEditorScreen(store = store, key = key, onBack = {}) }

        listOf("strings", "ints", "doubles", "bools", "dates", "urls", "blobs").forEach { element ->
            key = element
            composeRule.waitForIdle()

            composeRule.onNodeWithText("1 item").assertExists()
            composeRule.onAllNodesWithContentDescription("Actions").assertCountEquals(1)
        }
    }

    // Editing

    @Test
    fun addingAppendsAnEmptyElementOfTheRightType() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        composeRule.onNodeWithText("Add").performClick()

        composeRule.onNodeWithText("3 items").assertExists()
        assertEquals(
            listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"), FlagValueBox.String("")),
            store.elements("markets"),
        )
    }

    @Test
    fun editingAnElementWritesTheWholeArrayBack() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "counts")
        val field = composeRule.onNode(hasSetTextAction() and hasText("2"))

        field.performClick()
        field.performTextReplacement("20")
        field.performImeAction()
        composeRule.waitForIdle()

        assertEquals(listOf(FlagValueBox.Int(1), FlagValueBox.Int(20)), store.elements("counts"))
    }

    @Test
    fun anUneditableElementIsRefusedAndWarns() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "counts")
        val field = composeRule.onNode(hasSetTextAction() and hasText("2"))

        field.performClick()
        field.performTextReplacement("two")
        field.performImeAction()

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertExists()
        assertFalse(store.isOverridden(store.requireEntry("counts")))
    }

    // Reordering and removing

    @Test
    fun movingAnElementDownSwapsItWithTheNext() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        openActions(0)
        composeRule.onNodeWithText("Move down").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(FlagValueBox.String("NZ"), FlagValueBox.String("AU")), store.elements("markets"))
    }

    @Test
    fun theFirstElementCannotMoveUp() {
        show(testStore(ScreenFixtures.editors), "markets")

        openActions(0)

        composeRule.onNodeWithText("Move up").assertIsNotEnabled()
    }

    @Test
    fun deletingAnElementLeavesTheRest() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        openActions(0)
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(FlagValueBox.String("NZ")), store.elements("markets"))
    }

    @Test
    fun anEmptyArrayIsStillAnOverrideUntilItIsReset() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "markets")

        repeat(2) {
            openActions(0)
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText("0 items").assertExists()
        assertTrue(store.isOverridden(store.requireEntry("markets")))
        composeRule.onNodeWithText("Reset to the app's default").performClick()
        composeRule.onNodeWithText("2 items").assertExists()
        assertFalse(store.isOverridden(store.requireEntry("markets")))
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/RecordsScreenTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagRecordEditingException
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()
    private val failures = mutableListOf<Exception>()

    private fun show(store: FlagEditingStore, key: String, trail: List<RecordStep> = emptyList()) {
        composeRule.setFlagContent(onFailure = { failures += it }) {
            RecordsScreen(store = store, key = key, trail = trail, onOpen = { opened += it }, onBack = {})
        }
    }

    private fun FlagEditingStore.names(key: String): List<String> =
        checkNotNull(records(requireEntry(key))).map { (it["name"] as FlagValueBox.String).value }

    private fun twoEndpoints() = testStore(
        ScreenFixtures.editors,
        mapOf(
            FlagKey("endpoints") to FlagRecordList.encode(
                listOf(ScreenFixtures.endpoint("staging", 7, "secondary"), ScreenFixtures.endpoint("canary", 3)),
            ),
        ),
    )

    private fun twoRoutes() = testStore(
        ScreenFixtures.editors,
        mapOf(FlagKey("routes") to FlagValueBox.String("""[{"name":"a","hops":"[]"},{"name":"b","hops":"[]"}]""")),
    )

    private fun act(row: Int, action: String) {
        composeRule.onAllNodesWithContentDescription("Actions")[row].performClick()
        composeRule.onNodeWithText(action).performClick()
        composeRule.waitForIdle()
    }

    // Reading

    @Test
    fun eachRecordIsSummarisedByItsTitleAndItsOtherFields() {
        show(testStore(ScreenFixtures.editors), "endpoints")

        composeRule.onNodeWithText("Endpoints").assertExists()
        composeRule.onNodeWithText("1 record").assertExists()
        composeRule.onNodeWithText("production").assertExists()
        composeRule.onNodeWithText("url: https://production.example  enabled: true  weight: 10  tier: primary").assertExists()
        composeRule.onNodeWithText("Each record has 5 fields: name, url, enabled, weight, tier.").assertExists()
    }

    @Test
    fun aListWithADuplicateKeySaysWhichKey() {
        val raw = """[{"name":"a","hops":"[]"},{"name":"a","hops":"[]"}]"""
        show(testStore(ScreenFixtures.editors, mapOf(FlagKey("routes") to FlagValueBox.String(raw))), "routes")

        composeRule.onNodeWithText(
            "Two records share the same name, a — it is what tells one record from another, so they cannot.",
        ).assertExists()
        composeRule.onNodeWithText(raw).assertExists()
        composeRule.onNodeWithText(
            "The app cannot read it either, so it is using its default. Reset the flag to edit it here.",
        ).assertExists()
    }

    @Test
    fun textThatIsNotAListOfRecordsSaysSo() {
        show(testStore(ScreenFixtures.editors, mapOf(FlagKey("endpoints") to FlagValueBox.String("{ not json"))), "endpoints")

        composeRule.onNodeWithText("This value is not a list of records.").assertExists()
        composeRule.onNodeWithText("{ not json").assertExists()
    }

    @Test
    fun tappingARecordOpensIt() {
        show(testStore(ScreenFixtures.editors), "endpoints")

        composeRule.onNodeWithText("production").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Record("endpoints", emptyList(), 0)), opened)
    }

    // Changing the list

    @Test
    fun addingAppendsARecordOfEmptyFields() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "endpoints")

        composeRule.onNodeWithText("Add").performClick()

        composeRule.onNodeWithText("Untitled").assertExists()
        val added = checkNotNull(store.records(store.requireEntry("endpoints"))).last()
        assertEquals(FlagValueBox.String(""), added["name"])
        assertEquals(FlagValueBox.Int(0), added["weight"])
        assertEquals(FlagValueBox.String("primary"), added["tier"])
    }

    @Test
    fun duplicatingPutsTheCopyRightAfterTheOriginal() {
        val store = twoEndpoints()
        show(store, "endpoints")

        act(row = 0, action = "Duplicate")

        assertEquals(listOf("staging", "staging", "canary"), store.names("endpoints"))
    }

    @Test
    fun duplicatingInAKeyedListIsRefusedAndReported() {
        val store = twoRoutes()
        show(store, "routes")

        act(row = 0, action = "Duplicate")

        assertIs<FlagRecordEditingException>(failures.single())
        assertEquals(listOf("a", "b"), store.names("routes"))
    }

    @Test
    fun movingARecordUpWritesTheNewOrder() {
        val store = twoEndpoints()
        show(store, "endpoints")

        act(row = 1, action = "Move up")

        assertEquals(listOf("canary", "staging"), store.names("endpoints"))
    }

    @Test
    fun deletingARecordLeavesTheRest() {
        val store = twoEndpoints()
        show(store, "endpoints")

        act(row = 0, action = "Delete")

        assertEquals(listOf("canary"), store.names("endpoints"))
    }

    // Nested lists

    @Test
    fun aNestedListIsTitledByItsField() {
        val raw = """[{"name":"a","hops":"[{\"host\":\"x\"}]"}]"""
        show(testStore(ScreenFixtures.editors, mapOf(FlagKey("routes") to FlagValueBox.String(raw))), "routes", listOf(RecordStep(0, "hops")))

        composeRule.onNodeWithText("hops").assertExists()
        composeRule.onNodeWithText("1 record").assertExists()
        composeRule.onNodeWithText("x").assertExists()
    }

    @Test
    fun addingToANestedListWritesThroughTheParent() {
        val raw = """[{"name":"a","hops":"[{\"host\":\"x\"}]"}]"""
        val store = testStore(ScreenFixtures.editors, mapOf(FlagKey("routes") to FlagValueBox.String(raw)))
        show(store, "routes", listOf(RecordStep(0, "hops")))

        composeRule.onNodeWithText("Add").performClick()
        composeRule.waitForIdle()

        val route = checkNotNull(store.records(store.requireEntry("routes"))).single()
        val hops = checkNotNull(FlagRecordList.decode(checkNotNull(route["hops"]), ScreenFixtures.routeFields[1].fields.orEmpty()))
        assertEquals(listOf(FlagValueBox.String("x"), FlagValueBox.String("")), hops.map { it["host"] })
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/RecordScreenTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FlagRoute>()

    private fun show(store: FlagEditingStore, key: String, index: Int) {
        composeRule.setFlagContent {
            RecordScreen(store = store, key = key, trail = emptyList(), index = index, onOpen = { opened += it }, onBack = {})
        }
    }

    private fun FlagEditingStore.rows(key: String): List<Map<String, FlagValueBox>> = checkNotNull(records(requireEntry(key)))

    private fun twoRoutes() = testStore(
        ScreenFixtures.editors,
        mapOf(FlagKey("routes") to FlagValueBox.String("""[{"name":"a","hops":"[]"},{"name":"b","hops":"[]"}]""")),
    )

    private fun replace(current: String, with: String) {
        val field = composeRule.onNode(hasSetTextAction() and hasText(current))
        field.performClick()
        field.performTextReplacement(with)
        field.performImeAction()
        composeRule.waitForIdle()
    }

    @Test
    fun aRecordShowsEveryFieldWithItsType() {
        show(testStore(ScreenFixtures.editors), "endpoints", 0)

        // The title bar and the name field both say "production"; "url" is both a field and a type.
        composeRule.onAllNodesWithText("production").assertCountEquals(2)
        composeRule.onAllNodesWithText("url").assertCountEquals(2)
        composeRule.onAllNodesWithText("string").assertCountEquals(2)
        listOf("name", "enabled", "weight", "tier", "int", "bool").forEach { text ->
            composeRule.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun editingOneFieldLeavesEveryOtherRecordAlone() {
        val staging = ScreenFixtures.endpoint("staging", 7, "secondary")
        val store = testStore(
            ScreenFixtures.editors,
            mapOf(FlagKey("endpoints") to FlagRecordList.encode(listOf(staging, ScreenFixtures.endpoint("canary", 3)))),
        )
        show(store, "endpoints", 1)

        replace(current = "3", with = "99")

        val rows = store.rows("endpoints")
        assertEquals(staging, rows[0])
        assertEquals(FlagValueBox.Int(99), rows[1]["weight"])
        assertEquals(FlagValueBox.String("canary"), rows[1]["name"])
    }

    @Test
    fun aKeyThatAnotherRecordUsesIsRefused() {
        val store = twoRoutes()
        show(store, "routes", 1)

        replace(current = "b", with = "a")

        composeRule.onNodeWithContentDescription("Not a valid value for this flag").assertExists()
        assertEquals(listOf(FlagValueBox.String("a"), FlagValueBox.String("b")), store.rows("routes").map { it["name"] })
    }

    @Test
    fun aKeyThatIsFreeIsAccepted() {
        val store = twoRoutes()
        show(store, "routes", 1)

        replace(current = "b", with = "c")

        assertEquals(listOf(FlagValueBox.String("a"), FlagValueBox.String("c")), store.rows("routes").map { it["name"] })
    }

    @Test
    fun anEnumFieldIsAPickerOfItsCases() {
        val store = testStore(ScreenFixtures.editors)
        show(store, "endpoints", 0)

        composeRule.onNodeWithText("primary").performClick()
        composeRule.onNodeWithText("secondary").performClick()
        composeRule.waitForIdle()

        assertEquals(FlagValueBox.String("secondary"), store.rows("endpoints")[0]["tier"])
    }

    @Test
    fun aNestedListFieldOpensTheNestedList() {
        show(twoRoutes(), "routes", 0)

        composeRule.onNodeWithText("0 records").performClick()

        assertEquals(listOf<FlagRoute>(FlagRoute.Records("routes", listOf(RecordStep(0, "hops")))), opened)
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.ListEditorScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.RecordsScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.RecordScreenTest'
```

Expected: `BUILD FAILED` in `compileDebugUnitTestKotlin` with `Unresolved reference 'ListEditorScreen'`, `'RecordsScreen'` and `'RecordScreen'`.

- [ ] **Step 3: Implement**

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/ListEditorScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagEditorKind
import com.andyyhope.featureflag.ui.editing.editorKind
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant

/** An array of scalars, one row per element (iOS FlagArrayEditorView). Every change writes the whole array. */
@Composable
internal fun ListEditorScreen(store: FlagEditingStore, key: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    store.observeRevision()
    val entry = store.entry(FlagKey(key))
    val element = (entry?.editorKind as? FlagEditorKind.List)?.element
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = entry?.description ?: key, onBack = onBack)
        if (entry != null && element != null) ListEditorContent(store = store, entry = entry, element = element)
    }
}

@Composable
private fun ListEditorContent(store: FlagEditingStore, entry: FlagSchema.Entry, element: FlagValueType) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val values = (store.value(entry) as? FlagValueBox.Array)?.value.orEmpty()
    val isOverridden = store.isOverridden(entry)
    var failedWrites by remember { mutableIntStateOf(0) }
    val write: (List<FlagValueBox>) -> Unit = { changed ->
        writer.write(onFailed = { failedWrites++ }) { store.setValue(entry, FlagValueBox.Array(changed)) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "count") { FlagSectionHeader(text = FlagStrings.itemCount(values.size)) }
        itemsIndexed(values) { index, value ->
            ListElementRow(
                element = element,
                value = value,
                index = index,
                count = values.size,
                revertKey = failedWrites,
                onChange = { box -> write(values.replacing(index, box)) },
                onMove = { to -> write(values.moving(index, to)) },
                onDelete = { write(values.removing(index)) },
            )
        }
        item(key = "add") {
            FlagActionRow(text = FlagStrings.ADD, onClick = { write(values + FlagValueText.empty(element)) }, icon = Icons.Filled.Add)
        }
        item(key = "footer") { FlagSectionFooter(text = FlagStrings.listFooter(element.typeName)) }
        if (isOverridden) {
            item(key = "reset") {
                FlagActionRow(
                    text = FlagStrings.RESET_TO_DEFAULT,
                    onClick = { writer.write { store.reset(entry) } },
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun ListElementRow(
    element: FlagValueType,
    value: FlagValueBox,
    index: Int,
    count: Int,
    revertKey: Int,
    onChange: (FlagValueBox) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (element) {
                FlagValueType.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = FlagValueText.format(value),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.monospaced(),
                    )
                    Switch(
                        checked = (value as? FlagValueBox.Bool)?.value ?: false,
                        onCheckedChange = { checked -> onChange(FlagValueBox.Bool(checked)) },
                    )
                }
                FlagValueType.Date -> FlagDateTimeButton(
                    instant = (value as? FlagValueBox.Date)?.value ?: Instant.now(),
                    onPick = { instant -> onChange(FlagValueBox.Date(instant)) },
                )
                else -> FlagTextField(
                    value = FlagValueText.format(value),
                    onCommit = { text -> FlagValueText.parse(text, element)?.also(onChange) != null },
                    keyboardType = keyboardTypeFor(element),
                    revertKey = revertKey,
                )
            }
        }
        FlagRowActions(index = index, count = count, onMove = onMove, onDelete = onDelete)
    }
}

/** A row's reorder and remove actions, in a menu so no drag-and-drop dependency is needed. */
@Composable
internal fun FlagRowActions(
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: (() -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { isExpanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = FlagStrings.ACTIONS)
        }
        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = FlagStrings.MOVE_UP) },
                onClick = {
                    isExpanded = false
                    onMove(index - 1)
                },
                enabled = index > 0,
            )
            DropdownMenuItem(
                text = { Text(text = FlagStrings.MOVE_DOWN) },
                onClick = {
                    isExpanded = false
                    onMove(index + 1)
                },
                enabled = index < count - 1,
            )
            if (onDuplicate != null) {
                DropdownMenuItem(
                    text = { Text(text = FlagStrings.DUPLICATE) },
                    onClick = {
                        isExpanded = false
                        onDuplicate()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = FlagStrings.DELETE) },
                onClick = {
                    isExpanded = false
                    onDelete()
                },
            )
        }
    }
}

internal fun <T> List<T>.replacing(index: Int, item: T): List<T> = toMutableList().also { it[index] = item }

internal fun <T> List<T>.removing(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

internal fun <T> List<T>.moving(from: Int, to: Int): List<T> = toMutableList().also { it.add(to, it.removeAt(from)) }

internal fun <T> List<T>.duplicating(index: Int): List<T> = toMutableList().also { it.add(index + 1, it[index]) }
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/RecordsScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText

/** One list of records inside a record-list flag. [records] is null when [raw] cannot be read as one. */
internal class RecordLevel(
    val fields: List<FlagRecordField>,
    val records: List<Map<String, FlagValueBox>>?,
    val raw: FlagValueBox,
)

/**
 * The list at [trail] inside [entry]: the flag's own list when [trail] is empty, else a nested list
 * reached through each step's record and field. Null when the trail no longer leads anywhere.
 */
internal fun FlagEditingStore.recordLevel(entry: FlagSchema.Entry, trail: List<RecordStep>): RecordLevel? {
    var fields = entry.recordShape ?: return null
    var raw = value(entry)
    var records = records(entry)
    for (step in trail) {
        val record = records?.getOrNull(step.index) ?: return null
        val field = fields.firstOrNull { it.name == step.field } ?: return null
        val nested = field.fields ?: return null
        raw = record[field.name] ?: field.defaultValue ?: FlagValueBox.String("")
        records = FlagRecordList.decode(raw, nested)
        fields = nested
    }
    return RecordLevel(fields, records, raw)
}

/** Writes [records] as the list at [trail], re-encoding each parent on the way up. The store refuses a duplicate key at any depth. */
internal suspend fun FlagEditingStore.setRecordLevel(
    entry: FlagSchema.Entry,
    trail: List<RecordStep>,
    records: List<Map<String, FlagValueBox>>,
) {
    if (trail.isEmpty()) {
        setRecords(entry, records)
        return
    }
    val shape = checkNotNull(entry.recordShape) { "'${entry.key}' is not a record list." }
    val root = checkNotNull(records(entry)) { "'${entry.key}' cannot be read as a record list." }
    setRecords(entry, root.replacingNested(shape, trail, records))
}

private fun List<Map<String, FlagValueBox>>.replacingNested(
    fields: List<FlagRecordField>,
    trail: List<RecordStep>,
    replacement: List<Map<String, FlagValueBox>>,
): List<Map<String, FlagValueBox>> {
    val step = trail.first()
    val field = fields.first { it.name == step.field }
    val nested = checkNotNull(field.fields) { "'${field.name}' is not a nested list." }
    val record = this[step.index]
    val inner = if (trail.size == 1) {
        replacement
    } else {
        val current = FlagRecordList.decode(record[field.name] ?: field.defaultValue ?: FlagValueBox.String(""), nested).orEmpty()
        current.replacingNested(nested, trail.drop(1), replacement)
    }
    return replacing(step.index, record + (field.name to FlagRecordList.encode(inner)))
}

/** A record-list flag's records, or a nested list's (iOS FlagRecordsEditorView and FlagRecordList). */
@Composable
internal fun RecordsScreen(
    store: FlagEditingStore,
    key: String,
    trail: List<RecordStep>,
    onOpen: (FlagRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val entry = store.entry(FlagKey(key))
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = trail.lastOrNull()?.field ?: entry?.description ?: key, onBack = onBack)
        if (entry != null) RecordsContent(store = store, entry = entry, trail = trail, onOpen = onOpen)
    }
}

@Composable
private fun RecordsContent(store: FlagEditingStore, entry: FlagSchema.Entry, trail: List<RecordStep>, onOpen: (FlagRoute) -> Unit) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val level = store.recordLevel(entry, trail) ?: return
    val records = level.records
    val isOverridden = trail.isEmpty() && store.isOverridden(entry)
    // Each action re-reads the records first, so it never applies to a copy an outside change made stale.
    val update: (change: (List<Map<String, FlagValueBox>>) -> List<Map<String, FlagValueBox>>) -> Unit = { change ->
        writer.write {
            val current = store.recordLevel(entry, trail)?.records ?: return@write
            store.setRecordLevel(entry, trail, change(current))
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (records == null) {
            item(key = "reason") {
                Text(
                    text = unreadableReason(level),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item(key = "raw") {
                Text(
                    text = (level.raw as? FlagValueBox.String)?.value ?: FlagValueText.format(level.raw),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall.monospaced(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "unreadable-footer") { FlagSectionFooter(text = FlagStrings.UNREADABLE_FOOTER) }
        } else {
            item(key = "count") { FlagSectionHeader(text = FlagStrings.recordCount(records.size)) }
            itemsIndexed(records) { index, record ->
                RecordRow(
                    title = recordTitle(record, level.fields),
                    detail = recordDetail(record, level.fields),
                    index = index,
                    count = records.size,
                    onOpen = { onOpen(FlagRoute.Record(entry.key.rawValue, trail, index)) },
                    onMove = { to -> update { it.moving(index, to) } },
                    onDuplicate = { update { it.duplicating(index) } },
                    onDelete = { update { it.removing(index) } },
                )
            }
            item(key = "fields") { FlagSectionFooter(text = FlagStrings.recordsFooter(level.fields.map { it.name })) }
            item(key = "add") {
                FlagActionRow(
                    text = FlagStrings.ADD,
                    onClick = { update { it + FlagRecordList.emptyRecord(level.fields, alongside = it) } },
                    icon = Icons.Filled.Add,
                )
            }
        }
        if (isOverridden) {
            item(key = "reset") {
                FlagActionRow(
                    text = FlagStrings.RESET_TO_DEFAULT,
                    onClick = { writer.write { store.reset(entry) } },
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun RecordRow(
    title: String,
    detail: String,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.monospaced(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
        FlagRowActions(index = index, count = count, onMove = onMove, onDelete = onDelete, onDuplicate = onDuplicate)
    }
}

/** A shared key is named, because a well-formed list breaking that rule needs a different search. */
private fun unreadableReason(level: RecordLevel): String {
    val key = level.fields.firstOrNull { it.isKey }
    val duplicate = FlagRecordList.duplicateKey(level.raw, level.fields)
    return if (key != null && duplicate != null) {
        FlagStrings.duplicateKeyReason(key.name, FlagValueText.format(duplicate))
    } else {
        FlagStrings.NOT_A_LIST_OF_RECORDS
    }
}

/** The field a record is known by: its key field, else its first. */
private fun titleField(fields: List<FlagRecordField>): FlagRecordField? = fields.firstOrNull { it.isKey } ?: fields.firstOrNull()

/** The title field's value text; "Untitled" when that is empty, "Record" when there is none. */
internal fun recordTitle(record: Map<String, FlagValueBox>, fields: List<FlagRecordField>): String {
    val box = titleField(fields)?.let { record[it.name] } ?: return FlagStrings.RECORD
    return FlagValueText.format(box).ifEmpty { FlagStrings.UNTITLED }
}

/** Every other field as `name: value`, two spaces apart; a nested list shows how many records it holds. */
internal fun recordDetail(record: Map<String, FlagValueBox>, fields: List<FlagRecordField>): String {
    val title = titleField(fields)
    return fields.filter { it != title }.joinToString("  ") { field -> "${field.name}: ${fieldSummary(record[field.name], field)}" }
}

private fun fieldSummary(box: FlagValueBox?, field: FlagRecordField): String {
    if (box == null) return FlagStrings.MISSING_VALUE
    val nested = field.fields ?: return FlagValueText.format(box)
    return (FlagRecordList.decode(box, nested)?.size ?: 0).toString()
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/RecordScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant

/** One record, a row per field (iOS FlagRecordEditorView). Edits commit as they are made. */
@Composable
internal fun RecordScreen(
    store: FlagEditingStore,
    key: String,
    trail: List<RecordStep>,
    index: Int,
    onOpen: (FlagRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val entry = store.entry(FlagKey(key))
    val level = entry?.let { store.recordLevel(it, trail) }
    val record = level?.records?.getOrNull(index)
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(
            title = if (level != null && record != null) recordTitle(record, level.fields) else FlagStrings.RECORD,
            onBack = onBack,
        )
        if (entry != null && record != null) {
            RecordContent(store = store, entry = entry, trail = trail, index = index, onOpen = onOpen)
        }
    }
}

@Composable
private fun RecordContent(
    store: FlagEditingStore,
    entry: FlagSchema.Entry,
    trail: List<RecordStep>,
    index: Int,
    onOpen: (FlagRoute) -> Unit,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val level = store.recordLevel(entry, trail) ?: return
    val records = level.records ?: return
    val record = records.getOrNull(index) ?: return
    val others = records.filterIndexed { position, _ -> position != index }
    var failedWrites by remember { mutableIntStateOf(0) }
    val update: (FlagRecordField, FlagValueBox) -> Unit = { field, box ->
        writer.write(onFailed = { failedWrites++ }) {
            val current = store.recordLevel(entry, trail)?.records ?: return@write
            val target = current.getOrNull(index) ?: return@write
            store.setRecordLevel(entry, trail, current.replacing(index, target + (field.name to box)))
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(level.fields, key = { it.name }) { field ->
            RecordFieldRow(
                field = field,
                value = record[field.name] ?: emptyFieldValue(field),
                // Compared as values, as iOS does: a key another record already uses is refused before it is written.
                keysInUse = if (field.isKey) others.mapNotNull { it[field.name] }.toSet() else emptySet(),
                revertKey = failedWrites,
                onChange = { box -> update(field, box) },
                onOpenNested = { onOpen(FlagRoute.Records(entry.key.rawValue, trail + RecordStep(index, field.name))) },
            )
        }
    }
}

@Composable
private fun RecordFieldRow(
    field: FlagRecordField,
    value: FlagValueBox,
    keysInUse: Set<FlagValueBox>,
    revertKey: Int,
    onChange: (FlagValueBox) -> Unit,
    onOpenNested: () -> Unit,
) {
    val nested = field.fields
    val cases = field.cases.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = field.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = field.type.typeName,
            style = MaterialTheme.typography.labelSmall.monospaced(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            nested != null -> FlagNavigationRow(onClick = onOpenNested) {
                Text(
                    text = nestedSummary(value, nested),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cases.isNotEmpty() -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FlagPicker(selected = value, options = cases, onSelect = onChange)
            }
            field.type == FlagValueType.Bool -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Switch(
                    checked = (value as? FlagValueBox.Bool)?.value ?: false,
                    onCheckedChange = { checked -> onChange(FlagValueBox.Bool(checked)) },
                    modifier = Modifier.semantics { contentDescription = field.name },
                )
            }
            field.type == FlagValueType.Date -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FlagDateTimeButton(
                    instant = (value as? FlagValueBox.Date)?.value ?: Instant.now(),
                    onPick = { instant -> onChange(FlagValueBox.Date(instant)) },
                )
            }
            else -> FlagTextField(
                value = FlagValueText.format(value),
                onCommit = { text ->
                    val box = FlagValueText.parse(text, field.type)?.takeUnless { it in keysInUse }
                    if (box != null) onChange(box)
                    box != null
                },
                keyboardType = keyboardTypeFor(field.type),
                revertKey = revertKey,
            )
        }
    }
}

/** What a field starts as: its default, else its first case, else its type's empty value. */
internal fun emptyFieldValue(field: FlagRecordField): FlagValueBox =
    field.defaultValue ?: field.cases?.firstOrNull() ?: FlagValueText.empty(field.type)

private fun nestedSummary(value: FlagValueBox, fields: List<FlagRecordField>): String =
    FlagRecordList.decode(value, fields)?.let { FlagStrings.recordCount(it.size) } ?: FlagStrings.UNREADABLE
```

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.ListEditorScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.RecordsScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.RecordScreenTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `ListEditorScreenTest` `tests="9"`, `RecordsScreenTest` `tests="11"`, `RecordScreenTest` `tests="6"`, all `failures="0"` (26 tests).

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens
git commit -m "Add the list editor, the records list and the record screen with unique keys" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task B4: Overrides, the import sheet, the QR sheet and `FlagFileProvider`

**Files:**
- Modify: `featureflag-ui/src/main/AndroidManifest.xml` (full replacement of A2's empty manifest)
- Create: `featureflag-ui/src/main/res/xml/featureflag_file_paths.xml`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/share/FlagFileProvider.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/share/FlagQrImage.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagSheet.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/OverridesScreen.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/ImportSheet.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/QrSheet.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/share/FlagQrImageTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ImportFailureTextTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/OverridesScreenTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ImportSheetTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/QrSheetTest.kt`

**Interfaces:**
- Consumes: B1 (`FlagTopBar`, `FlagSectionHeader`, `FlagSectionFooter`, `FlagContentUnavailable`, `FlagActionRow`, `FlagCopyButton`, `COPIED_FEEDBACK_MILLIS`, `FlagClipboard`, `FlagIcons`, `LocalFlagWriter`, test support); A3 `overriddenKeys`, `entry`, `value`, `reset`, `resetAll`, `exportJson`, `qrCode`, `copyableQrCode`, `import`; core `FlagImportException`, `FlagImportProblem`, `FlagQRCodeException.PayloadTooLarge`, `FlagSerializationException`; androidx.core `FileProvider`, `IntentCompat`; ZXing `QRCodeWriter`.
- Produces:

```kotlin
// com.andyyhope.featureflag.ui.share — the contract's public class
public class FlagFileProvider : FileProvider()
internal object FlagQrImage {
    fun bitmap(code: String, scale: Int = MODULE_PIXELS): Bitmap       // ZXing, error correction L, nearest-neighbour modules
    fun png(bitmap: Bitmap): ByteArray
    fun writePng(context: Context, bitmap: Bitmap, name: String): File  // <cacheDir>/featureflag/<name>.png
    fun uri(context: Context, file: File): Uri                          // through FlagFileProvider
    fun authority(context: Context): String                             // "<packageName>.featureflag.files"
}
// com.andyyhope.featureflag.ui.screens
@Composable internal fun OverridesScreen(store: FlagEditingStore, modifier: Modifier = Modifier)
@Composable internal fun ImportSheet(store: FlagEditingStore, onDismiss: () -> Unit)
@Composable internal fun QrSheet(store: FlagEditingStore, onDismiss: () -> Unit)
@Composable internal fun FlagSheet(title: String, onDismiss: () -> Unit, navigation: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit)
internal fun describeImportFailure(error: Exception): String
internal suspend fun importOrDescribe(store: FlagEditingStore, text: String): String?
internal fun qrUnavailableMessage(code: Result<String>): String
internal fun exportedJson(store: FlagEditingStore): String
```

**Behaviour this task fixes (iOS `FlagOverridesView`, `FlagQRCodeView`, `FlagImportView`, `FlagImageExport`, `FlagPasteboard`):**
- **Overrides:** title "Overrides". Nothing overridden → "Nothing overridden" / "Every flag is reporting the value the app was built with.". Otherwise: header "N changed"; a row per overridden flag in schema order (description, key, reset arrow, the value text, at most 6 lines, selectable); footer "Tap the arrow…"; header "JSON"; the export (`exportJson()`, `{}` when it throws) with a "Copy JSON" button; its footer; then "Show QR code", "Share JSON" (an `ACTION_SEND` `text/plain` chooser) and "Reset every override". The import action ("Import…" and its footer) always shows.
- **Reset every override asks first** (overview deviation 5): an `AlertDialog` titled "Reset every override?", text "Every flag goes back to the value the app was built with.", buttons "Reset" and "Cancel". A failure goes to the shell's snackbar (deviation 6; iOS's "Could not do that" alert is not ported).
- **Import sheet:** a full-screen sheet titled "Import" with "Cancel" and "Import" (disabled while the text is empty); help text; a monospaced editor at least 160 dp tall; the problem in the error colour. Import calls `store.import(text)` (which trims and tells a code from a document); success closes the sheet, failure keeps it open with the text. Wording (iOS `describe`): `Rejected` → one line per problem, "<key>: not a flag in this app" / "<key>: wrong type" / "<key>: not one of its choices"; `UnsupportedFormatVersion` → "This document was written by a newer version (format <v>)."; `Malformed` → "That is not a flag document."; anything else → its message.
- **QR sheet:** titled "Scan to copy" with "Done". The code image (ZXing, error correction L, 8 px per module, drawn nearest-neighbour, at most 320 dp wide, described "QR code containing N overrides"); "Copy image" (the PNG through `FlagFileProvider` as a `ClipData` URI, then "Image copied" for 1.4 s) and "Save" (an `ACTION_SEND` `image/png` chooser with read permission); "Copy code" (the `FFQR1:` text, then "Code copied") when `copyableQrCode` is not null; the footnote "N override(s)". A code that does not fit says "<n> overrides need <bytes> characters, and a QR code holds <limit>. Reset a few, or export JSON instead."; an image that cannot be drawn says "Could not render the code.". The PNG is `feature-flags-<n>-overrides.png` in `cacheDir/featureflag/`, written when it is copied or saved (PNG, never JPEG: it keeps the modules' hard edges).
- **`FlagFileProvider`** is registered with authority `${applicationId}.featureflag.files`, not exported, granting URI permissions, serving only `cacheDir/featureflag/`.

- [ ] **Step 1: Write the failing tests**

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/share/FlagQrImageTest.kt` (iOS `QRCodeExportTests`' PNG checks, against ZXing)
```kotlin
package com.andyyhope.featureflag.ui.share

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagQrImageTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private fun code(): String {
        val schema = FlagSchema(
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            flags = listOf(
                FlagSchema.Entry(
                    key = FlagKey("new-onboarding"),
                    propertyPath = listOf("newOnboarding"),
                    description = "Show the redesigned onboarding",
                    valueType = FlagValueType.Bool,
                    defaultValue = FlagValueBox.Bool(false),
                ),
            ),
        )
        val source = SnapshotSource(values = mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))
        return FlagEditingStore(schema, source).qrCode()
    }

    @Test
    fun theCodeEncodesAsAPng() {
        val png = FlagQrImage.png(FlagQrImage.bitmap(code(), scale = 4))

        // PNG, not JPEG: JPEG smears a QR code's hard edges into exactly what a scanner struggles with.
        assertContentEquals(pngMagic, png.copyOfRange(0, 4))
        assertTrue(png.size > 100)
    }

    @Test
    fun savingWritesAFileThatExists() {
        val file = FlagQrImage.writePng(context, FlagQrImage.bitmap(code(), scale = 4), "qr-export-${UUID.randomUUID()}")

        assertEquals("png", file.extension)
        assertTrue(file.exists())
        assertContentEquals(pngMagic, file.readBytes().copyOfRange(0, 4))
        file.delete()
    }

    @Test
    fun theImageReadsBackAsTheSameCodeAtLevelL() {
        val code = code()
        val bitmap: Bitmap = FlagQrImage.bitmap(code)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val result = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )

        assertEquals(code, result.text)
        assertEquals("L", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun theFileIsServedByTheLibrarysProvider() {
        // create() reads the ProviderInfo the merged manifest declares, meta-data and grantUriPermissions included.
        Robolectric.buildContentProvider(FlagFileProvider::class.java).create()
        val file = FlagQrImage.writePng(context, FlagQrImage.bitmap(code()), "feature-flags-1-overrides")

        val uri = FlagQrImage.uri(context, file)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.featureflag.files", uri.authority)
        val served = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertContentEquals(pngMagic, served.copyOfRange(0, 4))
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ImportFailureTextTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class ImportFailureTextTest {

    @Test
    fun aRejectedDocumentListsOneLinePerProblem() {
        val rejected = FlagImportException.Rejected(
            listOf(
                FlagImportProblem(FlagKey("max-items"), FlagImportProblem.Kind.TypeMismatch, "int", "\"lots\""),
                FlagImportProblem(FlagKey("nope"), FlagImportProblem.Kind.UnknownKey),
                FlagImportProblem(FlagKey("tier"), FlagImportProblem.Kind.UnknownCase, "free, pro", "\"gold\""),
            ),
        )

        assertEquals(
            "max-items: wrong type\nnope: not a flag in this app\ntier: not one of its choices",
            describeImportFailure(rejected),
        )
    }

    @Test
    fun aNewerDocumentNamesItsFormat() {
        assertEquals(
            "This document was written by a newer version (format 2).",
            describeImportFailure(FlagImportException.UnsupportedFormatVersion(2)),
        )
    }

    @Test
    fun aMalformedDocumentIsNotAFlagDocument() {
        assertEquals("That is not a flag document.", describeImportFailure(FlagImportException.Malformed("not a JSON object")))
    }

    @Test
    fun anythingElseIsItsOwnMessage() {
        assertEquals(
            "Not a flag code. A flag code starts with \"FFQR1:\".",
            describeImportFailure(FlagQRCodeException.UnrecognisedFormat()),
        )
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/OverridesScreenTest.kt` (iOS `testTheLandingPageCompanionCompiles`)
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverridesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(store: FlagEditingStore) {
        composeRule.setFlagContent { OverridesScreen(store = store) }
    }

    private fun overridden() = testStore(
        ScreenFixtures.companion,
        mapOf(FlagKey("max-items") to FlagValueBox.Int(3), FlagKey("new-onboarding") to FlagValueBox.Bool(true)),
    )

    private fun clickAction(text: String) {
        composeRule.scrollTo(text)
        composeRule.onNodeWithText(text).performClick()
    }

    // Listing

    @Test
    fun nothingOverriddenSaysSoAndStillOffersImport() {
        show(testStore(ScreenFixtures.companion))

        composeRule.onNodeWithText("Nothing overridden").assertExists()
        composeRule.onNodeWithText("Every flag is reporting the value the app was built with.").assertExists()
        composeRule.onNodeWithText("Import…").assertExists()
        composeRule.onNodeWithText("JSON").assertDoesNotExist()
    }

    @Test
    fun eachOverrideIsListedWithItsValue() {
        show(overridden())

        composeRule.onNodeWithText("2 changed").assertExists()
        composeRule.onNodeWithText("New onboarding experience").assertExists()
        composeRule.onNodeWithText("true").assertExists()
        composeRule.onNodeWithText("Maximum items").assertExists()
        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithText("Tap the arrow to put a flag back to the app's default.").assertExists()
    }

    @Test
    fun aStoredValueTheSchemaRejectsIsNotListedAsAnOverride() {
        show(testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.String("nonsense"))))

        composeRule.onNodeWithText("Nothing overridden").assertExists()
    }

    @Test
    fun theArrowPutsOneFlagBack() {
        val store = testStore(ScreenFixtures.companion, mapOf(FlagKey("max-items") to FlagValueBox.Int(3)))
        show(store)

        composeRule.onNodeWithContentDescription("Reset to the app's default").performClick()

        composeRule.onNodeWithText("Nothing overridden").assertExists()
        assertTrue(store.overriddenKeys.isEmpty())
    }

    // JSON

    @Test
    fun theJsonBlockCopiesTheExport() {
        show(overridden())

        composeRule.scrollTo("Only overrides travel \u2014 the defaults are already in the app. This is what a QR code carries, compressed.")
        composeRule.onNodeWithContentDescription("Copy JSON").performClick()

        val copied = checkNotNull(clipboardText())
        assertTrue(copied.startsWith("{") && "\"max-items\"" in copied && "\"new-onboarding\"" in copied, copied)
    }

    @Test
    fun shareJsonOffersTheExportToTheChooser() {
        show(overridden())

        clickAction("Share JSON")

        val chooser = checkNotNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue("\"max-items\"" in send.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
    }

    // Actions

    @Test
    fun resetEveryOverrideAsksFirst() {
        val store = overridden()
        show(store)

        clickAction("Reset every override")
        composeRule.onNodeWithText("Reset every override?").assertExists()
        composeRule.onNodeWithText("Every flag goes back to the value the app was built with.").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Reset every override?").assertDoesNotExist()
        assertEquals(2, store.overriddenKeys.size)

        clickAction("Reset every override")
        composeRule.onNodeWithText("Reset").performClick()

        composeRule.onNodeWithText("Nothing overridden").assertExists()
        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun showQrCodeOpensTheSheet() {
        show(overridden())

        clickAction("Show QR code")
        composeRule.onNodeWithText("Scan to copy").assertExists()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Scan to copy").assertDoesNotExist()
    }

    @Test
    fun importOpensTheSheet() {
        show(testStore(ScreenFixtures.companion))

        clickAction("Import…")

        composeRule.onNodeWithText("Paste exported JSON, or the contents of a scanned flag code.").assertExists()
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/ImportSheetTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ImportSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var isShown by mutableStateOf(true)

    private fun show(store: FlagEditingStore) {
        composeRule.setFlagContent {
            if (isShown) ImportSheet(store = store, onDismiss = { isShown = false })
        }
    }

    private fun importText(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(text)
        composeRule.onNode(hasText("Import") and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun importingJsonAppliesItAndCloses() {
        val store = testStore(ScreenFixtures.companion)
        show(store)

        importText("""{"formatVersion":1,"values":{"max-items":3}}""")

        assertEquals(FlagValueBox.Int(3), store.value(store.requireEntry("max-items")))
        assertFalse(isShown)
    }

    @Test
    fun importingACodeAppliesIt() {
        val exporter = testStore(ScreenFixtures.companion, mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))
        val store = testStore(ScreenFixtures.companion)
        show(store)

        importText("  ${exporter.qrCode()}\n")

        assertEquals(listOf(FlagKey("new-onboarding")), store.overriddenKeys)
        assertFalse(isShown)
    }

    @Test
    fun aRejectedDocumentListsEveryProblemAndStaysOpen() {
        val store = testStore(ScreenFixtures.companion)
        show(store)

        importText("""{"formatVersion":1,"values":{"nope":1,"max-items":"lots"}}""")

        composeRule.onNodeWithText("max-items: wrong type\nnope: not a flag in this app").assertExists()
        assertTrue(isShown)
        assertTrue(store.overriddenKeys.isEmpty())
    }

    @Test
    fun importIsDisabledUntilThereIsText() {
        show(testStore(ScreenFixtures.companion))
        val importButton = composeRule.onNode(hasText("Import") and hasClickAction())
        importButton.assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("{")

        importButton.assertIsEnabled()
    }

    @Test
    fun cancelChangesNothing() {
        val store = testStore(ScreenFixtures.companion)
        show(store)

        composeRule.onNode(hasSetTextAction()).performTextInput("""{"formatVersion":1,"values":{"max-items":3}}""")
        composeRule.onNodeWithText("Cancel").performClick()

        assertFalse(isShown)
        assertTrue(store.overriddenKeys.isEmpty())
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/QrSheetTest.kt` (iOS `testTheSheetBuilds`, `testCopyingPutsAnImageOnThePasteboard`; `FlagQRCodeTextTests` from the screen's side)
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var dismissals = 0

    private fun show(store: FlagEditingStore) {
        composeRule.setFlagContent { QrSheet(store = store, onDismiss = { dismissals++ }) }
    }

    private fun overridden() = testStore(ScreenFixtures.companion, mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))

    /** iOS FlagQRCodeTextTests' incompressible text: base64 of 6,000 bytes from a 64-bit LCG. */
    private fun incompressible(): String {
        var state = 0x9E3779B97F4A7C15uL
        val bytes = ByteArray(6_000) {
            state = state * 6364136223846793005uL + 1442695040888963407uL
            (state shr 33).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun theSheetBuilds() {
        show(overridden())

        composeRule.onNodeWithText("Scan to copy").assertExists()
        composeRule.onNodeWithContentDescription("QR code containing 1 overrides").assertExists()
        composeRule.onNodeWithText("Copy image").assertExists()
        composeRule.onNodeWithText("Save").assertExists()
        composeRule.onNodeWithText("Copy code").assertExists()
        composeRule.onNodeWithText("1 override(s)").assertExists()
    }

    @Test
    fun copyImagePutsThePngOnTheClipboard() {
        show(overridden())

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Copy image").performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Image copied").assertExists()
        val clip = checkNotNull(checkNotNull(context.getSystemService(ClipboardManager::class.java)).primaryClip)
        assertTrue(clip.description.hasMimeType("image/png"))
        assertEquals("${context.packageName}.featureflag.files", clip.getItemAt(0).uri.authority)
        assertTrue(File(context.cacheDir, "featureflag/feature-flags-1-overrides.png").exists())
    }

    @Test
    fun copyCodeCopiesTextTheImportFieldAccepts() {
        val store = overridden()
        show(store)

        composeRule.onNodeWithText("Copy code").performClick()

        assertEquals(store.qrCode(), clipboardText())
        assertTrue(store.qrCode().startsWith("FFQR1:"))
    }

    @Test
    fun saveOffersThePngToTheChooser() {
        show(overridden())

        composeRule.onNodeWithText("Save").performClick()

        val chooser = checkNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals("image/png", send.type)
        val stream = checkNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("${context.packageName}.featureflag.files", stream.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun aPayloadTooLargeExplainsItself() {
        val store = testStore(
            ScreenFixtures.companion,
            mapOf(FlagKey("checkout.express.label") to FlagValueBox.String(incompressible())),
        )
        val tooLarge = assertFailsWith<FlagQRCodeException.PayloadTooLarge> { store.qrCode() }
        show(store)

        composeRule.onNodeWithText(
            "1 overrides need ${tooLarge.bytes} characters, and a QR code holds ${tooLarge.limit}. " +
                "Reset a few, or export JSON instead.",
        ).assertExists()
        composeRule.onNodeWithText("Copy image").assertDoesNotExist()
        composeRule.onNodeWithText("Copy code").assertDoesNotExist()
    }

    @Test
    fun doneClosesTheSheet() {
        show(overridden())

        composeRule.onNodeWithText("Done").performClick()

        assertEquals(1, dismissals)
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.share.FlagQrImageTest' --tests 'com.andyyhope.featureflag.ui.screens.ImportFailureTextTest' --tests 'com.andyyhope.featureflag.ui.screens.OverridesScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.ImportSheetTest' --tests 'com.andyyhope.featureflag.ui.screens.QrSheetTest'
```

Expected: `BUILD FAILED` in `compileDebugUnitTestKotlin` with `Unresolved reference 'FlagQrImage'`, `'FlagFileProvider'`, `'describeImportFailure'`, `'OverridesScreen'`, `'ImportSheet'` and `'QrSheet'`.

- [ ] **Step 3: Implement**

**Modify** `featureflag-ui/src/main/AndroidManifest.xml` (full replacement; A2's is empty):
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <!-- Serves the QR sheet's PNG to the clipboard and the share sheet. Only cache/featureflag/ is reachable. -->
        <provider
            android:name="com.andyyhope.featureflag.ui.share.FlagFileProvider"
            android:authorities="${applicationId}.featureflag.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/featureflag_file_paths" />
        </provider>
    </application>
</manifest>
```

`featureflag-ui/src/main/res/xml/featureflag_file_paths.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- FlagQrImage.writePng writes here; nothing else in the app is exposed. -->
    <cache-path name="featureflag" path="featureflag/" />
</paths>
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/share/FlagFileProvider.kt`
```kotlin
package com.andyyhope.featureflag.ui.share

import androidx.core.content.FileProvider

/**
 * Serves the QR sheet's PNG, so it can be copied and shared. featureflag-ui's manifest registers it with
 * the authority `${applicationId}.featureflag.files`, unexported, for `cacheDir/featureflag/` only. A
 * companion app needs to do nothing: the manifest merges.
 */
public class FlagFileProvider : FileProvider()
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/share/FlagQrImage.kt`
```kotlin
package com.andyyhope.featureflag.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File

/** A flag code as a QR image (iOS qrCodeImage + FlagImageExport), and the PNG file the sheet copies and shares. */
internal object FlagQrImage {

    /** Pixels per module: iOS draws the sheet's code at scale 8. */
    const val MODULE_PIXELS = 8

    private const val DIRECTORY = "featureflag"
    private const val PNG_QUALITY = 100

    /**
     * The code at error correction L, as iOS uses: capacity matters more than damage tolerance for a code
     * read off a screen. Each module is [scale] square pixels, so the image scales without blur.
     */
    fun bitmap(code: String, scale: Int = MODULE_PIXELS): Bitmap {
        val matrix = QRCodeWriter().encode(
            code,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
        )
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { index ->
            if (matrix[(index % size) / scale, (index / size) / scale]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** PNG, never JPEG: JPEG smears the hard edges a scanner relies on. */
    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        output.toByteArray()
    }

    /** Writes `<cacheDir>/featureflag/<name>.png`, replacing it whole. */
    fun writePng(context: Context, bitmap: Bitmap, name: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, "$name.png")
        val partial = File(directory, "$name.png.partial")
        partial.writeBytes(png(bitmap))
        check(partial.renameTo(file)) { "Could not write $file." }
        return file
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The authority featureflag-ui's manifest gives FlagFileProvider. */
    fun authority(context: Context): String = "${context.packageName}.featureflag.files"
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagSheet.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** iOS's modal sheet: a full-screen dialog with its own title bar and buttons. */
@Composable
internal fun FlagSheet(
    title: String,
    onDismiss: () -> Unit,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                FlagTopBar(title = title, navigation = navigation, actions = actions)
                content()
            }
        }
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/OverridesScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagValueText

private enum class OverridesSheet { QrCode, Import }

private data class OverrideLine(val entry: FlagSchema.Entry, val text: String)

/**
 * The Overrides tab (iOS FlagOverridesView): what has been changed, the JSON that carries it, the QR
 * code, sharing, resetting, and importing.
 */
@Composable
internal fun OverridesScreen(store: FlagEditingStore, modifier: Modifier = Modifier) {
    val revision = store.observeRevision()
    val writer = LocalFlagWriter.current
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<OverridesSheet?>(null) }
    var isConfirmingReset by rememberSaveable { mutableStateOf(false) }
    val lines = store.overriddenKeys.mapNotNull { key ->
        store.entry(key)?.let { entry -> OverrideLine(entry, FlagValueText.format(store.value(entry))) }
    }
    // Keyed by revision: the export carries its own timestamp, so it is made once per change, not per frame.
    val json = remember(revision) { exportedJson(store) }
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = FlagStrings.OVERRIDES)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (lines.isEmpty()) {
                item(key = "nothing") {
                    FlagContentUnavailable(title = FlagStrings.NOTHING_OVERRIDDEN, message = FlagStrings.NOTHING_OVERRIDDEN_MESSAGE)
                }
            } else {
                item(key = "changed") { FlagSectionHeader(text = FlagStrings.changedCount(lines.size)) }
                items(lines, key = { "line:${it.entry.key.rawValue}" }) { line ->
                    OverrideRow(line = line, onReset = { writer.write { store.reset(line.entry) } })
                }
                item(key = "lines-footer") { FlagSectionFooter(text = FlagStrings.OVERRIDES_FOOTER) }
                item(key = "json-header") { FlagSectionHeader(text = FlagStrings.JSON) }
                item(key = "json") { JsonBlock(json = json) }
                item(key = "json-footer") { FlagSectionFooter(text = FlagStrings.JSON_FOOTER) }
                item(key = "qr") {
                    FlagActionRow(text = FlagStrings.SHOW_QR_CODE, onClick = { sheet = OverridesSheet.QrCode }, icon = FlagIcons.QrCode)
                }
                item(key = "share") {
                    FlagActionRow(text = FlagStrings.SHARE_JSON, onClick = { shareJson(context, json) }, icon = Icons.Filled.Share)
                }
                item(key = "reset-all") {
                    FlagActionRow(
                        text = FlagStrings.RESET_EVERY_OVERRIDE,
                        onClick = { isConfirmingReset = true },
                        icon = FlagIcons.Undo,
                        destructive = true,
                    )
                }
            }
            item(key = "import") {
                FlagActionRow(text = FlagStrings.IMPORT_ELLIPSIS, onClick = { sheet = OverridesSheet.Import }, icon = FlagIcons.Download)
            }
            item(key = "import-footer") { FlagSectionFooter(text = FlagStrings.IMPORT_FOOTER) }
        }
    }
    if (isConfirmingReset) {
        ResetEveryOverrideDialog(
            onConfirm = {
                isConfirmingReset = false
                writer.write { store.resetAll() }
            },
            onDismiss = { isConfirmingReset = false },
        )
    }
    when (sheet) {
        OverridesSheet.QrCode -> QrSheet(store = store, onDismiss = { sheet = null })
        OverridesSheet.Import -> ImportSheet(store = store, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun OverrideRow(line: OverrideLine, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = line.entry.description, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = line.entry.key.rawValue,
                    style = MaterialTheme.typography.labelSmall.monospaced(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onReset) {
                Icon(imageVector = FlagIcons.Undo, contentDescription = FlagStrings.RESET_TO_DEFAULT)
            }
        }
        SelectionContainer {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium.monospaced(),
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun JsonBlock(json: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SelectionContainer {
            Text(
                text = json,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 48.dp, top = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall.monospaced(),
            )
        }
        FlagCopyButton(text = { json }, idleDescription = FlagStrings.COPY_JSON, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ResetEveryOverrideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = FlagStrings.RESET, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = FlagStrings.CANCEL) }
        },
        title = { Text(text = FlagStrings.RESET_EVERY_OVERRIDE_TITLE) },
        text = { Text(text = FlagStrings.RESET_EVERY_OVERRIDE_MESSAGE) },
    )
}

/** The export, or `{}` when a value cannot be written as JSON (iOS shows the same fallback). */
internal fun exportedJson(store: FlagEditingStore): String = try {
    store.exportJson()
} catch (ignored: FlagSerializationException) {
    "{}"
}

private fun shareJson(context: Context, json: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, json)
    val chooser = Intent.createChooser(send, null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/ImportSheet.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Paste an exported document or a scanned code (iOS FlagImportView). Import is all or nothing; a
 * refusal keeps the sheet and the text, and says why.
 */
@Composable
internal fun ImportSheet(store: FlagEditingStore, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FlagSheet(
        title = FlagStrings.IMPORT,
        onDismiss = onDismiss,
        navigation = { TextButton(onClick = onDismiss) { Text(text = FlagStrings.CANCEL) } },
        actions = {
            TextButton(
                onClick = {
                    scope.launch {
                        problem = importOrDescribe(store, text)
                        if (problem == null) onDismiss()
                    }
                },
                enabled = text.isNotEmpty(),
            ) { Text(text = FlagStrings.IMPORT) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = FlagStrings.IMPORT_HELP,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                textStyle = MaterialTheme.typography.bodyMedium.monospaced(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            problem?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Imports [text]; null when it was applied, else what to tell the person who pasted it. */
internal suspend fun importOrDescribe(store: FlagEditingStore, text: String): String? {
    val failure = runCatching { store.import(text) }.exceptionOrNull() ?: return null
    if (failure is CancellationException || failure !is Exception) throw failure
    return describeImportFailure(failure)
}

/** iOS FlagImportView.describe: short lines for a rejected document, the error's own message otherwise. */
internal fun describeImportFailure(error: Exception): String = when (error) {
    is FlagImportException.Rejected -> error.problems.joinToString("\n") { problem ->
        val key = problem.key.rawValue
        when (problem.kind) {
            FlagImportProblem.Kind.UnknownKey -> FlagStrings.notAFlagInThisApp(key)
            FlagImportProblem.Kind.TypeMismatch -> FlagStrings.wrongType(key)
            FlagImportProblem.Kind.UnknownCase -> FlagStrings.notOneOfItsChoices(key)
        }
    }
    is FlagImportException.UnsupportedFormatVersion -> FlagStrings.newerFormat(error.version)
    is FlagImportException.Malformed -> FlagStrings.NOT_A_FLAG_DOCUMENT
    else -> error.message ?: error.toString()
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/QrSheet.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.share.FlagClipboard
import com.andyyhope.featureflag.ui.share.FlagQrImage
import kotlinx.coroutines.delay

/**
 * The overrides as a QR code to scan with a second device (iOS FlagQRCodeView), with ways to get the code
 * off the screen without one: copy or share the image, or copy the code's text to paste into Import.
 */
@Composable
internal fun QrSheet(store: FlagEditingStore, onDismiss: () -> Unit) {
    val revision = store.observeRevision()
    val context = LocalContext.current
    val count = store.overriddenKeys.size
    val code = remember(revision) { runCatching { store.qrCode() } }
    val image = remember(code) { code.getOrNull()?.let { text -> runCatching { FlagQrImage.bitmap(text) }.getOrNull() } }
    val copyableCode = store.copyableQrCode
    val fileName = "feature-flags-$count-overrides"
    FlagSheet(
        title = FlagStrings.SCAN_TO_COPY,
        onDismiss = onDismiss,
        navigation = { TextButton(onClick = onDismiss) { Text(text = FlagStrings.DONE) } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = FlagStrings.qrCodeDescription(count),
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                    // Nearest neighbour: a smoothed module is a blurred one.
                    filterQuality = FilterQuality.None,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    FeedbackButton(label = FlagStrings.COPY_IMAGE, doneLabel = FlagStrings.IMAGE_COPIED, icon = FlagIcons.ContentCopy) {
                        FlagClipboard.copyImage(context, FlagQrImage.uri(context, FlagQrImage.writePng(context, image, fileName)))
                    }
                    TextButton(onClick = { sharePng(context, FlagQrImage.uri(context, FlagQrImage.writePng(context, image, fileName))) }) {
                        Icon(imageVector = FlagIcons.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = FlagStrings.SAVE)
                    }
                }
            } else {
                Text(
                    text = qrUnavailableMessage(code),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (copyableCode != null) {
                // No camera, no second device: paste the text into Import instead.
                FeedbackButton(label = FlagStrings.COPY_CODE, doneLabel = FlagStrings.CODE_COPIED, icon = FlagIcons.ContentCopy) {
                    FlagClipboard.copy(context, copyableCode)
                }
            }
            Text(
                text = FlagStrings.overrideCount(count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A text button that says it worked ("Image copied", "Code copied") for [COPIED_FEEDBACK_MILLIS]. */
@Composable
private fun FeedbackButton(label: String, doneLabel: String, icon: ImageVector, onClick: () -> Unit) {
    var isDone by remember { mutableStateOf(false) }
    LaunchedEffect(isDone) {
        if (isDone) {
            delay(COPIED_FEEDBACK_MILLIS)
            isDone = false
        }
    }
    TextButton(
        onClick = {
            onClick()
            isDone = true
        },
    ) {
        Icon(imageVector = if (isDone) Icons.Filled.Check else icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = if (isDone) doneLabel else label)
    }
}

/** Why there is no image: too many overrides for one code (always plural, as iOS words it), or a drawing failure. */
internal fun qrUnavailableMessage(code: Result<String>): String = when (val error = code.exceptionOrNull()) {
    null -> FlagStrings.COULD_NOT_RENDER
    is FlagQRCodeException.PayloadTooLarge -> FlagStrings.qrTooLarge(error.overrideCount, error.bytes, error.limit)
    else -> error.message ?: error.toString()
}

private fun sharePng(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip carries the grant to whichever app is chosen.
    send.clipData = ClipData.newRawUri(FlagStrings.SCAN_TO_COPY, uri)
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
```

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.share.FlagQrImageTest' --tests 'com.andyyhope.featureflag.ui.screens.ImportFailureTextTest' --tests 'com.andyyhope.featureflag.ui.screens.OverridesScreenTest' --tests 'com.andyyhope.featureflag.ui.screens.ImportSheetTest' --tests 'com.andyyhope.featureflag.ui.screens.QrSheetTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `FlagQrImageTest` 4, `ImportFailureTextTest` 4, `OverridesScreenTest` 9, `ImportSheetTest` 5, `QrSheetTest` 6, all `failures="0"` (28 tests). Lint reports no error for the merged provider.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src/main featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui
git commit -m "Add the overrides screen with JSON, QR code, sharing, a confirmed reset and import" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task B5: The Signals screen

**Files:**
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreen.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreenTest.kt`

**Interfaces:**
- Consumes: B1 (`FlagTopBar`, `FlagSectionHeader`, `FlagSectionFooter`, `FlagNavigationRow`, test support); A6 `FlagSignalGroup` (`title`, `signals`, `id`, `isNested`), `FlagSignalDelay` (`entries`, `label`), `FlagSignalCountdown` (`progress`, `end`, `duration`), `FlagSignalAttempt` (`signal`, `handled`, `at`, `outcome`), `FlagSignalsModel` (`delay`, `pending`, `countdown`, `inFlight`, `history`, `isDisabled`, `tapped`, `cancelPending`); core `FlagSignal`.
- Produces (all `internal`):

```kotlin
@Composable internal fun SignalsScreen(model: FlagSignalsModel, groups: List<FlagSignalGroup>, onOpenGroup: (FlagSignalGroup) -> Unit, modifier: Modifier = Modifier, title: String = FlagStrings.SIGNALS, clock: () -> Instant = Instant::now)
@Composable internal fun SignalGroupScreen(model: FlagSignalsModel, group: FlagSignalGroup, onBack: () -> Unit, modifier: Modifier = Modifier, clock: () -> Instant = Instant::now)
@Composable internal fun CancelPendingOnLeave(model: FlagSignalsModel)   // the shell puts this at the tab level
```

**Behaviour this task fixes (iOS `FlagSignalsView`):**
- **Delay:** header "Delay"; a single-choice segmented row "Instant", "3s", "5s", "10s", disabled while a send is pending; footer "Sent the moment you tap…" for Instant, else "Tap a signal, then switch to the app…".
- **Groups:** each non-nested group in order is a section (header = its title when not empty). A group with an empty title gets the footer "Signals carry no payload…", or "Tap again to cancel." while one is pending. Nested groups go under a "Groups" header as navigation rows `title` … `count`, each opening its own screen (title = the group's title) that repeats the delay picker, the signals without a header, and Recent.
- **A signal row:** its `signalDescription`; "Takes effect after a relaunch" when `requiresRestart`; at the trailing edge a spinner while its instant send is in flight, a 20 dp countdown ring while it is pending, otherwise a send icon. Rows are disabled by `model.isDisabled(signal)`; the pending one stays enabled, so tapping it again cancels.
- **The ring is drawn from the clock.** Its progress starts at `countdown.progress(clock())` and animates linearly to 1 over the time left to `countdown.end`, filling clockwise from the top (`CircularProgressIndicator` with a 20% track and round caps). Its `contentDescription` is "Sending in <n> seconds. Tap to cancel.".
- **Recent** (only when there is history): each attempt's description, its local time (`FormatStyle.MEDIUM`, as iOS's `.standard`), and `outcome` in `colorScheme.primary` when handled, else `colorScheme.error`.
- **Leaving cancels.** `CancelPendingOnLeave` calls `model.cancelPending()` when it leaves the composition. The shell places it at the Signals tab's root, so pushing a nested group does not cancel, but switching tabs does. iOS's "The App Group is unavailable…" line has no Android counterpart: the bridge client is always there.

- [ ] **Step 1: Write the failing tests**

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreenTest.kt` (iOS `testTheGroupedViewBuilds`, `testTheSingleTypeFormIsOneFlatGroup`)
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalGroupDisplay
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SignalsScreenTest {

    private enum class AppSignal(override val signalDescription: String) : FlagSignal {
        RefetchRemoteConfiguration("Re-fetch remote configuration"),
        ClearRemoteConfiguration("Clear remote configuration"),
    }

    private enum class CacheSignal(override val signalDescription: String, override val requiresRestart: Boolean) : FlagSignal {
        PurgeImageCache("Purge the image cache", requiresRestart = false),
        PurgeEverything("Purge everything", requiresRestart = true),
    }

    private enum class SevenSignal : FlagSignal { One, Two, Three, Four, Five, Six, Seven }

    /** The bridge client's send: records each call, answers [answer], and waits on [gate] when one is set. */
    private class FakeSend {
        val sent = mutableListOf<Pair<FlagSignal, Duration>>()
        var answer = true
        var gate: CompletableDeferred<Unit>? = null
        var wasCancelled = false

        suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
            sent += signal to delay
            try {
                gate?.await()
            } catch (cancelled: CancellationException) {
                wasCancelled = true
                throw cancelled
            }
            return answer
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val fake = FakeSend()
    private val opened = mutableListOf<FlagSignalGroup>()
    private val start: Instant = Instant.parse("2026-01-01T15:04:05Z")
    private lateinit var model: FlagSignalsModel

    private val grouped = listOf(
        FlagSignalGroup("Configuration", AppSignal.entries),
        FlagSignalGroup("Caches", CacheSignal.entries),
    )

    private fun show(groups: List<FlagSignalGroup>, isShown: () -> Boolean = { true }) {
        composeRule.setFlagContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = fake::send, scope = scope, clock = { start }) }
            if (isShown()) {
                CancelPendingOnLeave(model)
                SignalsScreen(model = model, groups = groups, onOpenGroup = { opened += it }, clock = { start })
            }
        }
    }

    private fun ring() = composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true)

    // Layout

    @Test
    fun theGroupedViewBuilds() {
        show(grouped)

        composeRule.onNodeWithText("Signals").assertExists()
        composeRule.onNodeWithText("Delay").assertExists()
        listOf("Instant", "3s", "5s", "10s").forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithText("Configuration").assertExists()
        composeRule.onNodeWithText("Caches").assertExists()
        composeRule.onNodeWithText("Re-fetch remote configuration").assertExists()
        composeRule.onNodeWithText("Purge everything").assertExists()
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun theSingleTypeFormIsOneFlatGroup() {
        show(listOf(FlagSignalGroup("", AppSignal.entries, display = FlagSignalGroupDisplay.Flat)))

        composeRule.onNodeWithText("Re-fetch remote configuration").assertExists()
        composeRule.onNodeWithText("Signals carry no payload. State belongs in flags, which you can already edit.").assertExists()
    }

    @Test
    fun theDelayFooterFollowsTheChoice() {
        show(grouped)
        composeRule.onNodeWithText(
            "Sent the moment you tap. The app has to be running to hear it, and opening this one puts it in the background.",
        ).assertExists()

        composeRule.onNodeWithText("5s").performClick()

        composeRule.onNodeWithText("5s").assertIsSelected()
        composeRule.onNodeWithText(
            "Tap a signal, then switch to the app. It fires when the circle completes, by which time the app is in " +
                "front and listening.",
        ).assertExists()
        assertEquals(FlagSignalDelay.Five, model.delay.value)
    }

    @Test
    fun aGroupPastTheThresholdIsNestedUnderGroups() {
        val seven = FlagSignalGroup("Seven", SevenSignal.entries)
        show(listOf(seven))

        composeRule.onNodeWithText("Groups").assertExists()
        composeRule.onNodeWithText("One").assertDoesNotExist()
        composeRule.onNode(hasText("Seven") and hasText("7")).performClick()

        assertEquals(listOf(seven), opened)
    }

    // Sending

    @Test
    fun anInstantSignalIsSentAndItsOutcomeRecorded() {
        show(grouped)

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        composeRule.onNodeWithText("Recent").assertExists()
        composeRule.onNodeWithText("handled").assertExists()
        assertEquals(listOf<Pair<FlagSignal, Duration>>(AppSignal.RefetchRemoteConfiguration to Duration.ZERO), fake.sent)
    }

    @Test
    fun aSignalThatNeedsARelaunchSaysSoBeforeAndAfter() {
        show(grouped)
        composeRule.onNodeWithText("Takes effect after a relaunch").assertExists()

        composeRule.onNodeWithText("Purge everything").performClick()

        composeRule.onNodeWithText("handled — relaunch to see it").assertExists()
    }

    @Test
    fun anUnansweredSignalIsNoResponse() {
        fake.answer = false
        show(grouped)

        composeRule.onNodeWithText("Clear remote configuration").performClick()

        composeRule.onNodeWithText("no response").assertExists()
    }

    @Test
    fun eachResultShowsItsLocalTime() {
        show(grouped)

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(start.atZone(ZoneId.systemDefault()))
        composeRule.onNodeWithText(time).assertExists()
    }

    @Test
    fun theHistoryKeepsTheFiveNewest() {
        show(listOf(FlagSignalGroup("Configuration", listOf(AppSignal.RefetchRemoteConfiguration))))

        repeat(6) {
            composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
            composeRule.waitForIdle()
        }

        composeRule.onAllNodesWithText("handled").assertCountEquals(5)
    }

    @Test
    fun aSpinnerShowsWhileAnInstantSendIsInFlight() {
        fake.gate = CompletableDeferred()
        composeRule.mainClock.autoAdvance = false
        show(grouped)
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.mainClock.advanceTimeBy(100)

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Clear remote configuration").assertIsNotEnabled()
    }

    // Delayed signals

    @Test
    fun aDelayedSignalShowsTheRingAndTappingAgainCancels() {
        fake.gate = CompletableDeferred()
        show(listOf(FlagSignalGroup("", AppSignal.entries, display = FlagSignalGroupDisplay.Flat)))

        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        ring().assertExists()
        composeRule.onNodeWithText("Tap again to cancel.").assertExists()
        assertEquals(listOf<Pair<FlagSignal, Duration>>(AppSignal.RefetchRemoteConfiguration to 3.seconds), fake.sent)

        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        ring().assertDoesNotExist()
        assertTrue(fake.wasCancelled)
        assertNull(model.pending.value)
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun otherSignalsAndTheDelayAreDisabledDuringACountdown() {
        fake.gate = CompletableDeferred()
        show(grouped)

        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()

        composeRule.onNodeWithText("Re-fetch remote configuration").assertIsEnabled()
        composeRule.onNodeWithText("Clear remote configuration").assertIsNotEnabled()
        composeRule.onNodeWithText("5s").assertIsNotEnabled()
    }

    @Test
    fun theRingFillsAsTheDelayRunsDown() {
        fake.gate = CompletableDeferred()
        show(grouped)
        composeRule.onNodeWithText("3s").performClick()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        ring().assert(progressNear(0f))

        composeRule.mainClock.advanceTimeBy(1_500)

        ring().assert(progressNear(0.5f))
    }

    @Test
    fun leavingTheTabCancelsThePendingSend() {
        fake.gate = CompletableDeferred()
        var isShown by mutableStateOf(true)
        show(grouped, isShown = { isShown })
        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.waitForIdle()

        isShown = false
        composeRule.waitForIdle()

        assertNull(model.pending.value)
        assertTrue(fake.wasCancelled)
        assertTrue(model.history.value.isEmpty())
    }

    // A group on its own screen

    @Test
    fun theGroupScreenRepeatsTheDelayAndTheHistory() {
        val seven = FlagSignalGroup("Seven", SevenSignal.entries)
        composeRule.setFlagContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = fake::send, scope = scope, clock = { start }) }
            SignalGroupScreen(model = model, group = seven, onBack = {}, clock = { start })
        }

        // The title bar and the seventh signal.
        composeRule.onAllNodesWithText("Seven").assertCountEquals(2)
        composeRule.onNodeWithText("Delay").assertExists()
        composeRule.onNodeWithText("One").performClick()

        composeRule.onNodeWithText("Recent").assertExists()
    }

    private fun progressNear(expected: Float) = SemanticsMatcher("progress near $expected") { node ->
        val progress = node.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }?.current
        progress != null && abs(progress - expected) < 0.05f
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.SignalsScreenTest'
```

Expected: `BUILD FAILED` in `compileDebugUnitTestKotlin` with `Unresolved reference 'SignalsScreen'`, `'SignalGroupScreen'` and `'CancelPendingOnLeave'`.

- [ ] **Step 3: Implement**

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreen.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.signals.FlagSignalAttempt
import com.andyyhope.featureflag.ui.signals.FlagSignalCountdown
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The model's state, collected once per screen. */
private class SignalsState(
    val delay: State<FlagSignalDelay>,
    val pending: State<FlagSignal?>,
    val countdown: State<FlagSignalCountdown?>,
    val inFlight: State<FlagSignal?>,
    val history: State<List<FlagSignalAttempt>>,
)

@Composable
private fun FlagSignalsModel.collectState(): SignalsState = SignalsState(
    delay = delay.collectAsState(),
    pending = pending.collectAsState(),
    countdown = countdown.collectAsState(),
    inFlight = inFlight.collectAsState(),
    history = history.collectAsState(),
)

/**
 * The Signals tab (iOS FlagSignalsView): a delay, the signals by group, and what happened to the last
 * five. Groups past the nesting threshold get their own screen through [onOpenGroup].
 */
@Composable
internal fun SignalsScreen(
    model: FlagSignalsModel,
    groups: List<FlagSignalGroup>,
    onOpenGroup: (FlagSignalGroup) -> Unit,
    modifier: Modifier = Modifier,
    title: String = FlagStrings.SIGNALS,
    clock: () -> Instant = Instant::now,
) {
    val state = model.collectState()
    val nested = groups.filter { it.isNested }
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = title)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            delayItems(model, state)
            groups.filterNot { it.isNested }.forEach { group ->
                signalItems(model = model, state = state, group = group, showsHeader = true, clock = clock)
            }
            if (nested.isNotEmpty()) {
                item(key = "groups") { FlagSectionHeader(text = FlagStrings.GROUPS) }
                items(nested, key = { "nested:${it.id}" }) { group ->
                    FlagNavigationRow(
                        onClick = { onOpenGroup(group) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(text = group.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${group.signals.size}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            historyItems(state)
        }
    }
}

/**
 * One group on its own screen. The delay is repeated rather than left on the previous screen: it is the
 * mechanism, and going back to change it would defeat it. It shares the tab's model, so a pending send
 * and the history survive the push.
 */
@Composable
internal fun SignalGroupScreen(
    model: FlagSignalsModel,
    group: FlagSignalGroup,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Instant = Instant::now,
) {
    val state = model.collectState()
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = group.title, onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            delayItems(model, state)
            signalItems(model = model, state = state, group = group, showsHeader = false, clock = clock)
            historyItems(state)
        }
    }
}

/** Leaving the Signals tab cancels a pending send, so the host never delivers one nobody is watching. */
@Composable
internal fun CancelPendingOnLeave(model: FlagSignalsModel) {
    DisposableEffect(model) {
        onDispose { model.cancelPending() }
    }
}

private fun LazyListScope.delayItems(model: FlagSignalsModel, state: SignalsState) {
    item(key = "delay-header") { FlagSectionHeader(text = FlagStrings.DELAY) }
    item(key = "delay") {
        val options = FlagSignalDelay.entries
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == state.delay.value,
                    onClick = { model.delay.value = option },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    enabled = state.pending.value == null,
                    label = { Text(text = option.label) },
                )
            }
        }
    }
    item(key = "delay-footer") {
        FlagSectionFooter(
            text = if (state.delay.value == FlagSignalDelay.Instant) {
                FlagStrings.DELAY_FOOTER_INSTANT
            } else {
                FlagStrings.DELAY_FOOTER_DELAYED
            },
        )
    }
}

private fun LazyListScope.signalItems(
    model: FlagSignalsModel,
    state: SignalsState,
    group: FlagSignalGroup,
    showsHeader: Boolean,
    clock: () -> Instant,
) {
    if (showsHeader && group.title.isNotEmpty()) {
        item(key = "header:${group.id}") { FlagSectionHeader(text = group.title) }
    }
    items(group.signals, key = { "signal:${group.id}:${it.name}" }) { signal ->
        val pending = state.pending.value
        val countdown = state.countdown.value
        SignalRow(
            signal = signal,
            isInFlight = state.inFlight.value == signal,
            countdown = countdown?.takeIf { pending == signal },
            isEnabled = !model.isDisabled(signal),
            onClick = { model.tapped(signal) },
            clock = clock,
        )
    }
    // The untitled group is iOS's single-type form; only it explains signals and how to cancel.
    if (showsHeader && group.title.isEmpty()) {
        item(key = "footer:${group.id}") {
            FlagSectionFooter(text = if (state.pending.value == null) FlagStrings.SIGNALS_FOOTER else FlagStrings.TAP_AGAIN_TO_CANCEL)
        }
    }
}

private val TimeText: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

/** Read while the list is built, which LazyColumn tracks, so a new attempt adds its row. */
private fun LazyListScope.historyItems(state: SignalsState) {
    val history = state.history.value
    if (history.isEmpty()) return
    item(key = "recent") { FlagSectionHeader(text = FlagStrings.RECENT) }
    items(history) { attempt -> HistoryRow(attempt) }
}

@Composable
private fun SignalRow(
    signal: FlagSignal,
    isInFlight: Boolean,
    countdown: FlagSignalCountdown?,
    isEnabled: Boolean,
    onClick: () -> Unit,
    clock: () -> Instant,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = signal.signalDescription,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEnabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
            )
            if (signal.requiresRestart) {
                Text(
                    text = FlagStrings.TAKES_EFFECT_AFTER_A_RELAUNCH,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        when {
            isInFlight -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            countdown != null -> CountdownRing(countdown = countdown, clock = clock)
            else -> Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = if (isEnabled) colors.primary else colors.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

/** Fills clockwise from the top as the delay runs down, from the clock rather than from a count of frames. */
@Composable
private fun CountdownRing(countdown: FlagSignalCountdown, clock: () -> Instant) {
    val progress = remember(countdown) { Animatable(countdown.progress(clock())) }
    LaunchedEffect(countdown) {
        val remaining = java.time.Duration.between(clock(), countdown.end).toMillis().coerceAtLeast(0)
        progress.animateTo(1f, tween(durationMillis = remaining.toInt(), easing = LinearEasing))
    }
    val description = FlagStrings.sendingIn(countdown.duration.inWholeSeconds)
    CircularProgressIndicator(
        progress = { progress.value },
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = description },
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
        strokeCap = StrokeCap.Round,
    )
}

@Composable
private fun HistoryRow(attempt: FlagSignalAttempt) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = attempt.signal.signalDescription, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = TimeText.format(attempt.at.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = attempt.outcome,
            style = MaterialTheme.typography.bodyMedium,
            color = if (attempt.handled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.screens.SignalsScreenTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `SignalsScreenTest` `tests="15" failures="0"`.

- [ ] **Step 5: Commit**

```bash
git add featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreen.kt featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreenTest.kt
git commit -m "Add the signals screen with delays, the countdown ring and recent results" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task B6: The `FlagCompanion` shell and the on-device smoke test

**Files:**
- Modify: `featureflag-ui/build.gradle.kts` (exact replace pair: androidTest Compose dependencies)
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagCompanion.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/StatusScreens.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/CompanionShell.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/TabNavigation.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/FlagCompanionTabTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/CompanionShellTest.kt`
- Test: `featureflag-ui/src/androidTest/kotlin/com/andyyhope/featureflag/ui/FlagCompanionSmokeTest.kt`

**Interfaces:**
- Consumes: B1–B5 screens; A4 `FlagCompanionState`, `FlagCompanionSession` (`state`, `retry`, `refresh`, `onForeground`), `FlagCompanionViewModel` (the seam's `session` and `send`); A6 `FlagSignalGroup`, `duplicateSignalName`, `FlagSignalsModel`; `FlagBridgeException`.
- Produces (public, exactly the contract):

```kotlin
// package com.andyyhope.featureflag.ui
@Composable public fun FlagCompanion(hostPackage: String, modifier: Modifier = Modifier, tabs: List<FlagCompanionTab> = FlagCompanionTab.Defaults, timeout: Duration = 5.seconds)
public sealed interface FlagCompanionTab {
    public val id: String
    public val title: String
    public data object Overrides : FlagCompanionTab
    public data object Flags : FlagCompanionTab
    public class Signals(vararg groups: FlagSignalGroup, override val title: String = "Signals") : FlagCompanionTab { public val groups: List<FlagSignalGroup> }
    public class Detail(public val key: String, override val title: String) : FlagCompanionTab
    public class Custom(override val id: String, override val title: String, public val icon: ImageVector, public val content: @Composable FlagCompanionScope.() -> Unit) : FlagCompanionTab
    public companion object { public val Defaults: List<FlagCompanionTab> }
}
public interface FlagCompanionScope { public val store: FlagEditingStore; @Composable public fun FlagRow(entry: FlagSchema.Entry) }
```

and, `internal`:

```kotlin
internal fun List<FlagCompanionTab>.duplicateTabId(): String?
internal fun requireDistinctTabIds(tabs: List<FlagCompanionTab>)          // IllegalArgumentException with the iOS message
internal fun FlagCompanionTab.icon(selected: Boolean): ImageVector
// .ui.screens
@Composable internal fun FlagCompanionContent(state: FlagCompanionState, tabs: List<FlagCompanionTab>, send: suspend (FlagSignal, Duration) -> Boolean, onRetry: () -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun OnEveryStartAfterTheFirst(onStart: () -> Unit)
@Composable internal fun ConnectingScreen(modifier: Modifier = Modifier)
@Composable internal fun UnavailableScreen(title: String, message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier)
internal val RouteStacksSaver: Saver<SnapshotStateMap<String, List<FlagRoute>>, Bundle>
```

**How the shell is built:**
- `FlagCompanion` checks the tab ids, gets the `FlagCompanionViewModel` keyed `"featureflag:$hostPackage"`, collects `session.state` with the lifecycle, calls `session.onForeground()` on every `ON_START` after the first, and hands the rest to `FlagCompanionContent`.
- **States:** `Connecting` → a centred `CircularProgressIndicator`; `Unavailable(message)` → "No flags yet", the message and "Try again" (`retry`); `Ready` with no tabs → "No tabs" and its message, no button; `Ready` → the tabs.
- **Tabs:** a `Scaffold` with a `NavigationBar`. Each item shows the tab's title and its icon: outlined, or filled when selected (Overrides: `FlagIcons.Tune` both ways; Flags: flag; Signals: `Icons.AutoMirrored.Outlined.Send` / `Filled.Send`; Detail: layers; Custom: its own). The first tab starts selected.
- **One back stack per tab:** `rememberSaveable` holds `Map<tabId, List<FlagRoute>>` (saved as a `Bundle` of encoded routes). The top route is drawn, else the tab's root screen. `BackHandler` pops while the stack is not empty. Each screen sits in a `SaveableStateHolder` slot keyed by tab, depth and route, so its own saved state (the search query) survives pushes and tab switches.
- **Failures:** `Ready.failure` is a banner below the tab content, with the exception's message and "Try again" (`refresh`). Every write failure goes through the shell's `FlagWriter` to a `SnackbarHost` (the exception's message, dismissable, `SnackbarDuration.Long`).
- **Signals:** one `FlagSignalsModel` per shell, so the history survives tab switches. `CancelPendingOnLeave` sits at the Signals tab's root, so switching tabs cancels a countdown and pushing a nested group does not.
- **Custom tabs** get a `FlagCompanionScope` whose `FlagRow` pushes list and record editors onto the custom tab's own stack.

- [ ] **Step 1: Add the androidTest dependencies and write the failing tests**

**Modify** `featureflag-ui/build.gradle.kts`. Replace

```kotlin
dependencies {
```

with

```kotlin
dependencies {
    // The on-device smoke test drives the shell with createComposeRule, which needs ComponentActivity
    // in the debug manifest. The unit-test Compose dependencies come from A2.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/FlagCompanionTabTest.kt` (iOS `CompanionViewTests`' tab descriptors and `FlagSignalGroupTests.testTheGroupedTabDescriptor`)
```kotlin
package com.andyyhope.featureflag.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagCompanionTabTest {

    private enum class SessionSignal : FlagSignal { SignOut, SwapDependency }

    private enum class OtherSignal : FlagSignal { SignOut }

    private val session = FlagSignalGroup("Session", SessionSignal.entries)

    // Descriptors

    @Test
    fun theDefaultTabsAreOverridesAndFlags() {
        assertEquals(listOf("overrides", "flags"), FlagCompanionTab.Defaults.map { it.id })
        assertEquals(listOf("Overrides", "Flags"), FlagCompanionTab.Defaults.map { it.title })
    }

    @Test
    fun tabsKeepTheOrderTheyAreGiven() {
        val tabs = listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Signals(session), FlagCompanionTab.Flags)

        assertEquals(listOf("overrides", "signals", "flags"), tabs.map { it.id })
    }

    @Test
    fun theGroupedTabDescriptor() {
        val tab = FlagCompanionTab.Signals(session)

        assertEquals("signals", tab.id)
        assertEquals("Signals", tab.title)
        assertEquals(listOf(session), tab.groups)
        assertEquals("Send", FlagCompanionTab.Signals(session, title = "Send").title)
    }

    @Test
    fun detailTabsAreIdentifiedByTheirKey() {
        val tabs = listOf(
            FlagCompanionTab.Detail(key = "environment", title = "Environment"),
            FlagCompanionTab.Detail(key = "checkout.tier", title = "Tier"),
        )

        assertEquals(listOf("detail.environment", "detail.checkout.tier"), tabs.map { it.id })
        assertNull(tabs.duplicateTabId())
    }

    @Test
    fun aCustomTabCarriesItsOwnIdentity() {
        val tab = FlagCompanionTab.Custom(id = "environment", title = "Environment", icon = Icons.Filled.Settings) {}

        assertEquals("environment", tab.id)
        assertEquals("Environment", tab.title)
        assertSame(Icons.Filled.Settings, tab.icon)
        assertSame(Icons.Filled.Settings, tab.icon(selected = true))
    }

    // Malformed lists

    @Test
    fun aDuplicateIdIsDetectedAndNamed() {
        assertEquals("overrides", listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Flags, FlagCompanionTab.Overrides).duplicateTabId())
        val custom = FlagCompanionTab.Custom(id = "flags", title = "Mine", icon = Icons.Filled.Star) {}
        assertEquals("flags", listOf(FlagCompanionTab.Flags, custom).duplicateTabId())

        val error = assertFailsWith<IllegalArgumentException> {
            requireDistinctTabIds(listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Overrides))
        }
        assertEquals(
            "A companion cannot have two tabs with the id 'overrides'. Give one of them a different id, or drop it.",
            error.message,
        )
    }

    @Test
    fun aWellFormedListHasNoDuplicate() {
        assertNull(listOf(FlagCompanionTab.Overrides, FlagCompanionTab.Signals(session), FlagCompanionTab.Flags).duplicateTabId())
    }

    @Test
    fun twoSignalGroupsSharingANameAreRefused() {
        val error = assertFailsWith<IllegalArgumentException> {
            FlagCompanionTab.Signals(session, FlagSignalGroup("Other", OtherSignal.entries))
        }

        assertEquals(
            "More than one signal group defines 'SignOut'. A signal travels as its raw value, so it has to be unique " +
                "across every group.",
            error.message,
        )
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/CompanionShellTest.kt` (iOS `testTheDefaultLayoutShowsOverridesAndFlags`, `testTheFirstTabIsSelected`, `testTheUnavailableViewBuilds`, `testAnEmptyListExplainsItselfRatherThanRenderingNothing`, `testSignalsCanBeLeftOutEntirely`, `testTheSignalsViewBuilds`, `testACustomTabCarriesItsOwnIdentityAndContent`, `testADetailTabRendersTheFlagItNames`)
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.companion.FlagCompanionState
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CompanionShellTest {

    private enum class AppSignal(override val signalDescription: String) : FlagSignal {
        RefetchRemoteConfiguration("Re-fetch remote configuration"),
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var retries = 0
    private var refreshes = 0
    private val sent = mutableListOf<Pair<FlagSignal, Duration>>()
    private var gate: CompletableDeferred<Unit>? = null
    private var sendWasCancelled = false

    private val signals = FlagCompanionTab.Signals(FlagSignalGroup("Configuration", AppSignal.entries))

    private suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
        sent += signal to delay
        try {
            gate?.await()
        } catch (cancelled: CancellationException) {
            sendWasCancelled = true
            throw cancelled
        }
        return true
    }

    private fun content(state: FlagCompanionState, tabs: List<FlagCompanionTab>): @Composable () -> Unit = {
        MaterialTheme {
            FlagCompanionContent(
                state = state,
                tabs = tabs,
                send = ::send,
                onRetry = { retries++ },
                onRefresh = { refreshes++ },
            )
        }
    }

    private fun show(state: FlagCompanionState, tabs: List<FlagCompanionTab>) {
        composeRule.setContent(content(state, tabs))
    }

    private fun ready(store: FlagEditingStore = testStore(ScreenFixtures.companion)) =
        FlagCompanionState.Ready(store, failure = null)

    private fun tab(title: String) = composeRule.onNode(hasText(title) and isSelectable())

    // Tabs

    @Test
    fun theDefaultLayoutShowsOverridesAndFlags() {
        show(ready(), FlagCompanionTab.Defaults)

        tab("Overrides").assertIsSelected()
        tab("Flags").assertIsNotSelected()
        composeRule.onNodeWithText("Nothing overridden").assertExists()
    }

    @Test
    fun theFirstTabIsSelected() {
        show(ready(), listOf(FlagCompanionTab.Flags, FlagCompanionTab.Overrides))

        tab("Flags").assertIsSelected()
        composeRule.onNodeWithText("Demo").assertExists()
    }

    @Test
    fun signalsCanBeLeftOutEntirely() {
        show(ready(), listOf(FlagCompanionTab.Overrides))

        tab("Overrides").assertIsSelected()
        tab("Signals").assertDoesNotExist()
        tab("Flags").assertDoesNotExist()
    }

    @Test
    fun theSignalsViewBuilds() {
        show(ready(), listOf(FlagCompanionTab.Overrides, signals))

        tab("Signals").performClick()

        composeRule.onNodeWithText("Delay").assertExists()
        composeRule.onNodeWithText("Re-fetch remote configuration").assertExists()
    }

    @Test
    fun aDetailTabRendersTheFlagItNames() {
        show(ready(), listOf(FlagCompanionTab.Detail(key = "new-onboarding", title = "Onboarding")))

        tab("Onboarding").assertIsSelected()
        // The navigation item and the title bar.
        composeRule.onAllNodesWithText("Onboarding").assertCountEquals(2)
        composeRule.onNodeWithText("Details").assertExists()
    }

    @Test
    fun aCustomTabCarriesItsOwnIdentityAndContent() {
        val custom = FlagCompanionTab.Custom(id = "environment", title = "Environment", icon = Icons.Filled.Settings) {
            Column {
                Text(text = "${store.schema.flags.size} flags published")
                FlagRow(store.requireEntry("new-onboarding"))
            }
        }
        show(ready(), listOf(custom))

        composeRule.onNodeWithText("9 flags published").assertExists()
        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()

        composeRule.onNodeWithText("Overridden").assertExists()
    }

    // Status

    @Test
    fun connectingShowsAProgressIndicator() {
        composeRule.mainClock.autoAdvance = false
        show(FlagCompanionState.Connecting, FlagCompanionTab.Defaults)
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun theUnavailableViewBuilds() {
        show(FlagCompanionState.Unavailable("Run the host app."), FlagCompanionTab.Defaults)

        composeRule.onNodeWithText("No flags yet").assertExists()
        composeRule.onNodeWithText("Run the host app.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun anEmptyListExplainsItselfRatherThanRenderingNothing() {
        show(ready(), emptyList())

        composeRule.onNodeWithText("No tabs").assertExists()
        composeRule.onNodeWithText(
            "This companion was given an empty tab list, so there is nothing to show. Pass at least one, or omit " +
                "the argument for the default two.",
        ).assertExists()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    // Failures

    @Test
    fun theLastFailureIsABannerWithTryAgain() {
        show(FlagCompanionState.Ready(testStore(ScreenFixtures.companion), FlagBridgeException.Unavailable(null)), FlagCompanionTab.Defaults)

        composeRule.onNodeWithText("The host could not be reached. Open the host app once, then try again.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun aFailedWriteIsShownInTheSnackbar() {
        val source = FailingSource { FlagBridgeException.Rejected("the host is read-only") }
        val store = FlagEditingStore(ScreenFixtures.companion, source, writeDispatcher = Dispatchers.Main.immediate)
        show(FlagCompanionState.Ready(store, failure = null), listOf(FlagCompanionTab.Flags))

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("New onboarding experience").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithText("The host rejected the change: the host is read-only").assertExists()
        composeRule.onNodeWithContentDescription("New onboarding experience").assertIsOff()
    }

    // Navigation

    @Test
    fun eachTabKeepsItsOwnBackStack() {
        show(ready(), listOf(FlagCompanionTab.Flags, FlagCompanionTab.Overrides))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()

        tab("Overrides").performClick()
        composeRule.onNodeWithText("Nothing overridden").assertExists()
        tab("Flags").performClick()

        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Search flags").assertDoesNotExist()
    }

    @Test
    fun systemBackPopsTheTabsStack() {
        show(ready(), listOf(FlagCompanionTab.Flags))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText("Search flags").assertExists()
        composeRule.onNodeWithText("Apple Pay").assertDoesNotExist()
    }

    @Test
    fun theBackArrowPopsToo() {
        show(ready(), listOf(FlagCompanionTab.Flags))
        composeRule.onNodeWithText("Checkout").performClick()

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Search flags").assertExists()
    }

    @Test
    fun aRouteSurvivesRecreation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent(content(ready(), listOf(FlagCompanionTab.Flags)))
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Apple Pay").assertExists()
        composeRule.onNodeWithText("Search flags").assertDoesNotExist()
    }

    // Signals

    @Test
    fun leavingTheSignalsTabCancelsItsCountdown() {
        gate = CompletableDeferred()
        show(ready(), listOf(signals, FlagCompanionTab.Overrides))
        composeRule.onNodeWithText("3s").performClick()
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true).assertExists()

        tab("Overrides").performClick()
        composeRule.waitForIdle()

        assertTrue(sendWasCancelled)
        tab("Signals").performClick()
        composeRule.onNodeWithContentDescription("Sending in 3 seconds. Tap to cancel.", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    // Lifecycle

    @Test
    fun everyStartAfterTheFirstCallsOnForeground() {
        val owner = TestLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED
        var starts = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                OnEveryStartAfterTheFirst { starts++ }
            }
        }
        composeRule.runOnIdle { assertEquals(0, starts) }

        repeat(2) {
            composeRule.runOnIdle {
                owner.registry.currentState = Lifecycle.State.CREATED
                owner.registry.currentState = Lifecycle.State.STARTED
            }
        }

        composeRule.runOnIdle { assertEquals(2, starts) }
    }
}
```

`featureflag-ui/src/androidTest/kotlin/com/andyyhope/featureflag/ui/FlagCompanionSmokeTest.kt`
```kotlin
package com.andyyhope.featureflag.ui

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.ui.companion.FlagCompanionState
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.screens.FlagCompanionContent
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertTrue

/**
 * The whole companion on a device, over an in-process store instead of a bridge. It walks every tab and
 * writes a screenshot of each to /data/local/tmp/featureflag-ui, which outlives the test APK's uninstall,
 * for the controller to pull and read.
 */
@RunWith(AndroidJUnit4::class)
class FlagCompanionSmokeTest {

    private enum class AppSignal(override val signalDescription: String) : FlagSignal {
        RefetchRemoteConfiguration("Re-fetch remote configuration"),
        ClearRemoteConfiguration("Clear remote configuration"),
    }

    private enum class CacheSignal(override val signalDescription: String, override val requiresRestart: Boolean) : FlagSignal {
        PurgeImageCache("Purge the image cache", requiresRestart = false),
        PurgeEverything("Purge everything", requiresRestart = true),
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun entry(key: String, type: FlagValueType, default: FlagValueBox, description: String, cases: List<FlagValueBox>? = null) =
        FlagSchema.Entry(
            key = FlagKey(key),
            propertyPath = key.split("."),
            description = description,
            valueType = type,
            defaultValue = default,
            cases = cases,
        )

    private val schema = FlagSchema(
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        applicationName = "Demo",
        description = "What the checkout team can turn on",
        flags = listOf(
            entry(
                "environment",
                FlagValueType.String,
                FlagValueBox.String("production"),
                "Environment",
                cases = listOf(FlagValueBox.String("production"), FlagValueBox.String("staging")),
            ),
            entry("new-onboarding", FlagValueType.Bool, FlagValueBox.Bool(false), "New onboarding experience"),
            entry("max-items", FlagValueType.Int, FlagValueBox.Int(10), "Maximum items"),
            entry("checkout.apple-pay", FlagValueType.Bool, FlagValueBox.Bool(false), "Apple Pay"),
            entry(
                "checkout.tier",
                FlagValueType.String,
                FlagValueBox.String("free"),
                "Tier",
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry("checkout.launched-at", FlagValueType.Date, FlagValueBox.Date(Instant.EPOCH), "Launched at"),
            entry(
                "checkout.markets",
                FlagValueType.Array(FlagValueType.String),
                FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
                "Markets",
            ),
        ),
        groups = listOf(FlagSchema.Group(listOf("checkout"), "Checkout")),
    )

    private val tabs = listOf(
        FlagCompanionTab.Overrides,
        FlagCompanionTab.Signals(
            FlagSignalGroup("Configuration", AppSignal.entries),
            FlagSignalGroup("Caches", CacheSignal.entries),
        ),
        FlagCompanionTab.Flags,
        FlagCompanionTab.Detail(key = "environment", title = "Environment"),
    )

    @Test
    fun everyTabRendersOnADevice() {
        val store = FlagEditingStore(
            schema,
            SnapshotSource(values = mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true), FlagKey("max-items") to FlagValueBox.Int(25))),
        )
        composeRule.setContent {
            MaterialTheme {
                FlagCompanionContent(
                    state = FlagCompanionState.Ready(store, failure = null),
                    tabs = tabs,
                    send = { _, _ -> true },
                    onRetry = {},
                    onRefresh = {},
                )
            }
        }
        shell("rm -rf $DIRECTORY")
        shell("mkdir -p $DIRECTORY")

        composeRule.onNodeWithText("2 changed").assertExists()
        capture("1-overrides")

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show QR code"))
        composeRule.onNodeWithText("Show QR code").performClick()
        composeRule.onNodeWithText("Scan to copy").assertExists()
        capture("2-qr-sheet")
        composeRule.onNodeWithText("Done").performClick()

        selectTab("Signals")
        composeRule.onNodeWithText("Re-fetch remote configuration").performClick()
        composeRule.onNodeWithText("handled").assertExists()
        capture("3-signals")

        selectTab("Flags")
        composeRule.onNodeWithText("Demo").assertExists()
        capture("4-flags")
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.onNodeWithText("Apple Pay").assertExists()
        capture("5-flags-checkout")

        selectTab("Environment")
        composeRule.onNodeWithText("Production").assertExists()
        capture("6-environment")
    }

    private fun selectTab(title: String) {
        composeRule.onNode(hasText(title) and isSelectable()).performClick()
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        shell("screencap -p $DIRECTORY/$name.png")
        assertTrue("$name.png" in shell("ls $DIRECTORY"), "screencap wrote no $name.png")
    }

    /** Runs [command] as the shell user, which can write /data/local/tmp, and waits for it to finish. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        const val DIRECTORY = "/data/local/tmp/featureflag-ui"
    }
}
```

- [ ] **Step 2: Run the unit tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.FlagCompanionTabTest' --tests 'com.andyyhope.featureflag.ui.screens.CompanionShellTest'
```

Expected: `BUILD FAILED` in `compileDebugUnitTestKotlin` with `Unresolved reference 'FlagCompanionTab'`, `'duplicateTabId'`, `'requireDistinctTabIds'`, `'FlagCompanionContent'` and `'OnEveryStartAfterTheFirst'`.

- [ ] **Step 3: Implement**

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagCompanion.kt`
```kotlin
package com.andyyhope.featureflag.ui

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.companion.FlagCompanionViewModel
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.screens.FlagCompanionContent
import com.andyyhope.featureflag.ui.screens.OnEveryStartAfterTheFirst
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.duplicateSignalName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole companion for one host app: its overrides, a browser for every flag, and any signals,
 * single-flag or custom tabs you add.
 *
 * Keep it in a separate app signed like the host: a host cannot bridge to itself. The companion connects
 * to [hostPackage] through featureflag-bridge, shows a status screen until it can, and reconnects whenever
 * it comes back to the foreground (on every `ON_START` after the first), so a reinstalled host with a new
 * schema is picked up.
 *
 * Draws with the caller's `MaterialTheme`.
 *
 * @param tabs shown in this order; the first starts selected. Two tabs with the same id throw
 *   [IllegalArgumentException].
 * @param timeout how long the host has to answer a call or a signal.
 */
@Composable
public fun FlagCompanion(
    hostPackage: String,
    modifier: Modifier = Modifier,
    tabs: List<FlagCompanionTab> = FlagCompanionTab.Defaults,
    timeout: Duration = 5.seconds,
) {
    requireDistinctTabIds(tabs)
    val application = LocalContext.current.applicationContext as Application
    val viewModel: FlagCompanionViewModel = viewModel(
        key = "featureflag:$hostPackage",
        factory = viewModelFactory { initializer { FlagCompanionViewModel(application, hostPackage, timeout) } },
    )
    val session = viewModel.session
    val state by session.state.collectAsStateWithLifecycle()
    OnEveryStartAfterTheFirst(session::onForeground)
    FlagCompanionContent(
        state = state,
        tabs = tabs,
        send = viewModel::send,
        onRetry = session::retry,
        onRefresh = session::refresh,
        modifier = modifier,
    )
}

/** One tab of [FlagCompanion]. Ids must be unique within a companion. */
public sealed interface FlagCompanionTab {

    /** What the companion uses to tell tabs apart. */
    public val id: String

    /** The navigation bar label. */
    public val title: String

    /** What has been overridden, as rows, JSON and a QR code, with import and reset. Id `overrides`. */
    public data object Overrides : FlagCompanionTab {
        override val id: String get() = "overrides"
        override val title: String get() = FlagStrings.OVERRIDES
    }

    /** Every flag the host publishes, by group, with search. Id `flags`. */
    public data object Flags : FlagCompanionTab {
        override val id: String get() = "flags"
        override val title: String get() = FlagStrings.FLAGS
    }

    /**
     * Buttons that send the host's signals, with an optional delay. Id `signals`.
     *
     * @throws IllegalArgumentException when two groups define a signal with the same name: a signal
     *   travels as its name, so the host's observers would both fire for one press.
     */
    public class Signals(vararg groups: FlagSignalGroup, override val title: String = FlagStrings.SIGNALS) : FlagCompanionTab {

        /** In the order given. */
        public val groups: List<FlagSignalGroup> = groups.toList()

        override val id: String get() = "signals"

        init {
            val duplicate = this.groups.duplicateSignalName()
            require(duplicate == null) { FlagStrings.duplicateSignal(duplicate.orEmpty()) }
        }
    }

    /** One flag on its own tab, such as an environment switch. Id `detail.<key>`. */
    public class Detail(public val key: String, override val title: String) : FlagCompanionTab {
        override val id: String get() = "detail.$key"
    }

    /** Your own tab. Its [content] gets the store and the standard row through [FlagCompanionScope]. */
    public class Custom(
        override val id: String,
        override val title: String,
        public val icon: ImageVector,
        public val content: @Composable FlagCompanionScope.() -> Unit,
    ) : FlagCompanionTab

    public companion object {
        /** Overrides and Flags: what every companion wants, and nothing it cannot fill. */
        public val Defaults: List<FlagCompanionTab> = listOf(Overrides, Flags)
    }
}

/** What a custom tab can use. */
public interface FlagCompanionScope {

    /** The host's flags, as the built-in tabs see them. */
    public val store: FlagEditingStore

    /** The standard row for one flag, with its editor, Overridden pill and reset. */
    @Composable
    public fun FlagRow(entry: FlagSchema.Entry)
}

/** The first id two tabs share, or null. */
internal fun List<FlagCompanionTab>.duplicateTabId(): String? {
    val seen = HashSet<String>()
    return firstOrNull { !seen.add(it.id) }?.id
}

/** Two tabs with one id leave one of them unreachable, so there is no correct rendering to fall back to. */
internal fun requireDistinctTabIds(tabs: List<FlagCompanionTab>) {
    val duplicate = tabs.duplicateTabId()
    require(duplicate == null) { FlagStrings.duplicateTabId(duplicate.orEmpty()) }
}

/** The navigation bar icon: outlined, or filled when selected. */
internal fun FlagCompanionTab.icon(selected: Boolean): ImageVector = when (this) {
    FlagCompanionTab.Overrides -> FlagIcons.Tune
    FlagCompanionTab.Flags -> if (selected) FlagIcons.FlagFilled else FlagIcons.FlagOutlined
    is FlagCompanionTab.Signals -> if (selected) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Outlined.Send
    is FlagCompanionTab.Detail -> if (selected) FlagIcons.LayersFilled else FlagIcons.LayersOutlined
    is FlagCompanionTab.Custom -> icon
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/StatusScreens.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings

/** While the companion connects (iOS `.loading`). */
@Composable
internal fun ConnectingScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** iOS FlagCompanionUnavailableView: a symbol, a title, a message, and "Try again" when retrying can help. */
@Composable
internal fun UnavailableScreen(title: String, message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = FlagIcons.FlagOutlined,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text(text = FlagStrings.TRY_AGAIN) }
        }
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/CompanionShell.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.companion.FlagCompanionState
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.icon
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The companion for a given connection state, without the ViewModel: what FlagCompanion draws, and what
 * the tests drive with an in-process store. [send] is the bridge client's delayed send.
 */
@Composable
internal fun FlagCompanionContent(
    state: FlagCompanionState,
    tabs: List<FlagCompanionTab>,
    send: suspend (FlagSignal, Duration) -> Boolean,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        FlagCompanionState.Connecting -> ConnectingScreen(modifier = modifier)
        is FlagCompanionState.Unavailable -> UnavailableScreen(
            title = FlagStrings.NO_FLAGS_YET,
            message = state.message,
            onRetry = onRetry,
            modifier = modifier,
        )
        is FlagCompanionState.Ready -> if (tabs.isEmpty()) {
            UnavailableScreen(title = FlagStrings.NO_TABS, message = FlagStrings.NO_TABS_MESSAGE, onRetry = null, modifier = modifier)
        } else {
            FlagCompanionTabs(store = state.store, failure = state.failure, tabs = tabs, send = send, onRefresh = onRefresh, modifier = modifier)
        }
    }
}

/** Calls [onStart] on every ON_START after the first: the companion coming back to the foreground. */
@Composable
internal fun OnEveryStartAfterTheFirst(onStart: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latestOnStart by rememberUpdatedState(onStart)
    DisposableEffect(owner) {
        var hasStarted = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (hasStarted) {
                    latestOnStart()
                } else {
                    hasStarted = true
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun FlagCompanionTabs(
    store: FlagEditingStore,
    failure: FlagBridgeException?,
    tabs: List<FlagCompanionTab>,
    send: suspend (FlagSignal, Duration) -> Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val shellScope = rememberCoroutineScope()
    // One model per shell, so a countdown's history survives switching tabs.
    val signalsModel = remember { FlagSignalsModel(send = send, scope = shellScope) }
    var selectedId by rememberSaveable { mutableStateOf(tabs.first().id) }
    val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.first()
    val stacks = rememberSaveable(saver = RouteStacksSaver) { mutableStateMapOf<String, List<FlagRoute>>() }
    val stateHolder = rememberSaveableStateHolder()
    ProvideFlagWriter(
        onFailure = { error ->
            shellScope.launch {
                snackbarHostState.showSnackbar(
                    message = error.message ?: error.toString(),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        val isSelected = tab.id == selected.id
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedId = tab.id },
                            icon = { Icon(imageVector = tab.icon(isSelected), contentDescription = null) },
                            label = { Text(text = tab.title) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            // Each screen's TopAppBar takes the status bar and the NavigationBar the navigation bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TabContent(
                        tab = selected,
                        store = store,
                        routes = stacks[selected.id].orEmpty(),
                        push = { route -> stacks[selected.id] = stacks[selected.id].orEmpty() + route },
                        pop = { stacks[selected.id] = stacks[selected.id].orEmpty().dropLast(1) },
                        signalsModel = signalsModel,
                        stateHolder = stateHolder,
                    )
                }
                if (failure != null) FailureBanner(message = failure.message.orEmpty(), onTryAgain = onRefresh)
            }
        }
    }
}

/** The bridge's last refresh failed: the values shown are the last good ones. */
@Composable
private fun FailureBanner(message: String, onTryAgain: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onTryAgain) { Text(text = FlagStrings.TRY_AGAIN) }
        }
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/TabNavigation.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagCompanionScope
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import com.andyyhope.featureflag.ui.screens.FlagRow as StandardFlagRow

/** Each tab's back stack, saved as a Bundle of encoded routes. */
internal val RouteStacksSaver: Saver<SnapshotStateMap<String, List<FlagRoute>>, Bundle> = Saver(
    save = { stacks ->
        Bundle().apply {
            stacks.forEach { (id, routes) -> putStringArrayList(id, ArrayList(routes.map(FlagRouteCodec::encode))) }
        }
    },
    restore = { bundle ->
        mutableStateMapOf<String, List<FlagRoute>>().apply {
            bundle.keySet().forEach { id -> put(id, bundle.getStringArrayList(id).orEmpty().map(FlagRouteCodec::decode)) }
        }
    },
)

/** One tab: its top route, or its root screen. System back pops the tab's own stack. */
@Composable
internal fun TabContent(
    tab: FlagCompanionTab,
    store: FlagEditingStore,
    routes: List<FlagRoute>,
    push: (FlagRoute) -> Unit,
    pop: () -> Unit,
    signalsModel: FlagSignalsModel,
    stateHolder: SaveableStateHolder,
) {
    BackHandler(enabled = routes.isNotEmpty(), onBack = pop)
    if (tab is FlagCompanionTab.Signals) CancelPendingOnLeave(signalsModel)
    val top = routes.lastOrNull()
    val slot = "${tab.id}/${routes.size}/${top?.let(FlagRouteCodec::encode).orEmpty()}"
    stateHolder.SaveableStateProvider(key = slot) {
        if (top == null) {
            TabRoot(tab = tab, store = store, push = push, signalsModel = signalsModel)
        } else {
            RouteScreen(route = top, tab = tab, store = store, push = push, pop = pop, signalsModel = signalsModel)
        }
    }
}

@Composable
private fun TabRoot(tab: FlagCompanionTab, store: FlagEditingStore, push: (FlagRoute) -> Unit, signalsModel: FlagSignalsModel) {
    when (tab) {
        FlagCompanionTab.Overrides -> OverridesScreen(store = store)
        FlagCompanionTab.Flags -> BrowserScreen(store = store, onOpen = push)
        is FlagCompanionTab.Signals -> SignalsScreen(
            model = signalsModel,
            groups = tab.groups,
            onOpenGroup = { group -> push(FlagRoute.SignalGroup(group.title)) },
            title = tab.title,
        )
        is FlagCompanionTab.Detail -> DetailScreen(store = store, key = tab.key, title = tab.title, onOpen = push)
        is FlagCompanionTab.Custom -> {
            val scope = remember(store, push) { CompanionScope(store, push) }
            Box(modifier = Modifier.fillMaxSize()) { tab.content(scope) }
        }
    }
}

@Composable
private fun RouteScreen(
    route: FlagRoute,
    tab: FlagCompanionTab,
    store: FlagEditingStore,
    push: (FlagRoute) -> Unit,
    pop: () -> Unit,
    signalsModel: FlagSignalsModel,
) {
    when (route) {
        is FlagRoute.Group -> GroupScreen(store = store, path = route.path, onOpen = push, onBack = pop)
        is FlagRoute.ListEditor -> ListEditorScreen(store = store, key = route.key, onBack = pop)
        is FlagRoute.Records -> RecordsScreen(store = store, key = route.key, trail = route.trail, onOpen = push, onBack = pop)
        is FlagRoute.Record -> RecordScreen(
            store = store,
            key = route.key,
            trail = route.trail,
            index = route.index,
            onOpen = push,
            onBack = pop,
        )
        is FlagRoute.SignalGroup -> {
            val group = (tab as? FlagCompanionTab.Signals)?.groups?.firstOrNull { it.title == route.title }
            if (group != null) SignalGroupScreen(model = signalsModel, group = group, onBack = pop)
        }
    }
}

/** A custom tab's scope: the store, and the standard row, whose editors push onto the custom tab's stack. */
private class CompanionScope(
    override val store: FlagEditingStore,
    private val onOpen: (FlagRoute) -> Unit,
) : FlagCompanionScope {

    @Composable
    override fun FlagRow(entry: FlagSchema.Entry) {
        StandardFlagRow(store = store, entry = entry, onOpen = onOpen)
    }
}
```

- [ ] **Step 4: Run the unit tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.FlagCompanionTabTest' --tests 'com.andyyhope.featureflag.ui.screens.CompanionShellTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `FlagCompanionTabTest` `tests="8"`, `CompanionShellTest` `tests="17"`, all `failures="0"` (25 tests). The whole `featureflag-ui` unit suite passes too (B1–B6).

- [ ] **Step 5: Run the smoke test on `Pixel_7`, pull the screenshots and read them**

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

If `emulator-5554	device` is not listed, start the AVD in the background and wait for it to boot:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_7 -no-snapshot-save -no-boot-anim &
~/Library/Android/sdk/platform-tools/adb wait-for-device
until [ "$(~/Library/Android/sdk/platform-tools/adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
```

Then wake it, run the suite and pull what it wrote:

```bash
~/Library/Android/sdk/platform-tools/adb shell input keyevent KEYCODE_WAKEUP
~/Library/Android/sdk/platform-tools/adb shell wm dismiss-keyguard
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:connectedDebugAndroidTest
rm -rf build/featureflag-ui-screenshots && mkdir -p build/featureflag-ui-screenshots
~/Library/Android/sdk/platform-tools/adb pull /data/local/tmp/featureflag-ui/. build/featureflag-ui-screenshots/
ls build/featureflag-ui-screenshots
```

Expected: `BUILD SUCCESSFUL`, `Finished 1 tests on Pixel_7(AVD) - 16`, and `ls` lists `1-overrides.png 2-qr-sheet.png 3-signals.png 4-flags.png 5-flags-checkout.png 6-environment.png`. The controller opens each PNG (the Read tool shows images) and checks:
- `1-overrides`: title "Overrides", "2 changed", rows for New onboarding experience (`true`) and Maximum items (`25`), each with a reset arrow; the JSON block with its copy icon; the navigation bar with four items, Overrides selected.
- `2-qr-sheet`: "Scan to copy" with Done, a square QR code with crisp modules, "Copy image", "Save", "Copy code", "2 override(s)".
- `3-signals`: "Delay" with the four segments (Instant selected) and its footer; "Configuration" and "Caches" sections with send icons; "Purge everything" with "Takes effect after a relaunch"; "Recent" with "Re-fetch remote configuration", a time, and "handled" in the primary colour.
- `4-flags`: title "Demo", the search field, the description, root rows (Environment picker, New onboarding experience with the Overridden pill, Maximum items `25`), the "Groups" header and a "Checkout" row with "4 flags".
- `5-flags-checkout`: title "Checkout" with a back arrow; Apple Pay switch, Tier picker "free", Launched at date button, Markets row "2 strings".
- `6-environment`: title "Environment", radio rows "Production" (selected) and "Staging", the Details section with Key `environment`, Default `production` and Remotely overridable "No".

Nothing may be clipped, overlap the status bar or sit under the navigation bar. If a screenshot shows a defect, fix it, rerun Steps 4 and 5, and pull again.

- [ ] **Step 6: Commit**

```bash
git add featureflag-ui/build.gradle.kts featureflag-ui/src/main/kotlin featureflag-ui/src/test/kotlin featureflag-ui/src/androidTest
git commit -m "Add FlagCompanion with tabs, per-tab back stacks, status screens, the failure banner and snackbar" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task B7 (cuttable, last): The countdown notification

Cut this task whole if M3 runs short: nothing else depends on it. The shell, the countdown ring and the host-side delay all work without it.

**Files:**
- Modify: `featureflag-ui/src/main/AndroidManifest.xml` (exact replace pair)
- Create: `featureflag-ui/src/main/res/drawable/featureflag_ic_signal.xml`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalNotifier.kt`
- Create: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/SignalCountdownNotification.kt`
- Modify: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagCompanion.kt` (exact replace pair)
- Modify: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/CompanionShell.kt` (exact replace pairs)
- Modify: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/TabNavigation.kt` (exact replace pairs)
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalNotifierTest.kt`
- Test: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalCountdownNotificationTest.kt`
- Test: `featureflag-ui/src/androidTest/kotlin/com/andyyhope/featureflag/ui/FlagSignalNotifierDeviceTest.kt`

**Interfaces:**
- Consumes: A6 `FlagSignalCountdown` (`progress`, `end`, `duration`), `FlagSignalDelay`, `FlagSignalsModel` (`delay`, `pending`, `countdown`, `tapped`); B5 `CancelPendingOnLeave`; B6 `TabContent`, `FlagCompanionContent`; `FlagStrings.NOTIFICATION_CHANNEL`, `SENDING_IN`.
- Produces (all `internal`):

```kotlin
// .ui.signals (the one Android file the overview allows in signals/)
internal class FlagSignalNotifier(context: Context, clock: () -> Instant = Instant::now) {
    fun show(signal: FlagSignal, countdown: FlagSignalCountdown, timeout: Duration)
    fun cancel()
    companion object { const val CHANNEL_ID: String; const val NOTIFICATION_ID: Int }
}
// .ui.screens
@Composable internal fun SignalCountdownNotification(model: FlagSignalsModel, timeout: Duration)
@Composable internal fun RequestNotificationPermissionOnDelay(model: FlagSignalsModel)
```

`FlagCompanionContent` gains `signalTimeout: Duration = 5.seconds`, which `FlagCompanion` passes its `timeout` to.

**The notification (overview deviation 3; no foreground service, because the host holds the delay):**
- Channel `featureflag.signal-countdown` ("Signal countdowns", `IMPORTANCE_LOW`, so it never makes a sound).
- Title: the signal's description. Text: "Sending in", beside a chronometer counting down to `countdown.end` (`setUsesChronometer(true)`, `setChronometerCountDown(true)`, `setWhen(end)`). Ongoing, only alerting once, category progress.
- API 36 and later: `Notification.ProgressStyle` with one segment, its progress `countdown.progress(now)`, and the request to be promoted to a Live Update. Below 36: the classic progress bar.
- `setTimeoutAfter(delay + timeout)`, so a frozen companion cannot leave it behind. The content intent is the app's launch intent, when it has one.
- Updated once a second while pending (the bar moves; the chronometer counts on its own), cancelled when the send finishes, is cancelled, or the Signals tab leaves.
- **Permission:** `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS` in the manifest. On API 33 and later the companion asks for `POST_NOTIFICATIONS` the first time a non-instant delay is chosen, once per saved state; if it is denied, nothing is posted and the send goes ahead, as iOS does when a Live Activity cannot start.
- `setRequestPromotedOngoing(true)` and `POST_PROMOTED_NOTIFICATIONS` are API 36.1 and absent from compileSdk 36's `android.jar`, so the notifier sets the extra that method sets, `"android.requestPromotedOngoing"` (`Notification.EXTRA_REQUEST_PROMOTED_ONGOING`), and the manifest names the permission by its string. See "Contract issues found".

- [ ] **Step 1: Write the failing tests**

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalNotifierTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagSignalNotifierTest {

    private enum class TestSignal(override val signalDescription: String) : FlagSignal {
        Refetch("Re-fetch remote configuration"),
    }

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val start: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val countdown = FlagSignalCountdown(start, 5.seconds)

    private fun notifier(now: Instant = start) = FlagSignalNotifier(context) { now }

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun onApi36TheCountdownIsAProgressStyleLiveUpdate() {
        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val notification = posted()
        assertEquals("android.app.Notification\$ProgressStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals("Re-fetch remote configuration", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Sending in", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(FlagSignalNotifier.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun theChronometerCountsDownToTheMomentTheHostDelivers() {
        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val notification = posted()
        assertEquals(start.plusSeconds(5).toEpochMilli(), notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }

    @Test
    fun itTimesOutAfterTheDelayAndTheTimeout() {
        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        assertEquals(10_000L, posted().timeoutAfter)
    }

    @Test
    fun theBarFollowsTheClock() {
        notifier(now = start.plusMillis(2_500)).show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val style = assertIs<Notification.ProgressStyle>(Notification.Builder.recoverBuilder(context, posted()).style)
        assertEquals(500, style.progress)
    }

    @Test
    fun withoutThePermissionNothingIsPosted() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier().show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun cancellingRemovesIt() {
        val notifier = notifier()
        notifier.show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        notifier.cancel()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    @Config(sdk = [35])
    fun beforeApi36ItIsAChronometerWithAProgressBar() {
        notifier(now = start.plusMillis(2_500)).show(TestSignal.Refetch, countdown, timeout = 5.seconds)

        val notification = posted()
        assertFalse(notification.extras.containsKey("android.requestPromotedOngoing"))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(1_000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(500, notification.extras.getInt(Notification.EXTRA_PROGRESS))
    }
}
```

`featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalCountdownNotificationTest.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SignalCountdownNotificationTest {

    private enum class TestSignal(override val signalDescription: String) : FlagSignal {
        Refetch("Re-fetch remote configuration"),
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val gate = CompletableDeferred<Boolean>()
    private lateinit var model: FlagSignalsModel

    private suspend fun send(signal: FlagSignal, delay: Duration): Boolean {
        check(signal == TestSignal.Refetch && delay > Duration.ZERO)
        return gate.await()
    }

    private fun showNotification(isShown: () -> Boolean = { true }) {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = ::send, scope = scope) }
            if (isShown()) SignalCountdownNotification(model = model, timeout = 5.seconds)
        }
    }

    private fun startCountdown() {
        composeRule.runOnIdle {
            model.delay.value = FlagSignalDelay.Three
            model.tapped(TestSignal.Refetch)
        }
        composeRule.waitForIdle()
    }

    // The notification

    @Test
    fun aCountdownPostsTheNotificationAndTheAnswerRemovesIt() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showNotification()

        startCountdown()

        assertEquals(1, shadowOf(manager).allNotifications.size)
        gate.complete(true)
        composeRule.waitForIdle()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun leavingTheTabRemovesTheNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var isShown by mutableStateOf(true)
        showNotification { isShown }
        startCountdown()

        isShown = false
        composeRule.waitForIdle()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    // The permission

    @Test
    fun choosingADelayAsksForThePermission() {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = ::send, scope = scope) }
            RequestNotificationPermissionOnDelay(model)
        }
        assertNull(shadowOf(composeRule.activity).lastRequestedPermission)

        composeRule.runOnIdle { model.delay.value = FlagSignalDelay.Three }
        composeRule.waitForIdle()

        val request = checkNotNull(shadowOf(composeRule.activity).lastRequestedPermission)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
    }

    @Test
    fun aGrantedPermissionIsNotAskedFor() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { FlagSignalsModel(send = ::send, scope = scope) }
            RequestNotificationPermissionOnDelay(model)
        }

        composeRule.runOnIdle { model.delay.value = FlagSignalDelay.Five }
        composeRule.waitForIdle()

        assertNull(shadowOf(composeRule.activity).lastRequestedPermission)
    }
}
```

`featureflag-ui/src/androidTest/kotlin/com/andyyhope/featureflag/ui/FlagSignalNotifierDeviceTest.kt`
```kotlin
package com.andyyhope.featureflag.ui

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.signals.FlagSignalCountdown
import com.andyyhope.featureflag.ui.signals.FlagSignalNotifier
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Posts the countdown on a device, opens the shade and screenshots it to
 * /data/local/tmp/featureflag-ui/7-notification.png for the controller to read.
 */
@RunWith(AndroidJUnit4::class)
class FlagSignalNotifierDeviceTest {

    private enum class DeviceSignal(override val signalDescription: String) : FlagSignal {
        Refetch("Re-fetch remote configuration"),
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @Test
    fun theCountdownIsPostedAndShownInTheShade() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("mkdir -p $DIRECTORY")
        val notifier = FlagSignalNotifier(context)

        notifier.show(DeviceSignal.Refetch, FlagSignalCountdown(Instant.now(), 10.seconds), timeout = 5.seconds)

        assertTrue(waitFor { manager.activeNotifications.any { it.id == FlagSignalNotifier.NOTIFICATION_ID } })
        shell("cmd statusbar expand-notifications")
        Thread.sleep(SHADE_SETTLE_MILLIS)
        shell("screencap -p $DIRECTORY/7-notification.png")
        shell("cmd statusbar collapse")
        assertTrue("7-notification.png" in shell("ls $DIRECTORY"), "screencap wrote no 7-notification.png")

        notifier.cancel()
        assertTrue(waitFor { manager.activeNotifications.none { it.id == FlagSignalNotifier.NOTIFICATION_ID } })
        assertFalse(manager.activeNotifications.any { it.id == FlagSignalNotifier.NOTIFICATION_ID })
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(POLLS) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        const val DIRECTORY = "/data/local/tmp/featureflag-ui"
        const val SHADE_SETTLE_MILLIS = 1_500L
        const val POLLS = 20
        const val POLL_MILLIS = 100L
    }
}
```

- [ ] **Step 2: Run the unit tests and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.signals.FlagSignalNotifierTest' --tests 'com.andyyhope.featureflag.ui.screens.SignalCountdownNotificationTest'
```

Expected: `BUILD FAILED` in `compileDebugUnitTestKotlin` with `Unresolved reference 'FlagSignalNotifier'`, `'SignalCountdownNotification'` and `'RequestNotificationPermissionOnDelay'`.

- [ ] **Step 3: Implement**

**Modify** `featureflag-ui/src/main/AndroidManifest.xml`. Replace

```xml
    <application>
```

with

```xml
    <!-- The delayed-signal countdown (B7). Without them the countdown is simply not shown. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- Manifest.permission.POST_PROMOTED_NOTIFICATIONS (API 36.1): lets API 36+ promote it to a Live Update. -->
    <uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />

    <application>
```

`featureflag-ui/src/main/res/drawable/featureflag_ic_signal.xml` (Material "send", Apache License 2.0)
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:autoMirrored="true"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z" />
</vector>
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/signals/FlagSignalNotifier.kt`
```kotlin
package com.andyyhope.featureflag.ui.signals

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.R
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The countdown for a delayed signal, shown outside the app while someone switches to the host (the
 * stand-in for iOS's Live Activity).
 * - API 36 and later: a ProgressStyle notification that asks to be promoted to a Live Update.
 * - Earlier: a chronometer counting down to the delivery, over a progress bar.
 * It times out after delay + timeout, so a frozen companion cannot leave it behind. Without the
 * notification permission it posts nothing, and the send goes ahead regardless.
 */
internal class FlagSignalNotifier(
    private val context: Context,
    private val clock: () -> Instant = Instant::now,
) {

    private val manager: NotificationManager =
        checkNotNull(context.getSystemService(NotificationManager::class.java)) { "This device has no notification manager." }

    /** Posts, or updates, the countdown for [signal]. [timeout] is how long the host has to answer once the delay ends. */
    fun show(signal: FlagSignal, countdown: FlagSignalCountdown, timeout: Duration) {
        if (!canPost()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, FlagStrings.NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_LOW),
        )
        val progress = (countdown.progress(clock()) * PROGRESS_MAX).roundToInt()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.featureflag_ic_signal)
            .setContentTitle(signal.signalDescription)
            .setContentText(FlagStrings.SENDING_IN)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(countdown.end.toEpochMilli())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((countdown.duration + timeout).inWholeMilliseconds)
        launchIntent()?.let { builder.setContentIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX)))
                    .setProgress(progress),
            )
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        } else {
            builder.setProgress(PROGRESS_MAX, progress, false)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Tapping it brings the companion back. */
    private fun launchIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    companion object {
        const val CHANNEL_ID = "featureflag.signal-countdown"
        const val NOTIFICATION_ID = 0x0F1A
        private const val PROGRESS_MAX = 1_000

        /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING, which setRequestPromotedOngoing sets; both are API 36.1. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
```

`featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/SignalCountdownNotification.kt`
```kotlin
package com.andyyhope.featureflag.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalNotifier
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val UpdateInterval = 1.seconds

/**
 * Shows [model]'s countdown as a notification while a delayed signal is pending, updating it once a
 * second, and removes it when the send ends, is cancelled, or this leaves the composition.
 */
@Composable
internal fun SignalCountdownNotification(model: FlagSignalsModel, timeout: Duration) {
    val context = LocalContext.current
    val notifier = remember(context) { FlagSignalNotifier(context.applicationContext) }
    val pending by model.pending.collectAsState()
    val countdown by model.countdown.collectAsState()
    LaunchedEffect(pending, countdown) {
        val signal = pending
        val running = countdown
        if (signal == null || running == null) {
            notifier.cancel()
            return@LaunchedEffect
        }
        // The chronometer counts down on its own; the updates only move the bar. One per second of the delay, then it rests.
        repeat(running.duration.inWholeSeconds.toInt() + 1) {
            notifier.show(signal, running, timeout)
            delay(UpdateInterval)
        }
    }
    DisposableEffect(notifier) {
        onDispose { notifier.cancel() }
    }
}

/** Asks for POST_NOTIFICATIONS the first time a delay is chosen (API 33+). Refusing only loses the notification. */
@Composable
internal fun RequestNotificationPermissionOnDelay(model: FlagSignalsModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var hasAsked by rememberSaveable { mutableStateOf(false) }
    val chosenDelay by model.delay.collectAsState()
    LaunchedEffect(chosenDelay) {
        val isGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (chosenDelay != FlagSignalDelay.Instant && !hasAsked && !isGranted) {
            hasAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
```

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagCompanion.kt`. Replace

```kotlin
        onRefresh = session::refresh,
```

with

```kotlin
        onRefresh = session::refresh,
        signalTimeout = timeout,
```

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/CompanionShell.kt`, five replacements.

1. Replace

```kotlin
import kotlin.time.Duration
```

with

```kotlin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
```

2. Replace

```kotlin
    modifier: Modifier = Modifier,
) {
    when (state) {
```

with

```kotlin
    modifier: Modifier = Modifier,
    signalTimeout: Duration = 5.seconds,
) {
    when (state) {
```

3. Replace

```kotlin
            FlagCompanionTabs(store = state.store, failure = state.failure, tabs = tabs, send = send, onRefresh = onRefresh, modifier = modifier)
```

with

```kotlin
            FlagCompanionTabs(
                store = state.store,
                failure = state.failure,
                tabs = tabs,
                send = send,
                onRefresh = onRefresh,
                signalTimeout = signalTimeout,
                modifier = modifier,
            )
```

4. Replace

```kotlin
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
```

with

```kotlin
    onRefresh: () -> Unit,
    signalTimeout: Duration,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
```

5. Replace

```kotlin
                        signalsModel = signalsModel,
                        stateHolder = stateHolder,
```

with

```kotlin
                        signalsModel = signalsModel,
                        stateHolder = stateHolder,
                        signalTimeout = signalTimeout,
```

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/TabNavigation.kt`, three replacements.

1. Replace

```kotlin
import com.andyyhope.featureflag.ui.screens.FlagRow as StandardFlagRow
```

with

```kotlin
import kotlin.time.Duration
import com.andyyhope.featureflag.ui.screens.FlagRow as StandardFlagRow
```

2. Replace

```kotlin
    signalsModel: FlagSignalsModel,
    stateHolder: SaveableStateHolder,
) {
```

with

```kotlin
    signalsModel: FlagSignalsModel,
    stateHolder: SaveableStateHolder,
    signalTimeout: Duration,
) {
```

3. Replace

```kotlin
    if (tab is FlagCompanionTab.Signals) CancelPendingOnLeave(signalsModel)
```

with

```kotlin
    if (tab is FlagCompanionTab.Signals) {
        CancelPendingOnLeave(signalsModel)
        SignalCountdownNotification(model = signalsModel, timeout = signalTimeout)
        RequestNotificationPermissionOnDelay(signalsModel)
    }
```

- [ ] **Step 4: Run the unit tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests 'com.andyyhope.featureflag.ui.signals.FlagSignalNotifierTest' --tests 'com.andyyhope.featureflag.ui.screens.SignalCountdownNotificationTest'
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times. `FlagSignalNotifierTest` `tests="7"` (one on SDK 35, the Robolectric SDK cached as `android-all-instrumented` 15), `SignalCountdownNotificationTest` `tests="4"`, all `failures="0"` (11 tests). B6's `CompanionShellTest` still passes: its calls use named arguments, and `signalTimeout` has a default. Lint reports no error: `POST_NOTIFICATIONS` is declared and checked before `notify`, and the API 36 calls sit behind `SDK_INT`.

- [ ] **Step 5: Post it on `Pixel_7`, screenshot the shade and read it**

With the emulator running and awake (B6 Step 5):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:connectedDebugAndroidTest
rm -rf build/featureflag-ui-screenshots && mkdir -p build/featureflag-ui-screenshots
~/Library/Android/sdk/platform-tools/adb pull /data/local/tmp/featureflag-ui/. build/featureflag-ui-screenshots/
ls build/featureflag-ui-screenshots
```

Expected: `BUILD SUCCESSFUL`, `Finished 2 tests on Pixel_7(AVD) - 16` (B6's smoke test and this one), and `7-notification.png` beside B6's six. The controller reads `7-notification.png`: the expanded shade shows a notification with the send icon, the title "Re-fetch remote configuration", the text "Sending in", a chronometer counting down from about 0:10, and a progress bar near its start. On an API 36 image the bar is the ProgressStyle track; a promoted chip in the status bar is a bonus, not a requirement (promotion needs API 36.1 and the user's consent). Then it re-reads B6's six screenshots, which must be unchanged.

- [ ] **Step 6: Commit**

```bash
git add featureflag-ui/src/main featureflag-ui/src/test/kotlin featureflag-ui/src/androidTest
git commit -m "Show a delayed signal's countdown as a notification that times out on its own" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
## Contract issues found

Each item is something the contract gets wrong, leaves open, or cannot do as written, with the minimal fix this plan uses. None renames a contract name.

1. **The accessibility texts are promised but not given.** The overview says "this contract gives the text" for the reset arrow, the warning triangle and the copy buttons, but it gives none. This plan uses iOS's own help and accessibility strings: "Reset to the app's default", "Not a valid value for this flag", "Copy value" / "Copy JSON", and "Copied".
2. **Android prose the contract does not give.** iOS gets these from the system (navigation, edit mode, swipe actions, Live Activities): "Back", "Actions", "Move up", "Move down", "Delete", the notification channel "Signal countdowns" and the notification text "Sending in". They are in `FlagStrings` under an "Android" comment.
3. **`FlagCompanionViewModel` has no members in the contract.** `FlagCompanion` needs the session and the delayed send. This plan assumes `internal val session: FlagCompanionSession` (started in `init`) and `internal suspend fun send(signal: FlagSignal, delay: Duration): Boolean` ("Seams"). If A4 names them differently, only `FlagCompanion.kt` changes.
4. **`setRequestPromotedOngoing(true)` and `POST_PROMOTED_NOTIFICATIONS` are API 36.1.** They are in `platforms/android-36.1/android.jar` but not in compileSdk 36's. B7 sets the extra the method sets, `"android.requestPromotedOngoing"` (`Notification.EXTRA_REQUEST_PROMOTED_ONGOING`), and names the permission by its string; the `Pixel_7` image is API 36, so promotion itself is not observable there. Moving `featureflag-ui` to compileSdk 36.1 would allow the real call, but that is a convention-plugin change for M4.
5. **Material's "tune" has no outlined and filled pair.** The Overrides tab uses the same vector in both states, and the `NavigationBar` indicator marks the selection. The Signals tab's unselected icon is `Icons.AutoMirrored.Outlined.Send` (the contract names only the filled one).
6. **`FlagStrings` is written whole in B1**, rather than "grown by each B task" as the file structure says, so no later task has to patch it. Strings only B7 uses stay harmless if B7 is cut.
7. **`FlagCompanionScope.FlagRow` and navigation.** A list or record row in a custom tab must open its editor somewhere. It pushes onto the custom tab's own back stack.
8. **"Could not do that" is not ported.** iOS raises that alert for a failed reset-all and a failed property-list copy. The property list is dropped (deviation 4), and a failed reset-all goes to the snackbar with every other write failure (deviation 6).
9. **A commit that changes nothing writes nothing.** iOS's `FlagTextField` writes on every blur, so focusing a field and leaving it marks the flag Overridden with its own default; across a bridge it also costs a call. Here a draft equal to the value in effect only clears the warning.
10. **One Robolectric test runs on SDK 35,** not 36, to cover the notification's pre-36 chronometer path (`FlagSignalNotifierTest.beforeApi36ItIsAChronometerWithAProgressBar`). SDK 35's `android-all-instrumented` is already in `~/.m2`.
11. **The notes describe two iOS pieces Android has no counterpart for.** "The App Group is unavailable, so there is nowhere to send." is dropped (the bridge client always exists). The single-type `FlagSignalsView(Type)` form becomes a group with an empty title, which keeps its footers ("Signals carry no payload…" / "Tap again to cancel.").
12. **"Save" on the QR sheet is a share.** iOS's `ShareLink` offers Save to Files and Photos; Android has no permission-free equivalent, so "Save" opens an `ACTION_SEND` `image/png` chooser. The PNG is written when it is copied or saved, not ahead of time as iOS's `.task` does.
13. **The failure banner sits below the tab content,** above the navigation bar, rather than over it. Each screen's `TopAppBar` owns the status-bar inset, and a banner above it would sit under the status bar.
14. **Android has no numbers-and-punctuation keyboard.** Integers use `KeyboardType.Number` and decimals `KeyboardType.Decimal`; a minus sign or an exponent may need the symbols page, and the parse still decides.

## Self-review

**Coverage against the brief and the overview's table:**
- B1: `FlagStrings`, `FlagIcons` (tune, flag ×2, layers ×2, QR code, content copy, undo, download, with Material path data), `FlagTextField` (draft, Done and focus-loss commits, refusal reverts with the warning, outside changes held off while focused), `FlagRow` for all eleven kinds (toggle, dropdown picker, `DatePickerDialog` + `TimePicker`, number/decimal/URL/text keyboards, data and JSON blocks with copy, list and records rows with their summaries), section helpers, the clipboard, the writer and routes.
- B2: search, tree versus flattened sections, group links with counts and badges, the schema description, both empty states, the group screen, the detail screen with its radio list, Details section and missing-key state.
- B3: list add, edit, delete and move (menu actions, no extra dependency); records summary rows, duplicate, delete, move, add with `emptyRecord`, the unreadable state; the record screen with the key-uniqueness refusal and nested lists.
- B4: rows, JSON with copy, Show QR code, Share JSON, Reset every override with the contract's dialog, Import…; the import sheet with `describe`'s wording; the QR sheet (ZXing at L, PNG in `cacheDir`, Copy image through `FlagFileProvider`, Save, Copy code, the too-large message); the provider, manifest and paths XML; `QRCodeExportTests`' PNG checks.
- B5: delay segments and footers, flat sections, nested "Groups", spinner / clock-driven ring / send icon, "Takes effect after a relaunch", Recent with coloured outcomes and local time, cancel on leaving.
- B6: `FlagCompanion`, `FlagCompanionTab` with `Defaults`, the duplicate-id check and "No tabs", `FlagCompanionScope`, the navigation bar with outlined and filled icons, per-tab `rememberSaveable` stacks with `BackHandler`, status screens, the failure banner with Try again, the write-failure snackbar, `ON_START` → `onForeground()`, the internal `FlagCompanionContent`, and the device smoke test with six screenshots and the exact commands.
- B7: `FlagSignalNotifier` (channel; ProgressStyle and promotion on 36; chronometer below; `setTimeoutAfter(delay + timeout)`; the launch intent; cancelled on completion, cancel and leaving), both permissions, the runtime request on the first delay, Robolectric checks on `ShadowNotificationManager`, and the device shade screenshot.

**The iOS [V] tests, by owner:** B1 `theFlagRowStillBuildsForAnArray` (and the [X] `FlagPasteboardTests`, portable here); B2 `theDetailViewBuildsForAFlagThatExists`, `theDetailViewBuildsForAKeyThatDoesNot`, `aDetailTabRendersTheFlagItNames`; B3 `theEditorBuilds`, `anElementRowBuildsForEveryScalar`; B4 `theSheetBuilds`, `copyImagePutsThePngOnTheClipboard`, `QRCodeExportTests`' `theCodeEncodesAsAPng` and `savingWritesAFileThatExists`, and `testTheLandingPageCompanionCompiles` as `eachOverrideIsListedWithItsValue`; B5 `theGroupedViewBuilds`, `theSingleTypeFormIsOneFlatGroup`; B6 `theDefaultLayoutShowsOverridesAndFlags`, `theFirstTabIsSelected`, `theUnavailableViewBuilds`, `anEmptyListExplainsItselfRatherThanRenderingNothing`, `signalsCanBeLeftOutEntirely`, `theSignalsViewBuilds`, `aCustomTabCarriesItsOwnIdentityAndContent`, `aDetailTabRendersTheFlagItNames`, with the tab [P] tests in `FlagCompanionTabTest`. `testTheTabModifierBuildsInBothStates` is covered by `aCustomTabCarriesItsOwnIdentity` (`icon(selected)`) and the shell's `assertIsSelected` checks. Not ported, with reasons: `testTheDefaultCompanionNeedsNothingButAGroup` and `testACustomLayoutReceivesTheLoadedStore` (Android has no closure form, and `FlagCompanion` needs a bridge; `FlagCompanionContent` is tested instead); the `DocumentationExampleTests` and `READMECompanionExampleTests` "compiles" checks (the screen tests build every screen); `testTheFailureMessageNamesBothCausesAndTheGroup` (the message is the connection's, A4's).

**Test counts:** B1 20, B2 19, B3 26, B4 28, B5 15, B6 25 + 1 instrumented, B7 11 + 1 instrumented: 144 Robolectric or JUnit tests and 2 on `Pixel_7`.

**Placeholders:** none. Every code step is a whole file or an exact replace pair whose anchor occurs once (B6's `dependencies {`, B7's manifest `<application>` and the shell lines quoted from B6's own code).

**Type consistency:** `FlagRoute`/`RecordStep`/`FlagRouteCodec` (B1) are used by B2, B3 and B6 with the same constructors; `LocalFlagWriter`, `ProvideFlagWriter`, `observeRevision` and `monospaced` keep B1's signatures everywhere; `FlagSheet` (B4) is used by both sheets; `CancelPendingOnLeave` (B5) by B6; `FlagCompanionContent(state, tabs, send, onRetry, onRefresh, modifier)` is called by `FlagCompanion`, `CompanionShellTest` and the smoke test with named arguments, so B7's added `signalTimeout` (defaulted) breaks none of them. Every contract call uses the overview's parameter names (`FlagEditingStore(schema, source, writeDispatcher = …)`, `FlagSignalsModel(send = …, scope = …, clock = …)`, `FlagRecordList.emptyRecord(fields, alongside = …)`).

**Risks the implementer should watch:** Robolectric's native graphics on the host (QR tests), `FileProvider` meta-data resolution under Robolectric (`theFileIsServedByTheLibrarysProvider` exercises it first), and whether the androidTest compilation sees `internal` declarations (it should: AGP associates it with main; if not, the smoke test must go through a small `internal`-free test hook, which needs a contract decision).
