package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType

/**
 * Everything about one flag that an editor or a remote mapper needs, available without an
 * instance of the container that declares it.
 */
public data class FlagDescriptor(
    public val propertyName: String,
    public val keyPath: FlagKeyPath,
    public val description: String,
    public val valueType: FlagValueType,
    public val defaultValue: FlagValueBox,
    public val cases: List<FlagValueBox>? = null,
    public val remoteKey: String? = null,
    public val recordShape: List<FlagRecordField>? = null,
) {
    internal fun prefixed(component: String): FlagDescriptor = copy(keyPath = keyPath.prepending(component))

    public companion object {
        /** The descriptor generated code writes for one `@Flag`, rooted at the container. */
        public fun <T> of(
            propertyName: String,
            description: String,
            remoteKey: String?,
            codec: FlagCodec<T>,
            default: T,
        ): FlagDescriptor = FlagDescriptor(
            propertyName = propertyName,
            keyPath = FlagKeyPath(listOf(propertyName)),
            description = description,
            valueType = codec.type,
            defaultValue = codec.encode(default),
            cases = codec.cases,
            remoteKey = remoteKey,
            recordShape = codec.recordShape,
        )
    }
}

/** A nested container, which namespaces its children and nothing more. */
public data class FlagGroupDescriptor(
    public val propertyName: String,
    public val keyPath: FlagKeyPath,
    public val description: String,
    public val children: List<FlagSchemaNode>,
) {
    internal fun prefixed(component: String): FlagGroupDescriptor = FlagGroupDescriptor(
        propertyName = propertyName,
        keyPath = keyPath.prepending(component),
        description = description,
        children = children.map { it.prefixed(component) },
    )
}

/** One entry in a container's shape: either a flag or a nested group. */
public sealed interface FlagSchemaNode {
    public val propertyName: String
    public val keyPath: FlagKeyPath

    /** Re-roots this node, and everything beneath it, under [component]. */
    public fun prefixed(component: String): FlagSchemaNode

    public data class Flag(public val descriptor: FlagDescriptor) : FlagSchemaNode {
        override val propertyName: String get() = descriptor.propertyName
        override val keyPath: FlagKeyPath get() = descriptor.keyPath
        override fun prefixed(component: String): FlagSchemaNode = Flag(descriptor.prefixed(component))
    }

    public data class Group(public val group: FlagGroupDescriptor) : FlagSchemaNode {
        override val propertyName: String get() = group.propertyName
        override val keyPath: FlagKeyPath get() = group.keyPath
        override fun prefixed(component: String): FlagSchemaNode = Group(group.prefixed(component))
    }
}

/** Every flag in the tree, depth first, in declaration order, with complete key paths. */
public fun List<FlagSchemaNode>.flattened(): List<FlagDescriptor> = flatMap { node ->
    when (node) {
        is FlagSchemaNode.Flag -> listOf(node.descriptor)
        is FlagSchemaNode.Group -> node.group.children.flattened()
    }
}
