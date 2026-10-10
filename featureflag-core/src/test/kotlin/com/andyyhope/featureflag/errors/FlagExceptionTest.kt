package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class FlagExceptionTest {

    @Test
    fun writingWithNothingWritableSaysWhatToAdd() {
        assertEquals(
            "No source in the stack can be written to. Add a SharedPreferencesSource or a SnapshotSource — " +
                "a pole built only from read-only sources has nowhere to put an override.",
            FlagException.NoMutableSource().message,
        )
    }

    @Test
    fun aRefusedValueNamesTheFlag() {
        val error = FlagException.UnsupportedValue(FlagKey("page-size"))

        assertEquals(FlagKey("page-size"), error.key)
        assertEquals(
            "The source refused the value for 'page-size' because it cannot represent it. " +
                "The store accepts a narrower set of types than a flag can declare.",
            error.message,
        )
    }
}
