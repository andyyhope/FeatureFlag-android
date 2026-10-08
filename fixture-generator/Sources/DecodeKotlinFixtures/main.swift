import Foundation

// Decodes what the Kotlin port writes to fixtures-android/ (QR codes and exports) with
// FeatureFlag 0.12.0, proving Swift can read Kotlin's output. Filled in by M1c; until then it
// only reports that there is nothing to decode.

let directory = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "fixtures-android")
let entries = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
let files = entries.filter { $0.hasPrefix(".") == false }

guard files.isEmpty else {
    FileHandle.standardError.write(Data("""
        decode-kotlin-fixtures: \(directory.path) holds \(files.count) file(s), but decoding arrives in M1c.

        """.utf8))
    exit(1)
}

print("usage: decode-kotlin-fixtures [fixtures-android-directory]")
print("Nothing to decode: \(directory.path) is empty or missing.")
exit(0)
