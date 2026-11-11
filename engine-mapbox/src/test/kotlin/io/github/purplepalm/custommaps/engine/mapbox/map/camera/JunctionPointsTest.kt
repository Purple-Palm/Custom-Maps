package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.LaneZoom
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

    /** What the data source was handed, in order. */
    private val handed = ArrayList<List<Point>>()

    /** How many plans were worked out. */
    private var plans = 0

    /** Works out the plan of a step whose road veers 26 degrees [late] meters past the maneuver point, and counts it. */
    private fun planner(late: Double = 62.0): () -> JunctionPoints.Planned = {
        plans += 1
        JunctionPoints.plan(veer(late, 26.0), bearingBefore = 0.0, stepMeters = 3000.0, coalesceMeters = 150.0, tuning)
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
    fun `a road drawn point by point is read to 30 m past a bend near the end of the search`() {
        // North for 140 m, then 26 degrees to the left, a point every 2 m.
        val a = Math.toRadians(26.0)
        val road = (0..70).map { at(0.0, it * 2.0) } + (1..130).map { at(-sin(a) * it * 2.0, 140.0 + cos(a) * it * 2.0) }
        val planned = JunctionPoints.plan(road, bearingBefore = 0.0, stepMeters = 3000.0, coalesceMeters = 150.0, tuning)
        assertEquals(140.0, planned.bend!!.meters, 1e-6)
        val bend = at(0.0, 140.0)
        val first = planned.points.first()
        assertEquals(30.0, Geo.meters(bend.latitude(), bend.longitude(), first.latitude(), first.longitude()), 1e-3)
    }

    @Test
    fun `while the sdk frames the maneuver with Large the points are handed once and planned once`() {
        val points = JunctionPoints { handed += it }
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        assertEquals(1, plans)
        assertEquals(1, handed.size)
        assertEquals(3, handed.single().size)
        assertEquals(mapOf("bendM" to 62.0, "bendDeg" to -26.0, "extraPts" to 3), points.diagnostics())
    }

    @Test
    fun `the first update that frames no maneuver takes the points back`() {
        val points = JunctionPoints { handed += it }
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        points.update("route/0/3", LaneZoom.LARGE, framing = false, planner())
        points.update("route/0/4", LaneZoom.LARGE, framing = false, planner())
        assertEquals(listOf(3, 0), handed.map { it.size })
        assertEquals(mapOf("bendM" to null, "bendDeg" to null, "extraPts" to 0), points.diagnostics())
        // Framed again on the same step: the same plan, not worked out again.
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        assertEquals(1, plans)
        assertEquals(listOf(3, 0, 3), handed.map { it.size })
    }

    @Test
    fun `the next step and a new route are planned again`() {
        val points = JunctionPoints { handed += it }
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner(62.0))
        points.update("route/0/4", LaneZoom.LARGE, framing = true, planner(45.0))
        // A reroute's route can have the same leg and step numbers.
        points.update("other/0/4", LaneZoom.LARGE, framing = true, planner(90.0))
        assertEquals(3, plans)
        assertEquals(3, handed.size)
        assertEquals(90.0, points.diagnostics()["bendM"])
    }

    @Test
    fun `clear takes the points back, and the same step is planned again after it`() {
        val points = JunctionPoints { handed += it }
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        points.clear()
        assertEquals(listOf(3, 0), handed.map { it.size })
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        assertEquals(2, plans)
        assertEquals(listOf(3, 0, 3), handed.map { it.size })
    }

    @Test
    fun `default hands nothing and plans nothing, also while the sdk frames a maneuver`() {
        val points = JunctionPoints { handed += it }
        points.update("route/0/3", LaneZoom.DEFAULT, framing = true, planner())
        assertEquals(0, plans)
        assertTrue(handed.isEmpty())
        assertEquals(mapOf("bendM" to null, "bendDeg" to null, "extraPts" to 0), points.diagnostics())
        // Large, then Default, then Large again within the step.
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        points.update("route/0/3", LaneZoom.DEFAULT, framing = true, planner())
        points.update("route/0/3", LaneZoom.LARGE, framing = true, planner())
        assertEquals(1, plans)
        assertEquals(listOf(3, 0, 3), handed.map { it.size })
    }

    @Test
    fun `an update without a step hands nothing`() {
        val points = JunctionPoints { handed += it }
        points.update(null, LaneZoom.LARGE, framing = true, planner())
        assertEquals(0, plans)
        assertTrue(handed.isEmpty())
    }

    @Test
    fun `nothing given, nothing taken back, and the camera line says so`() {
        val points = JunctionPoints { handed += it }
        points.clear()
        assertTrue(handed.isEmpty())
        assertEquals(mapOf("bendM" to null, "bendDeg" to null, "extraPts" to 0), points.diagnostics())
    }
}
