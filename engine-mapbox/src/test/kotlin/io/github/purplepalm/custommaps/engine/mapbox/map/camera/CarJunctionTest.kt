package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.JunctionFit
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.min
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The junction the car's camera frames itself, on made-up roads near 0, 0 at latitude 35 (synthetic): the car comes in
 * heading north, the maneuver point at 0, 0. The screen: an invented head unit's 800 x 400 px surface with the following
 * box from row 93 to the car's row 313 and 179 px to either side, at pixel ratio 1.5.
 */
class CarJunctionTest {

    private val car = CameraTuning.CAR
    private val local = LocalMeters(35.0, 0.0)

    private fun at(x: Double, y: Double): Point {
        val p = LocalPoint(x, y)
        return Point.fromLngLat(local.longitudeOf(p), local.latitudeOf(p))
    }

    private fun zoomFrom(junction: CarJunction, metersBefore: Double, bearing: Double = 0.0, lagMeters: Double = 0.0): Double {
        val carAt = at(0.0, -metersBefore)
        return junction.zoomAt(carAt.latitude(), carAt.longitude(), bearing, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, lagMeters)
    }

    @Test
    fun `a right turn holds the maneuver point and 30 m of the road east`() {
        val junction = CarJunction()
        junction.hold(listOf(at(0.0, 0.0), at(2_000.0, 0.0)), bearingBefore = 0.0, car)
        assertTrue(junction.points >= 2)
        for (before in listOf(150.0, 100.0, 40.0, 5.0)) {
            val expected = min(
                JunctionFit.zoomFor(before, 0.0, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0),
                JunctionFit.zoomFor(before, 30.0, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0),
            )
            assertEquals(expected, zoomFrom(junction, before), 1e-4, "$before m before")
        }
    }

    @Test
    fun `the maneuver point lies as far ahead as the car is before it, along the view's bearing`() {
        val junction = CarJunction()
        assertTrue(junction.metersAhead(35.0, 0.0, 0.0).isNaN(), "no junction")
        junction.hold(listOf(at(0.0, 0.0), at(2_000.0, 0.0)), bearingBefore = 0.0, car)
        for (before in listOf(250.0, 120.0, 5.0)) {
            val carAt = at(0.0, -before)
            assertEquals(before, junction.metersAhead(carAt.latitude(), carAt.longitude(), 0.0), 1e-3, "$before m before")
            // The view turned 20 degrees: less of it ahead along the view.
            assertEquals(before * Math.cos(Math.toRadians(20.0)), junction.metersAhead(carAt.latitude(), carAt.longitude(), 20.0), 1e-3)
        }
        // Past it: behind.
        val past = at(0.0, 10.0)
        assertEquals(-10.0, junction.metersAhead(past.latitude(), past.longitude(), 0.0), 1e-3)
        junction.clear()
        assertTrue(junction.metersAhead(35.0, 0.0, 0.0).isNaN())
    }

    @Test
    fun `a turn bay's bend 40 m on holds the road to 30 m past the bend`() {
        val junction = CarJunction()
        junction.hold(listOf(at(0.0, 0.0), at(0.0, 40.0), at(2_000.0, 40.0)), bearingBefore = 0.0, car)
        // The farthest point ahead is the bend, 40 m past the maneuver point; the farthest to the side 30 m past it.
        val expected = min(
            JunctionFit.zoomFor(100.0 + 40.0, 0.0, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0),
            JunctionFit.zoomFor(100.0 + 40.0, 30.0, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0),
        )
        assertEquals(expected, zoomFrom(junction, 100.0), 1e-4)
    }

    @Test
    fun `the view's bearing turns the points with it`() {
        val junction = CarJunction()
        junction.hold(listOf(at(0.0, 0.0), at(0.0, 2_000.0)), bearingBefore = 0.0, car)
        // Straight on: 30 m past the point straight ahead.
        val ahead = JunctionFit.zoomFor(130.0, 0.0, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0)
        assertEquals(ahead, zoomFrom(junction, 100.0), 1e-4)
        // The view turned 20 degrees right: the points lie to the left of the car's column.
        val turned = (0..3).minOf { i ->
            val d = 100.0 + i * 10.0
            JunctionFit.zoomFor(d * Math.cos(Math.toRadians(20.0)), -d * Math.sin(Math.toRadians(20.0)), 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0)
        }
        assertEquals(turned, zoomFrom(junction, 100.0, bearing = 20.0), 1e-4)
        // Turned around, every point lies behind the car's row: none limits the zoom.
        assertEquals(Double.POSITIVE_INFINITY, zoomFrom(junction, 100.0, bearing = 180.0))
    }

    @Test
    fun `each point ahead counts as far farther as the camera may trail the car, a point behind it none`() {
        val junction = CarJunction()
        junction.hold(listOf(at(0.0, 0.0), at(0.0, 2_000.0)), bearingBefore = 0.0, car)
        // Straight on, 100 m before: the maneuver point and the road 30 m past it, each 3 m farther from the camera's center.
        val lagged = listOf(100.0, 130.0).minOf { JunctionFit.zoomFor(it + 3.0, 0.0, 55.0, 400.0, 313.0, 93.0, 179.0, 1.5, 35.0) }
        assertEquals(lagged, zoomFrom(junction, 100.0, lagMeters = 3.0), 1e-4)
        assertTrue(lagged < zoomFrom(junction, 100.0) - 0.01, "the trailing camera needs a wider view")
        // A meter past a right turn its points lie behind the car's row, nearer than the lag: they still limit nothing.
        val turned = CarJunction()
        turned.hold(listOf(at(0.0, 0.0), at(2_000.0, 0.0)), bearingBefore = 0.0, car)
        assertEquals(Double.POSITIVE_INFINITY, zoomFrom(turned, -1.0))
        assertEquals(Double.POSITIVE_INFINITY, zoomFrom(turned, -1.0, lagMeters = 3.0))
    }

    @Test
    fun `without a junction there is no fit`() {
        val junction = CarJunction()
        assertTrue(zoomFrom(junction, 100.0).isNaN())
        junction.hold(emptyList(), bearingBefore = 0.0, car)
        assertEquals(0, junction.points)
        assertTrue(zoomFrom(junction, 100.0).isNaN())
        junction.hold(listOf(at(0.0, 0.0), at(500.0, 0.0)), bearingBefore = null, car)
        assertTrue(junction.points > 0)
        junction.clear()
        assertEquals(0, junction.points)
        assertEquals(null, junction.key)
    }
}
