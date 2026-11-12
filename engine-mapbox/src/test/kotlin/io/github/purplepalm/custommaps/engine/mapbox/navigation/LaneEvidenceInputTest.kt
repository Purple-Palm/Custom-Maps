package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.IntersectionLaneAccess
import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.LegAnnotation
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MapboxStreetsV8
import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidenceLine
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A made-up route north along longitude 0 from 0, 0, a point every 0.0009 degrees (about 100 m):
 * a street, an on ramp, a freeway with lanes, an exit, a turn and the arrival. Invented roads only.
 */
class LaneEvidenceInputTest {

    private val points = (0..19).map { Point.fromLngLat(0.0, it * 0.0009) }

    /** How far point [i] lies along the route from its start. */
    private fun along(i: Int) = Geo.meters(0.0, 0.0, i * 0.0009, 0.0)

    private fun lane(valid: Boolean, arrows: List<String> = listOf("straight"), designated: List<String>? = null): IntersectionLanes =
        IntersectionLanes.builder()
            .valid(valid)
            .active(valid)
            .indications(arrows)
            .access(designated?.let { IntersectionLaneAccess.builder().designated(it).build() })
            .build()

    /** An intersection at point [at]: the route comes in from the south and leaves to the north, with [others] roads besides. */
    private fun intersection(
        at: Int,
        lanes: List<IntersectionLanes>? = null,
        motorway: Boolean = false,
        streetsClass: String? = null,
        others: List<Pair<Int, Boolean>> = emptyList(),
        first: Boolean = false,
    ): StepIntersection {
        val bearings = if (first) listOf(0) else listOf(180, 0)
        val entry = if (first) listOf(true) else listOf(false, true)
        return StepIntersection.builder()
            .rawLocation(doubleArrayOf(points[at].longitude(), points[at].latitude()))
            .bearings(bearings + others.map { it.first })
            .entry(entry + others.map { it.second })
            .`in`(if (first) null else 0)
            .out(if (first) 0 else 1)
            .classes(if (motorway) listOf("motorway") else null)
            .mapboxStreetsV8(streetsClass?.let { MapboxStreetsV8.builder().roadClass(it).build() })
            .lanes(lanes)
            .geometryIndex(at)
            .build()
    }

