package docs.gettingstarted

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup

// region snippet: getting-started-declare
@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page")
    val pageSize: Int get() = 20

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
// endregion
