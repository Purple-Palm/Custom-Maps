package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.SpeedLimit
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.Polyline
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
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
 * - the posted speed of the road that leaves it, from the leg's maxspeed annotation;
 * - whether the route's flags give a traffic signal or a stop sign there, where a stop line of the app's own may stand.
 *
 * A shape or an intersection with a coordinate that is none is no route to read: it throws, and the
 * caller loses the line, never the drive.
 *
 * @param shapes the points of each step of each leg, as the steps' geometry decodes to
 */
internal fun laneRoute(routeId: String, legs: List<RouteLeg>, shapes: List<List<List<Point>>>): LaneRoute =
    laneRouteInput(routeId, legs, shapes).route

/**
 * The route [routeId] as the app's own lanes read it ([laneRoute]), with what the engine needs to find
 * its places again: where each step begins, which intersection a lane choice's key names, and where roads
 * without lane data leave a step on the side of its exit. One pass over the route, for the route lines'
 * worker; it throws where [laneRoute] does.
 */
internal fun laneRouteInput(routeId: String, legs: List<RouteLeg>, shapes: List<List<List<Point>>>): LaneRouteInput {
    val nodes = ArrayList<RouteNode>()
    val stepStarts = ArrayList<DoubleArray>(legs.size)
    val nodesByKey = HashMap<String, Int>()
    val unknownExits = ArrayList<Double>()
    var stepStart = 0.0
    var steps = 0
    for ((l, leg) in legs.withIndex()) {
        val legSteps = leg.steps().orEmpty()
        val starts = DoubleArray(legSteps.size)
        val maxSpeeds = leg.annotation()?.maxspeed()
        val besideFreeway = stepsBesideFreeway(legSteps)
        for ((k, step) in legSteps.withIndex()) {
            steps++
            starts[k] = stepStart
            val stepPoints = shapes.getOrNull(l)?.getOrNull(k).orEmpty()
            val shape = stepPoints.map { it.toGeoPoint() ?: offTheGlobe() }
            // As the lane choices list them, nulls included, so the keys count as theirs do.
            val listed = step.intersections().orEmpty()
            val intersections = listed.filterNotNull()
            val places = intersections.map { it.location().toGeoPoint() ?: offTheGlobe() }
            val meters = Polyline.distancesTo(shape, places)
            val type = step.maneuver().type()
            val stepType = ManeuverMapping.type(type)
            val besideFreewayStep = besideFreeway[k]
            var j = 0
            for ((raw, intersection) in listed.withIndex()) {
                if (intersection == null) continue
                val freeway = intersection.isFreeway()
                val node = RouteNode(
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
                    trafficSignal = intersection.trafficSignal() == true,
                    stopSign = intersection.stopSign() == true,
                )
                if (node.isLanePoint) {
                    nodesByKey["$routeId/$l/$k/$raw"] = nodes.size
                    // The first intersection of a step is the maneuver that ends the step before it.
                    if (raw == 0 && k > 0) nodesByKey["$routeId/$l/${k - 1}/end"] = nodes.size
                }
                nodes.add(node)
                j++
            }
            // The roads without lane data that leave on the side of the step's exit, as the lane choices
            // read them for the card's words, measured on the same shape as the intersections.
            val next = legSteps.getOrNull(k + 1)
            if (next != null) {
                val exitLanes = next.intersections()?.firstOrNull()?.routeLanes().orEmpty()
                val edge = RunUpRule.exitEdge(ManeuverMapping.modifier(next.maneuver().modifier()), exitLanes)
                if (edge != null) {
                    for (branch in exitSideBranches(listed, stepPoints, edge)) unknownExits.add(stepStart + branch.metersFromStepStart)
                }
            }
            stepStart += lengthOf(shape)
        }
        stepStarts.add(starts)
    }
    return LaneRouteInput(LaneRoute(routeId, steps, stepStart, nodes), stepStarts, nodesByKey, unknownExits)
}

/**
 * For each step of a leg's [legSteps], whether it lies next to a freeway, as [RoadKind.isRamp] asks: the step before it
 * ends on one, so a freeway leads to its maneuver, or the step after it begins on one, so a freeway leaves the ramp's end.
 */
internal fun stepsBesideFreeway(legSteps: List<LegStep>): BooleanArray {
    val endsOnFreeway = legSteps.map { it.intersections()?.lastOrNull()?.isFreeway() == true }
    val beginsOnFreeway = legSteps.map { it.intersections()?.firstOrNull()?.isFreeway() == true }
    return BooleanArray(legSteps.size) { k -> endsOnFreeway.getOrNull(k - 1) == true || beginsOnFreeway.getOrNull(k + 1) == true }
}

/**
 * A route as the app's own lanes read it ([route]) with the engine's index of its places, built once for
 * the route ([laneRouteInput]); it lives in memory with its route and is never stored.
 */
internal class LaneRouteInput(
    val route: LaneRoute,
    /** For each leg, where each of its steps begins along the route, in metres, as [route]'s stations are measured. */
    private val stepStarts: List<DoubleArray>,
    /**
     * The intersection, as its index in [route]'s nodes, of each lane point by the key the lane choices give
     * it (`route/leg/step/intersection`), and of each maneuver with lanes also by the key of the step it ends
     * (`route/leg/step/end`).
     */
    private val nodesByKey: Map<String, Int>,
    /** Where roads without lane data leave a step on the side of its exit, along the route, in route order. */
    val unknownExits: List<Double>,
) {
    /** Where along the route a car [metersOnStep] into step [step] of leg [leg] is; null for a step the route does not have. */
    fun station(leg: Int, step: Int, metersOnStep: Double): Double? {
        val start = stepStarts.getOrNull(leg)?.getOrNull(step) ?: return null
        return if (metersOnStep.isFinite()) start + metersOnStep.coerceAtLeast(0.0) else null
    }

    /** The intersection of the lane point the lane choices' [key] names, as its index in [route]'s nodes; null for any other key. */
    fun nodeOf(key: String?): Int? = if (key == null) null else nodesByKey[key]

    /** The lane choices' key of the lane point at [node], the index of its intersection in [route]'s nodes; null for one without lanes. */
    fun keyOf(node: Int): String? = keysByNode[node]

    /** Each lane point's key; a maneuver's is that of the step it ends, as the card's rows name it. */
    private val keysByNode: Map<Int, String> by lazy {
        val out = HashMap<Int, String>()
        for ((key, node) in nodesByKey) {
            val known = out[node]
            if (known == null || key.endsWith(END_KEY)) out[node] = key
        }
        out
    }

    private companion object {
        const val END_KEY = "/end"
    }
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
