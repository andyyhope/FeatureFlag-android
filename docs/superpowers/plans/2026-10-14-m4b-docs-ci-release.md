# M4b Docs, CI and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze, describe and ship the library:
- a narrow pre-freeze API pass (B1);
- a LICENSE, Maven Central publishing through `featureflag.publish`, a BOM and a Dokka site, dry-run only (B2);
- committed ABI dumps that fail `check` on any public API change (B3);
- `:docs-samples`, whose `DocsSyncTest` keeps every doc snippet byte-identical to the compiled code, checked file or computed output it quotes (B4);
- the README and fifteen `docs/*.md` articles, ported from the iOS DocC catalogs (B5–B7);
- three GitHub Actions workflows, written and checked locally (B8).

**Architecture:**
- **B1–B3 change the build, not behaviour.** One rename (`applyPayload`), two deleted placeholders, five constants made `private` and one companion string corrected, then the publishing plugin and the ABI dumps, so the API is fixed before anything documents it.
- **The checker is pure Kotlin, tested on strings first.** `docs.sync.DocsSync` reads markdown and source files as `TextFile`s and returns problems; `DocsSyncCheckerTest` pins every failure mode on fixture strings, and only then does `DocsSyncTest` run it over the repository.
- **Every doc is written code-first.** Each docs task first adds the `docs.<doc>` package (declarations, regions, tests, outputs). `DocsSyncTest` then fails, naming every region and output no document shows yet, which is the red step; writing the markdown turns it green.
- **Nothing outward-facing.** Publishing goes to Maven repositories under `build/`; the workflows are linted, not run.

**Tech Stack:** As the M4 overview: Gradle 9.5.1, AGP 9.1.1 (built-in Kotlin), Kotlin 2.2.10, KSP 2.2.10-2.0.2, Compose BOM 2026.01.00, Robolectric 4.16 (SDK 36), detekt 1.23.8 with formatting. New: `com.vanniktech.maven.publish` 0.37.0, `org.jetbrains.dokka` 2.2.0, `org.jetbrains.kotlinx.binary-compatibility-validator` 0.18.2, `io.github.tjokinen.android-bcv-bridge` 0.2.0. GitHub Actions: checkout v7, setup-java v6, setup-gradle v6, upload-artifact v7, cache v6, android-emulator-runner v2.

**Contract:** [`2026-10-14-m4-overview.md`](2026-10-14-m4-overview.md). Every name, path, id and marker below is the contract's. Where this plan had to choose something the contract leaves open, or found the contract unworkable, it says so under "Contract issues for the controller" at the end.

**Spec:** [`docs/superpowers/specs/2026-10-08-android-parity-design.md`](../specs/2026-10-08-android-parity-design.md), "Modules", "Execution checklist → M4" and "Verification (end-to-end)".

**Inputs:** `docs/superpowers/notes/2026-10-14-m4-docs.md` (the iOS articles mapped to Kotlin, the differences list, the public API per module), `2026-10-14-m4-tooling.md`, the three spikes (`-spike-publish`, `-spike-abi`, `-spike-ksp`), and `2026-10-09-m1-followups.md`. The iOS articles are `../feature-flags/Sources/FeatureFlag/FeatureFlag.docc/*.md`, `Sources/FeatureFlagUI/FeatureFlagUI.docc/*.md` and `README.md` at tag 0.12.0.

## Global Constraints

- Everything in the M4 overview's Global Constraints applies, and through it the M1–M3 ones. In particular:
  - **Every Gradle command runs with** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, from the repository root. A fresh worktree needs `local.properties` with `sdk.dir=/Users/andyyhope/Library/Android/sdk`.
  - **No version bumps**, and the only new build dependencies are the four plugins above.
  - **Published modules are exactly seven:** `featureflag-core`, `-ksp`, `-test`, `-android`, `-bridge`, `-ui`, `-bom`. `docs-samples` and `samples/*` are never published, never ABI-checked and never in Dokka.
  - **Outward-facing steps are THE USER's.** No task pushes, tags, creates GitHub settings or secrets, or publishes anywhere but a Maven repository under `build/` (`-Dmaven.repo.local=…`). The real `~/.m2` is never written.
  - **After B3, a public API change updates the dumps** (`./gradlew apiDump releaseApiDump`) in the same commit, and the task report names it. No task after B1 is expected to change the public API.
  - Robolectric tests carry `@RunWith(AndroidJUnit4::class)` and `@Config(sdk = [36])`; plain JUnit tests carry neither. Test names are camelCase sentences.
- **Plan-specific:**
  - **`allWarningsAsErrors` and detekt hold for `docs-samples`.** Every snippet uses everything it declares: a `val` a snippet introduces is read by an assertion after the region. detekt's ktlint rules apply too: imports in ASCII order with `java`, `javax`, `kotlin` last and aliases at the very end; trailing commas on multi-line calls and declarations; an annotation with arguments on its own line; one space before an end-of-line comment, never aligned comments; no line over 120 columns; a file with one top-level class is named after it.
  - **No `/*` inside a comment.** Kotlin comments nest, so a KDoc that says `docs/*.md` never closes.
  - **A doc fence starts at column 0**, never inside a list item, and its marker is the line directly above it.
  - **Docs voice:** THE USER's iOS wording where the behaviour is the same, British spelling, plain statements of where Android differs. No links into `docs/superpowers/`.
- **Definition of done for each task:** the named tests pass, and `./gradlew build detekt` is green (allWarningsAsErrors, explicitApi in library modules, detekt with formatting, lint `No issues found.` in `featureflag-android`, `-bridge` and `-ui`). From B3, `build` runs the six ABI checks; from B4, it runs `:docs-samples:testDebugUnitTest`.
- **Commits:** imperative sentence case, no type prefix, no trailing period, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

The overview's Review Focus items this plan owns, two more, and the test or command that pins each:

1. **(Overview 3) A release-version dry run with no signing key.** `publishToMavenLocal -PVERSION_NAME=0.1.0` succeeds unsigned, writing every artifact and no `.asc`; the same with a throwaway key writes 32 `.asc` files. Pinned by B2 Steps 8 and 9.
2. **(Overview 4) Docs that drift from code.** `DocsSyncTest` fails on an unmarked fence, a changed region, an orphan region, a broken link or anchor, a mismatched output, and unbalanced markers. Pinned by B4's `DocsSyncCheckerTest` (37 tests over fixture strings, before any real doc exists): `anUnmarkedKotlinKtsXmlOrJsonFenceFails`, `aSnippetWhoseRegionChangedShowsADiffWithBothLocations`, `aRegionNobodyQuotesFails`, `aLinkToAMissingFileFails`, `aLinkToAMissingHeadingFails`, `anOutputThatNoLongerMatchesFails`, `anEndregionWithNoRegionFails`, `aRegionThatIsNeverClosedFails`, `aSnippetRegionInsideAnotherFails`, `aFenceInABlockquoteFails`, `aLinkTheCheckerCannotReadFails`, among others.
3. **(Overview 5) An ABI check that silently checks nothing.** Six committed dumps; `./gradlew check --dry-run` lists all six check tasks; a public function added to `featureflag-core` and to `featureflag-ui` fails `apiCheck` and `releaseApiCheck`. Pinned by B3 Steps 8–9, and by B8's guard step, which fails CI when any of the six is missing from `check`.
4. **A doc or region edited without changing any bytecode.** Editing one character of a fence, or only the spacing or a comment inside a region (which leaves the class files identical), must re-run `DocsSyncTest` (not leave it UP-TO-DATE or take it from the build cache) and fail it with a diff. Pinned by B5 Step 6, which relies on B4's task inputs: the markdown, `LICENSE`, `docs-samples/src`, the `samples` tree and the bridge manifest, as text.
5. **A POM that Maven Central would refuse.** Every published POM, the BOM's included, carries a name, a module-specific description, the URL, the MIT licence, the developer and the scm block. Pinned by B2 Step 8's counts (seven POMs with each field).

---

## Seams this plan relies on (from M4a)

M4b runs after M4a and changes none of M4a's files. These are the seams M4a's plan (`2026-10-14-m4a-samples.md`, "Seams later plans rely on" and its code blocks) produces and this plan reads; the controller checks them against M4a's ledger before B1.

**Build plumbing (A1):**
1. **`gradle.properties` has `android.disallowKotlinSourceSets=false`** and `featureflag.sample.hostId=com.andyyhope.featureflag.sample.host`. Without the first, `:docs-samples` fails at configuration, because it applies KSP to an Android module (spike-ksp).
2. **`featureflag.compose` can still be applied to an Android library.** `:docs-samples` is one and applies it as `featureflag-ui` does. A1 widens it to `CommonExtension` for the sample apps; B4 would work with or without that change.
3. **Projects named `signals`, `host` and `companion` exist** (`:samples:signals` from A1, `:samples:host` from A2, `:samples:companion` from A4). B3's BCV `ignoredProjects` names them, and BCV fails the build on a name that matches no project.
4. **The root `build.gradle.kts` and `settings.gradle.kts` keep their shape**: one `plugins { }` block of `alias(…)` lines (A1 adds `alias(libs.plugins.android.application) apply false`), and one `include(…)` line per project. B2–B4 add lines by position, never replacing A1's. `build-logic/build.gradle.kts` gains A1's `androidApplication` registration, which B2 Step 3 leaves in place.

**The ten regions M4a marks, all cited by M4a's `samples/README.md` (A4):**

| Region | File | Cited by this plan |
|---|---|---|
| `sample-app-signal` | `samples/signals/src/main/kotlin/com/andyyhope/featureflag/sample/signals/AppSignals.kt` | — |
| `sample-environment-flag` | `samples/host/src/main/kotlin/com/andyyhope/featureflag/sample/host/AppFlags.kt` | — |
| `sample-host-dependencies` | `samples/host/build.gradle.kts` | — |
| `sample-host-wiring` | `samples/host/src/main/kotlin/com/andyyhope/featureflag/sample/host/DemoApplication.kt` | — |
| `sample-host-environment` | `samples/host/src/main/kotlin/com/andyyhope/featureflag/sample/host/DemoModel.kt` | — |
| `sample-remote-mapper` | `samples/host/src/main/kotlin/com/andyyhope/featureflag/sample/host/DemoRemoteMapper.kt` | — |
| `sample-companion-dependencies` | `samples/companion/build.gradle.kts` | — |
| `sample-companion-uses-permission` | `samples/companion/src/main/AndroidManifest.xml` | `docs/TheBridge.md` (B6) |
| `sample-companion-tabs` | `samples/companion/src/main/kotlin/com/andyyhope/featureflag/sample/companion/CompanionActivity.kt` | — |
| `sample-companion-signing` | `samples/companion/build.gradle.kts` | `docs/TheBridge.md` (B6) |

5. **Those ten, and no others.** `DocsSyncTest` reads every `// region snippet:` and `<!-- region snippet: -->` under `samples/**/src/**` and `samples/**/build.gradle.kts`. Because `samples/README.md` cites all ten, none is an orphan from B4 on; a region M4a added and did not cite would fail B4 Step 7.
6. **The two regions `TheBridge.md` quotes, byte for byte** as M4a's A4 Step 2 writes them: `sample-companion-uses-permission` is the single line `<uses-permission android:name="${hostId}.permission.FEATURE_FLAG_BRIDGE" />`, and `sample-companion-signing` is the `signingConfigs { … }` block (the shared `debug` key and the `untrusted` key) followed by `buildTypes { create("untrusted") { … } }`, dedented from four spaces. If M4a's files change after its plan was written, B6's fences follow the files.
7. **`samples/README.md` passes `DocsSyncTest` as M4a writes it.** Checked in the scratch copy with M4a's README and sample sources copied from its plan: its ten `<!-- snippet: … -->` fences match their regions, its five `<!-- snippet-ignore: … -->` fences have reasons, its `sh` and unlabelled fences need no marker, and its links (`keys/README.md`, the files table) resolve. A5 later adds a screenshot table linking `screenshots/host.png` and `screenshots/companion.png`, which must exist when it does.
8. **The names the docs print:** the task `:samples:companion:installUntrusted`; the application ids `com.andyyhope.featureflag.sample.host`, `com.andyyhope.featureflag.sample.companion` and `com.andyyhope.featureflag.sample.companion.untrusted`; the debug-only keys in `samples/keys/`. `docs/TheBridge.md` names the property, the task and the host id; `docs/GettingStarted.md`, `docs/TheBridge.md` and `README.md` link to `samples/README.md`.

## Follow-ups for THE USER (from B1)

Overview Deviation 5 leaves these `notes/2026-10-09-m1-followups.md` items as they are. They change names the docs print, so they are THE USER's call before the first publish, and each would be a breaking change after it:
- `SnapshotSource(name)` versus `RemoteOverrideSource(sourceName)`;
- `keyEncoding` and `mapper` in different positions in `RemoteOverrideSource` and `EnvironmentConfiguration` constructors;
- the bytes parameter called `json` in some places and `payload` in others;
- three verbs for reading (`parse`, `decode`, `parseJson`);
- `KeyEncoding.Kebabcase`, `Snakecase` and `Verbatim` as PascalCase values;
- `FlagImportException.Malformed` taking no cause, and `FlagMappingAuditException` living in `.audit` rather than `.errors`;
- the minimum consumer Kotlin version (the docs state Kotlin 2.2), and whether `docs/superpowers/` ships in the public repository.

## How this plan was checked

Every code block below was written and run in a scratch copy of the worktree at `ba4b29b` (`git archive HEAD`), with B1–B8 applied in order, and the generated plan embeds those files byte for byte.
- **Gate.** `./gradlew build detekt`: `BUILD SUCCESSFUL`, 354 tasks, with the six ABI checks and `:docs-samples:testDebugUnitTest` in it; lint `No issues found.` in all three Android library modules and in `docs-samples`. The scratch copy's `apiValidation` ignored only `featureflag-bom` and `docs-samples`, because the samples are not in its `settings.gradle.kts`. B3's literal list, with `signals`, `host` and `companion`, was therefore not run here; B3 Step 2 checks the three projects exist before it is written.
- **B1.** `FlagPoleTransportTest` 33 tests (32 before); `SignalsScreenTest` 17 tests with the new footer; `featureflag-ui` lint, which reported one `ModifierParameter` warning before, reports none.
- **B2.** `publishToMavenLocal -PVERSION_NAME=0.1.0` into `build/m2-unsigned`: 7 POMs, each with its own description, every jar or aar with `-sources` and `-javadoc` jars, the BOM as `.pom` and `.module` only, no `.asc`, and `~/.m2/repository/com/andyyhope` never created. With the throwaway key: 32 `.asc` files. `:dokkaGeneratePublicationHtml` writes `build/dokka/html/featureflag-{android,bridge,core,test,ui}`.
- **B3.** `apiDump releaseApiDump` writes six dumps; none holds `ATTEMPTS`, `HEX_DIGITS`, `SCHEMA_PATH`, `DECODE_ATTEMPTS` or `MAX_CANCELLATIONS`, and with the fifteen classes ignored `featureflag-ui.api` has no `ComposableSingletons`. `check --dry-run` lists the six tasks the B8 guard looks for. BCV given an ignored project that does not exist fails with `Cannot find excluded project signals in all projects`, which is why B3 Step 2 checks the samples exist and `docs-samples` is ignored from B4 (Contract issue 1).
- **B4–B7.** `:docs-samples:testDebugUnitTest`: 126 tests, `DocsSyncCheckerTest` 37 of them. Changing one character in a fence of `docs/FlagValues.md` re-ran the task and failed `DocsSyncTest` with the diff; so did adding a space inside the `values-enum` region, which left the compiled classes byte-identical. B5 Step 6 repeats both. The per-task states (B5 and B6 with their forward links as plain text) were checked mechanically, not built: the generator confirmed that at each task every cited region and output exists, every region and output is cited, and every link resolves.
- **B8.** The three workflows parse as YAML (`actionlint` is not installed here), and the guard step's loop, run locally, finds all six tasks. The emulator, cache and publish steps have not run anywhere.
- **M4a's files.** Every `samples/` file M4a's plan writes in full (its A4 versions; A5's screenshot table excepted) was copied from that plan into the scratch copy, so `DocsSyncTest` ran over M4a's real `samples/README.md` and its ten regions, and `TheBridge.md`'s two citations of them. They were scanned, not built: the samples are not in the scratch copy's `settings.gradle.kts`.
- **Not checked here:** M4a's files as M4a's implementer actually writes them, and the workflows on GitHub, whose first run is the M4 pull request.

## Files this plan creates or changes

```
LICENSE                                                         (B2)
gradle/libs.versions.toml                                       (B2, B3)
build-logic/build.gradle.kts                                    (B2)
build-logic/src/main/kotlin/PublishConventionPlugin.kt          (B2)
settings.gradle.kts                                             (B2, B4)
build.gradle.kts                                                (B2, B3, B4)
featureflag-bom/build.gradle.kts, gradle.properties             (B2)
featureflag-{core,ksp,test,android,bridge,ui}/build.gradle.kts  (B2; B3 for android, bridge, ui)
featureflag-{core,ksp,test,android,bridge,ui}/gradle.properties (B2)
featureflag-{core,ksp,test,android,bridge,ui}/api/*.api         (B3)
featureflag-core/src/main/.../transport/FlagPoleTransport.kt    (B1)
featureflag-core/src/main/.../FeatureFlag.kt                    (B1, deleted)
featureflag-core/src/test/.../SmokeTest.kt                      (B1)
featureflag-core/src/test/.../transport/FlagPoleTransportTest.kt (B1)
featureflag-test/src/main/.../TestFeatureFlags.kt               (B1, deleted)
featureflag-test/src/test/.../SmokeTest.kt                      (B1, deleted)
featureflag-android/src/main/.../bridge/FlagBridgeClient.kt     (B1)
featureflag-bridge/src/main/.../FlagBridgeProvider.kt           (B1)
featureflag-ui/src/main/.../screens/FlagTextField.kt            (B1)
featureflag-ui/src/main/.../FlagStrings.kt                      (B1)
featureflag-ui/src/test/.../screens/SignalsScreenTest.kt         (B1)
docs-samples/build.gradle.kts                                   (B4)
docs-samples/src/test/kotlin/docs/DocOutputs.kt                 (B4; B5, B6, B7 replace it)
docs-samples/src/test/kotlin/docs/sync/                         (B4)
  MarkdownDocument.kt RegionIndex.kt LineDiff.kt DocsSync.kt DocsRepository.kt
  DocsSyncCheckerTest.kt DocsSyncTest.kt
docs-samples/src/test/kotlin/docs/<doc>/                        (B5: declaringflags flagvalues recordflags
                                                                  remoteoverrides validatingaconfig;
                                                                 B6: sourcesandprecedence observingchanges
                                                                  sharingwithacompanionapp thebridge
                                                                  exportingandimporting;
                                                                 B7: sendingsignals buildingacompanionapp
                                                                  gettingstarted troubleshooting readme)
docs-samples/src/test/resources/                                (B5: staging.json; B6: docs/thebridge/;
                                                                 B7: docs/gettingstarted/, docs/buildingacompanionapp/)
docs/*.md, README.md                                            (B5–B7)
.github/workflows/check.yml instrumented.yml release.yml        (B8)
```

### Which doc is in which task

The overview leaves the split open. This one keeps each task's docs mostly linking backwards, since `DocsSyncTest` fails on a link to a file that does not exist yet:

| Task | Docs | Packages |
|---|---|---|
| B5 | DeclaringFlags, FlagValues, RecordFlags, RemoteOverrides, ValidatingAConfig | `docs.declaringflags`, `.flagvalues`, `.recordflags`, `.remoteoverrides`, `.validatingaconfig` |
| B6 | SourcesAndPrecedence, ObservingChanges, SharingWithACompanionApp, TheBridge, ExportingAndImporting | `docs.sourcesandprecedence`, `.observingchanges`, `.sharingwithacompanionapp`, `.thebridge`, `.exportingandimporting` |
| B7 | SendingSignals, BuildingACompanionApp, GettingStarted, Troubleshooting, DifferencesFromIOS, README | `docs.sendingsignals`, `.buildingacompanionapp`, `.gettingstarted`, `.troubleshooting`, `.readme` |

The eight links that point forward are written as plain text in their first version, and B6 and B7 each have a step that turns them into links once the target exists.

### Conventions every doc task follows

- **Markers.** `<!-- snippet: name -->` above a fence quotes the region `name` from `docs-samples/src/**` or `samples/**`; `<!-- output: name -->` quotes the string `docs.DocOutputs.all[name]` returns; `<!-- snippet-ignore: reason -->` is never used here. A `text`, `sh`, `bash`, `console` or unlabelled fence needs no marker, which is how KSP and `connect()` messages that cannot be computed in a test are quoted.
- **Regions** are `// region snippet: name` … `// endregion` in `.kt` and `.kts`, and `<!-- region snippet: name -->` … `<!-- endregion -->` in `.xml`. A region inside a test function is dedented when compared, so the fence shows it at column 0.
- **JSON** cannot hold a region marker, so a JSON payload is a Kotlin constant the test applies, registered in `DocOutputs`, and quoted with `output:`.
- **Gradle and manifest snippets** a reader copies (`com.example.app`, version `0.1.0`) live in `docs-samples/src/test/resources/docs/<doc>/`: not compiled, but scanned, version-checked by `DocsSync`, and checked by the doc's test (the XML is parsed and compared with `FlagBridgeContract` and with `featureflag-bridge`'s own manifest).
- **Each package owns its declarations.** `AppFlags` is declared once per package, so the generated `AppFlagsSpec`s never clash. A doc may quote another doc's region or output — `RemoteOverrides.md` shows `RecordFlags.md`'s endpoints payload, `Troubleshooting.md` and `README.md` quote several — and the region stays where it was first written.

---

### Task B1: The pre-freeze API pass

**Files:**
- Modify: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`
- Modify: `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransportTest.kt`
- Delete: `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/FeatureFlag.kt`
- Delete: `featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/TestFeatureFlags.kt`
- Delete: `featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/SmokeTest.kt`
- Modify (whole file): `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/SmokeTest.kt`
- Modify: `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClient.kt`
- Modify: `featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt`
- Modify: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagTextField.kt`
- Modify: `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagStrings.kt`
- Modify: `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreenTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public fun FlagPole<*>.applyPayload(payload: FlagPayload)` in `com.andyyhope.featureflag.transport`, replacing `apply(payload)`; `importJson` and `importQRCode` call it. `com.andyyhope.featureflag.FeatureFlag` and `com.andyyhope.featureflag.test.TestFeatureFlags` no longer exist. `featureflag-test`'s public API is then `assertFlagsFullyMapped` alone, which is what it is for, so the module stays. The companion's Signals footer for an instant signal reads "Sent the moment you tap. If the app isn't running, Android starts it to deliver the signal."

The five constants made `private` are the ones BCV's dumps show as public (spike-abi): `const val`s in a `private companion object` compile to `public static final` fields on the outer, public class. `FlagBridgeContract`'s, `FlagSchema`'s, `FlagPayload`'s, `FlagQRCode`'s and `FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD` are public on purpose and stay.

- [ ] **Step 1: Write the failing test**

**Modify** `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransportTest.kt`: insert this section directly above the line `    // Import used to apply part of a payload`. The file's package already holds `FlagPayload` and `applyPayload`, so it needs no import.

```kotlin
    // Applying a decoded payload

    @Test
    fun applyPayloadWritesEveryValueAsAnOverride() {
        val pole = goldenPole()
        val json = document("""{"formatVersion":1,"values":{"bool-flag":true,"int-flag":3}}""")

        pole.applyPayload(FlagPayload.decode(json, pole.schema))

        val expected = mapOf(boolFlag to FlagValueBox.Bool(true), intFlag to FlagValueBox.Int(3))
        assertEquals(expected, pole.exportPayload().values)
    }

```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests '*.FlagPoleTransportTest'
```

Expected: `BUILD FAILED`, `compileTestKotlin` reporting `Unresolved reference 'applyPayload'`.

- [ ] **Step 3: Rename `apply(payload)` to `applyPayload(payload)`**

**Modify** `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt`. Replace

```kotlin
/**
 * Applies an already-decoded payload as overrides in the top mutable source.
 *
```

with

```kotlin
/**
 * Applies an already-decoded payload as overrides in the top mutable source, as [importJson]
 * and [importQRCode] do once they have decoded one. It is not called `apply`, which reads as
 * the standard library's scope function and as `RemoteOverrideSource.apply`, which replaces a
 * remote layer rather than writing overrides.
 *
```

then replace `public fun FlagPole<*>.apply(payload: FlagPayload) {` with `public fun FlagPole<*>.applyPayload(payload: FlagPayload) {`, and in both `importJson` and `importQRCode` replace the line `    apply(payload)` with `    applyPayload(payload)`. Nothing else in the repository calls the pole's `apply`; `RemoteOverrideSource.apply` is a different function and keeps its name.

- [ ] **Step 4: Delete the placeholders and their smoke tests**

```bash
git rm featureflag-core/src/main/kotlin/com/andyyhope/featureflag/FeatureFlag.kt \
  featureflag-test/src/main/kotlin/com/andyyhope/featureflag/test/TestFeatureFlags.kt \
  featureflag-test/src/test/kotlin/com/andyyhope/featureflag/test/SmokeTest.kt
```

Core's `SmokeTest` also checks that the fixtures reach the tests and that the coroutine test tooling is on the classpath, which are worth keeping. **Replace** `featureflag-core/src/test/kotlin/com/andyyhope/featureflag/SmokeTest.kt` with:

```kotlin
package com.andyyhope.featureflag

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SmokeTest {
    @Test
    fun fixturesPathIsPassedToTests() {
        assertNotNull(System.getProperty("featureflag.fixtures"))
    }

    @Test
    fun coroutineTestToolingIsOnTheClasspath() = runTest {
        flowOf("ready").test {
            assertEquals("ready", awaitItem())
            awaitComplete()
        }
    }
}
```

`featureflag-test` keeps `AssertFlagsFullyMappedTest`, so its `test` task still runs tests.

- [ ] **Step 5: Make the accidentally public constants private**

**Modify** `featureflag-android/src/main/kotlin/com/andyyhope/featureflag/android/bridge/FlagBridgeClient.kt`, in its `private companion object`: `        const val ATTEMPTS = 2` becomes `        private const val ATTEMPTS = 2`, and `        const val HEX_DIGITS = "0123456789ABCDEF"` becomes `        private const val HEX_DIGITS = "0123456789ABCDEF"`.

**Modify** `featureflag-bridge/src/main/kotlin/com/andyyhope/featureflag/bridge/FlagBridgeProvider.kt`, in its `private companion object`: add `private ` before `const val` on the three lines `const val SCHEMA_PATH = "/schema"`, `const val DECODE_ATTEMPTS = 2` and `const val MAX_CANCELLATIONS = 64`.

The outer classes reach their companion's private members, so nothing else changes. Confirm nothing else has the same shape:

```bash
grep -rn -A16 -E "(private|internal) companion object" --include='*.kt' featureflag-*/src/main \
  | grep -E -- "-[0-9]+-[[:space:]]+const val"
```

Expected: no output.

- [ ] **Step 6: Clear the `ModifierParameter` lint warning**

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/screens/FlagTextField.kt`. In `private fun FlagCodeBlock(…)` swap the last two parameters, so the first `Modifier` parameter is the one called `modifier`:

```kotlin
    keyboardOptions: KeyboardOptions,
    fieldModifier: Modifier,
    modifier: Modifier,
) {
```

becomes

```kotlin
    keyboardOptions: KeyboardOptions,
    modifier: Modifier,
    fieldModifier: Modifier,
) {
```

The only call passes both by name, so it is unchanged.

- [ ] **Step 7: Correct the Signals footer**

The footer for an instant signal was ported from iOS, where a closed app cannot hear a signal. On Android a signal is a call to the host's bridge provider, and Android starts a closed host to answer it (overview Deviation 5; `CrossProcessBridgeTest.aSignalToAColdHostWaitsForItsApplicationToStart`). `docs/SendingSignals.md` (B7) says so, so the companion must not say the opposite.

**Modify** `featureflag-ui/src/test/kotlin/com/andyyhope/featureflag/ui/screens/SignalsScreenTest.kt`. In `theDelayFooterFollowsTheChoice`, replace the line

```kotlin
            "Sent the moment you tap. The app has to be running to hear it, and opening this one puts it in the background.",
```

with

```kotlin
            "Sent the moment you tap. If the app isn't running, Android starts it to deliver the signal.",
```

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-ui:testDebugUnitTest --tests '*.SignalsScreenTest'
```

Expected: `BUILD FAILED`, `theDelayFooterFollowsTheChoice` failing because no node has the new text.

**Modify** `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/FlagStrings.kt`. Under `// Signals`, replace

```kotlin
    const val DELAY_FOOTER_INSTANT =
        "Sent the moment you tap. The app has to be running to hear it, and opening this one puts it in the background."
```

with

```kotlin
    const val DELAY_FOOTER_INSTANT =
        "Sent the moment you tap. If the app isn't running, Android starts it to deliver the signal."
```

Run the same command again. Expected: `BUILD SUCCESSFUL`, `SignalsScreenTest` 17 tests, none failing. `DELAY_FOOTER_DELAYED` is unchanged: it tells the reader to switch to the app, which is still the way to see a delayed signal land.

- [ ] **Step 8: Run the tests and the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:test --tests '*.FlagPoleTransportTest' --tests '*.SmokeTest'
./gradlew build detekt
grep -rnE "FeatureFlag\.VERSION|TestFeatureFlags" --include='*.kt' . | grep -v /build/
grep -n "apply(payload)" featureflag-core/src/main/kotlin/com/andyyhope/featureflag/transport/FlagPoleTransport.kt
grep -rn "has to be running" --include='*.kt' featureflag-ui/src
cat featureflag-ui/build/reports/lint-results-debug.txt
```

Expected: `BUILD SUCCESSFUL` both times; `TEST-com.andyyhope.featureflag.transport.FlagPoleTransportTest.xml` shows `tests="33" … failures="0" errors="0"` and `TEST-com.andyyhope.featureflag.SmokeTest.xml` `tests="2"`; the three `grep`s print nothing (`applyPayload(payload)` does not contain `apply(payload)`); the lint report is `No issues found.`

- [ ] **Step 9: Commit**

```bash
git add -A featureflag-core featureflag-test featureflag-android/src featureflag-bridge/src featureflag-ui/src
git commit -m "Rename FlagPole.apply(payload) to applyPayload and drop the scaffolding placeholders before the API freezes" -m "Five constants in private companions compiled to public fields, so they are private now. FlagCodeBlock's modifier comes first, which clears the last lint warning. The Signals footer no longer says the app has to be running, which is untrue on Android: a signal starts a closed host." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

The task report repeats the "Follow-ups for THE USER" list above.

---

### Task B2: LICENSE, `featureflag.publish`, the BOM and Dokka

**Files:**
- Create: `LICENSE`
- Modify: `gradle/libs.versions.toml`
- Modify: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/PublishConventionPlugin.kt`
- Create: `featureflag-{core,ksp,test,android,bridge,ui,bom}/gradle.properties`
- Modify: `featureflag-{core,ksp,test,android,bridge,ui}/build.gradle.kts`
- Create: `featureflag-bom/build.gradle.kts`
- Modify: `settings.gradle.kts`, `build.gradle.kts`

**Interfaces:**
- Consumes: `GROUP=com.andyyhope.featureflag` and `VERSION_NAME=0.1.0-SNAPSHOT` from `gradle.properties` (unchanged).
- Produces: the plugin id `featureflag.publish` (class `PublishConventionPlugin`), applied last in each published module; the project `:featureflag-bom`; the root task `:dokkaGeneratePublicationHtml`; per module, vanniktech's `publishToMavenLocal` and `publishToMavenCentral`. Signing is on only when the Gradle property `signingInMemoryKey` is set, as `ORG_GRADLE_PROJECT_signingInMemoryKey` in B8's release workflow.

This is spike-publish's verified setup with three changes: `publishToMavenCentral()` is called, each module's description comes from its own `gradle.properties` (`POM_DESCRIPTION`, which vanniktech reads; verified, see Step 8), and `featureflag-ksp` is left out of the Dokka aggregate as the Contract says. It still publishes its own javadoc jar.

- [ ] **Step 1: Add the licence**

**Create** `LICENSE`:

```text
MIT License

Copyright (c) 2026 Andy Hope

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 2: Add the plugins to the catalog**

**Modify** `gradle/libs.versions.toml`. In `[versions]`, after `zxing = "3.5.4"`, add:

```toml
# Publishing (featureflag.publish): Maven Central through vanniktech, javadoc jars and the docs site from Dokka.
vanniktechPublish = "0.37.0"
dokka = "2.2.0"
```

In `[libraries]`, after the `compose-compiler-gradle-plugin` line, add:

```toml
vanniktech-publish-plugin = { module = "com.vanniktech:gradle-maven-publish-plugin", version.ref = "vanniktechPublish" }
dokka-gradle-plugin = { module = "org.jetbrains.dokka:dokka-gradle-plugin", version.ref = "dokka" }
```

At the end of `[plugins]`, add:

```toml
vanniktech-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechPublish" }
dokka = { id = "org.jetbrains.dokka", version.ref = "dokka" }
```

- [ ] **Step 3: Put them on build-logic's classpath and register the plugin**

**Modify** `build-logic/build.gradle.kts`. In `dependencies { }`, after `    implementation(libs.compose.compiler.gradle.plugin)`, add:

```kotlin
    implementation(libs.vanniktech.publish.plugin)
    implementation(libs.dokka.gradle.plugin)
```

