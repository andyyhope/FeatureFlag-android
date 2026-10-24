# M5 (hardening before 0.1.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every follow-up still open since M1 before the API is published: the iOS-mismatched source labels, the import error's missing cause, the duplicated logic, the edge inputs that behave differently from iOS, the silent key-encoding mismatch, the KSP gaps and the missing tests.

**Architecture:** Behaviour-preserving refactors run against the existing goldens. Every behaviour change has a failing test first. iOS 0.12.0 is the reference for each edge input; where the fixture generator can show Swift's answer, it does, rather than a reading of the Swift code.

**Tech Stack:** Kotlin 2.2.10, KSP 2.2.10-2.0.2 + kctfork, JUnit 4/kotlin.test, Robolectric, Swift 6.4 for `fixture-generator/`.

**Spec:** `docs/superpowers/notes/2026-10-09-m1-followups.md`, sections "Small code fixes (any time)" and "Test gaps (any time)", plus two items THE USER chose on 2026-10-25 from "Decide before the M4 API freeze". THE USER's decisions:
- Rename only where Kotlin departs from iOS: the source label parameter becomes `name`. Everything else in "Naming drift" mirrors iOS and stays.
- `FlagImportException.Malformed` gets a cause.
- `docs/superpowers/` stays in the public repository.
- Do all open engineering items in one PR.

An audit on 2026-10-25 found 28 of the note's 31 items open. The two QR items are fixed (`bf9f8a2`) and are not repeated here.

## Global Constraints

- Everything in the M1–M4 overviews' Global Constraints still applies: `explicitApi()` and `allWarningsAsErrors` in library modules, AGP built-in Kotlin, test naming as sentences in camelCase, Robolectric `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [36])`.
- Every Gradle command runs with `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- No version bumps and no new dependencies.
- **The API is frozen (M4 B3).** A task that changes a public signature runs `./gradlew apiDump releaseApiDump` and commits the dumps with the change. Its report names the change.
- **Docs are checked.** `:docs-samples:testDebugUnitTest` (`DocsSyncTest`) must stay green. A doc fence that shows a changed call changes in the same commit as its snippet region.
- `fixtures/` is only ever written by `scripts/sync-fixtures.sh`. The iOS repo (`/Users/andyyhope/Development/feature-flags`, tag `0.12.0`) is read-only.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nothing is pushed until Task 13.
- **Definition of done for every task:** `./gradlew build detekt` is green, and every test the task names passes.

## Review Focus

1. **A remote source that legitimately covers part of a pole.** It is built from a hand-made schema with a subset of the pole's keys, or from an empty schema. Task 7's new check must accept it and refuse only keys the pole lacks. Pinned in Task 7.
2. **Grapheme-aware key splitting changes no key that exists today.** Every row already in `fixtures/keys/key-encoding.tsv` must come out byte-identical. A changed key renames a stored override. Pinned in Task 10's verification (`git diff` shows only added rows).
3. **An enum with two entries sharing a raw value.** Encoding the second entry still writes the shared value, decoding gives the first entry, as Swift's first-wins does, and the schema lists the case once. Pinned in Task 5.
4. **`"formatVersion": false` in a schema.** It reads as 0, so it is `UnsupportedFormatVersion(0)`, not `Malformed`, matching the payload reader. Pinned in Task 5.
5. **A path index written in non-ASCII digits.** `valueAt("items.١")` (Arabic-Indic one) and `"items.１"` (fullwidth) find nothing, while `"items.1"` still works. Pinned in Task 5.

---

### Task 1: Source labels are called `name`, as on iOS

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSource.kt:30-42`
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSource.kt:30-33`
- Modify callers: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/scenario/ScenarioTest.kt:30,162,170`, `samples/host/src/test/kotlin/com/andyyhope/featureflag/sample/host/ScenarioTest.kt:34,161,169`
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSourceTest.kt`, `featureflag-android/src/test/kotlin/com/andyyhope/featureflag/android/sources/SharedPreferencesSourceTest.kt`

**Interfaces:**
- Produces: `RemoteOverrideSource(schema: FlagSchema, mapper: RemoteOverrideMapper = DotPathMapper, name: String = "Remote")`; `RemoteOverrideSource(spec, keyEncoding = KeyEncoding.Kebabcase, mapper = DotPathMapper, name = "Remote")`; `SharedPreferencesSource(preferences: SharedPreferences, name: String = "SharedPreferences")`. The `sourceName` property (from `FlagValueSource`) is unchanged.

- [ ] **Step 1: Write the failing tests**

```kotlin
// RemoteOverrideSourceTest
@Test fun nameLabelsTheSourceAsOnIos() {
    assertEquals("CDN", RemoteOverrideSource(RemoteDemoFlagsSpec, name = "CDN").sourceName)
    assertEquals("CDN", RemoteOverrideSource(FlagSchema.of(RemoteDemoFlagsSpec), name = "CDN").sourceName)
}

