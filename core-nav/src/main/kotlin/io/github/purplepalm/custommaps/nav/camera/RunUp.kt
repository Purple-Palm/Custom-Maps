package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.max

/**
 * The run-up to a freeway exit or fork: the last part of the step before it, over which the close
 * view comes in once and stays to the gore, instead of going in and out for each lane choice on the
 * way. One latch serves the card and every camera, so they cannot disagree: the map engine holds
 * it, moves it once per progress update, and hands its [exitKey] to each camera's [LaneFocus].
 *
 * A run-up begins at the first update where the step's exit or fork counts as a lane choice, lies
 * ahead no farther than [reachMeters], and one of these holds:
 * - a point on the way ahead has the lane at the exit's side leave the route
 *   ([LaneChoice.edgeLeaves]): an earlier exit, a split or a lane that ends on that side;
 * - a point within the close view's window ([LaneFocus.windowMeters]) counts as a lane choice or
 *   is such a point; the exit itself counts, so at the latest a run-up begins at its own window;
 * - a run-up ended less than [CameraTuning.runUpResumeSeconds] ago: a new route that keeps an exit
 *   in reach, a moment off the route, a seek, or the gore of the run-up before, as at an exit right
 *   after a fork.
 *
 * It begins no later than the lane plan's seam for the exit: the plan names a lane from its first
 * narrowing for a point of the run-up, a few seconds of driving ahead (the engine hands its distance
 * before the gore with every update), and the card, the map and both cameras go into the run-up with
 * it, also where that lies beyond the reach ([SEAM] as [startKey]). A new route's plan is made after
 * the route, so on a new route within [CameraTuning.runUpResumeSeconds] of a run-up's end the exit may
 * also lie as far as the seam that run-up began with: the run-up goes on and does not wait for the plan.
 *
 * It holds, whatever the speed does, until the exit is no longer a lane choice ahead: past the
 * gore, on a new route, off the route. Each of these records when it ended.
 *
 * Past the gore it holds on through the ramp ([rampAhead]) where the lane plan constrains the next
 * point there, a fork or a split with lane data or a ramp step's maneuver the plan names a lane for,
 * within [CameraTuning.rampBridgeSeconds] of driving at the ramp's planning speed: the engine hands
 * that point's station after the gore with every update. It holds until the car is at that point,
 * and ends before it on a new route, off the route and at a seek, which record when it ended as
 * above, and where an exit or fork that counts lies ahead within reach, whose run-up goes on at once.
 */
class RunUpLatch(private val tuning: CameraTuning) {

    /** The key of the exit the run-up is held for; null outside a run-up. */
    var exitKey: String? = null
        private set

    /**
     * What began the run-up, for the log: the key of the lane choice whose window did, [EDGE] when
     * a point on the way ahead did, [RESUME] when it began again, [SEAM] when the lane plan's seam
     * did; null outside a run-up.
     */
    var startKey: String? = null
        private set

    /**
     * On the ramp after the gore, while the latch holds through it: how far the point it holds to
     * still lies ahead of the car, in metres; not a number elsewhere. The exit it holds for, [exitKey],
     * is the one whose gore the car has passed.
     */
    var rampAhead = Double.NaN
        private set

    /** When the last run-up ended; minus infinity when none did, or when it was forgotten. */
    private var endedAt = Double.NEGATIVE_INFINITY

    /** Where along the route the gore of the exit held lies, from the last update it lay ahead; not a number outside a run-up. */
    private var goreAt = Double.NaN

    /** Where along the route the point lies that the latch holds to through the ramp; not a number when it holds through none. */
    private var rampTo = Double.NaN

    /** How far along the route the car was at the last update: a new route counts from its own start. */
    private var lastRouteMeters = Double.NaN

    /** The seam the run-up held now began with, in metres before its gore; not a number where it had none. */
    private var seamHeld = Double.NaN

    /** The seam of the run-up that ended last, which a new route may go on with; not a number where it had none. */
    private var seamEnded = Double.NaN

