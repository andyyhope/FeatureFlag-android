package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.schema.FlagSchema

/**
 * One level of the flag tree: the flags declared directly here, and the groups beneath.
 *
 * A published schema is flat: each flag carries the property path it sits at. An editor wants
 * the shape back, so a tree of any depth can be walked one screen at a time. Order follows the
 * schema, which follows declaration order. A flag whose parent group the schema does not list is
 * not reachable from the root.
 */
public data class FlagTreeNode(
    /** Property names from the root; empty for the root itself. */
    public val path: List<String>,
    /** The group's description, or null at the root. */
    public val title: String?,
    /** Flags declared directly at this level: those whose property path, less its last name, is [path]. */
    public val flags: List<FlagSchema.Entry>,
    /** The schema's groups one level below [path], in schema order. */
    public val groups: List<FlagTreeNode>,
) {

    /** [path] joined with dots; the root's is empty. */
    public val id: String
        get() = path.joinToString(".")

    /** Every flag at or below this node: this level's first, then each group's, depth first. */
    public val allFlags: List<FlagSchema.Entry>
        get() = flags + groups.flatMap { it.allFlags }

    /** Whether there is no flag here or anywhere beneath. */
    public val isEmpty: Boolean
        get() = flags.isEmpty() && groups.all { it.isEmpty }

    public companion object {

        /** Rebuilds the tree a schema flattened. */
        public fun of(schema: FlagSchema): FlagTreeNode = node(schema, path = emptyList(), title = null)

        private fun node(schema: FlagSchema, path: List<String>, title: String?): FlagTreeNode = FlagTreeNode(
            path = path,
            title = title,
            flags = schema.flags.filter { it.propertyPath.dropLast(1) == path },
            groups = schema.groups
                .filter { it.propertyPath.size == path.size + 1 && it.propertyPath.take(path.size) == path }
                .map { node(schema, it.propertyPath, it.description) },
        )
    }
}

/** One section of a flat, searchable list of flags: the ungrouped flags, or one group's own. */
public data class FlagSection(
    /** The group's property names; empty for the ungrouped flags at the root. */
    public val path: List<String>,
    /** The group's description, or null for the ungrouped section. */
    public val title: String?,
    /** Every ancestor group's description and this one's, joined with " › ", e.g. `Checkout › Express`. */
    public val pathDescription: String,
    public val entries: List<FlagSchema.Entry>,
) {

    /** [path] joined with dots; the ungrouped section's is empty. */
    public val id: String
        get() = path.joinToString(".")
}
