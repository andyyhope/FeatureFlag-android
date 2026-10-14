package docs.remoteoverrides

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagStringEnum

enum class Tier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
}

@FlagContainer
interface AppFlags {
    // region snippet: remote-flag
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false
    // endregion

    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 20

    @Flag(description = "Pricing tier at checkout", remoteKey = "config.tier")
    val checkoutTier: Tier get() = Tier.Free

    @Flag(description = "Whether the first experiment runs", remoteKey = "experiments.0.enabled")
    val firstExperiment: Boolean get() = false

    @Flag(description = "Share of people who see the new onboarding", remoteKey = "config.rolloutShare")
    val rolloutShare: Double get() = 0.0
}

/** Flags whose remote keys are experiment names rather than paths. */
@FlagContainer
interface ExperimentFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "onboarding-v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Offer Google Pay", remoteKey = "google-pay")
    val googlePay: Boolean get() = false
}

// region snippet: remote-mapper
object ExperimentListMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val experiments = payload.valueAt("experiments") as? RemoteValue.Array
            ?: throw RemoteOverrideException.Malformed("expected an experiments array")

        val states = mutableMapOf<String, Boolean>()
        for (experiment in experiments.value) {
            val name = experiment.valueAt("name") as? RemoteValue.String
            val state = experiment.valueAt("state") as? RemoteValue.String
            if (name != null && state != null) states[name.value] = state.value == "on"
        }

        return buildMap {
            for (entry in schema.flags) {
                val state = entry.remoteKey?.let(states::get) ?: continue
                put(entry.key, RemoteValue.Bool(state))
            }
        }
    }
}
// endregion
