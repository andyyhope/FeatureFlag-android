# Observing changes

Reacting when a flag changes, whether the change came from this process or another one.

## Overview

Every flag can be watched as a Kotlin `Flow`. Which way you collect it depends on whether
you are in Compose.

### In Compose

Collect the flag's flow as state. `pole[ref]` reads the current value for the first
frame, so nothing flickers:

<!-- snippet: observing-compose -->
```kotlin
@Composable
fun OnboardingEntry(pole: FlagPole<AppFlags>) {
    val newOnboarding = remember(pole) { pole.flow(AppFlagsSpec.newOnboarding) }
    val showsNewOnboarding by newOnboarding.collectAsStateWithLifecycle(initialValue = pole[AppFlagsSpec.newOnboarding])

    if (showsNewOnboarding) {
        Text("Welcome to the new onboarding")
    } else {
        Text("Welcome")
    }
}
```

`collectAsStateWithLifecycle` is in `androidx.lifecycle:lifecycle-runtime-compose`. The
library's host artifacts carry no Compose dependency, so an app without Compose pays for
none. The flow is remembered so that recomposition does not start a new collection.

The pole itself is not observable state: a screen watches the flags it shows, and only
those flags recompose it.

### Everywhere else

`pole.flow(ref)` emits the flag's current value as soon as it is collected, then again
each time it changes to something different:

<!-- snippet: observing-flow -->
```kotlin
pole.flow(AppFlagsSpec.newOnboarding)
    .onEach { isEnabled -> analytics.setUserProperty("new_onboarding", isEnabled) }
    .launchIn(scope)
```

That first value means a collector never has to read the flag separately to get
started, and because the flow is already distinct there is nothing to deduplicate before
anything that logs or fetches.

Values arrive in the context of the coroutine that collects them, whichever thread made
the change; nothing hops to the main thread for you. Collect from `lifecycleScope` or
`viewModelScope`, which run on the main thread, when a value has to update a view.

A nested flag is reached the way it is read: `AppFlagsSpec.checkout.googlePay`.

### Changes from another process

A companion app does not write your app's preferences file itself. It asks the
`featureflag-bridge` provider in your app's process to, so the edit lands through your
own `SharedPreferencesSource`, whose listener announces it at once. Every flow watching
an affected flag sees the new value within a moment, with nothing to wire up and nothing
to re-read when your app comes back to the foreground.

When your app is not running, the companion's edit starts it, and the change is there
when your code first reads the flag. See [The bridge](TheBridge.md) for how the provider
gets there.

If something writes the file in a way no listener hears, such as a file restored from a
backup or another process, ask the source to announce everything:

<!-- snippet: observing-refresh -->
```kotlin
source.refresh()
```

### Watching one thing rather than everything

`pole.changes` is the coarse stream: every change any source reports, as a `FlagChange`
naming the keys it touched, or `FlagChange.All` when a source cannot say:

<!-- snippet: observing-changes -->
```kotlin
pole.changes
    .filter { change -> change.affects(pole.key(AppFlagsSpec.pageSize)) }
    .onEach { reloadPage() }
    .launchIn(scope)
```

It never drops a change: a collector that falls behind receives the changes it missed,
merged into one. For a screen that reads one flag out of two hundred, `flow(ref)` is the
better fit, since it emits only that flag's values.
