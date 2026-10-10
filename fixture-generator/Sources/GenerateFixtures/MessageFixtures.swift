import FeatureFlag
import Foundation
import GoldenModel

/// messages/<ErrorType>.<case>[.<variant>].txt: every error FeatureFlag can be made to
/// throw through public API, as String(describing:).
enum MessageFixtures {
    static func write(into writer: inout FixtureWriter) {
        let messages: [(String, any Error)] = [
            ("FlagSerializationError.nonFiniteNumber", FlagSerializationError.nonFiniteNumber("ratio")),
            ("FlagSchemaError.unsupportedFormatVersion", FlagSchemaError.unsupportedFormatVersion(9)),
            ("FlagSchemaError.malformed", FlagSchemaError.malformed("missing formatVersion")),
            ("FlagSchemaError.notPublished", FlagSchemaError.notPublished),
            ("FlagImportError.malformed", FlagImportError.malformed("not valid JSON")),
            ("FlagImportError.unsupportedFormatVersion", FlagImportError.unsupportedFormatVersion(9)),
            ("FlagImportError.rejected.empty", FlagImportError.rejected([])),
            ("FlagImportError.rejected.one", FlagImportError.rejected([
                FlagImportProblem(key: "page-size", kind: .typeMismatch, expected: "int", found: "\"big\""),
            ])),
            ("FlagImportError.rejected.unknownKey", FlagImportError.rejected([
                FlagImportProblem(key: "nope", kind: .unknownKey),
            ])),
            ("FlagImportError.rejected.bare", FlagImportError.rejected([
                FlagImportProblem(key: "a", kind: .typeMismatch),
                FlagImportProblem(key: "b", kind: .unknownCase),
                FlagImportProblem(key: "c", kind: .unknownKey),
            ])),
            ("FlagImportError.rejected.decoded", decodeError()),
            ("FlagQRCodeError.payloadTooLarge.one", FlagQRCodeError.payloadTooLarge(bytes: 3400, limit: 2900, overrideCount: 1)),
            ("FlagQRCodeError.payloadTooLarge.many", FlagQRCodeError.payloadTooLarge(bytes: 3400, limit: 2900, overrideCount: 12)),
            ("FlagQRCodeError.unrecognisedFormat", FlagQRCodeError.unrecognisedFormat),
            ("FlagQRCodeError.corrupt", FlagQRCodeError.corrupt),
            ("FlagQRCodeError.imageGenerationFailed", FlagQRCodeError.imageGenerationFailed),
            ("FlagSignalError.notAcknowledged", FlagSignalError.notAcknowledged),
            ("FlagSignalError.unavailableAppGroup", FlagSignalError.unavailableAppGroup("group.com.example")),
            ("FlagError.noMutableSource", FlagError.noMutableSource),
            ("FlagError.unsupportedValue", FlagError.unsupportedValue("checkout.apple-pay")),
            ("RemoteOverrideError.malformed", RemoteOverrideError.malformed("not valid JSON")),
            ("RemoteOverrideError.rejected.empty", RemoteOverrideError.rejected([])),
            ("RemoteOverrideError.rejected.one", RemoteOverrideError.rejected([
                RemoteOverrideProblem(key: "new-onboarding", remoteKey: "featureToggles.onboarding", kind: .typeMismatch,
                                      expected: "bool", found: "\"yes\""),
            ])),
            ("RemoteOverrideError.rejected.bare", RemoteOverrideError.rejected([
                RemoteOverrideProblem(key: "a", remoteKey: "x.a", kind: .typeMismatch),
                RemoteOverrideProblem(key: "b", remoteKey: "x.b", kind: .unknownCase),
                RemoteOverrideProblem(key: "new-onbaording", remoteKey: "new-onbaording", kind: .unknownKey),
            ])),
            ("RemoteOverrideError.rejected.documented", RemoteOverrideError.rejected([
                RemoteOverrideProblem(key: "checkout-tier", remoteKey: "config.tier", kind: .unknownCase,
                                      expected: "free, pro", found: "\"gold\""),
                RemoteOverrideProblem(key: "new-onboarding", remoteKey: "featureToggles.onboarding", kind: .typeMismatch,
                                      expected: "bool", found: "\"yes\""),
                RemoteOverrideProblem(key: "page-size", remoteKey: "config.pageSize", kind: .typeMismatch,
                                      expected: "int", found: "an array of 3"),
            ])),
            ("RemoteOverrideError.rejected.decoded", remoteError()),
            ("FlagMappingAuditError.incomplete", auditError()),
        ]
        for (name, error) in messages {
            writer.write("messages/\(name).txt", text: String(describing: error))
        }
    }

    /// A real import failure, so the value-rendering helpers (quoting, truncation, counts,
    /// enum case lists, duplicate record keys) appear in the message.
    static func decodeError() -> any Error {
        let schema = FlagSchema(GoldenFlags.self)
        let document = """
            { "formatVersion": 1, "exportedAt": "2026-01-01T00:00:00Z", "values": {
                "int-flag": "a very long string with a \\n newline, a \\t tab and a \\\\ backslash in it",
                "bool-flag": 1,
                "double-flag": "1.5",
                "unknown-flag": [1, 2],
                "tier": "platinum",
                "level": 7,
                "limits": { "a": "x" },
                "markets": { "a": 1, "b": 2 },
                "date-flag": "2026-13-01T00:00:00Z",
                "endpoints": "[{\\"name\\":\\"a\\",\\"url\\":\\"https://a.example\\"},{\\"name\\":\\"a\\",\\"url\\":\\"https://b.example\\"}]",
                "checkout.tier": true,
                "url-flag": "",
                "data-flag": "not base64!"
            } }
            """
        do {
            _ = try FlagPayload.decode(
                Data(document.utf8), as: .json, valueTypes: schema.valueTypes, cases: schema.valueCases,
                recordShapes: schema.recordShapes
            )
            fatalError("decode was expected to fail")
        } catch {
            return error
        }
    }

    static func remoteError() -> any Error {
        let payload = """
            {
              "toggles": { "boolean": "true" },
              "numbers": { "int": 1.5, "double": [1] },
              "strings": { "plain": null },
              "dates": { "launch": 1767225600 },
              "config": { "endpoint": { "a": 1 }, "markets": [1, 2, 3],
                "endpoints": [ { "name": "a", "url": "https://a.example" }, { "name": "a", "url": "https://b.example" } ] },
              "tier": "Platinum Plus Ultra Extreme Deluxe Edition Max Pro",
              "featureToggles": { "checkout": { "applePay": 0 } }
            }
            """
        do {
            try RemoteOverrideSource(GoldenFlags.self).apply(Data(payload.utf8), format: .json)
            fatalError("apply was expected to fail")
        } catch {
            return error
        }
    }

    /// What `requireComplete()` throws for a payload that supplies nothing.
    static func auditError() -> any Error {
        do {
            try FlagMappingAudit(GoldenFlags.self, applying: Data("{}".utf8)).requireComplete()
            fatalError("an empty payload was expected to be incomplete")
        } catch {
            return error
        }
    }
}
