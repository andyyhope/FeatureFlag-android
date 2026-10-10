package com.andyyhope.featureflag

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixturesTest {

    @Test
    fun manifestNamesTheReferenceVersion() {
        assertTrue(Fixtures.text("manifest.json").contains("\"featureFlagVersion\" : \"0.12.0\""))
    }

    @Test
    fun tsvFieldsAreUnescaped() {
        assertEquals("a\\b\tc\nd\u0001e", Fixtures.unescape("a\\\\b\\tc\\nd\\u0001e"))
    }
}
