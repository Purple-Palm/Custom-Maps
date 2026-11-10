package io.github.purplepalm.custommaps.nav.guidance

/** What may be done to the routes of a running trip; the answer of [InTripRefresh.decide]. */
enum class InTripAction {
    /** Nothing: the trip goes on with the routes it has. */
    NONE,

    /** Ask the navigator to refresh the traffic and the times of the routes it has. */
    REFRESH,

    /** Give the navigator fresh routes to the same destination, in place of the ones it has. */
    SET_ROUTES,
}

/**
 * Decides what a [StartPlan] may do to the routes of a running trip. Pure: the engine asks and only
 * carries out the answer.
 *
 * The rules are about what Mapbox bills. A trip is billed once, when its session starts. Inside it,
 * refreshing its routes costs nothing, and neither does replacing them with other routes to the
 * same destination. But the navigator compares the destinations through its latest progress, and
 * takes routes that arrive before the first progress of a trip, or that end more than
 * [MAX_DESTINATION_SHIFT_METERS] from where the trip is going, for a new trip, billed again. An
 * empty list of routes ends guidance while the session goes on, which is billed by the hour. So
 * nothing happens before the first progress, an empty list is never set, and a destination that
 * moved is never set.
 */
object InTripRefresh {

    /**
     * Routes whose destination lies farther than this from the destination of the trip are never
     * set: the navigator's own limit for "the same trip". Exactly at the limit is still the same.
     */
    const val MAX_DESTINATION_SHIFT_METERS = 100.0

    /** Why nothing may be done; the first one that applies, in this order. */
    enum class Obstacle {
        /** Guidance is not running any more, or not for the trip that was asked about. */
        GUIDANCE_ENDED,

        /** The start plan asks for nothing inside the trip. */
        NOTHING_PLANNED,

        /** The navigator has not reported a progress for the routes it has now. */
        NO_PROGRESS,

        /** The navigator found the car off the route and replaces the routes itself. */
        REROUTED,

        /** There are no routes: none in the navigator, or none in the answer to a request. */
        NO_ROUTES,

        /** How far the new destination is from the trip's is not known. */
        DESTINATION_UNKNOWN,

        /** The new destination is farther than [MAX_DESTINATION_SHIFT_METERS] from the trip's. */
        DESTINATION_MOVED,
    }

    /**
     * What may be done now. It is asked at two kinds of moment in a trip, with the same rules.
     *
     * Before something is begun, to learn what to begin: at the first progress of the trip, and
     * again when a refresh has failed or was cancelled. [routeCount] is then the number of routes
     * the navigator guides on, and [destinationShiftMeters] is zero, as nothing has been requested
     * yet. [InTripAction.REFRESH] says to ask for a refresh, [InTripAction.SET_ROUTES] to request
     * routes again.
     *
     * And when the answer to that request is there, to learn whether its routes may be set:
     * [routeCount] is then the number of routes in the answer, and [destinationShiftMeters] how far
     * their destination is from the one of the route the navigator is on.
     *
     * @param plan the plan of this trip's start; [StartPlan.REPLAN_IN_TRIP] also once a refresh
     *   has failed
     * @param progressSeen the navigator has reported a progress of this trip, for the routes it has
     *   at this moment
     * @param guidanceActive guidance runs, for the trip the question is about
     * @param rerouteSeen the navigator has found the car off the route during this trip, or has
     *   replaced the routes by itself
     * @param routeCount see above
     * @param destinationShiftMeters see above; null when it is not known
     */
    fun decide(
        plan: StartPlan,
        progressSeen: Boolean,
        guidanceActive: Boolean,
        rerouteSeen: Boolean,
        routeCount: Int,
        destinationShiftMeters: Double?,
    ): InTripAction {
        if (obstacle(plan, progressSeen, guidanceActive, rerouteSeen, routeCount, destinationShiftMeters) != null) {
            return InTripAction.NONE
        }
        return when (plan) {
            StartPlan.AS_PREVIEWED -> InTripAction.NONE
            StartPlan.REFRESH_IN_TRIP -> InTripAction.REFRESH
            StartPlan.REPLAN_IN_TRIP -> InTripAction.SET_ROUTES
        }
    }

    /**
     * Why [decide] answers [InTripAction.NONE] for the same values, for the debug log; null when it
     * answers anything else.
     */
    fun obstacle(
        plan: StartPlan,
        progressSeen: Boolean,
        guidanceActive: Boolean,
        rerouteSeen: Boolean,
        routeCount: Int,
        destinationShiftMeters: Double?,
    ): Obstacle? = when {
        !guidanceActive -> Obstacle.GUIDANCE_ENDED
        plan == StartPlan.AS_PREVIEWED -> Obstacle.NOTHING_PLANNED
        !progressSeen -> Obstacle.NO_PROGRESS
        rerouteSeen -> Obstacle.REROUTED
        routeCount <= 0 -> Obstacle.NO_ROUTES
        // A refresh keeps the routes, and with them the destination.
        plan == StartPlan.REFRESH_IN_TRIP -> null
        // Not a distance: no number, or one below zero.
        destinationShiftMeters == null || !(destinationShiftMeters >= 0.0) -> Obstacle.DESTINATION_UNKNOWN
        destinationShiftMeters > MAX_DESTINATION_SHIFT_METERS -> Obstacle.DESTINATION_MOVED
        else -> null
    }
}
