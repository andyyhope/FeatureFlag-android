package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.json.JsonValue
import org.junit.Test
import kotlin.test.assertEquals

class UrlGoldenTest {

    @Test
    fun readsUrlsAsAppleDoesExceptTheKnownDeviations() {
        val mismatches = Fixtures.tsv("url/parse.tsv").mapNotNull { (input, expected) ->
            val box = JsonValue.Text(input).toBox(FlagValueType.Url) as FlagValueBox.Url?
            val actual = box?.value?.toString() ?: "nil"
            val wanted = KNOWN_URL_DEVIATIONS[input] ?: expected
            if (actual == wanted) null else "'$input': Apple $expected, Kotlin $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    private companion object {
        /** Inputs where Kotlin differs from `URL(string:)`, and what Kotlin produces instead. */
        val KNOWN_URL_DEVIATIONS = mapOf(
            // Apple converts a non-ASCII host to punycode; there is no IDNA here.
            "https://例え.jp/パス" to "nil",
            // java.net.URI refuses an empty authority, an empty scheme-specific part and a
            // bracketed host that is not an IP literal; Apple keeps all three as written.
            "://" to "nil",
            "https://" to "nil",
            "https:" to "nil",
            "http://[zz]/" to "nil",
        )
    }
}
