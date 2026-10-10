package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class FlagSerializationExceptionTest {

    @Test
    fun nonFiniteNumberMessageMatchesSwiftVerbatim() {
        assertEquals(
            Fixtures.text("messages/FlagSerializationError.nonFiniteNumber.txt"),
            FlagSerializationException.NonFiniteNumber(FlagKey("ratio")).message,
        )
    }

    @Test
    fun withoutAKeyTheMessageSaysAValue() {
        assertEquals(
            "A value holds an infinity or a NaN, and JSON can represent neither. " +
                "Use a finite value, or a sentinel the flag's type already has.",
            FlagSerializationException.NonFiniteNumber(null).message,
        )
    }
}
