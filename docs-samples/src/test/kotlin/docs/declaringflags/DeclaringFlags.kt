package docs.declaringflags

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.schema.FlagSchema
import kotlin.test.assertFailsWith

// region snippet: declaring-a-flag
@FlagContainer
interface OnboardingFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false
}
// endregion

@FlagContainer
interface RemoteOnboardingFlags {
    // region snippet: declaring-remote-key
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false
    // endregion
}

// region snippet: declaring-nesting
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

    @FlagGroup(description = "Express")
    val express: ExpressFlags
}

@FlagContainer
interface ExpressFlags {
    @Flag(description = "One-tap purchase")
    val oneTap: Boolean get() = false
}
// endregion

/** Two names that the default encoding turns into one key. */
@FlagContainer
interface CollidingFlags {
    @Flag(description = "Refuse plain HTTP")
    val useHTTPSOnly: Boolean get() = true

    @Flag(description = "Refuse plain HTTP, spelled differently")
    val useHttpsOnly: Boolean get() = true
}

// region snippet: declaring-without-a-pole
@FlagContainer
interface OnboardingPreviewFlags {
    @Flag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = true
}
// endregion

// region snippet: declaring-public-container
@FlagContainer
public interface StoreFlags {
    @Flag(description = "Show the redesigned onboarding")
    public val newOnboarding: Boolean get() = false
}
// endregion

// region snippet: declaring-container-description
@FlagContainer(description = "Everything the checkout team can turn on")
interface CheckoutTeamFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}
// endregion

/** What building a pole over [CollidingFlags] fails with. */
fun duplicateKeyMessage(): String =
    assertFailsWith<IllegalStateException> { FlagSchema.of(CollidingFlagsSpec) }.message.orEmpty()
