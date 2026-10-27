# Sending signals to the host app

Telling the host app to *do* something — re-fetch its configuration, purge a cache —
from the companion.

## Overview

Flags are state. Some things are not: "re-fetch the remote config now" is a verb, and
there is no value to store for it.

Signals carry those one way, from a companion app to its host. They travel over the same
bridge as everything else: the companion calls the host's provider with the signal's
name, the provider hands it to `FlagSignalChannel.host` in your app's process, your
handler runs, and the companion hears back whether anything handled it.

### Declaring the signals

An enum, shared between both apps — a small module both depend on, so the host and the
companion agree on every name:

<!-- snippet: signals-enum -->
```kotlin
enum class AppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    SignOut,
}
```

Declaring it as an enum is what gives the host an exhaustive `when`: add an entry, and
the compiler shows you every place that has to handle it. The entry's `name` is the
signal's name on the wire, so `FlagSignal` asks nothing more of an enum.

### Grouping them

One enum stops scaling the moment an app has more than a handful. File them by type:
each group is its own enum, so the host handles one at a time and adding an entry tells
you exactly where to handle it.

<!-- snippet: signals-groups -->
```kotlin
enum class ConfigSignal : FlagSignal {
    RefetchRemoteConfiguration,
    ClearRemoteConfiguration,
    ;

    override val signalDescription: String
        get() = when (this) {
            RefetchRemoteConfiguration -> "Re-fetch remote configuration"
            ClearRemoteConfiguration -> "Clear remote configuration"
        }
}

enum class CacheSignal : FlagSignal {
    PurgeImageCache,
    PurgeEverything,
    ;

    override val requiresRestart: Boolean get() = this == PurgeEverything
}
```

`signalDescription` is what a companion puts on the button. It defaults to the name.

The host observes each type it cares about, and each observer sees the signals in its
own list:

<!-- snippet: signals-observe-groups -->
```kotlin
val config = FlagSignalChannel.host.observe(ConfigSignal.entries) { signal -> handleConfig(signal) }
val caches = FlagSignalChannel.host.observe(CacheSignal.entries) { signal -> handleCache(signal) }
```

**Names have to be unique across every group.** A signal travels as its name and nothing
else, so two enums both declaring `Purge` would fire both observers for one press. The
companion refuses a set of groups that collide rather than picking one.

### Saying a signal needs a relaunch

A handled signal usually means something visibly changed. Some do not — purging a cache
the app read into memory at launch, or swapping a dependency built during start-up, is
done the moment the handler runs and invisible until the next launch. That is what
`requiresRestart` above says.

It changes nothing about delivery. It is there so the companion can report "handled —
relaunch to see it" rather than "handled", which is the difference between *nothing
happened* and *nothing happened yet*.

### Receiving, in the host

Register in `Application.onCreate`, so the observers are in place before anything can
send:

<!-- snippet: signals-host -->
```kotlin
private val subscriptions = mutableListOf<AutoCloseable>()

override fun onCreate() {
    super.onCreate()
    subscriptions += FlagSignalChannel.host.observe(AppSignal.entries) { signal ->
        when (signal) {
            AppSignal.RefetchRemoteConfiguration -> refetchConfiguration()
            AppSignal.PurgeImageCache -> imageCache.clear()
            AppSignal.SignOut -> session.signOut()
        }
    }
}
```

`observe` returns an `AutoCloseable`; `close()` stops delivery. Handlers run on the main
thread, and the companion waits for them, so start anything slow — a fetch — in a
coroutine rather than doing it in the handler.

### Sending, from the companion

<!-- snippet: signals-send -->
```kotlin
val client = FlagBridgeClient(context, hostPackage = "com.example.app")

val handled = client.send(AppSignal.RefetchRemoteConfiguration)
```

`send` suspends until the host answers, and returns `true` when a handler ran to the end.
It returns `false` when no observer in the host lists that signal, when every handler for
it threw (if several observers list it, one that ran to the end is enough for `true`),
when the host could not be reached, and when it did not answer within the timeout — five
seconds unless you pass another. It never throws, except to cancel. `connect()` says why
the host could not be reached, when that is the reason; a host that answered but did not
handle the signal is not something the companion can tell apart.

### Signals start a host that is not running

Android starts an app to answer its provider, so a signal sent to a host that is not
running — even one force-stopped — starts it. Its `Application.onCreate` registers the
observers, the provider waits for that to return, and the signal is handled. Nothing has
to be in the foreground.

That is the opposite of iOS, where a signal to a suspended app is lost. It is not
queuing, though: a signal is delivered to the observers registered when it arrives, and
an observer registered later never sees it.

### Delays

A delay exists so you can switch to the host before the signal fires, and watch it
happen:

<!-- snippet: signals-send-delayed -->
```kotlin
val handledLater = client.send(CacheSignal.PurgeImageCache, delay = 3.seconds)
```

The host holds the delay, not the companion, so a companion that Android freezes in the
background cannot stall it. The longest delay is a minute. Cancelling the coroutine that
called `send` while it waits asks the host to withdraw the signal, so leaving the
companion's Signals screen cancels what it had pending. The companion shows the
countdown as a notification — see [Building a companion app](BuildingACompanionApp.md).

### Signals carry no payload

That is a limit on purpose rather than a feature not yet built. State belongs in flags,
which the companion can already edit. "Re-fetch for staging" is the `environment` flag
plus a bare `RefetchRemoteConfiguration` — set the flag, then send the signal — not a
signal with an argument and its own parallel way of describing values.

### Delivery details

Each send is one call to the host's provider, answered once, so a signal is delivered
once per press: there are no sequence numbers to keep and nothing to deduplicate. The
one exception is a host that dies after running the handler but before answering: the
client retries once, and the handler may run again. A send that timed out may still be
delivered later, so a handler should be safe to run twice. A signal this build cannot
represent — a newer companion sending an entry that does not
exist here — matches no observer, and the companion is told it was not handled.
