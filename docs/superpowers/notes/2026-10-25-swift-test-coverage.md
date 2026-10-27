# Swift to Kotlin test coverage (M1d)

The map from each Swift test file in M1d's scope to its Kotlin test class, and what was not ported. It was moved out of [`2026-10-08-m1d-remote-audit-testsupport.md`](../plans/2026-10-08-m1d-remote-audit-testsupport.md), Task D7's Step 2.

| Swift test file | Kotlin test class | Not ported, and why |
|---|---|---|
| `RemoteValueTests` | `remote.RemoteValueTest` | `testPropertyListDatesAndDataSurviveDeserialising`: PLIST is dropped. Native date/data boxing is still covered by building `RemoteValue.Date`/`Data` directly. |
| `RemoteOverrideTests` | `remote.RemoteOverrideSourceTest` | The seven PLIST tests (`testAcceptsAPropertyListPayload`, `testDatesAndDataArriveNativelyFromAPropertyList`, `testAPropertyListStillRejectsAMistypedValue`, `testAWholeNumberStillWidensToADoubleFromAPropertyList`, `testRejectsAMalformedPropertyList`, `testJSONReadAsAPropertyListIsRejected`, `testAbsentKeysAreReportedForAPropertyListToo`). `testApplyingAPayloadNotifiesTheTower` is ported as the source emitting `FlagChange.All` (Combine `objectWillChange` → Flow). |
| `FlagRecordRemoteTests` | `remote.FlagRecordRemoteTest` | `testAPropertyListCarriesRecordsToo`: PLIST. |
| Remote parts of `FlagNestedRecordTests`, `FlagRecordDefaultTests`, `FlagRecordOptionalAndKeyTests`, `FlagRecordKeyTests` | `remote.FlagRecordRemoteTest`; diff part in `audit.FlagRecordDiffLineTest` | none |
| Remote parts of `AdversarialRegressionTests`, `DuplicateKeyTests`, `FlagPoleEdgeCaseTests` | `remote.RemoteOverrideSourceTest` | none |
| `EnvironmentConfigurationTests` | `remote.EnvironmentConfigurationTest` | none |
| `FlagMappingAuditTests` | `audit.FlagMappingAuditTest` | none |
| `FlagDefaultDiffTests` | `audit.FlagDefaultDiffTest` | none |
| `FlagRecordDiffTests` | `audit.FlagRecordDiffTest` | none |
| `ErrorMessageTests` | `errors.ErrorMessageTest` | `testAnUnacknowledgedSignalDoesNotClaimTheHostIsClosed` and `testAnUnavailableAppGroupNamesTheGroupAndTheEntitlement` cover the signal channel and App Group, which M2 handles or drops. The "App Group" fragment of `testAnUnpublishedSchemaSaysWhatToGoAndDo` and the "UserDefaultsSource" fragment of `testWritingWithNothingWritableSaysWhatToAdd` are reworded on Android. `testTheSameIsTrueOfValuesFromADocument` runs through a real import, because `flagShortDescription(of:)` has no Kotlin twin. |
| `XCTAssertFlagsFullyMappedTests` | `test.AssertFlagsFullyMappedTest` | none |
| `READMEExampleTests` (remote samples) | `scenario.RemoteDocumentationExampleTest` | The non-remote samples (resolution, publish schema, enum cases, records stored form and fallback, shadowing caveat) use no M1d API and belong to M1c. |
| `DocumentationExampleTests` (RemoteOverrides, the FlagValues enum case, ExportingAndImporting remote cases, RecordFlags remote cases, SendingSignals) | `scenario.RemoteDocumentationExampleTest`, `signals.FlagSignalTest` | `testAPropertyListPayloadApplies`: PLIST. `testASignalReachesAnObserver`: `FlagSignalChannel` is M2. The other sections belong to M1c. |
| `DemoExamplesTests/{ConfigurationAuditTests, DemoRemoteMapperTests, EnvironmentDrivenConfigTests, EnvironmentLayeringTests, RemoteConfigurationTests}` | `scenario.ScenarioTest` | `testEveryBundledPayloadIsFullyConsumed` asserts through `requireComplete(strict = true)` rather than `assertFlagsFullyMapped`, because core's tests cannot depend on `featureflag-test`. |

Goldens: `remote.RemoteGoldenFixtureTest` (`fixtures/remote`), `audit.AuditGoldenFixtureTest` (`fixtures/audit`), `errors.ErrorMessageTest` (`fixtures/messages`).
