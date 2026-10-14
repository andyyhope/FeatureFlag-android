package com.andyyhope.featureflag.ui.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andyyhope.featureflag.android.bridge.FlagBridgeClient
import com.andyyhope.featureflag.android.bridge.FlagBridgeSource
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.sources.MutableFlagValueSource
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
    )

    init {
        session.start()
    }

    /**
     * Asks the host to deliver [signal] after [delay], within the client's timeout on top: what the
     * signals model sends with. Cancelling the caller withdraws a delayed signal from the host.
     */
    suspend fun send(signal: FlagSignal, delay: Duration): Boolean = client.send(signal, delay = delay)

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
