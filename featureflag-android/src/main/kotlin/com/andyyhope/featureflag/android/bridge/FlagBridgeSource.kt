package com.andyyhope.featureflag.android.bridge

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * A live view of a host's companion overrides, for a companion's pole or editor.
 *
 * Reads come from an in-memory copy and never cross processes. The copy is refreshed when the
 * host's overrides change, which the host announces through a `ContentObserver` registered with
 * the system, so it keeps working when the host process dies and starts again. Writes are
 * blocking calls into the host: make them off the main thread. Close the source when done.
 *
 * Creating one throws [FlagBridgeException.Unavailable] when the host's provider cannot be found
 * or the companion may not observe it.
 */
public class FlagBridgeSource internal constructor(
    private val client: FlagBridgeClient,
    private val schema: FlagSchema,
    override val sourceName: String,
) : MutableFlagValueSource, AutoCloseable {

    private val emitter = FlagChangeEmitter()
    private val lock = Any()

    @Volatile
    private var cache: Map<FlagKey, FlagValueBox> = emptyMap()

    // Every refresh takes a ticket before its IPC; a reply is applied only if no newer ticket has been.
    private val tickets = AtomicLong()
    private var appliedTicket = 0L

    @Volatile
    private var closed = false

    private val failureState = MutableStateFlow<FlagBridgeException?>(null)

    private val thread = HandlerThread("FlagBridgeSource").apply { start() }

    private val observer = object : ContentObserver(Handler(thread.looper)) {
        override fun onChange(selfChange: Boolean) {
            // quitSafely still runs what was queued before close(); a closed source does nothing.
            if (closed) return
            try {
                refresh()
            } catch (ignored: FlagBridgeException) {
                // Reported through lastFailure; the next change, or an explicit refresh, tries again.
            }
        }
    }

    init {
        start()
    }

    // Rethrown after cleanup: whatever fails the constructor must not leave the thread running.
    @Suppress("TooGenericExceptionCaught")
    private fun start() {
        try {
            register()
            try {
                refresh()
            } catch (ignored: FlagBridgeException) {
                // Start empty, reported through lastFailure; the companion shows the defaults until the host answers.
            }
        } catch (error: RuntimeException) {
            abandon()
            throw error
        }
    }

    override val changes: Flow<FlagChange> = emitter.changes

    /**
     * Why the latest refresh failed, or null when it succeeded. Set by a failed first refresh, observer
     * refresh or explicit [refresh]; the cache keeps its last good values. Cleared by the next successful
     * refresh. A write does not change it: a failed write is thrown to its caller, and a successful one
     * does not prove the whole copy is current.
     */
    public val lastFailure: StateFlow<FlagBridgeException?> = failureState.asStateFlow()

    /** From the in-memory copy; never a call into the host. */
    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = cache[key]?.takeIf { it.matches(type) }

    /** Blocking IPC: one METHOD_APPLY. Call off the main thread. Throws [FlagBridgeException]. */
    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        setBoxes(mapOf(key to box))
    }

    /** Blocking IPC: one METHOD_APPLY carrying every change, one host commit. Throws [FlagBridgeException]. */
    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        if (changes.isEmpty()) return
        val values = changes.entries.mapNotNull { (key, box) -> box?.let { key to it } }.toMap()
        val removals = changes.filterValues { it == null }.keys.map { it.rawValue }.toTypedArray()
        val bytes = try {
            FlagPayload(values).jsonBytes()
        } catch (error: FlagSerializationException.NonFiniteNumber) {
            val key = error.key ?: values.entries.first { it.value.containsNonFiniteNumber }.key
            throw FlagBridgeException.Rejected(FlagException.UnsupportedValue(key).message.orEmpty())
        }
        if (bytes.size > FlagBridgeContract.MAX_PAYLOAD_BYTES) {
            throw FlagBridgeException.TooLarge(bytes.size, FlagBridgeContract.MAX_PAYLOAD_BYTES)
        }
        val extras = Bundle().apply {
            putByteArray(FlagBridgeContract.KEY_PAYLOAD, bytes)
            putStringArray(FlagBridgeContract.KEY_REMOVE, removals)
        }
        // Taken before the IPC: a refresh issued once the write began may already carry newer state.
        val ticket = tickets.incrementAndGet()
        val reply = request(FlagBridgeContract.METHOD_APPLY, extras)
        if (reply.getString(FlagBridgeContract.KEY_STATUS) != FlagBridgeContract.STATUS_OK) throw failure(reply)
        val changed = synchronized(lock) {
            // A newer ticket has been applied: that refresh began after the write and already holds it, or
            // will after the host's own change notification. Applying the write now could revert newer state.
            if (appliedTicket > ticket) return
            appliedTicket = ticket
            val updated = cache.toMutableMap()
            val changed = changes.filter { (key, box) -> updated[key] != box }.keys
            changes.forEach { (key, box) -> if (box == null) updated.remove(key) else updated[key] = box }
            cache = updated
            changed
        }
        if (changed.isNotEmpty()) emitter.emit(FlagChange.Keys(changed))
    }

    /**
     * Blocking IPC: re-reads the host's overrides; emits [FlagChange.All] when anything differs. Throws
     * [FlagBridgeException], which is also published as [lastFailure]. A reply that a newer refresh or
     * write has already overtaken is dropped: it neither changes the copy nor [lastFailure]. Still works
     * after [close] as an explicit call; only the host's change notifications stop. A failure that was
     * overtaken is still thrown to this caller; [lastFailure] is left to the newer result.
     */
    public fun refresh() {
        val ticket = tickets.incrementAndGet()
        val values = try {
            val reply = request(FlagBridgeContract.METHOD_OVERRIDES)
            if (reply.getString(FlagBridgeContract.KEY_STATUS) != FlagBridgeContract.STATUS_OK) throw failure(reply)
            decode(reply.getByteArray(FlagBridgeContract.KEY_PAYLOAD))
        } catch (error: FlagBridgeException) {
            synchronized(lock) {
                if (ticket > appliedTicket) {
                    appliedTicket = ticket
                    failureState.value = error
                }
            }
            throw error
        }
        val changed = synchronized(lock) {
            if (ticket > appliedTicket) {
                appliedTicket = ticket
                failureState.value = null
                (values != cache).also { cache = values }
            } else {
                false
            }
        }
        if (changed) emitter.emit(FlagChange.All)
    }

    /** The system refuses to observe a provider it cannot resolve or the companion may not use. */
    private fun register() {
        try {
            client.resolver.registerContentObserver(FlagBridgeContract.overridesUri(client.hostPackage), false, observer)
        } catch (error: SecurityException) {
            throw FlagBridgeException.Unavailable(error)
        }
    }

    /** A constructor that fails must not leave its thread or observer behind. */
    private fun abandon() {
        client.resolver.unregisterContentObserver(observer)
        thread.quitSafely()
    }

    /** Unregisters the ContentObserver and stops its thread; later change notifications do nothing. */
    override fun close() {
        closed = true
        client.resolver.unregisterContentObserver(observer)
        thread.quitSafely()
    }

    // Callers only ever see FlagBridgeExceptions, so any failure inside the provider call is Unavailable.
    @Suppress("TooGenericExceptionCaught")
    private fun request(method: String, extras: Bundle? = null): Bundle {
        val reply = try {
            client.call(method, extras)
        } catch (error: Exception) { // a RemoteException or any RuntimeException
            throw FlagBridgeException.Unavailable(error)
        }
        return reply ?: throw FlagBridgeException.Unavailable(null)
    }

    /** The exception for a reply whose status is not [FlagBridgeContract.STATUS_OK]. */
    private fun failure(reply: Bundle): FlagBridgeException {
        val status = reply.getString(FlagBridgeContract.KEY_STATUS)
        return when (status) {
            FlagBridgeContract.STATUS_REJECTED -> FlagBridgeException.Rejected(reply.getString(FlagBridgeContract.KEY_MESSAGE).orEmpty())
            FlagBridgeContract.STATUS_NOT_PUBLISHED -> FlagBridgeException.NotPublished()
            FlagBridgeContract.STATUS_TOO_LARGE ->
                FlagBridgeException.TooLarge(reply.getInt(FlagBridgeContract.KEY_SIZE), FlagBridgeContract.MAX_PAYLOAD_BYTES)
            else -> FlagBridgeException.Unavailable(IllegalStateException("The host answered with the unknown status '$status'."))
        }
    }

    // Rejected carries the import message as its reason; it has no cause to chain.
    @Suppress("SwallowedException")
    private fun decode(bytes: ByteArray?): Map<FlagKey, FlagValueBox> {
        val payload = bytes ?: throw FlagBridgeException.Unavailable(IllegalStateException("The host answered ok without a payload."))
        return try {
            FlagPayload.decode(payload, schema).values
        } catch (error: FlagImportException) {
            // The host's overrides no longer fit this companion's copy of its schema: reconnect.
            throw FlagBridgeException.Rejected(error.message.orEmpty())
        }
    }
}
