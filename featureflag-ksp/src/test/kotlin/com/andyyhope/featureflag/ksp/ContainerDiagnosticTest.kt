package com.andyyhope.featureflag.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Misuse is explained where it is written, never as an error inside generated code. Ports
 * Swift's FlagContainerDiagnosticTests; the Kotlin-only cases follow. Body line 1 is file line 10.
 */
class ContainerDiagnosticTest {
    /** The reported errors; nothing may be generated for [withheld], the faulty declarations. */
    private fun errors(body: String, vararg withheld: String = arrayOf("AppFlags")): List<Reported> {
        val compiled = compile(source("AppFlags.kt", body))
        compiled.assertNothingGeneratedFor(*withheld)
        return compiled.errors
    }

    private fun at(line: Int, message: String) = Reported("AppFlags.kt", line, message)

    @Test fun aClassIsRejected() = assertEquals(
        listOf(at(11, "'@FlagContainer' can only be applied to an interface")),
        errors(
            """
            @FlagContainer
            class AppFlags
            """,
        ),
    )

    @Test fun aContainerPropertyWithoutFlagGroupIsDiagnosedWhereItIsWritten() = assertEquals(
        listOf(
            at(
                14,
                "'checkout' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. " +
                    "Add '@FlagGroup(description = …)' if it is a nested container, '@Flag(description = …)' and a " +
                    "default getter if it is a value, or give it a getter if it is neither",
            ),
        ),
        errors(
            """
            interface CheckoutFlags

            @FlagContainer
            interface AppFlags {
                val checkout: CheckoutFlags
            }
            """,
        ),
    )

    @Test fun aRecordListWithoutAnAnnotationIsToldToUseFlag() = assertEquals(
        listOf(
            at(
                15,
                "'endpoints' holds a list of records, which is a flag's value rather than a nested container, so it " +
                    "needs '@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(val name: String)

            @FlagContainer
            interface AppFlags {
                val endpoints: FlagRecords<Endpoint>
            }
            """,
        ),
    )

    @Test fun aRecordListMarkedAsAGroupIsToldTheSameThing() = assertEquals(
        listOf(
            at(
                16,
                "'endpoints' holds a list of records, which is a flag's value rather than a nested container, so it " +
                    "needs '@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(val name: String)

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Endpoints")
                val endpoints: FlagRecords<Endpoint>
            }
            """,
        ),
    )

    @Test fun propertiesWithGettersAndFunctionsWithBodiesAreLeftAlone() {
        val compiled = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    val notes: String get() = "scratch"
                    fun summary(): String = "none"
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
    }

    @Test fun aFlagWithoutADefaultGetterIsDiagnosed() = assertEquals(
        listOf(
            at(
                13,
                "'@Flag' needs a 'default' value to fall back to: give 'newOnboarding' a getter, as in " +
                    "'val newOnboarding: Boolean get() = …'",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "New onboarding")
                val newOnboarding: Boolean
            }
            """,
        ),
    )

    @Test fun anOptionalFlagIsRejectedWithAnExplanation() = assertEquals(
        listOf(
            at(
                13,
                "flags cannot be optional: a flag always has a value, because 'default' is what it falls back to. " +
                    "Use a sentinel the type already has, or an enum with a case for 'unset'",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "Endpoint")
                val endpoint: String? get() = null
            }
            """,
        ),
    )

