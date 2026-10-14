# Kotlin API survey for M3 (Compose companion UI)

Repo root: /Users/andyyhope/Development/feature-flags-android/.claude/worktrees/m3-companion (branch worktree-m3-companion, clean, at M2 merge 351f4ef).
Paths below are relative to it. Core package root: `com.andyyhope.featureflag` at `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/`.
No `samples/`, no `featureflag-ui`, no `featureflag-bom` exist yet. Modules in settings: core, ksp, test, android, bridge.

## 1. Public signatures

### 1.1 featureflag-core

**schema/FlagSchema.kt** (`...featureflag.schema`)
```kotlin
public data class FlagSchema(
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
    public val generatedAt: Instant = Instant.now(),
    public val applicationName: String? = null,
    public val description: String? = null,
    public val flags: List<Entry>,                 // depth first, declaration order
    public val groups: List<Group> = emptyList(),
) {
    public data class Entry(
        public val key: FlagKey, public val propertyPath: List<String>, public val description: String,
        public val valueType: FlagValueType, public val defaultValue: FlagValueBox,
        public val cases: List<FlagValueBox>? = null, public val remoteKey: String? = null,
        public val recordShape: List<FlagRecordField>? = null)
    public data class Group(public val propertyPath: List<String>, public val description: String)
    public val duplicateKeys: Map<FlagKey, List<List<String>>>
    public val duplicateKeyDescription: String?
    public val valueTypes: Map<FlagKey, FlagValueType>
    public val valueCases: Map<FlagKey, List<FlagValueBox>>        // non-empty cases only
    public val recordShapes: Map<FlagKey, List<FlagRecordField>>   // non-empty only
    public fun jsonBytes(): ByteArray
    public fun write(directory: File): File                        // atomic; IOException on failure
    public companion object {
        public const val FILE_NAME: String = "flag-schema.json"
        public const val CURRENT_FORMAT_VERSION: Int = 1
        public fun of(spec: FlagContainerSpec<*>, keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
                      applicationName: String? = null, generatedAt: Instant = Instant.now()): FlagSchema
        public fun parse(bytes: ByteArray): FlagSchema    // FlagSchemaException.{Malformed,UnsupportedFormatVersion}
        public fun read(directory: File): FlagSchema      // FlagSchemaException.NotPublished (+ parse errors)
    }
}
```
Groups: `Group.propertyPath` is a property-name path (e.g. `["checkout","express"]`); flags link to groups by prefix of `Entry.propertyPath`, NOT by key. Group has no key. Order = depth-first declaration order.

**schema/FlagDescriptor.kt** (same pkg; host-side in-process descriptors, a companion normally sees only `FlagSchema`)
`FlagDescriptor(propertyName, keyPath: FlagKeyPath, description, valueType, defaultValue, cases?, remoteKey?, recordShape?)` + `FlagDescriptor.of(propertyName, description, remoteKey, codec, default)`; `FlagGroupDescriptor(propertyName, keyPath, description, children: List<FlagSchemaNode>)`; `sealed interface FlagSchemaNode { propertyName; keyPath; prefixed(component); data class Flag(descriptor); data class Group(group) }`; `fun List<FlagSchemaNode>.flattened(): List<FlagDescriptor>`.

**values/FlagKey.kt** (`...values`)
```kotlin
@JvmInline public value class FlagKey(public val rawValue: String)   // toString() = rawValue
public data class FlagKeyPath(public val propertyNames: List<String>) { appending(), prepending(), companion ROOT }
```
**values/KeyEncoding.kt**: `class KeyEncoding(separator = ".", transform)`; `Kebabcase` (default), `Snakecase`, `Verbatim`; `key(path: FlagKeyPath): FlagKey`.

**values/FlagValueType.kt**
```kotlin
public sealed class FlagValueType {
    data object Bool, Int, Double, Float, String, Data, Date, Url
    data class Array(public val element: FlagValueType)
    data class Dictionary(public val value: FlagValueType)
    public val typeName: kotlin.String      // "bool","int",...,"array<string>","dictionary<int>"
    companion { public fun parse(name: kotlin.String): FlagValueType? }
}
```
**values/FlagValueBox.kt** (sealed interface; nested names shadow kotlin types, always qualify)
```kotlin
data class Bool(value: Boolean); data class Int(value: Long)          // NB Long
data class Double(value: kotlin.Double); data class Float(value: kotlin.Float); data class String(value: kotlin.String)
class Data(value: ByteArray)   // content equality
data class Date(value: Instant); class Url(value: URI)  // Url equality by toString
data class Array(value: List<FlagValueBox>); data class Dictionary(value: Map<kotlin.String, FlagValueBox>)
public fun matches(type: FlagValueType): Boolean
public val containsNonFiniteNumber: Boolean
```
Equality is data-class equality (Double(0.0) != Double(-0.0); NaN == NaN).

