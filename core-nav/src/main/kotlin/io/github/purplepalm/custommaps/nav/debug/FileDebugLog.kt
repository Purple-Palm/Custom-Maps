package io.github.purplepalm.custommaps.nav.debug

import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
 * to die.
 *
 * A day that is over is kept compressed (gzip), which holds many more days in the same room: a
 * guided minute writes a lot. Days are compressed in the background, outside the lock that guards
 * writing, so neither [log] nor [logNow] waits for it. Once all files together, as stored, pass
 * [maxBytes], the oldest days are deleted; the file being written is always kept. Copies for sharing
 * go to [exportDirectory] as plain text, one file per day, and [clear] deletes them along with the
 * log.
 */
class FileDebugLog(
    private val directory: File,
    private val exportDirectory: File,
    private val scope: CoroutineScope,
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

    /** True while a pass of [compressClosedDays] runs; there is never more than one. */
    private val compressing = AtomicBoolean(false)

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

    /**
     * Each day of the log as a plain text file, `custom-maps-debug-<day>.txt`, the [header] line
     * first, then the day's lines in the order they were written; a compressed day is written out
     * as text. The files are opened under the lock and copied after it, so writing goes on
     * meanwhile: the copy has what the log held when the export began. A part of a day that cannot
     * be read to its end, as a compressed day cut short by a power loss, keeps what could be read
     * and ends with an "export_error" line, and the other days are shared all the same. Only a
     * failure to write the copies fails the export.
     */
    override suspend fun export(header: Map<String, Any?>): List<File> = withContext(io) {
        val sources = files.withLock {
            writer?.flush()
            exportDirectory.mkdirs()
            deleteExports()
            openParts()
        }
        try {
            val headerLine = JsonLines.event(clock.instant(), "export", header)
            sources.groupBy { it.day }.map { (day, parts) ->
                File(exportDirectory, "$EXPORT_PREFIX$day.txt").also { exported ->
                    exported.outputStream().buffered().use { out ->
                        out.write(headerLine.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                        for (part in parts) copyPart(part, out)
                    }
                }
            }
        } finally {
            sources.forEach { it.close() }
        }
    }

    /**
     * Copies what [part] holds into [out]. Where the part cannot be read on, what was read stays and
     * an "export_error" line with the day and the error follows; a failure to write is thrown.
     */
    private fun copyPart(part: Part, out: OutputStream) {
        val input = try {
            part.open()
        } catch (e: IOException) {
            return writeExportError(part.day, e, out)
        }
        input.use {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    return writeExportError(part.day, e, out)
                }
                if (read < 0) return
                out.write(buffer, 0, read)
            }
        }
    }

    /** The line that ends a day's copy where the rest could not be read, on a line of its own. */
    private fun writeExportError(day: String, error: IOException, out: OutputStream) {
        val line = Redaction.redact(JsonLines.event(clock.instant(), "export_error", mapOf("day" to day, "error" to error.toString())))
        out.write("\n$line\n".toByteArray(Charsets.UTF_8))
    }

    /**
     * One file of the log opened for reading: a compressed day, or [length] bytes of a plain one,
     * as long as it was when it was opened. A compressed day is never written to once it is there:
     * compression puts a new file in its place. An open file can still be read when it is replaced,
     * renamed or deleted.
     */
    private class Part(val day: String, private val input: FileInputStream, private val compressed: Boolean, private val length: Long) {
        fun open(): InputStream = if (compressed) GZIPInputStream(input.buffered()) else LimitedInput(input.buffered(), length)

        fun close() {
            try {
                input.close()
            } catch (e: IOException) {
                // Nothing to keep.
            }
        }
    }

    /** At most [left] bytes of [input]. */
    private class LimitedInput(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0L) return -1
            return input.read().also { if (it >= 0) left -= 1 }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0L) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }

        override fun close() = input.close()
    }

    /** Every file of the log, oldest first, opened for [export]. Call it holding [files]. */
    private fun openParts(): List<Part> {
        val parts = ArrayList<Part>()
        try {
            for (file in logFiles()) {
                parts += Part(dayOf(file), FileInputStream(file), file.name.endsWith(COMPRESSED_EXTENSION), file.length())
            }
        } catch (e: IOException) {
            parts.forEach { it.close() }
            throw e
        }
        return parts
    }

    override suspend fun clear() = withContext(io) {
        files.withLock {
            closeWriter()
            logFiles().forEach { it.delete() }
            temporaryFiles().forEach { it.delete() }
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
        // A new day: the days before it are over. Also at the first line of a run, for days a
        // run before left plain.
        compressClosedDaysLater()
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

    /** Starts a pass of [compressClosedDays] in the background, unless one runs. */
    private fun compressClosedDaysLater() {
        if (!compressing.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                compressClosedDays()
            } finally {
                compressing.set(false)
            }
        }
    }

    /**
     * Compresses each day that is over and still plain, one at a time, without holding the lock
     * while it compresses. A temporary file gets the day's compressed part as it was, if there is
     * one, and then the lines of the plain file as long as it was, as one more gzip member, which
     * reads on as the same text. It is on the storage before, under the lock, it takes the
     * compressed part's place in one rename, and only then is the plain file deleted: a pass cut
     * short by the end of the process, a full storage or a power loss leaves the day as it was
     * and is done again by the next pass. A day that got lines meanwhile, as when the phone's clock
     * was set back, stays as it is until the next pass.
     */
    private fun compressClosedDays() {
        files.withLock { temporaryFiles().forEach { it.delete() } }
        val closed = files.withLock {
            val current = writerDay?.let(::fileFor)
            logFiles().filter { it.name.endsWith(LOG_EXTENSION) && it != current }
        }
        for (plain in closed) {
            val compressed = File(directory, plain.name + GZIP_SUFFIX)
            val temporary = File(directory, compressed.name + TEMPORARY_SUFFIX)
            // The day as it is now: the plain file's length, and the compressed part's, if any.
            val before = files.withLock {
                if (!plain.exists() || plain == writerDay?.let(::fileFor)) null else DayFiles(plain.length(), compressed.lengthIfThere())
            } ?: continue
            try {
                FileOutputStream(temporary).use { file ->
                    val out = file.buffered()
                    before.compressed?.let { length -> LimitedInput(FileInputStream(compressed).buffered(), length).use { it.copyTo(out) } }
                    GZIPOutputStream(out).use { gzip ->
                        LimitedInput(FileInputStream(plain).buffered(), before.plain).use { it.copyTo(gzip) }
                        gzip.finish()
                        out.flush()
                        // On the storage before it takes the day's place, so a power loss cannot leave it empty.
                        file.fd.sync()
                    }
                }
            } catch (e: IOException) {
                // Storage full or a file went away: the day stays as it is.
                temporary.delete()
                continue
            }
            files.withLock {
                val unchanged = plain != writerDay?.let(::fileFor) &&
                    plain.exists() && plain.length() == before.plain && compressed.lengthIfThere() == before.compressed
                // The rename takes the compressed part's place in one step; an export that has the
                // old one open reads on in it.
                if (unchanged && temporary.renameTo(compressed)) plain.delete() else temporary.delete()
            }
        }
        // The days take less room now; the oldest may have to go all the same.
        files.withLock { trim() }
    }

    /** How long a day's two parts were when a pass of compression read them; [compressed] null when it had none. */
    private class DayFiles(val plain: Long, val compressed: Long?)

    private fun File.lengthIfThere(): Long? = if (exists()) length() else null

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

    /**
     * Every file of the log, oldest first: by day, the names being ISO dates, and within a day the
     * compressed part, which holds the older lines, before a plain one.
     */
    private fun logFiles(): List<File> =
        directory.listFiles { file -> file.isFile && (file.name.endsWith(LOG_EXTENSION) || file.name.endsWith(COMPRESSED_EXTENSION)) }
            ?.sortedWith(compareBy({ dayOf(it) }, { !it.name.endsWith(COMPRESSED_EXTENSION) }))
            .orEmpty()

    /** What a pass of compression left behind when the process ended during it. */
    private fun temporaryFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(COMPRESSED_EXTENSION + TEMPORARY_SUFFIX) }.orEmpty().toList()

    private fun dayOf(file: File): String = file.name.substringBefore('.')

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        const val EXPORT_PREFIX = "custom-maps-debug-"
        private const val LOG_EXTENSION = ".jsonl"
        private const val GZIP_SUFFIX = ".gz"
        private const val COMPRESSED_EXTENSION = LOG_EXTENSION + GZIP_SUFFIX
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val BUFFERED_EVENTS = 10_000
        private const val TRIM_EVERY_LINES = 1_000
        private const val COPY_BUFFER_BYTES = 8 * 1024
    }
}
