# Declaring flags

Containers, nesting, the keys values are stored under, and reading a flag with no pole
behind it.

## Overview

Everything starts with an interface annotated with `@FlagContainer`. The `featureflag-ksp`
processor generates two things beside it: `AppFlagsRefs`, a typed handle to every flag
beneath it, and `AppFlagsSpec`, an object that extends those handles and also describes
the container's shape and builds instances of it. A pole is built from the spec.

That description is why nothing here needs runtime reflection, and why a companion app
can render your flags without linking your code.

Code samples in these docs leave out their imports. Every Kotlin sample is compiled, with
its imports, in [`docs-samples`](../docs-samples), inside a test that runs it or uses it.

### Declaring a flag

<!-- snippet: declaring-a-flag -->
```kotlin
@FlagContainer
interface OnboardingFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false
}
```

Two rules you will be held to:

- **The default getter is required.** `get() = false` is the flag's compiled default: the
  value it falls back to when no source supplies one. A flag without a getter has nothing
  to fall back to, and the processor says so.
- **`description` is required.** It is the label in the companion app's list. A flag
  nobody can identify is a flag nobody dares change.

The getter runs once, against the compiled defaults, and these all report that one value:
`pole.flags`, `pole[ref]`, `pole.flow`, `pole.resolution`, the schema and
`ref.defaultValue`. A getter that reads another flag sees that flag's compiled default,
never an override, and one that calls `Instant.now()` keeps the moment it first ran. The
exception is `flagDefaults` (see below), which runs the getter again on each read.

**A flag's type cannot be nullable.** `val endpoint: String?` is rejected, because a flag
always has a value — the getter is what it falls back to when no source supplies one,
and `null` already means "no source supplied one" everywhere beneath. Use a sentinel the
type already has (`""`), or an enum with an entry for unset, which also earns you a picker
in the companion instead of a free-text field.

`remoteKey` is optional, and says where this flag lives in a backend payload. A flag
without one is not remotely overridable by the default mapping — see
[Remote overrides](RemoteOverrides.md).

<!-- snippet: declaring-remote-key -->
```kotlin
@Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
val newOnboarding: Boolean get() = false
```

### Nesting

`@FlagGroup` nests one container inside another:

<!-- snippet: declaring-nesting -->
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

    @FlagGroup(description = "Express")
    val express: ExpressFlags
}

@FlagContainer
interface ExpressFlags {
    @Flag(description = "One-tap purchase")
    val oneTap: Boolean get() = false
}
```

A group has no getter: the generated code provides it. Reading follows the shape you
declared, and so does the key:

<!-- snippet: declaring-nested-read -->
```kotlin
val oneTap = pole.flags.checkout.express.oneTap // stored under "checkout.express.one-tap"
```

**Nesting is namespacing and nothing else.** A group has no value of its own and never
gates the flags beneath it. `checkout.express.oneTap` is true or false entirely on its
own account, whatever `checkout` contains. There is no evaluation order to hold in your
head, and no way to accidentally disable a subtree.

### Keys

Property names become keys through a `KeyEncoding`. The default is `Kebabcase`, applied
to each path component separately so nesting always survives intact:

| Encoding | `checkout.express.oneTap` becomes |
|---|---|
| `KeyEncoding.Kebabcase` (default) | `checkout.express.one-tap` |
| `KeyEncoding.Snakecase` | `checkout.express.one_tap` |
| `KeyEncoding.Verbatim` | `checkout.express.oneTap` |

<!-- snippet: declaring-key-encoding -->
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = listOf(source), keyEncoding = KeyEncoding.Snakecase)
```

Word splitting handles acronyms and digits the way you would write them by hand:
`useHTTPSOnly` becomes `use-https-only`, and `checkoutV2` becomes `checkout-v2`.

Write your own if you have an existing key convention to match:

<!-- snippet: declaring-custom-encoding -->
```kotlin
val screaming = KeyEncoding(separator = "__") { it.uppercase() }
```

**An encoding has to keep every property distinct.** The one above does not: `oneTap` and
`onetap` both become `ONETAP`. Two flags landing on one key is a declaration mistake with
no runtime remedy — both want the same storage, and letting one quietly win would mean the
other never takes effect — so building the pole fails immediately, naming both
properties. It happens with the default encoding too: `useHTTPSOnly` and `useHttpsOnly`
are both `use-https-only`.

