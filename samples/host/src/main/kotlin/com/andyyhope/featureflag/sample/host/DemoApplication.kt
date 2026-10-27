package com.andyyhope.featureflag.sample.host

import android.app.Application
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.android.transport.publishSchema
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sample.signals.AppSignal
import com.andyyhope.featureflag.sample.signals.CacheSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The host app. It links featureflag-bridge, never featureflag-ui, so no editor code ships in the
 * app people install.
 *
 * Everything a companion relies on happens in onCreate, before it returns: the bridge provider
 * waits for onCreate before it answers, and a signal can start this process from cold.
 */
class DemoApplication : Application() {

    /** The screen's state. Built in onCreate, so it exists before any activity or provider call. */
    lateinit var model: DemoModel
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var subscriptions: List<AutoCloseable> = emptyList()

    override fun onCreate() {
        super.onCreate()
        // region snippet: sample-host-wiring
        val config = demoConfiguration()
        // Highest first: a value set by hand in the companion beats the fetched remote config,
        // which beats the bundled local one, which beats the compiled defaults.
        val pole = FlagPole(
            AppFlagsSpec,
            sources = listOf(SharedPreferencesSource.companion(this)) + config.sources,
            applicationName = "Demo",
        )
        // The companion is a separate app and knows nothing of this flag tree until it is
        // published. Do it on every launch, so a newly added flag shows up straight away.
        pole.publishSchema(this)
        model = DemoModel(pole, config, scope)
        // One observer per enum: each `when` stays small and exhaustive, and every observer
        // sees every signal rather than the first one claiming them all.
        subscriptions = listOf(
            FlagSignalChannel.host.observe(AppSignal.entries) { signal -> model.handle(signal) },
            FlagSignalChannel.host.observe(CacheSignal.entries) { signal -> model.handle(signal) },
        )
        model.start()
        // endregion
    }

    /**
     * Only emulated environments call this. Robolectric does, and builds a new application for
     * every test while FlagSignalChannel.host lives as long as the JVM.
     */
    override fun onTerminate() {
        subscriptions.forEach(AutoCloseable::close)
        scope.cancel()
        super.onTerminate()
    }
}
