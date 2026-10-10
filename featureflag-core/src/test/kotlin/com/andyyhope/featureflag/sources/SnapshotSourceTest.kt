package com.andyyhope.featureflag.sources

import app.cash.turbine.test
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotSourceTest {

    private val a = FlagKey("a")
    private val b = FlagKey("b")

    @Test
    fun aSnapshotCanBeSeededAtInit() {
        val source = SnapshotSource("seed", mapOf(a to FlagValueBox.Int(1)))

        assertEquals(FlagValueBox.Int(1), source.box(a, FlagValueType.Int))
        assertEquals("seed", source.sourceName)
    }

    @Test
    fun theDefaultNameIsTheSwiftOne() {
        assertEquals("Snapshot", SnapshotSource().sourceName)
    }

    @Test
    fun aSnapshotHandsBackWhateverItHoldsWhateverTheType() {
        // Type checking is the resolver's job.
        val source = SnapshotSource(values = mapOf(a to FlagValueBox.String("x")))

        assertEquals(FlagValueBox.String("x"), source.box(a, FlagValueType.Int))
    }

    @Test
    fun writingTheSameValueAgainEmitsNothing() = runBlocking<Unit> {
        // Otherwise every idle write would wake every subscriber.
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))

        source.changes.test {
            source.setBox(a, FlagValueBox.Int(1))
            expectNoEvents()

            source.setBox(a, FlagValueBox.Int(2))
            assertEquals(FlagChange.Keys(setOf(a)), awaitItem())
        }
    }

    @Test
    fun removingAValueEmitsAChange() = runBlocking<Unit> {
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))

        source.changes.test {
            source.setBox(a, null)
            assertEquals(FlagChange.Keys(setOf(a)), awaitItem())
        }
        assertNull(source.box(a, FlagValueType.Int))
    }

    @Test
    fun removingSomethingAbsentEmitsNothing() = runBlocking<Unit> {
        val source = SnapshotSource("local")

        source.changes.test {
            source.setBox(FlagKey("absent"), null)
            expectNoEvents()
        }
    }

    @Test
    fun replacingEverythingReportsOneWholesaleChange() = runBlocking<Unit> {
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))

        source.changes.test {
            source.replaceAll(mapOf(b to FlagValueBox.Int(2)))
            assertEquals(FlagChange.All, awaitItem())
            expectNoEvents()
        }
        assertNull(source.box(a, FlagValueType.Int))
        assertEquals(FlagValueBox.Int(2), source.box(b, FlagValueType.Int))
    }

    @Test
    fun valuesIsASnapshotThatLaterWritesDoNotChange() {
        val source = SnapshotSource("local", mapOf(a to FlagValueBox.Int(1)))
        val before = source.values

        source.setBox(b, FlagValueBox.Int(2))

        assertEquals(mapOf(a to FlagValueBox.Int(1)), before)
        assertEquals(mapOf(a to FlagValueBox.Int(1), b to FlagValueBox.Int(2)), source.values)
    }

    @Test
    fun aSeedMapChangedAfterwardsDoesNotLeakIn() {
        val seed = mutableMapOf<FlagKey, FlagValueBox>(a to FlagValueBox.Int(1))
        val source = SnapshotSource(values = seed)

        seed[b] = FlagValueBox.Int(2)

        assertEquals(mapOf(a to FlagValueBox.Int(1)), source.values)
    }

    @Test
    fun aSnapshotIsSafeUnderConcurrentWrites() {
        val source = SnapshotSource("local")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 200).map { index ->
                Callable { source.setBox(FlagKey("key.${index % 8}"), FlagValueBox.Int(index.toLong())) }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(8, source.values.size)
    }

    @Test
    fun concurrentReadersNeverSeeALostOrTornWrite() {
        // Each writer owns one key, so the final value of every key is known. A write that
        // replaced the map without the lock would drop another thread's key.
        val source = SnapshotSource("local")
        val writers = 8
        val writesPerKey = 1_000
        val pool = Executors.newFixedThreadPool(writers + 4)
        try {
            val writeTasks = (0 until writers).map { index ->
                Callable {
                    val key = FlagKey("key.$index")
                    for (value in 0 until writesPerKey) source.setBox(key, FlagValueBox.Int(value.toLong()))
                }
            }
            val readTasks = (0 until 4).map { _ ->
                Callable {
                    repeat(writesPerKey) { _ ->
                        for (index in 0 until writers) {
                            val box = source.box(FlagKey("key.$index"), FlagValueType.Int)
                            assertTrue(box == null || box is FlagValueBox.Int, "torn read: $box")
                        }
                    }
                }
            }
            pool.invokeAll(writeTasks + readTasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val expected = (0 until writers).associate { FlagKey("key.$it") to FlagValueBox.Int(writesPerKey - 1L) }
        assertEquals(expected, source.values)
    }
}
