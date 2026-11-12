package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.LegAnnotation
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.CardWindow
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidenceLine
import io.github.purplepalm.custommaps.nav.lanes.LanePlanLine
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Made-up routes north along longitude 0 from 0, 0, a point every 0.0009 degrees (about 100 m), with
 * invented roads and lanes only; route ids are invented too.
 */
class LanePlansTest {

    private class Lines : DebugLog {
        val lines = ArrayList<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            lines.add(type to fields)
        }

        fun types(): List<String> = lines.map { it.first }
    }

    private val step = 0.0009

    private fun point(i: Int, east: Double = 0.0): Point = Point.fromLngLat(east, i * step)

    /** How far point [i] lies along the route from its start. */
    private fun along(i: Int) = Geo.meters(0.0, 0.0, i * step, 0.0)

    private fun lane(valid: Boolean, arrows: List<String> = listOf("straight")): IntersectionLanes =
        IntersectionLanes.builder().valid(valid).active(valid).indications(arrows).build()

    /** An intersection at point [at]: the route comes in from the south and leaves to the north, with [others] roads besides. */
    private fun intersection(
        at: Int,
        lanes: List<IntersectionLanes>? = null,
        motorway: Boolean = false,
        others: List<Pair<Int, Boolean>> = emptyList(),
        first: Boolean = false,
        east: Double = 0.0,
    ): StepIntersection = StepIntersection.builder()
        .rawLocation(doubleArrayOf(east, at * step))
        .bearings((if (first) listOf(0) else listOf(180, 0)) + others.map { it.first })
        .entry((if (first) listOf(true) else listOf(false, true)) + others.map { it.second })
        .`in`(if (first) null else 0)
        .out(if (first) 0 else 1)
        .classes(if (motorway) listOf("motorway") else null)
        .lanes(lanes)
        .geometryIndex(at)
        .build()

    private fun legStep(type: String, modifier: String?, from: Int, intersections: List<StepIntersection>, east: Double = 0.0): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(east, from * step)).type(type).modifier(modifier).build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(intersections)
        .build()

    /** The points of each step: from its first point to the next step's first, the last step a point. */
    private fun shapes(starts: List<Int>, end: Int, east: Double = 0.0): List<List<List<Point>>> = listOf(
        starts.mapIndexed { i, from ->
            val to = if (i + 1 < starts.size) starts[i + 1] else end
            if (to > from) (from..to).map { point(it, east) } else listOf(point(from, east), point(from, east))
        },
    )

    private fun leg(steps: List<LegStep>, segments: Int, mph: Int): RouteLeg = RouteLeg.builder()
        .steps(steps)
        .annotation(LegAnnotation.builder().maxspeed(List(segments) { MaxSpeed.builder().speed(mph).unit("mph").build() }).build())
        .build()

    private val gore = listOf(lane(false), lane(false), lane(false), lane(true, listOf("straight", "slight right")), lane(true, listOf("slight right")))

    /**
     * Depart at 0 onto a freeway; lanes at 8 (five, the right one leaving: an edge point) and at 10; a road
     * without lane data leaves on the right at 12; off the freeway to the right at 15 with [gore]'s lanes; a
     * turn at 17; the arrival at 19. [east] moves it all that many degrees of longitude east.
     */
    private fun shortRoute(east: Double = 0.0): Pair<List<RouteLeg>, List<List<List<Point>>>> {
        val five = listOf(lane(true), lane(true), lane(true), lane(true), lane(false))
        val steps = listOf(
            legStep(
                "depart",
                null,
                0,
                listOf(
                    intersection(0, motorway = true, first = true, east = east),
                    intersection(8, five, motorway = true, others = listOf(30 to true), east = east),
                    intersection(10, listOf(lane(true), lane(true), lane(true), lane(true)), motorway = true, east = east),
                    intersection(12, motorway = true, others = listOf(30 to true), east = east),
                ),
                east,
            ),
            legStep("off ramp", "slight right", 15, listOf(intersection(15, gore, motorway = true, east = east)), east),
            legStep("turn", "right", 17, listOf(intersection(17, east = east)), east),
            legStep("arrive", "right", 19, listOf(intersection(19, east = east)), east),
        )
        return listOf(leg(steps, 19, 60)) to shapes(listOf(0, 15, 17, 19), 19, east)
    }

    private fun plan(lines: Lines = Lines(), id: String = "route-a#0", east: Double = 0.0, change: RouteChange? = null): RouteLanePlan? {
        val (legs, shapes) = shortRoute(east)
        return planRoute(id, { legs }, { shapes }, debugLog = lines, change = change)
    }

    // ------------------------------------------------------------------ the worker's job

    @Test
    fun `a new route gets its lane_evidence line, then its lane_plan line, and its plan`() {
        val lines = Lines()
        val held = requireNotNull(plan(lines))
        assertEquals(listOf(LaneEvidenceLine.TYPE, LanePlanLine.TYPE), lines.types(), "no error")
        assertEquals("route-a#0", held.routeId)
        assertEquals(LaneEvidenceLine.fields(held.plan.topology.evidence, held.plan, held.evidence), lines.lines[0].second)
        assertTrue("zones" in lines.lines[0].second && "noneM" in lines.lines[0].second, "with the second pass's fields")
        // The plan names lanes on the exit's run-up and nothing outside it.
        assertTrue(held.plan.segments.any { it.named != null })
        assertTrue(held.plan.windows.any { it.kind == CardWindow.Kind.RUN_UP })
        assertTrue(held.buildMillis >= 0.0)
    }

    @Test
    fun `the lane_plan line names the exit by the card's own key, in metres before its gore`() {
        val lines = Lines()
        val held = requireNotNull(plan(lines))
        val fields = lines.lines[1].second
        assertEquals("route-a#0", fields["routeId"])
        assertTrue(fields["replanMoves"] == null, "no change of route at the start of a drive")
        @Suppress("UNCHECKED_CAST")
        val windows = fields["windows"] as List<Map<String, Any?>>
        val runUp = windows.single { it["kind"] == "run_up" }
        assertEquals("route-a#0/0/0/end", runUp["key"])
        @Suppress("UNCHECKED_CAST")
        val points = runUp["points"] as List<Map<String, Any?>>
        assertEquals(listOf(-Math.round(along(15) - along(8)), -Math.round(along(15) - along(10)), 0L), points.map { it["atM"] })
        assertEquals(1, runUp["unknownExits"], "the road without lane data at 12")
        assertEquals(held.buildMillis, (fields["ms"] as Double), 0.05)
    }

    @Test
    fun `after a change of route on the same carriageway the new plan's lane_plan line says the count was kept`() {
        val old = requireNotNull(plan(id = "route-a#0"))
        val car = GeoPoint(13 * step, 0.0)
        val lines = Lines()
        val new = requireNotNull(plan(lines, id = "route-b#0", change = RouteChange(old, car, along(13))))
        val fields = lines.lines.single { it.first == LanePlanLine.TYPE }.second
        assertEquals(true, fields["replanKept"])
        assertEquals(0, fields["replanMoves"], "the same lane before and after")
        assertEquals("route-b#0", new.routeId)
    }

    @Test
    fun `after a change onto a road beside the route the count is not kept`() {
        val old = requireNotNull(plan(id = "route-a#0"))
        val car = GeoPoint(13 * step, 0.0)
        val lines = Lines()
        // The same lanes on a road 0.0002 degrees (about 22 m) east of the route before.
        requireNotNull(plan(lines, id = "route-c#0", east = 0.0002, change = RouteChange(old, car, along(13))))
        val fields = lines.lines.single { it.first == LanePlanLine.TYPE }.second
        assertEquals(false, fields["replanKept"])
        // Farther than 50 m from the car: no hand-over at all.
        val far = Lines()
        requireNotNull(plan(far, id = "route-d#0", east = 0.001, change = RouteChange(old, car, along(13))))
        assertTrue("replanKept" !in far.lines.single { it.first == LanePlanLine.TYPE }.second)
    }

    @Test
    fun `the roads without lane data on the exit's side reach the plan as stations`() {
        val held = requireNotNull(plan())
        // The road leaving on the right at 12, on the step whose exit leaves to the right at 15.
        assertEquals(along(12), held.input.unknownExits.single(), 1e-6)
        assertEquals(1, held.plan.unknownExits.size)
        assertEquals(along(12), held.plan.unknownExits.single().station, 1e-6)
    }

    @Test
    fun `the car's station is its step's start plus how far into the step it is`() {
        val held = requireNotNull(plan())
        assertEquals(30.0, held.station(0, 0, 30.0)!!, 1e-9)
        assertEquals(along(15) + 12.5, held.station(0, 1, 12.5)!!, 1e-6)
        assertEquals(along(17), held.station(0, 2, -3.0)!!, 1e-6, "never before the step's start")
        assertNull(held.station(0, 9, 0.0), "a step the route does not have")
        assertNull(held.station(1, 0, 0.0), "a leg the route does not have")
        assertNull(held.station(0, 0, Double.NaN))
    }

    @Test
    fun `a lane choice's key names its lane point, and the exit's by the step it ends`() {
        val held = requireNotNull(plan())
        val nodes = held.input.route.nodes
        val atEight = requireNotNull(held.pointOf("route-a#0/0/0/1"))
        assertEquals(along(8), atEight.station, 1e-6)
        assertEquals(along(10), held.pointOf("route-a#0/0/0/2")!!.station, 1e-6)
        val exit = requireNotNull(held.pointOf("route-a#0/0/0/end"))
        assertEquals(along(15), exit.station, 1e-6)
        assertEquals(ManeuverType.OFF_RAMP, nodes[exit.node].maneuver?.type)
        assertSame(exit, held.pointOf("route-a#0/0/1/0"), "the exit's own key names the same point")
        assertEquals("route-a#0/0/0/end", held.input.keyOf(exit.node))
        assertEquals("route-a#0/0/0/1", held.input.keyOf(atEight.node))
        assertNull(held.pointOf("route-a#0/0/0/3"), "the road without lane data is no lane point")
        assertNull(held.pointOf("route-b#0/0/0/1"), "another route's key")
        assertNull(held.pointOf(null))
        assertNull(held.input.keyOf(0), "the departure has no lanes")
    }

    @Test
    fun `the plan's lookup at a station gives the same object anywhere in a stretch`() {
        val held = requireNotNull(plan())
        val exit = held.pointOf("route-a#0/0/0/end")!!
        val a = requireNotNull(held.at(exit.station - 20.0))
        assertSame(a, held.at(a.from))
        assertSame(a, held.at((a.from + a.to) / 2.0))
        assertNull(held.at(-1.0))
    }

    @Test
    fun `a route that cannot be read costs its line and its plan, never more`() {
        val lines = Lines()
        val off = listOf(listOf(listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.0, 95.0))))
        val legs = listOf(RouteLeg.builder().steps(listOf(legStep("depart", null, 0, listOf(intersection(0, first = true))))).build())
        assertNull(planRoute("route-c#0", { legs }, { off }, debugLog = lines))
        assertEquals(listOf("error"), lines.types())
        assertEquals(LaneEvidenceLine.TYPE, lines.lines.single().second["where"])
    }

    @Test
    fun `a plan that cannot be made costs one error line, after the route's own line`() {
        val lines = Lines()
        val (legs, shapes) = shortRoute()
        val failing = planRoute("route-d#0", { legs }, { shapes }, debugLog = lines,
            planner = { _, _, _ -> throw IllegalStateException("a made-up failure") },
        )
        assertNull(failing)
        assertEquals(listOf(LaneEvidenceLine.TYPE, "error"), lines.types())
        assertTrue("zones" !in lines.lines.first().second, "the first pass's line alone")
        assertEquals(LANE_PLAN_ERROR, lines.lines.last().second["where"])
    }

    @Test
    fun `a plan the memory left cannot hold costs one error line too, never the drive`() {
        // The job runs on the route lines' worker in the engine's own scope, where an error that escaped would end
        // the app: the plan's own failure stays here.
        val lines = Lines()
        val (legs, shapes) = shortRoute()
        val failing = planRoute("route-d#0", { legs }, { shapes }, debugLog = lines,
            planner = { _, _, _ -> throw OutOfMemoryError("a made-up shortage") },
        )
        assertNull(failing)
        assertEquals(listOf(LaneEvidenceLine.TYPE, "error"), lines.types())
        assertEquals(LANE_PLAN_ERROR, lines.lines.last().second["where"])
        assertTrue((lines.lines.last().second["error"] as String).contains("OutOfMemoryError"))
    }

    @Test
    fun `work no longer wanted makes no line and no plan`() {
        val lines = Lines()
        val (legs, shapes) = shortRoute()
        assertNull(planRoute("route-e#0", { legs }, { shapes }, debugLog = lines, active = { false }))
        assertTrue(lines.lines.isEmpty())
        var calls = 0
        // Wanted while the route is read, no longer once its plan is made.
        assertNull(planRoute("route-e#0", { legs }, { shapes }, debugLog = lines, active = { ++calls <= 1 }))
        assertTrue(lines.lines.isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun `the planner gets the route's own card windows and stations, nothing of the car`() {
        val (legs, shapes) = shortRoute()
        var given: List<Double>? = null
        val held = planRoute("route-f#0", { legs }, { shapes }, debugLog = DebugLog.NONE,
            planner = { topology, windows, unknownExits ->
                given = unknownExits
                LanePlanner.plan(topology, windows, unknownExits)
            },
        )
        assertNotNull(held)
        assertEquals(held!!.input.unknownExits, given)
    }

    // ------------------------------------------------------------------ the holder

    private fun heldFor(id: String): RouteLanePlan = requireNotNull(plan(id = id))

    @Test
    fun `each new primary route of a drive gets a ticket, a refresh and the same route none`() {
        val plans = LanePlans()
        assertNull(plans.onRoutesChanged("route-a#0", refresh = false), "nothing before the drive's routes are in")
        plans.begin()
        assertNull(plans.onRoutesChanged("route-a#0", refresh = false), "nor while they are on their way")
        assertEquals("route-a#0", plans.onCommitted("route-a#0")?.routeId)
        assertNull(plans.onRoutesChanged("route-a#0", refresh = true), "a refresh")
        assertNull(plans.onRoutesChanged("route-a#0", refresh = false), "the same route again")
        assertEquals("route-b#0", plans.onRoutesChanged("route-b#0", refresh = false)?.routeId, "a reroute")
        assertNull(plans.onRoutesChanged("route-c#0", refresh = true), "a refresh never, whatever the id")
        assertNull(plans.onRoutesChanged(null, refresh = false))
        plans.end()
        assertNull(plans.onRoutesChanged("route-d#0", refresh = false), "nothing after the drive")
    }

    @Test
    fun `a plan is held with its route and read by that route's progress only`() {
        val plans = LanePlans()
        plans.begin()
        val ticket = requireNotNull(plans.onCommitted("route-a#0"))
        val plan = heldFor("route-a#0")
        assertNull(plans.planFor("route-a#0"), "none before it comes back")
        assertTrue(plans.deliver(ticket, plan))
        assertSame(plan, plans.planFor("route-a#0"))
        assertNull(plans.planFor("route-b#0"), "another route's progress reads none")
        assertNull(plans.planFor(null))
        // A reroute: the old plan is never read for the new route, and the new one replaces it.
        val reroute = requireNotNull(plans.onRoutesChanged("route-b#0", refresh = false))
        assertNull(plans.planFor("route-b#0"), "the new route reads none until its plan is in")
        val next = heldFor("route-b#0")
        assertTrue(plans.deliver(reroute, next))
        assertSame(next, plans.planFor("route-b#0"))
        assertNull(plans.planFor("route-a#0"))
    }

    @Test
    fun `an older ticket's plan, another route's plan and a drive's after its end are dropped`() {
        val plans = LanePlans()
        plans.begin()
        val first = requireNotNull(plans.onCommitted("route-a#0"))
        val second = requireNotNull(plans.onRoutesChanged("route-b#0", refresh = false))
        assertTrue(plans.deliver(second, heldFor("route-b#0")))
        assertFalse(plans.deliver(first, heldFor("route-a#0")), "older than the plan held")
        assertEquals("route-b#0", plans.held?.routeId)
        val third = requireNotNull(plans.onRoutesChanged("route-c#0", refresh = false))
        assertFalse(plans.deliver(third, heldFor("route-x#0")), "a plan of another route than the ticket's")
        plans.end()
        assertNull(plans.held, "forgotten with the drive")
        assertFalse(plans.deliver(third, heldFor("route-c#0")), "the drive ended")
        plans.begin()
        assertFalse(plans.deliver(third, heldFor("route-c#0")), "a ticket of the drive before")
        assertNull(plans.held)
    }

    @Test
    fun `a plan that failed leaves none held, not the one of the route before`() {
        val plans = LanePlans()
        plans.begin()
        assertTrue(plans.deliver(requireNotNull(plans.onCommitted("route-a#0")), heldFor("route-a#0")))
        val reroute = requireNotNull(plans.onRoutesChanged("route-b#0", refresh = false))
        assertTrue(plans.deliver(reroute, null))
        assertNull(plans.held)
        assertNull(plans.planFor("route-a#0"))
        assertNull(plans.planFor("route-b#0"))
    }

    @Test
    fun `a route's answer is there once its plan came back, made or not, and the listeners hear it`() {
        val plans = LanePlans()
        val heard = ArrayList<Pair<String, RouteLanePlan?>>()
        val listener: (String, RouteLanePlan?) -> Unit = { id, plan -> heard += id to plan }
        plans.addListener(listener)
        plans.begin()
        val ticket = requireNotNull(plans.onCommitted("route-a#0"))
        assertNull(plans.answerFor("route-a#0"), "on its way")
        val plan = heldFor("route-a#0")
        assertTrue(plans.deliver(ticket, plan))
        assertSame(plan, plans.answerFor("route-a#0")?.plan)
        assertNull(plans.answerFor("route-b#0"))
        assertNull(plans.answerFor(null))
        assertEquals(listOf("route-a#0" to plan), heard)
        // A route whose plan could not be made has an answer too: no plan.
        val reroute = requireNotNull(plans.onRoutesChanged("route-b#0", refresh = false))
        assertNull(plans.answerFor("route-b#0"))
        assertTrue(plans.deliver(reroute, null))
        assertNotNull(plans.answerFor("route-b#0"))
        assertNull(plans.answerFor("route-b#0")?.plan)
        assertNull(plans.answerFor("route-a#0"), "the newer route's answer has taken over")
        assertEquals("route-b#0" to null, heard.last())
        // An answer not taken is not heard; nor is anything after the listener left.
        val third = requireNotNull(plans.onRoutesChanged("route-c#0", refresh = false))
        assertFalse(plans.deliver(reroute, null))
        assertEquals(2, heard.size)
        plans.removeListener(listener)
        assertTrue(plans.deliver(third, heldFor("route-c#0")))
        assertEquals(2, heard.size)
        plans.end()
        assertNull(plans.answerFor("route-c#0"), "outside a drive")
    }

    @Test
    fun `a new drive starts with no plan held`() {
        val plans = LanePlans()
        plans.begin()
        assertTrue(plans.deliver(requireNotNull(plans.onCommitted("route-a#0")), heldFor("route-a#0")))
        plans.begin()
        assertNull(plans.held)
        assertNotNull(plans.onCommitted("route-a#0"), "the same route again in a new drive gets a plan again")
    }

    // ------------------------------------------------------------------ the time a plan takes

    /**
     * A made-up 30 km route: a street of 1 km, an on ramp, 28 km of freeway with an intersection every
     * 100 m, every fifteenth of them a lane point of five lanes (18 in all, the right one leaving at every
     * other one by a road on the right), roads without lane data leaving on the right before the exit, an
     * exit with two lanes of six, a turn and the arrival.
     */
    private fun longRoute(): Pair<List<RouteLeg>, List<List<List<Point>>>> {
        val freewayFrom = 12
        val exitAt = 292
        val freeway = ArrayList<StepIntersection>()
        freeway.add(intersection(freewayFrom, motorway = true, others = listOf(200 to false)))
        for (i in freewayFrom + 1 until exitAt) {
            val lanePoint = (i - freewayFrom) % 15 == 0
            val rightLeaves = lanePoint && (i - freewayFrom) % 30 == 0
            val lanes = when {
                !lanePoint -> null
                rightLeaves -> listOf(lane(true), lane(true), lane(true), lane(true), lane(false))
                else -> listOf(lane(true), lane(true), lane(true), lane(true), lane(true))
            }
            val others = when {
                rightLeaves -> listOf(30 to true)
                !lanePoint && i % 37 == 0 -> listOf(30 to true)
                else -> emptyList()
            }
            freeway.add(intersection(i, lanes, motorway = true, others = others))
        }
        val exitLanes = listOf(lane(false), lane(false), lane(false), lane(false), lane(true, listOf("straight", "slight right")), lane(true, listOf("slight right")))
        val steps = listOf(
            legStep("depart", null, 0, (0 until 10).map { intersection(it, first = it == 0) }),
            legStep("on ramp", "slight right", 10, listOf(intersection(10))),
            legStep("merge", "slight left", freewayFrom, freeway),
            legStep("off ramp", "slight right", exitAt, listOf(intersection(exitAt, exitLanes, motorway = true))),
            legStep("turn", "right", 296, listOf(intersection(296))),
            legStep("arrive", "right", 300, listOf(intersection(300))),
        )
        return listOf(leg(steps, 300, 65)) to shapes(listOf(0, 10, freewayFrom, exitAt, 296, 300), 300)
    }

    @Test
    fun `a plan of a 30 km route is made well within the design's budget`() {
        val (legs, shapes) = longRoute()
        val route = laneRouteInput("route-long#0", legs, shapes).route
        assertEquals(30_000.0, route.lengthMeters, 400.0)
        // Warm up the classes and the JIT, then time the job as the worker runs it.
        repeat(WARM_RUNS) { requireNotNull(planRoute("route-long#0", { legs }, { shapes }, debugLog = DebugLog.NONE)) }
        val times = (0 until TIMED_RUNS).map {
            val start = System.nanoTime()
            val held = requireNotNull(planRoute("route-long#0", { legs }, { shapes }, debugLog = DebugLog.NONE))
            val wall = (System.nanoTime() - start) / 1e6
            assertTrue(held.buildMillis <= wall + 1e-6, "the plan's own time is the job's")
            wall
        }.sorted()
        val median = times[times.size / 2]
        val held = requireNotNull(planRoute("route-long#0", { legs }, { shapes }, debugLog = DebugLog.NONE))
        assertEquals(19, held.input.route.nodes.count { it.isLanePoint }, "18 points on the way and the exit")
        assertTrue(held.plan.windows.any { it.kind == CardWindow.Kind.RUN_UP })
        assertTrue(held.input.unknownExits.isNotEmpty())
        println("lane plan of a 30 km route: median ${"%.1f".format(median)} ms over $TIMED_RUNS runs, fastest ${"%.1f".format(times.first())} ms")
        // The design's budget is 50 ms on a phone; this bound only catches a plan that grew much slower.
        assertTrue(median < BOUND_MILLIS, "median $median ms")
    }

    private companion object {
        const val WARM_RUNS = 5
        const val TIMED_RUNS = 7
        const val BOUND_MILLIS = 500.0
    }
}
