package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's puck between its provider's updates, on synthetic points near 0, 0: one speed through
 * the key points, a new update going on from where the puck is, a jump, and bearings the short way.
 */
class PuckTrackTest {

    /** A track whose puck stands at [lat], [lng] facing [bearing], placed there at time 0. */
    private fun placed(lat: Double = 0.0, lng: Double = 0.0, bearing: Double? = null) = PuckTrack().apply {
        onPosition(doubleArrayOf(lat), doubleArrayOf(lng), 1, SECOND, 0L)
        if (bearing != null) onBearing(doubleArrayOf(bearing), 1, SECOND, 0L)
    }

    private fun PuckTrack.positionAt(time: Long): Pair<Double, Double> {
        at(time)
        return latitude to longitude
    }

    private fun assertAt(expected: Pair<Double, Double>, actual: Pair<Double, Double>) {
        assertEquals(expected.first, actual.first, EPSILON, "latitude")
        assertEquals(expected.second, actual.second, EPSILON, "longitude")
    }

    @Test
    fun `the first position is drawn at once`() {
        val track = PuckTrack()
        track.onPosition(doubleArrayOf(0.0002), doubleArrayOf(0.0001), 1, SECOND, 0L)
        assertAt(0.0002 to 0.0001, track.positionAt(0L))
        assertFalse(track.at(0L))
    }

    @Test
    fun `the first update with several points is drawn at once on its last`() {
        val track = PuckTrack()
        track.onPosition(doubleArrayOf(0.0, 0.0001, 0.0002), doubleArrayOf(0.0, 0.0, 0.0001), 3, SECOND, 0L)
        assertAt(0.0002 to 0.0001, track.positionAt(0L))
        assertFalse(track.at(0L))
    }

    @Test
    fun `a glide through three key points goes at one speed and reaches each at its share of the way`() {
        val track = placed()
        // 0.0001, 0.0002 and 0.0003 degrees: a sixth, a third and a half of the way.
        track.onPosition(doubleArrayOf(0.0, 0.0, 0.0003), doubleArrayOf(0.0001, 0.0003, 0.0003), 3, SECOND, 0L)
        assertAt(0.0 to 0.0, track.positionAt(0L))
        assertAt(0.0 to 0.0001, track.positionAt(SECOND / 6))
        assertAt(0.0 to 0.0003, track.positionAt(SECOND / 2))
        assertAt(0.00015 to 0.0003, track.positionAt(SECOND * 3 / 4))
        assertTrue(track.at(SECOND * 3 / 4))
        assertAt(0.0003 to 0.0003, track.positionAt(SECOND))
        assertFalse(track.at(SECOND))
    }

    @Test
    fun `a new update in the middle of a glide goes on from where the puck is`() {
        val track = placed()
        track.onPosition(doubleArrayOf(0.0), doubleArrayOf(0.001), 1, SECOND, 0L)
        assertAt(0.0 to 0.0005, track.positionAt(SECOND / 2))
        // The next update comes half way: the new glide starts there, never back at the old start.
        track.onPosition(doubleArrayOf(0.001), doubleArrayOf(0.0005), 1, SECOND, SECOND / 2)
        assertAt(0.0 to 0.0005, track.positionAt(SECOND / 2))
        assertAt(0.0005 to 0.0005, track.positionAt(SECOND))
        assertAt(0.001 to 0.0005, track.positionAt(SECOND * 3 / 2))
    }

    @Test
    fun `an update that comes between two ticks starts from where the puck is at that moment`() {
        val track = placed()
        track.onPosition(doubleArrayOf(0.0), doubleArrayOf(0.001), 1, SECOND, 0L)
        track.at(SECOND / 4)
        // No tick at 0.6 s, but the puck was 60 percent of the way there then.
        track.onPosition(doubleArrayOf(0.0), doubleArrayOf(0.002), 1, SECOND, SECOND * 6 / 10)
        assertAt(0.0 to 0.0006, track.positionAt(SECOND * 6 / 10))
        assertAt(0.0 to 0.0013, track.positionAt(SECOND * 11 / 10))
    }

