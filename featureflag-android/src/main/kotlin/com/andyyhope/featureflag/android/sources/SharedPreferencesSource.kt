package com.andyyhope.featureflag.android.sources

import android.content.Context
import android.content.SharedPreferences
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.FlagChangeEmitter
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueJson
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.flow.Flow

/**
 * Flag values in a [SharedPreferences] file, keyed by [FlagKey.rawValue] with no prefix.
 *
 * Booleans, ints (as `Long`), floats and strings are stored as themselves, so the host's own
 * code and tools that open the file read plain values. Every other type is stored as its
 * JSON value text, written by [FlagValueJson].
 *
 * Reads are type-guided: a value stored with another class, or JSON text of another type,
 * reads as nothing, so resolution falls through to the next source. There is no numeric
 * conversion. A write that changes nothing is skipped. Every change, including one made by
 * other code or by another source over the same file, is announced through one listener,
 * which the framework calls on the main thread.
 */
public class SharedPreferencesSource(
    private val preferences: SharedPreferences,
    name: String = "SharedPreferences",
) : MutableFlagValueSource, AutoCloseable {

    override val sourceName: String = name

    private val emitter = FlagChangeEmitter()

    private val lock = Any()

    // Held strongly here: SharedPreferences keeps its listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A null key is clear() on API 30 and later: anything may have changed.
        emitter.emit(if (key == null) FlagChange.All else FlagChange.Keys(setOf(FlagKey(key))))
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val changes: Flow<FlagChange> = emitter.changes

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? {
        val name = key.rawValue
        // One key through its typed getter, never a copy of the whole file. A getter throws
        // ClassCastException when the key holds another class.
        if (!preferences.contains(name)) return null
        return try {
            when (type) {
                FlagValueType.Bool -> FlagValueBox.Bool(preferences.getBoolean(name, false))
                FlagValueType.Int -> FlagValueBox.Int(preferences.getLong(name, 0L))
                FlagValueType.Float -> FlagValueBox.Float(preferences.getFloat(name, 0f))
                FlagValueType.String -> preferences.getString(name, null)?.let { FlagValueBox.String(it) }
                FlagValueType.Double,
                FlagValueType.Data,
                FlagValueType.Date,
                FlagValueType.Url,
                is FlagValueType.Array,
                is FlagValueType.Dictionary,
                -> preferences.getString(name, null)?.let { FlagValueJson.decode(it, type) }
            }
        } catch (ignored: ClassCastException) {
            null
        }
    }

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        setBoxes(mapOf(key to box))
    }

    /**
     * Writes every change with one editor and one `commit()`, so the file never holds half of
     * them and the write is on disk when this returns. Changes that leave a key as it is are
     * left out; when none is left, the file is not touched at all.
     *
     * @throws FlagException.UnsupportedValue for a float or double holding an infinity or a NaN
     *   (also inside a collection). Nothing is written. A NaN already stored by someone else
     *   with `putFloat` still reads back as a float.
     */
    override fun setBoxes(changes: Map<FlagKey, FlagValueBox?>) {
        // Encode everything first, so a value that cannot be stored fails before any write.
        val values = changes.mapValues { (key, box) -> box?.let { storedForm(key, it) } }
        synchronized(lock) {
            val edits = values.filter { (key, value) ->
                if (value == null) preferences.contains(key.rawValue) else !holds(key.rawValue, value)
            }
            if (edits.isEmpty()) return
            val editor = preferences.edit()
            for ((key, value) in edits) {
                when (value) {
                    null -> editor.remove(key.rawValue)
                    is Boolean -> editor.putBoolean(key.rawValue, value)
                    is Long -> editor.putLong(key.rawValue, value)
                    is Float -> editor.putFloat(key.rawValue, value)
                    else -> editor.putString(key.rawValue, value as String)
                }
            }
            editor.commit()
        }
    }

    /** Announces [FlagChange.All], so every flag is read again. For values written by something other than a listener-notified editor. */
    public fun refresh() {
        emitter.emit(FlagChange.All)
    }

    /** Unregisters the preferences listener. The source still reads and writes, but announces nothing. */
    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Whether [name] already holds [value]: the same stored class and an equal value. */
    private fun holds(name: String, value: Any): Boolean {
        if (!preferences.contains(name)) return false
        return try {
            when (value) {
                is Boolean -> preferences.getBoolean(name, false) == value
                is Long -> preferences.getLong(name, 0L) == value
                // Bitwise, so 0.0 and -0.0 differ and NaN equals itself, as they are stored.
                is Float -> preferences.getFloat(name, 0f).toRawBits() == value.toRawBits()
                else -> preferences.getString(name, null) == value
            }
        } catch (ignored: ClassCastException) {
            false
        }
    }

    /** What [box] is stored as: a Boolean, Long, Float or String. */
    private fun storedForm(key: FlagKey, box: FlagValueBox): Any = when (box) {
        is FlagValueBox.Bool -> box.value
        is FlagValueBox.Int -> box.value
        is FlagValueBox.Float -> if (box.value.isFinite()) box.value else throw FlagException.UnsupportedValue(key)
        is FlagValueBox.String -> box.value
        else -> try {
            FlagValueJson.encode(box)
        } catch (ignored: FlagSerializationException.NonFiniteNumber) {
            throw FlagException.UnsupportedValue(key)
        }
    }

    public companion object {
        /**
         * The source a companion edits: the well-known file [FlagBridgeContract.PREFERENCES_NAME],
         * opened `MODE_PRIVATE`. Put it at the top of the host pole's sources.
         */
        public fun companion(context: Context, name: String = "Companion"): SharedPreferencesSource =
            SharedPreferencesSource(context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE), name)
    }
}
