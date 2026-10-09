package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagGroupDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import java.net.URI
import java.time.Instant

// Written by hand in exactly the shape featureflag-ksp generates for a @FlagContainer (compare
// featureflag-ksp/src/test/resources/expansions/AppFlagsSpec.kt.txt). Task B3 deletes this file and
// lets the processor write it; every test that uses these names must stay green across that swap.

open class GoldenFlagsRefs(prefix: FlagKeyPath) {
    val boolFlag: FlagRef<Boolean> = FlagRef(
        prefix.appending("boolFlag"),
        "boolFlag",
        "A boolean",
        "toggles.boolean",
        FlagCodecs.boolean,
    ) { GoldenFlagsSpec.flagDefaults.boolFlag }

    val intFlag: FlagRef<Int> = FlagRef(
        prefix.appending("intFlag"),
        "intFlag",
        "An integer",
        "numbers.int",
        FlagCodecs.int,
    ) { GoldenFlagsSpec.flagDefaults.intFlag }

    val doubleFlag: FlagRef<Double> = FlagRef(
        prefix.appending("doubleFlag"),
        "doubleFlag",
        "A double",
        "numbers.double",
        FlagCodecs.double,
    ) { GoldenFlagsSpec.flagDefaults.doubleFlag }

    val floatFlag: FlagRef<Float> = FlagRef(
        prefix.appending("floatFlag"),
        "floatFlag",
        "A float",
        null,
        FlagCodecs.float,
    ) { GoldenFlagsSpec.flagDefaults.floatFlag }

    val stringFlag: FlagRef<String> = FlagRef(
        prefix.appending("stringFlag"),
        "stringFlag",
        "A string",
        "strings.plain",
        FlagCodecs.string,
    ) { GoldenFlagsSpec.flagDefaults.stringFlag }

    val dataFlag: FlagRef<ByteArray> = FlagRef(
        prefix.appending("dataFlag"),
        "dataFlag",
        "Some bytes",
        null,
        FlagCodecs.byteArray,
    ) { GoldenFlagsSpec.flagDefaults.dataFlag }

    val dateFlag: FlagRef<Instant> = FlagRef(
        prefix.appending("dateFlag"),
        "dateFlag",
        "A date",
        "dates.launch",
        FlagCodecs.instant,
    ) { GoldenFlagsSpec.flagDefaults.dateFlag }

    val urlFlag: FlagRef<URI> = FlagRef(
        prefix.appending("urlFlag"),
        "urlFlag",
        "A URL",
        "config.endpoint",
        FlagCodecs.uri,
    ) { GoldenFlagsSpec.flagDefaults.urlFlag }

    val useHTTPSOnly: FlagRef<Boolean> = FlagRef(
        prefix.appending("useHTTPSOnly"),
        "useHTTPSOnly",
        "Uses an acronym",
        null,
        FlagCodecs.boolean,
    ) { GoldenFlagsSpec.flagDefaults.useHTTPSOnly }

    val markets: FlagRef<List<String>> = FlagRef(
        prefix.appending("markets"),
        "markets",
        "Markets",
        "config.markets",
        FlagCodecs.list(FlagCodecs.string),
    ) { GoldenFlagsSpec.flagDefaults.markets }

    val matrix: FlagRef<List<List<Boolean>>> = FlagRef(
        prefix.appending("matrix"),
        "matrix",
        "Nested arrays",
        null,
        FlagCodecs.list(FlagCodecs.list(FlagCodecs.boolean)),
    ) { GoldenFlagsSpec.flagDefaults.matrix }

    val limits: FlagRef<Map<String, Int>> = FlagRef(
        prefix.appending("limits"),
        "limits",
        "Limits by name",
        null,
        FlagCodecs.map(FlagCodecs.int),
    ) { GoldenFlagsSpec.flagDefaults.limits }

    val tier: FlagRef<GoldenTier> = FlagRef(
        prefix.appending("tier"),
        "tier",
        "A string enum",
        "tier",
        FlagCodecs.stringEnum(GoldenTier.entries),
    ) { GoldenFlagsSpec.flagDefaults.tier }

