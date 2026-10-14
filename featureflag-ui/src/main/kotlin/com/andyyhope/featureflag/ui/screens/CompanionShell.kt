package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andyyhope.featureflag.android.bridge.FlagBridgeException
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.companion.FlagCompanionState
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.icon
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import kotlinx.coroutines.launch

/**
 * The companion for a given connection state, without the ViewModel: what FlagCompanion draws, and what
 * the tests drive with an in-process store. [signals] is the ViewModel's model, so a pending send and
 * Recent survive tab switches and configuration changes.
 */
@Composable
internal fun FlagCompanionContent(
    state: FlagCompanionState,
    tabs: List<FlagCompanionTab>,
    signals: FlagSignalsModel,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        FlagCompanionState.Connecting -> ConnectingScreen(modifier = modifier)
        is FlagCompanionState.Unavailable -> UnavailableScreen(
            title = FlagStrings.NO_FLAGS_YET,
            message = state.message,
            onRetry = onRetry,
            modifier = modifier,
        )
        is FlagCompanionState.Ready -> if (tabs.isEmpty()) {
            UnavailableScreen(title = FlagStrings.NO_TABS, message = FlagStrings.NO_TABS_MESSAGE, onRetry = null, modifier = modifier)
        } else {
            FlagCompanionTabs(
                store = state.store,
                failure = state.failure,
                tabs = tabs,
                signals = signals,
                onRefresh = onRefresh,
                modifier = modifier,
            )
        }
    }
}

/** Calls [onStart] on every ON_START after the first: the companion coming back to the foreground. */
@Composable
internal fun OnEveryStartAfterTheFirst(onStart: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latestOnStart by rememberUpdatedState(onStart)
    DisposableEffect(owner) {
        var hasStarted = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (hasStarted) {
                    latestOnStart()
                } else {
                    hasStarted = true
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun FlagCompanionTabs(
    store: FlagEditingStore,
    failure: FlagBridgeException?,
    tabs: List<FlagCompanionTab>,
    signals: FlagSignalsModel,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val shellScope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf(tabs.first().id) }
    val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.first()
    val stacks = rememberSaveable(saver = RouteStacksSaver) { mutableStateMapOf<String, List<FlagRoute>>() }
    val stateHolder = rememberSaveableStateHolder()
    ProvideFlagWriter(
        onFailure = { error ->
            shellScope.launch {
                snackbarHostState.showSnackbar(
                    message = error.message ?: error.toString(),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        val isSelected = tab.id == selected.id
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedId = tab.id },
                            icon = { Icon(imageVector = tab.icon(isSelected), contentDescription = null) },
                            label = { Text(text = tab.title) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            // Each screen's TopAppBar takes the status bar and the NavigationBar the navigation bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TabContent(
                        tab = selected,
                        store = store,
                        routes = stacks[selected.id].orEmpty(),
                        push = { route -> stacks[selected.id] = stacks[selected.id].orEmpty() + route },
                        pop = { stacks[selected.id] = stacks[selected.id].orEmpty().dropLast(1) },
                        signalsModel = signals,
                        stateHolder = stateHolder,
                    )
                }
                if (failure != null) FailureBanner(message = failure.message.orEmpty(), onTryAgain = onRefresh)
            }
        }
    }
}

/** The bridge's last refresh failed: the values shown are the last good ones. */
@Composable
private fun FailureBanner(message: String, onTryAgain: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onTryAgain) { Text(text = FlagStrings.TRY_AGAIN) }
        }
    }
}
