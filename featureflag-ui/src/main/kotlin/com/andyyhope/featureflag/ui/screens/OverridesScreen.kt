package com.andyyhope.featureflag.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagValueText

private enum class OverridesSheet { QrCode, Import }

private data class OverrideLine(val entry: FlagSchema.Entry, val text: String)

/**
 * The Overrides tab (iOS FlagOverridesView): what has been changed, the JSON that carries it, the QR
 * code, sharing, resetting, and importing.
 */
@Composable
internal fun OverridesScreen(store: FlagEditingStore, modifier: Modifier = Modifier) {
    val revision = store.observeRevision()
    val writer = LocalFlagWriter.current
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<OverridesSheet?>(null) }
    var isConfirmingReset by rememberSaveable { mutableStateOf(false) }
    val lines = store.overriddenKeys.mapNotNull { key ->
        store.entry(key)?.let { entry -> OverrideLine(entry, FlagValueText.format(store.value(entry))) }
    }
    // Keyed by revision: the export carries its own timestamp, so it is made once per change, not per frame.
    val json = remember(revision) { exportedJson(store) }
    Column(modifier = modifier.fillMaxSize()) {
        FlagTopBar(title = FlagStrings.OVERRIDES)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (lines.isEmpty()) {
                item(key = "nothing") {
                    FlagContentUnavailable(title = FlagStrings.NOTHING_OVERRIDDEN, message = FlagStrings.NOTHING_OVERRIDDEN_MESSAGE)
                }
            } else {
                item(key = "changed") { FlagSectionHeader(text = FlagStrings.changedCount(lines.size)) }
                items(lines, key = { "line:${it.entry.key.rawValue}" }) { line ->
                    OverrideRow(line = line, onReset = { writer.write { store.reset(line.entry) } })
                }
                item(key = "lines-footer") { FlagSectionFooter(text = FlagStrings.OVERRIDES_FOOTER) }
                item(key = "json-header") { FlagSectionHeader(text = FlagStrings.JSON) }
                item(key = "json") { JsonBlock(json = json) }
                item(key = "json-footer") { FlagSectionFooter(text = FlagStrings.JSON_FOOTER) }
                item(key = "qr") {
                    FlagActionRow(text = FlagStrings.SHOW_QR_CODE, onClick = { sheet = OverridesSheet.QrCode }, icon = FlagIcons.QrCode)
                }
                item(key = "share") {
                    FlagActionRow(text = FlagStrings.SHARE_JSON, onClick = { shareJson(context, json) }, icon = Icons.Filled.Share)
                }
                item(key = "reset-all") {
                    FlagActionRow(
                        text = FlagStrings.RESET_EVERY_OVERRIDE,
                        onClick = { isConfirmingReset = true },
                        icon = FlagIcons.Undo,
                        destructive = true,
                    )
                }
            }
            item(key = "import") {
                FlagActionRow(
                    text = FlagStrings.IMPORT_ELLIPSIS,
                    onClick = { sheet = OverridesSheet.Import },
                    icon = FlagIcons.Download,
                )
            }
            item(key = "import-footer") { FlagSectionFooter(text = FlagStrings.IMPORT_FOOTER) }
        }
    }
    if (isConfirmingReset) {
        ResetEveryOverrideDialog(
            onConfirm = {
                isConfirmingReset = false
                writer.write { store.resetAll() }
            },
            onDismiss = { isConfirmingReset = false },
        )
    }
    when (sheet) {
        OverridesSheet.QrCode -> QrSheet(store = store, onDismiss = { sheet = null })
        OverridesSheet.Import -> ImportSheet(store = store, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun OverrideRow(line: OverrideLine, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = line.entry.description, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = line.entry.key.rawValue,
                    style = MaterialTheme.typography.labelSmall.monospaced(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onReset) {
                Icon(imageVector = FlagIcons.Undo, contentDescription = FlagStrings.RESET_TO_DEFAULT)
            }
        }
        SelectionContainer {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium.monospaced(),
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun JsonBlock(json: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SelectionContainer {
            Text(
                text = json,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 48.dp, top = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall.monospaced(),
            )
        }
        FlagCopyButton(text = { json }, idleDescription = FlagStrings.COPY_JSON, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ResetEveryOverrideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = FlagStrings.RESET, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = FlagStrings.CANCEL) }
        },
        title = { Text(text = FlagStrings.RESET_EVERY_OVERRIDE_TITLE) },
        text = { Text(text = FlagStrings.RESET_EVERY_OVERRIDE_MESSAGE) },
    )
}

/** The export, or `{}` when a value cannot be written as JSON (iOS shows the same fallback). */
internal fun exportedJson(store: FlagEditingStore): String = try {
    store.exportJson()
} catch (ignored: FlagSerializationException) {
    "{}"
}

private fun shareJson(context: Context, json: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, json)
    val chooser = Intent.createChooser(send, null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
