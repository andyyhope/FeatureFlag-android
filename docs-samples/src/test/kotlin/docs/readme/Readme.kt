package docs.readme

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup

// region snippet: readme-declaration
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false

    @Flag(description = "Pricing tier to present")
    val tier: Tier get() = Tier.Free
}

enum class Tier { Free, Pro }
// endregion
