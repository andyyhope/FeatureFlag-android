package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two property names that differ only in an acronym's case collapse to one kebabcase key. */
class DuplicateKeyTest {
    private fun entry(path: String, description: String) = FlagSchema.Entry(
        key = FlagKey("use-https-only"),
        propertyPath = listOf(path),
        description = description,
        valueType = FlagValueType.Bool,
        defaultValue = FlagValueBox.Bool(false),
    )

    /** Built by hand, so a collision can be inspected rather than only crashed into. */
    private val colliding = FlagSchema(flags = listOf(entry("useHTTPSOnly", "A"), entry("useHttpsOnly", "B")))

    private val duplicateFree = specOf(
        flag("oneTap", "One", FlagCodecs.boolean, false),
        flag("useHTTPSOnly", "Two", FlagCodecs.boolean, false),
    )

    @Test fun aCollisionIsReportedWithEveryPropertyThatCausedIt() {
        assertEquals(
            mapOf(FlagKey("use-https-only") to listOf(listOf("useHTTPSOnly"), listOf("useHttpsOnly"))),
            colliding.duplicateKeys,
        )
    }

    @Test fun aHealthySchemaReportsNoDuplicates() {
        assertTrue(FlagSchema.of(duplicateFree).duplicateKeys.isEmpty())
        assertNull(FlagSchema.of(duplicateFree).duplicateKeyDescription)
    }

    @Test fun theReportNamesThePropertyPathsNotJustTheKey() {
        assertEquals(
            "'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly",
            colliding.duplicateKeyDescription,
        )
    }

    @Test fun valueTypesAndCasesDoNotTrapOnACollision() {
        assertEquals(FlagValueType.Bool, colliding.valueTypes[FlagKey("use-https-only")])
        assertTrue(colliding.valueCases.isEmpty())
        assertTrue(colliding.recordShapes.isEmpty())
    }

    @Test fun describingAContainerWithACollisionFailsAtOnce() {
        val spec = specOf(
            flag("useHTTPSOnly", "A", FlagCodecs.boolean, false),
            flag("useHttpsOnly", "B", FlagCodecs.boolean, false),
        )
        val error = assertFailsWith<IllegalStateException> { FlagSchema.of(spec) }
        assertEquals(
            "Flags must resolve to unique keys, but 'use-https-only' is claimed by useHTTPSOnly and useHttpsOnly. " +
                "Rename one of the properties, or use a KeyEncoding that tells them apart.",
            error.message,
        )
    }

    @Test fun aPublishedSchemaWithADuplicateKeyIsRejected() {
        val json = """
            {"formatVersion":1,"flags":[
              {"key":"a","valueType":"bool","defaultValue":false},
              {"key":"a","valueType":"bool","defaultValue":true}
            ]}
        """.trimIndent()
        val error = assertFailsWith<FlagSchemaException.Malformed> { FlagSchema.parse(json.toByteArray()) }
        assertEquals("The schema could not be read: more than one flag uses the key 'a'.", error.message)
    }

    @Test fun aPublishedSchemaWithoutDuplicatesStillDecodes() {
        assertEquals(2, FlagSchema.parse(FlagSchema.of(duplicateFree).jsonBytes()).flags.size)
    }
}
