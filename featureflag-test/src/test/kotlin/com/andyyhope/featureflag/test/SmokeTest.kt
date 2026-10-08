package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.FeatureFlag
import org.junit.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun coreVersionMatchesCore() {
        assertEquals(FeatureFlag.VERSION, TestFeatureFlags.CORE_VERSION)
    }
}
