package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.editing.FlagEditorKind
import com.andyyhope.featureflag.ui.editing.editorKind
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueText

/**
 * One flag on its own tab (iOS FlagDetailView): an enum as a radio list, anything else as its row, then
 * what the flag is. A key the schema does not publish says so, since the usual mistake is a property name.
 */
@Composable
internal fun DetailScreen(
    store: FlagEditingStore,
    key: String,
    title: String?,
    onOpen: (FlagRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    store.observeRevision()
    val writer = LocalFlagWriter.current
    val entry = store.entry(FlagKey(key))
    val value = entry?.let { store.value(it) }
    val isOverridden = entry?.let { store.isOverridden(it) } ?: false
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = title ?: entry?.description ?: key)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (entry == null) {
                item(key = "missing") {
                    Text(
                        text = FlagStrings.noSuchFlag(key),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "missing-footer") { FlagSectionFooter(text = FlagStrings.NO_SUCH_FLAG_FOOTER) }
                return@LazyColumn
            }
            item(key = "header") { FlagSectionHeader(text = entry.description) }
            val kind = entry.editorKind
            if (kind is FlagEditorKind.Picker) {
                items(kind.cases, key = { "case:${FlagValueText.format(it)}" }) { option ->
                    FlagRadioRow(
                        label = capitalized(FlagValueText.format(option)),
                        isSelected = option == value,
                        onClick = { writer.write { store.setValue(entry, option) } },
                    )
                }
            } else {
                item(key = "row") { FlagRow(store = store, entry = entry, onOpen = onOpen) }
            }
            item(key = "footer") { FlagSectionFooter(text = FlagStrings.DETAIL_FOOTER) }
            item(key = "details") { FlagSectionHeader(text = FlagStrings.DETAILS) }
            item(key = "key") { DetailLine(label = FlagStrings.KEY, value = entry.key.rawValue) }
            item(key = "default") { DetailLine(label = FlagStrings.DEFAULT, value = FlagValueText.format(entry.defaultValue)) }
            val hostSource = if (isOverridden) null else store.sourceName(entry)
            if (hostSource != null) {
                item(key = "source") { DetailLine(label = FlagStrings.VALUE_FROM, value = hostSource) }
            }
            item(key = "remote") {
                DetailLine(
                    label = FlagStrings.REMOTELY_OVERRIDABLE,
                    value = entry.remoteKey?.let { FlagStrings.remoteKey(it) } ?: FlagStrings.NO,
                )
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
            item(key = "remote-footer") {
                FlagSectionFooter(
                    text = if (entry.remoteKey == null) FlagStrings.NO_REMOTE_KEY_FOOTER else FlagStrings.REMOTE_KEY_FOOTER,
                )
            }
        }
    }
}

@Composable
private fun FlagRadioRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** Swift's `String.capitalized`: the first letter of each word upper case, the rest lower case. */
internal fun capitalized(text: String): String {
    val result = StringBuilder(text.length)
    var startsWord = true
    for (character in text) {
        result.append(if (startsWord) character.titlecase() else character.lowercase())
        startsWord = !character.isLetterOrDigit()
    }
    return result.toString()
}
