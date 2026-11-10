package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Identifies one of Android's records of how a process ended: the process's pid and the wall-clock
 * time it died. Neither alone is enough: pids come back, and the phone's clock can move back, so a
 * record that is new can carry an older time than one already logged.
 */
data class ExitKey(val pid: Int, val timeMillis: Long) {

    /** One line of the [LoggedExits] file. */
    override fun toString(): String = "$pid$SEPARATOR$timeMillis"

    companion object {
        private const val SEPARATOR = ':'

        /** The key written by [toString], or null for any other text. */
        fun parse(text: String): ExitKey? {
            val pid = text.substringBefore(SEPARATOR, "").toIntOrNull() ?: return null
            val time = text.substringAfter(SEPARATOR, "").toLongOrNull() ?: return null
            return ExitKey(pid, time)
        }
    }
}

/**
 * Remembers, in [file], which of the records Android keeps across many starts and app updates (how
 * earlier processes ended) are already in the debug log, so that each is logged once.
 *
 * The file holds the [ExitKey] of every record Android still held at the last start, one per line;
 * Android keeps only a few records per app, so the file stays small. A new list is written to a
 * temporary file next to it and renamed over it, so a write cut short by the process dying leaves
 * the previous list in place. A missing or unreadable file counts as nothing logged yet: every record Android
 * still holds is logged once more.
 */
class LoggedExits(private val file: File) {

    private val temporary = File(file.path + TEMPORARY_SUFFIX)

    /** The records not in the log yet, oldest first. */
    fun <T> unseen(records: List<T>, keyOf: (T) -> ExitKey): List<T> {
        val logged = read()
        return records.filter { keyOf(it) !in logged }.sortedBy { keyOf(it).timeMillis }
    }

    /**
     * Hands each of [records] not in the log yet to [write], oldest first, then stores the keys of
     * the records that are in the log. [write] returns true once its record is on disk: a record is
     * never marked as logged before that, so one lost with a process that dies right after this
     * comes up again at the next start. The first false stops the writing, and the records left come
     * up again too. Returns how many were written.
     */
    fun <T> logNew(records: List<T>, keyOf: (T) -> ExitKey, write: (T) -> Boolean): Int {
        val unseen = unseen(records, keyOf)
        var written = 0
        while (written < unseen.size && write(unseen[written])) written += 1
        val left = unseen.subList(written, unseen.size).mapTo(HashSet(), keyOf)
        markLogged(records.map(keyOf).filterNot { it in left })
        return written
    }

    /**
     * Stores [keys] as the records that are in the log, in place of the ones stored so far: records
     * Android no longer holds never come back, so their keys can go. Nothing is written when the
     * same keys are stored already, or when [keys] is empty: right after a reboot Android lists no
     * records until it has read its own back from storage.
     */
    fun markLogged(keys: Collection<ExitKey>) {
        val logged = keys.toSet()
        if (logged.isEmpty() || logged == read()) return
        val text = logged.joinToString(separator = "") { "$it\n" }
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray(Charsets.US_ASCII))
                // On disk before the rename, so the renamed file is never empty after a power loss.
                out.fd.sync()
            }
            if (!temporary.renameTo(file)) file.writeText(text)
        } catch (e: IOException) {
            // The same records come up again at the next start; nothing worse.
        }
    }

    /** Lines that are not keys are skipped: their records are logged once more. */
    private fun read(): Set<ExitKey> =
        try {
            if (file.exists()) file.readLines().mapNotNullTo(HashSet()) { ExitKey.parse(it.trim()) } else emptySet()
        } catch (e: IOException) {
            emptySet()
        }

    private companion object {
        const val TEMPORARY_SUFFIX = ".new"
    }
}

/**
 * The parts of an ANR trace worth keeping in the debug log, from the text Android hands back for an
 * ANR exit (the traces.txt format, every thread of the process, often hundreds of kilobytes): the
 * "Subject:" line, which says what timed out, and the main thread's stack.
 */
object AnrTrace {

    const val DEFAULT_STACK_LINES = 40
    private const val MAX_NATIVE_FRAMES = 8
    private const val SUBJECT = "Subject:"
    private const val MAIN_THREAD = "\"main\""

    fun summarize(lines: Sequence<String>, maxStackLines: Int = DEFAULT_STACK_LINES): Map<String, Any?> {
        var subject: String? = null
        val stack = ArrayList<String>()
        var inMain = false
        var nativeFrames = 0
        for (raw in lines) {
            val line = raw.trim()
            if (!inMain) {
                if (subject == null && line.startsWith(SUBJECT)) subject = line.removePrefix(SUBJECT).trim()
                if (line.startsWith(MAIN_THREAD)) {
                    inMain = true
                    stack.add(line)
                }
                continue
            }
            // The main thread's block ends at the first blank line.
            if (line.isEmpty()) break
            // "| state=..., sysTid=..." lines describe the thread, not where it is stuck.
            if (line.startsWith("|")) continue
            // Native frames come first and run long; a few say enough.
            if (line.startsWith("native:") && ++nativeFrames > MAX_NATIVE_FRAMES) continue
            stack.add(line)
            if (stack.size >= maxStackLines) break
        }
        return mapOf("subject" to subject, "mainThread" to stack)
    }
}
