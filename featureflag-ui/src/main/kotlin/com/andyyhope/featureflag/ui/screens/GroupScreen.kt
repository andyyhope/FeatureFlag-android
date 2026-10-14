package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagTreeNode

/** One group, pushed from the browser (iOS FlagGroupView). A group the schema no longer has shows an empty screen. */
@Composable
internal fun GroupScreen(
    store: FlagEditingStore,
    path: List<String>,
    onOpen: (FlagRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val node = store.tree.find(path)
    val counts = node?.let { overriddenCounts(store, it) }.orEmpty()
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = node?.title ?: FlagStrings.FLAGS, onBack = onBack)
        if (node != null) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                treeItems(store = store, node = node, overriddenCounts = counts, onOpen = onOpen)
            }
        }
    }
}

/** The node at [path] in this subtree, or null. */
internal fun FlagTreeNode.find(path: List<String>): FlagTreeNode? =
    if (this.path == path) this else groups.firstNotNullOfOrNull { it.find(path) }
