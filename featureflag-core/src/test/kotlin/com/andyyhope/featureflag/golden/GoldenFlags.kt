package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.records.FlagRecords
import java.net.URI
import java.time.Instant

// The containers of fixture-generator/Sources/GoldenModel/GoldenFlags.swift; the enums and records
// are in GoldenModel.kt. Property names, descriptions, defaults, remote keys and declaration order
// must match Swift exactly: the schema golden is compared byte for byte.

@FlagContainer(description = "Every flag type, for cross-platform golden fixtures")
interface GoldenFlags {
    @Flag(description = "A boolean", remoteKey = "toggles.boolean")
    val boolFlag: Boolean get() = false

    @Flag(description = "An integer", remoteKey = "numbers.int")
    val intFlag: Int get() = 42

    @Flag(description = "A double", remoteKey = "numbers.double")
    val doubleFlag: Double get() = 0.1

    @Flag(description = "A float")
    val floatFlag: Float get() = 0.1f

    @Flag(description = "A string", remoteKey = "strings.plain")
    val stringFlag: String get() = "hello/world \"quoted\""

    @Flag(description = "Some bytes")
    val dataFlag: ByteArray get() = byteArrayOf(0, 1, 2, 254.toByte(), 255.toByte())

    @Flag(description = "A date", remoteKey = "dates.launch")
    val dateFlag: Instant get() = Instant.ofEpochSecond(1_767_225_600)

    @Flag(description = "A URL", remoteKey = "config.endpoint")
    val urlFlag: URI get() = URI("https://example.com/a/b?c=d")

    @Flag(description = "Uses an acronym")
    val useHTTPSOnly: Boolean get() = false

    @Flag(description = "Markets", remoteKey = "config.markets")
    val markets: List<String> get() = listOf("AU", "NZ")

    @Flag(description = "Nested arrays")
    val matrix: List<List<Boolean>> get() = listOf(listOf(true), listOf(false, true))

    @Flag(description = "Limits by name")
    val limits: Map<String, Int> get() = mapOf("b" to 2, "a" to 1, "v10" to 10, "v2" to 2)

    @Flag(description = "A string enum", remoteKey = "tier")
    val tier: GoldenTier get() = GoldenTier.FREE

    @Flag(description = "An int enum")
    val level: GoldenLevel get() = GoldenLevel.TWO

    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<GoldenEndpoint>
        get() = FlagRecords(listOf(GoldenEndpoint(name = "prod", url = URI("https://prod.example.com"))))

    @FlagGroup(description = "Checkout")
    val checkout: GoldenCheckout
}

@FlagContainer
interface GoldenCheckout {
    @Flag(description = "Offer Apple Pay", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = true

    @Flag(description = "Tier at checkout")
    val tier: GoldenTier get() = GoldenTier.PRO

    @FlagGroup(description = "Express checkout")
    val express: GoldenExpress
}

@FlagContainer
interface GoldenExpress {
    @Flag(description = "One-tap purchase")
    val oneTap: Boolean get() = false

    @Flag(description = "Seconds before confirming")
    val confirmationDelay: Double get() = 3.0
}