<!-- output: declaring-duplicate-keys -->
```text
Flags must resolve to unique keys, but 'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly. Rename one of the properties, or use a KeyEncoding that tells them apart.
```

A unit test that builds the schema finds out before anyone launches the app, because
building it throws:

<!-- snippet: declaring-unique-keys-test -->
```kotlin
@Test
fun everyFlagHasItsOwnKey() {
    FlagSchema.of(AppFlagsSpec, keyEncoding = KeyEncoding.Kebabcase)
}
```

Choose an encoding once, at the start. Changing it later renames every key, which means
every stored override — on every tester's device — stops being found.

### Reading metadata off a flag

Every flag has a typed handle on the generated spec, a `FlagRef`. It carries everything
the flag knows about itself:

<!-- snippet: declaring-metadata -->
```kotlin
val flag = AppFlagsSpec.newOnboarding

val key = pole.key(flag) // FlagKey("new-onboarding")
val description = flag.description // "Show the redesigned onboarding"
val defaultValue = flag.defaultValue // false
val remoteKey = flag.remoteKey // "featureToggles.onboarding.v2"
val current = pole[flag] // what it resolves to right now
val changes = pole.flow(flag) // a Flow<Boolean>
```

The handle follows the same path as the flag: `AppFlagsSpec.checkout.express.oneTap`.
The key is asked of the pole, because the pole's encoding decides it.

### Flags without a pole

Every spec has `flagDefaults`, an instance whose flags all report their compiled default
(each read runs the getter again).
Nothing has to be wired up, which is what makes previews and quick tests work with no
setup at all:

<!-- snippet: declaring-without-a-pole -->
```kotlin
@FlagContainer
interface OnboardingPreviewFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = true
}
```

<!-- snippet: declaring-defaults-read -->
```kotlin
val showsNewOnboarding = OnboardingPreviewFlagsSpec.flagDefaults.newOnboarding // true, with no pole anywhere
```

When a test needs a specific value rather than the default, put a `SnapshotSource` on top
of a pole instead — see Sources and precedence.

### Public containers

The generated spec and refs have the visibility of the interface they describe. A
container in a library module built in explicit API mode is `public`, and so is
everything generated with it:

<!-- snippet: declaring-public-container -->
```kotlin
@FlagContainer
public interface StoreFlags {
    @Flag(description = "Show the redesigned onboarding")
    public val newOnboarding: Boolean get() = false
}
```

An `internal` container gets `internal` generated code. A `private` one is an error,
because the generated spec has to implement it.

### What the processor checks

The processor reports a declaration it cannot generate code for, at the declaration, so
the error points at your code rather than at generated code. A container must be an
interface with no type parameters and no supertypes. Each flag must be a `val` with a
getter, and each group a `val` without one. A flag cannot be called `flagDefaults`,
`flagDescriptors`, `flagContainerDescription`, `createFlags`, `flagLookup` or `flagKeys`,
because the generated code already uses those names. The exact messages are in
Troubleshooting.

A group declared in another module is found by name: its `CheckoutFlagsSpec` has to
exist, so apply the processor to that module too.

### If your module already has a type called `Flag`

If your package has a type of its own called `Flag`, the two names collide, and
`@Flag` can end up resolving to your type, which the compiler rejects as an illegal
annotation class. An explicit `import com.andyyhope.featureflag.Flag` wins instead, which
hides your own type in that file. Import the annotation under another
name, so both stay usable:

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

The processor recognises the annotation by its full name, so an alias works, and so does
writing it out in full as `@com.andyyhope.featureflag.Flag(…)`.

### Saying what a set of flags is for

<!-- snippet: declaring-container-description -->
```kotlin
@FlagContainer(description = "Everything the checkout team can turn on")
interface CheckoutTeamFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
```

The companion shows it above the flags. The application name answers "whose flags are
these"; this answers "what are they", which is the question someone handed an unfamiliar
debug build actually has. It is optional, and a container that says nothing looks exactly
as it did before.
