package com.andyyhope.featureflag.errors

import org.junit.Test
import kotlin.test.assertEquals

class MessageTextTest {

    @Test
    fun quotingEscapesWhatWouldBreakALine() {
        assertEquals("\"a\\\\b\\nc\\rd\\te\"", MessageText.quoted("a\\b\nc\rd\te"))
        assertEquals("\"\"", MessageText.quoted(""))
    }

    @Test
    fun quotingCapsAtFortyCharactersAfterEscaping() {
        val forty = "x".repeat(40)
        assertEquals("\"$forty\"", MessageText.quoted(forty))
        assertEquals("\"$forty…\"", MessageText.quoted(forty + "y"))
        assertEquals("\"${"\\n".repeat(20)}…\"", MessageText.quoted("\n".repeat(21)))
        assertEquals("\"${"😀".repeat(40)}…\"", MessageText.quoted("😀".repeat(41)))
    }

    @Test
    fun collectionsAreDescribedInWords() {
        assertEquals("an array of 3", MessageText.arrayOf(3))
        assertEquals("an object with 1 field(s)", MessageText.objectWith(1))
    }
}
