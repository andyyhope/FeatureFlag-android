package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What a companion does with a document from an older or newer host. */
class FlagSchemaParseTest {
    private fun parse(json: String) = FlagSchema.parse(json.trimIndent().toByteArray())

    private fun malformed(json: String) = assertFailsWith<FlagSchemaException.Malformed> { parse(json) }.message

    @Test fun theSmallestDocumentFillsEveryGap() {
        val schema = parse("""{"formatVersion":1,"flags":[{"key":"one","valueType":"bool","defaultValue":false}]}""")
        val one = schema.flags.single()
        assertEquals(listOf("one"), one.propertyPath)
        assertEquals("", one.description)
        assertEquals(Instant.EPOCH, schema.generatedAt)
        assertNull(schema.applicationName)
        assertEquals(emptyList(), schema.groups)
    }

    @Test fun aDocumentFromBeforeDescriptionsExistedStillDecodes() {
        val schema = parse(
            """
            {
              "formatVersion": 1,
              "generatedAt": "2026-01-01T00:00:00.000Z",
              "flags": [
                { "key": "one", "propertyPath": ["one"], "description": "One",
                  "valueType": "bool", "defaultValue": false }
              ],
              "groups": []
            }
            """,
        )
        assertNull(schema.description)
        assertEquals(1, schema.flags.size)
    }

    @Test fun unknownFieldsAreIgnoredEverywhere() {
        val schema = parse(
            """
            {"formatVersion":1,"future":{"x":1},"flags":[{"key":"a","valueType":"bool","defaultValue":true,"hint":"new",
              "recordShape":[{"name":"n","type":"string","later":true}]}],
             "groups":[{"propertyPath":["g"],"description":"G","colour":"red"}]}
            """,
        )
        assertEquals(listOf(FlagRecordField("n", FlagValueType.String)), schema.flags.single().recordShape)
        assertEquals(listOf(FlagSchema.Group(listOf("g"), "G")), schema.groups)
    }

    @Test fun whatCannotBeDecodedInsideAFlagIsDropped() {
        val schema = parse(
            """
            {"formatVersion":1,"flags":[{"key":"t","valueType":"string","defaultValue":"a",
              "cases":["a", 2, "b"],
              "recordShape":[{"name":"ok","type":"int","default":"wrong"},{"name":"bad","type":"vector"},
                {"type":"int"}]}],
             "groups":[{"description":"no path"},{"propertyPath":["kept"]}]}
            """,
        )
        val flag = schema.flags.single()
        assertEquals(listOf(FlagValueBox.String("a"), FlagValueBox.String("b")), flag.cases)
        assertEquals(listOf(FlagRecordField("ok", FlagValueType.Int)), flag.recordShape)
        assertEquals(listOf(FlagSchema.Group(listOf("kept"), "")), schema.groups)
    }

    @Test fun aListHoldingSomethingOtherThanObjectsIsDroppedWhole() {
        val schema = parse(
            """
            {"formatVersion":1,"flags":[{"key":"t","valueType":"string","defaultValue":"a","propertyPath":["x", 1],
              "recordShape":[{"name":"ok","type":"int"}, 3]}],"groups":[{"propertyPath":["g"]}, "g"]}
            """,
        )
        assertEquals(listOf("t"), schema.flags.single().propertyPath)
        assertNull(schema.flags.single().recordShape)
        assertEquals(emptyList(), schema.groups)
    }

    @Test fun nestedRecordShapesAndFlagsRoundTrip() {
        val schema = parse(
            """
            {"formatVersion":1,"flags":[{"key":"e","valueType":"string","defaultValue":"[]","recordShape":[
              {"name":"name","type":"string","isKey":true},
              {"name":"note","type":"string","isOptional":true,"decodedName":"memo"},
              {"name":"targets","type":"string","default":"[]","fields":[{"name":"port","type":"int"}]}]}]}
            """,
        )
        val shape = schema.flags.single().recordShape!!
        assertEquals(true, shape[0].isKey)
        assertEquals(true, shape[1].isOptional)
        assertEquals("memo", shape[1].decodedName)
        assertEquals(listOf(FlagRecordField("port", FlagValueType.Int)), shape[2].fields)
        assertEquals(schema, FlagSchema.parse(schema.jsonBytes()))
    }

    @Test fun anUnknownValueTypeMakesTheDocumentMalformed() {
        assertEquals(
            "The schema could not be read: flag entry is missing a key or value type.",
            malformed("""{"formatVersion":1,"flags":[{"key":"a","valueType":"vector","defaultValue":[]}]}"""),
        )
        assertEquals(
            "The schema could not be read: flag entry is missing a key or value type.",
            malformed("""{"formatVersion":1,"flags":[{"valueType":"bool","defaultValue":true}]}"""),
        )
    }

    @Test fun aMistypedDefaultMakesTheDocumentMalformed() {
        assertEquals(
            "The schema could not be read: flag 'a' has a default that is not a bool.",
            malformed("""{"formatVersion":1,"flags":[{"key":"a","valueType":"bool","defaultValue":"yes"}]}"""),
        )
        assertEquals(
            "The schema could not be read: flag 'a' has a default that is not a int.",
            malformed("""{"formatVersion":1,"flags":[{"key":"a","valueType":"int"}]}"""),
        )
    }

    @Test fun theDocumentItselfMustBeAnObjectWithAVersionAndFlags() {
        assertEquals("The schema could not be read: not a JSON object.", malformed("""[1, 2]"""))
        assertEquals("The schema could not be read: not a JSON object.", malformed("""{"formatVersion":"""))
        assertEquals("The schema could not be read: missing formatVersion.", malformed("""{"flags":[]}"""))
        assertEquals(
            "The schema could not be read: missing formatVersion.",
            malformed("""{"formatVersion":"1","flags":[]}"""),
        )
        assertEquals("The schema could not be read: missing flags.", malformed("""{"formatVersion":1}"""))
        assertEquals("The schema could not be read: missing flags.", malformed("""{"formatVersion":1,"flags":[1]}"""))
    }

    @Test fun anEmptyFlagListIsAValidSchema() {
        assertEquals(emptyList(), parse("""{"formatVersion":1,"flags":[]}""").flags)
        val one = parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"bool","defaultValue":true}]}""")
        assertEquals(FlagKey("a"), one.flags.single().key)
    }
}
