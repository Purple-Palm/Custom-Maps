package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.os.SystemClock
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.utils.DecodeUtils.stepsGeometryToPoints
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import io.github.purplepalm.custommaps.engine.mapbox.map.StepShapes
import io.github.purplepalm.custommaps.nav.camera.Bend
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.RouteCounts
import io.github.purplepalm.custommaps.nav.geo.Geo

/*
 * What the debug log records about Mapbox objects. Only plain values go in: no request URLs,
 * which carry the access token.
 */

internal fun Point.debugFields(): List<Double> = listOf(latitude(), longitude())

/**
 * A route with every leg and step: roads, maneuvers, distances, times and traffic, and the
 * intersections that have lanes. It reads the shape of every step, which is too much work for the
 * main thread: call it on a background thread.
 */
internal fun NavigationRoute.debugFields(): Map<String, Any?> {
    val route = directionsRoute
    // One list of points for each step of each leg.
    val shapes = route.stepsGeometryToPoints()
    val legs = route.legs().orEmpty()
    return mapOf(
        "id" to id,
        "origin" to origin,
        "meters" to route.distance(),
        "seconds" to route.duration(),
        "typicalSeconds" to route.durationTypical(),
        // The stretches of heavy and of severe traffic, how many and how long each, in meters.
        "congestionRuns" to RouteCounts.congestionRuns(legs.mapNotNull { it.congestionSegments() }),
        "legs" to legs.mapIndexed { index, leg -> leg.debugFields(shapes.getOrNull(index).orEmpty()) },
    )
}

/**
 * The traffic level of each segment of the leg, by the names of [congestionLevels], with each
 * segment's length; null when the route has no traffic or no lengths for it.
 */
private fun RouteLeg.congestionSegments(): Pair<List<String?>, List<Double>>? {
    val annotation = annotation() ?: return null
    val meters = annotation.distance() ?: return null
    val levels = annotation.congestionNumeric()?.map(::congestionLevel) ?: annotation.congestion() ?: return null
    return levels to meters
}

/** The level of a numeric congestion value (0 to 100), in the SDK's default ranges. */
private fun congestionLevel(value: Int?): String = when {
    value == null -> "unknown"
    value < MODERATE_CONGESTION -> "low"
    value < HEAVY_CONGESTION -> "moderate"
    value < SEVERE_CONGESTION -> RouteCounts.HEAVY
    else -> RouteCounts.SEVERE
}

/**
 * Where the Directions API put each requested point, in order: the road it snapped to and how far
 * it moved the point ("snappedMeters"). A large move at the destination ends the route elsewhere.
 */
internal fun NavigationRoute.waypointsDebugFields(requested: List<Point>): List<Map<String, Any?>>? =
    waypoints?.mapIndexed { index, waypoint ->
        val snapped = waypoint.location()
        mapOf(
            "name" to waypoint.name(),
            "at" to snapped.debugFields(),
            "snappedMeters" to requested.getOrNull(index)?.let { asked ->
                Motion.round(Geo.meters(asked.latitude(), asked.longitude(), snapped.latitude(), snapped.longitude()), 1)
            },
        )
    }

private fun RouteLeg.debugFields(stepShapes: List<List<Point>>): Map<String, Any?> = mapOf(
    "summary" to summary(),
    "meters" to distance(),
    "seconds" to duration(),
    "typicalSeconds" to durationTypical(),
    // How much of the leg is in each traffic level, counted in route segments.
    "congestion" to congestionLevels(),
    "steps" to steps().orEmpty().mapIndexed { index, step -> step.debugFields(stepShapes.getOrNull(index).orEmpty()) },
)

/**
 * Navigation routes come with numeric congestion (0 to 100) rather than levels; it is grouped
 * here with the SDK's default level ranges.
 */
private fun RouteLeg.congestionLevels(): Map<String, Int>? {
    val annotation = annotation() ?: return null
    annotation.congestionNumeric()?.let { values ->
        return values.groupingBy(::congestionLevel).eachCount()
    }
    return annotation.congestion()?.groupingBy { it }?.eachCount()
}

private const val MODERATE_CONGESTION = 40
private const val HEAVY_CONGESTION = 60
private const val SEVERE_CONGESTION = 80

