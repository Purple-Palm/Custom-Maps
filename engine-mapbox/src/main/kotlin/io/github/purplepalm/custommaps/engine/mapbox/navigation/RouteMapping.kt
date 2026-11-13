package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.tripdata.maneuver.model.LaneIndicator
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.mapbox.navigation.tripdata.maneuver.model.Lane as MapboxLane
import com.mapbox.navigation.tripdata.maneuver.model.Maneuver as MapboxManeuver

internal fun GeoPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

/**
 * A position from [latitude] and [longitude] in degrees; null where they are no coordinate. Never
 * an exception: its message would carry the values.
 */
internal fun positionOrNull(latitude: Double, longitude: Double): GeoPoint? =
    if (latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude, longitude) else null

/** The route as the route sheet shows it; [place] is where it was asked to, for [RouteOption.endGap]. */
internal fun NavigationRoute.toRouteOption(place: Place?): RouteOption = RouteOption(
    id = id,
    duration = directionsRoute.duration().coerceAtLeast(0.0).seconds,
    distance = Distance.meters(directionsRoute.distance().coerceAtLeast(0.0)),
    summary = directionsRoute.legs()?.firstOrNull()?.summary().orEmpty(),
    endGap = place?.let { endGap(it, lastPoint()) },
)

/**
 * Where the route ends: the point on a road the routing service moved the destination to, or
 * else where the last step's maneuver, the arrival, is. Neither reads the route's shape.
 */
private fun NavigationRoute.lastPoint(): Point? =
    waypoints?.lastOrNull()?.location()
        ?: directionsRoute.legs()?.lastOrNull()?.steps()?.lastOrNull()?.maneuver()?.location()

/**
 * How far a route that ends at [routeEnd] ends from [place] itself, not from its stop point; null
 * when the route's end is not known.
 */
internal fun endGap(place: Place, routeEnd: Point?): Distance? {
    if (routeEnd == null) return null
    val meters = Geo.meters(place.point.latitude, place.point.longitude, routeEnd.latitude(), routeEnd.longitude())
    // A point that is no coordinate gives no distance: a bad value must not cost the preview.
    return if (meters.isFinite()) Distance.meters(meters) else null
}

/**
 * This progress as the start plan needs it: the route the navigator is on, and the one stop the
 * route still leads to. The stop is what Mapbox compares fresh routes with, to tell the same trip
 * from a new one.
 *
 * @param live whether the position the navigator reported with this progress is a recent one
 */
internal fun RouteProgress.toTripMoment(live: Boolean): TripMoment = TripMoment(
    routeId = navigationRoute.id,
    destination = soleDestination(navigationRoute.waypoints?.map { it.location() }, remainingWaypoints),
    offRoute = currentState == RouteProgressState.OFF_ROUTE,
    live = live,
)

internal fun MapboxLane.toLaneInfo(): LaneInfo? = laneInfoOf(allLanes)

/**
 * The next maneuver's lanes as the card draws them, from the navigation SDK's. A lane kept for car
 * pools, buses and the like is drawn dim, and is lit only where no lane that every car may use is
 * active. Null without lanes.
 */
internal fun laneInfoOf(lanes: List<LaneIndicator>): LaneInfo? {
    if (lanes.isEmpty()) return null
    val openLaneActive = lanes.any { it.isActive && it.accessDesignated.isEmpty() }
    return LaneInfo(
        lanes.map {
            ManeuverMapping.lane(
                it.directions,
                it.isActive,
                it.activeDirection,
                designated = it.accessDesignated.isNotEmpty(),
                openLaneActive = openLaneActive,
            )
        },
    )
}

/**
 * Snapshot for the UI: the upcoming maneuver from the maneuver API, the maneuver after it from the
 * route's steps, and the trip totals. Whether the Then chip shows is decided by the engine, which
 * remembers the earlier updates. [speedMps] is the car's matched speed at this update; [upcoming] the
 * leg's maneuvers after the next one, which the engine keeps for the whole step ([UpcomingManeuvers]).
 */
