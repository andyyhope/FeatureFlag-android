package com.andyyhope.featureflag.ksp

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS 0.12.1 fixed generated code that broke when the host declared a type named `FeatureFlag`, the
 * module's own name. Here the host's package `shadow` declares everything the generated code names:
 * every library type, some of them used as flag types, every function it calls by a bare name, a flag
 * named `com` (where every fully-qualified library name starts), classifiers nested in the container
 * that the generated `Impl` implements, and flags and record fields named like generated parameters
 * and locals.
 */
class NameShadowingTest {
    private val compiled = compile(HOSTILE, FLAGS, GUARD)

    private fun load(name: String): Class<*> = compiled.classLoader.loadClass("shadow.$name")

    private fun Any.read(owner: String, property: String): Any? =
        load(owner).getMethod("get" + property.replaceFirstChar(Char::uppercaseChar)).invoke(this)

    @Suppress("UNCHECKED_CAST")
    private val spec: FlagContainerSpec<Any>
        get() = load("ShadowFlagsSpec").getField("INSTANCE").get(null) as FlagContainerSpec<Any>

    @Test fun generatedCodeCompilesBesideHostTypesNamedLikeTheLibrarys() {
        assertTrue(compiled.succeeded, compiled.problems)
        // Records are stored as JSON, keys sorted, so this is also what encoding them again writes.
        val records = FlagValueBox.String(
            """[{"boxes":["a"],"codec":"HOST","com":"staging","defaults":{"b":2},"e":true,"it":4,"key":"k",""" +
                """"record":3,"wanted":"yes"}]""",
        )
        val local = SnapshotSource(
            "local",
            mapOf(
                FlagKey("com") to FlagValueBox.Bool(true),
                FlagKey("checkout.apple-pay") to FlagValueBox.Bool(true),
                FlagKey("endpoints") to records,
            ),
        )

        val flags = FlagPole(spec, listOf(local)).flags

        assertEquals(true, flags.read("ShadowFlags", "com"))
        assertEquals("none", flags.read("ShadowFlags", "title"))
        assertEquals("HOST", (flags.read("ShadowFlags", "codecs") as Enum<*>).name)
        assertEquals("HOST", (flags.read("ShadowFlags", "descriptor") as Enum<*>).name)
        val checkout = assertNotNull(flags.read("ShadowFlags", "checkout"))
        assertEquals(true, checkout.read("ShadowCheckoutFlags", "applePay"))
        @Suppress("UNCHECKED_CAST")
        val ref = spec.read("ShadowFlagsRefs", "endpoints") as FlagRef<Any?>
        val endpoints = flags.read("ShadowFlags", "endpoints")
        assertEquals(records, ref.codec.encode(endpoints), "the records read back unchanged")
        val lookup = load("ShadowEndpointFlagRecordKt").methods.single { it.name == "get" }
        val staging = assertNotNull(lookup.invoke(null, endpoints, "staging"))
        assertEquals("yes", staging.read("ShadowEndpoint", "wanted"))
    }

    @Test fun descriptorsAndRecordShapesSurviveTheClash() {
        assertTrue(compiled.succeeded, compiled.problems)
        assertEquals(
            listOf(
                "com", "title", "prefix", "encoding", "lookup", "flagPrefix", "codecs", "ref", "path", "node",
                "descriptor", "groupDescriptor", "endpoints", "checkout",
            ),
            spec.flagDescriptors.map { it.propertyName },
        )
        val codec = load("ShadowEndpointFlagRecord").getField("INSTANCE").get(null) as FlagRecordCodec<*>
        assertEquals(
            listOf("com", "boxes", "defaults", "codec", "record", "key", "it", "e", "wanted"),
            codec.shape.map { it.name },
        )
    }

    @Test fun theHostileDeclarationsReallyAreInScope() {
        // Guards the guard: if these stopped resolving to the host's declarations, the tests above prove nothing.
        assertTrue(compiled.succeeded, compiled.problems)
        val guard = load("GuardKt")
        assertEquals<Class<*>?>(load("FlagRef"), guard.getMethod("hostFlagRef").invoke(null)?.javaClass)
        val emptyList = runCatching { guard.getMethod("hostEmptyList").invoke(null) }.exceptionOrNull()
        assertEquals("host emptyList", emptyList?.cause?.message)
    }

