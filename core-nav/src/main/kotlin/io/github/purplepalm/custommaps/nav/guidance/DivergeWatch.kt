package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.debug.Motion

/**
 * Whether the car went the route's way where the road divides, or left the route there: one line
 * of the debug log for each such place passed, [MISSED_MANEUVER] or [DIVERGE_TAKEN]. Log only:
 * nothing here acts on the drive. After a few drives the lines tell how far the phone's own
 * positions stray at a ramp that was taken, and how long the navigator took to find a car that was
 * not on it.
 *
 * A watch starts at a diverge:
 * - [KIND_MANEUVER]: the step changes past an exit, a fork or an on ramp, the new step's own
 *   maneuver;
 * - [KIND_EDGE]: the car passes a point where the route goes straight on and the lane at the side
 *   the step's exit leaves on leaves the route ([LaneChoice.edgeLeaves]);
 * - [KIND_CHOICE]: the car passes a lane choice where the route goes straight on, one the close
 *   view counts;
 * - [KIND_BRANCH]: the car passes a road without lane data that leaves on the exit's side.
 *
 * It ends with [MISSED_MANEUVER] when the navigator finds the car off the route before
 * [WATCH_SECONDS] or [WATCH_METERS] along the route have passed since the diverge, and with
 * [DIVERGE_TAKEN] at the first update after either. A newer diverge ends the open watch without a
 * line, and so do a new route, the end of guidance and a seek ([reset]).
 *
 * The engine feeds it every update of the route progress that it does not hold back, and the
 * navigator's reports of a car off the route, on one thread. Pure, and nothing in it can fail.
 */
class DivergeWatch(private val tuning: CameraTuning = CameraTuning.PHONE) {

    /**
     * One update of the route progress, in plain values.
     *
     * @param stepKey the route, leg and step, as the lane choices key them
     * @param stepType the type of the current step's own maneuver, the one the car passed into it,
     *   as the route names it: "off ramp", "fork", "on ramp" and the like
     * @param endsOnFreeway the road that leads to the step's end is a freeway; null when not known
     * @param metersDone along the route, from its start
     * @param uncertain the navigator says it may be about to find the car off the route
     * @param points the points with lanes ahead on the step, the step's exit included, as the lane
     *   choices give them
     * @param branches the roads without lane data ahead on the step that leave on the exit's side
     * @param probability how sure the navigator is of the road it matched the car to
     * @param sideM how far the phone's own position lay to the right of the matched one ("raw")
     * @param card where the card's lane row comes from now, as the "lane_row" line names it
     * @param words the lanes to use beside it in words, as that line writes them
     * @param runUp the exit of the run-up on now
     */
    data class Update(
        val nowSeconds: Double,
        val routeId: String,
        val stepKey: String,
        val stepType: String?,
        val stepModifier: String?,
        val endsOnFreeway: Boolean?,
        val metersDone: Double,
        val offRoute: Boolean,
        val uncertain: Boolean,
        val points: List<LaneChoice>,
        val branches: List<Branch>,
        val probability: Double?,
        val sideM: Double?,
        val speedMps: Double?,
        val teleport: Boolean?,
        val card: String?,
        val words: String?,
        val runUp: String?,
    )

    /** A road without lane data that leaves the step on the exit's side, [metersAhead] of the car. */
    data class Branch(val key: String, val metersAhead: Double)

    /** A line to write: its [type], [MISSED_MANEUVER] or [DIVERGE_TAKEN], and its fields. */
    data class Line(val type: String, val fields: Map<String, Any?>)

    /** The update before, to tell what was passed since. */
    private var last: Update? = null

    private var watch: Watch? = null

