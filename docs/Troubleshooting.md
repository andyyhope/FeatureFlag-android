# Troubleshooting

The failures worth recognising on sight, and what each one actually means.

## Overview

Most of these are properties of the platform rather than bugs in the library, which is
why they are documented rather than fixed. A companion that cannot connect always says
why, in words; the first section is those words.

### The companion cannot connect

`FlagCompanion` shows the first thing in the way, and `FlagBridgeClient.connect()`
returns it. Each has its own fix.

**The companion does not declare the permission.**

<!-- output: troubleshooting-uses-permission-missing -->
```text
This app does not request 'com.example.app.permission.FEATURE_FLAG_BRIDGE'. Add <uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" /> to the companion's manifest.
```

**The host cannot be found.** Either it is not installed, or its application id is not
the one the companion names — a debug build with an `applicationIdSuffix` is a different
app — or it does not include `featureflag-bridge`. On Android 11 and later an app without
the bridge is invisible to the companion, so the last case looks exactly like the first:

<!-- output: troubleshooting-host-not-found -->
```text
No app with the package 'com.example.app' is installed, or it does not include featureflag-bridge. Install and run the host app, check the package name (a debug build often adds a suffix such as '.debug'), and check that the host depends on featureflag-bridge.
```

On Android 10 and earlier, a host without the bridge is visible, and the companion says
so directly: `BridgeNotInstalled`.

**The two apps are signed with different keys.** The companion shows both certificates,
so you can see which build is the odd one out:

<!-- output: troubleshooting-permission-denied -->
```text
'com.example.app' only lets apps signed with its own key connect. Sign this app with the same key, or list this app's certificate in the host's knownSigner permission (Android 12 and later). Host: 3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7. This app: B04C2E7A19D35F6C8E0A4B7D2F9C1E3A5B8D0F2C4E6A8B1D3F5C7E9A0B2D4F61.
```

Sign both with the same key, or list the companion's certificate in the host's
`knownSigner` permission — see [The bridge](TheBridge.md).

**The host has not published a schema.**

<!-- output: troubleshooting-not-published -->
```text
No schema has been published. The host app writes one with publishSchema(), so run it at least once.
```

Run the host once — publishing happens when *it* launches, in `Application.onCreate`, not
when the companion does. If it has run and the companion still says this, the host is not
calling `publishSchema(context)`.

**The host does not answer.** `Unresponsive` means the call timed out. Starting a host
that is not running counts against the timeout, so a slow `Application.onCreate` can cause
it; open the host once, or give the companion a longer `timeout`.

**`Incompatible`** means the host was built with a different version of the bridge
protocol, or published a schema this build cannot read. Build both apps from the same
version of the library.

### A flag changed in the companion but the app did not react

Check in this order:

1. **Is the companion's source in the stack?** Only `SharedPreferencesSource.companion(context)`
   reads the file the bridge writes. A `SharedPreferencesSource` over any other file never
   sees a companion's edit.
2. **Is something above it winning?** Ask:

<!-- snippet: readme-resolution -->
```kotlin
val sourceName = pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion", or null for the default
```

3. **Is the code reading the flag once and keeping the value?** A value read at start-up
   and stored stays what it was. Collect `pole.flow(ref)` instead — see
   [Observing changes](ObservingChanges.md).

### "Flags must resolve to unique keys" on the first launch

Two flags encode to the same storage key. The message names both properties:

<!-- output: declaring-duplicate-keys -->
```text
Flags must resolve to unique keys, but 'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly. Rename one of the properties, or use a KeyEncoding that tells them apart.
```

There is no runtime remedy — both flags want the same storage, and letting one win
silently would mean the other never takes effect anywhere — so this fails when the schema
is built rather than later. Renaming a property is usually the fix.

