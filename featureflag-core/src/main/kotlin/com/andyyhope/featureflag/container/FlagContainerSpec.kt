package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagKeyPath

/**
 * What the processor generates for each `@FlagContainer` interface `F`, as the object `FSpec`.
 * Everything a pole, a schema or a companion needs, without reflection.
 */
public interface FlagContainerSpec<F : Any> {
    /** What this set of flags is for, from `@FlagContainer(description = …)`; null when it says nothing. */
    public val flagContainerDescription: String?

    /** The container's shape, rooted at the container itself. */
    public val flagDescriptors: List<FlagSchemaNode>

    /** An instance whose every flag reads its compiled default. */
    public val flagDefaults: F

    /** An instance whose flags read through [lookup], with every key rooted at [prefix]. */
    public fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath = FlagKeyPath.ROOT): F
}
