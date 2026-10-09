package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodec
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * A typed handle to one flag, reached through the generated tree: `AppFlagsSpec.checkout.googlePay`.
 * The Kotlin counterpart of Swift's `$flag` projected value.
 */
public class FlagRef<T>(
    public val keyPath: FlagKeyPath,
    public val propertyName: String,
    public val description: String,
    public val remoteKey: String?,
    public val codec: FlagCodec<T>,
    defaultProvider: () -> T,
) {
    /**
     * The compiled default. Read on first use, because generated refs are built while the spec
     * object that owns the defaults is still initialising.
     */
    public val defaultValue: T by lazy(defaultProvider)

    /** The storage key under [encoding]. */
    public fun key(encoding: KeyEncoding): FlagKey = encoding.key(keyPath)

    override fun toString(): String = "FlagRef(${keyPath.propertyNames.joinToString(".")})"
}
