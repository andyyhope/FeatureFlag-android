# Building a companion app

An editor for another app's flags, rendered entirely from the schema that app publishes.

## Overview

A companion app is an ordinary app that happens to edit somebody else's flags. It links
`featureflag-ui`, connects to the host through the bridge, and renders whatever schema
the host published.

It does **not** link the host app's code, import its modules, or know its types. Change
a flag in the host, rebuild the host, and the companion picks it up — there is nothing to
keep in step. One companion build can edit any app that publishes a schema, and your
shipping app carries no editor UI at all.

### Adding the dependencies

The companion depends on `featureflag-ui`, and on Compose itself to draw it:

<!-- snippet: companion-install -->
```kts
dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    implementation("com.andyyhope.featureflag:featureflag-ui")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
}
```

`featureflag-ui` keeps Compose to itself, so an app that wants `MaterialTheme` and
`setContent` names them, and applies the Compose compiler plugin as any Compose app does.

### The whole app

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

That is not an abbreviation of the real thing — it *is* the app. `FlagCompanion` connects
to the host, reports anything in the way with a message someone can act on, and gives you
Overrides and Flags as tabs. It reconnects whenever the companion comes back to the
foreground, so a reinstalled host with new flags is picked up. None of it is specific to
any host, so none of it is worth writing twice. It draws with your `MaterialTheme`.

Two things make the connection possible, and both are described in
[The bridge](TheBridge.md). The companion declares the host's permission:

<!-- snippet: bridge-companion-permission -->
```xml
<uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
```

and it is signed with the host's key. `hostPackage` is the host's application id, so a
host built with an `applicationIdSuffix`, such as `.debug`, is a different host: both the
permission and `hostPackage` have to name the suffixed id.

The companion has to be its own app. A host cannot run `FlagCompanion` against itself:
the bridge refuses calls from its own process, and the host already has its pole.

`featureflag-ui` merges a little into the companion's manifest: `POST_NOTIFICATIONS` and
`POST_PROMOTED_NOTIFICATIONS`, for the countdown of a delayed signal, and a private
`FileProvider` for sharing a QR code's image. Nothing needs adding by hand.

### Choosing the tabs

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

They appear in that order, and the first starts selected. Each tab has an id; two with
the same one throw an `IllegalArgumentException`, because one of them could never be
reached.

`Signals` is opt-in — it needs your signal enums, so an app without them simply leaves it
out. See [Sending signals to the host app](SendingSignals.md).

### Promoting one flag to its own screen

Some flags earn a tab. Not because they are a special kind — the companion reads them
from the schema like any other — but because of their consequence: a flag that decides
which backend the app talks to moves half the values on the Flags tab with it, and
hunting for it in a list beside forty others undersells that.

`FlagCompanionTab.Detail(key = "environment", title = "Environment")` above does that.
The screen lays an enum's entries out rather than tucking them into a menu, and shows
what the schema knows about the flag — its key, its default, and whether a remote
payload can change it. Any flag works; enums are simply the ones this suits best.

Which key deserves it is knowledge about a particular app, which is why it is a
parameter rather than something the library guesses.

### Filing signals into groups

An app with four signals wants a list; an app with forty wants them filed. Each group is
its own enum, so the host keeps handling one at a time. A group lays out beside the
delay picker while it has six signals or fewer, and gets a screen of its own beyond
that. Say so yourself when that is the wrong call:

<!-- snippet: companion-nested-group -->
```kotlin
val caches = FlagSignalGroup("Caches", CacheSignal.entries, display = FlagSignalGroupDisplay.Nested)
```

`FlagSignalGroupDisplay.Flat` keeps a group inline however long it grows. The threshold
of six is `FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD`, and it is fixed.

### Adding your own tab

Some tabs only your app can supply — a screen built around one area of your flags, say.
A custom tab is handed the same store the built-in tabs use, and the standard row for any
flag, so it needs no editors of its own:

<!-- snippet: companion-custom-tab -->
```kotlin
val checkoutTab = FlagCompanionTab.Custom(id = "checkout", title = "Checkout", icon = Icons.Filled.ShoppingCart) {
    Column {
        for (entry in store.schema.flags.filter { it.key.rawValue.startsWith("checkout.") }) {
            FlagRow(entry)
        }
    }
}
```

Put it in the `tabs` list wherever it belongs.

### Following a delayed signal from the host app

A delay exists so you can switch to the host before the signal fires, which takes the
countdown out of sight. The companion keeps it in sight as a notification: on Android 16
(API 36) and later, a progress notification that asks to be promoted to a Live Update
(which Android honours from API 36.1), and a countdown chronometer below API 36.

