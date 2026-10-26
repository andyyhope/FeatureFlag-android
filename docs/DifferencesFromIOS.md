# Differences from iOS

Where this library departs from FeatureFlag for iOS, and why.

## Overview

This is a port of [FeatureFlag](https://github.com/andyyhope/FeatureFlag) 0.12.0. The
model is the same — a container of flags, an ordered stack of sources, a schema a
companion app renders — and the formats are the same: an export, a QR code or a schema
written on one platform reads on the other.

Everything below but the last section is a deliberate difference; the last is a quirk the
two share. Each is one line on what differs and one on why.

### Declaring and reading

- **Containers are interfaces read by KSP, not macros on structs.** Kotlin has no macros;
  the `featureflag-ksp` processor generates `AppFlagsSpec` and `AppFlagsRefs` instead.
- **The default is the property's getter, evaluated once.** It runs once for `pole.flags`,
  `pole[ref]`, `pole.flow`, `pole.resolution`, the schema and `ref.defaultValue`;
  `flagDefaults` runs it again on each read. A getter that reads another flag sees its
  compiled default, and `Instant.now()` keeps its first value in the first group.
- **`description` and `remoteKey` are annotation arguments; an empty `remoteKey` means
  none.** Annotation arguments cannot be null.
- **A flag's handle is a `FlagRef` on the spec, not a `$` projected value.** Kotlin has no
  projected values, so `AppFlagsSpec.checkout.googlePay` names the flag, `pole[ref]` reads
  it and `pole.flow(ref)` watches it.
- **There is no `pole.someFlag` shorthand.** Kotlin has no dynamic member lookup, so a
  flag is always read as `pole.flags.someFlag`, and no flag name collides with the pole's.
- **Six property names are reserved instead of five.** iOS reserves `flags`, `keys`,
  `schema`, `overrides` and `descriptors`; here `flagDefaults`, `flagDescriptors`,
  `flagContainerDescription`, `createFlags`, `flagLookup` and `flagKeys` belong to the
  generated code.
- **A container must be an interface with no type parameters and no supertypes, and not
  private.** The generated spec has to implement it and know every flag's type.
- **Nested declarations get `_`-joined generated names.** `Outer.Inner` generates
  `Outer_InnerSpec`, which keeps generated names unique.
- **A group or record from another module is found by its generated name.** KSP sees one
  module at a time, so apply the processor to every module that declares flags.
- **A container that depends on one the processor rejected is not generated either.**
  Only the root cause is reported, rather than a cascade.
- **A type of your own called `Flag` is handled with an import alias.** Kotlin has no
  module-qualified attribute syntax like `@FeatureFlag.Flag`.

### Values

- **The built-in types are Kotlin's.** `Boolean`, `Int`, `Long`, `Double`, `Float`,
  `String`, `ByteArray`, `Instant` and `URI`, with `Int` and `Long` both stored as the
  schema's `int`.
- **There is no custom `FlagValue` conformance.** The processor picks a codec at compile
  time and cannot find one you wrote; store the underlying type and wrap it.
- **Every enum publishes its entries, with no opt-in.** The processor can always list
  them, so every enum flag is a picker.
- **A plain enum is stored by its entry's `name`.** No raw value is needed; implement
  `FlagStringEnum` or `FlagIntEnum` to store something else.
- **An integer-backed enum's raw value is a `Long`.** It is the type every integer is
  stored as.
- **A record's fields are its primary constructor's properties.** A property in the class
  body is not a field, and `@FlagRecordKey` and `@FlagRecordProperty` go on constructor
  parameters.
- **A field's default is read from a record built with placeholders.** So a default must
  not depend on another constructor parameter.
- **`FlagRecords` is a `List` with no keyed subscript.** Find a record with
  `firstOrNull { it.name == … }`.
- **A remote record field holding infinity is a `TypeMismatch`.** It is refused like any
  other bad value rather than escaping as an exception.

### JSON, dates and numbers

- **JSON is read and written by the library itself.** That is what keeps the bytes
  identical to Apple's `JSONSerialization` output.
- **Only UTF-8 input is read.** UTF-16 and UTF-32 documents are refused.
- **Numbers past `Double` precision or `Long` range read differently.** Apple routes them
  through `NSDecimalNumber`; here an integer above `Long.MAX_VALUE` reads as a `Double`.
- **`-0.0` reads as `0`.** The JSON reader does not keep the sign of zero.
- **Dates are ISO 8601 in UTC, to the second, in their canonical forms only.** The lenient
  forms Apple's parser accepts are refused, so a date means one thing.
- **Keys outside ASCII sort by code point.** Apple sorts them with ICU collation, so a
  document with such keys may order them differently, though it reads the same.
- **A URL with a non-ASCII host may be encoded differently.** Percent-encoding follows
  Apple's `URL(string:)`, but host names are not converted the same way.
- **`\u` escapes accept ASCII hex digits only.** The reader is strict where Apple's is
  lenient.
- **A long value in a message is cut by code points.** Swift counts grapheme clusters, so
  a message quoting a value with emoji or combining marks may cut it at a different place.
- **Error messages say "the store" where iOS says "UserDefaults"**, and drop the App
  Group, property-list and Core Image cases, which do not exist here.

### The pole, sources and transport

- **Changes are Kotlin flows, not Combine publishers or `ObservableObject`.** `flow(ref)`
  emits the current value and then each distinct change, in the collector's context.
- **A custom source announces changes through `FlagChangeEmitter`.** A flow with a bounded
  buffer can drop a change and leave a flag stale; the emitter merges instead.
- **Errors are exceptions.** `RemoteOverrideError.rejected` is
  `RemoteOverrideException.Rejected`, `FlagImportError` is `FlagImportException`,
  `FlagQRCodeError` is `FlagQRCodeException`, and so on; a problem's kind is a PascalCase
  `Kind` entry, such as `Kind.UnknownKey`. `FlagMappingAuditException` lives in the `audit`
  package, and there is no `imageGenerationFailed`. Kotlin has no throwing enums.
- **`UserDefaultsSource` is `SharedPreferencesSource`.** `Boolean`, `Long`, `Float` and
  `String` are stored natively and every other type as JSON text. A value stored as
  another type reads as nothing, with no numeric conversion, and a write that changes
  nothing is skipped. The source holds its change listener strongly, because
  `SharedPreferences` holds listeners weakly.
- **Source names are "Companion" and "Remote", not "App Group".** They are what
  `resolution(…).sourceName` reports for `SharedPreferencesSource.companion(context)` and
  `RemoteOverrideSource`.
- **A pole refuses a remote source whose keys it does not have.** iOS resolves nothing and
  says nothing.
- **App Groups, Darwin notifications and the foreground re-read are replaced by the
  bridge.** Android has no shared container between apps; an exported, signature-guarded
  provider is the door instead.
- **`applicationName` defaults to nothing.** There is no bundle display name to fall back
  on, so the key is left out unless you give one.
- **`publishSchema(context)` writes to `noBackupFilesDir`.** A restored backup never brings
  back another build's schema.
- **`applyPayload` returns nothing, and `importJson` and `importQRCode` return the payload
  they applied.** An import result adds nothing the payload does not already say.
- **There are no property lists.** JSON is the only format, for export, import and
  remote payloads.
- **There is no `qrCodeImage()`.** The core library has no image library; the companion
  draws the code.
- **A QR code's compressed bytes may differ from iOS's.** The zlib implementations differ,
  but the JSON inside is identical and each platform reads the other's codes.
- **`EnvironmentConfiguration.load` suspends, and an overtaken load is `Superseded`.** Two
  quick environment switches can never apply in the wrong order.
- **`assertFlagsFullyMapped` throws `AssertionError`.** It works with any test framework,
  not only XCTest.
- **A custom mapper is a `fun interface`, and `RemoteValue.valueAt(path)` reads a path.**
  The Kotlin shapes for the same idea.

### Signals and the bridge

- **A signal starts a host that is not running.** Android starts an app to answer its
  provider; on iOS a signal to a suspended app is lost.
- **A signal is delivered once per send, with no sequence numbers.** Each send is one call
  to the provider, answered once; a host that dies after running the handler may run it
  again on the retry.
- **`send` returns whether a handler ran; it does not throw `notAcknowledged`.**
  `connect()` says why the host could not be reached.
- **The host holds a signal's delay.** A companion frozen in the background cannot stall
  it; the longest delay is a minute.
- **The countdown is a notification, not a Live Activity.** A progress notification that
  asks to be promoted to a Live Update on Android 16 and later, a chronometer below, and
  nothing to add to the companion.
- **Trust is a signature permission.** The companion is signed with the host's key, or
  named by certificate with `knownSigner` on Android 12 and later.
- **`connect()` reports eight results.** Each failure has its own fix, where iOS shows one
  message naming two causes.
- **The provider waits for `Application.onCreate`.** So the client's timeout has to cover a
  cold start of the host.
- **A call that finds the host dead is retried once.** The companion survives the host
  dying.
- **The companion's live source blocks.** `FlagBridgeClient.source()` suspends, but its
  writes and `refresh()` are blocking calls to make off the main thread; `lastFailure`
  publishes the latest refresh failure.
- **A request or reply over 512 KiB is refused.** That keeps clear of binder's limits.
- **A host cannot bridge to itself.** The companion is a separate app by design.
- **`featureflag-bridge` is a separate artifact.** An exported provider should be something
  an app opts into, and it can be a debug-only dependency.

### The companion app

- **The companion is one Compose composable, `FlagCompanion`.** The SwiftUI screens are
  not public; a custom tab gets the store and the standard row instead.
- **Tabs are `FlagCompanionTab` values.** `Overrides`, `Flags`, `Signals`, `Detail` and
  `Custom`; two tabs with one id throw, and an empty list shows "No tabs".
- **The companion reconnects every time it returns to the foreground.** A reinstalled
  host with a new schema is picked up without restarting the companion.
- **A signal group takes a list of entries, and its nesting threshold is fixed at six.**
  `FlagSignalGroup("Caches", CacheSignal.entries)`.
- **`FlagEditingStore` writes suspend.** A write over the bridge is a call into the host.
- **Resetting every override asks first.** It cannot be undone.
- **A failed write is shown, not swallowed.** The row returns to the host's value.
- **`nan`, `inf` and `infinity` are refused in a number field.** They could not be
  exported afterwards.
- **An import is one write.** iOS writes key by key and rolls back on failure.
- **There is no "Copy property list".** There are no property lists.
- **A text field whose text equals the value in effect writes nothing.** Leaving a field
  never turns a default into an override.
- **Rows are reordered from an "Actions" menu, not by dragging.** "Save" on a QR code
  opens the share sheet.

### Platforms and distribution

- **Android only, minimum SDK 26.** `java.time` needs it. There is no Wear OS or TV
  variant of the companion, just as iOS has none for watchOS or tvOS.
- **The core library is plain Kotlin on the JVM.** It runs in JVM tests and tools with no
  Android at all.
- **Artifacts are Maven artifacts with a BOM, not a Swift package.** They are prepared
  for Maven Central but not on it yet. Consumers need Kotlin 2.2 or later and a KSP
  matching their Kotlin.
- **Behaviour across processes was measured on Android 16.** Older releases, below
  Android 11 in particular, may differ.

### Same on both platforms: a known quirk

- **A record list sent as a JSON string is checked only as stored text.** Its fields are
  read by property name, enum entries and nested lists are not checked, and the text is
  stored as sent. FeatureFlag 0.12.0 for iOS does this, and this library mirrors it so the
  remote test fixtures both platforms share stay identical. Send record lists as arrays.
