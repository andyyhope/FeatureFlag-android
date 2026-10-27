package docs.observingchanges

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.pole.FlagPole

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

// region snippet: observing-compose
@Composable
fun OnboardingEntry(pole: FlagPole<AppFlags>) {
    val newOnboarding = remember(pole) { pole.flow(AppFlagsSpec.newOnboarding) }
    val showsNewOnboarding by newOnboarding.collectAsStateWithLifecycle(initialValue = pole[AppFlagsSpec.newOnboarding])

    if (showsNewOnboarding) {
        Text("Welcome to the new onboarding")
    } else {
        Text("Welcome")
    }
}
// endregion

/** Stands in for an analytics SDK. */
class Analytics {
    val properties = mutableMapOf<String, Any>()

    fun setUserProperty(name: String, value: Any) {
        properties[name] = value
    }
}
