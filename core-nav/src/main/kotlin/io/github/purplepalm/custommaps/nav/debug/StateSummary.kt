package io.github.purplepalm.custommaps.nav.debug

/**
 * The few bytes Android keeps with a running process and hands back in the record of how it ended
 * (`ActivityManager.setProcessStateSummary`), so the next start can tell what the app was doing
 * when it died: `key=value;key=value` in UTF-8, at most [MAX_BYTES]. Android refuses anything
 * longer, so a longer summary is cut, on a character boundary, rather than lost.
 */
object StateSummary {

    /** Android's limit for the summary. */
    const val MAX_BYTES = 128

    /** [fields] in their order; null values are left out. Never longer than [MAX_BYTES]. */
    fun encode(fields: Map<String, Any?>): ByteArray {
        val text = fields.entries
            .filter { it.value != null }
            .joinToString(SEPARATOR) { (key, value) -> "$key=$value" }
        return utf8Prefix(text, MAX_BYTES)
    }

    /** The summary as text, as [encode] wrote it. */
    fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** The longest start of [text] whose UTF-8 form fits in [maxBytes], without splitting a character. */
    internal fun utf8Prefix(text: String, maxBytes: Int): ByteArray {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end).toByteArray(Charsets.UTF_8)
    }

    private const val SEPARATOR = ";"
}
