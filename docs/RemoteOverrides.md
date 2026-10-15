# Remote overrides

Driving flags from a backend payload whose shape you do not control.

## Overview

`RemoteOverrideSource` decodes; it never fetches. Your app gets configuration however it
likes — OkHttp, Ktor, a CDN, Firebase Remote Config, a file in its assets — and hands
over the bytes:

<!-- snippet: remote-overview -->
```kotlin
val remote = RemoteOverrideSource(AppFlagsSpec)
val pole = FlagPole(AppFlagsSpec, sources = listOf(local, remote))

val bytes = httpClient.get(configUrl)
remote.apply(bytes)
```

No networking, auth, retry or cache invalidation lives in a flag library. Those belong to
your app, where the timeouts and the token refresh already are.

### Saying where a flag lives in the payload

A backend's JSON rarely has the shape of your flag tree, so each flag says where to find
itself with `remoteKey`, read as a dot path:

<!-- snippet: remote-flag -->
```kotlin
@Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
val newOnboarding: Boolean get() = false
```

<!-- output: remote-onboarding-payload -->
```json
{
  "featureToggles": {
    "onboarding": { "v2": true }
  }
}
```

Paths index into arrays with a number, so `experiments.0.enabled` works:

<!-- output: remote-array-payload -->
```json
{ "experiments": [ { "enabled": true } ] }
```

**A flag with no `remoteKey` is not remotely overridable by the default mapping.** That is the safety
property: a flag only moves remotely if you said it could, so nothing your backend sends
can reach a flag you did not opt in. The default `DotPathMapper` enforces it. A custom
mapper can return any declared key, so keep the rule in your mapper, as
`ExperimentListMapper` below does by matching on `remoteKey`.

### Checking a config before you trust it

A large payload is worth auditing rather than eyeballing: `absentKeys` names a flag
whose path matched nothing, but not a value in the file that no flag reads. See
[Validating a config](ValidatingAConfig.md) for both directions at once, from a test or a
debug build.

### What a successful apply tells you

<!-- snippet: remote-apply-result -->
```kotlin
val result = remote.apply(bytes)

val applied = result.appliedKeys // flags this payload set
val absent = result.absentKeys // flags with a remoteKey the payload did not mention
```

`absentKeys` is the useful one when a rollout does not appear to be working. A flag in
that list fell through to whatever sits below the remote source, which usually means the
backend key changed or the path has a typo.

Applying replaces everything the source holds — it is not a merge. A flag the new payload
omits stops being remotely overridden. `remote.clear()` drops the lot.

### Strictness

Applying is all-or-nothing. Every value is checked against its flag's declared type, and
against the enum's entries where there are any, *before* anything is stored:

<!-- snippet: remote-strict -->
```kotlin
try {
    remote.apply(bytes)
} catch (rejected: RemoteOverrideException.Rejected) {
    for (problem in rejected.problems) {
        Log.w("Flags", "${problem.remoteKey}: ${problem.kind}") // TypeMismatch, UnknownCase or UnknownKey
    }
}
```

One bad field rejects the payload and reports *every* problem at once. The alternative —
applying what parsed and skipping the rest — leaves an app running on half a
configuration, which is the hardest kind of bug to reproduce.

Enum entries are checked because a backend sending `"platinum"` for a three-entry enum
would otherwise fail silently at every read, looking exactly like a flag that does not
work. Bytes that are not JSON at all are a `RemoteOverrideException.Malformed`.

One deliberate allowance: JSON has a single number type, so a whole number satisfies a
`Double` or `Float` flag, and a decimal number narrows to a `Float` flag. That is numeric
conversion only, not coercion between kinds — `"true"` is still not a `Boolean`.

### What a rejection tells you

The exception's message is a report, so whatever your logging prints, it is readable:

<!-- output: remote-rejection-report -->
```text
Nothing was applied. 3 problems:
  • 'config.tier' → checkout-tier: "gold" is not one of free, pro
  • 'featureToggles.onboarding.v2' → new-onboarding: expected bool, got "yes"
  • 'config.pageSize' → page-size: expected int, got an array of 3
A remote payload is all or nothing, so one bad value leaves every other flag reading whatever sits below this source.
```

Each line names the path in the payload, the flag it maps to, what was expected and
what arrived. Strings keep their quotes, because `"true"` and `true` is the mistake a
backend makes most often and the two are indistinguishable otherwise.

