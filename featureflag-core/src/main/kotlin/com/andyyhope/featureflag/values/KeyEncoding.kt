package com.andyyhope.featureflag.values

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
            KeyEncoding { name -> splitWords(name).joinToString(joiner) { it.lowercase() } }

        /**
         * Splits a camel-cased identifier into words, exactly as FeatureFlag 0.12.0 does.
         *
         * A word starts at an uppercase character that either follows a non-uppercase character
         * (`applePay` → `apple`, `Pay`) or ends a run of them (`useHTTPSOnly` → `use`, `HTTPS`,
         * `Only`). Digits stay attached to the word they trail (`checkoutV2` → `checkout`, `V2`).
         */
        internal fun splitWords(name: String): List<String> {
            val codePoints = name.codePoints().toArray()
            val words = mutableListOf<String>()
            val current = StringBuilder()
            for ((index, codePoint) in codePoints.withIndex()) {
                if (index > 0 && Character.isUpperCase(codePoint)) {
                    val previousIsUpper = Character.isUpperCase(codePoints[index - 1])
                    val nextIsLower = index + 1 < codePoints.size && Character.isLowerCase(codePoints[index + 1])
                    if (!previousIsUpper || nextIsLower) {
                        words += current.toString()
                        current.setLength(0)
                    }
                }
                current.appendCodePoint(codePoint)
            }
            if (current.isNotEmpty()) words += current.toString()
            return words
        }
    }
}
