# FeatureFlag for Android — parity design & milestone plan

## Context

`andyyhope/FeatureFlag` (Swift, v0.12.0, local clone `../feature-flags`, ~9.8k lines, 533 tests) lets
an app declare type-safe flags, layer sources (companion overrides → remote → local → compiled
default), validate remote payloads strictly, export/import as JSON or a QR code, and be edited by a
**companion app that never links the host's code** (it renders a published schema). THE USER wants
the Android sibling: a **public OSS Kotlin library** with the same concepts, idiomatic API, and docs
quality. `feature-flags-android/` is empty (not yet a git repo).

### Decisions agreed with THE USER
| Topic | Decision |
|---|---|
| Purpose | Public OSS library, Maven Central, group/package `com.andyyhope.featureflag` (needs Sonatype DNS-TXT verification of andyyhope.com before first release) |
| Slicing | Milestones, core first: M1 core+KSP+test-support → M2 Android + bridge + signals → M3 Compose companion → M4 samples/docs/CI/publish |
| Declaring flags | **KSP annotations on interfaces**; compiled default = the property's default getter |
| Flag handle (`$flag`) | **Generated typed refs**: `AppFlagsSpec.checkout.googlePay : FlagRef<Boolean>` |
| Cross-process | **Host-owned exported ContentProvider**, signature-level permission (optional `knownSigner` API 31+) |
| Portable formats | **Byte-compatible with iOS** (schema JSON, export JSON, `FFQR1:` QR string, remote-payload semantics). PLIST dropped |

Toolchain mirrors THE USER's yewki project: Gradle 9.5.1, AGP 9.1.1, Kotlin 2.2.10, KSP 2.2.10-2.0.2,
coroutines 1.10.2, Compose BOM 2026.01.00, JUnit 4.13.2, detekt 1.23.8, JDK 21 daemon / JVM target 17,
minSdk 26 (java.time), compileSdk 36. Installed: SDK 36/36.1, build-tools 36.0.0, AVD `Pixel_7`.

---

## Architecture

### Modules
| Artifact | Kind | Ships in | Contents |
|---|---|---|---|
| `featureflag-core` | JVM | host + companion | annotations, values/codecs, keys, schema, `FlagPole`, sources (Snapshot, Remote, Environment), payload/QR codecs, audit/diff, `FlagSignal` interface, strict JSON reader + Apple-style writer |
| `featureflag-ksp` | JVM (KSP) | build only | processor for `@FlagContainer` / `@FlagRecord` |
| `featureflag-test` | JVM | tests only | `assertFlagsFullyMapped` (throws `AssertionError`, framework-agnostic) |
| `featureflag-android` | AAR, **no manifest components** | host + companion | `SharedPreferencesSource`, `publishSchema(context)`, host `FlagSignalChannel`, `FlagBridgeContract`, `FlagBridgeClient` |
| `featureflag-bridge` | AAR, manifest `<permission>` + `<provider>` | host (opt-in via `implementation`; `debugImplementation` documented as the locked-down option) | `FlagBridgeProvider` only |
| `featureflag-ui` | AAR, Compose M3, ZXing core | companion only | `FlagCompanion` + screens + `FlagEditingStore` |
| `featureflag-bom` | platform | — | version alignment (also `ksp(platform(bom))`) |
| `samples/{host,companion,signals}`, `docs-samples` | apps / test-only | — | DemoApp/DemoCompanion ports; doc snippets compiled + run |
| `build-logic/` | included build | — | convention plugins (`jvm-library`, `android-library`, `compose`, `publish`), `explicitApi()`, warnings-as-errors, detekt |
| `fixture-generator/` | SwiftPM package (macOS) | — | depends on `andyyhope/FeatureFlag` **0.12.0** by URL; writes golden fixtures + decodes Kotlin-made QR codes. **The iOS repo is not modified.** |

### Declaring flags (KSP)
```kotlin
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false            // default getter = compiled default (any expression)
    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}
enum class Tier(override val flagRawValue: String) : FlagStringEnum { FREE("free"), PRO("pro") }

@FlagRecord data class PaymentMethod(
    @FlagRecordKey val name: String,
    val kind: PaymentKind,
    @FlagRecordProperty(key = "is_enabled") val enabled: Boolean = true,
    val minimumSpend: Double? = null,                    // nullable = optional field
)
```
Generated per container, in the same package and visibility: `XSpec : XRefs, FlagContainerSpec<X>`, containing:
- `flagDescriptors`;
- `flagDefaults`, an `object : X` (so a missing default fails to compile, backed by a KSP diagnostic);
- `createFlags(lookup, prefix)`, a private `Impl` that reads the stack and falls back to `super<X>.prop`;
- the typed `FlagRef` tree.

