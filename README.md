# FeatureFlag for Android

[![Check](https://github.com/andyyhope/FeatureFlag-android/actions/workflows/check.yml/badge.svg)](https://github.com/andyyhope/FeatureFlag-android/actions/workflows/check.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Declare feature flags as Kotlin properties, read them type-safely, and edit them from a
separate companion app that never links a line of your code.

<!-- snippet: readme-declaration -->
```kotlin
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false

    @Flag(description = "Pricing tier to present")
    val tier: Tier get() = Tier.Free
}

enum class Tier { Free, Pro }
```

<!-- snippet: readme-pole -->
```kotlin
val pole = FlagPole(
    AppFlagsSpec,
    sources = listOf(
        SharedPreferencesSource.companion(context), // the companion app's edits
        RemoteOverrideSource(AppFlagsSpec), // your backend
    ),
)

if (pole.flags.checkout.googlePay) {
    showGooglePayButton()
}
```

This is the Android port of [FeatureFlag](https://github.com/andyyhope/FeatureFlag) for
iOS. Exports, QR codes and schemas written on one platform read on the other.
[Differences from iOS](docs/DifferencesFromIOS.md) lists everything that differs, and why.

## Installation

In your app module's `build.gradle.kts`:

<!-- snippet: install-app -->
```kts
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    ksp(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))

    implementation("com.andyyhope.featureflag:featureflag-android")
    implementation("com.andyyhope.featureflag:featureflag-bridge")
    ksp("com.andyyhope.featureflag:featureflag-ksp")
    testImplementation("com.andyyhope.featureflag:featureflag-test")
}
```

0.1.0 is not on Maven Central yet. Until it is, build it from a clone of this repository
with `./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0`, and add `mavenLocal()` to your
build's repositories.

The BOM keeps every artifact on one version; KSP's configuration does not inherit from
`implementation`, so it is named there too. Pre-1.0, a minor version can break the API,
so name an exact version. On AGP 9, KSP also needs `android.disallowKotlinSourceSets=false`
in `gradle.properties` — see [Getting started](docs/GettingStarted.md#1-add-the-dependencies).

| Artifact | What it is | Add it to |
|---|---|---|
| `featureflag-core` | Containers, the pole, sources, remote overrides, export and import, the audit. Plain JVM. | Anything; `featureflag-android` brings it |
| `featureflag-ksp` | The processor that generates each container's spec | `ksp(…)` in every module that declares flags |
| `featureflag-android` | `SharedPreferencesSource`, `publishSchema(context)`, signals, the bridge client | Your app |
| `featureflag-bridge` | The provider a companion app talks to | Your app, or only its debug builds |
| `featureflag-ui` | The companion app's screens, in Compose | A companion app only — never your shipping app |
| `featureflag-test` | `assertFlagsFullyMapped` | Your tests |
| `featureflag-bom` | One version for all of the above | `platform(…)`, for `implementation` and `ksp` |

### Install from JitPack

Every tag is built by [JitPack](https://jitpack.io). The group is
`com.github.andyyhope.FeatureFlag-android` and the artifact ids are the module names above.
Add the repository in `settings.gradle.kts`, limited to that group:

```kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.andyyhope.FeatureFlag-android") }
        }
    }
}
```

Then use the BOM, so one tag fixes every version:

```kts
dependencies {
    implementation(platform("com.github.andyyhope.FeatureFlag-android:featureflag-bom:0.1.0"))
    ksp(platform("com.github.andyyhope.FeatureFlag-android:featureflag-bom:0.1.0"))

    implementation("com.github.andyyhope.FeatureFlag-android:featureflag-android")
    implementation("com.github.andyyhope.FeatureFlag-android:featureflag-bridge")
    ksp("com.github.andyyhope.FeatureFlag-android:featureflag-ksp")
    testImplementation("com.github.andyyhope.FeatureFlag-android:featureflag-test")
}
```

The `mavenLocal` route above publishes the group `com.andyyhope.featureflag` instead.

## Requirements

- Android, minimum SDK 26.
- Kotlin 2.2 or later in the app that consumes it, with a KSP matching that Kotlin. The
  processor is built against KSP 2.2.10-2.0.2, and tested with Kotlin 2.2.10.
- Java 17 bytecode, so a JDK 17 or later to build with.
- Built and tested with AGP 9.1 and compileSdk 36. The companion app also needs Compose,
  with the Compose compiler plugin.

## What you get

- **Nested flags.** `@FlagGroup` namespaces keys — `checkout.express.one-tap`. Nesting is
  namespacing only: a child's value never depends on its parent, so there is no
  evaluation order to reason about.
- **The types you would reach for,** plus lists, string-keyed maps and any enum, which
  need no implementation at all.
- **Records.** `@FlagRecord` gives a flag a list of fixed-shape values — a name, a URL and
  a switch, repeated — with a field-by-field editor in the companion app.
- **A companion app** that edits your app's flags through a bridge only your signing key
  opens, updating your app while it runs, and starting it when it is not.
- **Remote overrides** from JSON, mapped from whatever shape your backend sends.
- **Environment layering.** `EnvironmentConfiguration` keeps a bundled local config and a
  fetched remote one in step with the environment you switch to — remote over local over
  the compiled defaults.
- **Export and import** as JSON or a QR code.
- **A mapping audit** that checks, without running the app, that a config file wires up
  every flag — in both directions, so a path typo cannot hide.
- **Kotlin flows** per flag, ready for `collectAsStateWithLifecycle`.

## The idea that makes it work

The processor generates `AppFlagsSpec` — a description of the flag tree available
**without an instance and without reflection**.

Everything else falls out of that. Your app publishes that description as a schema
document; the companion reads it through the bridge and renders an editor for an app
whose types it has never seen. One companion build works for any app that publishes a
schema. Remote key mapping, strict payload validation and editor selection all read the
same description.

## Documentation

| | |
|---|---|
| [Getting started](docs/GettingStarted.md) | Empty project to a flag you can toggle from a second app |
| [Declaring flags](docs/DeclaringFlags.md) | Containers, nesting, keys, and flags with no pole behind them |
| [Flag values](docs/FlagValues.md) | The types a flag can hold, and enums |
| [Records](docs/RecordFlags.md) | Lists of fixed-shape values, and how they stay editable |
| [Sources and precedence](docs/SourcesAndPrecedence.md) | Stacking sources, and finding out which one won |
| [Observing changes](docs/ObservingChanges.md) | Flows, Compose, and changes from another app |
| [Remote overrides](docs/RemoteOverrides.md) | Dot paths, custom mappers, and why a bad field rejects everything |
| [Validating a remote config](docs/ValidatingAConfig.md) | Checking a config file wires up every flag |
| [Sharing flags with a companion app](docs/SharingWithACompanionApp.md) | What the schema carries, and what it costs |
| [The bridge](docs/TheBridge.md) | The provider, the permission, and who may connect |
| [Exporting and importing](docs/ExportingAndImporting.md) | JSON documents and QR codes |
| [Sending signals to the host app](docs/SendingSignals.md) | One-way instructions from the companion to your app |
| [Building a companion app](docs/BuildingACompanionApp.md) | An editor for another app's flags, in one composable |
| [Troubleshooting](docs/Troubleshooting.md) | The failures worth recognising on sight |
| [Differences from iOS](docs/DifferencesFromIOS.md) | What this port does differently, and why |

The tests in [`docs-samples`](docs-samples) hold the samples in those articles and in this
README to the code:
- every Kotlin sample is compiled there, inside a test that uses it — statements run in
  the test, and declarations are used by it;
- every JSON block and every report or message shown as output is the string the code
  produces, or the payload a test applies;
- the Gradle and manifest snippets are files there that the tests read but do not build:
  they check the coordinates, versions and permission names, and compare the bridge's
  manifest with the library's own. Two in The bridge are quoted instead from the
  [sample apps](samples/README.md), which the build compiles;
- copied by hand and not checked: the processor's error messages in Records and
  Troubleshooting, and the one `gradle.properties` line in Getting started and
  Troubleshooting.

A test fails if a checked sample differs from its source by a single character.
Documentation that has drifted from the API is worse than none, and nothing else would
notice.

The API reference is generated with Dokka: `./gradlew :dokkaGeneratePublicationHtml`.

## Precedence

Sources are asked in order, so the order of the stack *is* the precedence:

```
1. local overrides    (the companion app, an imported document or QR code)
2. remote payload     (your backend)
3. compiled default   (the property's getter)
```

A value you set by hand stays set until you clear it — which is what makes the companion
trustworthy for QA. `resolution` names the source that won:

<!-- snippet: readme-resolution -->
```kotlin
val sourceName = pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion", or null for the default
```

That is the answer to "why is this flag false?".

## Remote overrides

The library decodes; it never fetches. Your app gets configuration however it likes and
hands over the bytes, so no networking, auth, retry or cache invalidation lives in a flag
library:

<!-- snippet: remote-overview -->
```kotlin
val remote = RemoteOverrideSource(AppFlagsSpec)
val pole = FlagPole(AppFlagsSpec, sources = listOf(local, remote))

val bytes = httpClient.get(configUrl)
remote.apply(bytes)
```

Flags say where they live in the payload with `remoteKey`, read as a dot path
(`experiments.0.enabled` indexes into arrays). For a shape no path can address — a list
of experiments keyed by a name field, say — implement `RemoteOverrideMapper`.

Applying is strict and all-or-nothing: one bad field rejects the payload and reports every
problem at once, rather than leaving your app running on half a configuration.

One deliberate allowance: JSON has a single number type, so a whole number satisfies a
`Double` or `Float` flag, and a decimal number narrows to a `Float` flag. That is numeric
conversion only, not coercion between kinds — `"true"` is still not a `Boolean`.

## The companion app

Your app publishes its schema on launch:

<!-- snippet: sharing-setup -->
```kotlin
class ExampleApplication : Application() {
    lateinit var pole: FlagPole<AppFlags>
        private set

    override fun onCreate() {
        super.onCreate()
        pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.companion(this)))
        pole.publishSchema(this)
    }
}
```

The companion renders it, linking none of your code:

<!-- snippet: companion-whole-app -->
```kotlin
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlagCompanion(hostPackage = "com.example.app")
            }
        }
    }
}
```

That is the whole app, not a sketch of one. It connects through the bridge, says plainly
what is wrong when it cannot, and gives you Overrides and Flags as tabs. The two apps
trust each other because they are signed with the same key; see
[The bridge](docs/TheBridge.md).

Tabs are a list you compose, so a companion takes only what it needs:

<!-- snippet: companion-tabs -->
```kotlin
FlagCompanion(
    hostPackage = "com.example.app",
    tabs = listOf(
        FlagCompanionTab.Overrides,
        FlagCompanionTab.Signals(
            FlagSignalGroup("Configuration", ConfigSignal.entries),
            FlagSignalGroup("Caches", CacheSignal.entries),
        ),
        FlagCompanionTab.Flags,
        FlagCompanionTab.Detail(key = "environment", title = "Environment"),
    ),
)
```

`Signals` is opt-in — it needs your signal enums, so an app without them simply leaves it
out. `Detail` promotes a single flag to its own screen, for the one whose consequence
earns it. `Custom` puts your own screens in the same list, handed the same store.

Edits reach your running app the moment they are made, because the bridge writes them
into your app's own preferences file. A signal or an edit sent while your app is not
running starts it.

## Reading from other threads

`FlagPole` is not tied to the main thread. The pole adds no locking, and reads are safe from any thread,
because apps read flags off the main thread constantly. No flow hops to the main thread
for you; collect where you need the values.

## Things worth knowing

**Export spans the whole stack.** `overrides`, and everything built on it, carries every
value a source supplies — including one a remote payload supplied, even when it equals
the default.
Exporting from a device with remote config and importing elsewhere turns those into local
overrides on the receiving device. Usually what you want from "reproduce this device's
state", but worth knowing before you share a code.

**If your own module declares a type called `Flag`,** import the annotation under another
name, such as `import com.andyyhope.featureflag.Flag as FeatureFlag`.

**A flag can hold a list of records** when configuration is not one value but several
small structured ones:

<!-- snippet: records-declaration -->
```kotlin
enum class PaymentKind(override val flagRawValue: String) : FlagStringEnum {
    Card("card"),
    Wallet("wallet"),
}

@FlagRecord
data class PaymentMethod(
    val name: String,
    val kind: PaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
interface AppFlags {
    @Flag(description = "Payment methods offered at checkout")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0),
                PaymentMethod("Google Pay", PaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
            ),
        )
}
```

Stored as JSON text beside a published shape, so the QR encoder and every store already
handle it, and import and remote validation check it field by field. Adding a field with a default keeps lists already stored readable; adding one without
a default makes the app fall back to its default rather than read a half-built value. See
[Records](docs/RecordFlags.md).

→ [Troubleshooting](docs/Troubleshooting.md) has the rest.

## Samples

[`samples/`](samples/README.md) contains two apps — a host that declares flags and a
companion that edits them — and the small module of signals they share. Install both
debug builds and open the companion; the samples README walks through what to try.

## Credit where it is due

FeatureFlag is inspired by [**Vexil**](https://github.com/unsignedapps/Vexil) by
[Unsigned Apps](https://github.com/unsignedapps), the best-established Swift feature flag
library. Several ideas here are Vexil's, by way of the iOS library: the `FlagPole` as the
thing you read flags through, containers that namespace by nesting, and an ordered stack
of sources where position decides precedence.

No code was copied — this is an independent implementation, and both are MIT licensed.

## Status and contributing

Early. The API may still move before 1.0. Issues and pull requests are welcome — if you
are reporting a bug, a failing test says more than a description.

## License

MIT. See [LICENSE](LICENSE).
