package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlagSchemaFileTest {
    private val directory: File = Files.createTempDirectory("flag-schema").toFile()

    @AfterTest fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test fun writingPublishesASchemaTheCompanionCanRead() {
        val schema = FlagSchema.of(demoSpec)
        val file = schema.write(directory)
        assertEquals(File(directory, "flag-schema.json"), file)
        assertContentEquals(schema.jsonBytes(), file.readBytes())
        assertEquals(schema.flags.map { it.key }, FlagSchema.read(directory).flags.map { it.key })
    }

    @Test fun writingCreatesAMissingDirectory() {
        val nested = File(directory, "a/b")
        FlagSchema.of(demoSpec).write(nested)
        assertTrue(File(nested, FlagSchema.FILE_NAME).isFile)
    }

    @Test fun writingAgainReplacesTheEarlierSchemaAndLeavesNoTemporaryFile() {
        FlagSchema.of(specOf()).write(directory)
        FlagSchema.of(demoSpec).write(directory)
        assertEquals(5, FlagSchema.read(directory).flags.size)
        assertEquals(listOf(FlagSchema.FILE_NAME), directory.list()!!.toList())
    }

    @Test fun readingReportsAMissingSchemaClearly() {
        val error = assertFailsWith<FlagSchemaException.NotPublished> { FlagSchema.read(directory) }
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            error.message,
        )
    }
}
