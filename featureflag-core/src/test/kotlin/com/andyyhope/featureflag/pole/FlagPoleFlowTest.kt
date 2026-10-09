package com.andyyhope.featureflag.pole

import app.cash.turbine.test
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.golden.GoldenTier
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class FlagPoleFlowTest {

    private val boolFlag = FlagKey("bool-flag")
    private val intFlag = FlagKey("int-flag")
    private val stringFlag = FlagKey("string-flag")
    private val tier = FlagKey("tier")

    // flow(ref)

    @Test
    fun aFlowEmitsTheCurrentValueImmediately() = runBlocking<Unit> {
        val pole = FlagPole(GoldenFlagsSpec, listOf(SnapshotSource("local")))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun aFlowEmitsWhenItsValueChanges() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            local.setBox(boolFlag, FlagValueBox.Bool(true))
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun aFlowFollowsAnUpdateFromEachSource() = runBlocking<Unit> {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        pole.flow(GoldenFlagsSpec.stringFlag).test {
            assertEquals("hello/world \"quoted\"", awaitItem())
            bottom.setBox(stringFlag, FlagValueBox.String("from bottom"))
            assertEquals("from bottom", awaitItem())
            top.setBox(stringFlag, FlagValueBox.String("from top"))
            assertEquals("from top", awaitItem())
            top.setBox(stringFlag, null)
            assertEquals("from bottom", awaitItem())
        }
    }

    @Test
    fun aFlowIgnoresUnrelatedKeys() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            local.setBox(intFlag, FlagValueBox.Int(5))
            expectNoEvents()
        }
    }

    @Test
    fun anUnrelatedChangeDoesNotEvenCauseARead() = runBlocking<Unit> {
        val local = SnapshotSource("local")

        FlagResolver(listOf(local), KeyEncoding.Kebabcase).ticks(boolFlag).test {
            awaitItem()
            local.setBox(intFlag, FlagValueBox.Int(5))
            expectNoEvents()
            local.setBox(boolFlag, FlagValueBox.Bool(true))
            awaitItem()
        }
    }

    @Test
    fun aChangeThatLeavesTheValueAloneEmitsNothing() = runBlocking<Unit> {
        val top = SnapshotSource("top", mapOf(boolFlag to FlagValueBox.Bool(true)))
        val bottom = SnapshotSource("bottom")
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(true, awaitItem())
            bottom.setBox(boolFlag, FlagValueBox.Bool(false))
            top.replaceAll(mapOf(boolFlag to FlagValueBox.Bool(true)))
            expectNoEvents()
        }
    }

    @Test
    fun aValueTheCodecCannotReadEmitsNothingWhileTheDefaultStaysInEffect() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.tier).test {
            assertEquals(GoldenTier.FREE, awaitItem())
            local.setBox(tier, FlagValueBox.String("platinum"))
            expectNoEvents()
            local.setBox(tier, FlagValueBox.String("pro"))
            assertEquals(GoldenTier.PRO, awaitItem())
        }
    }

    @Test
    fun aWholesaleChangeIsReadAgain() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            local.replaceAll(mapOf(boolFlag to FlagValueBox.Bool(true)))
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun aFlowUsesThePolesKeyEncoding() = runBlocking<Unit> {
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local), keyEncoding = KeyEncoding.Snakecase)

        pole.flow(GoldenFlagsSpec.checkout.applePay).test {
            assertEquals(true, awaitItem())
            local.setBox(FlagKey("checkout.apple_pay"), FlagValueBox.Bool(false))
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun aFlowOnAnEmptyStackEmitsTheDefaultAndStaysOpen() = runBlocking<Unit> {
        FlagPole(GoldenFlagsSpec, emptyList()).flow(GoldenFlagsSpec.boolFlag).test {
            assertEquals(false, awaitItem())
            expectNoEvents()
        }
    }

    // changes

    @Test
    fun changesFiresWhenASourceChanges() = runBlocking<Unit> {
        // The port of objectWillChange: subscribe, write at once, see it.
        val local = SnapshotSource("local")
        val pole = FlagPole(GoldenFlagsSpec, listOf(local))

        pole.changes.test {
            local.setBox(boolFlag, FlagValueBox.Bool(true))
            assertEquals(FlagChange.Keys(setOf(boolFlag)), awaitItem())
        }
    }

    @Test
    fun changesMergesEverySourceAndPassesWholesaleChangesThrough() = runBlocking<Unit> {
        val top = SnapshotSource("top")
        val bottom = SnapshotSource("bottom")
        val pole = FlagPole(GoldenFlagsSpec, listOf(top, bottom))

        pole.changes.test {
            bottom.setBox(intFlag, FlagValueBox.Int(1))
            assertEquals(FlagChange.Keys(setOf(intFlag)), awaitItem())
            top.replaceAll(emptyMap())
            assertEquals(FlagChange.All, awaitItem())
        }
    }

    // Races

    @Test
    fun writesRacingWithSubscriptionNeverLoseTheFinalValue() {
        val pool = Executors.newFixedThreadPool(2)
        val writerThread = pool.asCoroutineDispatcher()
        try {
            runBlocking<Unit> {
                repeat(200) { round ->
                    val local = SnapshotSource("local")
                    val pole = FlagPole(GoldenFlagsSpec, listOf(local))
                    val writer = launch(writerThread) {
                        for (value in 1..20) local.setBox(stringFlag, FlagValueBox.String("v$value"))
                    }

                    val last = withTimeout(5_000) { pole.flow(GoldenFlagsSpec.stringFlag).first { it == "v20" } }

                    assertEquals("v20", last, "round $round")
                    writer.join()
                }
            }
        } finally {
            writerThread.close()
        }
    }

    @Test
    fun aBurstFromInsideAnUnconfinedEventLoopNeverLeavesAFlowStale() {
        // A writer inside an active Unconfined loop queues the subscriber's resume instead of
        // running it, so the source must not drop the flag's own change from a bounded buffer.
        val executor = Executors.newSingleThreadExecutor()
        val collectorThread = executor.asCoroutineDispatcher()
        try {
            runBlocking<Unit> {
                val local = SnapshotSource("local")
                val pole = FlagPole(GoldenFlagsSpec, listOf(local))
                val seen = Channel<Boolean>(Channel.UNLIMITED)
                val collector = launch(collectorThread) {
                    pole.flow(GoldenFlagsSpec.boolFlag).collect { seen.send(it) }
                }
                assertEquals(false, withTimeout(5_000) { seen.receive() })

                launch(Dispatchers.Unconfined) {
                    local.setBox(boolFlag, FlagValueBox.Bool(true))
                    repeat(200) { local.setBox(intFlag, FlagValueBox.Int(it.toLong())) }
                }.join()

                assertEquals(true, withTimeout(5_000) { seen.receive() })
                collector.cancel()
            }
        } finally {
            collectorThread.close()
        }
    }

    @Test
    fun aBusyCollectorStillSeesItsChangeAfterABurstOfOtherWrites() {
        // Review focus 1. The collector's only thread is blocked while its own key changes
        // and then 200 other keys change. A subscription running on that thread would
        // fall more than 64 changes behind, and the source would drop the one that matters.
        val executor = Executors.newSingleThreadExecutor()
        val busyThread = executor.asCoroutineDispatcher()
        try {
            runBlocking<Unit> {
                val local = SnapshotSource("local")
                val pole = FlagPole(GoldenFlagsSpec, listOf(local))
                val seen = Channel<Boolean>(Channel.UNLIMITED)
                val collector = launch(busyThread) { pole.flow(GoldenFlagsSpec.boolFlag).collect { seen.send(it) } }
                assertEquals(false, withTimeout(5_000) { seen.receive() })

                val blocked = CountDownLatch(1)
                val release = CountDownLatch(1)
                executor.execute {
                    blocked.countDown()
                    release.await()
                }
                blocked.await()
                local.setBox(boolFlag, FlagValueBox.Bool(true))
                repeat(200) { local.setBox(intFlag, FlagValueBox.Int(it.toLong())) }
                release.countDown()

                assertEquals(true, withTimeout(5_000) { seen.receive() })
                collector.cancel()
            }
        } finally {
            busyThread.close()
        }
    }
}
