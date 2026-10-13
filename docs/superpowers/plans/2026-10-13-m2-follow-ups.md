# M2 follow-ups

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the items the M2 reviews carried into M3, on the M2 branch, before it merges.

**Architecture:** Three small changes to the bridge's client side and provider, each with its own tests, plus one on-device check of an assumption. No new modules or dependencies.

**Tech Stack:** As M2: AGP 9.1.1 with built-in Kotlin, Kotlin 2.2.10, kotlinx-coroutines 1.10.2, Robolectric 4.16, the `Pixel_7` AVD (API 36).

**Spec:** [`docs/superpowers/specs/2026-10-08-android-parity-design.md`](../specs/2026-10-08-android-parity-design.md). **Contract:** [`2026-10-10-m2-overview.md`](2026-10-10-m2-overview.md), whose "Carried into M3" section lists the items. THE USER chose each design below on 2026-10-13.

## Global Constraints

- Everything in the M2 overview's Global Constraints applies: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` for every Gradle command; `explicitApi()`, `allWarningsAsErrors`; detekt with formatting; Android lint clean; Robolectric tests with `@RunWith(AndroidJUnit4::class)`, `@Config(sdk = [36])`, camelCase sentence names and `// Section` comments; packages follow directories; `featureflag-android` keeps exactly one runtime dependency.
- Commits: imperative sentence case, no type prefix, no trailing period, ending with a `Co-Authored-By:` trailer.
- Each task updates the overview's contract text for what it changes, and marks its "Carried into M3" bullet as resolved (keep the bullet, append "Resolved in M2 follow-ups: …").
- Definition of done: the task's tests written first and shown failing, then passing; `./gradlew build detekt` green. F3 also needs `./gradlew :featureflag-bridge:connectedDebugAndroidTest` green on `Pixel_7`.

## Review Focus

1. A caller cancelled while `source()` blocks must not leak the source's thread or observer.
2. A refresh reply that arrives after a newer one (or after a write) must not overwrite the newer cache or failure state.
3. A signal whose client gave a short or already-expired budget must answer promptly, not wait the 4 s default.
4. A deadline from a client is clamped, so a garbage value cannot hold a host binder thread indefinitely.
5. An old client that sends no deadline keeps today's behaviour, measured from when the call arrived.

---

### Task F1: `FlagBridgeClient.source()` becomes `suspend`

**Files:**
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClient.kt`
- Modify: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeSourceTest.kt`, `featureflag-bridge/src/androidTest/kotlin/com/andyyhope/featureflag/bridge/CrossProcessBridgeTest.kt` (call sites)
- Modify: `docs/superpowers/plans/2026-10-10-m2-overview.md`

**Interfaces:**
- Produces: `public suspend fun source(schema: FlagSchema, name: String = "Companion"): FlagBridgeSource`. It builds the source (registration plus one refresh) on `Dispatchers.IO`.

**Behaviour:**
- The blocking construction runs on `Dispatchers.IO` and is not interrupted mid-way. If the caller was cancelled by the time construction finishes, `source()` closes the new source and rethrows the `CancellationException`: no thread or observer outlives a cancelled call. A failure during construction propagates as today (`FlagBridgeException.Unavailable` from registration).
- KDoc: suspends; safe to call from the main thread; the source's writes and `refresh()` still block, so make those off the main thread.

**Tests (write first, show failing):**
- `aSourceIsBuiltOffTheMainThread`: from the Robolectric test (main) thread, `runBlocking { client.source(schema) }` returns a source whose `box` serves the host's override, and the fake provider (which records, per call, whether it ran on the main looper) shows the first `overrides` call did not run on the main thread.
- `aCancelledSourceCreationLeaksNothing`: stall the fake host's first `overrides` (existing `stall` knob), launch `source()` in a coroutine, cancel it, release the stall, then assert the coroutine ended with cancellation and no live `FlagBridgeSource` thread was added (compare `Thread` object sets before/after, polling up to 2 s), and the resolver has no observer left for the overrides URI if Robolectric's `ShadowContentResolver` exposes it.
- Update every existing `client.source(...)` call site (Robolectric and instrumented) to call it from a coroutine.

### Task F2: `FlagBridgeSource.lastFailure` and ordered replies

