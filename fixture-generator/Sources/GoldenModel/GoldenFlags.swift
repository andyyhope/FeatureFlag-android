import FeatureFlag
import Foundation

@FlagContainer(description: "Every flag type, for cross-platform golden fixtures")
public struct GoldenFlags {
    @Flag(default: false, description: "A boolean", remoteKey: "toggles.boolean") public var boolFlag: Bool
    @Flag(default: 42, description: "An integer", remoteKey: "numbers.int") public var intFlag: Int
    @Flag(default: 0.1, description: "A double", remoteKey: "numbers.double") public var doubleFlag: Double
    @Flag(default: Float(0.1), description: "A float") public var floatFlag: Float
    @Flag(default: "hello/world \"quoted\"", description: "A string", remoteKey: "strings.plain") public var stringFlag: String
    @Flag(default: Data([0, 1, 2, 254, 255]), description: "Some bytes") public var dataFlag: Data
    @Flag(default: Date(timeIntervalSince1970: 1_767_225_600), description: "A date", remoteKey: "dates.launch") public var dateFlag: Date
    @Flag(default: URL(string: "https://example.com/a/b?c=d")!, description: "A URL", remoteKey: "config.endpoint") public var urlFlag: URL
    @Flag(default: false, description: "Uses an acronym") public var useHTTPSOnly: Bool
    @Flag(default: ["AU", "NZ"], description: "Markets", remoteKey: "config.markets") public var markets: [String]
    @Flag(default: [[true], [false, true]], description: "Nested arrays") public var matrix: [[Bool]]
    @Flag(default: ["b": 2, "a": 1, "v10": 10, "v2": 2], description: "Limits by name") public var limits: [String: Int]
    @Flag(default: GoldenTier.free, description: "A string enum", remoteKey: "tier") public var tier: GoldenTier
    @Flag(default: GoldenLevel.two, description: "An int enum") public var level: GoldenLevel
    @Flag(default: [GoldenEndpoint(name: "prod", url: URL(string: "https://prod.example.com")!)],
          description: "Endpoints", remoteKey: "config.endpoints") public var endpoints: FlagRecords<GoldenEndpoint>
    @FlagGroup(description: "Checkout") public var checkout: GoldenCheckout
}
@FlagContainer public struct GoldenCheckout {
    @Flag(default: true, description: "Offer Apple Pay", remoteKey: "featureToggles.checkout.applePay") public var applePay: Bool
    @Flag(default: GoldenTier.pro, description: "Tier at checkout") public var tier: GoldenTier
    @FlagGroup(description: "Express checkout") public var express: GoldenExpress
}
@FlagContainer public struct GoldenExpress {
    @Flag(default: false, description: "One-tap purchase") public var oneTap: Bool
    @Flag(default: 3.0, description: "Seconds before confirming") public var confirmationDelay: Double
}
public enum GoldenTier: String, FlagValue, CaseIterable, FlagValueCases { case free, pro, enterprise }
public enum GoldenLevel: Int, FlagValue, CaseIterable, FlagValueCases { case one = 1, two = 2, three = 3 }
@FlagRecord public struct GoldenEndpoint {
    @FlagRecordKey public var name: String
    public var url: URL
    public var weight: Int = 1
    @FlagRecordProperty(key: "memo") public var note: String?
    public var kind: GoldenTier = .free
    public var targets: FlagRecords<GoldenTarget> = []
}
@FlagRecord public struct GoldenTarget { public var host: String; public var port: Int }
