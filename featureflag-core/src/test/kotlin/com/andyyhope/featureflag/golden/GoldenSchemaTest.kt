package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The golden container's schema, byte for byte against what FeatureFlag 0.12.0 wrote. */
class GoldenSchemaTest {
    private val generatedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun schema(encoding: KeyEncoding) = FlagSchema.of(GoldenFlagsSpec, encoding, "Golden", generatedAt)

    @Test fun theKebabcaseSchemaIsByteIdenticalToApples() = assertEquals(
        Fixtures.text("schema/golden-kebab.flag-schema.json"),
        schema(KeyEncoding.Kebabcase).jsonBytes().decodeToString(),
    )

    @Test fun theSnakecaseSchemaIsByteIdenticalToApples() = assertEquals(
        Fixtures.text("schema/golden-snake.flag-schema.json"),
        schema(KeyEncoding.Snakecase).jsonBytes().decodeToString(),
    )

    @Test fun applesDocumentReadsBackAsTheSameSchema() {
        val kebab = FlagSchema.parse(Fixtures.bytes("schema/golden-kebab.flag-schema.json"))
        val snake = FlagSchema.parse(Fixtures.bytes("schema/golden-snake.flag-schema.json"))
        assertEquals(schema(KeyEncoding.Kebabcase), kebab)
        assertEquals(schema(KeyEncoding.Snakecase), snake)
    }
}
