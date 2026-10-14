package com.andyyhope.featureflag.ui.editing

import com.andyyhope.featureflag.values.FlagValueBox

/**
 * A record write the editor refused: two records would share [value] in the key [field]. The
 * host refuses such a list and reads its default instead, so it is never written.
 */
public class FlagRecordEditingException(
    public val field: String,
    public val value: FlagValueBox,
) : RuntimeException(
    "Another record already has that $field. It is what tells one record from another, so two cannot share it.",
)
