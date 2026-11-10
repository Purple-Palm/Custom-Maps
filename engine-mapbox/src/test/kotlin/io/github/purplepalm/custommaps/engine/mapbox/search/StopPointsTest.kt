package io.github.purplepalm.custommaps.engine.mapbox.search

import io.github.purplepalm.custommaps.engine.mapbox.search.StopPoints.Candidate
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StopPointsTest {

    /** The place: a point in the sea, where a degree of longitude is as long as one of latitude. */
    private val place = GeoPoint(0.0, 0.0)

    /** How long [degrees] along the equator or along a meridian are, in meters. */
    private fun metersOf(degrees: Double): Double = Math.toRadians(degrees) * Geo.EARTH_RADIUS_METERS

    @Test
    fun `the only routable point is taken, with how far it is from the place`() {
        val choice = StopPoints.choose(listOf(Candidate("POI", 0.0, 0.001)), place)

        assertEquals(GeoPoint(0.0, 0.001), choice?.point)
        assertEquals("POI", choice?.name)
        assertEquals(0, choice?.index)
        // About 111 m.
        assertEquals(metersOf(0.001), choice!!.metersFromPlace, 1e-6)
    }

    @Test
    fun `the first good one wins`() {
        val choice = StopPoints.choose(
            listOf(Candidate("Address", 0.0005, 0.0), Candidate("POI", 0.0, 0.0005), Candidate("default", 0.0002, 0.0)),
            place,
        )

        assertEquals(GeoPoint(0.0005, 0.0), choice?.point)
        assertEquals("Address", choice?.name)
        assertEquals(0, choice?.index)
    }

    @Test
    fun `a door is passed over, in any letter case`() {
        for (door in listOf("entrance", "Entrance", "ENTRANCE", "eNtRaNcE", " entrance ")) {
            val choice = StopPoints.choose(listOf(Candidate(door, 0.0, 0.0001), Candidate("Address", 0.0, 0.0004)), place)

            assertEquals(GeoPoint(0.0, 0.0004), choice?.point, door)
            assertEquals(1, choice?.index, door)
        }
    }

    @Test
    fun `a place with doors only has no stop point`() {
        assertNull(StopPoints.choose(listOf(Candidate("entrance", 0.0, 0.0001), Candidate("Entrance", 0.0001, 0.0)), place))
    }

    @Test
    fun `no list of good names is kept`() {
        for (name in listOf("POI", "Address", "default", "parking", "", "Main gate")) {
            assertEquals(GeoPoint(0.0, 0.0003), StopPoints.choose(listOf(Candidate(name, 0.0, 0.0003)), place)?.point, name)
        }
    }

    @Test
    fun `a name that only holds the word is no door`() {
        assertEquals(0, StopPoints.choose(listOf(Candidate("entrance road", 0.0, 0.0003)), place)?.index)
    }

    @Test
    fun `a far one is ignored and the next good one is taken`() {
        // About 2.2 km away, then about 220 m.
        val choice = StopPoints.choose(listOf(Candidate("POI", 0.0, 0.02), Candidate("Address", 0.002, 0.0)), place)

        assertEquals(GeoPoint(0.002, 0.0), choice?.point)
        assertEquals(1, choice?.index)
    }

    @Test
    fun `a far one alone gives no stop point`() {
        assertNull(StopPoints.choose(listOf(Candidate("default", 0.0, 0.02)), place))
    }

    @Test
    fun `one kilometer is the limit`() {
        // 0.00899 degrees are about 999.6 m, 0.009 degrees about 1,000.8 m.
        val inside = 0.00899
        val outside = 0.009
        assertTrue(metersOf(inside) < StopPoints.MAX_METERS && metersOf(outside) > StopPoints.MAX_METERS)

        assertEquals(GeoPoint(0.0, inside), StopPoints.choose(listOf(Candidate("POI", 0.0, inside)), place)?.point)
        assertNull(StopPoints.choose(listOf(Candidate("POI", 0.0, outside)), place))
        assertNull(StopPoints.choose(listOf(Candidate("POI", outside, 0.0)), place))
    }

    @Test
    fun `the distance is measured from the place, wherever that is`() {
        val north = GeoPoint(0.5, 0.5)
        // 0.001 degrees of latitude from the place, and far from the point 0, 0.
        val choice = StopPoints.choose(listOf(Candidate("POI", 0.501, 0.5)), north)

        assertEquals(metersOf(0.001), choice!!.metersFromPlace, 1e-3)
        assertNull(StopPoints.choose(listOf(Candidate("POI", 0.0, 0.0)), north))
    }

    @Test
    fun `an empty list and no list give no stop point`() {
        assertNull(StopPoints.choose(emptyList(), place))
        assertNull(StopPoints.choose(null, place))
    }

    @Test
    fun `numbers that are no coordinate are passed over`() {
        val bad = listOf(
            Candidate("POI", Double.NaN, 0.0),
            Candidate("POI", 0.0, Double.NaN),
            Candidate("POI", 90.5, 0.0),
            Candidate("POI", 0.0, 180.5),
            Candidate("POI", Double.POSITIVE_INFINITY, 0.0),
        )
        assertNull(StopPoints.choose(bad, place))

        val choice = StopPoints.choose(bad + Candidate("Address", 0.0, 0.0002), place)
        assertEquals(GeoPoint(0.0, 0.0002), choice?.point)
        assertEquals(bad.size, choice?.index)
    }

    @Test
    fun `a routable point at the place itself is a stop point`() {
        val choice = StopPoints.choose(listOf(Candidate("Address", 0.0, 0.0)), place)

        assertEquals(place, choice?.point)
        assertEquals(0.0, choice!!.metersFromPlace)
    }
}