    @Test fun aVarFlagIsRejected() = assertEquals(
        listOf(
            at(
                13,
                "'newOnboarding' must be a 'val': flags are read through a pole and changed with an override, " +
                    "never by assignment",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "New onboarding")
                var newOnboarding: Boolean
            }
            """,
        ),
    )

    @Test fun anExtensionFlagIsRejected() = assertEquals(
        listOf(at(13, "'shouted' must be a plain property the generated code can override: not private, and without a receiver")),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "Shouted")
                val String.shouted: Boolean get() = false
            }
            """,
        ),
    )

    @Test fun aGroupThatIsNotAContainerIsRejected() = assertEquals(
        listOf(at(15, "'checkout' is a '@FlagGroup', but 'CheckoutFlags' is not a '@FlagContainer' interface")),
        errors(
            """
            interface CheckoutFlags

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Checkout")
                val checkout: CheckoutFlags
            }
            """,
        ),
    )

    @Test fun aGroupWithAGetterIsRejected() = assertEquals(
        listOf(at(16, "'checkout' is a '@FlagGroup', so the generated code provides it: remove its getter")),
        errors(
            """
            @FlagContainer
            interface CheckoutFlags

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Checkout")
                val checkout: CheckoutFlags get() = error("unused")
            }
            """,
        ),
    )

    @Test fun anUnsupportedTypeIsNamed() = assertEquals(
        listOf(
            at(
                13,
                "'tags' has type 'Set<String>', which a flag cannot hold. Use Boolean, Int, Long, Double, Float, " +
                    "String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or FlagRecords of " +
                    "a '@FlagRecord' class",
            ),
            at(
                15,
                "'limits' has type 'Map<Int, String>', which a flag cannot hold. Use Boolean, Int, Long, Double, " +
                    "Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or " +
                    "FlagRecords of a '@FlagRecord' class",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "Tags")
                val tags: Set<String> get() = emptySet()
                @Flag(description = "Limits")
                val limits: Map<Int, String> get() = emptyMap()
            }
            """,
        ),
    )

    @Test fun aRecordDeclaredAsAFlagIsToldToUseFlagRecords() = assertEquals(
        listOf(
            at(
                16,
                "a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' " +
                    "or 'List<Endpoint>'",
            ),
            at(
                18,
                "a record is stored as a list — declare the flag as 'FlagRecords<Endpoint>' rather than 'Endpoint' " +
                    "or 'List<Endpoint>'",
            ),
        ),
        errors(
            """
            @FlagRecord
            data class Endpoint(val name: String)

            @FlagContainer
            interface AppFlags {
                @Flag(description = "One")
                val endpoint: Endpoint get() = Endpoint("prod")
                @Flag(description = "Many")
                val endpoints: List<Endpoint> get() = emptyList()
            }
            """,
        ),
    )

    @Test fun anAbstractFunctionIsRejected() = assertEquals(
        listOf(
            at(
                12,
                "'refresh' is an abstract function, which the generated implementation cannot provide: " +
                    "'@FlagContainer' interfaces declare flags and groups as properties. Give it a body, or move it " +
                    "elsewhere",
            ),
        ),
        errors(
            """
            @FlagContainer
            interface AppFlags {
                fun refresh()
            }
            """,
        ),
    )

    @Test fun reservedNamesAreRejected() = assertEquals(
        listOf("flagDefaults", "flagDescriptors", "flagContainerDescription", "createFlags", "flagLookup", "flagKeys")
            .mapIndexed { index, name ->
                at(13 + 2 * index, "'$name' is reserved for the code generated for 'AppFlags'. Rename the property")
            },
        errors(
            """
            @FlagContainer
            interface AppFlags {
                @Flag(description = "x")
                val flagDefaults: Boolean get() = false
                @Flag(description = "x")
                val flagDescriptors: Boolean get() = false
                @Flag(description = "x")
                val flagContainerDescription: Boolean get() = false
                @Flag(description = "x")
                val createFlags: Boolean get() = false
                @Flag(description = "x")
                val flagLookup: Boolean get() = false
                @Flag(description = "x")
                val flagKeys: Boolean get() = false
            }
            """,
        ),
    )

    @Test fun theContainerShapeIsChecked() = assertEquals(
        listOf(
            at(11, "'@FlagContainer' interface 'Hidden' cannot be private: the generated 'HiddenSpec' has to implement it"),
            at(
                14,
                "'@FlagContainer' interface 'Generic' cannot have type parameters: every flag's type has to be known " +
                    "when the schema is published",
            ),
            at(
                19,
                "'@FlagContainer' interface 'Extends' cannot extend other interfaces: declare its flags and groups " +
                    "directly, and nest shared ones with '@FlagGroup'",
            ),
        ),
        errors(
            """
            @FlagContainer
            private interface Hidden

            @FlagContainer
            interface Generic<T>

            interface Base

            @FlagContainer
            interface Extends : Base
            """,
            "Hidden", "Generic", "Extends",
        ),
    )

    @Test fun aGroupCycleIsReportedOnEveryGroupInIt() = assertEquals(
        listOf(
            at(13, "'@FlagGroup' 'b' makes 'A' contain itself (A → B → A), so its flags could never finish building"),
            at(19, "'@FlagGroup' 'a' makes 'B' contain itself (B → A → B), so its flags could never finish building"),
            at(25, "'@FlagGroup' 'c' makes 'C' contain itself (C → C), so its flags could never finish building"),
        ),
        errors(
            """
            @FlagContainer
            interface A {
                @FlagGroup(description = "B")
                val b: B
            }

            @FlagContainer
            interface B {
                @FlagGroup(description = "A")
                val a: A
            }

            @FlagContainer
            interface C {
                @FlagGroup(description = "C")
                val c: C
            }
            """,
            "A", "B", "C",
        ),
    )

    @Test fun aGroupFromAModuleWithoutTheProcessorIsExplained() {
        val library = compileWithoutProcessor(
            source(
                "SharedFlags.kt",
                """
                @FlagContainer
                interface SharedFlags {
                    @Flag(description = "Shared")
                    val shared: Boolean get() = true
                }
                """,
            ),
        )
        val compiled = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    @FlagGroup(description = "Shared")
                    val shared: SharedFlags
                }
                """,
            ),
            classpath = listOf(library),
        )
        compiled.assertNothingGeneratedFor("AppFlags")
        assertEquals(
            listOf(
                at(
                    13,
                    "'shared' is a '@FlagGroup' of 'SharedFlags', but 'SharedFlagsSpec' does not exist: apply the " +
                        "featureflag-ksp processor to the module that declares 'SharedFlags'",
                ),
            ),
            compiled.errors,
        )
    }

    @Test fun aContainerWhoseGroupIsInvalidIsWithheldWithoutItsOwnError() = assertEquals(
        listOf(at(11, "'@FlagContainer' interface 'Hidden' cannot be private: the generated 'HiddenSpec' has to implement it")),
        errors(
            """
            @FlagContainer
            private interface Hidden

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "Hidden")
                val hidden: Hidden
            }
            """,
            "Hidden", "AppFlags",
        ),
    )

    @Test fun aContainerWhoseGroupIsInACycleIsWithheldWithoutItsOwnError() = assertEquals(
        listOf(
            at(13, "'@FlagGroup' 'b' makes 'A' contain itself (A → B → A), so its flags could never finish building"),
            at(19, "'@FlagGroup' 'a' makes 'B' contain itself (B → A → B), so its flags could never finish building"),
        ),
        errors(
            """
            @FlagContainer
            interface A {
                @FlagGroup(description = "B")
                val b: B
            }

            @FlagContainer
            interface B {
                @FlagGroup(description = "A")
                val a: A
            }

            @FlagContainer
            interface AppFlags {
                @FlagGroup(description = "A")
                val a: A
            }
            """,
            "A", "B", "AppFlags",
        ),
    )

    @Test fun aContainerWhoseRecordIsInvalidIsWithheldWithoutItsOwnError() = assertEquals(
        listOf(at(11, "'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor nothing to show")),
        errors(
            """
            @FlagRecord
            class Empty()

            @FlagContainer
            interface AppFlags {
                @Flag(description = "Empties")
                val empties: FlagRecords<Empty> get() = FlagRecords()
            }
            """,
            "Empty", "AppFlags",
        ),
    )
}