The default encoding collides only when two names differ just in letter case, as an
acronym's does in `useHTTPSOnly` and `useHttpsOnly`.
A custom `KeyEncoding` collides far more easily: anything that maps two distinct property
names onto one string will do it. A unit test that builds the schema finds it first — see
[Declaring flags](DeclaringFlags.md#keys).

### The processor rejects a declaration

The processor reports at the declaration that is wrong. The messages worth recognising:

```text
flags cannot be optional: a flag always has a value, because 'default' is what it falls back to. Use a sentinel the type already has, or an enum with a case for 'unset'
```

A flag's type cannot be nullable. `null` already means "no source supplied one" at every
layer beneath — `box()` returning `null` means *try the next source*, and
`setOverride(key, null)` means *remove the override* — so a flag whose value could be
`null` would be indistinguishable from one nothing supplies. Use a sentinel the type
already has, or an enum with an entry for unset.

```text
'checkout' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. Add '@FlagGroup(description = …)' if it is a nested container, '@Flag(description = …)' and a default getter if it is a value, or give it a getter if it is neither
```

Almost always a nested container written without `@FlagGroup`.

```text
'@Flag' needs a 'default' value to fall back to: give 'pageSize' a getter, as in 'val pageSize: Int get() = …'
```

```text
a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' or 'List<Endpoint>'
```

A `@FlagRecord` class declared as a flag's own type, or as a plain list of one.

```text
'flagDefaults' is reserved for the code generated for 'AppFlags'. Rename the property
```

Six names belong to the generated code: `flagDefaults`, `flagDescriptors`,
`flagContainerDescription`, `createFlags`, `flagLookup` and `flagKeys`.

### KSP fails with "Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"

KSP 2.2 still adds its generated sources the way AGP 9's built-in Kotlin forbids. Until a
KSP release stops, an Android module that runs KSP needs this in `gradle.properties`:

```
android.disallowKotlinSourceSets=false
```

Gradle then prints a one-line warning that the option is experimental, which is
expected.

### The compiler rejects `@Flag` as an illegal annotation class

Kotlin resolves a name by an explicit import first, then a declaration in the same
package, then star imports. So when your package declares its own type called `Flag` and
the file only has `import com.andyyhope.featureflag.*`, your type shadows the annotation
and the compiler rejects `@Flag` as an illegal annotation class. Import the annotation
under another name:

<!-- snippet: declaring-flag-alias -->
```kotlin
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.Flag as FeatureFlag

@FlagContainer
interface AliasedFlags {
    @FeatureFlag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false
}
```

Generated code imports everything it names, so nothing else in the library can be shadowed
by a type of your own, not even one named like one of the library's.

### A remote payload appears to do nothing

Ask what the apply reported:

<!-- snippet: remote-apply-result -->
```kotlin
val result = remote.apply(bytes)

val applied = result.appliedKeys // flags this payload set
val absent = result.absentKeys // flags with a remoteKey the payload did not mention
```

A flag in `absentKeys` fell through to whatever sits below the remote source. Usually the
backend key changed, or the dot path has a typo. A flag with no `remoteKey` at all is not
remotely overridable and will never appear in either list.

If nothing applied and it threw instead, `RemoteOverrideException.Rejected` carries every
problem — one bad field rejects the whole payload by design. A
[mapping audit](ValidatingAConfig.md) shows both directions at once.

### A remote value never shows up

A pole refuses a remote source built with a different `keyEncoding` from its own.
Constructing the pole throws `IllegalArgumentException`, and the message begins "RemoteOverrideSource 'Remote' describes
'new-onboarding', which this pole does not have." The source's keys are spelled one way and
the pole's another, so the pole would never have asked for them. Build the source with the
pole's `keyEncoding`, as [Remote Overrides](RemoteOverrides.md) shows. A source built from a
hand-made schema that covers only some of the pole's flags is fine; only a key the pole
lacks is refused.

### Importing throws `Rejected` with `UnknownKey`

The document was exported from a different app, or from a build where that flag has since
been renamed or removed. Import is all-or-nothing so an app never runs on half a
configuration; there is no partial-apply option, on purpose.

### A QR code will not encode

`FlagQRCodeException.PayloadTooLarge` carries the override count so you can say what to
remove. `ByteArray` flags are the usual culprit: base64 does not compress, and one blob
can exhaust the whole code. Export a document and share that instead.

### A signal reports that it was not handled

`send` returns `false` when no observer in the host lists the signal, when the handler
threw, when the host could not be reached, or when it did not answer in time. A host that
is not running is started, but starting it counts against the timeout, so a slow
`Application.onCreate` can be the cause: pass a longer `timeout`. Check too that the host
registers its observers in `Application.onCreate`, that both apps agree on the signal's
name — the same shared enum — and that the handler returns within the timeout. See
[Sending signals to the host app](SendingSignals.md).

### A record flag reads as its default, and the companion says "This value is not a list of records."

A `FlagRecords` list is all of its records or none of them. One record missing a field,
or holding the wrong type in one, and the whole list is unreadable — so the flag falls
back to its default, silently, exactly as any other type mismatch does.

The usual causes are a file edited by hand, and a build that has added a field since the
stored value was written. The companion cannot produce one: it builds records from the
published shape.

Reset the flag to clear it. To see what is actually stored:

<!-- snippet: records-resolution -->
```kotlin
val stored = pole.resolution(AppFlagsSpec.paymentMethods)
```

Give a new field a default and this stops being a problem: a record stored before the
field existed is filled from it rather than rejected.

<!-- snippet: records-added-field -->
```kotlin
@FlagRecord
data class Mirror(
    val name: String,
    val url: URI,
    val weight: Int = 1, // added in a later build
)
```

A field with no default has nothing honest to fall back to, so a record missing one is
still refused. A field that is *present* but holds the wrong type is refused either way —
filling in an absent field is a migration, overwriting a wrong one would be guessing.

### Everything reverts when a config refresh lands

The remote source is above the local one in the stack. Reverse them:

<!-- snippet: remote-precedence -->
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = listOf(sharedPreferences, remote))
```

Local overrides belong on top, so a value set by hand survives the next refresh.