**values/FlagValueJson.kt** (public box <-> JSON-text codec)
```kotlin
public object FlagValueJson {
    public fun encode(box: FlagValueBox): String          // compact JSON value text; FlagSerializationException.NonFiniteNumber(null)
    public fun decode(text: String, type: FlagValueType): FlagValueBox?   // strict; null if invalid/mismatched; int->double/float allowed; scalar root ok
}
```
String encodes WITH JSON quotes (`"free"`), Date ISO-8601 to the second, Data padded base64 string, Url string, collections JSON. `BoxJson.kt` (`toJson`/`toBox`), JsonValue/JsonReader/JsonWriter/IsoDate/StrictBase64 and `RecordJson` are all `internal`.

**values/FlagCodec.kt, FlagCodecs.kt, FlagEnums.kt**: `FlagCodec<T> { type; cases; recordShape; encode(T): FlagValueBox; decode(FlagValueBox): T? }`; `FlagCodecs.{boolean,int,long,double,float,string,byteArray,instant,uri,list(),map(),enumByName(),stringEnum(),intEnum(),records()}`; `FlagStringEnum{flagRawValue:String}`, `FlagIntEnum{flagRawValue:Long}`.

**records/FlagRecordField.kt**
```kotlin
public data class FlagRecordField(name: String, type: FlagValueType, cases: List<FlagValueBox>? = null,
    defaultValue: FlagValueBox? = null, fields: List<FlagRecordField>? = null /*nested record list*/,
    isKey: Boolean = false, isOptional: Boolean = false, decodedName: String? = null) { val payloadName; companion of(...) }
```
A record-list flag has `valueType = String`; its box is `FlagValueBox.String(<json array text>)`; the editor uses `Entry.recordShape`. A nested record-list field is `type=String` with `fields!=null` (string-in-string). `RecordJson.{encode,decode,duplicateKey}` is internal. `FlagRecordCodec<R>` (shape, keyField, boxes, fromBoxes) and `FlagRecords<R>` are public but host-typed.

**Sources** (`...sources`)
```kotlin
public sealed interface FlagChange { fun affects(key: FlagKey): Boolean; data object All; data class Keys(val keys: Set<FlagKey>) }
public interface FlagValueSource { val sourceName: String; fun box(key: FlagKey, type: FlagValueType): FlagValueBox?; val changes: Flow<FlagChange> }
public interface MutableFlagValueSource : FlagValueSource { fun setBox(key: FlagKey, box: FlagValueBox?); fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) /*default loops setBox*/ }
public class FlagChangeEmitter { val changes: Flow<FlagChange>; fun emit(change: FlagChange) }   // lossless, merged per collector, never suspends
```
`SnapshotSource` (sources/SnapshotSource.kt) is a public in-memory source, usable for fakes. Contract: a collector is subscribed by the first suspend of `collect`.

**signals/FlagSignal.kt**: `interface FlagSignal { val name: String; val signalDescription: String get() = name; val requiresRestart: Boolean get() = false }`. No `FlagSignalGroup`, no send-result type, no history type exist.

**transport** (`...transport`)
```kotlin
public data class FlagPayload(val values: Map<FlagKey, FlagValueBox>, val exportedAt: Instant = Instant.now(), val formatVersion: Int = CURRENT_FORMAT_VERSION) {
    public fun jsonBytes(): ByteArray   // iOS-identical export JSON; throws FlagSerializationException.NonFiniteNumber
    companion { const CURRENT_FORMAT_VERSION = 1; fun decode(bytes: ByteArray, schema: FlagSchema): FlagPayload }  // all-or-nothing, FlagImportException
}
public object FlagQRCode {
    const val PREFIX = "FFQR1:"; const val MAX_ENCODED_LENGTH = 2953
    fun encode(payload: FlagPayload): String          // FlagQRCodeException.PayloadTooLarge(bytes, limit, overrideCount)
    fun decode(text: String, schema: FlagSchema): FlagPayload   // UnrecognisedFormat | Corrupt | FlagImportException.*
}
// FlagPoleTransport.kt: FlagPole<*>.{publishSchema(dir), exportPayload(), exportJson(), importJson(bytes), apply(payload), qrCodeString(), importQRCode(text)}
```
`FlagPayload`/`FlagQRCode` take a `FlagSchema`, not a pole, so the companion can use them with the schema from `Connected`. The pole extensions need a pole, which a companion lacks: apply an import via `FlagBridgeSource.setBoxes(payload.values)` (one atomic host commit; add `null` entries for replace semantics). QR image rendering is NOT in core (string only).

