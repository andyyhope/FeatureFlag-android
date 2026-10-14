# Records

A flag that holds a list of fixed-shape values, each field typed and each editable.

## Overview

Some configuration is not one value but a list of small structured ones: the payment
methods a checkout offers, the endpoints a build can talk to, the tiers a paywall
shows. A `Map<String, String>` loses the types and a JSON string loses everything, so
FeatureFlag has a shape for it.

<!-- snippet: records-declaration -->
```kotlin
enum class PaymentKind(override val flagRawValue: String) : FlagStringEnum {
    Card("card"),
    Wallet("wallet"),
}

@FlagRecord
data class PaymentMethod(
    val name: String,
    val kind: PaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
interface AppFlags {
    @Flag(description = "Payment methods offered at checkout")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0),
                PaymentMethod("Google Pay", PaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
            ),
        )
}
```

Reading gives you your own type back. `FlagRecords` is a `List`:

<!-- snippet: records-reading -->
```kotlin
for (method in pole.flags.paymentMethods.filter { it.enabled }) {
    show(method.name)
}
```

### What `@FlagRecord` writes

Every property of the primary constructor becomes a field, in declaration order. A
property declared in the class body is not a field. The processor generates
`PaymentMethodFlagRecord`, which knows the shape, boxes each field, and builds the record
back from boxes.

Each field must be a type a flag could hold — a field of some other type gets an error
where you wrote it. A field may be nullable (`val minimumSpend: Double?`): a payload that
omits it, or sends it as `null`, reads it as `null` rather than failing the record, and a
`null` value is left out of the stored form rather than written as anything. The
record's key cannot be nullable — it is what tells one record from another, so every
record has to carry it.

Fields are boxed one at a time rather than run through a serialisation library. That
matters more than it sounds: going through the same boxing as everything else means a
date inside a record is stored exactly as a date in the flag beside it, with one wire
format and one set of round-trip tests covering it.

### Telling one record from another

Mark the field that identifies a record and it becomes the record's key:

<!-- snippet: records-key -->
```kotlin
@FlagRecord
data class Endpoint(
    @FlagRecordKey val name: String,
    val url: URI,
)
```

<!-- snippet: records-find-by-key -->
```kotlin
val staging = pole.flags.endpoints.firstOrNull { it.name == "staging" }?.url
```

A key is what makes one record a *different* record rather than an edited one. Two
sharing it has no correct behaviour — picking one would leave the app running on a value
nobody chose — so a list containing a duplicate is unreadable and falls back to the
flag's default, exactly as any other malformed value does. A payload or a document
carrying one is refused outright, naming the key:

<!-- output: records-duplicate-key -->
```text
'config.endpoints' → endpoints: expected every record to have its own name, got two with "staging"
```

The companion enforces the same rule rather than writing something the app will not
read: an edit that would collide is refused the way an unparseable one is, and "Add"
gives a new record a distinct key instead of a second empty one. The key also titles
each row, which is otherwise the first declared field and therefore a guess.

### A record is not a flag's type on its own

`FlagRecords<Endpoint>` is how a record reaches a flag. Neither `List<Endpoint>` nor a
bare `Endpoint` works, and the processor says so:

```text
a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' or 'List<Endpoint>'
```

That is a refusal rather than a missing feature. A record boxed on its own would be a
map of mixed field types, which is the one shape a `FlagValueType` cannot describe — and
the reason a record list is stored as text at all.

### How it is stored

A list of records is stored as JSON text:

<!-- output: records-stored-text -->
```json
[{"enabled":true,"kind":"card","minimumSpend":0,"name":"Visa"}]
```

To `SharedPreferences`, to a QR code, to import and export, and to remote validation, the
flag is a `String`. Nothing had to learn what a record is, and nothing can drift.

What makes it more than a string is the **shape**, published beside it in the schema.
This is the whole schema a host with only `AppFlags` publishes:

<!-- output: records-schema -->
```json
{
  "flags" : [
    {
      "defaultValue" : "[{\"enabled\":true,\"kind\":\"card\",\"minimumSpend\":0,\"name\":\"Visa\"},{\"enabled\":true,\"kind\":\"wallet\",\"minimumSpend\":5,\"name\":\"Google Pay\"}]",
      "description" : "Payment methods offered at checkout",
      "key" : "payment-methods",
      "propertyPath" : [
        "paymentMethods"
      ],
      "recordShape" : [
        {
          "name" : "name",
          "type" : "string"
        },
        {
          "cases" : [
            "card",
            "wallet"
          ],
          "name" : "kind",
          "type" : "string"
        },
        {
          "name" : "enabled",
          "type" : "bool"
        },
        {
          "name" : "minimumSpend",
          "type" : "double"
        }
      ],
      "valueType" : "string"
    }
  ],
  "formatVersion" : 1,
  "generatedAt" : "2026-01-01T00:00:00Z",
  "groups" : [

  ]
}
```

