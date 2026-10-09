package com.andyyhope.featureflag.sources

import app.cash.turbine.test
import com.andyyhope.featureflag.values.FlagKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals

class FlagChangeEmitterTest {

    private val a = FlagKey("a")
    private val b = FlagKey("b")

    private fun keys(first: FlagKey, vararg rest: String) = FlagChange.Keys(setOf(first) + rest.map { FlagKey(it) })

    @Test
    fun anInboxMergesByTheRules() = runBlocking<Unit> {
        suspend fun merged(vararg changes: FlagChange): FlagChange {
            val inbox = ChangeInbox()
            changes.forEach { inbox.offer(it) }
            return inbox.next()
        }
        assertEquals(keys(a), merged(keys(a)))
        assertEquals(FlagChange.All, merged(FlagChange.All))
        assertEquals(FlagChange.All, merged(FlagChange.All, keys(a)))
        assertEquals(FlagChange.All, merged(keys(a), FlagChange.All))
        assertEquals(keys(a, "b"), merged(keys(a), keys(b)))
    }

    @Test
    fun aCollectorReceivesAnEmit() = runBlocking<Unit> {
        val emitter = FlagChangeEmitter()
        emitter.changes.test {
            emitter.emit(keys(a))
            assertEquals(keys(a), awaitItem())
        }
    }

    @Test
    fun aBlockedCollectorReceivesOneChangeWithEveryKey() = runBlocking<Unit> {
        val emitter = FlagChangeEmitter()
        val all = (0 until 1_000).map { FlagKey("k$it") }.toSet()
        val received = mutableListOf<FlagChange>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            emitter.changes.collect { received += it }
        }
        // The collector cannot run until this coroutine yields, so every emit lands first.
        all.forEach { emitter.emit(FlagChange.Keys(setOf(it))) }
        yield()
        job.cancel()
        assertEquals(listOf<FlagChange>(FlagChange.Keys(all)), received)
    }

    @Test
    fun allAbsorbsKeys() = runBlocking<Unit> {
        val emitter = FlagChangeEmitter()
        val received = mutableListOf<FlagChange>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            emitter.changes.collect { received += it }
        }
        emitter.emit(keys(a))
        emitter.emit(FlagChange.All)
        emitter.emit(keys(b))
        yield()
        job.cancel()
        assertEquals(listOf<FlagChange>(FlagChange.All), received)
    }

    @Test
    fun aCancelledCollectorIsRemoved() = runBlocking<Unit> {
        val emitter = FlagChangeEmitter()
        emitter.changes.test {
            assertEquals(1, emitter.subscriberCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, emitter.subscriberCount)
    }

    @Test
    fun emitWithNoCollectorsReturnsAndDeliversNothingLater() = runBlocking<Unit> {
        val emitter = FlagChangeEmitter()
        repeat(1_000) { emitter.emit(keys(a)) }
        assertEquals(0, emitter.subscriberCount)
        emitter.changes.test {
            expectNoEvents()
        }
    }
}
