package com.andyyhope.featureflag.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.signals.FlagSignal
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.signals.FlagSignalAttempt
import com.andyyhope.featureflag.ui.signals.FlagSignalCountdown
import com.andyyhope.featureflag.ui.signals.FlagSignalDelay
import com.andyyhope.featureflag.ui.signals.FlagSignalGroup
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The model's state, collected once per screen. */
private class SignalsState(
    val delay: State<FlagSignalDelay>,
    val pending: State<FlagSignal?>,
    val countdown: State<FlagSignalCountdown?>,
    val inFlight: State<FlagSignal?>,
    val history: State<List<FlagSignalAttempt>>,
)

@Composable
private fun FlagSignalsModel.collectState(): SignalsState = SignalsState(
    delay = delay.collectAsState(),
    pending = pending.collectAsState(),
    countdown = countdown.collectAsState(),
    inFlight = inFlight.collectAsState(),
    history = history.collectAsState(),
)

/**
 * The Signals tab (iOS FlagSignalsView): a delay, the signals by group, and what happened to the last
 * five. Groups past the nesting threshold get their own screen through [onOpenGroup].
 */
@Composable
internal fun SignalsScreen(
    model: FlagSignalsModel,
    groups: List<FlagSignalGroup>,
    onOpenGroup: (FlagSignalGroup) -> Unit,
    modifier: Modifier = Modifier,
    title: String = FlagStrings.SIGNALS,
    clock: () -> Instant = Instant::now,
) {
    val state = model.collectState()
    val nested = groups.filter { it.isNested }
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = title)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            delayItems(model, state)
            groups.filterNot { it.isNested }.forEach { group ->
                signalItems(model = model, state = state, group = group, showsHeader = true, clock = clock)
            }
            if (nested.isNotEmpty()) {
                item(key = "groups") { FlagSectionHeader(text = FlagStrings.GROUPS) }
                items(nested, key = { "nested:${it.id}" }) { group ->
                    FlagNavigationRow(
                        onClick = { onOpenGroup(group) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(text = group.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${group.signals.size}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            historyItems(state)
        }
    }
}

/**
 * One group on its own screen. The delay is repeated rather than left on the previous screen: it is the
 * mechanism, and going back to change it would defeat it. It shares the tab's model, so a pending send
 * and the history survive the push.
 */
@Composable
internal fun SignalGroupScreen(
    model: FlagSignalsModel,
    group: FlagSignalGroup,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Instant = Instant::now,
) {
    val state = model.collectState()
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = group.title, onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            delayItems(model, state)
            signalItems(model = model, state = state, group = group, showsHeader = false, clock = clock)
            historyItems(state)
        }
    }
}

/**
 * Leaving the Signals tab cancels a pending send, so the host never delivers one nobody is watching.
 * Not when the activity is only being recreated: the model lives in the ViewModel and carries the send over.
 */
@Composable
internal fun CancelPendingOnLeave(model: FlagSignalsModel) {
    val activity = LocalActivity.current
    DisposableEffect(model) {
        onDispose {
            if (shouldCancelOnLeave(isChangingConfigurations = activity?.isChangingConfigurations == true)) {
                model.cancelPending()
            }
        }
    }
}

internal fun shouldCancelOnLeave(isChangingConfigurations: Boolean): Boolean = !isChangingConfigurations

private fun LazyListScope.delayItems(model: FlagSignalsModel, state: SignalsState) {
    item(key = "delay-header") { FlagSectionHeader(text = FlagStrings.DELAY) }
    item(key = "delay") {
        val options = FlagSignalDelay.entries
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == state.delay.value,
                    onClick = { model.delay.value = option },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    enabled = state.pending.value == null,
                    label = { Text(text = option.label) },
                )
            }
        }
    }
    item(key = "delay-footer") {
        FlagSectionFooter(
            text = if (state.delay.value == FlagSignalDelay.Instant) {
                FlagStrings.DELAY_FOOTER_INSTANT
            } else {
                FlagStrings.DELAY_FOOTER_DELAYED
            },
        )
    }
}

private fun LazyListScope.signalItems(
    model: FlagSignalsModel,
    state: SignalsState,
    group: FlagSignalGroup,
    showsHeader: Boolean,
    clock: () -> Instant,
) {
    if (showsHeader && group.title.isNotEmpty()) {
        item(key = "header:${group.id}") { FlagSectionHeader(text = group.title) }
    }
    items(group.signals, key = { "signal:${group.id}:${it.name}" }) { signal ->
        val pending = state.pending.value
        val countdown = state.countdown.value
        SignalRow(
            signal = signal,
            isInFlight = state.inFlight.value == signal,
            countdown = countdown?.takeIf { pending == signal },
            isEnabled = !model.isDisabled(signal),
            onClick = { model.tapped(signal) },
            clock = clock,
        )
    }
    // The untitled group is iOS's single-type form; only it explains signals and how to cancel.
    if (showsHeader && group.title.isEmpty()) {
        item(key = "footer:${group.id}") {
            FlagSectionFooter(text = if (state.pending.value == null) FlagStrings.SIGNALS_FOOTER else FlagStrings.TAP_AGAIN_TO_CANCEL)
        }
    }
}

private val TimeText: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

/** Read while the list is built, which LazyColumn tracks, so a new attempt adds its row. */
private fun LazyListScope.historyItems(state: SignalsState) {
    val history = state.history.value
    if (history.isEmpty()) return
    item(key = "recent") { FlagSectionHeader(text = FlagStrings.RECENT) }
    items(history) { attempt -> HistoryRow(attempt) }
}

@Composable
private fun SignalRow(
    signal: FlagSignal,
    isInFlight: Boolean,
    countdown: FlagSignalCountdown?,
    isEnabled: Boolean,
    onClick: () -> Unit,
    clock: () -> Instant,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = signal.signalDescription,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEnabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
            )
            if (signal.requiresRestart) {
                Text(
                    text = FlagStrings.TAKES_EFFECT_AFTER_A_RELAUNCH,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        when {
            isInFlight -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            countdown != null -> CountdownRing(countdown = countdown, clock = clock)
            else -> Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = if (isEnabled) colors.primary else colors.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

/** Fills clockwise from the top as the delay runs down, from the clock rather than from a count of frames. */
@Composable
private fun CountdownRing(countdown: FlagSignalCountdown, clock: () -> Instant) {
    val progress = remember(countdown) { Animatable(countdown.progress(clock())) }
    LaunchedEffect(countdown) {
        val remaining = java.time.Duration.between(clock(), countdown.end).toMillis().coerceAtLeast(0)
        progress.animateTo(1f, tween(durationMillis = remaining.toInt(), easing = LinearEasing))
    }
    val description = FlagStrings.sendingIn(countdown.duration.inWholeSeconds)
    CircularProgressIndicator(
        progress = { progress.value },
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = description },
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
        strokeCap = StrokeCap.Round,
    )
}

@Composable
private fun HistoryRow(attempt: FlagSignalAttempt) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = attempt.signal.signalDescription, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = TimeText.format(attempt.at.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = attempt.outcome,
            style = MaterialTheme.typography.bodyMedium,
            color = if (attempt.handled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
