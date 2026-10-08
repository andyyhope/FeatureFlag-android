import Foundation

/// Writes fixture files under one root and remembers what it wrote.
struct FixtureWriter {
    let root: URL
    private(set) var written: [String] = []

    init(root: URL) {
        self.root = root
    }

    mutating func write(_ relativePath: String, data: Data) {
        let url = root.appendingPathComponent(relativePath)
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try data.write(to: url, options: .atomic)
        } catch {
            fatalError("could not write \(relativePath): \(error)")
        }
        written.append(relativePath)
    }

    mutating func write(_ relativePath: String, text: String) {
        write(relativePath, data: Data(text.utf8))
    }

    /// One row per line, every line ending in `\n`, fields escaped by `tsvEscape`.
    mutating func writeTSV(_ relativePath: String, rows: [[String]]) {
        write(relativePath, text: rows.map { $0.map(tsvEscape).joined(separator: "\t") + "\n" }.joined())
    }
}

/// The TSV escaping rule shared with `Fixtures.kt`: `\` → `\\`, tab → `\t`, newline → `\n`,
/// any other C0 control → `\u00xx` (lowercase hex).
func tsvEscape(_ text: String) -> String {
    var out = ""
    for scalar in text.unicodeScalars {
        switch scalar {
        case "\\": out += "\\\\"
        case "\t": out += "\\t"
        case "\n": out += "\\n"
        default:
            if scalar.value < 0x20 {
                out += String(format: "\\u%04x", scalar.value)
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    return out
}

/// What FeatureFlag writes for records and what JsonWriter.compact must reproduce.
func appleCompact(_ object: Any) -> String {
    do {
        let data = try JSONSerialization.data(
            withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed]
        )
        return String(decoding: data, as: UTF8.self)
    } catch {
        fatalError("could not serialise \(object): \(error)")
    }
}

/// What FeatureFlag writes for schemas and exports and what JsonWriter.pretty must reproduce.
func applePretty(_ object: Any) -> Data {
    do {
        return try JSONSerialization.data(
            withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
    } catch {
        fatalError("could not serialise \(object): \(error)")
    }
}

func hexString(_ bytes: some Sequence<UInt8>) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
}

func bitsHex(_ value: Double) -> String {
    String(format: "%016llx", value.bitPattern)
}

/// SplitMix64, so every "random" case list is identical on every run.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// Keeps the first occurrence of each element, in order.
func unique<T: Hashable>(_ items: [T]) -> [T] {
    var seen = Set<T>()
    return items.filter { seen.insert($0).inserted }
}
