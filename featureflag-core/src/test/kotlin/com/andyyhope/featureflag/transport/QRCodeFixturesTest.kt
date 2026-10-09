package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both directions of the QR golden.
 *
 * - `fixtures/qr/<name>.qr.txt` is the code FeatureFlag 0.12.0 made for the export JSON in
 *   `<name>.json`: `golden`, `single`, and `stored`, which Apple stored uncompressed.
 * - `fixtures-android/` holds the codes this port makes. `decode-kotlin-fixtures` decodes them with Swift.
 */
class QRCodeFixturesTest {

    private val appleCodes = File(fixturesDirectory(), "qr")
    private val androidCodes = File(fixturesDirectory().parentFile, "fixtures-android")

    private fun appleFixtures(): List<Pair<String, File>> = appleCodes.listFiles().orEmpty()
        .filter { it.name.endsWith(".qr.txt") }
        .sortedBy { it.name }
        .map { it.name.removeSuffix(".qr.txt") to it }

    @Test
    fun everyAppleCodeDecodesToItsJson() {
        val fixtures = appleFixtures()
        assertTrue(fixtures.isNotEmpty(), "no QR fixtures in $appleCodes")

        for ((name, file) in fixtures) {
            val json = File(appleCodes, "$name.json").readText()
            val code = file.readText().trimEnd()

            assertEquals(json, FlagQRCode.decodeJson(code).decodeToString(), name)
            assertEquals(json, FlagQRCode.decode(code, goldenSchema).jsonBytes().decodeToString(), name)
        }
    }

    @Test
    fun kotlinEncodesEveryAppleCodeByteForByte() {
        // `stored` included: Kotlin must make the same deflate-or-store choice Apple made.
        for ((name, file) in appleFixtures()) {
            if (name in ZLIB_DIFFERENCES) continue
            val json = File(appleCodes, "$name.json").readBytes()

            assertEquals(file.readText().trimEnd(), FlagQRCode.PREFIX + FlagQRCode.base64UrlEncode(FlagQRCode.compress(json)), name)
        }
    }

    /**
     * `fixtures-android/` is committed, and `decode-kotlin-fixtures` decodes it with Swift. By default this
     * test only checks that the committed files are exactly what this port writes today. With
     * `-Pfeatureflag.writeAndroidFixtures=true` it rewrites them instead, so the source tree changes only
     * when someone asks it to.
     */
    @Test
    fun theKotlinFixturesSwiftDecodesAreCurrent() {
        val write = System.getProperty("featureflag.writeAndroidFixtures") == "true"
        if (write) androidCodes.mkdirs()
        // Decoded values, so each payload equals what reading its own code back yields.
        val payloads = linkedMapOf(
            "golden" to FlagPayload(GoldenOverrides.decodedValues, GoldenOverrides.exportedAt),
            "empty" to FlagPayload(emptyMap(), GoldenOverrides.exportedAt),
            "single" to FlagPayload(mapOf(FlagKey("bool-flag") to FlagValueBox.Bool(true)), GoldenOverrides.exportedAt),
        )

        for ((name, payload) in payloads) {
            val code = FlagQRCode.encode(payload)
            val json = payload.jsonBytes().decodeToString()
            assertEquals(payload, FlagQRCode.decode(code, goldenSchema), name)

            val jsonFile = File(androidCodes, "$name.json")
            val codeFile = File(androidCodes, "$name.qr.txt")
            if (write) {
                jsonFile.writeText(json)
                codeFile.writeText(code)
            } else {
                val stale = "fixtures-android/ is stale; rerun with -Pfeatureflag.writeAndroidFixtures=true"
                assertTrue(jsonFile.isFile && codeFile.isFile, "$name: missing. $stale")
                assertEquals(json, jsonFile.readText(), "$name.json: $stale")
                assertEquals(code, codeFile.readText(), "$name.qr.txt: $stale")
            }
        }
    }

    private companion object {
        /**
         * Apple fixtures whose code this JDK's zlib does not reproduce, so they are only
         * decode-checked. Add a name here only when decoding passes and encoding does not,
         * and give the JDK vendor and version alongside it.
         */
        val ZLIB_DIFFERENCES: Set<String> = emptySet()
    }
}
