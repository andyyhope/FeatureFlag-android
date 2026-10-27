package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagEditorKind
import com.andyyhope.featureflag.ui.editing.editorKind
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant

/**
 * One flag, with the control its type calls for (iOS FlagRowView): its description and key, the
 * Overridden pill and reset arrow when overridden, then the editor. List and record flags push their
 * own screen through [onOpen].
 */
@Composable
internal fun FlagRow(
    store: FlagEditingStore,
    entry: FlagSchema.Entry,
    onOpen: (FlagRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val value = store.value(entry)
    var failedWrites by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val isOverridden = store.isOverridden(entry)
        FlagRowHeader(
            entry = entry,
            isOverridden = isOverridden,
            // Where the value comes from when it is not the companion's own: a host config, say.
            hostSource = if (isOverridden) null else store.sourceName(entry),
            onReset = { writer.write { store.reset(entry) } },
        )
        when (val kind = entry.editorKind) {
            FlagEditorKind.Toggle -> TrailingControl {
                Switch(
                    checked = (value as? FlagValueBox.Bool)?.value ?: false,
                    onCheckedChange = { checked -> writer.write { store.setValue(entry, FlagValueBox.Bool(checked)) } },
                    modifier = Modifier.semantics { contentDescription = entry.description },
                )
            }
            is FlagEditorKind.Picker -> TrailingControl {
                FlagPicker(
                    selected = value,
                    options = kind.cases,
                    onSelect = { box -> writer.write { store.setValue(entry, box) } },
                )
            }
            FlagEditorKind.Date -> TrailingControl {
                FlagDateTimeButton(
                    instant = (value as? FlagValueBox.Date)?.value ?: Instant.now(),
                    onPick = { instant -> writer.write { store.setValue(entry, FlagValueBox.Date(instant)) } },
                )
            }
            FlagEditorKind.Integer, FlagEditorKind.Decimal, FlagEditorKind.Url, FlagEditorKind.Text, FlagEditorKind.Data, FlagEditorKind.Json -> FlagTextField(
                value = FlagValueText.format(value),
                onCommit = { text ->
                    // Refuse what does not parse rather than guess; the field snaps back and warns.
                    val box = FlagValueText.parse(text, entry.valueType)
                    if (box != null) writer.write(onFailed = { failedWrites++ }) { store.setValue(entry, box) }
                    box != null
                },
                keyboardType = keyboardTypeFor(entry.valueType),
                multiline = kind.wantsWrappingEditor,
                revertKey = failedWrites,
            )
            is FlagEditorKind.List -> FlagNavigationRow(onClick = { onOpen(FlagRoute.ListEditor(entry.key.rawValue)) }) {
                Text(
                    text = listSummary(value, kind.element),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Middle truncation keeps both brackets, which says it is a list and where it stops.
                Text(
                    text = FlagValueText.format(value),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.monospaced(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.MiddleEllipsis,
                    maxLines = 1,
                )
            }
            is FlagEditorKind.Records -> FlagNavigationRow(onClick = { onOpen(FlagRoute.Records(entry.key.rawValue)) }) {
                Text(
                    text = recordSummary(store, entry, value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = kind.fields.joinToString(", ") { it.name },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FlagRowHeader(entry: FlagSchema.Entry, isOverridden: Boolean, hostSource: String?, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = entry.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.key.rawValue,
                style = MaterialTheme.typography.labelSmall.monospaced(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hostSource != null) {
                Text(
                    text = FlagStrings.fromSource(hostSource),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isOverridden) {
            // The most useful thing to see at a glance: what has been changed from what the app ships with.
            FlagPill(text = FlagStrings.OVERRIDDEN)
            IconButton(onClick = onReset) {
                Icon(imageVector = FlagIcons.Undo, contentDescription = FlagStrings.RESET_TO_DEFAULT)
            }
        }
    }
}

/** Toggles, pickers and dates sit at the trailing edge, under the header. */
@Composable
private fun TrailingControl(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { content() }
}

/** "3 strings", "1 int", or "Edit" when the value in effect is not an array. */
internal fun listSummary(value: FlagValueBox, element: FlagValueType): String =
    (value as? FlagValueBox.Array)?.let { FlagStrings.listSummary(it.value.size, element.typeName) } ?: FlagStrings.EDIT

/** "3 records", or why there is no count: a shared key, or text that is not a list of records. */
internal fun recordSummary(store: FlagEditingStore, entry: FlagSchema.Entry, value: FlagValueBox): String {
    val records = store.records(entry)
    if (records != null) return FlagStrings.recordCount(records.size)
    val shape = entry.recordShape.orEmpty()
    val key = shape.firstOrNull { it.isKey }
    return if (key != null && FlagRecordList.duplicateKey(value, shape) != null) {
        FlagStrings.twoRecordsShareA(key.name)
    } else {
        FlagStrings.UNREADABLE
    }
}

/** Android has no numbers-and-punctuation keyboard, so numbers get the numeric ones; the parse is what decides. */
internal fun keyboardTypeFor(type: FlagValueType): KeyboardType = when (type) {
    FlagValueType.Int -> KeyboardType.Number
    FlagValueType.Double, FlagValueType.Float -> KeyboardType.Decimal
    FlagValueType.Url -> KeyboardType.Uri
    else -> KeyboardType.Text
}
