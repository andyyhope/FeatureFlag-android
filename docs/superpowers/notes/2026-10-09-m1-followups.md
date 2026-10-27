# M1 follow-ups

Status (2026-10-27): the 'Small code fixes' and 'Test gaps' items are closed by `2026-10-25-m5-hardening.md`. Filing the upstream iOS issue is still THE USER's call.

Milestone M1 (core, KSP, test support) was executed task by task, with a review after each task and a final whole-branch review. This note keeps what those reviews deferred, so M2–M4 planning can pick it up. Line numbers are from the M1 head and may drift.

## Design changes made during M1 (where the code departs from the plans)

- **Change delivery is lossless.**
  - Sources emit through the public `sources/FlagChangeEmitter`. It is built on the internal `ChangeInbox`: one merged pending change per subscriber, plus a conflated signal.
  - The plan used `MutableSharedFlow(64, DROP_OLDEST)`. That left `flow(ref)` stale when more than 64 writes ran inside an Unconfined or `Main.immediate` event loop.
  - `FlagValueSource.changes` now *requires* lossless delivery. Every new source (the M2 `SharedPreferencesSource`, the bridge client) must use `FlagChangeEmitter`.
- **A flag's default getter is evaluated once.**
  - The generated `Impl` falls back to `XSpec.<name>.defaultValue`, which `pole.flags`, refs, `flow`, `resolution` and the schema all share.
  - The plan's `super<X>.name` fallback re-ran the getter on each read, so different read paths could disagree.
  - The spec is corrected. The `docs/superpowers/plans/` files still describe the old fallback; they are historical.
- **KSP withholds dependents.** A container or record that depends on an invalid or cyclic one is not generated. Only the root cause is reported.
- **`EnvironmentConfiguration` closes Swift's overlap race.**
  - A stale layer returns `Superseded` without clearing or loading.
  - The commit is atomic through the internal `RemoteOverrideSource.apply/clear(…, commitIf)`.
  - A loader's own `withTimeout` gives `Failed` instead of cancelling the caller.
- **Remote records reject infinities.** A record field that boxes to ±Infinity is a `TypeMismatch` problem, not an escaping `FlagSerializationException`.
- **Strict JSON escapes.** `\u` escapes accept ASCII hex digits only.

## Carry into M2 (Android)

- **`SharedPreferencesSource`:** must use `FlagChangeEmitter` and an atomic `setBoxes`, because `removeAllOverrides` clears in one `setBoxes` batch. Consider routing `FlagPole.apply(payload)` through `setBoxes` so the bridge gets one commit.
- **QR import:** the decoder allocates up to the claimed 50,000,000 bytes before verifying. Add a plausibility check in the companion import path; a garbled scan can OOM a small heap.
- **`MessageText.double`/`float`:** these rely on `BigDecimal.toDouble/toFloat` rounding correctly. Verify on ART.
- **`FlagSchema.write`:**
  - It throws if `mkdirs()` loses a race.
  - `Files.move(ATOMIC_MOVE)` would give better errors. Revisit when `publishSchema(context)` lands.
  - The file mode is 0600, which is fine for the same-uid provider.
- **Build:** move detekt and the fixture test inputs into convention plugins, so the Android plugin reuses them. Drop the root `subprojects {}` block.

## Decide before the M4 API freeze (breaking after 0.1.0)

- **The `FlagPole.apply(payload)` extension** reads like the stdlib scope function, and `RemoteOverrideSource.apply(json)` means something else. Consider `applyPayload`.
- **Naming drift:**
  - `SnapshotSource(name)` versus `RemoteOverrideSource(sourceName)`.
  - The position of `keyEncoding` versus `mapper` varies between constructors.
  - The bytes parameter is called both `json` and `payload`.
  - Three verbs (`parse`, `decode`, `parseJson`) do similar jobs.
  - `KeyEncoding.Kebabcase`, `Snakecase` and `Verbatim` are PascalCase vals.
