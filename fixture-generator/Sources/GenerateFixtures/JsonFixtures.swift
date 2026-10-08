import Foundation

/// json/*: number formatting, number classification, string escaping, key order,
/// pretty printing and parse acceptance — everything JsonReader/JsonWriter copy.
enum JsonFixtures {
    static func write(into writer: inout FixtureWriter) {
        writeNumbers(into: &writer)
        writeNumberParse(into: &writer)
        writeStrings(into: &writer)
        writeKeyOrder(into: &writer)
        writer.write("json/pretty.json", data: applePretty(prettyStructure))
        writeParse(into: &writer)
    }

    // MARK: - numbers.tsv

    static func writeNumbers(into writer: inout FixtureWriter) {
        var values: [Double] = [
            0, -0.0, 3, 0.1, 1e16, 1e-5, 1e21, 5e-324, Double(Float(0.1)),
            1, -1, 0.5, 2.5, -2.5, 100, 1e15, 1e17, 1.5e16, 9.999999999999999e16,
            99_999_999_999_999_990, 0.0001, 0.00001, 0.000123, 5e-5,
            1_234_567_890_123_456_789, 123_456_789.123, 0.1 + 0.2, 1.0 / 3, 2.0 / 3,
            Double.pi, M_E, 9_007_199_254_740_992, 9_007_199_254_740_993,
            9_223_372_036_854_775_808, 18_446_744_073_709_551_616, 1e300, -1e300, 1e-300,
            Double.greatestFiniteMagnitude, -Double.greatestFiniteMagnitude,
            Double.leastNormalMagnitude, Double.leastNonzeroMagnitude, -Double.leastNonzeroMagnitude,
            2.225_073_858_507_200_9e-308, 2 * Double.leastNonzeroMagnitude,
            (1e17).nextDown, (1e16).nextDown, (1e16).nextUp, (1e-4).nextDown, (1e-4).nextUp,
            (1e21).nextDown, (1e22).nextUp, 1e23, 8.41e21, 5e-310, 4.35, 0.035, 1.005, 2.675,
            123e18, -0.000_001, 1e-7, 7e-10,
        ]
        for exponent in -10...22 {
            values.append(Double("1e\(exponent)")!)
            values.append(-Double("1e\(exponent)")!)
        }
        let floats: [Float] = [
            0.1, 1.0 / 3, Float.pi, .greatestFiniteMagnitude, .leastNonzeroMagnitude,
            .leastNormalMagnitude, 16_777_217, 1e-10, 3.4e38, -0.3, 0.7, 1e10, 123.456,
        ]
        values += floats.map(Double.init)

        var random = SeededGenerator(seed: 0x5EED_0001)
        var bitPatterns = 0
        while bitPatterns < 400 {
            let value = Double(bitPattern: random.next())
            if value.isFinite { values.append(value); bitPatterns += 1 }
        }
        for _ in 0..<200 { values.append(Double(random.next() % 1_000_000_000) / 1000) }
        for _ in 0..<100 { values.append(Double(Int64(bitPattern: random.next()) >> (random.next() % 64))) }
        for _ in 0..<100 {
            values.append(Double(random.next() % 10_000) * pow(10, Double(Int(random.next() % 40) - 20)))
        }
        for _ in 0..<100 { values.append(Double(Float(bitPattern: UInt32(truncatingIfNeeded: random.next()) & 0x7F7F_FFFF))) }

        let rows = unique(values.map(\.bitPattern)).map(Double.init(bitPattern:)).map { value -> [String] in
            let array = appleCompact([value])
            return [bitsHex(value), String(array.dropFirst().dropLast())]
        }
        writer.writeTSV("json/numbers.tsv", rows: rows)
    }

    // MARK: - number-parse.tsv

    static let numberLiterals: [String] = [
        "0", "-0", "1", "-1", "42", "3.0", "1e2", "2.0e0", "1E2", "1e+2", "1.0e0", "0e0", "1E-0",
        "-0.0", "0.0", "-0e5", "-0.0e-0", "1e-400", "-1e-400", "0.1", "1.5", "-2.5", "123.456e-2",
        "1.0000000000000002", "0.30000000000000004", "1.5e3", "2e-1", "1e-7", "123e-20", "0.000001",
        "1e19", "10000000000000000000", "9223372036854775807", "9223372036854775808",
        "-9223372036854775808", "-9223372036854775809", "18446744073709551615", "18446744073709551616",
        "123456789012345678901234567890", "-123456789012345678901234567890", "100000000000000000000000",
        "9007199254740992", "9007199254740993", "-9007199254740993", "9007199254740993.0",
        "4611686018427387904", "4611686018427387905", "16777217",
        "9.2233720368547758E18", "-9.2233720368547758E18", "9.223372036854777e18", "-9.223372036854777e18",
        "4.5e15", "1e300", "1e308", "1.7976931348623157e308", "1.7976931348623158e308",
        "1.7976931348623159e308", "1e309", "1E400", "-1E400", "2.5e-324", "4.9e-324",
        "2.4703282292062327e-324", "2.4703282292062328e-324", "2.2250738585072011e-308",
        "2.2250738585072012e-308", "3.4028235e38", "3.5e38", "1e39",
        "1" + String(repeating: "0", count: 400), "0." + String(repeating: "0", count: 400) + "1",
        "1.", ".5", "+1", "01", "-01", "00", "-", "1e", "1e+", "0x10", "Infinity", "-Infinity", "NaN",
        "1.e5", "1e5.5", "--1", "1_000",
    ]

