package io.github.purplepalm.custommaps.nav.debug

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The debug log in [directory]: one JSON-lines file per local day, written by a single background
 * coroutine so [log] never blocks the caller. Once all files together pass [maxBytes], the oldest
 * days are deleted; the file being written is always kept.
 */
class FileDebugLog(
    private val directory: File,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : DebugLogArchive {

    private class Entry(val day: LocalDate, val line: String)

    // A full buffer drops the oldest events rather than blocking the main thread.
    private val entries = Channel<Entry>(BUFFERED_EVENTS, BufferOverflow.DROP_OLDEST)
    private val files = Mutex()
    private var writer: Writer? = null
    private var writerDay: LocalDate? = null
    private var linesSinceTrim = 0

    init {
        scope.launch(io) {
            for (entry in entries) files.withLock { write(entry) }
        }
    }

    override fun log(type: String, fields: Map<String, Any?>) {
        val now = clock.instant()
        val line = try {
            JsonLines.event(now, type, fields)
        } catch (e: RuntimeException) {
            JsonLines.event(now, "log_error", mapOf("type" to type, "error" to e.toString()))
        }
        entries.trySend(Entry(now.atZone(clock.zone).toLocalDate(), Redaction.redact(line)))
    }

    override suspend fun sizeBytes(): Long = withContext(io) {
        files.withLock { logFiles().sumOf { it.length() } }
    }

    override suspend fun export(target: File, header: Map<String, Any?>): List<File> = withContext(io) {
        files.withLock {
            writer?.flush()
            target.mkdirs()
            target.listFiles { file -> file.name.startsWith(EXPORT_PREFIX) }?.forEach { it.delete() }
            val headerLine = JsonLines.event(clock.instant(), "export", header)
            logFiles().map { source ->
                File(target, "$EXPORT_PREFIX${source.nameWithoutExtension}.txt").also { exported ->
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
        }
    }

    private fun write(entry: Entry) {
        try {
            val out = writerFor(entry.day)
            out.write(entry.line)
            out.write("\n")
            out.flush()
            linesSinceTrim += 1
            if (linesSinceTrim >= TRIM_EVERY_LINES) trim()
        } catch (e: IOException) {
            // Storage full or the file went away: drop this line, start over with the next one.
            closeWriter()
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