    /** One update of the route progress; the line that closes a watch with it, or null. */
    fun onProgress(update: Update): Line? {
        val before = last
        last = update
        if (before == null) return null
        // A new route: after a reroute what was watched is over, and nothing can be told of it.
        if (update.routeId != before.routeId) {
            watch = null
            return null
        }
        var line: Line? = null
        val open = watch
        if (open != null) {
            open.add(update.nowSeconds, update.metersDone, update.probability, update.sideM, update.speedMps, update.uncertain)
            if (update.offRoute) {
                watch = null
                return open.close(offRoute = true, teleport = update.teleport)
            }
            if (open.over()) {
                watch = null
                line = open.close(offRoute = false, teleport = null)
            }
        }
        // Off the route there is nothing ahead to pass, and no step to change to.
        if (update.offRoute || before.offRoute) return line
        if (update.stepKey != before.stepKey) {
            if (update.stepType in DIVERGE_TYPES) {
                val exit = before.points.firstOrNull { it.atManeuver }
                watch = Watch(
                    kind = KIND_MANEUVER,
                    type = update.stepType,
                    modifier = update.stepModifier,
                    key = "${before.stepKey}/end",
                    freeway = before.endsOnFreeway,
                    lanes = exit?.laneCount,
                    usable = exit?.usableLanes,
                    before = before,
                    at = update,
                ).also { it.advanced = true }
            } else {
                // Onto the next step on the way: the watch goes on, and says so.
                watch?.advanced = true
            }
            return line
        }
        startAtPassed(before, update)
        return line
    }

    /**
     * The navigator found the car off the route: a watch still open ends as a miss, one past its
     * limits as taken. The values are the latest the engine has: no update came with this report.
     */
    fun onOffRoute(nowSeconds: Double, probability: Double?, sideM: Double?, speedMps: Double?, teleport: Boolean?): Line? {
        val open = watch ?: return null
        watch = null
        open.add(nowSeconds, metersDone = null, probability, sideM, speedMps, uncertain = false)
        return open.close(offRoute = true, teleport = teleport)
    }

    /** A new route was set by hand, guidance ended or a seek moved the car: nothing goes on. */
    fun reset() {
        last = null
        watch = null
    }

    /**
     * Starts a watch at each point and road off passed since [before], in the order the car passed
     * them: the last one passed is the one watched.
     */
    private fun startAtPassed(before: Update, now: Update) {
        var passed: ArrayList<Passed>? = null
        for (point in before.points) {
            if (point.atManeuver || !point.isAhead || now.points.any { it.key == point.key }) continue
            val kind = when {
                point.edgeLeaves -> KIND_EDGE
                point.counts(tuning) -> KIND_CHOICE
                else -> continue
            }
            val list = passed ?: ArrayList<Passed>(1).also { passed = it }
            list.add(Passed(kind, point.key, point.metersAhead, point.onFreeway, point.laneCount, point.usableLanes))
        }
        for (branch in before.branches) {
            if (!(branch.metersAhead >= 0.0) || now.branches.any { it.key == branch.key }) continue
            val list = passed ?: ArrayList<Passed>(1).also { passed = it }
            list.add(Passed(KIND_BRANCH, branch.key, branch.metersAhead, freeway = true, lanes = null, usable = null))
        }
        val inOrder = passed?.sortedBy { it.metersAhead } ?: return
        for (point in inOrder) {
            watch = Watch(
                kind = point.kind,
                type = null,
                modifier = null,
                key = point.key,
                freeway = point.freeway,
                lanes = point.lanes,
                usable = point.usable,
                before = before,
                at = now,
            )
        }
    }

    /** A point or road off the car has passed since the update before. */
    private class Passed(
        val kind: String,
        val key: String,
        val metersAhead: Double,
        val freeway: Boolean?,
        val lanes: Int?,
        val usable: Int?,
    )

