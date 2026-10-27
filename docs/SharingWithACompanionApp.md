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

### The values your app actually reads

A flag's compiled default is often not what your app reads. A bundled or fetched config
beneath the companion's overrides can change it, and a companion that showed only the
default would show the wrong value for every flag a config sets.

So `publishSchema` also writes what the rest of your stack says about each flag: the value
it reads while it has no override, as `effectiveValue`, and the name of the source that
supplied it, as `effectiveSource`. It leaves out the pole's top mutable source, where
overrides are written and the companion's edits land: the companion reads that itself, and
a value copied from it would outlive its reset. A flag that only its default supplies gets
neither field, and neither does a value the pole would not read, such as an enum entry the
flag does not have. Here a config named "Staging config" turns Google Pay on:

<!-- output: sharing-schema-effective -->
```json
{
  "applicationName" : "Demo",
  "flags" : [
    {
      "defaultValue" : false,
      "description" : "Offer Google Pay",
      "effectiveSource" : "Staging config",
      "effectiveValue" : true,
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

The companion shows that value until you override the flag, with "From Staging config"
under it, and resetting the override goes back to it rather than to the default. What is
published is `pole.schemaWithEffectiveValues()`; `pole.schema` and `FlagSchema.of`, which
imports and remote payloads are checked against, never carry the two fields.

The values are read when you publish. When a source beneath the overrides changes later —
a remote config arrives, or `EnvironmentConfiguration` loads another environment — publish
again:

<!-- snippet: sharing-republish -->
```kotlin
config.apply(payload) // a source beneath the overrides changed,
pole.publishSchema(context) // so publish again: an open companion reads it at once
```

`publishSchema(context)` announces every publish on the bridge, so a companion that is open
reconnects and shows the new schema straight away, its `applicationName` included. A
companion also reconnects when it comes back to the foreground, and a second after it sends
a signal, which covers a host that restarts itself in answer to one, such as an environment
switch.

Both fields are optional, and the `formatVersion` is still 1. A companion from before them
ignores them and shows the defaults, as it always did; a companion reading a schema from an
older host finds neither and does the same.

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