internal fun RouteProgress.toSnapshot(
    next: MapboxManeuver,
    speedMps: Double?,
    upcoming: List<FollowingManeuver> = emptyList(),
): RouteProgressSnapshot {
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
        upcoming = upcoming,
        offRoute = currentState == RouteProgressState.OFF_ROUTE,
        inTunnel = inTunnel,
        speedMps = speedMps,
    )
}

/**
 * The card of a route that has just come in, before the navigator's first instruction for it
 * ([RouteProgressSnapshot.pending]): the maneuver the step ends with, as the route's own step
 * describes it, and the trip's totals. Its distance is set, but the card does not show it.
 * [speedMps] is the car's matched speed at this update.
 */
internal fun RouteProgress.toPendingSnapshot(speedMps: Double?): RouteProgressSnapshot {
    val leg = currentLegProgress
    val step = leg?.currentStepProgress
    val next = step?.let { nextManeuverOf(leg?.routeLeg?.steps().orEmpty(), it.stepIndex) }
    return RouteProgressSnapshot(
        nextManeuver = next ?: Maneuver(type = ManeuverType.UNKNOWN, modifier = null, primaryText = ""),
        distanceToNextManeuver = Distance.meters((step?.distanceRemaining?.toDouble() ?: 0.0).coerceAtLeast(0.0)),
        lanes = null,
        distanceRemaining = Distance.meters(distanceRemaining.toDouble().coerceAtLeast(0.0)),
        durationRemaining = durationRemaining.coerceAtLeast(0.0).seconds,
        durationToNextManeuver = step?.durationRemaining.knownSeconds(),
        stepKey = "${navigationRoute.id}/${leg?.legIndex}/${step?.stepIndex}",
        offRoute = currentState == RouteProgressState.OFF_ROUTE,
        pending = true,
        inTunnel = inTunnel,
        speedMps = speedMps,
    )
}

/**
 * The maneuver step [stepIndex] of [steps] ends with, the next one while the car is on that step,
 * as the step's first banner describes it; null for the last step and for a step without banners.
 */
internal fun nextManeuverOf(steps: List<LegStep>, stepIndex: Int): Maneuver? = maneuverAtEndOf(steps, stepIndex)?.maneuver

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

/**
 * Every maneuver of the leg after the next one, while the car is on step [stepIndex] of [steps], the
 * leg's steps: in route order through the leg's arrival, each read from its step as
 * [followingManeuver] reads the one after the next, with its distance and time from the next
 * maneuver, summed along the way. So the first is the one [followingManeuver] gives.
 *
 * The way out of a roundabout is no item, as the roundabout's arrow shows it already: its length and
 * time go to the maneuver after it. A step without a banner, or with a length that is not a number,
 * ends the list there, since every distance after it would be wrong. A time that is not known makes
 * the time of that maneuver and of every one after it unknown (zero), and the list goes on. Built
 * once for each step of a drive ([UpcomingManeuvers]).
 */
internal fun upcomingManeuvers(steps: List<LegStep>, stepIndex: Int): List<FollowingManeuver> {
    val upcoming = ArrayList<FollowingManeuver>()
    // Summed in the order followingManeuver sums a roundabout's way out and the road after it, so
    // the first item is equal to its answer, to the last bit.
    var distance: Distance? = null
    var duration: Duration? = null
    var timeKnown = true
    var index = stepIndex + 1
    while (true) {
        val step = maneuverAtEndOf(steps, index) ?: break
        distance = distance?.plus(step.distanceAfterNext) ?: step.distanceAfterNext
        if (step.durationAfterNext.isPositive()) {
            duration = duration?.plus(step.durationAfterNext) ?: step.durationAfterNext
        } else {
            timeKnown = false
        }
        if (!ManeuverMapping.isRoundaboutExit(step.maneuver.type)) {
            upcoming += FollowingManeuver(step.maneuver, distance, duration?.takeIf { timeKnown } ?: Duration.ZERO)
        }
        index += 1
    }
    return upcoming
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
