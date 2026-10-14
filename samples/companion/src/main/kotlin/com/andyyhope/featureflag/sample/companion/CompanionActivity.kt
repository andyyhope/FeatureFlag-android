package com.andyyhope.featureflag.sample.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.andyyhope.featureflag.sample.signals.AppSignal
import com.andyyhope.featureflag.sample.signals.CacheSignal
import com.andyyhope.featureflag.ui.FlagCompanion
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup

/**
 * The whole companion app. It links no host code and knows nothing about AppFlags: it reads the
 * schema the host published, through the host's bridge, and renders whatever it finds.
 *
 * Everything visible here is a choice rather than an implementation: which tabs, in which order.
 * Connecting, every way that fails, and the overrides, flags and signals screens come from
 * featureflag-ui.
 */
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                // region snippet: sample-companion-tabs
                FlagCompanion(
                    hostPackage = BuildConfig.HOST_PACKAGE,
                    tabs = listOf(
                        // Overrides first: "what have I changed?" is the question you have before
                        // filing a bug or handing the device to someone else.
                        FlagCompanionTab.Overrides,
                        // Two enums, two groups. The host observes each separately, so its
                        // configuration handler never has to mention caches.
                        FlagCompanionTab.Signals(
                            FlagSignalGroup("Configuration", AppSignal.entries),
                            FlagSignalGroup("Caches", CacheSignal.entries),
                        ),
                        FlagCompanionTab.Flags,
                        // The environment flag earns a tab because changing it makes the host load
                        // a different payload. Which key that is, is knowledge about this app.
                        FlagCompanionTab.Detail(key = "environment", title = "Environment"),
                    ),
                )
                // endregion
            }
        }
    }
}
