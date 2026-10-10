package com.andyyhope.featureflag.transport

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.golden.GoldenFlags
import com.andyyhope.featureflag.golden.GoldenFlagsSpec
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.temporal.ChronoUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishSchemaTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun pole(): FlagPole<GoldenFlags> = FlagPole(GoldenFlagsSpec, emptyList(), applicationName = "Golden")

    private fun parse(json: String): FlagSchema = FlagSchema.parse(json.encodeToByteArray())

    // Publishing

    @Test
    fun publishingWritesTheSchemaBytesWhereReadFindsThem() {
        val pole = pole()
        val directory = File(temporary.root, "nested/deeper")

        val file = pole.publishSchema(directory)

        assertEquals(File(directory, FlagSchema.FILE_NAME), file)
        assertContentEquals(pole.schema.jsonBytes(), file.readBytes())

        // The file carries the date to the second; everything else round-trips exactly.
        val read = FlagSchema.read(directory)
        assertEquals(pole.schema.generatedAt.truncatedTo(ChronoUnit.SECONDS), read.generatedAt)
        assertEquals(pole.schema, read.copy(generatedAt = pole.schema.generatedAt))
    }

    @Test
    fun publishingAgainReplacesTheFileAndLeavesNothingBehind() {
        val directory = temporary.newFolder("schema")
        pole().publishSchema(directory)
        val snake = FlagPole(GoldenFlagsSpec, emptyList(), KeyEncoding.Snakecase, "Golden")

        snake.publishSchema(directory)

        assertEquals(listOf(FlagSchema.FILE_NAME), directory.list().orEmpty().sorted())
        assertContentEquals(snake.schema.jsonBytes(), File(directory, FlagSchema.FILE_NAME).readBytes())
    }

    @Test
    fun publishingWhereNoDirectoryCanBeMadeThrows() {
        val blocker = temporary.newFile("not-a-directory")

        assertFails { pole().publishSchema(File(blocker, "schema")) }
    }

    @Test
    fun aPublishedSchemaDoesNotEscapeSlashes() {
        val text = pole().publishSchema(temporary.newFolder("slashes")).readText()

        assertTrue(text.contains("https://example.com/a/b?c=d"), text)
        assertFalse(text.contains("\\/"), "slashes should not be escaped")
    }

    @Test
    fun readingADirectoryWithNoSchemaSaysNothingWasPublished() {
        // Replaces Swift's two unknown-App-Group tests: Android has no App Group.
        assertFailsWith<FlagSchemaException.NotPublished> { FlagSchema.read(temporary.newFolder("empty")) }
    }

    // Malformed schemas (TransportEdgeCaseTests)

    @Test
    fun aSchemaMissingItsVersionIsRejected() {
        assertFailsWith<FlagSchemaException.Malformed> { parse("""{"flags": []}""") }
    }

    @Test
    fun aSchemaMissingItsFlagsIsRejected() {
        assertFailsWith<FlagSchemaException.Malformed> { parse("""{"formatVersion": 1}""") }
    }

    @Test
    fun aSchemaThatIsNotAnObjectIsRejected() {
        assertFailsWith<FlagSchemaException> { parse("[]") }
        assertFailsWith<FlagSchemaException> { parse("garbage") }
    }

    @Test
    fun anEntryWithAnUnknownTypeNameIsRejected() {
        assertFailsWith<FlagSchemaException> {
            parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"banana","defaultValue":1}]}""")
        }
    }

    @Test
    fun anEntryWhoseDefaultDoesNotMatchItsTypeIsRejected() {
        val error = assertFailsWith<FlagSchemaException.Malformed> {
            parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"int","defaultValue":"lots"}]}""")
        }

        assertTrue(error.message.orEmpty().contains("'a'"), "message should name the flag: ${error.message}")
    }

    @Test
    fun aSchemaWithNoGroupsDecodesFine() {
        val schema = parse("""{"formatVersion":1,"flags":[{"key":"a","valueType":"int","defaultValue":1}]}""")

        assertTrue(schema.groups.isEmpty())
        assertEquals(listOf("a"), schema.flags.first().propertyPath)
    }

    @Test
    fun anEmptyContainerProducesAnEmptySchema() {
        val schema = FlagSchema.of(EmptySpec)

        assertTrue(schema.flags.isEmpty())
        assertTrue(schema.groups.isEmpty())
    }

    /** A container with no flags. Swift's `EmptyFlags {}`, written by hand so no KSP edge case is involved. */
    private object EmptySpec : FlagContainerSpec<Any> {
        override val flagContainerDescription: String? = null
        override val flagDescriptors: List<FlagSchemaNode> = emptyList()
        override val flagDefaults: Any = Any()

        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Any()
    }
}
