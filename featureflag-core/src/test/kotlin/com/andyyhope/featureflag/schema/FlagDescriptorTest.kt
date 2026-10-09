package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlagDescriptorTest {
    @Test fun ofCarriesEverythingAnEditorNeeds() {
        val descriptor = FlagDescriptor.of(
            propertyName = "newOnboarding",
            description = "New onboarding",
            remoteKey = "featureToggles.onboarding.v2",
            codec = FlagCodecs.boolean,
            default = false,
        )
        assertEquals("newOnboarding", descriptor.propertyName)
        assertEquals(FlagKeyPath(listOf("newOnboarding")), descriptor.keyPath)
        assertEquals("New onboarding", descriptor.description)
        assertEquals(FlagValueType.Bool, descriptor.valueType)
        assertEquals(FlagValueBox.Bool(false), descriptor.defaultValue)
        assertEquals("featureToggles.onboarding.v2", descriptor.remoteKey)
        assertNull(descriptor.cases)
        assertNull(descriptor.recordShape)
    }

    @Test fun enumDescriptorListsItsCases() {
        val descriptor = FlagDescriptor.of("tier", "Tier", null, FlagCodecs.stringEnum(DemoTier.entries), DemoTier.FREE)
        assertEquals(FlagValueType.String, descriptor.valueType)
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), descriptor.cases)
    }

    @Test fun descriptorsKeepDeclarationOrder() {
        assertEquals(listOf("newOnboarding", "maxItems", "checkout"), demoSpec.flagDescriptors.map { it.propertyName })
    }

    @Test fun groupRerootsItsChildrenThroughEveryLevel() {
        val checkout = assertIs<FlagSchemaNode.Group>(demoSpec.flagDescriptors[2]).group
        assertEquals(FlagKeyPath(listOf("checkout")), checkout.keyPath)
        val applePay = assertIs<FlagSchemaNode.Flag>(checkout.children[0]).descriptor
        assertEquals(FlagKeyPath(listOf("checkout", "applePay")), applePay.keyPath)
        val express = assertIs<FlagSchemaNode.Group>(checkout.children[1]).group
        val oneTap = assertIs<FlagSchemaNode.Flag>(express.children[0]).descriptor
        assertEquals(FlagKeyPath(listOf("checkout", "express", "oneTap")), oneTap.keyPath)
    }

    @Test fun prefixingANodeRerootsItAndKeepsItsPropertyName() {
        val express = group("express", "Express", flag("oneTap", "One tap", FlagCodecs.boolean, false))
        val node = express.prefixed("checkout")
        assertEquals("express", node.propertyName)
        assertEquals(FlagKeyPath(listOf("checkout", "express")), node.keyPath)
    }

    @Test fun flattenedIsDepthFirstWithCompletePaths() {
        assertEquals(
            listOf(
                listOf("newOnboarding"), listOf("maxItems"), listOf("checkout", "applePay"),
                listOf("checkout", "express", "oneTap"), listOf("checkout", "tier"),
            ),
            demoSpec.flagDescriptors.flattened().map { it.keyPath.propertyNames },
        )
    }

    @Test fun aGroupMountedTwiceReportsTwoKeys() {
        val oneTap = flag("oneTap", "One tap", FlagCodecs.boolean, false)
        val spec = specOf(group("first", "First", oneTap), group("second", "Second", oneTap))
        assertEquals(
            listOf(listOf("first", "oneTap"), listOf("second", "oneTap")),
            spec.flagDescriptors.flattened().map { it.keyPath.propertyNames },
        )
    }
}
