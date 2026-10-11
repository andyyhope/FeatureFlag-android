package com.andyyhope.featureflag.android.bridge

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow

/**
 * A live view of a host's companion overrides, for a companion's pole or editor.
 *
 * Reads come from an in-memory copy and never cross processes. The copy is refreshed when the
 * host's overrides change, which the host announces through a `ContentObserver` registered with
 * the system, so it keeps working when the host process dies and starts again. Writes are
 * blocking calls into the host: make them off the main thread. Close the source when done.
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

    private val thread = HandlerThread("FlagBridgeSource").apply { start() }

    private val observer = object : ContentObserver(Handler(thread.looper)) {
        override fun onChange(selfChange: Boolean) {
            try {
                refresh()
            } catch (ignored: FlagBridgeException) {
                // The next change, or an explicit refresh, tries again.
            }
        }
    }

    init {
        client.resolver.registerContentObserver(FlagBridgeContract.overridesUri(client.hostPackage), false, observer)
        try {
            refresh()
        } catch (ignored: FlagBridgeException) {
            // Start empty; the companion shows the defaults until the host answers.
        }
    }

    override val changes: Flow<FlagChange> = emitter.changes

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
        val bytes = FlagPayload(values).jsonBytes()
        if (bytes.size > FlagBridgeContract.MAX_PAYLOAD_BYTES) {
            throw FlagBridgeException.TooLarge(bytes.size, FlagBridgeContract.MAX_PAYLOAD_BYTES)
        }
        val extras = Bundle().apply {
            putByteArray(FlagBridgeContract.KEY_PAYLOAD, bytes)
            putStringArray(FlagBridgeContract.KEY_REMOVE, removals)
        }
        val reply = request(FlagBridgeContract.METHOD_APPLY, extras)
        if (reply.getString(FlagBridgeContract.KEY_STATUS) != FlagBridgeContract.STATUS_OK) throw failure(reply)
        val changed = synchronized(lock) {
            val updated = cache.toMutableMap()
            val changed = changes.filter { (key, box) -> updated[key] != box }.keys
            changes.forEach { (key, box) -> if (box == null) updated.remove(key) else updated[key] = box }
            cache = updated
            changed
        }
        if (changed.isNotEmpty()) emitter.emit(FlagChange.Keys(changed))
    }

    /** Blocking IPC: re-reads the host's overrides; emits [FlagChange.All] when anything differs. Throws [FlagBridgeException]. */
    public fun refresh() {
        val reply = request(FlagBridgeContract.METHOD_OVERRIDES)
        if (reply.getString(FlagBridgeContract.KEY_STATUS) != FlagBridgeContract.STATUS_OK) throw failure(reply)
        val values = decode(reply.getByteArray(FlagBridgeContract.KEY_PAYLOAD))
        val changed = synchronized(lock) {
            (values != cache).also { cache = values }
        }
        if (changed) emitter.emit(FlagChange.All)
    }

    /** Unregisters the ContentObserver and stops its thread. */
    override fun close() {
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
