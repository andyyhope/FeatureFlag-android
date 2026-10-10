package com.andyyhope.featureflag.signals

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private enum class BareAppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    SignOut,
}

private enum class LabelledAppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    ;

    override val signalDescription: String
        get() = when (this) {
            RefetchRemoteConfiguration -> "Re-fetch remote config"
            PurgeImageCache -> "Purge image cache"
        }
}

/** The demo's second group: emptying everything takes hold only after a relaunch. */
private enum class CacheSignal : FlagSignal {
    PurgeImageCache,
    PurgeEverything,
    ;

    override val signalDescription: String
        get() = when (this) {
            PurgeImageCache -> "Purge the image cache"
            PurgeEverything -> "Purge everything"
        }

    override val requiresRestart: Boolean get() = this == PurgeEverything
}

class FlagSignalTest {

    @Test
    fun anEnumsOwnNameIsTheSignalAndItsDefaultLabel() {
        assertEquals("RefetchRemoteConfiguration", BareAppSignal.RefetchRemoteConfiguration.name)
        assertEquals("RefetchRemoteConfiguration", BareAppSignal.RefetchRemoteConfiguration.signalDescription)
        assertEquals(3, BareAppSignal.entries.size)
    }

    @Test
    fun aSignalCanCarryAReadableLabel() {
        assertEquals("Re-fetch remote config", LabelledAppSignal.RefetchRemoteConfiguration.signalDescription)
        assertEquals("Purge image cache", LabelledAppSignal.PurgeImageCache.signalDescription)
    }

    @Test
    fun aSignalNeedsNoRelaunchUnlessItSaysSo() {
        assertFalse(BareAppSignal.SignOut.requiresRestart)
        assertFalse(CacheSignal.PurgeImageCache.requiresRestart)
        assertTrue(CacheSignal.PurgeEverything.requiresRestart)
        assertEquals("Purge everything", CacheSignal.PurgeEverything.signalDescription)
    }

    @Test
    fun aSignalNeedNotBeAnEnum() {
        val signal = object : FlagSignal {
            override val name = "refetch"
        }
        assertEquals("refetch", signal.signalDescription)
        assertFalse(signal.requiresRestart)
    }
}