In `gradlePlugin { plugins { } }`, after the `register("fixtures") { … }` block, add (A1's `featureflag.android-application` registration, wherever it is, stays as it is):

```kotlin
        register("publish") {
            id = "featureflag.publish"
            implementationClass = "PublishConventionPlugin"
        }
```

**Create** `build-logic/src/main/kotlin/PublishConventionPlugin.kt`:

```kotlin
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `featureflag.publish` — Maven Central publishing (vanniktech) and a Dokka javadoc jar for one module.
 *
 * Apply it last in a module's `plugins {}`, so Dokka sees the Android or Kotlin plugin first. The
 * coordinates are `GROUP`, the project name and `VERSION_NAME`; the module's own `gradle.properties`
 * gives its `POM_DESCRIPTION`. Publications are signed only when the `signingInMemoryKey` Gradle
 * property is supplied, so a release-version `publishToMavenLocal` dry run needs no key. A
 * `java-platform` project (the BOM) has no sources, so it gets no Dokka.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        if (!pluginManager.hasPlugin("java-platform")) {
            pluginManager.apply("org.jetbrains.dokka")
        }
        pluginManager.apply("com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            pom {
                name.set(project.name)
                inceptionYear.set("2026")
                url.set(REPO_URL)
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("andyyhope")
                        name.set("Andy Hope")
                        url.set("https://github.com/andyyhope/")
                    }
                }
                scm {
                    url.set(REPO_URL)
                    connection.set("scm:git:git://github.com/andyyhope/FeatureFlag-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/andyyhope/FeatureFlag-android.git")
                }
            }
        }
    }

    private companion object {
        const val REPO_URL = "https://github.com/andyyhope/FeatureFlag-android"
    }
}
```

- [ ] **Step 4: Give each module a description and apply the plugin last**

**Create** these seven files, one line each:

`featureflag-core/gradle.properties`
```properties
POM_DESCRIPTION=Feature flags declared as Kotlin interfaces: the pole, its sources, remote overrides, the schema, export and import, and the mapping audit. Plain JVM.
```

`featureflag-ksp/gradle.properties`
```properties
POM_DESCRIPTION=The KSP processor that generates the spec of every @FlagContainer and the codec of every @FlagRecord.
```

`featureflag-test/gradle.properties`
```properties
POM_DESCRIPTION=Test support for feature flags: assertFlagsFullyMapped checks that a remote config wires up every flag.
```

`featureflag-android/gradle.properties`
```properties
POM_DESCRIPTION=Android support for feature flags: SharedPreferencesSource, publishSchema(context), the signal channel and the companion's bridge client.
```

`featureflag-bridge/gradle.properties`
```properties
POM_DESCRIPTION=The exported ContentProvider that lets a companion app signed with the same key read and edit the host app's flags.
```

`featureflag-ui/gradle.properties`
```properties
POM_DESCRIPTION=A Jetpack Compose companion app that edits another app's flags from the schema it publishes.
```

`featureflag-bom/gradle.properties`
```properties
POM_DESCRIPTION=Aligns the versions of every FeatureFlag artifact.
```

**Modify** each library module's `plugins { }` block so that `id("featureflag.publish")` is its **last** line (Dokka has to see the Android or Kotlin plugin first):
- `featureflag-core/build.gradle.kts`: after `    alias(libs.plugins.ksp)`;
- `featureflag-ksp/build.gradle.kts` and `featureflag-test/build.gradle.kts`: after `    id("featureflag.jvm-library")`;
- `featureflag-android/build.gradle.kts` and `featureflag-bridge/build.gradle.kts`: after `    id("featureflag.android-library")`;
- `featureflag-ui/build.gradle.kts`: after `    id("featureflag.compose")`.

Each gains exactly this line:

```kotlin
    id("featureflag.publish")
```

- [ ] **Step 5: Add the BOM**

**Create** `featureflag-bom/build.gradle.kts`:

```kotlin
plugins {
    `java-platform`
    id("featureflag.publish")
}

// One version for every artifact: implementation(platform("com.andyyhope.featureflag:featureflag-bom:<version>")).
dependencies {
    constraints {
        api(project(":featureflag-core"))
        api(project(":featureflag-ksp"))
        api(project(":featureflag-test"))
        api(project(":featureflag-android"))
        api(project(":featureflag-bridge"))
        api(project(":featureflag-ui"))
    }
}
```

**Modify** `settings.gradle.kts`: after `include(":featureflag-ui")`, add `include(":featureflag-bom")`.

- [ ] **Step 6: Declare the plugins at the root and aggregate the docs**

**Modify** `build.gradle.kts`. At the end of the `plugins { }` block (after its last `alias(…)` line), add:

```kotlin
    alias(libs.plugins.vanniktech.publish) apply false
    // Applied, not apply-false: the root aggregates the modules' API docs into one site.
    alias(libs.plugins.dokka)
```

Directly after the `plugins { }` block's closing brace, add:

```kotlin

// The docs site: ./gradlew :dokkaGeneratePublicationHtml, into build/dokka/html. featureflag-ksp is left out,
// because no app calls it; it still publishes its own javadoc jar.
dependencies {
    dokka(project(":featureflag-core"))
    dokka(project(":featureflag-test"))
    dokka(project(":featureflag-android"))
    dokka(project(":featureflag-bridge"))
    dokka(project(":featureflag-ui"))
}
```

- [ ] **Step 7: Run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL`. The root `detekt` now also reads `PublishConventionPlugin.kt` and `featureflag-bom/build.gradle.kts`.

- [ ] **Step 8: Dry-run a release version, unsigned**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0 -Dmaven.repo.local="$PWD/build/m2-unsigned"
find build/m2-unsigned/com/andyyhope/featureflag -type f -path '*/0.1.0/*' | sort
find build/m2-unsigned -name '*.asc' | wc -l
for field in '<description>' '<url>https://github.com/andyyhope/FeatureFlag-android</url>' '<name>The MIT License</name>' '<id>andyyhope</id>' '<connection>'; do
  echo "$field $(grep -l "$field" build/m2-unsigned/com/andyyhope/featureflag/*/0.1.0/*.pom | wc -l)"
done
grep -h '<description>' build/m2-unsigned/com/andyyhope/featureflag/*/0.1.0/*.pom
ls ~/.m2/repository/com/andyyhope 2>&1
```

Expected:
- `BUILD SUCCESSFUL`, with no key anywhere.
- 32 files: for `featureflag-core`, `-ksp` and `-test` a `.jar`, `-sources.jar`, `-javadoc.jar`, `.pom` and `.module`; for `-android`, `-bridge` and `-ui` the same with an `.aar`; for `featureflag-bom` only `.pom` and `.module`.
- `0` `.asc` files.
- Each of the five fields counted `7`, and seven different descriptions, the ones from Step 4.
- `ls: …/com/andyyhope: No such file or directory`: the real `~/.m2` is untouched.

If a POM has no `<description>`, vanniktech did not read the module's `gradle.properties`: stop and report it rather than setting descriptions another way.

- [ ] **Step 9: Dry-run it signed, with a throwaway key**

`gpg` is not installed here, so the key comes from Gradle's own BouncyCastle jars (spike-publish). Everything stays under `build/`, which git ignores. **Create** `build/signing-check/ThrowawayKey.java` (not committed):

```java
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Date;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;

/** Writes a throwaway armored PGP secret key, passphrase "throwaway", to args[0]. For checking signing only. */
public class ThrowawayKey {
    public static void main(String[] args) throws Exception {
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
        generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12));
        PGPKeyPair pair = new BcPGPKeyPair(PublicKeyPacket.VERSION_4, PGPPublicKey.RSA_GENERAL, generator.generateKeyPair(), new Date());
        PGPDigestCalculator sha1 = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PGPKeyRingGenerator ring = new PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION, pair, "Throwaway <throwaway@example.invalid>", sha1, null, null,
            new BcPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
            new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1).build("throwaway".toCharArray()));
        try (ArmoredOutputStream out = new ArmoredOutputStream(new FileOutputStream(args[0]))) {
            ring.generateSecretKeyRing().encode(out);
        }
    }
}
```

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
BC=$(find ~/.gradle/wrapper/dists/gradle-9.5.1-bin -path '*/lib/plugins/*' -name 'bc*-jdk18on-*.jar' | tr '\n' ':')
"$JAVA_HOME/bin/java" -cp "$BC" build/signing-check/ThrowawayKey.java build/signing-check/key.asc
ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat build/signing-check/key.asc)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=throwaway \
  ./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0 -Dmaven.repo.local="$PWD/build/m2-signed"
find build/m2-signed/com/andyyhope/featureflag -name '*.asc' | wc -l
```

Expected: the key file starts `-----BEGIN PGP PRIVATE KEY BLOCK-----`; `BUILD SUCCESSFUL`; `32` (the pom, module, jar or aar, sources and javadoc of six modules, and the BOM's pom and module).

- [ ] **Step 10: Build the docs site**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :dokkaGeneratePublicationHtml
ls build/dokka/html
```

Expected: `BUILD SUCCESSFUL` with no Dokka warning (the one `android.disallowKotlinSourceSets` warning is Gradle's, and expected), and `featureflag-android  featureflag-bridge  featureflag-core  featureflag-test  featureflag-ui` among the entries, without `featureflag-ksp`.

- [ ] **Step 11: Commit**

```bash
git add LICENSE gradle/libs.versions.toml build-logic settings.gradle.kts build.gradle.kts featureflag-bom \
  featureflag-core/build.gradle.kts featureflag-core/gradle.properties featureflag-ksp/build.gradle.kts featureflag-ksp/gradle.properties \
  featureflag-test/build.gradle.kts featureflag-test/gradle.properties featureflag-android/build.gradle.kts featureflag-android/gradle.properties \
  featureflag-bridge/build.gradle.kts featureflag-bridge/gradle.properties featureflag-ui/build.gradle.kts featureflag-ui/gradle.properties
git commit -m "Prepare Maven Central publishing with a BOM and a Dokka site, signing only when a key is given" -m "A release-version publishToMavenLocal works unsigned, so a dry run needs no secrets." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B3: ABI dumps

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `featureflag-android/build.gradle.kts`, `featureflag-bridge/build.gradle.kts`, `featureflag-ui/build.gradle.kts`
- Create: `featureflag-{core,ksp,test,android,bridge,ui}/api/featureflag-*.api` (generated)

**Interfaces:**
- Consumes: B1's API, B2's root `plugins { }` block.
- Produces: the tasks `:featureflag-core:apiCheck`, `:featureflag-ksp:apiCheck`, `:featureflag-test:apiCheck`, `:featureflag-android:releaseApiCheck`, `:featureflag-bridge:releaseApiCheck`, `:featureflag-ui:releaseApiCheck`, all run by `check`, which is exactly the list B8's guard step looks for; and the update command `./gradlew apiDump releaseApiDump`.

This is spike-abi's verified setup. BCV applied at the root configures the JVM modules; `android-bcv-bridge`, applied in the three Android modules, registers the same tasks against AGP 9's built-in Kotlin, which BCV alone silently skips.

- [ ] **Step 1: Add the plugins to the catalog**

**Modify** `gradle/libs.versions.toml`. In `[versions]`, after `dokka = "2.2.0"`, add:

```toml
# ABI dumps (B3): BCV for the JVM modules; the bridge plugin runs BCV's tasks on AGP 9's built-in Kotlin, using
# BCV internals, so the pair is pinned exactly.
binaryCompatibilityValidator = "0.18.2"
androidBcvBridge = "0.2.0"
```

At the end of `[plugins]`, add:

```toml
binary-compatibility-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "binaryCompatibilityValidator" }
android-bcv-bridge = { id = "io.github.tjokinen.android-bcv-bridge", version.ref = "androidBcvBridge" }
```

- [ ] **Step 2: Check that M4a's three sample projects exist**

BCV fails the whole build at configuration on an ignored project that matches no project (`Cannot find excluded project signals in all projects`), and Step 3 ignores `signals`, `host` and `companion` by name.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew projects | grep -E "':samples:(signals|host|companion)'"
```

Expected: three lines, naming `':samples:signals'`, `':samples:host'` and `':samples:companion'`. If any is missing, stop: M4a has not merged (Seam 3), or a sample was renamed. Report it to the controller rather than leaving a name out of `ignoredProjects`, which would ABI-check a sample.

- [ ] **Step 3: Apply BCV at the root, ignoring what is not published**

**Modify** `build.gradle.kts`. At the end of the `plugins { }` block, add:

```kotlin
    // Applied, not apply-false: BCV configures the JVM modules' apiDump and apiCheck from the root.
    alias(libs.plugins.binary.compatibility.validator)
    // The Android modules apply it themselves; declaring it here loads it in the root classloader beside BCV.
    alias(libs.plugins.android.bcv.bridge) apply false
```

After the `dependencies { dokka(…) }` block B2 added, add:

```kotlin

// The published API, frozen in featureflag-*/api/*.api. Update it on purpose, with the change that needs it:
// ./gradlew apiDump releaseApiDump
apiValidation {
    // Nothing published: the BOM has no classes, and the samples are apps and a sample-only library.
    ignoredProjects.addAll(listOf("featureflag-bom", "signals", "host", "companion"))
}
```

`docs-samples` does not exist yet, and BCV fails on an ignored project that matches nothing (Step 2), so B4 adds it.

- [ ] **Step 4: Apply the bridge plugin in the Android modules**

**Modify** `featureflag-android/build.gradle.kts`, `featureflag-bridge/build.gradle.kts` and `featureflag-ui/build.gradle.kts`: in each `plugins { }` block, directly above `    id("featureflag.publish")` (which stays last), add:

```kotlin
    alias(libs.plugins.android.bcv.bridge)
```

- [ ] **Step 5: Dump, and read the dumps**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew apiDump releaseApiDump
ls featureflag-*/api/
grep -n "static final field [A-Z_]* " featureflag-*/api/*.api | grep -v "INSTANCE\|Companion"
grep -o 'ComposableSingletons\$[A-Za-z]*Kt' featureflag-ui/api/featureflag-ui.api | sort -u | sed 's/ComposableSingletons\$//'
```

Expected:
- `BUILD SUCCESSFUL` and one `.api` file in each of the six `api/` directories.
- The constants are all ones that are public on purpose: `FlagBridgeContract`'s `KEY_*`, `METHOD_*`, `STATUS_*`, `MAX_PAYLOAD_BYTES`, `PREFERENCES_NAME`, `PROTOCOL` and `SCHEMA_DIRECTORY`; `FlagSchema`'s and `FlagPayload`'s `CURRENT_FORMAT_VERSION`; `FlagSchema.FILE_NAME`; `FlagQRCode`'s `MAX_ENCODED_LENGTH` and `PREFIX`; `FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD`. If anything else appears (B1 should have removed `ATTEMPTS`, `HEX_DIGITS`, `SCHEMA_PATH`, `DECODE_ATTEMPTS`, `MAX_CANCELLATIONS`), make it `private` or `internal` as B1 Step 5 did, re-run this step, and name it in the task report.
- The last command lists the Compose singleton holders, which are internal in Kotlin but public in bytecode, and are renamed whenever a screen's lambdas change. At `ba4b29b` plus B1 they are these fifteen: `BrowserScreenKt`, `CompanionShellKt`, `DetailScreenKt`, `FlagControlsKt`, `FlagRowKt`, `FlagSectionHeaderKt`, `FlagSheetKt`, `FlagTextFieldKt`, `ImportSheetKt`, `ListEditorScreenKt`, `OverridesScreenKt`, `QrSheetKt`, `RecordsScreenKt`, `SignalsScreenKt`, `StatusScreensKt`.

- [ ] **Step 6: Ignore every Compose singleton holder**

**Modify** `build.gradle.kts`: inside `apiValidation { }`, after the `ignoredProjects` line, add this, with the names Step 5 printed (the fifteen below, unless Step 5 printed different ones, in which case use Step 5's and say so in the task report):

```kotlin
    // Compose's holders for lambdas without captures: public in bytecode, internal in Kotlin, and renamed
    // whenever a screen's lambdas change.
    ignoredClasses.addAll(
        listOf(
            "BrowserScreenKt",
            "CompanionShellKt",
            "DetailScreenKt",
            "FlagControlsKt",
            "FlagRowKt",
            "FlagSectionHeaderKt",
            "FlagSheetKt",
            "FlagTextFieldKt",
            "ImportSheetKt",
            "ListEditorScreenKt",
            "OverridesScreenKt",
            "QrSheetKt",
            "RecordsScreenKt",
            "SignalsScreenKt",
            "StatusScreensKt",
        ).map { "com.andyyhope.featureflag.ui.screens.ComposableSingletons\$$it" },
    )
```

- [ ] **Step 7: Dump again**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew apiDump releaseApiDump
grep -c ComposableSingletons featureflag-ui/api/featureflag-ui.api
wc -l featureflag-*/api/*.api
```

Expected: `0`, and roughly core 1268, ksp 5, test 5, android 216, bridge 12, ui 307 lines (the first five exactly, if nothing has changed since `ba4b29b` but B1).

- [ ] **Step 8: Check that `check` runs all six**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew check --dry-run > build/check-plan.txt
grep -E "^:featureflag-[a-z]+:(api|releaseApi)Check SKIPPED$" build/check-plan.txt
```

Expected: exactly these six lines, which are what B8's guard greps for:

```
:featureflag-android:releaseApiCheck SKIPPED
:featureflag-bridge:releaseApiCheck SKIPPED
:featureflag-core:apiCheck SKIPPED
:featureflag-ksp:apiCheck SKIPPED
:featureflag-test:apiCheck SKIPPED
:featureflag-ui:releaseApiCheck SKIPPED
```

- [ ] **Step 9: Mutation check: an added function fails the check**

**Create** `featureflag-core/src/main/kotlin/com/andyyhope/featureflag/AbiMutation.kt` and `featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/AbiMutation.kt`, each holding its package line and `public fun abiMutationCheck(): Int = 1` (`package com.andyyhope.featureflag` and `package com.andyyhope.featureflag.ui`). Then:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :featureflag-core:apiCheck --continue; ./gradlew :featureflag-ui:releaseApiCheck --continue
rm featureflag-core/src/main/kotlin/com/andyyhope/featureflag/AbiMutation.kt featureflag-ui/src/main/kotlin/com/andyyhope/featureflag/ui/AbiMutation.kt
./gradlew :featureflag-core:apiCheck :featureflag-ui:releaseApiCheck
```

Expected: both of the first two runs `BUILD FAILED`, each showing a diff with `+public final class …AbiMutationKt {` and `+	public static final fun abiMutationCheck ()I`. (The bridge's message says to run `apiDump`; for Android modules the task is `releaseApiDump`.) After the files are deleted, `BUILD SUCCESSFUL`.

- [ ] **Step 10: Run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL`, the log listing all six check tasks as executed.

- [ ] **Step 11: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts featureflag-android/build.gradle.kts featureflag-bridge/build.gradle.kts \
  featureflag-ui/build.gradle.kts featureflag-core/api featureflag-ksp/api featureflag-test/api featureflag-android/api \
  featureflag-bridge/api featureflag-ui/api
git commit -m "Freeze the public API in committed dumps that check verifies in all six library modules" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B4: `:docs-samples` and `DocsSyncTest`

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `docs-samples/build.gradle.kts`
- Create: `docs-samples/src/test/kotlin/docs/sync/MarkdownDocument.kt`, `RegionIndex.kt`, `LineDiff.kt`, `DocsSync.kt`, `DocsRepository.kt`
- Create: `docs-samples/src/test/kotlin/docs/DocOutputs.kt`
- Test: `docs-samples/src/test/kotlin/docs/sync/DocsSyncCheckerTest.kt`, `docs-samples/src/test/kotlin/docs/sync/DocsSyncTest.kt`

**Interfaces:**
- Consumes: Seams 1, 2, 5 and 7; the published modules as `testImplementation` dependencies; `kspTest(project(":featureflag-ksp"))`.
- Produces (all in `docs-samples/src/test`, package `docs.sync` unless noted):

```kotlin
// A summary of the signatures Steps 4 and 6 write in full; not code to paste.
data class TextFile(val path: String, val text: String)                 // path from the repository root, '/'-separated
sealed interface Marker { Snippet(name), Output(name), Ignore(reason) }
data class Fence(val file: String, val line: Int, val language: String, val body: String, val marker: Marker?)
data class Link(val file: String, val line: Int, val target: String)
class MarkdownDocument(val file: TextFile) {                            // fences, links, anchors, problems
    companion object { val NAME: Regex; const val NAME_RULE: String; fun slug(heading: String): String }
}
data class Region(val name: String, val file: String, val line: Int, val body: String, val support: Boolean)
class RegionIndex(sources: List<TextFile>) { val regions; val problems; fun named(name: String): List<Region> }
object LineDiff { fun unified(expected: String, actual: String, expectedLabel: String, actualLabel: String): String }
class DocsSync(
    markdown: List<TextFile>, sources: List<TextFile>, outputs: Map<String, () -> String>,
    versionName: String, fileExists: (String) -> Boolean,
) {
    fun problems(): List<String>                                         // "path:line: what", empty when in sync
    companion object { val UNMARKED: Set<String>; val ARTIFACTS: Set<String>; fun resolve(from: String, relative: String): String? }
}
class DocsRepository(root: File, val versionName: String) {
    val markdown: List<TextFile>; val sources: List<TextFile>; fun exists(path: String): Boolean
    companion object { fun fromSystemProperties(): DocsRepository }      // featureflag.docs.root, featureflag.docs.versionName
}
object docs.DocOutputs { val all: Map<String, () -> String> }           // B5–B7 replace the file to add entries
```

What `DocsSync.problems()` reports, each as `path:line: …`:
- a `kotlin`, `kts`, `xml`, `json` or any other labelled fence with no marker (`text`, `sh`, `bash`, `console` and unlabelled fences need none);
- a marker that is malformed, has an invalid name, or is not on the line directly above a fence; a `snippet-ignore` with no reason;
- a fence that is indented, inside a `>` blockquote, or never closed;
- a link it cannot read: one that wraps onto the next line, has a title, or is reference-style (`[x][r]`, `[r]: a.md`);
- a `snippet:` whose region is missing, or whose body differs (a unified diff, labelled with both locations);
- a region defined twice; a region inside another snippet region; an `endregion` with no region; a region never closed; a misspelled region marker;
- a region no document quotes, unless it is a `snippet-support:` region;
- an `output:` with no `DocOutputs` entry, one whose computation throws, one that differs (a diff), and an entry no document shows;
- a relative link to a file that does not exist, to a heading the target does not have (GitHub's anchors, including `-1` for a repeated heading and without a heading's closing `#`s), out of the repository, or into `docs/superpowers/`; links in fences and inline code are not followed, and absolute URLs are not checked;
- a `com.andyyhope.featureflag:<artifact>:<version>` coordinate in any fence whose artifact is not one of the seven, or whose version is not `VERSION_NAME` without `-SNAPSHOT`.

Comparison is the Contract's: the lines strictly between the markers, common indent removed, `\r\n` as `\n`, trailing blank lines dropped, equal to the fence body exactly. An output is normalised the same way.

DocsSyncTest scans its own module, so `DocsSyncCheckerTest` never writes a region marker as a whole line of its own source: it splices `// region` and `// endregion` in from properties.

- [ ] **Step 1: Add the module**

**Modify** `settings.gradle.kts`: after `include(":featureflag-bom")`, add `include(":docs-samples")`.

**Modify** `build.gradle.kts`: in `apiValidation { }`, replace

```kotlin
    // Nothing published: the BOM has no classes, and the samples are apps and a sample-only library.
    ignoredProjects.addAll(listOf("featureflag-bom", "signals", "host", "companion"))
```

with

```kotlin
    // Nothing published: the BOM has no classes, the samples are apps and a sample-only library, and
    // docs-samples is the docs' tests.
    ignoredProjects.addAll(listOf("featureflag-bom", "docs-samples", "signals", "host", "companion"))
```

**Create** `docs-samples/build.gradle.kts`:

```kotlin
plugins {
    id("featureflag.android-library")
    id("featureflag.compose")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.andyyhope.featureflag.docs"
}

// Every code sample in README.md and docs/ lives in src/test, compiled against the libraries as an app
// gets them, with KSP running over it as it runs over an app's flags. Nothing here is published.
dependencies {
    kspTest(project(":featureflag-ksp"))
    testImplementation(project(":featureflag-core"))
    testImplementation(project(":featureflag-test"))
    testImplementation(project(":featureflag-android"))
    testImplementation(project(":featureflag-ui"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.lifecycle.runtime.compose)
}

// DocsSyncTest compares the markdown with the code it quotes. The repository root and the version reach
// the test as system properties, and every file it reads is a task input, so editing a doc re-runs it:
// the same hand-over featureflag.fixtures uses. DocsRepository walks the markdown and source patterns.
// The sources are inputs as text, not only as compiled classes: a comment or the indent inside a region
// changes what a doc quotes without changing any bytecode. The link targets are inputs too.
val repositoryRoot = rootProject.layout.projectDirectory
val versionName = providers.gradleProperty("VERSION_NAME")

tasks.withType<Test>().configureEach {
    systemProperty("featureflag.docs.root", repositoryRoot.asFile.absolutePath)
    systemProperty("featureflag.docs.versionName", versionName.get())
    inputs.files(
        repositoryRoot.file("README.md"),
        repositoryRoot.file("LICENSE"),
        repositoryRoot.dir("docs").asFileTree.matching { include("*.md") },
    ).withPropertyName("docsMarkdown").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(layout.projectDirectory.dir("src").asFileTree)
        .withPropertyName("docsSampleRegions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Everything under samples/: the sources and build files it quotes, its README, and the keys and
    // screenshots that README links to.
    inputs.files(repositoryRoot.dir("samples").asFileTree.matching { exclude("**/build/**", "**/.gradle/**") })
        .withPropertyName("docsSamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // TheBridge.md shows what this manifest merges into a host, and TheBridgeTest compares the two.
    inputs.file(repositoryRoot.file("featureflag-bridge/src/main/AndroidManifest.xml"))
        .withPropertyName("docsBridgeManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
```

The test task's inputs are every tree `DocsRepository` reads, as text: `README.md`, `LICENSE` and `docs/*.md`; all of `docs-samples/src`; the whole `samples` tree except build output (its sources, build files and README, and the keys and screenshots that README links to); and the bridge's manifest. The compiled classes alone are not enough: changing a comment or the spacing inside a region changes what a doc quotes but leaves the class files identical, so the test would stay `UP-TO-DATE`, or come back `FROM-CACHE` on CI. B5 Step 6 checks this.

The module has no `src/main`. `featureflag.android-library` gives it Robolectric, JUnit, kotlin-test, coroutines-test and Turbine for tests; the Compose and Material dependencies are the ones a companion app adds, and `material-icons-core` is for B7's custom-tab sample.

- [ ] **Step 2: Write the checker's tests**

**Create** `docs-samples/src/test/kotlin/docs/sync/DocsSyncCheckerTest.kt`:

````kotlin
package docs.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DocsSync over fixture strings: each way the docs can drift from the code, before any real doc exists. */
class DocsSyncCheckerTest {

    private val greetingDoc = """
        # Greeting

        <!-- snippet: greeting -->
        ```kotlin
        val greeting = "hello"
        println(greeting)
        ```
    """.trimIndent()

    // Spliced in, so that DocsSyncTest, which scans this file too, does not read the fixture's markers as real ones.
    private val region = "// region"
    private val endregion = "// endregion"

    private val greetingSource = """
        class Greeting {
            fun run() {
                $region snippet: greeting
                val greeting = "hello"
                println(greeting)
                $endregion
            }
        }
    """.trimIndent()

    private val noQuotes = mapOf("docs/A.md" to "# Nothing quoted\n")

    private fun problems(
        markdown: Map<String, String>,
        sources: Map<String, String> = emptyMap(),
        outputs: Map<String, () -> String> = emptyMap(),
        versionName: String = "0.1.0-SNAPSHOT",
        existing: Set<String> = emptySet(),
    ): List<String> = DocsSync(
        markdown = markdown.map { (path, text) -> TextFile(path, text) },
        sources = sources.map { (path, text) -> TextFile(path, text) },
        outputs = outputs,
        versionName = versionName,
        fileExists = { it in existing },
    ).problems()

    private fun assertOneProblem(problems: List<String>, vararg fragments: String) {
        assertEquals(1, problems.size, problems.joinToString("\n"))
        for (fragment in fragments) {
            assertTrue(fragment in problems.single(), "'$fragment' is not in: ${problems.single()}")
        }
    }

    // Snippets

    @Test
    fun aSnippetMatchingItsIndentedRegionPasses() {
        val problems = problems(mapOf("docs/A.md" to greetingDoc), mapOf("src/Greeting.kt" to greetingSource))

        assertEquals(emptyList(), problems)
    }

    @Test
    fun windowsLineEndingsAndTrailingBlankLinesInARegionAreIgnored() {
        val body = "val greeting = \"hello\"\r\nprintln(greeting)\r\n\r\n"
        val source = "// region snippet: greeting\r\n$body// endregion\r\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to greetingDoc), mapOf("src/Greeting.kt" to source)))
    }

    @Test
    fun aSnippetWhoseRegionChangedShowsADiffWithBothLocations() {
        val changed = greetingSource.replace("\"hello\"", "\"goodbye\"")

        val problems = problems(mapOf("docs/A.md" to greetingDoc), mapOf("src/Greeting.kt" to changed))

        assertOneProblem(
            problems,
            "docs/A.md:4: the fence differs from region 'greeting' at src/Greeting.kt:3",
            "--- docs/A.md:4 (document)",
            "+++ src/Greeting.kt:3 (region)",
            "-val greeting = \"hello\"",
            "+val greeting = \"goodbye\"",
            " println(greeting)",
        )
    }

    @Test
    fun aSnippetWithNoRegionFails() {
        val problems = problems(mapOf("docs/A.md" to greetingDoc))

        assertOneProblem(problems, "docs/A.md:4: no source has the region 'snippet: greeting'")
    }

    @Test
    fun aRegionDefinedTwiceFails() {
        val sources = mapOf("src/Greeting.kt" to greetingSource, "src/Copy.kt" to greetingSource)

        assertOneProblem(
            problems(mapOf("docs/A.md" to greetingDoc), sources),
            "region 'greeting' is defined more than once",
            "src/Greeting.kt:3",
            "src/Copy.kt:3",
        )
    }

    @Test
    fun xmlRegionsAreReadToo() {
        val doc = "<!-- snippet: permission -->\n```xml\n<uses-permission android:name=\"a.b\" />\n```\n"
        val manifest = "<manifest>\n    <!-- region snippet: permission -->\n" +
            "    <uses-permission android:name=\"a.b\" />\n    <!-- endregion -->\n</manifest>\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc), mapOf("src/AndroidManifest.xml" to manifest)))
    }

    // Unmarked fences

    @Test
    fun anUnmarkedKotlinKtsXmlOrJsonFenceFails() {
        for (language in listOf("kotlin", "kts", "xml", "json")) {
            val doc = "# Title\n\n```$language\nsomething\n```\n"

            assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:3: a '$language' fence needs a marker")
        }
    }

