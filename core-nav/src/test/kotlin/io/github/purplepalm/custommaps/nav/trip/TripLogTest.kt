package io.github.purplepalm.custommaps.nav.trip

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TripLogTest {

    private val california = ZoneId.of("America/Los_Angeles")

    private fun start(iso: String, kind: TripKind = TripKind.ACTIVE_GUIDANCE, simulated: Boolean = false) =
        TripSessionStart(Instant.parse(iso), kind, simulated)

    @Test
    fun `counts only starts in the current local month`() {
        val entries = listOf(
            start("2026-10-15T18:00:00Z"),
            // 23:59 on October 31 in California, although it is already November in UTC.
            start("2026-11-01T06:59:00Z"),
            start("2026-11-01T08:00:00Z"),
            start("2026-11-03T02:00:00Z", TripKind.FREE_DRIVE, simulated = true),
        )
        val now = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, california)
        assertEquals(2, TripLog.countInMonth(entries, now))
    }

    @Test
    fun `encoding round trips`() {
        val entries = listOf(
            start("2026-11-01T08:00:00Z"),
            start("2026-11-02T09:30:00Z", TripKind.FREE_DRIVE, simulated = true),
        )
        assertEquals(entries, TripLog.decode(TripLog.encode(entries)))
    }

    @Test
    fun `unreadable lines are skipped`() {
        val text = "1793520000000,ACTIVE_GUIDANCE,0\ngarbage\n1793520000000,SOMETHING,0\n1793606400000,FREE_DRIVE,1\n"
        val decoded = TripLog.decode(text)
        assertEquals(2, decoded.size)
        assertEquals(TripKind.FREE_DRIVE, decoded.last().kind)
    }

    @Test
    fun `the log keeps only the newest entries`() {
        var entries = emptyList<TripSessionStart>()
        repeat(TripLog.MAX_ENTRIES + 3) { index ->
            entries = TripLog.append(entries, TripSessionStart(Instant.ofEpochSecond(index.toLong()), TripKind.ACTIVE_GUIDANCE, false))
        }
        assertEquals(TripLog.MAX_ENTRIES, entries.size)
        assertEquals(Instant.ofEpochSecond(3), entries.first().startedAt)
    }
}
