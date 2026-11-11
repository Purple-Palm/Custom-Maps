package io.github.purplepalm.custommaps.nav.look

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Synthetic places only (open ocean or near 0, 0) and synthetic time zone rules. */
class LookRuleTest {

    private val equator = SunPlace.of(0.0, 0.0, PlaceSource.FIX)
    private val midAtlantic = SunPlace.of(45.0, -30.0, PlaceSource.FIX)
    private val farNorth = SunPlace.of(80.0, 0.0, PlaceSource.FIX)
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun sunAt(elevation: Double) = SunElevation { _, _, _ -> elevation }

    @Test
    fun `a choice by hand wins over the sun`() {
        assertEquals(Look.DAY, LookRule.wanted(LookChoice.DAY, equator, at, Look.NIGHT, sun = sunAt(-40.0)))
        assertEquals(Look.NIGHT, LookRule.wanted(LookChoice.NIGHT, equator, at, Look.DAY, sun = sunAt(40.0)))
    }

    @ParameterizedTest
    @CsvSource(
        // elevation, shown, expected
        "10.0, NIGHT, DAY",
        "-2.5, NIGHT, DAY",
        "-2.6, NIGHT, NIGHT",
        "-3.4, DAY, DAY",
        "-3.5, DAY, NIGHT",
        "-30.0, DAY, NIGHT",
    )
    fun `between the two elevations the shown look stays`(elevation: Double, shown: Look, expected: Look) {
        assertEquals(expected, LookRule.wanted(LookChoice.AUTO, equator, at, shown, sun = sunAt(elevation)))
    }

    @ParameterizedTest
    @CsvSource("-2.9, DAY", "-3.0, DAY", "-3.1, NIGHT")
    fun `with nothing shown yet the middle decides`(elevation: Double, expected: Look) {
        assertEquals(expected, LookRule.wanted(LookChoice.AUTO, equator, at, null, sun = sunAt(elevation)))
    }

    @Test
    fun `an evening in mid latitudes`() {
        // 45 north, 30 west, at the March equinox: sunset 20:12:23, -2.5 at 20:21:50, -3.5 at 20:27:30 (UTC).
        fun look(time: String, shown: Look?) = LookRule.wanted(LookChoice.AUTO, midAtlantic, Instant.parse("2026-03-20T${time}Z"), shown)
        assertEquals(Look.DAY, look("20:12:00", Look.DAY))
        assertEquals(Look.DAY, look("20:25:00", Look.DAY))
        assertEquals(Look.NIGHT, look("20:25:00", null))
        assertEquals(Look.NIGHT, look("20:28:00", Look.DAY))
        // That morning: -3.5 at 07:48:08, -2.5 at 07:53:48, sunrise 08:03:13; day nine minutes before sunrise.
        assertEquals(Look.NIGHT, look("07:51:00", Look.NIGHT))
        assertEquals(Look.DAY, look("07:55:00", Look.NIGHT))
    }

    @Test
    fun `polar day stays day and polar night stays night`() {
        for (hour in 0 until 24) {
            val june = Instant.parse("2026-06-21T00:00:00Z").plusSeconds(hour * 3_600L)
            val december = Instant.parse("2026-12-21T00:00:00Z").plusSeconds(hour * 3_600L)
            assertEquals(Look.DAY, LookRule.wanted(LookChoice.AUTO, farNorth, june, Look.NIGHT))
            assertEquals(Look.NIGHT, LookRule.wanted(LookChoice.AUTO, farNorth, december, Look.DAY))
        }
    }

    @Test
    fun `without a position the time zone's standard meridian stands in`() {
        assertEquals(15.0, SunPlace.ofTimeZone(3_600).longitude, 0.0)
        assertEquals(0.0, SunPlace.ofTimeZone(3_600).latitude, 0.0)
        assertEquals(-75.0, SunPlace.ofTimeZone(-5 * 3_600).longitude, 0.0)
        assertEquals(82.5, SunPlace.ofTimeZone(5 * 3_600 + 1_800).longitude, 0.0)
        // East of +12 hours the meridian is across the date line.
        assertEquals(-165.0, SunPlace.ofTimeZone(13 * 3_600).longitude, 0.0)
        assertEquals(PlaceSource.TIME_ZONE, SunPlace.ofTimeZone(0).source)
    }

    @Test
    fun `the time zone guess takes the darkest sun on its meridian`() {
        val guess = SunPlace.ofTimeZone(0)
        // An autumn evening on the prime meridian: the equator still has the sun up, 45 and 60 north do not.
        val evening = Instant.parse("2026-11-11T17:00:00Z")
        assertEquals(Look.DAY, LookRule.wanted(LookChoice.AUTO, equator, evening, null))
        assertEquals(Look.NIGHT, LookRule.wanted(LookChoice.AUTO, guess, evening, null))
        assertEquals(Look.NIGHT, LookRule.wanted(LookChoice.AUTO, guess, evening, Look.DAY))
        // Around local noon every latitude tried has the sun up.
        assertEquals(Look.DAY, LookRule.wanted(LookChoice.AUTO, guess, Instant.parse("2026-11-11T11:45:00Z"), Look.NIGHT))
        // A fix is never taken for a guess.
        assertEquals(
            SunPosition.elevation(evening, 0.0, 0.0),
            LookRule.elevation(SunPlace.of(0.0, 0.0, PlaceSource.FIX), evening),
            0.0,
        )
    }

    @Test
    fun `summer time moves the clock, not the guess`() {
        // A synthetic zone: standard +01:00, summer time +02:00 from a date in March.
        val rules = ZoneRules.of(
            ZoneOffset.ofHours(1),
            ZoneOffset.ofHours(1),
            emptyList(),
            listOf(ZoneOffsetTransition.of(LocalDateTime.of(2026, 3, 29, 2, 0), ZoneOffset.ofHours(1), ZoneOffset.ofHours(2))),
            emptyList(),
        )
        val winter = Instant.parse("2026-02-01T12:00:00Z")
        val summer = Instant.parse("2026-07-01T12:00:00Z")
        assertEquals(ZoneOffset.ofHours(2), rules.getOffset(summer))
        val winterGuess = SunPlace.ofTimeZone(rules.getStandardOffset(winter).totalSeconds)
        val summerGuess = SunPlace.ofTimeZone(rules.getStandardOffset(summer).totalSeconds)
        assertEquals(winterGuess, summerGuess)
        assertEquals(15.0, summerGuess.longitude, 0.0)
    }

    @Test
    fun `a place keeps a tenth of a degree and never prints its coordinates`() {
        val place = SunPlace.of(0.123456, -0.987654, PlaceSource.LAST_KNOWN)
        assertEquals(0.1, place.latitude, 0.0)
        assertEquals(-1.0, place.longitude, 0.0)
        assertEquals("SunPlace(LAST_KNOWN)", place.toString())
        assertFalse(place.toString().contains("0.1"))
        assertEquals(-179.9, SunPlace.of(0.0, 180.1, PlaceSource.FIX).longitude, 0.0)
    }
}
