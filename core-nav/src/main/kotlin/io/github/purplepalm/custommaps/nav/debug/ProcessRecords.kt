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
 * The file holds one [ExitKey] per line: of the records Android listed when the file was last
 * written, the ones in the log by then, and before them the last few it had stopped listing. Those
 * few stay because they can come back: Android saves its records only about every half hour and not
 * when the phone shuts down, so after a reboot it lists again the ones it dropped since its last
 * save. A new list is written to a temporary file next to it and renamed over it, so a write cut
 * short by the process dying leaves the previous list in place. A missing or unreadable file counts
 * as nothing logged yet: every record Android still holds is logged once more.
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
     * the records that are in the log. [write] returns true once its record has been handed to the
     * operating system: a record is never marked as logged before that, so one lost with a process
     * that dies right after this comes up again at the next start. A power loss can still take the
     * line and keep the mark: the keys are forced to storage, the log line is not. The first false
     * stops the writing, and the records left come up again too. Returns how many were written.
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
     * Stores [keys], the records Android lists now that are in the log, after the keys stored so
     * far. Of the stored keys Android no longer lists, the last [DROPPED_KEYS_KEPT] stay, by their
     * place in the file and not by their time, which a wrong clock would upset; a key Android still
     * lists is never cut. Nothing is written when every key is stored already, as when [keys] is
     * empty: right after a reboot Android lists no records until it has read its own back from
     * storage.
     */
    fun markLogged(keys: Collection<ExitKey>) {
        val stored = read()
        val listed = keys.toSet()
        if (stored.containsAll(listed)) return
        val logged = stored.filterNot { it in listed }.takeLast(DROPPED_KEYS_KEPT) + listed
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

    /**
     * The stored keys in the order of the file. Lines that are not keys are skipped: their records
     * are logged once more.
     */
    private fun read(): Set<ExitKey> =
        try {
            if (file.exists()) file.readLines().mapNotNullTo(LinkedHashSet()) { ExitKey.parse(it.trim()) } else emptySet()
        } catch (e: IOException) {
            emptySet()
        }

    private companion object {
        const val TEMPORARY_SUFFIX = ".new"

        /**
         * How many keys of records Android no longer lists are kept. Only the ones it dropped since
         * its last save can come back; this is three times the 16 records it keeps per app.
         */
        const val DROPPED_KEYS_KEPT = 48
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
