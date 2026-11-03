package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class FileDebugLogTest {

    @TempDir
    lateinit var temp: File

    private val logDirectory get() = File(temp, "debug-log")
    private val exportDirectory get() = File(temp, "export")

    // November in Los Angeles is UTC-8: 07:59Z is still the 2nd there, 08:01Z the 3rd.
    private val clock = MutableClock(Instant.parse("2026-11-03T07:59:00Z"), ZoneId.of("America/Los_Angeles"))

    private fun TestScope.newLog(maxBytes: Long = FileDebugLog.DEFAULT_MAX_BYTES) =
        FileDebugLog(logDirectory, backgroundScope, StandardTestDispatcher(testScheduler), clock, maxBytes)

    @Test
    fun `writes one line per event into the file of the local day`() = runTest {
        val log = newLog()
        log.log("route_request", mapOf("to" to "Ferry Building"))
        clock.now = Instant.parse("2026-11-03T08:01:00Z")
        log.log("arrival")
        runCurrent()

        assertEquals(
            listOf("""{"t":"2026-11-03T07:59:00Z","type":"route_request","to":"Ferry Building"}"""),
            File(logDirectory, "2026-11-02.jsonl").readLines(),
        )
        assertEquals(
            listOf("""{"t":"2026-11-03T08:01:00Z","type":"arrival"}"""),
            File(logDirectory, "2026-11-03.jsonl").readLines(),
        )
    }

    @Test
    fun `export writes one text file per day, oldest first, each with the header`() = runTest {
        val log = newLog()
        log.log("first")
        clock.now = Instant.parse("2026-11-03T09:00:00Z")
        log.log("second")
        runCurrent()

        val files = log.export(exportDirectory, mapOf("app" to "0.1.0"))

        assertEquals(listOf("custom-maps-debug-2026-11-02.txt", "custom-maps-debug-2026-11-03.txt"), files.map { it.name })
        val lines = files.last().readLines()
        assertEquals("""{"t":"2026-11-03T09:00:00Z","type":"export","app":"0.1.0"}""", lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("\"type\":\"second\""))
    }

    @Test
    fun `export replaces earlier exports`() = runTest {
        val log = newLog()
        exportDirectory.mkdirs()
        val stale = File(exportDirectory, "custom-maps-debug-2026-10-01.txt").apply { writeText("old") }
        log.log("event")
        runCurrent()

        log.export(exportDirectory, emptyMap())

        assertFalse(stale.exists())
    }

    @Test
    fun `clear deletes the log and logging carries on`() = runTest {
        val log = newLog()
        log.log("before")
        runCurrent()
        log.clear()
        assertEquals(0L, log.sizeBytes())

        log.log("after")
        runCurrent()

        val lines = File(logDirectory, "2026-11-02.jsonl").readLines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\"type\":\"after\""))
    }

    @Test
    fun `the oldest days go first once the log is too large`() = runTest {
        val log = newLog(maxBytes = 200)
        val filler = mapOf("text" to "x".repeat(80))
        for (day in listOf("2026-11-01", "2026-11-02", "2026-11-03")) {
            clock.now = Instant.parse("${day}T20:00:00Z")
            log.log("event", filler)
            runCurrent()
        }

        val remaining = logDirectory.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("2026-11-02.jsonl", "2026-11-03.jsonl"), remaining)
        assertTrue(log.sizeBytes() <= 400)
    }

    @Test
    fun `the day being written is kept even when it alone is over the limit`() = runTest {
        val log = newLog(maxBytes = 10)
        log.log("event", mapOf("text" to "x".repeat(80)))
        runCurrent()
        clock.now = Instant.parse("2026-11-03T20:00:00Z")
        log.log("event", mapOf("text" to "x".repeat(80)))
        runCurrent()

        assertFalse(File(logDirectory, "2026-11-02.jsonl").exists())
        assertEquals(1, File(logDirectory, "2026-11-03.jsonl").readLines().size)
    }

    private class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now
    }
}
