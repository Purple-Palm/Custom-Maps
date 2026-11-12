package io.github.purplepalm.custommaps.ui.theme

import java.io.File

/**
 * The app's Kotlin sources, for tests that hold a rule no compiler checks. Unit tests run with the
 * module directory as the working directory; the sources are inputs of the test task, so a change
 * to one runs these tests again.
 */
internal object Sources {

    /** The app's own package, under the module's main sources. */
    val root = File("src/main/kotlin/io/github/purplepalm/custommaps")

    /** The text of [path], under [root], with its comments taken out: what the compiler reads. */
    fun code(path: String): String = withoutComments(File(root, path).readText())

    /** Every Kotlin file under [dir] of [root] (all of them for ""), by its path from [root]. */
    fun filesUnder(dir: String): Map<String, File> =
        File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }
            .toSortedMap()

    /** What lies between the braces of the function whose declaration starts with [declaration]. */
    fun functionBody(code: String, declaration: String): String {
        val start = code.indexOf(declaration)
        require(start >= 0) { "no $declaration" }
        // Past the parameters, which may hold parentheses of their own, to the body's brace.
        var depth = 0
        var i = code.indexOf('(', start)
        while (true) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            i++
        }
        val open = code.indexOf('{', i)
        depth = 0
        for (end in open until code.length) {
            when (code[end]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open + 1, end)
            }
        }
        error("$declaration has no end")
    }

    private fun withoutComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")
}
