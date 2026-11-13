package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver

/**
 * The maneuvers of the current leg after the next one ([upcomingManeuvers]), built once for each step
 * of a drive and handed out as the same list for every progress update of that step, so a screen
 * that compares by identity builds its rows once a step. The step is the route, the leg and the step
 * of the snapshot's `stepKey`: a new step or a new route builds the list again. A route refresh keeps
 * the route and so the list, whose times are then those from before it until the next step; its
 * maneuvers and distances do not change with one. Not thread-safe: the updates of a drive arrive on
 * one thread.
 */
internal class UpcomingManeuvers {

    private var key: String? = null
    private var list: List<FollowingManeuver> = emptyList()

    /**
     * The maneuvers after the next one while the car is on step [stepIndex] of leg [legIndex] of the
     * route [routeId], whose legs are [legs]: only that leg's, through its arrival.
     */
    fun of(routeId: String, legs: List<RouteLeg>, legIndex: Int, stepIndex: Int): List<FollowingManeuver> {
        val stepKey = "$routeId/$legIndex/$stepIndex"
        if (stepKey != key) {
            list = upcomingManeuvers(legs.getOrNull(legIndex)?.steps().orEmpty(), stepIndex)
            key = stepKey
        }
        return list
    }

    /** A new drive knows nothing of the last one. */
    fun reset() {
        key = null
        list = emptyList()
    }
}

/**
 * The maneuvers after the next one at this moment of the drive, from the leg the progress is on, as
 * the navigator takes it from its route; empty before the progress has a leg and a step.
 */
internal fun UpcomingManeuvers.of(progress: RouteProgress): List<FollowingManeuver> {
    val leg = progress.currentLegProgress ?: return emptyList()
    val step = leg.currentStepProgress ?: return emptyList()
    return of(progress.navigationRoute.id, progress.navigationRoute.directionsRoute.legs().orEmpty(), leg.legIndex, step.stepIndex)
}
