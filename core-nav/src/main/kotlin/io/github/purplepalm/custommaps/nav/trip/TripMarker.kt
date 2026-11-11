package io.github.purplepalm.custommaps.nav.trip

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A small file that is there while a trip runs, with what the trip had added up when it was last
 * written ([TripTally.fields]). The trip's end deletes it. Found at the next start of the app, it
 * says that the process ended during the trip, and what that trip was up to then.
 *
 * One line for each field, `name=value`, numbers, booleans and strings without line breaks; a map
 * of counts is written as `name.key=value`. A new content is written to a file next to it and
 * renamed over it, so a write cut short by the process dying leaves the content before.
 */
class TripMarker(private val file: File) {

    private val temporary = File(file.path + TEMPORARY_SUFFIX)

    /** Writes [fields] in place of what the marker held; false when the storage refused. */
    fun write(fields: Map<String, Any?>): Boolean =
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temporary).use { it.write(encode(fields).toByteArray(Charsets.UTF_8)) }
            temporary.renameTo(file) || run {
                file.writeText(encode(fields))
                true
            }
        } catch (e: IOException) {
            false
        }

    /** What the marker holds; null when there is none or it cannot be read. */
    fun read(): Map<String, Any?>? =
        try {
            if (file.exists()) decode(file.readText()) else null
        } catch (e: IOException) {
            null
        }

    /** Deletes the marker. */
    fun clear() {
        temporary.delete()
        file.delete()
    }

    companion object {
        private const val TEMPORARY_SUFFIX = ".new"
        private const val NESTED = '.'

        /** The marker's text for [fields]; null values are left out, and so are line breaks in strings. */
        fun encode(fields: Map<String, Any?>): String = buildString {
            for ((name, value) in fields) {
                when (value) {
                    null -> Unit
                    is Map<*, *> -> for ((key, item) in value) {
                        if (item != null) append(name).append(NESTED).append(key).append('=').append(clean(item)).append('\n')
                    }
                    else -> append(name).append('=').append(clean(value)).append('\n')
                }
            }
        }

        /**
         * The fields of a marker's text: true and false as booleans, whole numbers as Long, other
         * numbers as Double, anything else as a string; `name.key` lines become one map under
         * `name`. Lines it cannot read are skipped.
         */
        fun decode(text: String): Map<String, Any?> {
            val fields = LinkedHashMap<String, Any?>()
            for (line in text.lineSequence()) {
                val name = line.substringBefore('=', "")
                if (name.isEmpty()) continue
                val value = parse(line.substringAfter('='))
                val dot = name.indexOf(NESTED)
                if (dot > 0) {
                    @Suppress("UNCHECKED_CAST")
                    val map = fields.getOrPut(name.substring(0, dot)) { LinkedHashMap<String, Any?>() } as? MutableMap<String, Any?> ?: continue
                    map[name.substring(dot + 1)] = value
                } else {
                    fields[name] = value
                }
            }
            return fields
        }

        private fun clean(value: Any): String = value.toString().replace('\n', ' ').replace('\r', ' ')

        private fun parse(text: String): Any = when (text) {
            "true" -> true
            "false" -> false
            else -> text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: text
        }
    }
}