    @Test
    fun shellConsoleAndPlainTextFencesNeedNoMarker() {
        val doc = listOf("sh", "bash", "console", "", "text").joinToString("\n\n") { "```$it\nls\n```" }

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    @Test
    fun anIgnoredFenceIsNotCompared() {
        val doc = "<!-- snippet-ignore: shows a mistake on purpose -->\n```kotlin\nval x: String = 1\n```\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    @Test
    fun anIgnoreWithNoReasonFails() {
        val doc = "<!-- snippet-ignore:  -->\n```kotlin\nval x = 1\n```\n"

        assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:1: snippet-ignore needs a reason")
    }

    @Test
    fun aMarkerThatIsNotDirectlyBeforeAFenceFails() {
        val doc = "<!-- snippet: greeting -->\n\n```kotlin\nval greeting = \"hello\"\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc))

        assertEquals(
            listOf(
                "docs/A.md:1: the marker is not on the line directly before a fence",
                "docs/A.md:3: a 'kotlin' fence needs a marker",
            ),
            problems,
        )
    }

    @Test
    fun aMisspelledMarkerOrAnInvalidNameFails() {
        val misspelled = problems(mapOf("docs/A.md" to "<!--snippet: greeting-->\n```text\nx\n```\n"))
        val badName = problems(mapOf("docs/A.md" to "<!-- snippet: Greeting_1 -->\n```text\nx\n```\n"))

        assertOneProblem(misspelled, "docs/A.md:1: '<!--snippet: greeting-->' is not a marker")
        assertTrue(badName.any { "docs/A.md:1: 'Greeting_1' is not a name" in it }, badName.joinToString("\n"))
    }

    @Test
    fun anIndentedOrUnclosedFenceFails() {
        val indented = problems(mapOf("docs/A.md" to "- a list\n    ```kotlin\n    val x = 1\n    ```\n"))
        val unclosed = problems(mapOf("docs/A.md" to "```text\nnever closed\n"))

        assertEquals(
            listOf(
                "docs/A.md:2: a fence must start at column 0, or the checker cannot read it",
                "docs/A.md:4: a fence must start at column 0, or the checker cannot read it",
            ),
            indented,
        )
        assertOneProblem(unclosed, "docs/A.md:1: the fence is never closed")
    }

    @Test
    fun aFenceInABlockquoteFails() {
        val problems = problems(mapOf("docs/A.md" to "> Note:\n> ```kotlin\n> val x = 1\n> ```\n"))

        assertEquals(
            listOf(
                "docs/A.md:2: a fence must start at column 0, or the checker cannot read it",
                "docs/A.md:4: a fence must start at column 0, or the checker cannot read it",
            ),
            problems,
        )
    }

    // Orphans

    @Test
    fun aRegionNobodyQuotesFails() {
        val problems = problems(noQuotes, mapOf("src/Greeting.kt" to greetingSource))

        assertOneProblem(problems, "src/Greeting.kt:3: no document quotes region 'greeting'")
    }

    @Test
    fun aSupportRegionIsNeverAnOrphan() {
        val source = "// region snippet-support: fixture\nval fixture = 1\n// endregion\n"

        assertEquals(emptyList(), problems(noQuotes, mapOf("src/Fixture.kt" to source)))
    }

    @Test
    fun plainIdeRegionsAreNotSnippets() {
        val source = "// region Helpers\nfun helper() = 1\n// endregion\n"

        assertEquals(emptyList(), problems(noQuotes, mapOf("src/Helpers.kt" to source)))
    }

    // Outputs

    @Test
    fun anOutputMatchingItsValuePasses() {
        val doc = "<!-- output: report -->\n```text\nline one\nline two\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc), outputs = mapOf("report" to { "line one\nline two\n" }))

        assertEquals(emptyList(), problems)
    }

    @Test
    fun anOutputThatNoLongerMatchesFails() {
        val doc = "<!-- output: report -->\n```text\nline one\nline two\n```\n"

        assertOneProblem(
            problems(mapOf("docs/A.md" to doc), outputs = mapOf("report" to { "line one\nline 2" })),
            "docs/A.md:2: the fence differs from output 'report'",
            "-line two",
            "+line 2",
        )
    }

    @Test
    fun anUnregisteredOrUnshownOutputFails() {
        val doc = "<!-- output: report -->\n```text\nx\n```\n"

        assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:2: DocOutputs registers no output 'report'")
        assertOneProblem(
            problems(noQuotes, outputs = mapOf("report" to { "x" })),
            "DocOutputs: no document shows output 'report'",
        )
    }

    @Test
    fun anOutputThatThrowsFails() {
        val doc = "<!-- output: report -->\n```text\nx\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc), outputs = mapOf("report" to { error("no report") }))

        assertOneProblem(problems, "docs/A.md:2: computing output 'report' threw", "no report")
    }

    // Unbalanced markers

    @Test
    fun anEndregionWithNoRegionFails() {
        val problems = problems(noQuotes, mapOf("src/A.kt" to "val a = 1\n// endregion\n"))

        assertOneProblem(problems, "src/A.kt:2: an endregion closes no region")
    }

    @Test
    fun aRegionThatIsNeverClosedFails() {
        val problems = problems(noQuotes, mapOf("src/A.kt" to "// region snippet: open\nval a = 1\n"))

        assertOneProblem(problems, "src/A.kt:1: the region is never closed")
    }

    @Test
    fun aSnippetRegionInsideAnotherFails() {
        val source = "// region snippet: outer\n// region snippet: inner\nval a = 1\n// endregion\n// endregion\n"
        val doc = "<!-- snippet: inner -->\n```kotlin\nval a = 1\n```\n\n" +
            "<!-- snippet: outer -->\n```kotlin\n// region snippet: inner\nval a = 1\n// endregion\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc), mapOf("src/A.kt" to source))

        assertOneProblem(problems, "src/A.kt:2: region 'inner' is inside another region")
    }

    @Test
    fun aMisspelledRegionMarkerFails() {
        val problems = problems(noQuotes, mapOf("src/A.kt" to "// region snippet:greeting\nval a = 1\n// endregion\n"))

        assertOneProblem(problems, "src/A.kt:1: '// region snippet:greeting' is not a snippet region marker")
    }

    // Links

    @Test
    fun relativeLinksToFilesAndHeadingsResolve() {
        val readme = "# Read me\n\n[![Licence](LICENSE)](LICENSE)\n\n" +
            "[Nesting](docs/DeclaringFlags.md#nesting-flaggroups), " +
            "[values](docs/FlagValues.md),\n[the licence](LICENSE), [the top](#read-me) and " +
            "[a site](https://example.com/missing).\n"
        val declaring = "# Declaring flags\n\n## Nesting `@FlagGroup`s\n\nBack to [the readme](../README.md#read-me).\n"
        val markdown = mapOf(
            "README.md" to readme,
            "docs/DeclaringFlags.md" to declaring,
            "docs/FlagValues.md" to "# Values\n",
        )

        assertEquals(emptyList(), problems(markdown, existing = setOf("LICENSE")))
    }

    @Test
    fun aLinkToAMissingFileFails() {
        val problems = problems(mapOf("docs/A.md" to "See [B](B.md).\n"))

        assertOneProblem(problems, "docs/A.md:1: 'B.md' points to docs/B.md, which does not exist")
    }

    @Test
    fun aLinkToAMissingHeadingFails() {
        val problems = problems(mapOf("docs/A.md" to "See [B](B.md#missing).\n", "docs/B.md" to "# Present\n"))

        assertOneProblem(problems, "docs/A.md:1: 'B.md#missing' names a heading docs/B.md does not have")
    }

    @Test
    fun aRepeatedHeadingGetsANumberedAnchor() {
        val doc = "# Overview\n\n## Overview\n\nSee [the second](#overview-1) and [no third](#overview-2).\n"

        assertOneProblem(problems(mapOf("docs/A.md" to doc)), "docs/A.md:5: '#overview-2' names a heading")
    }

    @Test
    fun aLinkIntoThePlanningNotesFails() {
        val doc = "See [the plan](superpowers/plans/x.md).\n"

        val problems = problems(mapOf("docs/A.md" to doc), existing = setOf("docs/superpowers/plans/x.md"))

        assertOneProblem(problems, "points into docs/superpowers/")
    }

    @Test
    fun aLinkTheCheckerCannotReadFails() {
        val wrapped = "See [the other\ndoc](B.md).\n"
        val titled = "See [B](B.md \"B\").\n"
        val reference = "See [B][b].\n\n[b]: B.md\n"

        assertOneProblem(problems(mapOf("docs/A.md" to wrapped)), "docs/A.md:2: a link the checker cannot read")
        assertOneProblem(problems(mapOf("docs/A.md" to titled)), "docs/A.md:1: a link the checker cannot read")
        assertOneProblem(problems(mapOf("docs/A.md" to reference)), "docs/A.md:3: a link the checker cannot read")
    }

    @Test
    fun aHeadingsClosingHashesAreNotPartOfItsAnchor() {
        val doc = "## Closing hashes ##\n\nSee [it](#closing-hashes).\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    @Test
    fun linksInsideFencesAndInlineCodeAreNotFollowed() {
        val doc = "Write `[x](missing.md)` to link.\n\n```text\n[x](missing.md)\n```\n"

        assertEquals(emptyList(), problems(mapOf("docs/A.md" to doc)))
    }

    // Install coordinates

    @Test
    fun anInstallCoordinateMustNameTheReleaseVersion() {
        val line = "implementation(\"com.andyyhope.featureflag:featureflag-core:0.1.0\")"
        val source = "// region snippet: install\n$line\n// endregion\n"
        val doc = "<!-- snippet: install -->\n```kts\n$line\n```\n"

        val current = problems(mapOf("docs/A.md" to doc), mapOf("src/install.kts" to source))
        val stale = problems(
            mapOf("docs/A.md" to doc.replace("0.1.0", "0.0.9")),
            mapOf("src/install.kts" to source.replace("0.1.0", "0.0.9")),
        )

        assertEquals(emptyList(), current)
        assertOneProblem(stale, "docs/A.md:2: 'featureflag-core:0.0.9' should name the release version, 0.1.0")
    }

    @Test
    fun anInstallCoordinateMustNameAPublishedArtifact() {
        val doc = "```sh\necho com.andyyhope.featureflag:featureflag-compose:0.1.0\n```\n"

        val problems = problems(mapOf("docs/A.md" to doc))

        assertOneProblem(problems, "docs/A.md:1: 'featureflag-compose' is not a published artifact")
    }

    // Helpers

    @Test
    fun headingsSlugTheWayGitHubDoes() {
        assertEquals("what-flagrecord-writes", MarkdownDocument.slug("What `@FlagRecord` writes"))
        assertEquals("1-add-the-dependencies", MarkdownDocument.slug("1. Add the dependencies"))
        assertEquals("a--b", MarkdownDocument.slug("A — B"))
    }

    @Test
    fun relativePathsResolveAgainstTheLinkingFile() {
        assertEquals("docs/B.md", DocsSync.resolve("docs/A.md", "./B.md"))
        assertEquals("README.md", DocsSync.resolve("docs/A.md", "../README.md"))
        assertEquals(null, DocsSync.resolve("docs/A.md", "../../outside.md"))
    }
}
````

- [ ] **Step 3: Run them and watch them fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest --tests 'docs.sync.DocsSyncCheckerTest'
```

Expected: `BUILD FAILED` in `:docs-samples:compileDebugUnitTestKotlin`, with `Unresolved reference 'DocsSync'`, `'TextFile'` and `'MarkdownDocument'`. (`kspDebugUnitTestKotlin` runs first and finds no `@FlagContainer`, which is fine.)

- [ ] **Step 4: Write the checker**

**Create** `docs-samples/src/test/kotlin/docs/sync/MarkdownDocument.kt`:

```kotlin
package docs.sync

/** A file the checker reads: its path from the repository root, with `/` separators, and its text. */
data class TextFile(val path: String, val text: String)

/** What the HTML comment on the line before a fence says about it. */
sealed interface Marker {
    /** `<!-- snippet: name -->`: the body equals the source region `name`. */
    data class Snippet(val name: String) : Marker

    /** `<!-- output: name -->`: the body equals the string `DocOutputs` registers under `name`. */
    data class Output(val name: String) : Marker

    /** `<!-- snippet-ignore: reason -->`: deliberately unchecked. */
    data class Ignore(val reason: String) : Marker
}

/** One fenced block: the line it opens on, its language, its body and its marker, if it has one. */
data class Fence(val file: String, val line: Int, val language: String, val body: String, val marker: Marker?)

/** A link to something in the repository, as written. */
data class Link(val file: String, val line: Int, val target: String)

/**
 * Reads one markdown file: its fences with their markers, its relative links, the anchors its
 * headings make, and whatever about its markup the checker cannot trust.
 *
 * A fence opens at column 0 with three or more backticks or tildes, and a marker is the whole line
 * immediately before it. Markers, links and headings inside a fence are not read. Anything it cannot
 * read reliably is a problem rather than skipped: an indented or quoted fence, and a link that wraps,
 * has a title, or is a reference.
 */
class MarkdownDocument(val file: TextFile) {

    private val fenceList = mutableListOf<Fence>()
    private val linkList = mutableListOf<Link>()
    private val anchorSet = linkedSetOf<String>()
    private val headingCounts = mutableMapOf<String, Int>()
    private val problemList = mutableListOf<String>()

    val fences: List<Fence> get() = fenceList
    val links: List<Link> get() = linkList
    val anchors: Set<String> get() = anchorSet
    val problems: List<String> get() = problemList

    init {
        val lines = file.text.replace("\r\n", "\n").split("\n")
        var marker: Pair<Int, Marker>? = null
        var index = 0
        while (index < lines.size) {
            val opening = FENCE.matchEntire(lines[index])
            if (opening != null) {
                index = readFence(lines, index, opening, marker?.second)
                marker = null
            } else {
                marker?.let { (line, _) -> problem(line, "the marker is not on the line directly before a fence") }
                marker = readMarker(lines[index], index + 1)
                if (marker == null) readProse(lines[index], index + 1)
                index += 1
            }
        }
        marker?.let { (line, _) -> problem(line, "the marker is not on the line directly before a fence") }
    }

    private fun readFence(lines: List<String>, start: Int, opening: MatchResult, marker: Marker?): Int {
        val delimiter = opening.groupValues[1]
        val language = opening.groupValues[2].trim().substringBefore(' ').lowercase()
        val close = (start + 1 until lines.size).firstOrNull { closes(lines[it], delimiter) }
        if (close == null) {
            problem(start + 1, "the fence is never closed")
            return lines.size
        }
        val body = lines.subList(start + 1, close).joinToString("\n")
        fenceList += Fence(file.path, start + 1, language, body, marker)
        return close + 1
    }

    private fun closes(line: String, delimiter: String): Boolean {
        val trimmed = line.trimEnd()
        return trimmed.length >= delimiter.length && trimmed.all { it == delimiter.first() }
    }

    private fun readMarker(line: String, number: Int): Pair<Int, Marker>? {
        val match = MARKER.matchEntire(line.trimEnd())
        if (match == null) {
            if (MARKER_LIKE.matches(line.trim())) problem(number, "'${line.trim()}' is not a marker: $MARKER_FORMS")
            return null
        }
        val (kind, argument) = match.destructured
        val value = argument.trim()
        val marker = when (kind) {
            "snippet" -> Marker.Snippet(value)
            "output" -> Marker.Output(value)
            else -> Marker.Ignore(value)
        }
        when {
            marker is Marker.Ignore && value.isEmpty() -> problem(number, "snippet-ignore needs a reason")
            marker !is Marker.Ignore && !NAME.matches(value) -> problem(number, "'$value' is not a name: $NAME_RULE")
            else -> Unit
        }
        return number to marker
    }

    private fun readProse(line: String, number: Int) {
        if (INDENTED_FENCE.matches(line)) problem(number, INDENTED_FENCE_MESSAGE)
        HEADING.matchEntire(line)?.let { addAnchor(slug(it.groupValues[1])) }
        // Innermost first, so an image inside a link, such as a badge, is read as both.
        var rest = line.replace(INLINE_CODE, "")
        var found = LINK.findAll(rest).toList()
        while (found.isNotEmpty()) {
            val targets = found.map { it.groupValues[1] }.filterNot { "://" in it || it.startsWith("mailto:") }
            targets.forEach { linkList += Link(file.path, number, it) }
            rest = rest.replace(LINK, "")
            found = LINK.findAll(rest).toList()
        }
        // What is left that still looks like a link is one the checker cannot follow, so it would go unchecked.
        if ("](" in rest || REFERENCE_LINK.matches(rest)) problem(number, UNREADABLE_LINK_MESSAGE)
    }

    // GitHub numbers a repeated heading's anchor: the second "Overview" is #overview-1.
    private fun addAnchor(base: String) {
        val seen = headingCounts.getOrDefault(base, 0)
        headingCounts[base] = seen + 1
        anchorSet += if (seen == 0) base else "$base-$seen"
    }

    private fun problem(line: Int, message: String) {
        problemList += "${file.path}:$line: $message"
    }

    companion object {
        /** Names of snippets and outputs. */
        val NAME = Regex("[a-z0-9-]+")
        const val NAME_RULE = "use [a-z0-9-]+"

        private const val MARKER_FORMS = "use <!-- snippet: x -->, <!-- output: x --> or <!-- snippet-ignore: why -->"
        private const val INDENTED_FENCE_MESSAGE = "a fence must start at column 0, or the checker cannot read it"
        private val MARKER = Regex("""<!-- (snippet|output|snippet-ignore): (.*) -->""")
        private val MARKER_LIKE = Regex("""<!--\s*(snippet|output|snippet-ignore)\b.*""")
        private val FENCE = Regex("""(`{3,}|~{3,})(.*)""")
        private const val UNREADABLE_LINK_MESSAGE = "a link the checker cannot read: keep it on one line, with no title"
        private val INDENTED_FENCE = Regex("""(\s+|\s*>.*?)(`{3,}|~{3,}).*""")
        private val REFERENCE_LINK = Regex("""\s*\[[^\]]+]:\s*\S.*""")
        private val HEADING = Regex("""#{1,6}\s+(.+?)(?:\s+#+)?\s*""")
        private val LINK = Regex("""\[[^\[\]]*]\(([^)\s]+)\)""")
        private val INLINE_CODE = Regex("`[^`]*`")

        /** The anchor GitHub gives a heading: lowercase, punctuation dropped, spaces as hyphens. */
        fun slug(heading: String): String = heading
            .lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/sync/RegionIndex.kt`:

```kotlin
package docs.sync

/**
 * A `// region snippet: name` … `// endregion` block (`<!-- region snippet: name -->` … `<!-- endregion -->`
 * in XML), with its body as a fence shows it: the lines strictly between the markers, their common
 * indent removed, trailing blank lines dropped. A `snippet-support:` region is never quoted.
 */
data class Region(val name: String, val file: String, val line: Int, val body: String, val support: Boolean)

/** Every snippet region in [sources], and whatever about their markers the checker cannot trust. */
class RegionIndex(sources: List<TextFile>) {

    private val regionList = mutableListOf<Region>()
    private val problemList = mutableListOf<String>()

    val regions: List<Region> get() = regionList
    val problems: List<String> get() = problemList

    init {
        for (source in sources) {
            syntaxOf(source.path)?.let { read(source, it) }
        }
        regionList.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            val places = duplicates.joinToString(" and ") { "${it.file}:${it.line}" }
            problemList += "region '$name' is defined more than once: $places"
        }
    }

    /** The regions called [name]: one, unless it is missing or defined twice. */
    fun named(name: String): List<Region> = regionList.filter { it.name == name }

    private fun read(source: TextFile, syntax: Syntax) {
        val lines = source.text.replace("\r\n", "\n").split("\n")
        val open = ArrayDeque<Open>()
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            val start = syntax.start.matchEntire(line)
            when {
                start != null -> {
                    val (kind, name) = start.destructured
                    if (open.any { it.name != null }) problem(source, index, "region '$name' is inside another region")
                    if (!MarkdownDocument.NAME.matches(name)) {
                        problem(source, index, "'$name' is not a name: ${MarkdownDocument.NAME_RULE}")
                    }
                    open.addLast(Open(index, name, support = kind == "snippet-support"))
                }
                syntax.end.matches(line) -> close(source, lines, index, open.removeLastOrNull())
                syntax.anyStart.matches(line) -> {
                    if (syntax.lookalike.matches(line)) problem(source, index, "'$line' is not a snippet region marker")
                    open.addLast(Open(index, name = null, support = false))
                }
                else -> Unit
            }
        }
        open.forEach { problem(source, it.index, "the region is never closed") }
    }

    private fun close(source: TextFile, lines: List<String>, index: Int, opened: Open?) {
        when {
            opened == null -> problem(source, index, "an endregion closes no region")
            opened.name != null -> regionList += Region(
                name = opened.name,
                file = source.path,
                line = opened.index + 1,
                body = body(lines.subList(opened.index + 1, index)),
                support = opened.support,
            )
            else -> Unit
        }
    }

    private fun problem(source: TextFile, index: Int, message: String) {
        problemList += "${source.path}:${index + 1}: $message"
    }

    /** A region marker that is open. A plain IDE `// region` has no [name]. */
    private class Open(val index: Int, val name: String?, val support: Boolean)

    private class Syntax(val start: Regex, val end: Regex, val anyStart: Regex, val lookalike: Regex)

    companion object {
        private val KOTLIN = Syntax(
            start = Regex("""// region (snippet|snippet-support): (\S+)"""),
            end = Regex("""// endregion"""),
            anyStart = Regex("""// region\b.*"""),
            lookalike = Regex("""//\s*region\s*snippet.*"""),
        )
        private val XML = Syntax(
            start = Regex("""<!-- region (snippet|snippet-support): (\S+) -->"""),
            end = Regex("""<!-- endregion -->"""),
            anyStart = Regex("""<!-- region\b.*"""),
            lookalike = Regex("""<!--\s*region\s*snippet.*"""),
        )

        private fun syntaxOf(path: String): Syntax? = when (path.substringAfterLast('.')) {
            "kt", "kts" -> KOTLIN
            "xml" -> XML
            else -> null
        }

        /** The lines with their common indent removed and trailing blank lines dropped. */
        fun body(lines: List<String>): String {
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
            return lines
                .map { if (it.isBlank()) "" else it.substring(indent) }
                .dropLastWhile { it.isEmpty() }
                .joinToString("\n")
        }
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/sync/LineDiff.kt`:

```kotlin
package docs.sync

/** A unified-style line diff, small enough to print in a test failure. */
object LineDiff {

    /** Lines only in [expected] start with `-`, lines only in [actual] with `+`, shared lines with a space. */
    fun unified(expected: String, actual: String, expectedLabel: String, actualLabel: String): String {
        val a = expected.split("\n")
        val b = actual.split("\n")
        // common[i][j]: the length of the longest common subsequence of a[i..] and b[j..].
        val common = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                common[i][j] = if (a[i] == b[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
            }
        }
        val lines = mutableListOf("--- $expectedLabel", "+++ $actualLabel")
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> {
                    lines += " ${a[i]}"
                    i += 1
                    j += 1
                }
                i < a.size && (j == b.size || common[i + 1][j] >= common[i][j + 1]) -> {
                    lines += "-${a[i]}"
                    i += 1
                }
                else -> {
                    lines += "+${b[j]}"
                    j += 1
                }
            }
        }
        return lines.joinToString("\n")
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/sync/DocsSync.kt`:

```kotlin
package docs.sync

/**
 * Checks that the docs say what the code does: every fence a reader could copy is byte-identical to
 * a region of compiled, tested code or to a value a test computed, every region is quoted somewhere,
 * every relative link resolves, and every install coordinate names the version being released.
 *
 * Pure: it reads only what it is given, so its tests run over fixture strings.
 *
 * @param fileExists whether a repository path names a file or directory, for links to anything that
 *   is not one of [markdown].
 */
class DocsSync(
    markdown: List<TextFile>,
    sources: List<TextFile>,
    private val outputs: Map<String, () -> String>,
    versionName: String,
    private val fileExists: (String) -> Boolean,
) {

    private val documents = markdown.map(::MarkdownDocument)
    private val regions = RegionIndex(sources)
    private val releaseVersion = versionName.removeSuffix("-SNAPSHOT")

    /** Everything wrong, each as `path:line: what`, or empty. */
    fun problems(): List<String> {
        val fences = documents.flatMap { it.fences }
        return documents.flatMap { it.problems } +
            regions.problems +
            fences.flatMap(::check) +
            orphans(fences) +
            documents.flatMap { it.links }.mapNotNull(::checkLink)
    }

    private fun check(fence: Fence): List<String> {
        val where = "${fence.file}:${fence.line}"
        val problem = when (val marker = fence.marker) {
            null -> "$where: a '${fence.language}' fence needs a marker".takeUnless { fence.language in UNMARKED }
            is Marker.Snippet -> compareSnippet(where, fence, marker.name)
            is Marker.Output -> compareOutput(where, fence, marker.name)
            is Marker.Ignore -> null
        }
        return listOfNotNull(problem) + checkCoordinates(where, fence.body)
    }

    private fun compareSnippet(where: String, fence: Fence, name: String): String? {
        val region = regions.named(name).firstOrNull() ?: return "$where: no source has the region 'snippet: $name'"
        if (region.body == fence.body) return null
        val source = "${region.file}:${region.line}"
        return "$where: the fence differs from region '$name' at $source\n" +
            LineDiff.unified(fence.body, region.body, "$where (document)", "$source (region)")
    }

    private fun compareOutput(where: String, fence: Fence, name: String): String? {
        val compute = outputs[name] ?: return "$where: DocOutputs registers no output '$name'"
        val actual = runCatching { compute() }.getOrElse { return "$where: computing output '$name' threw $it" }
        val expected = actual.replace("\r\n", "\n").split("\n").dropLastWhile { it.isBlank() }.joinToString("\n")
        if (expected == fence.body) return null
        return "$where: the fence differs from output '$name'\n" +
            LineDiff.unified(fence.body, expected, "$where (document)", "DocOutputs['$name'] (computed)")
    }

    private fun checkCoordinates(where: String, body: String): List<String> = COORDINATE.findAll(body).mapNotNull {
        val (artifact, version) = it.destructured
        when {
            artifact !in ARTIFACTS -> "$where: '$artifact' is not a published artifact"
            version != releaseVersion -> "$where: '$artifact:$version' should name the release version, $releaseVersion"
            else -> null
        }
    }.toList()

    private fun orphans(fences: List<Fence>): List<String> {
        val snippets = fences.mapNotNull { (it.marker as? Marker.Snippet)?.name }.toSet()
        val shown = fences.mapNotNull { (it.marker as? Marker.Output)?.name }.toSet()
        val regionOrphans = regions.regions.filter { !it.support && it.name !in snippets }.map {
            "${it.file}:${it.line}: no document quotes region '${it.name}'; quote it, or call it snippet-support"
        }
        val outputOrphans = outputs.keys.filter { it !in shown }.map { "DocOutputs: no document shows output '$it'" }
        return regionOrphans + outputOrphans
    }

    private fun checkLink(link: Link): String? {
        val where = "${link.file}:${link.line}"
        val path = link.target.substringBefore('#')
        val anchor = link.target.substringAfter('#', missingDelimiterValue = "").ifEmpty { null }
        val target = if (path.isEmpty()) link.file else resolve(link.file, path)
        val document = documents.firstOrNull { it.file.path == target }
        return when {
            target == null -> "$where: '${link.target}' points outside the repository"
            target.startsWith("docs/superpowers/") -> "$where: '${link.target}' points into docs/superpowers/"
            document != null && anchor != null && anchor !in document.anchors ->
                "$where: '${link.target}' names a heading $target does not have"
            document != null || fileExists(target) -> null
            else -> "$where: '${link.target}' points to $target, which does not exist"
        }
    }

    companion object {
        /** Fences that need no marker: shell, console and plain text. */
        val UNMARKED = setOf("", "text", "sh", "bash", "console")

        /** The seven published artifacts. */
        val ARTIFACTS = setOf(
            "featureflag-core",
            "featureflag-ksp",
            "featureflag-test",
            "featureflag-android",
            "featureflag-bridge",
            "featureflag-ui",
            "featureflag-bom",
        )

        private val COORDINATE = Regex("""com\.andyyhope\.featureflag:([A-Za-z0-9._-]+):([^"'\s)]+)""")

        /** [relative] resolved against the directory of [from], or null if it climbs out of the repository. */
        fun resolve(from: String, relative: String): String? {
            val segments = from.split('/').dropLast(1).toMutableList()
            for (part in relative.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                    else -> segments += part
                }
            }
            return segments.joinToString("/")
        }
    }
}
```

- [ ] **Step 5: Run the checker's tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest --tests 'docs.sync.DocsSyncCheckerTest'
```

Expected: `BUILD SUCCESSFUL`; `docs-samples/build/test-results/testDebugUnitTest/TEST-docs.sync.DocsSyncCheckerTest.xml` shows `tests="37" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Run it over the repository**

**Create** `docs-samples/src/test/kotlin/docs/sync/DocsRepository.kt`:

```kotlin
package docs.sync

import java.io.File

/**
 * The files DocsSyncTest reads from the repository at [root]: the markdown a reader sees, and the
 * sources its fences quote. docs-samples/build.gradle.kts declares every tree read here, and the link
 * targets, as the test task's inputs; a new pattern here needs a new input there.
 */
class DocsRepository(private val root: File, val versionName: String) {

    /** The root README, every markdown file directly in docs, and the samples README, whichever exist. */
    val markdown: List<TextFile> by lazy {
        val docs = File(root, "docs").listFiles { file -> file.isFile && file.extension == "md" }.orEmpty()
        (listOf(File(root, "README.md")) + docs.sortedBy { it.name } + File(root, "samples/README.md"))
            .filter { it.isFile }
            .map(::read)
    }

    /** Kotlin, script and XML files: all of docs-samples/src, and each sample's src and build.gradle.kts. */
    val sources: List<TextFile> by lazy {
        val docsSamples = walk("docs-samples/src") { true }
        val samples = walk("samples") { path -> "/src/" in path || path.endsWith("/build.gradle.kts") }
        (docsSamples + samples).map(::read)
    }

    /** Whether a repository path names a file or a directory. */
    fun exists(path: String): Boolean = File(root, path).exists()

    private fun walk(directory: String, include: (String) -> Boolean): List<File> = File(root, directory)
        .walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" }
        .filter { it.isFile && it.extension in SOURCE_EXTENSIONS && include(relative(it)) }
        .sortedBy { relative(it) }
        .toList()

    private fun read(file: File): TextFile = TextFile(relative(file), file.readText())

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    companion object {
        private val SOURCE_EXTENSIONS = setOf("kt", "kts", "xml")

        /** The repository Gradle hands the test, as `featureflag.docs.root` and `featureflag.docs.versionName`. */
        fun fromSystemProperties(): DocsRepository {
            val root = checkNotNull(System.getProperty("featureflag.docs.root")) {
                "featureflag.docs.root is not set; run the tests through Gradle"
            }
            val versionName = checkNotNull(System.getProperty("featureflag.docs.versionName")) {
                "featureflag.docs.versionName is not set; run the tests through Gradle"
            }
            return DocsRepository(File(root), versionName)
        }
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/DocOutputs.kt`:

```kotlin
package docs

/**
 * The value behind every `<!-- output: name -->` fence, computed by the code the document describes.
 * DocsSyncTest compares each fence with its entry, and fails on an entry no document shows.
 */
object DocOutputs {
    val all: Map<String, () -> String> = emptyMap()
}
```

**Create** `docs-samples/src/test/kotlin/docs/sync/DocsSyncTest.kt`:

```kotlin
package docs.sync

import docs.DocOutputs
import org.junit.Test
import kotlin.test.assertTrue

/** The repository's docs against the code they quote. DocsSyncCheckerTest pins every way this can fail. */
class DocsSyncTest {

    private val repository = DocsRepository.fromSystemProperties()

    @Test
    fun everyDocumentMatchesTheCodeItQuotes() {
        val problems = DocsSync(
            markdown = repository.markdown,
            sources = repository.sources,
            outputs = DocOutputs.all,
            versionName = repository.versionName,
            fileExists = repository::exists,
        ).problems()

        assertTrue(problems.isEmpty(), "The docs have drifted from the code:\n\n" + problems.joinToString("\n\n"))
    }

    @Test
    fun theScanFindsTheCheckerItself() {
        // A wrong root would scan nothing and pass, so prove the walk reached this module's sources.
        assertTrue(repository.sources.any { it.path == "docs-samples/src/test/kotlin/docs/sync/DocsSync.kt" })
    }
}
```

- [ ] **Step 7: Run the module's tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 39 tests (`DocsSyncCheckerTest` 37, `DocsSyncTest` 2). At this point the only markdown it reads is M4a's `samples/README.md`, and the only regions are M4a's ten (Seams 5 and 7), which its README cites: the same README and sources, copied from M4a's plan into the scratch copy, passed this test.

If `everyDocumentMatchesTheCodeItQuotes` fails, M4a's files have drifted from M4a's plan (a fence no longer equal to its region, a region added and not cited, a link to a missing file). That is an M4a defect: stop, report each problem line to the controller as an M4a fix, and do not loosen the checker or edit the samples in this task.

- [ ] **Step 8: Run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL`, including `:docs-samples:testDebugUnitTest`, `:docs-samples:detekt`, `:docs-samples:lintDebug` and, at the root, `detekt` over `docs-samples/build.gradle.kts`.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts docs-samples
git commit -m "Add docs-samples, whose DocsSyncTest fails when a doc's code differs from compiled, tested code" -m "The checker is tested on fixture strings for every way the docs can drift before it reads the repository." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B5: Declaring flags, values, records, remote overrides and the audit

**Files:**
- Create: `docs-samples/src/test/kotlin/docs/declaringflags/DeclaringFlags.kt`, `Flag.kt`, `AliasedFlags.kt`, `DeclaringFlagsTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/flagvalues/FlagValues.kt`, `FlagValuesTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/recordflags/RecordFlags.kt`, `RecordFlagsTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/remoteoverrides/RemoteOverrides.kt`, `RemoteOverridesTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/validatingaconfig/ValidatingAConfig.kt`, `ValidatingAConfigTest.kt`
- Create: `docs-samples/src/test/resources/staging.json`
- Modify (whole file): `docs-samples/src/test/kotlin/docs/DocOutputs.kt`
- Create: `docs/DeclaringFlags.md`, `docs/FlagValues.md`, `docs/RecordFlags.md`, `docs/RemoteOverrides.md`, `docs/ValidatingAConfig.md`

**Interfaces:**
- Consumes: B4's checker and `DocOutputs`; B1's `applyPayload` is not used here (B6 is). Public API only.
- Produces: the regions and outputs these five docs show, which later docs also quote: `records-declaration`, `records-remote-flag`, `records-resolution`, `records-added-field`, `records-endpoints-payload` (by `RemoteOverrides.md`, `Troubleshooting.md`, `README.md`); `declaring-flag-alias`, `declaring-duplicate-keys` (by `Troubleshooting.md`); `remote-overview`, `remote-apply-result`, `remote-precedence` (by `Troubleshooting.md`, `README.md`).

Each iOS article is ported section by section (`../feature-flags/Sources/FeatureFlag/FeatureFlag.docc/<Article>.md`). What changes for Android, as the docs note maps it: interfaces and getters instead of macros and `default:`; `AppFlagsSpec.newOnboarding` instead of `$newOnboarding`; Google Pay instead of Apple Pay; no `FlagValue` conformance (FlagValues says what to do instead), no keyed subscript on `FlagRecords`, no property lists, no `format:` argument; the companion's record editor uses an "Actions" menu rather than swipes. The forward links to SourcesAndPrecedence, BuildingACompanionApp and Troubleshooting are plain text here; B6 and B7 turn them into links.

- [ ] **Step 1: Write the code the docs will quote**

**Create** `docs-samples/src/test/kotlin/docs/declaringflags/DeclaringFlags.kt`:

```kotlin
package docs.declaringflags

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.schema.FlagSchema
import kotlin.test.assertFailsWith

// region snippet: declaring-a-flag
@FlagContainer
interface OnboardingFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false
}
// endregion

@FlagContainer
interface RemoteOnboardingFlags {
    // region snippet: declaring-remote-key
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false
    // endregion
}

// region snippet: declaring-nesting
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
// endregion

/** Two names that the default encoding turns into one key. */
@FlagContainer
interface CollidingFlags {
    @Flag(description = "Refuse plain HTTP")
    val useHTTPSOnly: Boolean get() = true

    @Flag(description = "Refuse plain HTTP, spelled differently")
    val useHttpsOnly: Boolean get() = true
}

// region snippet: declaring-without-a-pole
@FlagContainer
interface OnboardingPreviewFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = true
}
// endregion

// region snippet: declaring-public-container
@FlagContainer
public interface StoreFlags {
    @Flag(description = "Show the redesigned onboarding")
    public val newOnboarding: Boolean get() = false
}
// endregion

// region snippet: declaring-container-description
@FlagContainer(description = "Everything the checkout team can turn on")
interface CheckoutTeamFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
// endregion

/** What building a pole over [CollidingFlags] fails with. */
fun duplicateKeyMessage(): String =
    assertFailsWith<IllegalStateException> { FlagSchema.of(CollidingFlagsSpec) }.message.orEmpty()
```

**Create** `docs-samples/src/test/kotlin/docs/declaringflags/Flag.kt`, a type of the module's own called `Flag`, which the alias sample works around:

```kotlin
package docs.declaringflags

/** A type of this module's own called Flag, which is why AliasedFlags imports the annotation under another name. */
data class Flag(val country: String, val isRaised: Boolean)
```

**Create** `docs-samples/src/test/kotlin/docs/declaringflags/AliasedFlags.kt`. The region holds the imports, because the imports are the point:

```kotlin
package docs.declaringflags

// region snippet: declaring-flag-alias
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.Flag as FeatureFlag

@FlagContainer
interface AliasedFlags {
    @FeatureFlag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false
}
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/declaringflags/DeclaringFlagsTest.kt`:

```kotlin
package docs.declaringflags

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/DeclaringFlags.md. */
class DeclaringFlagsTest {

    private val source = SnapshotSource()

    @Test
    fun aDeclaredFlagReadsItsDefault() {
        val pole = FlagPole(OnboardingFlagsSpec, sources = listOf(source))

        assertFalse(pole.flags.newOnboarding)
        assertEquals("Show the redesigned onboarding", pole.schema.flags.single().description)
        assertEquals("featureToggles.onboarding.v2", RemoteOnboardingFlagsSpec.newOnboarding.remoteKey)
    }

    @Test
    fun nestedFlagsAreKeyedByTheirPath() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
        source.setBox(FlagKey("checkout.express.one-tap"), FlagValueBox.Bool(true))

        // region snippet: declaring-nested-read
        val oneTap = pole.flags.checkout.express.oneTap // stored under "checkout.express.one-tap"
        // endregion

        assertTrue(oneTap)
        assertEquals(FlagKey("checkout.express.one-tap"), pole.key(AppFlagsSpec.checkout.express.oneTap))
    }

    @Test
    fun encodingsTurnThePathIntoAKey() {
        val path = AppFlagsSpec.checkout.express.oneTap.keyPath

        assertEquals("checkout.express.one-tap", KeyEncoding.Kebabcase.key(path).rawValue)
        assertEquals("checkout.express.one_tap", KeyEncoding.Snakecase.key(path).rawValue)
        assertEquals("checkout.express.oneTap", KeyEncoding.Verbatim.key(path).rawValue)

        // region snippet: declaring-key-encoding
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source), keyEncoding = KeyEncoding.Snakecase)
        // endregion

        assertEquals(FlagKey("checkout.express.one_tap"), pole.key(AppFlagsSpec.checkout.express.oneTap))
    }

    @Test
    fun wordSplittingHandlesAcronymsAndDigits() {
        assertEquals("use-https-only", KeyEncoding.Kebabcase.key(FlagKeyPath(listOf("useHTTPSOnly"))).rawValue)
        assertEquals("checkout-v2", KeyEncoding.Kebabcase.key(FlagKeyPath(listOf("checkoutV2"))).rawValue)
    }

    @Test
    fun aCustomEncoding() {
        // region snippet: declaring-custom-encoding
        val screaming = KeyEncoding(separator = "__") { it.uppercase() }
        // endregion

        assertEquals("CHECKOUT__EXPRESS__ONETAP", screaming.key(AppFlagsSpec.checkout.express.oneTap.keyPath).rawValue)
        assertEquals("ONETAP", screaming.key(FlagKeyPath(listOf("onetap"))).rawValue)
    }

    @Test
    fun twoFlagsOnOneKeyFailWhenTheSchemaIsBuilt() {
        assertTrue(duplicateKeyMessage().startsWith("Flags must resolve to unique keys"))
    }

    // region snippet: declaring-unique-keys-test
    @Test
    fun everyFlagHasItsOwnKey() {
        FlagSchema.of(AppFlagsSpec, keyEncoding = KeyEncoding.Kebabcase)
    }
    // endregion

    @Test
    fun aFlagCarriesItsOwnMetadata() = runTest {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))

        // region snippet: declaring-metadata
        val flag = AppFlagsSpec.newOnboarding

        val key = pole.key(flag) // FlagKey("new-onboarding")
        val description = flag.description // "Show the redesigned onboarding"
        val defaultValue = flag.defaultValue // false
        val remoteKey = flag.remoteKey // "featureToggles.onboarding.v2"
        val current = pole[flag] // what it resolves to right now
        val changes = pole.flow(flag) // a Flow<Boolean>
        // endregion

        assertEquals(FlagKey("new-onboarding"), key)
        assertEquals("Show the redesigned onboarding", description)
        assertFalse(defaultValue)
        assertEquals("featureToggles.onboarding.v2", remoteKey)
        assertFalse(current)
        assertFalse(changes.first())
    }

    @Test
    fun flagsWithoutAPoleReportTheirDefault() {
        // region snippet: declaring-defaults-read
        val showsNewOnboarding = OnboardingPreviewFlagsSpec.flagDefaults.newOnboarding // true, with no pole anywhere
        // endregion

        assertTrue(showsNewOnboarding)
    }

    @Test
    fun publicAliasedAndDescribedContainersWork() {
        assertFalse(FlagPole(StoreFlagsSpec, sources = listOf(source)).flags.newOnboarding)
        assertFalse(FlagPole(AliasedFlagsSpec, sources = listOf(source)).flags.newOnboarding)
        assertEquals("Everything the checkout team can turn on", FlagSchema.of(CheckoutTeamFlagsSpec).description)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/flagvalues/FlagValues.kt`:

```kotlin
package docs.flagvalues

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI
import java.time.Instant

// region snippet: values-built-in
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
// endregion

@FlagContainer
interface CollectionFlags {
    // region snippet: values-collections
    @Flag(description = "Markets to enable")
    val markets: List<String> get() = emptyList()

    @Flag(description = "Per-market rollout share")
    val rolloutByMarket: Map<String, Double> get() = emptyMap()
    // endregion

    // region snippet: values-records-flag
    @Flag(description = "Endpoints")
    val endpoints: FlagRecords<Endpoint> get() = FlagRecords()
    // endregion

    // region snippet: values-enum-flag
    @Flag(description = "Pricing tier to present")
    val tier: Tier get() = Tier.Free
    // endregion

    @Flag(description = "Pricing tier, stored in lowercase")
    val storedTier: StoredTier get() = StoredTier.Free

    @Flag(description = "How hard to retry")
    val retryPolicy: RetryPolicy get() = RetryPolicy.Once
}

// region snippet: values-record
@FlagRecord
data class Endpoint(
    val name: String,
    val url: URI,
    val enabled: Boolean,
)
// endregion

// region snippet: values-enum
enum class Tier { Free, Pro, Enterprise }
// endregion

// region snippet: values-string-enum
enum class StoredTier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
    Enterprise("enterprise"),
}
// endregion

// region snippet: values-int-enum
enum class RetryPolicy(override val flagRawValue: Long) : FlagIntEnum {
    None(0),
    Once(1),
    Aggressive(3),
}
// endregion

// region snippet: values-wrapped-type
@FlagContainer
interface PricingFlags {
    @Flag(description = "Discount at checkout, as a fraction of the price")
    val discountAmount: Double get() = 0.1
}

@JvmInline
value class Percentage(val fraction: Double)

val PricingFlags.discount: Percentage
    get() = Percentage(discountAmount)
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/flagvalues/FlagValuesTest.kt`:

```kotlin
package docs.flagvalues

import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.importJson
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/FlagValues.md. */
class FlagValuesTest {

    @Test
    fun everyBuiltInTypeReadsItsDefault() {
        val flags = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource())).flags

        assertFalse(flags.enabled)
        assertEquals(20, flags.pageSize)
        assertEquals(5_000_000_000, flags.quota)
        assertEquals(0.5, flags.rolloutShare)
        assertEquals(1.0f, flags.scale)
        assertEquals("beta", flags.channel)
        assertContentEquals(ByteArray(0), flags.payload)
        assertEquals(Instant.EPOCH, flags.launchesAt)
        assertEquals(URI("https://example.com"), flags.endpoint)
    }

    @Test
    fun intAndLongAreBothStoredAsInt() {
        val types = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource())).schema.valueTypes

        assertEquals(FlagValueType.Int, types[FlagKey("page-size")])
        assertEquals(FlagValueType.Int, types[FlagKey("quota")])
        assertEquals(FlagValueType.Url, types[FlagKey("endpoint")])
    }

    @Test
    fun collectionsRecordsAndEnumsAreDeclaredLikeAnyOtherFlag() {
        val pole = FlagPole(CollectionFlagsSpec, sources = listOf(SnapshotSource()))

        assertEquals(emptyList(), pole.flags.markets)
        assertEquals(emptyMap(), pole.flags.rolloutByMarket)
        assertEquals(emptyList(), pole.flags.endpoints)
        assertEquals(Tier.Free, pole.flags.tier)
        assertEquals(RetryPolicy.Once, pole.flags.retryPolicy)
    }

    @Test
    fun everyEnumPublishesItsCases() {
        val cases = FlagPole(CollectionFlagsSpec, sources = listOf(SnapshotSource())).schema.valueCases

        assertEquals(listOf("Free", "Pro", "Enterprise").map(FlagValueBox::String), cases[FlagKey("tier")])
        assertEquals(listOf("free", "pro", "enterprise").map(FlagValueBox::String), cases[FlagKey("stored-tier")])
        assertEquals(listOf(0L, 1L, 3L).map(FlagValueBox::Int), cases[FlagKey("retry-policy")])
    }

    @Test
    fun aCaseThisBuildDoesNotHaveIsRefused() {
        val pole = FlagPole(CollectionFlagsSpec, sources = listOf(SnapshotSource()))

        val rejected = assertFailsWith<FlagImportException.Rejected> {
            pole.importJson("""{"formatVersion": 1, "values": {"stored-tier": "platinum"}}""".encodeToByteArray())
        }

        assertEquals(listOf(FlagImportProblem.Kind.UnknownCase), rejected.problems.map { it.kind })
        assertEquals(StoredTier.Free, pole.flags.storedTier)
    }

    @Test
    fun aTypeOfYourOwnWrapsOneTheProcessorKnows() {
        val pole = FlagPole(PricingFlagsSpec, sources = listOf(SnapshotSource()))

        assertEquals(Percentage(0.1), pole.flags.discount)
    }

    @Test
    fun boxesCanBeReadAndWrittenDirectly() {
        // region snippet: values-boxes
        val box = FlagValueBox.Bool(true)
        val isBool = box.matches(FlagValueType.Bool) // true
        val value = FlagCodecs.boolean.decode(box) // true
        // endregion

        assertTrue(isBool)
        assertEquals(true, value)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/recordflags/RecordFlags.kt`:

```kotlin
package docs.recordflags

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.FlagRecordProperty
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

// region snippet: records-declaration
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
// endregion

// region snippet: records-key
@FlagRecord
data class Endpoint(
    @FlagRecordKey val name: String,
    val url: URI,
)
// endregion

@FlagContainer
interface EndpointFlags {
    // region snippet: records-remote-flag
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<Endpoint> get() = FlagRecords()
    // endregion
}

// region snippet: records-renamed-field
@FlagRecord
data class PaymentOption(
    @FlagRecordKey val name: String,
    @FlagRecordProperty(key = "google_pay")
    val googlePay: Boolean,
    val minimumSpend: Double?, // absent or null reads as null
)
// endregion

// region snippet: records-nested
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
// endregion

// region snippet: records-added-field
@FlagRecord
data class Mirror(
    val name: String,
    val url: URI,
    val weight: Int = 1, // added in a later build
)
// endregion

@FlagContainer
interface BackendFlags {
    @Flag(description = "Payment options", remoteKey = "config.paymentOptions")
    val paymentOptions: FlagRecords<PaymentOption> get() = FlagRecords()

    @Flag(description = "Payment providers", remoteKey = "config.providers")
    val providers: FlagRecords<PaymentProvider> get() = FlagRecords()

    @Flag(description = "Mirrors")
    val mirrors: FlagRecords<Mirror> get() = FlagRecords()
}
```

**Create** `docs-samples/src/test/kotlin/docs/recordflags/RecordFlagsTest.kt`. The payloads are top-level constants because the doc shows them as `output:` fences and the tests apply them:

```kotlin
package docs.recordflags

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What docs/RecordFlags.md says a backend can send for `config.endpoints`. */
val endpointsPayload = """
    { "config": { "endpoints": [
        { "name": "staging", "url": "https://staging.example", "weight": 7 }
    ] } }
""".trimIndent()

/** A backend whose field names are not the record's, and which leaves one out. */
val renamedFieldsPayload = """
    { "config": { "paymentOptions": [
        { "name": "card", "google_pay": false },
        { "name": "wallet", "google_pay": true, "minimumSpend": 10 }
    ] } }
""".trimIndent()

/** One record with a nested list, as a backend writes it. */
val nestedRecord = """
    { "name": "Visa", "limits": [ { "currency": "AUD", "maximum": 500 } ] }
""".trimIndent()

/** The problem a list with two records keyed "staging" is refused with. */
fun duplicateRecordKeyProblem(): String {
    val payload = """{"config": {"endpoints": [{"name": "staging", "url": "https://a.example"},""" +
        """ {"name": "staging", "url": "https://b.example"}]}}"""
    val rejected = assertFailsWith<RemoteOverrideException.Rejected> {
        RemoteOverrideSource(EndpointFlagsSpec).apply(payload.encodeToByteArray())
    }
    return rejected.problems.single().toString()
}

/** How a list of one record is stored. */
fun storedRecordText(): String {
    val source = SnapshotSource()
    val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
    pole.setOverride(AppFlagsSpec.paymentMethods, FlagRecords(listOf(visa)))
    return (source.values.getValue(FlagKey("payment-methods")) as FlagValueBox.String).value
}

/** The schema a host with only [AppFlags] publishes. */
fun recordSchema(): String =
    FlagSchema.of(AppFlagsSpec, generatedAt = Instant.parse("2026-01-01T00:00:00Z")).jsonBytes().decodeToString()

private val visa = PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0)

/** docs/RecordFlags.md. */
class RecordFlagsTest {

    private val source = SnapshotSource()
    private val shown = mutableListOf<String>()

    private fun show(name: String) {
        shown += name
    }

    @Test
    fun readingGivesYourOwnTypeBack() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))

        // region snippet: records-reading
        for (method in pole.flags.paymentMethods.filter { it.enabled }) {
            show(method.name)
        }
        // endregion

        assertEquals(listOf("Visa", "Google Pay"), shown)
    }

    @Test
    fun aRecordIsFoundByItsKey() {
        val remote = RemoteOverrideSource(EndpointFlagsSpec)
        val pole = FlagPole(EndpointFlagsSpec, sources = listOf(remote))
        remote.apply(endpointsPayload.encodeToByteArray())

        // region snippet: records-find-by-key
        val staging = pole.flags.endpoints.firstOrNull { it.name == "staging" }?.url
        // endregion

        assertEquals(URI("https://staging.example"), staging)
    }

    @Test
    fun twoRecordsWithOneKeyAreRefused() {
        assertTrue(duplicateRecordKeyProblem().contains("expected every record to have its own name"))
    }

    @Test
    fun aListIsStoredAsJsonText() {
        assertEquals("""[{"enabled":true,"kind":"card","minimumSpend":0,"name":"Visa"}]""", storedRecordText())
        assertTrue(recordSchema().contains("\"recordShape\""))
    }

    @Test
    fun aRecordFieldMayBeRenamedOrLeftOut() {
        val remote = RemoteOverrideSource(BackendFlagsSpec)
        val pole = FlagPole(BackendFlagsSpec, sources = listOf(remote))

        remote.apply(renamedFieldsPayload.encodeToByteArray())

        assertEquals(
            listOf(PaymentOption("card", googlePay = false, minimumSpend = null), PaymentOption("wallet", true, 10.0)),
            pole.flags.paymentOptions.values,
        )
    }

    @Test
    fun aFieldCanBeAListOfRecords() {
        val remote = RemoteOverrideSource(BackendFlagsSpec)
        val pole = FlagPole(BackendFlagsSpec, sources = listOf(remote))

        remote.apply("""{"config": {"providers": [$nestedRecord]}}""".encodeToByteArray())

        assertEquals(listOf(SpendLimit("AUD", 500.0)), pole.flags.providers.single().limits.values)
    }

    @Test
    fun aFieldAddedLaterIsFilledFromItsDefault() {
        val pole = FlagPole(BackendFlagsSpec, sources = listOf(source))
        source.setBox(FlagKey("mirrors"), FlagValueBox.String("""[{"name":"eu","url":"https://eu.example"}]"""))

        assertEquals(listOf(Mirror("eu", URI("https://eu.example"), weight = 1)), pole.flags.mirrors.values)
    }

    @Test
    fun aListThatCannotBeReadFallsBackToTheDefault() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
        source.setBox(FlagKey("payment-methods"), FlagValueBox.String("""[{"name":"Visa"}]"""))

        // region snippet: records-resolution
        val stored = pole.resolution(AppFlagsSpec.paymentMethods)
        // endregion

        assertEquals(AppFlagsSpec.paymentMethods.defaultValue, pole.flags.paymentMethods)
        assertEquals(FlagValueBox.String("""[{"name":"Visa"}]"""), stored.box)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/remoteoverrides/RemoteOverrides.kt`:

```kotlin
package docs.remoteoverrides

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum

enum class Tier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
}

@FlagContainer
interface AppFlags {
    // region snippet: remote-flag
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false
    // endregion

    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 20

    @Flag(description = "Pricing tier at checkout", remoteKey = "config.tier")
    val checkoutTier: Tier get() = Tier.Free

    @Flag(description = "Whether the first experiment runs", remoteKey = "experiments.0.enabled")
    val firstExperiment: Boolean get() = false

    @Flag(description = "Share of people who see the new onboarding", remoteKey = "config.rolloutShare")
    val rolloutShare: Double get() = 0.0
}

/** Flags whose remote keys are experiment names rather than paths. */
@FlagContainer
interface ExperimentFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "onboarding-v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Offer Google Pay", remoteKey = "google-pay")
    val googlePay: Boolean get() = false
}

// region snippet: remote-mapper
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
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/remoteoverrides/RemoteOverridesTest.kt`:

```kotlin
package docs.remoteoverrides

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The payload docs/RemoteOverrides.md uses to show a dot path. */
val onboardingPayload = """
    {
      "featureToggles": {
        "onboarding": { "v2": true }
      }
    }
""".trimIndent()

/** A path indexing into an array. */
val arrayPathPayload = """
    { "experiments": [ { "enabled": true } ] }
""".trimIndent()

/** A payload no path can address. */
val experimentsPayload = """
    {
      "experiments": [
        { "name": "onboarding-v2", "state": "on" },
        { "name": "google-pay", "state": "off" }
      ]
    }
""".trimIndent()

/** What a payload with three bad values is refused with. */
fun rejectionReport(): String {
    val payload = """{"featureToggles": {"onboarding": {"v2": "yes"}},""" +
        """ "config": {"tier": "gold", "pageSize": [1, 2, 3]}}"""
    return assertFailsWith<RemoteOverrideException.Rejected> {
        RemoteOverrideSource(AppFlagsSpec).apply(payload.encodeToByteArray())
    }.message.orEmpty()
}

/** Stands in for whatever the app fetches its configuration with. */
class HttpClient(private val responses: Map<URI, String>) {
    fun get(url: URI): ByteArray = responses.getValue(url).encodeToByteArray()
}

/** docs/RemoteOverrides.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RemoteOverridesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val local = SnapshotSource()
    private val configUrl = URI("https://config.example.com/flags.json")
    private val httpClient = HttpClient(mapOf(configUrl to onboardingPayload))

    @Test
    fun theSourceDecodesWhatTheAppFetched() {
        // region snippet: remote-overview
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(local, remote))

        val bytes = httpClient.get(configUrl)
        remote.apply(bytes)
        // endregion

        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun aPathCanIndexIntoAnArray() {
        val remote = RemoteOverrideSource(AppFlagsSpec)

        remote.apply(arrayPathPayload.encodeToByteArray())

        assertTrue(FlagPole(AppFlagsSpec, sources = listOf(remote)).flags.firstExperiment)
    }

    @Test
    fun aSuccessfulApplySaysWhatItSetAndWhatItMissed() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val bytes = onboardingPayload.encodeToByteArray()

        // region snippet: remote-apply-result
        val result = remote.apply(bytes)

        val applied = result.appliedKeys // flags this payload set
        val absent = result.absentKeys // flags with a remoteKey the payload did not mention
        // endregion

        assertEquals(listOf(FlagKey("new-onboarding")), applied)
        assertEquals(listOf("page-size", "checkout-tier", "first-experiment", "rollout-share").map(::FlagKey), absent)
    }

    @Test
    fun applyingReplacesEverything() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote))
        remote.apply(onboardingPayload.encodeToByteArray())

        remote.apply(arrayPathPayload.encodeToByteArray())

        assertFalse(pole.flags.newOnboarding)
        remote.clear()
        assertFalse(pole.flags.firstExperiment)
    }

    @Test
    fun oneBadValueRejectsTheWholePayload() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote))
        val bytes = """{"featureToggles": {"onboarding": {"v2": true}}, "config": {"tier": "gold"}}""".toByteArray()

        // region snippet: remote-strict
        try {
            remote.apply(bytes)
        } catch (rejected: RemoteOverrideException.Rejected) {
            for (problem in rejected.problems) {
                Log.w("Flags", "${problem.remoteKey}: ${problem.kind}") // TypeMismatch, UnknownCase or UnknownKey
            }
        }
        // endregion

        assertFalse(pole.flags.newOnboarding)
        assertEquals("config.tier: UnknownCase", ShadowLog.getLogsForTag("Flags").single().msg)
        assertTrue(rejectionReport().startsWith("Nothing was applied. 3 problems:"))
    }

    @Test
    fun aWholeNumberSatisfiesADoubleFlagButAQuotedTrueIsNotABoolean() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote))

        remote.apply("""{"config": {"rolloutShare": 1}}""".encodeToByteArray())

        assertEquals(1.0, pole.flags.rolloutShare)
        assertFailsWith<RemoteOverrideException.Rejected> {
            remote.apply("""{"featureToggles": {"onboarding": {"v2": "true"}}}""".encodeToByteArray())
        }
    }

    @Test
    fun aMapperReadsWhatNoPathCan() {
        // region snippet: remote-mapper-source
        val remote = RemoteOverrideSource(ExperimentFlagsSpec, mapper = ExperimentListMapper)
        // endregion
        val pole = FlagPole(ExperimentFlagsSpec, sources = listOf(remote))

        remote.apply(experimentsPayload.encodeToByteArray())

        assertTrue(pole.flags.newOnboarding)
        assertFalse(pole.flags.googlePay)
    }

    @Test
    fun aHandSetValueSurvivesTheNextRefresh() {
        val sharedPreferences = SharedPreferencesSource.companion(context)
        val remote = RemoteOverrideSource(AppFlagsSpec)

        // region snippet: remote-precedence
        val pole = FlagPole(AppFlagsSpec, sources = listOf(sharedPreferences, remote))
        // endregion

        pole.setOverride(AppFlagsSpec.newOnboarding, false)
        remote.apply(onboardingPayload.encodeToByteArray())
        assertFalse(pole.flags.newOnboarding)
        sharedPreferences.close()
    }

    @Test
    fun theSourceAndThePoleShareAKeyEncoding() {
        // region snippet: remote-key-encoding
        val remote = RemoteOverrideSource(AppFlagsSpec, keyEncoding = KeyEncoding.Snakecase)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(remote), keyEncoding = KeyEncoding.Snakecase)
        // endregion

        remote.apply(onboardingPayload.encodeToByteArray())

        assertTrue(pole.flags.newOnboarding)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/validatingaconfig/ValidatingAConfig.kt`:

```kotlin
package docs.validatingaconfig

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum

enum class Tier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
}

@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 20

    @Flag(description = "Pricing tier at checkout", remoteKey = "config.tier")
    val checkoutTier: Tier get() = Tier.Free

    @Flag(description = "Markets the app is live in", remoteKey = "config.markets")
    val markets: List<String> get() = listOf("AU", "NZ")
}

@FlagRecord
data class PaymentMethod(
    @FlagRecordKey val name: String,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Payment methods offered at checkout", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", enabled = true, minimumSpend = 0.0),
                PaymentMethod("Google Pay", enabled = true, minimumSpend = 5.0),
                PaymentMethod("Bank transfer", enabled = false, minimumSpend = 50.0),
            ),
        )
}

/** A config with a typo, an unknown tier, and metadata no flag reads. */
val draftConfig = """
    {
      "featureToggles": { "onboarding": { "v2": true } },
      "config": { "page_size": 25, "tier": "gold", "markets": ["AU", "NZ"] },
      "meta": { "version": 3 }
    }
""".trimIndent()

/** The same config, fixed. Also on the test classpath as staging.json. */
val stagingConfig = """
    {
      "featureToggles": { "onboarding": { "v2": true } },
      "config": { "pageSize": 20, "tier": "pro", "markets": ["AU", "NZ"] },
      "meta": { "version": 3 }
    }
""".trimIndent()

/** A checkout config that changes one payment method, drops one and adds one. */
val checkoutConfig = """
    {
      "config": {
        "pageSize": 25,
        "paymentMethods": [
          { "name": "Visa", "enabled": true, "minimumSpend": 0 },
          { "name": "Google Pay", "enabled": false, "minimumSpend": 10 },
          { "name": "PayPal", "enabled": true, "minimumSpend": 0 }
        ]
      }
    }
""".trimIndent()

/** What printing the audit of [draftConfig] shows. */
fun draftAuditReport(): String = FlagMappingAudit(AppFlagsSpec, draftConfig.encodeToByteArray()).toString()

/** What [stagingConfig] changes. */
fun stagingDefaultsReport(): String =
    FlagMappingAudit(AppFlagsSpec, stagingConfig.encodeToByteArray()).defaultsDescription

/** What [checkoutConfig] changes, record by record. */
fun checkoutDefaultsReport(): String =
    FlagMappingAudit(CheckoutFlagsSpec, checkoutConfig.encodeToByteArray()).defaultsDescription
```

**Create** `docs-samples/src/test/kotlin/docs/validatingaconfig/ValidatingAConfigTest.kt`:

```kotlin
package docs.validatingaconfig

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.audit.FlagMappingAuditException
import com.andyyhope.featureflag.test.assertFlagsFullyMapped
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/ValidatingAConfig.md. */
class ValidatingAConfigTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theAuditReportsBothDirections() {
        val configFile = folder.newFile("draft.json").apply { writeText(draftConfig) }

        // region snippet: validating-audit
        val json = configFile.readBytes()
        val audit = FlagMappingAudit(AppFlagsSpec, json)

        println(audit)
        // endregion

        assertEquals(listOf(FlagKey("page-size")), audit.absent)
        assertEquals(listOf("config.page_size", "meta.version"), audit.unconsumed)
        assertEquals(draftAuditReport(), audit.toString())
    }

    @Test
    fun unconsumedValuesFailOnlyAStrictAudit() {
        val audit = FlagMappingAudit(AppFlagsSpec, stagingConfig.encodeToByteArray())

        // region snippet: validating-complete
        val complete = audit.isComplete // absent and mismatched only
        // endregion

        // region snippet: validating-strict
        val everyValueRead = audit.isComplete(strict = true) // every value must be read
        val everyValueButMeta = audit.isComplete(strict = true, ignoring = listOf("meta")) // except under meta
        // endregion

        assertTrue(complete)
        assertFalse(everyValueRead)
        assertTrue(everyValueButMeta)
    }

    // region snippet: validating-test-helper
    @Test
    fun stagingConfigMapsEveryFlag() {
        val json = checkNotNull(javaClass.getResource("/staging.json")).readBytes()
        assertFlagsFullyMapped(AppFlagsSpec, json, strict = true, ignoring = listOf("meta"))
    }
    // endregion

    @Test
    fun theStagingResourceIsTheConfigTheDocShows() {
        assertEquals(stagingConfig, checkNotNull(javaClass.getResource("/staging.json")).readText().trimEnd())
    }

    @Test
    fun theHelperFailsWithTheWholeReport() {
        val json = draftConfig.encodeToByteArray()

        val error = assertFailsWith<AssertionError> { assertFlagsFullyMapped(AppFlagsSpec, json) }

        assertEquals("\n" + draftAuditReport(), error.message)
    }

    @Test
    fun theAuditWorksWithAnyTestFramework() {
        val json = stagingConfig.encodeToByteArray()

        // region snippet: validating-framework-agnostic
        val audit = FlagMappingAudit(AppFlagsSpec, json)
        assertTrue(audit.isComplete, audit.toString()) // any test framework
        audit.requireComplete(strict = true, ignoring = listOf("meta")) // throws the report
        // endregion

        assertFailsWith<FlagMappingAuditException.Incomplete> { audit.requireComplete(strict = true) }
    }

    @Test
    fun theAuditComparesEveryValueWithItsDefault() {
        val audit = FlagMappingAudit(AppFlagsSpec, stagingConfig.encodeToByteArray())

        // region snippet: validating-defaults
        println(audit.defaultsDescription)
        // endregion

        assertEquals(listOf("checkout-tier", "new-onboarding").map(::FlagKey), audit.changesDefault)
        assertEquals(listOf("markets", "page-size").map(::FlagKey), audit.matchesDefault)
        assertEquals(stagingDefaultsReport(), audit.defaultsDescription)
    }

    @Test
    fun aRecordFlagIsComparedRecordByRecord() {
        val records = FlagMappingAudit(CheckoutFlagsSpec, checkoutConfig.encodeToByteArray()).defaults
            .single { it.key == FlagKey("payment-methods") }
            .records

        assertEquals(listOf("Google Pay", "Bank transfer", "PayPal"), records?.map { it.identifier })
        assertTrue(checkoutDefaultsReport().contains("Google Pay"))
    }
}
```

**Create** `docs-samples/src/test/resources/staging.json`, the config the test-helper sample reads from the classpath (`theStagingResourceIsTheConfigTheDocShows` keeps it equal to `stagingConfig`):

```json
{
  "featureToggles": { "onboarding": { "v2": true } },
  "config": { "pageSize": 20, "tier": "pro", "markets": ["AU", "NZ"] },
  "meta": { "version": 3 }
}
```

- [ ] **Step 2: Register the outputs**

**Replace** `docs-samples/src/test/kotlin/docs/DocOutputs.kt` with:

```kotlin
package docs

import docs.declaringflags.duplicateKeyMessage
import docs.recordflags.duplicateRecordKeyProblem
import docs.recordflags.endpointsPayload
import docs.recordflags.nestedRecord
import docs.recordflags.recordSchema
import docs.recordflags.renamedFieldsPayload
import docs.recordflags.storedRecordText
import docs.remoteoverrides.arrayPathPayload
import docs.remoteoverrides.experimentsPayload
import docs.remoteoverrides.onboardingPayload
import docs.remoteoverrides.rejectionReport
import docs.validatingaconfig.checkoutDefaultsReport
import docs.validatingaconfig.draftAuditReport
import docs.validatingaconfig.stagingDefaultsReport

/**
 * The value behind every `<!-- output: name -->` fence, computed by the code the document describes.
 * DocsSyncTest compares each fence with its entry, and fails on an entry no document shows.
 */
object DocOutputs {
    val all: Map<String, () -> String> = mapOf(
        // DeclaringFlags.md
        "declaring-duplicate-keys" to ::duplicateKeyMessage,
        // RecordFlags.md
        "records-duplicate-key" to ::duplicateRecordKeyProblem,
        "records-stored-text" to ::storedRecordText,
        "records-schema" to ::recordSchema,
        "records-endpoints-payload" to { endpointsPayload },
        "records-renamed-payload" to { renamedFieldsPayload },
        "records-nested-record" to { nestedRecord },
        // RemoteOverrides.md
        "remote-onboarding-payload" to { onboardingPayload },
        "remote-array-payload" to { arrayPathPayload },
        "remote-rejection-report" to ::rejectionReport,
        "remote-experiments-payload" to { experimentsPayload },
        // ValidatingAConfig.md
        "validating-audit-report" to ::draftAuditReport,
        "validating-defaults-report" to ::stagingDefaultsReport,
        "validating-record-defaults-report" to ::checkoutDefaultsReport,
    )
}
```

- [ ] **Step 3: Run the tests and watch DocsSyncTest fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
```

Expected: `BUILD FAILED` with only `DocsSyncTest > everyDocumentMatchesTheCodeItQuotes FAILED` (81 tests, 80 passing). Its message lists 60 problems: one `no document quotes region '…'` for each of the 46 regions, and one `DocOutputs: no document shows output '…'` for each of the 14 outputs. Every sample now compiles and runs; nothing shows it yet.

- [ ] **Step 4: Write the five docs**

**Create** `docs/DeclaringFlags.md`:

````markdown
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

The getter runs once, against the compiled defaults, and every way of reading the flag
reports that one value. A getter that reads another flag sees that flag's compiled
default, never an override, and one that calls `Instant.now()` keeps the moment it first
ran.

**A flag's type cannot be nullable.** `val endpoint: String?` is rejected, because a flag
always has a value — the getter is what it falls back to when no source supplies one,
and `null` already means "no source supplied one" everywhere beneath. Use a sentinel the
type already has (`""`), or an enum with an entry for unset, which also earns you a picker
in the companion instead of a free-text field.

`remoteKey` is optional, and says where this flag lives in a backend payload. A flag
without one is not remotely overridable at all — see
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

Every spec has `flagDefaults`, an instance whose flags all report their compiled default.
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

If your package has a type of its own called `Flag`, the two names collide. A wildcard
`import com.andyyhope.featureflag.*` loses to your type, and `@Flag` then fails with
`illegal annotation class 'Flag'`. An explicit `import com.andyyhope.featureflag.Flag`
wins instead, which hides your own type in that file. Import the annotation under another
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
````

**Create** `docs/FlagValues.md`:

````markdown
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
````

**Create** `docs/RecordFlags.md`:

````markdown
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
````

**Create** `docs/RemoteOverrides.md`:

````markdown
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

**A flag with no `remoteKey` is not remotely overridable at all.** That is the safety
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
`Double` flag. That is exact widening, not coercion — `"true"` is still not a `Boolean`.

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
````

**Create** `docs/ValidatingAConfig.md`:

````markdown
# Validating a remote config

Check, without running the app, that a config file wires up every flag it should.

## Overview

A large config file has too many values to trace by hand, and two ways for one to be
wrong that no single assertion catches.

A flag whose `remoteKey` matches nothing is **not an error** — it falls through to its
default, which looks exactly like a backend that sent no value. And a value in the file
that no flag reads is invisible until someone notices the feature never turned on.

`FlagMappingAudit` reports both directions, from a payload and a container, with no pole
and no running app:

<!-- snippet: validating-audit -->
```kotlin
val json = configFile.readBytes()
val audit = FlagMappingAudit(AppFlagsSpec, json)

println(audit)
```

<!-- output: validating-audit-report -->
```text
Flag mapping audit — incomplete.
  absent (1) — a flag declares this path, the payload has nothing there:
    • page-size
  mismatched (1):
    • 'config.tier' → checkout-tier: "gold" is not one of free, pro
  unconsumed (2) — usually fine, often backend metadata — no flag reads these:
    • config.page_size
    • meta.version
  2 of 4 remotely-overridable flags applied.
```

### The two directions

- **Absent** is the forward check: a flag declares a `remoteKey` the payload does not
  supply. Almost always a typo in the key — `config.page_size` where the flag reads
  `config.pageSize` — which is otherwise indistinguishable from a value the backend
  chose not to send.
- **Unconsumed** is the reverse: a value in the file that no flag reads. The same typo
  usually shows here too, as `config.page_size`, which is what makes it findable.

The audit never checks types itself. It reuses the validation `RemoteOverrideSource`
applies, so a value it calls *applied* is one a real apply would accept — and any type or
enum-entry problem arrives as a `RemoteOverrideProblem` in `mismatched`, the whole list
at once rather than one per run.

### Unconsumed values usually are not faults

A real config carries metadata no flag reads — schema versions, build stamps, keys
another app shares the document for. So unconsumed values are reported but do **not**
fail the default audit:

<!-- snippet: validating-complete -->
```kotlin
val complete = audit.isComplete // absent and mismatched only
```

Ask for more when the file is yours alone:

<!-- snippet: validating-strict -->
```kotlin
val everyValueRead = audit.isComplete(strict = true) // every value must be read
val everyValueButMeta = audit.isComplete(strict = true, ignoring = listOf("meta")) // except under meta
```

### In a test

Add `featureflag-test` to your **test** dependencies — never an app's, since it is test
support — for a one-line assertion:

<!-- snippet: validating-test-helper -->
```kotlin
@Test
fun stagingConfigMapsEveryFlag() {
    val json = checkNotNull(javaClass.getResource("/staging.json")).readBytes()
    assertFlagsFullyMapped(AppFlagsSpec, json, strict = true, ignoring = listOf("meta"))
}
```

The failure is an `AssertionError` whose message is the whole report, so a large file
surfaces all of its problems in one run, and any test framework reports it as a failure.

Without the helper, the audit works anywhere:

<!-- snippet: validating-framework-agnostic -->
```kotlin
val audit = FlagMappingAudit(AppFlagsSpec, json)
assertTrue(audit.isComplete, audit.toString()) // any test framework
audit.requireComplete(strict = true, ignoring = listOf("meta")) // throws the report
```

`requireComplete` throws `FlagMappingAuditException.Incomplete`, which carries the
report. It also suits a one-off check at launch in a debug build.

### Which values actually change something

Alongside coverage, the audit compares every value the config supplies against the
flag's compiled default — so a large file tells you what it really does, not just that
it parses.

<!-- snippet: validating-defaults -->
```kotlin
println(audit.defaultsDescription)
```

<!-- output: validating-defaults-report -->
```text
Default vs config:
  changes (2):
    • checkout-tier: "free" → "pro"
    • new-onboarding: false → true
  restated (2) — same as the default, could be omitted:
    • markets
    • page-size
```

`changesDefault` is the values that differ; `matchesDefault` is the ones the config
restates and could drop. `defaults` carries the full per-flag comparison. None of it
affects `isComplete` — restating a default, or changing it, is not a coverage problem,
only something worth seeing.

A record flag is broken down per record rather than shown as two JSON blobs — which
field of which record changed, and which records were added or removed:

<!-- output: validating-record-defaults-report -->
```text
Default vs config:
  changes (2):
    • page-size: 10 → 25
    • payment-methods:
        ~ Google Pay:
            enabled: true → false
            minimumSpend: 5.0 → 10.0
        - Bank transfer
        + PayPal
```

Records are paired by their `@FlagRecordKey` when they have one — so a reordered list
is not a wall of false changes — and by position (`[0]`, `[1]`) when they do not. Each
comparison's `records` carries the structured breakdown, as `FlagRecordDiff`s, for
inspecting a specific record rather than reading the text.

A whole number sent for a `Double` flag matches a `Double` default: JSON has one number
type, so `3` and `3.0` are the same value, not a change.

### A record flag reads a whole subtree

A record list's `remoteKey` names a subtree — `config.paymentMethods` — and every leaf
beneath it counts as read, so a keyed list of a hundred endpoints does not show as a
hundred unconsumed values.

### A note on custom mappers

Reverse coverage is computed from the flags' declared `remoteKey` paths, so it reflects
`DotPathMapper`. A custom `RemoteOverrideMapper` that reads the payload some other way
makes `unconsumed` advisory rather than exact; `absent` and `mismatched` stay accurate,
since those come from what the mapper actually produced. Pass the mapper to the audit as
you would to the source: `FlagMappingAudit(AppFlagsSpec, json, mapper = ExperimentListMapper)`.
````

- [ ] **Step 5: Run the tests and watch them pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 81 tests: `DeclaringFlagsTest` 10, `FlagValuesTest` 7, `RecordFlagsTest` 8, `RemoteOverridesTest` 9, `ValidatingAConfigTest` 8, and B4's 39.

- [ ] **Step 6: Check a doc edit alone, and a whitespace edit in a region, re-run and fail the test (Review Focus 4)**

In `docs/FlagValues.md`, change the fence line `enum class Tier { Free, Pro, Enterprise }` to `enum class Tier { Free, Pro, Enterprise, Platinum }`, and touch nothing else.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
```

Expected: `:docs-samples:testDebugUnitTest` runs (it is not `UP-TO-DATE`) and fails with:

```text
docs/FlagValues.md:98: the fence differs from region 'values-enum' at docs-samples/src/test/kotlin/docs/flagvalues/FlagValues.kt:80
--- docs/FlagValues.md:98 (document)
+++ docs-samples/src/test/kotlin/docs/flagvalues/FlagValues.kt:80 (region)
-enum class Tier { Free, Pro, Enterprise, Platinum }
+enum class Tier { Free, Pro, Enterprise }
```

Put the line back. Now make the same kind of edit on the code side, one the compiler cannot see: in `docs-samples/src/test/kotlin/docs/flagvalues/FlagValues.kt`, add a second space after `enum class Tier {`, and run the same command. The class files come out identical, so only B4's text input on `docs-samples/src` re-runs the test. Expected: `:docs-samples:testDebugUnitTest` runs (not `UP-TO-DATE` or `FROM-CACHE`) and fails with the same message, its last two lines now `-enum class Tier { Free, Pro, Enterprise }` and `+enum class Tier {  Free, Pro, Enterprise }`.

Put the space back and run it again: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add docs-samples docs/DeclaringFlags.md docs/FlagValues.md docs/RecordFlags.md docs/RemoteOverrides.md docs/ValidatingAConfig.md
git commit -m "Document declaring flags, their values, records, remote overrides and the mapping audit" -m "Every Kotlin sample is compiled in docs-samples and run or used by its tests, and DocsSyncTest holds the text to it." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B6: Sources, observing, sharing, the bridge, and export and import

**Files:**
- Create: `docs-samples/src/test/kotlin/docs/sourcesandprecedence/SourcesAndPrecedence.kt`, `SourcesAndPrecedenceTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/observingchanges/ObservingChanges.kt`, `ObservingChangesTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/sharingwithacompanionapp/SharingWithACompanionApp.kt`, `SharingWithACompanionAppTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/thebridge/TheBridgeTest.kt`
- Create: `docs-samples/src/test/resources/docs/thebridge/companion-manifest.xml`, `merged-manifest.xml`, `known-signer-manifest.xml`, `known-certificates.xml`, `host-dependencies.kts`
- Create: `docs-samples/src/test/kotlin/docs/exportingandimporting/ExportingAndImporting.kt`, `ExportingAndImportingTest.kt`
- Modify (whole file): `docs-samples/src/test/kotlin/docs/DocOutputs.kt`
- Create: `docs/SourcesAndPrecedence.md`, `docs/ObservingChanges.md`, `docs/SharingWithACompanionApp.md`, `docs/TheBridge.md`, `docs/ExportingAndImporting.md`
- Modify: `docs/DeclaringFlags.md` (one link)

**Interfaces:**
- Consumes: B1's `applyPayload` (the `exporting-apply-payload` sample); B5's docs; Seams 6 and 8 (M4a's `sample-companion-uses-permission` and `sample-companion-signing` regions, `installUntrusted`, `samples/README.md`).
- Produces: `sharing-setup` (the `Application` that builds the pole and publishes the schema) and `bridge-companion-permission`, which `GettingStarted.md`, `BuildingACompanionApp.md` and `README.md` quote in B7.

Two kinds of test here are new. `SharingWithACompanionAppTest` uses `@Config(application = ExampleApplication::class)`, so Robolectric runs the documented `Application.onCreate` before each test, which is the code the doc shows. `TheBridgeTest` parses the manifests the doc shows and compares them with `FlagBridgeContract` and with `featureflag-bridge/src/main/AndroidManifest.xml` itself (with `${applicationId}` as `com.example.app`), which B4's build file declares as an input. The `knownSigner` snippets are the exception: Android's manifest merger and package manager are not run here, so `TheBridgeTest` checks only that they parse and name the bridge's permission and the certificate array, and `TheBridge.md` says the library's tests do not exercise that platform behaviour.

- [ ] **Step 1: Write the code the docs will quote**

**Create** `docs-samples/src/test/kotlin/docs/sourcesandprecedence/SourcesAndPrecedence.kt`:

```kotlin
package docs.sourcesandprecedence

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import java.net.URI

enum class Environment(override val flagRawValue: String) : FlagStringEnum {
    Production("production"),
    Staging("staging"),
}

@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Which backend this build talks to")
    val environment: Environment get() = Environment.Production

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay", remoteKey = "checkout.googlePay")
    val googlePay: Boolean get() = false

    @Flag(description = "Where the remote config is fetched from", remoteKey = "checkout.remoteUrl")
    val remoteUrl: URI get() = URI("https://config.example.com")
}

// region snippet: sources-custom
class RemoteConfigSource : FlagValueSource {

    override val sourceName: String = "Remote Config"

    @Volatile
    private var values: Map<FlagKey, FlagValueBox> = emptyMap()

    private val emitter = FlagChangeEmitter()

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    fun refresh(values: Map<FlagKey, FlagValueBox>) {
        this.values = values
        emitter.emit(FlagChange.All)
    }
}
// endregion

/** Stands in for an app's own configuration service. */
class ConfigApi(private val configs: Map<String, String>) {
    val requests = mutableListOf<String>()

    suspend fun fetchConfig(environment: Environment): ByteArray? = fetch(DEFAULT_SERVER, environment)

    suspend fun fetch(url: URI, environment: Environment): ByteArray? {
        yield()
        val request = "$url/${environment.flagRawValue}.json"
        requests += request
        return configs[request]?.encodeToByteArray()
    }

    private companion object {
        val DEFAULT_SERVER = URI("https://config.example.com")
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/sourcesandprecedence/SourcesAndPrecedenceTest.kt`. `runCurrent()`, not `advanceUntilIdle()`, drives the collector in `backgroundScope`:

```kotlin
package docs.sourcesandprecedence

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.EnvironmentConfiguration
import com.andyyhope.featureflag.remote.LayerOutcome
import com.andyyhope.featureflag.remote.LoadOutcome
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/SourcesAndPrecedence.md. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SourcesAndPrecedenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val byHand = SnapshotSource(name = "By hand")
    private val api = ConfigApi(
        mapOf(
            "https://config.example.com/staging.json" to """{"featureToggles": {"onboarding": {"v2": true}}}""",
            "https://staging-config.example.com/staging.json" to """{"checkout": {"googlePay": true}}""",
        ),
    )
    private val bundled = mapOf(
        "production.json" to """{"checkout": {"googlePay": false}}""",
        "staging.json" to """{"checkout": {"remoteUrl": "https://staging-config.example.com"}}""",
    )
    private val opened = mutableListOf<SharedPreferencesSource>()

    private fun bundledConfig(name: String): ByteArray? = bundled[name]?.encodeToByteArray()

    @After
    fun closeSources() {
        opened.forEach(SharedPreferencesSource::close)
    }

    @Test
    fun theOrderOfTheStackIsThePrecedence() {
        // region snippet: sources-stack
        val pole = FlagPole(
            AppFlagsSpec,
            sources = listOf(
                SharedPreferencesSource.companion(context), // 1. the companion app
                RemoteOverrideSource(AppFlagsSpec), // 2. your backend
            ),
        )
        // 3. each flag's default getter
        // endregion
        opened += pole.sources.filterIsInstance<SharedPreferencesSource>()
        val remote = pole.sources[1] as RemoteOverrideSource

        remote.apply("""{"featureToggles": {"onboarding": {"v2": true}}}""".encodeToByteArray())
        pole.setOverride(AppFlagsSpec.newOnboarding, false)

        assertFalse(pole.flags.newOnboarding)
        assertEquals("Companion", pole.resolution(AppFlagsSpec.newOnboarding).sourceName)
    }

    @Test
    fun theSourcesThatShip() {
        // region snippet: sources-shared-preferences
        val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
        val own = SharedPreferencesSource(preferences) // a file of this app's own
        val shared = SharedPreferencesSource.companion(context) // the file a companion app edits
        // endregion
        opened += listOf(own, shared)

        assertEquals("SharedPreferences", own.sourceName)
        assertEquals("Companion", shared.sourceName)
    }

    @Test
    fun aSnapshotHoldsValuesInMemory() {
        // region snippet: sources-snapshot
        val local = SnapshotSource(name = "test")
        val pole = FlagPole(AppFlagsSpec, sources = listOf(local))

        local.setBox(FlagKey("checkout.google-pay"), FlagValueBox.Bool(true))
        val offersGooglePay = pole.flags.checkout.googlePay // true
        // endregion

        assertTrue(offersGooglePay)
    }

    @Test
    fun anEnvironmentConfigurationLayersRemoteOverLocal() = runTest {
        // region snippet: sources-environment-configuration
        val config = EnvironmentConfiguration<Environment>(
            AppFlagsSpec,
            local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // ships with the app
            remote = { environment -> api.fetchConfig(environment) }, // fetched, suspending
        )

        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

        // when the environment flag changes:
        val outcome = config.load(Environment.Staging) // local staging.json, then remote staging.json
        // endregion

        assertEquals(LoadOutcome(LayerOutcome.Applied, LayerOutcome.Applied), outcome)
        assertTrue(pole.flags.newOnboarding)
        assertEquals(URI("https://staging-config.example.com"), pole.flags.checkout.remoteUrl)
        assertEquals(listOf("Remote", "Local"), config.sources.map { it.sourceName })
    }

    @Test
    fun theEnvironmentFlagDrivesTheLoad() = runTest {
        val config = EnvironmentConfiguration<Environment>(
            AppFlagsSpec,
            local = { environment -> bundledConfig("${environment.flagRawValue}.json") },
            remote = { environment -> api.fetchConfig(environment) },
        )
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)
        val scope = backgroundScope

        // region snippet: sources-environment-flag
        scope.launch {
            pole.flow(AppFlagsSpec.environment).collectLatest { environment -> config.load(environment) }
        }
        // endregion
        runCurrent()
        assertFalse(pole.flags.newOnboarding)

        pole.setOverride(AppFlagsSpec.environment, Environment.Staging)
        runCurrent()

        assertTrue(pole.flags.newOnboarding)
    }

    @Test
    fun theRemoteLoaderCanReadAFlagTheLocalLayerSets() = runTest {
        // region snippet: sources-fetch-remote
        val config = EnvironmentConfiguration<Environment>(
            AppFlagsSpec,
            local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // carries checkout.remoteUrl
        )
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

        config.fetchRemote { environment ->
            val url = pole.flags.checkout.remoteUrl // resolved: by hand, then local, then the default
            api.fetch(url, environment)
        }
        // endregion

        config.load(Environment.Staging)

        assertEquals(listOf("https://staging-config.example.com/staging.json"), api.requests)
        assertTrue(pole.flags.checkout.googlePay)
    }

    @Test
    fun overridesGoToTheFirstMutableSource() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand, RemoteOverrideSource(AppFlagsSpec)))

        // region snippet: sources-overrides
        pole.setOverride(AppFlagsSpec.newOnboarding, true)
        pole.removeOverride(AppFlagsSpec.newOnboarding)
        pole.removeAllOverrides()
        // endregion

        assertTrue(byHand.values.isEmpty())
        val readOnly = FlagPole(AppFlagsSpec, sources = listOf(RemoteOverrideSource(AppFlagsSpec)))
        assertFailsWith<FlagException.NoMutableSource> { readOnly.setOverride(AppFlagsSpec.newOnboarding, true) }
    }

    @Test
    fun aResolutionNamesTheSourceThatWon() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand))

        // region snippet: sources-resolution
        val resolution = pole.resolution(AppFlagsSpec.newOnboarding)

        val source = resolution.sourceName // "Companion", "Remote", or null for the compiled default
        val value = resolution.box // the value in effect
        val isDefault = resolution.isDefault // true when nothing overrode it
        // endregion

        assertNull(source)
        assertEquals(FlagValueBox.Bool(false), value)
        assertTrue(isDefault)
    }

    @Test
    fun everyFlagCanBeExplainedInALoop() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand))
        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)

        // region snippet: sources-resolution-loop
        for (entry in pole.schema.flags) {
            val resolution = pole.resolution(entry.key, entry.valueType)
            Log.d("Flags", "${entry.key}: ${resolution.sourceName ?: "default"}")
        }
        // endregion

        assertEquals(
            listOf(
                "new-onboarding: default",
                "environment: default",
                "checkout.google-pay: By hand",
                "checkout.remote-url: default",
            ),
            ShadowLog.getLogsForTag("Flags").map { it.msg },
        )
    }

    @Test
    fun aSourceOfYourOwn() {
        val source = RemoteConfigSource()
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))

        source.refresh(mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))

        assertTrue(pole.flags.newOnboarding)
        assertEquals("Remote Config", pole.resolution(AppFlagsSpec.newOnboarding).sourceName)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/observingchanges/ObservingChanges.kt`:

```kotlin
package docs.observingchanges

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.pole.FlagPole

@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}

// region snippet: observing-compose
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
// endregion

/** Stands in for an analytics SDK. */
class Analytics {
    val properties = mutableMapOf<String, Any>()

    fun setUserProperty(name: String, value: Any) {
        properties[name] = value
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/observingchanges/ObservingChangesTest.kt`:

```kotlin
package docs.observingchanges

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** docs/ObservingChanges.md. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ObservingChangesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val analytics = Analytics()
    private var reloads = 0

    private fun reloadPage() {
        reloads += 1
    }

    @Test
    fun aComposableFollowsAFlag() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
        composeRule.setContent { OnboardingEntry(pole) }
        composeRule.onNodeWithText("Welcome").assertExists()

        pole.setOverride(AppFlagsSpec.newOnboarding, true)

        composeRule.onNodeWithText("Welcome to the new onboarding").assertExists()
    }

    @Test
    fun aFlowSendsTheCurrentValueThenEachChange() = runTest {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
        val scope = backgroundScope

        // region snippet: observing-flow
        pole.flow(AppFlagsSpec.newOnboarding)
            .onEach { isEnabled -> analytics.setUserProperty("new_onboarding", isEnabled) }
            .launchIn(scope)
        // endregion
        runCurrent()
        assertEquals(false, analytics.properties["new_onboarding"])

        pole.setOverride(AppFlagsSpec.newOnboarding, true)
        runCurrent()

        assertEquals(true, analytics.properties["new_onboarding"])
    }

    @Test
    fun theCoarseStreamSaysWhichKeysChanged() = runTest {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
        val scope = backgroundScope

        // region snippet: observing-changes
        pole.changes
            .filter { change -> change.affects(pole.key(AppFlagsSpec.pageSize)) }
            .onEach { reloadPage() }
            .launchIn(scope)
        // endregion
        runCurrent()

        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
        pole.setOverride(AppFlagsSpec.pageSize, 50)
        runCurrent()

        assertEquals(1, reloads)
    }

    @Test
    fun aSourceCanBeAskedToAnnounceEverything() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val source = SharedPreferencesSource.companion(context)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
        pole.changes.onEach { reloadPage() }.launchIn(backgroundScope)
        runCurrent()

        // region snippet: observing-refresh
        source.refresh()
        // endregion
        runCurrent()

        assertEquals(1, reloads)
        source.close()
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/sharingwithacompanionapp/SharingWithACompanionApp.kt`:

```kotlin
package docs.sharingwithacompanionapp

import android.app.Application
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.android.transport.publishSchema
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import java.time.Instant

@FlagContainer
interface AppFlags {
    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}

// region snippet: sharing-setup
class ExampleApplication : Application() {
    lateinit var pole: FlagPole<AppFlags>
        private set

    override fun onCreate() {
        super.onCreate()
        pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.companion(this)))
        pole.publishSchema(this)
    }
}
// endregion

/** The schema a host with [AppFlags] publishes, at a fixed moment. */
fun publishedSchema(): String = FlagSchema
    .of(AppFlagsSpec, applicationName = "Demo", generatedAt = Instant.parse("2026-01-01T00:00:00Z"))
    .jsonBytes()
    .decodeToString()
```

**Create** `docs-samples/src/test/kotlin/docs/sharingwithacompanionapp/SharingWithACompanionAppTest.kt`:

```kotlin
package docs.sharingwithacompanionapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.publishSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SharingWithACompanionApp.md. Robolectric builds [ExampleApplication] and runs its onCreate first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = ExampleApplication::class)
class SharingWithACompanionAppTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sources = listOf(SnapshotSource())

    @Test
    fun theApplicationPublishesItsSchemaOnLaunch() {
        val application = ApplicationProvider.getApplicationContext<ExampleApplication>()

        val published = File(FlagBridgeContract.schemaDirectory(application), FlagSchema.FILE_NAME)

        assertTrue(published.isFile)
        assertEquals(application.pole.schema.flags, FlagSchema.parse(published.readBytes()).flags)
    }

    @Test
    fun aDirectoryCanBePublishedToForTestsAndTools() {
        val pole = FlagPole(AppFlagsSpec, sources = sources)
        val directory = folder.newFolder("published")

        // region snippet: sharing-publish-directory
        val file = pole.publishSchema(directory)
        // endregion

        assertEquals(File(directory, "flag-schema.json"), file)
    }

    @Test
    fun theSchemaCarriesEveryFlagAndGroup() {
        assertTrue(publishedSchema().contains("\"applicationName\" : \"Demo\""))
    }

    @Test
    fun theApplicationNameIsYours() {
        // region snippet: sharing-application-name
        val pole = FlagPole(AppFlagsSpec, sources = sources, applicationName = "Demo (Staging)")
        // endregion

        assertEquals("Demo (Staging)", pole.schema.applicationName)
        assertEquals(null, FlagPole(AppFlagsSpec, sources = sources).schema.applicationName)
    }

    @Test
    fun theSchemaReadsBack() {
        val directory = folder.newFolder("read")
        FlagPole(AppFlagsSpec, sources = sources).publishSchema(directory)

        // region snippet: sharing-read-schema
        val schema = FlagSchema.read(directory)

        val count = schema.flags.size
        val types = schema.valueTypes // Map<FlagKey, FlagValueType>, for validating a payload
        // endregion

        assertEquals(1, count)
        assertEquals(mapOf(FlagKey("checkout.google-pay") to FlagValueType.Bool), types)
    }
}
```

**Create** `docs-samples/src/test/resources/docs/thebridge/companion-manifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- A companion's manifest, for a host whose application id is com.example.app. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- region snippet: bridge-companion-permission -->
    <uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
    <!-- endregion -->

    <application android:label="Flags" />
</manifest>
```

**Create** `docs-samples/src/test/resources/docs/thebridge/merged-manifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- What featureflag-bridge merges into a host whose application id is com.example.app. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- region snippet: bridge-merged-manifest -->
    <permission
        android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE"
        android:protectionLevel="signature" />

    <application>
        <provider
            android:name="com.andyyhope.featureflag.bridge.FlagBridgeProvider"
            android:authorities="com.example.app.featureflag"
            android:exported="true"
            android:permission="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
    </application>
    <!-- endregion -->
</manifest>
```

**Create** `docs-samples/src/test/resources/docs/thebridge/known-signer-manifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- A host, com.example.app, that also trusts a companion signed with another key. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- region snippet: bridge-known-signer -->
    <permission
        android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE"
        android:knownCerts="@array/featureflag_companion_certificates"
        android:protectionLevel="signature|knownSigner"
        tools:replace="android:protectionLevel" />
    <!-- endregion -->
</manifest>
```

**Create** `docs-samples/src/test/resources/docs/thebridge/known-certificates.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The host's res/values/featureflag.xml for known-signer-manifest.xml. -->
<resources>
    <!-- region snippet: bridge-known-certs -->
    <string-array name="featureflag_companion_certificates">
        <item>3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7</item>
    </string-array>
    <!-- endregion -->
</resources>
```

**Create** `docs-samples/src/test/resources/docs/thebridge/host-dependencies.kts`:

```kotlin
// A host app's dependencies on the bridge, as TheBridge.md shows them. Not compiled: DocsSyncTest checks
// the coordinates, and TheBridgeTest checks the manifests beside this file.

// region snippet: bridge-install
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-bridge")
}
// endregion

// region snippet: bridge-debug-only
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-android")
    debugImplementation("com.andyyhope.featureflag:featureflag-bridge")
}
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/thebridge/TheBridgeTest.kt`:

```kotlin
package docs.thebridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.schema.FlagSchema
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/** docs/TheBridge.md: the manifests it shows, checked against the contract and the bridge's own manifest. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TheBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val problems = mutableListOf<String>()
    private var schema: FlagSchema? = null

    private fun show(schema: FlagSchema) {
        this.schema = schema
    }

    private fun showProblem(message: String) {
        problems += message
    }

    private fun parse(stream: InputStream): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(stream)

    private fun resource(name: String): Document =
        parse(checkNotNull(javaClass.getResourceAsStream("/docs/thebridge/$name")))

    private fun Document.element(tag: String): Element = getElementsByTagName(tag).item(0) as Element

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    @Test
    fun theCompanionRequestsTheHostsPermission() {
        val permission = resource("companion-manifest.xml").element("uses-permission").android("name")

        assertEquals(FlagBridgeContract.permission("com.example.app"), permission)
    }

    @Test
    fun theMergedManifestIsTheBridgesOwnForThatHost() {
        val shown = resource("merged-manifest.xml")
        val root = checkNotNull(System.getProperty("featureflag.docs.root"))
        val library = File(root, "featureflag-bridge/src/main/AndroidManifest.xml")
            .readText()
            .replace("\${applicationId}", "com.example.app")
        val actual = parse(library.byteInputStream())

        for (tag in listOf("permission", "provider")) {
            for (attribute in listOf("name", "protectionLevel", "authorities", "exported", "permission")) {
                val expected = actual.element(tag).android(attribute)
                assertEquals(expected, shown.element(tag).android(attribute), "$tag $attribute")
            }
        }
        assertEquals(FlagBridgeContract.authority("com.example.app"), shown.element("provider").android("authorities"))
    }

    // The knownSigner recipe is Android platform behaviour this library's tests do not exercise; this
    // only checks that the XML the doc shows parses and redeclares the bridge's permission.
    @Test
    fun theKnownSignerSnippetsParseAndNameTheBridgePermission() {
        val permission = resource("known-signer-manifest.xml").element("permission")

        assertEquals(FlagBridgeContract.permission("com.example.app"), permission.android("name"))
        val certificates = resource("known-certificates.xml").element("string-array")
        assertEquals("featureflag_companion_certificates", certificates.getAttribute("name"))
    }

    @Test
    fun aCompanionOfYourOwnConnectsWithTheClient() = runTest {
        // region snippet: bridge-connect
        val client = FlagBridgeClient(context, hostPackage = "com.example.app")

        when (val connection = client.connect()) {
            is FlagBridgeConnection.Connected -> show(connection.schema)
            else -> showProblem(connection.message.orEmpty())
        }
        // endregion

        // This test app declares no <uses-permission>, which is the first thing connect() checks.
        val missing = FlagBridgeConnection.UsesPermissionMissing(FlagBridgeContract.permission("com.example.app"))
        assertEquals(missing.message, problems.single())
        assertEquals(null, schema)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/exportingandimporting/ExportingAndImporting.kt`:

```kotlin
package docs.exportingandimporting

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.exportPayload
import java.net.URI
import java.time.Instant

@FlagContainer
interface AppFlags {
    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 20

    @Flag(description = "Backend the app talks to")
    val endpoint: URI get() = URI("https://api.example.com/v3")

    @Flag(description = "A blob too big for a QR code")
    val payload: ByteArray get() = ByteArray(0)

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}

/** What exporting three overrides writes, at a fixed moment. */
fun exportedDocument(): String {
    val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
    pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
    pole.setOverride(AppFlagsSpec.endpoint, URI("https://staging.example.com/v3"))
    pole.setOverride(AppFlagsSpec.pageSize, 50)
    val payload = pole.exportPayload().copy(exportedAt = Instant.parse("2026-01-01T09:41:00Z"))
    return payload.jsonBytes().decodeToString()
}
```

**Create** `docs-samples/src/test/kotlin/docs/exportingandimporting/ExportingAndImportingTest.kt`:

```kotlin
package docs.exportingandimporting

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.transport.applyPayload
import com.andyyhope.featureflag.transport.exportJson
import com.andyyhope.featureflag.transport.exportPayload
import com.andyyhope.featureflag.transport.importJson
import com.andyyhope.featureflag.transport.importQRCode
import com.andyyhope.featureflag.transport.qrCodeString
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/ExportingAndImporting.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExportingAndImportingTest {

    private val local = SnapshotSource()
    private val pole = FlagPole(AppFlagsSpec, sources = listOf(local))
    private val codes = mutableListOf<String>()

    private fun showCode(code: String) {
        codes += code
    }

    private fun document(values: String): ByteArray = """{"formatVersion": 1, "values": $values}""".encodeToByteArray()

    @Test
    fun exportingCarriesOnlyOverriddenFlags() {
        pole.setOverride(AppFlagsSpec.pageSize, 50)

        // region snippet: exporting-export
        val json = pole.exportJson() // UTF-8 JSON, as a ByteArray
        val payload = pole.exportPayload() // the same, as a FlagPayload
        // endregion

        assertEquals(setOf(FlagKey("page-size")), payload.values.keys)
        assertTrue(json.decodeToString().contains("\"page-size\" : 50"))
        assertTrue(exportedDocument().contains("\"checkout.google-pay\" : true"))
    }

    @Test
    fun importingAppliesEveryValueOrNone() {
        val bytes = document("""{"page-size": 50, "checkout.google-pay": true}""")

        // region snippet: exporting-import
        val applied = pole.importJson(bytes)
        val keys = applied.values.keys
        // endregion

        assertEquals(setOf(FlagKey("page-size"), FlagKey("checkout.google-pay")), keys)
        assertEquals(50, pole.flags.pageSize)
    }

    @Test
    fun aRejectedDocumentListsEveryProblem() {
        val bytes = document("""{"page-size": "fifty", "not-a-flag": 1}""")

        // region snippet: exporting-import-rejected
        try {
            pole.importJson(bytes)
        } catch (rejected: FlagImportException.Rejected) {
            for (problem in rejected.problems) {
                Log.w("Flags", "${problem.key}: ${problem.kind}") // UnknownKey, TypeMismatch or UnknownCase
            }
        }
        // endregion

        val logged = ShadowLog.getLogsForTag("Flags").map { it.msg }
        assertEquals(listOf("not-a-flag: UnknownKey", "page-size: TypeMismatch"), logged)
        assertEquals(20, pole.flags.pageSize)
    }

    @Test
    fun importingDoesNotClearWhatTheDocumentLeavesOut() {
        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
        val bytes = document("""{"page-size": 50}""")

        // region snippet: exporting-replace
        pole.removeAllOverrides()
        pole.importJson(bytes)
        // endregion

        assertFalse(pole.flags.checkout.googlePay)
        assertEquals(50, pole.flags.pageSize)
    }

    @Test
    fun aDecodedPayloadAppliesTheSameWay() {
        val bytes = document("""{"page-size": 50}""")

        // region snippet: exporting-apply-payload
        val payload = FlagPayload.decode(bytes, pole.schema)
        pole.applyPayload(payload)
        // endregion

        assertEquals(50, pole.flags.pageSize)
    }

    @Test
    fun aQrCodeRoundTrips() {
        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)

        // region snippet: exporting-qr
        val code = pole.qrCodeString() // "FFQR1:…"
        // endregion
        val scanned = code
        val receiver = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))

        // region snippet: exporting-qr-import
        receiver.importQRCode(scanned)
        // endregion

        assertTrue(code.startsWith("FFQR1:"))
        assertTrue(receiver.flags.checkout.googlePay)
    }

    @Test
    fun aCodeThatWillNotFitSaysWhatIsInIt() {
        pole.setOverride(AppFlagsSpec.payload, Random(seed = 1).nextBytes(3_000))

        // region snippet: exporting-qr-too-large
        try {
            showCode(pole.qrCodeString())
        } catch (tooLarge: FlagQRCodeException.PayloadTooLarge) {
            Log.w("Flags", "${tooLarge.overrideCount} overrides need ${tooLarge.bytes} of ${tooLarge.limit} characters")
        }
        // endregion

        assertTrue(codes.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("Flags").single().msg.startsWith("1 overrides need "))
    }

    @Test
    fun exportSpansTheWholeStack() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val stacked = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource(), remote))

        remote.apply("""{"config": {"pageSize": 30}}""".encodeToByteArray())

        assertEquals(setOf(FlagKey("page-size")), stacked.exportPayload().values.keys)
        assertEquals(URI("https://api.example.com/v3"), stacked.flags.endpoint)
    }
}
```

- [ ] **Step 2: Register the outputs**

**Replace** `docs-samples/src/test/kotlin/docs/DocOutputs.kt` with:

```kotlin
package docs

import docs.declaringflags.duplicateKeyMessage
import docs.exportingandimporting.exportedDocument
import docs.recordflags.duplicateRecordKeyProblem
import docs.recordflags.endpointsPayload
import docs.recordflags.nestedRecord
import docs.recordflags.recordSchema
import docs.recordflags.renamedFieldsPayload
import docs.recordflags.storedRecordText
import docs.remoteoverrides.arrayPathPayload
import docs.remoteoverrides.experimentsPayload
import docs.remoteoverrides.onboardingPayload
import docs.remoteoverrides.rejectionReport
import docs.sharingwithacompanionapp.publishedSchema
import docs.validatingaconfig.checkoutDefaultsReport
import docs.validatingaconfig.draftAuditReport
import docs.validatingaconfig.stagingDefaultsReport

/**
 * The value behind every `<!-- output: name -->` fence, computed by the code the document describes.
 * DocsSyncTest compares each fence with its entry, and fails on an entry no document shows.
 */
object DocOutputs {
    val all: Map<String, () -> String> = mapOf(
        // DeclaringFlags.md
        "declaring-duplicate-keys" to ::duplicateKeyMessage,
        // RecordFlags.md
        "records-duplicate-key" to ::duplicateRecordKeyProblem,
        "records-stored-text" to ::storedRecordText,
        "records-schema" to ::recordSchema,
        "records-endpoints-payload" to { endpointsPayload },
        "records-renamed-payload" to { renamedFieldsPayload },
        "records-nested-record" to { nestedRecord },
        // RemoteOverrides.md
        "remote-onboarding-payload" to { onboardingPayload },
        "remote-array-payload" to { arrayPathPayload },
        "remote-rejection-report" to ::rejectionReport,
        "remote-experiments-payload" to { experimentsPayload },
        // ValidatingAConfig.md
        "validating-audit-report" to ::draftAuditReport,
        "validating-defaults-report" to ::stagingDefaultsReport,
        "validating-record-defaults-report" to ::checkoutDefaultsReport,
        // SharingWithACompanionApp.md
        "sharing-schema" to ::publishedSchema,
        // ExportingAndImporting.md
        "exporting-json" to ::exportedDocument,
    )
}
```

- [ ] **Step 3: Run the tests and watch DocsSyncTest fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
```

Expected: `BUILD FAILED` with only `everyDocumentMatchesTheCodeItQuotes` failing (112 tests, 111 passing). It lists 35 problems: the 33 new regions in `docs-samples` and the 2 new outputs, `sharing-schema` and `exporting-json`.

- [ ] **Step 4: Write the five docs**

`docs/TheBridge.md`'s "In the samples" section quotes two of M4a's regions, `sample-companion-uses-permission` and `sample-companion-signing` (Seam 6), byte for byte as M4a's plan writes them; no sample file changes. If `DocsSyncTest` in Step 6 shows either fence differing from its region, M4a's file has drifted from its plan: copy the region's lines, as the diff's `+` lines show them, into the fence, and name the difference in the task report.

**Create** `docs/SourcesAndPrecedence.md`:

````markdown
# Sources and precedence

Stacking the places a value can come from, and finding out which one won.

## Overview

A `FlagPole` holds an ordered list of `FlagValueSource`s. Reading a flag asks each source
in turn and takes the first answer; if none has one, the flag reports its compiled
default.

**The order of the list *is* the precedence.** There is no priority number to set and no
rule to remember beyond the order you wrote.

<!-- snippet: sources-stack -->
```kotlin
val pole = FlagPole(
    AppFlagsSpec,
    sources = listOf(
        SharedPreferencesSource.companion(context), // 1. the companion app
        RemoteOverrideSource(AppFlagsSpec), // 2. your backend
    ),
)
// 3. each flag's default getter
```

That arrangement is the recommended one. A value set by hand stays set until it is
explicitly cleared, so a tester's device does not silently revert the moment a config
refresh lands. It is also what makes the companion app trustworthy: what you set is what
you get.

### The sources that ship

`SharedPreferencesSource` reads and writes a `SharedPreferences` file. `companion(context)`
opens the one a companion app edits through the bridge:

<!-- snippet: sources-shared-preferences -->
```kotlin
val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
val own = SharedPreferencesSource(preferences) // a file of this app's own
val shared = SharedPreferencesSource.companion(context) // the file a companion app edits
```

Values are stored natively where `SharedPreferences` can: a `Boolean` flag is a real
`Boolean` in the file, an `Int` or `Long` flag a `Long`, a `Float` a `Float` and a
`String` a `String`, so a value your app already stores with `putBoolean`, `putLong`,
`putFloat` or `putString` is adopted as it is. One stored with `putInt` is not: an `Int`
flag is read as a `Long`. Every other type, `Double` included, is stored as its JSON
text. A value stored as something other than the
flag's type reads as nothing, so the flag falls through to the next source; there is no
numeric conversion. A write that changes nothing is skipped.

`SnapshotSource` holds values in memory. It is the top of the stack in tests, the
landing place for an imported payload, and the easy way to force a value in a preview:

<!-- snippet: sources-snapshot -->
```kotlin
val local = SnapshotSource(name = "test")
val pole = FlagPole(AppFlagsSpec, sources = listOf(local))

local.setBox(FlagKey("checkout.google-pay"), FlagValueBox.Bool(true))
val offersGooglePay = pole.flags.checkout.googlePay // true
```

`RemoteOverrideSource` holds whatever the last backend payload said. It has its own
article: [Remote overrides](RemoteOverrides.md).

### Layering a config per environment

An app that talks to more than one backend usually wants two config layers for the
environment it is in: one bundled with the build, and one fetched.
`EnvironmentConfiguration` holds both and keeps them in step with the environment you
switch to.

<!-- snippet: sources-environment-configuration -->
```kotlin
val config = EnvironmentConfiguration<Environment>(
    AppFlagsSpec,
    local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // ships with the app
    remote = { environment -> api.fetchConfig(environment) }, // fetched, suspending
)

val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

// when the environment flag changes:
val outcome = config.load(Environment.Staging) // local staging.json, then remote staging.json
```

`bundledConfig` is yours: it reads a file the app ships with, such as
`context.assets.open(name).use { it.readBytes() }`, and returns `null` when there is none.

Precedence within the pair is **remote over local**, and `config.sources` returns them in
that order — so the full stack, highest first, is a by-hand override, the fetched config,
the bundled config, then the compiled defaults.

Loading an environment clears each layer before it loads it, so a fetch that fails or
returns nothing falls back to the layer beneath — the bundled config, then the defaults —
rather than leaving an app labelled *staging* running the previous environment's values.
`load` suspends, and returns a `LoadOutcome` saying what happened to each layer, since
one can succeed while the other does not: `Applied`, `Absent` when the loader returned
`null`, `Failed` with the error, or `Superseded` when a newer load started first. A
superseded load leaves the layer to the newer one, so two quick switches can never apply
in the wrong order.

Drive it from the environment flag itself, so setting the environment to *staging* loads
staging's two layers. Collect from a scope that lives as long as the pole, such as one
your `Application` holds:

<!-- snippet: sources-environment-flag -->
```kotlin
scope.launch {
    pole.flow(AppFlagsSpec.environment).collectLatest { environment -> config.load(environment) }
}
```

A flow emits only when the value changes, so there is nothing to deduplicate, and
`collectLatest` abandons a load the moment the environment changes again.

Give that flag no `remoteKey`. A config could otherwise set the environment, which would
mean the app should have loaded a different config — and loading *that* could set it back.

The library does no networking and reads no files: the two loaders hand it the bytes, it
decodes, validates against the schema, and layers them. Each layer is an ordinary
`RemoteOverrideSource`, so you can audit either with a `FlagMappingAudit` before you
trust it — see [Validating a config](ValidatingAConfig.md).

#### When the fetch depends on a flag the local layer carries

Sometimes the remote config's URL is itself a flag — one the bundled local config sets, so
the app knows where to fetch from only after the local layer is applied. The timing
already works: `load` applies the local layer in full before it runs the remote loader.
What is missing at construction is the pole — it does not exist until you splice in
`config.sources`, so the loader you pass to the constructor cannot read a resolved flag.

Set the loader afterwards with `fetchRemote`, capturing the pole:

<!-- snippet: sources-fetch-remote -->
```kotlin
val config = EnvironmentConfiguration<Environment>(
    AppFlagsSpec,
    local = { environment -> bundledConfig("${environment.flagRawValue}.json") }, // carries checkout.remoteUrl
)
val pole = FlagPole(AppFlagsSpec, sources = listOf(byHand) + config.sources)

config.fetchRemote { environment ->
    val url = pole.flags.checkout.remoteUrl // resolved: by hand, then local, then the default
    api.fetch(url, environment)
}
```

Because the loader resolves the flag through the whole pole, a by-hand override of the URL
wins over the bundled value — so you can point a *staging* build at a different config
server from the companion without touching the build. And because switching environments
clears the remote layer before fetching, the URL the loader reads is never the previous
fetch's own value.

### Setting and clearing overrides

Writes go to the highest-priority source that accepts them — the first
`MutableFlagValueSource` in the stack:

<!-- snippet: sources-overrides -->
```kotlin
pole.setOverride(AppFlagsSpec.newOnboarding, true)
pole.removeOverride(AppFlagsSpec.newOnboarding)
pole.removeAllOverrides()
```

`removeAllOverrides()` only touches flags this pole declares, so unrelated values sharing
the file are left alone, and it skips flags that hold nothing rather than writing
blindly. What is left goes to the source as one batch, which `SharedPreferencesSource`
commits as a single edit, so the file is never half-cleared.

If nothing in the stack is mutable, writing throws `FlagException.NoMutableSource`.

### Why is this flag false?

`resolution` names the source that supplied the current value:

<!-- snippet: sources-resolution -->
```kotlin
val resolution = pole.resolution(AppFlagsSpec.newOnboarding)

val source = resolution.sourceName // "Companion", "Remote", or null for the compiled default
val value = resolution.box // the value in effect
val isDefault = resolution.isDefault // true when nothing overrode it
```

There is a second form taking a `FlagKey` and a `FlagValueType` instead of a typed
handle. The typed form needs one call site per flag, which makes a diagnostics screen —
"where did every value come from?" — impossible to write as a loop:

<!-- snippet: sources-resolution-loop -->
```kotlin
for (entry in pole.schema.flags) {
    val resolution = pole.resolution(entry.key, entry.valueType)
    Log.d("Flags", "${entry.key}: ${resolution.sourceName ?: "default"}")
}
```

### Writing your own source

Three requirements: a name for diagnostics, a way to read a value, and a flow that says
when things changed. Implement `MutableFlagValueSource` as well if it accepts writes.

<!-- snippet: sources-custom -->
```kotlin
class RemoteConfigSource : FlagValueSource {

    override val sourceName: String = "Remote Config"

    @Volatile
    private var values: Map<FlagKey, FlagValueBox> = emptyMap()

    private val emitter = FlagChangeEmitter()

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    fun refresh(values: Map<FlagKey, FlagValueBox>) {
        this.values = values
        emitter.emit(FlagChange.All)
    }
}
```

The `@Volatile` immutable map is not decoration. A pole reads flags from whatever thread
the app happens to be on while your network callback writes from another, and `box` has
to be cheap. Replacing one immutable map, rather than editing a shared one, gives every
read a consistent view with no lock. `SnapshotSource` and `RemoteOverrideSource` are
written this way.

`FlagChangeEmitter` is not decoration either. A source's changes must never be dropped,
or a flow watching a flag stays stale for good, and a `MutableSharedFlow` with a bounded,
dropping buffer drops them exactly when a collector falls behind. The emitter keeps one
merged change per collector instead, so nothing is lost and memory stays bounded.

The `type` argument is the flag's declared type. A source stored in a loosely typed
medium can use it to decode exactly rather than guess — which is how
`SharedPreferencesSource` knows to read a JSON list rather than a string.

Report `FlagChange.Keys(setOf(…))` when you know what changed and `FlagChange.All` when you
do not. `All` is correct but expensive: every collector reads everything again.

### Reading from other threads

`FlagPole` is not tied to the main thread. Reads are lock-free and safe from any thread,
because apps read flags off the main thread constantly. No flow here hops to the main
thread either: values arrive in the context of the coroutine that collects them — see
[Observing changes](ObservingChanges.md).
````

**Create** `docs/ObservingChanges.md`:

````markdown
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
````

**Create** `docs/SharingWithACompanionApp.md`:

````markdown
# Sharing flags with a companion app

Publishing a schema so a second app can edit your flags without linking your code.

## Overview

A companion app is a separate APK. Through the bridge it can read and write your app's
overrides, but it has no idea what your flag tree *is* — what exists, what it is called,
what type it holds, or which values an enum allows.

So the host publishes a `FlagSchema`: a JSON description of the tree, complete enough for
an editor to be rendered from it alone. That document is what decouples the UI. One
companion build can edit any app that publishes one.

### Setting it up

Add `featureflag-bridge` to the host, put the companion's source at the top of the
pole's stack, and publish the schema on launch, in `Application.onCreate`:

<!-- snippet: sharing-setup -->
```kotlin
class ExampleApplication : Application() {
    lateinit var pole: FlagPole<AppFlags>
        private set

    override fun onCreate() {
        super.onCreate()
        pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.companion(this)))
        pole.publishSchema(this)
    }
}
```

Name the class in the manifest's `<application android:name=".ExampleApplication">`.

Publish every launch, not once. The schema describes the build that is running, and a
build with a new flag needs to say so. `publishSchema(context)` writes
`flag-schema.json` under the app's `noBackupFilesDir`, so a backup restored onto another
build never brings back a stale one, and it writes atomically, so the bridge never
serves half a file.

Do it in `onCreate`, before anything slow. The bridge's provider waits for
`Application.onCreate` to return before it answers a companion, for up to ten seconds, so
a schema published there is in place by the time a companion asks for it — even when the
companion's request is what started your app.

There is also a directory form, which is what tests and JVM tools use:

<!-- snippet: sharing-publish-directory -->
```kotlin
val file = pole.publishSchema(directory)
```

The two apps also have to trust each other: the companion declares the bridge's
permission and is signed with your app's key. [The bridge](TheBridge.md) covers that.

### What the schema carries

Per flag: the storage key, the property path, the description, the value type, the
compiled default, the enum's entries if it has any, the remote key, and a record's
shape. Per group: its property path and description, so the editor can label its
sections and nest them.

<!-- output: sharing-schema -->
```json
{
  "applicationName" : "Demo",
  "flags" : [
    {
      "defaultValue" : false,
      "description" : "Offer Google Pay",
      "key" : "checkout.google-pay",
      "propertyPath" : [
        "checkout",
        "googlePay"
      ],
      "valueType" : "bool"
    }
  ],
  "formatVersion" : 1,
  "generatedAt" : "2026-01-01T00:00:00Z",
  "groups" : [
    {
      "description" : "Checkout",
      "propertyPath" : [
        "checkout"
      ]
    }
  ]
}
```

`applicationName` is left out unless you give one, so a companion that can see more than
one app has something readable to head the list with only when you say so:

<!-- snippet: sharing-application-name -->
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = sources, applicationName = "Demo (Staging)")
```

### Reading it back

A companion built with `featureflag-ui` gets the schema from the bridge when it connects,
and never reads the file. A test or a tool can read a published one directly:

<!-- snippet: sharing-read-schema -->
```kotlin
val schema = FlagSchema.read(directory)

val count = schema.flags.size
val types = schema.valueTypes // Map<FlagKey, FlagValueType>, for validating a payload
```

This is what the `featureflag-ui` module builds its editor from. If you are writing the
companion, start at Building a companion app instead — it has
the screens already.

### What it costs

Everything the editor shows has to survive a trip through JSON. A flag's Kotlin type is
gone by the time the companion sees it; what remains is a `FlagValueType`. A flag you
read through a `Percentage` wrapper is stored as a `Double`, so it is a number field in
the companion, not a percentage field.

That is the trade for a companion app you build once, and it is deliberate.

### Getting changes back into the running app

The companion's edits go through the bridge's provider, which runs in your app's process
and writes your app's own preferences file. The `SharedPreferencesSource` at the top of
your stack hears every write as it happens, so an edit appears in a running app within a
moment, and every flow watching that flag sees it.

When your app is not running, the companion's first request starts it. Its
`Application.onCreate` runs, the provider answers, and the change is in the file the
next time your code reads the flag — which is exactly what you want from a stored
override.
````

**Create** `docs/TheBridge.md`:

````markdown
# The bridge

How a companion app reaches your app's flags: one exported provider, guarded by a
permission only your own signing key can hold.

## Overview

Two Android apps share nothing by default. iOS solves this with an App Group both apps
join; Android has no equivalent, so `featureflag-bridge` adds one small, deliberate
door to your app instead: a `ContentProvider`, `FlagBridgeProvider`, that a companion
calls to read your schema, read and write the companion overrides, and send signals.

The provider runs in your app's process and writes through its own
`SharedPreferencesSource.companion(context)`, over the same file your pole's source reads,
so an edit is a change to your own file, seen by your own listener, the moment it is made.

Nothing else crosses: the companion never links your code, and can reach only the schema
you publish and the overrides file the bridge serves.

### Adding it

`featureflag-bridge` is a separate artifact because an exported provider should be
something an app opts into. Add it to the host, beside `featureflag-android`, with its
version from the BOM as Getting started shows:

<!-- snippet: bridge-install -->
```kts
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-bridge")
}
```

That merges this into your app's manifest, for an app whose application id is
`com.example.app`:

<!-- snippet: bridge-merged-manifest -->
```xml
<permission
    android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE"
    android:protectionLevel="signature" />

<application>
    <provider
        android:name="com.andyyhope.featureflag.bridge.FlagBridgeProvider"
        android:authorities="com.example.app.featureflag"
        android:exported="true"
        android:permission="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
</application>
```

The permission's `signature` protection level is the whole security model: Android grants
it only to an app signed with the same key as yours, so a companion you did not sign
cannot even ask.

### Only in debug builds

If your release builds should carry no exported provider at all, make the bridge a debug
dependency. Everything your code calls — `SharedPreferencesSource`, `publishSchema` — is
in `featureflag-android`, so release builds still compile; they simply have no door:

<!-- snippet: bridge-debug-only -->
```kts
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-android")
    debugImplementation("com.andyyhope.featureflag:featureflag-bridge")
}
```

A companion pointed at a release build then cannot reach it: on Android 11 and later an
app without the bridge is hidden from the companion, which reports `HostNotFound`, and
below that it reports `BridgeNotInstalled`.

### The companion's side

A companion needs two things. It declares the host's permission in its manifest:

<!-- snippet: bridge-companion-permission -->
```xml
<uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
```

And it is signed with the host's key — for debug builds, usually the same debug
keystore. Android 11 and later also hides an app from another unless the other declares
an interest in it; the permission declaration is that interest, so no `<queries>` element
is needed.

The authority and the permission are both derived from the host's application id. A
host built with an `applicationIdSuffix`, such as `.debug`, has a different id, so a
companion has to name that id, both in its manifest and in `FlagCompanion(hostPackage = …)`.

### Trusting a companion signed with another key

On Android 12 (API 31) and later, Android lets an app grant a signature permission to apps
signed with other certificates it names: the `knownSigner` protection flag and the
`knownCerts` attribute, described in Android's
[Defining permissions](https://developer.android.com/guide/topics/permissions/defining).
This is platform behaviour, and this library's tests do not exercise it, so check it on a
device before relying on it. Applied to the bridge, the host redeclares the permission in
its own manifest, whose root declares
`xmlns:tools="http://schemas.android.com/tools"`:

<!-- snippet: bridge-known-signer -->
```xml
<permission
    android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE"
    android:knownCerts="@array/featureflag_companion_certificates"
    android:protectionLevel="signature|knownSigner"
    tools:replace="android:protectionLevel" />
```

and lists the digest of each companion's signing certificate in a resource, in the form
Android's documentation for `knownCerts` gives:

<!-- snippet: bridge-known-certs -->
```xml
<string-array name="featureflag_companion_certificates">
    <item>3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7</item>
</string-array>
```

Below API 31, `knownSigner` is ignored and only the same key works.

### In the samples

The sample host's id, `com.andyyhope.featureflag.sample.host`, is written once, as the
`featureflag.sample.hostId` property in the root `gradle.properties`. It becomes the host's
`applicationId`, the companion's `BuildConfig.HOST_PACKAGE`, and the `${hostId}`
placeholder in the permission the companion declares, so the three cannot disagree:

<!-- snippet: sample-companion-uses-permission -->
```xml
<uses-permission android:name="${hostId}.permission.FEATURE_FLAG_BRIDGE" />
```

Both sample apps' debug builds are signed with one committed, debug-only key. The
companion also has an `untrusted` build type, signed with another key and installed beside
the trusted one with `./gradlew :samples:companion:installUntrusted`, so you can watch the
bridge refuse it with `PermissionDenied`:

<!-- snippet: sample-companion-signing -->
```kts
signingConfigs {
    // The key the host is signed with too, so Android grants the host's signature permission.
    getByName("debug") {
        storeFile = file("../keys/shared-debug.keystore")
        storePassword = "android"
        keyAlias = "sample"
        keyPassword = "android"
    }
    // Any other key: the host must refuse this build with PermissionDenied.
    create("untrusted") {
        storeFile = file("../keys/untrusted.keystore")
        storePassword = "android"
        keyAlias = "untrusted"
        keyPassword = "android"
    }
}
buildTypes {
    create("untrusted") {
        initWith(getByName("debug"))
        signingConfig = signingConfigs.getByName("untrusted")
        // Installs beside the trusted companion instead of replacing it.
        applicationIdSuffix = ".untrusted"
        // The library modules have only debug and release.
        matchingFallbacks.add("debug")
    }
}
```

Those keys are committed for the samples' sake and must never sign anything real — see
[the samples](../samples/README.md).

### Connecting

`FlagCompanion` connects for you and shows what it finds. A companion of your own uses
`FlagBridgeClient` directly:

<!-- snippet: bridge-connect -->
```kotlin
val client = FlagBridgeClient(context, hostPackage = "com.example.app")

when (val connection = client.connect()) {
    is FlagBridgeConnection.Connected -> show(connection.schema)
    else -> showProblem(connection.message.orEmpty())
}
```

`connect()` checks everything a companion needs, in the order it is worth fixing, and
returns the first problem. It never throws:

| Result | Means |
|---|---|
| `Connected` | It works. `schema` is the host's published schema. |
| `UsesPermissionMissing` | The companion's manifest does not declare the host's permission. |
| `HostNotFound` | No app with that id is installed, or it does not include the bridge, which hides it all the same. |
| `BridgeNotInstalled` | The host is installed and visible, but without the bridge. |
| `PermissionDenied` | The two apps are signed with different keys. It carries both certificate digests. |
| `NotPublished` | The host has never published its schema. |
| `Unresponsive` | The host did not answer within the client's timeout. |
| `Incompatible` | The host speaks another bridge protocol, or published a schema this build cannot read. |

Each one's `message` says what to do about it, which is what `FlagCompanion` shows.

`client.source(schema)` then gives a `FlagBridgeSource`, a `MutableFlagValueSource` over
the host's overrides, which is what a `FlagEditingStore` edits. `source()` suspends while
it registers its observer. The source it returns writes and refreshes with blocking calls
into the host, so make those off the main thread (`FlagEditingStore` does this for you);
read `lastFailure` for the latest refresh failure, and `close()` it when done.
`client.send(signal)` sends a signal — see
Sending signals to the host app.

### A host that is not running

Android starts an app to answer a call to its provider, so a companion never needs the
host to be open. The provider waits for your `Application.onCreate` to return before it
answers, so the pole is built and the schema published by the time it does — the reason
both belong in `onCreate`.

A cold start takes time. The client's timeout, five seconds unless you pass another,
covers starting the host as well as answering, so give it more if your app starts slowly.

If the host dies in the middle of a call, the client retries once, and the companion
survives either way.

### What it refuses

- **Itself.** A companion is a separate app. The same app hosting the bridge and running
  `FlagCompanion` against itself is not supported.
- **Too much at once.** A request or a reply over 512 KiB is refused with
  `FlagBridgeException.TooLarge` rather than risking a binder failure. Real flag sets are
  far below that.
- **A value the schema does not accept.** A write is validated against your schema
  before it is stored, exactly as an import is.

### What it exposes

The provider serves your published schema, the overrides in the companion's file, and
the signals your app observes. It does not expose any other preferences, files or
databases, and an app that cannot hold the signature permission cannot call it at all.
That is also why a release build should either keep the bridge or drop it deliberately:
with the default `implementation` dependency, a release build signed with your release
key accepts a companion signed with that same key.
````

**Create** `docs/ExportingAndImporting.md`:

````markdown
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

Values land in the highest-priority mutable source — the local override layer — so an
imported value outranks anything a backend later sends, and stays until it is cleared.

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

If the source refuses a write partway through, the writes already made are undone before
the error reaches you.

### QR codes

For handing state to someone sitting next to you, a code on screen beats a file:

<!-- snippet: exporting-qr -->
```kotlin
val code = pole.qrCodeString() // "FFQR1:…"
```

The wire format is `FFQR1:` followed by base64url of a deflate-compressed payload.
Compression is what makes this practical: flag keys and JSON structure repeat heavily, so
realistic payloads shrink by an order of magnitude and fit where the raw JSON would not.
A code made on iOS imports here and one made here imports on iOS.

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
````

- [ ] **Step 5: Link B5's mention of a doc that now exists**

**Modify** `docs/DeclaringFlags.md`: replace

```markdown
When a test needs a specific value rather than the default, put a `SnapshotSource` on top
of a pole instead — see Sources and precedence.
```

with

```markdown
When a test needs a specific value rather than the default, put a `SnapshotSource` on top
of a pole instead — see [Sources and precedence](SourcesAndPrecedence.md).
```

- [ ] **Step 6: Run the tests and watch them pass, then run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
./gradlew build detekt
```

Expected: `BUILD SUCCESSFUL` both times; 112 tests: `SourcesAndPrecedenceTest` 10, `ObservingChangesTest` 4, `SharingWithACompanionAppTest` 5, `TheBridgeTest` 4, `ExportingAndImportingTest` 8, and the earlier 81.

- [ ] **Step 7: Commit**

```bash
git add docs-samples docs
git commit -m "Document sources and precedence, observing changes, the schema, the bridge, and export and import" -m "TheBridgeTest holds the manifests the docs show to FlagBridgeContract and to the bridge's own manifest." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B7: Signals, the companion, getting started, troubleshooting, the differences, and the README

**Files:**
- Create: `docs-samples/src/test/kotlin/docs/sendingsignals/SendingSignals.kt`, `SendingSignalsTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/buildingacompanionapp/BuildingACompanionApp.kt`, `BuildingACompanionAppTest.kt`
- Create: `docs-samples/src/test/resources/docs/buildingacompanionapp/companion-dependencies.kts`
- Create: `docs-samples/src/test/kotlin/docs/gettingstarted/GettingStarted.kt`, `GettingStartedTest.kt`
- Create: `docs-samples/src/test/resources/docs/gettingstarted/app-dependencies.kts`
- Create: `docs-samples/src/test/kotlin/docs/troubleshooting/Troubleshooting.kt`, `TroubleshootingTest.kt`
- Create: `docs-samples/src/test/kotlin/docs/readme/Readme.kt`, `ReadmeTest.kt`
- Modify (whole file): `docs-samples/src/test/kotlin/docs/DocOutputs.kt`
- Create: `docs/SendingSignals.md`, `docs/BuildingACompanionApp.md`, `docs/GettingStarted.md`, `docs/Troubleshooting.md`, `docs/DifferencesFromIOS.md`, `README.md`
- Modify: `docs/DeclaringFlags.md`, `docs/RecordFlags.md`, `docs/SharingWithACompanionApp.md`, `docs/TheBridge.md`, `docs/ExportingAndImporting.md` (links)

**Interfaces:**
- Consumes: everything B5 and B6 produced; `docs.sendingsignals.ConfigSignal` and `CacheSignal` are imported by `docs.buildingacompanionapp`; `docs.sync.DocsSync.ARTIFACTS` by `GettingStartedTest`; Seam 8 for the links to `samples/README.md`.
- Produces: the finished doc set — the README and all fifteen `docs/*.md` files the Contract lists.

`BuildingACompanionApp.md` merges FeatureFlagUI's two articles, and carries what M3 asked for: `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS` merging in from `featureflag-ui`, self-bridging refused, and a host `applicationIdSuffix` changing the bridge's authority and permission. `Troubleshooting.md` quotes the messages `FlagBridgeConnection` shows (computed, as outputs) and the KSP messages (in `text` fences, copied from `featureflag-ksp/.../Diagnostics.kt`, since no test can compile a broken declaration here). `DifferencesFromIOS.md` is the docs note's list, deduplicated and grouped, each with its reason, and has no code.

- [ ] **Step 1: Write the code the docs will quote**

**Create** `docs-samples/src/test/kotlin/docs/sendingsignals/SendingSignals.kt`. Its `ExampleApplication` is the documented host; the test runs its `onCreate`:

```kotlin
package docs.sendingsignals

import android.app.Application
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.signals.FlagSignal

// region snippet: signals-enum
enum class AppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    SignOut,
}
// endregion

// region snippet: signals-groups
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
// endregion

/** Stands in for an app's own image cache and session. */
class ImageCache {
    var entries = 3
        private set

    fun clear() {
        entries = 0
    }
}

class Session {
    var isSignedIn = true
        private set

    fun signOut() {
        isSignedIn = false
    }
}

/** A host that handles every [AppSignal]. Robolectric builds it, and runs onCreate, before each test. */
class ExampleApplication : Application(), AutoCloseable {
    val imageCache = ImageCache()
    val session = Session()
    var refetches = 0
        private set

    private fun refetchConfiguration() {
        refetches += 1
    }

    // region snippet: signals-host
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
    // endregion

    override fun close() {
        subscriptions.forEach(AutoCloseable::close)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/sendingsignals/SendingSignalsTest.kt`. `FlagSignalChannel.host` lives as long as the test JVM, so each test closes the application's observers. `aSendToAHostThatIsNotInstalledIsNotHandled`, which holds the `signals-send` regions, is a smoke test: it shows the calls compile and return `false` with no host, and cannot tell a missing provider from a timeout. That `send` returns `true` once a handler has run is proven across two processes by the bridge's instrumented `CrossProcessBridgeTest.aSignalIsHandledInTheHostProcess`, which CI's device job runs:

```kotlin
package docs.sendingsignals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** docs/SendingSignals.md. Robolectric builds [ExampleApplication] and runs its onCreate first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = ExampleApplication::class)
class SendingSignalsTest {

    private val application = ApplicationProvider.getApplicationContext<ExampleApplication>()
    private val context: Context = application
    private val received = mutableListOf<String>()

    private fun handleConfig(signal: ConfigSignal) {
        received += signal.name
    }

    private fun handleCache(signal: CacheSignal) {
        received += signal.name
    }

    @After
    fun stopObserving() {
        application.close()
    }

    @Test
    fun theHostHandlesEachSignalItObserves() {
        assertTrue(FlagSignalChannel.host.deliver("PurgeImageCache"))
        assertTrue(FlagSignalChannel.host.deliver("SignOut"))
        assertTrue(FlagSignalChannel.host.deliver("RefetchRemoteConfiguration"))

        assertEquals(0, application.imageCache.entries)
        assertFalse(application.session.isSignedIn)
        assertEquals(1, application.refetches)
    }

    @Test
    fun aSignalNobodyObservesIsNotHandled() {
        assertFalse(FlagSignalChannel.host.deliver("PurgeEverything"))
    }

    @Test
    fun eachGroupIsObservedOnItsOwn() {
        // region snippet: signals-observe-groups
        val config = FlagSignalChannel.host.observe(ConfigSignal.entries) { signal -> handleConfig(signal) }
        val caches = FlagSignalChannel.host.observe(CacheSignal.entries) { signal -> handleCache(signal) }
        // endregion

        FlagSignalChannel.host.deliver("ClearRemoteConfiguration")
        FlagSignalChannel.host.deliver("PurgeEverything")
        config.close()
        caches.close()

        assertEquals(listOf("ClearRemoteConfiguration", "PurgeEverything"), received)
    }

    @Test
    fun aSignalSaysWhatItIsAndWhetherItNeedsARelaunch() {
        assertEquals("Re-fetch remote configuration", ConfigSignal.RefetchRemoteConfiguration.signalDescription)
        assertEquals("PurgeImageCache", CacheSignal.PurgeImageCache.signalDescription)
        assertTrue(CacheSignal.PurgeEverything.requiresRestart)
        assertFalse(CacheSignal.PurgeImageCache.requiresRestart)
    }

    @Test
    fun aSendToAHostThatIsNotInstalledIsNotHandled() = runTest {
        // region snippet: signals-send
        val client = FlagBridgeClient(context, hostPackage = "com.example.app")

        val handled = client.send(AppSignal.RefetchRemoteConfiguration)
        // endregion

        // region snippet: signals-send-delayed
        val handledLater = client.send(CacheSignal.PurgeImageCache, delay = 3.seconds)
        // endregion

        assertFalse(handled)
        assertFalse(handledLater)
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/buildingacompanionapp/BuildingACompanionApp.kt`:

```kotlin
package docs.buildingacompanionapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.ui.FlagCompanion
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalGroupDisplay
import docs.sendingsignals.CacheSignal
import docs.sendingsignals.ConfigSignal

enum class Environment { Production, Staging }

@FlagContainer
interface AppFlags {
    @Flag(description = "Which backend this build talks to")
    val environment: Environment get() = Environment.Production

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false

    @Flag(description = "Minimum spend for express checkout")
    val expressMinimum: Double get() = 10.0
}

// region snippet: companion-whole-app
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlagCompanion(hostPackage = "com.example.app")
            }
        }
    }
}
// endregion

/** The companion with every kind of tab, as BuildingACompanionApp.md composes it. */
@Composable
fun CompanionWithTabs() {
    // region snippet: companion-tabs
    FlagCompanion(
        hostPackage = "com.example.app",
        tabs = listOf(
            FlagCompanionTab.Overrides,
            FlagCompanionTab.Signals(
                FlagSignalGroup("Configuration", ConfigSignal.entries),
                FlagSignalGroup("Caches", CacheSignal.entries),
            ),
            FlagCompanionTab.Flags,
            FlagCompanionTab.Detail(key = "environment", title = "Environment"),
        ),
    )
    // endregion
}

// region snippet: companion-custom-tab
val checkoutTab = FlagCompanionTab.Custom(id = "checkout", title = "Checkout", icon = Icons.Filled.ShoppingCart) {
    Column {
        for (entry in store.schema.flags.filter { it.key.rawValue.startsWith("checkout.") }) {
            FlagRow(entry)
        }
    }
}
// endregion

// region snippet: companion-nested-group
val caches = FlagSignalGroup("Caches", CacheSignal.entries, display = FlagSignalGroupDisplay.Nested)
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/buildingacompanionapp/BuildingACompanionAppTest.kt`. With no host installed, `FlagCompanion` shows a status screen with "Try again", which is what a companion shows on a phone without its host:

```kotlin
package docs.buildingacompanionapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.publishSchema
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.signals.FlagSignalGroupDisplay
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/BuildingACompanionApp.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BuildingACompanionAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun publishedDirectory() = folder.newFolder("exported").also { directory ->
        FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource())).publishSchema(directory)
    }

    @Test
    fun theWholeAppStarts() {
        val activity = Robolectric.buildActivity(CompanionActivity::class.java).setup().get()

        assertFalse(activity.isFinishing)
    }

    @Test
    fun aCompanionWithEveryKindOfTabShowsWhyItCannotConnect() {
        composeRule.setContent { MaterialTheme { CompanionWithTabs() } }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun customTabsAndGroupsAreDescribedUpFront() {
        assertEquals("checkout", checkoutTab.id)
        assertEquals("Checkout", checkoutTab.title)
        assertEquals(FlagSignalGroupDisplay.Nested, caches.display)
    }

    @Test
    fun aStoreEditsAnySource() = runTest {
        val exportedDirectory = publishedDirectory()

        // region snippet: companion-store
        val schema = FlagSchema.read(exportedDirectory)
        val store = FlagEditingStore(schema, SnapshotSource(name = "Preview"))
        // endregion
        val entry = checkNotNull(store.entry(FlagKey("checkout.google-pay")))

        // region snippet: companion-store-api
        val tree = store.tree // the flag tree, rebuilt from the flat schema
        val sections = store.sections(query = "pay") // a flat, searchable grouping
        val overridden = store.overriddenKeys // what has been changed
        val badge = store.overriddenCount(tree) // for a badge on a group row

        val value = store.value(entry) // the value in effect
        val isOverridden = store.isOverridden(entry)
        store.setValue(entry, FlagValueBox.Bool(true)) // suspends: writes go to the source off the main thread
        store.reset(entry)
        store.resetAll()
        // endregion

        val found = sections.flatMap { section -> section.entries.map { it.key } }
        assertEquals(listOf(FlagKey("checkout.google-pay")), found)
        assertEquals(emptyList(), overridden)
        assertEquals(0, badge)
        assertEquals(FlagValueBox.Bool(false), value)
        assertFalse(isOverridden)
        assertFalse(store.isOverridden(entry))
    }

    @Test
    fun aStoreMovesOverridesOnAndOffTheDevice() = runTest {
        val store = FlagEditingStore(FlagSchema.read(publishedDirectory()), SnapshotSource())
        val entry = checkNotNull(store.entry(FlagKey("checkout.google-pay")))
        store.setValue(entry, FlagValueBox.Bool(true))

        // region snippet: companion-store-transport
        val json = store.exportJson()
        val code = store.qrCode()

        store.import(code) // a document or a scanned code, told apart by the FFQR1: prefix
        // endregion

        assertTrue(json.contains("\"checkout.google-pay\" : true"))
        assertTrue(store.isOverridden(entry))
    }
}
```

**Create** `docs-samples/src/test/resources/docs/buildingacompanionapp/companion-dependencies.kts`:

```kotlin
// A companion app's dependencies, as BuildingACompanionApp.md shows them. Not compiled: DocsSyncTest
// checks the coordinates, and docs-samples itself depends on the same Compose artifacts to run the samples.

// region snippet: companion-install
dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    implementation("com.andyyhope.featureflag:featureflag-ui")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/gettingstarted/GettingStarted.kt`:

```kotlin
package docs.gettingstarted

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup

// region snippet: getting-started-declare
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/gettingstarted/GettingStartedTest.kt`. Its second test reads every install block and fails on an artifact name that is not published, which `DocsSync`'s version check does not cover (it checks only coordinates with a version):

```kotlin
package docs.gettingstarted

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.pole.FlagPole
import docs.sync.DocsSync
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/GettingStarted.md, and the install block README.md shares with it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GettingStartedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shown = mutableListOf<String>()

    private fun showNewOnboarding() {
        shown += "onboarding"
    }

    private fun addGooglePayButton() {
        shown += "google-pay"
    }

    @Test
    fun aPoleReadsEveryFlagThroughItsSources() {
        // region snippet: getting-started-pole
        val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
        val pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource(preferences)))

        if (pole.flags.newOnboarding) {
            showNewOnboarding()
        }

        if (pole.flags.checkout.googlePay) {
            addGooglePayButton()
        }
        // endregion

        assertEquals(emptyList(), shown)
        assertEquals(20, pole.flags.pageSize)
        preferences.edit().putBoolean("checkout.google-pay", true).commit()
        assertTrue(pole.flags.checkout.googlePay)
    }

    @Test
    fun theInstallBlocksNameOnlyPublishedArtifacts() {
        val coordinate = Regex("""com\.andyyhope\.featureflag:([a-z-]+)""")
        val blocks = listOf(
            "/docs/gettingstarted/app-dependencies.kts",
            "/docs/buildingacompanionapp/companion-dependencies.kts",
            "/docs/thebridge/host-dependencies.kts",
        ).map { checkNotNull(javaClass.getResource(it)).readText() }

        val artifacts = blocks.flatMap { block -> coordinate.findAll(block).map { it.groupValues[1] } }.toSet()

        assertTrue(DocsSync.ARTIFACTS.containsAll(artifacts), "$artifacts")
        assertTrue(blocks.first().contains("ksp(platform(\"com.andyyhope.featureflag:featureflag-bom:0.1.0\"))"))
    }
}
```

**Create** `docs-samples/src/test/resources/docs/gettingstarted/app-dependencies.kts`. The Contract's install block: the BOM for `implementation` and for `ksp`, with the literal version `0.1.0`:

```kotlin
// A host app's build.gradle.kts additions, as README.md and GettingStarted.md show them. Not compiled:
// DocsSyncTest checks the coordinates and the version, and docs-samples itself builds against the same modules.

// region snippet: install-app
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    ksp(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))

    implementation("com.andyyhope.featureflag:featureflag-android")
    implementation("com.andyyhope.featureflag:featureflag-bridge")
    ksp("com.andyyhope.featureflag:featureflag-ksp")
    testImplementation("com.andyyhope.featureflag:featureflag-test")
}
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/troubleshooting/Troubleshooting.kt`:

```kotlin
package docs.troubleshooting

import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract

private const val HOST = "com.example.app"

/** What a companion without the host's permission in its manifest shows. */
fun usesPermissionMissingMessage(): String =
    FlagBridgeConnection.UsesPermissionMissing(FlagBridgeContract.permission(HOST)).message

/** What a companion shows when it cannot see the host. */
fun hostNotFoundMessage(): String = FlagBridgeConnection.HostNotFound(HOST).message

/** What a companion signed with another key shows. */
fun permissionDeniedMessage(): String = FlagBridgeConnection.PermissionDenied(
    hostPackage = HOST,
    hostCertificates = listOf("3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7"),
    companionCertificates = listOf("B04C2E7A19D35F6C8E0A4B7D2F9C1E3A5B8D0F2C4E6A8B1D3F5C7E9A0B2D4F61"),
).message

/** What a companion shows before the host has ever published its schema. */
fun notPublishedMessage(): String = FlagBridgeConnection.NotPublished.message
```

**Create** `docs-samples/src/test/kotlin/docs/troubleshooting/TroubleshootingTest.kt`:

```kotlin
package docs.troubleshooting

import org.junit.Test
import kotlin.test.assertTrue

/** docs/Troubleshooting.md quotes the messages a companion shows; DocsSyncTest compares them word for word. */
class TroubleshootingTest {

    @Test
    fun eachMessageNamesWhatToDo() {
        assertTrue(usesPermissionMissingMessage().contains("<uses-permission android:name=\"com.example.app."))
        assertTrue(hostNotFoundMessage().contains("featureflag-bridge"))
        assertTrue(permissionDeniedMessage().contains("knownSigner"))
        assertTrue(notPublishedMessage().isNotEmpty())
    }
}
```

**Create** `docs-samples/src/test/kotlin/docs/readme/Readme.kt`:

```kotlin
package docs.readme

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup

// region snippet: readme-declaration
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

    @Flag(description = "Pricing tier to present")
    val tier: Tier get() = Tier.Free
}

enum class Tier { Free, Pro }
// endregion
```

**Create** `docs-samples/src/test/kotlin/docs/readme/ReadmeTest.kt`:

```kotlin
package docs.readme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** README.md's own samples. The rest of its samples are the articles', tested beside them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadmeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var googlePayButtons = 0

    private fun showGooglePayButton() {
        googlePayButtons += 1
    }

    @Test
    fun theQuickStartReadsThroughTheStack() {
        // region snippet: readme-pole
        val pole = FlagPole(
            AppFlagsSpec,
            sources = listOf(
                SharedPreferencesSource.companion(context), // the companion app's edits
                RemoteOverrideSource(AppFlagsSpec), // your backend
            ),
        )

        if (pole.flags.checkout.googlePay) {
            showGooglePayButton()
        }
        // endregion

        assertEquals(0, googlePayButtons)

        // region snippet: readme-resolution
        val sourceName = pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion", or null for the default
        // endregion

        assertNull(sourceName)
        pole.setOverride(AppFlagsSpec.newOnboarding, true)
        assertEquals("Companion", pole.resolution(AppFlagsSpec.newOnboarding).sourceName)
        pole.sources.filterIsInstance<SharedPreferencesSource>().forEach(SharedPreferencesSource::close)
    }
}
```

- [ ] **Step 2: Register the outputs**

**Replace** `docs-samples/src/test/kotlin/docs/DocOutputs.kt` with:

```kotlin
package docs

import docs.declaringflags.duplicateKeyMessage
import docs.exportingandimporting.exportedDocument
import docs.recordflags.duplicateRecordKeyProblem
import docs.recordflags.endpointsPayload
import docs.recordflags.nestedRecord
import docs.recordflags.recordSchema
import docs.recordflags.renamedFieldsPayload
import docs.recordflags.storedRecordText
import docs.remoteoverrides.arrayPathPayload
import docs.remoteoverrides.experimentsPayload
import docs.remoteoverrides.onboardingPayload
import docs.remoteoverrides.rejectionReport
import docs.sharingwithacompanionapp.publishedSchema
import docs.troubleshooting.hostNotFoundMessage
import docs.troubleshooting.notPublishedMessage
import docs.troubleshooting.permissionDeniedMessage
import docs.troubleshooting.usesPermissionMissingMessage
import docs.validatingaconfig.checkoutDefaultsReport
import docs.validatingaconfig.draftAuditReport
import docs.validatingaconfig.stagingDefaultsReport

/**
 * The value behind every `<!-- output: name -->` fence, computed by the code the document describes.
 * DocsSyncTest compares each fence with its entry, and fails on an entry no document shows.
 */
object DocOutputs {
    val all: Map<String, () -> String> = mapOf(
        // DeclaringFlags.md
        "declaring-duplicate-keys" to ::duplicateKeyMessage,
        // RecordFlags.md
        "records-duplicate-key" to ::duplicateRecordKeyProblem,
        "records-stored-text" to ::storedRecordText,
        "records-schema" to ::recordSchema,
        "records-endpoints-payload" to { endpointsPayload },
        "records-renamed-payload" to { renamedFieldsPayload },
        "records-nested-record" to { nestedRecord },
        // RemoteOverrides.md
        "remote-onboarding-payload" to { onboardingPayload },
        "remote-array-payload" to { arrayPathPayload },
        "remote-rejection-report" to ::rejectionReport,
        "remote-experiments-payload" to { experimentsPayload },
        // ValidatingAConfig.md
        "validating-audit-report" to ::draftAuditReport,
        "validating-defaults-report" to ::stagingDefaultsReport,
        "validating-record-defaults-report" to ::checkoutDefaultsReport,
        // SharingWithACompanionApp.md
        "sharing-schema" to ::publishedSchema,
        // ExportingAndImporting.md
        "exporting-json" to ::exportedDocument,
        // Troubleshooting.md
        "troubleshooting-uses-permission-missing" to ::usesPermissionMissingMessage,
        "troubleshooting-host-not-found" to ::hostNotFoundMessage,
        "troubleshooting-permission-denied" to ::permissionDeniedMessage,
        "troubleshooting-not-published" to ::notPublishedMessage,
    )
}
```

- [ ] **Step 3: Run the tests and watch DocsSyncTest fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
```

Expected: `BUILD FAILED` with only `everyDocumentMatchesTheCodeItQuotes` failing (126 tests, 125 passing). It lists 24 problems: the 20 new regions and the 4 `troubleshooting-*` outputs.

- [ ] **Step 4: Write the five docs and the README**

**Create** `docs/SendingSignals.md`:

````markdown
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
It returns `false` when no observer in the host lists that signal, when a handler threw,
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
````

**Create** `docs/BuildingACompanionApp.md`:

````markdown
# Building a companion app

An editor for another app's flags, rendered entirely from the schema that app publishes.

## Overview

A companion app is an ordinary app that happens to edit somebody else's flags. It links
`featureflag-ui`, connects to the host through the bridge, and renders whatever schema
the host published.

It does **not** link the host app's code, import its modules, or know its types. Change
a flag in the host, rebuild the host, and the companion picks it up — there is nothing to
keep in step. One companion build can edit any app that publishes a schema, and your
shipping app carries no editor UI at all.

### Adding the dependencies

The companion depends on `featureflag-ui`, and on Compose itself to draw it:

<!-- snippet: companion-install -->
```kts
dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    implementation("com.andyyhope.featureflag:featureflag-ui")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
```

`featureflag-ui` keeps Compose to itself, so an app that wants `MaterialTheme` and
`setContent` names them, and applies the Compose compiler plugin as any Compose app does.

### The whole app

<!-- snippet: companion-whole-app -->
```kotlin
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlagCompanion(hostPackage = "com.example.app")
            }
        }
    }
}
```

That is not an abbreviation of the real thing — it *is* the app. `FlagCompanion` connects
to the host, reports anything in the way with a message someone can act on, and gives you
Overrides and Flags as tabs. It reconnects whenever the companion comes back to the
foreground, so a reinstalled host with new flags is picked up. None of it is specific to
any host, so none of it is worth writing twice. It draws with your `MaterialTheme`.

Two things make the connection possible, and both are described in
[The bridge](TheBridge.md). The companion declares the host's permission:

<!-- snippet: bridge-companion-permission -->
```xml
<uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
```

and it is signed with the host's key. `hostPackage` is the host's application id, so a
host built with an `applicationIdSuffix`, such as `.debug`, is a different host: both the
permission and `hostPackage` have to name the suffixed id.

The companion has to be its own app. A host cannot run `FlagCompanion` against itself:
the bridge refuses calls from its own process, and the host already has its pole.

`featureflag-ui` merges a little into the companion's manifest: `POST_NOTIFICATIONS` and
`POST_PROMOTED_NOTIFICATIONS`, for the countdown of a delayed signal, and a private
`FileProvider` for sharing a QR code's image. Nothing needs adding by hand.

### Choosing the tabs

Tabs are a list you compose, so a companion takes only what it needs:

<!-- snippet: companion-tabs -->
```kotlin
FlagCompanion(
    hostPackage = "com.example.app",
    tabs = listOf(
        FlagCompanionTab.Overrides,
        FlagCompanionTab.Signals(
            FlagSignalGroup("Configuration", ConfigSignal.entries),
            FlagSignalGroup("Caches", CacheSignal.entries),
        ),
        FlagCompanionTab.Flags,
        FlagCompanionTab.Detail(key = "environment", title = "Environment"),
    ),
)
```

They appear in that order, and the first starts selected. Each tab has an id; two with
the same one throw an `IllegalArgumentException`, because one of them could never be
reached.

`Signals` is opt-in — it needs your signal enums, so an app without them simply leaves it
out. See [Sending signals to the host app](SendingSignals.md).

### Promoting one flag to its own screen

Some flags earn a tab. Not because they are a special kind — the companion reads them
from the schema like any other — but because of their consequence: a flag that decides
which backend the app talks to moves half the values on the Flags tab with it, and
hunting for it in a list beside forty others undersells that.

`FlagCompanionTab.Detail(key = "environment", title = "Environment")` above does that.
The screen lays an enum's entries out rather than tucking them into a menu, and shows
what the schema knows about the flag — its key, its default, and whether a remote
payload can change it. Any flag works; enums are simply the ones this suits best.

Which key deserves it is knowledge about a particular app, which is why it is a
parameter rather than something the library guesses.

### Filing signals into groups

An app with four signals wants a list; an app with forty wants them filed. Each group is
its own enum, so the host keeps handling one at a time. A group lays out beside the
delay picker while it has six signals or fewer, and gets a screen of its own beyond
that. Say so yourself when that is the wrong call:

<!-- snippet: companion-nested-group -->
```kotlin
val caches = FlagSignalGroup("Caches", CacheSignal.entries, display = FlagSignalGroupDisplay.Nested)
```

`FlagSignalGroupDisplay.Flat` keeps a group inline however long it grows. The threshold
of six is `FlagSignalGroup.AUTOMATIC_NESTING_THRESHOLD`, and it is fixed.

### Adding your own tab

Some tabs only your app can supply — a screen built around one area of your flags, say.
A custom tab is handed the same store the built-in tabs use, and the standard row for any
flag, so it needs no editors of its own:

<!-- snippet: companion-custom-tab -->
```kotlin
val checkoutTab = FlagCompanionTab.Custom(id = "checkout", title = "Checkout", icon = Icons.Filled.ShoppingCart) {
    Column {
        for (entry in store.schema.flags.filter { it.key.rawValue.startsWith("checkout.") }) {
            FlagRow(entry)
        }
    }
}
```

Put it in the `tabs` list wherever it belongs.

### Following a delayed signal from the host app

A delay exists so you can switch to the host before the signal fires, which takes the
countdown out of sight. The companion keeps it in sight as a notification: on Android 16
(API 36) and later, a progress notification that asks to be promoted to a Live Update
(which Android honours from API 36.1), and a countdown chronometer below API 36.

The first time someone picks a delay, the companion asks for the notification permission
on Android 13 and later. Refusing it costs only the notification: the signal is still
sent. The host holds the delay, so the signal fires on time even when Android freezes
the companion in the background — but a frozen companion cannot update its notification,
which then times out at the moment the signal was due.

### The two built-in screens

**Overrides** answers *what have I changed?* — the question you actually have before
filing a bug or handing a device to someone else. It lists only what has been changed,
with a per-row reset, the exported JSON inline and copyable, and the actions that move
overrides off the device: a QR code, which can be copied as an image, shared, or copied as
text; sharing the JSON; importing; and resetting everything, which asks first.

**Flags** answers *what can I change?* — the whole tree. Groups are rows you push into,
each showing how many flags it holds and how many are overridden; searching flattens the
whole tree, because someone hunting for a key by name should not have to guess which
group it was filed under.

Everything else — which control a flag gets, how an enum becomes a picker, how a
`ByteArray` flag is edited, what a reset does — comes from the schema and needs no work
from you. A text field writes when it loses focus or you press Done, and only if what it
holds has changed; text that is not a valid value for the flag is refused, with a
warning, rather than written. A write the host refuses shows why, and the row goes back
to the value the host holds.

A list of scalars gets a row per element, with the control its element type deserves.
Add and delete come with it, and each row's "Actions" menu moves it up or down. Lists of
lists and maps stay as JSON text, because no row-per-element layout reads well for
either.

A flag holding a list of records gets a row per record showing its key, or its first
field, and the verbs a list needs: add, and duplicate, move or delete from each row's
"Actions" menu. Each record opens onto a screen of its own, a row per field with the
control that field's type calls for, including a picker for an enum field.

None of this needs the host's Kotlin types. A record arrives as a map of boxes and a
published shape, which is enough to lay it out and enough to write it back the way the
host will read it — new records start every enum field on a real entry, so the companion
cannot write a list the app would reject. When the stored text is not a list of records
at all, the screen says so rather than showing an empty list, because an empty list would
invite you to add one and wonder why nothing changed.

### Platforms

Android only, minimum SDK 26, drawn with Material 3. The core library is plain Kotlin,
but a companion is an Android app by definition: it needs the bridge to reach the host.

## Going further

### Editing something other than a host

`FlagEditingStore` is the companion's model of one host: plain Kotlin over a schema and
any `MutableFlagValueSource`. The same model can edit a schema loaded from a file, a
fixture in a test, or an in-memory store in a preview:

<!-- snippet: companion-store -->
```kotlin
val schema = FlagSchema.read(exportedDirectory)
val store = FlagEditingStore(schema, SnapshotSource(name = "Preview"))
```

Over the bridge, the source is the one `FlagBridgeClient.source(schema)` returns.

### Building your own screens

The store exposes everything the built-in screens use, so a custom editor is not a fork:

<!-- snippet: companion-store-api -->
```kotlin
val tree = store.tree // the flag tree, rebuilt from the flat schema
val sections = store.sections(query = "pay") // a flat, searchable grouping
val overridden = store.overriddenKeys // what has been changed
val badge = store.overriddenCount(tree) // for a badge on a group row

val value = store.value(entry) // the value in effect
val isOverridden = store.isOverridden(entry)
store.setValue(entry, FlagValueBox.Bool(true)) // suspends: writes go to the source off the main thread
store.reset(entry)
store.resetAll()
```

Reads are immediate. Writes suspend, because a write over the bridge is a call into the
host, and throw whatever the source throws. `store.revision` is a `StateFlow` that moves
after every write, failed or not, which is what a screen collects to know when to read
again. Call `store.observeIn(scope)` once and it moves on the source's own changes too.

### Moving overrides on and off the device

The same transport the core library has, driven from the store:

<!-- snippet: companion-store-transport -->
```kotlin
val json = store.exportJson()
val code = store.qrCode()

store.import(code) // a document or a scanned code, told apart by the FFQR1: prefix
```

Import is strict and all-or-nothing, as everywhere else, and lands as one write: one
unknown key or wrong type rejects the whole document rather than leaving a device on
half a configuration.

### Choosing a control

`FlagSchema.Entry.editorKind` says which control the built-in screens use for a flag, as a
`FlagEditorKind`: a `Picker` for anything with entries, `Records` for a record list, then
by type — `Toggle`, `Integer`, `Decimal`, `Text`, `Date`, `Url`, `Data`, a `List` for an
array of scalars, and `Json` for maps and lists of collections. A screen of your own can
switch on it to pick the same controls.

### What the store will not show you

An override the host app would ignore is not displayed as an override. The host's file
can hold a stale or hand-edited value of the wrong type, or an enum entry this build no
longer has; the host skips those and falls back to its default, so the editor has to as
well.

Showing it would be worse than useless — it would claim a value the app is quietly
ignoring, and bind a control to something it cannot render.
````

**Create** `docs/GettingStarted.md`:

````markdown
# Getting started

Go from an empty project to a flag you can toggle from a second app.

## Overview

This walks the whole path in five steps. Each one works on its own — you can stop after
step three and still have type-safe flags with defaults, and come back for the companion
app when you need someone else to change a value on a device you are not holding.

### 1. Add the dependencies

In your app module's `build.gradle.kts`:

<!-- snippet: install-app -->
```kts
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    ksp(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))

