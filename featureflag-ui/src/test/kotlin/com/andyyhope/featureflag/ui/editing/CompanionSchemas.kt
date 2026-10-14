package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.records.FlagRecordCodec
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.records.FlagRecords
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.net.URI
import java.time.Instant

/**
 * The Swift tests' `@FlagContainer` and `@FlagRecord` fixtures, rebuilt by hand as the schemas
 * their macros publish: the same keys, property paths, types, defaults, cases, groups and
 * descriptions. A companion only ever sees a schema, so these are exactly what it works from.
 */
internal object CompanionSchemas {

    val generatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** FlagEditingStoreTests' `CompanionFlags`. */
    val companion: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("new-onboarding", listOf("newOnboarding"), "New onboarding experience", FlagValueBox.Bool(false)),
            entry("max-items", listOf("maxItems"), "Maximum items", FlagValueBox.Int(10)),
            entry("checkout.apple-pay", listOf("checkout", "applePay"), "Apple Pay", FlagValueBox.Bool(false)),
            entry("checkout.ratio", listOf("checkout", "ratio"), "Ratio", FlagValueBox.Double(1.0)),
            entry(
                "checkout.tier",
                listOf("checkout", "tier"),
                "Tier",
                FlagValueBox.String("free"),
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry("checkout.launched-at", listOf("checkout", "launchedAt"), "Launched at", FlagValueBox.Date(Instant.EPOCH)),
            entry("checkout.endpoint", listOf("checkout", "endpoint"), "Endpoint", FlagValueBox.Url(URI("https://example.com"))),
            entry("checkout.express.label", listOf("checkout", "express", "label"), "Label", FlagValueBox.String("")),
            entry(
                "checkout.express.tags",
                listOf("checkout", "express", "tags"),
                "Tags",
                FlagValueBox.Array(emptyList()),
                type = FlagValueType.Array(FlagValueType.String),
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
        ),
    )

    /** FlagEditingStoreEdgeCaseTests' `DemoUIFlags`. */
    val demoUi: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("new-onboarding", listOf("newOnboarding"), "New onboarding", FlagValueBox.Bool(false)),
            entry("max-items", listOf("maxItems"), "Maximum items", FlagValueBox.Int(10)),
            entry("checkout.apple-pay", listOf("checkout", "applePay"), "Apple Pay", FlagValueBox.Bool(false)),
        ),
        groups = listOf(FlagSchema.Group(listOf("checkout"), "Checkout")),
    )

    /** FlagTreeNodeTests' `TreeFlags`, four levels deep. */
    val tree: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("top", listOf("top"), "Top", FlagValueBox.Bool(false)),
            entry("second", listOf("second"), "Second", FlagValueBox.Int(1)),
            entry("checkout.apple-pay", listOf("checkout", "applePay"), "Apple Pay", FlagValueBox.Bool(false)),
            entry("checkout.express.one-tap", listOf("checkout", "express", "oneTap"), "One tap", FlagValueBox.Bool(false)),
            entry(
                "checkout.express.confirmation.delay",
                listOf("checkout", "express", "confirmation", "delay"),
                "Delay",
                FlagValueBox.Double(3.0),
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
            FlagSchema.Group(listOf("checkout", "express", "confirmation"), "Confirmation"),
        ),
    )

    /** FlagArrayEditorTests' `ArrayEditorFlags`. */
    val arrayEditor: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry(
                "markets",
                listOf("markets"),
                "Markets",
                FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
                type = FlagValueType.Array(FlagValueType.String),
            ),
            entry(
                "counts",
                listOf("counts"),
                "Counts",
                FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))),
                type = FlagValueType.Array(FlagValueType.Int),
            ),
            entry(
                "launches",
                listOf("launches"),
                "Launch dates",
                FlagValueBox.Array(emptyList()),
                type = FlagValueType.Array(FlagValueType.Date),
            ),
            entry(
                "nested",
                listOf("nested"),
                "Nested",
                FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.String("a"))))),
                type = FlagValueType.Array(FlagValueType.Array(FlagValueType.String)),
            ),
            entry(
                "shares",
                listOf("shares"),
                "Shares",
                FlagValueBox.Dictionary(emptyMap()),
                type = FlagValueType.Dictionary(FlagValueType.Double),
            ),
            entry("name", listOf("name"), "Name", FlagValueBox.String("demo")),
        ),
    )

    // Record shapes, in declaration order, as @FlagRecord publishes them.

    /** FlagRecordEditorTests' `Endpoint`: no key. */
    val endpointShape: List<FlagRecordField> = listOf(
        FlagRecordField("name", FlagValueType.String),
        FlagRecordField("url", FlagValueType.Url),
        FlagRecordField("enabled", FlagValueType.Bool),
        FlagRecordField("weight", FlagValueType.Int),
        FlagRecordField(
            "tier",
            FlagValueType.String,
            cases = listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")),
        ),
    )

    /** FlagRecordKeyEditorTests' `Hop`, keyed by host. */
    val hopShape: List<FlagRecordField> = listOf(FlagRecordField("host", FlagValueType.String, isKey = true))

    /** FlagRecordKeyEditorTests' `Route`: keyed by name, with a nested list of hops that defaults to none. */
    val routeShape: List<FlagRecordField> = listOf(
        FlagRecordField("name", FlagValueType.String, isKey = true),
        FlagRecordField("hops", FlagValueType.String, defaultValue = FlagValueBox.String("[]"), fields = hopShape),
    )

    /** FlagRecordKeyEditorTests' `Stamped`, keyed by a date. */
    val stampedShape: List<FlagRecordField> = listOf(
        FlagRecordField("at", FlagValueType.Date, isKey = true),
        FlagRecordField("note", FlagValueType.String, defaultValue = FlagValueBox.String("")),
    )

    /** FlagRecordKeyEditorTests' `EditorNote`: no key. */
    val noteShape: List<FlagRecordField> = listOf(FlagRecordField("text", FlagValueType.String))

    val production: Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String("production"),
        "url" to FlagValueBox.Url(URI("https://prod.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(10),
        "tier" to FlagValueBox.String("primary"),
    )

    /** FlagRecordEditorTests' `RecordEditorFlags`. */
    val recordEditor: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("endpoints", listOf("endpoints"), "Endpoints", FlagRecordList.encode(listOf(production)), shape = endpointShape),
            entry("name", listOf("name"), "Name", FlagValueBox.String("Demo")),
        ),
    )

    /** FlagRecordKeyEditorTests' `KeyedEditorFlags`. */
    val keyedEditor: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("routes", listOf("routes"), "Routes", FlagValueBox.String("[]"), shape = routeShape),
            entry("notes", listOf("notes"), "Notes", FlagValueBox.String("[]"), shape = noteShape),
            entry("stamped", listOf("stamped"), "Stamped", FlagValueBox.String("[]"), shape = stampedShape),
        ),
    )

    /** FlagQRCodeTextTests' `QRFlags`. */
    val qr: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("page-size", listOf("pageSize"), "Items per page", FlagValueBox.Int(10)),
            entry("note", listOf("note"), "A note", FlagValueBox.String("")),
        ),
    )

    /** FlagContainerDescriptionUITests' `DescribedUIFlags`. */
    val described: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        description = "What the checkout team can turn on",
        flags = listOf(entry("new-onboarding", listOf("newOnboarding"), "Onboarding", FlagValueBox.Bool(false))),
    )

    /** FlagContainerDescriptionUITests' `PlainUIFlags`. */
    val plain: FlagSchema = described.copy(description = null)

    /** A flag of [default]'s type unless [type] says otherwise, as a hand-built schema states it. */
    fun entry(
        key: String,
        path: List<String>,
        description: String,
        default: FlagValueBox,
        type: FlagValueType = scalarType(default),
        cases: List<FlagValueBox>? = null,
        shape: List<FlagRecordField>? = null,
    ): FlagSchema.Entry = FlagSchema.Entry(
        key = FlagKey(key),
        propertyPath = path,
        description = description,
        valueType = type,
        defaultValue = default,
        cases = cases,
        recordShape = shape,
    )

    private fun scalarType(box: FlagValueBox): FlagValueType = when (box) {
        is FlagValueBox.Bool -> FlagValueType.Bool
        is FlagValueBox.Int -> FlagValueType.Int
        is FlagValueBox.Double -> FlagValueType.Double
        is FlagValueBox.Float -> FlagValueType.Float
        is FlagValueBox.String -> FlagValueType.String
        is FlagValueBox.Data -> FlagValueType.Data
        is FlagValueBox.Date -> FlagValueType.Date
        is FlagValueBox.Url -> FlagValueType.Url
        is FlagValueBox.Array, is FlagValueBox.Dictionary -> error("A collection's element type has to be given.")
    }
}

