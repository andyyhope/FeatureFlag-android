package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.flattened
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shapes of container a real codebase produces sooner or later, generated rather than hand-written. */
class GeneratedContainerTest {
    private val nothingStored = object : FlagLookup {
        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null
    }

    @Test fun aContainerWithNoFlagsAtAllWorks() {
        NoFlagsSpec.createFlags(nothingStored)
        assertTrue(NoFlagsSpec.flagDescriptors.isEmpty())
    }

    @Test fun aContainerOfNothingButGroupsWorks() {
        assertEquals(1, GroupsOnlyFlagsSpec.flagDescriptors.size)
        assertEquals(listOf("oneTap"), GroupsOnlyFlagsSpec.flagDescriptors.flattened().map { it.propertyName })
        assertEquals(FlagKey("express.one-tap"), GroupsOnlyFlagsSpec.express.oneTap.key(KeyEncoding.Kebabcase))
    }

    @Test fun deeplyNestedGroupsKeepNesting() {
        assertEquals(FlagKey("a.b.c.deep"), DeepFlagsSpec.a.b.c.deep.key(KeyEncoding.Kebabcase))
        assertEquals(
            listOf(listOf("a", "b", "c", "deep")),
            DeepFlagsSpec.flagDescriptors.flattened().map { it.keyPath.propertyNames },
        )
        assertEquals(false, DeepFlagsSpec.createFlags(nothingStored).a.b.c.deep)
    }

    @Test fun theSameGroupTypeCanBeMountedTwice() {
        assertEquals(FlagKey("first.one-tap"), TwiceFlagsSpec.first.oneTap.key(KeyEncoding.Kebabcase))
        assertEquals(FlagKey("second.one-tap"), TwiceFlagsSpec.second.oneTap.key(KeyEncoding.Kebabcase))
        assertEquals(
            listOf(FlagKey("first.one-tap"), FlagKey("second.one-tap")),
            TwiceFlagsSpec.flagDescriptors.flattened().map { KeyEncoding.Kebabcase.key(it.keyPath) },
        )
    }

    @Test fun complexDefaultExpressionsAreCarriedThrough() {
        val flags = ComplexDefaultsFlagsSpec.createFlags(nothingStored)
        assertEquals(6, flags.computedDefault)
        assertEquals(listOf("a", "b"), flags.listDefault)
        assertEquals(mapOf("x" to 1), flags.mapDefault)
        assertEquals(listOf(listOf("a")), flags.nestedDefault)
        assertEquals(listOf("z"), flags.aliased)
    }

    @Test fun complexDefaultsAppearInDescriptors() {
        val descriptors = ComplexDefaultsFlagsSpec.flagDescriptors.flattened()
        assertEquals(FlagValueBox.Int(6), descriptors.single { it.propertyName == "computedDefault" }.defaultValue)
        assertEquals(
            FlagValueType.Array(FlagValueType.Array(FlagValueType.String)),
            descriptors.single { it.propertyName == "nestedDefault" }.valueType,
        )
        val aliased = descriptors.single { it.propertyName == "aliased" }
        assertEquals(FlagValueType.Array(FlagValueType.String), aliased.valueType)
    }

    @Test fun fullyQualifiedTypesAndAnnotationsWork() {
        assertEquals(FlagKey("stamp"), QualifiedFlagsSpec.stamp.key(KeyEncoding.Kebabcase))
        assertEquals(FlagValueType.Date, QualifiedFlagsSpec.flagDescriptors.flattened().single().valueType)
        assertEquals(Instant.EPOCH, QualifiedFlagsSpec.createFlags(nothingStored).stamp)
    }

    @Test fun nonFlagMembersAreLeftEntirelyAlone() {
        val flags = MixedFlagsSpec.createFlags(nothingStored)
        assertEquals(5, flags.constant)
        assertEquals(10, flags.computed)
        assertEquals(15, flags.method())
        assertEquals("static", MixedFlags.STATIC_VALUE)
        assertEquals(1, MixedFlagsSpec.flagDescriptors.size)
    }

    @Test fun aContainerPublishesItsDescriptionAndOneWithoutSaysNothing() {
        assertEquals("Everything the checkout team can turn on", DescribedFlagsSpec.flagContainerDescription)
        assertEquals("Everything the checkout team can turn on", FlagSchema.of(DescribedFlagsSpec).description)
        assertNull(OneTapFlagsSpec.flagContainerDescription)
    }

    @Test fun aPrefixRootsEveryKeyBeneathIt() {
        val lookup = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? =
                FlagValueBox.Bool(true).takeIf { key == FlagKey("mounted.express.one-tap") }
        }
        assertTrue(GroupsOnlyFlagsSpec.createFlags(lookup, FlagKeyPath(listOf("mounted"))).express.oneTap)
    }

    @Test fun aDefaultGetterRunsOnEveryReadThatFallsBackToIt() {
        // Unlike Swift, where `default:` is evaluated once per container, a Kotlin default is a getter:
        // a non-constant one (Instant.now(), a counter) gives a fresh value on every fallback read.
        DefaultReads.count = 0
        val flags = CountingFlagsSpec.createFlags(nothingStored)
        assertEquals(1, flags.counted)
        assertEquals(2, flags.counted)
        val stored = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = FlagValueBox.Int(7)
        }
        assertEquals(7, CountingFlagsSpec.createFlags(stored).counted)
        assertEquals(2, DefaultReads.count)
    }
}
