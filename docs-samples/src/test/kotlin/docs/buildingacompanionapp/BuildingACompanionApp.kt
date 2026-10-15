package docs.buildingacompanionapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.andyyhope.featureflag.Flag
import com.andyyhope.featureflag.FlagContainer
import com.andyyhope.featureflag.FlagGroup
import com.andyyhope.featureflag.ui.FlagCompanion
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalGroupDisplay
import docs.sendingsignals.CacheSignal
import docs.sendingsignals.ConfigSignal

enum class Environment { Production, Staging }

@FlagContainer
interface AppFlags {
    @Flag(description = "Which backend this build talks to")
    val environment: Environment get() = Environment.Production

    @FlagGroup(description = "Checkout")
    val checkout: CheckoutFlags
}

@FlagContainer
interface CheckoutFlags {
    @Flag(description = "Offer Google Pay")
    val googlePay: Boolean get() = false

    @Flag(description = "Minimum spend for express checkout")
    val expressMinimum: Double get() = 10.0
}

// region snippet: companion-whole-app
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlagCompanion(hostPackage = "com.example.app")
            }
        }
    }
}
// endregion

/** The companion with every kind of tab, as BuildingACompanionApp.md composes it. */
@Composable
fun CompanionWithTabs() {
    // region snippet: companion-tabs
    FlagCompanion(
        hostPackage = "com.example.app",
        tabs = listOf(
            FlagCompanionTab.Overrides,
            FlagCompanionTab.Signals(
                FlagSignalGroup("Configuration", ConfigSignal.entries),
                FlagSignalGroup("Caches", CacheSignal.entries),
            ),
            FlagCompanionTab.Flags,
            FlagCompanionTab.Detail(key = "environment", title = "Environment"),
        ),
    )
    // endregion
}

// region snippet: companion-custom-tab
val checkoutTab = FlagCompanionTab.Custom(id = "checkout", title = "Checkout", icon = Icons.Filled.ShoppingCart) {
    Column {
        for (entry in store.schema.flags.filter { it.key.rawValue.startsWith("checkout.") }) {
            FlagRow(entry)
        }
    }
}
// endregion

// region snippet: companion-nested-group
val caches = FlagSignalGroup("Caches", CacheSignal.entries, display = FlagSignalGroupDisplay.Nested)
// endregion
