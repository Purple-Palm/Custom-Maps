package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class CrashRecorderTest {

    @TempDir
    lateinit var temp: File

    private val clock = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val logFile get() = File(temp, "log/2000-01-01.jsonl")

    /** Stands in for Android's handler, which would end the process. */
    private class PreviousHandler : Thread.UncaughtExceptionHandler {
        val received = mutableListOf<Throwable>()
        var onCrash: () -> Unit = {}

        override fun uncaughtException(thread: Thread, error: Throwable) {
            onCrash()
            received += error
        }
    }

    private class Captured : UrgentDebugLog {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            events += type to fields
        }

        override fun logNow(type: String, fields: Map<String, Any?>, timeoutMillis: Long): Boolean {
            events += type to fields
            return true
        }
    }

    @Test
    fun `the crash is on disk, after the events before it, when the previous handler runs`() = runTest {
        val log = FileDebugLog(File(temp, "log"), File(temp, "export"), backgroundScope, StandardTestDispatcher(testScheduler), clock)
        val previous = PreviousHandler()
        var linesWhenPreviousRan = emptyList<String>()
        previous.onCrash = { linesWhenPreviousRan = logFile.readLines() }
        val recorder = CrashRecorder(log, previous)
        recorder.context = { mapOf("guidance" to "ACTIVE") }
        log.log("progress", mapOf("step" to 4))
        val error = IllegalStateException("boom", IOException("disk"))

        recorder.uncaughtException(Thread.currentThread(), error)

        assertEquals(2, linesWhenPreviousRan.size)
        assertTrue(linesWhenPreviousRan[0].contains("\"type\":\"progress\""))
        val crash = linesWhenPreviousRan[1]
        assertTrue(crash.startsWith("""{"t":"2000-01-01T00:00:00Z","type":"crash","thread":""""))
        assertTrue(crash.contains("\"error\":\"java.lang.IllegalStateException: boom\""))
        assertTrue(crash.contains("\"causes\":[{\"error\":\"java.io.IOException: disk\""))
        assertTrue(crash.contains("\"context\":{\"guidance\":\"ACTIVE\"}"))
        assertSame(error, previous.received.single())
    }

    @Test
    fun `the previous handler still runs when writing fails`() {
        val failing = object : UrgentDebugLog {
            override fun log(type: String, fields: Map<String, Any?>) = Unit

            override fun logNow(type: String, fields: Map<String, Any?>, timeoutMillis: Long): Boolean =
                throw IllegalStateException("storage gone")
        }
        val previous = PreviousHandler()
        val error = RuntimeException("boom")

        CrashRecorder(failing, previous).uncaughtException(Thread.currentThread(), error)

        assertSame(error, previous.received.single())
    }

    @Test
    fun `only the first crash is written, but every crash is passed on`() {
        val log = Captured()
        val previous = PreviousHandler()
        val recorder = CrashRecorder(log, previous)

        recorder.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        recorder.uncaughtException(Thread.currentThread(), RuntimeException("second"))

        assertEquals(1, log.events.size)
        assertEquals("java.lang.RuntimeException: first", log.events.single().second["error"])
        assertEquals(2, previous.received.size)
    }

    /** A log whose crash line takes until [release] opens; [steps] records what happened, in order. */
    private class SlowLog : UrgentDebugLog {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun log(type: String, fields: Map<String, Any?>) = Unit

        override fun logNow(type: String, fields: Map<String, Any?>, timeoutMillis: Long): Boolean {
            writing.countDown()
            release.await(10, TimeUnit.SECONDS)
            steps += "written: ${fields["error"]}"
            return true
        }
    }

    private class Passing(private val steps: MutableList<String>) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            steps += "passed on: $error"
        }
    }

    @Test
    fun `a second thread that crashes meanwhile waits until the first crash is written`() {
        val log = SlowLog()
        val recorder = CrashRecorder(log, Passing(log.steps), firstCrashWaitMillis = 10_000)
        val first = thread { recorder.uncaughtException(Thread.currentThread(), RuntimeException("first")) }
        assertTrue(log.writing.await(5, TimeUnit.SECONDS))

        val second = thread { recorder.uncaughtException(Thread.currentThread(), RuntimeException("second")) }
        // Without the wait, the second thread would reach the previous handler, which ends the process.
        second.join(200)
        assertTrue(second.isAlive)
        log.release.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals("written: java.lang.RuntimeException: first", log.steps.first())
        assertEquals(
            setOf("passed on: java.lang.RuntimeException: first", "passed on: java.lang.RuntimeException: second"),
            log.steps.drop(1).toSet(),
        )
    }

    @Test
    fun `a second thread stops waiting once the first crash takes too long`() {
        val log = SlowLog()
        val recorder = CrashRecorder(log, Passing(log.steps), firstCrashWaitMillis = 50)
        val first = thread { recorder.uncaughtException(Thread.currentThread(), RuntimeException("first")) }
        assertTrue(log.writing.await(5, TimeUnit.SECONDS))

        val second = thread { recorder.uncaughtException(Thread.currentThread(), RuntimeException("second")) }
        second.join(5_000)

        assertFalse(second.isAlive)
        assertEquals(listOf("passed on: java.lang.RuntimeException: second"), log.steps.toList())
        log.release.countDown()
        first.join(5_000)
    }

    @Test
    fun `a crash inside the crash's own write never waits for itself`() {
        val previous = PreviousHandler()
        lateinit var recorder: CrashRecorder
        val log = object : UrgentDebugLog {
            override fun log(type: String, fields: Map<String, Any?>) = Unit

            override fun logNow(type: String, fields: Map<String, Any?>, timeoutMillis: Long): Boolean {
                recorder.uncaughtException(Thread.currentThread(), RuntimeException("inside"))
                return true
            }
        }
        recorder = CrashRecorder(log, previous, firstCrashWaitMillis = 10_000)

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            recorder.uncaughtException(Thread.currentThread(), RuntimeException("outer"))
        }

        assertEquals(listOf("inside", "outer"), previous.received.map { it.message })
    }

    @Test
    fun `a failing context is noted and the crash is written anyway`() {
        val log = Captured()
        val recorder = CrashRecorder(log, PreviousHandler())
        recorder.context = { throw IllegalStateException("not ready") }

        recorder.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(
            mapOf("contextError" to "java.lang.IllegalStateException: not ready"),
            log.events.single().second["context"],
        )
    }

    @Test
    fun `a cause chain that loops back is cut where it repeats`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        val fields = CrashRecorder.describe(Thread.currentThread(), first)

        @Suppress("UNCHECKED_CAST")
        val causes = fields["causes"] as List<Map<String, Any?>>
        assertEquals(listOf("java.lang.RuntimeException: second"), causes.map { it["error"] })
    }

    @Test
    fun `causes and suppressed errors are bounded`() {
        var chain: Throwable = RuntimeException("cause 10")
        for (depth in 9 downTo 1) chain = RuntimeException("cause $depth", chain)
        val error = IllegalStateException("top", chain)
        repeat(5) { error.addSuppressed(RuntimeException("suppressed $it")) }

        val fields = CrashRecorder.describe(Thread.currentThread(), error)

        @Suppress("UNCHECKED_CAST")
        val causes = fields["causes"] as List<Map<String, Any?>>
        assertEquals((1..5).map { "java.lang.RuntimeException: cause $it" }, causes.map { it["error"] })
        assertEquals((0..2).map { "java.lang.RuntimeException: suppressed $it" }, fields["suppressed"])
    }

    @Test
    fun `long stacks and messages are cut short`() {
        val error = RuntimeException("x".repeat(5_000))
        error.stackTrace = Array(100) { StackTraceElement("Some", "method$it", "Some.kt", it) }

        val fields = CrashRecorder.describe(Thread.currentThread(), error)

        assertEquals(40, (fields["stack"] as List<*>).size)
        assertEquals(60, fields["framesLeftOut"])
        assertEquals(2_000, (fields["error"] as String).length)
    }

    @Test
    fun `install becomes the default handler and keeps the one before`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val previous = PreviousHandler()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        try {
            val log = Captured()
            val recorder = CrashRecorder.install(log)

            assertSame(recorder, Thread.getDefaultUncaughtExceptionHandler())
            recorder.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertEquals(1, previous.received.size)
            assertEquals(CrashRecorder.EVENT, log.events.single().first)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
