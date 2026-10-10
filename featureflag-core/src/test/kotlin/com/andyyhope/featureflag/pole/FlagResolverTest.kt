package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlagResolverTest {

    private val maxItems = FlagKey("max-items")

    @Test
    fun anEmptySourceStackAlwaysReportsTheDefault() {
        val resolution = FlagResolver(emptyList(), KeyEncoding.Kebabcase).resolution(FlagKey("anything"), FlagValueType.Bool)

        assertTrue(resolution.isDefault)
        assertNull(resolution.sourceName)
        assertNull(resolution.box)
    }

    @Test
    fun resolutionWalksPastEverySourceThatCannotAnswer() {
        val third = SnapshotSource("third", mapOf(maxItems to FlagValueBox.Int(3)))
        val sources = listOf(SnapshotSource("first"), SnapshotSource("second"), third)
        val resolver = FlagResolver(sources, KeyEncoding.Kebabcase)

        assertEquals(FlagResolution(maxItems, "third", FlagValueBox.Int(3)), resolver.resolution(maxItems, FlagValueType.Int))
    }

    @Test
    fun aStoredValueOfTheWrongTypeIsSkippedNotSurfaced() {
        val local = SnapshotSource("local", mapOf(maxItems to FlagValueBox.String("nonsense")))
        val remote = SnapshotSource("remote", mapOf(maxItems to FlagValueBox.Int(3)))
        val resolver = FlagResolver(listOf(local, remote), KeyEncoding.Kebabcase)

        assertEquals("remote", resolver.resolution(maxItems, FlagValueType.Int).sourceName)
        assertEquals(FlagValueBox.Int(3), resolver.box(maxItems, FlagValueType.Int))
    }

    @Test
    fun anEmptyCollectionMatchesAnyElementType() {
        val markets = FlagKey("markets")
        val local = SnapshotSource("local", mapOf(markets to FlagValueBox.Array(emptyList())))

        val resolution = FlagResolver(listOf(local), KeyEncoding.Kebabcase).resolution(markets, FlagValueType.Array(FlagValueType.String))

        assertEquals("local", resolution.sourceName)
    }

    @Test
    fun writesGoToTheFirstMutableSourceAndCurrentOverrideReadsOnlyIt() {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom", mapOf(maxItems to FlagValueBox.Int(3)))
        val resolver = FlagResolver(listOf(ReadOnlySource("remote"), top, bottom), KeyEncoding.Kebabcase)

        assertNull(resolver.currentOverride(maxItems, FlagValueType.Int))

        // Whatever the top source holds, even of the wrong type: undoing an import needs exactly that.
        resolver.setOverride(maxItems, FlagValueBox.String("raw"))

        assertEquals(FlagValueBox.String("raw"), resolver.currentOverride(maxItems, FlagValueType.Int))
        assertEquals(FlagValueBox.String("raw"), top.values[maxItems])
    }

    @Test
    fun withNothingWritableWritesThrowAndCurrentOverrideIsNull() {
        val remote = ReadOnlySource("remote", mapOf(maxItems to FlagValueBox.Int(3)))
        val resolver = FlagResolver(listOf(remote), KeyEncoding.Kebabcase)

        assertFailsWith<FlagException.NoMutableSource> { resolver.setOverride(maxItems, FlagValueBox.Int(1)) }
        assertFailsWith<FlagException.NoMutableSource> { resolver.setOverrides(mapOf(maxItems to null)) }
        assertNull(resolver.currentOverride(maxItems, FlagValueType.Int))
    }

    @Test
    fun theResolverCarriesItsKeyEncoding() {
        assertSame(KeyEncoding.Snakecase, FlagResolver(emptyList(), KeyEncoding.Snakecase).keyEncoding)
    }
}
