# Sharing flags with a companion app

Publishing a schema so a second app can edit your flags without linking your code.

## Overview

A companion app is a separate APK. Through the bridge it can read and write your app's
overrides, but it has no idea what your flag tree *is* — what exists, what it is called,
what type it holds, or which values an enum allows.

So the host publishes a `FlagSchema`: a JSON description of the tree, complete enough for
an editor to be rendered from it alone. That document is what decouples the UI. One
companion build can edit any app that publishes one and trusts it.

### Setting it up

Add `featureflag-bridge` to the host, put the companion's source at the top of the
pole's stack, and publish the schema on launch, in `Application.onCreate`:

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

Name the class in the manifest's `<application android:name=".ExampleApplication">`.

Publish every launch, not once. The schema describes the build that is running, and a
build with a new flag needs to say so. `publishSchema(context)` writes
`flag-schema.json` under the app's `noBackupFilesDir`, so a backup restored onto another
build never brings back a stale one, and it writes atomically, so the bridge never
serves half a file.

Do it in `onCreate`, before anything slow. The bridge's provider waits for
`Application.onCreate` to return before it answers a companion, for up to ten seconds, so
a schema published there is in place by the time a companion asks for it — even when the
companion's request is what started your app.

There is also a directory form, which is what tests and JVM tools use:

<!-- snippet: sharing-publish-directory -->
```kotlin
val file = pole.publishSchema(directory)
```

The two apps also have to trust each other: the companion declares the bridge's
permission and is signed with your app's key. [The bridge](TheBridge.md) covers that.

### What the schema carries

Per flag: the storage key, the property path, the description, the value type, the
compiled default, the enum's entries if it has any, the remote key, and a record's
shape. Per group: its property path and description, so the editor can label its
sections and nest them.

<!-- output: sharing-schema -->
```json
{
  "applicationName" : "Demo",
  "flags" : [
    {
      "defaultValue" : false,
      "description" : "Offer Google Pay",
      "key" : "checkout.google-pay",
      "propertyPath" : [
        "checkout",
        "googlePay"
      ],
      "valueType" : "bool"
    }
  ],
  "formatVersion" : 1,
  "generatedAt" : "2026-01-01T00:00:00Z",
  "groups" : [
    {
      "description" : "Checkout",
      "propertyPath" : [
        "checkout"
      ]
    }
  ]
}
```

`applicationName` is left out unless you give one, so a companion that can see more than
one app has something readable to head the list with only when you say so:

<!-- snippet: sharing-application-name -->
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = sources, applicationName = "Demo (Staging)")
```

### Reading it back

A companion built with `featureflag-ui` gets the schema from the bridge when it connects,
and never reads the file. A test or a tool can read a published one directly:

<!-- snippet: sharing-read-schema -->
```kotlin
val schema = FlagSchema.read(directory)

val count = schema.flags.size
val types = schema.valueTypes // Map<FlagKey, FlagValueType>, for validating a payload
```

This is what the `featureflag-ui` module builds its editor from. If you are writing the
companion, start at [Building a companion app](BuildingACompanionApp.md) instead — it has
the screens already.

### What it costs

Everything the editor shows has to survive a trip through JSON. A flag's Kotlin type is
gone by the time the companion sees it; what remains is a `FlagValueType`. A flag you
read through a `Percentage` wrapper is stored as a `Double`, so it is a number field in
the companion, not a percentage field.

That is the trade for a companion app you build once, and it is deliberate.

### Getting changes back into the running app

The companion's edits go through the bridge's provider, which runs in your app's process
and writes your app's own preferences file. The `SharedPreferencesSource` at the top of
your stack hears every write as it happens, so an edit appears in a running app within a
moment, and every flow watching that flag sees it.

When your app is not running, the companion's first request starts it. Its
`Application.onCreate` runs, the provider answers, and the change is in the file the
next time your code reads the flag — which is exactly what you want from a stored
override.
