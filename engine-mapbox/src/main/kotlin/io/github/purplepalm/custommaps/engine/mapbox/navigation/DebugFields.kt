package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.os.SystemClock
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.geo.Geo

/*
 * What the debug log records about Mapbox objects. Only plain values go in: no request URLs,
 * which carry the access token.
 */

internal fun Point.debugFields(): List<Double> = listOf(latitude(), longitude())

/** A route with every leg and step: roads, maneuvers, distances, times and traffic. */
internal fun NavigationRoute.debugFields(): Map<String, Any?> {
    val route = directionsRoute
    return mapOf(
        "id" to id,
        "origin" to origin,
        "meters" to route.distance(),
        "seconds" to route.duration(),
        "typicalSeconds" to route.durationTypical(),
        "legs" to route.legs().orEmpty().map { it.debugFields() },
    )
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

private fun RouteLeg.debugFields(): Map<String, Any?> = mapOf(
    "summary" to summary(),
    "meters" to distance(),
    "seconds" to duration(),
    "typicalSeconds" to durationTypical(),
    // How much of the leg is in each traffic level, counted in route segments.
    "congestion" to congestionLevels(),
    "steps" to steps().orEmpty().map { it.debugFields() },
)

/**
 * Navigation routes come with numeric congestion (0 to 100) rather than levels; it is grouped
 * here with the SDK's default level ranges.
 */
private fun RouteLeg.congestionLevels(): Map<String, Int>? {
    val annotation = annotation() ?: return null
    annotation.congestionNumeric()?.let { values ->
        return values.groupingBy { value ->
            when {
                value == null -> "unknown"
                value < MODERATE_CONGESTION -> "low"
                value < HEAVY_CONGESTION -> "moderate"
                value < SEVERE_CONGESTION -> "heavy"
                else -> "severe"
            }
        }.eachCount()
    }
    return annotation.congestion()?.groupingBy { it }?.eachCount()
}

private const val MODERATE_CONGESTION = 40
private const val HEAVY_CONGESTION = 60
private const val SEVERE_CONGESTION = 80

private fun LegStep.debugFields(): Map<String, Any?> {
    val maneuver = maneuver()
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

internal fun Location.debugFields(): Map<String, Any?> = mapOf(
    "lat" to latitude,
    "lon" to longitude,
    "speedMps" to speed,
    "bearing" to bearing,
    "accuracyMeters" to horizontalAccuracy,
    "source" to source,
    "fixAgeMs" to ageMillis(),
)

/**
 * How old the fix is now, when its line is made. The monotonic time is Android's elapsed realtime
 * for fixes from its location service; without it, the wall clock time of the fix is used.
 */
private fun Location.ageMillis(): Long {
    val monotonic = monotonicTimestamp
    return if (monotonic != null) {
        (SystemClock.elapsedRealtimeNanos() - monotonic) / NANOS_PER_MILLI
    } else {
        System.currentTimeMillis() - timestamp
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

/** A failed route request. The request URL is left out on purpose: it contains the token. */
internal fun RouterFailure.debugFields(): Map<String, Any?> = mapOf(
    "message" to message,
    "type" to type,
    "origin" to routerOrigin,
    "retryable" to isRetryable,
    "error" to throwable?.toString(),
)
