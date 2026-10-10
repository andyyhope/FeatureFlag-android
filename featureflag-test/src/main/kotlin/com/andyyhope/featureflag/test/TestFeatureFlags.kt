package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.FeatureFlag

/** Test helpers for consumers of featureflag-core. */
public object TestFeatureFlags {
    /** The featureflag-core version these helpers were built against. */
    public const val CORE_VERSION: String = FeatureFlag.VERSION
}
