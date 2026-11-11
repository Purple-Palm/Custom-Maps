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
 * It holds, whatever the speed does, until the exit is no longer a lane choice ahead: past the
 * gore, on a new route, off the route. Each of these records when it ended.
 */
class RunUpLatch(private val tuning: CameraTuning) {

    /** The key of the exit the run-up is held for; null outside a run-up. */
    var exitKey: String? = null
        private set

    /**
     * What began the run-up, for the log: the key of the lane choice whose window did, [EDGE] when
     * a point on the way ahead did, [RESUME] when it began again; null outside a run-up.
     */
    var startKey: String? = null
        private set

    /** When the last run-up ended; minus infinity when none did, or when it was forgotten. */
    private var endedAt = Double.NEGATIVE_INFINITY

    /**
     * One progress update.
     *
     * @param choices the points with lanes ahead of the car, as the map engine reads them
     * @param speedMps the car's speed; unknown counts as standing
     * @param onRoute false while the navigator has the car off the route
     * @return the exit the run-up is held for; null outside a run-up
     */
    fun onProgress(nowSeconds: Double, choices: List<LaneChoice>, speedMps: Double?, onRoute: Boolean = true): LaneChoice? {
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
        if (exit == null) {
            end(nowSeconds)
            return null
        }
        if (exit.key == exitKey) return exit
        // Another exit than the one held for: on a new route, or the next step's.
        if (exitKey != null) end(nowSeconds)
        if (exit.metersAhead > reachMeters(tuning, speedMps)) return null
        val window = LaneFocus.windowMeters(tuning, speedMps)
        var edgeAhead = false
        var nearest: LaneChoice? = null
        for (i in choices.indices) {
            val choice = choices[i]
            if (!choice.isAhead) continue
            if (!choice.atManeuver && choice.edgeLeaves) edgeAhead = true
            val opens = choice.metersAhead <= window && (choice.counts(tuning) || choice.edgeLeaves)
            if (opens && (nearest == null || choice.metersAhead < nearest.metersAhead)) nearest = choice
        }
        val start = when {
            nowSeconds - endedAt < tuning.runUpResumeSeconds -> RESUME
            edgeAhead -> EDGE
            nearest != null -> nearest.key
            else -> return null
        }
        exitKey = exit.key
        startKey = start
        return exit
    }

    /**
     * A seek ([seek]) ends the run-up now, and it may begin again within
     * [CameraTuning.runUpResumeSeconds] on its reach alone. A new drive or the end of guidance
     * forgets it.
     */
    fun reset(nowSeconds: Double, seek: Boolean = false) {
        if (seek) {
            end(nowSeconds)
        } else {
            exitKey = null
            startKey = null
            endedAt = Double.NEGATIVE_INFINITY
        }
    }

    private fun end(nowSeconds: Double) {
        if (exitKey != null) endedAt = nowSeconds
        exitKey = null
        startKey = null
    }

    companion object {
        /** [startKey] of a run-up begun by a point on the way where the lane at the exit's side leaves. */
        const val EDGE = "edge"

        /** [startKey] of a run-up begun again soon after one ended. */
        const val RESUME = "resume"

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