// SharedPreferencesSourceTest
@Test fun nameLabelsTheSourceAsOnIos() =
    assertEquals("Local", SharedPreferencesSource(preferences, name = "Local").sourceName)
```

Use whatever `preferences` fixture the test class already has.

- [ ] **Step 2: Run them to see them fail to compile**

Run: `./gradlew :featureflag-core:test --tests '*RemoteOverrideSourceTest' :featureflag-android:testDebugUnitTest --tests '*SharedPreferencesSourceTest'`
Expected: compilation error, "No parameter with name 'name' found".

- [ ] **Step 3: Rename the constructor parameters**

Each primary constructor takes a plain `name: String` parameter and declares `override val sourceName: String = name` in the body, as `SnapshotSource` already does. Update the six `sourceName = "Remote"` callers to `name = "Remote"`.

- [ ] **Step 4: Run the tests and the ABI check**

Run: `./gradlew :featureflag-core:test :featureflag-android:testDebugUnitTest :samples:host:testDebugUnitTest apiCheck releaseApiCheck`
Expected: PASS. Parameter names are not in the dumps, so they need no update. If `apiCheck` fails, the property was dropped by mistake.

- [ ] **Step 5: Commit**: `Name the remote and SharedPreferences sources' label parameter name, as iOS does`

### Task 2: `FlagImportException.Malformed` keeps the reader's error

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/FlagImportException.kt:41-43`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt:95-103` (`parseDocument`)
- Modify: `featureflag-core/api/featureflag-core.api` (regenerated)
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagImportExceptionTest.kt`

**Interfaces:**
- Produces: `sealed class FlagImportException(message: String, cause: Throwable? = null)` and `class Malformed(val reason: String, cause: Throwable? = null)`, the same shape as `RemoteOverrideException.Malformed`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun aDocumentThatIsNotJsonKeepsTheReadersError() {
    val error = assertFailsWith<FlagImportException.Malformed> { FlagPayload.decode("{ nope".encodeToByteArray(), schema) }
    assertEquals("not a JSON object", error.reason)
    assertIs<JsonSyntaxException>(error.cause)
}

@Test fun aMalformedDocumentThatIsValidJsonHasNoCause() =
    assertNull(assertFailsWith<FlagImportException.Malformed> { FlagPayload.decode("[]".encodeToByteArray(), schema) }.cause)
```

`JsonSyntaxException` is internal to core, so this test can see it. Use the schema the test class already builds.

- [ ] **Step 2: Run it to see it fail**: `./gradlew :featureflag-core:test --tests '*FlagImportExceptionTest'`. Expected: `cause` is null.
- [ ] **Step 3: Add the cause parameter, and pass the caught `JsonSyntaxException` from `parseDocument`**. Keep `null` for a document that parses but is not an object.
- [ ] **Step 4: Run `./gradlew apiDump`, then `./gradlew :featureflag-core:test apiCheck`**. Expected: PASS. The dump diff adds only the `Malformed(String, Throwable)` constructor and its synthetic default.
- [ ] **Step 5: Commit**: `Keep the JSON reader's error as the cause of a malformed import`. The report names the API addition.

