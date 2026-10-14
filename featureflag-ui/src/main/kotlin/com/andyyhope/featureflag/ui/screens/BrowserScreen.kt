package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagSection
import com.andyyhope.featureflag.ui.editing.FlagTreeNode

/**
 * The Flags tab (iOS FlagBrowserView): the schema as a tree of groups, or, while searching, flattened
 * into one section per group with a match.
 */
@Composable
internal fun BrowserScreen(store: FlagEditingStore, onOpen: (FlagRoute) -> Unit, modifier: Modifier = Modifier) {
    store.observeRevision()
    var query by rememberSaveable { mutableStateOf("") }
    val isSearching = query.isNotEmpty()
    val sections = store.sections(query)
    val tree = store.tree
    val counts = overriddenCounts(store, tree)
    val description = store.schema.description
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = store.schema.applicationName ?: FlagStrings.FEATURE_FLAGS)
        FlagSearchField(query = query, onQueryChange = { query = it })
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (description != null && !isSearching) {
                item(key = "description") { FlagSectionFooter(text = description) }
            }
            when {
                sections.isEmpty() -> item(key = "empty") {
                    if (isSearching) {
                        FlagContentUnavailable(title = FlagStrings.NO_MATCHES, message = FlagStrings.noMatches(query))
                    } else {
                        FlagContentUnavailable(title = FlagStrings.NO_FLAGS, message = FlagStrings.NO_FLAGS_MESSAGE)
                    }
                }
                isSearching -> sectionItems(store = store, sections = sections, onOpen = onOpen)
                else -> treeItems(store = store, node = tree, overriddenCounts = counts, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun FlagSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(text = FlagStrings.SEARCH_FLAGS) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        singleLine = true,
    )
}

/** Search results: a header for each titled section (the full path when nested), then its rows. */
private fun LazyListScope.sectionItems(
    store: FlagEditingStore,
    sections: List<FlagSection>,
    onOpen: (FlagRoute) -> Unit,
) {
    sections.forEach { section ->
        val title = section.title
        if (title != null) {
            item(key = "section:${section.id}") {
                FlagSectionHeader(text = if (section.path.size > 1) section.pathDescription else title)
            }
        }
        items(section.entries, key = { "flag:${it.key.rawValue}" }) { flag ->
            FlagRow(store = store, entry = flag, onOpen = onOpen)
        }
    }
}

/**
 * One level of the tree: its own flags, then a link per subgroup. The "Groups" header shows only when
 * the level has flags of its own, as on iOS. [overriddenCounts] is keyed by group id and computed by
 * the caller's composition, so a write updates the badges.
 */
internal fun LazyListScope.treeItems(
    store: FlagEditingStore,
    node: FlagTreeNode,
    overriddenCounts: Map<String, Int>,
    onOpen: (FlagRoute) -> Unit,
) {
    items(node.flags, key = { "flag:${it.key.rawValue}" }) { flag ->
        FlagRow(store = store, entry = flag, onOpen = onOpen)
    }
    if (node.groups.isEmpty()) return
    if (node.flags.isNotEmpty()) {
        item(key = "groups") { FlagSectionHeader(text = FlagStrings.GROUPS) }
    }
    items(node.groups, key = { "group:${it.id}" }) { group ->
        FlagGroupLink(
            group = group,
            overriddenCount = overriddenCounts[group.id] ?: 0,
            onClick = { onOpen(FlagRoute.Group(group.path)) },
        )
    }
}

/** Each direct subgroup's overridden count, by group id. */
internal fun overriddenCounts(store: FlagEditingStore, node: FlagTreeNode): Map<String, Int> =
    node.groups.associate { it.id to store.overriddenCount(it) }

/** A group: its title, how many flags it holds at any depth, and a badge when any are overridden. */
@Composable
internal fun FlagGroupLink(
    group: FlagTreeNode,
    overriddenCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlagNavigationRow(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = group.title ?: group.id, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = FlagStrings.flagCount(group.allFlags.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overriddenCount > 0) FlagPill(text = "$overriddenCount")
    }
}
