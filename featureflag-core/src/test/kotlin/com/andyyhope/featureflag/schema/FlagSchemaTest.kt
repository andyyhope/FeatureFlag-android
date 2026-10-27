package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagSchemaTest {
    private val exotic = specOf(
        flag("tags", "Tags", FlagCodecs.list(FlagCodecs.string), listOf("a")),
        flag("launchedAt", "Launched at", FlagCodecs.instant, Instant.EPOCH),
        flag("limits", "Limits", FlagCodecs.map(FlagCodecs.int), emptyMap()),
    )

    private fun text(schema: FlagSchema) = schema.jsonBytes().toString(Charsets.UTF_8)

    @Test fun schemaListsEveryFlagWithResolvedKeys() {
        assertEquals(
            listOf("new-onboarding", "max-items", "checkout.apple-pay", "checkout.express.one-tap", "checkout.tier"),
            FlagSchema.of(demoSpec).flags.map { it.key.rawValue },
        )
    }

    @Test fun schemaUsesTheGivenKeyEncoding() {
        val schema = FlagSchema.of(demoSpec, keyEncoding = KeyEncoding.Snakecase)
        assertTrue(schema.flags.any { it.key == FlagKey("checkout.apple_pay") })
    }

    @Test fun schemaCarriesEditorMetadata() {
        val tier = FlagSchema.of(demoSpec).flags.single { it.key == FlagKey("checkout.tier") }
        assertEquals("Tier", tier.description)
        assertEquals(FlagValueType.String, tier.valueType)
        assertEquals(FlagValueBox.String("free"), tier.defaultValue)
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), tier.cases)
        assertEquals(listOf("checkout", "tier"), tier.propertyPath)
    }

    @Test fun schemaCarriesRemoteKeysWhereDeclared() {
        val flags = FlagSchema.of(demoSpec).flags
        assertEquals("featureToggles.onboarding.v2", flags.single { it.key == FlagKey("new-onboarding") }.remoteKey)
        assertNull(flags.single { it.key == FlagKey("max-items") }.remoteKey)
    }

    @Test fun schemaDescribesGroupsSoTheEditorCanLabelSections() {
        assertEquals(
            listOf(
                FlagSchema.Group(listOf("checkout"), "Checkout"),
                FlagSchema.Group(listOf("checkout", "express"), "Express"),
            ),
            FlagSchema.of(demoSpec).groups,
        )
    }

    @Test fun schemaCarriesTheContainerDescription() {
        val described = specOf(
            flag("newOnboarding", "Onboarding", FlagCodecs.boolean, false),
            description = "Everything the checkout team can turn on",
        )
        assertEquals("Everything the checkout team can turn on", FlagSchema.of(described).description)
        val reread = FlagSchema.parse(FlagSchema.of(described).jsonBytes())
        assertEquals("Everything the checkout team can turn on", reread.description)
        assertNull(FlagSchema.parse(FlagSchema.of(demoSpec).jsonBytes()).description)
        assertEquals(listOf(FlagKey("new-onboarding")), FlagSchema.of(described).flags.map { it.key })
    }

    @Test fun schemaRoundTripsThroughJson() {
        val generatedAt = Instant.ofEpochSecond(1_767_225_600)
        val schema = FlagSchema.of(demoSpec, applicationName = "Demo", generatedAt = generatedAt)
        val decoded = FlagSchema.parse(schema.jsonBytes())
        assertEquals(schema, decoded)
        assertEquals("Demo", decoded.applicationName)
    }

    @Test fun collectionAndDateTypesSurviveTheRoundTrip() {
        val schema = FlagSchema.of(exotic)
        val decoded = FlagSchema.parse(schema.jsonBytes())
        assertEquals(schema.flags, decoded.flags)
        val tags = decoded.flags.single { it.key == FlagKey("tags") }
        assertEquals(FlagValueType.Array(FlagValueType.String), tags.valueType)
        assertEquals(FlagValueBox.Array(listOf(FlagValueBox.String("a"))), tags.defaultValue)
        assertEquals(FlagValueType.Date, decoded.flags.single { it.key == FlagKey("launched-at") }.valueType)
    }

    @Test fun absentOptionalsAreOmittedNotWrittenAsNull() {
        val json = text(FlagSchema.of(demoSpec, generatedAt = Instant.EPOCH))
        assertFalse("applicationName" in json, json)
        assertFalse("\"description\" : null" in json, json)
        assertFalse("null" in json, json)
        assertTrue("\"formatVersion\" : 1" in json, json)
        assertTrue("\"generatedAt\" : \"1970-01-01T00:00:00Z\"" in json, json)
    }

    @Test fun aNonFiniteDefaultIsRefusedNamingTheFlag() {
        val schema = FlagSchema.of(specOf(flag("ratio", "Ratio", FlagCodecs.double, Double.NaN)))
        val error = assertFailsWith<FlagSerializationException.NonFiniteNumber> { schema.jsonBytes() }
        assertEquals(FlagKey("ratio"), error.key)
    }

    @Test fun decodingRejectsAnUnknownFormatVersion() {
        val json = text(FlagSchema.of(demoSpec)).replace("\"formatVersion\" : 1", "\"formatVersion\" : 99")
        val error = assertFailsWith<FlagSchemaException.UnsupportedFormatVersion> {
            FlagSchema.parse(json.toByteArray())
        }
        assertEquals(99L, error.version)
        assertEquals(
            "The schema is format version 99; this build understands 1. Update whichever app is older.",
            error.message,
        )
    }

    @Test fun aLoneSurrogateIsWrittenAsTheReplacementCharacterLikeTheExport() {
        val replacement = byteArrayOf(0x61, 0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(), 0x62)
        fun containsReplacement(bytes: ByteArray) =
            bytes.toList().windowed(replacement.size).any { it == replacement.toList() }

        val schema = FlagSchema.of(specOf(flag("one", "a\uD800b", FlagCodecs.boolean, false)))
        assertTrue(containsReplacement(schema.jsonBytes()))
        val payload = FlagPayload(mapOf(FlagKey("one") to FlagValueBox.String("a\uD800b")))
        assertTrue(containsReplacement(payload.jsonBytes()))
    }
}
