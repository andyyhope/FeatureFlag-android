package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagEditorKind
import com.andyyhope.featureflag.ui.editing.editorKind
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant

/** An array of scalars, one row per element (iOS FlagArrayEditorView). Every change writes the whole array. */
@Composable
internal fun ListEditorScreen(store: FlagEditingStore, key: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    store.observeRevision()
    val entry = store.entry(FlagKey(key))
    val element = (entry?.editorKind as? FlagEditorKind.List)?.element
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = entry?.description ?: key, onBack = onBack)
        if (entry != null && element != null) ListEditorContent(store = store, entry = entry, element = element)
    }
}

@Composable
private fun ListEditorContent(store: FlagEditingStore, entry: FlagSchema.Entry, element: FlagValueType) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val values = (store.value(entry) as? FlagValueBox.Array)?.value.orEmpty()
    val isOverridden = store.isOverridden(entry)
    var failedWrites by remember { mutableIntStateOf(0) }
    val write: (List<FlagValueBox>) -> Unit = { changed ->
        writer.write(onFailed = { failedWrites++ }) { store.setValue(entry, FlagValueBox.Array(changed)) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "count") { FlagSectionHeader(text = FlagStrings.itemCount(values.size)) }
        itemsIndexed(values) { index, value ->
            ListElementRow(
                element = element,
                value = value,
                index = index,
                count = values.size,
                revertKey = failedWrites,
                onChange = { box -> write(values.replacing(index, box)) },
                onMove = { to -> write(values.moving(index, to)) },
                onDelete = { write(values.removing(index)) },
            )
        }
        item(key = "add") {
            FlagActionRow(
                text = FlagStrings.ADD,
                onClick = { write(values + FlagValueText.empty(element)) },
                icon = Icons.Filled.Add,
            )
        }
        item(key = "footer") { FlagSectionFooter(text = FlagStrings.listFooter(element.typeName)) }
        if (isOverridden) {
            item(key = "reset") {
                FlagActionRow(
                    text = FlagStrings.RESET_TO_DEFAULT,
                    onClick = { writer.write { store.reset(entry) } },
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun ListElementRow(
    element: FlagValueType,
    value: FlagValueBox,
    index: Int,
    count: Int,
    revertKey: Int,
    onChange: (FlagValueBox) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (element) {
                FlagValueType.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = FlagValueText.format(value),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.monospaced(),
                    )
                    Switch(
                        checked = (value as? FlagValueBox.Bool)?.value ?: false,
                        onCheckedChange = { checked -> onChange(FlagValueBox.Bool(checked)) },
                    )
                }
                FlagValueType.Date -> FlagDateTimeButton(
                    instant = (value as? FlagValueBox.Date)?.value ?: Instant.now(),
                    onPick = { instant -> onChange(FlagValueBox.Date(instant)) },
                )
                else -> FlagTextField(
                    value = FlagValueText.format(value),
                    onCommit = { text -> FlagValueText.parse(text, element)?.also(onChange) != null },
                    keyboardType = keyboardTypeFor(element),
                    revertKey = revertKey,
                )
            }
        }
        FlagRowActions(index = index, count = count, onMove = onMove, onDelete = onDelete)
    }
}

/** A row's reorder and remove actions, in a menu so no drag-and-drop dependency is needed. */
@Composable
internal fun FlagRowActions(
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: (() -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { isExpanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = FlagStrings.ACTIONS)
        }
        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = FlagStrings.MOVE_UP) },
                onClick = {
                    isExpanded = false
                    onMove(index - 1)
                },
                enabled = index > 0,
            )
            DropdownMenuItem(
                text = { Text(text = FlagStrings.MOVE_DOWN) },
                onClick = {
                    isExpanded = false
                    onMove(index + 1)
                },
                enabled = index < count - 1,
            )
            if (onDuplicate != null) {
                DropdownMenuItem(
                    text = { Text(text = FlagStrings.DUPLICATE) },
                    onClick = {
                        isExpanded = false
                        onDuplicate()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = FlagStrings.DELETE) },
                onClick = {
                    isExpanded = false
                    onDelete()
                },
            )
        }
    }
}

internal fun <T> List<T>.replacing(index: Int, item: T): List<T> = toMutableList().also { it[index] = item }

internal fun <T> List<T>.removing(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

internal fun <T> List<T>.moving(from: Int, to: Int): List<T> = toMutableList().also { it.add(to, it.removeAt(from)) }

internal fun <T> List<T>.duplicating(index: Int): List<T> = toMutableList().also { it.add(index + 1, it[index]) }
