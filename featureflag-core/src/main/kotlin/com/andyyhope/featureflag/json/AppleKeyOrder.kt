package com.andyyhope.featureflag.json

/**
 * The key order `JSONSerialization` uses for `.sortedKeys`: ICU root collation with numeric
 * ordering, case-insensitive, with a forced tie-break. It is independent of the device locale.
 *
 * For ASCII, keys compare element by element, where an element is one punctuation character,
 * one run of digits, or one letter:
 * - punctuation (including space) < digit runs < letters;
 * - punctuation follows ICU's root order, [PUNCTUATION];
 * - digit runs compare by numeric value, at any length;
 * - letters compare case-insensitively;
 * - a proper prefix sorts first.
 *
 * When every element is equal ignoring case and leading zeros, a second left-to-right pass
 * decides at the first element that differs: lowercase before uppercase, fewer leading zeros
 * before more.
 *
 * Deviation: any character outside printable ASCII is treated as a letter that sorts after
 * `z`, ordered by code point. Deterministic, but ICU orders such keys differently.
 */
internal object AppleKeyOrder : Comparator<String> {

    private const val PUNCTUATION = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$"

    private const val PUNCTUATION_CATEGORY = 0
    private const val DIGIT_CATEGORY = 1
    private const val LETTER_CATEGORY = 2

    override fun compare(a: String, b: String): Int {
        val left = elements(a)
        val right = elements(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val primary = primary(left[i], right[i])
            if (primary != 0) return primary
        }
        if (left.size != right.size) return left.size.compareTo(right.size)
        for (i in left.indices) {
            val tie = tieBreak(left[i], right[i])
            if (tie != 0) return tie
        }
        return 0
    }

    private fun elements(key: String): List<String> {
        val elements = mutableListOf<String>()
        var index = 0
        while (index < key.length) {
            val start = index
            if (key[index] in '0'..'9') {
                while (index < key.length && key[index] in '0'..'9') index++
            } else {
                index += Character.charCount(key.codePointAt(index))
            }
            elements += key.substring(start, index)
        }
        return elements
    }

    private fun category(element: String): Int {
        val first = element[0]
        return when {
            first in '0'..'9' -> DIGIT_CATEGORY
            first in 'a'..'z' || first in 'A'..'Z' -> LETTER_CATEGORY
            first in PUNCTUATION -> PUNCTUATION_CATEGORY
            else -> LETTER_CATEGORY
        }
    }

    private fun primary(a: String, b: String): Int {
        val categories = category(a).compareTo(category(b))
        if (categories != 0) return categories
        return when (category(a)) {
            PUNCTUATION_CATEGORY -> PUNCTUATION.indexOf(a[0]).compareTo(PUNCTUATION.indexOf(b[0]))
            DIGIT_CATEGORY -> compareNumerically(a.trimStart('0'), b.trimStart('0'))
            else -> letterWeight(a).compareTo(letterWeight(b))
        }
    }

    private fun compareNumerically(a: String, b: String): Int =
        if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

    /** ASCII letters by their lowercase form; anything else after `z`, by code point. */
    private fun letterWeight(element: String): Int {
        val codePoint = element.codePointAt(0)
        return when (codePoint.toChar()) {
            in 'a'..'z' -> codePoint
            in 'A'..'Z' -> codePoint + ('a' - 'A')
            else -> 'z'.code + 1 + codePoint
        }
    }

    private fun tieBreak(a: String, b: String): Int = when {
        a == b -> 0
        category(a) == DIGIT_CATEGORY -> a.length.compareTo(b.length)
        a[0].isLowerCase() && b[0].isUpperCase() -> -1
        a[0].isUpperCase() && b[0].isLowerCase() -> 1
        else -> a.compareTo(b)
    }
}
