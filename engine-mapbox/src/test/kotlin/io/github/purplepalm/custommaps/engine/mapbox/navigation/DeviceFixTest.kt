package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.bindgen.Value
import com.mapbox.common.location.Location
import com.mapbox.common.location.LocationExtraKeys
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.guidance.FixTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Invented positions in the sea near the point 0, 0, and invented clocks. */
class DeviceFixTest {

    private val point = Point.fromLngLat(0.002, 0.001)

    private fun fix(bearing: Double? = 44.0, speed: Double? = 12.0, ageMillis: Long = 1_000L) =
        DeviceFix(point, bearing, speed, ageMillis)

    @Test
    fun `a fresh position of a moving car gives its direction`() {
        assertEquals(44.0, fix().originBearing())
        assertEquals(0.0, fix(bearing = 0.0).originBearing())
        assertEquals(359.5, fix(bearing = 359.5, speed = 30.0, ageMillis = 0L).originBearing())
    }

    @Test
    fun `the direction counts from three meters a second on`() {
        assertEquals(3.0, DeviceFix.MOVING_METERS_PER_SECOND)
        assertEquals(44.0, fix(speed = 3.0).originBearing())
        assertEquals(44.0, fix(speed = 3.1).originBearing())
        assertNull(fix(speed = 2.9).originBearing())
        assertNull(fix(speed = 0.0).originBearing())
    }

    @Test
    fun `a position without a speed gives no direction`() {
        assertNull(fix(speed = null).originBearing())
        assertNull(fix(speed = Double.NaN).originBearing())
    }

    @Test
    fun `a position without a direction gives none`() {
        assertNull(fix(bearing = null).originBearing())
        assertNull(fix(bearing = Double.NaN).originBearing())
        assertNull(fix(bearing = Double.POSITIVE_INFINITY).originBearing())
    }

    @Test
    fun `the direction of a position older than thirty seconds is not used`() {
        assertEquals(30_000L, DeviceLocation.MAX_AGE.inWholeMilliseconds)
        assertEquals(44.0, fix(ageMillis = 30_000L).originBearing())
        assertNull(fix(ageMillis = 30_001L).originBearing())
        assertNull(fix(ageMillis = 600_000L).originBearing())
    }

    @Test
    fun `a position says where the car is now for thirty seconds`() {
        assertTrue(fix(ageMillis = 0L).live)
        assertTrue(fix(ageMillis = 30_000L).live)
        assertFalse(fix(ageMillis = 30_001L).live)
        assertFalse(fix(ageMillis = 300_000L).live)
        // Neither the speed nor the direction counts for it.
        assertTrue(fix(bearing = null, speed = null).live)
    }

    @Test
    fun `the age comes from the monotonic clock when the position has its time`() {
        // Measured at 5.0 s of the monotonic clock, read at 7.5 s: the wall clock is not looked at.
        assertEquals(2_500L, fixAgeMillis(5_000_000_000L, timestampMillis = 0L, nowNanos = 7_500_000_000L, nowMillis = 946_684_800_000L))
        assertEquals(0L, fixAgeMillis(5_000_000_000L, timestampMillis = 0L, nowNanos = 5_000_000_000L, nowMillis = 1L))
    }

    @Test
    fun `without a monotonic time the wall clock gives the age`() {
        // 2000-01-01 00:00:00 UTC, read four seconds later.
        val measured = 946_684_800_000L
        assertEquals(4_000L, fixAgeMillis(null, measured, nowNanos = 123L, nowMillis = measured + 4_000L))
        // A wall clock that was set back in between gives an age below zero, which callers bring to zero.
        assertEquals(-1_000L, fixAgeMillis(null, measured, nowNanos = 123L, nowMillis = measured - 1_000L))
    }

    /** A position as the replayer of a simulated drive makes it: marked as mock, stamped 100 s ahead. */
    private fun replayedLocation(mock: Boolean): Location = Location.Builder()
        .latitude(0.001)
        .longitude(0.002)
        .timestamp(946_684_900_000L)
        .monotonicTimestamp(105_000_000_000L)
        .speed(12.0)
        .bearing(44.0)
        .extra(Value.valueOf(hashMapOf(LocationExtraKeys.IS_MOCK to Value(mock))))
        .build()

    @Test
    fun `a replayed position is as new as the moment it is played`() {
        // Read at 5 s of the monotonic clock and on 2000-01-01 00:00:00 UTC: the replayer's own
        // times lie 100 s ahead.
        val fix = DeviceFix.of(replayedLocation(mock = true), nowNanos = 5_000_000_000L, nowMillis = 946_684_800_000L)

        assertTrue(fix.replayed)
        assertEquals(0L, fix.ageMillis)
        assertTrue(fix.live)
        assertEquals(mapOf("ageMs" to null, "speedMps" to 12.0, "bearing" to 44.0, "replayed" to true), fix.debugFields())
    }

    @Test
    fun `a position from the phone keeps its age`() {
        val location = replayedLocation(mock = false)

        val fix = DeviceFix.of(location, nowNanos = 135_000_000_000L, nowMillis = 946_685_000_000L)

        assertFalse(fix.replayed)
        assertFalse(location.isReplayed())
        assertEquals(30_000L, fix.ageMillis)
        assertEquals(mapOf("ageMs" to 30_000L, "speedMps" to 12.0, "bearing" to 44.0), fix.debugFields())
    }

    @Test
    fun `a position without the mark is the phone's`() {
        val plain = Location.Builder().latitude(0.0).longitude(0.0).timestamp(946_684_800_000L).build()

        assertFalse(plain.isReplayed())
    }

    @Test
    fun `a position's time carries the replayer's mark`() {
        assertEquals(FixTime(105_000_000_000L, 946_684_900_000L, replayed = true), replayedLocation(mock = true).fixTime())
        assertEquals(FixTime(105_000_000_000L, 946_684_900_000L, replayed = false), replayedLocation(mock = false).fixTime())
        val plain = Location.Builder().latitude(0.0).longitude(0.0).timestamp(946_684_800_000L).build()
        assertEquals(FixTime(elapsedNanos = null, wallMillis = 946_684_800_000L, replayed = false), plain.fixTime())
    }
}
