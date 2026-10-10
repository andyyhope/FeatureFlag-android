package com.andyyhope.featureflag.audit

import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val REQUIRED_AUDIT_CASES = listOf(
    "complete", "incomplete", "empty-object", "root-array", "nested-empty", "restated", "record-changes", "scalar-changes",
)

/** Every audit report and default diff the iOS generator wrote for `GoldenFlags` reads the same here, byte for byte. */
class AuditGoldenFixtureTest {

    private val directory =
        File(requireNotNull(System.getProperty("featureflag.fixtures")) { "featureflag.fixtures is not set" }, "audit")

    private val cases: List<String>
        get() = directory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".payload.json") }
            .map { it.removeSuffix(".payload.json") }
            .sorted()

    private fun golden(name: String): String = File(directory, name).readText().removeSuffix("\n")

    @Test
    fun theGeneratorWroteEveryCaseThisPortRelies() {
        val missing = REQUIRED_AUDIT_CASES.filterNot { case ->
            case in cases && listOf("txt", "strict.txt", "defaults.txt").all { File(directory, "$case.$it").exists() }
        }
        assertTrue(missing.isEmpty(), "missing audit goldens (payload, .txt, .strict.txt and .defaults.txt): $missing")
    }

    @Test
    fun everyReportMatchesIos() {
        for (case in cases) {
            val audit = FlagMappingAudit(GoldenFlagsSpec, File(directory, "$case.payload.json").readBytes())
            assertEquals(golden("$case.txt"), audit.toString(), case)
            assertEquals(golden("$case.strict.txt"), audit.description(strict = true, ignoring = listOf("meta")), "$case strict")
            assertEquals(golden("$case.defaults.txt"), audit.defaultsDescription, "$case defaults")
        }
    }
}