    val level: FlagRef<GoldenLevel> = FlagRef(
        prefix.appending("level"),
        "level",
        "An int enum",
        null,
        FlagCodecs.intEnum(GoldenLevel.entries),
    ) { GoldenFlagsSpec.flagDefaults.level }

    val endpoints: FlagRef<FlagRecords<GoldenEndpoint>> = FlagRef(
        prefix.appending("endpoints"),
        "endpoints",
        "Endpoints",
        "config.endpoints",
        FlagCodecs.records(GoldenEndpointFlagRecord),
    ) { GoldenFlagsSpec.flagDefaults.endpoints }

    val checkout: GoldenCheckoutRefs = GoldenCheckoutRefs(prefix.appending("checkout"))
}

object GoldenFlagsSpec : GoldenFlagsRefs(FlagKeyPath.ROOT), FlagContainerSpec<GoldenFlags> {
    override val flagContainerDescription: String? = "Every flag type, for cross-platform golden fixtures"

    override val flagDefaults: GoldenFlags = object : GoldenFlags {
        override val checkout: GoldenCheckout
            get() = GoldenCheckoutSpec.flagDefaults
    }

    override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
            FlagSchemaNode.Flag(
                FlagDescriptor.of("boolFlag", "A boolean", "toggles.boolean", boolFlag.codec, flagDefaults.boolFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("intFlag", "An integer", "numbers.int", intFlag.codec, flagDefaults.intFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "doubleFlag",
                    "A double",
                    "numbers.double",
                    doubleFlag.codec,
                    flagDefaults.doubleFlag,
                ),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("floatFlag", "A float", null, floatFlag.codec, flagDefaults.floatFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("stringFlag", "A string", "strings.plain", stringFlag.codec, flagDefaults.stringFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("dataFlag", "Some bytes", null, dataFlag.codec, flagDefaults.dataFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("dateFlag", "A date", "dates.launch", dateFlag.codec, flagDefaults.dateFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("urlFlag", "A URL", "config.endpoint", urlFlag.codec, flagDefaults.urlFlag),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "useHTTPSOnly",
                    "Uses an acronym",
                    null,
                    useHTTPSOnly.codec,
                    flagDefaults.useHTTPSOnly,
                ),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("markets", "Markets", "config.markets", markets.codec, flagDefaults.markets),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("matrix", "Nested arrays", null, matrix.codec, flagDefaults.matrix),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("limits", "Limits by name", null, limits.codec, flagDefaults.limits),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("tier", "A string enum", "tier", tier.codec, flagDefaults.tier),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("level", "An int enum", null, level.codec, flagDefaults.level),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "endpoints",
                    "Endpoints",
                    "config.endpoints",
                    endpoints.codec,
                    flagDefaults.endpoints,
                ),
            ),
            FlagSchemaNode.Group(
                FlagGroupDescriptor(
                    "checkout",
                    FlagKeyPath(listOf("checkout")),
                    "Checkout",
                    GoldenCheckoutSpec.flagDescriptors.map { it.prefixed("checkout") },
                ),
            ),
        )
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): GoldenFlags = Impl(lookup, prefix)

    private class Impl(private val flagLookup: FlagLookup, flagPrefix: FlagKeyPath) : GoldenFlags {
        private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

        override val boolFlag: Boolean
            get() = flagLookup.read(flagKeys.boolFlag, GoldenFlagsSpec.boolFlag.codec) { super<GoldenFlags>.boolFlag }

        override val intFlag: Int
            get() = flagLookup.read(flagKeys.intFlag, GoldenFlagsSpec.intFlag.codec) { super<GoldenFlags>.intFlag }

        override val doubleFlag: Double
            get() = flagLookup.read(flagKeys.doubleFlag, GoldenFlagsSpec.doubleFlag.codec) {
                super<GoldenFlags>.doubleFlag
            }

        override val floatFlag: Float
            get() = flagLookup.read(flagKeys.floatFlag, GoldenFlagsSpec.floatFlag.codec) {
                super<GoldenFlags>.floatFlag
            }

        override val stringFlag: String
            get() = flagLookup.read(flagKeys.stringFlag, GoldenFlagsSpec.stringFlag.codec) {
                super<GoldenFlags>.stringFlag
            }

        override val dataFlag: ByteArray
            get() = flagLookup.read(flagKeys.dataFlag, GoldenFlagsSpec.dataFlag.codec) { super<GoldenFlags>.dataFlag }

        override val dateFlag: Instant
            get() = flagLookup.read(flagKeys.dateFlag, GoldenFlagsSpec.dateFlag.codec) { super<GoldenFlags>.dateFlag }

        override val urlFlag: URI
            get() = flagLookup.read(flagKeys.urlFlag, GoldenFlagsSpec.urlFlag.codec) { super<GoldenFlags>.urlFlag }

        override val useHTTPSOnly: Boolean
            get() = flagLookup.read(flagKeys.useHTTPSOnly, GoldenFlagsSpec.useHTTPSOnly.codec) {
                super<GoldenFlags>.useHTTPSOnly
            }

        override val markets: List<String>
            get() = flagLookup.read(flagKeys.markets, GoldenFlagsSpec.markets.codec) { super<GoldenFlags>.markets }

        override val matrix: List<List<Boolean>>
            get() = flagLookup.read(flagKeys.matrix, GoldenFlagsSpec.matrix.codec) { super<GoldenFlags>.matrix }

        override val limits: Map<String, Int>
            get() = flagLookup.read(flagKeys.limits, GoldenFlagsSpec.limits.codec) { super<GoldenFlags>.limits }

        override val tier: GoldenTier
            get() = flagLookup.read(flagKeys.tier, GoldenFlagsSpec.tier.codec) { super<GoldenFlags>.tier }

        override val level: GoldenLevel
            get() = flagLookup.read(flagKeys.level, GoldenFlagsSpec.level.codec) { super<GoldenFlags>.level }

        override val endpoints: FlagRecords<GoldenEndpoint>
            get() = flagLookup.read(flagKeys.endpoints, GoldenFlagsSpec.endpoints.codec) {
                super<GoldenFlags>.endpoints
            }

        override val checkout: GoldenCheckout =
            GoldenCheckoutSpec.createFlags(flagLookup, flagPrefix.appending("checkout"))
    }

    private class Keys(encoding: KeyEncoding, prefix: FlagKeyPath) {
        val boolFlag: FlagKey = encoding.key(prefix.appending("boolFlag"))

        val intFlag: FlagKey = encoding.key(prefix.appending("intFlag"))

        val doubleFlag: FlagKey = encoding.key(prefix.appending("doubleFlag"))

        val floatFlag: FlagKey = encoding.key(prefix.appending("floatFlag"))

        val stringFlag: FlagKey = encoding.key(prefix.appending("stringFlag"))

        val dataFlag: FlagKey = encoding.key(prefix.appending("dataFlag"))

        val dateFlag: FlagKey = encoding.key(prefix.appending("dateFlag"))

        val urlFlag: FlagKey = encoding.key(prefix.appending("urlFlag"))

        val useHTTPSOnly: FlagKey = encoding.key(prefix.appending("useHTTPSOnly"))

        val markets: FlagKey = encoding.key(prefix.appending("markets"))

        val matrix: FlagKey = encoding.key(prefix.appending("matrix"))

        val limits: FlagKey = encoding.key(prefix.appending("limits"))

        val tier: FlagKey = encoding.key(prefix.appending("tier"))

        val level: FlagKey = encoding.key(prefix.appending("level"))

        val endpoints: FlagKey = encoding.key(prefix.appending("endpoints"))
    }
}

open class GoldenCheckoutRefs(prefix: FlagKeyPath) {
    val applePay: FlagRef<Boolean> = FlagRef(
        prefix.appending("applePay"),
        "applePay",
        "Offer Apple Pay",
        "featureToggles.checkout.applePay",
        FlagCodecs.boolean,
    ) { GoldenCheckoutSpec.flagDefaults.applePay }

