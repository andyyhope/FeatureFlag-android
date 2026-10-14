package com.andyyhope.featureflag.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.companion.FlagCompanionViewModel
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.screens.FlagCompanionContent
import com.andyyhope.featureflag.ui.screens.OnEveryStartAfterTheFirst
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.duplicateSignalName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole companion for one host app: its overrides, a browser for every flag, and any signals,
 * single-flag or custom tabs you add.
 *
 * Keep it in a separate app signed like the host: a host cannot bridge to itself. The companion connects
 * to [hostPackage] through featureflag-bridge, shows a status screen until it can, and reconnects whenever
 * it comes back to the foreground (on every `ON_START` after the first), so a reinstalled host with a new
 * schema is picked up.
 *
 * Draws with the caller's `MaterialTheme`.
 *
 * @param tabs shown in this order; the first starts selected. Two tabs with the same id throw
 *   [IllegalArgumentException].
 * @param timeout how long the host has to answer a call or a signal.
 */
@Composable
public fun FlagCompanion(
    hostPackage: String,
    modifier: Modifier = Modifier,
    tabs: List<FlagCompanionTab> = FlagCompanionTab.Defaults,
    timeout: Duration = 5.seconds,
) {
    requireDistinctTabIds(tabs)
    val viewModel: FlagCompanionViewModel = viewModel(
        key = "featureflag:$hostPackage",
        factory = FlagCompanionViewModel.factory(hostPackage, timeout),
    )
    val session = viewModel.session
    val state by session.state.collectAsStateWithLifecycle()
    OnEveryStartAfterTheFirst(session::onForeground)
    FlagCompanionContent(
        state = state,
        tabs = tabs,
        signals = viewModel.signals,
        onRetry = session::retry,
        onRefresh = session::refresh,
        signalTimeout = timeout,
        modifier = modifier,
    )
}

/** One tab of [FlagCompanion]. Ids must be unique within a companion. */
public sealed interface FlagCompanionTab {

    /** What the companion uses to tell tabs apart. */
    public val id: String

    /** The navigation bar label. */
    public val title: String

    /** What has been overridden, as rows, JSON and a QR code, with import and reset. Id `overrides`. */
    public data object Overrides : FlagCompanionTab {
        override val id: String get() = "overrides"
        override val title: String get() = FlagStrings.OVERRIDES
    }

    /** Every flag the host publishes, by group, with search. Id `flags`. */
    public data object Flags : FlagCompanionTab {
        override val id: String get() = "flags"
        override val title: String get() = FlagStrings.FLAGS
    }

    /**
     * Buttons that send the host's signals, with an optional delay. Id `signals`.
     *
     * @throws IllegalArgumentException when two groups define a signal with the same name: a signal
     *   travels as its name, so the host's observers would both fire for one press.
     */
    public class Signals(
        vararg groups: FlagSignalGroup,
        override val title: String = FlagStrings.SIGNALS,
    ) : FlagCompanionTab {

        /** In the order given. */
        public val groups: List<FlagSignalGroup> = groups.toList()

        override val id: String get() = "signals"

        init {
            val duplicate = this.groups.duplicateSignalName()
            require(duplicate == null) { FlagStrings.duplicateSignal(duplicate.orEmpty()) }
        }
    }

    /** One flag on its own tab, such as an environment switch. Id `detail.<key>`. */
    public class Detail(public val key: String, override val title: String) : FlagCompanionTab {
        override val id: String get() = "detail.$key"
    }

    /** Your own tab. Its [content] gets the store and the standard row through [FlagCompanionScope]. */
    public class Custom(
        override val id: String,
        override val title: String,
        public val icon: ImageVector,
        public val content: @Composable FlagCompanionScope.() -> Unit,
    ) : FlagCompanionTab

    public companion object {
        /** Overrides and Flags: what every companion wants, and nothing it cannot fill. */
        public val Defaults: List<FlagCompanionTab> = listOf(Overrides, Flags)
    }
}

/** What a custom tab can use. */
public interface FlagCompanionScope {

    /** The host's flags, as the built-in tabs see them. */
    public val store: FlagEditingStore

    /** The standard row for one flag, with its editor, Overridden pill and reset. */
    @Composable
    public fun FlagRow(entry: FlagSchema.Entry)
}

/** The first id two tabs share, or null. */
internal fun List<FlagCompanionTab>.duplicateTabId(): String? {
    val seen = HashSet<String>()
    return firstOrNull { !seen.add(it.id) }?.id
}

/** Two tabs with one id leave one of them unreachable, so there is no correct rendering to fall back to. */
internal fun requireDistinctTabIds(tabs: List<FlagCompanionTab>) {
    val duplicate = tabs.duplicateTabId()
    require(duplicate == null) { FlagStrings.duplicateTabId(duplicate.orEmpty()) }
}

/** The navigation bar icon: outlined, or filled when selected. */
internal fun FlagCompanionTab.icon(selected: Boolean): ImageVector = when (this) {
    FlagCompanionTab.Overrides -> FlagIcons.Tune
    FlagCompanionTab.Flags -> if (selected) FlagIcons.FlagFilled else FlagIcons.FlagOutlined
    is FlagCompanionTab.Signals -> if (selected) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Outlined.Send
    is FlagCompanionTab.Detail -> if (selected) FlagIcons.LayersFilled else FlagIcons.LayersOutlined
    is FlagCompanionTab.Custom -> icon
}
