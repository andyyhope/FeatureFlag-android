package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.share.FlagClipboard
import kotlinx.coroutines.delay

/** How long a copy button shows its checkmark: long enough to read, short enough not to lie about the state. */
internal const val COPIED_FEEDBACK_MILLIS: Long = 1_400L

/**
 * A text editor for one value that commits only text that parses (iOS FlagTextField).
 * - It keeps a local draft, and commits on IME Done and when focus leaves. A draft equal to [value] commits nothing.
 * - [onCommit] returns false to refuse: the draft snaps back to [value], and a warning shows until a later commit succeeds.
 * - While focused, a change to [value] from elsewhere does not overwrite the draft; once unfocused, it does.
 * - Bumping [revertKey] (after a failed write) puts [value] back into an unfocused field.
 * - [multiline] draws a wrapping code block with a copy button, for data and JSON. It has no Done; only blur commits.
 */
@Composable
internal fun FlagTextField(
    value: String,
    onCommit: (String) -> Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    revertKey: Int = 0,
) {
    var draft by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }
    var isRejected by remember { mutableStateOf(false) }
    var refusals by remember { mutableIntStateOf(0) }
    val latestValue by rememberUpdatedState(value)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val focusManager = LocalFocusManager.current

    // An outside change (another process, a reset, a failed write) reaches the field only while nobody is typing.
    LaunchedEffect(value) {
        if (!isFocused) draft = value
    }

    // A refused edit or a failed write puts the value back, even if focus has already returned to the field.
    LaunchedEffect(revertKey, refusals) {
        draft = latestValue
    }

    fun commit() {
        if (draft == latestValue) {
            isRejected = false
            return
        }
        isRejected = !latestOnCommit(draft)
        if (isRejected) refusals++
    }

    val focusTracking = Modifier.onFocusChanged { state ->
        if (isFocused && !state.isFocused) commit()
        isFocused = state.isFocused
    }
    val textStyle = MaterialTheme.typography.bodySmall.monospaced()
    val keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = keyboardType,
        imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
    )
    if (multiline) {
        FlagCodeBlock(
            draft = draft,
            onDraftChange = { draft = it },
            isRejected = isRejected,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            fieldModifier = focusTracking,
            modifier = modifier,
        )
    } else {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = modifier
                .fillMaxWidth()
                .then(focusTracking),
            textStyle = textStyle,
            trailingIcon = if (isRejected) {
                { RejectedIcon() }
            } else {
                null
            },
            keyboardOptions = keyboardOptions,
            // Done clears focus, and losing focus is what commits, so an edit is committed exactly once.
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
        )
    }
}

/** Presented as a code block, because that is what it is: a tinted ground and a hairline border mark the editable region. */
@Composable
private fun FlagCodeBlock(
    draft: String,
    onDraftChange: (String) -> Unit,
    isRejected: Boolean,
    textStyle: TextStyle,
    keyboardOptions: KeyboardOptions,
    fieldModifier: Modifier,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.04f))
                .border(width = 1.dp, color = colors.onSurface.copy(alpha = 0.12f), shape = shape)
                // Room on the right for the copy button, so base64 (which has no spaces to wrap at) does not run under it.
                .padding(start = 8.dp, end = 48.dp, top = 6.dp, bottom = 6.dp),
            textStyle = textStyle.copy(color = colors.onSurface),
            keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(colors.primary),
        )
        FlagCopyButton(
            text = { draft },
            idleDescription = FlagStrings.COPY_VALUE,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        if (isRejected) {
            RejectedIcon(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun RejectedIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = FlagStrings.NOT_A_VALID_VALUE,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.error,
    )
}

/** Copies [text] and shows a checkmark ("Copied") for [COPIED_FEEDBACK_MILLIS]. */
@Composable
internal fun FlagCopyButton(text: () -> String, idleDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCopied by remember { mutableStateOf(false) }
    LaunchedEffect(hasCopied) {
        if (hasCopied) {
            delay(COPIED_FEEDBACK_MILLIS)
            hasCopied = false
        }
    }
    IconButton(
        onClick = {
            FlagClipboard.copy(context, text())
            hasCopied = true
        },
        modifier = modifier,
    ) {
        if (hasCopied) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = FlagStrings.COPIED,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = FlagIcons.ContentCopy,
                contentDescription = idleDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
