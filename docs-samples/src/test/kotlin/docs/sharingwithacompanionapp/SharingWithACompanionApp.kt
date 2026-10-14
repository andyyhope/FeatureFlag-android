package docs.sharingwithacompanionapp

import android.app.Application
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.android.transport.publishSchema
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import java.time.Instant

@FlagContainer
interface AppFlags {
    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}

// region snippet: sharing-setup
class ExampleApplication : Application() {
    lateinit var pole: FlagPole<AppFlags>
        private set

    override fun onCreate() {
        super.onCreate()
        pole = FlagPole(AppFlagsSpec, sources = listOf(SharedPreferencesSource.companion(this)))
        pole.publishSchema(this)
    }
}
// endregion

/** The schema a host with [AppFlags] publishes, at a fixed moment. */
fun publishedSchema(): String = FlagSchema
    .of(AppFlagsSpec, applicationName = "Demo", generatedAt = Instant.parse("2026-01-01T00:00:00Z"))
    .jsonBytes()
    .decodeToString()
