package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.math.sign
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The measured probe facts; `JsonWriterGoldenTest` checks ~700 keys against Apple. */
class AppleKeyOrderTest {

    private fun assertSorted(vararg keys: String) {
        assertEquals(keys.toList(), keys.reversed().sortedWith(AppleKeyOrder))
        assertEquals(keys.toList(), keys.toList().shuffled(kotlin.random.Random(1)).sortedWith(AppleKeyOrder))
    }

    @Test
    fun punctuationThenDigitsThenLetters() {
        assertSorted(
            " ", "_", "-", ",", ";", ":", "!", "?", ".", "'", "\"", "(", ")", "[", "]", "{", "}",
            "@", "*", "/", "\\", "&", "#", "%", "`", "^", "+", "<", "=", ">", "|", "~", "$",
            "0", "9", "a", "Z",
        )
    }

    @Test
    fun digitRunsCompareByValue() {
        assertSorted("9", "99999999999999999999", "100000000000000000000")
        assertSorted("item9", "item10")
    }

    @Test
    fun aProperPrefixSortsFirst() {
        assertSorted("a", "a-", "a--", "a-a", "aa")
        assertSorted("foo", "foo2", "foo2bar", "foo10")
    }

    @Test
    fun tiesBreakOnCaseThenLeadingZerosAtTheFirstDifference() {
        assertSorted("a1b", "a1B", "a01b", "a01B", "a001b", "A1b")
        assertSorted("ab", "aB", "Ab", "AB")
        assertSorted("x", "x0", "x00", "x000", "x1", "x01")
    }

    private fun randomKeys(): List<String> {
        val random = Random(7)
        val alphabet = "aAzZ09_-. éÉ"
        return List(2_000) { String(CharArray(random.nextInt(0, 7)) { alphabet[random.nextInt(alphabet.length)] }) }
    }

    private fun order(a: String, b: String): Int = AppleKeyOrder.compare(a, b).sign

    @Test
    fun keysFromARandomAlphabetSortConsistently() {
        val keys = randomKeys()
        val pairs = keys.take(300)
        for (a in pairs) {
            for (b in pairs) {
                assertEquals(-order(b, a), order(a, b), "antisymmetry of '$a' and '$b'")
                if (order(a, b) == 0) assertEquals(a, b, "a tie must mean equal keys")
            }
        }
        val triples = keys.take(60)
        for (a in triples) {
            for (b in triples) {
                if (order(a, b) > 0) continue
                for (c in triples) {
                    if (order(b, c) <= 0) assertTrue(order(a, c) <= 0, "transitivity of '$a', '$b', '$c'")
                }
            }
        }
        val first = keys.shuffled(Random(1)).sortedWith(AppleKeyOrder)
        val second = keys.shuffled(Random(2)).sortedWith(AppleKeyOrder)
        assertEquals(first, second)
    }
}
