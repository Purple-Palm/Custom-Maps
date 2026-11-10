package io.github.purplepalm.custommaps.nav.geo

import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.PI
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeoTest {

    /** The length of an arc of [degrees] on the Earth sphere: what the haversine must give along a meridian or the equator. */
    private fun arc(degrees: Double) = Math.toRadians(degrees) * Geo.EARTH_RADIUS_METERS

    @Test
    fun `a point is zero meters from itself`() {
        assertEquals(0.0, Geo.meters(0.001, 0.002, 0.001, 0.002))
    }

    @Test
    fun `the distance is the same both ways`() {
        assertEquals(Geo.meters(0.0, 0.0, 0.003, -0.002), Geo.meters(0.003, -0.002, 0.0, 0.0))
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(arc(1.0), Geo.meters(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(111_195.08, Geo.meters(0.0, 0.0, 1.0, 0.0), 0.01)
    }

    @Test
    fun `along the equator a degree of longitude is as long as one of latitude`() {
        assertEquals(arc(0.001), Geo.meters(0.0, 0.0, 0.0, 0.001), 1e-6)
    }

    @Test
    fun `a short diagonal near 0, 0 is the square root of two times the side`() {
        // Within a few hundred meters of the equator the sphere is flat to well under a millimeter.
        assertEquals(arc(0.001) * sqrt(2.0), Geo.meters(0.0, 0.0, 0.001, 0.001), 1e-3)
    }

    @Test
    fun `the distance takes the short way across the 180th meridian`() {
        assertEquals(arc(0.001), Geo.meters(0.0, 179.9995, 0.0, -179.9995), 1e-6)
    }

    @Test
    fun `opposite points are half the circumference apart`() {
        assertEquals(PI * Geo.EARTH_RADIUS_METERS, Geo.meters(0.0, 0.0, 0.0, 180.0), 1e-6)
    }

    @Test
    fun `the point overload gives the same distance`() {
        assertEquals(
            Geo.meters(0.0, 0.0, 0.0005, 0.0005),
            Geo.meters(GeoPoint(0.0, 0.0), GeoPoint(0.0005, 0.0005)),
        )
    }
}
