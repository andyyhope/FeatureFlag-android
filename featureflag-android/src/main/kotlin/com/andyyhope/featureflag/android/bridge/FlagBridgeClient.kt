package com.andyyhope.featureflag.android.bridge

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A companion's connection to one host app's FeatureFlag bridge.
 *
 * The companion needs `<uses-permission android:name="<hostPackage>.permission.FEATURE_FLAG_BRIDGE" />`
 * in its manifest, and must be signed with the host's key (or listed in its knownSigner permission).
 * Every operation acquires its own unstable provider client and closes it afterwards, so the
 * companion survives the host dying; a call that finds the host dead is retried once.
 */
public class FlagBridgeClient(
    context: Context,
    public val hostPackage: String,
    public val timeout: Duration = 5.seconds,
) {

    private val context: Context = context.applicationContext ?: context
    private val authority = FlagBridgeContract.authority(hostPackage)
    private val permission = FlagBridgeContract.permission(hostPackage)

    // Blocking binder calls run here, detached from the caller, so a timeout returns on time
    // even while the call itself is still waiting for the host.
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val resolver: ContentResolver get() = context.contentResolver

    /** Checks everything a companion needs, in the order it is worth fixing, and loads the host's schema. Never throws. */
    public suspend fun connect(): FlagBridgeConnection {
        val packageManager = context.packageManager
        val requested = packageInfo(packageManager, context.packageName, PackageManager.GET_PERMISSIONS)?.requestedPermissions
        if (requested == null || permission !in requested) return FlagBridgeConnection.UsesPermissionMissing(permission)
        packageInfo(packageManager, hostPackage, 0) ?: return FlagBridgeConnection.HostNotFound(hostPackage)
        // Only a host that includes featureflag-bridge defines the permission, so without it the bridge is missing.
        if (!definesPermission(packageManager)) return FlagBridgeConnection.BridgeNotInstalled(hostPackage)
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return permissionDenied()
        return within(timeout) { handshake() } ?: FlagBridgeConnection.Unresponsive(hostPackage, timeout)
    }

    /** True when the host ran a handler for [signal]; false on no observer, timeout, or an unreachable host. Never throws.
     * A send that timed out or was cancelled may still be delivered, and a host that dies after running
     * the handler may run it again on the retry. The host is given the caller's remaining budget, so a cold
     * host's startup wait no longer pushes a slow handler past the caller's timeout. */
    public suspend fun send(signal: FlagSignal, timeout: Duration = this.timeout): Boolean {
        // Fixed before the first attempt: a retry after the host died has only what is left of the same budget.
        val now = SystemClock.elapsedRealtime()
        val budget = timeout.inWholeMilliseconds
        // Saturated: Duration.INFINITE is Long.MAX_VALUE milliseconds, which would wrap into the past.
        val deadline = if (budget > Long.MAX_VALUE - now) Long.MAX_VALUE else now + budget
        val extras = Bundle().apply { putLong(FlagBridgeContract.KEY_DEADLINE, deadline) }
        return within(timeout) {
            try {
                perform { client -> client.call(FlagBridgeContract.METHOD_SIGNAL, signal.name, extras) }
                    ?.getBoolean(FlagBridgeContract.KEY_HANDLED) ?: false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                false
            }
        } ?: false
    }

    /**
     * A live view of the host's companion overrides, validated against [schema]. Close it when done.
     * Suspends while it registers an observer and reads the overrides once, on a background thread,
     * so it is safe to call from the main thread. The source's writes and [FlagBridgeSource.refresh]
     * still block: make those off the main thread. Construction is not interrupted, so a call cancelled
     * meanwhile stays suspended until it finishes (up to the host's startup barrier), then closes the
     * new source and throws [CancellationException]. Throws [FlagBridgeException.Unavailable] when the host's provider cannot be found or the
     * companion may not observe it. A first read that fails leaves the source empty instead.
     */
    public suspend fun source(schema: FlagSchema, name: String = "Companion"): FlagBridgeSource {
        // Not interrupted mid-way: a half-built source would leave its thread and observer behind.
        // withContext discards its result when the caller was cancelled meanwhile, so the source is
        // handed out through a variable to close it in that case too. The failure travels as a value
        // so the coroutines machinery does not wrap it on the way out.
        var built: Result<FlagBridgeSource>? = null
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                built = runCatching { FlagBridgeSource(this@FlagBridgeClient, schema, name) }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            built?.getOrNull()?.close()
            throw cancelled
        }
        return checkNotNull(built).getOrThrow()
    }

    /**
     * Runs [operation] on a fresh unstable client for the host's provider and closes it. A
     * [DeadObjectException] means the host died mid-call; it is retried once with a new client,
     * which starts the host again. Returns null when the host has no provider. Blocking.
     */
    internal fun <T> perform(operation: (ContentProviderClient) -> T): T? {
        var attempt = 0
        while (true) {
            val client = resolver.acquireUnstableContentProviderClient(authority) ?: return null
            try {
                return operation(client)
            } catch (died: DeadObjectException) {
                if (++attempt == ATTEMPTS) throw died
            } finally {
                client.close()
            }
        }
    }

    /** One bridge method, blocking; the reply, or null when the host has no provider. */
    internal fun call(method: String, extras: Bundle? = null): Bundle? =
        perform { client -> client.call(method, null, extras) }

    private suspend fun <T : Any> within(timeout: Duration, block: () -> T): T? {
        val result = calls.async { block() }
        return withTimeoutOrNull(timeout) { result.await() }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever the host throws over binder must become a status.
    private fun handshake(): FlagBridgeConnection = try {
        perform { client ->
            val protocol = client.call(FlagBridgeContract.METHOD_HELLO, null, null)?.getInt(FlagBridgeContract.KEY_PROTOCOL)
            if (protocol != FlagBridgeContract.PROTOCOL) {
                FlagBridgeConnection.Incompatible(
                    "The host speaks bridge protocol $protocol; this companion speaks ${FlagBridgeContract.PROTOCOL}. " +
                        "Update whichever app is older.",
                )
            } else {
                schema(client)
            }
        } ?: FlagBridgeConnection.BridgeNotInstalled(hostPackage)
    } catch (ignored: SecurityException) {
        permissionDenied()
    } catch (ignored: RemoteException) {
        // The host died twice in a row: as far as the companion can tell, it is not answering.
        FlagBridgeConnection.Unresponsive(hostPackage, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RuntimeException) {
        FlagBridgeConnection.Incompatible(
            "'$hostPackage' failed while answering the bridge: ${error.message ?: error.javaClass.simpleName}. " +
                "Update whichever app is older, then try again.",
        )
    }

    private fun schema(client: ContentProviderClient): FlagBridgeConnection = try {
        val descriptor = client.openFile(FlagBridgeContract.schemaUri(hostPackage), "r")
            ?: throw FileNotFoundException("The host served no schema.")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        FlagBridgeConnection.Connected(FlagSchema.parse(bytes))
    } catch (ignored: FileNotFoundException) {
        FlagBridgeConnection.NotPublished
    } catch (ignored: IOException) {
        FlagBridgeConnection.Unresponsive(hostPackage, timeout)
    } catch (error: FlagSchemaException) {
        FlagBridgeConnection.Incompatible(error.message.orEmpty())
    }

    private fun definesPermission(packageManager: PackageManager): Boolean = try {
        packageManager.getPermissionInfo(permission, 0)
        true
    } catch (ignored: PackageManager.NameNotFoundException) {
        false
    }

    private fun permissionDenied(): FlagBridgeConnection.PermissionDenied {
        val packageManager = context.packageManager
        return FlagBridgeConnection.PermissionDenied(
            hostPackage = hostPackage,
            hostCertificates = certificates(packageManager, hostPackage),
            companionCertificates = certificates(packageManager, context.packageName),
        )
    }

    private fun certificates(packageManager: PackageManager, packageName: String): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo(packageManager, packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo(packageManager, packageName, PackageManager.GET_SIGNATURES)?.signatures
        }
        return signatures.orEmpty().map(::fingerprint)
    }

    private companion object {
        const val ATTEMPTS = 2

        @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) needs API 33.
        fun packageInfo(packageManager: PackageManager, packageName: String, flags: Int): PackageInfo? = try {
            packageManager.getPackageInfo(packageName, flags)
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }

        const val HEX_DIGITS = "0123456789ABCDEF"

        /** SHA-256 of the certificate, uppercase hex with no separators, as apksigner prints it. */
        fun fingerprint(signature: Signature): String = buildString {
            for (byte in MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) {
                val unsigned = byte.toInt() and 0xFF
                append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0xF])
            }
        }
    }
}
