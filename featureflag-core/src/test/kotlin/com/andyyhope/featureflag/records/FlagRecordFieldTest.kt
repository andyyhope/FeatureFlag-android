package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlagRecordFieldTest {

    private enum class Tier(override val flagRawValue: String) : FlagStringEnum {
        PRIMARY("primary"),
        SECONDARY("secondary"),
    }

    @Test
    fun ofTakesTypeAndCasesFromTheCodecAndBoxesTheDefault() {
        val field = FlagRecordField.of("tier", FlagCodecs.stringEnum(Tier.entries), default = Tier.PRIMARY)
        assertEquals(FlagValueType.String, field.type)
        assertEquals(listOf(FlagValueBox.String("primary"), FlagValueBox.String("secondary")), field.cases)
        assertEquals(FlagValueBox.String("primary"), field.defaultValue)
    }

    @Test
    fun aFieldWithNoCasesSaysSoRatherThanSayingNone() {
        assertNull(FlagRecordField.of("name", FlagCodecs.string).cases)
        assertNull(FlagRecordField.of("name", FlagCodecs.string).defaultValue)
    }

    @Test
    fun anOptionalFieldNeverCarriesADefault() {
        val field = FlagRecordField.of("minimumSpend", FlagCodecs.double, default = 1.0, isOptional = true)
        assertNull(field.defaultValue)
        assertEquals(true, field.isOptional)
    }

    @Test
    fun payloadNameIsTheDecodedNameWhenThereIsOne() {
        val renamed = FlagRecordField.of("applePay", FlagCodecs.boolean, decodedName = "apple_pay")
        assertEquals("apple_pay", renamed.payloadName)
        assertEquals("applePay", FlagRecordField.of("applePay", FlagCodecs.boolean).payloadName)
    }
}
