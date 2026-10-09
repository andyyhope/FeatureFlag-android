package com.andyyhope.featureflag.remote

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.FlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import kotlinx.coroutines.flow.Flow

/** What a successful apply did. */
public data class RemoteApplyResult(
    /** Flags this payload set, sorted by key. */
    public val appliedKeys: List<FlagKey>,
    /** Flags with a `remoteKey` the payload did not mention, in schema order. They fall through to the sources below. */
    public val absentKeys: List<FlagKey>,
)

/**
 * Holds the values from the most recent remote payload.
 *
 * It decodes and never fetches: the app gets the bytes however it likes and hands them
 * over. An apply is strict and all-or-nothing, and it replaces everything rather than
 * merging. Reads are lock-free against an immutable map.
 */
public class RemoteOverrideSource(
    public val schema: FlagSchema,
    public val mapper: RemoteOverrideMapper = DotPathMapper,
    override val sourceName: String = "Remote",
) : FlagValueSource {

    /** A source for a container, described with the key encoding its pole uses. */
    public constructor(
        spec: FlagContainerSpec<*>,
        keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
        mapper: RemoteOverrideMapper = DotPathMapper,
        sourceName: String = "Remote",
    ) : this(FlagSchema.of(spec, keyEncoding), mapper, sourceName)

    // Serialises the swaps of values, so a conditional commit is atomic with respect to every other
    // apply and clear. Reads take no lock, and nothing is emitted while it is held.
    private val lock = Any()

    @Volatile
    private var values: Map<FlagKey, FlagValueBox> = emptyMap()

    private val emitter = FlagChangeEmitter()

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = values[key]

    /**
     * Decodes a JSON payload and replaces everything this source holds.
     *
     * @throws RemoteOverrideException.Malformed when the bytes are not JSON.
     * @throws RemoteOverrideException.Rejected with every problem, sorted by key, when any value does not fit.
     */
    public fun apply(json: ByteArray): RemoteApplyResult = apply(RemoteValue.parseJson(json))

    /** Applies an already-decoded payload; see the [ByteArray] overload. */
    public fun apply(payload: RemoteValue): RemoteApplyResult = checkNotNull(apply(payload) { true })

    /**
     * Validates [json] as the public overload does, then stores it only if [commitIf] still holds
     * at the moment of the store. Returns `null`, having stored and announced nothing, when it does not.
     */
    internal fun apply(json: ByteArray, commitIf: () -> Boolean): RemoteApplyResult? =
        apply(RemoteValue.parseJson(json), commitIf)

    private fun apply(payload: RemoteValue, commitIf: () -> Boolean): RemoteApplyResult? {
        val mapping = schema.mapRemote(payload, mapper)
        if (mapping.problems.isNotEmpty()) {
            throw RemoteOverrideException.Rejected(mapping.problems.sortedBy { it.key.rawValue })
        }
        if (!swap(mapping.boxes, commitIf)) return null
        return RemoteApplyResult(
            appliedKeys = mapping.boxes.keys.sortedBy { it.rawValue },
            absentKeys = schema.flags.filter { it.remoteKey != null && it.key !in mapping.boxes }.map { it.key },
        )
    }

    /** Drops every remote value, so flags fall through to the sources beneath. */
    public fun clear() {
        swap(emptyMap()) { true }
    }

    /** Drops every remote value only if [commitIf] holds at that moment. Returns whether it did. */
    internal fun clear(commitIf: () -> Boolean): Boolean = swap(emptyMap(), commitIf)

    private fun swap(replacement: Map<FlagKey, FlagValueBox>, commitIf: () -> Boolean): Boolean {
        val committed = synchronized(lock) {
            commitIf().also { if (it) values = replacement }
        }
        if (committed) emitter.emit(FlagChange.All)
        return committed
    }
}