**errors/** (all RuntimeException subclasses; `.message` is UI-ready)
- `FlagException` (sealed): `NoMutableSource`, `UnsupportedValue(key)`.
- `FlagImportException` (sealed): `Malformed(reason)` -> "The document could not be read: $reason."; `UnsupportedFormatVersion(version)`; `Rejected(problems: List<FlagImportProblem>)` -> "Nothing was applied. N problem(s):\n  • ...\nImport is all or nothing, so no flag was changed." `FlagImportProblem(key, kind: Kind{UnknownKey,TypeMismatch,UnknownCase}, expected?, found?)`, `toString()` is the line.
- `FlagQRCodeException` (sealed): `PayloadTooLarge(bytes, limit, overrideCount)` -> "N override(s) need X characters and a QR code holds 2953. Reset a few, or export JSON instead."; `UnrecognisedFormat` -> `Not a flag code. A flag code starts with "FFQR1:".`; `Corrupt` -> "The code could not be decompressed, which usually means a partial scan. Try again with the whole code in frame."
- `FlagSchemaException` (sealed): `UnsupportedFormatVersion(version)`, `Malformed(reason, cause)`, `NotPublished(cause)` -> "No schema has been published. The host app writes one with publishSchema(), so run it at least once."
- `FlagSerializationException.NonFiniteNumber(key: FlagKey?)`.
- `RemoteOverrideException`/`RemoteOverrideProblem`: remote mapping, not UI.

**Host-side (probably unused by the companion)**: `FlagPole<F>(spec, sources.., keyEncoding, applicationName)` with `schema`, `overrides`, `resolution(key,type): FlagResolution(key, sourceName?, box?)` (`isDefault`), `setOverride`, `removeAllOverrides`, `changes`; `audit/`: `FlagRecordDiff`, `FlagFieldDiff`, `FlagDefaultComparison`, `FlagMappingAudit` (only if the UI shows diffs).

### 1.2 featureflag-android (`featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/`, namespace `com.andyyhope.featureflag.android`, manifest is empty `<manifest />`)

**bridge/FlagBridgeClient.kt** (`...android.bridge`)
```kotlin
public class FlagBridgeClient(context: Context, public val hostPackage: String, public val timeout: Duration = 5.seconds) {
    public suspend fun connect(): FlagBridgeConnection                  // never throws
    public suspend fun send(signal: FlagSignal, timeout: Duration = this.timeout): Boolean   // never throws
    public suspend fun source(schema: FlagSchema, name: String = "Companion"): FlagBridgeSource   // throws FlagBridgeException.Unavailable; safe from main thread
}
```
`send`: `deadline = elapsedRealtime + timeout` goes in extras `KEY_DEADLINE`; runs `call("signal", signal.name, extras)` on an IO scope under `withTimeoutOrNull`; returns `getBoolean(KEY_HANDLED)`, false on any exception/timeout. Boolean only: not-handled and timed-out are indistinguishable; relaunch hint must come from `signal.requiresRestart`.
`reloadSchema` does NOT exist as a function. The spec's `FlagEditingStore(schema, MutableFlagValueSource, reloadSchema)` means a lambda M3 supplies, e.g. `suspend { (client.connect() as? Connected)?.schema }`. `connect()` re-opens `content://<host>.featureflag/schema` every call (host rewrites the file each launch via `publishSchema(context)`). A held `FlagBridgeSource` keeps the schema passed to `source(schema)` and validates `overrides` replies with it; `refresh()` throws `Rejected` when the host's schema drifted, so a schema reload needs a NEW source (close the old one).

**bridge/FlagBridgeConnection.kt**
```kotlin
public sealed interface FlagBridgeConnection {
    public val message: String?          // null only for Connected
    data class Connected(val schema: FlagSchema)
    data class UsesPermissionMissing(val permission: String)
    data class HostNotFound(val hostPackage: String)
    data class PermissionDenied(val hostPackage: String, val hostCertificates: List<String>, val companionCertificates: List<String>)   // SHA-256 uppercase hex
    data class BridgeNotInstalled(val hostPackage: String)
    data class Unresponsive(val hostPackage: String, val timeout: Duration)
    data class Incompatible(val reason: String)
    data object NotPublished
}
```
Messages are UI-ready (HostNotFound mentions the `.debug` suffix; PermissionDenied lists both cert hashes and `knownSigner`). Check order: UsesPermissionMissing, HostNotFound, BridgeNotInstalled, PermissionDenied, then hello/schema (Incompatible / NotPublished / Unresponsive / Connected).

**bridge/FlagBridgeException.kt**: sealed `FlagBridgeException(message, cause)`: `NotPublished`, `Rejected(reason)` ("The host rejected the change: $reason"), `TooLarge(bytes, limit)`, `Unavailable(cause)` ("The host could not be reached. Open the host app once, then try again.").

**bridge/FlagBridgeSource.kt**
```kotlin
public class FlagBridgeSource internal constructor(client, schema, sourceName) : MutableFlagValueSource, AutoCloseable {
    override val changes: Flow<FlagChange>
    public val lastFailure: StateFlow<FlagBridgeException?>     // null = last refresh ok; cache keeps last good values
    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox?   // in-memory, type-matched, no IPC
    override fun setBox(key, box) / setBoxes(changes)           // BLOCKING IPC (METHOD_APPLY); throws FlagBridgeException; off the main thread
    public fun refresh()                                        // BLOCKING IPC (METHOD_OVERRIDES); throws FlagBridgeException; emits FlagChange.All on difference
    override fun close()
}
```
Built only via `client.source(schema)`. A `ContentObserver` on `content://<host>.featureflag/overrides` (own HandlerThread) calls `refresh()`, swallowing failures into `lastFailure`. Writes emit `FlagChange.Keys`. Spec's "re-read on ON_START" = call `refresh()` on a background thread.

**bridge/FlagBridgeContract.kt** (`object`)
`PROTOCOL = 1`; `PREFERENCES_NAME = "com.andyyhope.featureflag.companion"`; `SCHEMA_DIRECTORY = "featureflag"`; `MAX_PAYLOAD_BYTES = 512 * 1024`; `METHOD_HELLO="hello"`, `METHOD_OVERRIDES="overrides"`, `METHOD_APPLY="apply"`, `METHOD_SIGNAL="signal"`; keys `KEY_PROTOCOL="protocol"`(Int), `KEY_STATUS="status"`, `KEY_MESSAGE="message"`, `KEY_PAYLOAD="payload"`(ByteArray), `KEY_REMOVE="remove"`(Array<String>), `KEY_HANDLED="handled"`(Boolean), `KEY_SIZE="size"`(Int), `KEY_DEADLINE="deadline"`(Long, elapsedRealtime ms); statuses `STATUS_OK="ok"`, `STATUS_NOT_PUBLISHED="not-published"`, `STATUS_REJECTED="rejected"`, `STATUS_TOO_LARGE="too-large"`; `authority(host)="$host.featureflag"`, `permission(host)="$host.permission.FEATURE_FLAG_BRIDGE"`, `schemaUri(host)`, `overridesUri(host)`, `schemaDirectory(context)=File(noBackupFilesDir,"featureflag")`.

**signals/FlagSignalChannel.kt** (host side): `class FlagSignalChannel internal constructor(mainThread: Executor?)`; `fun <S : FlagSignal> observe(signals: List<S>, handler: (S) -> Unit): AutoCloseable`; `fun deliver(name: String, timeout: Duration = DELIVERY_TIMEOUT): Boolean`; `companion { val DELIVERY_TIMEOUT = 4.seconds; val host }`.
**transport/AndroidTransport.kt**: `fun FlagPole<*>.publishSchema(context: Context): File`. Host calls it every launch in `Application.onCreate`.
**sources/SharedPreferencesSource.kt**: `class SharedPreferencesSource(preferences, sourceName = "SharedPreferences") : MutableFlagValueSource, AutoCloseable`; `refresh()`; `close()`; `companion.companion(context, name = "Companion")` (host side).

### 1.3 featureflag-bridge (`featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt`)
`public class FlagBridgeProvider : ContentProvider()`. Manifest: `<permission ${applicationId}.permission.FEATURE_FLAG_BRIDGE protectionLevel=signature>` + exported `<provider authorities=${applicationId}.featureflag permission=...>`.

`call(method, arg, extras)`:
1. `enteredAt = elapsedRealtime()`; `enforceCallingPermission`.
2. Expired-signal fast path BEFORE the startup barrier: method == signal, extras has `KEY_DEADLINE`, `deadline <= enteredAt` -> return `handled=false`.
3. `awaitStartup()`: if not yet started and not on the main looper, post to main and `await(startupTimeout = 10s)`; then `started = true`.
4. Signal: `timeout = deliveryTimeout(extras, enteredAt)`; `handled = timeout > ZERO && FlagSignalChannel.host.deliver(arg.orEmpty(), timeout)`.

`deliveryTimeout`: no `KEY_DEADLINE` (old client) -> `DELIVERY_TIMEOUT(4s) - (now - enteredAt)`, floor 0; with deadline -> `deadline <= now` => 0, else `(deadline - now - REPLY_MARGIN 100ms).coerceIn(0, MAX_DELIVERY 30s)`. Test seams: `internal var startupTimeout`, `internal var elapsedRealtime: () -> Long`. `deliver` runs handlers on main through a FutureTask; a timeout withdraws it.

Where a delay extra slots in: add `KEY_DELAY` (Long ms) to `FlagBridgeContract`, read it in the METHOD_SIGNAL branch of `call` after step 3 and before `deliver`. Options: (a) provider sleeps (holds a binder thread, and the client's deadline would have to include the delay: `deadline = now + delay + timeout`; simplest, the delay stays host-side so a frozen companion cannot stall it, but the call stays open and `handled` remains a real result); (b) provider schedules `Handler(mainLooper).postDelayed` and replies at once (needs a new reply key, `handled` no longer meaningful). `deliveryTimeout` must subtract the delay in (a). Client `send` needs a `delay: Duration` parameter and a per-call timeout of `delay + timeout`.

### 1.4 Gaps for a companion UI
- No text parser/formatter for editors: `FlagValueJson.decode/encode` is JSON text (strings need quotes). Nothing public turns typed "42"/"0.5"/"https://..." into a box, or a box into display text (Int/Double/Float/Url/Date/String). `MessageText.short`/`caseList` are internal to core. M3 needs its own helpers (UI module or promoted into core).
- No public record-list codec (`RecordJson` is internal): the record editor must turn `FlagValueBox.String(json)` into rows via `recordShape` and back (compact sorted-key JSON, unique-key detection). UI cannot parse JSON itself (JsonReader internal; `org.json` is Android-only, breaks JVM-testability of `FlagEditingStore`). Core must expose it.
- No override-resolution view for the companion: `FlagPole.resolution` needs a pole. Effective value = `source.box(key,type) ?: entry.defaultValue`; overridden counts computed in the store.
- Absent: `FlagEditingStore`, `FlagCompanion`, `FlagCompanionTab`, `FlagSignalGroup`, `FlagFileProvider`, ViewModel/retry, QR PNG (ZXing), send-history model.
- `send` returns Boolean only; no cancel API beyond coroutine cancellation (a send already past the binder call may still deliver).
- No reload-schema primitive; a held source is bound to its schema.
- Source writes and `refresh()` block: wrap in `Dispatchers.IO`.
- No reset-all API: use `source.setBoxes(schema.flags.associate { it.key to null })` (as the androidTest does). Only schema keys: the provider rejects unknown removal keys.
- Payload over 512 KiB -> `FlagBridgeException.TooLarge` (client side, before sending).
- `FlagSchema` has no group key; derive sections from `Group.propertyPath` being a prefix of `Entry.propertyPath`.

## 2. Build conventions

**settings.gradle.kts**: `pluginManagement { includeBuild("build-logic"); repositories gradlePluginPortal, google, mavenCentral }`; `dependencyResolutionManagement { FAIL_ON_PROJECT_REPOS; google(); mavenCentral() }`; `rootProject.name = "feature-flags-android"`; `include(":featureflag-core"|":featureflag-ksp"|":featureflag-test"|":featureflag-android"|":featureflag-bridge")`. A new module is one `include(":featureflag-ui")`.
**Root build.gradle.kts**: aliases `kotlin.jvm`, `ksp`, `android.library`, `detekt` with `apply false` (loads plugins once in the root classloader); detekt on root covers `build-logic/src/main/kotlin` and `*.gradle.kts`. A new plugin used by convention code (e.g. the Compose compiler plugin) is applied by id from build-logic, so it must be on build-logic's classpath.
**gradle.properties**: `org.gradle.caching=true`, `parallel=true`, `configuration-cache=true`, `android.useAndroidX=true`, `kotlin.code.style=official`, `-Xmx4g`. Gradle runs with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
**config/detekt/detekt.yml**: copy of yewki's (header says "Yewki"): Compose-friendly (FunctionNaming/TopLevelPropertyNaming ignoreAnnotated Composable; LongMethod, LongParameterList, TooManyFunctions, CyclomaticComplexMethod, NestedBlockDepth, MagicNumber, ReturnCount, UnusedPrivateMember, MaxLineLength, MatchingDeclarationName off; formatting ArgumentListWrapping etc. off).

**build-logic/build.gradle.kts**: `kotlin-dsl`; `implementation(libs.kotlin.gradle.plugin, libs.android.gradle.plugin, libs.detekt.gradle.plugin)`; `gradlePlugin { register("jvmLibrary"|"androidLibrary"|"detekt"|"fixtures") }` with ids `featureflag.jvm-library`, `featureflag.android-library`, `featureflag.detekt`, `featureflag.fixtures`. A Compose convention needs one more `register("compose") { id = "featureflag.compose"; implementationClass = "ComposeConventionPlugin" }` and the compiler plugin dependency.

Convention plugins (`build-logic/src/main/kotlin/`):
- `JvmLibraryConventionPlugin`: applies `org.jetbrains.kotlin.jvm`, `featureflag.detekt`, `featureflag.fixtures`; `jvmToolchain(21)`, `explicitApi()`, `jvmTarget 17`, `allWarningsAsErrors=true`, `-Xjdk-release=17`, javac `release=17`; testImplementation kotlin-test-junit, junit, coroutines-test, turbine.
- `AndroidLibraryConventionPlugin`: applies `com.android.library` (never kotlin-android; AGP 9 built-in Kotlin), `featureflag.detekt`, `featureflag.fixtures`. `LibraryExtension`: `compileSdk=36`, `minSdk=26`, `testInstrumentationRunner=androidx.test.runner.AndroidJUnitRunner`, Java 17 source/target, `testOptions.unitTests.isIncludeAndroidResources=true`. `KotlinAndroidProjectExtension`: `jvmToolchain(21)`, `explicitApi()`, `jvmTarget 17`, `allWarningsAsErrors=true`. `DetektExtension.source` = `src/main/kotlin`, `src/test/kotlin`, `src/androidTest/kotlin`. Dependencies: testImplementation = kotlin-test-junit, junit, coroutines-test, turbine, robolectric, androidx-test-core, androidx-test-ext-junit; androidTestImplementation = kotlin-test-junit, junit, coroutines-test, turbine, androidx-test-core/runner/rules/ext-junit. No Robolectric properties file and no screen-size config anywhere: each test class carries `@Config(sdk = [36])`.
- `DetektConventionPlugin`: id `io.gitlab.arturbosch.detekt`, `buildUponDefaultConfig=true`, config `rootDir/config/detekt/detekt.yml`, `detektPlugins` = detekt-formatting, HTML report only.
- `FixturesConventionPlugin`: every `Test` task gets sysprops `featureflag.fixtures` (repo `fixtures/`) and `featureflag.writeAndroidFixtures`, plus both fixture dirs as inputs.
- Module scripts: android/bridge: `plugins { id("featureflag.android-library") }`, `android { namespace = ... }`, `dependencies { api(project(":featureflag-core")) }` / `api(project(":featureflag-android"))`. Core: `featureflag.jvm-library` + `alias(libs.plugins.ksp)`, `api(libs.kotlinx.coroutines.core)`, `kspTest(project(":featureflag-ksp"))`.

**gradle/libs.versions.toml (here)** versions: kotlin 2.2.10, ksp 2.2.10-2.0.2, agp 9.1.1, kotlinxCoroutines 1.10.2, junit 4.13.2, turbine 1.2.1, kotlinpoet 2.2.0, kctfork 0.8.0, robolectric 4.16 (JDK 21 test JVM, SDK 36), androidxTestCore/Runner/Rules 1.7.0, androidxTestExtJunit 1.3.0, detekt 1.23.8. Libraries: kotlin-gradle-plugin, android-gradle-plugin, detekt-gradle-plugin, kotlinx-coroutines-core/test, ksp-*, kotlinpoet(-ksp), kotlin-test-junit, junit, turbine, kctfork-*, robolectric, androidx-test-core/runner/rules/ext-junit, detekt-formatting. Plugins: kotlin-jvm, ksp, android-library, detekt. Nothing for Compose, lifecycle, activity or ZXing; no `android-application` alias.

### How a Compose library convention would be added (from yewki, same Kotlin 2.2.10 / AGP 9.1.1)
`yewki/android/build-logic/src/main/kotlin/AndroidComposeConventionPlugin.kt` (`yewki.android.compose`), essence:
```kotlin
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        when (val ext = androidExtension()) {
            is ApplicationExtension -> ext.buildFeatures.compose = true
            is LibraryExtension -> ext.buildFeatures.compose = true
            else -> error("... requires com.android.{application,library}")
        }
    }
    private fun Project.androidExtension(): Any? =
        extensions.findByType(ApplicationExtension::class.java) ?: extensions.findByType(LibraryExtension::class.java)
}
```
Build-logic classpath in yewki: `implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")` (alongside AGP 9.1.1 and kotlin-gradle-plugin 2.2.10). Here: add catalog entry `compose-compiler-gradle-plugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }` and `implementation(libs.compose.compiler.gradle.plugin)` in build-logic; module script: `plugins { id("featureflag.android-library"); id("featureflag.compose") }`. The `ApplicationExtension` branch is only needed if a sample app is added (this repo's library plugin has no application counterpart).
`RobolectricSharedConfig.kt`: `internal fun Project.useSharedRobolectricConfig(testSourceSet: AndroidSourceSet)` adds `rootDir/config/robolectric` to the test resources via `testSourceSet.resources.directories.add(rootDir.resolve("config/robolectric").absolutePath)` (not deprecated `srcDir`), unless the module has its own `src/test/resources/robolectric.properties`. Yewki's `config/robolectric/robolectric.properties` is one line: `qualifiers=w320dp-h800dp`, because Robolectric's default screen is 320x480px (470dp tall), which clips Compose content. It is invoked from yewki's AndroidLibraryConventionPlugin as `useSharedRobolectricConfig(sourceSets.getByName("test"))` inside the `LibraryExtension` block. This repo has neither; Compose Robolectric tests here need it (or per-test `@Config(qualifiers = ...)`).
Yewki `gradle/libs.versions.toml` Compose entries, exact:
```
androidxActivity = "1.10.1"
androidxLifecycle = "2.9.1"
composeBom = "2026.01.00"
androidx-activity = { module = "androidx.activity:activity", version.ref = "androidxActivity" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "androidxLifecycle" }   # collectAsStateWithLifecycle
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }                       # all below: no version, BOM-managed
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons-core = { module = "androidx.compose.material:material-icons-core" }
androidx-compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }   # [plugins]
```
(Yewki also has navigationCompose 2.9.6, not wanted here: spec says no navigation-compose. Yewki's robolectric is 4.15.1/SDK 35; keep this repo's 4.16/SDK 36. ZXing is in neither catalog; spec names `zxing core`, version not chosen.)
Yewki consumption (`core/ui/build.gradle.kts`): `implementation(platform(libs.androidx.compose.bom))` + `ui`, `ui-graphics`, `foundation`, `material3`, `ui-tooling-preview`; `debugImplementation(libs.androidx.compose.ui.tooling)`; Robolectric Compose tests: `testImplementation(platform(bom))`, `robolectric`, `ui-test-junit4`, `ui-test-manifest`; instrumented: `androidTestImplementation(platform(bom))`, `androidx-test-ext-junit`, `ui-test-junit4`, `debugImplementation(ui-test-manifest)`. Caveats here: `explicitApi()` applies to every public `@Composable`; `allWarningsAsErrors=true` turns Material3/Compose deprecations and compiler-plugin warnings into build failures; detekt source set already includes `src/main|test|androidTest/kotlin`.

## 3. Test conventions
- Frameworks: JUnit4 + `kotlin.test` assertions (`assertEquals`, `assertFailsWith`, `assertIs`, `assertNull`, `assertTrue/False`), Turbine (`flow.test { awaitItem() }`, used in `FlagBridgeSourceTest`, `CrossProcessBridgeTest`), coroutines-test.
- Android unit tests: `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [36])`; `ApplicationProvider.getApplicationContext<Application>()`; `shadowOf(context)` (permissions, packages); custom `@Implements(ShadowContentResolver::class)` shadows. Suspend tests use `runBlocking<Unit> { ... }` (real IO dispatchers in the client), not `runTest`.
- Naming: camelCase full-sentence names, no backticks (`aReachableHostWithASchemaIsConnected`, `overridesLargerThanTheLimitAreRefusedNotThrown`); `// section` comment lines group tests; test package mirrors main; per-file private helper like `entry(key, type, default)`.
- Schemas in Android tests are hand-built (no KSP): `FlagSchema(generatedAt = Instant.parse("2026-01-01T00:00:00Z"), flags = listOf(FlagSchema.Entry(key = FlagKey("enabled"), propertyPath = listOf("enabled"), description = "enabled", valueType = FlagValueType.Bool, defaultValue = FlagValueBox.Bool(false))))`. Fixed `generatedAt` is needed for `assertEquals(Connected(schema), ...)` (default is `Instant.now()`).
- Core JVM fixtures (core `src/test` only, not shared across modules; no testFixtures): `schema/SchemaFixtures.kt` (`specOf`, `flag`, `group`, `demoSpec` with a `DemoTier` string enum), `scenario/DemoFlags.kt`, `golden/GoldenFlags.kt`, `pole/TestSources.kt` (`ReadOnlySource`, `CountingSource`), `Fixtures.bytes/text/tsv` via the `featureflag.fixtures` sysprop; core runs KSP over test sources (`kspTest`).
- `FakeBridgeProvider` (`featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FakeBridgeProvider.kt`, 209 lines, android module test source set, so NOT reusable from another module): an in-process `ContentProvider` speaking the protocol over an in-memory map. Knobs: `hostPackage`, `protocol`, `schema`, `schemaBytes`, `values`, `handledSignals`, `forcedStatus/forcedMessage/forcedSize`, `deaths: AtomicInteger`, `refusal`, `failure`, `stall: CountDownLatch`, `replyGate/replyReached`, `applyGate/applyReached`, `notifiesOnApply`; recorders `calls`, `extrasSeen`, `onMainThread`; `hostWrites(map)`. Registered via `Robolectric.buildContentProvider(FakeBridgeProvider::class.java).create(authority).get()`. It ignores `KEY_DEADLINE` and has no delay support.
- For M3: the store is plain Kotlin over `(schema, MutableFlagValueSource, reloadSchema)`, so test it on the JVM with `SnapshotSource` or a small fake `MutableFlagValueSource` + Turbine; Compose screens via Robolectric (`createComposeRule`, `ui-test-junit4`) or instrumented tests.
- Instrumented tests (`featureflag-bridge/src/androidTest`) run on AVD `Pixel_7` (API 36): `./gradlew :featureflag-bridge:connectedDebugAndroidTest`.

## 4. Existing apps / host harness
No `samples/`, no application module, no `com.android.application` alias. The only host app is the androidTest harness in `featureflag-bridge/src/androidTest`:
- `AndroidManifest.xml`: `<uses-permission ${applicationId}.permission.FEATURE_FLAG_BRIDGE>` (the test app is its own companion) and `<application android:name="com.andyyhope.featureflag.bridge.HostTestApplication"><provider android:name="...FlagBridgeProvider" android:process=":host" tools:node="merge"/>`.
- `HostTestApplication.kt`: in the `:host` process only (detected from `/proc/self/cmdline` ending `:host`) it sleeps `STARTUP_MILLIS = 1_000L`, then `BridgeTestHost.schema.write(FlagBridgeContract.schemaDirectory(this))` and `FlagSignalChannel.host.observe(listOf(Purge, Explode, DieOnce, Slow))` (Explode throws; DieOnce kills the process once via marker file `die-once`; Slow sleeps `SLOW_HANDLER_MILLIS = 4_500L`). The main process (tests = companion) does nothing.
- `enum class BridgeTestSignal : FlagSignal { Purge, Explode, DieOnce, Slow, Unobserved }`; `object BridgeTestHost.schema`: 10 flags, one per value type (enabled Bool, limit Int, ratio Double, scale Float, tier String with cases free/pro, logo Data, launch Date, endpoint Url, markets Array<String>, limits Dictionary<Int>), no groups, no record shapes, no signal `requiresRestart`/descriptions.
- `CrossProcessBridgeTest` (266 lines): `FlagBridgeClient(context, context.packageName, timeout = 15.seconds)`; `@Before` clears overrides with `source().setBoxes(schema.flags.associate { it.key to null })`; uses Turbine.
- `ArtNumberDescriptionTest` (77 lines): checks number formatting on ART.
A Compose androidTest or sample app would extend this pattern; nothing here is shared outside the bridge module.
