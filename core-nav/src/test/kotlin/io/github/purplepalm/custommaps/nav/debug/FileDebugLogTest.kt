package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class FileDebugLogTest {

    @TempDir
    lateinit var temp: File

    private val logDirectory get() = File(temp, "debug-log")
    private val exportDirectory get() = File(temp, "export")
    private val firstDay get() = File(logDirectory, "2000-01-01.jsonl")

    private val clock = MutableClock(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun TestScope.newLog(maxBytes: Long = FileDebugLog.DEFAULT_MAX_BYTES) =
        FileDebugLog(logDirectory, exportDirectory, backgroundScope, StandardTestDispatcher(testScheduler), clock, maxBytes)

    @Test
    fun `writes one line per event into the file of its day`() = runTest {
        val log = newLog()
        clock.now = Instant.parse("2000-01-01T23:59:00Z")
        log.log("route_request", mapOf("to" to "Elm Street"))
        clock.now = Instant.parse("2000-01-02T00:01:00Z")
        log.log("arrival")
        runCurrent()

        assertEquals(
            listOf("""{"t":"2000-01-01T23:59:00Z","type":"route_request","to":"Elm Street"}"""),
            firstDay.readLines(),
        )
        assertEquals(
            listOf("""{"t":"2000-01-02T00:01:00Z","type":"arrival"}"""),
            File(logDirectory, "2000-01-02.jsonl").readLines(),
        )
    }

    @Test
    fun `log only queues the event and the background writer encodes it`() = runTest {
        val log = newLog()
        var encoded = 0
        val value = object {
            override fun toString(): String {
                encoded += 1
                return "value"
            }
        }

        log.log("event", mapOf("value" to value))
        assertEquals(0, encoded)
        runCurrent()

        assertEquals(1, encoded)
        assertEquals(listOf("""{"t":"2000-01-01T00:00:00Z","type":"event","value":"value"}"""), firstDay.readLines())
    }

    @Test
    fun `an event that cannot be encoded becomes a log_error line`() = runTest {
        val log = newLog()
        val broken = object {
            override fun toString(): String = throw IllegalStateException("no text")
        }

        log.log("event", mapOf("value" to broken))
        runCurrent()

        assertEquals(
            listOf("""{"t":"2000-01-01T00:00:00Z","type":"log_error","failedType":"event","error":"java.lang.IllegalStateException: no text"}"""),
            firstDay.readLines(),
        )
    }

    @Test
    fun `tokens are masked by the background writer`() = runTest {
        val log = newLog()

        log.log("error", mapOf("error" to "GET https://api.mapbox.com/x?access_token=pk.abcdefgh12.ijklmnop34"))
        runCurrent()

        val line = firstDay.readText()
        assertFalse(line.contains("abcdefgh12"))
        assertTrue(line.contains("access_token=[redacted]"))
    }

    @Test
    fun `export writes one text file per day, oldest first, each with the header`() = runTest {
        val log = newLog()
        log.log("first")
        clock.now = Instant.parse("2000-01-02T09:00:00Z")
        log.log("second")
        runCurrent()

        val files = log.export(mapOf("app" to "0.1.0"))

        assertEquals(listOf("custom-maps-debug-2000-01-01.txt", "custom-maps-debug-2000-01-02.txt"), files.map { it.name })
        val lines = files.last().readLines()
        assertEquals("""{"t":"2000-01-02T09:00:00Z","type":"export","app":"0.1.0"}""", lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("\"type\":\"second\""))
    }

    @Test
    fun `export replaces earlier exports`() = runTest {
        val log = newLog()
        exportDirectory.mkdirs()
        val stale = File(exportDirectory, "custom-maps-debug-1999-12-01.txt").apply { writeText("old") }
        log.log("event")
        runCurrent()

        log.export(emptyMap())

        assertFalse(stale.exists())
    }

    @Test
    fun `clear also deletes the copies made for sharing`() = runTest {
        val log = newLog()
        log.log("event")
        runCurrent()
        val exported = log.export(emptyMap())

        log.clear()

        assertTrue(exported.isNotEmpty())
        assertTrue(exported.none { it.exists() })
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

        val lines = firstDay.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\"type\":\"after\""))
    }

    @Test
    fun `the oldest days go first once the log is too large`() = runTest {
        val log = newLog(maxBytes = 200)
        val filler = mapOf("text" to "x".repeat(80))
        for (day in listOf("2000-01-01", "2000-01-02", "2000-01-03")) {
            clock.now = Instant.parse("${day}T12:00:00Z")
            log.log("event", filler)
            runCurrent()
        }

        val remaining = logDirectory.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("2000-01-02.jsonl", "2000-01-03.jsonl"), remaining)
        assertTrue(log.sizeBytes() <= 400)
    }

    @Test
    fun `the day being written is kept even when it alone is over the limit`() = runTest {
        val log = newLog(maxBytes = 10)
        log.log("event", mapOf("text" to "x".repeat(80)))
        runCurrent()
        clock.now = Instant.parse("2000-01-02T12:00:00Z")
        log.log("event", mapOf("text" to "x".repeat(80)))
        runCurrent()

        assertFalse(firstDay.exists())
        assertEquals(1, File(logDirectory, "2000-01-02.jsonl").readLines().size)
    }

    @Test
    fun `logNow writes what is still queued, then its own event, before it returns`() = runTest {
        val log = newLog()
        log.log("progress", mapOf("step" to 1))
        log.log("progress", mapOf("step" to 2))

        // No runCurrent(): the background writer never ran, as on a thread that is about to die.
        val written = log.logNow("crash", mapOf("error" to "boom"), timeoutMillis = 100)

        assertTrue(written)
        assertEquals(
            listOf(
                """{"t":"2000-01-01T00:00:00Z","type":"progress","step":1}""",
                """{"t":"2000-01-01T00:00:00Z","type":"progress","step":2}""",
                """{"t":"2000-01-01T00:00:00Z","type":"crash","error":"boom"}""",
            ),
            firstDay.readLines(),
        )
    }

    @Test
    fun `logNow lands after an event logged just before it, also with the writer on its own thread`() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val log = FileDebugLog(logDirectory, exportDirectory, scope, dispatcher, clock)
            // Each time the writer is idle when the event comes, as before a crash on the main thread.
            repeat(RACE_RUNS) { run ->
                log.log("error", mapOf("run" to run))
                assertTrue(log.logNow("crash", mapOf("run" to run), timeoutMillis = 5_000))
            }

            assertEquals(
                (0 until RACE_RUNS).flatMap { listOf("error $it", "crash $it") },
                firstDay.readLines().map { line ->
                    line.substringAfter("\"type\":\"").substringBefore('"') + " " + line.substringAfter("\"run\":").substringBefore('}')
                },
            )
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `logNow masks tokens like every other event`() = runTest {
        val log = newLog()

        log.logNow("crash", mapOf("error" to "GET https://api.mapbox.com/x?access_token=pk.abcdefgh12.ijklmnop34"), 100)

        val line = firstDay.readText()
        assertFalse(line.contains("abcdefgh12"))
        assertTrue(line.contains("access_token=[redacted]"))
    }

    @Test
    fun `the background writer carries on after logNow`() = runTest {
        val log = newLog()
        log.logNow("crash", emptyMap(), 100)

        log.log("after")
        runCurrent()

        val lines = firstDay.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("\"type\":\"after\""))
    }

    @Test
    fun `logNow gives up with false while another write holds the files, and log never waits`() = runTest {
        val log = newLog()
        val encoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Queued events are encoded while logNow holds the files: this one keeps them held.
        val slow = object {
            override fun toString(): String {
                encoding.countDown()
                release.await(5, TimeUnit.SECONDS)
                return "slow"
            }
        }
        log.log("slow", mapOf("value" to slow))
        var firstWritten = false
        val first = thread { firstWritten = log.logNow("first", emptyMap(), timeoutMillis = 5_000) }
        assertTrue(encoding.await(5, TimeUnit.SECONDS))

        val secondWritten = log.logNow("second", emptyMap(), timeoutMillis = 50)
        assertTimeoutPreemptively(Duration.ofSeconds(1)) { log.log("during") }
        release.countDown()
        first.join()
        runCurrent()

        assertFalse(secondWritten)
        assertTrue(firstWritten)
        // The first logNow drains everything queued until its own line, "during" too.
        assertEquals(
            listOf("slow", "during", "first"),
            firstDay.readLines().map { it.substringAfter("\"type\":\"").substringBefore('"') },
        )
    }

    private companion object {
        const val RACE_RUNS = 200
    }

    private class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now
    }
}
