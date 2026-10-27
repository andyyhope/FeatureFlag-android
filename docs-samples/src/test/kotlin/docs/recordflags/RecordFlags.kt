package docs.recordflags

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.FlagRecordProperty
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI

// region snippet: records-declaration
enum class PaymentKind(override val flagRawValue: String) : FlagStringEnum {
    Card("card"),
    Wallet("wallet"),
}

@FlagRecord
data class PaymentMethod(
    val name: String,
    val kind: PaymentKind,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
interface AppFlags {
    @Flag(description = "Payment methods offered at checkout")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", PaymentKind.Card, enabled = true, minimumSpend = 0.0),
                PaymentMethod("Google Pay", PaymentKind.Wallet, enabled = true, minimumSpend = 5.0),
            ),
        )
}
// endregion

// region snippet: records-key
@FlagRecord
data class Endpoint(
    @FlagRecordKey val name: String,
    val url: URI,
)
// endregion

@FlagContainer
interface EndpointFlags {
    // region snippet: records-remote-flag
    @Flag(description = "Endpoints", remoteKey = "config.endpoints")
    val endpoints: FlagRecords<Endpoint> get() = FlagRecords()
    // endregion
}

// region snippet: records-renamed-field
@FlagRecord
data class PaymentOption(
    @FlagRecordKey val name: String,
    @FlagRecordProperty(key = "google_pay")
    val googlePay: Boolean,
    val minimumSpend: Double?, // absent or null reads as null
)
// endregion

// region snippet: records-nested
@FlagRecord
data class SpendLimit(
    val currency: String,
    val maximum: Double,
)

@FlagRecord
data class PaymentProvider(
    val name: String,
    val limits: FlagRecords<SpendLimit> = FlagRecords(),
)
// endregion

// region snippet: records-added-field
@FlagRecord
data class Mirror(
    val name: String,
    val url: URI,
    val weight: Int = 1, // added in a later build
)
// endregion

@FlagContainer
interface BackendFlags {
    @Flag(description = "Payment options", remoteKey = "config.paymentOptions")
    val paymentOptions: FlagRecords<PaymentOption> get() = FlagRecords()

    @Flag(description = "Payment providers", remoteKey = "config.providers")
    val providers: FlagRecords<PaymentProvider> get() = FlagRecords()

    @Flag(description = "Mirrors")
    val mirrors: FlagRecords<Mirror> get() = FlagRecords()
}
