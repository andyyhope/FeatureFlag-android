package com.andyyhope.featureflag.ui.signals

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Ports Swift's FlagSignalDelayTests.
class FlagSignalDelayTest {

    @Test
    fun theOfferedDelaysAreInstantThreeFiveAndTen() {
        assertEquals(listOf(Duration.ZERO, 3.seconds, 5.seconds, 10.seconds), FlagSignalDelay.entries.map { it.duration })
    }

    @Test
    fun instantIsLabelledAsSuchAndTheRestInSeconds() {
        assertEquals(listOf("Instant", "3s", "5s", "10s"), FlagSignalDelay.entries.map { it.label })
    }

    @Test
    fun instantIsTheOnlyZeroDelay() {
        // Zero sends at once; anything else counts down, and can be cancelled.
        assertEquals(listOf(FlagSignalDelay.Instant), FlagSignalDelay.entries.filter { it.duration == Duration.ZERO })
    }

    @Test
    fun delaysAreDistinctAndAscending() {
        val durations = FlagSignalDelay.entries.map { it.duration }

        assertEquals(durations.sorted(), durations)
        assertEquals(durations.size, durations.toSet().size)
    }
}
