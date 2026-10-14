package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.errors.RemoteOverrideProblem
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.EnvironmentConfiguration
import com.andyyhope.featureflag.remote.LayerOutcome
import com.andyyhope.featureflag.sample.signals.AppSignal
import com.andyyhope.featureflag.sample.signals.CacheSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen shows besides the flags themselves: iOS DemoModel's published properties. */
data class DemoState(
    /** Which payload is currently applied, if any. */
    val appliedConfiguration: RemoteConfiguration? = null,
    /** Why the last apply was rejected. Payloads are all or nothing, so this is the whole story. */
    val rejection: String? = null,
    /** Proof the flows work: changes counted as they arrive, not read on redraw. */
    val onboardingChanges: Int = 0,
    /** The last signal the companion sent, so the demo can show it arriving. */
    val lastSignal: String? = null,
    val purgedCaches: Int = 0,
    val awaitingRelaunch: Boolean = false,
)

/** Which source supplied one flag's value: a row of "Where each value came from". */
data class Provenance(val key: String, val sourceName: String, val isDefault: Boolean)

/** The flags as the app reads them, taken in one go whenever any source changes. */
data class DemoValues(
    val environment: DemoEnvironment,
    val newOnboarding: Boolean,
    val pageSize: Int,
    val markets: List<String>,
    val googlePay: Boolean,
    val tier: CheckoutTier,
    val endpointHost: String?,
    val oneTap: Boolean,
    val paymentMethods: List<PaymentMethod>,
    val provenance: List<Provenance>,
)

/**
 * Everything the demo screen needs: the pole, the environment configuration that feeds its local
 * and remote layers, and the collections that let the environment flag drive which config is
 * loaded. A port of iOS DemoModel. DemoApplication builds the pole and owns this.
 */
class DemoModel(
    val pole: FlagPole<AppFlags>,
    private val config: EnvironmentConfiguration<DemoEnvironment>,
    private val scope: CoroutineScope,
) {

    private val mutableState = MutableStateFlow(DemoState())
    val state: StateFlow<DemoState> = mutableState.asStateFlow()

    private val mutableValues = MutableStateFlow(read())
    val values: StateFlow<DemoValues> = mutableValues.asStateFlow()

    /**
     * Starts watching the pole. Each collection starts undispatched, so it is subscribed, and the
     * current environment's payload is loaded, before this returns. Application.onCreate calls
     * it, and the bridge provider answers a companion only once onCreate has returned.
     */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pole.changes.collect { mutableValues.value = read() }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pole.flow(AppFlagsSpec.newOnboarding).drop(1).collect { _ ->
                mutableState.update { state -> state.copy(onboardingChanges = state.onboardingChanges + 1) }
            }
        }
        // region snippet: sample-host-environment
        // The environment flag drives which payload is applied. flow() emits the current value
        // first, so this also performs the initial load, and it never repeats a value.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pole.flow(AppFlagsSpec.environment).collect { environment -> switchTo(environment) }
        }
        // endregion
        mutableValues.value = read()
    }

    /**
     * Loads both layers for an environment: the bundled local config, then the fetched remote one.
     *
     * The environment flag has no remoteKey. If it did, a staging payload could set the
     * environment to production, which would mean the app should have loaded a different config,
     * and loading that could set it back. Leaving the key off is what prevents the loop.
     *
     * The configuration clears each layer before loading it, so a failed fetch falls back to the
     * local config, then the compiled defaults, and never to the previous environment's values.
     */
    suspend fun switchTo(environment: DemoEnvironment) {
        when (val remote = config.load(environment).remote) {
            LayerOutcome.Applied -> mutableState.update { state ->
                state.copy(appliedConfiguration = RemoteConfiguration.forEnvironment(environment), rejection = null)
            }
            LayerOutcome.Absent -> mutableState.update { state ->
                state.copy(appliedConfiguration = null, rejection = null)
            }
            is LayerOutcome.Failed -> mutableState.update { state ->
                state.copy(appliedConfiguration = null, rejection = describe(remote.error))
            }
            // A newer switch is already in flight; let it own the state.
            LayerOutcome.Superseded -> Unit
        }
    }

    /** iOS's environment setter: the choice lands in the Companion source, the highest one. */
    fun selectEnvironment(environment: DemoEnvironment) {
        pole.setOverride(AppFlagsSpec.environment, environment)
    }

    /** Stands in for "your app downloaded a config and handed over the bytes". */
    fun applyPayload(configuration: RemoteConfiguration) {
        try {
            config.remoteSource.apply(configuration.data)
            mutableState.update { state -> state.copy(appliedConfiguration = configuration, rejection = null) }
        } catch (error: RemoteOverrideException) {
            mutableState.update { state -> state.copy(appliedConfiguration = null, rejection = describe(error)) }
        }
    }

    /**
     * Clears just the remote layer, leaving the bundled local config showing through: the local
     * layer earning its place, rather than a fall to the raw defaults.
     */
    fun clearRemote() {
        config.remoteSource.clear()
        mutableState.update { state -> state.copy(appliedConfiguration = null, rejection = null) }
    }

    /** Signals from the companion, on the main thread. */
    fun handle(signal: AppSignal) {
        mutableState.update { state -> state.copy(lastSignal = signal.signalDescription) }
        when (signal) {
            AppSignal.RefetchRemoteConfiguration -> scope.launch { switchTo(pole.flags.environment) }
            AppSignal.ClearRemoteConfiguration -> clearRemote()
        }
    }

    fun handle(signal: CacheSignal) {
        when (signal) {
            // Visible immediately, so this one needs no relaunch.
            CacheSignal.PurgeImageCache -> mutableState.update { state ->
                state.copy(lastSignal = signal.signalDescription, purgedCaches = state.purgedCaches + 1)
            }
            // Emptied now, rebuilt at launch, so nothing here looks different until the app starts
            // again. That is what the signal's requiresRestart says, and why the companion reports
            // "handled — relaunch to see it".
            CacheSignal.PurgeEverything -> mutableState.update { state ->
                state.copy(lastSignal = signal.signalDescription, awaitingRelaunch = true)
            }
        }
    }

    private fun read(): DemoValues {
        val flags = pole.flags
        return DemoValues(
            environment = flags.environment,
            newOnboarding = flags.newOnboarding,
            pageSize = flags.pageSize,
            markets = flags.markets,
            googlePay = flags.checkout.googlePay,
            tier = flags.checkout.tier,
            endpointHost = flags.checkout.endpoint.host,
            oneTap = flags.checkout.express.oneTap,
            paymentMethods = flags.paymentMethods.toList(),
            provenance = pole.schema.flags.map { entry ->
                val resolution = pole.resolution(entry.key, entry.valueType)
                Provenance(entry.key.rawValue, resolution.sourceName ?: "default", resolution.isDefault)
            },
        )
    }
}

/** A rejection is only useful if it names the field and the reason. */
internal fun describe(error: Exception): String {
    if (error !is RemoteOverrideException.Rejected) return error.message ?: error.toString()
    return error.problems.joinToString("\n") { problem ->
        when (problem.kind) {
            RemoteOverrideProblem.Kind.TypeMismatch -> "${problem.remoteKey} — wrong type for ${problem.key.rawValue}"
            RemoteOverrideProblem.Kind.UnknownCase ->
                "${problem.remoteKey} — not a case ${problem.key.rawValue} can represent"
            RemoteOverrideProblem.Kind.UnknownKey -> "${problem.key.rawValue} — no such flag in this app"
        }
    }
}
