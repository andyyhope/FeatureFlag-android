package com.andyyhope.featureflag.bridge

import android.app.Application
import android.os.Process
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.io.File
import java.time.Instant

/**
 * The test app's Application. In the `:host` process, where the provider runs, it does what a
 * real host does in `onCreate`: publishes its schema and registers its signal observers. It does
 * them slowly and last, so a call that skips the provider's startup barrier finds neither.
 * In the main process, where the tests run as the companion, it does nothing.
 */
class HostTestApplication : Application() {

    private var observation: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        if (!processName().endsWith(HOST_PROCESS_SUFFIX)) return
        Thread.sleep(STARTUP_MILLIS)
        BridgeTestHost.schema.write(FlagBridgeContract.schemaDirectory(this))
        val observed = listOf(
            BridgeTestSignal.Purge,
            BridgeTestSignal.Explode,
            BridgeTestSignal.DieOnce,
            BridgeTestSignal.Slow,
            BridgeTestSignal.Record,
            BridgeTestSignal.Republish,
        )
        observation = FlagSignalChannel.host.observe(observed) { signal ->
            when (signal) {
                BridgeTestSignal.Explode -> error("The Explode handler always fails.")
                // Kills this process mid-call the first time, as a host that crashes or is reclaimed would.
                BridgeTestSignal.DieOnce -> if (File(filesDir, DIE_ONCE_MARKER).createNewFile()) Process.killProcess(Process.myPid())
                BridgeTestSignal.Slow -> Thread.sleep(SLOW_HANDLER_MILLIS)
                // Leaves a trace the tests, in the other process, can read: one line per delivery.
                BridgeTestSignal.Record -> File(filesDir, DELIVERIES_LOG).appendText("${signal.name}\n")
                // What publishSchema(context) does once it has written the file: tells an open companion.
                BridgeTestSignal.Republish -> contentResolver.notifyChange(FlagBridgeContract.schemaUri(packageName), null)
                else -> Unit
            }
        }
    }

    private fun processName(): String = File("/proc/self/cmdline").readText().substringBefore('\u0000')

    companion object {
        const val HOST_PROCESS_SUFFIX = ":host"

        /** Present once DieOnce has killed a host; the tests delete it first. Both processes share filesDir. */
        const val DIE_ONCE_MARKER = "die-once"

        /** A line per Record the host's handler ran; the tests delete it first. */
        const val DELIVERIES_LOG = "deliveries"

        /** A cold start long enough that the first call arrives while onCreate is still running (S6). */
        const val STARTUP_MILLIS = 1_000L

        /** Longer than the provider's old fixed 4 s delivery timeout. */
        const val SLOW_HANDLER_MILLIS = 4_500L
    }
}

/** The signals the host observes, plus one it does not. */
enum class BridgeTestSignal : FlagSignal { Purge, Explode, DieOnce, Slow, Record, Republish, Unobserved }

/** The host's flags: one of every value type. */
object BridgeTestHost {
    val enabled = FlagKey("enabled")
    val limit = FlagKey("limit")
    val ratio = FlagKey("ratio")
    val scale = FlagKey("scale")
    val tier = FlagKey("tier")
    val logo = FlagKey("logo")
    val launch = FlagKey("launch")
    val endpoint = FlagKey("endpoint")
    val markets = FlagKey("markets")
    val limits = FlagKey("limits")

    val schema = FlagSchema(
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        applicationName = "Bridge test host",
        flags = listOf(
            entry(enabled, FlagValueType.Bool, FlagValueBox.Bool(false)),
            entry(limit, FlagValueType.Int, FlagValueBox.Int(3)),
            entry(ratio, FlagValueType.Double, FlagValueBox.Double(0.5)),
            entry(scale, FlagValueType.Float, FlagValueBox.Float(1f)),
            entry(
                tier,
                FlagValueType.String,
                FlagValueBox.String("free"),
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry(logo, FlagValueType.Data, FlagValueBox.Data(byteArrayOf())),
            entry(launch, FlagValueType.Date, FlagValueBox.Date(Instant.EPOCH)),
            entry(endpoint, FlagValueType.Url, FlagValueBox.Url(java.net.URI("https://example.com"))),
            entry(markets, FlagValueType.Array(FlagValueType.String), FlagValueBox.Array(emptyList())),
            entry(limits, FlagValueType.Dictionary(FlagValueType.Int), FlagValueBox.Dictionary(emptyMap())),
        ),
    )

    private fun entry(
        key: FlagKey,
        type: FlagValueType,
        default: FlagValueBox,
        cases: List<FlagValueBox>? = null,
    ) = FlagSchema.Entry(
        key = key,
        propertyPath = listOf(key.rawValue),
        description = key.rawValue,
        valueType = type,
        defaultValue = default,
        cases = cases,
    )
}
