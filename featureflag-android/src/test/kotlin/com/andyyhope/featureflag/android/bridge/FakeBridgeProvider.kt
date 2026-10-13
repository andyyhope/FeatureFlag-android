package com.andyyhope.featureflag.android.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A host's bridge, in-process, for Robolectric. It speaks the protocol FlagBridgeProvider speaks,
 * over an in-memory map, and each test bends it to reach one status. Register it with
 * `Robolectric.buildContentProvider(FakeBridgeProvider::class.java).create(authority).get()`.
 */
class FakeBridgeProvider : ContentProvider() {

    /** The host package whose overrides URI is notified after an apply. */
    var hostPackage: String = ""

    var protocol: Int = FlagBridgeContract.PROTOCOL

    /** The published schema, or null for a host that never published. */
    var schema: FlagSchema? = null

    /** Served instead of [schema]'s bytes when set. */
    var schemaBytes: ByteArray? = null

    /** The overrides the host holds. */
    @Volatile
    var values: Map<FlagKey, FlagValueBox> = emptyMap()

    /** Signal names a host observer handles. */
    var handledSignals: Set<String> = emptySet()

    /** When set, every call answers with this status (and [forcedMessage]/[forcedSize]) instead. */
    var forcedStatus: String? = null
    var forcedMessage: String? = null
    var forcedSize: Int = 0

    /** How many calls throw DeadObjectException before one is answered, as a host that died would. */
    val deaths = AtomicInteger(0)

    /** When set, calls throw it, as the provider's own permission check does. */
    var refusal: SecurityException? = null

    /** When set, calls throw it, as a host bridge that fails inside its own handler does. */
    var failure: RuntimeException? = null

    /** When set, calls wait for it first, as a host stuck starting up does. */
    var stall: CountDownLatch? = null

    /** Every method called, in order, with its argument when it has one. */
    val calls = CopyOnWriteArrayList<String>()

    /** For each entry of [calls], whether it ran on the main thread. */
    val onMainThread = CopyOnWriteArrayList<Boolean>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        calls.add(if (arg == null) method else "$method $arg")
        onMainThread.add(Looper.getMainLooper().thread === Thread.currentThread())
        stall?.await(STALL_LIMIT_SECONDS, TimeUnit.SECONDS)
        refusal?.let { throw it }
        failure?.let { throw it }
        if (deaths.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw DeadObjectException()
        forcedStatus?.let { status ->
            return status(status).apply {
                forcedMessage?.let { putString(FlagBridgeContract.KEY_MESSAGE, it) }
                putInt(FlagBridgeContract.KEY_SIZE, forcedSize)
            }
        }
        return when (method) {
            FlagBridgeContract.METHOD_HELLO -> Bundle().apply { putInt(FlagBridgeContract.KEY_PROTOCOL, protocol) }
            FlagBridgeContract.METHOD_OVERRIDES -> overrides()
            FlagBridgeContract.METHOD_APPLY -> apply(extras)
            FlagBridgeContract.METHOD_SIGNAL -> Bundle().apply {
                putBoolean(FlagBridgeContract.KEY_HANDLED, arg in handledSignals)
            }
            else -> throw IllegalArgumentException("Unknown bridge method '$method'.")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        calls.add("openFile ${uri.path}")
        val bytes = schemaBytes ?: schema?.jsonBytes() ?: throw FileNotFoundException("No schema has been published.")
        val file = File.createTempFile("served-schema", ".json").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Changes the host's overrides as the host's own code would, and tells observers. */
    fun hostWrites(values: Map<FlagKey, FlagValueBox>) {
        this.values = values
        notifyObservers()
    }

    private fun overrides(): Bundle {
        if (schema == null) return status(FlagBridgeContract.STATUS_NOT_PUBLISHED)
        val bytes = FlagPayload(values).jsonBytes()
        return status(FlagBridgeContract.STATUS_OK).apply { putByteArray(FlagBridgeContract.KEY_PAYLOAD, bytes) }
    }

    private fun apply(extras: Bundle?): Bundle {
        val schema = schema ?: return status(FlagBridgeContract.STATUS_NOT_PUBLISHED)
        val payload = try {
            FlagPayload.decode(checkNotNull(extras?.getByteArray(FlagBridgeContract.KEY_PAYLOAD)), schema)
        } catch (error: FlagImportException) {
            return status(FlagBridgeContract.STATUS_REJECTED).apply { putString(FlagBridgeContract.KEY_MESSAGE, error.message) }
        }
        val removals = extras?.getStringArray(FlagBridgeContract.KEY_REMOVE).orEmpty().map(::FlagKey)
        values = values + payload.values - removals.toSet()
        notifyObservers()
        return status(FlagBridgeContract.STATUS_OK)
    }

    private fun notifyObservers() {
        context?.contentResolver?.notifyChange(FlagBridgeContract.overridesUri(hostPackage), null)
    }

    private fun status(status: String) = Bundle().apply { putString(FlagBridgeContract.KEY_STATUS, status) }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val STALL_LIMIT_SECONDS = 10L
    }
}
