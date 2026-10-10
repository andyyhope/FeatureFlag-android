import Foundation

enum ManifestFixtures {
    static func write(into writer: inout FixtureWriter) {
        let manifest: [String: Any] = [
            "featureFlagVersion": "0.12.0",
            "swift": swiftVersion,
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "generatedBy": "fixture-generator",
        ]
        writer.write("manifest.json", data: applePretty(manifest))
    }

    private static var swiftVersion: String {
        #if compiler(>=6.5)
            return "6.5+"
        #elseif compiler(>=6.4)
            return "6.4"
        #elseif compiler(>=6.3)
            return "6.3"
        #elseif compiler(>=6.2)
            return "6.2"
        #elseif compiler(>=6.1)
            return "6.1"
        #else
            return "6.0"
        #endif
    }
}
