# The bridge

How a companion app reaches your app's flags: one exported provider, guarded by a
permission only your own signing key can hold.

## Overview

Two Android apps share nothing by default. iOS solves this with an App Group both apps
join; Android has no equivalent, so `featureflag-bridge` adds one small, deliberate
door to your app instead: a `ContentProvider`, `FlagBridgeProvider`, that a companion
calls to read your schema, read and write the companion overrides, and send signals.

The provider runs in your app's process and writes through its own
`SharedPreferencesSource.companion(context)`, over the same file your pole's source reads,
so an edit is a change to your own file, seen by your own listener, the moment it is made.

Nothing else crosses: the companion never links your code, and can reach only the schema
you publish, the overrides file the bridge serves and the signals your app observes.

### Adding it

`featureflag-bridge` is a separate artifact because an exported provider should be
something an app opts into. Add it to the host, beside `featureflag-android`, with its
version from the BOM as Getting started shows:

<!-- snippet: bridge-install -->
```kts
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-bridge")
}
```

That merges this into your app's manifest, for an app whose application id is
`com.example.app`:

<!-- snippet: bridge-merged-manifest -->
```xml
<permission
    android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE"
    android:protectionLevel="signature" />

<application>
    <provider
        android:name="com.andyyhope.featureflag.bridge.FlagBridgeProvider"
        android:authorities="com.example.app.featureflag"
        android:exported="true"
        android:permission="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
</application>
```

The permission's `signature` protection level is the whole security model: Android grants
it only to an app signed with the same key as yours, so a companion you did not sign
cannot even ask.

### Only in debug builds

If your release builds should carry no exported provider at all, make the bridge a debug
dependency. Everything your code calls — `SharedPreferencesSource`, `publishSchema` — is
in `featureflag-android`, so release builds still compile; they simply have no door:

<!-- snippet: bridge-debug-only -->
```kts
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-android")
    debugImplementation("com.andyyhope.featureflag:featureflag-bridge")
}
```

A companion pointed at a release build then cannot reach it: on Android 11 and later an
app without the bridge is hidden from the companion, which reports `HostNotFound`, and
below that it reports `BridgeNotInstalled`.

### The companion's side

A companion needs two things. It declares the host's permission in its manifest:

<!-- snippet: bridge-companion-permission -->
```xml
<uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
```

And it is signed with the host's key — for debug builds, usually the same debug
keystore. Android 11 and later also hides an app from another unless the other declares
an interest in it; the permission declaration is that interest, so no `<queries>` element
is needed.

The authority and the permission are both derived from the host's application id. A
host built with an `applicationIdSuffix`, such as `.debug`, has a different id, so a
companion has to name that id, both in its manifest and in `FlagCompanion(hostPackage = …)`.

### Trusting a companion signed with another key

