package docs.sync

/** A unified-style line diff, small enough to print in a test failure. */
object LineDiff {

    /** Lines only in [expected] start with `-`, lines only in [actual] with `+`, shared lines with a space. */
    fun unified(expected: String, actual: String, expectedLabel: String, actualLabel: String): String {
        val a = expected.split("\n")
        val b = actual.split("\n")
        // common[i][j]: the length of the longest common subsequence of a[i..] and b[j..].
        val common = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                common[i][j] = if (a[i] == b[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
            }
        }
        val lines = mutableListOf("--- $expectedLabel", "+++ $actualLabel")
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> {
                    lines += " ${a[i]}"
                    i += 1
                    j += 1
                }
                i < a.size && (j == b.size || common[i + 1][j] >= common[i][j + 1]) -> {
                    lines += "-${a[i]}"
                    i += 1
                }
                else -> {
                    lines += "+${b[j]}"
                    j += 1
                }
            }
        }
        return lines.joinToString("\n")
    }
}
