package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What featureflag-ksp writes for a @FlagRecord: a shape for the companion, a box per field, and the way back. */
class FlagRecordGenerationTest {
    private val card = PaymentMethod(name = "visa", kind = PaymentKind.CARD, minimumSpend = 5.0)

    @Test fun everyConstructorPropertyIsAFieldInDeclarationOrder() {
        assertEquals(listOf("name", "kind", "enabled", "minimumSpend"), PaymentMethodFlagRecord.shape.map { it.name })
        assertEquals(listOf("startsAt", "percent"), RolloutFlagRecord.shape.map { it.name })
    }

    @Test fun aFieldWrittenWithADefaultCarriesIt() {
        val enabled = PaymentMethodFlagRecord.shape.single { it.name == "enabled" }
        assertEquals(FlagValueBox.Bool(true), enabled.defaultValue)
        assertEquals(FlagValueBox.Int(10), RolloutFlagRecord.shape.single { it.name == "percent" }.defaultValue)
        assertNull(PaymentMethodFlagRecord.shape.single { it.name == "name" }.defaultValue)
    }

    @Test fun theKeyIsMarkedAndPublished() {
        assertEquals("name", PaymentMethodFlagRecord.keyField)
        assertEquals(listOf(true, false, false, false), PaymentMethodFlagRecord.shape.map { it.isKey })
    }

    @Test fun aNullableFieldIsOptionalWithNoDefault() {
        val spend = PaymentMethodFlagRecord.shape.single { it.name == "minimumSpend" }
        assertTrue(spend.isOptional)
        assertNull(spend.defaultValue)
        assertEquals(FlagValueType.Double, spend.type)
    }

    @Test fun aCustomKeyIsCarriedAsTheDecodedName() {
        val enabled = PaymentMethodFlagRecord.shape.single { it.name == "enabled" }
        assertEquals("is_enabled", enabled.decodedName)
        assertEquals("is_enabled", enabled.payloadName)
    }

    @Test fun anEnumFieldPublishesItsCases() {
        val kind = PaymentMethodFlagRecord.shape.single { it.name == "kind" }
        assertEquals(listOf(FlagValueBox.String("CARD"), FlagValueBox.String("WALLET")), kind.cases)
    }

    @Test fun boxesAreKeyedByPropertyNameAndLeaveOutANullOptional() {
        assertEquals(
            mapOf(
                "name" to FlagValueBox.String("visa"),
                "kind" to FlagValueBox.String("CARD"),
                "enabled" to FlagValueBox.Bool(true),
                "minimumSpend" to FlagValueBox.Double(5.0),
            ),
            PaymentMethodFlagRecord.boxes(card),
        )
        assertTrue("minimumSpend" !in PaymentMethodFlagRecord.boxes(card.copy(minimumSpend = null)))
    }

    @Test fun aRecordIsRebuiltFromItsBoxes() {
        val boxes = PaymentMethodFlagRecord.boxes(card)
        assertEquals(card, PaymentMethodFlagRecord.fromBoxes(boxes))
        assertEquals(card.copy(minimumSpend = null), PaymentMethodFlagRecord.fromBoxes(boxes - "minimumSpend"))
    }

    @Test fun aMissingOrMistypedRequiredFieldRefusesTheRecord() {
        val boxes = PaymentMethodFlagRecord.boxes(card)
        assertNull(PaymentMethodFlagRecord.fromBoxes(boxes - "kind"))
        assertNull(PaymentMethodFlagRecord.fromBoxes(boxes + ("enabled" to FlagValueBox.Int(1))))
    }

    @Test fun recordsAreFoundByAKeyOfAnyTypeButInt() {
        val at = Instant.ofEpochSecond(1_767_225_600)
        val rollouts = FlagRecords(listOf(Rollout(at, 50)))
        assertEquals(50, rollouts[at]?.percent)
        assertNull(rollouts[Instant.EPOCH])
        val cards = FlagRecords(listOf(card))
        assertEquals(card, cards["visa"])
    }

    @Test fun anIntKeyedListStillIndexesByPosition() {
        // An Int key gets no `get`: List's own get(index) would always win, so records[1] stays positional.
        val picky = FlagRecords(listOf(Picky(id = 7), Picky(id = 9)))
        assertEquals(9, picky[1].id)
    }

    @Test fun aConstructorThatRejectsThePlaceholdersFailsNamingTheRecord() {
        val error = assertFailsWith<IllegalStateException> { PickyFlagRecord.shape }
        assertEquals(
            "Could not read the field defaults of 'Picky': its constructor threw when 'PickyFlagRecord' called it " +
                "with placeholder values (false, 0, \"\", empty, an enum's first case) for the fields that have no " +
                "default. Give those fields defaults the constructor accepts.",
            error.message,
        )
        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test fun aRecordThatRejectsItsPlaceholdersFailsWhenTheSchemaIsBuiltNotWhenItsSpecLoads() {
        val nothingStored = object : FlagLookup {
            override val keyEncoding: KeyEncoding = KeyEncoding.Kebabcase
            override fun box(key: FlagKey, type: FlagValueType): FlagValueBox? = null
        }
        assertEquals(FlagRecords(), PickyFlagsSpec.createFlags(nothingStored).pickies)
        val error = assertFailsWith<IllegalStateException> { FlagSchema.of(PickyFlagsSpec) }
        assertTrue("'Picky'" in error.message.orEmpty(), error.message)
    }
}
