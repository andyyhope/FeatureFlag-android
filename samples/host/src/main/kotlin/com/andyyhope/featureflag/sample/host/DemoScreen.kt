package com.andyyhope.featureflag.sample.host

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The host's one screen, a port of iOS ContentView: seven sections in the same order, with the
 * same words. Everything it shows comes from [DemoModel]; nothing here reads the pole.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(model: DemoModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val values by model.values.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(text = "Demo") }) },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = innerPadding) {
            environment(
                selected = values.environment,
                applied = state.appliedConfiguration,
                onSelect = model::selectEnvironment,
            )
            liveValues(values)
            paymentMethods(values.paymentMethods)
            remotePayloads(state, onApply = model::applyPayload, onClear = model::clearRemote)
            provenance(values.provenance)
            signals(state)
            flows(state.onboardingChanges)
        }
    }
}

// Environment

private fun LazyListScope.environment(
    selected: DemoEnvironment,
    applied: RemoteConfiguration?,
    onSelect: (DemoEnvironment) -> Unit,
) {
    item { SectionHeader("Environment") }
    item {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            DemoEnvironment.entries.forEachIndexed { index, environment ->
                SegmentedButton(
                    selected = environment == selected,
                    onClick = { onSelect(environment) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = DemoEnvironment.entries.size),
                    label = { Text(text = environment.title) },
                )
            }
        }
    }
    if (applied != null) {
        item { ValueRow(label = "Payload applied", value = applied.name, monospaced = true) }
    }
    item { SectionFooter(ENVIRONMENT_FOOTER) }
}

// What the app sees

private fun LazyListScope.liveValues(values: DemoValues) {
    item { SectionHeader("Live flag values") }
    item { ValueRow(label = "New onboarding", value = values.newOnboarding.toString()) }
    item { ValueRow(label = "Page size", value = values.pageSize.toString()) }
    item { ValueRow(label = "Markets", value = values.markets.joinToString(", ")) }
    item { ValueRow(label = "Google Pay", value = values.googlePay.toString()) }
    item { ValueRow(label = "Tier", value = values.tier.flagRawValue) }
    item { ValueRow(label = "Endpoint", value = values.endpointHost ?: "—") }
    item { ValueRow(label = "One tap", value = values.oneTap.toString()) }
}

/** A record flag read the way an app would actually read one: typed, and used. */
private fun LazyListScope.paymentMethods(methods: List<PaymentMethod>) {
    item { SectionHeader("Payment methods") }
    // Keyed by position (the lazy list's default), as on iOS. Names are unique by the time a list is
    // readable, but a blank one is allowed.
    items(methods) { method -> PaymentMethodRow(method) }
    item { SectionFooter(PAYMENT_METHODS_FOOTER) }
}

// Remote payloads

private fun LazyListScope.remotePayloads(
    state: DemoState,
    onApply: (RemoteConfiguration) -> Unit,
    onClear: () -> Unit,
) {
    item { SectionHeader("Rejecting a bad payload") }
    val offered = listOf(
        RemoteConfiguration.experiments,
        RemoteConfiguration.experimentsWithATypo,
        RemoteConfiguration.malformed,
    )
    items(offered) { configuration -> PayloadButton(configuration, onClick = { onApply(configuration) }) }
    if (state.appliedConfiguration != null) {
        item {
            TextButton(
                onClick = onClear,
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(text = "Clear remote payload") }
        }
    }
    val rejection = state.rejection
    if (rejection != null) {
        item { Rejection(rejection) }
    }
    item { SectionFooter(REJECTION_FOOTER) }
}

// Why each value is what it is

