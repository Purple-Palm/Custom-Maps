package io.github.purplepalm.custommaps.nav.debug

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The debug log in [directory]: one JSON-lines file per local day, written by a single background
 * coroutine so [log] never blocks the caller: it only queues the event, and the coroutine encodes,
 * masks and writes it. [logNow] writes on the calling thread instead, for a process that is about
 * to die. Once all files together pass [maxBytes], the oldest days are deleted; the file being
 * written is always kept. Copies for sharing go to [exportDirectory], and [clear] deletes them
 * along with the log.
 */
class FileDebugLog(
    private val directory: File,
    private val exportDirectory: File,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : DebugLogArchive, UrgentDebugLog {

    /** An event as the caller handed it over; encoded only by whoever writes it. */
    private class Entry(val time: Instant, val type: String, val fields: Map<String, Any?>)

    /** An encoded and masked line, and the day whose file it goes into. */
    private class Line(val day: LocalDate, val text: String)

    // A full buffer drops the oldest events rather than blocking the main thread.
    private val entries = Channel<Entry>(BUFFERED_EVENTS, BufferOverflow.DROP_OLDEST)

    /**
     * Wakes the background writer; one wake-up covers every event queued meanwhile. The writer
     * waits here rather than on [entries], because a waiting receiver would take an event off the
     * queue at once, before it holds [files].
     */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    /**
     * Guards the files and the open writer. A thread lock rather than a coroutine mutex, because
     * [logNow] runs on a thread that cannot suspend; it is never held across a suspension point.
     * Fair, so the background writer, which takes it again for every event, queues behind a waiting
     * [logNow] instead of getting ahead of it each time.
     */
    private val files = ReentrantLock(true)
    private var writer: Writer? = null
    private var writerDay: LocalDate? = null
    private var linesSinceTrim = 0

    init {
        scope.launch(io) {
            for (wakeUp in wakeUps) {
                // One event per turn of the lock, so logNow waits for one event at most.
                while (files.withLock { writeOldestQueued() }) {
                    // Until the queue is empty.
                }
            }
        }
    }

    override fun log(type: String, fields: Map<String, Any?>) {
        entries.trySend(Entry(clock.instant(), type, fields))
        wakeUps.trySend(Unit)
    }

    override fun logNow(type: String, fields: Map<String, Any?>, timeoutMillis: Long): Boolean {
        val entry = Entry(clock.instant(), type, fields)
        // A writer stuck on slow storage must not keep a dying process alive for long.
        val locked = try {
            files.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) return false
        try {
            // Every event logged before this one is written already or still queued: the queued
            // ones go first, in order.
            while (writeOldestQueued()) {
                // Until the queue is empty.
            }
            return write(encode(entry))
        } finally {
            files.unlock()
        }
    }

    /**
     * Takes the oldest event off the queue, then encodes and writes it; false when the queue is
     * empty. Call it holding [files]: events leave the queue only then, so [logNow] never misses
     * one that the background writer has taken but not written yet.
     */
    private fun writeOldestQueued(): Boolean {
        val entry = entries.tryReceive().getOrNull() ?: return false
        write(encode(entry))
        return true
    }

    /** The JSON line of [entry] with tokens masked; an event that cannot be encoded becomes "log_error". */
    private fun encode(entry: Entry): Line {
        val text = try {
            JsonLines.event(entry.time, entry.type, entry.fields)
        } catch (e: RuntimeException) {
            JsonLines.event(entry.time, "log_error", mapOf("failedType" to entry.type, "error" to e.toString()))
        }
        return Line(entry.time.atZone(clock.zone).toLocalDate(), Redaction.redact(text))
    }

    override suspend fun sizeBytes(): Long = withContext(io) {
        files.withLock { logFiles().sumOf { it.length() } }
    }

    override suspend fun export(header: Map<String, Any?>): List<File> = withContext(io) {
        files.withLock {
            writer?.flush()
            exportDirectory.mkdirs()
            deleteExports()
            val headerLine = JsonLines.event(clock.instant(), "export", header)
            logFiles().map { source ->
                File(exportDirectory, "$EXPORT_PREFIX${source.nameWithoutExtension}.txt").also { exported ->
                    exported.bufferedWriter().use { out ->
                        out.write(headerLine)
                        out.write("\n")
                        source.bufferedReader().use { it.copyTo(out) }
                    }
                }
            }
        }
    }

    override suspend fun clear() = withContext(io) {
        files.withLock {
            closeWriter()
            logFiles().forEach { it.delete() }
            deleteExports()
        }
    }

    private fun deleteExports() {
        exportDirectory.listFiles { file -> file.name.startsWith(EXPORT_PREFIX) }?.forEach { it.delete() }
    }

    /**
     * Appends one line and flushes it to the operating system, which keeps it even if this process
     * dies right after. Returns false if the line was dropped. Call it holding [files].
     */
    private fun write(line: Line): Boolean {
        try {
            val out = writerFor(line.day)
            out.write(line.text)
            out.write("\n")
            out.flush()
            linesSinceTrim += 1
            if (linesSinceTrim >= TRIM_EVERY_LINES) trim()
            return true
        } catch (e: IOException) {
            // Storage full or the file went away: drop this line, start over with the next one.
            closeWriter()
            return false
        }
    }

    private fun writerFor(day: LocalDate): Writer {
        writer?.takeIf { writerDay == day }?.let { return it }
        closeWriter()
        directory.mkdirs()
        val opened = BufferedWriter(OutputStreamWriter(FileOutputStream(fileFor(day), true), Charsets.UTF_8))
        writer = opened
        writerDay = day
        trim()
        return opened
    }

    private fun trim() {
        linesSinceTrim = 0
        val current = writerDay?.let(::fileFor)
        var total = logFiles().sumOf { it.length() }
        for (file in logFiles()) {
            if (total <= maxBytes || file == current) break
            total -= file.length()
            file.delete()
        }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (e: IOException) {
            // Nothing left to save.
        }
        writer = null
        writerDay = null
    }

    private fun fileFor(day: LocalDate) = File(directory, "$day$LOG_EXTENSION")

    /** Oldest first: the names are ISO dates. */
    private fun logFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(LOG_EXTENSION) }
            ?.sortedBy { it.name }
            .orEmpty()

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        const val EXPORT_PREFIX = "custom-maps-debug-"
        private const val LOG_EXTENSION = ".jsonl"
        private const val BUFFERED_EVENTS = 10_000
        private const val TRIM_EVERY_LINES = 1_000
    }
}
