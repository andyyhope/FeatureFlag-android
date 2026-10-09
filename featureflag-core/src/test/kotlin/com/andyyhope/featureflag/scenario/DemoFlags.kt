package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

/** Which backend this build talks to. It has no remoteKey, so no payload can choose which payload gets fetched. */
internal enum class DemoEnvironment(override val flagRawValue: String) : FlagStringEnum {
    Production("production"),
    Staging("staging"),
    Local("local"),
}

internal enum class CheckoutTier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
    Enterprise("enterprise"),
}

internal enum class PaymentKind(override val flagRawValue: String) : FlagStringEnum {
    Card("card"),
    Wallet("wallet"),
    Transfer("transfer"),
}

/** A record nested inside another. */
@FlagRecord
internal data class SpendLimit(val currency: String, val maximum: Double)

@FlagRecord
internal data class PaymentMethod(
    @FlagRecordKey val name: String,
    val kind: PaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
    val limits: FlagRecords<SpendLimit> = FlagRecords(),
)

/**
 * A port of Examples/DemoApp/AppFlags.swift. `applePay` keeps its iOS name here; the M4
 * samples rename it to googlePay.
 */
@FlagContainer(description = "Everything this demo can turn on, and where each value came from")
internal interface DemoFlags {
    @Flag(description = "Which backend this build talks to")
    val environment: DemoEnvironment get() = DemoEnvironment.Production

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
                PaymentMethod("Apple Pay", PaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
                PaymentMethod("Bank transfer", PaymentKind.Transfer, enabled = false, minimumSpend = 50.0),
            ),
        )

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
internal interface CheckoutFlags {
    @Flag(description = "Offer Apple Pay at checkout", remoteKey = "featureToggles.checkout.applePay")
    val applePay: Boolean get() = false

    @Flag(description = "Which pricing tier to present")
    val tier: CheckoutTier get() = CheckoutTier.Free

    @Flag(description = "Backend the app talks to", remoteKey = "config.apiEndpoint")
    val endpoint: URI get() = URI("https://api.example.com")

    @FlagGroup(description = "Express checkout")
    val express: ExpressFlags
}

@FlagContainer
internal interface ExpressFlags {
    @Flag(description = "Skip the review step")
    val oneTap: Boolean get() = false

    @Flag(description = "Seconds before the order is placed")
    val confirmationDelay: Double get() = 3.0
}