private fun LegStep.debugFields(shape: List<Point>): Map<String, Any?> {
    val maneuver = maneuver()
    val bend = stepBend(maneuver.type(), maneuver.bearingBefore(), shape)
    return mapOf(
        "road" to name(),
        "ref" to ref(),
        "destinations" to destinations(),
        "exits" to exits(),
        "rotary" to rotaryName(),
        "type" to maneuver.type(),
        "modifier" to maneuver.modifier(),
        "exit" to maneuver.exit(),
        "instruction" to maneuver.instruction(),
        "at" to maneuver.location().debugFields(),
        "meters" to distance(),
        "seconds" to duration(),
        "drivingSide" to drivingSide(),
        "speedLimitSign" to speedLimitSign(),
        "lanes" to bannerInstructions()?.firstOrNull()?.sub()?.components()
            ?.filter { it.type() == "lane" }
            ?.map { mapOf("directions" to it.directions(), "active" to it.active(), "activeDirection" to it.activeDirection()) },
        "laneIntersections" to laneIntersectionFields(intersections().orEmpty(), shape),
        // The class of the step's road, as it leaves the maneuver, and what the step has on the way.
        "roadClass" to intersections()?.firstOrNull()?.mapboxStreetsV8()?.roadClass(),
    ) + stepSignFields(intersections().orEmpty()) + mapOf(
        // Which way the road runs into and out of the maneuver, and where it bends after it: numbers only.
        "bearingBefore" to maneuver.bearingBefore(),
        "bearingAfter" to maneuver.bearingAfter(),
        "bendM" to bend?.meters?.let { Motion.round(it, 1) },
        "bendDeg" to bend?.degrees?.let { Motion.round(it, 1) },
    )
}

/**
 * How many of a step's intersections have traffic signals, a stop sign, a yield sign or a railway
 * crossing, as the route's intersections flag them; each null when no intersection of the step
 * says anything of it ([RouteCounts.flagged]).
 */
internal fun stepSignFields(intersections: List<StepIntersection?>): Map<String, Any?> = mapOf(
    "trafficSignals" to RouteCounts.flagged(intersections.map { it?.trafficSignal() }),
    "stopSigns" to RouteCounts.flagged(intersections.map { it?.stopSign() }),
    "yieldSigns" to RouteCounts.flagged(intersections.map { it?.yieldSign() }),
    "railwayCrossings" to RouteCounts.flagged(intersections.map { it?.railwayCrossing() }),
)

/**
 * The speed limit the navigator has for the road under the car, for the "progress" line when it
 * changes: the [speed] (null where the road has none), its [unit] and the [sign] type.
 */
internal fun speedLimitFields(speed: Int?, unit: String, sign: String): Map<String, Any?> =
    mapOf("value" to speed, "unit" to unit, "sign" to sign)

/**
 * Where the road of a step bends after its maneuver point, as the junction's frame with Large finds
 * it ([StepShapes.bendOf]): how often a step begins well before its bend, and at what angle, can be
 * counted from the log. Null for the departure, which has no way in, and without the bearing in.
 *
 * @param shape the points of the step, from its maneuver point
 */
internal fun stepBend(type: String?, bearingBefore: Double?, shape: List<Point>): Bend? {
    if (type == StepManeuver.DEPART || bearingBefore == null) return null
    return StepShapes.bendOf(shape, bearingBefore, CameraTuning.PHONE)
}

/**
 * The intersections of a step that have lanes of their own, which is where a lane choice can be,
 * also where the route goes straight on: how far into the step each one lies, its lanes as the
 * lane choice rule counts them, the classes of the road that leaves it, and whether the road that
 * leads to it is a freeway. With these the rule can be tried with other numbers after a drive.
 * Null for a step without any, which is most steps.
 *
 * @param shape the points of the step; each intersection lies on one of them
 */
internal fun laneIntersectionFields(intersections: List<StepIntersection?>, shape: List<Point>): List<Map<String, Any?>>? =
    stepLanes(intersections, shape).takeIf { it.isNotEmpty() }?.map { point ->
        mapOf(
            "meters" to Motion.round(point.metersFromStepStart, 0),
            "lanes" to point.counts.lanes,
            "usable" to point.counts.usable,
            "active" to point.counts.active,
            "designated" to point.counts.designated,
            // Copied: the route may hand out a view of its own data.
            "classes" to intersections[point.index]?.classes()?.toList(),
            "freeway" to point.onFreeway,
        )
    }

