package io.github.purplepalm.custommaps.nav.model

import io.github.purplepalm.custommaps.nav.geo.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeoPointTest {

    @Test
    fun `accepts coordinates in range`() {
        val point = GeoPoint(37.7749, -122.4194)
        assertEquals(37.7749, point.latitude)
        assertEquals(-122.4194, point.longitude)
    }

    @Test
    fun `rejects coordinates out of range`() {
        assertThrows<IllegalArgumentException> { GeoPoint(90.5, 0.0) }
        assertThrows<IllegalArgumentException> { GeoPoint(0.0, -180.5) }
    }

    @Test
    fun `distanceTo is the great-circle distance in meters`() {
        val distance = GeoPoint(0.0, 0.0).distanceTo(GeoPoint(0.0, 0.001))
        assertEquals(Math.toRadians(0.001) * Geo.EARTH_RADIUS_METERS, distance.meters, 1e-6)
        assertEquals(0.0, GeoPoint(0.001, 0.001).distanceTo(GeoPoint(0.001, 0.001)).meters)
    }
}
