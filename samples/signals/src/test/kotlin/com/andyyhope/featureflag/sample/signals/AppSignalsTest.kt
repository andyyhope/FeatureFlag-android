package com.andyyhope.featureflag.sample.signals

import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.Test
import kotlin.test.assertEquals

class AppSignalsTest {

    // What the companion's buttons say, and what travels over the bridge

    @Test
    fun appSignalsTravelAsTheirEntryNamesWithTheIOSDescriptions() {
        assertEquals(
            listOf(
                "RefetchRemoteConfiguration" to "Re-fetch remote configuration",
                "ClearRemoteConfiguration" to "Clear remote configuration",
            ),
            AppSignal.entries.map { signal -> signal.name to signal.signalDescription },
        )
    }

    @Test
    fun cacheSignalsTravelAsTheirEntryNamesWithTheIOSDescriptions() {
        assertEquals(
            listOf(
                "PurgeImageCache" to "Purge the image cache",
                "PurgeEverything" to "Purge everything",
            ),
            CacheSignal.entries.map { signal -> signal.name to signal.signalDescription },
        )
    }

    @Test
    fun onlyPurgeEverythingNeedsARelaunch() {
        val all: List<FlagSignal> = AppSignal.entries + CacheSignal.entries
        assertEquals(listOf<FlagSignal>(CacheSignal.PurgeEverything), all.filter { signal -> signal.requiresRestart })
    }

    @Test
    fun noNameIsSharedAcrossTheTwoGroups() {
        // FlagCompanionTab.Signals refuses two groups that share a name: the host would run both observers.
        val all: List<FlagSignal> = AppSignal.entries + CacheSignal.entries
        val names = all.map { signal -> signal.name }
        assertEquals(names.distinct(), names)
    }
}
