package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.records.RecordJson
import java.net.URI
import java.time.Instant

/** The codec for every supported value type. */
public object FlagCodecs {

    public val boolean: FlagCodec<Boolean> =
        codec(FlagValueType.Bool, { FlagValueBox.Bool(it) }) { (it as? FlagValueBox.Bool)?.value }

    /** Stored as a 64-bit `int`, like Swift's `Int`. A stored value outside `Int` range decodes to `null`. */
    public val int: FlagCodec<Int> = codec(FlagValueType.Int, { FlagValueBox.Int(it.toLong()) }) { box ->
        (box as? FlagValueBox.Int)?.value?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    public val long: FlagCodec<Long> =
        codec(FlagValueType.Int, { FlagValueBox.Int(it) }) { (it as? FlagValueBox.Int)?.value }

    public val double: FlagCodec<Double> =
        codec(FlagValueType.Double, { FlagValueBox.Double(it) }) { (it as? FlagValueBox.Double)?.value }

    public val float: FlagCodec<Float> =
        codec(FlagValueType.Float, { FlagValueBox.Float(it) }) { (it as? FlagValueBox.Float)?.value }

    public val string: FlagCodec<String> =
        codec(FlagValueType.String, { FlagValueBox.String(it) }) { (it as? FlagValueBox.String)?.value }

    /** Copies on the way in and out, so a stored value cannot be changed through an array someone kept. */
    public val byteArray: FlagCodec<ByteArray> =
        codec(FlagValueType.Data, { FlagValueBox.Data(it.copyOf()) }) { (it as? FlagValueBox.Data)?.value?.copyOf() }

    public val instant: FlagCodec<Instant> =
        codec(FlagValueType.Date, { FlagValueBox.Date(it) }) { (it as? FlagValueBox.Date)?.value }

    public val uri: FlagCodec<URI> =
        codec(FlagValueType.Url, { FlagValueBox.Url(it) }) { (it as? FlagValueBox.Url)?.value }

    /** A list, decoded all or nothing: one element of the wrong type fails the whole list. */
    public fun <T> list(element: FlagCodec<T>): FlagCodec<List<T>> = codec(
        FlagValueType.Array(element.type),
        { values -> FlagValueBox.Array(values.map(element::encode)) },
    ) { box ->
        (box as? FlagValueBox.Array)?.value?.map { element.decode(it) ?: return@codec null }
    }

    /** A map with string keys, decoded all or nothing. */
    public fun <T> map(value: FlagCodec<T>): FlagCodec<Map<String, T>> = codec(
        FlagValueType.Dictionary(value.type),
        { values -> FlagValueBox.Dictionary(values.mapValues { value.encode(it.value) }) },
    ) { box ->
        (box as? FlagValueBox.Dictionary)?.value?.mapValues { value.decode(it.value) ?: return@codec null }
    }

    /** An enum stored as its `name`. Publishes every name as a case. */
    public fun <E : Enum<E>> enumByName(entries: List<E>): FlagCodec<E> =
        enumCodec(FlagValueType.String, entries) { FlagValueBox.String(it.name) }

    /** An enum stored as its [FlagStringEnum.flagRawValue]. */
    public fun <E> stringEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagStringEnum =
        enumCodec(FlagValueType.String, entries) { FlagValueBox.String(it.flagRawValue) }

    /** An enum stored as its [FlagIntEnum.flagRawValue]. */
    public fun <E> intEnum(entries: List<E>): FlagCodec<E> where E : Enum<E>, E : FlagIntEnum =
        enumCodec(FlagValueType.Int, entries) { FlagValueBox.Int(it.flagRawValue) }

    /**
     * A list of records, stored as JSON text in a `string` flag. Decoding is all or nothing: one
     * unreadable record, or two sharing a key, fails the whole list.
     */
    public fun <R> records(codec: FlagRecordCodec<R>): FlagCodec<FlagRecords<R>> = object : FlagCodec<FlagRecords<R>> {
        override val type: FlagValueType = FlagValueType.String
        override val recordShape: List<FlagRecordField> = codec.shape

        override fun encode(value: FlagRecords<R>): FlagValueBox =
            FlagValueBox.String(RecordJson.encode(value.map(codec::boxes)))

        override fun decode(box: FlagValueBox): FlagRecords<R>? {
            val text = (box as? FlagValueBox.String)?.value ?: return null
            val records = RecordJson.decode(text, codec.shape) ?: return null
            return FlagRecords(records.map { codec.fromBoxes(it) ?: return null })
        }
    }

    private fun <T> codec(
        valueType: FlagValueType,
        encoder: (T) -> FlagValueBox,
        decoder: (FlagValueBox) -> T?,
    ): FlagCodec<T> = object : FlagCodec<T> {
        override val type: FlagValueType = valueType
        override fun encode(value: T): FlagValueBox = encoder(value)
        override fun decode(box: FlagValueBox): T? = decoder(box)
    }

    private fun <E> enumCodec(valueType: FlagValueType, entries: List<E>, toBox: (E) -> FlagValueBox): FlagCodec<E> {
        val byBox = entries.associateBy(toBox)
        val allCases = entries.map(toBox)
        return object : FlagCodec<E> {
            override val type: FlagValueType = valueType
            override val cases: List<FlagValueBox> = allCases
            override fun encode(value: E): FlagValueBox = toBox(value)
            override fun decode(box: FlagValueBox): E? = byBox[box]
        }
    }
}