    implementation("com.andyyhope.featureflag:featureflag-android")
    implementation("com.andyyhope.featureflag:featureflag-bridge")
    ksp("com.andyyhope.featureflag:featureflag-ksp")
    testImplementation("com.andyyhope.featureflag:featureflag-test")
}
```

0.1.0 is not on Maven Central yet. Until it is, build it from a clone of this repository
with `./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0`, and add `mavenLocal()` to your
build's repositories.

The BOM keeps every artifact on one version. KSP's configuration does not inherit from
`implementation`, so the BOM is named for it separately. KSP itself has to match your
Kotlin version; the processor is built against KSP 2.2.10-2.0.2 and tested with Kotlin
2.2.10.

On AGP 9, add this to `gradle.properties` too, because this KSP still adds its generated
sources through the `kotlin.sourceSets` API that AGP 9's built-in Kotlin forbids:

```
android.disallowKotlinSourceSets=false
```

Gradle prints a one-line warning that the option is experimental, which is expected.

`featureflag-bridge` is what a companion app talks to. Leave it out until step four if
you only want flags for now, and see [The bridge](TheBridge.md) for keeping it out of
release builds. Add `featureflag-ui` **only** to a companion app — your shipping app
never links it.

Pre-1.0, a minor version can break the API. Name an exact version, as above, rather than
a range.

### 2. Declare your flags

A container is an interface annotated with `@FlagContainer`. Each flag is a property with
a getter, which is its compiled default:

<!-- snippet: getting-started-declare -->
```kotlin
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
```

The processor generates `AppFlagsSpec` beside it, which is what a pole is built from.

`description` is not a comment. It is what a companion app puts next to the control, so
write it for whoever is looking at the list wondering which switch to flip.

### 3. Build a pole and read flags

A `FlagPole` binds a container to an ordered list of sources.

<!-- snippet: getting-started-pole -->
```kotlin
val preferences = context.getSharedPreferences("flags", Context.MODE_PRIVATE)
val pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource(preferences)))

