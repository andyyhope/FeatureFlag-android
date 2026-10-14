package com.andyyhope.featureflag.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lets a companion app signed with the host's key read the host's schema, read and write its
 * companion overrides, observe them, and send it signals.
 *
 * Merged into the host by depending on `featureflag-bridge`. It needs no registration: it serves
 * the schema `publishSchema(context)` writes, the overrides in the file
 * [SharedPreferencesSource.companion] reads, and the signals [FlagSignalChannel.host] delivers.
 *
 * A host cannot use the bridge on itself: the provider refuses callers in its own process, so the
 * host reads and writes through its pole.
 */
public class FlagBridgeProvider : ContentProvider() {

    private lateinit var source: SharedPreferencesSource
    private lateinit var preferences: SharedPreferences
    private lateinit var overridesUri: Uri

    // Strongly held: SharedPreferences keeps its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.contentResolver?.notifyChange(overridesUri, null)
    }

    @Volatile
    private var started = false

    /** How long the first answer waits for the main looper. Tests shorten it. */
    internal var startupTimeout: Duration = STARTUP_TIMEOUT

    /**
     * Each delayed signal's cancellation, by ticket: opened by [FlagBridgeContract.METHOD_CANCEL],
     * removed when the signal stops waiting. A cancel for a signal that never arrives, or already
     * finished, stays; the oldest go once there are more than [MAX_CANCELLATIONS].
     */
    private val cancellations = object : LinkedHashMap<String, CountDownLatch>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CountDownLatch>?): Boolean =
            size > MAX_CANCELLATIONS
    }

    override fun onCreate(): Boolean {
        val context = checkNotNull(context)
        source = SharedPreferencesSource.companion(context)
        preferences = context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE)
        overridesUri = FlagBridgeContract.overridesUri(context.packageName)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /** The clock a signal's deadline is read against; the same one the client reads. Tests replace it. */
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val enteredAt = elapsedRealtime()
        val context = checkNotNull(context)
        // The manifest permission already keeps other apps from opening the provider; this is defence in depth.
        context.enforceCallingPermission(
            FlagBridgeContract.permission(context.packageName),
            "Only apps signed with the host's key may use the FeatureFlag bridge",
        )
        // A client that has already given up gets its answer before the startup barrier holds a binder thread for it.
        if (method == FlagBridgeContract.METHOD_SIGNAL && deadlineExpired(extras, enteredAt)) {
            return Bundle().apply { putBoolean(FlagBridgeContract.KEY_HANDLED, false) }
        }
        // A cancel needs nothing the app's onCreate sets up, so it does not wait for it either.
        if (method == FlagBridgeContract.METHOD_CANCEL) return cancel(arg)
        awaitStartup()
        return when (method) {
            FlagBridgeContract.METHOD_HELLO -> Bundle().apply {
                putInt(FlagBridgeContract.KEY_PROTOCOL, FlagBridgeContract.PROTOCOL)
            }
            FlagBridgeContract.METHOD_OVERRIDES -> overrides(context)
            FlagBridgeContract.METHOD_APPLY -> apply(context, extras)
            FlagBridgeContract.METHOD_SIGNAL -> Bundle().apply {
                // The wait comes first: the deadline already includes it, so the budget is not spent twice.
                val delivered = waitOutDelay(extras, enteredAt) &&
                    deliver(arg.orEmpty(), deliveryTimeout(extras, enteredAt))
                putBoolean(FlagBridgeContract.KEY_HANDLED, delivered)
            }
            else -> throw IllegalArgumentException("Unknown bridge method '$method'.")
        }
    }

    // No budget left: deliver would still queue the task, and a handler could run for a client told false.
    private fun deliver(name: String, timeout: Duration): Boolean =
        timeout > Duration.ZERO && FlagSignalChannel.host.deliver(name, timeout)

    /** How long a signal asks the host to wait before delivering it: its [FlagBridgeContract.KEY_DELAY], clamped. */
    internal fun signalDelay(extras: Bundle?): Duration =
        (extras?.getLong(FlagBridgeContract.KEY_DELAY) ?: 0L)
            .coerceIn(0L, FlagBridgeContract.MAX_DELAY.inWholeMilliseconds)
            .milliseconds

    /**
     * Holds a delayed signal until its delay, counted from when it arrived, has passed. False when
     * a [FlagBridgeContract.METHOD_CANCEL] for its ticket came first, before or during the wait.
     */
    private fun waitOutDelay(extras: Bundle?, enteredAt: Long): Boolean {
        val delay = signalDelay(extras)
        if (delay <= Duration.ZERO) return true
        val ticket = extras?.getString(FlagBridgeContract.KEY_TICKET)
        // A delayed signal without a ticket cannot be cancelled, but still waits.
        val cancelled = ticket?.let(::cancellation) ?: CountDownLatch(1)
        try {
            val remaining = enteredAt + delay.inWholeMilliseconds - elapsedRealtime()
            if (remaining > 0) cancelled.await(remaining, TimeUnit.MILLISECONDS)
            return cancelled.count > 0
        } finally {
            if (ticket != null) synchronized(cancellations) { cancellations.remove(ticket) }
        }
    }

    /**
     * Withdraws the delayed signal [ticket] names. A cancel can overtake its signal on the way in, so
     * one for a ticket that is not waiting yet is kept, and the signal finds it when it arrives.
     */
    private fun cancel(ticket: String?): Bundle {
        if (ticket != null) cancellation(ticket).countDown()
        return status(FlagBridgeContract.STATUS_OK)
    }

    private fun cancellation(ticket: String): CountDownLatch =
        synchronized(cancellations) { cancellations.getOrPut(ticket) { CountDownLatch(1) } }

    private fun deadlineExpired(extras: Bundle?, enteredAt: Long): Boolean =
        extras != null && extras.containsKey(FlagBridgeContract.KEY_DEADLINE) &&
            extras.getLong(FlagBridgeContract.KEY_DEADLINE) <= enteredAt

    /**
     * How long a signal's handlers may take. A client states when it stops waiting, so the host
     * gives it what is left less [REPLY_MARGIN] for the reply's trip back, clamped so a garbage
     * deadline cannot hold a binder thread. An older client states nothing: it gets the default,
     * counted from [enteredAt] so a startup wait is not added on top.
     */
    internal fun deliveryTimeout(extras: Bundle?, enteredAt: Long): Duration {
        val now = elapsedRealtime()
        if (extras == null || !extras.containsKey(FlagBridgeContract.KEY_DEADLINE)) {
            return (FlagSignalChannel.DELIVERY_TIMEOUT - (now - enteredAt).milliseconds).coerceAtLeast(Duration.ZERO)
        }
        val deadline = extras.getLong(FlagBridgeContract.KEY_DEADLINE)
        // Compared first so an absurdly early deadline cannot overflow the subtraction.
        if (deadline <= now) return Duration.ZERO
        val remaining = deadline - now - REPLY_MARGIN.inWholeMilliseconds
        return remaining.coerceIn(0L, MAX_DELIVERY.inWholeMilliseconds).milliseconds
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        awaitStartup()
        val context = checkNotNull(context)
        if (uri.path != SCHEMA_PATH) throw FileNotFoundException("The bridge serves no file at $uri.")
        val file = File(FlagBridgeContract.schemaDirectory(context), FlagSchema.FILE_NAME)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

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

    /**
     * A cold-started host can receive its first call on a binder thread while `Application.onCreate`
     * is still running (S6). Once the main looper has run one task posted from here, `onCreate` has
     * returned, so the schema is published and every signal observer is registered.
     */
    private fun awaitStartup() {
        if (started) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val ran = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { ran.countDown() }
            ran.await(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
        started = true
    }

    private fun overrides(context: Context): Bundle {
        val schema = schema(context) ?: return status(FlagBridgeContract.STATUS_NOT_PUBLISHED)
        val bytes = FlagPayload(acceptedOverrides(schema)).jsonBytes()
        if (bytes.size > FlagBridgeContract.MAX_PAYLOAD_BYTES) {
            return status(FlagBridgeContract.STATUS_TOO_LARGE).apply { putInt(FlagBridgeContract.KEY_SIZE, bytes.size) }
        }
        return status(FlagBridgeContract.STATUS_OK).apply { putByteArray(FlagBridgeContract.KEY_PAYLOAD, bytes) }
    }

    /**
     * Every stored override the schema accepts. The file belongs to the host, so its own code may
     * have left a value a strict import would reject; that value is left out rather than failing the
     * whole document. One pass drops every rejected key, so the second always decodes.
     */
    private fun acceptedOverrides(schema: FlagSchema): Map<FlagKey, FlagValueBox> {
        var values = schema.valueTypes.mapNotNull { (key, type) ->
            source.box(key, type)?.takeUnless { it.containsNonFiniteNumber }?.let { key to it }
        }.toMap()
        repeat(DECODE_ATTEMPTS) {
            val problems = try {
                FlagPayload.decode(FlagPayload(values).jsonBytes(), schema)
                return values
            } catch (rejected: FlagImportException.Rejected) {
                rejected.problems
            }
            values = values - problems.map { it.key }.toSet()
        }
        return values
    }

    private fun apply(context: Context, extras: Bundle?): Bundle {
        val schema = schema(context) ?: return status(FlagBridgeContract.STATUS_NOT_PUBLISHED)
        val payload = try {
            extras?.getByteArray(FlagBridgeContract.KEY_PAYLOAD)?.let { FlagPayload.decode(it, schema) }
        } catch (error: FlagImportException) {
            return rejected(error.message.orEmpty())
        }
        val removals = extras?.getStringArray(FlagBridgeContract.KEY_REMOVE).orEmpty().map(::FlagKey)
        val unknown = removals.filter { it !in schema.valueTypes }
        if (unknown.isNotEmpty()) return rejected(unknownRemovalMessage(unknown))

        val changes = LinkedHashMap<FlagKey, FlagValueBox?>()
        payload?.values?.let(changes::putAll)
        removals.forEach { changes[it] = null }
        try {
            source.setBoxes(changes)
        } catch (error: FlagException.UnsupportedValue) {
            // Strict import reads -1e400 as an infinity, which the store cannot hold. Nothing was written.
            return rejected(error.message.orEmpty())
        }
        // setBoxes commits nothing, and so notifies nothing, when the values already match. The companion
        // orders its refresh replies on every successful apply being announced, so announce it here.
        context.contentResolver.notifyChange(overridesUri, null)
        return status(FlagBridgeContract.STATUS_OK)
    }

    private fun schema(context: Context): FlagSchema? = try {
        FlagSchema.read(FlagBridgeContract.schemaDirectory(context))
    } catch (ignored: FlagSchemaException) {
        // Missing, or unreadable: either way there is nothing to check overrides against.
        null
    }

    private fun status(status: String) = Bundle().apply { putString(FlagBridgeContract.KEY_STATUS, status) }

    private fun rejected(message: String) = status(FlagBridgeContract.STATUS_REJECTED).apply {
        putString(FlagBridgeContract.KEY_MESSAGE, message)
    }

    private fun unknownRemovalMessage(keys: List<FlagKey>): String {
        val names = keys.map { "'${it.rawValue}'" }
        val listed = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " or " + names.last()
        val them = if (names.size == 1) "it" else "them"
        return "The host's schema has no flag $listed, so it cannot remove $them. Reconnect to load the current schema."
    }

    private companion object {
        val STARTUP_TIMEOUT = 10.seconds
        val REPLY_MARGIN = 100.milliseconds
        val MAX_DELIVERY = 30.seconds
        const val SCHEMA_PATH = "/schema"
        const val DECODE_ATTEMPTS = 2

        // Far more than the binder threads that can be waiting at once.
        const val MAX_CANCELLATIONS = 64
    }
}
