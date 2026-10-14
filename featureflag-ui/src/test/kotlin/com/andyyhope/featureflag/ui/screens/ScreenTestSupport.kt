package com.andyyhope.featureflag.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.records.FlagRecordList
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.sources.MutableFlagValueSource
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.ui.editing.FlagEditingStore
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.net.URI
import java.time.Instant

/**
 * Hand-built schemas for the screen tests. They mirror the iOS fixtures (`CompanionFlags`,
 * `ArrayEditorFlags`, the record fixtures) by key, type, default, group and description.
 */
internal object ScreenFixtures {

    private val generatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    fun entry(
        key: String,
        type: FlagValueType,
        default: FlagValueBox,
        description: String = key,
        cases: List<FlagValueBox>? = null,
        remoteKey: String? = null,
        shape: List<FlagRecordField>? = null,
    ): FlagSchema.Entry = FlagSchema.Entry(
        key = FlagKey(key),
        propertyPath = key.split("."),
        description = description,
        valueType = type,
        defaultValue = default,
        cases = cases,
        remoteKey = remoteKey,
        recordShape = shape,
    )

    /** iOS CompanionFlags: two root flags, a Checkout group with five, and Checkout › Express with two. */
    val companion: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        applicationName = "Demo",
        description = "What the checkout team can turn on",
        flags = listOf(
            entry("new-onboarding", FlagValueType.Bool, FlagValueBox.Bool(false), "New onboarding experience"),
            entry("max-items", FlagValueType.Int, FlagValueBox.Int(10), "Maximum items"),
            entry("checkout.apple-pay", FlagValueType.Bool, FlagValueBox.Bool(false), "Apple Pay"),
            entry("checkout.ratio", FlagValueType.Double, FlagValueBox.Double(1.0), "Ratio"),
            entry(
                "checkout.tier",
                FlagValueType.String,
                FlagValueBox.String("free"),
                "Tier",
                cases = listOf(FlagValueBox.String("free"), FlagValueBox.String("pro")),
            ),
            entry("checkout.launched-at", FlagValueType.Date, FlagValueBox.Date(Instant.EPOCH), "Launched at"),
            entry(
                "checkout.endpoint",
                FlagValueType.Url,
                FlagValueBox.Url(URI("https://example.com")),
                "Endpoint",
                remoteKey = "checkout_endpoint",
            ),
            entry("checkout.express.label", FlagValueType.String, FlagValueBox.String(""), "Label"),
            entry(
                "checkout.express.tags",
                FlagValueType.Array(FlagValueType.String),
                FlagValueBox.Array(emptyList()),
                "Tags",
            ),
        ),
        groups = listOf(
            FlagSchema.Group(listOf("checkout"), "Checkout"),
            FlagSchema.Group(listOf("checkout", "express"), "Express"),
        ),
    )

    /** iOS Endpoint record: no key field. */
    val endpointFields: List<FlagRecordField> = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String),
        FlagRecordField(name = "url", type = FlagValueType.Url),
        FlagRecordField(name = "enabled", type = FlagValueType.Bool),
        FlagRecordField(name = "weight", type = FlagValueType.Int),
        FlagRecordField(
            name = "tier",
            type = FlagValueType.String,
            cases = listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")),
        ),
    )

    /** iOS Route record: keyed by name, with a nested list of hops keyed by host. */
    val routeFields: List<FlagRecordField> = listOf(
        FlagRecordField(name = "name", type = FlagValueType.String, isKey = true),
        FlagRecordField(
            name = "hops",
            type = FlagValueType.String,
            defaultValue = FlagValueBox.String("[]"),
            fields = listOf(FlagRecordField(name = "host", type = FlagValueType.String, isKey = true)),
        ),
    )

    fun endpoint(name: String, weight: Long = 10, tier: String = "primary"): Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String(name),
        "url" to FlagValueBox.Url(URI("https://$name.example")),
        "enabled" to FlagValueBox.Bool(true),
        "weight" to FlagValueBox.Int(weight),
        "tier" to FlagValueBox.String(tier),
    )

    /** Lists, blocks and record lists (iOS ArrayEditorFlags and the record fixtures). */
    val editors: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry(
                "markets",
                FlagValueType.Array(FlagValueType.String),
                FlagValueBox.Array(listOf(FlagValueBox.String("AU"), FlagValueBox.String("NZ"))),
                "Markets",
            ),
            entry(
                "counts",
                FlagValueType.Array(FlagValueType.Int),
                FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))),
                "Counts",
            ),
            entry("logo", FlagValueType.Data, FlagValueBox.Data(byteArrayOf(1, 2)), "Logo"),
            entry("limits", FlagValueType.Dictionary(FlagValueType.Int), FlagValueBox.Dictionary(emptyMap()), "Limits"),
            entry(
                "endpoints",
                FlagValueType.String,
                FlagRecordList.encode(listOf(endpoint("production"))),
                "Endpoints",
                shape = endpointFields,
            ),
            entry("routes", FlagValueType.String, FlagValueBox.String("[]"), "Routes", shape = routeFields),
        ),
    )

    /** One single-element list per scalar element type (iOS testAnElementRowBuildsForEveryScalar). */
    val elements: FlagSchema = FlagSchema(
        generatedAt = generatedAt,
        flags = listOf(
            entry("strings", FlagValueType.Array(FlagValueType.String), FlagValueBox.Array(listOf(FlagValueBox.String("a")))),
            entry("ints", FlagValueType.Array(FlagValueType.Int), FlagValueBox.Array(listOf(FlagValueBox.Int(1)))),
            entry("doubles", FlagValueType.Array(FlagValueType.Double), FlagValueBox.Array(listOf(FlagValueBox.Double(1.5)))),
            entry("bools", FlagValueType.Array(FlagValueType.Bool), FlagValueBox.Array(listOf(FlagValueBox.Bool(true)))),
            entry("dates", FlagValueType.Array(FlagValueType.Date), FlagValueBox.Array(listOf(FlagValueBox.Date(Instant.EPOCH)))),
            entry(
                "urls",
                FlagValueType.Array(FlagValueType.Url),
                FlagValueBox.Array(listOf(FlagValueBox.Url(URI("https://a.example")))),
            ),
            entry("blobs", FlagValueType.Array(FlagValueType.Data), FlagValueBox.Array(listOf(FlagValueBox.Data(byteArrayOf(1))))),
        ),
    )

    val empty: FlagSchema = FlagSchema(generatedAt = generatedAt, flags = emptyList())
}

