package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The phone's fix against the matched position, at made-up points on the equator. */
class RawOffsetTest {

    /**
     * The fields for a phone fix [east] and [north] meters from a matched position at [latitude],
     * [longitude], heading [bearing], with the given speed, accuracy and age.
     */
    private fun offset(
        latitude: Double,
        longitude: Double,
        bearing: Double?,
        east: Double,
        north: Double,
        rawBearing: Double? = bearing,
        speed: Double? = 25.0,
        accuracy: Double? = 4.0,
        age: Long? = 150L,
    ): Map<String, Any?> {
        val around = LocalMeters(latitude, longitude)
        val fix = LocalPoint(east, north)
        return RawOffset.fields(around.latitudeOf(fix), around.longitudeOf(fix), rawBearing, speed, accuracy, latitude, longitude, bearing, age)
    }

    @Test
    fun `a fix three meters right of the car and two behind it, heading north`() {
        // Right of a car heading north is east, behind it south.
        assertEquals(
            mapOf("sideM" to 3.0, "aheadM" to -2.0, "accM" to 4.0, "ageMs" to 150L, "bearingDiff" to 0.0),
            offset(0.0, 0.0, bearing = 0.0, east = 3.0, north = -2.0),
        )
        // To the left and ahead: both the other way round.
        val left = offset(0.0, 0.0, bearing = 0.0, east = -3.0, north = 2.0)
        assertEquals(-3.0, left["sideM"])
        assertEquals(2.0, left["aheadM"])
    }

    @Test
    fun `the same fix for a car heading east, and one heading south west`() {
        // Right of a car heading east is south, behind it west.
        val east = offset(0.0, 0.001, bearing = 90.0, east = -2.0, north = -3.0)
        assertEquals(3.0, east["sideM"])
        assertEquals(-2.0, east["aheadM"])
        // Heading 225 degrees: right is north west, behind is north east.
        val half = Math.sqrt(0.5)
        val southWest = offset(0.001, 0.0, bearing = 225.0, east = -3.0 * half + 2.0 * half, north = 3.0 * half + 2.0 * half)
        assertEquals(3.0, southWest["sideM"])
        assertEquals(-2.0, southWest["aheadM"])
    }

    @Test
    fun `across the date line the offsets are as anywhere else`() {
        // Heading west just east of the line of 180 degrees, which lies behind: the fix is across it.
        val longitude = 179.999995
        val behind = offset(0.0, longitude, bearing = 270.0, east = 2.0, north = 3.0)
        val fix = LocalMeters(0.0, longitude).longitudeOf(LocalPoint(2.0, 3.0))
        assertEquals(true, fix < 0.0, "the fix lies on the other side of the line")
        assertEquals(3.0, behind["sideM"])
        assertEquals(-2.0, behind["aheadM"])
    }

    @Test
    fun `the two directions differ the short way round`() {
        assertEquals(-20.0, RawOffset.bearingDifference(raw = 350.0, matched = 10.0, rawSpeedMps = 20.0))
        assertEquals(20.0, RawOffset.bearingDifference(raw = 10.0, matched = 350.0, rawSpeedMps = 20.0))
        assertEquals(180.0, RawOffset.bearingDifference(raw = 180.0, matched = 0.0, rawSpeedMps = 20.0))
        assertEquals(180.0, RawOffset.bearingDifference(raw = 0.0, matched = 180.0, rawSpeedMps = 20.0))
        assertEquals(-5.5, RawOffset.bearingDifference(raw = 715.0, matched = 0.5, rawSpeedMps = 20.0), "more than a turn")
        assertEquals(12.5, offset(0.0, 0.0, bearing = 90.0, east = 0.0, north = 0.0, rawBearing = 102.5)["bearingDiff"])
    }

    @Test
    fun `below three meters a second, or without a direction or a speed, the directions are not compared`() {
        assertNull(RawOffset.bearingDifference(raw = 100.0, matched = 90.0, rawSpeedMps = 2.9))
        assertEquals(10.0, RawOffset.bearingDifference(raw = 100.0, matched = 90.0, rawSpeedMps = RawOffset.MIN_BEARING_SPEED_MPS))
        assertNull(RawOffset.bearingDifference(raw = 100.0, matched = 90.0, rawSpeedMps = null))
        assertNull(RawOffset.bearingDifference(raw = 100.0, matched = 90.0, rawSpeedMps = Double.NaN))
        assertNull(RawOffset.bearingDifference(raw = null, matched = 90.0, rawSpeedMps = 20.0))
        assertNull(RawOffset.bearingDifference(raw = 100.0, matched = Double.NaN, rawSpeedMps = 20.0))
        val slow = offset(0.0, 0.0, bearing = 0.0, east = 3.0, north = -2.0, speed = 1.0)
        assertNull(slow["bearingDiff"])
        assertEquals(3.0, slow["sideM"], "the offsets do not need a speed")
    }

    @Test
    fun `without a matched direction there are no offsets, and what the fix lacks is null`() {
        assertEquals(
            mapOf("sideM" to null, "aheadM" to null, "accM" to null, "ageMs" to null, "bearingDiff" to null),
            offset(0.0, 0.0, bearing = null, east = 3.0, north = -2.0, rawBearing = 10.0, accuracy = null, age = null),
        )
        assertNull(offset(0.0, 0.0, bearing = Double.NaN, east = 3.0, north = -2.0)["sideM"])
        // Rounded for the log: a tenth of a meter.
        assertEquals(1.2, offset(0.0, 0.0, bearing = 0.0, east = 1.234, north = 0.0, accuracy = 3.26)["sideM"])
        assertEquals(3.3, offset(0.0, 0.0, bearing = 0.0, east = 1.234, north = 0.0, accuracy = 3.26)["accM"])
    }
}