`valueType` stays `"string"` on purpose. A companion built before records existed reads
a type name it already understands and shows the JSON in a text field; a value type it
had never heard of would have made it reject the whole document and show nothing at all.

### In the companion app

A record flag opens onto a list. Each row has an "Actions" menu to duplicate, move up,
move down or delete it, and "Add" appends a new one. Each record opens onto a screen of
its own, a row per field with the control its type calls for — a switch for a `Boolean`,
a date and time picker for an `Instant`, a picker for an enum field, because the shape
carries its entries, another list for a nested list, and a text field for the rest.

The companion builds records from the shape, so it cannot write one the app will not
read — a new record starts every enum field on a real entry rather than an empty string.
Building a companion app covers the screens.

### From a backend

Give the flag a `remoteKey` and a payload can carry the shape a backend would write
anyway:

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

A field the record does not declare, like `weight` here, is ignored. Validation is
otherwise as strict as everywhere else: a missing field (unless it is nullable or has a
default), a field of the wrong type, or an enum entry this build has never heard of
rejects the **whole payload** rather than being found later as a flag that quietly
stopped taking effect.

Send the list as a JSON array. A backend that sends it already serialised as a string is
understood too, but checked only as stored text: fields are read by property name, enum
entries and nested lists are not checked, and the text is stored as sent. That is a known
quirk of FeatureFlag 0.12.0 for iOS, mirrored here so the remote test fixtures both
platforms share stay identical.

### Matching a backend that is not shaped like your Kotlin

Two things a real payload often needs. A field the backend omits or sends as `null`:
declare it nullable, and it reads as `null` instead of failing the record. And a field
whose JSON key is not the property name: `@FlagRecordProperty(key = …)` gives it the key
to read from.

<!-- snippet: records-renamed-field -->
```kotlin
@FlagRecord
data class PaymentOption(
    @FlagRecordKey val name: String,
    @FlagRecordProperty(key = "google_pay")
    val googlePay: Boolean,
    val minimumSpend: Double?, // absent or null reads as null
)
```

<!-- output: records-renamed-payload -->
```json
{ "config": { "paymentOptions": [
    { "name": "card", "google_pay": false },
    { "name": "wallet", "google_pay": true, "minimumSpend": 10 }
] } }
```

The custom key is **decode-only**: it applies when reading a payload, not to the record's
own stored form, which stays keyed by the property name. Once decoded a record is
canonical — the backend's key never appears in what is stored, exported, or shown in the
companion, and re-reading uses the property name.

### A field that is itself a list

A record's field can be another list of records:

<!-- snippet: records-nested -->
```kotlin
@FlagRecord
data class SpendLimit(
    val currency: String,
    val maximum: Double,
)

@FlagRecord
data class PaymentProvider(
    val name: String,
    val limits: FlagRecords<SpendLimit> = FlagRecords(),
)
```

The nested list is stored as a string inside its parent's JSON, so this always
round-trips. What the shape adds is that it *describes* the nested fields, so the
companion opens another list rather than showing a block of escaped JSON, and a backend
can send the shape it would write anyway:

<!-- output: records-nested-record -->
```json
{ "name": "Visa", "limits": [ { "currency": "AUD", "maximum": 500 } ] }
```

A nested list already serialised as a string is still understood, with the same quirk as
above. In the companion, each level of nesting opens another list.

### Adding a field later

Give the new field a default in the constructor and lists already stored keep working:

<!-- snippet: records-added-field -->
```kotlin
@FlagRecord
data class Mirror(
    val name: String,
    val url: URI,
    val weight: Int = 1, // added in a later build
)
```

The default goes into the shape, so a record written before the field existed — in the
store, in a backend's payload, or in an exported document — is filled from it rather than
refused. It is the same syntax and the same meaning as a default anywhere else in Kotlin.
A default is read from a record built with every other field at a placeholder, so it
must not depend on another constructor parameter.

A field with no default has nothing to fall back to, so a record missing one is still
refused. And a field that is present but holds the wrong type is refused whether it has a
default or not: filling in an absent field is a migration, while overwriting a wrong one
would be guessing, and would hide a stored value that genuinely disagrees with this
build.

### When a stored list cannot be read

A list is all of its records or none of them. If one record is missing a field that has
no default, or holds the wrong type in one — a hand-edited store, say — the flag falls
back to its default, exactly as any other type mismatch does.

That fallback is silent in the app. Ask the pole what is actually stored:

<!-- snippet: records-resolution -->
```kotlin
val stored = pole.resolution(AppFlagsSpec.paymentMethods)
```

`stored.box` is the text the source holds, and `stored.sourceName` the source holding
it. The companion says so plainly rather than showing an empty list, since an empty list
would invite you to add a record and wonder why nothing changed. See
Troubleshooting.
