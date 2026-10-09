package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.coroutines.cancellation.CancellationException

/**
 * Two config layers per environment, kept in step with the environment the app is in. The
 * local layer ships with the app; the remote one is fetched.
 *
 * Within the pair, remote beats local, and both beat the compiled default. [sources] hands
 * them back in that order, to splice into a pole above the defaults and below any by-hand
 * override.
 *
 * Loading an environment clears each layer before loading it. A layer whose loader
 * returns `null` or throws is left empty, so a failed fetch falls back to the local
 * config and then the defaults. It never leaves the previous environment's values in place.
 * The library does no networking and reads no files: the loaders supply the bytes.
 */
public class EnvironmentConfiguration<E>(
    schema: FlagSchema,
    mapper: RemoteOverrideMapper = DotPathMapper,
    localName: String = "Local",
    remoteName: String = "Remote",
    private val local: (E) -> ByteArray?,
    remote: suspend (E) -> ByteArray? = { null },
) {

    /** One for a container, described with the key encoding its pole uses. */
    public constructor(
        spec: FlagContainerSpec<*>,
        keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
        mapper: RemoteOverrideMapper = DotPathMapper,
        localName: String = "Local",
        remoteName: String = "Remote",
        local: (E) -> ByteArray?,
        remote: suspend (E) -> ByteArray? = { null },
    ) : this(FlagSchema.of(spec, keyEncoding), mapper, localName, remoteName, local, remote)

    /** The bundled layer: above the compiled defaults, below the remote layer. */
    public val localSource: RemoteOverrideSource = RemoteOverrideSource(schema, mapper, localName)

    /** The fetched layer, the higher of the pair. */
    public val remoteSource: RemoteOverrideSource = RemoteOverrideSource(schema, mapper, remoteName)

    /** Both layers in precedence order, remote first. */
    public val sources: List<FlagValueSource> get() = listOf(remoteSource, localSource)

    // A monotonic token, so a slow load cannot apply over a newer one. Guarded by lock, with remote.
    private val lock = Any()
    private var epoch = 0L
    private var remote: suspend (E) -> ByteArray? = remote

    /**
     * Loads both layers for an environment: local first, then remote. The outcome says what
     * happened to each, since one can succeed while the other does not.
     *
     * Cancellation propagates rather than being reported as [LayerOutcome.Failed]. A layer
     * cleared before the cancellation stays cleared.
     */
    public suspend fun load(environment: E): LoadOutcome {
        val (mine, remoteLoader) = synchronized(lock) {
            epoch += 1
            epoch to remote
        }
        val localOutcome = applyLayer(localSource, mine) { local(environment) }
        val remoteOutcome = applyLayer(remoteSource, mine) { remoteLoader(environment) }
        return LoadOutcome(localOutcome, remoteOutcome)
    }

    /**
     * Supplies the remote layer's bytes, replacing any loader given at construction.
     *
     * Set it after the pole exists when the fetch depends on a flag the local layer
     * carries. [load] applies the local layer before this runs, so that value is already
     * resolvable.
     */
    public fun fetchRemote(load: suspend (E) -> ByteArray?) {
        synchronized(lock) { remote = load }
    }

    private fun isCurrent(mine: Long): Boolean = synchronized(lock) { mine == epoch }

    // A loader is caller code and may throw anything; every failure becomes Failed except
    // cancellation, which must reach the caller.
    @Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
    private suspend fun applyLayer(
        source: RemoteOverrideSource,
        mine: Long,
        load: suspend () -> ByteArray?,
    ): LayerOutcome {
        source.clear()
        return try {
            val bytes = load() ?: return LayerOutcome.Absent
            // Re-checked after the await: a newer load may own the source now.
            if (!isCurrent(mine)) return LayerOutcome.Superseded
            source.apply(bytes)
            LayerOutcome.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LayerOutcome.Failed(error)
        }
    }
}

/** What became of each layer when an environment was loaded. */
public data class LoadOutcome(public val local: LayerOutcome, public val remote: LayerOutcome) {

    /** Both layers loaded as far as they could: applied, absent or superseded. False if either failed. */
    public val isComplete: Boolean get() = local.isComplete && remote.isComplete
}

/** What became of one config layer. */
public sealed interface LayerOutcome {

    /** The config was loaded and applied. */
    public data object Applied : LayerOutcome

    /** The loader returned `null`: there is no config of this kind for this environment. */
    public data object Absent : LayerOutcome

    /** The loader threw, or its config was rejected. The layer is left empty. */
    public data class Failed(public val error: Exception) : LayerOutcome

    /** A newer load started first and owns the layer now, so this result was dropped. */
    public data object Superseded : LayerOutcome

    public val isApplied: Boolean get() = this == Applied

    /** Not a failure: applied, legitimately absent, or dropped for a newer load. */
    public val isComplete: Boolean get() = this !is Failed
}
