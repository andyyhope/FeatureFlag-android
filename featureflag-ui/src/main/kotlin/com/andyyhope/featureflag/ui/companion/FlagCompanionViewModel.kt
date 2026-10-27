package com.andyyhope.featureflag.ui.companion

import android.app.Application
import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.android.bridge.FlagBridgeSource
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlin.time.Duration

/**
 * The companion's connection to one host, kept across configuration changes: a
 * [FlagBridgeClient] and a [FlagCompanionSession] running in [viewModelScope], started at once
 * and closed with the view model. `FlagCompanion` keys it by host package.
 */
internal class FlagCompanionViewModel(
    application: Application,
    hostPackage: String,
    timeout: Duration,
) : AndroidViewModel(application) {

    private val client = FlagBridgeClient(application, hostPackage, timeout)

    val session: FlagCompanionSession = FlagCompanionSession(
        connect = client::connect,
        openSource = { schema -> client.source(schema).asCompanionSource() },
        scope = viewModelScope,
        observeHost = { onRepublish -> watchSchema(application.contentResolver, hostPackage, onRepublish) },
    )

    init {
        session.start()
    }

    /**
     * Asks the host to deliver [signal] after [delay], within the client's timeout on top: what the
     * signals model sends with. Cancelling the caller withdraws a delayed signal from the host.
     * Once it returns, the session reloads the host's schema, which the signal may have changed.
     */
    suspend fun send(signal: FlagSignal, delay: Duration): Boolean = sendThenReload(signal, delay)

    private val sendThenReload = session.reloadingAfter(viewModelScope) { signal, delay -> client.send(signal, delay = delay) }

    /** Owned here so a pending send and Recent survive configuration changes. */
    internal val signals: FlagSignalsModel = FlagSignalsModel(send = ::send, scope = viewModelScope)

    override fun onCleared() {
        session.close()
    }

    companion object {
        /** Builds the view model for [hostPackage] from the application in the creation extras. */
        fun factory(hostPackage: String, timeout: Duration): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FlagCompanionViewModel(
                    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    hostPackage,
                    timeout,
                )
            }
        }
    }
}

/**
 * Calls [onRepublish], on the main thread, each time the host installed as [hostPackage] announces a
 * republished schema, until the result is closed. Null when the host has no provider to watch.
 */
internal fun watchSchema(resolver: ContentResolver, hostPackage: String, onRepublish: () -> Unit): AutoCloseable? {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onRepublish()
        }
    }
    return try {
        resolver.registerContentObserver(FlagBridgeContract.schemaUri(hostPackage), false, observer)
        AutoCloseable { resolver.unregisterContentObserver(observer) }
    } catch (ignored: SecurityException) {
        // The host's provider is gone, or not visible: reconnecting on resume and after a signal still works.
        null
    }
}

/** This bridge source as the session sees it. Lives here, not beside the session, which stays free of Android types. */
internal fun FlagBridgeSource.asCompanionSource(): CompanionSource {
    val bridge = this
    return object : CompanionSource, MutableFlagValueSource by bridge {
        override val lastFailure = bridge.lastFailure

        override fun refresh() {
            bridge.refresh()
        }

        override fun close() {
            bridge.close()
        }
    }
}
