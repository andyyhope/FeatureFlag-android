package com.andyyhope.featureflag.ksp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ports Swift's FlagRecordMacroTests diagnostics; the Kotlin-only cases follow. Body line 1 is file line 10. */
class RecordDiagnosticTest {
    /** The reported errors; nothing may be generated for [withheld], the faulty declarations. */
    private fun errors(body: String, vararg withheld: String = arrayOf("Endpoint")): List<Reported> {
        val compiled = compile(source("Endpoint.kt", body))
        compiled.assertNothingGeneratedFor(*withheld)
        return compiled.errors
    }

    private fun at(line: Int, message: String) = Reported("Endpoint.kt", line, message)

    @Test fun anInterfaceIsRejected() = assertEquals(
        listOf(at(11, "'@FlagRecord' can only be applied to a class with a primary constructor")),
        errors(
            """
            @FlagRecord
            interface Endpoint
            """,
        ),
    )

    @Test fun aClassWithoutAPrimaryConstructorIsRejected() = assertEquals(
        listOf(at(11, "'@FlagRecord' can only be applied to a class with a primary constructor")),
        errors(
            """
            @FlagRecord
            class Endpoint {
                constructor(name: String)
            }
            """,
        ),
    )

    @Test fun aPrivateRecordIsRejected() = assertEquals(
        listOf(at(11, "'@FlagRecord' class 'Endpoint' cannot be private: the generated 'EndpointFlagRecord' has to construct it")),
        errors(
            """
            @FlagRecord
            private data class Endpoint(val name: String)
            """,
        ),
    )

    @Test fun aRecordWithNoFieldsIsDiagnosed() = assertEquals(
        listOf(
            at(
                11,
                "'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor " +
                    "nothing to show",
            ),
        ),
        errors(
            """
            @FlagRecord
            class Endpoint()
            """,
        ),
    )

    @Test fun aParameterThatIsNotAPropertyIsDiagnosed() = assertEquals(
        listOf(
            at(
                11,
                "'name' is a constructor parameter but not a property, so a record could not be written back out. " +
                    "Declare it 'val name'",
            ),
        ),
        errors(
            """
            @FlagRecord
            class Endpoint(name: String) {
                val upper: String = name.uppercase()
            }
            """,
        ),
    )

    @Test fun twoFieldsMarkedAsTheKeyIsDiagnosed() = assertEquals(
        listOf(
            at(
                11,
                "a record has one key at most, and name, region are all marked '@FlagRecordKey' — the key is the " +
                    "single field that tells one record from another",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                @FlagRecordKey val name: String,
                @FlagRecordKey val region: String,
            )
            """,
        ),
    )

    @Test fun anOptionalKeyIsDiagnosed() = assertEquals(
        listOf(
            at(
                12,
                "a record's key cannot be optional: it is the field that tells one record from another, so every " +
                    "record has to carry it",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                @FlagRecordKey val name: String?,
                val url: String,
            )
            """,
        ),
    )

    @Test fun anUnsupportedFieldTypeIsNamed() = assertEquals(
        listOf(
            at(
                12,
                "record field 'tags' has type 'Set<String>', which a record field cannot hold. Use Boolean, Int, " +
                    "Long, Double, Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of " +
                    "those, or FlagRecords of a '@FlagRecord' class; a nullable type makes the field optional",
            ),
            at(
                13,
                "record field 'maybe' has type 'List<String?>', which a record field cannot hold. Use Boolean, Int, " +
                    "Long, Double, Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of " +
                    "those, or FlagRecords of a '@FlagRecord' class; a nullable type makes the field optional",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                val tags: Set<String>,
                val maybe: List<String?>,
            )
            """,
        ),
    )

    @Test fun aRecordFieldHoldingARecordIsToldToUseFlagRecords() = assertEquals(
        listOf(
            at(
                15,
                "a record is stored as a list — declare the field as 'FlagRecords<Target>' rather than 'Target' or " +
                    "'List<Target>'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Target(val host: String)

            @FlagRecord
            data class Endpoint(
                val target: Target,
            )
            """,
        ),
    )

    @Test fun anEmptyDecodedNameIsDiagnosed() = assertEquals(
        listOf(
            at(
                12,
                "'@FlagRecordProperty' on 'name' needs a non-empty 'key': it is the name the field is read from in " +
                    "a payload",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(
                @FlagRecordProperty(key = "") val name: String,
            )
            """,
        ),
    )

    @Test fun aRecordFromAModuleWithoutTheProcessorIsExplained() {
        val library = compileWithoutProcessor(source("Remote.kt", "@FlagRecord\ndata class Remote(val name: String)"))
        val compiled = compile(
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
            classpath = listOf(library),
        )
        compiled.assertNothingGeneratedFor("AppFlags")
        assertEquals(
            listOf(
                Reported(
                    "AppFlags.kt",
                    13,
                    "'RemoteFlagRecord' does not exist: apply the featureflag-ksp processor to the module that " +
                        "declares 'Remote'",
                ),
            ),
            compiled.errors,
        )
    }

    @Test fun aRecordWhoseNestedRecordIsInvalidIsWithheldWithoutItsOwnError() = assertEquals(
        listOf(at(11, "'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor nothing to show")),
        errors(
            """
            @FlagRecord
            class Empty()

            @FlagRecord
            data class Endpoint(
                val empties: FlagRecords<Empty>,
            )
            """,
            "Empty", "Endpoint",
        ),
    )
}
