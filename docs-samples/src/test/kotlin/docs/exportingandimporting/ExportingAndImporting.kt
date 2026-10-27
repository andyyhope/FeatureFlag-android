package docs.exportingandimporting

import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.exportPayload
import java.net.URI
import java.time.Instant

@FlagContainer
interface AppFlags {
    @Flag(description = "Items per page", remoteKey = "config.pageSize")
    val pageSize: Int get() = 20

    @Flag(description = "Backend the app talks to")
    val endpoint: URI get() = URI("https://api.example.com/v3")

    @Flag(description = "A blob too big for a QR code")
    val payload: ByteArray get() = ByteArray(0)

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false
}

/** What exporting three overrides writes, at a fixed moment. */
fun exportedDocument(): String {
    val pole = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))
    pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
    pole.setOverride(AppFlagsSpec.endpoint, URI("https://staging.example.com/v3"))
    pole.setOverride(AppFlagsSpec.pageSize, 50)
    val payload = pole.exportPayload().copy(exportedAt = Instant.parse("2026-01-01T09:41:00Z"))
    return payload.jsonBytes().decodeToString()
}
