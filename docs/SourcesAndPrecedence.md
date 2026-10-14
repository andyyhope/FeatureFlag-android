# Sources and precedence

Stacking the places a value can come from, and finding out which one won.

## Overview

A `FlagPole` holds an ordered list of `FlagValueSource`s. Reading a flag asks each source
in turn and takes the first answer; if none has one, the flag reports its compiled
default.

**The order of the list *is* the precedence.** There is no priority number to set and no
rule to remember beyond the order you wrote.

<!-- snippet: sources-stack -->
```kotlin
val pole = FlagPole(
    AppFlagsSpec,
    sources = listOf(
        SharedPreferencesSource.companion(context), // 1. the companion app
        RemoteOverrideSource(AppFlagsSpec), // 2. your backend
    ),
)
// 3. each flag's default getter
```

That arrangement is the recommended one. A value set by hand stays set until it is
explicitly cleared, so a tester's device does not silently revert the moment a config
refresh lands. It is also what makes the companion app trustworthy: what you set is what
you get.

### The sources that ship

`SharedPreferencesSource` reads and writes a `SharedPreferences` file. `companion(context)`
opens the one a companion app edits through the bridge:

<!-- snippet: sources-shared-preferences -->
```kotlin
val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
val own = SharedPreferencesSource(preferences) // a file of this app's own
val shared = SharedPreferencesSource.companion(context) // the file a companion app edits
```

Values are stored natively where `SharedPreferences` can: a `Boolean` flag is a real
`Boolean` in the file, an `Int` or `Long` flag a `Long`, a `Float` a `Float` and a
`String` a `String`, so a value your app already stores with `putBoolean`, `putLong`,
`putFloat` or `putString` is adopted as it is. One stored with `putInt` is not: an `Int`
flag is read as a `Long`. Every other type, `Double` included, is stored as its JSON
text. A value stored as something other than the
flag's type reads as nothing, so the flag falls through to the next source; there is no
numeric conversion. A write that changes nothing is skipped.

`SnapshotSource` holds values in memory. It is the top of the stack in tests, the
landing place for an imported payload, and the easy way to force a value in a preview:

<!-- snippet: sources-snapshot -->
```kotlin
val local = SnapshotSource(name = "test")
val pole = FlagPole(AppFlagsSpec, sources = listOf(local))

local.setBox(FlagKey("checkout.google-pay"), FlagValueBox.Bool(true))
val offersGooglePay = pole.flags.checkout.googlePay // true
```

`RemoteOverrideSource` holds whatever the last backend payload said. It has its own
article: [Remote overrides](RemoteOverrides.md).

### Layering a config per environment

An app that talks to more than one backend usually wants two config layers for the
environment it is in: one bundled with the build, and one fetched.
`EnvironmentConfiguration` holds both and keeps them in step with the environment you
switch to.

<!-- snippet: sources-environment-configuration -->
```kotlin
val config = EnvironmentConfiguration<Environment>(
    AppFlagsSpec,
    local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // ships with the app
    remote = { environment -> api.fetchConfig(environment) }, // fetched, suspending
)

val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

// when the environment flag changes:
val outcome = config.load(Environment.Staging) // local staging.json, then remote staging.json
```

`bundledConfig` is yours: it reads a file the app ships with, such as
`context.assets.open(name).use { it.readBytes() }`, and returns `null` when there is none.

Precedence within the pair is **remote over local**, and `config.sources` returns them in
that order — so the full stack, highest first, is a by-hand override, the fetched config,
the bundled config, then the compiled defaults.

Loading an environment clears each layer before it loads it, so a fetch that fails or
returns nothing falls back to the layer beneath — the bundled config, then the defaults —
rather than leaving an app labelled *staging* running the previous environment's values.
`load` suspends, and returns a `LoadOutcome` saying what happened to each layer, since
one can succeed while the other does not: `Applied`, `Absent` when the loader returned
`null`, `Failed` with the error, or `Superseded` when a newer load started first. A
superseded load leaves the layer to the newer one, so two quick switches can never apply
in the wrong order.

Drive it from the environment flag itself, so setting the environment to *staging* loads
staging's two layers. Collect from a scope that lives as long as the pole, such as one
your `Application` holds:

<!-- snippet: sources-environment-flag -->
```kotlin
scope.launch {
    pole.flow(AppFlagsSpec.environment).collectLatest { environment -> config.load(environment) }
}
```

A flow emits only when the value changes, so there is nothing to deduplicate, and
`collectLatest` abandons a load the moment the environment changes again.

Give that flag no `remoteKey`. A config could otherwise set the environment, which would
mean the app should have loaded a different config — and loading *that* could set it back.