### Task 3: One first-wins view of a schema

Pure refactor. No behaviour changes, and the existing suite is the test.

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt` (add `entriesByKey`)
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt:116-143` (`ImportRules`)
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteMapping.kt:25-30,118-127`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/audit/FlagMappingAudit.kt:172-175`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteValue.kt:137-148`

**Interfaces:**
- Produces: `internal val FlagSchema.entriesByKey: Map<FlagKey, FlagSchema.Entry>`, first entry per key, in schema order.

- [ ] **Step 1: Make the replacements**
  - `ImportRules.types`, `cases` and `shapes` become `schema.valueTypes`, `schema.valueCases` and `schema.recordShapes`, computed once in the constructor. Keep the class's KDoc sentence on first-wins.
  - `mapRemote` and `comparisons` use `entriesByKey` instead of building their own map.
  - `firstDuplicateKey` is deleted. `duplicateRecordKey` calls `RecordJson.duplicateKey(records, shape)`.
  - `RemoteValue.shortDescription` uses `MessageText.date`, `MessageText.arrayOf` and `MessageText.objectWith`.
- [ ] **Step 2: Run `./gradlew :featureflag-core:test`**. Expected: PASS, with no golden changed.
- [ ] **Step 3: Commit**: `Read first-wins lookups, duplicate record keys and short descriptions from one place each`

### Task 4: One exponent formatter

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/AppleNumberFormat.kt`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPayload.kt:197-214` (`numberDescription`, `NUMBER_DIGITS`)
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/errors/MessageText.kt:156-160` (`exponentialForm`)
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/json/AppleNumberFormatTest.kt`, `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/errors/FlagImportExceptionTest.kt`

**Interfaces:**
- Produces, in `AppleNumberFormat`:
  - `fun g(value: Double, significantDigits: Int): String`: C's `%.<n>g` of the exact binary value for a finite value, with trailing zeros and a trailing `.` removed. `format(value)` becomes `g(value, 17)` after its `require`.
  - `fun exponential(digits: String, exponent: Int): String`: unsigned `d.ddd` + `e` + sign + two or more exponent digits, for example `exponential("15", 300) == "1.5e+300"` and `exponential("1", -7) == "1e-07"`.

- [ ] **Step 1: Pin today's import descriptions before touching them** (these pass now):

```kotlin
@Test fun aRealValueOfTheWrongTypeIsDescribedAsNsNumberWouldDescribeIt() {
    val found = { json: String -> assertFailsWith<FlagImportException.Rejected> {
        FlagPayload.decode("""{"formatVersion":1,"values":{"$STRING_KEY":$json}}""".encodeToByteArray(), schema)
    }.problems.single().found }
    assertEquals("1.5e+300", found("1.5e300"))
    assertEquals("0.1", found("0.1"))
    assertEquals("1.234567890123457e+17", found("123456789012345680.0"))
    assertEquals("-1.5e-07", found("-1.5e-7"))
    assertEquals("-inf", found("-1e400"))
}
```

`STRING_KEY` is any string flag in the test's schema, so a number is a type mismatch.

- [ ] **Step 2: Add `AppleNumberFormatTest` cases for `g` and `exponential`.** Use the values above for `g(…, 16)`, plus `g(0.1, 17) == "0.10000000000000001"`. Run them: they fail because the functions don't exist.
- [ ] **Step 3: Implement `g` and `exponential` by extracting from `format`/`scientific`.** `numberDescription` keeps only its inf and `-0` handling and calls `g(value, 16)`. `MessageText.exponentialForm` calls `exponential`. Delete the now-unused constants and imports.
- [ ] **Step 4: Run `./gradlew :featureflag-core:test`**. Expected: PASS, including every message golden.
- [ ] **Step 5: Commit**: `Format exponents in one place for JSON, NSNumber descriptions and messages`

### Task 5: Four edge inputs read as iOS reads them

One TDD cycle and one commit per item.

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/schema/FlagSchema.kt:179` and `:90`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/FlagCodecs.kt:101-110`
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/remote/RemoteValue.kt:57`
- Test: `schema/FlagSchemaJsonTest.kt` (or the existing schema parse test), `values/FlagCodecsTest.kt`, `remote/RemoteValueTest.kt`, under `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/`