    val tier: FlagRef<GoldenTier> = FlagRef(
        prefix.appending("tier"),
        "tier",
        "Tier at checkout",
        null,
        FlagCodecs.stringEnum(GoldenTier.entries),
    ) { GoldenCheckoutSpec.flagDefaults.tier }

    val express: GoldenExpressRefs = GoldenExpressRefs(prefix.appending("express"))
}

object GoldenCheckoutSpec : GoldenCheckoutRefs(FlagKeyPath.ROOT), FlagContainerSpec<GoldenCheckout> {
    override val flagContainerDescription: String? = null

    override val flagDefaults: GoldenCheckout = object : GoldenCheckout {
        override val express: GoldenExpress
            get() = GoldenExpressSpec.flagDefaults
    }

    override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "applePay",
                    "Offer Apple Pay",
                    "featureToggles.checkout.applePay",
                    applePay.codec,
                    flagDefaults.applePay,
                ),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of("tier", "Tier at checkout", null, tier.codec, flagDefaults.tier),
            ),
            FlagSchemaNode.Group(
                FlagGroupDescriptor(
                    "express",
                    FlagKeyPath(listOf("express")),
                    "Express checkout",
                    GoldenExpressSpec.flagDescriptors.map { it.prefixed("express") },
                ),
            ),
        )
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): GoldenCheckout = Impl(lookup, prefix)

    private class Impl(private val flagLookup: FlagLookup, flagPrefix: FlagKeyPath) : GoldenCheckout {
        private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

        override val applePay: Boolean
            get() = flagLookup.read(flagKeys.applePay, GoldenCheckoutSpec.applePay.codec) {
                super<GoldenCheckout>.applePay
            }

        override val tier: GoldenTier
            get() = flagLookup.read(flagKeys.tier, GoldenCheckoutSpec.tier.codec) { super<GoldenCheckout>.tier }

        override val express: GoldenExpress =
            GoldenExpressSpec.createFlags(flagLookup, flagPrefix.appending("express"))
    }

    private class Keys(encoding: KeyEncoding, prefix: FlagKeyPath) {
        val applePay: FlagKey = encoding.key(prefix.appending("applePay"))

        val tier: FlagKey = encoding.key(prefix.appending("tier"))
    }
}