**Files:**
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeSource.kt`
- Modify: `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeSourceTest.kt`
- Modify: `docs/superpowers/plans/2026-10-10-m2-overview.md`

**Interfaces:**
- Produces: `public val lastFailure: StateFlow<FlagBridgeException?>` on `FlagBridgeSource`.

**Behaviour:**
- `lastFailure` is set when a refresh fails: the first refresh during creation, an observer-triggered refresh, or an explicit `refresh()` (which also still throws). The cache keeps its last good values.
- It is cleared (`null`) by the next successful refresh. A write's success or failure does not change it: a failed `setBoxes` is reported to its caller, and a successful one does not prove the whole cache is current.
- Replies are ordered. Every refresh takes a ticket from a monotonic counter before its IPC. When its reply (success or failure) comes back, it is applied only if no newer ticket has been applied; otherwise it is dropped. A successful `setBoxes` takes a fresh ticket when it updates the cache, so a refresh that started before the write and answers after it is dropped (the host's own change notification triggers a newer refresh anyway). The cache swap, the ticket check and the `lastFailure` update happen under the existing `lock`.
- `changes` emission rules are unchanged.

**Tests (write first, show failing):**
- `aRefreshThatFailsIsReportedAndKeepsTheCache`: host has an override; source created; host switches to `forcedStatus = STATUS_NOT_PUBLISHED`; `refresh()` throws `NotPublished`; `lastFailure.value` is `NotPublished`; `box` still serves the old override.
- `aSuccessfulRefreshClearsTheFailure`: after the above, clear the forced status, `refresh()` succeeds, `lastFailure.value == null`.
- `anObserverRefreshThatFailsIsReported`: host republishes another schema so its overrides no longer decode (or forced `STATUS_REJECTED`), trigger the change notification the way the existing observer test does, wait for `lastFailure.value` to become `Rejected`.
- `aFirstRefreshThatFailsIsReported`: host forced to `STATUS_NOT_PUBLISHED` before `source()`; the source is empty and `lastFailure.value` is `NotPublished`.
- `aWriteDoesNotClearAFailure`: with `lastFailure` set, a successful `setBox` leaves it set.
- `anOlderReplyDoesNotOverwriteANewerOne`: use the fake's `stall` to hold one refresh in flight, complete a second refresh (or a write) with newer state, then release the stalled one carrying older state; the cache keeps the newer state and no `FlagChange.All` reverts it.

### Task F3: Signals get the client's remaining budget

**Files:**
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeContract.kt`, `FlagBridgeClient.kt`
- Modify: `featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt`
- Modify tests: `FlagBridgeContractTest.kt`, `FlagBridgeClientTest.kt` (+ `FakeBridgeProvider.kt` to record extras), `FlagBridgeProviderTest.kt`, `CrossProcessBridgeTest.kt`
- Modify: `docs/superpowers/plans/2026-10-10-m2-overview.md`

**Interfaces:**
- Produces: `FlagBridgeContract.KEY_DEADLINE: String = "deadline"` — a `Long`, the moment the client stops waiting, in `SystemClock.elapsedRealtime()` milliseconds (the clock is system-wide, so both processes read the same one). `PROTOCOL` stays 1: an old host ignores the key, and a new host treats its absence as today.

**Behaviour:**
- `send(signal, timeout)` puts `KEY_DEADLINE = SystemClock.elapsedRealtime() + timeout.inWholeMilliseconds` in the `METHOD_SIGNAL` extras, computed once before the first attempt (a retry after `DeadObjectException` reuses it).
- The provider computes the delivery timeout after its startup barrier:
  - with a deadline: `deadline − SystemClock.elapsedRealtime() − REPLY_MARGIN`, where `REPLY_MARGIN` is 100 ms (the reply's trip back), clamped to `0 .. MAX_DELIVERY` with `MAX_DELIVERY = 30.seconds`;
  - without one (an older client): `FlagSignalChannel.DELIVERY_TIMEOUT` minus the time since `call()` was entered (which includes any barrier wait), clamped at 0.
  - A timeout of zero makes `deliver` answer `false` at once (the handlers do not run).
- `send`'s KDoc: the host is given the caller's remaining budget, so a cold host's startup wait no longer pushes a slow handler past the caller's timeout.

**Tests (write first, show failing):**
- Contract: `KEY_DEADLINE` wire name pinned with the other keys.
- Client: `aSignalCarriesItsDeadline` — the fake records the `signal` extras; the deadline is within `[before + timeout, after + timeout]` of `elapsedRealtime()` sampled around the call; and on a retry after one death both attempts carry the same deadline.
- Provider (Robolectric; drive `call` from a background thread as the barrier tests do, with a handler that blocks): `aSignalWaitsNoLongerThanItsDeadline` (a deadline 300 ms away with a blocked handler answers `handled = false` within ~300 ms, not 4 s); `anExpiredDeadlineAnswersAtOnce`; `aSignalWithoutADeadlineKeepsTheDefault` (no key → waits about `DELIVERY_TIMEOUT` minus elapsed; assert it answers false after > 3 s with a blocked handler only if the suite tolerates it, otherwise inject a shorter default via an `internal` seam like `startupTimeout`); `aFarDeadlineIsClamped` (assert the computed timeout via an `internal` function `deliveryTimeout(extras, enteredAt)` rather than waiting 30 s).
- Device (`CrossProcessBridgeTest`): `aColdHostRunsASlowSignalWithinTheCallersBudget` — kill the host; send a signal whose host handler sleeps 4.5 s (add it to `HostTestApplication`'s observers in the style of the existing ones) with a client timeout of 7 s; expect `true`. Under the old fixed 4 s delivery timeout this answers `false` (the handler outlives 4 s); with the deadline the host may wait about 7 s − 1 s cold start − 0.1 s margin. Show it failing before the provider change.

---

## After the tasks

- F0 (verification, controller): on the `Pixel_7` AVD, two separately built apps show whether `getPermissionInfo` sees another app's signature permission on API 30+ for a correctly configured companion, and what a host without the permission yields at each `connect()` step. Record the result in the overview next to the `connect()` order. If step 3 throws for a correctly configured host, fix `connect()` before F1.
- Final whole-branch review of the follow-up commits; one fix wave if needed.
