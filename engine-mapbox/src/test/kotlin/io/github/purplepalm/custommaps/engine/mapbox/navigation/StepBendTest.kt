package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Where a route step's road bends, for the step fields of the debug log: made-up steps at 0, 0. */
class StepBendTest {

    private val local = LocalMeters(0.0, 0.0)

    private fun at(x: Double, y: Double): Point {
        val p = LocalPoint(x, y)
        return Point.fromLngLat(local.longitudeOf(p), local.latitudeOf(p))
    }

    /** North from the maneuver point for 62 m, then 26 degrees to the left. */
    private val veer = listOf(at(0.0, 0.0), at(0.0, 62.0), at(-sin(Math.toRadians(26.0)) * 3000.0, 62.0 + cos(Math.toRadians(26.0)) * 3000.0))

    @Test
    fun `a turn step that begins before its bend logs where the road bends`() {
        val bend = stepBend("turn", bearingBefore = 0.0, shape = veer)!!
        assertEquals(62.0, bend.meters, 1e-6)
        assertEquals(-26.0, bend.degrees, 1e-6)
    }

    @Test
    fun `the way in counts, and the same road entered heading east bends at once`() {
        assertEquals(0.0, stepBend("turn", bearingBefore = 90.0, shape = veer)!!.meters)
    }

    @Test
    fun `a step that leaves its maneuver point a few degrees off the way in logs no bend`() {
        val a = Math.toRadians(5.0)
        assertNull(stepBend("new name", bearingBefore = 0.0, shape = listOf(at(0.0, 0.0), at(-sin(a) * 3000.0, cos(a) * 3000.0))))
    }

    @Test
    fun `the departure, a step without the bearing in and an arrival log no bend`() {
        assertNull(stepBend("depart", bearingBefore = 0.0, shape = veer))
        assertNull(stepBend("turn", bearingBefore = null, shape = veer))
        assertNull(stepBend("arrive", bearingBefore = 0.0, shape = listOf(at(0.0, 0.0), at(0.0, 0.0))))
        assertNull(stepBend("turn", bearingBefore = 0.0, shape = emptyList()))
    }
}
