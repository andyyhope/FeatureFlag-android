package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.flattened
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun aDefaultGetterIsEvaluatedOnceAndEveryReadPathAgrees() = runTest {
        // As Swift evaluates `default:` once, a non-constant default (Instant.now(), a counter) is read
        // once. Every path then reports that one value, at the root and with the container mounted as a group.
        val pole = FlagPole(MountedCountingFlagsSpec, sources = emptyList())
        val mounted = MountedCountingFlagsSpec.counting.counted
        val default = pole.flags.counting.counted
        val reads = DefaultReads.count

        assertEquals(default, pole.flags.counting.counted)
        assertEquals(default, pole[mounted])
        assertEquals(default, pole.flow(mounted).first())
        assertEquals(FlagValueBox.Int(default.toLong()), pole.resolution(mounted).box)
        assertEquals(FlagValueBox.Int(default.toLong()), pole.schema.flags.single().defaultValue)
        assertEquals(default, CountingFlagsSpec.createFlags(nothingStored).counted)
        assertEquals(default, CountingFlagsSpec.counted.defaultValue)
        assertEquals(FlagValueBox.Int(default.toLong()), CountingFlagsSpec.flagDescriptors.flattened().single().defaultValue)
        assertEquals(reads, DefaultReads.count, "no read ran the default getter again")

        val stored = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = FlagValueBox.Int(7)
        }
        assertEquals(7, CountingFlagsSpec.createFlags(stored).counted)
    }

    @Test fun aDefaultThatReadsAnotherFlagSeesThatFlagsCompiledDefaultOnEveryPath() = runTest {
        // `b`'s default getter reads `a`. It is evaluated against the compiled defaults, so a source that
        // sets `a` does not move `b`'s default, whichever way `b` is read.
        val source = SnapshotSource(values = mapOf(FlagKey("a") to FlagValueBox.Bool(true)))
        val pole = FlagPole(DerivedDefaultFlagsSpec, listOf(source))
        val b = DerivedDefaultFlagsSpec.b

        assertTrue(pole.flags.a)
        assertFalse(pole.flags.b, "pole.flags")
        assertFalse(pole[b], "pole[ref]")
        assertFalse(pole.flow(b).first(), "flow(ref)")
        assertEquals(FlagValueBox.Bool(false), pole.resolution(b).box, "resolution(ref)")
        assertEquals(FlagValueBox.Bool(false), pole.schema.flags.single { it.key == FlagKey("b") }.defaultValue, "schema")
    }
}
