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
    /**
     * A lane choice the car reaches before the next maneuver, where the route goes straight on while
     * some lanes leave it; null on most of the road. While it is set, the card shows its lanes in
     * place of [lanes], with its distance.
     */
    val laneChoiceAhead: LanesAhead? = null,
    /**
     * True right after a new route, before the navigator's first instruction for it: [nextManeuver]
     * is then the new route's next maneuver as the route has it, and the card shows it dimmed and
     * without its distance. Never a maneuver of the route before (`CardContinuity`).
     */
    val pending: Boolean = false,
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

/**
 * The lanes of a lane choice ahead of the next maneuver, and how far ahead it is. The lanes are
 * the same object from one progress update to the next for as long as it is the same choice.
 */
data class LanesAhead(
    /** Along the route, from the car to the choice. */
    val distance: Distance,
    /** Every lane that reaches the choice, from the leftmost; the ones to use are the recommended ones. */
    val lanes: LaneInfo,
)
