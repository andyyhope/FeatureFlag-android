package com.andyyhope.featureflag.ui.screens

import org.junit.Test
import kotlin.test.assertEquals

class FlagRouteTest {

    @Test
    fun everyRouteSurvivesItsSavedForm() {
        val routes = listOf(
            FlagRoute.Group(listOf("checkout", "express")),
            FlagRoute.Group(emptyList()),
            FlagRoute.ListEditor("markets"),
            FlagRoute.Records("routes"),
            FlagRoute.Records("routes", listOf(RecordStep(0, "hops"), RecordStep(2, "legs"))),
            FlagRoute.Record("routes", listOf(RecordStep(1, "hops")), 3),
            FlagRoute.SignalGroup("Caches"),
        )

        routes.forEach { route -> assertEquals(route, FlagRouteCodec.decode(FlagRouteCodec.encode(route))) }
    }
}