    static func writeNumberParse(into writer: inout FixtureWriter) {
        let rows = numberLiterals.map { literal -> [String] in
            guard
                let parsed = try? JSONSerialization.jsonObject(with: Data("[\(literal)]".utf8)) as? [Any],
                let number = parsed.first as? NSNumber
            else { return [literal, "reject", "-"] }
            // `as? Int` is exactly what FlagValueBox(jsonValue:as: .int) does.
            if let integer = parsed.first as? Int { return [literal, "integer", String(integer)] }
            return [literal, "real", bitsHex(number.doubleValue)]
        }
        writer.writeTSV("json/number-parse.tsv", rows: rows)
    }

    // MARK: - strings

    static var stringCases: [[UInt32]] {
        var cases: [[UInt32]] = [[]]
        cases += (0x00...0x1F).map { [UInt32($0)] }
        cases += [
            [0x7F], [0x80], [0x9F], [0xA0], [0xE9], [0x2028], [0x2029], [0xFEFF], [0xFFFD], [0xFFFF],
            [0x1F600], [0x10FFFF], [0x22], [0x5C], [0x2F], [0x27], [0x3C, 0x2F, 0x73, 0x3E],
            Array("a\"b\\c/d".unicodeScalars.map(\.value)),
            Array("line1\nline2\r\n\ttab\u{0}nul\u{1F}us\u{7F}del".unicodeScalars.map(\.value)),
            Array("hello/world \"quoted\"".unicodeScalars.map(\.value)),
            Array("ünïcödé ✓ 日本語 👩‍👩‍👧".unicodeScalars.map(\.value)),
            Array("\\u0041 is not an escape".unicodeScalars.map(\.value)),
        ]
        return cases
    }

    static func writeStrings(into writer: inout FixtureWriter) {
        let cases = stringCases
        let lines = cases.map { $0.map { String($0, radix: 16) }.joined(separator: " ") }
        writer.write("json/strings.codepoints.txt", text: lines.joined(separator: "\n"))
        let strings = cases.map { scalars in
            String(String.UnicodeScalarView(scalars.map { Unicode.Scalar($0)! }))
        }
        writer.write("json/strings.json", text: appleCompact(strings))
    }

    // MARK: - key order

    static var orderKeys: [String] {
        var keys: [String] = (0x20...0x7E).map { String(UnicodeScalar(UInt8($0))) }
        keys += [
            "a1b", "a1B", "a01b", "a01B", "a001b", "A1b", "ab", "aB", "Ab", "AB",
            "x", "x0", "x00", "x000", "x1", "x01", "a", "a-", "a--", "a-a", "aa",
            "foo", "foo2", "foo2bar", "foo10", "9", "99999999999999999999", "100000000000000000000",
            "0", "00", "000", "007", "7", "07", "1.5", "1.05", "1.50", "1-5", "1_5", "1 5",
            "item9", "item10", "item010", "item09", "Item9", "ITEM10", "v2", "v10", "V2", "v1.2.10", "v1.2.9",
            "use-https-only", "use_https_only", "useHTTPSOnly", "checkout.apple-pay", "checkout.tier",
            "checkout.express.one-tap", "checkout.express.confirmation-delay", "checkout_express",
            "featureToggles.onboarding.v2", "max-items", "new-onboarding", "v2-enabled", "enable2-fa",
            "a.b", "a_b", "a-b", "a b", "A.b", "a.B", "1password", "_private", "-dash", "~tilde", "$dollar",
            "#hash", "@at", "a@b", "a#b", "a$b", "a~b", "a|b", "a=b", "a+b", "a^b", "a`b", "a%b", "a&b",
            "a\\b", "a/b", "a*b", "a}b", "a{b", "a]b", "a[b", "a)b", "a(b", "a\"b", "a'b", "a.b.c",
            "a!b", "a?b", "a:b", "a;b", "a,b", "a<b", "a>b", "zz", "ZZ", "zZ", "Zz", "z9", "z09", "z9z",
        ]
        let pairAlphabet = [" ", "_", "-", ".", "0", "1", "9", "a", "A", "z", "Z", "$", "~"]
        for first in pairAlphabet { for second in pairAlphabet { keys.append(first + second) } }

        var random = SeededGenerator(seed: 0x5EED_0002)
        let alphabet = Array("aAbB0019_-. $~zZ")
        for _ in 0..<500 {
            let length = Int(random.next() % 8) + 1
            keys.append(String((0..<length).map { _ in alphabet[Int(random.next() % UInt64(alphabet.count))] }))
        }
        return unique(keys)
    }