/** A store whose writes run inline on the Robolectric main thread. */
internal fun testStore(
    schema: FlagSchema,
    values: Map<FlagKey, FlagValueBox> = emptyMap(),
): FlagEditingStore = FlagEditingStore(schema, SnapshotSource(values = values), writeDispatcher = Dispatchers.Main.immediate)

internal fun FlagEditingStore.requireEntry(key: String): FlagSchema.Entry =
    checkNotNull(entry(FlagKey(key))) { "The schema has no flag '$key'." }

/** Shows [content] the way the shell does: in the caller's theme, with a writer that reports to [onFailure]. */
internal fun ComposeContentTestRule.setFlagContent(
    onFailure: (Exception) -> Unit = { throw it },
    content: @Composable () -> Unit,
) {
    setContent { MaterialTheme { ProvideFlagWriter(onFailure = onFailure) { content() } } }
}

/** Brings the lazy-list item holding [text] into composition. */
internal fun ComposeContentTestRule.scrollTo(text: String) {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
}

internal fun clipboardText(): String? = ApplicationProvider.getApplicationContext<Context>()
    .getSystemService(ClipboardManager::class.java)
    ?.primaryClip
    ?.getItemAt(0)
    ?.text
    ?.toString()

/** A source every write to fails, as a host that refuses or has died would. Reads find nothing. */
internal class FailingSource(private val failure: () -> RuntimeException) : MutableFlagValueSource {
    override val sourceName: String = "Failing"
    override val changes: Flow<FlagChange> = emptyFlow()

    override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null

    override fun setBox(key: FlagKey, box: FlagValueBox?) {
        throw failure()
    }
}

/**
 * The editable field that shows [text] now. It is looked up by position, so the handle stays valid once the
 * field's text changes (a handle matching on its text would no longer find it).
 */
internal fun ComposeContentTestRule.textFieldShowing(text: String): SemanticsNodeInteraction {
    val index = onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        .indexOfFirst { node -> node.config.getOrNull(SemanticsProperties.EditableText)?.text == text }
    check(index >= 0) { "No text field shows '$text'." }
    return onAllNodes(hasSetTextAction())[index]
}
