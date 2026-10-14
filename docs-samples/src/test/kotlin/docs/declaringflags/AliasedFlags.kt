package docs.declaringflags

// region snippet: declaring-flag-alias
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.Flag as FeatureFlag

@FlagContainer
interface AliasedFlags {
    @FeatureFlag(description = "Show the redesigned onboarding")
    val newOnboarding: Boolean get() = false
}
// endregion