- [ ] **5a. A schema's `formatVersion` may be a boolean, as Swift's `as? Int` allows.**
  - Test `aBooleanFormatVersionReadsAsSwiftReadsIt`: a valid published document with `"formatVersion": true` parses with `formatVersion == 1`. The same document with `false` throws `FlagSchemaException.UnsupportedFormatVersion` with `version == 0` (Review Focus 4).
  - Fix: mirror `FlagPayload.formatVersion`'s `when`, including its comment.
  - Commit: `Read a boolean schema formatVersion as Swift does`.
- [ ] **5b. Duplicate enum raw values: the first entry wins and the case is listed once.**
  - Test `twoEntriesSharingARawValueDecodeToTheFirst` with a test-local `enum class Plan(override val flagRawValue: String) : FlagStringEnum { Free("free"), Gratis("free"), Pro("pro") }`. Assert:
    - `codec.decode(FlagValueBox.String("free")) == Plan.Free`;
    - `codec.encode(Plan.Gratis) == FlagValueBox.String("free")`;
    - `codec.cases == listOf(FlagValueBox.String("free"), FlagValueBox.String("pro"))`.

    Do the same for an `intEnum` (Review Focus 3).
  - Fix: build `byBox` with `putIfAbsent`, and take `distinct()` cases.
  - Commit: `Decode a shared enum raw value to the first entry, as Swift does`.
- [ ] **5c. Array indexes in a dot path are ASCII digits only.**
  - Test `anIndexInNonAsciiDigitsFindsNothing`: on `RemoteValue.parseJson("""{"items":["a","b"]}""")`, `valueAt("items.1")` is `RemoteValue.String("b")`, while `valueAt("items.١")` and `valueAt("items.１")` are null (Review Focus 5).
  - Fix: accept the component only when every char is in `'0'..'9'`, then `toIntOrNull()`.
  - Commit: `Index arrays in a dot path by ASCII digits only`.
- [ ] **5d. A lone surrogate is written as U+FFFD in the schema too.**
  - Test `aLoneSurrogateIsWrittenAsTheReplacementCharacterLikeTheExport`: a schema whose description is `"a\uD800b"` writes the bytes `EF BF BD` between `a` and `b` in `jsonBytes()`, not `?`. A payload holding the same string already does; assert both.
  - Fix: `FlagSchema.jsonBytes` uses `encodeToByteArray()`.
  - Commit: `Write a lone surrogate in a schema as the export does`.
- [ ] **Run `./gradlew :featureflag-core:test`** after the four commits. Expected: PASS.

