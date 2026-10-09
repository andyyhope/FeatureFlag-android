package com.andyyhope.featureflag.json

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrictBase64Test {

    private fun hex(bytes: ByteArray?): String = bytes?.joinToString("") { "%02x".format(it) } ?: "nil"

    @Test
    fun decodesExactlyAsAppleDoes() {
        val mismatches = Fixtures.tsv("base64/decode.tsv").mapNotNull { (input, expected) ->
            val actual = hex(StrictBase64.decode(input))
            if (actual == expected) null else "'$input': Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun probeFactsHold() {
        assertEquals("61", hex(StrictBase64.decode("YR==")))
        assertEquals("6869", hex(StrictBase64.decode("aGk===")))
        assertEquals("", hex(StrictBase64.decode("")))
        for (rejected in listOf("YQ", "Y Q==", "Zm-_", "YQ==\n", "Y===")) {
            assertNull(StrictBase64.decode(rejected), rejected)
        }
    }

    @Test
    fun encodesStandardPaddedBase64AndRoundTrips() {
        assertEquals("", StrictBase64.encode(ByteArray(0)))
        assertEquals("AAEC/v8=", StrictBase64.encode(byteArrayOf(0, 1, 2, -2, -1)))
        val deadBeef = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals("3q2+7w==", StrictBase64.encode(deadBeef))
        val random = Random(64)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            assertContentEquals(bytes, StrictBase64.decode(StrictBase64.encode(bytes)))
        }
    }
}
