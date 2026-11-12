package io.github.purplepalm.custommaps.look

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.look.LookTuning
import io.github.purplepalm.custommaps.nav.look.PlaceSource
import io.github.purplepalm.custommaps.nav.look.SunPlace
import io.github.purplepalm.custommaps.nav.look.SunPosition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The next change under Auto at a synthetic place, 0, 0, on a synthetic date, the equinox of the
 * year 2000. Every time is checked by the sun's own elevation around it, never printed: an
 * assertion that fails says only true or false.
 */
class NextLookTest {

    private val equator = SunPlace.of(0.0, 0.0, PlaceSource.FIX)
    private val equinox = LocalDate.of(2000, 3, 20)
    private val noon = equinox.atTime(12, 0).toInstant(ZoneOffset.UTC)
    private val lateEvening = equinox.atTime(22, 0).toInstant(ZoneOffset.UTC)
    private val tuning = LookTuning()

    private fun elevation(at: Instant) = SunPosition.elevation(at, 0.0, 0.0)

    /** The instant of [next] on [day] in UTC, one minute before it and one after. */
    private fun around(next: NextLook, day: LocalDate): Triple<Instant, Instant, Instant> {
        val at = day.atTime(next.at).toInstant(ZoneOffset.UTC)
        return Triple(at.minusSeconds(60), at, at.plusSeconds(60))
    }

    @Test
    fun `by day under Auto the next change is the evening's, when the sun sinks past the night threshold`() {
        val next = NextLook.of(LookChoice.AUTO, Look.DAY, equator, noon, ZoneOffset.UTC)
        assertEquals(Look.NIGHT, next?.look)
        val (before, _, after) = around(next!!, equinox)
        assertTrue(elevation(before) > tuning.nightFrom)
        assertTrue(elevation(after) < tuning.nightFrom)
        assertTrue(next.at.second == 0)
    }

    @Test
    fun `at night under Auto the next change is the morning's, when the sun rises past the day threshold`() {
        val next = NextLook.of(LookChoice.AUTO, Look.NIGHT, equator, lateEvening, ZoneOffset.UTC)
        assertEquals(Look.DAY, next?.look)
        val (before, _, after) = around(next!!, equinox.plusDays(1))
        assertTrue(elevation(before) < tuning.dayFrom)
        assertTrue(elevation(after) > tuning.dayFrom)
    }

    @Test
    fun `the time is the phone's own clock time`() {
        val utc = NextLook.of(LookChoice.AUTO, Look.DAY, equator, noon, ZoneOffset.UTC)!!
        val oneHourEast = NextLook.of(LookChoice.AUTO, Look.DAY, equator, noon, ZoneOffset.ofHours(1))!!
        assertTrue(oneHourEast.at == utc.at.plusHours(1))
    }

    @Test
    fun `no time by hand, under the time zone's guess, when the change is due, or in polar day`() {
        assertNull(NextLook.of(LookChoice.DAY, Look.DAY, equator, noon, ZoneOffset.UTC))
        assertNull(NextLook.of(LookChoice.NIGHT, Look.NIGHT, equator, lateEvening, ZoneOffset.UTC))
        assertNull(NextLook.of(LookChoice.AUTO, Look.DAY, SunPlace.ofTimeZone(0), noon, ZoneOffset.UTC))
        // The day look still shown late in the evening, or the night look at noon: the change waits
        // for the next minute, not for another day.
        assertNull(NextLook.of(LookChoice.AUTO, Look.DAY, equator, lateEvening, ZoneOffset.UTC))
        assertNull(NextLook.of(LookChoice.AUTO, Look.NIGHT, equator, noon, ZoneOffset.UTC))
        // Open ocean far north at midsummer: the sun does not set within a day.
        val farNorth = SunPlace.of(80.0, 0.0, PlaceSource.FIX)
        val midsummer = LocalDate.of(2000, 6, 21).atTime(12, 0).toInstant(ZoneOffset.UTC)
        assertNull(NextLook.of(LookChoice.AUTO, Look.DAY, farNorth, midsummer, ZoneOffset.UTC))
    }

    @Test
    fun `a time is rounded to the nearest minute`() {
        val t = Instant.parse("2000-01-01T00:00:00Z")
        assertEquals(t, NextLook.toTheMinute(t.plusMillis(29_999)))
        assertEquals(t.plusSeconds(60), NextLook.toTheMinute(t.plusSeconds(30)))
        assertEquals(t, NextLook.toTheMinute(t.minusSeconds(30)))
    }
}