    @Test
    fun `a zero duration puts the puck on the last point at once`() {
        val track = placed()
        track.onPosition(doubleArrayOf(0.0004, 0.0005), doubleArrayOf(0.0004, 0.0005), 2, 0L, SECOND)
        assertAt(0.0005 to 0.0005, track.positionAt(SECOND))
        assertFalse(track.at(SECOND))
    }

    @Test
    fun `a tick from before an update's time keeps the puck where it was then`() {
        val track = placed()
        track.onPosition(doubleArrayOf(0.0), doubleArrayOf(0.001), 1, SECOND, SECOND)
        // A display frame's time can lie a little before the moment the update came.
        assertAt(0.0 to 0.0, track.positionAt(SECOND - 5 * MS))
    }

    @Test
    fun `a glide to where the puck stands is no move`() {
        val track = placed(0.0001, 0.0001)
        track.onPosition(doubleArrayOf(0.0001), doubleArrayOf(0.0001), 1, SECOND, 0L)
        assertFalse(track.at(SECOND / 2))
    }

    @Test
    fun `a bearing across north turns the short way`() {
        val track = placed(bearing = 350.0)
        track.onBearing(doubleArrayOf(10.0), 1, SECOND, 0L)
        track.at(SECOND / 4)
        assertEquals(355.0, track.bearing, EPSILON)
        track.at(SECOND / 2)
        assertEquals(0.0, track.bearing, EPSILON)
        track.at(SECOND * 3 / 4)
        assertEquals(5.0, track.bearing, EPSILON)
        track.at(SECOND)
        assertEquals(10.0, track.bearing, EPSILON)
    }

    @Test
    fun `bearings of the same update are reached with their points`() {
        val track = placed(bearing = 90.0)
        val at = SECOND
        track.onPosition(doubleArrayOf(0.0, 0.0, 0.0003), doubleArrayOf(0.0001, 0.0003, 0.0003), 3, SECOND, at)
        track.onBearing(doubleArrayOf(90.0, 0.0, 45.0), 3, SECOND, at + MS)
        // The first point at a sixth of the time, the second at half of it.
        track.at(at + SECOND / 6)
        assertEquals(90.0, track.bearing, EPSILON)
        track.at(at + SECOND / 2)
        assertEquals(0.0, track.bearing, EPSILON)
        track.at(at + SECOND * 3 / 4)
        assertEquals(22.5, track.bearing, EPSILON)
    }

    @Test
    fun `bearings on their own are spread evenly over their time`() {
        val track = placed(bearing = 0.0)
        track.onBearing(doubleArrayOf(100.0, 200.0), 2, SECOND, 0L)
        track.at(SECOND / 2)
        assertEquals(100.0, track.bearing, EPSILON)
        track.at(SECOND * 3 / 4)
        assertEquals(150.0, track.bearing, EPSILON)
        // Turned the short way at each step: 100 to 200 goes through 150, not through 330.
        track.at(SECOND)
        assertEquals(200.0, track.bearing, EPSILON)
    }

    @Test
    fun `bearings that come later than the positions are not taken for theirs`() {
        val track = placed(bearing = 0.0)
        track.onPosition(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0001, 0.0004), 2, SECOND, 0L)
        track.onBearing(doubleArrayOf(40.0, 80.0), 2, SECOND, 10 * MS)
        // Even shares from 10 ms: 40 degrees half way through the bearings' own time.
        track.at(10 * MS + SECOND / 2)
        assertEquals(40.0, track.bearing, EPSILON)
    }

    @Test
    fun `the first bearing is drawn at once and a zero duration turns at once`() {
        val track = PuckTrack()
        track.onBearing(doubleArrayOf(123.0), 1, SECOND, 0L)
        track.at(0L)
        assertEquals(123.0, track.bearing, EPSILON)
        track.onBearing(doubleArrayOf(250.0), 1, 0L, SECOND)
        assertFalse(track.at(SECOND))
        assertEquals(250.0, track.bearing, EPSILON)
    }

    @Test
    fun `the puck moves while either glide goes on`() {
        val track = placed(bearing = 0.0)
        track.onBearing(doubleArrayOf(30.0), 1, SECOND, 0L)
        assertTrue(track.at(SECOND / 2), "turning")
        assertFalse(track.at(SECOND), "done")
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000 * MS
        const val EPSILON = 1e-9
    }
}
