package com.andyyhope.featureflag.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyyhope.featureflag.audit.FlagDefaultComparison
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Core writes numbers as Swift does, by finding the shortest digits that `BigDecimal.toDouble()`
 * or `toFloat()` reads back exactly. That relies on ART's BigDecimal rounding as the JVM's does.
 * Each expected string is what the same call printed on JDK 21, the JVM core's tests run on.
 */
@RunWith(AndroidJUnit4::class)
class ArtNumberDescriptionTest {

    @Test
    fun doublesDescribeThemselvesAsOnTheJvm() {
        val cases = listOf(
            0.1 to "0.1",
            5.0 to "5.0",
            0.1 + 0.2 to "0.30000000000000004",
            1.0 / 3.0 to "0.3333333333333333",
            2.675 to "2.675",
            123456.789 to "123456.789",
            0.000123 to "0.000123",
            1.0E-7 to "1e-07",
            9007199254740992.0 to "9007199254740992.0",
            9007199254740994.0 to "9.007199254740994e+15",
            1.0E19 to "1e+19",
            1.0E21 to "1e+21",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            2.2250738585072014E-308 to "2.2250738585072014e-308",
            Double.MIN_VALUE to "5e-324",
            -0.0 to "-0.0",
            -1.5 to "-1.5",
        )

        val described = cases.map { (value, _) ->
            FlagDefaultComparison(FlagKey("n"), FlagValueBox.Double(0.0), FlagValueBox.Double(value)).toString()
        }

        assertEquals(cases.map { (_, expected) -> "n: 0.0 → $expected" }, described)
    }

    @Test
    fun floatsDescribeThemselvesAsOnTheJvm() {
        val cases = listOf(
            0.1f to "0.1",
            0.3f to "0.3",
            1.1f to "1.1",
            3.14159f to "3.14159",
            -2.5f to "-2.5",
            16777216f to "16777216.0",
            16777218f to "1.6777218e+07",
            1.0E10f to "1e+10",
            1.0E-10f to "1e-10",
            Float.MAX_VALUE to "3.4028235e+38",
            Float.MIN_VALUE to "1e-45",
        )

        val described = cases.map { (value, _) ->
            FlagDefaultComparison(FlagKey("n"), FlagValueBox.Float(0f), FlagValueBox.Float(value)).toString()
        }

        assertEquals(cases.map { (_, expected) -> "n: 0.0 → $expected" }, described)
    }

    @Test
    fun aRestatedDefaultSaysSo() {
        val comparison = FlagDefaultComparison(FlagKey("n"), FlagValueBox.Double(0.1), FlagValueBox.Double(0.1))

        assertEquals("n: 0.1 — matches the default", comparison.toString())
    }
}
