package com.andyyhope.featureflag.ui.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/** The system clipboard: plain text for values, JSON and codes; a content URI for the QR image. */
internal object FlagClipboard {

    private const val LABEL = "Feature flags"
    private const val PNG = "image/png"

    fun copy(context: Context, text: String) {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    /** A PNG the library's FlagFileProvider serves; whoever pastes it reads it through the URI. */
    fun copyImage(context: Context, uri: Uri) {
        clipboard(context).setPrimaryClip(ClipData(ClipDescription(LABEL, arrayOf(PNG)), ClipData.Item(uri)))
    }

    private fun clipboard(context: Context): ClipboardManager =
        checkNotNull(context.getSystemService(ClipboardManager::class.java)) { "This device has no clipboard." }
}
