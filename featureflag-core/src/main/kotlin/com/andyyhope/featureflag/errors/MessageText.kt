package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.json.IsoDate
import java.time.Instant

/**
 * The value-rendering helpers behind every error message, ported from `ErrorMessages.swift`.
 *
 * Each problem is a bullet, so a raw newline in a value would put the rest of it in column zero
 * where it reads as another bullet. Length is capped for the same reason.
 */
internal object MessageText {

    private const val MAX_QUOTED_LENGTH = 40

    /**
     * A value as one short, single-line, quoted fragment: `\`, newline, carriage return and tab
     * are escaped, and anything over 40 characters is cut to 40 plus `…`.
     *
     * Swift counts grapheme clusters; this counts code points, so a value whose 40th character
     * is part of a combining sequence or an emoji ZWJ sequence is cut at a different place.
     */
    fun quoted(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        if (escaped.codePointCount(0, escaped.length) <= MAX_QUOTED_LENGTH) return "\"$escaped\""
        return "\"${escaped.substring(0, escaped.offsetByCodePoints(0, MAX_QUOTED_LENGTH))}…\""
    }

    fun arrayOf(count: Int): String = "an array of $count"

    fun objectWith(count: Int): String = "an object with $count field(s)"

    fun date(instant: Instant): String = IsoDate.format(instant)
}
