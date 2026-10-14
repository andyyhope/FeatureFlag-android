package com.andyyhope.featureflag.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs store writes off the composition and reports what fails. A write suspends and can throw whatever
 * the source throws (for a bridge, FlagBridgeException), so no screen calls one directly.
 */
internal class FlagWriter(
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit,
) {

    /** Launches [block]. If it throws, [onFailed] runs first (so a field can put the value back), then the failure is reported. */
    fun write(onFailed: () -> Unit = {}, block: suspend () -> Unit): Job = scope.launch {
        val failure = runCatching { block() }.exceptionOrNull() ?: return@launch
        if (failure is CancellationException || failure !is Exception) throw failure
        onFailed()
        onFailure(failure)
    }
}

/** The screens' writer. The shell provides one that shows failures in its snackbar. */
internal val LocalFlagWriter = staticCompositionLocalOf<FlagWriter> {
    error("No FlagWriter is provided. Wrap the screen in ProvideFlagWriter.")
}

/** Provides a [FlagWriter] whose writes outlive the screen that started them. */
@Composable
internal fun ProvideFlagWriter(onFailure: (Exception) -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val latestOnFailure by rememberUpdatedState(onFailure)
    val writer = remember(scope) { FlagWriter(scope) { failure -> latestOnFailure(failure) } }
    CompositionLocalProvider(LocalFlagWriter provides writer, content = content)
}

/**
 * Reads the store's revision, so the caller recomposes whenever the host's values change. Every
 * composable that reads the store calls this first: a store read is not snapshot state.
 */
@Composable
internal fun FlagEditingStore.observeRevision(): Long = revision.collectAsState().value

/** Flag keys and values are read character by character, so they are set in a monospaced face. */
internal fun TextStyle.monospaced(): TextStyle = copy(fontFamily = FontFamily.Monospace)
