# Getting started

Go from an empty project to a flag you can toggle from a second app.

## Overview

This walks the whole path in five steps. Each one works on its own — you can stop after
step three and still have type-safe flags with defaults, and come back for the companion
app when you need someone else to change a value on a device you are not holding.

### 1. Add the dependencies

In your app module's `build.gradle.kts`:

<!-- snippet: install-app -->
```kts
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.1"))
    ksp(platform("com.andyyhope.featureflag:featureflag-bom:0.1.1"))

    implementation("com.andyyhope.featureflag:featureflag-android")
    implementation("com.andyyhope.featureflag:featureflag-bridge")
    ksp("com.andyyhope.featureflag:featureflag-ksp")
    testImplementation("com.andyyhope.featureflag:featureflag-test")
}
```

0.1.1 is not on Maven Central yet. Until it is, build it from a clone of this repository
with `./gradlew publishToMavenLocal -PVERSION_NAME=0.1.1`, and add `mavenLocal()` to your
build's repositories. Building from source needs an Android SDK: put `sdk.dir=<path>` in
`local.properties`, or set `ANDROID_HOME`.

The BOM keeps every artifact on one version. KSP's configuration does not inherit from
`implementation`, so the BOM is named for it separately. KSP itself has to match your
Kotlin version; the processor is built against KSP 2.2.10-2.0.2 and tested with Kotlin
2.2.10.

On AGP 9, add this to `gradle.properties` too, because this KSP still adds its generated
sources through the `kotlin.sourceSets` API that AGP 9's built-in Kotlin forbids:

```
android.disallowKotlinSourceSets=false
```

Gradle prints a one-line warning that the option is experimental, which is expected.

`featureflag-bridge` is what a companion app talks to. Leave it out until step four if
you only want flags for now, and see [The bridge](TheBridge.md) for keeping it out of
release builds. Add `featureflag-ui` **only** to a companion app — your shipping app
never links it.

Pre-1.0, a minor version can break the API. Name an exact version, as above, rather than
a range.

### 2. Declare your flags

A container is an interface annotated with `@FlagContainer`. Each flag is a property with
a getter, which is its compiled default:

<!-- snippet: getting-started-declare -->
```kotlin
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
```

The processor generates `AppFlagsSpec` beside it, which is what a pole is built from.

`description` is not a comment. It is what a companion app puts next to the control, so
write it for whoever is looking at the list wondering which switch to flip.

### 3. Build a pole and read flags

A `FlagPole` binds a container to an ordered list of sources.

<!-- snippet: getting-started-pole -->
```kotlin
val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
val pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource(preferences)))

if (pole.flags.newOnboarding) {
    showNewOnboarding()
}

if (pole.flags.checkout.googlePay) {
    addGooglePayButton()
}
```

Make it one instance your app shares. The pole adds no locking, and reads are safe from any thread, so it
does not need to live on the main thread — see [Observing changes](ObservingChanges.md).

With no override stored anywhere, every flag reports its compiled default. That is
already useful: you have one place that lists what your app can turn on, with
descriptions, and nothing to configure.

### 4. Share the flags with a companion app

A companion app reaches your flags through the bridge, so the host needs three things:
`featureflag-bridge` in its dependencies, the companion's source at the top of the stack,
and its schema published on launch. All of it belongs in `Application.onCreate`:

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

`SharedPreferencesSource.companion(context)` opens the file the bridge serves to a
companion. `publishSchema(context)` writes the schema the bridge serves with it. The
companion has no other way to learn what flags exist, what they are called, or what types
they hold — it never links your code.

Publish on every launch, in `onCreate`. The bridge waits for `onCreate` to finish before
it answers a companion, for up to ten seconds, so a schema published there is in place —
even when the companion's request is what started your app. See
[Sharing flags with a companion app](SharingWithACompanionApp.md).

### 5. Render it in the companion

The companion is a second app, with its own module. It is an ordinary Compose app, so it
applies the Compose compiler plugin (at your Kotlin version), turns Compose on, and needs
a minimum SDK of 26 or higher:

<!-- snippet: companion-compose-setup -->
```kts
plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    defaultConfig {
        minSdk = 26
    }
    buildFeatures {
        compose = true
    }
}
```

Then its dependencies:

<!-- snippet: companion-install -->
```kts
dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.1"))
    implementation("com.andyyhope.featureflag:featureflag-ui")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
}
```

It declares your app's bridge permission, named from your application id:

<!-- snippet: bridge-companion-permission -->
```xml
<uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
```

and it is signed with the same key as your app — for debug builds, the same debug
keystore. That is the whole of the trust between them.

The app itself is one composable:

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

Edits reach your running app the moment they are made: the bridge writes them into your
app's own preferences file, and the source on top of your stack hears it. If your app is
not running, the companion's first request starts it.

`samples/` in the repository is exactly this: two Android apps, one declaring flags and
one editing them. See [the samples](../samples/README.md).

## Where to go next

- [Declaring flags](DeclaringFlags.md) — nesting, keys, remote paths, and flags with no
  pole behind them.
- [Flag values](FlagValues.md) — the types a flag can hold, including your own enums.
- [Sources and precedence](SourcesAndPrecedence.md) — stacking sources, and finding out
  which one won.
- [Building a companion app](BuildingACompanionApp.md) — tabs, signals, and your own
  screens.
