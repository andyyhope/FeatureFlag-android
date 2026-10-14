package com.andyyhope.featureflag.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.ui.share.FlagClipboard
import com.andyyhope.featureflag.ui.share.FlagQrImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The overrides as a QR code to scan with a second device (iOS FlagQRCodeView), with ways to get the code
 * off the screen without one: copy or share the image, or copy the code's text to paste into Import.
 */
@Composable
internal fun QrSheet(store: FlagEditingStore, onDismiss: () -> Unit) {
    val revision = store.observeRevision()
    val context = LocalContext.current
    val count = store.overriddenKeys.size
    val code = remember(revision) { runCatching { store.qrCode() } }
    val image = remember(code) {
        code.getOrNull()?.let { text -> runCatching { FlagQrImage.bitmap(text) }.getOrNull() }
    }
    val copyableCode = remember(revision) { store.copyableQrCode }
    val fileName = "feature-flags-$count-overrides"
    var writeFailure by remember { mutableStateOf<String?>(null) }

    /** The PNG Copy image and Save hand over, or null once the sheet says why it could not be written. */
    fun pngUri(bitmap: Bitmap): Uri? {
        val written = runCatching { FlagQrImage.uri(context, FlagQrImage.writePng(context, bitmap, fileName)) }
        val failure = written.exceptionOrNull()
        if (failure is CancellationException || (failure != null && failure !is Exception)) throw failure
        writeFailure = failure?.let { FlagStrings.couldNotWriteImage(it.message ?: it.toString()) }
        return written.getOrNull()
    }

    FlagSheet(
        title = FlagStrings.SCAN_TO_COPY,
        onDismiss = onDismiss,
        navigation = { TextButton(onClick = onDismiss) { Text(text = FlagStrings.DONE) } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = FlagStrings.qrCodeDescription(count),
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                    // Nearest neighbour: a smoothed module is a blurred one.
                    filterQuality = FilterQuality.None,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    FeedbackButton(label = FlagStrings.COPY_IMAGE, doneLabel = FlagStrings.IMAGE_COPIED, icon = FlagIcons.ContentCopy) {
                        val uri = pngUri(image)
                        if (uri != null) FlagClipboard.copyImage(context, uri)
                        uri != null
                    }
                    TextButton(onClick = { pngUri(image)?.let { uri -> sharePng(context, uri) } }) {
                        Icon(imageVector = FlagIcons.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = FlagStrings.SAVE)
                    }
                }
                writeFailure?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                Text(
                    text = qrUnavailableMessage(code),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (copyableCode != null) {
                // No camera, no second device: paste the text into Import instead.
                FeedbackButton(label = FlagStrings.COPY_CODE, doneLabel = FlagStrings.CODE_COPIED, icon = FlagIcons.ContentCopy) {
                    FlagClipboard.copy(context, copyableCode)
                    true
                }
            }
            Text(
                text = FlagStrings.overrideCount(count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A text button that says it worked ("Image copied", "Code copied") for [COPIED_FEEDBACK_MILLIS].
 * [onClick] returns whether it did: one that failed shows no feedback.
 */
@Composable
private fun FeedbackButton(label: String, doneLabel: String, icon: ImageVector, onClick: () -> Boolean) {
    var isDone by remember { mutableStateOf(false) }
    LaunchedEffect(isDone) {
        if (isDone) {
            delay(COPIED_FEEDBACK_MILLIS)
            isDone = false
        }
    }
    TextButton(
        onClick = { isDone = onClick() },
    ) {
        Icon(imageVector = if (isDone) Icons.Filled.Check else icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = if (isDone) doneLabel else label)
    }
}

/** Why there is no image: too many overrides for one code (always plural, as iOS words it), or a drawing failure. */
internal fun qrUnavailableMessage(code: Result<String>): String = when (val error = code.exceptionOrNull()) {
    null -> FlagStrings.COULD_NOT_RENDER
    is FlagQRCodeException.PayloadTooLarge -> FlagStrings.qrTooLarge(error.overrideCount, error.bytes, error.limit)
    else -> error.message ?: error.toString()
}

private fun sharePng(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip carries the grant to whichever app is chosen.
    send.clipData = ClipData.newRawUri(FlagStrings.SCAN_TO_COPY, uri)
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
