package io.github.purplepalm.custommaps.engine.mapbox.map

/** The app's own sources as the compiler reads them, for the tests that pin where a call is made. */
internal object SourceText {

    /**
     * Whether the function [declaration] of [text] has [statement] among the statements of its own body, outside every block and
     * condition in it: a line of the body, at the body's own indent, with nothing else on it but a comment.
     */
    fun hasStatement(text: String, declaration: String, statement: String): Boolean {
        val lines = code(text).lines()
        val at = lines.indexOfFirst { it.trim().startsWith(declaration) }
        require(at >= 0) { "No $declaration" }
        val indent = lines[at].takeWhile { it == ' ' }.length
        val body = " ".repeat(indent + 4)
        for (line in lines.drop(at + 1)) {
            // The function's closing brace, or whatever follows it.
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) return false
            if (line.trimEnd() == body + statement) return true
        }
        return false
    }

    /** [text] without its comments: what the compiler reads. */
    fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")
}
