package io.github.purplepalm.custommaps.nav.trip

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripLogTest {

    private val california = ZoneId.of("America/Los_Angeles")

    private fun start(iso: String, kind: TripKind = TripKind.ACTIVE_GUIDANCE, simulated: Boolean = false, tokenId: String? = null) =
        TripSessionStart(Instant.parse(iso), kind, simulated, tokenId)

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
    fun `lines with a token id are written with four fields and read back`() {
        val entries = listOf(
            TripSessionStart(Instant.parse("2000-01-03T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false, tokenId = "a1b2c3d4e5f6"),
            TripSessionStart(Instant.parse("2000-01-04T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = true, tokenId = "t-2_x"),
            TripSessionStart(Instant.parse("2000-01-05T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false),
        )

        val text = TripLog.encode(entries)

        assertEquals(
            listOf("946886400000,ACTIVE_GUIDANCE,0,a1b2c3d4e5f6", "946972800000,ACTIVE_GUIDANCE,1,t-2_x", "947059200000,ACTIVE_GUIDANCE,0"),
            text.lines(),
        )
        assertEquals(entries, TripLog.decode(text))
    }

    @Test
    fun `the three-field lines of earlier versions are read, without a token`() {
        val decoded = TripLog.decode("946886400000,ACTIVE_GUIDANCE,0\n946972800000,FREE_DRIVE,1")

        assertEquals(
            listOf(
                TripSessionStart(Instant.parse("2000-01-03T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false, tokenId = null),
                TripSessionStart(Instant.parse("2000-01-04T08:00:00Z"), TripKind.FREE_DRIVE, simulated = true, tokenId = null),
            ),
            decoded,
        )
    }

    @Test
    fun `a line whose token id is no id, or with more fields, is skipped`() {
        val text = listOf(
            "946886400000,ACTIVE_GUIDANCE,0,a1",
            "946886400000,ACTIVE_GUIDANCE,0,",
            "946886400000,ACTIVE_GUIDANCE,0,a b",
            "946886400000,ACTIVE_GUIDANCE,0,${"a".repeat(33)}",
            "946886400000,ACTIVE_GUIDANCE,0,a1,b2",
        ).joinToString("\n")

        assertEquals(listOf("a1"), TripLog.decode(text).map { it.tokenId })
        assertTrue(TripLog.isTokenId("a".repeat(32)))
    }

    @Test
    fun `the month's trips are counted for each token`() {
        val entries = listOf(
            start("1999-12-31T08:00:00Z", tokenId = "main"),
            start("2000-01-03T08:00:00Z", tokenId = "main"),
            start("2000-01-04T08:00:00Z", tokenId = "test"),
            start("2000-01-05T08:00:00Z", tokenId = "main", simulated = true),
            start("2000-01-06T08:00:00Z"),
        )

        val counts = TripLog.countByToken(entries, midJanuary)

        assertEquals(mapOf("main" to 2, "test" to 1, null to 1), counts)
        assertEquals(TripLog.countInMonth(entries, midJanuary), counts.values.sum())
        assertEquals(emptyMap<String?, Int>(), TripLog.countByToken(emptyList(), midJanuary))
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

    /** Times in UTC on invented days of January 2000. */
    private val utc = ZoneId.of("UTC")
    private val midJanuary = ZonedDateTime.of(2000, 1, 20, 12, 0, 0, 0, utc)

    @Test
    fun `a log begun before the month covers all of it`() {
        val entries = listOf(start("2000-01-03T08:00:00Z"), start("2000-01-19T08:00:00Z"))

        val month = TripLog.monthCount(entries, midJanuary, installedAt = Instant.parse("1999-12-10T00:00:00Z"))

        assertEquals(MonthCount(2), month)
    }

    @Test
    fun `an installation in the middle of the month counts since that day`() {
        val entries = listOf(start("2000-01-16T08:00:00Z"), start("2000-01-19T08:00:00Z"))

        val month = TripLog.monthCount(entries, midJanuary, installedAt = Instant.parse("2000-01-15T21:30:00Z"))

        assertEquals(MonthCount(2, LocalDate.of(2000, 1, 15), LogBegan.INSTALLATION), month)
    }

    @Test
    fun `the day is the installation's local day`() {
        // 02:00 UTC on January 15 is still January 14 in California.
        val nowInCalifornia = ZonedDateTime.of(2000, 1, 20, 4, 0, 0, 0, california)

        val month = TripLog.monthCount(emptyList(), nowInCalifornia, installedAt = Instant.parse("2000-01-15T02:00:00Z"))

        assertEquals(MonthCount(0, LocalDate.of(2000, 1, 14), LogBegan.INSTALLATION), month)
    }

    @Test
    fun `an installation on the first moment of the month covers it`() {
        assertEquals(MonthCount(0), TripLog.monthCount(emptyList(), midJanuary, installedAt = Instant.parse("2000-01-01T00:00:00Z")))
        assertEquals(
            MonthCount(0, LocalDate.of(2000, 1, 1), LogBegan.INSTALLATION),
            TripLog.monthCount(emptyList(), midJanuary, installedAt = Instant.parse("2000-01-01T00:00:01Z")),
        )
    }

    @Test
    fun `trips restored from before the installation move the start back`() {
        // A backup brought back trips of December and January; the app was installed again later.
        val entries = listOf(start("1999-12-20T08:00:00Z"), start("2000-01-05T08:00:00Z"))
        assertEquals(MonthCount(1), TripLog.monthCount(entries, midJanuary, installedAt = Instant.parse("2000-01-18T00:00:00Z")))

        // Restored trips that begin within the month: since the oldest of them, as far as anyone can tell.
        val january = listOf(start("2000-01-05T08:00:00Z"), start("2000-01-19T08:00:00Z"))
        assertEquals(
            MonthCount(2, LocalDate.of(2000, 1, 5), LogBegan.OLDEST_KEPT),
            TripLog.monthCount(january, midJanuary, installedAt = Instant.parse("2000-01-18T00:00:00Z")),
        )
    }

    @Test
    fun `a full log within the month counts since its oldest trip`() {
        // Trips every half hour from January 9, 500 of them, the last on January 19.
        val first = Instant.parse("2000-01-09T06:00:00Z")
        val entries = (0 until TripLog.MAX_ENTRIES).map { TripSessionStart(first.plusSeconds(1_800L * it), TripKind.ACTIVE_GUIDANCE, false) }

        val month = TripLog.monthCount(entries, midJanuary, installedAt = Instant.parse("1999-06-01T00:00:00Z"))

        assertEquals(MonthCount(TripLog.MAX_ENTRIES, LocalDate.of(2000, 1, 9), LogBegan.OLDEST_KEPT), month)
    }
}
