package io.github.purplepalm.custommaps.nav.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalMetersTest {

    /** One degree of a great circle on the app's Earth sphere. */
    private val degree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

    @Test
    fun `the origin is at zero`() {
        assertEquals(LocalPoint(0.0, 0.0), LocalMeters(0.001, 0.002).toLocal(0.001, 0.002))
    }

    @Test
    fun `north and east are meters along the sphere`() {
        val near = LocalMeters(0.0, 0.0)
        assertEquals(LocalPoint(0.0, degree * 0.001), near.toLocal(0.001, 0.0))
        assertEquals(degree * 0.001, near.toLocal(0.0, 0.001).x, 1e-9)
        // A degree of longitude shrinks with the cosine of the latitude.
        assertEquals(degree * 0.001 / 2.0, LocalMeters(60.0, 0.0).toLocal(60.0, 0.001).x, 1e-6)
    }

    @Test
    fun `a point taken there and back is the same point`() {
        val local = LocalMeters(0.0004, -0.0003)
        val p = local.toLocal(0.0011, 0.0007)
        assertEquals(0.0011, local.latitudeOf(p), 1e-15)
        assertEquals(0.0007, local.longitudeOf(p), 1e-15)
    }

    @Test
    fun `distances near a junction agree with the sphere to the centimeter`() {
        val local = LocalMeters(0.001, 0.001)
        val a = local.toLocal(0.0012, 0.0005)
        val b = local.toLocal(0.0002, 0.0016)
        assertEquals(Geo.meters(0.0012, 0.0005, 0.0002, 0.0016), a.distanceTo(b), 0.01)
    }

    @Test
    fun `east stays east across the 180th meridian`() {
        val local = LocalMeters(0.0, 179.9995)
        val p = local.toLocal(0.0, -179.9995)
        assertEquals(degree * 0.001, p.x, 1e-6)
        assertEquals(-179.9995, local.longitudeOf(p), 1e-9)
    }

    @Test
    fun `a line's length and its first meters`() {
        val line = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 10.0), LocalPoint(10.0, 10.0))
        assertEquals(20.0, LocalLine.length(line))
        assertEquals(listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 10.0), LocalPoint(5.0, 10.0)), LocalLine.head(line, 15.0))
        assertEquals(listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 4.0)), LocalLine.head(line, 4.0))
        // A line shorter than what is asked for comes whole.
        assertEquals(line, LocalLine.head(line, 50.0))
        assertEquals(listOf(LocalPoint(0.0, 0.0)), LocalLine.head(line, 0.0))
    }

    @Test
    fun `a line's last meters end with its last point`() {
        val line = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 10.0), LocalPoint(10.0, 10.0))
        assertEquals(listOf(LocalPoint(0.0, 7.0), LocalPoint(0.0, 10.0), LocalPoint(10.0, 10.0)), LocalLine.tail(line, 13.0))
        assertEquals(LocalPoint(10.0, 10.0), LocalLine.pointAt(line, 25.0))
    }

    @Test
    fun `a point repeated on a line adds nothing to it`() {
        val line = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 5.0), LocalPoint(0.0, 5.0), LocalPoint(0.0, 10.0))
        assertEquals(LocalPoint(0.0, 5.0), LocalLine.pointAt(line, 5.0))
        assertEquals(LocalPoint(0.0, 7.0), LocalLine.pointAt(line, 7.0))
    }

    @Test
    fun `bearings are clockwise from north`() {
        val o = LocalPoint(0.0, 0.0)
        assertEquals(0.0, LocalLine.bearing(o, LocalPoint(0.0, 1.0)))
        assertEquals(90.0, LocalLine.bearing(o, LocalPoint(1.0, 0.0)))
        assertEquals(180.0, LocalLine.bearing(o, LocalPoint(0.0, -1.0)))
        assertEquals(270.0, LocalLine.bearing(o, LocalPoint(-1.0, 0.0)))
    }
}