### Task 6: Catch only what can be thrown

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/json/JsonReader.kt:25`, `values/BoxJson.kt:119`, `records/RecordJson.kt:52`

- [ ] **Step 1: Replace each `runCatching`.** `JsonReader.parse` catches `CharacterCodingException`. `parseUrl` catches `URISyntaxException`. `RecordJson.parse` catches `JsonSyntaxException`. Each is a `try`/`catch` returning null, as `FlagPayload.parseDocument` already does.
- [ ] **Step 2: Run `./gradlew :featureflag-core:test`**. Expected: PASS (`JsonReaderTest.invalidUtf8IsRejected`, `UrlGoldenTest` and the record tests cover all three paths).
- [ ] **Step 3: Commit**: `Catch the one exception each parser can throw instead of Throwable`

### Task 7: A pole refuses a remote source that names keys it lacks

Today a `RemoteOverrideSource` built with a different `KeyEncoding` from its pole resolves nothing, and nothing says so.

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/pole/FlagPole.kt` (init)
- Modify: `docs/Troubleshooting.md` (new section), `docs/DifferencesFromIOS.md` (one bullet)
- Test: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/remote/RemoteOverrideSourceTest.kt`

**Interfaces:**
- Produces: `FlagPole(...)` throws `IllegalArgumentException` when any source in `sources` is a `RemoteOverrideSource` whose `schema` holds a key that `this.keys` does not hold. The message is exactly:
  `"RemoteOverrideSource '<sourceName>' describes '<first such key in its schema order>', which this pole does not have. Build it with the pole's keyEncoding."`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aPoleRefusesARemoteSourceBuiltWithAnotherKeyEncoding() {
    val remote = RemoteOverrideSource(RemoteDemoFlagsSpec, keyEncoding = KeyEncoding.Snakecase)
    val error = assertFailsWith<IllegalArgumentException> { FlagPole(RemoteDemoFlagsSpec, listOf(remote)) }
    assertEquals(
        "RemoteOverrideSource 'Remote' describes '<first snake_case key that differs>', which this pole does not have. " +
            "Build it with the pole's keyEncoding.",
        error.message,
    )
}

@Test fun aPoleAcceptsARemoteSourceCoveringPartOfItsFlags() {
    val full = FlagSchema.of(RemoteDemoFlagsSpec)
    val part = full.copy(flags = full.flags.take(1))
    FlagPole(RemoteDemoFlagsSpec, listOf(RemoteOverrideSource(part), RemoteOverrideSource(full.copy(flags = emptyList()))))
}
```

Fill in the key from `RemoteDemoFlagsSpec`, which has a multi-word flag (`newOnboarding`). If `FlagSchema` has no `copy`, build the subset with its constructor (Review Focus 1).

- [ ] **Step 2: Run them to see the first fail**: `./gradlew :featureflag-core:test --tests '*RemoteOverrideSourceTest'`.
- [ ] **Step 3: Add the check to `FlagPole`'s init**, after `schema` is built.
- [ ] **Step 4: Run `./gradlew :featureflag-core:test :featureflag-android:testDebugUnitTest :featureflag-ui:testDebugUnitTest :docs-samples:testDebugUnitTest :samples:host:testDebugUnitTest`**. Expected: PASS. If an existing test builds a mismatched source on purpose, stop and report it rather than loosening the rule.
- [ ] **Step 5: Document it**
  - `Troubleshooting.md`: a section "A remote value never shows up", in prose with no new fence. It says the pole now refuses a source built with a different `keyEncoding`, quotes the message's first sentence, and gives the fix.
  - `DifferencesFromIOS.md`: one bullet under the sources group: "A pole refuses a remote source whose keys it does not have. iOS resolves nothing and says nothing."
  - Run `./gradlew :docs-samples:testDebugUnitTest`.
- [ ] **Step 6: Commit**: `Refuse a remote source built with a different key encoding from its pole`

### Task 8: KSP expands generic and nullable typealiases correctly

