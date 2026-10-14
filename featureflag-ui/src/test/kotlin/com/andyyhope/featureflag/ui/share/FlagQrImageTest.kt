package com.andyyhope.featureflag.ui.share

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagQrImageTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun freshProviderStrategies() = forgetFileProviderStrategies()

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private fun code(): String {
        val schema = FlagSchema(
            generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            flags = listOf(
                FlagSchema.Entry(
                    key = FlagKey("new-onboarding"),
                    propertyPath = listOf("newOnboarding"),
                    description = "Show the redesigned onboarding",
                    valueType = FlagValueType.Bool,
                    defaultValue = FlagValueBox.Bool(false),
                ),
            ),
        )
        val source = SnapshotSource(values = mapOf(FlagKey("new-onboarding") to FlagValueBox.Bool(true)))
        return FlagEditingStore(schema, source).qrCode()
    }

    @Test
    fun theCodeEncodesAsAPng() {
        val png = FlagQrImage.png(FlagQrImage.bitmap(code(), scale = 4))

        // PNG, not JPEG: JPEG smears a QR code's hard edges into exactly what a scanner struggles with.
        assertContentEquals(pngMagic, png.copyOfRange(0, 4))
        assertTrue(png.size > 100)
    }

    @Test
    fun savingWritesAFileThatExists() {
        val file = FlagQrImage.writePng(context, FlagQrImage.bitmap(code(), scale = 4), "qr-export-${UUID.randomUUID()}")

        assertEquals("png", file.extension)
        assertTrue(file.exists())
        assertContentEquals(pngMagic, file.readBytes().copyOfRange(0, 4))
        file.delete()
    }

    @Test
    fun theImageReadsBackAsTheSameCodeAtLevelL() {
        val code = code()
        val bitmap: Bitmap = FlagQrImage.bitmap(code)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val result = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )

        assertEquals(code, result.text)
        assertEquals("L", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun theFileIsServedByTheLibrarysProvider() {
        // create() reads the ProviderInfo the merged manifest declares, meta-data and grantUriPermissions included.
        Robolectric.buildContentProvider(FlagFileProvider::class.java).create()
        val file = FlagQrImage.writePng(context, FlagQrImage.bitmap(code()), "feature-flags-1-overrides")

        val uri = FlagQrImage.uri(context, file)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.featureflag.files", uri.authority)
        val served = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertContentEquals(pngMagic, served.copyOfRange(0, 4))
    }
}
