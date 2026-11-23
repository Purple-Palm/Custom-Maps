package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.log2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's road view by speed on an invented head unit: a surface 800 px wide and 400 px high at one pixel a dp, the
 * host's stable area 88 px from the top, 12 px from the bottom and 88 px from the right, and the car a quarter up that
 * area (CarAreas): the following padding is 93 px at the top and 87 px at the bottom. Latitude 35, synthetic.
 */
class RoadViewTest {
    private val car = CameraTuning.CAR
    private val height = 400.0
    private val top = 93.0
    private val carRow = 400.0 - 87.0
    private val latitude = 35.0
    private val kmh = 1.0 / 3.6

    private fun zoom(speedMps: Double?, ratio: Double = 1.5) = RoadView.zoom(car, speedMps, height, carRow, top, ratio, latitude)

    @Test
    fun `at one and a half times the view is closest below 45 km per h and farther out with speed`() {
        // The 150 m floor below 45 km/h (12 s of 12.5 m/s); then 12 s of driving; the 600 m ceiling above 180 km/h.
        val table = listOf(
            0.0 to 17.81,
            20.0 to 17.81,
            45.0 to 17.81,
            50.0 to 17.66,
            90.0 to 16.81,
            110.0 to 16.52,
            130.0 to 16.28,
            180.0 to 15.81,
            250.0 to 15.81,
        )
        for ((speed, expected) in table) assertEquals(expected, zoom(speed * kmh), 0.05, "$speed km/h")
        assertEquals(150.0, RoadView.aheadMeters(car, 45.0 * kmh), 1e-9)
        assertEquals(600.0, RoadView.aheadMeters(car, 250.0 * kmh), 1e-9)
    }

    @Test
    fun `the view shows the road it is worked out for, at the car's tilt`() {
        for (speed in listOf(0.0, 13.0, 25.0, 33.0, 60.0)) {
            for (ratio in listOf(1.0, 1.5, 2.0)) {
                val zoom = zoom(speed, ratio)
                val ahead = LookAhead.metersAhead(zoom, car.cruisePitch, height, carRow, top, ratio, latitude)
                assertEquals(RoadView.aheadMeters(car, speed), ahead, 1e-6, "$speed m/s at x$ratio")
            }
        }
        assertEquals(55.0, car.cruisePitch)
    }

    @Test
    fun `the map's pixel ratio moves the same road by its logarithm`() {
        for (speed in listOf(0.0, 25.0)) {
            assertEquals(log2(1.5), zoom(speed, ratio = 1.0) - zoom(speed, ratio = 1.5), 1e-9)
            assertEquals(log2(2.0 / 1.5), zoom(speed, ratio = 1.5) - zoom(speed, ratio = 2.0), 1e-9)
        }
    }

    @Test
    fun `a speed that is missing or no speed counts as standing`() {
        val standing = RoadView.aheadMeters(car, 0.0)
        assertEquals(150.0, standing)
        for (speed in listOf(null, -3.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(standing, RoadView.aheadMeters(car, speed), "$speed")
            assertEquals(zoom(0.0), zoom(speed), 1e-12, "$speed")
        }
    }

    @Test
    fun `no screen to fit gives no zoom, and the zoom never passes the closest`() {
        assertTrue(RoadView.zoom(car, 10.0, 0.0, carRow, top, 1.5, latitude).isNaN())
        assertTrue(RoadView.zoom(car, 10.0, height, 200.0, 200.0, 1.5, latitude).isNaN())
        assertTrue(RoadView.zoom(car, 10.0, height, carRow, top, 0.0, latitude).isNaN())
        assertTrue(RoadView.zoom(car, 10.0, height, carRow, top, 1.5, Double.NaN).isNaN())
        // A pixel ratio far under any screen's asks for a zoom past the closest one.
        assertEquals(car.maxZoom, RoadView.zoom(car, 0.0, height, carRow, top, 0.01, latitude))
    }

    @Test
    fun `the car's view before it had a camera of its own showed some 650 m at zoom 15 point 3`() {
        // At x1 and 45 degrees, the car a third up its area: the following padding's bottom was 12 + 100 px.
        val before = LookAhead.metersAhead(15.3, 45.0, height, 400.0 - 112.0, top, 1.0, latitude)
        assertEquals(650.0, before, 5.0)
        assertEquals(2.3, MapScale.laneDp(15.3, latitude), 0.05)
    }
}