**Files:**
- Modify: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/TypeMapping.kt:80-98,186-188`
- Test: `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/TypeMappingTest.kt`

**Interfaces:**
- Consumes: `TypeMapping(resolver).map(type: KSType): TypeMapping.Result` (`Supported(codec, placeholder, originatingFiles)`, `Unsupported(record: String?)`, `MissingRecordCodec`).
- Produces: `KSType.expanded()` substitutes an alias's type arguments into its target at every depth. If building the substituted arguments needs the `Resolver`, change the signature to `KSType.expanded(resolver: Resolver)` and update every caller.

- [ ] **Step 1: Add a helper `mapped(vararg sources: SourceFile): Map<String, TypeMapping.Result>`** beside `originatingFiles`, built the same way.
- [ ] **Step 2: Write the failing tests**
  - `aGenericTypealiasIsExpandedWithItsArguments`: with `typealias Named<T> = List<T>` and `typealias Table<T> = Map<String, List<T>>`, the properties `named: Named<String>` and `table: Table<Int>` map to `Supported` with the same `codec.toString()` as `List<String>` and `Map<String, List<Int>>` declared directly.
  - `aNullableRecordReachedThroughATypealiasIsUnsupported`: with `@FlagRecord data class Endpoint(@FlagRecordKey val name: String)` and `typealias MaybeEndpoint = Endpoint?`, `FlagRecords<MaybeEndpoint>` maps to `Unsupported(null)`.
  - `unsupportedTypesSayWhichRecordIfAny`:
    - `Set<String>` gives `Unsupported(null)`;
    - `Map<Int, String>` gives `Unsupported(null)`;
    - `java.util.UUID` gives `Unsupported(null)`;
    - the `@FlagRecord` class `Endpoint` used directly gives `Unsupported("Endpoint")`.
- [ ] **Step 3: Run them to see the first two fail**: `./gradlew :featureflag-ksp:test --tests '*TypeMappingTest'`.
- [ ] **Step 4: Implement the substitution.** In `records`, test `element.expanded().isMarkedNullable` instead of `element.isMarkedNullable`.
- [ ] **Step 5: Run `./gradlew :featureflag-ksp:test`**. Expected: PASS, with every expansion snapshot unchanged.
- [ ] **Step 6: Commit**: `Substitute a generic typealias's arguments and see a nullable record through an alias`

### Task 9: KSP finds a group cycle through a container outside the round

In an incremental build only the changed files are in the round, so `Checks.cycles` can miss a cycle through an unchanged container.

**Files:**
- Modify: `featureflag-ksp/src/main/kotlin/com/andyyhope/featureflag/ksp/Diagnostics.kt:246-262` (`cycles`, `pathBack`)
- Test: `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/ContainerDiagnosticTest.kt`

**Interfaces:**
- Consumes: `Checks(resolver: Resolver, logger: KSPLogger).cycles(containers: List<KSClassDeclaration>): Set<KSClassDeclaration>`, `Diagnostics.groupCycle(name, container, path)`.

- [ ] **Step 1: Write the failing test `aCycleThroughAContainerOutsideTheRoundIsFound`.** A test `SymbolProcessorProvider`, like `TypeMappingTest`'s, calls `Checks(resolver, logger).cycles(listOf(a))` with only `A`. The sources declare `@FlagContainer interface A { @FlagGroup(description = "b") val b: B }` and `@FlagContainer interface B { @FlagGroup(description = "a") val a: A }`. Assert that the returned set is `{A}` and that the compilation's messages contain `Diagnostics.groupCycle("b", "A", "A → B → A")`. Check the exact path format against an existing cycle test.
- [ ] **Step 2: Run it to see it fail**: no cycle is reported.
- [ ] **Step 3: Resolve group targets that are not in the round** with `resolver.getClassDeclarationByName`, keeping only `@FlagContainer` declarations.
- [ ] **Step 4: Run `./gradlew :featureflag-ksp:test`**. Expected: PASS.
- [ ] **Step 5: Commit**: `Follow a group cycle through containers outside the current KSP round`

### Task 10: Keys split by grapheme and lowercase as Swift does

**Files:**
- Modify: `fixture-generator/Sources/GenerateFixtures/EncodingFixtures.swift:14-25` (`propertyNames`)
- Regenerate: `fixtures/keys/key-encoding.tsv` (via `scripts/sync-fixtures.sh` only)
- Modify, only if the golden fails: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/values/KeyEncoding.kt` (`splitWords`, `joinedWords`)

- [ ] **Step 1: Append these names to `propertyNames`** under a `// Graphemes and final sigma` comment:
  - final sigma: `"ΟΔΟΣ"`, `"ΟΔΟΣName"`, `"σοφίαΣ"`;
  - combining marks: `"ABE\u{301}c"`, `"E\u{301}clair"`, `"cafe\u{301}Bar"`;
  - emoji: `"x\u{1F1E6}\u{1F1FA}Y"`, `"AB\u{1F44D}\u{1F3FD}c"`.
