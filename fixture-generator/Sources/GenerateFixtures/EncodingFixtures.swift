import FeatureFlag
import Foundation

/// keys/, base64/ and url/: key encoding, Data(base64Encoded:) and URL(string:).
enum EncodingFixtures {
    static func write(into writer: inout FixtureWriter) {
        writeKeyEncoding(into: &writer)
        writeBase64(into: &writer)
        writeURLs(into: &writer)
    }

    // MARK: - keys/key-encoding.tsv

    static let propertyNames = [
        // FlagKeyTests and KeyEncodingEdgeCaseTests
        "newOnboarding", "checkout", "enableAPI", "apiKey", "useHTTPSOnly", "checkoutV2", "express", "oneTap",
        "applePay", "a", "A", "", "API", "NewOnboarding", "v2Enabled", "enable2FA", "legacy_name",
        // GoldenFlags
        "boolFlag", "intFlag", "doubleFlag", "floatFlag", "stringFlag", "dataFlag", "dateFlag", "urlFlag",
        "markets", "matrix", "limits", "tier", "level", "endpoints", "confirmationDelay",
        // More shapes
        "URLSession", "HTTPSURL", "iOS", "isOK", "aB", "ABc", "x1Y2", "__a", "a__b", "ID", "userID", "IDs",
        "PDFViewer", "XY", "x", "maxItems", "featureToggles", "v2", "V2", "V2Beta", "version2Point1",
        "already-kebab", "snake_Case", "ÄpfelÖl", "naïveCafé", "straßeGroß", "ΑλφαΒήτα", "日本Go",
        "isİstanbul", "a1B2c3", "HTML5Parser", "x2Y", "ABC123def", "lowerUPPERLower",
        // Graphemes and final sigma
        "ΟΔΟΣ", "ΟΔΟΣName", "σοφίαΣ",
        "ABE\u{301}c", "E\u{301}clair", "cafe\u{301}Bar",
        "x\u{1F1E6}\u{1F1FA}Y", "AB\u{1F44D}\u{1F3FD}c",
    ]

    static func writeKeyEncoding(into writer: inout FixtureWriter) {
        let rows = unique(propertyNames).map { name in
            [
                name,
                KeyEncoding.kebabcase.key(for: FlagKeyPath([name])).rawValue,
                KeyEncoding.snakecase.key(for: FlagKeyPath([name])).rawValue,
            ]
        }
        writer.writeTSV("keys/key-encoding.tsv", rows: rows)
    }

    // MARK: - base64/decode.tsv

    static func writeBase64(into writer: inout FixtureWriter) {
        var inputs = [
            "", "YQ", "YQ=", "YQ==", "YR==", "YWI=", "YWJ=", "YWJj", "aGk=", "aGk==", "aGk===", "QUJD====",
            "Zm9v", "Zm9vYg", "Zm9vYg==", "Zm9vYmE=", "Zm9vYmE", "Zm9vYmFy", " Zm9v", "Zm9v ", "Zm 9v",
            "Zm9v\n", "Zm9v\r\n", "Zm9v\t", "Zm-_", "Zm+/", "Zm9v=Zm9v", "Zm9v====Zm9v", "=Zm9v", "Z===",
            "Zg=", "Zg===", "Zg=====", "Zm8=", "Zm9=v", "AA=A", "//=/", "/w=/", "====", "=", "==", "===",
            "AAECAwQFBgcICQ==", "AAEC/v8=", "3q2+7w==", "w6k=", "ÿÿÿÿ", "YWJj\u{0}", "YW=j", "Y===",
            "YQ==YQ==", "YQ==\u{0}", "Y Q = =", "-_-_", "QUJD", "QUJDRA", "QUJDRA=", "QUJDRA==",
        ]
        let alphabet = ["A", "/", "=", "g"]
        var frontier = [""]
        for _ in 1...6 {
            frontier = frontier.flatMap { prefix in alphabet.map { prefix + $0 } }
            inputs += frontier
        }
        var random = SeededGenerator(seed: 0x5EED_0004)
        for _ in 0..<200 {
            let bytes = (0..<Int(random.next() % 40)).map { _ in UInt8(truncatingIfNeeded: random.next()) }
            inputs.append(Data(bytes).base64EncodedString())
        }
        let rows = unique(inputs).map { input -> [String] in
            [input, Data(base64Encoded: input).map(hexString) ?? "nil"]
        }
        writer.writeTSV("base64/decode.tsv", rows: rows)
    }

    // MARK: - url/parse.tsv (URL(string:) as FlagValueBox(jsonValue:as: .url) uses it)

    static let urlInputs = [
        "", " ", "a b", "https://example.com", "https://example.com/a/b?c=d", "https://example.com/a b",
        "https://例え.jp/パス", "http://[::1]:8080/x", "%", "https://example.com/%zz", "not a url", "://",
        "mailto:x@y.z", "http://a.com/ü", "#frag", "?q", "a/b", "https://exa mple.com",
        "https://example.com/a?b=c d#e f", "https://example.com/\"q\"", "https://example.com/<x>",
        "https://example.com/a|b", "https://example.com/a\\b", "https://example.com/{x}", "https://example.com/^",
        "https://example.com/`", "http://a.com:abc/", "http://a.com:99999/", "http://[zz]/",
        "https://example.com/a%20b", "HTTPS://Example.COM/A", "https://example.com/a\tb", "https://example.com/a\nb",
        " https://example.com", "https://example.com ", "file:///tmp/x", "urn:isbn:0451450523",
        "https://user:pa ss@example.com/", "https://example.com/😀", "https://example.com/path?q=1&r=a%20b#frag",
        "https://prod.example.com", "https://example.com:443/", "ftp://ftp.example.com/file.txt",
        "data:text/plain;base64,SGk=", "https://example.com/a%2Fb", "//example.com/path", "/relative/path",
        "relative", "https://", "https:", "http://a.com/%E2%82%AC", "http://a.com/€", "https://example.com/#",
        "https://example.com/?", "custom-scheme://host/path", "1http://x", "a:b:c", "https://a.com/[x]",
    ]

    static func writeURLs(into writer: inout FixtureWriter) {
        let rows = urlInputs.map { input -> [String] in
            [input, URL(string: input)?.absoluteString ?? "nil"]
        }
        writer.writeTSV("url/parse.tsv", rows: rows)
    }
}