    private companion object {
        /** The host's own declarations, named after everything the generated code names. */
        val HOSTILE = SourceFile.kotlin(
            "Hostile.kt",
            """
            package shadow

            // An enum of flag names called Flag is what iOS's FeatureFlag was.
            enum class Flag { NewOnboarding }
            enum class FlagContainer { HOST }
            enum class FlagGroup { HOST }
            enum class FlagRecord { HOST }
            enum class FlagRecordKey { HOST }
            enum class FlagRecordProperty { HOST }
            enum class FlagCodecs { HOST }
            enum class FlagCodec { HOST }
            enum class FlagKey { HOST }
            enum class FlagKeyPath { HOST }
            enum class FlagValueBox { HOST }
            enum class KeyEncoding { HOST }
            enum class FlagRecords { HOST }
            enum class FlagRecordCodec { HOST }
            enum class FlagRecordField { HOST }
            enum class FlagGroupDescriptor { HOST }
            enum class FlagSchemaNode { HOST }
            enum class FlagLookup { HOST }
            enum class FlagContainerSpec { HOST }
            enum class FlagRef { HOST }
            enum class HostDescriptor { HOST }
            typealias FlagDescriptor = HostDescriptor
            enum class Instant { HOST }
            enum class URI { HOST }
            enum class Exception { HOST }
            enum class IllegalStateException { HOST }
            enum class Keys { HOST }
            enum class Impl { HOST }

            fun <T> com.andyyhope.featureflag.container.FlagLookup.read(
                key: com.andyyhope.featureflag.values.FlagKey,
                codec: com.andyyhope.featureflag.values.FlagCodec<T>,
                default: () -> T,
            ): T = error("host read")
            fun <T> lazy(initializer: () -> T): Lazy<T> = error("host lazy")
            fun <T> listOf(vararg elements: T): List<T> = error("host listOf")
            fun <K, V> buildMap(builderAction: MutableMap<K, V>.() -> Unit): Map<K, V> = error("host buildMap")
            fun <T> emptyList(): List<T> = error("host emptyList")
            fun <K, V> emptyMap(): Map<K, V> = error("host emptyMap")
            fun <T> List<T>.first(): T = error("host first")
            fun <T> List<T>.firstOrNull(predicate: (T) -> Boolean): T? = error("host firstOrNull")
            fun <T, R> List<T>.map(transform: (T) -> R): List<R> = error("host map")
            fun <T, R> T.let(block: (T) -> R): R = error("host let")
            """.trimIndent(),
        )

        val FLAGS = SourceFile.kotlin(
            "Flags.kt",
            """
            package shadow

            import com.andyyhope.featureflag.Flag
            import com.andyyhope.featureflag.FlagContainer
            import com.andyyhope.featureflag.FlagGroup
            import com.andyyhope.featureflag.FlagRecord
            import com.andyyhope.featureflag.FlagRecordKey
            import com.andyyhope.featureflag.records.FlagRecords
            import kotlin.collections.listOf

            @FlagContainer(description = "Flags in a package that declares the library's names")
            interface ShadowFlags {
                // Named like what the generated Impl, which implements this interface, refers to.
                interface FlagRef
                interface FlagLookup
                interface FlagKeyPath
                interface Keys

                @Flag(description = "Named like the package every library name starts with")
                val com: Boolean get() = false

                @Flag(description = "Title")
                val title: String get() = "none"

                @Flag(description = "Named like a generated parameter")
                val prefix: Boolean get() = false

                @Flag(description = "Named like a generated parameter")
                val encoding: Boolean get() = false

                @Flag(description = "Named like a generated parameter")
                val lookup: Boolean get() = false

                @Flag(description = "Named like a generated parameter")
                val flagPrefix: Boolean get() = false

                @Flag(description = "The host's FlagCodecs")
                val codecs: FlagCodecs get() = FlagCodecs.HOST

                @Flag(description = "The host's FlagRef")
                val ref: shadow.FlagRef get() = shadow.FlagRef.HOST

                @Flag(description = "The host's FlagKeyPath")
                val path: shadow.FlagKeyPath get() = shadow.FlagKeyPath.HOST

                @Flag(description = "The host's FlagSchemaNode")
                val node: FlagSchemaNode get() = FlagSchemaNode.HOST

                @Flag(description = "The host's FlagDescriptor, a typealias")
                val descriptor: FlagDescriptor get() = HostDescriptor.HOST

                @Flag(description = "The host's FlagGroupDescriptor")
                val groupDescriptor: FlagGroupDescriptor get() = FlagGroupDescriptor.HOST

                @Flag(description = "Endpoints")
                val endpoints: FlagRecords<ShadowEndpoint>
                    get() = FlagRecords(
                        listOf(
                            ShadowEndpoint(com = "prod", boxes = listOf(), defaults = mapOf(), codec = FlagCodecs.HOST),
                        ),
                    )

                @FlagGroup(description = "Checkout")
                val checkout: ShadowCheckoutFlags
            }

            @FlagContainer
            interface ShadowCheckoutFlags {
                @Flag(description = "Apple Pay")
                val applePay: Boolean get() = false
            }

            @FlagRecord
            data class ShadowEndpoint(
                @FlagRecordKey val com: String,
                val boxes: List<String>,
                val defaults: Map<String, Int>,
                val codec: FlagCodecs,
                val record: Int = 1,
                val key: String = "",
                val it: Int = 0,
                val e: Boolean = false,
                val wanted: String? = null,
            )
            """.trimIndent(),
        )

        /** Hand-written code in the host's package, resolving the same simple names generated code would. */
        val GUARD = SourceFile.kotlin(
            "Guard.kt",
            """
            package shadow

            fun hostFlagRef(): Any = FlagRef.HOST

            fun hostEmptyList(): List<String> = emptyList()
            """.trimIndent(),
        )
    }
}
