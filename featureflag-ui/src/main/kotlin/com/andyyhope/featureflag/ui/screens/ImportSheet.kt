package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagImportProblem
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Paste an exported document or a scanned code (iOS FlagImportView). Import is all or nothing; a
 * refusal keeps the sheet and the text, and says why.
 */
@Composable
internal fun ImportSheet(store: FlagEditingStore, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FlagSheet(
        title = FlagStrings.IMPORT,
        onDismiss = onDismiss,
        navigation = { TextButton(onClick = onDismiss) { Text(text = FlagStrings.CANCEL) } },
        actions = {
            TextButton(
                onClick = {
                    scope.launch {
                        problem = importOrDescribe(store, text)
                        if (problem == null) onDismiss()
                    }
                },
                enabled = text.isNotEmpty(),
            ) { Text(text = FlagStrings.IMPORT) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = FlagStrings.IMPORT_HELP,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                textStyle = MaterialTheme.typography.bodyMedium.monospaced(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            problem?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Imports [text]; null when it was applied, else what to tell the person who pasted it. */
internal suspend fun importOrDescribe(store: FlagEditingStore, text: String): String? {
    val failure = runCatching { store.import(text) }.exceptionOrNull() ?: return null
    if (failure is CancellationException || failure !is Exception) throw failure
    return describeImportFailure(failure)
}

/** iOS FlagImportView.describe: short lines for a rejected document, the error's own message otherwise. */
internal fun describeImportFailure(error: Exception): String = when (error) {
    is FlagImportException.Rejected -> error.problems.joinToString("\n") { problem ->
        val key = problem.key.rawValue
        when (problem.kind) {
            FlagImportProblem.Kind.UnknownKey -> FlagStrings.notAFlagInThisApp(key)
            FlagImportProblem.Kind.TypeMismatch -> FlagStrings.wrongType(key)
            FlagImportProblem.Kind.UnknownCase -> FlagStrings.notOneOfItsChoices(key)
        }
    }
    is FlagImportException.UnsupportedFormatVersion -> FlagStrings.newerFormat(error.version)
    is FlagImportException.Malformed -> FlagStrings.NOT_A_FLAG_DOCUMENT
    else -> error.message ?: error.toString()
}
