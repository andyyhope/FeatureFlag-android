package docs.validatingaconfig

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.audit.FlagMappingAuditException
import com.andyyhope.featureflag.test.assertFlagsFullyMapped
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/ValidatingAConfig.md. */
class ValidatingAConfigTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theAuditReportsBothDirections() {
        val configFile = folder.newFile("draft.json").apply { writeText(draftConfig) }

        // region snippet: validating-audit
        val json = configFile.readBytes()
        val audit = FlagMappingAudit(AppFlagsSpec, json)

        println(audit)
        // endregion

        assertEquals(listOf(FlagKey("page-size")), audit.absent)
        assertEquals(listOf("config.page_size", "meta.version"), audit.unconsumed)
        assertEquals(draftAuditReport(), audit.toString())
    }

    @Test
    fun unconsumedValuesFailOnlyAStrictAudit() {
        val audit = FlagMappingAudit(AppFlagsSpec, stagingConfig.encodeToByteArray())

        // region snippet: validating-complete
        val complete = audit.isComplete // absent and mismatched only
        // endregion

        // region snippet: validating-strict
        val everyValueRead = audit.isComplete(strict = true) // every value must be read
        val everyValueButMeta = audit.isComplete(strict = true, ignoring = listOf("meta")) // except under meta
        // endregion

        assertTrue(complete)
        assertFalse(everyValueRead)
        assertTrue(everyValueButMeta)
    }

    // region snippet: validating-test-helper
    @Test
    fun stagingConfigMapsEveryFlag() {
        val json = checkNotNull(javaClass.getResource("/staging.json")).readBytes()
        assertFlagsFullyMapped(AppFlagsSpec, json, strict = true, ignoring = listOf("meta"))
    }
    // endregion

    @Test
    fun theStagingResourceIsTheConfigTheDocShows() {
        assertEquals(stagingConfig, checkNotNull(javaClass.getResource("/staging.json")).readText().trimEnd())
    }

    @Test
    fun theHelperFailsWithTheWholeReport() {
        val json = draftConfig.encodeToByteArray()

        val error = assertFailsWith<AssertionError> { assertFlagsFullyMapped(AppFlagsSpec, json) }

        assertEquals("\n" + draftAuditReport(), error.message)
    }

    @Test
    fun theAuditWorksWithAnyTestFramework() {
        val json = stagingConfig.encodeToByteArray()

        // region snippet: validating-framework-agnostic
        val audit = FlagMappingAudit(AppFlagsSpec, json)
        assertTrue(audit.isComplete, audit.toString()) // any test framework
        audit.requireComplete(strict = true, ignoring = listOf("meta")) // throws the report
        // endregion

        assertFailsWith<FlagMappingAuditException.Incomplete> { audit.requireComplete(strict = true) }
    }

    @Test
    fun theAuditComparesEveryValueWithItsDefault() {
        val audit = FlagMappingAudit(AppFlagsSpec, stagingConfig.encodeToByteArray())

        // region snippet: validating-defaults
        println(audit.defaultsDescription)
        // endregion

        assertEquals(listOf("checkout-tier", "new-onboarding").map(::FlagKey), audit.changesDefault)
        assertEquals(listOf("markets", "page-size").map(::FlagKey), audit.matchesDefault)
        assertEquals(stagingDefaultsReport(), audit.defaultsDescription)
    }

    @Test
    fun aRecordFlagIsComparedRecordByRecord() {
        val records = FlagMappingAudit(CheckoutFlagsSpec, checkoutConfig.encodeToByteArray()).defaults
            .single { it.key == FlagKey("payment-methods") }
            .records

        assertEquals(listOf("Google Pay", "Bank transfer", "PayPal"), records?.map { it.identifier })
        assertTrue(checkoutDefaultsReport().contains("Google Pay"))
    }
}
