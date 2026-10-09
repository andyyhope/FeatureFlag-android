package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagStringEnum
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal enum class HelperTier(override val flagRawValue: String) : FlagStringEnum { Free("free"), Pro("pro") }

internal interface HelperFlags {
    val newOnboarding: Boolean
    val pageSize: Int
    val checkoutTier: HelperTier
}

/**
 * Written by hand because this module's tests do not run the KSP processor. An audit reads
 * only the descriptors, so the flags this creates are just the defaults.
 */
internal object HelperFlagsSpec : FlagContainerSpec<HelperFlags> {
    override val flagContainerDescription: String? = null

    override val flagDescriptors: List<FlagSchemaNode> = listOf(
        FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "Onboarding", "featureToggles.onboarding", FlagCodecs.boolean, false)),
        FlagSchemaNode.Flag(FlagDescriptor.of("pageSize", "Page size", "config.pageSize", FlagCodecs.int, 10)),
        FlagSchemaNode.Flag(
            FlagDescriptor.of("checkoutTier", "Tier", "config.tier", FlagCodecs.stringEnum(HelperTier.entries), HelperTier.Free),
        ),
    )

    override val flagDefaults: HelperFlags = object : HelperFlags {
        override val newOnboarding = false
        override val pageSize = 10
        override val checkoutTier = HelperTier.Free
    }

    override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): HelperFlags = flagDefaults
}

class AssertFlagsFullyMappedTest {

    private val complete = """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" } }"""

    private val withMetadata =
        """{ "featureToggles": { "onboarding": true }, "config": { "pageSize": 25, "tier": "pro" }, "meta": { "version": 7 } }"""

    @Test
    fun aCompletePayloadPasses() {
        assertFlagsFullyMapped(HelperFlagsSpec, complete.toByteArray())
    }

    @Test
    fun strictWithIgnoredMetadataPasses() {
        assertFlagsFullyMapped(HelperFlagsSpec, withMetadata.toByteArray(), strict = true, ignoring = listOf("meta"))
    }

    @Test
    fun unconsumedMetadataPassesUnlessStrict() {
        assertFlagsFullyMapped(HelperFlagsSpec, withMetadata.toByteArray())
        val error = assertFailsWith<AssertionError> {
            assertFlagsFullyMapped(HelperFlagsSpec, withMetadata.toByteArray(), strict = true)
        }
        assertEquals(
            "\nFlag mapping audit — incomplete.\n" +
                "  unconsumed (1) — no flag reads these:\n" +
                "    • meta.version\n" +
                "  3 of 3 remotely-overridable flags applied.",
            error.message,
        )
    }

    @Test
    fun anAbsentFlagFailsWithTheWholeReport() {
        val payload = """{ "featureToggles": { "onboarding": true }, "config": { "page_size": 25, "tier": "pro" } }""".toByteArray()
        val error = assertFailsWith<AssertionError> { assertFlagsFullyMapped(HelperFlagsSpec, payload) }
        assertEquals("\n" + FlagMappingAudit(HelperFlagsSpec, payload), error.message)
        assertEquals(
            "\nFlag mapping audit — incomplete.\n" +
                "  absent (1) — a flag declares this path, the payload has nothing there:\n" +
                "    • page-size\n" +
                "  unconsumed (1) — usually fine, often backend metadata — no flag reads these:\n" +
                "    • config.page_size\n" +
                "  2 of 3 remotely-overridable flags applied.",
            error.message,
        )
    }

    @Test
    fun anUnreadablePayloadSaysSo() {
        val error =
            assertFailsWith<AssertionError> { assertFlagsFullyMapped(HelperFlagsSpec, "{ not json".toByteArray()) }
        assertEquals("The payload could not be read: The remote payload could not be read: not valid JSON.", error.message)
    }
}
