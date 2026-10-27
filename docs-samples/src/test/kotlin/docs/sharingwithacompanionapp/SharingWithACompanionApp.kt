package docs.sharingwithacompanionapp

import android.app.Application
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.android.sources.SharedPreferencesSource
import com.andyyhope.featureflag.android.transport.publishSchema
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
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

/** What a host publishes when a config beneath its overrides turns Google Pay on, at a fixed moment. */
fun publishedSchemaWithAConfig(): String {
    val googlePay = FlagKey("checkout.google-pay")
    val config = SnapshotSource(name = "Staging config", values = mapOf(googlePay to FlagValueBox.Bool(true)))
    val companion = SnapshotSource(name = "Companion")
    val pole = FlagPole(AppFlagsSpec, sources = listOf(companion, config), applicationName = "Demo")
    return pole.schemaWithEffectiveValues()
        .copy(generatedAt = Instant.parse("2026-01-01T00:00:00Z"))
        .jsonBytes()
        .decodeToString()
}

/** The schema a host with [AppFlags] publishes, at a fixed moment. */
fun publishedSchema(): String = FlagSchema
    .of(AppFlagsSpec, applicationName = "Demo", generatedAt = Instant.parse("2026-01-01T00:00:00Z"))
    .jsonBytes()
    .decodeToString()
