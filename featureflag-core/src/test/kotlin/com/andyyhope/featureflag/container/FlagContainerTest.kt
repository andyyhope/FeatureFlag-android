package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.golden.GoldenCheckoutSpec
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports Swift's FlagContainerTests and FlagContainerMacroTests onto the golden container. Task B2
 * runs these against a hand-written GoldenFlagsSpec; from Task B3 the processor writes it, and
 * every assertion here must hold for both.
 */
class FlagContainerTest {
    private class StubLookup(
        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
        private val boxes: Map<String, FlagValueBox> = emptyMap(),
    ) : FlagLookup {
        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = boxes[key.rawValue]
    }

    private fun flags(vararg boxes: Pair<String, FlagValueBox>) =
        GoldenFlagsSpec.createFlags(StubLookup(boxes = boxes.toMap()))

    // Reading values

    @Test fun aFlagFallsBackToItsDefaultWhenNothingIsStored() {
        val flags = flags()
        assertFalse(flags.boolFlag)
        assertEquals(42, flags.intFlag)
        assertEquals(listOf("AU", "NZ"), flags.markets)
        assertContentEquals(byteArrayOf(0, 1, 2, 254.toByte(), 255.toByte()), flags.dataFlag)
        assertEquals(URI("https://prod.example.com"), flags.endpoints.single().url)
    }

    @Test fun aFlagReadsAStoredValue() {
        assertTrue(flags("bool-flag" to FlagValueBox.Bool(true)).boolFlag)
        assertEquals(GoldenTier.ENTERPRISE, flags("tier" to FlagValueBox.String("enterprise")).tier)
    }

    @Test fun aFlagFallsBackToItsDefaultWhenTheStoredValueIsTheWrongType() {
        assertEquals(42, flags("int-flag" to FlagValueBox.String("nonsense")).intFlag)
    }

    @Test fun aFlagFallsBackToItsDefaultWhenTheStoredValueWillNotDecode() {
        assertEquals(42, flags("int-flag" to FlagValueBox.Int(Long.MAX_VALUE)).intFlag)
        assertEquals(GoldenTier.FREE, flags("tier" to FlagValueBox.String("platinum")).tier)
    }

    @Test fun theDefaultsInstanceReadsEveryCompiledDefault() {
        val defaults = GoldenFlagsSpec.flagDefaults
        assertEquals(0.1, defaults.doubleFlag)
        assertEquals(GoldenTier.PRO, defaults.checkout.tier)
        assertEquals(3.0, defaults.checkout.express.confirmationDelay)
    }

    // Nesting

    @Test fun aNestedGroupNamespacesItsKeys() {
        assertFalse(flags("checkout.apple-pay" to FlagValueBox.Bool(false)).checkout.applePay)
    }

    @Test fun aDeeplyNestedGroupNamespacesItsKeys() {
        assertTrue(flags("checkout.express.one-tap" to FlagValueBox.Bool(true)).checkout.express.oneTap)
    }

    @Test fun aChildValueIsIndependentOfItsParent() {
        val flags = flags(
            "checkout.express.one-tap" to FlagValueBox.Bool(true),
            "checkout.apple-pay" to FlagValueBox.Bool(false),
        )
        assertTrue(flags.checkout.express.oneTap)
        assertFalse(flags.checkout.applePay)
    }

    @Test fun aPrefixRootsEveryKeyBeneathIt() {
        val lookup = StubLookup(boxes = mapOf("mounted.checkout.express.one-tap" to FlagValueBox.Bool(true)))
        assertTrue(GoldenFlagsSpec.createFlags(lookup, FlagKeyPath(listOf("mounted"))).checkout.express.oneTap)
    }

    // Key encoding

    @Test fun keyEncodingComesFromTheLookup() {
        val lookup = StubLookup(KeyEncoding.Snakecase, mapOf("checkout.apple_pay" to FlagValueBox.Bool(false)))
        assertFalse(GoldenFlagsSpec.createFlags(lookup).checkout.applePay)
    }

    // Refs, the counterpart of Swift's projected value

    @Test fun refsExposeTheResolvedKey() {
        assertEquals(FlagKey("bool-flag"), GoldenFlagsSpec.boolFlag.key(KeyEncoding.Kebabcase))
        assertEquals(FlagKey("use-https-only"), GoldenFlagsSpec.useHTTPSOnly.key(KeyEncoding.Kebabcase))
        assertEquals(
            FlagKey("checkout.express.one-tap"),
            GoldenFlagsSpec.checkout.express.oneTap.key(KeyEncoding.Kebabcase),
        )
    }

    @Test fun refsExposeDeclarationMetadata() {
        val ref = GoldenFlagsSpec.boolFlag
        assertEquals("boolFlag", ref.propertyName)
        assertEquals("A boolean", ref.description)
        assertEquals(false, ref.defaultValue)
        assertEquals("toggles.boolean", ref.remoteKey)
        assertEquals(GoldenTier.PRO, GoldenFlagsSpec.checkout.tier.defaultValue)
    }

    @Test fun refsReportNoRemoteKeyWhenUndeclared() {
        assertNull(GoldenFlagsSpec.floatFlag.remoteKey)
    }

    // Descriptors

    @Test fun descriptorsListFlagsInDeclarationOrder() {
        assertEquals(
            listOf(
                "boolFlag", "intFlag", "doubleFlag", "floatFlag", "stringFlag", "dataFlag", "dateFlag", "urlFlag",
                "useHTTPSOnly", "markets", "matrix", "limits", "tier", "level", "endpoints", "checkout",
            ),
            GoldenFlagsSpec.flagDescriptors.map { it.propertyName },
        )
    }

    @Test fun aFlagDescriptorCarriesEverythingAnEditorNeeds() {
        val descriptor = assertIs<FlagSchemaNode.Flag>(GoldenFlagsSpec.flagDescriptors[0]).descriptor
        assertEquals(FlagKeyPath(listOf("boolFlag")), descriptor.keyPath)
        assertEquals("A boolean", descriptor.description)
        assertEquals(FlagValueType.Bool, descriptor.valueType)
        assertEquals(FlagValueBox.Bool(false), descriptor.defaultValue)
        assertEquals("toggles.boolean", descriptor.remoteKey)
        assertNull(descriptor.cases)
    }

    @Test fun aGroupDescriptorRerootsItsChildrenThroughEveryLevel() {
        val checkout = assertIs<FlagSchemaNode.Group>(GoldenFlagsSpec.flagDescriptors.last()).group
        assertEquals("Checkout", checkout.description)
        val express = assertIs<FlagSchemaNode.Group>(checkout.children[2]).group
        val oneTap = assertIs<FlagSchemaNode.Flag>(express.children[0]).descriptor
        assertEquals(FlagKeyPath(listOf("checkout", "express", "oneTap")), oneTap.keyPath)
    }

    @Test fun anEnumDescriptorListsItsCases() {
        val checkout = assertIs<FlagSchemaNode.Group>(GoldenFlagsSpec.flagDescriptors.last()).group
        val tier = assertIs<FlagSchemaNode.Flag>(checkout.children[1]).descriptor
        assertEquals(FlagValueType.String, tier.valueType)
        assertEquals(listOf("free", "pro", "enterprise").map { FlagValueBox.String(it) }, tier.cases)
    }

    @Test fun aContainerDescribesItselfOnlyWhenItSaysSomething() {
        assertEquals("Every flag type, for cross-platform golden fixtures", GoldenFlagsSpec.flagContainerDescription)
        assertNull(GoldenCheckoutSpec.flagContainerDescription)
    }
}