    static func writeKeyOrder(into writer: inout FixtureWriter) {
        let keys = orderKeys
        writer.write("json/key-order.keys.txt", text: keys.joined(separator: "\n"))
        let object = Dictionary(uniqueKeysWithValues: keys.map { ($0, 0) })
        writer.write("json/key-order.json", text: appleCompact(object))
    }

    // MARK: - pretty.json

    /// Built identically by JsonWriterGoldenTest.prettyStructure() on the Kotlin side.
    static var prettyStructure: [String: Any] {
        [
            "array": [1, 2.5, "three", true, false, NSNull(), [Any](), [String: Any](), [[Any]()], [[String: Any]()]] as [Any],
            "emptyArray": [Any](),
            "emptyObject": [String: Any](),
            "nested": [
                "deeper": ["deepest": [Any](), "value": -0.0] as [String: Any],
                "list": [["a": 1], [String: Any]()] as [Any],
            ] as [String: Any],
            "numbers": [0, -1, 1e21, 0.1, 5e-324, Int.max, Int.min, 3.0] as [Any],
            "strings": ["", "/", "\"\\", "\u{1}\t\n", "é😀"],
            "Z": "capital",
            "z": NSNull(),
            "item10": 10,
            "item9": 9,
        ]
    }

    // MARK: - parse.tsv

    static var parseInputs: [String] {
        let deepArrays = { (depth: Int) in String(repeating: "[", count: depth) + String(repeating: "]", count: depth) }
        let deepObjects = { (depth: Int) in
            String(repeating: "{\"a\":", count: depth - 1) + "{}" + String(repeating: "}", count: depth - 1)
        }
        return [
            "[]", "{}", "[ ]", "{ }", " [1] ", "\t\n\r[1]\t\n\r", "\u{FEFF}[1]", "\u{FEFF} {}",
            "[1,]", "{\"a\":1,}", "[ 1 , ]", "[,]", "[1,,]", "{,}", "{\"a\":1,,}", "[1] x", "[1][2]",
            "", "   ", "\u{0C}[1]", "\u{A0}[1]", "[1]\u{0}", "3", "\"x\"", "true", "null",
            "[NaN]", "[01]", "[1.]", "[-]", "[+1]", "[.5]", "[1E400]", "[Infinity]",
            "[\"\\ud800\"]", "[\"\\udc00\"]", "[\"\\ud800x\"]", "[\"\\ud800\\u0041\"]", "[\"\\ud83d\\ude00\"]",
            "[\"\\/\\b\\f\\n\\r\\t\\\"\\\\\\u0041\\u00e9\"]", "[\"\\u0000\\u001F\\u007f\"]",
            "[\"a\tb\"]", "[\"a\nb\"]", "[\"a\u{01}b\"]", "[\"a\u{7F}b\"]", "[\"\u{2028}\u{2029}\"]",
            "[\"\\x\"]", "[\"\\U0041\"]", "[\"\\u004\"]", "[\"\\u00G0\"]", "[\"unterminated]", "[\"a\" \"b\"]",
            "{\"a\":1,\"a\":2}", "{\"b\":1,\"a\":2,\"b\":3}", "{\"a\":{\"x\":1},\"a\":[2]}",
            "[true,false,null]", "[tru]", "[True]", "[nul]", "{1:2}", "{\"a\" 1}", "{\"a\":}", "{\"a\"}",
            "[1 2]", "// c\n[1]", "[/*c*/1]", "['a']", "{a:1}", "[1,2", "{\"a\":1", "]", "}", "[}", "{]",
            "{\"z\":{\"b\":[1,{\"y\":2,\"x\":1}],\"a\":null},\"y\":[]}",
            "[1.5,-2,1e2,0.1,0,-0,1E-2,5e-324,1.7976931348623157e308]",
            "{\"\":0,\"key with space\":\"v\",\"slash/key\":\"a/b\"}",
            "[[[[[]]]],{\"a\":{\"b\":{}}}]",
            deepArrays(512), deepArrays(513), deepArrays(514), deepObjects(513), deepObjects(514),
            "{\"a\":" + deepArrays(512) + "}", "{\"a\":" + deepArrays(513) + "}",
        ]
    }

    static func writeParse(into writer: inout FixtureWriter) {
        let rows = parseInputs.map { input -> [String] in
            guard let object = try? JSONSerialization.jsonObject(with: Data(input.utf8)) else {
                return [input, "reject"]
            }
            return [input, appleCompact(object)]
        }
        writer.writeTSV("json/parse.tsv", rows: rows)
    }
}
