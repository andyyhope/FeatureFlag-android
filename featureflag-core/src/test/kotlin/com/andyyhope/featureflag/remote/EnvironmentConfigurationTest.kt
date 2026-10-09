package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@FlagContainer
internal interface EnvFlags {
    @Flag(description = "Page size", remoteKey = "pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Beta", remoteKey = "beta")
    val beta: Boolean get() = false

    @Flag(description = "Label", remoteKey = "label")
    val label: String get() = "prod"
}

// runCurrent() is experimental in kotlinx-coroutines-test 1.10.2, and allWarningsAsErrors applies to tests too.
@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentConfigurationTest {

    private enum class Env { Staging, Production }

    private class Offline : Exception("offline")

    private fun bytes(json: String): ByteArray = json.toByteArray()

    // Layering and precedence

    @Test
    fun remoteWinsOverLocalWhichWinsOverTheDefault() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { bytes("""{ "pageSize": 20, "label": "local" }""") },
            remote = { bytes("""{ "pageSize": 25 }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        config.load(Env.Staging)

        assertEquals(25, pole.flags.pageSize, "remote wins where both set it")
        assertEquals("local", pole.flags.label, "local wins where remote is silent")
        assertFalse(pole.flags.beta, "neither set it: the compiled default")
    }

    @Test
    fun provenanceNamesTheLayerEachValueCameFrom() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { bytes("""{ "label": "local" }""") },
            remote = { bytes("""{ "pageSize": 25 }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)
        config.load(Env.Staging)

        assertEquals("Remote", pole.resolution(FlagKey("page-size"), FlagValueType.Int).sourceName)
        assertEquals("Local", pole.resolution(FlagKey("label"), FlagValueType.String).sourceName)
        assertTrue(pole.resolution(FlagKey("beta"), FlagValueType.Bool).isDefault)
    }

    @Test
    fun theSourcesAreRemoteThenLocal() {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { null })
        assertEquals(listOf(config.remoteSource, config.localSource), config.sources)
        assertEquals(listOf("Remote", "Local"), config.sources.map { it.sourceName })
    }

    // Switching environments

    @Test
    fun switchingClearsThePreviousEnvironmentsValues() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { env -> if (env == Env.Staging) bytes("""{ "label": "staging-local" }""") else null },
            remote = { env -> if (env == Env.Staging) bytes("""{ "pageSize": 25 }""") else bytes("""{ "beta": true }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        config.load(Env.Staging)
        assertEquals(25, pole.flags.pageSize)
        assertEquals("staging-local", pole.flags.label)

        config.load(Env.Production)

        assertEquals(10, pole.flags.pageSize, "staging's remote value is gone")
        assertEquals("prod", pole.flags.label, "staging's local value is gone")
        assertTrue(pole.flags.beta, "production's remote value is applied")
    }

    // A layer that is absent or fails

    @Test
    fun anAbsentLocalLayerLeavesTheRemoteAndDefaults() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { bytes("""{ "pageSize": 25 }""") },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertEquals(LayerOutcome.Absent, outcome.local)
        assertTrue(outcome.remote.isApplied)
        assertEquals(25, pole.flags.pageSize)
        assertEquals("prod", pole.flags.label)
    }

    @Test
    fun aFailedRemoteFetchFallsBackToTheLocalLayer() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { bytes("""{ "pageSize": 20, "label": "local" }""") },
            remote = { throw Offline() },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertTrue(outcome.local.isApplied)
        assertIs<Offline>(assertIs<LayerOutcome.Failed>(outcome.remote).error)
        assertFalse(outcome.isComplete)
        assertEquals(20, pole.flags.pageSize, "the local layer stands in for the missing remote")
    }

    @Test
    fun aFailedRemoteDoesNotLeaveStaleValuesFromThePreviousEnvironment() = runTest {
        var online = true
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { env ->
                if (env == Env.Production && !online) throw Offline()
                bytes("""{ "pageSize": 25 }""")
            },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        config.load(Env.Staging)
        assertEquals(25, pole.flags.pageSize)

        online = false
        config.load(Env.Production)

        assertEquals(10, pole.flags.pageSize, "a failed fetch falls to defaults, not stale values")
    }

    @Test
    fun aMalformedLayerIsReportedAndDoesNotApply() = runTest {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("{ not json") })
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertIs<LayerOutcome.Failed>(outcome.local)
        assertEquals(10, pole.flags.pageSize)
    }

    @Test
    fun onlyAFailureMakesALoadIncomplete() {
        assertTrue(LoadOutcome(LayerOutcome.Applied, LayerOutcome.Absent).isComplete)
        assertTrue(LoadOutcome(LayerOutcome.Superseded, LayerOutcome.Applied).isComplete)
        assertFalse(LoadOutcome(LayerOutcome.Applied, LayerOutcome.Failed(IllegalStateException("x"))).isComplete)
        assertTrue(LayerOutcome.Applied.isApplied)
        assertFalse(LayerOutcome.Superseded.isApplied)
    }

    // Overlapping loads

    @Test
    fun aStaleLoadDoesNotClobberANewerOne() = runTest(StandardTestDispatcher()) {
        // Staging's fetch is slow, and production starts while it waits. Production must win
        // even though staging resolves last.
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { env ->
                if (env == Env.Staging) delay(60)
                bytes(if (env == Env.Staging) """{ "pageSize": 25 }""" else """{ "pageSize": 99 }""")
            },
        )
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val stale = async { config.load(Env.Staging) }
        delay(10)
        val winner = async { config.load(Env.Production) }
        val staleOutcome = stale.await()
        winner.await()

        assertEquals(99, pole.flags.pageSize, "production won; staging's late fetch was dropped")
        assertEquals(LayerOutcome.Superseded, staleOutcome.remote)
    }

    @Test
    fun aSupersededLoadWhoseFetchFindsNothingReportsAbsent() = runTest(StandardTestDispatcher()) {
        // Swift checks for "nothing to load" before it checks for a newer load.
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { env ->
                if (env == Env.Staging) {
                    delay(60)
                    null
                } else {
                    bytes("""{ "pageSize": 99 }""")
                }
            },
        )

        val stale = async { config.load(Env.Staging) }
        delay(10)
        config.load(Env.Production)

        assertEquals(LayerOutcome.Absent, stale.await().remote)
    }

    // Cancellation (Review Focus 5)

    @Test
    fun cancellingALoadPropagatesAndLeavesTheRemoteLayerCleared() = runTest(StandardTestDispatcher()) {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { null }, remote = { awaitCancellation() })
        val pole = FlagPole(EnvFlagsSpec, config.sources)
        config.remoteSource.apply(bytes("""{ "pageSize": 25 }"""))

        val loading = launch { config.load(Env.Staging) }
        runCurrent()
        loading.cancel()
        loading.join()

        assertTrue(loading.isCancelled)
        assertEquals(10, pole.flags.pageSize, "the layer was cleared before the fetch and stays cleared")
    }

    @Test
    fun aLoaderThrowingCancellationIsRethrownRatherThanReportedAsFailed() = runTest {
        val config = EnvironmentConfiguration<Env>(
            EnvFlagsSpec,
            local = { null },
            remote = { throw CancellationException("the caller gave up") },
        )
        assertFailsWith<CancellationException> { config.load(Env.Staging) }
    }

    // A remote loader that depends on the local layer

    @Test
    fun aLateBoundRemoteLoaderRunsAfterLocalIsApplied() = runTest {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("""{ "label": "from-local" }""") })
        val pole = FlagPole(EnvFlagsSpec, config.sources)
        var seenLabel: String? = null
        config.fetchRemote {
            seenLabel = pole.flags.label
            bytes("""{ "pageSize": 25 }""")
        }

        val outcome = config.load(Env.Staging)

        assertEquals("from-local", seenLabel, "the loader runs after the local layer is applied")
        assertTrue(outcome.remote.isApplied)
        assertEquals(25, pole.flags.pageSize, "the bytes the loader returned were applied")
    }

    @Test
    fun aLateBoundRemoteLoaderResolvesThroughLayersAboveLocal() = runTest {
        val companion = SnapshotSource("By hand")
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("""{ "label": "from-local" }""") })
        val pole = FlagPole(EnvFlagsSpec, listOf(companion) + config.sources)
        pole.setOverride(EnvFlagsSpec.label, "from-companion")
        var seenLabel: String? = null
        config.fetchRemote {
            seenLabel = pole.flags.label
            null
        }

        config.load(Env.Staging)

        assertEquals("from-companion", seenLabel, "resolution walks the whole stack")
    }

    @Test
    fun withNoRemoteLoaderTheRemoteLayerIsAbsent() = runTest {
        val config = EnvironmentConfiguration<Env>(EnvFlagsSpec, local = { bytes("""{ "label": "local" }""") })
        val pole = FlagPole(EnvFlagsSpec, config.sources)

        val outcome = config.load(Env.Staging)

        assertEquals(LayerOutcome.Absent, outcome.remote)
        assertEquals("local", pole.flags.label)
    }
}