/** One moment of guidance: where on the route, what is left and what comes next. */
internal fun RouteProgress.debugFields(): Map<String, Any?> {
    val leg = currentLegProgress
    val step = leg?.currentStepProgress
    val upcoming = leg?.upcomingStep
    return mapOf(
        "state" to currentState.name,
        "routeId" to navigationRoute.id,
        "leg" to leg?.legIndex,
        "step" to step?.stepIndex,
        "road" to step?.step?.name(),
        "stepMetersLeft" to step?.distanceRemaining,
        "stepSecondsLeft" to step?.durationRemaining,
        "metersLeft" to distanceRemaining,
        "secondsLeft" to durationRemaining,
        "metersDone" to distanceTraveled,
        "next" to upcoming?.let {
            mapOf(
                "type" to it.maneuver().type(),
                "modifier" to it.maneuver().modifier(),
                "road" to it.name(),
                "instruction" to it.maneuver().instruction(),
            )
        },
        // The maneuver after the next one, which the Then chip shows when the two are close.
        "after" to followingManeuver()?.let {
            mapOf(
                "type" to it.maneuver.type,
                "modifier" to it.maneuver.modifier,
                "road" to it.maneuver.primaryText,
                "metersAfterNext" to it.distanceAfterNext.meters,
                "secondsAfterNext" to it.durationAfterNext.inWholeMilliseconds / MILLIS_PER_SECOND,
            )
        },
        "banner" to bannerInstructions?.primary()?.text(),
        "voice" to voiceInstructions?.announcement(),
        "inTunnel" to inTunnel,
        "stale" to stale,
        "waypointsLeft" to remainingWaypoints,
    )
}

/** The navigator's view of the position: map-matched location, road and speed limit. */
internal fun LocationMatcherResult.debugFields(): Map<String, Any?> = mapOf(
    "location" to enhancedLocation.debugFields(),
    "road" to road.components.joinToString(" ") { it.text }.ifBlank { null },
    "speedLimit" to speedLimitInfo.speed,
    "speedLimitUnit" to speedLimitInfo.unit.name,
    "offRoad" to isOffRoad,
    "teleport" to isTeleport,
    "matchProbability" to roadEdgeMatchProbability,
    "inTunnel" to inTunnel,
    // Predicted points the drawn car glides through up to this fix; none means one straight move.
    "keyPoints" to keyPoints.size,
)

/**
 * A position for the debug log. "fixAgeMs" is how old the position is; a replayed one, of a
 * simulated drive, has "replayed" true instead, since its times are the replayer's ([isReplayed]).
 */
internal fun Location.debugFields(): Map<String, Any?> {
    val replayed = isReplayed()
    val fields = mapOf(
        "lat" to latitude,
        "lon" to longitude,
        "speedMps" to speed,
        "bearing" to bearing,
        "accuracyMeters" to horizontalAccuracy,
        "source" to source,
        "fixAgeMs" to if (replayed) null else ageMillis(),
    )
    return if (replayed) fields + ("replayed" to true) else fields
}

/**
 * What a route request says about the position it starts from: how old it was, and the speed and
 * the direction the location service gave with it. The direction goes into the request only for a
 * fresh position of a moving car ([DeviceFix.originBearing]).
 */
internal fun DeviceFix.debugFields(): Map<String, Any?> = mapOf(
    "ageMs" to ageMillis.takeUnless { replayed },
    "speedMps" to speed,
    "bearing" to bearing,
) + if (replayed) mapOf("replayed" to true) else emptyMap()

/** How old the fix is now, when its line is made; see [fixAgeMillis]. */
private fun Location.ageMillis(): Long =
    fixAgeMillis(monotonicTimestamp, timestamp, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())

private const val MILLIS_PER_SECOND = 1_000.0

/** A failed route request. The request URL is left out on purpose: it contains the token. */
internal fun RouterFailure.debugFields(): Map<String, Any?> = mapOf(
    "message" to message,
    "type" to type,
    "origin" to routerOrigin,
    "retryable" to isRetryable,
    "error" to throwable?.toString(),
)
