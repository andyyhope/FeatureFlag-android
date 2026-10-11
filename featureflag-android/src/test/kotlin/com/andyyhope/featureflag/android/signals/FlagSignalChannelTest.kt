package com.andyyhope.featureflag.android.signals

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.signals.FlagSignal
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private enum class TestSignal : FlagSignal {
    Refetch,
    PurgeCache,
    DumpDiagnostics,
    ;

    override val signalDescription: String
        get() = when (this) {
            Refetch -> "Re-fetch remote configuration"
            PurgeCache -> "Purge cached responses"
            DumpDiagnostics -> "Write diagnostics"
        }
}

/** Stands in for an older host build that knows fewer signals. */
private enum class NarrowSignal : FlagSignal {
    Refetch,
}

private enum class GroupedCacheSignal : FlagSignal {
    PurgeImages,
}

private enum class GroupedSessionSignal : FlagSignal {
    SignOut,
}

private enum class RestartingSignal : FlagSignal {
    SwapDependency,
    Harmless,
    ;

    override val requiresRestart: Boolean get() = this == SwapDependency
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagSignalChannelTest {

    /** Runs handlers on the calling thread, which is the main thread under Robolectric. */
    private val channel = FlagSignalChannel(null)

    private val background = Executors.newSingleThreadExecutor()

    /** Posts to the main looper, as FlagSignalChannel.host does. */
    private val mainThread = Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    @After
    fun shutDown() {
        background.shutdownNow()
    }

    /** Runs main-looper tasks until [future] completes: the main thread is free to run handlers. */
    private fun <T> idleMainLooperUntilDone(future: Future<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    // Delivery (FlagSignalChannelTests)

    @Test
    fun aSignalReachesAnObserver() {
        val seen = mutableListOf<TestSignal>()
        channel.observe(TestSignal.entries) { seen += it }

        channel.deliver(TestSignal.Refetch.name)

        assertEquals(listOf(TestSignal.Refetch), seen)
    }

    @Test
    fun eachSignalArrivesAsItself() {
        val seen = mutableListOf<TestSignal>()
        channel.observe(TestSignal.entries) { seen += it }

        for (signal in TestSignal.entries) channel.deliver(signal.name)

        assertEquals(TestSignal.entries.toList(), seen)
    }

    @Test
    fun nothingArrivesAfterTheObserverIsClosed() {
        var count = 0
        val observer = channel.observe(TestSignal.entries) { count++ }
        observer.close()

        assertFalse(channel.deliver(TestSignal.Refetch.name))
        assertEquals(0, count)
    }

    // Delivered once

    @Test
    fun oneDeliverRunsAHandlerOnce() {
        var count = 0
        channel.observe(TestSignal.entries) { count++ }

        channel.deliver(TestSignal.Refetch.name)

        assertEquals(1, count)
    }

    @Test
    fun anObserverRegisteredAfterADeliverIsNotCalledForIt() {
        // A signal is not queued: a host that starts later must not act on it.
        channel.deliver(TestSignal.Refetch.name)
        var count = 0

        channel.observe(TestSignal.entries) { count++ }

        assertEquals(0, count)
    }

    // Acknowledgement

    @Test
    fun deliverReturnsTrueWhenTheHostIsListening() {
        channel.observe(TestSignal.entries) { }

        assertTrue(channel.deliver(TestSignal.Refetch.name))
    }

    @Test
    fun deliverReturnsFalseWhenNothingIsListening() {
        assertFalse(channel.deliver(TestSignal.Refetch.name))
    }

    @Test
    fun aSignalThisBuildCannotRepresentIsNotHandled() {
        // A newer companion sending a signal an older host does not have.
        var count = 0
        channel.observe(NarrowSignal.entries) { count++ }

        assertFalse(channel.deliver(TestSignal.PurgeCache.name))
        assertEquals(0, count)
    }

    @Test
    fun anUnknownSignalDoesNotBlockTheNextValidOne() {
        val seen = mutableListOf<NarrowSignal>()
        channel.observe(NarrowSignal.entries) { seen += it }

        channel.deliver(TestSignal.PurgeCache.name)

        assertTrue(channel.deliver(TestSignal.Refetch.name))
        assertEquals(listOf(NarrowSignal.Refetch), seen)
    }

    // Declaring signals

    @Test
    fun signalsDescribeThemselvesForAButton() {
        assertEquals("Re-fetch remote configuration", TestSignal.Refetch.signalDescription)
        // Without an explicit description, the name stands in.
        assertEquals("Refetch", NarrowSignal.Refetch.signalDescription)
    }

    @Test
    fun theSignalListIsDiscoverable() {
        assertEquals(listOf("Refetch", "PurgeCache", "DumpDiagnostics"), TestSignal.entries.map { it.name })
    }

    // More than one observer (MultipleObserverTests)

    @Test
    fun eachObserverReceivesItsOwnSignals() {
        val caches = mutableListOf<GroupedCacheSignal>()
        val sessions = mutableListOf<GroupedSessionSignal>()
        channel.observe(GroupedCacheSignal.entries) { caches += it }
        channel.observe(GroupedSessionSignal.entries) { sessions += it }

        channel.deliver(GroupedSessionSignal.SignOut.name)
        channel.deliver(GroupedCacheSignal.PurgeImages.name)

        assertEquals(listOf(GroupedCacheSignal.PurgeImages), caches)
        assertEquals(listOf(GroupedSessionSignal.SignOut), sessions)
    }

    @Test
    fun anObserverStillRunsEachSignalOnlyOnce() {
        var caches = 0
        var sessions = 0
        channel.observe(GroupedCacheSignal.entries) { caches++ }
        channel.observe(GroupedSessionSignal.entries) { sessions++ }

        channel.deliver(GroupedCacheSignal.PurgeImages.name)

        assertEquals(1, caches)
        assertEquals(0, sessions)
    }

    @Test
    fun aSignalNoObserverCanRepresentIsNotHandled() {
        var count = 0
        channel.observe(GroupedCacheSignal.entries) { count++ }

        assertFalse(channel.deliver(GroupedSessionSignal.SignOut.name))
        assertEquals(0, count)
    }

    // requiresRestart

    @Test
    fun aSignalDoesNotRequireARestartUnlessItSaysSo() {
        assertFalse(GroupedCacheSignal.PurgeImages.requiresRestart)
    }

    @Test
    fun aSignalCanSayItNeedsARestart() {
        assertTrue(RestartingSignal.SwapDependency.requiresRestart)
        assertFalse(RestartingSignal.Harmless.requiresRestart)
    }

    @Test
    fun requiringARestartDoesNotChangeDelivery() {
        val seen = mutableListOf<RestartingSignal>()
        channel.observe(RestartingSignal.entries) { seen += it }

        assertTrue(channel.deliver(RestartingSignal.SwapDependency.name))
        assertEquals(listOf(RestartingSignal.SwapDependency), seen)
    }

    // Android only

    @Test
    fun twoObserversListingOneNameBothRun() {
        var first = 0
        var second = 0
        channel.observe(listOf(TestSignal.Refetch)) { first++ }
        channel.observe(TestSignal.entries) { second++ }

        assertTrue(channel.deliver(TestSignal.Refetch.name))
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun aThrowingHandlerIsNotHandledAndDoesNotEscape() {
        ShadowLog.clear()
        channel.observe(TestSignal.entries) { error("cache is locked") }

        assertFalse(channel.deliver(TestSignal.PurgeCache.name))

        val logged = ShadowLog.getLogsForTag("FeatureFlag").single()
        assertEquals("cache is locked", logged.throwable.message)
    }

    @Test
    fun aThrowingHandlerDoesNotStopTheOthers() {
        var ran = 0
        channel.observe(TestSignal.entries) { error("cache is locked") }
        channel.observe(TestSignal.entries) { ran++ }

        assertTrue(channel.deliver(TestSignal.PurgeCache.name))
        assertEquals(1, ran)
    }

    @Test
    fun deliverFromABackgroundThreadRunsTheHandlerOnTheMainThread() {
        val channel = FlagSignalChannel(mainThread)
        var handlerThread: Thread? = null
        channel.observe(TestSignal.entries) { handlerThread = Thread.currentThread() }

        val handled = idleMainLooperUntilDone(background.submit<Boolean> { channel.deliver(TestSignal.Refetch.name) })

        assertTrue(handled)
        assertSame(Looper.getMainLooper().thread, handlerThread)
    }

    @Test
    fun deliverReturnsFalseAfterTheTimeoutWhenTheMainThreadIsBlocked() {
        val channel = FlagSignalChannel(mainThread)
        var count = 0
        channel.observe(TestSignal.entries) { count++ }

        // The test thread is the main thread. Not idling its looper stands in for a main
        // thread busy elsewhere for longer than the timeout.
        val handled = background.submit<Boolean> { channel.deliver(TestSignal.Refetch.name, 100.milliseconds) }
            .get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(handled)
        assertEquals(0, count, "a delivery that timed out must not run later")
    }

    @Test
    fun aTimedOutDeliveryDoesNotRunObserversThatHaveNotStarted() {
        val channel = FlagSignalChannel(mainThread)
        var firstRan = false
        var secondRan = false
        channel.observe(TestSignal.entries) {
            firstRan = true
            Thread.sleep(500)
        }
        channel.observe(TestSignal.entries) { secondRan = true }

        val handled = idleMainLooperUntilDone(
            background.submit<Boolean> { channel.deliver(TestSignal.Refetch.name, 100.milliseconds) },
        )

        assertFalse(handled)
        assertTrue(firstRan, "the first handler was already running when the delivery timed out")
        assertFalse(secondRan, "a delivery that timed out must not start further handlers")
    }

    @Test
    fun aHandlerThrowingAnErrorOffTheMainThreadIsNotHandledAndDoesNotEscape() {
        val channel = FlagSignalChannel(mainThread)
        channel.observe(TestSignal.entries) { throw AssertionError("not implemented") }

        val handled = idleMainLooperUntilDone(background.submit<Boolean> { channel.deliver(TestSignal.Refetch.name) })

        assertFalse(handled)
    }

    @Test
    fun deliverOnTheMainThreadRunsInline() {
        // Posting and waiting would deadlock: the main thread would wait for itself.
        val channel = FlagSignalChannel(mainThread)
        var count = 0
        channel.observe(TestSignal.entries) { count++ }

        assertTrue(channel.deliver(TestSignal.Refetch.name))
        assertEquals(1, count)
    }

    @Test
    fun theHostChannelRunsHandlersOnTheMainThread() {
        var count = 0
        val observer = FlagSignalChannel.host.observe(TestSignal.entries) { count++ }
        try {
            assertTrue(FlagSignalChannel.host.deliver(TestSignal.Refetch.name))
            assertEquals(1, count)
        } finally {
            observer.close()
        }
        assertEquals(4.seconds, FlagSignalChannel.DELIVERY_TIMEOUT)
    }
}