if (pole.flags.newOnboarding) {
    showNewOnboarding()
}

if (pole.flags.checkout.googlePay) {
    addGooglePayButton()
}
```

Make it one instance your app shares. Reads are lock-free and safe from any thread, so it
does not need to live on the main thread — see [Observing changes](ObservingChanges.md).

With no override stored anywhere, every flag reports its compiled default. That is
already useful: you have one place that lists what your app can turn on, with
descriptions, and nothing to configure.

### 4. Share the flags with a companion app

A companion app reaches your flags through the bridge, so the host needs three things:
`featureflag-bridge` in its dependencies, the companion's source at the top of the stack,
and its schema published on launch. All of it belongs in `Application.onCreate`:

<!-- snippet: sharing-setup -->
```kotlin
class ExampleApplication : Application() {
    lateinit var pole: FlagPole<AppFlags>
        private set

    override fun onCreate() {
        super.onCreate()
        pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.companion(this)))
        pole.publishSchema(this)
    }
}
```

`SharedPreferencesSource.companion(context)` opens the file the bridge serves to a
companion. `publishSchema(context)` writes the schema the bridge serves with it. The
companion has no other way to learn what flags exist, what they are called, or what types
they hold — it never links your code.

Publish on every launch, in `onCreate`. The bridge waits for `onCreate` to finish before
it answers a companion, for up to ten seconds, so a schema published there is in place —
even when the companion's request is what started your app. See
[Sharing flags with a companion app](SharingWithACompanionApp.md).

### 5. Render it in the companion

The companion is a second app, with its own module, which applies the Compose compiler
plugin as any Compose app does:

<!-- snippet: companion-install -->
```kts
dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    implementation("com.andyyhope.featureflag:featureflag-ui")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
```

It declares your app's bridge permission, named from your application id:

<!-- snippet: bridge-companion-permission -->
```xml
<uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" />
```

and it is signed with the same key as your app — for debug builds, the same debug
keystore. That is the whole of the trust between them.

The app itself is one composable:

<!-- snippet: companion-whole-app -->
```kotlin
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlagCompanion(hostPackage = "com.example.app")
            }
        }
    }
}
```

Edits reach your running app the moment they are made: the bridge writes them into your
app's own preferences file, and the source on top of your stack hears it. If your app is
not running, the companion's first request starts it.

`samples/` in the repository is exactly this: two Android apps, one declaring flags and
one editing them. See [the samples](../samples/README.md).

## Where to go next

- [Declaring flags](DeclaringFlags.md) — nesting, keys, remote paths, and flags with no
  pole behind them.
- [Flag values](FlagValues.md) — the types a flag can hold, including your own enums.
- [Sources and precedence](SourcesAndPrecedence.md) — stacking sources, and finding out
  which one won.
- [Building a companion app](BuildingACompanionApp.md) — tabs, signals, and your own
  screens.
````

**Create** `docs/Troubleshooting.md`:

````markdown
# Troubleshooting

The failures worth recognising on sight, and what each one actually means.

## Overview

Most of these are properties of the platform rather than bugs in the library, which is
why they are documented rather than fixed. A companion that cannot connect always says
why, in words; the first section is those words.

### The companion cannot connect

`FlagCompanion` shows the first thing in the way, and `FlagBridgeClient.connect()`
returns it. Each has its own fix.

**The companion does not declare the permission.**

<!-- output: troubleshooting-uses-permission-missing -->
```text
This app does not request 'com.example.app.permission.FEATURE_FLAG_BRIDGE'. Add <uses-permission android:name="com.example.app.permission.FEATURE_FLAG_BRIDGE" /> to the companion's manifest.
```

**The host cannot be found.** Either it is not installed, or its application id is not
the one the companion names — a debug build with an `applicationIdSuffix` is a different
app — or it does not include `featureflag-bridge`. On Android 11 and later an app without
the bridge is invisible to the companion, so the last case looks exactly like the first:

<!-- output: troubleshooting-host-not-found -->
```text
No app with the package 'com.example.app' is installed, or it does not include featureflag-bridge. Install and run the host app, check the package name (a debug build often adds a suffix such as '.debug'), and check that the host depends on featureflag-bridge.
```

On Android 10 and earlier, a host without the bridge is visible, and the companion says
so directly: `BridgeNotInstalled`.

**The two apps are signed with different keys.** The companion shows both certificates,
so you can see which build is the odd one out:

<!-- output: troubleshooting-permission-denied -->
```text
'com.example.app' only lets apps signed with its own key connect. Sign this app with the same key, or list this app's certificate in the host's knownSigner permission (Android 12 and later). Host: 3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7. This app: B04C2E7A19D35F6C8E0A4B7D2F9C1E3A5B8D0F2C4E6A8B1D3F5C7E9A0B2D4F61.
```

Sign both with the same key, or list the companion's certificate in the host's
`knownSigner` permission — see [The bridge](TheBridge.md).

**The host has not published a schema.**

<!-- output: troubleshooting-not-published -->
```text
No schema has been published. The host app writes one with publishSchema(), so run it at least once.
```

Run the host once — publishing happens when *it* launches, in `Application.onCreate`, not
when the companion does. If it has run and the companion still says this, the host is not
calling `publishSchema(context)`.

**The host does not answer.** `Unresponsive` means the call timed out. Starting a host
that is not running counts against the timeout, so a slow `Application.onCreate` can cause
it; open the host once, or give the companion a longer `timeout`.

**`Incompatible`** means the host was built with a different version of the bridge
protocol, or published a schema this build cannot read. Build both apps from the same
version of the library.

### A flag changed in the companion but the app did not react

Check in this order:

1. **Is the companion's source in the stack?** Only `SharedPreferencesSource.companion(context)`
   reads the file the bridge writes. A `SharedPreferencesSource` over any other file never
   sees a companion's edit.
2. **Is something above it winning?** Ask:

<!-- snippet: readme-resolution -->
```kotlin
val sourceName = pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion", or null for the default
```

3. **Is the code reading the flag once and keeping the value?** A value read at start-up
   and stored stays what it was. Collect `pole.flow(ref)` instead — see
   [Observing changes](ObservingChanges.md).

### "Flags must resolve to unique keys" on the first launch

Two flags encode to the same storage key. The message names both properties:

<!-- output: declaring-duplicate-keys -->
```text
Flags must resolve to unique keys, but 'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly. Rename one of the properties, or use a KeyEncoding that tells them apart.
```

There is no runtime remedy — both flags want the same storage, and letting one win
silently would mean the other never takes effect anywhere — so this fails when the schema
is built rather than later. Renaming a property is usually the fix.

The default encoding collides only when two names differ just in letter case, as an
acronym's does in `useHTTPSOnly` and `useHttpsOnly`.
A custom `KeyEncoding` collides far more easily: anything that maps two distinct property
names onto one string will do it. A unit test that builds the schema finds it first — see
[Declaring flags](DeclaringFlags.md#keys).

### The processor rejects a declaration

The processor reports at the declaration that is wrong. The messages worth recognising:

```text
flags cannot be optional: a flag always has a value, because 'default' is what it falls back to. Use a sentinel the type already has, or an enum with a case for 'unset'
```

A flag's type cannot be nullable. `null` already means "no source supplied one" at every
layer beneath — `box()` returning `null` means *try the next source*, and
`setOverride(key, null)` means *remove the override* — so a flag whose value could be
`null` would be indistinguishable from one nothing supplies. Use a sentinel the type
already has, or an enum with an entry for unset.

```text
'checkout' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. Add '@FlagGroup(description = …)' if it is a nested container, '@Flag(description = …)' and a default getter if it is a value, or give it a getter if it is neither
```

Almost always a nested container written without `@FlagGroup`.

```text
'@Flag' needs a 'default' value to fall back to: give 'pageSize' a getter, as in 'val pageSize: Int get() = …'
```

```text
a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' or 'List<Endpoint>'
```

A `@FlagRecord` class declared as a flag's own type, or as a plain list of one.

```text
'flagDefaults' is reserved for the code generated for 'AppFlags'. Rename the property
```

Six names belong to the generated code: `flagDefaults`, `flagDescriptors`,
`flagContainerDescription`, `createFlags`, `flagLookup` and `flagKeys`.

### KSP fails with "Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"

KSP 2.2 still adds its generated sources the way AGP 9's built-in Kotlin forbids. Until a
KSP release stops, an Android module that runs KSP needs this in `gradle.properties`:

```
android.disallowKotlinSourceSets=false
```

Gradle then prints a one-line warning that the option is experimental, which is
expected.

### "illegal annotation class 'Flag'"

Your package declares its own type called `Flag`, and the file imports the annotation
with a wildcard, `import com.andyyhope.featureflag.*`, so your type wins. Import the
annotation under another name:

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

### A remote payload appears to do nothing

Ask what the apply reported:

<!-- snippet: remote-apply-result -->
```kotlin
val result = remote.apply(bytes)

