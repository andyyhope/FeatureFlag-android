import FeatureFlag
import Foundation
import GoldenModel

/// schema/, export/, records/ and qr/: the portable documents, byte for byte.
enum TransportFixtures {
    /// 2026-01-01T00:00:00Z
    static let schemaDate = Date(timeIntervalSince1970: 1_767_225_600)
    /// 2026-01-01T09:41:00Z
    static let exportDate = Date(timeIntervalSince1970: 1_767_260_460)

    static func write(into writer: inout FixtureWriter) {
        writeSchemas(into: &writer)
        writer.write("export/golden.json", data: encoded(goldenPayload))
        writeRecords(into: &writer)
        writeQRCodes(into: &writer)
    }

    // MARK: - Records

    static func endpoint(
        _ name: String, _ url: String, weight: Int = 1, note: String? = nil, kind: GoldenTier = .free,
        targets: [GoldenTarget] = []
    ) -> GoldenEndpoint {
        var boxes: [String: FlagValueBox] = [
            "name": .string(name), "url": .url(URL(string: url)!), "weight": .int(weight),
            "kind": kind.box, "targets": FlagRecords(targets).box,
        ]
        if let note { boxes["note"] = .string(note) }
        guard let record = GoldenEndpoint(flagRecordBoxes: boxes) else { fatalError("bad endpoint \(name)") }
        return record
    }

    static func target(_ host: String, _ port: Int) -> GoldenTarget {
        guard let record = GoldenTarget(flagRecordBoxes: ["host": .string(host), "port": .int(port)]) else {
            fatalError("bad target \(host)")
        }
        return record
    }

    /// Mirrored by RecordsGoldenTest on the Kotlin side.
    static var recordLists: [(String, [GoldenEndpoint])] {
        [
            ("default", [endpoint("prod", "https://prod.example.com")]),
            ("empty", []),
            ("full", [
                endpoint(
                    "prod", "https://prod.example.com/v1?x=1", weight: 10, note: "primary \"quoted\" / slash",
                    kind: .enterprise, targets: [target("a.example", 443), target("b.example", 8443)]
                ),
                endpoint("staging", "https://staging.example.com", weight: 0, kind: .pro),
                endpoint("ünïcode", "https://example.com/%C3%BC", weight: -3, note: ""),
            ]),
        ]
    }

    static func recordText(_ endpoints: [GoldenEndpoint]) -> String {
        guard case let .string(text) = FlagRecords(endpoints).box else { fatalError("records box as text") }
        return text
    }

    static func writeRecords(into writer: inout FixtureWriter) {
        for (name, endpoints) in recordLists {
            writer.write("records/\(name).txt", text: recordText(endpoints))
        }
    }

    // MARK: - Schema

    static func writeSchemas(into writer: inout FixtureWriter) {
        for (name, encoding) in [("kebab", KeyEncoding.kebabcase), ("snake", KeyEncoding.snakecase)] {
            let schema = FlagSchema(
                GoldenFlags.self, keyEncoding: encoding, applicationName: "Golden", generatedAt: schemaDate
            )
            do {
                writer.write("schema/golden-\(name).flag-schema.json", data: try schema.jsonData())
            } catch {
                fatalError("schema \(name): \(error)")
            }
        }
    }

    // MARK: - Export

    /// One override for every flag in GoldenFlags, every type represented. Kebab-case keys.
    static var goldenPayload: FlagPayload {
        FlagPayload(
            values: [
                "bool-flag": .bool(true),
                "int-flag": .int(-7),
                "double-flag": .double(0.30000000000000004),
                "float-flag": .float(1.1),
                "string-flag": .string("line1\nline2\t\"q\" / é 😀"),
                "data-flag": .data(Data([0xDE, 0xAD, 0xBE, 0xEF])),
                "date-flag": .date(Date(timeIntervalSince1970: 1_767_260_460.75)),
                "url-flag": .url(URL(string: "https://example.com/path?q=1&r=a%20b#frag")!),
                "use-https-only": .bool(true),
                "markets": .array([.string("AU"), .string("NZ"), .string("US")]),
                "matrix": .array([.array([]), .array([.bool(true)])]),
                "limits": .dictionary(["z": .int(26), "a10": .int(10), "a9": .int(9), "A": .int(1)]),
                "tier": .string("enterprise"),
                "level": .int(3),
                "endpoints": .string(recordText(recordLists[2].1)),
                "checkout.apple-pay": .bool(false),
                "checkout.tier": .string("free"),
                "checkout.express.one-tap": .bool(true),
                "checkout.express.confirmation-delay": .double(0.5),
            ],
            exportedAt: exportDate
        )
    }

    static func encoded(_ payload: FlagPayload) -> Data {
        do {
            return try payload.encoded(as: .json)
        } catch {
            fatalError("export: \(error)")
        }
    }

    // MARK: - QR

    static func writeQRCodes(into writer: inout FixtureWriter) {
        let single = FlagPayload(values: ["bool-flag": .bool(true)], exportedAt: exportDate)
        var cases: [(String, FlagPayload)] = [("golden", goldenPayload), ("single", single)]

        // Apple stores the JSON uncompressed when deflate cannot make it smaller, which
        // only happens for short, high-entropy documents. Search a seeded family of
        // candidates for the first one that takes that path, and prove it did.
        var random = SeededGenerator(seed: 0x5EED_0005)
        var stored: FlagPayload?
        search: for length in 1...160 {
            for _ in 0..<8 {
                let scalars = (0..<length).map { _ -> Unicode.Scalar in
                    var value: UInt32
                    repeat { value = UInt32(0x800 + random.next() % (0xFFFE - 0x800)) } while (0xD800...0xDFFF).contains(value)
                    return Unicode.Scalar(value)!
                }
                let candidate = FlagPayload(
                    values: ["string-flag": .string(String(String.UnicodeScalarView(scalars)))], exportedAt: exportDate
                )
                if isStored(try! FlagQRCode.encode(candidate), json: encoded(candidate)) {
                    stored = candidate
                    break search
                }
            }
        }
        guard let stored else { fatalError("no stored-fallback QR candidate found") }
        cases.append(("stored", stored))

        for (name, payload) in cases {
            writer.write("qr/\(name).json", data: encoded(payload))
            do {
                writer.write("qr/\(name).qr.txt", text: try FlagQRCode.encode(payload))
            } catch {
                fatalError("qr \(name): \(error)")
            }
        }
        if isStored(try! FlagQRCode.encode(goldenPayload), json: encoded(goldenPayload)) {
            fatalError("the golden QR payload was expected to compress")
        }
    }

    /// Whether `code` carries `json` verbatim after its 4-byte length prefix.
    static func isStored(_ code: String, json: Data) -> Bool {
        var base64 = String(code.dropFirst(FlagQRCode.prefix.count))
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        if base64.count % 4 != 0 { base64 += String(repeating: "=", count: 4 - base64.count % 4) }
        guard let bytes = Data(base64Encoded: base64) else { fatalError("bad QR base64") }
        return bytes.dropFirst(4) == json
    }
}
