package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.values.FlagKey

/**
 * Something wrong with one value in a remote payload.
 *
 * `toString()` is the line a rejection report shows for it. Switch on [kind]; [expected]
 * and [found] are there for the message.
 */
public data class RemoteOverrideProblem(
    public val key: FlagKey,
    public val remoteKey: String,
    public val kind: Kind,
    /** What the flag would have accepted: a type name, or an enum's cases. */
    public val expected: String? = null,
    /** What the payload actually held, short enough to read in one line. */
    public val found: String? = null,
) {

    public enum class Kind {
        /** The value is not of the flag's declared type. */
        TypeMismatch,

        /** The value is not one of the enum's cases. */
        UnknownCase,

        /**
         * The mapper produced a key no flag has. `DotPathMapper` cannot cause this, so it
         * points at a custom mapper's bug rather than at anything the backend sent.
         */
        UnknownKey,
    }

    override fun toString(): String = when (kind) {
        Kind.TypeMismatch ->
            "'$remoteKey' → $key: expected ${expected ?: "another type"}" + (found?.let { ", got $it" } ?: "")
        Kind.UnknownCase ->
            "'$remoteKey' → $key: ${found ?: "that value"} is not one of " + (expected ?: "this flag's cases")
        Kind.UnknownKey ->
            "$key: no flag has this key, so the mapper that produced it is wrong"
    }
}

/** Why a remote payload was not applied. Nothing is ever half-applied. */
public sealed class RemoteOverrideException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /** The bytes could not be read as a payload; [reason] says why. */
    public class Malformed(public val reason: String, cause: Throwable? = null) :
        RemoteOverrideException("The remote payload could not be read: $reason.", cause)

    /** Every problem found, and nothing applied. */
    public class Rejected(public val problems: List<RemoteOverrideProblem>) :
        RemoteOverrideException(rejectionMessage(problems))
}

private fun rejectionMessage(problems: List<RemoteOverrideProblem>): String {
    if (problems.isEmpty()) return "Nothing was applied, and no problem was recorded."
    val noun = if (problems.size == 1) "problem" else "problems"
    val list = problems.joinToString("\n") { "  • $it" }
    return "Nothing was applied. ${problems.size} $noun:\n$list\n" +
        "A remote payload is all or nothing, so one bad value leaves every other " +
        "flag reading whatever sits below this source."
}