    /**
     * One diverge watched: what it was and what the card showed on the way to it, taken from the
     * update [before] it, and how the navigator's numbers went from the update [at] it on.
     */
    private class Watch(
        val kind: String,
        val type: String?,
        val modifier: String?,
        val key: String,
        val freeway: Boolean?,
        val lanes: Int?,
        val usable: Int?,
        before: Update,
        at: Update,
    ) {
        private val card = before.card
        private val words = before.words
        private val runUp = before.runUp
        private val startSeconds = at.nowSeconds
        private val startMeters = at.metersDone
        private val probAt = at.probability
        private val sideAt = at.sideM
        private val speedAt = at.speedMps

        /** The step changed at the diverge or since, while it was watched. */
        var advanced = false

        private var lastSeconds = at.nowSeconds
        private var lastMeters = at.metersDone
        private var lastUncertain = at.uncertain
        private var uncertainSeconds = 0.0
        private var probMin = at.probability
        private var probMax = at.probability
        private var probLast = at.probability
        private var sideMin = at.sideM
        private var sideMax = at.sideM
        private var speedEnd = at.speedMps

        /** One more moment of the watch; [metersDone] null when the moment has none, as a report of the car off the route. */
        fun add(nowSeconds: Double, metersDone: Double?, probability: Double?, sideM: Double?, speedMps: Double?, uncertain: Boolean) {
            // The navigator was unsure from the moment it said so to the next.
            if (lastUncertain && nowSeconds > lastSeconds) uncertainSeconds += nowSeconds - lastSeconds
            lastUncertain = uncertain
            lastSeconds = nowSeconds
            if (metersDone != null) lastMeters = metersDone
            if (probability != null) {
                probMin = smaller(probMin, probability)
                probMax = larger(probMax, probability)
                probLast = probability
            }
            if (sideM != null) {
                sideMin = smaller(sideMin, sideM)
                sideMax = larger(sideMax, sideM)
            }
            if (speedMps != null) speedEnd = speedMps
        }

        /** Whether the time or the way since the diverge has reached its limit: the diverge was taken. */
        fun over(): Boolean = lastSeconds - startSeconds >= WATCH_SECONDS || lastMeters - startMeters >= WATCH_METERS

        /**
         * The line that ends the watch: a miss when the car is [offRoute] within the limits, with
         * whether the navigator's position jumped then ([teleport]); taken otherwise.
         */
        fun close(offRoute: Boolean, teleport: Boolean?): Line {
            val missed = offRoute && !over()
            val fields = mapOf(
                "kind" to kind,
                // Not "type": that is the line's own, and the log would write this one as "field_type".
                "maneuverType" to type,
                "modifier" to modifier,
                "key" to key,
                "freeway" to freeway,
                "lanes" to lanes,
                "usable" to usable,
                "card" to card,
                "words" to words,
                "runUp" to runUp,
                "advanced" to advanced,
                "sinceS" to round(lastSeconds - startSeconds, 1),
                "sinceM" to (lastMeters - startMeters).takeIf { it.isFinite() }?.let { Math.round(it) },
                "probAt" to probAt?.let { round(it, 2) },
                "probMin" to probMin?.let { round(it, 2) },
                "probMax" to probMax?.let { round(it, 2) },
                "probLast" to probLast?.let { round(it, 2) },
                "uncertainS" to round(uncertainSeconds, 1),
                "sideAt" to sideAt?.let { round(it, 1) },
                "sideMin" to sideMin?.let { round(it, 1) },
                "sideMax" to sideMax?.let { round(it, 1) },
                "speedAt" to speedAt?.let { round(it, 1) },
                "speedEnd" to speedEnd?.let { round(it, 1) },
                "teleportAtOff" to teleport.takeIf { missed },
            ).filterValues { it != null }
            return Line(if (missed) MISSED_MANEUVER else DIVERGE_TAKEN, fields)
        }

        private fun round(value: Double, decimals: Int): Double? = Motion.round(value, decimals).takeIf { it.isFinite() }

        private fun smaller(a: Double?, b: Double): Double = if (a == null || b < a) b else a

        private fun larger(a: Double?, b: Double): Double = if (a == null || b > a) b else a
    }

    companion object {
        /** How long after a diverge a report of the car off the route counts as having missed it. */
        const val WATCH_SECONDS = 30.0

        /** How far along the route after a diverge a report of the car off the route counts as having missed it. */
        const val WATCH_METERS = 1_000.0

        /** The line of a diverge the car left the route at. */
        const val MISSED_MANEUVER = "missed_maneuver"

        /** The line of a diverge the car went the route's way at. */
        const val DIVERGE_TAKEN = "diverge_taken"

        /** A step change past an exit, a fork or an on ramp. */
        const val KIND_MANEUVER = "maneuver"

        /** A lane choice passed where the route goes straight on. */
        const val KIND_CHOICE = "choice"

        /** A point passed where the lane at the exit's side leaves the route. */
        const val KIND_EDGE = "edge"

        /** A road without lane data passed that leaves on the exit's side. */
        const val KIND_BRANCH = "branch"

        /** The maneuvers where the road divides, as the route names them. */
        private val DIVERGE_TYPES = setOf("off ramp", "fork", "on ramp")
    }
}
