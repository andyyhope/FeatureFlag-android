package com.andyyhope.featureflag.pole

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.container.read
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.flattened
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Reads feature flags, resolving each one through an ordered stack of sources, as in
 * `FlagPole(AppFlagsSpec, sources = listOf(local, remote)).flags.checkout.googlePay`, and
 * watches one with `pole.flow(AppFlagsSpec.checkout.googlePay)`.
 *
 * The order of [sources] is the precedence. Anything no source supplies falls back to
 * the flag's compiled default. Reads are lock-free and safe from any thread, and no flow
 * here hops to the main thread.
 *
 * @param keyEncoding how property-name paths become storage keys. Changing it renames
 *   every key, so choose it once.
 * @param applicationName what a companion app calls this app. It goes into [schema]; null
 *   leaves the name out, as Swift does for an app without a display name.
 */
public class FlagPole<F : Any>(
    public val spec: FlagContainerSpec<F>,
    sources: List<FlagValueSource>,
    public val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    public val applicationName: String? = null,
) {

    /** The source stack, highest priority first. */
    public val sources: List<FlagValueSource> = sources.toList()

    private val resolver = FlagResolver(this.sources, keyEncoding)

    /** The flag tree, read through the source stack. */
    public val flags: F = spec.createFlags(resolver)

    /** This pole's flags, described so a companion app can render them without the host's types. Built once. */
    public val schema: FlagSchema = FlagSchema.of(spec, keyEncoding = keyEncoding, applicationName = applicationName)

    /**
     * Every change any source reports: cold, delivered in the collector's context, with no
     * hop to the main thread. Nothing is lost: a collector that falls behind receives the
     * changes it missed combined into one. Watch one flag with [flow] to get its values.
     */
    public val changes: Flow<FlagChange>
        get() = resolver.changes

    /** Every flag this pole knows about, flattened out of the container tree, depth first. */
    public val descriptors: List<FlagDescriptor>
        get() = spec.flagDescriptors.flattened()

    /** The key each flag resolves against, in declaration order. */
    public val keys: List<FlagKey>
        get() = schema.flags.map { it.key }

    /**
     * Every declared flag some source supplies a value for.
     *
     * This spans the whole stack, not just local overrides: a value a remote payload
     * supplied is included, and exporting therefore carries it too.
     */
    public val overrides: Map<FlagKey, FlagValueBox>
        get() = buildMap {
            for (entry in schema.flags) {
                val box = resolver.box(entry.key, entry.valueType)
                if (box != null) put(entry.key, box)
            }
        }

    /** A flag's current value, read without going through [flags]. */
    public operator fun <T> get(ref: FlagRef<T>): T = resolver.read(key(ref), ref.codec) { ref.defaultValue }

    /**
     * A flag's value now, then each time it changes to something different.
     *
     * Every source is subscribed before the first value is read, so a write made while
     * collection starts is never lost. Values arrive on the collector's context, and the
     * flow never completes.
     */
    public fun <T> flow(ref: FlagRef<T>): Flow<T> {
        val flagKey = key(ref)
        val type = ref.codec.type
        return resolver.ticks(flagKey)
            .map { resolver.box(flagKey, type) }
            .distinctUntilChanged()
            .map { box -> box?.let { ref.codec.decode(it) } ?: ref.defaultValue }
            .distinctUntilChanged()
    }

    /** The storage key [ref] resolves against under this pole's [keyEncoding]. */
    public fun key(ref: FlagRef<*>): FlagKey = ref.key(keyEncoding)

    /**
     * Which source supplied a flag's current value, and what it was: the answer to "why
     * is this flag false?". When nothing did, `box` is the compiled default.
     */
    public fun resolution(ref: FlagRef<*>): FlagResolution {
        val flagKey = key(ref)
        val resolution = resolver.resolution(flagKey, ref.codec.type)
        return if (resolution.isDefault) FlagResolution(flagKey, sourceName = null, box = ref.defaultBox()) else resolution
    }

    /**
     * Which source supplied a value, addressed by what a schema entry carries, so a
     * diagnostics screen can explain every flag in a loop.
     */
    public fun resolution(key: FlagKey, type: FlagValueType): FlagResolution {
        val resolution = resolver.resolution(key, type)
        if (!resolution.isDefault) return resolution
        val fallback = schema.flags.firstOrNull { it.key == key }?.defaultValue
        return FlagResolution(key, sourceName = null, box = fallback)
    }

    /** Writes an override to the highest-priority mutable source. */
    public fun <T> setOverride(ref: FlagRef<T>, value: T) {
        resolver.setOverride(key(ref), ref.codec.encode(value))
    }

    /** Writes an already-boxed override, or removes it when [box] is null. */
    public fun setOverride(key: FlagKey, box: FlagValueBox?) {
        resolver.setOverride(key, box)
    }

    /** Clears a flag's override, restoring whatever the sources beneath say. */
    public fun removeOverride(ref: FlagRef<*>) {
        resolver.setOverride(key(ref), null)
    }

    /**
     * Clears the override of every declared flag, in one batch.
     *
     * Only declared flags are touched, so unrelated values sharing the store are left
     * alone. Flags with no override are skipped rather than written blindly, so a pole
     * with no mutable source has nothing to clear and does not throw.
     */
    public fun removeAllOverrides() {
        val cleared = schema.flags
            .filter { resolver.currentOverride(it.key, it.valueType) != null }
            .map { it.key }
            .associateWith<FlagKey, FlagValueBox?> { null }
        if (cleared.isNotEmpty()) resolver.setOverrides(cleared)
    }

    /** What the mutable source holds for [key], ignoring the sources beneath it. Used to undo an import. */
    public fun currentOverride(key: FlagKey, type: FlagValueType): FlagValueBox? = resolver.currentOverride(key, type)
}

private fun <T> FlagRef<T>.defaultBox(): FlagValueBox = codec.encode(defaultValue)
