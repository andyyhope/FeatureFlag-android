package com.andyyhope.featureflag.golden

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagValueBox

// Hand-written stand-ins for what KSP generates in M1b. M1b Task B4 deletes this file when the
// processor starts generating GoldenEndpointFlagRecord and GoldenTargetFlagRecord.

object GoldenTargetFlagRecord : FlagRecordCodec<GoldenTarget> {
    override val shape: List<FlagRecordField> = listOf(
        FlagRecordField.of("host", FlagCodecs.string),
        FlagRecordField.of("port", FlagCodecs.int),
    )
    override val keyField: String? = null

    override fun boxes(record: GoldenTarget): Map<String, FlagValueBox> = mapOf(
        "host" to FlagCodecs.string.encode(record.host),
        "port" to FlagCodecs.int.encode(record.port),
    )

    override fun fromBoxes(boxes: Map<String, FlagValueBox>): GoldenTarget? {
        val host = boxes["host"]?.let(FlagCodecs.string::decode) ?: return null
        val port = boxes["port"]?.let(FlagCodecs.int::decode) ?: return null
        return GoldenTarget(host, port)
    }
}

object GoldenEndpointFlagRecord : FlagRecordCodec<GoldenEndpoint> {
    private val tier = FlagCodecs.stringEnum(GoldenTier.entries)
    private val targets = FlagCodecs.records(GoldenTargetFlagRecord)

    override val shape: List<FlagRecordField> = listOf(
        FlagRecordField.of("name", FlagCodecs.string, isKey = true),
        FlagRecordField.of("url", FlagCodecs.uri),
        FlagRecordField.of("weight", FlagCodecs.int, default = 1),
        FlagRecordField.of("note", FlagCodecs.string, isOptional = true, decodedName = "memo"),
        FlagRecordField.of("kind", tier, default = GoldenTier.FREE),
        FlagRecordField.of("targets", targets, default = FlagRecords()),
    )
    override val keyField: String? = "name"

    override fun boxes(record: GoldenEndpoint): Map<String, FlagValueBox> = buildMap {
        put("name", FlagCodecs.string.encode(record.name))
        put("url", FlagCodecs.uri.encode(record.url))
        put("weight", FlagCodecs.int.encode(record.weight))
        record.note?.let { put("note", FlagCodecs.string.encode(it)) }
        put("kind", tier.encode(record.kind))
        put("targets", targets.encode(record.targets))
    }

    override fun fromBoxes(boxes: Map<String, FlagValueBox>): GoldenEndpoint? {
        val name = boxes["name"]?.let(FlagCodecs.string::decode) ?: return null
        val url = boxes["url"]?.let(FlagCodecs.uri::decode) ?: return null
        val weight = boxes["weight"]?.let(FlagCodecs.int::decode) ?: return null
        val kind = boxes["kind"]?.let(tier::decode) ?: return null
        val targets = boxes["targets"]?.let(targets::decode) ?: return null
        val note = boxes["note"]?.let(FlagCodecs.string::decode)
        return GoldenEndpoint(name, url, weight, note, kind, targets)
    }
}