The first time someone picks a delay, the companion asks for the notification permission
on Android 13 and later. Refusing it costs only the notification: the signal is still
sent. The host holds the delay, so the signal fires on time even when Android freezes
the companion in the background — but a frozen companion cannot update its notification,
which then times out once the delay and the send's timeout have passed since the tap.

### The two built-in screens

**Overrides** answers *what have I changed?* — the question you actually have before
filing a bug or handing a device to someone else. It lists only what has been changed,
with a per-row reset, the exported JSON inline and copyable, and the actions that move
overrides off the device: a QR code, which can be copied as an image, shared, or copied as
text; sharing the JSON; importing; and resetting everything, which asks first.

**Flags** answers *what can I change?* — the whole tree. Groups are rows you push into,
each showing how many flags it holds and how many are overridden; searching flattens the
whole tree, because someone hunting for a key by name should not have to guess which
group it was filed under.

Everything else — which control a flag gets, how an enum becomes a picker, how a
`ByteArray` flag is edited, what a reset does — comes from the schema and needs no work
from you. A text field writes when it loses focus or you press Done, and only if what it
holds has changed; text that is not a valid value for the flag is refused, with a
warning, rather than written. A write the host refuses shows why, and the row goes back
to the value the host holds.

A list of scalars gets a row per element, with the control its element type deserves.
Add and delete come with it, and each row's "Actions" menu moves it up or down. Lists of
lists and maps stay as JSON text, because no row-per-element layout reads well for
either.

A flag holding a list of records gets a row per record showing its key, or its first
field, and the verbs a list needs: add, and duplicate, move or delete from each row's
"Actions" menu. Each record opens onto a screen of its own, a row per field with the
control that field's type calls for, including a picker for an enum field.

None of this needs the host's Kotlin types. A record arrives as a map of boxes and a
published shape, which is enough to lay it out and enough to write it back the way the
host will read it — new records start every enum field on a real entry, so the companion
cannot write a list the app would reject. When the stored text is not a list of records
at all, the screen says so rather than showing an empty list, because an empty list would
invite you to add one and wonder why nothing changed.

### Platforms

Android only, minimum SDK 26, drawn with Material 3. The core library is plain Kotlin,
but a companion is an Android app by definition: it needs the bridge to reach the host.

## Going further

### Editing something other than a host

`FlagEditingStore` is the companion's model of one host: plain Kotlin over a schema and
any `MutableFlagValueSource`. The same model can edit a schema loaded from a file, a
fixture in a test, or an in-memory store in a preview:

<!-- snippet: companion-store -->
```kotlin
val schema = FlagSchema.read(exportedDirectory)
val store = FlagEditingStore(schema, SnapshotSource(name = "Preview"))
```

Over the bridge, the source is the one `FlagBridgeClient.source(schema)` returns.

### Building your own screens

The store exposes everything the built-in screens use, so a custom editor is not a fork:

<!-- snippet: companion-store-api -->
```kotlin
val tree = store.tree // the flag tree, rebuilt from the flat schema
val sections = store.sections(query = "pay") // a flat, searchable grouping
val overridden = store.overriddenKeys // what has been changed
val badge = store.overriddenCount(tree) // for a badge on a group row

val value = store.value(entry) // the value in effect
val isOverridden = store.isOverridden(entry)
store.setValue(entry, FlagValueBox.Bool(true)) // suspends: writes go to the source off the main thread
store.reset(entry)
store.resetAll()
```

Reads are immediate. Writes suspend, because a write over the bridge is a call into the
host, and throw whatever the source throws. `store.revision` is a `StateFlow` that moves
after every write, failed or not, which is what a screen collects to know when to read
again. Call `store.observeIn(scope)` once and it moves on the source's own changes too.

### Moving overrides on and off the device

The same transport the core library has, driven from the store:

<!-- snippet: companion-store-transport -->
```kotlin
val json = store.exportJson()
val code = store.qrCode()

store.import(code) // a document or a scanned code, told apart by the FFQR1: prefix
```

Import is strict and all-or-nothing, as everywhere else, and lands as one write: one
unknown key or wrong type rejects the whole document rather than leaving a device on
half a configuration.

### Choosing a control

`FlagSchema.Entry.editorKind` says which control the built-in screens use for a flag, as a
`FlagEditorKind`: a `Picker` for anything with entries, `Records` for a record list, then
by type — `Toggle`, `Integer`, `Decimal`, `Text`, `Date`, `Url`, `Data`, a `List` for an
array of scalars, and `Json` for maps and lists of collections. A screen of your own can
switch on it to pick the same controls.

### What the store will not show you

An override the host app would ignore is not displayed as an override. The host's file
can hold a stale or hand-edited value of the wrong type, or an enum entry this build no
longer has; the host skips those and falls back to its default, so the editor has to as
well.

Showing it would be worse than useless — it would claim a value the app is quietly
ignoring, and bind a control to something it cannot render.
