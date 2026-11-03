package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import kotlin.time.Duration.Companion.seconds
import com.mapbox.navigation.tripdata.maneuver.model.Lane as MapboxLane
import com.mapbox.navigation.tripdata.maneuver.model.Maneuver as MapboxManeuver

internal fun GeoPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

internal fun NavigationRoute.toRouteOption(): RouteOption = RouteOption(
    id = id,
    duration = directionsRoute.duration().coerceAtLeast(0.0).seconds,
    distance = Distance.meters(directionsRoute.distance().coerceAtLeast(0.0)),
    summary = directionsRoute.legs()?.firstOrNull()?.summary().orEmpty(),
)

internal fun MapboxLane.toLaneInfo(): LaneInfo? = allLanes
    .takeIf { it.isNotEmpty() }
    ?.let { lanes -> LaneInfo(lanes.map { ManeuverMapping.lane(it.directions, it.isActive, it.activeDirection) }) }

/** Snapshot for the UI: the upcoming maneuver from the maneuver API plus the trip totals. */
internal fun RouteProgress.toSnapshot(next: MapboxManeuver): RouteProgressSnapshot {
    val type = ManeuverMapping.type(next.primary.type)
    val exit = if (ManeuverMapping.isRoundabout(type)) currentLegProgress?.upcomingStep?.maneuver()?.exit() else null
    val maneuver = Maneuver(
        type = type,
        modifier = ManeuverMapping.modifier(next.primary.modifier),
        primaryText = next.primary.text,
        secondaryText = next.secondary?.text?.takeIf { it.isNotBlank() },
        roundaboutExitNumber = exit?.takeIf { it > 0 },
    )
    val toManeuver = next.stepDistance.distanceRemaining ?: next.stepDistance.totalDistance
    return RouteProgressSnapshot(
        nextManeuver = maneuver,
        distanceToNextManeuver = Distance.meters(toManeuver.coerceAtLeast(0.0)),
        lanes = next.laneGuidance?.toLaneInfo(),
        distanceRemaining = Distance.meters(distanceRemaining.toDouble().coerceAtLeast(0.0)),
        durationRemaining = durationRemaining.coerceAtLeast(0.0).seconds,
    )
}
