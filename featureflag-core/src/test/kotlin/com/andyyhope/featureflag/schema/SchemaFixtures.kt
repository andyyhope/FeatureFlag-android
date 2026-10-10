package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagStringEnum

/** A one-off spec for schema tests: only the shape matters, so there are no flags to read. */
internal fun specOf(vararg nodes: FlagSchemaNode, description: String? = null): FlagContainerSpec<Any> =
    object : FlagContainerSpec<Any> {
        override val flagContainerDescription: String? = description
        override val flagDescriptors: List<FlagSchemaNode> = nodes.toList()
        override val flagDefaults: Any = Unit
        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Unit
    }

/** A `@Flag`, written the way generated code writes it. */
internal fun <T> flag(
    name: String,
    description: String,
    codec: FlagCodec<T>,
    default: T,
    remoteKey: String? = null,
): FlagSchemaNode = FlagSchemaNode.Flag(FlagDescriptor.of(name, description, remoteKey, codec, default))

/** A `@FlagGroup`, written the way generated code writes it: its children re-rooted under it. */
internal fun group(name: String, description: String, vararg children: FlagSchemaNode): FlagSchemaNode =
    FlagSchemaNode.Group(
        FlagGroupDescriptor(name, FlagKeyPath(listOf(name)), description, children.map { it.prefixed(name) }),
    )

internal enum class DemoTier(override val flagRawValue: String) : FlagStringEnum { FREE("free"), PRO("pro") }

/** Swift's `DemoFlags` from `Tests/FeatureFlagTests/Fixtures.swift`. */
internal val demoSpec: FlagContainerSpec<Any> = specOf(
    flag("newOnboarding", "New onboarding", FlagCodecs.boolean, false, "featureToggles.onboarding.v2"),
    flag("maxItems", "Maximum items", FlagCodecs.int, 10),
    group(
        "checkout",
        "Checkout",
        flag("applePay", "Apple Pay", FlagCodecs.boolean, false, "featureToggles.checkout.applePay"),
        group("express", "Express", flag("oneTap", "One tap", FlagCodecs.boolean, false)),
        flag("tier", "Tier", FlagCodecs.stringEnum(DemoTier.entries), DemoTier.FREE),
    ),
)
