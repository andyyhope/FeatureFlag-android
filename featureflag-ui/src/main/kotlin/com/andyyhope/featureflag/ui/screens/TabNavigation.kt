package com.andyyhope.featureflag.ui.screens

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagCompanionScope
import com.andyyhope.featureflag.ui.FlagCompanionTab
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.signals.FlagSignalsModel
import com.andyyhope.featureflag.ui.screens.FlagRow as StandardFlagRow

/** Each tab's back stack, saved as a Bundle of encoded routes. */
internal val RouteStacksSaver: Saver<SnapshotStateMap<String, List<FlagRoute>>, Bundle> = Saver(
    save = { stacks ->
        Bundle().apply {
            stacks.forEach { (id, routes) -> putStringArrayList(id, ArrayList(routes.map(FlagRouteCodec::encode))) }
        }
    },
    restore = { bundle ->
        mutableStateMapOf<String, List<FlagRoute>>().apply {
            bundle.keySet().forEach { id -> put(id, bundle.getStringArrayList(id).orEmpty().map(FlagRouteCodec::decode)) }
        }
    },
)

/** One tab: its top route, or its root screen. System back pops the tab's own stack. */
@Composable
internal fun TabContent(
    tab: FlagCompanionTab,
    store: FlagEditingStore,
    routes: List<FlagRoute>,
    push: (FlagRoute) -> Unit,
    pop: () -> Unit,
    signalsModel: FlagSignalsModel,
    stateHolder: SaveableStateHolder,
) {
    BackHandler(enabled = routes.isNotEmpty(), onBack = pop)
    if (tab is FlagCompanionTab.Signals) CancelPendingOnLeave(signalsModel)
    val top = routes.lastOrNull()
    val slot = "${tab.id}/${routes.size}/${top?.let(FlagRouteCodec::encode).orEmpty()}"
    stateHolder.SaveableStateProvider(key = slot) {
        if (top == null) {
            TabRoot(tab = tab, store = store, push = push, signalsModel = signalsModel)
        } else {
            RouteScreen(route = top, tab = tab, store = store, push = push, pop = pop, signalsModel = signalsModel)
        }
    }
}

@Composable
private fun TabRoot(
    tab: FlagCompanionTab,
    store: FlagEditingStore,
    push: (FlagRoute) -> Unit,
    signalsModel: FlagSignalsModel,
) {
    when (tab) {
        FlagCompanionTab.Overrides -> OverridesScreen(store = store)
        FlagCompanionTab.Flags -> BrowserScreen(store = store, onOpen = push)
        is FlagCompanionTab.Signals -> SignalsScreen(
            model = signalsModel,
            groups = tab.groups,
            onOpenGroup = { group -> push(FlagRoute.SignalGroup(group.title)) },
            title = tab.title,
        )
        is FlagCompanionTab.Detail -> DetailScreen(store = store, key = tab.key, title = tab.title, onOpen = push)
        is FlagCompanionTab.Custom -> {
            val scope = remember(store, push) { CompanionScope(store, push) }
            Box(modifier = Modifier.fillMaxSize()) { tab.content(scope) }
        }
    }
}

@Composable
private fun RouteScreen(
    route: FlagRoute,
    tab: FlagCompanionTab,
    store: FlagEditingStore,
    push: (FlagRoute) -> Unit,
    pop: () -> Unit,
    signalsModel: FlagSignalsModel,
) {
    when (route) {
        is FlagRoute.Group -> GroupScreen(store = store, path = route.path, onOpen = push, onBack = pop)
        is FlagRoute.ListEditor -> ListEditorScreen(store = store, key = route.key, onBack = pop)
        is FlagRoute.Records -> RecordsScreen(store = store, key = route.key, trail = route.trail, onOpen = push, onBack = pop)
        is FlagRoute.Record -> RecordScreen(
            store = store,
            key = route.key,
            trail = route.trail,
            index = route.index,
            onOpen = push,
            onBack = pop,
        )
        is FlagRoute.SignalGroup -> {
            val group = (tab as? FlagCompanionTab.Signals)?.groups?.firstOrNull { it.title == route.title }
            if (group != null) SignalGroupScreen(model = signalsModel, group = group, onBack = pop)
        }
    }
}

/** A custom tab's scope: the store, and the standard row, whose editors push onto the custom tab's stack. */
private class CompanionScope(
    override val store: FlagEditingStore,
    private val onOpen: (FlagRoute) -> Unit,
) : FlagCompanionScope {

    @Composable
    override fun FlagRow(entry: FlagSchema.Entry) {
        StandardFlagRow(store = store, entry = entry, onOpen = onOpen)
    }
}
