package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Place
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Synthetic places in the sea near the point 0, 0. */
class RouteEndGapTest {

    private val building = GeoPoint(0.0, 0.0)
    private val parking = GeoPoint(0.0, 0.002)

    /** How long [degrees] along the equator are, in meters. */
    private fun metersOf(degrees: Double): Double = Math.toRadians(degrees) * Geo.EARTH_RADIUS_METERS

    @Test
    fun `the gap is the distance from the place to the end of the route`() {
        val place = Place("A large store", null, building)
        // The route ends 0.0006 degrees east of the place: about 67 m.
        val gap = endGap(place, Point.fromLngLat(0.0006, 0.0))

        assertEquals(metersOf(0.0006), gap!!.meters, 1e-6)
    }

    @Test
    fun `with a stop point the gap is still measured from the place itself`() {
        val place = Place("A large store", null, building, stopPoint = parking)
        // The route ends at the stop point, 0.002 degrees from the building: about 222 m.
        val gap = endGap(place, Point.fromLngLat(parking.longitude, parking.latitude))

        assertEquals(metersOf(0.002), gap!!.meters, 1e-6)
    }

    @Test
    fun `a route that ends at the place has no gap`() {
        assertEquals(0.0, endGap(Place("Pine Street", null, building), Point.fromLngLat(0.0, 0.0))!!.meters)
    }

    @Test
    fun `an unknown end gives no gap`() {
        assertNull(endGap(Place("Pine Street", null, building), routeEnd = null))
    }

    @Test
    fun `an end that is no coordinate gives no gap`() {
        assertNull(endGap(Place("Pine Street", null, building), Point.fromLngLat(Double.NaN, 0.0)))
        assertNull(endGap(Place("Pine Street", null, building), Point.fromLngLat(0.0, Double.NaN)))
    }
}
