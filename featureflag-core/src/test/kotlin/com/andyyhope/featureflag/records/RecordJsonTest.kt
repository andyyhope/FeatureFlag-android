package com.andyyhope.featureflag.records

import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagStringEnum
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import org.junit.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from FlagRecordTests, FlagRecordKeyTests, FlagRecordDefaultTests,
 * FlagRecordOptionalAndKeyTests and FlagNestedRecordTests: everything that reads or writes the
 * stored text. The cases that need a FlagPole, an import or a RemoteOverrideSource are ported
 * with those types in M1c and M1d.
 */
class RecordJsonTest {

    private enum class Tier(override val flagRawValue: String) : FlagStringEnum {
        PRIMARY("primary"),
        SECONDARY("secondary"),
    }

    private val tier = FlagCodecs.stringEnum(Tier.entries)

    /** FlagRecordTests' Endpoint: no key, no defaults. */
    private val endpointShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string),
        FlagRecordField.of("url", FlagCodecs.uri),
        FlagRecordField.of("enabled", FlagCodecs.boolean),
        FlagRecordField.of("weight", FlagCodecs.int),
        FlagRecordField.of("expires", FlagCodecs.instant),
        FlagRecordField.of("tier", tier),
    )

    private fun endpoint(name: String, enabled: Boolean, weight: Int, tier: Tier): Map<String, FlagValueBox> = mapOf(
        "name" to FlagValueBox.String(name),
        "url" to FlagValueBox.Url(URI("https://$name.example")),
        "enabled" to FlagValueBox.Bool(enabled),
        "weight" to FlagValueBox.Int(weight.toLong()),
        "expires" to FlagValueBox.Date(Instant.ofEpochSecond(1_700_000_000)),
        "tier" to FlagValueBox.String(tier.flagRawValue),
    )

    private val staging = endpoint("staging", enabled = false, weight = 7, tier = Tier.SECONDARY)
    private val canary = endpoint("canary", enabled = true, weight = 3, tier = Tier.SECONDARY)

    /** FlagRecordKeyTests' Endpoint: keyed by name. */
    private val keyedShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string, isKey = true),
        FlagRecordField.of("url", FlagCodecs.uri),
    )

    /** FlagRecordDefaultTests' Endpoint: weight and region have defaults. */
    private val defaultedShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string),
        FlagRecordField.of("url", FlagCodecs.uri),
        FlagRecordField.of("weight", FlagCodecs.int, default = 1),
        FlagRecordField.of("region", FlagCodecs.string, default = "au"),
    )

    /** FlagRecordOptionalAndKeyTests' PaymentMethod. */
    private val paymentShape = listOf(
        FlagRecordField.of("name", FlagCodecs.string, isKey = true),
        FlagRecordField.of("applePay", FlagCodecs.boolean, decodedName = "apple_pay"),
        FlagRecordField.of("minimumSpend", FlagCodecs.double, isOptional = true),
    )

    // Wire format

    @Test
    fun datesAndUrlsAreWrittenTheWayEveryOtherFlagWritesThem() {
        val json = RecordJson.encode(listOf(endpoint("prod", enabled = true, weight = 10, tier = Tier.PRIMARY)))
        assertTrue(json.contains("\"expires\":\"2023-11-14T22:13:20Z\""), json)
        assertTrue(json.contains("\"url\":\"https://prod.example\""), json)
    }

    @Test
    fun theStoredFormIsPlainJsonWithSortedKeys() {
        assertEquals(
            """[{"enabled":true,"expires":"2023-11-14T22:13:20Z","name":"canary","tier":"secondary",""" +
                """"url":"https://canary.example","weight":3}]""",
            RecordJson.encode(listOf(canary)),
        )
        val parsed = JsonReader.parse(RecordJson.encode(listOf(canary)).encodeToByteArray()) as JsonValue.Array
        assertEquals(JsonValue.Integer(3), (parsed.items.single() as JsonValue.Object).fields["weight"])
    }

    // Reading through the shape alone

    @Test
    fun storedTextCanBeReadBackFromTheShapeAlone() {
        val records = assertNotNull(RecordJson.decode(RecordJson.encode(listOf(staging, canary)), endpointShape))
        assertEquals(2, records.size)
        assertEquals(FlagValueBox.String("staging"), records.first()["name"])
        assertEquals(FlagValueBox.Int(7), records.first()["weight"])
        assertEquals(FlagValueBox.String("secondary"), records.last()["tier"])
    }

    @Test
    fun aListWithAnIncompleteRecordCannotBeRead() {
        assertNull(RecordJson.decode("""[{"name":"partial","enabled":true}]""", endpointShape))
    }

    @Test
    fun textThatIsNotARecordListCannotBeRead() {
        assertNull(RecordJson.decode("{ not json", endpointShape))
        assertNull(RecordJson.decode("total garbage", endpointShape))
        assertNull(RecordJson.decode("""{"name":"a"}""", endpointShape))
        assertNull(RecordJson.decode("""[1]""", endpointShape))
    }

    @Test
    fun aFieldOfTheWrongTypeFailsTheWholeList() {
        fun list(enabled: String) = """[{"name":"bad","url":"https://x.example","enabled":$enabled,""" +
            """"weight":1,"expires":"2023-11-14T22:13:20Z","tier":"primary"}]"""
        assertEquals(1, RecordJson.decode(list("true"), endpointShape)?.size)
        assertNull(RecordJson.decode(list("\"yes\""), endpointShape))
    }

    @Test
    fun anEmptyListIsAValueLikeAnyOther() {
        assertEquals("[]", RecordJson.encode(emptyList()))
        assertEquals(emptyList(), RecordJson.decode("[]", endpointShape))
    }

    // Numbers JSON cannot write

    @Test
    fun aNonFiniteFieldIsRefusedRatherThanWritten() {
        val records = listOf(
            mapOf("name" to FlagValueBox.String("fine"), "weight" to FlagValueBox.Int(1)),
            mapOf("name" to FlagValueBox.String("bad"), "ratio" to FlagValueBox.Double(Double.POSITIVE_INFINITY)),
        )
        assertFailsWith<FlagSerializationException.NonFiniteNumber> { RecordJson.encode(records) }
        assertFailsWith<FlagSerializationException.NonFiniteNumber> {
            RecordJson.encode(listOf(mapOf("ratio" to FlagValueBox.Float(Float.NaN))))
        }
    }

    @Test
    fun ordinaryNumbersAreNotMistakenForNonFiniteOnes() {
        val record = mapOf(
            "a" to FlagValueBox.Double(0.0),
            "b" to FlagValueBox.Float(-1.5f),
            "c" to FlagValueBox.Int(Long.MAX_VALUE),
        )
        val records = listOf(record)
        assertEquals("""[{"a":0,"b":-1.5,"c":9223372036854775807}]""", RecordJson.encode(records))
    }

    // Keys

    @Test
    fun aDuplicateKeyMakesTheListUnreadable() {
        val text = """[{"name":"a","url":"https://one.example"},{"name":"a","url":"https://two.example"}]"""
        assertNull(RecordJson.decode(text, keyedShape))
        assertEquals(FlagValueBox.String("a"), RecordJson.duplicateKey(text, keyedShape))
    }

    @Test
    fun distinctKeysAreFineAndARecordWithNoKeyAllowsIdenticalRecords() {
        val text = """[{"name":"a","url":"https://one.example"},{"name":"b","url":"https://two.example"}]"""
        assertEquals(2, RecordJson.decode(text, keyedShape)?.size)
        assertNull(RecordJson.duplicateKey(text, keyedShape))
        val noteShape = listOf(FlagRecordField.of("text", FlagCodecs.string))
        assertEquals(2, RecordJson.decode("""[{"text":"same"},{"text":"same"}]""", noteShape)?.size)
    }

    @Test
    fun keysCompareAsTheyAreStoredNotAsTheyAreHeld() {
        val shape = listOf(FlagRecordField.of("at", FlagCodecs.instant, isKey = true))
        val records = listOf(
            mapOf("at" to FlagValueBox.Date(Instant.ofEpochMilli(1_000_100))),
            mapOf("at" to FlagValueBox.Date(Instant.ofEpochMilli(1_000_900))),
        )
        assertNotNull(RecordJson.duplicateKey(records, shape))
    }

    @Test
    fun aFloatKeyThatOverflowsDoesNotCrashTheDuplicateCheck() {
        val shape = listOf(FlagRecordField.of("ratio", FlagCodecs.float, isKey = true))
        assertNull(RecordJson.decode("""[{"ratio":1e39},{"ratio":1e40}]""", shape))
        assertEquals(1, RecordJson.decode("""[{"ratio":1e39}]""", shape)?.size)
    }

    // Defaults

    @Test
    fun aRecordMissingADefaultedFieldIsFilledRatherThanRejected() {
        val text = """[{"name":"legacy","url":"https://legacy.example"}]"""
        val records = assertNotNull(RecordJson.decode(text, defaultedShape))
        assertEquals(FlagValueBox.Int(1), records.single()["weight"])
        assertEquals(FlagValueBox.String("au"), records.single()["region"])
    }

    @Test
    fun aRecordMissingAFieldWithNoDefaultIsStillRejected() {
        assertNull(RecordJson.decode("""[{"url":"https://nameless.example"}]""", defaultedShape))
    }

    @Test
    fun aFieldThatIsPresentButWrongIsRejectedEvenWithADefault() {
        assertNull(RecordJson.decode("""[{"name":"bad","url":"https://x.example","weight":"heavy"}]""", defaultedShape))
    }

    @Test
    fun anExplicitNullIsTreatedAsAbsent() {
        val records = RecordJson.decode("""[{"name":"n","url":"https://x.example","weight":null}]""", defaultedShape)
        assertEquals(FlagValueBox.Int(1), records?.single()?.get("weight"))
    }

    // Optional fields and custom keys

    @Test
    fun anAbsentOrNullOptionalFieldIsLeftOut() {
        val texts = listOf(
            """[{"name":"card","applePay":false}]""",
            """[{"name":"card","applePay":false,"minimumSpend":null}]""",
        )
        for (text in texts) {
            val record = RecordJson.decode(text, paymentShape)?.single()
            assertEquals(mapOf("name" to FlagValueBox.String("card"), "applePay" to FlagValueBox.Bool(false)), record)
        }
    }

    @Test
    fun aPresentOptionalFieldDecodesAndAWrongOneStillRejects() {
        val present = RecordJson.decode("""[{"name":"w","applePay":true,"minimumSpend":10.5}]""", paymentShape)
        assertEquals(FlagValueBox.Double(10.5), present?.single()?.get("minimumSpend"))
        assertNull(RecordJson.decode("""[{"name":"w","applePay":true,"minimumSpend":"lots"}]""", paymentShape))
    }

    @Test
    fun theStoredFormIsKeyedByPropertyNameAndOmitsNilOptionals() {
        val json = RecordJson.encode(
            listOf(mapOf("name" to FlagValueBox.String("wallet"), "applePay" to FlagValueBox.Bool(true))),
        )
        assertTrue(json.contains("applePay"))
        assertFalse(json.contains("apple_pay"))
        assertFalse(json.contains("minimumSpend"))
        val backendKey = """[{"name":"wallet","apple_pay":true}]"""
        assertNull(RecordJson.decode(backendKey, paymentShape), "the stored form is read by name")
    }

    // Nested lists

    @Test
    fun aNestedListIsWrittenAsAStringInsideItsParent() {
        val target = mapOf("host" to FlagValueBox.String("a"), "port" to FlagValueBox.Int(1))
        val targets = FlagValueBox.String(RecordJson.encode(listOf(target)))
        val json = RecordJson.encode(listOf(mapOf("name" to FlagValueBox.String("ap"), "targets" to targets)))
        assertEquals("""[{"name":"ap","targets":"[{\"host\":\"a\",\"port\":1}]"}]""", json)
    }

    @Test
    fun aNestedListCanBeReadFromItsFieldBoxWithTheShapeAlone() {
        val targetShape = listOf(
            FlagRecordField.of("host", FlagCodecs.string),
            FlagRecordField.of("port", FlagCodecs.int),
        )
        val clusterShape = listOf(
            FlagRecordField.of("name", FlagCodecs.string),
            FlagRecordField(name = "targets", type = FlagValueType.String, fields = targetShape),
        )
        val text = """[{"name":"ap","targets":"[{\"host\":\"a\",\"port\":1}]"}]"""
        val cluster = RecordJson.decode(text, clusterShape)?.single()
        val targets = (cluster?.get("targets") as FlagValueBox.String).value
        val nested = RecordJson.decode(targets, assertNotNull(clusterShape[1].fields))?.single()
        assertEquals(FlagValueBox.String("a"), nested?.get("host"))
        assertEquals(FlagValueBox.Int(1), nested?.get("port"))
    }
}
