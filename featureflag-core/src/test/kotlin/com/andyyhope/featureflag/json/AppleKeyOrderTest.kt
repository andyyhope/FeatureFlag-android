package com.andyyhope.featureflag.json

import org.junit.Test
import kotlin.test.assertEquals

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
}
