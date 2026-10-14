package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText

/** One list of records inside a record-list flag. [records] is null when [raw] cannot be read as one. */
internal class RecordLevel(
    val fields: List<FlagRecordField>,
    val records: List<Map<String, FlagValueBox>>?,
    val raw: FlagValueBox,
)

/**
 * The list at [trail] inside [entry]: the flag's own list when [trail] is empty, else a nested list
 * reached through each step's record and field. Null when the trail no longer leads anywhere.
 */
internal fun FlagEditingStore.recordLevel(entry: FlagSchema.Entry, trail: List<RecordStep>): RecordLevel? {
    var fields = entry.recordShape ?: return null
    var raw = value(entry)
    var records = records(entry)
    for (step in trail) {
        val record = records?.getOrNull(step.index) ?: return null
        val field = fields.firstOrNull { it.name == step.field } ?: return null
        val nested = field.fields ?: return null
        raw = record[field.name] ?: field.defaultValue ?: FlagValueBox.String("")
        records = FlagRecordList.decode(raw, nested)
        fields = nested
    }
    return RecordLevel(fields, records, raw)
}

/** Writes [records] as the list at [trail], re-encoding each parent on the way up. The store refuses a duplicate key at any depth. */
internal suspend fun FlagEditingStore.setRecordLevel(
    entry: FlagSchema.Entry,
    trail: List<RecordStep>,
    records: List<Map<String, FlagValueBox>>,
) {
    if (trail.isEmpty()) {
        setRecords(entry, records)
        return
    }
    val shape = checkNotNull(entry.recordShape) { "'${entry.key}' is not a record list." }
    val root = checkNotNull(records(entry)) { "'${entry.key}' cannot be read as a record list." }
    setRecords(entry, root.replacingNested(shape, trail, records))
}

private fun List<Map<String, FlagValueBox>>.replacingNested(
    fields: List<FlagRecordField>,
    trail: List<RecordStep>,
    replacement: List<Map<String, FlagValueBox>>,
): List<Map<String, FlagValueBox>> {
    val step = trail.first()
    val field = fields.first { it.name == step.field }
    val nested = checkNotNull(field.fields) { "'${field.name}' is not a nested list." }
    val record = this[step.index]
    val inner = if (trail.size == 1) {
        replacement
    } else {
        val current = FlagRecordList.decode(record[field.name] ?: field.defaultValue ?: FlagValueBox.String(""), nested).orEmpty()
        current.replacingNested(nested, trail.drop(1), replacement)
    }
    return replacing(step.index, record + (field.name to FlagRecordList.encode(inner)))
}

/** A record-list flag's records, or a nested list's (iOS FlagRecordsEditorView and FlagRecordList). */
@Composable
internal fun RecordsScreen(
    store: FlagEditingStore,
    key: String,
    trail: List<RecordStep>,
    onOpen: (FlagRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val entry = store.entry(FlagKey(key))
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = trail.lastOrNull()?.field ?: entry?.description ?: key, onBack = onBack)
        if (entry != null) RecordsContent(store = store, entry = entry, trail = trail, onOpen = onOpen)
    }
}

@Composable
private fun RecordsContent(
    store: FlagEditingStore,
    entry: FlagSchema.Entry,
    trail: List<RecordStep>,
    onOpen: (FlagRoute) -> Unit,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val level = store.recordLevel(entry, trail) ?: return
    val records = level.records
    val isOverridden = trail.isEmpty() && store.isOverridden(entry)
    // Each action re-reads the records first, so it never applies to a copy an outside change made stale.
    val update: (change: (List<Map<String, FlagValueBox>>) -> List<Map<String, FlagValueBox>>) -> Unit = { change ->
        writer.write {
            val current = store.recordLevel(entry, trail)?.records ?: return@write
            store.setRecordLevel(entry, trail, change(current))
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (records == null) {
            item(key = "reason") {
                Text(
                    text = unreadableReason(level),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item(key = "raw") {
                Text(
                    text = (level.raw as? FlagValueBox.String)?.value ?: FlagValueText.format(level.raw),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall.monospaced(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "unreadable-footer") { FlagSectionFooter(text = FlagStrings.UNREADABLE_FOOTER) }
        } else {
            item(key = "count") { FlagSectionHeader(text = FlagStrings.recordCount(records.size)) }
            itemsIndexed(records) { index, record ->
                RecordRow(
                    title = recordTitle(record, level.fields),
                    detail = recordDetail(record, level.fields),
                    index = index,
                    count = records.size,
                    onOpen = { onOpen(FlagRoute.Record(entry.key.rawValue, trail, index)) },
                    onMove = { to -> update { it.moving(index, to) } },
                    onDuplicate = { update { it.duplicating(index) } },
                    onDelete = { update { it.removing(index) } },
                )
            }
            item(key = "fields") { FlagSectionFooter(text = FlagStrings.recordsFooter(level.fields.map { it.name })) }
            item(key = "add") {
                FlagActionRow(
                    text = FlagStrings.ADD,
                    onClick = { update { it + FlagRecordList.emptyRecord(level.fields, alongside = it) } },
                    icon = Icons.Filled.Add,
                )
            }
        }
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
private fun RecordRow(
    title: String,
    detail: String,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.monospaced(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
        FlagRowActions(index = index, count = count, onMove = onMove, onDelete = onDelete, onDuplicate = onDuplicate)
    }
}

/** A shared key is named, because a well-formed list breaking that rule needs a different search. */
private fun unreadableReason(level: RecordLevel): String {
    val key = level.fields.firstOrNull { it.isKey }
    val duplicate = FlagRecordList.duplicateKey(level.raw, level.fields)
    return if (key != null && duplicate != null) {
        FlagStrings.duplicateKeyReason(key.name, FlagValueText.format(duplicate))
    } else {
        FlagStrings.NOT_A_LIST_OF_RECORDS
    }
}

/** The field a record is known by: its key field, else its first. */
private fun titleField(fields: List<FlagRecordField>): FlagRecordField? =
    fields.firstOrNull { it.isKey } ?: fields.firstOrNull()

/** The title field's value text; "Untitled" when that is empty, "Record" when there is none. */
internal fun recordTitle(record: Map<String, FlagValueBox>, fields: List<FlagRecordField>): String {
    val box = titleField(fields)?.let { record[it.name] } ?: return FlagStrings.RECORD
    return FlagValueText.format(box).ifEmpty { FlagStrings.UNTITLED }
}

/** Every other field as `name: value`, two spaces apart; a nested list shows how many records it holds. */
internal fun recordDetail(record: Map<String, FlagValueBox>, fields: List<FlagRecordField>): String {
    val title = titleField(fields)
    return fields.filter { it != title }.joinToString("  ") { field ->
        "${field.name}: ${fieldSummary(record[field.name], field)}"
    }
}

private fun fieldSummary(box: FlagValueBox?, field: FlagRecordField): String {
    if (box == null) return FlagStrings.MISSING_VALUE
    val nested = field.fields ?: return FlagValueText.format(box)
    return (FlagRecordList.decode(box, nested)?.size ?: 0).toString()
}
