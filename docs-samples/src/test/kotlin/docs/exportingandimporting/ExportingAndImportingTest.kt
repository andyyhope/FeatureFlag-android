package docs.exportingandimporting

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.errors.FlagImportException
import com.andyyhope.featureflag.errors.FlagQRCodeException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.RemoteOverrideSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.transport.applyPayload
import com.andyyhope.featureflag.transport.exportJson
import com.andyyhope.featureflag.transport.exportPayload
import com.andyyhope.featureflag.transport.importJson
import com.andyyhope.featureflag.transport.importQRCode
import com.andyyhope.featureflag.transport.qrCodeString
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/ExportingAndImporting.md. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExportingAndImportingTest {

    private val local = SnapshotSource()
    private val pole = FlagPole(AppFlagsSpec, sources = listOf(local))
    private val codes = mutableListOf<String>()

    private fun showCode(code: String) {
        codes += code
    }

    private fun document(values: String): ByteArray = """{"formatVersion": 1, "values": $values}""".encodeToByteArray()

    @Test
    fun exportingCarriesOnlyOverriddenFlags() {
        pole.setOverride(AppFlagsSpec.pageSize, 50)

        // region snippet: exporting-export
        val json = pole.exportJson() // UTF-8 JSON, as a ByteArray
        val payload = pole.exportPayload() // the same, as a FlagPayload
        // endregion

        assertEquals(setOf(FlagKey("page-size")), payload.values.keys)
        assertTrue(json.decodeToString().contains("\"page-size\" : 50"))
        assertTrue(exportedDocument().contains("\"checkout.google-pay\" : true"))
    }

    @Test
    fun importingAppliesEveryValueOrNone() {
        val bytes = document("""{"page-size": 50, "checkout.google-pay": true}""")

        // region snippet: exporting-import
        val applied = pole.importJson(bytes)
        val keys = applied.values.keys
        // endregion

        assertEquals(setOf(FlagKey("page-size"), FlagKey("checkout.google-pay")), keys)
        assertEquals(50, pole.flags.pageSize)
    }

    @Test
    fun aRejectedDocumentListsEveryProblem() {
        val bytes = document("""{"page-size": "fifty", "not-a-flag": 1}""")

        // region snippet: exporting-import-rejected
        try {
            pole.importJson(bytes)
        } catch (rejected: FlagImportException.Rejected) {
            for (problem in rejected.problems) {
                Log.w("Flags", "${problem.key}: ${problem.kind}") // UnknownKey, TypeMismatch or UnknownCase
            }
        }
        // endregion

        val logged = ShadowLog.getLogsForTag("Flags").map { it.msg }
        assertEquals(listOf("not-a-flag: UnknownKey", "page-size: TypeMismatch"), logged)
        assertEquals(20, pole.flags.pageSize)
    }

    @Test
    fun importingDoesNotClearWhatTheDocumentLeavesOut() {
        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
        val bytes = document("""{"page-size": 50}""")

        // region snippet: exporting-replace
        pole.removeAllOverrides()
        pole.importJson(bytes)
        // endregion

        assertFalse(pole.flags.checkout.googlePay)
        assertEquals(50, pole.flags.pageSize)

        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)
        pole.importJson(bytes)
        assertTrue(pole.flags.checkout.googlePay)
    }

    @Test
    fun aDecodedPayloadAppliesTheSameWay() {
        val bytes = document("""{"page-size": 50}""")

        // region snippet: exporting-apply-payload
        val payload = FlagPayload.decode(bytes, pole.schema)
        pole.applyPayload(payload)
        // endregion

        assertEquals(50, pole.flags.pageSize)
    }

    @Test
    fun aQrCodeRoundTrips() {
        pole.setOverride(AppFlagsSpec.checkout.googlePay, true)

        // region snippet: exporting-qr
        val code = pole.qrCodeString() // "FFQR1:…"
        // endregion
        val scanned = code
        val receiver = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource()))

        // region snippet: exporting-qr-import
        receiver.importQRCode(scanned)
        // endregion

        assertTrue(code.startsWith("FFQR1:"))
        assertTrue(receiver.flags.checkout.googlePay)
    }

    @Test
    fun aCodeThatWillNotFitSaysWhatIsInIt() {
        pole.setOverride(AppFlagsSpec.payload, Random(seed = 1).nextBytes(3_000))

        // region snippet: exporting-qr-too-large
        try {
            showCode(pole.qrCodeString())
        } catch (tooLarge: FlagQRCodeException.PayloadTooLarge) {
            Log.w("Flags", "${tooLarge.overrideCount} overrides need ${tooLarge.bytes} of ${tooLarge.limit} characters")
        }
        // endregion

        assertTrue(codes.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("Flags").single().msg.startsWith("1 overrides need "))
    }

    @Test
    fun exportSpansTheWholeStack() {
        val remote = RemoteOverrideSource(AppFlagsSpec)
        val stacked = FlagPole(AppFlagsSpec, sources = listOf(SnapshotSource(), remote))

        remote.apply("""{"config": {"pageSize": 30}}""".encodeToByteArray())

        assertEquals(setOf(FlagKey("page-size")), stacked.exportPayload().values.keys)
        assertEquals(URI("https://api.example.com/v3"), stacked.flags.endpoint)
    }
}
