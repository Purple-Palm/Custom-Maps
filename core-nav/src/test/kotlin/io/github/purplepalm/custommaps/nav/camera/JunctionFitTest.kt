package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A junction's own fit on an invented head unit: a surface 800 x 400 px, the following box from row 93 to the car's row
 * 313 (the stable area 88 px from the top and 12 from the bottom, the car a quarter up), from 17 px to 707 px across
 * (half-width 345 px), or from 349 px while the routing card shows (half-width 179 px). Pixel ratio 1.5, latitude 35,
 * the car's tilt of 55 degrees; roads made up in local meters around a maneuver point at 0, 0.
 */
class JunctionFitTest {
    private val car = CameraTuning.CAR
    private val height = 400.0
    private val top = 93.0
    private val carRow = 313.0
    private val wide = 345.0
    private val withCard = 179.0
    private val ratio = 1.5
    private val latitude = 35.0

    private fun fit(ahead: Double, side: Double, halfWidth: Double = wide) =
        JunctionFit.zoomFor(ahead, side, car.cruisePitch, height, carRow, top, halfWidth, ratio, latitude)

    /** Where a point [ahead] and [side] meters from the car shows at [zoom]: its row, and its pixels from the car's column. */
    private fun shown(ahead: Double, side: Double, zoom: Double): Pair<Double, Double> {
        val f = 0.5 * height / tan(LookAhead.FIELD_OF_VIEW_RADIANS / 2.0)
        val perPx = MapScale.metersPerDp(zoom, latitude) / ratio
        val a = ahead / perPx
        val s = side / perPx
        val pitch = Math.toRadians(car.cruisePitch)
        val scale = f / (f + a * sin(pitch))
        return (carRow - a * kotlin.math.cos(pitch) * scale) to (s * scale)
    }

    @Test
    fun `a point straight ahead fits as far as the box's top reaches`() {
        for (ahead in listOf(10.0, 40.0, 137.0, 300.0)) {
            assertEquals(LookAhead.zoomFor(ahead, 55.0, height, carRow, top, ratio, latitude), fit(ahead, 0.0), 1e-12, "$ahead m")
            val (row, column) = shown(ahead, 0.0, fit(ahead, 0.0))
            assertEquals(top, row, 1e-6, "$ahead m on the box's top")
            assertEquals(0.0, column, 1e-12)
        }
    }

    @Test
    fun `a point to the side fits within the box's half-width at the car's scale, and on the screen too`() {
        for ((ahead, side) in listOf(0.0 to 30.0, 20.0 to 30.0, 40.0 to -30.0, 40.0 to 45.0, 120.0 to 30.0)) {
            for (halfWidth in listOf(wide, withCard)) {
                val zoom = fit(ahead, side, halfWidth)
                val (row, column) = shown(ahead, side, zoom)
                assertTrue(row >= top - 1e-6, "$ahead, $side inside the top")
                // At the zoom found the side is the half-width at the car's scale, or the point is on the box's top.
                val atCarScale = abs(side) / (MapScale.metersPerDp(zoom, latitude) / ratio)
                assertTrue(abs(atCarScale - halfWidth) < 1e-6 || abs(row - top) < 1e-6, "$ahead, $side at $halfWidth px: row $row, side $atCarScale px")
                // The perspective draws it nearer the car's column, inside the box.
                assertTrue(abs(column) <= halfWidth + 1e-6, "$ahead, $side at $halfWidth px: column $column")
            }
        }
        // Either side alike, and the side does not depend on how far ahead the point is: the fit only comes closer on
        // the way in.
        assertEquals(fit(40.0, 30.0, withCard), fit(40.0, -30.0, withCard), 1e-12)
        assertEquals(fit(0.0, 30.0, withCard), fit(40.0, 30.0, withCard), 1e-12)
        var last = Double.NEGATIVE_INFINITY
        for (ahead in listOf(300.0, 200.0, 120.0, 80.0, 40.0, 10.0, 0.0)) {
            val zoom = minOf(fit(ahead, 0.0), fit(ahead, 30.0, withCard))
            assertTrue(zoom >= last, "$ahead m: $zoom after $last")
            last = zoom
        }
    }

