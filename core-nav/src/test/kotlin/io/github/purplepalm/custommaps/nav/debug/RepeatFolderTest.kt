package io.github.purplepalm.custommaps.nav.debug

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepeatFolderTest {

    private class Captured : DebugLog {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            events += type to fields
        }
    }

    /** The test scheduler's virtual time, starting at midnight on 2000-01-01 UTC. */
    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = START.plusMillis(scheduler.currentTime)

        companion object {
            val START: Instant = Instant.parse("2000-01-01T00:00:00Z")
        }
    }

    private val offline = "Failed to load tile: Couldn't connect to server: Exception in CronetUrlRequest: " +
        "net::ERR_INTERNET_DISCONNECTED, ErrorCode=2, InternalErrorCode=-106, Retryable=false"

    private fun tileError(source: String) = mapOf("errorType" to "TILE", "message" to offline, "source" to source)

    @Test
    fun `the first error is logged in full and the repeats once per window`() = runTest {
        val log = Captured()
        val folder = RepeatFolder(log, backgroundScope, SchedulerClock(testScheduler), windowMillis = 60_000)
        val key = RepeatFolder.keyOf("TILE", offline)

        folder.record("map_error", key, tileError("composite"), origin = "composite")
        advanceTimeBy(1_000)
        folder.record("map_error", key, tileError("composite"), origin = "composite")
        advanceTimeBy(1_000)
        folder.record("map_error", key, tileError("3dbuildings"), origin = "3dbuildings")
        assertEquals(listOf("map_error"), log.events.map { it.first })

        advanceTimeBy(59_000)

        assertEquals(listOf("map_error", "map_error_repeats"), log.events.map { it.first })
        val repeats = log.events[1].second
        assertEquals(2, repeats["count"])
        assertEquals(Instant.parse("2000-01-01T00:00:01Z"), repeats["from"])
        assertEquals(Instant.parse("2000-01-01T00:00:02Z"), repeats["to"])
        assertEquals(mapOf("composite" to 1, "3dbuildings" to 1), repeats["origins"])
        assertEquals(tileError("composite"), repeats["first"])
    }

    @Test
    fun `a flood that goes on gets one repeats line per window`() = runTest {
        val log = Captured()
        val folder = RepeatFolder(log, backgroundScope, SchedulerClock(testScheduler), windowMillis = 60_000)
        val key = RepeatFolder.keyOf("TILE", offline)

        // 50 errors a second for three minutes, as when every tile fails offline.
        repeat(180 * 50) {
            folder.record("map_error", key, tileError("composite"), origin = "composite")
            advanceTimeBy(20)
        }
        runCurrent()

        assertEquals(listOf("map_error", "map_error_repeats", "map_error_repeats", "map_error_repeats"), log.events.map { it.first })
        assertEquals(180 * 50 - 1, log.events.drop(1).sumOf { it.second["count"] as Int })
    }

    @Test
    fun `a new kind is logged at once while another kind is being counted`() = runTest {
        val log = Captured()
        val folder = RepeatFolder(log, backgroundScope, SchedulerClock(testScheduler), windowMillis = 60_000)
        val unreachable = mapOf("errorType" to "TILE", "message" to "net::ERR_ADDRESS_UNREACHABLE", "source" to "composite")

        folder.record("map_error", RepeatFolder.keyOf("TILE", offline), tileError("composite"))
        folder.record("map_error", RepeatFolder.keyOf("TILE", offline), tileError("composite"))
        folder.record("map_error", RepeatFolder.keyOf("TILE", "net::ERR_ADDRESS_UNREACHABLE"), unreachable)

        assertEquals(listOf(tileError("composite"), unreachable), log.events.map { it.second })
    }

    @Test
    fun `a key that stays quiet for a whole window is logged in full again`() = runTest {
        val log = Captured()
        val folder = RepeatFolder(log, backgroundScope, SchedulerClock(testScheduler), windowMillis = 60_000)
        val key = RepeatFolder.keyOf("TILE", offline)

        folder.record("map_error", key, tileError("composite"))
        advanceTimeBy(61_000)
        folder.record("map_error", key, tileError("composite"))

        assertEquals(listOf("map_error", "map_error"), log.events.map { it.first })
    }

    @Test
    fun `flush writes the open counts at once`() = runTest {
        val log = Captured()
        val folder = RepeatFolder(log, backgroundScope, SchedulerClock(testScheduler), windowMillis = 60_000)
        val key = RepeatFolder.keyOf("TILE", offline)
        folder.record("map_error", key, tileError("composite"))
        folder.record("map_error", key, tileError("composite"))

        folder.flush()
        advanceTimeBy(120_000)

        assertEquals(listOf("map_error", "map_error_repeats"), log.events.map { it.first })
        assertEquals(1, log.events[1].second["count"])
    }

    @Test
    fun `keys ignore numbers but not words`() {
        assertEquals(
            RepeatFolder.keyOf("TILE", "ErrorCode=2, InternalErrorCode=-106 tile 14/8192/8192"),
            RepeatFolder.keyOf("TILE", "ErrorCode=9, InternalErrorCode=-109 tile 15/16385/16383"),
        )
        assertNotEquals(
            RepeatFolder.keyOf("TILE", "net::ERR_INTERNET_DISCONNECTED"),
            RepeatFolder.keyOf("TILE", "net::ERR_ADDRESS_UNREACHABLE"),
        )
    }
}
