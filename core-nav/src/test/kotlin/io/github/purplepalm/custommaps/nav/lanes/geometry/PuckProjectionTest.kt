package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.cos

/**
 * The app's own projection of the puck on invented lines along longitude 0 from 0, 0, a point every 100 m. Along a
 * meridian a station of s metres lies at latitude s / [METERS_PER_DEGREE], exactly as [Geo.meters] measures it.
 */
class PuckProjectionTest {

    private val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

    /** The line through the stations [along], each the metres north of 0, 0 at which a point lies. */
    private fun line(along: List<Double>): PuckProjection {
        val points = along.map { GeoPoint(it / metersPerDegree, 0.0) }
        val stations = DoubleArray(points.size)
        for (i in 1 until points.size) stations[i] = stations[i - 1] + Geo.meters(points[i - 1], points[i])
        return PuckProjection.of(points, stations)
    }

    /** North from 0, 0 to 2,000 m, a point every 100 m. */
    private val north = line((0..20).map { it * 100.0 })

    /** The station of a puck [east] metres east (west when negative) of the line at [station] metres north. */
    private fun PuckProjection.at(station: Double, east: Double, near: Double): Double? {
        val latitude = station / metersPerDegree
        return station(latitude, east / (metersPerDegree * cos(Math.toRadians(latitude))), near)
    }

    @Test
    fun `a puck beside the line is placed at its own station`() {
        assertEquals(537.5, north.at(537.5, 3.0, near = 500.0)!!, 1e-6)
        assertEquals(537.5, north.at(537.5, -3.0, near = 500.0)!!, 1e-6)
        assertEquals(1_250.0, north.at(1_250.0, 0.0, near = 1_200.0)!!, 1e-6)
    }

    @Test
    fun `a puck more than 30 m from the line is not on it`() {
        assertEquals(30.0, PuckProjection.MAX_OFF_LINE_METERS)
        assertEquals(700.0, north.at(700.0, 29.9, near = 650.0)!!, 1e-6)
        assertEquals(700.0, north.at(700.0, -29.9, near = 650.0)!!, 1e-6)
        assertNull(north.at(700.0, 30.1, near = 650.0))
        assertNull(north.at(700.0, -30.1, near = 650.0))
    }

    @Test
    fun `the window reaches from the point 100 m behind the trim to the point after 1,000 m ahead of it`() {
        assertEquals(100.0, PuckProjection.BACK_METERS)
        assertEquals(1_000.0, PuckProjection.AHEAD_METERS)
        // Trimmed at 500 m: the window runs from the point at 400 m to the one at 1,600 m.
        assertEquals(401.0, north.at(401.0, 0.0, near = 500.0)!!, 1e-6)
        assertNull(north.at(399.0, 0.0, near = 500.0), "behind the window: its first point is no projection")
        assertEquals(1_599.0, north.at(1_599.0, 0.0, near = 500.0)!!, 1e-6)
        assertNull(north.at(1_601.0, 0.0, near = 500.0), "ahead of the window")
        // Trimmed at 520 m, the window begins at the point at or before 420 m: the same one.
        assertEquals(401.0, north.at(401.0, 0.0, near = 520.0)!!, 1e-6)
    }

    @Test
    fun `at the line's own ends the place is the end`() {
        // 10 m south of the first point: the line begins there, so its start is the place.
        assertEquals(0.0, north.at(-10.0, 0.0, near = 50.0)!!, 1e-9)
        assertEquals(2_000.0, north.at(2_010.0, 0.0, near = 1_950.0)!!, 1e-6)
        assertNull(north.at(-40.0, 0.0, near = 50.0), "40 m off the line's start")
    }

    @Test
    fun `of two places as near, the one nearer to the trim wins`() {
        // Up to 200 m and back down the same way: 100 m north is both station 100 and station 300.
        val back = line(listOf(0.0, 100.0, 200.0, 100.0, 0.0))
        assertEquals(100.0, back.at(100.0, 0.0, near = 110.0)!!, 1e-6)
        assertEquals(300.0, back.at(100.0, 0.0, near = 290.0)!!, 1e-6)
    }

    @Test
    fun `a line of one point, or no station to look around, places nothing`() {
        assertNull(line(listOf(0.0)).at(0.0, 0.0, near = 0.0))
        assertNull(north.at(500.0, 0.0, near = Double.NaN))
    }
}
