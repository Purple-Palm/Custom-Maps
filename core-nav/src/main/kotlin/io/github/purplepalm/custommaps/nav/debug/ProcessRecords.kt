package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Remembers, in [file], the time of the newest record already written to the debug log, so that
 * records Android keeps across many starts and app updates (how earlier processes ended) are
 * logged once each.
 *
 * The file holds that time in epoch milliseconds as decimal text. A new mark is written to a
 * temporary file next to it and renamed over it, so a write cut short by the process dying leaves
 * the previous mark in place. A missing or unreadable mark counts as nothing logged yet: every
 * record Android still holds is logged once more.
 */
class LoggedUpTo(private val file: File) {

    private val temporary = File(file.path + TEMPORARY_SUFFIX)

    /** The records newer than the last one logged, oldest first. */
    fun <T> unseen(records: List<T>, timeOf: (T) -> Long): List<T> {
        val upTo = read()
        return records.filter { timeOf(it) > upTo }.sortedBy(timeOf)
    }

    /** Stores [millis] unless the same or a newer time is already stored. */
    fun markLogged(millis: Long) {
        if (millis <= read()) return
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temporary).use { out ->
                out.write(millis.toString().toByteArray(Charsets.US_ASCII))
                // On disk before the rename, so the renamed file is never empty after a power loss.
                out.fd.sync()
            }
            if (!temporary.renameTo(file)) file.writeText(millis.toString())
        } catch (e: IOException) {
            // The same records come up again at the next start; nothing worse.
        }
    }

    private fun read(): Long =
        try {
            if (file.exists()) file.readText().trim().toLong() else 0L
        } catch (e: IOException) {
            0L
        } catch (e: NumberFormatException) {
            0L
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
