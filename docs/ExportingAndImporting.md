# Exporting and importing

Moving one device's overrides onto another, as a document or a QR code.

## Overview

"It only happens on my phone" is the question this answers. Export the overrides, send
them to whoever is trying to reproduce it, and their device is in the same state as yours.

Only overridden flags travel. Exporting every flag would mostly be a copy of the defaults
already compiled into the receiving app, and would not fit in a QR code.

### Exporting

<!-- snippet: exporting-export -->
```kotlin
val json = pole.exportJson() // UTF-8 JSON, as a ByteArray
val payload = pole.exportPayload() // the same, as a FlagPayload
```

The JSON is meant to be read and hand-edited:

<!-- output: exporting-json -->
```json
{
  "exportedAt" : "2026-01-01T09:41:00Z",
  "formatVersion" : 1,
  "values" : {
    "checkout.google-pay" : true,
    "endpoint" : "https://staging.example.com/v3",
    "page-size" : 50
  }
}
```

Keys are sorted, and slashes are not escaped — a URL that reads `https:\/\/…` is not
hand-editable, whatever the specification permits. The bytes are what the iOS library
writes for the same values, so a document travels between the two platforms;
Differences from iOS lists the edge cases.

There is no property-list form.

### Importing

<!-- snippet: exporting-import -->
```kotlin
val applied = pole.importJson(bytes)
val keys = applied.values.keys
```

`importJson` returns the `FlagPayload` it applied. Import is strict and all-or-nothing.
If any key is unknown to this app, holds a value of the wrong type, or names an enum entry
this build does not have, **nothing** is applied and every problem is reported at once:

<!-- snippet: exporting-import-rejected -->
```kotlin
try {
    pole.importJson(bytes)
} catch (rejected: FlagImportException.Rejected) {
    for (problem in rejected.problems) {
        Log.w("Flags", "${problem.key}: ${problem.kind}") // UnknownKey, TypeMismatch or UnknownCase
    }
}
```

A document from a different app, or from a build where a flag has since changed type or
lost an enum entry, is refused rather than half-applied. The enum check matters more here
than anywhere else: documents travel between builds by design, so the entry that exists
on the sending device and not the receiving one is the normal situation, not the exotic
one. Bytes that are not a flag document at all are a `FlagImportException.Malformed`.

Values land in the highest-priority mutable source — the local override layer — so, when
that source is above your remote one in the stack, an imported value outranks anything a
backend later sends, and stays until it is cleared.

Import applies what it carries; it is not a wholesale replacement. Overrides the document
does not mention are left alone. Clear first if you want the document to be the whole
state:

<!-- snippet: exporting-replace -->
```kotlin
pole.removeAllOverrides()
pole.importJson(bytes)
```

A payload you have already decoded, against this pole's schema, applies the same way:

<!-- snippet: exporting-apply-payload -->
```kotlin
val payload = FlagPayload.decode(bytes, pole.schema)
pole.applyPayload(payload)
```

If the source refuses a write partway through, the writes already made are undone, on a
best-effort basis, before the error reaches you.

### QR codes

For handing state to someone sitting next to you, a code on screen beats a file:

<!-- snippet: exporting-qr -->
```kotlin
val code = pole.qrCodeString() // "FFQR1:…"
```

The wire format is `FFQR1:` followed by base64url of the payload's length and its
deflate-compressed bytes. Compression is what makes this practical: flag keys and JSON
structure repeat heavily, so realistic payloads shrink substantially and fit where the raw
JSON would not. A code made on iOS imports here and one made here imports on iOS.

The library turns overrides into the string; the companion app draws it as an image, with
Copy and Share. No camera UI ships here either. Decoding takes whatever string your
scanner produced:

<!-- snippet: exporting-qr-import -->
```kotlin
receiver.importQRCode(scanned)
```

A code that will not fit throws, and says what is in it so the message can be useful:

<!-- snippet: exporting-qr-too-large -->
```kotlin
try {
    showCode(pole.qrCodeString())
} catch (tooLarge: FlagQRCodeException.PayloadTooLarge) {
    Log.w("Flags", "${tooLarge.overrideCount} overrides need ${tooLarge.bytes} of ${tooLarge.limit} characters")
}
```

The limit is 2,953 characters — the capacity of the largest QR code at the lowest error
correction level. Correction stays at that level on purpose: for a code shown on one
screen and scanned by another, capacity matters more than damage tolerance.

A scanned code is validated against this app's own flags exactly as a document is, so a
code from another app's build is rejected rather than partially applied.

### One thing worth knowing

**Export spans the whole stack.** `pole.overrides`, and everything built on it, carries
every value a source supplies — *including* one a remote payload supplied, and even one
that equals the default.
Exporting from a device with remote configuration and importing elsewhere turns those
into local overrides on the receiving device.

That is usually what you want from "reproduce this device's state". It is worth knowing
before you share a code, because the receiving device will then hold those values against
its own backend's wishes until they are cleared.

### Errors you can hit while serialising

A `Double` or `Float` flag holding infinity or NaN cannot be written as JSON. Exporting
one throws `FlagSerializationException.NonFiniteNumber`, which names the flag.
