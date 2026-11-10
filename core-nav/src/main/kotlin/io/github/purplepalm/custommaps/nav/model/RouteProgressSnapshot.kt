package io.github.purplepalm.custommaps.nav.model

import kotlin.time.Duration

/** Guidance state at one moment, as the UI and the camera logic need it. */
data class RouteProgressSnapshot(
    val nextManeuver: Maneuver,
    val distanceToNextManeuver: Distance,
    val lanes: LaneInfo?,
    val distanceRemaining: Distance,
    val durationRemaining: Duration,
    /** Expected time to the next maneuver at the route's speeds; zero when unknown. */
    val durationToNextManeuver: Duration = Duration.ZERO,
    /**
     * Route id, leg and step. It changes at every step change and with every new route, even when
     * the new route's leg and step numbers are the same.
     */
    val stepKey: String = "",
    /** The maneuver after the next one in this leg; null when the next one is the arrival. */
    val following: FollowingManeuver? = null,
    /** The navigator has left the route; until a new route arrives, the maneuvers may be wrong. */
    val offRoute: Boolean = false,
    /** Whether the Then chip shows [following], as decided by the Then rule. */
    val showThen: Boolean = false,
) {
    /** The maneuver the Then chip shows, or null. The phone and the car screen both read this. */
    val thenManeuver: FollowingManeuver? get() = if (showThen) following else null
}

/** The maneuver after the next one, and how far it comes after the next one. */
data class FollowingManeuver(
    val maneuver: Maneuver,
    /** Along the route, from the next maneuver to this one. */
    val distanceAfterNext: Distance,
    /** Expected time from the next maneuver to this one at the route's speeds; zero when unknown. */
    val durationAfterNext: Duration,
)
