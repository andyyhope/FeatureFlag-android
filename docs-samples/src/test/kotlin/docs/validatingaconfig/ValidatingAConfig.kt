package docs.validatingaconfig

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.FlagRecordKey
import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagStringEnum

enum class Tier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
}

@FlagContainer
interface AppFlags {
    @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
    val newOnboarding: Boolean get() = false

    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 20

    @Flag(description = "Pricing tier at checkout", remoteKey = "config.tier")
    val checkoutTier: Tier get() = Tier.Free

    @Flag(description = "Markets the app is live in", remoteKey = "config.markets")
    val markets: List<String> get() = listOf("AU", "NZ")
}

@FlagRecord
data class PaymentMethod(
    @FlagRecordKey val name: String,
    val enabled: Boolean,
    val minimumSpend: Double,
)

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 10

    @Flag(description = "Payment methods offered at checkout", remoteKey = "config.paymentMethods")
    val paymentMethods: FlagRecords<PaymentMethod>
        get() = FlagRecords(
            listOf(
                PaymentMethod("Visa", enabled = true, minimumSpend = 0.0),
                PaymentMethod("Google Pay", enabled = true, minimumSpend = 5.0),
                PaymentMethod("Bank transfer", enabled = false, minimumSpend = 50.0),
            ),
        )
}

/** A config with a typo, an unknown tier, and metadata no flag reads. */
val draftConfig = """
    {
      "featureToggles": { "onboarding": { "v2": true } },
      "config": { "page_size": 25, "tier": "gold", "markets": ["AU", "NZ"] },
      "meta": { "version": 3 }
    }
""".trimIndent()

/** The same config, fixed. Also on the test classpath as staging.json. */
val stagingConfig = """
    {
      "featureToggles": { "onboarding": { "v2": true } },
      "config": { "pageSize": 20, "tier": "pro", "markets": ["AU", "NZ"] },
      "meta": { "version": 3 }
    }
""".trimIndent()

/** A checkout config that changes one payment method, drops one and adds one. */
val checkoutConfig = """
    {
      "config": {
        "pageSize": 25,
        "paymentMethods": [
          { "name": "Visa", "enabled": true, "minimumSpend": 0 },
          { "name": "Google Pay", "enabled": false, "minimumSpend": 10 },
          { "name": "PayPal", "enabled": true, "minimumSpend": 0 }
        ]
      }
    }
""".trimIndent()

/** What printing the audit of [draftConfig] shows. */
fun draftAuditReport(): String = FlagMappingAudit(AppFlagsSpec, draftConfig.encodeToByteArray()).toString()

/** What [stagingConfig] changes. */
fun stagingDefaultsReport(): String =
    FlagMappingAudit(AppFlagsSpec, stagingConfig.encodeToByteArray()).defaultsDescription

/** What [checkoutConfig] changes, record by record. */
fun checkoutDefaultsReport(): String =
    FlagMappingAudit(CheckoutFlagsSpec, checkoutConfig.encodeToByteArray()).defaultsDescription
