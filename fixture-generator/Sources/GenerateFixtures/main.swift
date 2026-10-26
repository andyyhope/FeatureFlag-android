import Foundation

// Writes every golden fixture the Kotlin port is tested against, using only the public
// API of FeatureFlag 0.12.1 and Foundation. Run through scripts/sync-fixtures.sh.

let arguments = CommandLine.arguments.dropFirst()
guard let outputPath = arguments.first else {
    FileHandle.standardError.write(Data("usage: generate-fixtures <output-directory>\n".utf8))
    exit(64)
}

var writer = FixtureWriter(root: URL(fileURLWithPath: outputPath, isDirectory: true))
ManifestFixtures.write(into: &writer)
JsonFixtures.write(into: &writer)
DateFixtures.write(into: &writer)
EncodingFixtures.write(into: &writer)
TransportFixtures.write(into: &writer)
RemoteFixtures.write(into: &writer)
MessageFixtures.write(into: &writer)
print("Wrote \(writer.written.count) fixture files to \(writer.root.path)")
