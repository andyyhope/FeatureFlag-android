// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "fixture-generator",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "generate-fixtures", targets: ["GenerateFixtures"]),
        .executable(name: "decode-kotlin-fixtures", targets: ["DecodeKotlinFixtures"]),
    ],
    dependencies: [
        .package(url: "https://github.com/andyyhope/FeatureFlag.git", exact: "0.12.1"),
    ],
    targets: [
        .target(
            name: "GoldenModel",
            dependencies: [.product(name: "FeatureFlag", package: "FeatureFlag")]
        ),
        .executableTarget(
            name: "GenerateFixtures",
            dependencies: ["GoldenModel", .product(name: "FeatureFlag", package: "FeatureFlag")]
        ),
        .executableTarget(
            name: "DecodeKotlinFixtures",
            dependencies: ["GoldenModel", .product(name: "FeatureFlag", package: "FeatureFlag")]
        ),
    ]
)
