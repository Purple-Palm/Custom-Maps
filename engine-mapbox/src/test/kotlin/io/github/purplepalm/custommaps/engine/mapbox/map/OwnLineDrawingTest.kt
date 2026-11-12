package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.api.directions.v5.models.Closure
import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.LegAnnotation
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.engine.mapbox.navigation.planRoute
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * The own route line's drawing of made-up routes due north along longitude 0 from 0, 0, a point every 0.0009 degrees
 * (about 100 m), with invented lanes; route ids are invented too.
 */
class OwnLineDrawingTest {

    private val step = 0.0009

    private fun point(i: Int): Point = Point.fromLngLat(0.0, i * step)

    /** How far point [i] lies along the line from 0, 0. */
    private fun along(i: Int) = Geo.meters(0.0, 0.0, i * step, 0.0)

    private fun lane(valid: Boolean, arrows: List<String> = listOf("straight")): IntersectionLanes =
        IntersectionLanes.builder().valid(valid).active(valid).indications(arrows).build()

    private fun intersection(at: Int, lanes: List<IntersectionLanes>? = null, motorway: Boolean = false, first: Boolean = false): StepIntersection =
        StepIntersection.builder()
            .rawLocation(doubleArrayOf(0.0, at * step))
            .bearings(if (first) listOf(0) else listOf(180, 0))
            .entry(if (first) listOf(true) else listOf(false, true))
            .`in`(if (first) null else 0)
            .out(if (first) 0 else 1)
            .classes(if (motorway) listOf("motorway") else null)
            .lanes(lanes)
            .geometryIndex(at)
            .build()