val applied = result.appliedKeys // flags this payload set
val absent = result.absentKeys // flags with a remoteKey the payload did not mention
```

A flag in `absentKeys` fell through to whatever sits below the remote source. Usually the
backend key changed, or the dot path has a typo. A flag with no `remoteKey` at all is not
remotely overridable and will never appear in either list.

If nothing applied and it threw instead, `RemoteOverrideException.Rejected` carries every
problem — one bad field rejects the whole payload by design. A
[mapping audit](ValidatingAConfig.md) shows both directions at once.

### Importing throws `Rejected` with `UnknownKey`

The document was exported from a different app, or from a build where that flag has since
been renamed or removed. Import is all-or-nothing so an app never runs on half a
configuration; there is no partial-apply option, on purpose.

### A QR code will not encode

`FlagQRCodeException.PayloadTooLarge` carries the override count so you can say what to
remove. `ByteArray` flags are the usual culprit: base64 does not compress, and one blob
can exhaust the whole code. Export a document and share that instead.

### A signal reports that it was not handled

`send` returns `false` when no observer in the host lists the signal, when the handler
threw, when the host could not be reached, or when it did not answer in time. A host that
is not running is started, but starting it counts against the timeout, so a slow
`Application.onCreate` can be the cause: pass a longer `timeout`. Check too that the host
registers its observers in `Application.onCreate`, that both apps agree on the signal's
name — the same shared enum — and that the handler returns within the timeout. See
[Sending signals to the host app](SendingSignals.md).

### A record flag reads as its default, and the companion says "This value is not a list of records."

A `FlagRecords` list is all of its records or none of them. One record missing a field,
or holding the wrong type in one, and the whole list is unreadable — so the flag falls
back to its default, silently, exactly as any other type mismatch does.

The usual causes are a file edited by hand, and a build that has added a field since the
stored value was written. The companion cannot produce one: it builds records from the
published shape.

Reset the flag to clear it. To see what is actually stored:

<!-- snippet: records-resolution -->
```kotlin
val stored = pole.resolution(AppFlagsSpec.paymentMethods)
```

Give a new field a default and this stops being a problem: a record stored before the
field existed is filled from it rather than rejected.

<!-- snippet: records-added-field -->
```kotlin
@FlagRecord
data class Mirror(
    val name: String,
    val url: URI,
    val weight: Int = 1, // added in a later build
)
```

A field with no default has nothing honest to fall back to, so a record missing one is
still refused. A field that is *present* but holds the wrong type is refused either way —
filling in an absent field is a migration, overwriting a wrong one would be guessing.

### Everything reverts when a config refresh lands

The remote source is above the local one in the stack. Reverse them:

<!-- snippet: remote-precedence -->
```kotlin
val pole = FlagPole(AppFlagsSpec, sources = listOf(sharedPreferences, remote))
```

Local overrides belong on top, so a value set by hand survives the next refresh.
````

**Create** `docs/DifferencesFromIOS.md`:

```markdown
# Differences from iOS

