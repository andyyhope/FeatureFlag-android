package com.andyyhope.featureflag.values

import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from FlagValueBoxTests. */
class FlagCodecsTest {

    @Test
    fun primitivesBoxToTheExpectedCaseAndRoundTrip() {
        assertEquals(FlagValueBox.Bool(true), FlagCodecs.boolean.encode(true))
        assertEquals(false, FlagCodecs.boolean.decode(FlagCodecs.boolean.encode(false)))
        assertEquals(FlagValueBox.Int(42), FlagCodecs.int.encode(42))
        assertEquals(-7, FlagCodecs.int.decode(FlagCodecs.int.encode(-7)))
        assertEquals(FlagValueBox.Int(Long.MAX_VALUE), FlagCodecs.long.encode(Long.MAX_VALUE))
        assertEquals(FlagValueBox.Double(3.5), FlagCodecs.double.encode(3.5))
        assertEquals(3.5, FlagCodecs.double.decode(FlagCodecs.double.encode(3.5)))
        assertEquals(FlagValueBox.Float(3.5f), FlagCodecs.float.encode(3.5f))
        assertEquals(3.5f, FlagCodecs.float.decode(FlagCodecs.float.encode(3.5f)))
        assertEquals(FlagValueBox.String("hello"), FlagCodecs.string.encode("hello"))
        assertEquals("hello", FlagCodecs.string.decode(FlagCodecs.string.encode("hello")))
        val bytes = FlagCodecs.byteArray
        assertContentEquals(byteArrayOf(1, 2), bytes.decode(bytes.encode(byteArrayOf(1, 2))))
        val date = Instant.ofEpochSecond(1000)
        assertEquals(FlagValueBox.Date(date), FlagCodecs.instant.encode(date))
        assertEquals(date, FlagCodecs.instant.decode(FlagCodecs.instant.encode(date)))
        val url = URI("https://example.com")
        assertEquals(FlagValueBox.Url(url), FlagCodecs.uri.encode(url))
        assertEquals(url, FlagCodecs.uri.decode(FlagCodecs.uri.encode(url)))
    }

    @Test
    fun unboxingIsStrict() {
        assertNull(FlagCodecs.boolean.decode(FlagValueBox.Int(1)))
        assertNull(FlagCodecs.int.decode(FlagValueBox.String("1")))
        assertNull(FlagCodecs.string.decode(FlagValueBox.Bool(true)))
        assertNull(FlagCodecs.double.decode(FlagValueBox.Int(1)))
        assertNull(FlagCodecs.float.decode(FlagValueBox.Double(1.0)))
    }

    @Test
    fun intIsSixtyFourBitOnTheWireAndRefusesWhatItCannotHold() {
        assertEquals(FlagValueType.Int, FlagCodecs.int.type)
        assertEquals(Int.MAX_VALUE, FlagCodecs.int.decode(FlagValueBox.Int(Int.MAX_VALUE.toLong())))
        assertNull(FlagCodecs.int.decode(FlagValueBox.Int(Int.MAX_VALUE + 1L)))
        assertNull(FlagCodecs.int.decode(FlagValueBox.Int(Long.MIN_VALUE)))
        assertEquals(Long.MIN_VALUE, FlagCodecs.long.decode(FlagValueBox.Int(Long.MIN_VALUE)))
    }

    @Test
    fun primitivesDeclareTheirValueType() {
        assertEquals(FlagValueType.Bool, FlagCodecs.boolean.type)
        assertEquals(FlagValueType.Int, FlagCodecs.long.type)
        assertEquals(FlagValueType.Double, FlagCodecs.double.type)
        assertEquals(FlagValueType.Float, FlagCodecs.float.type)
        assertEquals(FlagValueType.String, FlagCodecs.string.type)
        assertEquals(FlagValueType.Data, FlagCodecs.byteArray.type)
        assertEquals(FlagValueType.Date, FlagCodecs.instant.type)
        assertEquals(FlagValueType.Url, FlagCodecs.uri.type)
        assertNull(FlagCodecs.string.cases)
        assertNull(FlagCodecs.string.recordShape)
    }

    @Test
    fun byteArraysAreCopiedSoAStoredValueCannotChange() {
        val bytes = byteArrayOf(1, 2)
        val box = FlagCodecs.byteArray.encode(bytes)
        bytes[0] = 9
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), box)
        FlagCodecs.byteArray.decode(box)!![1] = 9
        assertEquals(FlagValueBox.Data(byteArrayOf(1, 2)), box)
    }

    private enum class Plan(override val flagRawValue: String) : FlagStringEnum {
        Free("free"),
        Gratis("free"),
        Pro("pro"),
    }

    private enum class Level(override val flagRawValue: Long) : FlagIntEnum {
        Low(1),
        Minimum(1),
        High(2),
    }

    @Test
    fun twoEntriesSharingARawValueDecodeToTheFirst() {
        val plans = FlagCodecs.stringEnum(Plan.entries)
        assertEquals(Plan.Free, plans.decode(FlagValueBox.String("free")))
        assertEquals(FlagValueBox.String("free"), plans.encode(Plan.Gratis))
        assertEquals(listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")), plans.cases)

        val levels = FlagCodecs.intEnum(Level.entries)
        assertEquals(Level.Low, levels.decode(FlagValueBox.Int(1)))
        assertEquals(FlagValueBox.Int(1), levels.encode(Level.Minimum))
        assertEquals(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2)), levels.cases)
    }
}
