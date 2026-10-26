package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.transport.FlagPayload
import com.andyyhope.featureflag.values.FlagKey

/**
 * Something wrong with one key in an incoming document.
 *
 * @property expected what the flag would have accepted: a type name, or an enum's cases.
 *   It is there for the message; switch on [kind].
 * @property found what the document held, short enough to read on one line.
 */
public data class FlagImportProblem(
    public val key: FlagKey,
    public val kind: Kind,
    public val expected: String? = null,
    public val found: String? = null,
) {

    public enum class Kind {
        /** No flag in this app has that key. */
        UnknownKey,

        /** The value is not of the flag's declared type, or is text that is not a readable record list. */
        TypeMismatch,

        /** The value is not one of the enum's cases. */
        UnknownCase,
    }

    override fun toString(): String = when (kind) {
        Kind.TypeMismatch -> "$key: expected ${expected ?: "another type"}" + (found?.let { ", got $it" } ?: "")
        Kind.UnknownCase -> "$key: ${found ?: "that value"} is not one of " + (expected ?: "this flag's cases")
        Kind.UnknownKey ->
            "$key: no flag has this key. The document came from a different app, " +
                "or from a build where the flag was named differently"
    }
}

/** Why a document or a scanned code was not applied. Nothing is applied when this is thrown. */
public sealed class FlagImportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    public class Malformed(public val reason: String, cause: Throwable? = null) :
        FlagImportException("The document could not be read: $reason.", cause)

    public class UnsupportedFormatVersion(public val version: Long) : FlagImportException(
        "The document is format version $version; this build understands " +
            "${FlagPayload.CURRENT_FORMAT_VERSION}. Update whichever app is older.",
    )

    /** Every problem found, sorted by key. Import is all or nothing, so an app never runs on half a configuration. */
    public class Rejected(
        public val problems: List<FlagImportProblem>,
    ) : FlagImportException(rejectionMessage(problems))
}

private fun rejectionMessage(problems: List<FlagImportProblem>): String {
    if (problems.isEmpty()) return "Nothing was applied, and no problem was recorded."
    val count = "${problems.size} problem${if (problems.size == 1) "" else "s"}"
    val list = problems.joinToString("\n") { "  • $it" }
    return "Nothing was applied. $count:\n$list\nImport is all or nothing, so no flag was changed."
}
