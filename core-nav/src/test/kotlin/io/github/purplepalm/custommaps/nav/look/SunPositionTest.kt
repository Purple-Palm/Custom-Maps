package io.github.purplepalm.custommaps.nav.look

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Synthetic places only: open ocean, or near 0, 0. The expected times come from PyEphem 4.2.1
 * (VSOP87; no refraction, the sun's centre), an ephemeris independent of NOAA's equations, run
 * outside this repository.
 */
class SunPositionTest {

    @ParameterizedTest
    @CsvSource(
        "0.0, 0.0, 2026-03-20, -0.833, UP, 2026-03-20T06:04:11Z",
        "0.0, 0.0, 2026-03-20, -0.833, DOWN, 2026-03-20T18:10:40Z",
        "0.0, 0.0, 2026-06-21, -0.833, UP, 2026-06-21T05:58:08Z",
        "0.0, 0.0, 2026-06-21, -0.833, DOWN, 2026-06-21T18:05:29Z",
        "45.0, -30.0, 2026-03-20, -0.833, UP, 2026-03-20T08:03:13Z",
        "45.0, -30.0, 2026-03-20, -0.833, DOWN, 2026-03-20T20:12:23Z",
        "45.0, -30.0, 2026-06-21, -2.5, UP, 2026-06-21T06:01:34Z",
        "45.0, -30.0, 2026-06-21, -2.5, DOWN, 2026-06-21T22:02:05Z",
        "45.0, -30.0, 2026-12-21, -3.5, UP, 2026-12-21T09:17:23Z",
        "45.0, -30.0, 2026-12-21, -3.5, DOWN, 2026-12-21T18:38:49Z",
        "45.0, -30.0, 2026-11-11, -0.833, UP, 2026-11-11T08:52:10Z",
        "45.0, -30.0, 2026-11-11, -0.833, DOWN, 2026-11-11T18:35:22Z",
        "45.0, -30.0, 2026-09-23, -6.0, UP, 2026-09-23T07:18:55Z",
        "45.0, -30.0, 2026-09-23, -6.0, DOWN, 2026-09-23T20:24:54Z",
        "-45.0, -120.0, 2026-06-21, -0.833, UP, 2026-06-21T15:38:57Z",
        "-45.0, -120.0, 2026-06-21, -0.833, DOWN, 2026-06-21T00:24:35Z",
        "-45.0, -120.0, 2026-12-21, -3.5, UP, 2026-12-21T11:50:42Z",
        "-45.0, -120.0, 2026-12-21, -3.5, DOWN, 2026-12-21T04:05:15Z",
        "60.0, -30.0, 2026-06-21, -0.833, UP, 2026-06-21T04:35:47Z",
        "60.0, -30.0, 2026-06-21, -0.833, DOWN, 2026-06-21T23:27:51Z",
        "60.0, -30.0, 2026-12-21, -2.5, UP, 2026-12-21T10:41:58Z",
        "60.0, -30.0, 2026-12-21, -2.5, DOWN, 2026-12-21T17:14:13Z",
        "66.0, 0.0, 2026-12-21, -0.833, UP, 2026-12-21T10:34:49Z",
        "66.0, 0.0, 2026-12-21, -0.833, DOWN, 2026-12-21T13:21:17Z",
        "80.0, 0.0, 2026-03-20, -0.833, UP, 2026-03-20T05:51:41Z",
        "80.0, 0.0, 2026-03-20, -0.833, DOWN, 2026-03-20T18:27:54Z",
        "0.0, 179.9, 2026-09-23, -0.833, UP, 2026-09-23T17:49:21Z",
        "0.0, 179.9, 2026-09-23, -0.833, DOWN, 2026-09-23T05:56:11Z",
        "0.0, -179.9, 2026-09-23, -0.833, UP, 2026-09-23T17:48:33Z",
        "0.0, -179.9, 2026-09-23, -0.833, DOWN, 2026-09-23T05:55:23Z",
    )
    fun `the sun passes each elevation within a minute of an independent ephemeris`(
        latitude: Double,
        longitude: Double,
        date: String,
        degrees: Double,
        way: SunPosition.Way,
        expected: String,
    ) {
        val found = SunPosition.nextCrossing(Instant.parse("${date}T00:00:00Z"), 24.hours, latitude, longitude, degrees, way, step = 1.minutes)
        assertNotNull(found)
        val error = abs(found!!.epochSecond - Instant.parse(expected).epochSecond)
        assertTrue(error <= 60, "off by $error s")
    }

    @ParameterizedTest
    @CsvSource(
        "0.0, 0.0, 2026-03-20T12:00:00Z, 88.1402",
        "45.0, -30.0, 2026-11-11T00:00:00Z, -54.8493",
        "45.0, -30.0, 2026-11-11T09:00:00Z, 0.4197",
        "45.0, -30.0, 2026-11-11T15:00:00Z, 25.1193",
        "45.0, -30.0, 2026-11-11T21:00:00Z, -25.6649",
        "-45.0, -120.0, 2026-06-21T21:00:00Z, 20.2887",
        "80.0, 0.0, 2026-06-21T00:00:00Z, 13.4355",
        "80.0, 0.0, 2026-12-21T12:00:00Z, -13.4396",
    )
    fun `the elevation is within a twentieth of a degree of an independent ephemeris`(
        latitude: Double,
        longitude: Double,
        at: String,
        expected: Double,
    ) {
        assertEquals(expected, SunPosition.elevation(Instant.parse(at), latitude, longitude), 0.05)
    }

    @Test
    fun `polar day has no sunset and polar night no sunrise`() {
        val midsummer = Instant.parse("2026-06-21T00:00:00Z")
        val midwinter = Instant.parse("2026-12-21T00:00:00Z")
        assertNull(SunPosition.nextCrossing(midsummer, 24.hours, 80.0, 0.0, SunPosition.RISE_SET))
        assertNull(SunPosition.nextCrossing(midwinter, 24.hours, 80.0, 0.0, SunPosition.CIVIL_TWILIGHT))
        for (minute in 0 until 24 * 60 step 10) {
            assertTrue(SunPosition.elevation(midsummer.plusSeconds(minute * 60L), 80.0, 0.0) > 10.0)
            assertTrue(SunPosition.elevation(midwinter.plusSeconds(minute * 60L), 80.0, 0.0) < -10.0)
        }
    }

    @Test
    fun `the seasons are mirrored south of the equator`() {
        // Solar midnight at 30 west in June: at 60 north the sun is only about 6.6 degrees down, at 60
        // south about 53; at solar noon in December the two swap.
        val juneMidnight = Instant.parse("2026-06-21T02:00:00Z")
        assertEquals(-6.6, SunPosition.elevation(juneMidnight, 60.0, -30.0), 0.2)
        assertEquals(-53.4, SunPosition.elevation(juneMidnight, -60.0, -30.0), 0.2)
        val decemberNoon = Instant.parse("2026-12-21T14:00:00Z")
        assertEquals(6.6, SunPosition.elevation(decemberNoon, 60.0, -30.0), 0.2)
        assertEquals(53.4, SunPosition.elevation(decemberNoon, -60.0, -30.0), 0.2)
    }

    @Test
    fun `an instant is an instant whatever offset wrote it`() {
        val utc = OffsetDateTime.of(2026, 3, 29, 1, 30, 0, 0, ZoneOffset.UTC).toInstant()
        val plusOne = OffsetDateTime.of(2026, 3, 29, 2, 30, 0, 0, ZoneOffset.ofHours(1)).toInstant()
        val plusTwo = OffsetDateTime.of(2026, 3, 29, 3, 30, 0, 0, ZoneOffset.ofHours(2)).toInstant()
        val expected = SunPosition.elevation(utc, 45.0, -30.0)
        assertEquals(expected, SunPosition.elevation(plusOne, 45.0, -30.0), 0.0)
        assertEquals(expected, SunPosition.elevation(plusTwo, 45.0, -30.0), 0.0)
    }

    @Test
    fun `the date line changes nothing`() {
        // Two places 0.2 degrees apart on either side of the date line: different calendar days, same sun.
        for (hour in 0 until 24) {
            val at = Instant.parse("2026-09-23T00:00:00Z").plusSeconds(hour * 3_600L)
            assertEquals(SunPosition.elevation(at, 0.0, 179.9), SunPosition.elevation(at, 0.0, -179.9), 0.25)
        }
    }

    @Test
    fun `a crossing is found only the way asked`() {
        val from = Instant.parse("2026-03-20T00:00:00Z")
        val up = SunPosition.nextCrossing(from, 24.hours, 0.0, 0.0, SunPosition.RISE_SET, SunPosition.Way.UP)
        val down = SunPosition.nextCrossing(from, 24.hours, 0.0, 0.0, SunPosition.RISE_SET, SunPosition.Way.DOWN)
        assertTrue(up!!.isBefore(down))
        assertEquals(up, SunPosition.nextCrossing(from, 24.hours, 0.0, 0.0, SunPosition.RISE_SET))
        assertNull(SunPosition.nextCrossing(from, 5.hours, 0.0, 0.0, SunPosition.RISE_SET))
    }
}
