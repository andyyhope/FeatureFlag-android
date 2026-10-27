// Decodes every file the Kotlin port wrote into fixtures-android/ with FeatureFlag 0.12.1,
// and exits non-zero if any of them fails.
//
//   swift run --package-path fixture-generator decode-kotlin-fixtures [fixtures-android]
//
// - `<name>.json` is a Kotlin export. It must decode against the GoldenFlags schema and
//   re-encode to the same bytes.
// - `<name>.qr.txt` is a Kotlin QR string. It must decode, and its payload must encode to
//   the bytes of the sibling `<name>.json`. Whether Apple's own QR string for that payload
//   is identical is printed but not required: zlib implementations may differ.

import FeatureFlag
import Foundation
import GoldenModel

func run() -> Int32 {
    let arguments = CommandLine.arguments
    let directory = URL(
        fileURLWithPath: arguments.count > 1 ? arguments[1] : "fixtures-android",
        isDirectory: true
    )
    let schema = FlagSchema(GoldenFlags.self, keyEncoding: .kebabcase, applicationName: "Golden")

    let names: [String]
    do {
        names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
    } catch {
        print("FAIL cannot list \(directory.path): \(error)")
        return 1
    }

    var failures = 0
    var checked = 0

    func fail(_ name: String, _ reason: String) {
        print("FAIL \(name): \(reason)")
        failures += 1
    }

    for name in names where name.hasSuffix(".json") {
        checked += 1
        do {
            let bytes = try Data(contentsOf: directory.appendingPathComponent(name))
            let payload = try FlagPayload.decode(
                bytes,
                as: .json,
                valueTypes: schema.valueTypes,
                cases: schema.valueCases,
                recordShapes: schema.recordShapes
            )
            if try payload.encoded(as: .json) == bytes {
                print("ok   \(name): \(payload.values.count) values, byte-identical")
            } else {
                fail(name, "decoded, but Apple re-encodes it to different bytes")
            }
        } catch {
            fail(name, "\(error)")
        }
    }

    for name in names where name.hasSuffix(".qr.txt") {
        checked += 1
        let stem = String(name.dropLast(".qr.txt".count))
        do {
            let scanned = try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
                .trimmingCharacters(in: .newlines)
            let expected = try Data(contentsOf: directory.appendingPathComponent("\(stem).json"))
            let payload = try FlagQRCode.decode(
                scanned,
                valueTypes: schema.valueTypes,
                cases: schema.valueCases,
                recordShapes: schema.recordShapes
            )
            guard try payload.encoded(as: .json) == expected else {
                fail(name, "decoded, but the payload does not encode to \(stem).json")
                continue
            }
            let appleString = try FlagQRCode.encode(payload)
            print("ok   \(name): decoded; Apple's own code is \(appleString == scanned ? "identical" : "different (allowed)")")
        } catch {
            fail(name, "\(error)")
        }
    }

    if checked == 0 {
        print("FAIL no fixtures in \(directory.path)")
        return 1
    }
    print(failures == 0 ? "All \(checked) Kotlin fixtures decode." : "\(failures) of \(checked) failed.")
    return failures == 0 ? 0 : 1
}

exit(run())