Problems are sorted by flag key, so the same payload reports the same way every time.

### JSON only

There is no property-list form. A configuration file shipped in your assets is JSON,
read into bytes and applied the same way.

### Lists of records

A flag holding a `FlagRecords` list takes the shape a backend would write anyway:

<!-- snippet: records-remote-flag -->
```kotlin
@Flag(description = "Endpoints", remoteKey = "config.endpoints")
val endpoints: FlagRecords<Endpoint> get() = FlagRecords()
```

<!-- output: records-endpoints-payload -->
```json
{ "config": { "endpoints": [
    { "name": "staging", "url": "https://staging.example", "weight": 7 }
] } }
```

Records are stored as text, so what decides here is not the flag's type but the shape
published beside it. Every field must be present (unless it is nullable or has a
default) and hold its declared type, and an enum field must name an entry this build
has — otherwise the whole payload is rejected, as with any other mismatch.

Send the list as a JSON array. A list sent already serialised as a string is checked only
as stored text: fields are read by property name, enum entries and nested lists are not
checked, and the text is stored as sent. That is a known quirk of FeatureFlag 0.12.0 for
iOS, mirrored here so the remote test fixtures both platforms share stay identical.

A bad field inside a record is reported as a `TypeMismatch` on the flag, because a
`RemoteOverrideProblem` names a flag rather than a field. See [Records](RecordFlags.md).

### When a path cannot express it

Some payloads are not addressable by path — a list of records where the flag name is a
field rather than a key:

<!-- output: remote-experiments-payload -->
```json
{
  "experiments": [
    { "name": "onboarding-v2", "state": "on" },
    { "name": "google-pay", "state": "off" }
  ]
}
```

Implement `RemoteOverrideMapper`. It receives the decoded `RemoteValue` tree and the
`FlagSchema`, and returns raw values per flag key; validating them is still the source's
job, so a mapper never has to think about types or boxing:

<!-- snippet: remote-mapper -->
```kotlin
object ExperimentListMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val experiments = payload.valueAt("experiments") as? RemoteValue.Array
            ?: throw RemoteOverrideException.Malformed("expected an experiments array")

        val states = mutableMapOf<String, Boolean>()
        for (experiment in experiments.value) {
            val name = experiment.valueAt("name") as? RemoteValue.String
            val state = experiment.valueAt("state") as? RemoteValue.String
            if (name != null && state != null) states[name.value] = state.value == "on"
        }

        return buildMap {
            for (entry in schema.flags) {
                val state = entry.remoteKey?.let(states::get) ?: continue
                put(entry.key, RemoteValue.Bool(state))
            }
        }
    }
}
```

<!-- snippet: remote-mapper-source -->
```kotlin
val remote = RemoteOverrideSource(ExperimentFlagsSpec, mapper = ExperimentListMapper)
```

A mapper that returns a key no flag has is reported as `RemoteOverrideProblem.Kind.UnknownKey`
rather than ignored. `DotPathMapper` cannot cause that — it only ever emits keys it read
from the schema — but a custom mapper with a typo can, and silently doing nothing would
look exactly like a backend that sent no overrides at all.

Worth knowing when you see it: this is the one problem kind that says something about
*your* code rather than the payload. Everything else in the list means the backend sent
something this build cannot use; `UnknownKey` here means the mapper asked for a flag that
does not exist. The identically named `FlagImportProblem.Kind.UnknownKey` is the ordinary
data version of the same idea — a document naming a flag this app does not have.

### Precedence

Put the remote source *below* local overrides:

<!-- snippet: remote-precedence -->
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = listOf(sharedPreferences, remote))
```

A value someone set by hand then survives the next config refresh, which is what makes a
device usable for testing. Reverse the two and every refresh silently undoes the tester's
work.

### Key encoding has to agree

`RemoteOverrideSource` builds its own `FlagSchema`, so if the pole uses a non-default
encoding the source needs the same one — otherwise it validates against keys the pole
never looks up:

<!-- snippet: remote-key-encoding -->
```kotlin
val remote = RemoteOverrideSource(AppFlagsSpec, keyEncoding = KeyEncoding.Snakecase)
val pole = FlagPole(AppFlagsSpec, sources = listOf(remote), keyEncoding = KeyEncoding.Snakecase)
```