    private fun step(type: String, modifier: String?, from: Int, intersections: List<StepIntersection>): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(points[from].longitude(), points[from].latitude())).type(type).modifier(modifier).build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(intersections)
        .build()

    private fun speed(value: Int?, unit: String? = null, unknown: Boolean? = null, none: Boolean? = null): MaxSpeed =
        MaxSpeed.builder().speed(value).unit(unit).unknown(unknown).none(none).build()

    /** The posted speed of each of the leg's 19 segments, from point 0 on. */
    private val maxSpeeds = List(3) { speed(25, "mph") } + List(2) { speed(null, unknown = true) } + List(10) { speed(105, "km/h") } +
        List(2) { speed(null, none = true) } + List(2) { speed(30, "mph") }

    private val freewayLanes = listOf(
        lane(false, designated = listOf("hov")),
        lane(true),
        lane(true),
        lane(true),
        lane(false, listOf("slight right"), designated = listOf("bus", "taxi")),
    )
    private val goreLanes = listOf(lane(false), lane(false), lane(false), lane(true, listOf("straight", "slight right")), lane(true, listOf("slight right")))

    /** Depart on a street; an on ramp at 3; merge onto a freeway at 5; lanes at 8; an exit road at 12; off at 15; a turn at 17; arrive at 19. */
    private val steps = listOf(
        step("depart", null, 0, listOf(intersection(0, first = true), intersection(2))),
        step("on ramp", "slight right", 3, listOf(intersection(3, streetsClass = "motorway_link"))),
        step(
            "merge",
            "slight left",
            5,
            listOf(
                intersection(5, motorway = true, others = listOf(200 to false)),
                intersection(8, freewayLanes, motorway = true),
                intersection(12, motorway = true, others = listOf(30 to true)),
            ),
        ),
        step("off ramp", "slight right", 15, listOf(intersection(15, goreLanes, motorway = true))),
        step("turn", "right", 17, listOf(intersection(17))),
        step("arrive", "right", 19, listOf(intersection(19))),
    )
    private val shapes = listOf(listOf(points.subList(0, 4), points.subList(3, 6), points.subList(5, 16), points.subList(15, 18), points.subList(17, 20), listOf(points[19], points[19])))
    private val leg: RouteLeg = RouteLeg.builder().steps(steps).annotation(LegAnnotation.builder().maxspeed(maxSpeeds).build()).build()

    private fun route() = laneRoute("route-1#0", listOf(leg), shapes)

    @Test
    fun `every intersection gets its station along the steps' shapes`() {
        val route = route()
        assertEquals("route-1#0", route.routeId)
        assertEquals(6, route.steps)
        assertEquals(9, route.nodes.size)
        route.nodes.zip(listOf(0, 2, 3, 5, 8, 12, 15, 17, 19)).forEach { (node, at) -> assertEquals(along(at), node.station, 1e-6) }
        assertEquals(along(19), route.lengthMeters, 1e-6)
    }

    @Test
    fun `a step's maneuver comes with its first intersection only`() {
        val nodes = route().nodes
        assertEquals(
            listOf(ManeuverType.DEPART, null, ManeuverType.ON_RAMP, ManeuverType.MERGE, null, null, ManeuverType.OFF_RAMP, ManeuverType.TURN, ManeuverType.ARRIVE),
            nodes.map { it.maneuver?.type },
        )
        val offRamp = nodes[6].maneuver!!
        assertEquals(TurnModifier.SLIGHT_RIGHT, offRamp.modifier)
        assertTrue(offRamp.viewStaysUp, "the camera stays up for an off ramp")
        assertFalse(nodes[7].maneuver!!.viewStaysUp, "and frames a turn")
        assertTrue(nodes[3].maneuver!!.viewStaysUp)
    }

    @Test
    fun `lanes come as the route lists them, with the kinds each is kept for`() {
        val point = route().nodes[4]
        assertEquals(5, point.lanes.size)
        assertEquals(RouteLane(setOf(TurnModifier.STRAIGHT), valid = false, active = false, designated = true), point.lanes[0])
        assertTrue(point.lanes[1].usable)
        assertEquals(
            listOf(setOf(DesignatedKind.HOV), emptySet(), emptySet(), emptySet(), setOf(DesignatedKind.BUS, DesignatedKind.TAXI)),
            point.laneKinds,
        )
        assertTrue(route().nodes[1].lanes.isEmpty())
    }

    @Test
    fun `roads come with their bearings, entry and the ways in and out`() {
        val exitRoad = route().nodes[5]
        assertEquals(listOf(180, 0, 30), exitRoad.bearings)
        assertEquals(listOf(false, true, true), exitRoad.entry)
        assertEquals(0, exitRoad.inIndex)
        assertEquals(1, exitRoad.outIndex)
        assertTrue(LaneEvidence.branchesOff(exitRoad, LaneEdge.RIGHT))
        assertTrue(LaneEvidence.joins(route().nodes[3]), "the freeway joined from the left")
        assertNull(route().nodes[0].inIndex, "the departure has no way in")
    }

    @Test
    fun `each road is a freeway, a ramp or a street`() {
        val nodes = route().nodes
        assertEquals(
            listOf(RoadKind.STREET, RoadKind.STREET, RoadKind.RAMP, RoadKind.FREEWAY, RoadKind.FREEWAY, RoadKind.FREEWAY, RoadKind.RAMP, RoadKind.STREET, RoadKind.STREET),
            nodes.map { it.road },
        )
        // The exit's road has no Streets class and the motorway class: a ramp by its step, next to the freeway step.
        assertTrue(nodes[6].freeway)
        assertTrue(nodes[6].ramp)
        assertFalse(nodes[2].freeway, "the link class makes a ramp without the motorway class")
    }

    @Test
    fun `a link class makes a ramp in a step of any kind`() {
        val turnOntoLink = listOf(
            step("depart", null, 0, listOf(intersection(0, first = true))),
            step("turn", "right", 2, listOf(intersection(2, streetsClass = "motorway_link"))),
            step("arrive", "right", 4, listOf(intersection(4))),
        )
        val shapes = listOf(listOf(points.subList(0, 3), points.subList(2, 5), listOf(points[4], points[4])))
        val nodes = laneRoute("route-4#0", listOf(RouteLeg.builder().steps(turnOntoLink).build()), shapes).nodes
        assertEquals(listOf(RoadKind.STREET, RoadKind.RAMP, RoadKind.STREET), nodes.map { it.road })
    }

    /** The roads of a leg of [steps] whose shapes run from each step's first point to the next one's, the last a point. */
    private fun roadsOf(vararg steps: Pair<Int, LegStep>): List<RoadKind> {
        val starts = steps.map { it.first }
        val shapes = starts.mapIndexed { i, from -> if (i + 1 < starts.size) points.subList(from, starts[i + 1] + 1) else listOf(points[from], points[from]) }
        return laneRoute("route-5#0", listOf(RouteLeg.builder().steps(steps.map { it.second }).build()), listOf(shapes)).nodes.map { it.road }
    }

    @Test
    fun `an exit without a class after a step that ends on a freeway is a ramp`() {
        // The departure's road becomes a freeway at point 2; the exit's road has no class at all.
        val roads = roadsOf(
            0 to step("depart", null, 0, listOf(intersection(0, first = true), intersection(2, motorway = true))),
            4 to step("off ramp", "slight right", 4, listOf(intersection(4))),
            6 to step("turn", "right", 6, listOf(intersection(6))),
            8 to step("arrive", "right", 8, listOf(intersection(8))),
        )
        assertEquals(listOf(RoadKind.STREET, RoadKind.FREEWAY, RoadKind.RAMP, RoadKind.STREET, RoadKind.STREET), roads)
    }

    @Test
    fun `an on ramp without a class before a step that begins on a freeway is a ramp`() {
        // The merge's road is a freeway where it begins and a street from point 5 on.
        val roads = roadsOf(
            0 to step("depart", null, 0, listOf(intersection(0, first = true))),
            2 to step("on ramp", "right", 2, listOf(intersection(2))),
            4 to step("merge", "slight left", 4, listOf(intersection(4, motorway = true), intersection(5))),
            6 to step("arrive", "right", 6, listOf(intersection(6))),
        )
        assertEquals(listOf(RoadKind.STREET, RoadKind.RAMP, RoadKind.FREEWAY, RoadKind.STREET, RoadKind.STREET), roads)
    }

    @Test
    fun `a fork without a class between streets is no ramp`() {
        val roads = roadsOf(
            0 to step("depart", null, 0, listOf(intersection(0, first = true))),
            2 to step("fork", "slight left", 2, listOf(intersection(2))),
            4 to step("arrive", "left", 4, listOf(intersection(4))),
        )
        assertEquals(listOf(RoadKind.STREET, RoadKind.STREET, RoadKind.STREET), roads)
    }

    @Test
    fun `the posted speed is the maxspeed of the road that leaves the intersection`() {
        val posted = route().nodes.map { it.postedMps }
        assertEquals(25.0 * PlanningSpeed.MPS_PER_MPH, posted[0]!!, 1e-9)
        assertEquals(25.0 * PlanningSpeed.MPS_PER_MPH, posted[1]!!, 1e-9, "the segment that leaves it, the last one at 25 mph")
        assertNull(posted[2], "unknown")
        assertEquals(105.0 / 3.6, posted[3]!!, 1e-9)
        assertEquals(105.0 / 3.6, posted[5]!!, 1e-9)
        assertNull(posted[6], "no limit is no posted speed")
        assertEquals(30.0 * PlanningSpeed.MPS_PER_MPH, posted[7]!!, 1e-9)
        assertNull(posted[8], "past the last segment")
    }

    @Test
    fun `a maxspeed gives metres a second in either unit, and nothing without a speed`() {
        assertEquals(100.0 / 3.6, speed(100, "km/h").metersPerSecond()!!, 1e-9)
        assertEquals(60.0 * 0.44704, speed(60, "mph").metersPerSecond()!!, 1e-9)
        assertNull(speed(60, "knots").metersPerSecond())
        assertNull(speed(null, "mph").metersPerSecond())
        assertNull(speed(60, "mph", unknown = true).metersPerSecond())
        assertNull(speed(null, none = true).metersPerSecond())
        assertNull(speed(130, "km/h", none = true).metersPerSecond(), "no limit, whatever speed comes with it")
    }

    @Test
    fun `designated kinds by the route's names, others as other`() {
        assertEquals(setOf(DesignatedKind.HOV), lane(false, designated = listOf("hov")).designatedKinds())
        assertEquals(setOf(DesignatedKind.OTHER), lane(false, designated = listOf("delivery")).designatedKinds())
        assertTrue(lane(true).designatedKinds().isEmpty())
    }

    @Test
    fun `a step without intersections or shape adds nothing and moves nothing`() {
        val empty = LegStep.builder()
            .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(0.0, 0.0)).type("notification").build())
            .distance(0.0)
            .duration(0.0)
            .weight(0.0)
            .mode("driving")
            .build()
        val route = laneRoute("route-2#0", listOf(RouteLeg.builder().steps(listOf(empty)).build()), emptyList())
        assertEquals(1, route.steps)
        assertTrue(route.nodes.isEmpty())
        assertEquals(0.0, route.lengthMeters)
    }

    @Test
    fun `a coordinate off the globe is no route to read`() {
        val off = listOf(listOf(listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.0, 95.0))))
        val leg = RouteLeg.builder().steps(listOf(step("depart", null, 0, listOf(intersection(0, first = true))))).build()
        assertThrows(IllegalArgumentException::class.java) { laneRoute("route-3#0", listOf(leg), off) }
    }

    @Test
    fun `the route's line counts its lane points, windows and turns`() {
        val evidence = LaneEvidence.of(route())
        val fields = LaneEvidenceLine.fields(evidence)
        assertEquals(mapOf("freeway" to 2, "ramp" to 0, "street" to 0), fields["lanePoints"])
        assertEquals(mapOf("runUps" to 1, "street" to 1), fields["windows"])
        assertEquals(mapOf("withLanes" to 0, "withoutLanes" to 1), fields["streetTurns"])
        @Suppress("UNCHECKED_CAST")
        val designated = fields["designated"] as Map<String, Int>
        assertEquals(1, designated["hov"])
        assertEquals(1, designated["bus"])
        assertEquals(false, fields["noLanes"])
        // Numbers, ids and kinds only: the line never holds a coordinate.
        val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LaneEvidenceLine.TYPE, fields)
        assertFalse(line.contains("0.0009"))
        assertFalse(line.contains("lat") || line.contains("lon"))
    }
}
