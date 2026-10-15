package docs.sendingsignals

import android.app.Application
import com.andyyhope.featureflag.android.signals.FlagSignalChannel
import com.andyyhope.featureflag.signals.FlagSignal

// region snippet: signals-enum
enum class AppSignal : FlagSignal {
    RefetchRemoteConfiguration,
    PurgeImageCache,
    SignOut,
}
// endregion

// region snippet: signals-groups
enum class ConfigSignal : FlagSignal {
    RefetchRemoteConfiguration,
    ClearRemoteConfiguration,
    ;

    override val signalDescription: String
        get() = when (this) {
            RefetchRemoteConfiguration -> "Re-fetch remote configuration"
            ClearRemoteConfiguration -> "Clear remote configuration"
        }
}

enum class CacheSignal : FlagSignal {
    PurgeImageCache,
    PurgeEverything,
    ;

    override val requiresRestart: Boolean get() = this == PurgeEverything
}
// endregion

/** Stands in for an app's own image cache and session. */
class ImageCache {
    var entries = 3
        private set

    fun clear() {
        entries = 0
    }
}

class Session {
    var isSignedIn = true
        private set

    fun signOut() {
        isSignedIn = false
    }
}

/** A host that handles every [AppSignal]. Robolectric builds it, and runs onCreate, before each test. */
class ExampleApplication : Application(), AutoCloseable {
    val imageCache = ImageCache()
    val session = Session()
    var refetches = 0
        private set

    private fun refetchConfiguration() {
        refetches += 1
    }

    // region snippet: signals-host
    private val subscriptions = mutableListOf<AutoCloseable>()

    override fun onCreate() {
        super.onCreate()
        subscriptions += FlagSignalChannel.host.observe(AppSignal.entries) { signal ->
            when (signal) {
                AppSignal.RefetchRemoteConfiguration -> refetchConfiguration()
                AppSignal.PurgeImageCache -> imageCache.clear()
                AppSignal.SignOut -> session.signOut()
            }
        }
    }
    // endregion

    override fun close() {
        subscriptions.forEach(AutoCloseable::close)
    }
}
