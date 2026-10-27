# Flag values

The types a flag can hold, and what to do with one it cannot.

## Overview

A flag can hold any of nine Kotlin types, lists and string-keyed maps of them, any enum,
and a list of records. The processor reads the type off the property and picks the codec
for it, so there is nothing to implement.

Everything is stored through one canonical representation, `FlagValueBox`. That is what
lets a single value travel through `SharedPreferences`, JSON, a QR code and the bridge to
a companion app without four separate encodings to keep in agreement.

### The built-in types

<!-- snippet: values-built-in -->
```kotlin
@FlagContainer
interface AppFlags {
    @Flag(description = "A switch")
    val enabled: Boolean get() = false

    @Flag(description = "A count")
    val pageSize: Int get() = 20

    @Flag(description = "A larger count")
    val quota: Long get() = 5_000_000_000

    @Flag(description = "A ratio")
    val rolloutShare: Double get() = 0.5

    @Flag(description = "A ratio, smaller")
    val scale: Float get() = 1.0f

    @Flag(description = "A name")
    val channel: String get() = "beta"

    @Flag(description = "A blob")
    val payload: ByteArray get() = ByteArray(0)

    @Flag(description = "A moment")
    val launchesAt: Instant get() = Instant.EPOCH

    @Flag(description = "An endpoint")
    val endpoint: URI get() = URI("https://example.com")
}
```

`Int` and `Long` are both stored as a 64-bit integer, the schema's `int`, so a companion
edits them the same way. An `Instant` is stored to the second, as ISO 8601 in UTC. A `URI`
is stored as its string form, so a stored value stays readable and portable between
builds.

### Collections

Lists and string-keyed maps work as long as their elements do:

<!-- snippet: values-collections -->
```kotlin
@Flag(description = "Markets to enable")
val markets: List<String> get() = emptyList()

@Flag(description = "Per-market rollout share")
val rolloutByMarket: Map<String, Double> get() = emptyMap()
```

Maps must be keyed by `String`. JSON has no other kind of key, and a flag that cannot
survive a trip through it would not be editable from a companion app.

A collection has to be homogeneous, because its element type is what the schema
publishes and what a payload is validated against. When you want a list of small
structured values instead — a name, a URL and a switch, repeated — that is a record:

<!-- snippet: values-record -->
```kotlin
@FlagRecord
data class Endpoint(
    val name: String,
    val url: URI,
    val enabled: Boolean,
)
```

<!-- snippet: values-records-flag -->
```kotlin
@Flag(description = "Endpoints")
val endpoints: FlagRecords<Endpoint> get() = FlagRecords()
```

See [Records](RecordFlags.md).

### Enums

Any enum works, with no implementation at all:

<!-- snippet: values-enum -->
```kotlin
enum class Tier { Free, Pro, Enterprise }
```

<!-- snippet: values-enum-flag -->
```kotlin
@Flag(description = "Pricing tier to present")
val tier: Tier get() = Tier.Free
```

It is stored as the entry's `name`. Implement `FlagStringEnum` to store something else,
such as the lowercase strings a backend already sends:

<!-- snippet: values-string-enum -->
```kotlin
enum class StoredTier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
    Enterprise("enterprise"),
}
```

Every enum flag is a picker in the companion app rather than a free-text field, because
the schema carries the list of its entries. There is nothing to opt in to.

That list is also enforced, on every way a value can arrive: a `"platinum"` from your
backend, from an imported document, or from a scanned code is rejected outright rather
than stored and silently fallen back from on every read. That is the difference between
an error you can see and a flag that mysteriously never takes effect.

`FlagIntEnum` stores an enum as an integer. Its raw value is a `Long`, the type every
integer is stored as:

<!-- snippet: values-int-enum -->
```kotlin
enum class RetryPolicy(override val flagRawValue: Long) : FlagIntEnum {
    None(0),
    Once(1),
    Aggressive(3),
}
```

### A type the processor does not know

The set of types is closed: the processor has to pick a codec for every flag at compile
time, and it cannot find one you wrote. A property of any other type is an error naming
the types it can hold.

Store the value as the type underneath, and wrap it where you read it:

<!-- snippet: values-wrapped-type -->
```kotlin
@FlagContainer
interface PricingFlags {
    @Flag(description = "Discount at checkout, as a fraction of the price")
    val discountAmount: Double get() = 0.1
}

@JvmInline
value class Percentage(val fraction: Double)

val PricingFlags.discount: Percentage
    get() = Percentage(discountAmount)
```

`pole.flags.discount` then reads as a `Percentage`, and the companion edits it as the
`Double` it is stored as — which is what a companion that never sees your types would do
anyway.

### Reading and writing boxes directly

Most code never touches `FlagValueBox`. Import, export and the companion app all do,
because they work from a schema rather than from your Kotlin types:

<!-- snippet: values-boxes -->
```kotlin
val box = FlagValueBox.Bool(true)
val isBool = box.matches(FlagValueType.Bool) // true
val value = FlagCodecs.boolean.decode(box) // true
```

`FlagCodecs` holds the codec for every type a flag can hold, which is what the generated
code reads and writes with.
