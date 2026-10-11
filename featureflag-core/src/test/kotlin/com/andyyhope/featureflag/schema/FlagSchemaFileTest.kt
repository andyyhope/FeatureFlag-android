package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.errors.FlagSchemaException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    @Test fun aDirectoryAnotherWriterCreatedFirstIsNotAnError() {
        // mkdirs() returns false when someone else made the directory between the check and the call.
        val raced = object : File(directory, "raced") {
            override fun mkdirs(): Boolean {
                super.mkdirs()
                return false
            }
        }

        val schema = FlagSchema.of(demoSpec)

        val file = schema.write(raced)

        assertContentEquals(schema.jsonBytes(), file.readBytes())
        assertEquals(listOf(FlagSchema.FILE_NAME), raced.list()!!.toList())
    }

    @Test fun aDirectoryThatCannotBeMadeNamesTheTarget() {
        val blocker = File(directory, "not-a-directory").apply { writeText("x") }
        val target = File(File(blocker, "schema"), FlagSchema.FILE_NAME)

        val error = assertFailsWith<IOException> { FlagSchema.of(demoSpec).write(File(blocker, "schema")) }

        assertTrue(error.message.orEmpty().contains(target.path), "message should name the target: ${error.message}")
    }

    @Test fun aFailedReplacementNamesTheTargetKeepsTheCauseAndLeavesNoTemporaryFile() {
        // A directory squatting on the file name cannot be replaced by a file.
        val target = File(directory, FlagSchema.FILE_NAME)
        File(target, "occupied").apply { parentFile.mkdirs() }.writeText("x")

        val error = assertFailsWith<IOException> { FlagSchema.of(demoSpec).write(directory) }

        assertTrue(error.message.orEmpty().contains(target.path), "message should name the target: ${error.message}")
        assertNotNull(error.cause, "the underlying failure should be carried")
        assertEquals(listOf(FlagSchema.FILE_NAME), directory.list()!!.toList())
    }

    @Test fun concurrentWritersAllSucceedAndLeaveOneCompleteFile() {
        val schema = FlagSchema.of(demoSpec)
        val expected = schema.jsonBytes()
        val target = File(directory, "fresh/nested")
        val writers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writers)
        try {
            val results = (1..writers).map {
                pool.submit<Unit> {
                    start.await()
                    repeat(25) { schema.write(target) }
                }
            }
            start.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertContentEquals(expected, File(target, FlagSchema.FILE_NAME).readBytes())
        assertEquals(listOf(FlagSchema.FILE_NAME), target.list()!!.toList())
    }

    @Test fun readingReportsAMissingSchemaClearly() {
        val error = assertFailsWith<FlagSchemaException.NotPublished> { FlagSchema.read(directory) }
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            error.message,
        )
    }
}
