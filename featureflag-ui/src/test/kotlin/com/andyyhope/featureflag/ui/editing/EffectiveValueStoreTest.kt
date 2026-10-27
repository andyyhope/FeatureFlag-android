package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

// What a flag shows when it has no override: the effective value the host published (a bundled or remote
// config), not the compiled default, which is what the companion showed before the host published one.
class EffectiveValueStoreTest {

    private val source = SnapshotSource("Companion")

    /** CompanionSchemas.companion as a host with a staging config publishes it. */
    private val published = CompanionSchemas.companion.withEffective(
        "new-onboarding" to FlagValueBox.Bool(true),
        "checkout.tier" to FlagValueBox.String("pro"),
    )
    private val store = FlagEditingStore(published, source, Dispatchers.Unconfined)

    private fun entry(key: String) = checkNotNull(store.entry(FlagKey(key)))

    @Test
    fun aFlagWithNoOverrideShowsTheEffectiveValueAndItsSource() {
        assertEquals(FlagValueBox.Bool(true), store.value(entry("new-onboarding")))
        assertEquals("Environment (staging)", store.sourceName(entry("new-onboarding")))
        assertFalse(store.isOverridden(entry("new-onboarding")))
    }

    @Test
    fun aFlagNoConfigTouchesShowsItsDefault() {
        assertEquals(FlagValueBox.Int(10), store.value(entry("max-items")))
        assertNull(store.sourceName(entry("max-items")))
    }

    @Test
    fun anOverrideWinsAndNamesTheCompanionsSource() = runBlocking {
        store.setValue(entry("new-onboarding"), FlagValueBox.Bool(false))

        assertEquals(FlagValueBox.Bool(false), store.value(entry("new-onboarding")))
        assertEquals("Companion", store.sourceName(entry("new-onboarding")))
    }

    @Test
    fun resettingFallsBackToTheEffectiveValueNotTheDefault() = runBlocking {
        store.setValue(entry("new-onboarding"), FlagValueBox.Bool(false))

        store.reset(entry("new-onboarding"))

        assertEquals(FlagValueBox.Bool(true), store.value(entry("new-onboarding")))
    }

    @Test
    fun anEffectiveValueIsNotAnOverride() {
        // Exports and the Overrides tab carry only what the companion set.
        assertEquals(emptyList(), store.overriddenKeys)
        assertEquals(emptyMap(), FlagPayload.decode(store.exportJson().encodeToByteArray(), published).values)
    }

    @Test
    fun anEffectiveValueTheHostWouldNotReadIsIgnored() {
        // A schema from another app: an entry this build lacks, or the wrong type, falls back to the default.
        val odd = FlagEditingStore(
            CompanionSchemas.companion.withEffective(
                "checkout.tier" to FlagValueBox.String("platinum"),
                "max-items" to FlagValueBox.String("lots"),
            ),
            source,
            Dispatchers.Unconfined,
        )

        assertEquals(FlagValueBox.String("free"), odd.value(checkNotNull(odd.entry(FlagKey("checkout.tier")))))
        assertNull(odd.sourceName(checkNotNull(odd.entry(FlagKey("checkout.tier")))))
        assertEquals(FlagValueBox.Int(10), odd.value(checkNotNull(odd.entry(FlagKey("max-items")))))
    }

    @Test
    fun aSchemaFromAnOlderHostShowsDefaultsAsBefore() {
        val older = FlagEditingStore(CompanionSchemas.companion, source, Dispatchers.Unconfined)

        assertEquals(FlagValueBox.Bool(false), older.value(checkNotNull(older.entry(FlagKey("new-onboarding")))))
        assertNull(older.sourceName(checkNotNull(older.entry(FlagKey("new-onboarding")))))
    }
}

/** This schema as a host publishes it when a source called "Environment (staging)" supplies [values]. */
internal fun FlagSchema.withEffective(vararg values: Pair<String, FlagValueBox>): FlagSchema {
    val byKey = values.toMap()
    return copy(
        flags = flags.map { entry ->
            byKey[entry.key.rawValue]?.let { entry.copy(effectiveValue = it, effectiveSource = "Environment (staging)") } ?: entry
        },
    )
}
