package io.github.purplepalm.custommaps.nav.debug

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * Writes debug events as single-line JSON objects. Covers what events carry: strings, numbers,
 * booleans, null, enums, lists and maps; anything else is written as its string form. A time
 * ([Instant]) is written as [time] writes it. Numbers that JSON cannot represent (NaN, infinity)
 * become null.
 */
object JsonLines {

    /** UTC with exactly three digits of the second, such as 2000-01-01T00:00:00.000Z. */
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(MILLIS_DIGITS).toFormatter()

    /** [instant] as every time in the debug log is written: UTC, to the millisecond, always three digits. */
    fun time(instant: Instant): String = TIME_FORMAT.format(instant)

    /**
     * `{"t":"<time>","type":"<type>", ...fields}`. A field named "t" or "type" is written as
     * "field_t" or "field_type", so it cannot replace the event's own time or type.
     */
    fun event(time: Instant, type: String, fields: Map<String, Any?>): String {
        val out = StringBuilder(INITIAL_CAPACITY)
        out.append("{\"$TIME_KEY\":")
        appendString(out, time(time))
        out.append(",\"$TYPE_KEY\":")
        appendString(out, type)
        for ((key, value) in fields) {
            out.append(',')
            appendString(out, if (key == TIME_KEY || key == TYPE_KEY) "field_$key" else key)
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
            is Instant -> appendString(out, time(value))
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
    private const val MILLIS_DIGITS = 3
    private const val TIME_KEY = "t"
    private const val TYPE_KEY = "type"
}