The library does no networking and reads no files: the two loaders hand it the bytes, it
decodes, validates against the schema, and layers them. Each layer is an ordinary
`RemoteOverrideSource`, so you can audit either with a `FlagMappingAudit` before you
trust it — see [Validating a config](ValidatingAConfig.md).

#### When the fetch depends on a flag the local layer carries

Sometimes the remote config's URL is itself a flag — one the bundled local config sets, so
the app knows where to fetch from only after the local layer is applied. The timing
already works: `load` applies the local layer in full before it runs the remote loader.
What is missing at construction is the pole — it does not exist until you splice in
`config.sources`, so the loader you pass to the constructor cannot read a resolved flag.

Set the loader afterwards with `fetchRemote`, capturing the pole:

<!-- snippet: sources-fetch-remote -->
```kotlin
val config = EnvironmentConfiguration<Environment>(
    AppFlagsSpec,
    local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // carries checkout.remoteUrl
)
val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

config.fetchRemote { environment ->
    val url = pole.flags.checkout.remoteUrl // resolved: by hand, then local, then the default
    api.fetch(url, environment)
}
```

Because the loader resolves the flag through the whole pole, a by-hand override of the URL
wins over the bundled value — so you can point a *staging* build at a different config
server from the companion without touching the build. And because switching environments
clears the remote layer before fetching, the URL the loader reads is never the previous
fetch's own value.

### Setting and clearing overrides

Writes go to the highest-priority source that accepts them — the first
`MutableFlagValueSource` in the stack:

<!-- snippet: sources-overrides -->
```kotlin
pole.setOverride(AppFlagsSpec.newOnboarding, true)
pole.removeOverride(AppFlagsSpec.newOnboarding)
pole.removeAllOverrides()
```

`removeAllOverrides()` only touches flags this pole declares, so unrelated values sharing
the file are left alone, and it skips flags that hold nothing rather than writing
blindly. What is left goes to the source as one batch, which `SharedPreferencesSource`
commits as a single edit, so the file is never half-cleared.

If nothing in the stack is mutable, writing throws `FlagException.NoMutableSource`.

### Why is this flag false?

`resolution` names the source that supplied the current value:

<!-- snippet: sources-resolution -->
```kotlin
val resolution = pole.resolution(AppFlagsSpec.newOnboarding)

val source = resolution.sourceName // "Companion", "Remote", or null for the compiled default
val value = resolution.box // the value in effect
val isDefault = resolution.isDefault // true when nothing overrode it
```

There is a second form taking a `FlagKey` and a `FlagValueType` instead of a typed
handle. The typed form needs one call site per flag, which makes a diagnostics screen —
"where did every value come from?" — impossible to write as a loop:

<!-- snippet: sources-resolution-loop -->
```kotlin
for (entry in pole.schema.flags) {
    val resolution = pole.resolution(entry.key, entry.valueType)
    Log.d("Flags", "${entry.key}: ${resolution.sourceName ?: "default"}")
}
```

### Writing your own source

Three requirements: a name for diagnostics, a way to read a value, and a flow that says
when things changed. Implement `MutableFlagValueSource` as well if it accepts writes.

<!-- snippet: sources-custom -->
```kotlin
class RemoteConfigSource : FlagValueSource {

    override val sourceName: String = "Remote Config"

    @Volatile
    private var values: Map<FlagKey, FlagValueBox> = emptyMap()

    private val emitter = FlagChangeEmitter()

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    fun refresh(values: Map<FlagKey, FlagValueBox>) {
        this.values = values
        emitter.emit(FlagChange.All)
    }
}
```

The `@Volatile` immutable map is not decoration. A pole reads flags from whatever thread
the app happens to be on while your network callback writes from another, and `box` has
to be cheap. Replacing one immutable map, rather than editing a shared one, gives every
read a consistent view with no lock. `SnapshotSource` and `RemoteOverrideSource` are
written this way.

`FlagChangeEmitter` is not decoration either. A source's changes must never be dropped,
or a flow watching a flag stays stale for good, and a `MutableSharedFlow` with a bounded,
dropping buffer drops them exactly when a collector falls behind. The emitter keeps one
merged change per collector instead, so nothing is lost and memory stays bounded.

The `type` argument is the flag's declared type. A source stored in a loosely typed
medium can use it to decode exactly rather than guess — which is how
`SharedPreferencesSource` knows to read a JSON list rather than a string.

Report `FlagChange.Keys(setOf(…))` when you know what changed and `FlagChange.All` when you
do not. `All` is correct but expensive: every collector reads everything again.

### Reading from other threads

`FlagPole` is not tied to the main thread. Reads are lock-free and safe from any thread,
because apps read flags off the main thread constantly. No flow here hops to the main
thread either: values arrive in the context of the coroutine that collects them — see
[Observing changes](ObservingChanges.md).
