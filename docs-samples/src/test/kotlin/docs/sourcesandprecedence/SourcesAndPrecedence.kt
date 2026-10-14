package docs.sourcesandprecedence

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import java.net.URI

enum class Environment(override val flagRawValue: String) : FlagStringEnum {
    Production("production"),
    Staging("staging"),
}

@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Which backend this build talks to")
    val environment: Environment get() = Environment.Production

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay", remoteKey = "checkout.googlePay")
    val googlePay: Boolean get() = false

    @Flag(description = "Where the remote config is fetched from", remoteKey = "checkout.remoteUrl")
    val remoteUrl: URI get() = URI("https://config.example.com")
}

// region snippet: sources-custom
class RemoteConfigSource : FlagValueSource {

    override val sourceName: String = "Remote Config"

    @Volatile
    private var values: Map<FlagKey, FlagValueBox> = emptyMap()

    private val emitter = FlagChangeEmitter()

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    fun refresh(values: Map<FlagKey, FlagValueBox>) {
        this.values = values
        emitter.emit(FlagChange.All)
    }
}
// endregion

/** Stands in for an app's own configuration service. */
class ConfigApi(private val configs: Map<String, String>) {
    val requests = mutableListOf<String>()

    suspend fun fetchConfig(environment: Environment): ByteArray? = fetch(DEFAULT_SERVER, environment)

    suspend fun fetch(url: URI, environment: Environment): ByteArray? {
        yield()
        val request = "$url/${environment.flagRawValue}.json"
        requests += request
        return configs[request]?.encodeToByteArray()
    }

    private companion object {
        val DEFAULT_SERVER = URI("https://config.example.com")
    }
}
