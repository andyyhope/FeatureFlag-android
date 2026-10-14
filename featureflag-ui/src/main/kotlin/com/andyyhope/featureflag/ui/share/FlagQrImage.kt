package com.andyyhope.featureflag.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File

/** A flag code as a QR image (iOS qrCodeImage + FlagImageExport), and the PNG file the sheet copies and shares. */
internal object FlagQrImage {

    /** Pixels per module: iOS draws the sheet's code at scale 8. */
    const val MODULE_PIXELS = 8

    private const val DIRECTORY = "featureflag"
    private const val PNG_QUALITY = 100

    /**
     * The code at error correction L, as iOS uses: capacity matters more than damage tolerance for a code
     * read off a screen. Each module is [scale] square pixels, so the image scales without blur.
     */
    fun bitmap(code: String, scale: Int = MODULE_PIXELS): Bitmap {
        val matrix = QRCodeWriter().encode(
            code,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
        )
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { index ->
            if (matrix[(index % size) / scale, (index / size) / scale]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** PNG, never JPEG: JPEG smears the hard edges a scanner relies on. */
    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        output.toByteArray()
    }

    /** Writes `<cacheDir>/featureflag/<name>.png`, replacing it whole. */
    fun writePng(context: Context, bitmap: Bitmap, name: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, "$name.png")
        val partial = File(directory, "$name.png.partial")
        partial.writeBytes(png(bitmap))
        check(partial.renameTo(file)) { "Could not write $file." }
        return file
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The authority featureflag-ui's manifest gives FlagFileProvider. */
    fun authority(context: Context): String = "${context.packageName}.featureflag.files"
}