// What the host reads: the Swift tests check writes through a FlagPole over the same store; these
// decode exactly as the host's FlagCodecs.records does, from a record type or from the shape alone.

internal enum class Tier(val rawValue: String) { Primary("primary"), Secondary("secondary") }

internal data class Endpoint(val name: String, val url: URI, val enabled: Boolean, val weight: Long, val tier: Tier)

/** A hand-written codec for [Endpoint], as KSP would generate it. */
internal object EndpointCodec : FlagRecordCodec<Endpoint> {
    override val shape: List<FlagRecordField> = CompanionSchemas.endpointShape
    override val keyField: String? = null

    override fun boxes(record: Endpoint): Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String(record.name),
        "url" to FlagValueBox.Url(record.url),
        "enabled" to FlagValueBox.Bool(record.enabled),
        "weight" to FlagValueBox.Int(record.weight),
        "tier" to FlagValueBox.String(record.tier.rawValue),
    )

    override fun fromBoxes(boxes: Map<String, FlagValueBox>): Endpoint? {
        return Endpoint(
            name = (boxes["name"] as? FlagValueBox.String)?.value ?: return null,
            url = (boxes["url"] as? FlagValueBox.Url)?.value ?: return null,
            enabled = (boxes["enabled"] as? FlagValueBox.Bool)?.value ?: return null,
            weight = (boxes["weight"] as? FlagValueBox.Int)?.value ?: return null,
            tier = Tier.entries.firstOrNull { FlagValueBox.String(it.rawValue) == boxes["tier"] } ?: return null,
        )
    }
}

/** A codec that keeps each record as its boxes, so the host's reader can be run from a shape alone. */
internal class RowsCodec(override val shape: List<FlagRecordField>) : FlagRecordCodec<Map<String, FlagValueBox>> {
    override val keyField: String? = shape.firstOrNull { it.isKey }?.name
    override fun boxes(record: Map<String, FlagValueBox>): Map<String, FlagValueBox> = record
    override fun fromBoxes(boxes: Map<String, FlagValueBox>): Map<String, FlagValueBox> = boxes
}

/** The endpoints the host's pole would read from [box], or null when it would fall back to its default. */
internal fun hostEndpoints(box: FlagValueBox?): FlagRecords<Endpoint>? =
    box?.let(FlagCodecs.records(EndpointCodec)::decode)

/** The records the host's pole would read from [box] through [shape], or null when it would use its default. */
internal fun hostRecords(
    box: FlagValueBox?,
    shape: List<FlagRecordField>,
): FlagRecords<Map<String, FlagValueBox>>? = box?.let(FlagCodecs.records(RowsCodec(shape))::decode)
