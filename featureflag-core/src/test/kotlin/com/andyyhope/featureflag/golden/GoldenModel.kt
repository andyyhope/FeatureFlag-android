package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

// The Kotlin mirror of fixture-generator/Sources/GoldenModel/GoldenFlags.swift (records and enums).

enum class GoldenTier(override val flagRawValue: String) : FlagStringEnum {
    FREE("free"),
    PRO("pro"),
    ENTERPRISE("enterprise"),
}

enum class GoldenLevel(override val flagRawValue: Long) : FlagIntEnum { ONE(1), TWO(2), THREE(3) }

data class GoldenEndpoint(
    val name: String,
    val url: URI,
    val weight: Int = 1,
    val note: String? = null,
    val kind: GoldenTier = GoldenTier.FREE,
    val targets: FlagRecords<GoldenTarget> = FlagRecords(),
)

data class GoldenTarget(val host: String, val port: Int)
