package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The road a junction's frame holds, worked out from a next step on the map: made-up steps at
 * 0, 0, the car coming in heading north.
 */
class JunctionPointsTest {

    private val tuning = CameraTuning.PHONE
    private val local = LocalMeters(0.0, 0.0)

    private fun at(x: Double, y: Double): Point {
        val p = LocalPoint(x, y)
        return Point.fromLngLat(local.longitudeOf(p), local.latitudeOf(p))
    }

    /** Straight on for [late] meters from the maneuver point, then [degrees] to the left for 3 km. */
    private fun veer(late: Double, degrees: Double): List<Point> {
        val a = Math.toRadians(degrees)
        return listOf(at(0.0, 0.0), at(0.0, late), at(-sin(a) * 3000.0, late + cos(a) * 3000.0))
    }

    @Test
    fun `the screenshot's turn gives the road back from 30 m past the bend to the maneuver point`() {
        val planned = JunctionPoints.plan(veer(62.0, 26.0), bearingBefore = 0.0, stepMeters = 3000.0, coalesceMeters = 150.0, tuning)
        assertEquals(62.0, planned.bend!!.meters, 1e-6)
        assertEquals(-26.0, planned.bend!!.degrees, 1e-6)
        val last = planned.points.last()
        assertEquals(0.0, last.latitude(), 1e-12)
        assertEquals(0.0, last.longitude(), 1e-12)
        // The first point is 30 m along the road past the bend: 92 m of road from the maneuver point.
        val first = planned.points.first()
        val bend = at(0.0, 62.0)
        assertEquals(62.0, Geo.meters(0.0, 0.0, bend.latitude(), bend.longitude()), 1e-6)
        assertEquals(30.0, Geo.meters(bend.latitude(), bend.longitude(), first.latitude(), first.longitude()), 1e-3)
        assertEquals(3, planned.points.size)
    }

    @Test
    fun `without the bearing of the way in there is no plan`() {
        val planned = JunctionPoints.plan(veer(62.0, 26.0), bearingBefore = null, stepMeters = 3000.0, coalesceMeters = 150.0, tuning)
        assertNull(planned.bend)
        assertTrue(planned.points.isEmpty())
        assertTrue(JunctionPoints.plan(emptyList(), 0.0, 3000.0, 150.0, tuning).points.isEmpty())
    }

    @Test
    fun `a short next step is framed whole by the SDK and gets no points`() {
        val planned = JunctionPoints.plan(veer(62.0, 26.0), bearingBefore = 0.0, stepMeters = 120.0, coalesceMeters = 150.0, tuning)
        assertEquals(62.0, planned.bend!!.meters, 1e-6)
        assertTrue(planned.points.isEmpty())
    }

    @Test
    fun `a plain turn gets no points`() {
        val turn = listOf(at(0.0, 0.0), at(-3000.0, 0.0))
        val planned = JunctionPoints.plan(turn, bearingBefore = 0.0, stepMeters = 3000.0, coalesceMeters = 150.0, tuning)
        assertEquals(0.0, planned.bend!!.meters, 1e-9)
        assertTrue(planned.points.isEmpty())
    }

    @Test
    fun `nothing given, nothing taken back, and the camera line says so`() {
        val handed = ArrayList<List<Point>>()
        val points = JunctionPoints { handed += it }
        points.clear()
        assertTrue(handed.isEmpty())
        assertEquals(mapOf("bendM" to null, "bendDeg" to null, "extraPts" to 0), points.diagnostics())
    }
}
