package docs.declaringflags

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/DeclaringFlags.md. */
class DeclaringFlagsTest {

    private val source = SnapshotSource()

    @Test
    fun aDeclaredFlagReadsItsDefault() {
        val pole = FlagPole(OnboardingFlagsSpec, sources = listOf(source))

        assertFalse(pole.flags.newOnboarding)
        assertEquals("Show the redesigned onboarding", pole.schema.flags.single().description)
        assertEquals("featureToggles.onboarding.v2", RemoteOnboardingFlagsSpec.newOnboarding.remoteKey)
    }

    @Test
    fun nestedFlagsAreKeyedByTheirPath() {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))
        source.setBox(FlagKey("checkout.express.one-tap"), FlagValueBox.Bool(true))

        // region snippet: declaring-nested-read
        val oneTap = pole.flags.checkout.express.oneTap // stored under "checkout.express.one-tap"
        // endregion

        assertTrue(oneTap)
        assertEquals(FlagKey("checkout.express.one-tap"), pole.key(AppFlagsSpec.checkout.express.oneTap))
    }

    @Test
    fun encodingsTurnThePathIntoAKey() {
        val path = AppFlagsSpec.checkout.express.oneTap.keyPath

        assertEquals("checkout.express.one-tap", KeyEncoding.Kebabcase.key(path).rawValue)
        assertEquals("checkout.express.one_tap", KeyEncoding.Snakecase.key(path).rawValue)
        assertEquals("checkout.express.oneTap", KeyEncoding.Verbatim.key(path).rawValue)

        // region snippet: declaring-key-encoding
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source), keyEncoding = KeyEncoding.Snakecase)
        // endregion

        assertEquals(FlagKey("checkout.express.one_tap"), pole.key(AppFlagsSpec.checkout.express.oneTap))
    }

    @Test
    fun wordSplittingHandlesAcronymsAndDigits() {
        assertEquals("use-https-only", KeyEncoding.Kebabcase.key(FlagKeyPath(listOf("useHTTPSOnly"))).rawValue)
        assertEquals("checkout-v2", KeyEncoding.Kebabcase.key(FlagKeyPath(listOf("checkoutV2"))).rawValue)
    }

    @Test
    fun aCustomEncoding() {
        // region snippet: declaring-custom-encoding
        val screaming = KeyEncoding(separator = "__") { it.uppercase() }
        // endregion

        assertEquals("CHECKOUT__EXPRESS__ONETAP", screaming.key(AppFlagsSpec.checkout.express.oneTap.keyPath).rawValue)
        assertEquals("ONETAP", screaming.key(FlagKeyPath(listOf("onetap"))).rawValue)
    }

    @Test
    fun twoFlagsOnOneKeyFailWhenTheSchemaIsBuilt() {
        assertTrue(duplicateKeyMessage().startsWith("Flags must resolve to unique keys"))
    }

    // region snippet: declaring-unique-keys-test
    @Test
    fun everyFlagHasItsOwnKey() {
        FlagSchema.of(AppFlagsSpec, keyEncoding = KeyEncoding.Kebabcase)
    }
    // endregion

    @Test
    fun aFlagCarriesItsOwnMetadata() = runTest {
        val pole = FlagPole(AppFlagsSpec, sources = listOf(source))

        // region snippet: declaring-metadata
        val flag = AppFlagsSpec.newOnboarding

        val key = pole.key(flag) // FlagKey("new-onboarding")
        val description = flag.description // "Show the redesigned onboarding"
        val defaultValue = flag.defaultValue // false
        val remoteKey = flag.remoteKey // "featureToggles.onboarding.v2"
        val current = pole[flag] // what it resolves to right now
        val changes = pole.flow(flag) // a Flow<Boolean>
        // endregion

        assertEquals(FlagKey("new-onboarding"), key)
        assertEquals("Show the redesigned onboarding", description)
        assertFalse(defaultValue)
        assertEquals("featureToggles.onboarding.v2", remoteKey)
        assertFalse(current)
        assertFalse(changes.first())
    }

    @Test
    fun flagsWithoutAPoleReportTheirDefault() {
        // region snippet: declaring-defaults-read
        val showsNewOnboarding = OnboardingPreviewFlagsSpec.flagDefaults.newOnboarding // true, with no pole anywhere
        // endregion

        assertTrue(showsNewOnboarding)
    }

    @Test
    fun publicAliasedAndDescribedContainersWork() {
        assertFalse(FlagPole(StoreFlagsSpec, sources = listOf(source)).flags.newOnboarding)
        assertFalse(FlagPole(AliasedFlagsSpec, sources = listOf(source)).flags.newOnboarding)
        assertEquals("Everything the checkout team can turn on", FlagSchema.of(CheckoutTeamFlagsSpec).description)
    }
}