Groups and records in other modules are found by naming convention (`XSpec`, `RFlagRecord`).

Each record gets a generated `RFlagRecord : FlagRecordCodec<R>`. Field defaults come from a prototype instance built from placeholders for the parameters that have no default. Documented limit: a default that reads another constructor parameter sees the placeholder.

Diagnostics port the Swift wording, mirroring `FlagContainerDiagnosticTests` and `FlagRecordMacroTests`.

### Using it
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.bridged(ctx), remote, local))
pole.flags.checkout.googlePay                          // Boolean (lock-free read)
pole.flow(AppFlagsSpec.checkout.googlePay)             // Flow<Boolean>, initial + changes, distinct
pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion" / "Remote" / null = default
pole.setOverride(AppFlagsSpec.checkout.tier, Tier.PRO); pole.removeOverride(...); pole.overrides
// Compose (no Compose dep in host artifacts):
val on by pole.flow(AppFlagsSpec.newOnboarding).collectAsStateWithLifecycle(pole[AppFlagsSpec.newOnboarding])
```
Semantics ported exactly from the Swift resolver:
- **Resolution:** the first source with a non-null box of the right type wins. A box of the right type that still won't decode gives the default, and `resolution` still names that source.
- **Writes:** they go to the top mutable source.
- **Sources:** each must be thread-safe, and `box()` must be cheap. Built-in sources hold a `@Volatile` immutable map.
- **Change flow:** `changes: Flow<FlagChange>` is cold, with no main-thread hop.

Value types:

| Kotlin | Wire type |
|---|---|
| Boolean | bool |
| Int, Long | `int` (stored as a Long, because Swift's Int is 64-bit) |
| Double, Float | double, float |
| String | string |
| ByteArray | data |
| Instant | date |
| URI | url |
| List\<T\>, Map\<String, T\> | array, dictionary |
| Enums | by `name`, or `FlagStringEnum` / `FlagIntEnum` for custom raw values; every enum publishes its `cases` |
| FlagRecords\<R\> | string holding JSON text |

### Byte compatibility
- **JSON:** a hand-written strict reader, plus a writer that copies Apple's output.
  - The reader keeps literal text, classifies numbers like NSNumber, rejects top-level scalars, and has no kotlinx-serialization dependency at runtime.
  - The writer uses `"k" : v` pretty-printing, sorted keys (the comparator is pinned by fixtures), unescaped `/`, shortest round-trip doubles with `3.0 → 3`, and Floats widened when written.
- **Dates:** ISO-8601 UTC, to the second.
- **Data:** strict padded base64.
- **QR codes:** `FFQR1:` + base64url (no padding) of [4-byte big-endian length + raw DEFLATE (`Deflater(nowrap=true)`)], with a stored fallback, a 2,953-character limit and a 50 M cap.
- **What "byte-compatible" means:**
  - Decompressed JSON is byte-identical.
  - Each side decodes the other's codes.
  - The compressed QR string itself may differ, because zlib implementations differ.
- **Goldens:** `fixture-generator/` writes `fixtures/` (schema ×3, export, records, qr, json-writer numbers/strings/key-order/empty, dates, key-encoding, remote payloads with expected problems, audit and message text). `scripts/sync-fixtures.sh` refreshes them, and `GoldenFixtureTest` asserts byte equality.

### Bridge (M2)
The provider authority is `<hostAppId>.featureflag`, guarded by the permission `<hostAppId>.permission.FEATURE_FLAG_BRIDGE` (`signature`). Both names come from `${applicationId}` placeholders.

| Operation | Mechanism | Payload / result |
|---|---|---|
| hello | `call("hello")` | `protocol = 1` |
| schema | `openFile(content://A/schema)` | the raw `flag-schema.json`, as written by `pole.publishSchema(context)` into `noBackupFilesDir` |
| read | `call("overrides")` | export-format JSON of the bridged source |
| write | `call("apply")` | `payload` JSON + `remove` keys; strict and all-or-nothing in one prefs commit; returns status + problems |
| signal | `call("signal", name)` | `handled: Boolean` |
| observe | `ContentObserver` on `content://A/overrides` | the provider's prefs listener calls `notifyChange` |

Rules:
- `call()` is **not** permission-guarded by the framework. The provider calls `enforceCallingPermission` itself, and a test covers it.
- The client always uses `acquireUnstableContentProviderClient` and runs calls on IO with `withTimeoutOrNull`.
- **Startup barrier:** on its first request, the provider posts to the main looper and waits. That way a cold-started host has finished `Application.onCreate` (schema published, signal observers registered) before it answers.
- **Companion manifest:** needs `<uses-permission>` and `<queries><package>`. The client checks both at runtime and names whichever is missing.

`SharedPreferencesSource` behaviour:
- **Storage:** Boolean, Long, Float and String are stored natively; every other type is stored as its JSON value text.
- **Reads:** type-guided, so a value of the wrong type falls through to the next source.
- **Writes:** writes that change nothing are skipped, and every change is emitted through one strongly-held `OnSharedPreferenceChangeListener`.
- **Refresh:** the host needs no foreground refresh, since every write lands in its own process. The companion re-reads on `ON_START`.

`FlagBridgeClient.connect()` returns one of:
- `Connected(schema)`
- `HostNotFound`
- `PermissionDenied`, showing both certificate SHA-256s and mentioning `knownSigner`
- `NotPublished`
- `Incompatible`
- `Unresponsive`

These are finer-grained than iOS's two failure messages.

### Signals (M2)
- **Core:** `interface FlagSignal { val name: String; val signalDescription: String; val requiresRestart: Boolean }`. An enum's own `name` satisfies it.
- **Host:** `FlagSignalChannel.host.observe(CacheSignal.entries) { … }` returns an `AutoCloseable`, and handlers run on the main thread. Register observers in `Application.onCreate`.
- **Companion:** `client.send(signal, timeout = 5.seconds): Boolean`, which shows as handled, "handled — relaunch to see it", or no response.
- **Dropped from the iOS protocol:** sequence numbers and watermarks, because `call()` delivers exactly once.
- **Documented difference from iOS:** a signal to a dead host cold-starts it and is handled. On iOS it is lost.

### Companion UI (M3)
```kotlin
FlagCompanion(hostPackage = "com.example.app", tabs = listOf(
    FlagCompanionTab.Overrides,
    FlagCompanionTab.Signals(FlagSignalGroup("Configuration", AppSignal.entries), FlagSignalGroup("Caches", CacheSignal.entries)),
    FlagCompanionTab.Flags,
    FlagCompanionTab.Detail(key = "environment", title = "Environment"),
    FlagCompanionTab.Custom(id = "x", title = "X", icon = …) { /* FlagCompanionScope */ }))
```
- **State:** `FlagEditingStore` is plain Kotlin over `(schema, MutableFlagValueSource, reloadSchema)`, so it is JVM-testable. A ViewModel keyed by `hostPackage` owns the connection and retries.
- **Navigation:** one back stack per tab, using `rememberSaveable` routes. No navigation-compose dependency in the library.
- **Screens** port the iOS ones:
  - Overrides: JSON, copy, Share, QR sheet (ZXing, error correction L) with Copy/Share PNG through a `FlagFileProvider` subclass, Import (`FFQR1:` or JSON), and Reset all.
  - Browser: search, groups, overridden counts.
  - Row editors per editor kind: toggle, picker, date+time, integer/decimal/url/text fields that commit on Done or blur, data/json block, list editor, records list and record screen with unique keys.
  - Detail: radio list for enums.
  - Signals: delay picker (0/3/5/10 s), countdown ring, tap to cancel, last 5 results.
- **Delayed-signal countdown notification:** the stand-in for the Dynamic Island. It uses a ProgressStyle Live Update on API 36+ and a chronometer notification below that. It is the **last M3 task and can be cut**. A short foreground service is added only if spike S1 shows a backgrounded companion gets frozen.

### Not ported / deliberate deviations
| iOS | Android |
|---|---|
| PLIST | Dropped |
| App Group suite, Darwin notifications, foreground re-read, signal watermarks | Replaced by the bridge |
| ActivityKit | Notification instead (cuttable) |
| `@dynamicMemberLookup`, `$flag` projected values | `pole.flags` plus the `FlagRef` tree |
| Custom user `FlagValue` conformances | Closed codec set for now |
| `FlagValueCases` opt-in | All enums publish cases |
| Record fields declared as `let` constants in the body | Only primary-constructor parameters are fields |
| Companion editing several hosts | One host per `FlagCompanion` |
| Wear/TV UI | Out of scope |
| Camera scanning | Out of scope; iOS has none either |
| Swift quirk: a record list sent as a JSON string skips enum/nested checks | **Mirror it, and pin it with a test that links an upstream issue**, so the shared remote goldens don't drift. Fix it on both sides later |

## Adversarial self-review — changes folded in
1. **Golden fixtures would have required editing the iOS repo.** They now come from a standalone SwiftPM `fixture-generator/` pinned to FeatureFlag 0.12.0.
2. **The bridge as a separate artifact pushed people toward debug-only use**, which THE USER rejected. The docs now default to `implementation`; `debugImplementation` is offered as the locked-down option. It stays a separate artifact because a manifest-merged exported provider must be opt-in.
3. **The preview's `pole.collectAsState(ref)` would force Compose on host apps.** It is replaced by `pole.flow(ref).collectAsStateWithLifecycle(...)`.
4. **The Dynamic Island countdown was missing.** It is in M3 as a notification, last and cuttable.
5. **Key sorting, empty containers, exponents, pre-1582 dates and `isAbstract()` on KSP2 are all unverified assumptions.** Each one is a spike with a fixture, before any code depends on it.
6. **Several steps are outward-facing:** creating the GitHub repo, DNS verification, and publishing. Each is confirmed with THE USER at M4, not assumed.

---

## Execution checklist

**Step 0: bootstrap.** The first action after approval:
- [ ] `git init` in `feature-flags-android/`, copy this plan to `docs/superpowers/specs/2026-10-08-android-parity-design.md`, commit → verify: `git log` shows the spec commit
- [ ] Invoke `superpowers:writing-plans` to turn M1 into a bite-sized TDD plan for THE USER to review and pick an execution mode for (later milestones get their own plan when reached) → verify: plan file reviewed by THE USER

**Spikes.** Throwaway; results go into the spec.
- [ ] S1 (JVM): JSON writer conformance (key order, empty `[\n\n  ]`, exponents, escape case) → verify: diff against generator output
- [ ] S2 (JVM): dates (distantPast, 1500-01-01, fractions, offsets) → verify: date fixtures pass
- [ ] S3 (KSP): kctfork + KSP 2.2.10-2.0.2; `isAbstract()` on interface getters; `super<I>.p`; cross-module `XSpec` lookup; AGP 9 + KSP (`android.disallowKotlinSourceSets=false`) → verify: throwaway 2-module build
- [ ] S4 (QR): Kotlin decodes Apple codes; Swift decodes Kotlin codes → verify: both directions pass
- [ ] S5 (records): prototype-instance defaults, including an `init { require }` failure → verify: the shape carries the defaults
- [ ] S6 (Pixel_7, API 36): signature permission with a shared debug key; install order; no `<queries>` gives null; cross-app ContentObserver; `call()` into a killed, force-stopped or cached host plus the startup barrier; `DeadObjectException` survival; a 10 s delayed send from the background → verify: findings recorded

**M1: core + KSP + test support** (TDD, in this order)
- [ ] Scaffold: wrapper, version catalog, build-logic, 3 modules, detekt → verify: `./gradlew build detekt`
- [ ] `fixture-generator/` + `scripts/sync-fixtures.sh` → verify: `fixtures/manifest.json` exists
- [ ] FlagKey, FlagKeyPath, KeyEncoding → verify: tests + `key-encoding.json`
- [ ] FlagValueType, FlagValueBox → JSON reader → number formatting (fuzzed against JDK 21) → writer → date/base64/url → box⇄JSON + `storageIdentity` → verify: ported tests + goldens at each step
- [ ] FlagCodec(s), enums, records runtime → verify: collection/record tests + records golden
- [ ] Schema model and JSON, against a hand-written `DemoFlagsSpec` → verify: schema golden
- [ ] FlagContainerSpec, FlagRef, FlagLookup → KSP containers (replace the hand-written spec, keep tests green) → KSP records/enums → KSP diagnostics → verify: kctfork diagnostic tests + expansion snapshots
- [ ] SnapshotSource → FlagPole → flows (Turbine, subscribe-race stress) → verify: FlagPole*Test
- [ ] Payload export/import with rollback → QR codec (+ reverse fixtures) → verify: transport tests + goldens
- [ ] RemoteValue, DotPathMapper, RemoteOverrideSource → EnvironmentConfiguration → verify: remote goldens + `runTest` superseded cases
- [ ] FlagMappingAudit, FlagDefaultComparison, FlagRecordDiff → error prose → verify: text goldens + ErrorMessageTest
- [ ] `featureflag-test` + scenario ports of DemoExamplesTests → verify: `./gradlew check` green

**M2: Android + bridge + signals**
- [ ] Android convention plugin → verify: `assembleRelease publishToMavenLocal`
- [ ] `SharedPreferencesSource` (port UserDefaultsSourceTests, no-op skip, `clear()` → All) → verify: Robolectric tests
- [ ] `publishSchema(context)` → verify: file bytes == `schema.jsonBytes()`
- [ ] `FlagSignalChannel` host registry → verify: JVM tests
- [ ] `FlagBridgeProvider` + manifest + permission enforcement → verify: Robolectric provider tests, including the unauthorised `call()` case
- [ ] `FlagBridgeClient`, status mapping, `client.source(schema)` → verify: unit tests
- [ ] Instrumented cross-process suite (provider in `:host` process) → verify: `connectedDebugAndroidTest` on Pixel_7

**M3: Compose companion**
- [ ] Editor kinds and value parsing → tree/sections → `FlagEditingStore` → ViewModel/status → verify: JVM ports of the FeatureFlagUITests logic
- [ ] Row editors → browser/group → overrides/import → QR sheet (a 2,953-character code renders) → array editor → records editors → detail → signals model + screen → `FlagCompanion` shell → verify: Robolectric 4.16 + compose ui-test per screen
- [ ] (Cuttable) delayed-signal countdown notification → verify: manual check on Pixel_7

**M4: samples, docs, CI, release**
- [ ] `samples/host` (a DemoApp port; applePay → googlePay), `samples/companion`, shared debug keystore, `untrusted` companion build type → verify: e2e recipe below
- [ ] README + `docs/*.md` (DocC equivalents + `DifferencesFromIOS.md`); `docs-samples` + `DocsSyncTest` → verify: `:docs-samples:test`
- [ ] GitHub Actions: check / instrumented (emulator) / release; binary-compat `apiCheck`; Dokka → verify: green run on a PR
- [ ] **Confirm with THE USER before:** creating the GitHub repo, the Sonatype namespace DNS TXT, publishing 0.1.0 (vanniktech plugin, Central Portal)

## Verification (end-to-end)
1. `./gradlew check apiCheck detekt :docs-samples:test` is green on the JVM, and `GoldenFixtureTest` matches the iOS 0.12.0 fixtures byte for byte.
2. `cd fixture-generator && swift run decode-kotlin-fixtures` shows Swift decoding Kotlin-made QR codes and exports.
3. Start `~/Library/Android/sdk/emulator/emulator -avd Pixel_7`, then run `./gradlew :samples:host:installDebug :samples:companion:installDebug`:
   - Toggling a flag in the companion makes the host update live (split-screen), with the source shown as `Companion`.
   - The host's environment switch updates the companion's Overrides tab.
   - Signals report handled, and "handled — relaunch to see it" where applicable.
   - After `adb shell am kill <host>`, a signal still reports handled (cold start).
   - QR → Copy code → Import round-trips. A bad JSON import is rejected in full.
   - Installing `:samples:companion:installUntrusted` shows `PermissionDenied` with both certificate digests.
4. `./gradlew :featureflag-bridge:connectedDebugAndroidTest` passes on the Pixel_7.

## Execution notes
- For runs longer than 5 minutes, post a progress update at least every 5 minutes (CLAUDE.md §6). Run Gradle and emulator work in the background.
- Independent tasks (for example codecs vs KSP diagnostics) may go to parallel subagents as the writing-plans output allows.

## iOS reference files (read-only, `../feature-flags`)
- `Sources/FeatureFlag/{FlagPole.swift, Internal/FlagResolver.swift, Values/*, Schema/*, Transport/*, Sources/*, ErrorMessages.swift}`
- `Sources/FeatureFlagMacros/FlagContainerMacro.swift`, `FlagRecordMacro.swift`
- `Sources/FeatureFlag/Signals/FlagSignalChannel.swift`, `Sources/UserDefaultsSource.swift`
- `Sources/FeatureFlagUI/{FlagCompanionView, FlagEditingStore, FlagSignalsView, FlagRecordsEditorView, FlagRowView, FlagEditorKind}.swift`
- `Examples/DemoApp/*`, `Examples/DemoCompanion/*`, `Tests/**` (port targets)
- Toolchain conventions to copy: `../yewki/android/build-logic/`, `../yewki/android/docs/TOOLCHAIN.md`