On Android 12 (API 31) and later, Android lets an app grant a signature permission to apps
signed with other certificates it names: the `knownSigner` protection flag and the
`knownCerts` attribute, described in Android's
[Defining permissions](https://developer.android.com/guide/topics/permissions/defining).
This is platform behaviour, and this library's tests do not exercise it, so check it on a
device before relying on it. Applied to the bridge, the host redeclares the permission in
its own manifest, whose root declares
`xmlns:tools="http://schemas.android.com/tools"`:

<!-- snippet: bridge-known-signer -->
```xml
<permission
    android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE"
    android:knownCerts="@array/featureflag_companion_certificates"
    android:protectionLevel="signature|knownSigner"
    tools:replace="android:protectionLevel" />
```

and lists the digest of each companion's signing certificate in a resource, in the form
Android's documentation for `knownCerts` gives:

<!-- snippet: bridge-known-certs -->
```xml
<string-array name="featureflag_companion_certificates">
    <item>3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7</item>
</string-array>
```

Below API 31, `knownSigner` is ignored and only the same key works.

### In the samples

The sample host's id, `com.andyyhope.featureflag.sample.host`, is written once, as the
`featureflag.sample.hostId` property in the root `gradle.properties`. It becomes the host's
`applicationId`, the companion's `BuildConfig.HOST_PACKAGE`, and the `${hostId}`
placeholder in the permission the companion declares, so the three cannot disagree:

<!-- snippet: sample-companion-uses-permission -->
```xml
<uses-permission android:name="${hostId}.permission.FEATURE_FLAG_BRIDGE" />
```

Both sample apps' debug builds are signed with one committed, debug-only key. The
companion also has an `untrusted` build type, signed with another key and installed beside
the trusted one with `./gradlew :samples:companion:installUntrusted`, so you can watch the
bridge refuse it with `PermissionDenied`:

<!-- snippet: sample-companion-signing -->
```kts
signingConfigs {
    // The key the host is signed with too, so Android grants the host's signature permission.
    getByName("debug") {
        storeFile = file("../keys/shared-debug.keystore")
        storePassword = "android"
        keyAlias = "sample"
        keyPassword = "android"
    }
    // Any other key: the host must refuse this build with PermissionDenied.
    create("untrusted") {
        storeFile = file("../keys/untrusted.keystore")
        storePassword = "android"
        keyAlias = "untrusted"
        keyPassword = "android"
    }
}
buildTypes {
    create("untrusted") {
        initWith(getByName("debug"))
        signingConfig = signingConfigs.getByName("untrusted")
        // Installs beside the trusted companion instead of replacing it.
        applicationIdSuffix = ".untrusted"
        // The library modules have only debug and release.
        matchingFallbacks.add("debug")
    }
}
```

Those keys are committed for the samples' sake and must never sign anything real — see
[the samples](../samples/README.md).

### Connecting

`FlagCompanion` connects for you and shows what it finds. A companion of your own uses
`FlagBridgeClient` directly:

<!-- snippet: bridge-connect -->
```kotlin
val client = FlagBridgeClient(context, hostPackage = "com.example.app")

when (val connection = client.connect()) {
    is FlagBridgeConnection.Connected -> show(connection.schema)
    else -> showProblem(connection.message.orEmpty())
}
```

`connect()` checks everything a companion needs, in the order it is worth fixing, and
returns the first problem. It never throws:

| Result | Means |
|---|---|
| `Connected` | It works. `schema` is the host's published schema. |
| `UsesPermissionMissing` | The companion's manifest does not declare the host's permission. |
| `HostNotFound` | No app with that id is installed, or it does not include the bridge, which hides it all the same. |
| `BridgeNotInstalled` | The host is installed and visible, but without the bridge. |
| `PermissionDenied` | The two apps are signed with different keys. It carries both certificate digests. |
| `NotPublished` | The host has never published its schema. |
| `Unresponsive` | The host did not answer within the client's timeout. |
| `Incompatible` | The host speaks another bridge protocol, or published a schema this build cannot read. |

Each problem's `message` says what to do about it, which is what `FlagCompanion` shows.

`client.source(schema)` then gives a `FlagBridgeSource`, a `MutableFlagValueSource` over
the host's overrides, which is what a `FlagEditingStore` edits. `source()` suspends while
it registers its observer. The source it returns writes and refreshes with blocking calls
into the host, so make those off the main thread (`FlagEditingStore` does this for you);
read `lastFailure` for the latest refresh failure, and `close()` it when done.
`client.send(signal)` sends a signal — see
Sending signals to the host app.

### A host that is not running

Android starts an app to answer a call to its provider, so a companion never needs the
host to be open. The provider waits for your `Application.onCreate` to return before it
answers, so the pole is built and the schema published by the time it does — the reason
both belong in `onCreate`.

A cold start takes time. The client's timeout, five seconds unless you pass another,
covers starting the host as well as answering, so give it more if your app starts slowly.

If the host dies in the middle of a call, the client retries once, and the companion
survives either way.

### What it refuses

- **Itself.** A companion is a separate app. The same app hosting the bridge and running
  `FlagCompanion` against itself is not supported.
- **Too much at once.** A request or a reply over 512 KiB is refused with
  `FlagBridgeException.TooLarge` rather than risking a binder failure. Real flag sets are
  far below that.
- **A value the schema does not accept.** A write is validated against your schema
  before it is stored, exactly as an import is.

### What it exposes

The provider serves your published schema, the overrides in the companion's file, and
the signals your app observes. It does not expose any other preferences, files or
databases, and an app that cannot hold the signature permission cannot call it at all.
That is also why a release build should either keep the bridge or drop it deliberately:
with the default `implementation` dependency, a release build signed with your release
key accepts a companion signed with that same key.
