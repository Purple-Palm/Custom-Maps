package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.format.DisplayDistance
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteEndNoteTest {

    private val stopPoint = GeoPoint(0.0012, 0.0026)

    private fun gap(meters: Double) = Distance.meters(meters)

    @Test
    fun `a route that ends 49 m from the place gets no note`() {
        assertNull(routeEndNote(stopPoint = null, endGap = gap(49.0)))
    }

    @Test
    fun `a route that ends 51 m from the place gets a note`() {
        // 51 m are 167 ft, shown to the nearest 50 ft.
        assertEquals(DisplayDistance("150", "ft"), routeEndNote(stopPoint = null, endGap = gap(51.0)))
    }

    @Test
    fun `50 m itself gets no note`() {
        assertNull(routeEndNote(stopPoint = null, endGap = gap(50.0)))
        assertNull(routeEndNote(stopPoint = null, endGap = ROUTE_END_NOTE_ABOVE))
    }

    @Test
    fun `a place with a stop point never gets a note`() {
        for (meters in listOf(0.0, 49.0, 51.0, 300.0, 5_000.0)) {
            assertNull(routeEndNote(stopPoint, gap(meters)), "$meters m")
        }
    }

    @Test
    fun `an unknown end gets no note`() {
        assertNull(routeEndNote(stopPoint = null, endGap = null))
        assertNull(routeEndNote(stopPoint, endGap = null))
    }

    @Test
    fun `a route that ends at the place gets no note`() {
        assertNull(routeEndNote(stopPoint = null, endGap = Distance.ZERO))
    }

    @Test
    fun `the note gives the distance as every other distance is shown`() {
        // 70 m are 230 ft, 300 m are 984 ft, which is 0.2 mi.
        assertEquals("250 ft", routeEndNote(null, gap(70.0)).toString())
        assertEquals("0.2 mi", routeEndNote(null, gap(300.0)).toString())
        assertEquals("1.2 mi", routeEndNote(null, gap(2_000.0)).toString())
    }
}
