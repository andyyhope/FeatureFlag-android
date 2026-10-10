package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.Fixtures
import com.andyyhope.featureflag.errors.FlagSchemaException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Messages ported verbatim from ErrorMessages.swift, pinned against the text Swift itself produced. */
class FlagSchemaExceptionTest {
    @Test fun anUnsupportedVersionSaysWhichAppToUpdate() = assertEquals(
        Fixtures.text("messages/FlagSchemaError.unsupportedFormatVersion.txt"),
        FlagSchemaException.UnsupportedFormatVersion(9).message,
    )

    @Test fun aMalformedSchemaSaysWhy() = assertEquals(
        Fixtures.text("messages/FlagSchemaError.malformed.txt"),
        FlagSchemaException.Malformed("missing formatVersion").message,
    )

    @Test fun notPublishedDropsOnlyTheAppGroupClause() {
        val swift = Fixtures.text("messages/FlagSchemaError.notPublished.txt")
        val kotlin = FlagSchemaException.NotPublished().message.orEmpty()
        assertEquals(
            "No schema has been published. The host app writes one with publishSchema(), so run it at least once.",
            kotlin,
        )
        assertTrue("App Group" in swift, swift)
        assertEquals(swift.substringBefore(" — and check"), kotlin.removeSuffix("."))
    }
}
