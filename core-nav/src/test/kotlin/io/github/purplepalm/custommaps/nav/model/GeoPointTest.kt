package io.github.purplepalm.custommaps.nav.model

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
}
