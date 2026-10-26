package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RemoteValueTest {

    private fun decoded(json: String): RemoteValue = RemoteValue.parseJson(json.toByteArray())

    // Deserialising

    @Test
    fun booleansAreNotMistakenForNumbers() {
        val value = decoded("""{"a": true, "b": 1}""")
        assertEquals(RemoteValue.Bool(true), value.valueAt("a"))
        assertEquals(RemoteValue.Int(1), value.valueAt("b"))
    }

    @Test
    fun wholeAndFractionalNumbersAreDistinguished() {
        val value = decoded("""{"a": 1, "b": 1.5}""")
        assertEquals(RemoteValue.Int(1), value.valueAt("a"))
        assertEquals(RemoteValue.Double(1.5), value.valueAt("b"))
    }

    @Test
    fun nullBecomesNull() {
        assertEquals(RemoteValue.Null, decoded("""{"a": null}""").valueAt("a"))
    }

    @Test
    fun nestingIsPreserved() {
        assertEquals(
            RemoteValue.Array(listOf(RemoteValue.Int(1), RemoteValue.Int(2))),
            decoded("""{"a": {"b": [1, 2]}}""").valueAt("a.b"),
        )
    }

    @Test
    fun aWholeValuedRealArrivesAsAnInteger() {
        // JSONSerialization hands 2.0 and 1e2 over as integers, so iOS sees .int too.
        val value = decoded("""{"a": 2.0, "b": 1e2}""")
        assertEquals(RemoteValue.Int(2), value.valueAt("a"))
        assertEquals(RemoteValue.Int(100), value.valueAt("b"))
    }

    @Test
    fun anIntegerBeyondLongArrivesAsADouble() {
        assertEquals(RemoteValue.Double(1.0E19), decoded("""{"a": 10000000000000000000}""").valueAt("a"))
    }

    @Test
    fun malformedBytesAreRejectedAsMalformed() {
        val error = assertFailsWith<RemoteOverrideException.Malformed> { decoded("not json") }
        assertEquals("The remote payload could not be read: not valid JSON.", error.message)
    }

    @Test
    fun aTopLevelScalarIsMalformed() {
        assertFailsWith<RemoteOverrideException.Malformed> { decoded("true") }
    }

    // Paths

    @Test
    fun anEmptyPathReturnsTheRoot() {
        val value = decoded("""{"a": 1}""")
        assertEquals(value, value.valueAt(""))
    }

    @Test
    fun aMissingKeyReturnsNothing() {
        assertNull(decoded("""{"a": 1}""").valueAt("b"))
        assertNull(decoded("""{"a": 1}""").valueAt("a.b"))
    }

    @Test
    fun anIndexPastTheEndReturnsNothing() {
        assertNull(decoded("""{"a": [1]}""").valueAt("a.5"))
    }

    @Test
    fun aNegativeIndexReturnsNothing() {
        assertNull(decoded("""{"a": [1]}""").valueAt("a.-1"))
    }

    @Test
    fun aNonNumericComponentOnAnArrayReturnsNothing() {
        assertNull(decoded("""{"a": [1]}""").valueAt("a.first"))
    }

    @Test
    fun descendingIntoAScalarReturnsNothing() {
        assertNull(decoded("""{"a": 1}""").valueAt("a.b.c"))
    }

    @Test
    fun deepPathsResolve() {
        assertEquals(RemoteValue.Bool(true), decoded("""{"a": {"b": [{"c": {"d": true}}]}}""").valueAt("a.b.0.c.d"))
    }

    @Test
    fun emptySegmentsAreSkippedTheWaySwiftSplitSkipsThem() {
        val value = decoded("""{"a": {"b": 1}}""")
        assertEquals(RemoteValue.Int(1), value.valueAt("a..b"))
        assertEquals(RemoteValue.Int(1), value.valueAt(".a.b."))
    }

    @Test
    fun aSignedIndexIsReadTheWaySwiftIntParsesIt() {
        assertEquals(RemoteValue.Int(2), decoded("""{"a": [1, 2]}""").valueAt("a.+1"))
    }

    @Test
    fun aMemberNameContainingADotCannotBeReachedByAPath() {
        // Review Focus 1: a flat, Firebase-style key is split at its dots like any path.
        assertNull(decoded("""{"featureToggles.onboarding.v2": true}""").valueAt("featureToggles.onboarding.v2"))
    }

    // Boxing

    @Test
    fun stringsBecomeDatesDataAndUrlsWhereDeclared() {
        assertEquals(FlagValueBox.Date(Instant.EPOCH), RemoteValue.String("1970-01-01T00:00:00Z").box(FlagValueType.Date))
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), RemoteValue.String("AQI=").box(FlagValueType.Data))
        assertEquals(FlagValueBox.Url(URI("https://a.example")), RemoteValue.String("https://a.example").box(FlagValueType.Url))
    }

    @Test
    fun anUnparseableStringIsRejectedForEachStructuredType() {
        assertNull(RemoteValue.String("not a date").box(FlagValueType.Date))
        assertNull(RemoteValue.String("!!!not base64!!!").box(FlagValueType.Data))
    }

    @Test
    fun aUrlNeedingPercentEncodingBoxesTheWayIosDoes() {
        // Review Focus 3. These are iOS 17+ URL(string:) results, measured with swiftc.
        assertEquals(
            FlagValueBox.Url(URI("https://example.com/a%20b")),
            RemoteValue.String("https://example.com/a b").box(FlagValueType.Url),
        )
        assertEquals(
            FlagValueBox.Url(URI("https://example.com/%C3%BC")),
            RemoteValue.String("https://example.com/ü").box(FlagValueType.Url),
        )
        assertNull(RemoteValue.String("https://exa mple.com").box(FlagValueType.Url))
        assertNull(RemoteValue.String("").box(FlagValueType.Url))
    }

    @Test
    fun nullNeverBoxes() {
        for (type in listOf(FlagValueType.Bool, FlagValueType.Int, FlagValueType.String, FlagValueType.Array(FlagValueType.Int))) {
            assertNull(RemoteValue.Null.box(type))
        }
    }

    @Test
    fun wideningIsExactAndOnlyUpwards() {
        assertEquals(FlagValueBox.Double(1.0), RemoteValue.Int(1).box(FlagValueType.Double))
        assertEquals(FlagValueBox.Float(1f), RemoteValue.Int(1).box(FlagValueType.Float))
        assertNull(RemoteValue.Double(1.5).box(FlagValueType.Int))
        assertNull(RemoteValue.Double(1.0).box(FlagValueType.Int))
    }

    @Test
    fun aDoubleNarrowsToAFloatFlag() {
        assertEquals(FlagValueBox.Float(0.1f), RemoteValue.Double(0.1).box(FlagValueType.Float))
    }

    @Test
    fun booleansAndNumbersNeverConvertIntoEachOther() {
        assertNull(RemoteValue.Int(1).box(FlagValueType.Bool))
        assertNull(RemoteValue.Bool(true).box(FlagValueType.Int))
        assertNull(RemoteValue.String("true").box(FlagValueType.Bool))
    }

    @Test
    fun collectionsBoxElementByElement() {
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))),
            RemoteValue.Array(listOf(RemoteValue.Int(1), RemoteValue.Int(2))).box(FlagValueType.Array(FlagValueType.Int)),
        )
        assertNull(RemoteValue.Array(listOf(RemoteValue.Int(1), RemoteValue.String("x"))).box(FlagValueType.Array(FlagValueType.Int)))
    }

    @Test
    fun anEmptyCollectionBoxesAsAnyElementType() {
        assertEquals(FlagValueBox.Array(emptyList()), RemoteValue.Array(emptyList()).box(FlagValueType.Array(FlagValueType.Date)))
        assertEquals(
            FlagValueBox.Dictionary(emptyMap()),
            RemoteValue.Object(emptyMap()).box(FlagValueType.Dictionary(FlagValueType.Url)),
        )
    }

    @Test
    fun collectionsWidenTheirElementsToo() {
        assertEquals(
            FlagValueBox.Array(listOf(FlagValueBox.Double(1.0))),
            RemoteValue.Array(listOf(RemoteValue.Int(1))).box(FlagValueType.Array(FlagValueType.Double)),
        )
        assertEquals(
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.Double(1.0))),
            RemoteValue.Object(mapOf("a" to RemoteValue.Int(1))).box(FlagValueType.Dictionary(FlagValueType.Double)),
        )
    }

    @Test
    fun nativeDatesAndDataBoxAsThemselves() {
        val moment = Instant.ofEpochSecond(1_000)
        assertEquals(FlagValueBox.Date(moment), RemoteValue.Date(moment).box(FlagValueType.Date))
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals(FlagValueBox.Data(bytes), RemoteValue.Data(bytes).box(FlagValueType.Data))
    }

    @Test
    fun nativeDatesAndDataDoNotSatisfyOtherTypes() {
        val moment = RemoteValue.Date(Instant.ofEpochSecond(1_000))
        for (type in listOf(FlagValueType.String, FlagValueType.Int, FlagValueType.Double, FlagValueType.Bool, FlagValueType.Data, FlagValueType.Url)) {
            assertNull(moment.box(type), "a date should not box as $type")
        }
        val bytes = RemoteValue.Data(byteArrayOf(1))
        for (type in listOf(FlagValueType.String, FlagValueType.Int, FlagValueType.Double, FlagValueType.Bool, FlagValueType.Date, FlagValueType.Url)) {
            assertNull(bytes.box(type), "data should not box as $type")
        }
    }

    // Describing

    @Test
    fun shortDescriptionsReadTheWaySwiftPrintsThem() {
        assertEquals("null", RemoteValue.Null.shortDescription)
        assertEquals("true", RemoteValue.Bool(true).shortDescription)
        assertEquals("7", RemoteValue.Int(7).shortDescription)
        assertEquals("1.5", RemoteValue.Double(1.5).shortDescription)
        assertEquals("1e+19", RemoteValue.Double(1.0E19).shortDescription)
        assertEquals("\"short\"", RemoteValue.String("short").shortDescription)
        assertEquals("3 bytes", RemoteValue.Data(byteArrayOf(1, 2, 3)).shortDescription)
        assertEquals("1970-01-01T00:16:40Z", RemoteValue.Date(Instant.ofEpochSecond(1_000)).shortDescription)
        assertEquals("an array of 2", RemoteValue.Array(listOf(RemoteValue.Null, RemoteValue.Null)).shortDescription)
        assertEquals("an object with 1 field(s)", RemoteValue.Object(mapOf("a" to RemoteValue.Null)).shortDescription)
    }

    @Test
    fun anIndexInNonAsciiDigitsFindsNothing() {
        val value = decoded("""{"items":["a","b"]}""")
        assertEquals(RemoteValue.String("b"), value.valueAt("items.1"))
        assertNull(value.valueAt("items.\u0661"))
        assertNull(value.valueAt("items.\uFF11"))
    }
}