Where this library departs from FeatureFlag for iOS, and why.

## Overview

This is a port of [FeatureFlag](https://github.com/andyyhope/FeatureFlag) 0.12.0. The
model is the same — a container of flags, an ordered stack of sources, a schema a
companion app renders — and the formats are the same: an export, a QR code or a schema
written on one platform reads on the other.

Everything below but the last section is a deliberate difference; the last is a quirk the
two share. Each is one line on what differs and one on why.

### Declaring and reading

- **Containers are interfaces read by KSP, not macros on structs.** Kotlin has no macros;
  the `featureflag-ksp` processor generates `AppFlagsSpec` and `AppFlagsRefs` instead.
- **The default is the property's getter, evaluated once.** A getter that reads another
  flag sees its compiled default, and `Instant.now()` keeps its first value, so every way
  of reading a flag agrees.
- **`description` and `remoteKey` are annotation arguments; an empty `remoteKey` means
  none.** Annotation arguments cannot be null.
- **A flag's handle is a `FlagRef` on the spec, not a `$` projected value.** Kotlin has no
  projected values, so `AppFlagsSpec.checkout.googlePay` names the flag, `pole[ref]` reads
  it and `pole.flow(ref)` watches it.
- **There is no `pole.someFlag` shorthand.** Kotlin has no dynamic member lookup, so a
  flag is always read as `pole.flags.someFlag`, and no flag name collides with the pole's.
- **Six property names are reserved instead of five.** `flagDefaults`, `flagDescriptors`,
  `flagContainerDescription`, `createFlags`, `flagLookup` and `flagKeys` belong to the
  generated code.
- **A container must be an interface with no type parameters and no supertypes, and not
  private.** The generated spec has to implement it and know every flag's type.
- **Nested declarations get `_`-joined generated names.** `Outer.Inner` generates
  `Outer_InnerSpec`, which keeps generated names unique.
- **A group or record from another module is found by its generated name.** KSP sees one
  module at a time, so apply the processor to every module that declares flags.
- **A container that depends on one the processor rejected is not generated either.**
  Only the root cause is reported, rather than a cascade.
- **A type of your own called `Flag` is handled with an import alias.** Kotlin has no
  module-qualified attribute syntax like `@FeatureFlag.Flag`.

### Values

- **The built-in types are Kotlin's.** `Boolean`, `Int`, `Long`, `Double`, `Float`,
  `String`, `ByteArray`, `Instant` and `URI`, with `Int` and `Long` both stored as the
  schema's `int`.
- **There is no custom `FlagValue` conformance.** The processor picks a codec at compile
  time and cannot find one you wrote; store the underlying type and wrap it.
- **Every enum publishes its entries, with no opt-in.** The processor can always list
  them, so every enum flag is a picker.
- **A plain enum is stored by its entry's `name`.** No raw value is needed; implement
  `FlagStringEnum` or `FlagIntEnum` to store something else.
- **An integer-backed enum's raw value is a `Long`.** It is the type every integer is
  stored as.
- **A record's fields are its primary constructor's properties.** A property in the class
  body is not a field, and `@FlagRecordKey` and `@FlagRecordProperty` go on constructor
  parameters.
- **A field's default is read from a record built with placeholders.** So a default must
  not depend on another constructor parameter.
- **`FlagRecords` is a `List` with no keyed subscript.** Find a record with
  `firstOrNull { it.name == … }`.
- **A remote record field holding infinity is a `TypeMismatch`.** It is refused like any
  other bad value rather than escaping as an exception.

### JSON, dates and numbers

- **JSON is read and written by the library itself.** That is what keeps the bytes
  identical to Apple's `JSONSerialization` output.
- **Only UTF-8 input is read.** UTF-16 and UTF-32 documents are refused.
- **Numbers past `Double` precision or `Long` range read differently.** Apple routes them
  through `NSDecimalNumber`; here an integer above `Long.MAX_VALUE` reads as a `Double`.
- **`-0.0` reads as `0`.** The JSON reader does not keep the sign of zero.
- **Dates are ISO 8601 in UTC, to the second, in their canonical forms only.** The lenient
  forms Apple's parser accepts are refused, so a date means one thing.
- **Keys outside ASCII sort by code point.** Apple sorts them with ICU collation, so a
  document with such keys may order them differently, though it reads the same.
- **A URL with a non-ASCII host may be encoded differently.** Percent-encoding follows
  Apple's `URL(string:)`, but host names are not converted the same way.
- **`\u` escapes accept ASCII hex digits only.** The reader is strict where Apple's is
  lenient.
- **A long value in a message is cut by code points.** Swift counts grapheme clusters, so
  a message quoting a value with emoji or combining marks may cut it at a different place.
- **Error messages say "the store" where iOS says "UserDefaults"**, and drop the App
  Group, property-list and Core Image cases, which do not exist here.

### The pole, sources and transport

- **Changes are Kotlin flows, not Combine publishers or `ObservableObject`.** `flow(ref)`
  emits the current value and then each distinct change, in the collector's context.
- **A custom source announces changes through `FlagChangeEmitter`.** A flow with a bounded
  buffer can drop a change and leave a flag stale; the emitter merges instead.
- **Errors are exceptions.** `RemoteOverrideError.rejected` is
  `RemoteOverrideException.Rejected`, `FlagImportError` is `FlagImportException`,
  `FlagQRCodeError` is `FlagQRCodeException`, and so on; a problem's kind is a PascalCase
  `Kind` entry, such as `Kind.UnknownKey`. `FlagMappingAuditException` lives in the `audit`
  package, and there is no `imageGenerationFailed`. Kotlin has no throwing enums.
- **`UserDefaultsSource` is `SharedPreferencesSource`.** `Boolean`, `Long`, `Float` and
  `String` are stored natively and every other type as JSON text. A value stored as
  another type reads as nothing, with no numeric conversion, and a write that changes
  nothing is skipped. The source holds its change listener strongly, because
  `SharedPreferences` holds listeners weakly.
- **Source names are "Companion" and "Remote", not "App Group".** They are what
  `resolution(…).sourceName` reports for `SharedPreferencesSource.companion(context)` and
  `RemoteOverrideSource`.
- **App Groups, Darwin notifications and the foreground re-read are replaced by the
  bridge.** Android has no shared container between apps; an exported, signature-guarded
  provider is the door instead.
- **`applicationName` defaults to nothing.** There is no bundle display name to fall back
  on, so the key is left out unless you give one.
- **`publishSchema(context)` writes to `noBackupFilesDir`.** A restored backup never brings
  back another build's schema.
- **`applyPayload` returns nothing, and `importJson` and `importQRCode` return the payload
  they applied.** An import result adds nothing the payload does not already say.
- **There are no property lists.** JSON is the only format, for export, import and
  remote payloads.
- **There is no `qrCodeImage()`.** The core library has no image library; the companion
  draws the code.
- **A QR code's compressed bytes may differ from iOS's.** The zlib implementations differ,
  but the JSON inside is identical and each platform reads the other's codes.
- **`EnvironmentConfiguration.load` suspends, and an overtaken load is `Superseded`.** Two
  quick environment switches can never apply in the wrong order.
- **`assertFlagsFullyMapped` throws `AssertionError`.** It works with any test framework,
  not only XCTest.
- **A custom mapper is a `fun interface`, and `RemoteValue.valueAt(path)` reads a path.**
  The Kotlin shapes for the same idea.

### Signals and the bridge

- **A signal starts a host that is not running.** Android starts an app to answer its
  provider; on iOS a signal to a suspended app is lost.
- **A signal is delivered exactly once per send, with no sequence numbers.** Each send is
  one call to the provider, answered once.
- **`send` returns whether a handler ran; it does not throw `notAcknowledged`.**
  `connect()` says why the host could not be reached.
- **The host holds a signal's delay.** A companion frozen in the background cannot stall
  it; the longest delay is a minute.
- **The countdown is a notification, not a Live Activity.** A progress notification that
  asks to be promoted to a Live Update on Android 16 and later, a chronometer below, and
  nothing to add to the companion.
- **Trust is a signature permission.** The companion is signed with the host's key, or
  named by certificate with `knownSigner` on Android 12 and later.
- **`connect()` reports eight results.** Each failure has its own fix, where iOS shows one
  message naming two causes.
- **The provider waits for `Application.onCreate`.** So the client's timeout has to cover a
  cold start of the host.
- **A call that finds the host dead is retried once.** The companion survives the host
  dying.
- **The companion's live source blocks.** `FlagBridgeClient.source()` suspends, but its
  writes and `refresh()` are blocking calls to make off the main thread; `lastFailure`
  publishes the latest refresh failure.
- **A request or reply over 512 KiB is refused.** That keeps clear of binder's limits.
- **A host cannot bridge to itself.** The companion is a separate app by design.
- **`featureflag-bridge` is a separate artifact.** An exported provider should be something
  an app opts into, and it can be a debug-only dependency.

### The companion app

- **The companion is one Compose composable, `FlagCompanion`.** The SwiftUI screens are
  not public; a custom tab gets the store and the standard row instead.
- **Tabs are `FlagCompanionTab` values.** `Overrides`, `Flags`, `Signals`, `Detail` and
  `Custom`; two tabs with one id throw, and an empty list shows "No tabs".
- **The companion reconnects every time it returns to the foreground.** A reinstalled
  host with a new schema is picked up without restarting the companion.
- **A signal group takes a list of entries, and its nesting threshold is fixed at six.**
  `FlagSignalGroup("Caches", CacheSignal.entries)`.
- **`FlagEditingStore` writes suspend.** A write over the bridge is a call into the host.
- **Resetting every override asks first.** It cannot be undone.
- **A failed write is shown, not swallowed.** The row returns to the host's value.
- **`nan`, `inf` and `infinity` are refused in a number field.** They could not be
  exported afterwards.
- **An import is one write.** iOS writes key by key and rolls back on failure.
- **There is no "Copy property list".** There are no property lists.
- **A text field whose text equals the value in effect writes nothing.** Leaving a field
  never turns a default into an override.
- **Rows are reordered from an "Actions" menu, not by dragging.** "Save" on a QR code
  opens the share sheet.
- **Strings are English only.**

### Platforms and distribution

- **Android only, minimum SDK 26.** `java.time` needs it. There is no Wear OS or TV
  variant of the companion, just as iOS has none for watchOS or tvOS.
- **The core library is plain Kotlin on the JVM.** It runs in JVM tests and tools with no
  Android at all.
- **Artifacts are Maven artifacts with a BOM, not a Swift package.** They are prepared
  for Maven Central but not on it yet. Consumers need Kotlin 2.2 or later and a KSP
  matching their Kotlin.
- **Behaviour across processes was measured on Android 16.** Older releases, below
  Android 11 in particular, may differ.

### Same on both platforms: a known quirk

- **A record list sent as a JSON string is checked only as stored text.** Its fields are
  read by property name, enum entries and nested lists are not checked, and the text is
  stored as sent. FeatureFlag 0.12.0 for iOS does this, and this library mirrors it so the
  remote test fixtures both platforms share stay identical. Send record lists as arrays.
```

**Create** `README.md`:

````markdown
# FeatureFlag for Android

[![Check](https://github.com/andyyhope/FeatureFlag-android/actions/workflows/check.yml/badge.svg)](https://github.com/andyyhope/FeatureFlag-android/actions/workflows/check.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Declare feature flags as Kotlin properties, read them type-safely, and edit them from a
separate companion app that never links a line of your code.

<!-- snippet: readme-declaration -->
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

    @Flag(description = "Pricing tier to present")
    val tier: Tier get() = Tier.Free
}

enum class Tier { Free, Pro }
```

<!-- snippet: readme-pole -->
```kotlin
val pole = FlagPole(
    AppFlagsSpec,
    sources = listOf(
        SharedPreferencesSource.companion(context), // the companion app's edits
        RemoteOverrideSource(AppFlagsSpec), // your backend
    ),
)

if (pole.flags.checkout.googlePay) {
    showGooglePayButton()
}
```

This is the Android port of [FeatureFlag](https://github.com/andyyhope/FeatureFlag) for
iOS. Exports, QR codes and schemas written on one platform read on the other.
[Differences from iOS](docs/DifferencesFromIOS.md) lists everything that differs, and why.

## Installation

In your app module's `build.gradle.kts`:

<!-- snippet: install-app -->
```kts
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    ksp(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))

    implementation("com.andyyhope.featureflag:featureflag-android")
    implementation("com.andyyhope.featureflag:featureflag-bridge")
    ksp("com.andyyhope.featureflag:featureflag-ksp")
    testImplementation("com.andyyhope.featureflag:featureflag-test")
}
```

0.1.0 is not on Maven Central yet. Until it is, build it from a clone of this repository
with `./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0`, and add `mavenLocal()` to your
build's repositories.

The BOM keeps every artifact on one version; KSP's configuration does not inherit from
`implementation`, so it is named there too. Pre-1.0, a minor version can break the API,
so name an exact version. On AGP 9, KSP also needs `android.disallowKotlinSourceSets=false`
in `gradle.properties` — see [Getting started](docs/GettingStarted.md#1-add-the-dependencies).

| Artifact | What it is | Add it to |
|---|---|---|
| `featureflag-core` | Containers, the pole, sources, remote overrides, export and import, the audit. Plain JVM. | Anything; `featureflag-android` brings it |
| `featureflag-ksp` | The processor that generates each container's spec | `ksp(…)` in every module that declares flags |
| `featureflag-android` | `SharedPreferencesSource`, `publishSchema(context)`, signals, the bridge client | Your app |
| `featureflag-bridge` | The provider a companion app talks to | Your app, or only its debug builds |
| `featureflag-ui` | The companion app's screens, in Compose | A companion app only — never your shipping app |
| `featureflag-test` | `assertFlagsFullyMapped` | Your tests |
| `featureflag-bom` | One version for all of the above | `platform(…)`, for `implementation` and `ksp` |

## Requirements

- Android, minimum SDK 26.
- Kotlin 2.2 or later in the app that consumes it, with a KSP matching that Kotlin. The
  processor is built against KSP 2.2.10-2.0.2, and tested with Kotlin 2.2.10.
- Java 17 bytecode, so a JDK 17 or later to build with.
- Built and tested with AGP 9.1 and compileSdk 36. The companion app also needs Compose,
  with the Compose compiler plugin.

## What you get

- **Nested flags.** `@FlagGroup` namespaces keys — `checkout.express.one-tap`. Nesting is
  namespacing only: a child's value never depends on its parent, so there is no
  evaluation order to reason about.
- **The types you would reach for,** plus lists, string-keyed maps and any enum, which
  need no implementation at all.
- **Records.** `@FlagRecord` gives a flag a list of fixed-shape values — a name, a URL and
  a switch, repeated — with a field-by-field editor in the companion app.
- **A companion app** that edits your app's flags through a bridge only your signing key
  opens, updating your app while it runs, and starting it when it is not.
- **Remote overrides** from JSON, mapped from whatever shape your backend sends.
- **Environment layering.** `EnvironmentConfiguration` keeps a bundled local config and a
  fetched remote one in step with the environment you switch to — remote over local over
  the compiled defaults.
- **Export and import** as JSON or a QR code.
- **A mapping audit** that checks, without running the app, that a config file wires up
  every flag — in both directions, so a path typo cannot hide.
- **Kotlin flows** per flag, ready for `collectAsStateWithLifecycle`.

## The idea that makes it work

The processor generates `AppFlagsSpec` — a description of the flag tree available
**without an instance and without reflection**.

Everything else falls out of that. Your app publishes that description as a schema
document; the companion reads it through the bridge and renders an editor for an app
whose types it has never seen. One companion build works for any app that publishes a
schema. Remote key mapping, strict payload validation and editor selection all read the
same description.

## Documentation

| | |
|---|---|
| [Getting started](docs/GettingStarted.md) | Empty project to a flag you can toggle from a second app |
| [Declaring flags](docs/DeclaringFlags.md) | Containers, nesting, keys, and flags with no pole behind them |
| [Flag values](docs/FlagValues.md) | The types a flag can hold, and enums |
| [Records](docs/RecordFlags.md) | Lists of fixed-shape values, and how they stay editable |
| [Sources and precedence](docs/SourcesAndPrecedence.md) | Stacking sources, and finding out which one won |
| [Observing changes](docs/ObservingChanges.md) | Flows, Compose, and changes from another app |
| [Remote overrides](docs/RemoteOverrides.md) | Dot paths, custom mappers, and why a bad field rejects everything |
| [Validating a config](docs/ValidatingAConfig.md) | Checking a config file wires up every flag |
| [Sharing with a companion app](docs/SharingWithACompanionApp.md) | What the schema carries, and what it costs |
| [The bridge](docs/TheBridge.md) | The provider, the permission, and who may connect |
| [Exporting and importing](docs/ExportingAndImporting.md) | JSON documents and QR codes |
| [Sending signals to the host](docs/SendingSignals.md) | One-way instructions from the companion to your app |
| [Building a companion app](docs/BuildingACompanionApp.md) | An editor for another app's flags, in one composable |
| [Troubleshooting](docs/Troubleshooting.md) | The failures worth recognising on sight |
| [Differences from iOS](docs/DifferencesFromIOS.md) | What this port does differently, and why |

The tests in [`docs-samples`](docs-samples) hold the samples in those articles and in this
README to the code:
- every Kotlin sample is compiled there, inside a test that uses it — statements run in
  the test, and declarations are used by it;
- every JSON block and every report or message shown as output is the string the code
  produces, or the payload a test applies;
- the Gradle and manifest snippets are files there that the tests read but do not build:
  they check the coordinates, versions and permission names, and compare the bridge's
  manifest with the library's own. Two in The bridge are quoted instead from the
  [sample apps](samples/README.md), which the build compiles;
- copied by hand and not checked: the processor's error messages in Record flags and
  Troubleshooting, and the one `gradle.properties` line in Getting started and
  Troubleshooting.

A test fails if a checked sample differs from its source by a single character.
Documentation that has drifted from the API is worse than none, and nothing else would
notice.

The API reference is generated with Dokka: `./gradlew :dokkaGeneratePublicationHtml`.

## Precedence

Sources are asked in order, so the order of the stack *is* the precedence:

```
1. local overrides    (the companion app, an imported document or QR code)
2. remote payload     (your backend)
3. compiled default   (the property's getter)
```

A value you set by hand stays set until you clear it — which is what makes the companion
trustworthy for QA. `resolution` names the source that won:

<!-- snippet: readme-resolution -->
```kotlin
val sourceName = pole.resolution(AppFlagsSpec.newOnboarding).sourceName // "Companion", or null for the default
```

That is the answer to "why is this flag false?".

## Remote overrides

The library decodes; it never fetches. Your app gets configuration however it likes and
hands over the bytes, so no networking, auth, retry or cache invalidation lives in a flag
library:

<!-- snippet: remote-overview -->
```kotlin
val remote = RemoteOverrideSource(AppFlagsSpec)
val pole = FlagPole(AppFlagsSpec, sources = listOf(local, remote))

val bytes = httpClient.get(configUrl)
remote.apply(bytes)
```

Flags say where they live in the payload with `remoteKey`, read as a dot path
(`experiments.0.enabled` indexes into arrays). For a shape no path can address — a list
of experiments keyed by a name field, say — implement `RemoteOverrideMapper`.

Applying is strict and all-or-nothing: one bad field rejects the payload and reports every
problem at once, rather than leaving your app running on half a configuration.

One deliberate allowance: JSON has a single number type, so a whole number satisfies a
`Double` flag. That is exact widening, not coercion — `"true"` is still not a `Boolean`.

## The companion app

Your app publishes its schema on launch:

<!-- snippet: sharing-setup -->
```kotlin
class ExampleApplication : Application() {
    lateinit var pole: FlagPole<AppFlags>
        private set

    override fun onCreate() {
        super.onCreate()
        pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.companion(this)))
        pole.publishSchema(this)
    }
}
```

The companion renders it, linking none of your code:

<!-- snippet: companion-whole-app -->
```kotlin
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlagCompanion(hostPackage = "com.example.app")
            }
        }
    }
}
```

That is the whole app, not a sketch of one. It connects through the bridge, says plainly
what is wrong when it cannot, and gives you Overrides and Flags as tabs. The two apps
trust each other because they are signed with the same key; see
[The bridge](docs/TheBridge.md).

Tabs are a list you compose, so a companion takes only what it needs:

<!-- snippet: companion-tabs -->
```kotlin
FlagCompanion(
    hostPackage = "com.example.app",
    tabs = listOf(
        FlagCompanionTab.Overrides,
        FlagCompanionTab.Signals(
            FlagSignalGroup("Configuration", ConfigSignal.entries),
            FlagSignalGroup("Caches", CacheSignal.entries),
        ),
        FlagCompanionTab.Flags,
        FlagCompanionTab.Detail(key = "environment", title = "Environment"),
    ),
)
```

`Signals` is opt-in — it needs your signal enums, so an app without them simply leaves it
out. `Detail` promotes a single flag to its own screen, for the one whose consequence
earns it. `Custom` puts your own screens in the same list, handed the same store.

Edits reach your running app the moment they are made, because the bridge writes them
into your app's own preferences file. A signal or an edit sent while your app is not
running starts it.

## Reading from other threads

`FlagPole` is not tied to the main thread. Reads are lock-free and safe from any thread,
because apps read flags off the main thread constantly. No flow hops to the main thread
for you; collect where you need the values.

## Things worth knowing

**Export spans the whole stack.** `overrides`, and everything built on it, carries every
value a source supplies — including one a remote payload supplied, even when it equals
the default.
Exporting from a device with remote config and importing elsewhere turns those into local
overrides on the receiving device. Usually what you want from "reproduce this device's
state", but worth knowing before you share a code.

**If your own module declares a type called `Flag`,** import the annotation under another
name, such as `import com.andyyhope.featureflag.Flag as FeatureFlag`.

**A flag can hold a list of records** when configuration is not one value but several
small structured ones:

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

Stored as JSON text, so every codec, the QR encoder and remote validation already handle
it. Adding a field with a default keeps lists already stored readable; adding one without
a default makes the app fall back to its default rather than read a half-built value. See
[Records](docs/RecordFlags.md).

→ [Troubleshooting](docs/Troubleshooting.md) has the rest.

## Samples

[`samples/`](samples/README.md) contains two apps — a host that declares flags and a
companion that edits them — and the small module of signals they share. Install both
debug builds and open the companion; the samples README walks through what to try.

## Credit where it is due

FeatureFlag is inspired by [**Vexil**](https://github.com/unsignedapps/Vexil) by
[Unsigned Apps](https://github.com/unsignedapps), the best-established Swift feature flag
library. Several ideas here are Vexil's, by way of the iOS library: the `FlagPole` as the
thing you read flags through, containers that namespace by nesting, and an ordered stack
of sources where position decides precedence.

No code was copied — this is an independent implementation, and both are MIT licensed.

## Status and contributing

Early. The API may still move before 1.0. Issues and pull requests are welcome — if you
are reporting a bug, a failing test says more than a description.

## License

MIT. See [LICENSE](LICENSE).
````

- [ ] **Step 5: Link the earlier docs' mentions of these**

**Modify** `docs/DeclaringFlags.md`: replace

```markdown
because the generated code already uses those names. The exact messages are in
Troubleshooting.
```

with

```markdown
because the generated code already uses those names. The exact messages are in
[Troubleshooting](Troubleshooting.md).
```

**Modify** `docs/RecordFlags.md`: replace

```markdown
read — a new record starts every enum field on a real entry rather than an empty string.
Building a companion app covers the screens.
```

with

```markdown
read — a new record starts every enum field on a real entry rather than an empty string.
[Building a companion app](BuildingACompanionApp.md) covers the screens.
```

**Modify** `docs/RecordFlags.md`: replace

```markdown
would invite you to add a record and wonder why nothing changed. See
Troubleshooting.
```

with

```markdown
would invite you to add a record and wonder why nothing changed. See
[Troubleshooting](Troubleshooting.md).
```

**Modify** `docs/SharingWithACompanionApp.md`: replace

```markdown
This is what the `featureflag-ui` module builds its editor from. If you are writing the
companion, start at Building a companion app instead — it has
```

with

```markdown
This is what the `featureflag-ui` module builds its editor from. If you are writing the
companion, start at [Building a companion app](BuildingACompanionApp.md) instead — it has
```

**Modify** `docs/TheBridge.md`: replace

```markdown
something an app opts into. Add it to the host, beside `featureflag-android`, with its
version from the BOM as Getting started shows:
```

with

```markdown
something an app opts into. Add it to the host, beside `featureflag-android`, with its
version from the BOM as [Getting started](GettingStarted.md) shows:
```

**Modify** `docs/TheBridge.md`: replace

```markdown
`client.send(signal)` sends a signal — see
Sending signals to the host app.
```

with

```markdown
`client.send(signal)` sends a signal — see
[Sending signals to the host app](SendingSignals.md).
```

**Modify** `docs/ExportingAndImporting.md`: replace

```markdown
writes for the same values, so a document travels between the two platforms;
Differences from iOS lists the edge cases.
```

with

```markdown
writes for the same values, so a document travels between the two platforms;
[Differences from iOS](DifferencesFromIOS.md#json-dates-and-numbers) lists the edge cases.
```

- [ ] **Step 6: Run the tests, check the doc set, and run the gate**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :docs-samples:testDebugUnitTest
ls docs/*.md
grep -c '](docs/' README.md
grep -rn "has to be running" README.md docs/*.md
./gradlew build detekt
```

Expected:
- `BUILD SUCCESSFUL`; 126 tests: `SendingSignalsTest` 5, `BuildingACompanionAppTest` 5, `GettingStartedTest` 2, `TroubleshootingTest` 1, `ReadmeTest` 1, and the earlier 112.
- Exactly these fifteen: `BuildingACompanionApp.md DeclaringFlags.md DifferencesFromIOS.md ExportingAndImporting.md FlagValues.md GettingStarted.md ObservingChanges.md RecordFlags.md RemoteOverrides.md SendingSignals.md SharingWithACompanionApp.md SourcesAndPrecedence.md TheBridge.md Troubleshooting.md ValidatingAConfig.md`.
- At least `15` from `grep -c` (the docs table links each one; `DocsSyncTest` has already checked every link resolves).
- Nothing from the last `grep`: no doc repeats the Signals footer B1 replaced.
- `BUILD SUCCESSFUL` for the gate.

- [ ] **Step 7: Commit**

```bash
git add docs-samples docs README.md
git commit -m "Document signals, the companion app, getting started, troubleshooting and the differences from iOS, and add the README" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B8: The three workflows

**Files:**
- Create: `.github/workflows/check.yml`
- Create: `.github/workflows/instrumented.yml`
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: B3's six check tasks; `./gradlew build detekt`, which from B4 runs `DocsSyncTest`; `:dokkaGeneratePublicationHtml` (B2); `publishToMavenLocal` and `publishToMavenCentral` (B2), and the Gradle properties `signingInMemoryKey`, `signingInMemoryKeyPassword`, `mavenCentralUsername` and `mavenCentralPassword`, here as `ORG_GRADLE_PROJECT_…` environment variables.
- Produces: for THE USER to set up before the first publish, and nothing a task creates: the repository variable `PUBLISH_ENABLED`, the environment `maven-central` (with required reviewers), and its secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), `SIGNING_KEY` (an armored private key) and `SIGNING_KEY_PASSWORD`.

The workflows follow the overview's CI section and the tooling note's sketches, with these decisions of this plan's:
- **The guard step reads `check --dry-run`** rather than asking for each task with `help --task`, so it proves the six tasks both exist and are part of `check`, in one Gradle call. B3 Step 8 showed the exact lines it greps for. It pipes through `tee`, so a configuration failure shows in the log instead of vanishing into the file (the default shell has `pipefail`).
- **The version comes from the tag inside a `run:` script**, as an array passed to Gradle, rather than through `${{ }}` in the command line, so a tag name never becomes shell code.
- **The publish job runs only from a `v*` tag.** Its `if` requires `startsWith(github.ref, 'refs/tags/v')` beside the dispatch, the `publish` input and `PUBLISH_ENABLED`, and its script refuses any other ref as well. A dispatch from `main`, the "Run workflow" default, would otherwise upload `0.1.0-SNAPSHOT`. The release workflow has one `concurrency` group that never cancels, so two releases cannot race on Central.
- **`setup-gradle` uses `cache-provider: basic`** in every job: the open-source GitHub Actions cache. v6's default, `enhanced`, is a closed-source caching service with its own terms of use.
- **The device job builds both test APKs before starting the emulator**, and gives the emulator 4 cores and 4 GB, so compiling does not compete with it during the timing-sensitive bridge tests. `CrossProcessBridgeTest`'s cold-start test has the client's default 5-second budget, and has only run on a local arm64 emulator, so it is the likeliest flake on the first CI run; the task report says so.

- [ ] **Step 1: Write the check workflow**

**Create** `.github/workflows/check.yml`:

```yaml
name: Check

# Unit tests (Robolectric included), lint, detekt, the six ABI checks, DocsSyncTest and the API docs,
# on every pull request and every push to main.
on:
  pull_request:
  push:
    branches: [main]

concurrency:
  group: check-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

permissions:
  contents: read

jobs:
  check:
    name: Build, test, lint, detekt and ABI
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v6
        with:
          # The open-source GitHub Actions cache: no closed-source caching service, no terms to accept.
          cache-provider: basic
          # Only main writes the Gradle cache, so a pull request cannot evict what main relies on.
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Cache Robolectric's Android jars
        uses: actions/cache@v6
        with:
          path: ~/.m2/repository/org/robolectric
          key: robolectric-${{ hashFiles('gradle/libs.versions.toml') }}

      # A module whose ABI plugin stopped applying has no check task, and `check` would pass without
      # it. Fail here instead, by asking Gradle what `check` will run.
      - name: Every ABI check is part of check
        run: |
          ./gradlew check --dry-run | tee check-plan.txt
          missing=0
          for task in \
            :featureflag-core:apiCheck \
            :featureflag-ksp:apiCheck \
            :featureflag-test:apiCheck \
            :featureflag-android:releaseApiCheck \
            :featureflag-bridge:releaseApiCheck \
            :featureflag-ui:releaseApiCheck
          do
            if ! grep -qx "$task SKIPPED" check-plan.txt; then
              echo "::error::$task is not part of check, so that module's API would go unchecked"
              missing=1
            fi
          done
          exit "$missing"

      - name: Build, unit tests, lint, detekt and ABI checks
        run: ./gradlew build detekt

      - name: API docs
        run: ./gradlew :dokkaGeneratePublicationHtml

      - name: Upload reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: check-reports
          path: |
            **/build/reports/
            **/build/test-results/
            build/dokka/html/
          if-no-files-found: ignore
```

- [ ] **Step 2: Write the device workflow**

**Create** `.github/workflows/instrumented.yml`. It has no `paths-ignore`, so a docs-only pull request runs it too:

```yaml
name: Instrumented

# The two device suites on an API 36 emulator: the bridge across two processes, and the companion's
# screens. Every pull request runs them, docs-only ones included.
on:
  pull_request:
  push:
    branches: [main]

concurrency:
  group: instrumented-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

permissions:
  contents: read

jobs:
  device:
    name: Device tests (API 36)
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v7

      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v6
        with:
          # The open-source GitHub Actions cache: no closed-source caching service, no terms to accept.
          cache-provider: basic
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      # Built before the emulator starts, so compiling does not compete with it for the runner's CPUs
      # while the timing-sensitive bridge tests run.
      - name: Build the test APKs
        run: ./gradlew :featureflag-bridge:assembleDebugAndroidTest :featureflag-ui:assembleDebugAndroidTest

      - name: Cache the emulator
        id: avd-cache
        uses: actions/cache@v6
        with:
          path: |
            ~/.android/avd/*
            ~/.android/adb*
          key: avd-36-google_apis-x86_64

      - name: Create the emulator and its snapshot
        if: steps.avd-cache.outputs.cache-hit != 'true'
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 36
          target: google_apis
          arch: x86_64
          cores: 4
          ram-size: 4096M
          force-avd-creation: false
          emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          disable-animations: false
          script: echo "Created the emulator snapshot for the cache."

      - name: Device tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 36
          target: google_apis
          arch: x86_64
          cores: 4
          ram-size: 4096M
          force-avd-creation: false
          emulator-options: -no-snapshot-save -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          disable-animations: true
          script: ./gradlew :featureflag-bridge:connectedDebugAndroidTest :featureflag-ui:connectedDebugAndroidTest

      - name: Upload reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: device-test-reports
          path: |
            **/build/reports/androidTests/
            **/build/outputs/androidTest-results/
          if-no-files-found: ignore
```

- [ ] **Step 3: Write the release workflow**

**Create** `.github/workflows/release.yml`. A tag or a manual run dry-runs; only a manual run from a `v*` tag, with `publish` ticked, `PUBLISH_ENABLED` set to `true`, and the `maven-central` environment's approval, publishes:

```yaml
name: Release

# A tag or a manual run publishes every artifact to a Maven repository inside the job and keeps it as
# an artifact: a dry run, unsigned. Nothing reaches Maven Central unless a person dispatches this from
# a v-tag with "publish" ticked, the repository variable PUBLISH_ENABLED is 'true', and the
# maven-central environment's reviewers approve.
on:
  push:
    tags: ['v*']
  workflow_dispatch:
    inputs:
      publish:
        description: Upload to Maven Central (run from a v-tag; also needs PUBLISH_ENABLED and the maven-central environment)
        type: boolean
        default: false

# One release at a time, and never cancelled halfway through an upload.
concurrency:
  group: release
  cancel-in-progress: false

permissions:
  contents: read

jobs:
  dry-run:
    name: Publish to a job-local Maven repository
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v6
        with:
          # The open-source GitHub Actions cache: no closed-source caching service, no terms to accept.
          cache-provider: basic
          cache-read-only: true

      # A v-tag publishes its own version (v0.1.0 is 0.1.0); anything else publishes gradle.properties'.
      - name: Publish, unsigned
        run: |
          version_args=()
          if [[ "$GITHUB_REF" == refs/tags/v* ]]; then
            version_args=("-PVERSION_NAME=${GITHUB_REF_NAME#v}")
          fi
          ./gradlew publishToMavenLocal "${version_args[@]}" -Dmaven.repo.local="$RUNNER_TEMP/maven-local"

      - name: List what was published
        run: find "$RUNNER_TEMP/maven-local/com/andyyhope/featureflag" -type f | sort

      - uses: actions/upload-artifact@v7
        with:
          name: maven-local
          path: ${{ runner.temp }}/maven-local/com/andyyhope/featureflag/
          if-no-files-found: error

  publish:
    name: Upload to Maven Central
    needs: dry-run
    if: >-
      github.event_name == 'workflow_dispatch' && inputs.publish && vars.PUBLISH_ENABLED == 'true'
      && startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    timeout-minutes: 45
    environment: maven-central
    env:
      ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
      ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
      ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_KEY }}
      ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_KEY_PASSWORD }}
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v6
        with:
          # The open-source GitHub Actions cache: no closed-source caching service, no terms to accept.
          cache-provider: basic
          cache-read-only: true

      # Uploads and validates a deployment; releasing it is a click in the Central Portal. Only ever the
      # tag's version: the job's condition already requires a v-tag, and this refuses anything else too.
      - name: Upload to the Central Portal
        run: |
          if [[ "$GITHUB_REF" != refs/tags/v* ]]; then
            echo "::error::Publish only from a v-tag, never from $GITHUB_REF"
            exit 1
          fi
          ./gradlew publishToMavenCentral "-PVERSION_NAME=${GITHUB_REF_NAME#v}"
```

- [ ] **Step 4: Lint them**

```bash
if command -v actionlint > /dev/null; then
  actionlint .github/workflows/check.yml .github/workflows/instrumented.yml .github/workflows/release.yml
else
  python3 -c '
import sys, yaml
for path in sys.argv[1:]:
    document = yaml.safe_load(open(path))
    triggers = document.get("on", document.get(True))
    uses = sorted({step["uses"] for job in document["jobs"].values() for step in job["steps"] if "uses" in step})
    print(path, sorted(document["jobs"]), sorted(triggers), uses)
' .github/workflows/check.yml .github/workflows/instrumented.yml .github/workflows/release.yml
fi
```

Expected, without `actionlint` (PyYAML reads `on:` as `True`, hence the fallback):

```text
.github/workflows/check.yml ['check'] ['pull_request', 'push'] ['actions/cache@v6', 'actions/checkout@v7', 'actions/setup-java@v6', 'actions/upload-artifact@v7', 'gradle/actions/setup-gradle@v6']
.github/workflows/instrumented.yml ['device'] ['pull_request', 'push'] ['actions/cache@v6', 'actions/checkout@v7', 'actions/setup-java@v6', 'actions/upload-artifact@v7', 'gradle/actions/setup-gradle@v6', 'reactivecircus/android-emulator-runner@v2']
.github/workflows/release.yml ['dry-run', 'publish'] ['push', 'workflow_dispatch'] ['actions/checkout@v7', 'actions/setup-java@v6', 'actions/upload-artifact@v7', 'gradle/actions/setup-gradle@v6']
```

With `actionlint`, no output. Either way, read each file against the overview's CI section: triggers, `ubuntu-latest`, Temurin 21, the cache written only from `main`, the Robolectric and AVD caches, API 36 `google_apis` `x86_64`, reports uploaded `if: always()`, `cache-provider: basic` on all four `setup-gradle` steps, the release `concurrency` group, the publish job's four conditions (manual dispatch, a `v*` tag, the `publish` input, `PUBLISH_ENABLED`) and its environment, and the action majors. If a later change adds or removes an action, the `uses` lists above change with it; nothing else in this output should.

- [ ] **Step 5: Run the guard and the dry run's commands locally**

The guard step, as `check.yml` runs it:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
set -o pipefail
./gradlew check --dry-run | tee build/check-plan.txt | tail -3
missing=0
for task in :featureflag-core:apiCheck :featureflag-ksp:apiCheck :featureflag-test:apiCheck \
  :featureflag-android:releaseApiCheck :featureflag-bridge:releaseApiCheck :featureflag-ui:releaseApiCheck; do
  grep -qx "$task SKIPPED" build/check-plan.txt || { echo "missing $task"; missing=1; }
done
echo "missing=$missing"
```

Expected: `BUILD SUCCESSFUL` and `missing=0`. Then the publish step's ref check, with a branch ref:

```bash
GITHUB_REF=refs/heads/main bash -c '
  if [[ "$GITHUB_REF" != refs/tags/v* ]]; then
    echo "::error::Publish only from a v-tag, never from $GITHUB_REF"
    exit 1
  fi
  echo "would publish"
'
echo "exit=$?"
```

Expected: `::error::Publish only from a v-tag, never from refs/heads/main` and `exit=1`. Then the dry-run job's script with a tag-shaped ref, into `build/`:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
GITHUB_REF=refs/tags/v0.1.0 GITHUB_REF_NAME=v0.1.0 RUNNER_TEMP="$PWD/build/runner-temp" bash -c '
  version_args=()
  if [[ "$GITHUB_REF" == refs/tags/v* ]]; then
    version_args=("-PVERSION_NAME=${GITHUB_REF_NAME#v}")
  fi
  ./gradlew publishToMavenLocal "${version_args[@]}" -Dmaven.repo.local="$RUNNER_TEMP/maven-local"
'
ls build/runner-temp/maven-local/com/andyyhope/featureflag/featureflag-core
```

Expected: `BUILD SUCCESSFUL`, and `0.1.0` (not `0.1.0-SNAPSHOT`) among the entries.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/check.yml .github/workflows/instrumented.yml .github/workflows/release.yml
git commit -m "Add CI for every pull request and push to main, device tests on an emulator, and a release dry run" -m "Publishing to Maven Central needs a manual run from a v-tag, the PUBLISH_ENABLED variable and the maven-central environment's approval." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

THE USER's steps before the first real run (the task report lists them; no task does them), in this order:
1. Push the branch and open the M4 pull request.
2. Verify the `com.andyyhope.featureflag` namespace in the Central Portal (DNS TXT record).
3. Create the `maven-central` environment **with required reviewers**, and add its four secrets. Do this before step 4: a job that names an environment which does not exist creates it, with no protection rules, so it would publish without a reviewer.
4. Set `PUBLISH_ENABLED` to `true` only when ready.
5. Push a `v*` tag only from a commit on `main` whose Check and Instrumented runs are green. A tag push runs the dry run, not the tests, and the publish job builds from the tag's source.

---

## Contract issues for the controller

Each is something the Contract gets wrong, leaves open, or cannot do as written. The plan implements the closest faithful version; none renames a Contract name.

1. **BCV cannot ignore `docs-samples` at B3.** The Contract's `ignoredProjects` lists `docs-samples`, but the module arrives in B4, and BCV fails on a name that matches no project (`Cannot find excluded project … in all projects`, reproduced in the scratch copy). B3 ignores the BOM, `signals`, `host` and `companion`; B4 adds `docs-samples` when it creates the module.
2. **Sample regions come from M4a, not from the Contract.** The Contract names no regions in the samples; M4a's plan defines ten and cites them all from `samples/README.md` (Seams). This plan adds none and edits no sample file: `docs/TheBridge.md` quotes `sample-companion-uses-permission` and `sample-companion-signing`, and the host-id wiring, which no M4a region covers (`manifestPlaceholders` and `buildConfigField` in `samples/companion/build.gradle.kts`), is described in prose rather than quoted. Everything else a reader copies uses `com.example.app` and lives in `docs-samples`, because the samples' `${hostId}` placeholder and `project(":…")` dependencies are not what a reader types. M4a's README passes the checker unchanged (Seam 7), so there is no M4a fix to request.
3. **Cross-links decide the order of the docs.** `DocsSyncTest` fails on a link to a doc that does not exist yet, so a doc cannot link forward. B5–B7 are split to keep links backward (see "Which doc is in which task"); the eight forward links are plain text until B6 and B7 restore them in a stated step. The README goes last, with its table of every doc.
4. **Reader-facing XML and Gradle snippets are resources, not compiled code.** They sit in `docs-samples/src/test/resources/docs/…` with Contract-syntax regions. The XML is parsed and checked by tests, the `.kts` coordinates by `DocsSync` (version) and `GettingStartedTest` (artifact names); neither is built by Gradle. JSON cannot hold a marker at all, so every JSON fence is an `output:` of a constant the tests apply, or of a computed document.
5. **"Markdown and source roots reach the test as system properties."** One root and the version name are passed (`featureflag.docs.root`, `featureflag.docs.versionName`), and `DocsRepository` applies the Contract's patterns under that root. `docs-samples/build.gradle.kts` declares every tree those patterns read as the task's inputs, as text: the root README, `LICENSE` and `docs/*.md`, all of `docs-samples/src`, and the whole `samples` tree (so the files the samples README links to count too), with a comment at each end pointing at the other. The text matters: an edit to a comment or to spacing inside a region changes what a doc quotes but not the class files, and with the build cache on, a test whose only inputs were the classes would come back `FROM-CACHE` and green. Passing file lists would have read the trees at configuration time, which the configuration cache would then track for every build.
6. **`snippet-support:` syntax.** The Contract names it `snippet-support:<name>`; the plan reads it as `// region snippet-support: <name>`, the same shape as `snippet:`. No doc task uses one.
7. **`DocOutputs` is "filled by the doc tests".** It is a map of functions written in one place, each defined in its doc's package and exercised by that doc's tests, so `DocsSyncTest` never depends on the order JUnit runs classes in.
8. **`docs-samples` reads one file outside the scanned trees:** `featureflag-bridge/src/main/AndroidManifest.xml`, which `TheBridgeTest` compares with the manifest `TheBridge.md` shows. It is declared as a task input too.
9. **Rules beyond the Contract's list are enforced**, all cheap, so that nothing the checker cannot read is silently skipped: a fence must start at column 0 (an indented fence inside a list, or a fence in a `>` blockquote, would otherwise go unread); a link must be on one line with no title, and reference-style links are refused, since the checker would not follow them; a heading's closing `#`s are not part of its anchor, as on GitHub; and a link may not point into `docs/superpowers/` (the Global Constraints' rule, now checked). An anchor into a markdown file the checker does not scan is not checked; no doc has one.
10. **The ModifierParameter fix is a reorder, not a rename.** The overview suggested renaming; swapping `FlagCodeBlock`'s last two parameters is enough, and lint then reports nothing (checked).
11. **`featureflag-test` keeps its public API.** With `TestFeatureFlags` gone it exports `assertFlagsFullyMapped` alone, which is the module's purpose, so it stays published.
12. **The publish job publishes only from a `v*` tag, and only the tag's version.** The Contract gives the version handling only for the dry run. The publish job's `if` requires `startsWith(github.ref, 'refs/tags/v')`, and its script refuses any other ref and passes `-PVERSION_NAME` from the tag, so no dispatch can upload `0.1.0-SNAPSHOT`. (The controller updates Deviation 4 to match.)
13. **`POM_*` properties are not all in the root `gradle.properties`.** The overview's file list puts them there. Each module's description is `POM_DESCRIPTION` in that module's own `gradle.properties`, because one root value would give all seven POMs the same description; the shared fields (name pattern, URL, licence, developer, scm) are set in `PublishConventionPlugin`. B2 Step 8 checks the result in the POMs.
14. **The install-version rule names an unreleased version.** `DocsSyncTest` requires install coordinates to equal `VERSION_NAME` without `-SNAPSHOT`, so the README and Getting started say `0.1.0` before it is on Maven Central, and after a release bumps `VERSION_NAME` to `0.2.0-SNAPSHOT` they will say `0.2.0` before that exists. Accepted as written, with no change to the rule: both docs say plainly that 0.1.0 is not on Maven Central yet and how to build it into `mavenLocal()`, and the release checklist must update that sentence.
15. **Review findings not applied as the reviewer wrote them:**
    - Build #3's optional "ignore only projects that exist" filter: not applied. B3 Step 2 checks the three samples exist and stops if they do not, which keeps the failure loud.
    - Build #4's "note in the task report": applied as B8's text, which tells the task report to name the 5-second cold-start budget.
    - Build #8's "run `./gradlew build` in the dry-run job": replaced by THE USER's step 5 (tag only a green `main`), so the release workflow does not repeat the Check workflow.
    - Docs #6's "On AGP 8, apply `org.jetbrains.kotlin.android`": cut. Nothing here builds a consumer on AGP 8, so the sentence would be unverified.
    - Docs #10's "verify the knownSigner merge on `samples/host`": replaced by the controller's ruling. `TheBridge.md` presents it as Android platform behaviour, links the Android docs, and says the library's tests do not exercise it; `TheBridgeTest` checks only that the snippets parse and name the bridge permission.
    - Docs #20, "each level of nesting opens another list": stated from code and tests, not from a device run. `FlagRoute.Records` carries a `trail` of any depth, and `RecordScreenTest.aNestedListFieldOpensTheNestedList` opens one.
    - Docs #28, the `signals-send` test: kept as a smoke test that the snippet compiles and runs. That `send` returns `true` when a handler ran is proven by the bridge's instrumented `CrossProcessBridgeTest`, not by `docs-samples`.
    - Docs #29's further gaps (`FlagSignalDelay`, `FlagCompanionTab.Defaults`, `FlagEditingStore.records`, `copyableQrCode`, split-screen): not added. The reviewer's fix was the "Choosing a control" section, which is applied.
    - Docs #33: the `knownCerts` digest-case claim is cut (`TheBridge.md` only says to give the digest in the form Android's `knownCerts` documentation gives). `TheBridge.md`'s "below that it reports `BridgeNotInstalled`" stays: it follows from `FlagBridgeClient`'s code, and Differences from iOS already says cross-process behaviour was measured on Android 16 and older releases may differ.

## Self-review

**Spec and contract coverage:**
- B1: Deviation 5 exactly — `applyPayload` with its test and KDoc, both placeholders and their smoke tests deleted (core's other two smoke checks kept), the five constants, the Signals footer with its test changed first, the optional lint fix, the remaining drift listed for THE USER.
- B2: LICENSE text and holder; `PublishConventionPlugin` applied last, Dokka skipped for `java-platform`, `publishToMavenCentral()`, signing only with `signingInMemoryKey`, POM fields and per-module descriptions; the BOM's six constraints; the Dokka aggregate without `featureflag-ksp`; unsigned and signed dry runs into `build/` (Review Focus 1, 5).
- B3: a check that M4a's three samples exist, BCV at the root, the bridge in three modules, `ignoredProjects` and every `ComposableSingletons` class generated from the dump, six dumps committed, `check --dry-run`, a mutation in a JVM and an Android module (Review Focus 3).
- B4: the module and its Gradle wiring (system properties, and every tree read as a text input), the markers and comparison exactly as the Contract writes them, every failure mode the Contract lists pinned in `DocsSyncCheckerTest` before any doc exists, the version check, and the real `DocsSyncTest` with a guard that it scanned something (Review Focus 2).
- B5–B7: the fifteen files and the README the Contract lists; `TheBridge.md` with permission, manifest, provider, statuses, security, `debugImplementation` and `knownSigner`; `BuildingACompanionApp.md` with the M3 carry-ins; `DifferencesFromIOS.md` grouped with reasons, ending with the one quirk both platforms share (a record list sent as a JSON string); the README's install block with the BOM and `ksp(platform(bom))`, module table, requirements, quick start, docs table, samples and licence; a doc edit, and a whitespace-only edit inside a region, re-running the test (Review Focus 4).
- B8: the three workflows with the triggers, runner, JDK, caches (`basic` provider), guard, reports, emulator and gates the Contract describes, plus the tag requirement and the release concurrency group, linted locally; the guard and the publish ref check run locally.

**Placeholder scan:** every code step is a whole file, generated from the files that passed in the scratch copy, or an exact insertion with its anchor. Two steps read M4a's files, which this plan quotes from M4a's plan, and each says what to do if the files have drifted from it: B4 Step 7 (stop and report an M4a fix) and B6 Step 4 (copy M4a's region text into `TheBridge.md`'s two fences).

**Type and name consistency:** the checker's API in B4's Interfaces is what its tests call; `DocOutputs.all` is `Map<String, () -> String>` throughout, and each version's imports match its entries; at every task the regions defined equal the regions cited and the outputs registered equal the outputs shown (checked by the generator per task, and by `DocsSyncTest`, which also rejects duplicates, in the built final state); `ExampleApplication` is declared in two packages (`sharingwithacompanionapp` and `sendingsignals`), which the one-package-per-doc rule keeps apart; `ConfigSignal` and `CacheSignal` are declared in `docs.sendingsignals` and imported by `docs.buildingacompanionapp`; the six ABI task paths are identical in B3 Step 8, B8's guard and Review Focus 3.

**Review Focus:** each item names the step that pins it, and each of those steps has a command and an expected result.
