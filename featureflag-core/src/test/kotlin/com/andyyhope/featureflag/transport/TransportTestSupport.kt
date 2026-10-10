package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.KeyEncoding
import java.io.File
import java.time.Instant

/** The GoldenFlags schema, exactly as the fixture generator builds it. */
internal val goldenSchema: FlagSchema = FlagSchema.of(
    GoldenFlagsSpec,
    keyEncoding = KeyEncoding.Kebabcase,
    applicationName = "Golden",
    generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

/** `fixtures/` at the repository root, as Gradle hands it to the test JVM. */
internal fun fixturesDirectory(): File {
    val path = checkNotNull(System.getProperty("featureflag.fixtures")) {
        "featureflag.fixtures is not set; run the tests through Gradle"
    }
    return File(path)
}

/** Bytes no compressor can shrink, from the generator the Swift tests use, so both sides see the same input. */
internal fun incompressibleBytes(count: Int, seed: ULong): ByteArray {
    var state = seed
    return ByteArray(count) {
        state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
        (state shr 33).toByte()
    }
}
