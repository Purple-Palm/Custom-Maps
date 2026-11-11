package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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

    private fun TestScope.newLog(maxBytes: Long = FileDebugLog.DEFAULT_MAX_BYTES, clock: Clock = this@FileDebugLogTest.clock) =
        FileDebugLog(logDirectory, exportDirectory, backgroundScope, StandardTestDispatcher(testScheduler), clock, maxBytes)

    @Test
    fun `writes one line per event into the file of its local day`() = runTest {
        // A made-up offset of 12 hours: the times stay on 2000-01-01 and the lines show them in UTC,
        // but the clock's own day, and with it the file, changes at noon UTC.
        val clock = MutableClock(Instant.parse("2000-01-01T11:59:00Z"), ZoneOffset.ofHours(12))
        val log = newLog(clock = clock)
        log.log("route_request", mapOf("to" to "Elm Street"))
        clock.now = Instant.parse("2000-01-01T12:01:00Z")
        log.log("arrival")
        runCurrent()

        // The first day is over, and compressed.
        assertEquals(
            listOf("""{"t":"2000-01-01T11:59:00Z","type":"route_request","to":"Elm Street"}"""),
            gunzip(File(logDirectory, "2000-01-01.jsonl.gz")).lines().filter { it.isNotEmpty() },
        )
        assertEquals(
            listOf("""{"t":"2000-01-01T12:01:00Z","type":"arrival"}"""),
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
        val log = newLog(maxBytes = 400)
        // Text that gzip cannot make much smaller, so each day takes about as much room compressed.
        val filler = mapOf("text" to noise(120))
        for (day in listOf("2000-01-01", "2000-01-02", "2000-01-03")) {
            clock.now = Instant.parse("${day}T12:00:00Z")
            log.log("event", filler)
            runCurrent()
        }

        val remaining = logDirectory.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("2000-01-02.jsonl.gz", "2000-01-03.jsonl"), remaining)
        assertTrue(log.sizeBytes() <= 400)
    }

    @Test
    fun `a day is compressed once the log goes on to the next one`() = runTest {
        val log = newLog()
        log.log("first", mapOf("text" to "Oak Street"))
        log.log("second")
        runCurrent()
        val written = firstDay.readText()
        clock.now = Instant.parse("2000-01-02T08:00:00Z")
        log.log("third")
        runCurrent()

        assertFalse(firstDay.exists())
        assertEquals(written, gunzip(File(logDirectory, "2000-01-01.jsonl.gz")))
        // The day being written stays plain.
        assertEquals(1, File(logDirectory, "2000-01-02.jsonl").readLines().size)
        assertEquals(emptyList<String>(), logDirectory.list().orEmpty().filter { it.endsWith(".tmp") })
    }

    @Test
    fun `the limit is on the compressed size`() = runTest {
        // Ten days of 50 lines each: about 8 KB of text a day, a few hundred bytes compressed. As
        // text they would take 78 KB, and only the newest day would be kept.
        val log = newLog(maxBytes = 12_000)
        for (day in 1..10) {
            clock.now = Instant.parse("2000-01-${day.toString().padStart(2, '0')}T12:00:00Z")
            repeat(50) { log.log("progress", mapOf("road" to "Pine Avenue", "metersLeft" to 1_200 - it, "text" to "x".repeat(60))) }
            runCurrent()
        }

        val days = logDirectory.list().orEmpty().map { it.substringBefore('.') }.distinct()
        assertEquals(10, days.size)
        assertTrue(log.sizeBytes() <= 12_000, "stored ${log.sizeBytes()} bytes")
        val exported = log.export(emptyMap())
        assertEquals(10, exported.size)
        assertTrue(exported.sumOf { it.length() } > 50_000L)
    }

    @Test
    fun `export writes a compressed day as the plain text it was`() = runTest {
        val log = newLog()
        log.log("route_request", mapOf("to" to "Elm Street"))
        log.log("arrival")
        runCurrent()
        val written = firstDay.readLines()
        clock.now = Instant.parse("2000-01-02T09:00:00Z")
        log.log("next day")
        runCurrent()
        assertFalse(firstDay.exists())

        val files = log.export(mapOf("app" to "0.1.0"))

        assertEquals(listOf("custom-maps-debug-2000-01-01.txt", "custom-maps-debug-2000-01-02.txt"), files.map { it.name })
        val lines = files.first().readLines()
        assertTrue(lines.first().contains("\"type\":\"export\""))
        assertEquals(written, lines.drop(1))
    }

    @Test
    fun `lines that come back to a compressed day are kept, after the ones before`() = runTest {
        val log = newLog()
        log.log("before")
        runCurrent()
        clock.now = Instant.parse("2000-01-02T09:00:00Z")
        log.log("next day")
        runCurrent()
        // The phone's clock is set back a day.
        clock.now = Instant.parse("2000-01-01T23:00:00Z")
        log.log("late")
        clock.now = Instant.parse("2000-01-02T09:30:00Z")
        log.log("next day again")
        runCurrent()

        // Both parts of the day are one compressed file now, read as one text.
        assertEquals(listOf("before", "late"), gunzip(File(logDirectory, "2000-01-01.jsonl.gz")).lines().filter { it.isNotEmpty() }.map(::typeOf))
        val exported = log.export(emptyMap()).first().readLines().drop(1).map(::typeOf)
        assertEquals(listOf("before", "late"), exported)
    }

    @Test
    fun `days left plain by an earlier run are compressed at the first line`() = runTest {
        logDirectory.mkdirs()
        val old = File(logDirectory, "1999-12-30.jsonl").apply { writeText("{\"t\":\"1999-12-30T10:00:00Z\",\"type\":\"old\"}\n") }
        val log = newLog()

        log.log("today")
        runCurrent()

        assertFalse(old.exists())
        assertEquals("{\"t\":\"1999-12-30T10:00:00Z\",\"type\":\"old\"}\n", gunzip(File(logDirectory, "1999-12-30.jsonl.gz")))
    }

    @Test
    fun `clear also deletes the compressed days`() = runTest {
        val log = newLog()
        log.log("first")
        runCurrent()
        clock.now = Instant.parse("2000-01-02T09:00:00Z")
        log.log("second")
        runCurrent()
        assertTrue(File(logDirectory, "2000-01-01.jsonl.gz").exists())

        log.clear()

        assertEquals(emptyList<String>(), logDirectory.list().orEmpty().toList())
        assertEquals(0L, log.sizeBytes())
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

    @Test
    fun `logNow waits only for the event being written, not for the ones queued behind it`() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val encoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val log = FileDebugLog(logDirectory, exportDirectory, scope, dispatcher, clock)
            // The background writer holds the files while it encodes this one.
            val slow = object {
                override fun toString(): String {
                    encoding.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return "slow"
                }
            }
            val encodedOn = CopyOnWriteArrayList<String>()
            val queued = object {
                override fun toString(): String {
                    encodedOn.add(Thread.currentThread().name)
                    return "queued"
                }
            }
            log.log("slow", mapOf("value" to slow))
            assertTrue(encoding.await(5, TimeUnit.SECONDS))
            repeat(QUEUED_BEHIND) { log.log("queued", mapOf("value" to queued)) }
            var written = false
            val crashing = thread(name = "crashing") { written = log.logNow("crash", emptyMap(), timeoutMillis = 5_000) }
            // Parked means it is in line for the files, which is what the writer has to respect.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (crashing.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.TIMED_WAITING, crashing.state)

            release.countDown()
            crashing.join()

            assertTrue(written)
            // The writer did not take the files again: logNow wrote the rest of the queue itself.
            assertEquals(List(QUEUED_BEHIND) { "crashing" }, encodedOn.toList())
            assertEquals(
                listOf("slow") + List(QUEUED_BEHIND) { "queued" } + "crash",
                firstDay.readLines().map { it.substringAfter("\"type\":\"").substringBefore('"') },
            )
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    private companion object {
        const val RACE_RUNS = 200
        const val QUEUED_BEHIND = 5

        fun gunzip(file: File): String = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }

        fun typeOf(line: String): String = line.substringAfter("\"type\":\"").substringBefore('"')

        /** [length] letters that follow no pattern gzip can use, the same each time. */
        fun noise(length: Int): String {
            var seed = 12_345L
            return buildString {
                repeat(length) {
                    seed = (seed * 1_103_515_245L + 12_345L) and 0x7fffffffL
                    append('a' + (seed % 26).toInt())
                }
            }
        }
    }

    private class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now
    }
}
