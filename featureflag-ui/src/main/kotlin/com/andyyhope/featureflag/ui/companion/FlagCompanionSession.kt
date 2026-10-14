package com.andyyhope.featureflag.ui.companion

import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the session needs from a bridge source: a writable source that can be re-read and closed. */
internal interface CompanionSource : MutableFlagValueSource, AutoCloseable {
    /** Why the latest re-read failed, or null when it succeeded. */
    val lastFailure: StateFlow<FlagBridgeException?>

    /** Re-reads the host's overrides. Blocking; throws [FlagBridgeException], which also lands in [lastFailure]. */
    fun refresh()
}

/** Where the companion is with its host. */
internal sealed interface FlagCompanionState {
    /** Connecting, or opening a source for the schema the host published. */
    data object Connecting : FlagCompanionState

    /** Connected: [store] edits the host; [failure] is the latest re-read's, shown as a banner over the last good values. */
    data class Ready(val store: FlagEditingStore, val failure: FlagBridgeException?) : FlagCompanionState

    /** Not connected, or connected but the source could not be opened; [message] says why. */
    data class Unavailable(val message: String) : FlagCompanionState
}

/**
 * One host, from first connection to close: connects, opens a source for the schema the host
 * published, builds a [FlagEditingStore] over it, and keeps it current.
 *
 * Coming back to the foreground reconnects. A host that now publishes a different schema gets a
 * new source and a new store (a source is bound to the schema it was opened with); the same
 * schema keeps the store, so the screens keep their state, and re-reads the overrides instead.
 *
 * Plain Kotlin. Its state changes on [scope]; blocking source calls run on [ioDispatcher], which
 * is also the store's write dispatcher. Call it from one thread, the main thread in the app.
 */
internal class FlagCompanionSession(
    private val connect: suspend () -> FlagBridgeConnection,
    private val openSource: suspend (FlagSchema) -> CompanionSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private class Open(val source: CompanionSource, val store: FlagEditingStore, val jobs: List<Job>)

    private val stateFlow = MutableStateFlow<FlagCompanionState>(FlagCompanionState.Connecting)
    val state: StateFlow<FlagCompanionState> = stateFlow.asStateFlow()

    private var open: Open? = null
    private var work: Job? = null
    private var started = false

    /** Connects. Only the first call does anything. */
    fun start() {
        if (started) return
        started = true
        attempt { connectAndOpen() }
    }

    /** From [FlagCompanionState.Unavailable]: shows Connecting and connects again. Otherwise does nothing. */
    fun retry() {
        if (state.value !is FlagCompanionState.Unavailable) return
        stateFlow.value = FlagCompanionState.Connecting
        attempt { connectAndOpen() }
    }

    /**
     * The app came back to the foreground, after the first time. Ready: reconnects, and replaces
     * the store when the schema differs, or re-reads the overrides when it does not (or when the
     * host cannot be reached, which then shows in the banner). Unavailable: retries.
     */
    fun onForeground() {
        when (state.value) {
            is FlagCompanionState.Ready -> attempt { reconnect() }
            is FlagCompanionState.Unavailable -> retry()
            FlagCompanionState.Connecting -> Unit
        }
    }

    /** The banner's "Try again": re-reads the overrides; a failure stays in the banner. */
    fun refresh() {
        val source = open?.source ?: return
        scope.launch { refresh(source) }
    }

    /** Stops connecting and closes the source. */
    fun close() {
        work?.cancel()
        work = null
        dispose(open)
        open = null
    }

    /** One connection attempt at a time: a second asked for while one runs is the same attempt. */
    private fun attempt(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = scope.launch { block() }
    }

    private suspend fun connectAndOpen() {
        when (val connection = connect()) {
            is FlagBridgeConnection.Connected -> openStore(connection.schema)
            else -> stateFlow.value = FlagCompanionState.Unavailable(connection.message.orEmpty())
        }
    }

    private suspend fun reconnect() {
        val current = open ?: return
        val connection = connect()
        if (connection is FlagBridgeConnection.Connected && connection.schema.differsFrom(current.store.schema)) {
            openStore(connection.schema)
        } else {
            refresh(current.source)
        }
    }

    private suspend fun openStore(schema: FlagSchema) {
        val source = try {
            openSource(schema)
        } catch (error: FlagBridgeException) {
            dispose(open)
            open = null
            stateFlow.value = FlagCompanionState.Unavailable(error.message.orEmpty())
            return
        }
        val store = FlagEditingStore(schema, source, ioDispatcher)
        val failures = scope.launch {
            source.lastFailure.collect { failure ->
                if (open?.store === store) stateFlow.value = FlagCompanionState.Ready(store, failure)
            }
        }
        val previous = open
        open = Open(source, store, listOf(store.observeIn(scope), failures))
        dispose(previous)
        stateFlow.value = FlagCompanionState.Ready(store, source.lastFailure.value)
    }

    private suspend fun refresh(source: CompanionSource) {
        withContext(ioDispatcher) {
            try {
                source.refresh()
            } catch (ignored: FlagBridgeException) {
                // Published through lastFailure, which the banner shows.
            }
        }
    }

    /** The host stamps generatedAt on every launch, so a relaunch alone is not a change. */
    private fun FlagSchema.differsFrom(other: FlagSchema) = copy(generatedAt = other.generatedAt) != other

    private fun dispose(open: Open?) {
        open ?: return
        open.jobs.forEach { it.cancel() }
        open.source.close()
    }
}