- **`FeatureFlag.VERSION`** hard-codes `0.1.0-SNAPSHOT`, and `TestFeatureFlags` is a placeholder. Generate the version from `VERSION_NAME`, or drop both, before `apiCheck` freezes them.
- **Errors:**
  - `FlagImportException.Malformed` takes no cause.
  - `FlagMappingAuditException` lives in `.audit`, not `.errors`.
  - The duplicate-key `IllegalStateException` and `setOverride`'s `NoMutableSource` lack `@throws` docs.
  - `NonFiniteNumber` from `RecordJson` does not name the field.
- **Documentation and process:**
  - Document the minimum consumer Kotlin version (Kotlin 2.2 metadata, coroutines 1.10), or pin `apiVersion` and `languageVersion`.
  - Decide whether `docs/superpowers/` ships in the public repo.
  - File the upstream iOS issue for the mirrored quirk (a record list sent as a string skips case checks). Then link it from `FlagRecordRemoteTest` and `RemoteMapping`. This is outward-facing, so it is THE USER's call.

## Small code fixes (any time)

- **Duplicated logic:**
  - `ImportRules` (`FlagPayload`) duplicates `FlagSchema.valueTypes`, `valueCases` and `recordShapes`.
  - `RemoteMapping.firstDuplicateKey` duplicates `RecordJson.duplicateKey`.
  - The first-wins entry map is built twice (`RemoteMapping` and `FlagMappingAudit`).
  - `FlagPayload.numberDescription` is a third copy of the exponent formatter.
  - `RemoteValue.shortDescription` re-spells `MessageText` helpers.
- **Behaviour:**
  - `FlagPayload` reads `formatVersion: true` as 1, but `FlagSchema.parse` rejects it. Swift accepts it in both.
  - Duplicate enum raw values: the last one wins, where Swift's first wins.
  - `RemoteValue.valueAt` accepts non-ASCII digits as array indexes.
  - `runCatching` catches `Throwable` in `JsonReader`, `BoxJson.parseUrl` and `RecordJson.decode`; catch the specific exception instead.
  - Generic typealias arguments are not substituted in `TypeMapping`, and an alias to a nullable record inside `FlagRecords` slips through.
  - Lone surrogates encode differently in the schema and the export (`?` versus U+FFFD).
- **Unverified:** KSP cycle detection looks only at the current round's candidates. Resolve group targets with `getClassDeclarationByName`, so incremental builds cannot miss a cycle.
- **Silent failure:** a `RemoteOverrideSource` built with a different `KeyEncoding` than its pole resolves nothing, and nothing reports it. A schema-key comparison in `FlagPole` would catch this.
- **Stale text:** the comment at `FlagPoleFlowTest.kt` around line 277 is stale, and the `config/detekt/detekt.yml` header is still the yewki one.

## Test gaps (any time)

- **Fixtures:** add one central non-empty guard in `Fixtures.tsv/lines`. Several golden tests would pass on an empty fixture.
- **Comparator:** add a fuzz test for `AppleKeyOrder`. Also cover the invalid-UTF-8 edge cases (truncated 4-byte sequences, `F5+`, values above U+10FFFF), and pin the grapheme and final-sigma key-encoding cases.
- **Unit tests:**
  - `TypeMapping` `Unsupported` cases;
  - snapshots for nested, internal and all-default records;
  - a container nested in a private parent.
- **QR:** a 50,000,000-byte boundary test and a deflate-bomb test.
- **Exact wording:** pin the three reworded error messages exactly in `ErrorMessageTest`, and check REWORDED/DROPPED names against the directory.
- **`EnvironmentConfiguration`:** a throwing local loader, and a schema-rejected payload. Put `withTimeout` around the two-thread test's `join()`s.
- **`FlagMappingAuditTest`:** add the Double `-1e400` case.
- **Coverage map:** reproduce the Swift-test → Kotlin-class coverage checklist from M1d D7.4 in the repo.