private fun LazyListScope.provenance(rows: List<Provenance>) {
    item { SectionHeader("Where each value came from") }
    items(rows) { row ->
        ValueRow(
            label = row.key,
            value = row.sourceName,
            monospaced = true,
            valueColor = if (row.isDefault) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
    item { SectionFooter(PROVENANCE_FOOTER) }
}

// Signals

private fun LazyListScope.signals(state: DemoState) {
    item { SectionHeader("Signals from the companion") }
    item { ValueRow(label = "Last signal received", value = state.lastSignal ?: "—", monospaced = true) }
    item { ValueRow(label = "Caches purged", value = state.purgedCaches.toString(), monospaced = true) }
    if (state.awaitingRelaunch) {
        item {
            Text(
                text = RELAUNCH_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = Orange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    item { SectionFooter(SIGNALS_FOOTER) }
}

// Flows

private fun LazyListScope.flows(onboardingChanges: Int) {
    item { SectionHeader("Flows") }
    item { ValueRow(label = "new-onboarding changes seen", value = onboardingChanges.toString(), monospaced = true) }
    item { SectionFooter(FLOWS_FOOTER) }
}

// Pieces

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** iOS LabeledContent: a label, then its value. The row is a traversal group, so the two read as a pair. */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    monospaced: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = rowStyle(monospaced), modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = value, style = rowStyle(monospaced), color = valueColor)
    }
}

@Composable
private fun PaymentMethodRow(method: PaymentMethod) {
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = method.name.ifEmpty { "Untitled" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${method.kind.flagRawValue} · min ${formatNumber(method.minimumSpend)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (method.enabled) "on" else "off",
            color = if (method.enabled) Green else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One payload, offered as a button. The broken ones are marked, so a rejection reads as the point. */
@Composable
private fun PayloadButton(configuration: RemoteConfiguration, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = configuration.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = configuration.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (configuration.isDeliberatelyBroken) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(imageVector = Icons.Outlined.Warning, contentDescription = null, tint = Orange)
        }
    }
}

@Composable
private fun Rejection(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Rejected, nothing applied",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun rowStyle(monospaced: Boolean): TextStyle = if (monospaced) {
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
} else {
    MaterialTheme.typography.bodyLarge
}

private val rowModifier = Modifier
    .fillMaxWidth()
    .semantics { isTraversalGroup = true }
    .padding(horizontal = 16.dp, vertical = 10.dp)

/** As iOS shows it: the raw value, capitalised. */
private val DemoEnvironment.title: String
    get() = flagRawValue.replaceFirstChar { first -> first.uppercase() }

/** As iOS's `.number` format writes it: no ".0" on a whole number. */
internal fun formatNumber(value: Double): String =
    if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

// iOS's system green and orange, which Material has no role for.
private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)

private const val ENVIRONMENT_FOOTER =
    "Changing this loads two layers for the environment: a config bundled in the app, then one fetched " +
        "for it, remote winning. The flag carries no remoteKey, so nothing a payload contains can change it " +
        "back — otherwise the two would drive each other in a loop."

private const val PAYMENT_METHODS_FOOTER =
    "A list of records. Edit them field by field in the companion app, or switch environment above to " +
        "have a payload replace the whole list."

private const val REJECTION_FOOTER =
    "One field of the wrong type rejects the whole payload — the valid googlePay beside it is not applied " +
        "either. Clearing the remote layer falls back to the bundled local config, not to raw defaults — " +
        "watch the sources below change from Remote to Local."

private const val PROVENANCE_FOOTER =
    "resolution(key, type) — which layer each value came from. Highest first: a by-hand override " +
        "(Companion), the fetched Remote config, the bundled Local config, then the compiled default."

private const val RELAUNCH_NOTE =
    "Everything was purged. It is rebuilt at launch, so this screen will not look any different until " +
        "the app starts again — which is what that signal's requiresRestart is for."

private const val SIGNALS_FOOTER =
    "The companion can ask this app to do something, not only change what it reads. A signal sent " +
        "while this app is not running starts it, so it is handled rather than lost."

private const val FLOWS_FOOTER =
    "Counted from pole.flow(AppFlagsSpec.newOnboarding) as changes arrive, whether they come from the " +
        "companion app or a remote payload."
