package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.remote.EnvironmentConfiguration
import com.andyyhope.featureflag.sources.SnapshotSource
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import kotlinx.coroutines.CoroutineScope

/**
 * The host's wiring, as DemoApplication builds it, with a SnapshotSource standing in for the
 * companion's SharedPreferences file. A test plays the companion by writing to [companion].
 */
internal class TestDemo(
    scope: CoroutineScope,
    config: EnvironmentConfiguration<DemoEnvironment> = demoConfiguration(),
) {
    val companion = SnapshotSource("Companion")

    val model = DemoModel(
        FlagPole(AppFlagsSpec, sources = listOf(companion) + config.sources, applicationName = "Demo"),
        config,
        scope,
    )

    /** Writes [box] as the companion would, or removes the override when it is null. */
    fun set(key: String, box: FlagValueBox?) {
        companion.setBox(FlagKey(key), box)
    }

    /** Which source "Where each value came from" names for [key]. */
    fun source(key: String): String = model.values.value.provenance.first { row -> row.key == key }.sourceName
}
