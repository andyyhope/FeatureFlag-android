package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

/** Which backend this build talks to. Published as production, staging and local, as on iOS. */
enum class DemoEnvironment(override val flagRawValue: String) : FlagStringEnum {
    Production("production"),
    Staging("staging"),
    Local("local"),
}

enum class CheckoutTier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
    Enterprise("enterprise"),
}

enum class PaymentKind(override val flagRawValue: String) : FlagStringEnum {
    Card("card"),
    Wallet("wallet"),
    Transfer("transfer"),
}

/** A record nested inside another. */
@FlagRecord
data class SpendLimit(val currency: String, val maximum: Double)

@FlagRecord
data class PaymentMethod(
    @FlagRecordKey val name: String,
    val kind: PaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
    /**
     * Declared with a default, so the payloads that predate it, and every list already stored on a
     * device, keep working instead of being rejected.
     */
    val limits: FlagRecords<SpendLimit> = FlagRecords(),
)

/** A port of iOS Examples/DemoApp/AppFlags.swift, with Apple Pay renamed to Google Pay. */
@FlagContainer(description = "Everything this demo can turn on, and where each value came from")
interface AppFlags {
    // region snippet: sample-environment-flag
    /**
     * Deliberately no remoteKey: this flag decides which payload gets fetched, so a payload must
     * not be able to decide it back.
     */
    @Flag(description = "Which backend this build talks to")
    val environment: DemoEnvironment get() = DemoEnvironment.Production
    // endregion

    @Flag(description = "Show the redesigned onboarding flow", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "How many items to show per page")
    val pageSize: Int get() = 10

    @Flag(description = "Markets the app is live in")
    val markets: List<String> get() = listOf("AU", "NZ")

    @Flag(description = "Payment methods offered at checkout", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0),
                PaymentMethod("Google Pay", PaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
                PaymentMethod("Bank transfer", PaymentKind.Transfer, enabled = false, minimumSpend = 50.0),
            ),
        )

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay at checkout", remoteKey = "featureToggles.checkout.googlePay")
    val googlePay: Boolean get() = false

    @Flag(description = "Which pricing tier to present")
    val tier: CheckoutTier get() = CheckoutTier.Free

    @Flag(description = "Backend the app talks to", remoteKey = "config.apiEndpoint")
    val endpoint: URI get() = URI("https://api.example.com")

    @FlagGroup(description = "Express checkout")
    val express: ExpressFlags
}

@FlagContainer
interface ExpressFlags {
    @Flag(description = "Skip the review step")
    val oneTap: Boolean get() = false

    @Flag(description = "Seconds before the order is placed")
    val confirmationDelay: Double get() = 3.0
}
