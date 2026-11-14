package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.LegAnnotation
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.engine.mapbox.navigation.RouteLanePlan
import io.github.purplepalm.custommaps.engine.mapbox.navigation.planRoute
import io.github.purplepalm.custommaps.nav.debug.DebugLog

/**
 * A made-up freeway exit for the own lanes' tests: a freeway due north along longitude 0 from 0, 0, a point every
 * [STEP] degrees, three lanes (or as many as a test asks for), lane points where all of them go on, and off it to the
 * right at a gore where only the right one may take the exit; a turn two points after the gore, the arrival two more
 * after it. A posted 60 mph all along. Invented lanes and route ids.
 */
internal object FreewayExit {

    const val STEP = 0.0009

    fun point(i: Int): Point = Point.fromLngLat(0.0, i * STEP)

    private fun lane(valid: Boolean, arrows: List<String> = listOf("straight")): IntersectionLanes =
        IntersectionLanes.builder().valid(valid).active(valid).indications(arrows).build()

    private fun intersection(
        at: Int,
        lanes: List<IntersectionLanes>? = null,
        motorway: Boolean = false,
        first: Boolean = false,
        leftRoad: Boolean = false,
    ): StepIntersection =
        StepIntersection.builder()
            .rawLocation(doubleArrayOf(0.0, at * STEP))
            // A road on the left that may be entered: no rule bridges lane points across it toward a right exit.
            .bearings(if (first) listOf(0) else if (leftRoad) listOf(180, 0, 330) else listOf(180, 0))
            .entry(if (first) listOf(true) else if (leftRoad) listOf(false, true, true) else listOf(false, true))
            .`in`(if (first) null else 0)
            .out(if (first) 0 else 1)
            .classes(if (motorway) listOf("motorway") else null)
            .lanes(lanes)
            .geometryIndex(at)
            .build()

    private fun legStep(type: String, modifier: String?, from: Int, intersections: List<StepIntersection>): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(0.0, from * STEP)).type(type).modifier(modifier).build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(intersections)
        .build()

    /**
     * The route's legs and its steps' points: lane points at [points] with [lanes] lanes, the gore at [gore]; at [leftRoad],
     * where given, an intersection without lane data where a road that may be entered leaves on the left.
     */
    fun route(gore: Int, points: List<Int>, lanes: Int = 3, leftRoad: Int? = null): Pair<List<RouteLeg>, List<List<List<Point>>>> {
        val three = List(lanes) { lane(true) }
        val exit = List(lanes - 1) { lane(false) } + lane(true, listOf("slight right"))
        val turn = gore + 2
        val end = gore + 4
        val steps = listOf(
            legStep(
                "depart",
                null,
                0,
                listOf(intersection(0, motorway = true, first = true)) +
                    (points.map { it to false } + listOfNotNull(leftRoad?.let { it to true })).sortedBy { it.first }.map { (at, road) ->
                        if (road) intersection(at, motorway = true, leftRoad = true) else intersection(at, three, motorway = true)
                    },
            ),
            legStep("off ramp", "slight right", gore, listOf(intersection(gore, exit, motorway = true))),
            legStep("turn", "right", turn, listOf(intersection(turn))),
            legStep("arrive", "right", end, listOf(intersection(end))),
        )
        val starts = listOf(0, gore, turn, end)
        val shapes = listOf(
            starts.mapIndexed { i, from ->
                val to = if (i + 1 < starts.size) starts[i + 1] else end
                if (to > from) (from..to).map(::point) else listOf(point(from), point(from))
            },
        )
        val maxSpeeds = LegAnnotation.builder().maxspeed(List(end) { MaxSpeed.builder().speed(60).unit("mph").build() }).build()
        return listOf(RouteLeg.builder().steps(steps).annotation(maxSpeeds).build()) to shapes
    }

    /** The lane plan of the route [routeId] with [legs] and [shapes]; null where none can be made. */
    fun plan(routeId: String, legs: List<RouteLeg>, shapes: List<List<List<Point>>>): RouteLanePlan? =
        planRoute(routeId, { legs }, { shapes }, DebugLog.NONE)
}