open class GoldenExpressRefs(prefix: FlagKeyPath) {
    val oneTap: FlagRef<Boolean> = FlagRef(
        prefix.appending("oneTap"),
        "oneTap",
        "One-tap purchase",
        null,
        FlagCodecs.boolean,
    ) { GoldenExpressSpec.flagDefaults.oneTap }

    val confirmationDelay: FlagRef<Double> = FlagRef(
        prefix.appending("confirmationDelay"),
        "confirmationDelay",
        "Seconds before confirming",
        null,
        FlagCodecs.double,
    ) { GoldenExpressSpec.flagDefaults.confirmationDelay }
}

object GoldenExpressSpec : GoldenExpressRefs(FlagKeyPath.ROOT), FlagContainerSpec<GoldenExpress> {
    override val flagContainerDescription: String? = null

    override val flagDefaults: GoldenExpress = object : GoldenExpress {}

    override val flagDescriptors: List<FlagSchemaNode> by lazy {
        listOf(
            FlagSchemaNode.Flag(
                FlagDescriptor.of("oneTap", "One-tap purchase", null, oneTap.codec, flagDefaults.oneTap),
            ),
            FlagSchemaNode.Flag(
                FlagDescriptor.of(
                    "confirmationDelay",
                    "Seconds before confirming",
                    null,
                    confirmationDelay.codec,
                    flagDefaults.confirmationDelay,
                ),
            ),
        )
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): GoldenExpress = Impl(lookup, prefix)

    private class Impl(private val flagLookup: FlagLookup, flagPrefix: FlagKeyPath) : GoldenExpress {
        private val flagKeys: Keys = Keys(flagLookup.keyEncoding, flagPrefix)

        override val oneTap: Boolean
            get() = flagLookup.read(flagKeys.oneTap, GoldenExpressSpec.oneTap.codec) { super<GoldenExpress>.oneTap }

        override val confirmationDelay: Double
            get() = flagLookup.read(flagKeys.confirmationDelay, GoldenExpressSpec.confirmationDelay.codec) {
                super<GoldenExpress>.confirmationDelay
            }
    }

    private class Keys(encoding: KeyEncoding, prefix: FlagKeyPath) {
        val oneTap: FlagKey = encoding.key(prefix.appending("oneTap"))

        val confirmationDelay: FlagKey = encoding.key(prefix.appending("confirmationDelay"))
    }
}
