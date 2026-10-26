package com.andyyhope.featureflag.ksp

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContainerGenerationTest {
    private val appFlags = compile(
        source(
            "AppFlags.kt",
            """
            enum class Tier { FREE, PRO }

            @FlagContainer(description = "Everything the checkout team can turn on")
            interface AppFlags {
                @Flag(description = "New onboarding", remoteKey = "featureToggles.onboarding.v2")
                val newOnboarding: Boolean get() = false

                @Flag(description = "Maximum items")
                val maxItems: Int get() = 10

                @FlagGroup(description = "Checkout")
                val checkout: CheckoutFlags

                val notAFlag: String get() = "plain"
            }

            @FlagContainer
            interface CheckoutFlags {
                @Flag(description = "Apple Pay")
                val applePay: Boolean get() = false

                @Flag(description = "Tier")
                val tier: Tier get() = Tier.FREE
            }
            """,
        ),
    )

    @Test fun appFlagsSpecMatchesItsSnapshot() = appFlags.assertMatchesSnapshot("AppFlagsSpec.kt")

    @Test fun checkoutFlagsSpecMatchesItsSnapshot() = appFlags.assertMatchesSnapshot("CheckoutFlagsSpec.kt")

    @Test fun aConsumerOnJvmTarget18RunsTheGeneratedCode() {
        // Android modules often still target 1.8 or 11. Generated code must not inline anything from
        // core, whose bytecode is 17, and the default fallback must work from a non-inline lambda.
        val compiled = compile(
            source(
                "Probe.kt",
                """
                import com.andyyhope.featureflag.container.FlagLookup
                import com.andyyhope.featureflag.values.FlagKey
                import com.andyyhope.featureflag.values.FlagValueBox
                import com.andyyhope.featureflag.values.FlagValueType
                import com.andyyhope.featureflag.values.KeyEncoding

                @FlagContainer
                interface ProbeFlags {
                    @Flag(description = "Maximum items")
                    val maxItems: Int get() = 10
                }

                fun probe(): Int = ProbeFlagsSpec.createFlags(
                    object : FlagLookup {
                        override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
                        override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null
                    },
                ).maxItems
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        assertEquals(10, compiled.classLoader.loadClass("app.ProbeKt").getMethod("probe").invoke(null))
    }

    @Test fun generatedCodeSurvivesShadowedNames() {
        // A host app is likely to declare its own Flag, FlagRef or Keys. Generated code imports every
        // name it uses explicitly, and explicit imports beat declarations in the same package.
        val compiled = compile(
            SourceFile.kotlin(
                "ShadowedFlags.kt",
                """
                package app

                import com.andyyhope.featureflag.FlagContainer
                import com.andyyhope.featureflag.FlagGroup

                annotation class Flag(val description: String)
                class FlagRef
                class FlagKey
                class FlagKeyPath
                class FlagCodecs
                class FlagLookup
                class FlagDescriptor
                class FlagGroupDescriptor
                class FlagSchemaNode
                class FlagContainerSpec
                class KeyEncoding
                class Keys
                class Impl
                fun read(): Nothing = error("shadowed")

                @FlagContainer
                interface ShadowedFlags {
                    @com.andyyhope.featureflag.Flag(description = "Title")
                    val title: String get() = "none"

                    @FlagGroup(description = "Checkout")
                    val checkout: ShadowedCheckoutFlags
                }

                @FlagContainer
                interface ShadowedCheckoutFlags {
                    @com.andyyhope.featureflag.Flag(description = "Apple Pay")
                    val applePay: Boolean get() = false
                }
                """.trimIndent(),
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
    }

    @Test fun anInternalContainerGetsInternalDeclarations() {
        val compiled = compile(
            source(
                "InternalFlags.kt",
                """
                @FlagContainer
                internal interface InternalFlags {
                    @Flag(description = "x")
                    val x: Boolean get() = true
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        val text = compiled.generated.getValue("InternalFlagsSpec.kt")
        assertTrue("internal open class InternalFlagsRefs(" in text, text)
        assertTrue("internal object InternalFlagsSpec :" in text, text)
    }

    @Test fun aNestedContainerIsNamedAfterItsOuterDeclaration() {
        val compiled = compile(
            source(
                "Outer.kt",
                """
                interface Outer {
                    @FlagContainer
                    interface Inner {
                        @Flag(description = "x")
                        val x: Boolean get() = true
                    }
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        assertEquals(setOf("Outer_InnerSpec.kt"), compiled.generated.keys)
    }

    @Test fun aHandWrittenRecordCodecIsFoundByName() {
        val compiled = compile(
            source(
                "Legacy.kt",
                """
                import com.andyyhope.featureflag.records.FlagRecordCodec
                import com.andyyhope.featureflag.records.FlagRecordField
                import com.andyyhope.featureflag.values.FlagCodecs
                import com.andyyhope.featureflag.values.FlagValueBox

                data class Legacy(val name: String)

                object LegacyFlagRecord : FlagRecordCodec<Legacy> {
                    override val shape: List<FlagRecordField> = listOf(FlagRecordField.of("name", FlagCodecs.string))
                    override val keyField: String? = null
                    override fun boxes(record: Legacy): Map<String, FlagValueBox> =
                        mapOf("name" to FlagCodecs.string.encode(record.name))
                    override fun fromBoxes(boxes: Map<String, FlagValueBox>): Legacy? =
                        boxes["name"]?.let(FlagCodecs.string::decode)?.let(::Legacy)
                }

                @FlagContainer
                interface LegacyFlags {
                    @Flag(description = "Legacy records")
                    val legacy: FlagRecords<Legacy> get() = FlagRecords()
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        assertTrue("FlagCodecs.records(LegacyFlagRecord)" in compiled.generated.getValue("LegacyFlagsSpec.kt"))
    }

    @Test fun aGroupFromAnotherModuleIsFoundByName() {
        val library = compile(
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
        assertTrue(library.succeeded, library.problems)
        val app = compile(
            source(
                "AppFlags.kt",
                """
                @FlagContainer
                interface AppFlags {
                    @FlagGroup(description = "Shared")
                    val group: SharedFlags
                }
                """,
            ),
            classpath = listOf(library.outputDirectory),
        )
        assertTrue(app.succeeded, app.problems)
        assertEquals(setOf("AppFlagsSpec.kt"), app.generated.keys)
        val spec = app.classLoader.loadClass("app.AppFlagsSpec").getField("INSTANCE").get(null)
        val defaults = spec.javaClass.getMethod("getFlagDefaults").invoke(spec)
        val group = defaults.javaClass.getMethod("getGroup").invoke(defaults)
        assertEquals(true, group.javaClass.getMethod("getShared").invoke(group))
    }

    @Test fun flagsTypedThroughGenericTypealiasesCompileAndKeepTheirDefaults() {
        val compiled = compile(
            source(
                "AliasedFlags.kt",
                """
                typealias Named<T> = List<T>
                typealias Table<T> = Map<String, List<T>>
                typealias Rows<R> = FlagRecords<R>

                @FlagRecord
                data class Row(@FlagRecordKey val name: String, val weight: Int = 1)

                @FlagContainer
                interface AliasedFlags {
                    @Flag(description = "Names")
                    val named: Named<String> get() = listOf("a", "b")

                    @Flag(description = "Table")
                    val table: Table<Int> get() = mapOf("x" to listOf(1, 2))

                    @Flag(description = "Rows")
                    val rows: Rows<Row> get() = FlagRecords()
                }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.problems)
        val spec = compiled.classLoader.loadClass("app.AliasedFlagsSpec").getField("INSTANCE").get(null)
        val defaults = spec.javaClass.getMethod("getFlagDefaults").invoke(spec)
        fun default(name: String) = defaults.javaClass.getMethod("get" + name.replaceFirstChar(Char::uppercase)).invoke(defaults)
        assertEquals(listOf("a", "b"), default("named"))
        assertEquals(mapOf("x" to listOf(1, 2)), default("table"))
        assertTrue((default("rows") as List<*>).isEmpty())
    }
}