    /**
     * One progress update.
     *
     * @param choices the points with lanes ahead of the car, as the map engine reads them
     * @param speedMps the car's speed; unknown counts as standing
     * @param onRoute false while the navigator has the car off the route
     * @param routeMeters how far the car is along the current route; not a number where it is not
     *   known, where the latch holds through no ramp
     * @param rampMeters the next point the lane plan constrains after the gore of the exit held now
     *   ([exitKey]): its station after the gore, in metres; not a number where the plan has none
     * @param rampMps the planning speed of the ramp that leaves that gore, at which
     *   [CameraTuning.rampBridgeSeconds] are driven
     * @param seamMeters where the lane plan begins the run-up to the step's exit (its seam), in
     *   metres before the exit's gore; not a number where the plan has none
     * @return the exit the run-up is held for while it lies ahead; null outside a run-up and on its
     *   ramp
     */
    fun onProgress(
        nowSeconds: Double,
        choices: List<LaneChoice>,
        speedMps: Double?,
        onRoute: Boolean = true,
        routeMeters: Double = Double.NaN,
        rampMeters: Double = Double.NaN,
        rampMps: Double = Double.NaN,
        seamMeters: Double = Double.NaN,
    ): LaneChoice? {
        // A new route counts its metres from its own start: a ramp held on the route before says
        // nothing of it.
        val newRoute = routeMeters < lastRouteMeters - ROUTE_RESTART_METERS
        lastRouteMeters = routeMeters
        if (!onRoute) {
            end(nowSeconds)
            return null
        }
        var exit: LaneChoice? = null
        for (i in choices.indices) {
            val choice = choices[i]
            if (choice.atManeuver && choice.isAhead && choice.counts(tuning)) {
                exit = choice
                break
            }
        }
        if (!rampTo.isNaN()) {
            // On the ramp: held until the car is at the point the plan constrains, unless an exit or
            // fork that counts lies ahead within reach, whose own run-up goes on at once.
            if (!newRoute && routeMeters < rampTo && (exit == null || exit.metersAhead > reachMeters(tuning, speedMps))) {
                rampAhead = rampTo - routeMeters
                return null
            }
            end(nowSeconds)
        }
        if (exit == null) {
            // Just past the gore: on through the ramp where the plan constrains a point near enough.
            if (exitKey != null && !newRoute && holdsRamp(routeMeters, rampMeters, rampMps)) {
                rampTo = goreAt + rampMeters
                rampAhead = rampTo - routeMeters
                return null
            }
            end(nowSeconds)
            return null
        }
        if (exit.key == exitKey) {
            goreAt = routeMeters + exit.metersAhead
            return exit
        }
        // Another exit than the one held for: on a new route, or the next step's.
        if (exitKey != null) end(nowSeconds)
        // The lane plan names a lane from its seam on, so the run-up is on there whatever else holds, also beyond the reach.
        val atSeam = exit.metersAhead <= seamMeters
        val inReach = exit.metersAhead <= reachMeters(tuning, speedMps)
        // A new route's plan comes after the route: meanwhile the seam of the run-up that just ended keeps it on.
        val carried = if (newRoute) seamEnded else Double.NaN
        val resumes = nowSeconds - endedAt < tuning.runUpResumeSeconds && (inReach || exit.metersAhead <= carried)
        if (!inReach && !atSeam && !resumes) return null
        val window = LaneFocus.windowMeters(tuning, speedMps)
        var edgeAhead = false
        var nearest: LaneChoice? = null
        for (i in choices.indices) {
            val choice = choices[i]
            if (!inReach || !choice.isAhead) continue
            if (!choice.atManeuver && choice.edgeLeaves) edgeAhead = true
            val opens = choice.metersAhead <= window && (choice.counts(tuning) || choice.edgeLeaves)
            if (opens && (nearest == null || choice.metersAhead < nearest.metersAhead)) nearest = choice
        }
        val start = when {
            resumes -> RESUME
            edgeAhead -> EDGE
            nearest != null -> nearest.key
            atSeam -> SEAM
            else -> return null
        }
        exitKey = exit.key
        startKey = start
        seamHeld = if (seamMeters.isNaN()) carried else seamMeters
        goreAt = routeMeters + exit.metersAhead
        return exit
    }

    /**
     * Whether the point [rampMeters] past the gore of the exit held lies within
     * [CameraTuning.rampBridgeSeconds] of driving at [rampMps], and still ahead of a car
     * [routeMeters] along the route.
     */
    private fun holdsRamp(routeMeters: Double, rampMeters: Double, rampMps: Double): Boolean =
        rampMps > 0.0 && rampMeters >= 0.0 && rampMeters <= tuning.rampBridgeSeconds * rampMps && routeMeters < goreAt + rampMeters

    /**
     * A seek ([seek]) ends the run-up now, also on its ramp, and it may begin again within
     * [CameraTuning.runUpResumeSeconds] on its reach alone. A new drive or the end of guidance
     * forgets it.
     */
    fun reset(nowSeconds: Double, seek: Boolean = false) {
        end(nowSeconds)
        if (!seek) {
            endedAt = Double.NEGATIVE_INFINITY
            seamEnded = Double.NaN
        }
        // The car is somewhere else now: its metres along the route start anew.
        lastRouteMeters = Double.NaN
    }

    private fun end(nowSeconds: Double) {
        if (exitKey != null) {
            endedAt = nowSeconds
            seamEnded = seamHeld
        }
        exitKey = null
        startKey = null
        seamHeld = Double.NaN
        goreAt = Double.NaN
        rampTo = Double.NaN
        rampAhead = Double.NaN
    }

    companion object {
        /** [startKey] of a run-up begun by a point on the way where the lane at the exit's side leaves. */
        const val EDGE = "edge"

        /** [startKey] of a run-up begun again soon after one ended. */
        const val RESUME = "resume"

        /** [startKey] of a run-up begun by the lane plan's seam: its first narrowing for a point of the run-up, a little ahead. */
        const val SEAM = "seam"

        /** The matched position can slip back by less than this; a new route starts far below the old one. */
        private const val ROUTE_RESTART_METERS = 1.0

        /**
         * How far before the exit a run-up can begin: the close view's window plus
         * [CameraTuning.runUpSeconds] of driving, at least [CameraTuning.runUpMinMeters].
         */
        fun reachMeters(tuning: CameraTuning, speedMps: Double?): Double =
            LaneFocus.windowMeters(tuning, speedMps) + max(knownSpeed(speedMps) * tuning.runUpSeconds, tuning.runUpMinMeters)

        /** A speed that is missing, negative or not a number counts as standing. */
        private fun knownSpeed(speedMps: Double?): Double = if (speedMps != null && speedMps > 0.0) speedMps else 0.0
    }
}

/**
 * The maneuver that ends the step the car is on, where the camera frames it as a junction: a
 * maneuver the camera's own rule counts, as a turn at the end of an exit's ramp. A camera makes one
 * per progress update; [LaneFocus] keeps its close view for a lane choice until this framing
 * begins, when it comes right after the choice.
 */
data class TurnAhead(
    /** The route, leg and step, as the progress snapshot keys it. */
    val stepKey: String,
    /** Along the route, from the car to where the camera begins to frame the maneuver: the meters to it less the framing distance. */
    val metersToFraming: Double,
)
