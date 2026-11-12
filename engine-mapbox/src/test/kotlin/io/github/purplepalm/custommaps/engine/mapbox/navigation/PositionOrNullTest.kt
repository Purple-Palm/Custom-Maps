package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Synthetic values only: the point 0, 0 and the edges of the coordinate ranges. */
class PositionOrNullTest {

    @Test
    fun `a coordinate becomes a position`() {
        assertEquals(GeoPoint(0.0, 0.0), positionOrNull(0.0, 0.0))
        assertEquals(GeoPoint(-0.5, 0.25), positionOrNull(-0.5, 0.25))
        assertEquals(GeoPoint(90.0, -180.0), positionOrNull(90.0, -180.0))
    }

    @Test
    fun `values that are no coordinate give no position and no exception`() {
        assertNull(positionOrNull(90.5, 0.0))
        assertNull(positionOrNull(0.0, 180.5))
        assertNull(positionOrNull(Double.NaN, 0.0))
        assertNull(positionOrNull(0.0, Double.POSITIVE_INFINITY))
    }
}
