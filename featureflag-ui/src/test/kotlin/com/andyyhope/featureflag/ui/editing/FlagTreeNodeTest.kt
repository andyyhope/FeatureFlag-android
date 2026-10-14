package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ports Swift's FlagTreeNodeTests.
class FlagTreeNodeTest {

    private val tree = FlagTreeNode.of(CompanionSchemas.tree)

    @Test
    fun rootHoldsOnlyItsOwnFlags() {
        assertEquals(listOf("top", "second"), tree.flags.keys())
    }

    @Test
    fun rootListsItsDirectGroupsOnly() {
        assertEquals(listOf("Checkout"), tree.groups.map { it.title })
    }

    @Test
    fun aGroupHoldsItsOwnFlagsAndItsOwnSubgroups() {
        val checkout = tree.groups.first()

        assertEquals(listOf("checkout"), checkout.path)
        assertEquals(listOf("checkout.apple-pay"), checkout.flags.keys())
        assertEquals(listOf("Express"), checkout.groups.map { it.title })
    }

    @Test
    fun nestingGoesAsDeepAsTheContainer() {
        val express = tree.groups.first().groups.first()
        assertEquals(listOf("checkout", "express"), express.path)
        assertEquals(listOf("checkout.express.one-tap"), express.flags.keys())

        val deeper = express.groups.first()
        assertEquals(listOf("checkout", "express", "confirmation"), deeper.path)
        assertEquals(listOf("checkout.express.confirmation.delay"), deeper.flags.keys())
        assertTrue(deeper.groups.isEmpty())
    }

    @Test
    fun everyFlagAppearsExactlyOnceInTheWholeTree() {
        val all = tree.allFlags.keys()

        assertEquals(all.size, all.toSet().size, "a flag is in two places")
        assertEquals(CompanionSchemas.tree.flags.keys().toSet(), all.toSet())
    }

    @Test
    fun declarationOrderSurvives() {
        assertEquals(
            listOf("top", "second", "checkout.apple-pay", "checkout.express.one-tap", "checkout.express.confirmation.delay"),
            tree.allFlags.keys(),
        )
    }

    @Test
    fun identityIsThePath() {
        assertEquals("", tree.id)
        assertEquals("checkout", tree.groups.first().id)
        assertEquals("checkout.express", tree.groups.first().groups.first().id)
    }

    @Test
    fun theRootHasNoTitle() {
        assertEquals(null, tree.title)
        assertEquals(emptyList(), tree.path)
    }

    // Degenerate shapes

    @Test
    fun anEmptySchemaProducesAnEmptyRoot() {
        val empty = FlagTreeNode.of(FlagSchema(flags = emptyList()))

        assertTrue(empty.isEmpty)
        assertTrue(empty.flags.isEmpty())
        assertTrue(empty.groups.isEmpty())
    }

    @Test
    fun aGroupWithNoFlagsOfItsOwnStillCarriesItsChildren() {
        // "Checkout" holding only "Express" must not vanish, or its contents become unreachable.
        val node = FlagTreeNode.of(oneTapUnderTwoGroups)

        assertEquals(listOf("Checkout"), node.groups.map { it.title })
        assertTrue(node.groups[0].flags.isEmpty())
        assertEquals(listOf("Express"), node.groups[0].groups.map { it.title })
        assertEquals(1, node.allFlags.size)
        assertFalse(node.isEmpty)
    }

    @Test
    fun aGroupDeclaredWithNoFlagsAnywhereIsEmpty() {
        val schema = FlagSchema(flags = emptyList(), groups = listOf(FlagSchema.Group(listOf("hollow"), "Hollow")))

        assertTrue(FlagTreeNode.of(schema).isEmpty)
    }

    // Sections

    @Test
    fun aSectionIsIdentifiedByItsPath() {
        assertEquals("", FlagSection(emptyList(), null, "", emptyList()).id)
        assertEquals("checkout.express", FlagSection(listOf("checkout", "express"), "Express", "Checkout › Express", emptyList()).id)
    }

    private val oneTapUnderTwoGroups = FlagSchema(
        flags = listOf(
            FlagSchema.Entry(
                key = FlagKey("checkout.express.one-tap"),
                propertyPath = listOf("checkout", "express", "oneTap"),
                description = "",
                valueType = FlagValueType.Bool,
                defaultValue = FlagValueBox.Bool(false),
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
        ),
    )

    private fun List<FlagSchema.Entry>.keys(): List<String> = map { it.key.rawValue }
}
