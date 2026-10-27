package docs.flagvalues

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagRecord
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagIntEnum
import com.andyyhope.featureflag.values.FlagStringEnum
import java.net.URI
import java.time.Instant

// region snippet: values-built-in
@FlagContainer
interface AppFlags {
    @Flag(description = "A switch")
    val enabled: Boolean get() = false

    @Flag(description = "A count")
    val pageSize: Int get() = 20

    @Flag(description = "A larger count")
    val quota: Long get() = 5_000_000_000

    @Flag(description = "A ratio")
    val rolloutShare: Double get() = 0.5

    @Flag(description = "A ratio, smaller")
    val scale: Float get() = 1.0f

    @Flag(description = "A name")
    val channel: String get() = "beta"

    @Flag(description = "A blob")
    val payload: ByteArray get() = ByteArray(0)

    @Flag(description = "A moment")
    val launchesAt: Instant get() = Instant.EPOCH

    @Flag(description = "An endpoint")
    val endpoint: URI get() = URI("https://example.com")
}
// endregion

@FlagContainer
interface CollectionFlags {
    // region snippet: values-collections
    @Flag(description = "Markets to enable")
    val markets: List<String> get() = emptyList()

    @Flag(description = "Per-market rollout share")
    val rolloutByMarket: Map<String, Double> get() = emptyMap()
    // endregion

    // region snippet: values-records-flag
    @Flag(description = "Endpoints")
    val endpoints: FlagRecords<Endpoint> get() = FlagRecords()
    // endregion

    // region snippet: values-enum-flag
    @Flag(description = "Pricing tier to present")
    val tier: Tier get() = Tier.Free
    // endregion

    @Flag(description = "Pricing tier, stored in lowercase")
    val storedTier: StoredTier get() = StoredTier.Free

    @Flag(description = "How hard to retry")
    val retryPolicy: RetryPolicy get() = RetryPolicy.Once
}

// region snippet: values-record
@FlagRecord
data class Endpoint(
    val name: String,
    val url: URI,
    val enabled: Boolean,
)
// endregion

// region snippet: values-enum
enum class Tier { Free, Pro, Enterprise }
// endregion

// region snippet: values-string-enum
enum class StoredTier(override val flagRawValue: String) : FlagStringEnum {
    Free("free"),
    Pro("pro"),
    Enterprise("enterprise"),
}
// endregion

// region snippet: values-int-enum
enum class RetryPolicy(override val flagRawValue: Long) : FlagIntEnum {
    None(0),
    Once(1),
    Aggressive(3),
}
// endregion

// region snippet: values-wrapped-type
@FlagContainer
interface PricingFlags {
    @Flag(description = "Discount at checkout, as a fraction of the price")
    val discountAmount: Double get() = 0.1
}

@JvmInline
value class Percentage(val fraction: Double)

val PricingFlags.discount: Percentage
    get() = Percentage(discountAmount)
// endregion