    @Test
    fun `the car's own row and what lies behind it limit nothing`() {
        val none = Double.POSITIVE_INFINITY
        assertEquals(none, fit(0.0, 0.0))
        assertEquals(none, fit(-15.0, 0.0))
        assertEquals(none, fit(-15.0, 40.0))
        assertEquals(none, fit(Double.NaN, 0.0))
        assertEquals(none, fit(20.0, Double.NaN))
        assertEquals(none, JunctionFit.zoomFor(20.0, 10.0, 55.0, 0.0, carRow, top, wide, ratio, latitude))
        assertEquals(none, JunctionFit.zoomFor(0.0, 10.0, 55.0, height, carRow, top, 0.0, ratio, latitude))
    }

    @Test
    fun `at 50 km per h the view stays on the road view until about 140 m before a junction ahead`() {
        // The road view at 50 km/h shows 166.7 m; a junction straight on with the road 30 m past it reaches that at 137 m.
        val road = RoadView.zoom(car, 50.0 / 3.6, height, carRow, top, ratio, latitude)
        fun straightOn(d: Double) = minOf(fit(d, 0.0), fit(d + car.largeMetersAfterManeuver, 0.0))
        assertEquals(road, straightOn(136.7), 0.01)
        assertTrue(straightOn(150.0) < road)
        // And the 28 dp lane's zoom, 18.89, some 40 m before it.
        val lane28 = MapScale.zoomForLaneDp(car.junctionLaneDp, latitude)
        assertEquals(18.89, lane28, 0.01)
        assertEquals(lane28, straightOn(41.3), 0.01)
        // A right-angle turn: the maneuver point is the farthest ahead and the road past it lies to the side, where the
        // wide box holds it a little closer than the 28 dp lane, and the routing card's narrow one only up to zoom 17.96,
        // from about 135 m before the turn.
        fun rightTurn(d: Double, halfWidth: Double) = minOf(fit(d, 0.0), fit(d, car.largeMetersAfterManeuver, halfWidth))
        assertEquals(road, rightTurn(166.7, wide), 0.01)
        assertEquals(lane28, rightTurn(70.8, wide), 0.01)
        assertEquals(18.91, fit(0.0, car.largeMetersAfterManeuver, wide), 0.01)
        assertEquals(17.96, rightTurn(134.7, withCard), 0.01)
        assertTrue(rightTurn(140.0, withCard) < 17.95)
        assertEquals(17.96, rightTurn(40.0, withCard), 0.01)
    }

    @Test
    fun `the road a junction's fit holds ends 30 m past the point, or 30 m past a bend`() {
        // A right turn: east from the maneuver point.
        val east = (0..20).map { LocalPoint(it * 10.0, 0.0) }
        val right = JunctionFit.road(east, approachBearing = 0.0, car)
        assertEquals(LocalPoint(0.0, 0.0), right.first())
        assertEquals(30.0, LocalLine.length(right), 1e-9)
        // Straight on for 60 m, then 40 degrees to the left: held to 30 m past the bend.
        val bend = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 60.0)) +
            (1..10).map { LocalPoint(-it * 10.0 * sin(Math.toRadians(40.0)), 60.0 + it * 10.0 * kotlin.math.cos(Math.toRadians(40.0))) }
        assertEquals(90.0, LocalLine.length(JunctionFit.road(bend, approachBearing = 0.0, car)), 1e-6)
        // Without the way in no bend is looked for; and a road without points holds none.
        assertEquals(30.0, LocalLine.length(JunctionFit.road(bend, approachBearing = null, car)), 1e-9)
        assertTrue(JunctionFit.road(emptyList(), 0.0, car).isEmpty())
        // A bend closer than 15 m is in the first 30 m already.
        val near = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 10.0), LocalPoint(40.0, 10.0))
        assertEquals(30.0, LocalLine.length(JunctionFit.road(near, approachBearing = 0.0, car)), 1e-9)
    }

    @Test
    fun `the zoom of a point grows by the logarithm of how much closer it is`() {
        // Half the distance straight ahead, one level closer.
        assertEquals(1.0, fit(50.0, 0.0) - fit(100.0, 0.0), 1e-9)
        // Beside the car, half the side, one level closer.
        assertEquals(1.0, fit(0.0, 15.0) - fit(0.0, 30.0), 1e-9)
        assertEquals(wide / withCard, 2.0.pow(fit(0.0, 30.0) - fit(0.0, 30.0, withCard)), 1e-9)
        // And by the logarithm of the pixel ratio: the same box holds the same road at a ratio half as large one level closer.
        assertEquals(1.0, JunctionFit.zoomFor(0.0, 30.0, 55.0, height, carRow, top, wide, 0.75, latitude) - fit(0.0, 30.0), 1e-9)
    }
}
