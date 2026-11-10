package io.github.purplepalm.custommaps.nav.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaceTest {

    private val building = GeoPoint(0.0010, 0.0020)
    private val parking = GeoPoint(0.0012, 0.0026)

    @Test
    fun `routes go to the stop point when the place has one`() {
        val place = Place("A large store", "1 Oak Street", building, stopPoint = parking)
        assertEquals(parking, place.routePoint)
        // The place itself stays where it is: the pin and the side of the road are the building's.
        assertEquals(building, place.point)
    }

    @Test
    fun `routes go to the place itself when it has no stop point`() {
        val place = Place("A large store", "1 Oak Street", building)
        assertNull(place.stopPoint)
        assertEquals(building, place.routePoint)
    }

    @Test
    fun `a place made from a name, an address and a point has no stop point and no provider id`() {
        val place = Place("Pine Street", null, building)
        assertNull(place.stopPoint)
        assertNull(place.providerId)
    }

    @Test
    fun `the provider id is kept as given and changes no point`() {
        val place = Place("A large store", null, building, providerId = "id-1")
        assertEquals("id-1", place.providerId)
        assertEquals(building, place.routePoint)
    }
}
