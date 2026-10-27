package com.andyyhope.featureflag

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SmokeTest {
    @Test
    fun fixturesPathIsPassedToTests() {
        assertNotNull(System.getProperty("featureflag.fixtures"))
    }

    @Test
    fun coroutineTestToolingIsOnTheClasspath() = runTest {
        flowOf("ready").test {
            assertEquals("ready", awaitItem())
            awaitComplete()
        }
    }
}
