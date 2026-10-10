import FeatureFlag
import Foundation
import GoldenModel

/// remote/ and audit/: RemoteOverrideSource and FlagMappingAudit against GoldenFlags
/// with the default (kebab-case) key encoding and DotPathMapper.
enum RemoteFixtures {
    /// Every remote flag GoldenFlags declares, each given a value its type accepts.
    static let everyRemoteFlag = #"{"toggles":{"boolean":true},"numbers":{"int":7,"double":2.5},"strings":{"plain":"remote"},"dates":{"launch":"2026-06-01T09:30:00Z"},"config":{"endpoint":"https://remote.example/x","markets":["AU","NZ","GB"],"endpoints":[{"name":"edge","url":"https://edge.example","memo":"from backend","targets":[{"host":"a.example","port":443}]}]},"tier":"pro","featureToggles":{"checkout":{"applePay":false}}}"#

    /// M1d Task D2's remote golden table, in its order.
    static let remoteCases: [(String, String)] = [
        ("every-remote-flag", everyRemoteFlag),
        ("empty-object", #"{}"#),
        ("nulls-are-absent", #"{"toggles":{"boolean":null},"tier":"enterprise"}"#),
        ("whole-numbers", #"{"numbers":{"int":3.0,"double":2}}"#),
        ("type-mismatch", #"{"toggles":{"boolean":"true"}}"#),
        ("unknown-case", #"{"tier":"platinum"}"#),
        ("every-problem-sorted", #"{"numbers":{"int":1.5},"strings":{"plain":7},"tier":"gold","config":{"markets":["AU",1]}}"#),
        ("long-string-value", #"{"toggles":{"boolean":"line one\nline two, and then a good deal more text than fits"}}"#),
        ("integer-overflow", #"{"numbers":{"int":10000000000000000000}}"#),
        ("object-for-string", #"{"strings":{"plain":{"a":1}}}"#),
        ("bad-date", #"{"dates":{"launch":"2026-01-01"}}"#),
        ("offset-date", #"{"dates":{"launch":"2026-01-01T10:00:00+10:00"}}"#),
        ("url-needing-encoding", #"{"config":{"endpoint":"https://example.com/a b/ü"}}"#),
        ("records-natural", #"{"config":{"endpoints":[{"name":"prod","url":"https://prod.example.com"},{"name":"edge","url":"https://edge.example","weight":3,"memo":"note","kind":"enterprise","targets":[{"host":"a","port":1}]}]}}"#),
        ("records-as-string", #"{"config":{"endpoints":"[{\"name\":\"x\",\"url\":\"https://x.example\",\"kind\":\"platinum\"}]"}}"#),
        ("records-duplicate-key", #"{"config":{"endpoints":[{"name":"prod","url":"https://a.example"},{"name":"prod","url":"https://b.example"}]}}"#),
        ("records-missing-field", #"{"config":{"endpoints":[{"name":"x"}]}}"#),
        ("records-unknown-case", #"{"config":{"endpoints":[{"name":"x","url":"https://x.example","kind":"platinum"}]}}"#),
        ("records-mixed-forms", #"{"config":{"endpoints":[{"name":"a","url":"https://a.example"},{"name":"b","url":"https://b.example","weight":5,"memo":null,"kind":"enterprise","targets":"[{\"host\":\"h\",\"port\":1}]"},{"name":"c","url":"https://c.example","note":"ignored: read from memo","targets":[{"host":"x","port":2},{"host":"y","port":3}]}]}}"#),
        ("records-wrong-field-type", #"{"config":{"endpoints":[{"name":"a","url":"https://a.example","weight":"heavy"}]}}"#),
        ("not-json", #"not json"#),
        ("top-level-scalar", #"true"#),
    ]

    /// M1d Task D4's audit golden table, in its order.
    static let auditCases: [(String, String)] = [
        ("complete", everyRemoteFlag),
        ("incomplete", #"{"toggles":{"boolean":"yes"},"numbers":{"int":42},"tier":"gold","config":{"page_size":25},"meta":{"version":7}}"#),
        ("empty-object", #"{}"#),
        ("root-array", #"[1,2]"#),
        ("nested-empty", #"{"meta":{}}"#),
        ("restated", #"{"toggles":{"boolean":false},"numbers":{"int":42,"double":0.1},"strings":{"plain":"hello/world \"quoted\""},"dates":{"launch":"2026-01-01T00:00:00Z"},"config":{"endpoint":"https://example.com/a/b?c=d","markets":["AU","NZ"],"endpoints":[{"name":"prod","url":"https://prod.example.com"}]},"tier":"free","featureToggles":{"checkout":{"applePay":true}}}"#),
        ("record-changes", #"{"config":{"endpoints":[{"name":"prod","url":"https://prod.example.com","weight":5,"kind":"pro","memo":"hello"},{"name":"canary","url":"https://canary.example"}]}}"#),
        ("scalar-changes", #"{"numbers":{"int":10000000000,"double":0.30000000000000004},"strings":{"plain":"a much longer string than forty characters\nwith a newline"},"dates":{"launch":"2026-06-01T09:30:00Z"},"config":{"endpoint":"https://remote.example/x","markets":["AU"]}}"#),
    ]

    static func write(into writer: inout FixtureWriter) {
        let schema = FlagSchema(GoldenFlags.self)
        for (name, payload) in remoteCases {
            writer.write("remote/\(name).payload.json", text: payload)
            writer.write("remote/\(name).expected.json", data: applePretty(expected(payload, schema: schema)))
        }
        for (name, payload) in auditCases {
            writer.write("audit/\(name).payload.json", text: payload)
            do {
                let audit = try FlagMappingAudit(GoldenFlags.self, applying: Data(payload.utf8))
                writer.write("audit/\(name).txt", text: audit.description)
                writer.write("audit/\(name).strict.txt", text: audit.description(strict: true, ignoring: ["meta"]))
                writer.write("audit/\(name).defaults.txt", text: audit.defaultsDescription)
            } catch {
                fatalError("audit \(name): \(error)")
            }
        }
    }

    static func expected(_ payload: String, schema: FlagSchema) -> [String: Any] {
        let source = RemoteOverrideSource(GoldenFlags.self)
        do {
            let result = try source.apply(Data(payload.utf8), format: .json)
            var values = [String: Any]()
            for key in result.appliedKeys {
                guard let type = schema.valueTypes[key], let box = source.box(for: key, as: type) else {
                    fatalError("applied key \(key) has no value")
                }
                values[key.rawValue] = box.jsonValue
            }
            return [
                "applied": result.appliedKeys.map(\.rawValue),
                "absent": result.absentKeys.map(\.rawValue),
                "problems": [Any](),
                "values": values,
            ]
        } catch let error as RemoteOverrideError {
            var problems = [[String: Any]]()
            if case let .rejected(list) = error {
                problems = list.map { problem in
                    [
                        "key": problem.key.rawValue,
                        "remoteKey": problem.remoteKey,
                        "kind": String(describing: problem.kind),
                        "message": String(describing: problem),
                    ]
                }
            }
            return ["rejected": String(describing: error), "problems": problems]
        } catch {
            fatalError("unexpected error \(error)")
        }
    }
}
