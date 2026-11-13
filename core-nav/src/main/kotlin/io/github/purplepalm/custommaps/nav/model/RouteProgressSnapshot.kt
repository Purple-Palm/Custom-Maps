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
    /**
     * Every maneuver of this leg after the next one, in route order through the leg's arrival, each
     * with its distance and time from the next maneuver as [following] has them, summed along the way:
     * the first is the one [following] names. The way out of a roundabout is none of them, since the
     * roundabout's arrow shows it; a step the route gives no banner or no length for ends the list,
     * as every distance after it would be wrong. Empty when the next maneuver is the arrival and on a
     * [pending] card. The same list object for every update of one step, so a screen builds its rows
     * once a step.
     */
    val upcoming: List<FollowingManeuver> = emptyList(),
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
    /**
     * The lane row of a run-up: the last part of a freeway step before its exit or fork, from where
     * the close view starts for it to the gore. While it is set the card shows its lanes in place of
     * [laneChoiceAhead] and [lanes], with the lanes to use in words where they have some. Null
     * outside a run-up, and on a pending card.
     */
    val runUpLanes: RunUpLanes? = null,
    /** The navigator says the car is in a tunnel at this update. */
    val inTunnel: Boolean = false,
    /**
     * The car's speed at this update, in meters a second, as the navigator matched it: the speed
     * the lane choices of the same update were read with. Null when unknown.
     */
    val speedMps: Double? = null,
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

/**
 * The card's lane row on a run-up to an exit or a fork: the lanes of the next point on the way
 * where a lane at the exit's side leaves the route, lit on the one lane that leads through every
 * such point to the exit, and from the last of them on the exit's own lanes. Where the route has a
 * lane plan, the plan lights it and marks the lanes the map does not draw yet as outlines. It holds
 * no distance, so it stays the same object for as long as the row does.
 */
data class RunUpLanes(
    /** The lanes the card draws, from the leftmost: the ones to use recommended, lanes for car pools and buses dim. */
    val lanes: LaneInfo,
    /** The lanes to use in a few words; null when the row has none. */
    val target: LaneTarget?,
    /** The point whose lanes the row shows: a point on the way, or the exit's own, as the lane choices key them. */
    val key: String,
    /** Why the row has no words although it lights lanes, for the log; null otherwise. */
    val odd: String?,
    /**
     * Where the first lit lane is among the lanes open to every car, counted from the exit's side
     * from 0, for the log; null when the side is not known or no lane open to every car is lit.
     */
    val need: Int? = null,
    /** The side the exit leaves on, which the lanes and the words count from; null when it cannot be told. */
    val edge: LaneEdge? = null,
)
