package com.andyyhope.featureflag.ui.share

import androidx.core.content.FileProvider

/**
 * FileProvider keeps one path strategy per authority in a static map, and the strategy captures the cache
 * directory. Robolectric gives every test its own cache directory, so a strategy left by an earlier test
 * would refuse this test's files. Production has one cache directory for the process's life.
 */
internal fun forgetFileProviderStrategies() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    (cache.get(null) as MutableMap<*, *>).clear()
}