    private fun legStep(type: String, modifier: String?, from: Int, intersections: List<StepIntersection>): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(0.0, from * step)).type(type).modifier(modifier).build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(intersections)
        .build()

    /** The points of each step: from its first point to the next step's first; the last step, the arrival, one point twice. */
    private fun shapes(starts: List<Int>, end: Int): List<List<List<Point>>> = listOf(
        starts.mapIndexed { i, from ->
            val to = if (i + 1 < starts.size) starts[i + 1] else end
            if (to > from) (from..to).map(::point) else listOf(point(from), point(from))
        },
    )

    private fun leg(steps: List<LegStep>, annotation: LegAnnotation? = null, closures: List<Closure>? = null): RouteLeg =
        RouteLeg.builder().steps(steps).annotation(annotation).closures(closures).build()

    /** A street from 0 to 5, a turn onto another street to 9, and the arrival at 9. */
    private val streetShapes = shapes(listOf(0, 5, 9), 9)
    private val streetSteps = listOf(
        legStep("depart", null, 0, listOf(intersection(0, first = true))),
        legStep("turn", "right", 5, listOf(intersection(5))),
        legStep("arrive", "right", 9, listOf(intersection(9))),
    )

    @Test
    fun `without a lane plan the path is the route's own line, every point of every step`() {
        val drawing = ownLineDrawing("route-a#0", listOf(leg(streetSteps)), streetShapes, plan = null, version = 3)
        // Six points of the first step, five of the second, the arrival's two: step ends repeat, as the SDK draws them.
        assertEquals(13, drawing.points.size)
        assertEquals((0..5).map { GeoPoint(it * step, 0.0) } + (5..9).map { GeoPoint(it * step, 0.0) } + listOf(GeoPoint(9 * step, 0.0), GeoPoint(9 * step, 0.0)), drawing.points)
        assertEquals("route-a#0", drawing.table.routeId)
        assertEquals(3, drawing.table.pathVersion)
        assertEquals(3, drawing.version)
        // The path is the route: every fraction is the route's own.
        for (i in 0..9) assertEquals(drawing.table.routeFraction(along(i)), drawing.table.pathFraction(along(i)), 1e-12)
        assertEquals(1, drawing.legStations.size)
        assertEquals(listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN)), drawing.runs, "no congestion in the route")
    }

    @Test
    fun `a leg's congestion is laid along it as the navigation SDK counts its segments`() {
        // Nine segments: 0 to 5 on the first step, 5 to 9 on the second; the arrival adds none.
        val congestion = listOf(10, 20, 30, 45, 45, 65, 85, null, 101)
        val drawing = ownLineDrawing("route-a#0", listOf(leg(streetSteps, LegAnnotation.builder().congestionNumeric(congestion).build())), streetShapes, null, 1)
        assertEquals(1, drawing.legStations.size)
        // The leg's ten points: the first step's six, the second's after its first, none of the arrival.
        val st = drawing.legStations[0]
        assertEquals(10, st.size)
        for (i in 0..9) assertEquals(along(i), st[i], 1e-6)
        val f = drawing.table::pathFraction
        assertEquals(
            listOf(
                TrafficRun(0.0, f(st[3]), TrafficLevel.LOW),
                TrafficRun(f(st[3]), f(st[5]), TrafficLevel.MODERATE),
                TrafficRun(f(st[5]), f(st[6]), TrafficLevel.HEAVY),
                TrafficRun(f(st[6]), f(st[7]), TrafficLevel.SEVERE),
                // Unknown where the route says nothing, and above 100.
                TrafficRun(f(st[7]), 1.0, TrafficLevel.UNKNOWN),
            ),
            drawing.runs,
        )
    }

    @Test
    fun `congestion words stand in where the route has no numbers, and a closure is closed`() {
        val words = listOf("low", "low", "moderate", "heavy", "severe", "unknown", "low", "low", "low")
        val closures = listOf(Closure.builder().geometryIndexStart(7).geometryIndexEnd(8).build())
        val drawing = ownLineDrawing("route-a#0", listOf(leg(streetSteps, LegAnnotation.builder().congestion(words).build(), closures)), streetShapes, null, 1)
        val st = drawing.legStations[0]
        val f = drawing.table::pathFraction
        assertEquals(
            listOf(
                TrafficRun(0.0, f(st[2]), TrafficLevel.LOW),
                TrafficRun(f(st[2]), f(st[3]), TrafficLevel.MODERATE),
                TrafficRun(f(st[3]), f(st[4]), TrafficLevel.HEAVY),
                TrafficRun(f(st[4]), f(st[5]), TrafficLevel.SEVERE),
                TrafficRun(f(st[5]), f(st[6]), TrafficLevel.UNKNOWN),
                TrafficRun(f(st[6]), f(st[7]), TrafficLevel.LOW),
                // Segments 7 and 8, both ends of the closure's range.
                TrafficRun(f(st[7]), 1.0, TrafficLevel.CLOSED),
            ),
            drawing.runs,
        )
    }

    @Test
    fun `a refresh lays the new traffic along the same path`() {
        val drawing = ownLineDrawing("route-a#0", listOf(leg(streetSteps)), streetShapes, null, 1)
        val heavy = ownLineRuns(listOf(leg(streetSteps, LegAnnotation.builder().congestionNumeric(List(9) { 70 }).build())), drawing)
        assertEquals(listOf(TrafficRun(0.0, 1.0, TrafficLevel.HEAVY)), heavy)
        val refreshed = drawing.withRuns(heavy)
        assertSame(drawing.table, refreshed.table)
        assertSame(drawing.points, refreshed.points)
        assertEquals(drawing.version, refreshed.version)
        // An annotation that a refresh emptied leaves the traffic unknown.
        assertEquals(listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN)), ownLineRuns(listOf(leg(streetSteps, LegAnnotation.builder().congestionNumeric(emptyList()).build())), drawing))
    }

    @Test
    fun `a route without a line to draw throws, for the caller to keep the SDK's line`() {
        assertThrows<IllegalArgumentException> { ownLineDrawing("route-a#0", emptyList(), emptyList(), null, 1) }
    }

    /**
     * A freeway from 0 north, three lanes at the intersection at 10 that all go on, and off it to the right at 15
     * where only the right one of three may take the exit (a gore), a turn at 17, the arrival at 19.
     */
    private fun exitRoute(): Pair<List<RouteLeg>, List<List<List<Point>>>> {
        val three = listOf(lane(true), lane(true), lane(true))
        val gore = listOf(lane(false), lane(false), lane(true, listOf("slight right")))
        val steps = listOf(
            legStep("depart", null, 0, listOf(intersection(0, motorway = true, first = true), intersection(10, three, motorway = true))),
            legStep("off ramp", "slight right", 15, listOf(intersection(15, gore, motorway = true))),
            legStep("turn", "right", 17, listOf(intersection(17))),
            legStep("arrive", "right", 19, listOf(intersection(19))),
        )
        val maxSpeeds = LegAnnotation.builder().maxspeed(List(19) { MaxSpeed.builder().speed(60).unit("mph").build() }).build()
        return listOf(leg(steps, maxSpeeds)) to shapes(listOf(0, 15, 17, 19), 19)
    }

    @Test
    fun `with a lane plan the path leaves the route's line for the planned lane and comes back to it`() {
        val (legs, shapes) = exitRoute()
        val plan = requireNotNull(planRoute("route-e#0", { legs }, { shapes }, DebugLog.NONE))
        assertTrue(plan.plan.segments.any { it.named != null }, "the plan names a lane before the exit")
        val drawing = ownLineDrawing("route-e#0", legs, shapes, plan, 2)
        val line = shapes.flatten().flatten().map { GeoPoint(it.latitude(), it.longitude()) }
        // Off the line where the lane is named: the right lane lies east of a road going north.
        assertTrue(drawing.points.any { it.longitude > 1e-5 }, "the path runs in the right lane somewhere")
        assertTrue(drawing.points.none { it.longitude < -1e-9 }, "never left of the road's line on a right exit")
        // Back on the route's own line at both ends.
        assertEquals(line.first(), drawing.points.first())
        assertEquals(line.last().latitude, drawing.points.last().latitude, 1e-12)
        assertTrue(abs(drawing.points.last().longitude) < 1e-12)
        // The table measures this path, not the route's line: the fractions differ inside the drawing.
        assertEquals("route-e#0", drawing.table.routeId)
        assertEquals(2, drawing.table.pathVersion)
        assertEquals(1.0, drawing.table.pathFraction(along(19)), 1e-12)
    }
}