- [ ] **Step 2: Regenerate**: `scripts/sync-fixtures.sh`, then `git diff --stat fixtures/`. Expected: only `fixtures/keys/key-encoding.tsv` changes, and `git diff fixtures/keys/key-encoding.tsv` shows only added lines (Review Focus 2). If anything else changes, stop and report.
- [ ] **Step 3: Run the key-encoding golden**: `./gradlew :featureflag-core:test --tests '*KeyEncoding*'`. If it passes, Kotlin already matches; commit the fixtures and skip to Step 6.
- [ ] **Step 4: Make Kotlin match Swift** for each failing row:
  - Split on extended grapheme clusters (`java.text.BreakIterator.getCharacterInstance(Locale.ROOT)`), with each cluster's case taken as Swift's `Character.isUppercase`/`isLowercase` takes it.
  - Lowercase as Swift's `lowercased()` does. If Swift keeps a word-final `σ` where Java's `lowercase()` writes `ς`, lowercase per code point.
  - Comment the rule beside `splitWords` the way the existing KDoc states the 0.12.0 rule.
- [ ] **Step 5: Run `./gradlew :featureflag-core:test`**. Expected: PASS, including every key-encoding row and the schema goldens.
- [ ] **Step 6: Commit**: `Pin grapheme and final-sigma key encoding against Swift` (add `and match it` if Step 4 ran)

### Task 11: The remaining test gaps

Test-only, unless a test exposes a bug. If one does, stop and report it.

**Files (all under `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/` unless named):**
- `Fixtures.kt:27-34`, `json/AppleKeyOrderTest.kt`, `json/JsonReaderTest.kt:67-74`, `errors/ErrorMessageTest.kt`, `remote/EnvironmentConfigurationTest.kt`, `audit/FlagMappingAuditTest.kt`
- `featureflag-ksp/src/test/kotlin/com/andyyhope/featureflag/ksp/RecordGenerationTest.kt`, `ContainerDiagnosticTest.kt`, plus new snapshots in `featureflag-ksp/src/test/resources/expansions/`

- [ ] **11a. Fixtures guard.** `tsv` and `lines` call `check(text.isNotEmpty()) { "Empty fixture $path; run scripts/sync-fixtures.sh" }`. Remove `tsv`'s empty early return. Run the core tests; any fixture that turns out to be empty is reported, not edited.
- [ ] **11b. `AppleKeyOrder` honours the comparator contract.** `keysFromARandomAlphabetSortConsistently` uses `Random(7)` to make 2,000 keys of length 0–6 from `"aAzZ09_-. éÉ"`. For all pairs in a 300-key sample, assert:
  - `sign(compare(a, b)) == -sign(compare(b, a))`;
  - `compare(a, b) == 0` only when `a == b` (the tie-break is total);
  - transitivity on every triple of a 60-key sample.

  Finally, `sortedWith(AppleKeyOrder)` gives the same list from two shuffles.
- [ ] **11c. More invalid UTF-8.** Add to `invalidUtf8IsRejected`:
  - a truncated 4-byte sequence `F0 9F 98`;
  - `F5 80 80 80`;
  - `F4 90 80 80` (above U+10FFFF).

  Each is wrapped as `["…"]`.
- [ ] **11d. Reworded messages are pinned exactly and accounted for.**
  - `assertSays` stays for the fragments. Add `assertEquals(<full Kotlin message>, error.message)` for `FlagSchemaException.NotPublished()`, `FlagException.NoMutableSource()` and `FlagException.UnsupportedValue(FlagKey("page-size"))`, copying the current text.
  - `everyMessageGoldenIsAccountedFor` also asserts `(REWORDED + DROPPED) - names` is empty.
