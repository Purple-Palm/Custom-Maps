package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.SpeedLimit
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.Polyline
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.NodeManeuver
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.RouteNode
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/*
 * The route as the app's own lanes read it ([LaneRoute]), built from the route the navigator already
 * holds: no request, nothing stored. It reads the shape of every step, which is work for a background
 * thread.
 */

/**
 * The route [routeId] with [legs] as the app's own lanes read it: every intersection of every step,
 * leg after leg, with
 * - its station: the step's start plus how far along the step's shape it lies, measured as the lane
 *   choices measure their points ([stepLanes]); a step's start is where the shapes of the steps before
 *   it end;
 * - its lanes ([routeLanes]), each with the kinds of vehicle it is kept for;
 * - its roads, the ones that may be entered, and the ones the route comes in and leaves on;
 * - whether the road that leaves it is a freeway ([isFreeway]) or a ramp ([RoadKind.isRamp]);
 * - the maneuver its step begins with, for the first intersection of each step;
 * - the posted speed of the road that leaves it, from the leg's maxspeed annotation.
 *
 * A shape or an intersection with a coordinate that is none is no route to read: it throws, and the
 * caller loses the line, never the drive.
 *
 * @param shapes the points of each step of each leg, as the steps' geometry decodes to
 */
internal fun laneRoute(routeId: String, legs: List<RouteLeg>, shapes: List<List<List<Point>>>): LaneRoute {
    val nodes = ArrayList<RouteNode>()
    var stepStart = 0.0
    var steps = 0
    for ((l, leg) in legs.withIndex()) {
        val legSteps = leg.steps().orEmpty()
        val maxSpeeds = leg.annotation()?.maxspeed()
        // A ramp step lies next to a freeway where the step before it ends on one, so a freeway leads
        // to its maneuver, or the step after it begins on one, so a freeway leaves the ramp's end.
        val endsOnFreeway = legSteps.map { it.intersections()?.lastOrNull()?.isFreeway() == true }
        val beginsOnFreeway = legSteps.map { it.intersections()?.firstOrNull()?.isFreeway() == true }
        for ((k, step) in legSteps.withIndex()) {
            steps++
            val shape = shapes.getOrNull(l)?.getOrNull(k).orEmpty().map { it.toGeoPoint() ?: offTheGlobe() }
            val intersections = step.intersections().orEmpty().filterNotNull()
            val places = intersections.map { it.location().toGeoPoint() ?: offTheGlobe() }
            val meters = Polyline.distancesTo(shape, places)
            val type = step.maneuver().type()
            val stepType = ManeuverMapping.type(type)
            val besideFreewayStep = endsOnFreeway.getOrNull(k - 1) == true || beginsOnFreeway.getOrNull(k + 1) == true
            for ((j, intersection) in intersections.withIndex()) {
                val freeway = intersection.isFreeway()
                nodes.add(
                    RouteNode(
                        station = stepStart + meters[j],
                        lanes = intersection.routeLanes(),
                        bearings = intersection.bearings().orEmpty(),
                        entry = intersection.entry().orEmpty(),
                        inIndex = intersection.`in`(),
                        outIndex = intersection.out(),
                        freeway = freeway,
                        ramp = RoadKind.isRamp(intersection.mapboxStreetsV8()?.roadClass(), stepType, besideFreewayStep, freeway),
                        maneuver = if (j == 0) {
                            NodeManeuver(stepType, ManeuverMapping.modifier(step.maneuver().modifier()), LaneChoices.isLaneChoiceManeuver(type))
                        } else {
                            null
                        },
                        postedMps = intersection.geometryIndex()?.let { maxSpeeds?.getOrNull(it) }?.metersPerSecond(),
                    ),
                )
            }
            stepStart += lengthOf(shape)
        }
    }
    return LaneRoute(routeId, steps, stepStart, nodes)
}

/**
 * The posted speed of this stretch in metres a second; null where the route does not know it, where
 * there is none, or in a unit it does not name.
 */
internal fun MaxSpeed.metersPerSecond(): Double? {
    if (unknown() == true || none() == true) return null
    val speed = speed() ?: return null
    return when (unit()) {
        SpeedLimit.KMPH -> speed / KMPH_PER_MPS
        SpeedLimit.MPH -> speed * PlanningSpeed.MPS_PER_MPH
        else -> null
    }
}

private const val KMPH_PER_MPS = 3.6

/** The length of a shape along its points. */
private fun lengthOf(points: List<GeoPoint>): Double {
    var meters = 0.0
    for (i in 1 until points.size) meters += Geo.meters(points[i - 1], points[i])
    return meters
}

private fun offTheGlobe(): Nothing = throw IllegalArgumentException("A step has a coordinate off the globe")
