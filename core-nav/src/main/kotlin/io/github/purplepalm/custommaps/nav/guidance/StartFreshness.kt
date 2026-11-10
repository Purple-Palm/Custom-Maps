package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Distance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What Start does about routes that were previewed a while ago. In every plan the previewed routes
 * go to the navigator at once and the drive begins on them: Start never waits for anything. The
 * plans differ in what follows inside the trip, once the navigator has reported its first progress
 * ([InTripRefresh]).
 */
enum class StartPlan {
    /** Nothing follows: the routes are as good as new. */
    AS_PREVIEWED,

    /** The traffic and the times of the routes are refreshed. Their roads stay the same. */
    REFRESH_IN_TRIP,

    /** Routes are requested again from where the car is, and take the place of the previewed ones. */
    REPLAN_IN_TRIP,
}

/**
 * Picks the [StartPlan] from how old the preview is and how far the car has moved since the routes
 * were requested. The limits are first settings, to be checked against drive logs.
 */
object StartFreshness {

    /**
     * A preview older than this gets its traffic refreshed. Mapbox refreshes the traffic of a route
     * every five minutes during a drive, so a younger preview is no older than a route under way.
     */
    val REFRESH_AFTER: Duration = 5.minutes

    /**
     * A car farther than this from where the routes were requested counts as moved. Well above the
     * error of a position, and above the few car lengths a car rolls while its driver looks at the
     * routes.
     */
    val MOVED_BEYOND: Distance = Distance.meters(100.0)

    /**
     * A preview older than this is planned again: after half an hour the traffic has changed enough
     * for another route to be the best one, which a refresh of the old routes cannot find.
     */
    val REPLAN_AFTER: Duration = 30.minutes

    /**
     * The plan for a start.
     *
     * A preview up to [REFRESH_AFTER] old, with the car within [MOVED_BEYOND] of where it was
     * planned, starts as previewed; exactly at a limit still counts as within it. An older preview,
     * or a car that moved farther, has its routes refreshed inside the trip. A car that has left the
     * route by then is put on a new one by the navigator itself, which no plan repeats. A preview
     * older than [REPLAN_AFTER] is planned again inside the trip, however little the car moved.
     *
     * @param age how long ago the routes arrived
     * @param moved how far the car is from where the routes were requested; null when its position
     *   is not known, and then only the age counts
     * @param simulated a replayed drive: it starts at the route's own origin whenever it is started,
     *   and is always as previewed
     */
    fun plan(age: Duration, moved: Distance?, simulated: Boolean): StartPlan = when {
        simulated -> StartPlan.AS_PREVIEWED
        age > REPLAN_AFTER -> StartPlan.REPLAN_IN_TRIP
        age > REFRESH_AFTER -> StartPlan.REFRESH_IN_TRIP
        moved != null && moved > MOVED_BEYOND -> StartPlan.REFRESH_IN_TRIP
        else -> StartPlan.AS_PREVIEWED
    }
}
