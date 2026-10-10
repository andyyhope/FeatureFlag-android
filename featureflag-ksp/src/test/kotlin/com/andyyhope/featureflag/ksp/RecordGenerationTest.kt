package com.andyyhope.featureflag.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordGenerationTest {
    @Test fun endpointCodecMatchesItsSnapshot() = compile(
        source(
            "Endpoint.kt",
            """
            enum class Tier { FREE, PRO }

            @FlagRecord
            data class Endpoint(
                @FlagRecordKey val name: String,
                val weight: Int = 1,
                @FlagRecordProperty(key = "memo") val note: String? = null,
                val tier: Tier = Tier.FREE,
            )
            """,
        ),
    ).assertMatchesSnapshot("EndpointFlagRecord.kt")

    @Test fun aRecordWithoutDefaultsBuildsNoPrototype() {
        val compiled = compile(source("Target.kt", "@FlagRecord\ndata class Target(val host: String, val port: Int)"))
        assertTrue(compiled.succeeded, compiled.problems)
        val text = compiled.generated.getValue("TargetFlagRecord.kt")
        assertFalse("prototype" in text, text)
        assertFalse("operator fun" in text, text)
    }

    @Test fun anIntKeyGetsNoLookupOperatorBecauseListGetWouldWin() {
        val compiled = compile(source("Numbered.kt", "@FlagRecord\ndata class Numbered(@FlagRecordKey val id: Int)"))
        assertTrue(compiled.succeeded, compiled.problems)
        assertFalse("operator fun" in compiled.generated.getValue("NumberedFlagRecord.kt"))
    }

    @Test fun aRecordFromAnotherModuleIsFoundByName() {
        val library = compile(source("Remote.kt", "@FlagRecord\ndata class Remote(@FlagRecordKey val name: String)"))
        assertTrue(library.succeeded, library.problems)
        val app = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    @Flag(description = "Remotes")
                    val remotes: FlagRecords<Remote> get() = FlagRecords()
                }
                """,
            ),
            classpath = listOf(library.outputDirectory),
        )
        assertTrue(app.succeeded, app.problems)
        assertEquals(setOf("AppFlagsSpec.kt"), app.generated.keys)
    }
}
