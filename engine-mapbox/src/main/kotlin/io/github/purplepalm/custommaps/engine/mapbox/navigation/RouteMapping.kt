package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import kotlin.time.Duration
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

/**
 * Snapshot for the UI: the upcoming maneuver from the maneuver API, the maneuver after it from the
 * route's steps, and the trip totals. Whether the Then chip shows is decided by the engine, which
 * remembers the earlier updates.
 */
internal fun RouteProgress.toSnapshot(next: MapboxManeuver): RouteProgressSnapshot {
    val type = ManeuverMapping.type(next.primary.type)
    val roundabout = ManeuverMapping.isRoundabout(type)
    val leg = currentLegProgress
    val step = leg?.currentStepProgress
    val exit = if (roundabout) leg?.upcomingStep?.maneuver()?.exit() else null
    val maneuver = Maneuver(
        type = type,
        modifier = ManeuverMapping.modifier(next.primary.modifier),
        primaryText = next.primary.text,
        secondaryText = next.secondary?.text?.takeIf { it.isNotBlank() },
        roundaboutExitNumber = exit?.takeIf { it > 0 },
        roundaboutExitDegrees = if (roundabout) next.primary.degrees else null,
    )
    val toManeuver = next.stepDistance.distanceRemaining ?: next.stepDistance.totalDistance
    return RouteProgressSnapshot(
        nextManeuver = maneuver,
        distanceToNextManeuver = Distance.meters(toManeuver.coerceAtLeast(0.0)),
        lanes = next.laneGuidance?.toLaneInfo(),
        distanceRemaining = Distance.meters(distanceRemaining.toDouble().coerceAtLeast(0.0)),
        durationRemaining = durationRemaining.coerceAtLeast(0.0).seconds,
        durationToNextManeuver = step?.durationRemaining.knownSeconds(),
        stepKey = "${navigationRoute.id}/${leg?.legIndex}/${step?.stepIndex}",
        following = followingManeuver(),
        offRoute = currentState == RouteProgressState.OFF_ROUTE,
    )
}

/** The maneuver after the next one at this moment of the drive; null when none follows in this leg. */
internal fun RouteProgress.followingManeuver(): FollowingManeuver? {
    val leg = currentLegProgress ?: return null
    val step = leg.currentStepProgress ?: return null
    return followingManeuver(leg.routeLeg?.steps().orEmpty(), step.stepIndex)
}

/**
 * The maneuver after the next one, while the car is on step [stepIndex] of [steps].
 *
 * Step k of a leg runs from maneuver k to maneuver k + 1, and its banners describe maneuver k + 1;
 * the SDK's maneuver list is built the same way. So step k + 1 lies between the next maneuver and
 * the following one, its first banner describes the following one, and step k + 2 starts with it.
 * Read from the route's steps, not from the banner's `sub`: the Directions API puts lanes there
 * instead whenever the next maneuver has lanes, which is where the following maneuver matters most.
 *
 * Routes come with a step of their own for the way out of a roundabout. When the next maneuver
 * goes into a roundabout, the way out is no maneuver to announce, since the roundabout arrow shows
 * it already: what follows is the maneuver after the way out, the roundabout's own length included.
 */
internal fun followingManeuver(steps: List<LegStep>, stepIndex: Int): FollowingManeuver? {
    val following = maneuverAtEndOf(steps, stepIndex + 1) ?: return null
    if (!ManeuverMapping.isRoundaboutExit(following.maneuver.type)) return following
    val beyond = maneuverAtEndOf(steps, stepIndex + 2) ?: return null
    val bothTimesKnown = following.durationAfterNext.isPositive() && beyond.durationAfterNext.isPositive()
    return FollowingManeuver(
        maneuver = beyond.maneuver,
        distanceAfterNext = following.distanceAfterNext + beyond.distanceAfterNext,
        durationAfterNext = if (bothTimesKnown) following.durationAfterNext + beyond.durationAfterNext else Duration.ZERO,
    )
}

/** The maneuver step [index] ends with, as the step's first banner describes it, and the step's length and time. */
private fun maneuverAtEndOf(steps: List<LegStep>, index: Int): FollowingManeuver? {
    val step = steps.getOrNull(index) ?: return null
    // No step after it: this step is the arrival, and nothing follows it.
    val after = steps.getOrNull(index + 1) ?: return null
    val banner = step.bannerInstructions()?.firstOrNull()?.primary() ?: return null
    // A length that is not a number gives nothing: a bad value must not end the drive.
    val meters = step.distance().takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val type = ManeuverMapping.type(banner.type())
    val roundabout = ManeuverMapping.isRoundabout(type)
    return FollowingManeuver(
        maneuver = Maneuver(
            type = type,
            modifier = ManeuverMapping.modifier(banner.modifier()),
            primaryText = banner.text(),
            roundaboutExitNumber = if (roundabout) after.maneuver().exit()?.takeIf { it > 0 } else null,
            roundaboutExitDegrees = if (roundabout) banner.degrees() else null,
        ),
        distanceAfterNext = Distance.meters(meters),
        durationAfterNext = step.duration().knownSeconds(),
    )
}

/** Seconds as a duration; zero when the value is missing, negative or not a number. */
private fun Number?.knownSeconds(): Duration =
    this?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }?.seconds ?: Duration.ZERO