- [ ] **11e. `EnvironmentConfiguration` gaps.**
  - `aThrowingLocalLoaderIsReportedAndTheRemoteStillApplies`: local throws `Offline()`, remote returns a valid payload. Assert that `outcome.local` is `Failed` with `Offline`, that `outcome.remote.isApplied` holds, and that the remote's value is read.
  - `aPayloadTheSchemaRejectsIsReportedAndDoesNotApply`: local returns `{ "pageSize": "big" }`. Assert that `outcome.local` is `Failed` with a `RemoteOverrideException.Rejected` error, and that `pageSize` is the default.
  - In `loadStagingAroundProduction`, replace both bare `join()`s with `join(5_000)` followed by `assertFalse(thread.isAlive, …)`.
- [ ] **11f. Audit: `-1e400` in a `Double` record field is a mismatch.** `aRecordFieldThatUnderflowsToNegativeInfinityIsAMismatch` mirrors `aRecordFieldThatOverflowsToInfinityIsAMismatch` with a `Double` field. Add a field or a small spec beside `RatioAuditFlagsSpec` if none has one.
- [ ] **11g. KSP record snapshots.** Add `assertMatchesSnapshot` tests and `.txt` snapshots for three records:
  - one nested in an `object Catalog`;
  - an `internal` record;
  - a record whose every field has a default.

  Read each generated file before committing it as a snapshot. The harness compiles it, so it must build.
- [ ] **11h. KSP: a container nested in a private parent is refused.** `aContainerInsideAPrivateInterfaceIsRefused`: `private interface Outer { @FlagContainer interface Inner { … } }` reports `Diagnostics.containerPrivate("Inner")` at Inner's line, and nothing is generated for it (`assertNothingGeneratedFor`).
- [ ] **Run `./gradlew :featureflag-core:test :featureflag-ksp:test`**, and commit each sub-item separately, for example `Guard fixtures against an empty file`.

### Task 12: Housekeeping

**Files:**
- `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/pole/FlagPoleFlowTest.kt:277-279`
- `config/detekt/detekt.yml:1-3`
- Create: `docs/superpowers/notes/2026-10-25-swift-test-coverage.md`
- `docs/superpowers/notes/2026-10-09-m1-followups.md` (status line)

- [ ] **Step 1: Make the comment say what the test does**: the collector's thread is blocked while `intFlag` is written 200 times after its own change.
- [ ] **Step 2: Rewrite the detekt header's first line** for this repo: "FeatureFlag detekt config — extends detekt's defaults …". Keep the rest.
- [ ] **Step 3: Move the Swift→Kotlin coverage table** from `docs/superpowers/plans/2026-10-08-m1d-remote-audit-testsupport.md` (from line ~6152) into the new note under a short heading, and leave a link where it was.
- [ ] **Step 4: Add a status line at the top of the M1 follow-ups note**: "Status (2026-10-25): the 'Small code fixes' and 'Test gaps' items are closed by `2026-10-25-m5-hardening.md`. Filing the upstream iOS issue is still THE USER's call."
- [ ] **Step 5: Run `./gradlew build detekt`**. Expected: PASS.
- [ ] **Step 6: Commit**: `Correct a stale test comment and the detekt header, and keep the Swift coverage map as a note`

### Task 13: Verify the branch and open the PR

- [ ] **Step 1: Verify the whole branch**: `./gradlew build detekt --rerun-tasks --no-build-cache` and `./gradlew :dokkaGeneratePublicationHtml`. Record the unit test count and the warning count.
- [ ] **Step 2: Run a whole-branch review** (superpowers:requesting-code-review) against `72710a0..HEAD`. Fix what it confirms, with tests.
- [ ] **Step 3: Push over SSH**: `git push git@github.com:andyyhope/FeatureFlag-android.git worktree-m5-hardening`. HTTPS lacks the `workflow` scope.
- [ ] **Step 4: Open the PR.** The base is `main` if PR #4 has merged, otherwise `worktree-m4-samples`. The title is "Harden the API and close the M1 follow-ups before 0.1.0", in the same body format as #4. Watch both workflows to green.
