package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant

/** One record, a row per field (iOS FlagRecordEditorView). Edits commit as they are made. */
@Composable
internal fun RecordScreen(
    store: FlagEditingStore,
    key: String,
    trail: List<RecordStep>,
    index: Int,
    onOpen: (FlagRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val entry = store.entry(FlagKey(key))
    val level = entry?.let { store.recordLevel(it, trail) }
    val record = level?.records?.getOrNull(index)
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(
            title = if (level != null && record != null) recordTitle(record, level.fields) else FlagStrings.RECORD,
            onBack = onBack,
        )
        if (entry != null && record != null) {
            RecordContent(store = store, entry = entry, trail = trail, index = index, onOpen = onOpen)
        }
    }
}

@Composable
private fun RecordContent(
    store: FlagEditingStore,
    entry: FlagSchema.Entry,
    trail: List<RecordStep>,
    index: Int,
    onOpen: (FlagRoute) -> Unit,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val level = store.recordLevel(entry, trail) ?: return
    val records = level.records ?: return
    val record = records.getOrNull(index) ?: return
    val others = records.filterIndexed { position, _ -> position != index }
    var failedWrites by remember { mutableIntStateOf(0) }
    val update: (FlagRecordField, FlagValueBox) -> Unit = { field, box ->
        writer.write(onFailed = { failedWrites++ }) {
            val current = store.recordLevel(entry, trail)?.records ?: return@write
            val target = current.getOrNull(index) ?: return@write
            store.setRecordLevel(entry, trail, current.replacing(index, target + (field.name to box)))
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(level.fields, key = { it.name }) { field ->
            RecordFieldRow(
                field = field,
                value = record[field.name] ?: emptyFieldValue(field),
                // Compared as values, as iOS does: a key another record already uses is refused before it is written.
                keysInUse = if (field.isKey) others.mapNotNull { it[field.name] }.toSet() else emptySet(),
                revertKey = failedWrites,
                onChange = { box -> update(field, box) },
                onOpenNested = { onOpen(FlagRoute.Records(entry.key.rawValue, trail + RecordStep(index, field.name))) },
            )
        }
    }
}

@Composable
private fun RecordFieldRow(
    field: FlagRecordField,
    value: FlagValueBox,
    keysInUse: Set<FlagValueBox>,
    revertKey: Int,
    onChange: (FlagValueBox) -> Unit,
    onOpenNested: () -> Unit,
) {
    val nested = field.fields
    val cases = field.cases.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = field.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = field.type.typeName,
            style = MaterialTheme.typography.labelSmall.monospaced(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            nested != null -> FlagNavigationRow(onClick = onOpenNested) {
                Text(
                    text = nestedSummary(value, nested),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cases.isNotEmpty() -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FlagPicker(selected = value, options = cases, onSelect = onChange)
            }
            field.type == FlagValueType.Bool -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Switch(
                    checked = (value as? FlagValueBox.Bool)?.value ?: false,
                    onCheckedChange = { checked -> onChange(FlagValueBox.Bool(checked)) },
                    modifier = Modifier.semantics { contentDescription = field.name },
                )
            }
            field.type == FlagValueType.Date -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FlagDateTimeButton(
                    instant = (value as? FlagValueBox.Date)?.value ?: Instant.now(),
                    onPick = { instant -> onChange(FlagValueBox.Date(instant)) },
                )
            }
            else -> FlagTextField(
                value = FlagValueText.format(value),
                onCommit = { text ->
                    val box = FlagValueText.parse(text, field.type)?.takeUnless { it in keysInUse }
                    if (box != null) onChange(box)
                    box != null
                },
                keyboardType = keyboardTypeFor(field.type),
                revertKey = revertKey,
            )
        }
    }
}

/** What a field starts as: its default, else its first case, else its type's empty value. */
internal fun emptyFieldValue(field: FlagRecordField): FlagValueBox =
    field.defaultValue ?: field.cases?.firstOrNull() ?: FlagValueText.empty(field.type)

private fun nestedSummary(value: FlagValueBox, fields: List<FlagRecordField>): String =
    FlagRecordList.decode(value, fields)?.let { FlagStrings.recordCount(it.size) } ?: FlagStrings.UNREADABLE
