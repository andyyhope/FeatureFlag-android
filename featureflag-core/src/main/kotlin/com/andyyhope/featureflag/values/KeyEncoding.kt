package com.andyyhope.featureflag.values

import java.text.BreakIterator
import java.util.Locale

/**
 * Turns a [FlagKeyPath] into the [FlagKey] a value source stores.
 *
 * Each path component is transformed individually, then joined with [separator], so nesting
 * always survives the transform intact.
 */
public class KeyEncoding(
    public val separator: String = ".",
    public val transform: (String) -> String,
) {

    public fun key(path: FlagKeyPath): FlagKey = FlagKey(path.propertyNames.joinToString(separator) { transform(it) })

    public companion object {

        /** `checkout.express.oneTap` becomes `checkout.express.one-tap`. The default. */
        public val Kebabcase: KeyEncoding = joinedWords("-")

        /** `checkout.express.oneTap` becomes `checkout.express.one_tap`. */
        public val Snakecase: KeyEncoding = joinedWords("_")

        /** Property names are used exactly as written. */
        public val Verbatim: KeyEncoding = KeyEncoding { it }

        private fun joinedWords(joiner: String): KeyEncoding =
            KeyEncoding { name -> splitWords(name).joinToString(joiner) { lowercased(it) } }

        /**
         * Splits a camel-cased identifier into words, exactly as FeatureFlag 0.12.0 does.
         *
         * A word starts at an uppercase character that either follows a non-uppercase character
         * (`applePay` → `apple`, `Pay`) or ends a run of them (`useHTTPSOnly` → `use`, `HTTPS`,
         * `Only`). Digits stay attached to the word they trail (`checkoutV2` → `checkout`, `V2`).
         *
         * A character is an extended grapheme cluster, as a Swift `Character` is, so a combining
         * mark or an emoji modifier never splits from its base (`ABE\u0301c` → `AB`, `E\u0301c`).
         * A cluster's case is Swift's `Character.isUppercase` and `isLowercase`: a single code
         * point with the Unicode property, or a cased cluster that the case mapping leaves as it
         * is (`E\u0301` is uppercase).
         */
        internal fun splitWords(name: String): List<String> {
            val characters = graphemeClusters(name)
            val words = mutableListOf<String>()
            val current = StringBuilder()
            for ((index, character) in characters.withIndex()) {
                if (index > 0 && isUppercase(character)) {
                    val previousIsUpper = isUppercase(characters[index - 1])
                    val nextIsLower = index + 1 < characters.size && isLowercase(characters[index + 1])
                    if (!previousIsUpper || nextIsLower) {
                        words += current.toString()
                        current.setLength(0)
                    }
                }
                current.append(character)
            }
            if (current.isNotEmpty()) words += current.toString()
            return words
        }

        private fun graphemeClusters(text: String): List<String> {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
            val clusters = mutableListOf<String>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                clusters += text.substring(start, end)
                start = end
                end = iterator.next()
            }
            return clusters
        }

        private fun isUppercase(character: String): Boolean =
            (isSingleCodePoint(character) && Character.isUpperCase(character.codePointAt(0))) ||
                (uppercased(character) == character && isCased(character))

        private fun isLowercase(character: String): Boolean =
            (isSingleCodePoint(character) && Character.isLowerCase(character.codePointAt(0))) ||
                (lowercased(character) == character && isCased(character))

        private fun isCased(character: String): Boolean {
            if (isSingleCodePoint(character)) {
                val codePoint = character.codePointAt(0)
                if (Character.isUpperCase(codePoint) || Character.isLowerCase(codePoint) || Character.isTitleCase(codePoint)) {
                    return true
                }
            }
            return uppercased(character) != character || lowercased(character) != character
        }

        private fun isSingleCodePoint(character: String): Boolean = character.codePointCount(0, character.length) == 1

        /**
         * Swift's `lowercased()` and `uppercased()` map each code point on its own, with no
         * context, so a word-final `Σ` lowercases to `σ`, never Java's final `ς`.
         */
        private fun lowercased(text: String): String = mapCodePoints(text) { it.lowercase(Locale.ROOT) }

        private fun uppercased(text: String): String = mapCodePoints(text) { it.uppercase(Locale.ROOT) }

        private fun mapCodePoints(text: String, transform: (String) -> String): String = buildString {
            text.codePoints().forEach { append(transform(String(Character.toChars(it)))) }
        }
    }
}
