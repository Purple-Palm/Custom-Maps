package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Invented positions in the sea near the point 0, 0. */
class PuckGlideTest {

    @Test
    fun `a moving car glides on along its heading for one glide`() {
        // 20 m/s due east for 1.1 s: 22 m east of the new position.
        val ahead = PuckGlide.ahead(0.0, 0.01, bearing = 90.0, speed = 20.0, seconds = 1.1)!!

        assertEquals(22.0, Geo.meters(0.0, 0.01, ahead.latitude, ahead.longitude), 0.01)
        assertEquals(0.0, ahead.latitude, 1e-9)
        assertEquals(0.01 + 22.0 / Geo.meters(0.0, 0.0, 0.0, 1.0), ahead.longitude, 1e-9)
    }

    @Test
    fun `the heading decides the way`() {
        val north = PuckGlide.ahead(0.0, 0.0, bearing = 0.0, speed = 10.0, seconds = 1.0)!!
        val south = PuckGlide.ahead(0.0, 0.0, bearing = 180.0, speed = 10.0, seconds = 1.0)!!

        assertEquals(10.0, Geo.meters(0.0, 0.0, north.latitude, north.longitude), 0.01)
        assertEquals(0.0, north.longitude, 1e-12)
        assertEquals(-north.latitude, south.latitude, 1e-12)
    }

    @Test
    fun `a car that stands, or has no speed or heading, stays where it was put`() {
        assertNull(PuckGlide.ahead(0.0, 0.0, bearing = 90.0, speed = 0.9, seconds = 1.1))
        assertNotNull(PuckGlide.ahead(0.0, 0.0, bearing = 90.0, speed = 1.0, seconds = 1.1))
        assertNull(PuckGlide.ahead(0.0, 0.0, bearing = null, speed = 20.0, seconds = 1.1))
        assertNull(PuckGlide.ahead(0.0, 0.0, bearing = 90.0, speed = null, seconds = 1.1))
        assertNull(PuckGlide.ahead(0.0, 0.0, bearing = Double.NaN, speed = 20.0, seconds = 1.1))
        assertNull(PuckGlide.ahead(0.0, 0.0, bearing = 90.0, speed = 20.0, seconds = 0.0))
    }
}
