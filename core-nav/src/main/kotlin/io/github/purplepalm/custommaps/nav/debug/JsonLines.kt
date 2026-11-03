package io.github.purplepalm.custommaps.nav.debug

import java.time.Instant

/**
 * Writes debug events as single-line JSON objects. Covers what events carry: strings, numbers,
 * booleans, null, enums, lists and maps; anything else is written as its string form.
 * Numbers that JSON cannot represent (NaN, infinity) become null.
 */
object JsonLines {

    /** `{"t":"<time>","type":"<type>", ...fields}` */
    fun event(time: Instant, type: String, fields: Map<String, Any?>): String {
        val out = StringBuilder(INITIAL_CAPACITY)
        out.append("{\"t\":")
        appendString(out, time.toString())
        out.append(",\"type\":")
        appendString(out, type)
        for ((key, value) in fields) {
            out.append(',')
            appendString(out, key)
            out.append(':')
            appendValue(out, value)
        }
        return out.append('}').toString()
    }

    fun encode(value: Any?): String = StringBuilder().also { appendValue(it, value) }.toString()

    private fun appendValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Double -> if (value.isFinite()) out.append(value) else out.append("null")
            is Float -> if (value.isFinite()) out.append(value) else out.append("null")
            is Number -> appendValue(out, value.toDouble())
            is Enum<*> -> appendString(out, value.name)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    appendString(out, key.toString())
                    out.append(':')
                    appendValue(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> appendArray(out, value)
            is Array<*> -> appendArray(out, value.asIterable())
            else -> appendString(out, value.toString())
        }
    }

    private fun appendArray(out: StringBuilder, items: Iterable<*>) {
        out.append('[')
        var first = true
        for (item in items) {
            if (!first) out.append(',')
            first = false
            appendValue(out, item)
        }
        out.append(']')
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (char < ' ') {
                        out.append("\\u").append(Integer.toHexString(char.code).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
            }
        }
        out.append('"')
    }

    private const val INITIAL_CAPACITY = 256
}
