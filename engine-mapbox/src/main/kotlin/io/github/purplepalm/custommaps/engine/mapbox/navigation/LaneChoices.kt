package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.camera.Polyline
import io.github.purplepalm.custommaps.nav.camera.RunUpLatch
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.guidance.DivergeWatch
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.RunUpLanes

/**
 * The points with lanes ahead of the car, read from the lane data the route carries for its
 * intersections. No request and no other service is involved.
 *
 * The maneuver card shows the lanes of the next maneuver only. An intersection the route goes
 * straight through has lanes of its own, for example where a freeway splits or where two lanes
 * turn into an exit; those are read here, and so is the next maneuver where it is an exit or a
 * fork. Whether a point is worth a close view is [LaneChoice.skipReason]'s decision: every point
 * with lanes is handed out, so the ones passed over can be logged with their lane counts
 * ([entering]). The maneuver card gets the lanes of a lane choice ahead from here as well
 * ([lanesAhead]).
 *
 * On a freeway the last part of a step before its exit or fork is a run-up ([RunUpLatch]): the
 * close view comes in once for it and stays to the gore, and the card shows one lane row for it
 * ([RunUpRule]). The one latch for the card and every camera is held here and moved once per
 * update of the route progress ([runUp]); the cameras get the exit it holds for ([runUpKey]).
 *
 * The engine owns one. The lanes of a step are read once, when the car enters it; an update of the
 * route progress only measures how far ahead each point is. Called on the main thread.
 */
internal class LaneChoices(
    private val tuning: CameraTuning = CameraTuning.PHONE,
    private val onError: (RuntimeException) -> Unit = {},
) {

    /** One intersection of the current step that has lanes; [lanes] is how the card draws them. */
    private class LanePoint(
        val key: String,
        val metersFromStepStart: Double,
        val counts: LaneCounts,
        val onFreeway: Boolean,
        val lanes: LaneInfo?,
        /** Every lane as the route lists it, from the leftmost, for the run-up's row. */
        val routeLanes: List<RouteLane>,
        /** The lane at the side the step's exit leaves on leaves the route here ([RunUpRule.edgeLeaves]). */
        val edgeLeaves: Boolean,
        /** Every lane as the route lists it, from the leftmost, as the "lane_choice" line writes it ([fromLeftFields]). */
        val fromLeft: List<Map<String, Any?>>,
    )

    private var stepKey: String? = null
    private var points: List<LanePoint> = emptyList()

    /** The next maneuver's own lanes; whether it is a lane choice depends on the kind of maneuver. */
    private var maneuverPoint: LanePoint? = null

    /** The side the step's exit or fork leaves on ([RunUpRule.exitEdge]); null when it cannot be told. */
    private var exitEdge: LaneEdge? = null

    /** Where roads without lane data leave the step on the exit's side, in the order the car reaches them. */
    private var branches: List<SideBranch> = emptyList()

    /** The run-up on now, one for the card and every camera. */
    private val latch = RunUpLatch(tuning)

    /** The update the latch was moved for last, and the card's row it gave: the same object while the row is. */
    private var runUpUpdate: Any? = null
    private var runUpRow: RunUpLanes? = null

    /** Whether the run-up's row could not be made on this step; reported once. */
    private var rowFailed = false

    private var lastProgress: RouteProgress? = null
    private var lastAhead: List<LaneChoice> = emptyList()

    /** The choice whose lanes the card shows, and what was handed out for it at the last update. */
    private var cardKey: String? = null
    private var cardLanes: LanesAhead? = null

    /** The points of the current step that [entering] has handed out. */
    private val reported = HashSet<String>()

    /**
     * The points with lanes ahead on the current step, nearest first, and the next maneuver where
     * the view stays up for it. Every observer of one progress update gets the same list.
     */
    fun ahead(progress: RouteProgress): List<LaneChoice> {
        if (progress !== lastProgress) {
            lastProgress = progress
            lastAhead = read(progress)
        }
        return lastAhead
    }

    private fun read(progress: RouteProgress): List<LaneChoice> {
        val leg = progress.currentLegProgress ?: return emptyList()
        val stepProgress = leg.currentStepProgress ?: return emptyList()
        return update(
            offRoute = progress.currentState == RouteProgressState.OFF_ROUTE,
            stepKey = "${progress.navigationRoute.id}/${leg.legIndex}/${stepProgress.stepIndex}",
            metersOnStep = stepProgress.distanceTraveled.toDouble(),
            metersToManeuver = stepProgress.distanceRemaining.toDouble(),
            maneuverType = progress.bannerInstructions?.primary()?.type(),
        ) { key ->
            load(
                stepKey = key,
                intersections = stepProgress.step?.intersections().orEmpty(),
                stepPoints = stepProgress.stepPoints.orEmpty(),
                maneuver = leg.upcomingStep?.intersections()?.firstOrNull(),
                exitModifier = leg.upcomingStep?.maneuver()?.modifier(),
            )
        }
    }

    /**
     * One update of the route progress, in plain values: the points ahead of the car, nearest
     * first. The lanes of a step are read when the car enters it, through [loadStep], and not
     * again while it stays on that step. Off the route there are no points: the route's lanes are
     * not the car's then, and its distances are stale until a new route comes. The step stays as
     * it was read meanwhile.
     *
     * @param stepKey the route, leg and step, as in the progress snapshot
     * @param maneuverType the type of the maneuver that ends the step, as the route's banner names it
     * @param loadStep reads the lanes of the step with the given key, with [load]
     */
    fun update(
        offRoute: Boolean,
        stepKey: String,
        metersOnStep: Double,
        metersToManeuver: Double,
        maneuverType: String?,
        loadStep: (String) -> Unit,
    ): List<LaneChoice> {
        if (offRoute) return emptyList()
        if (stepKey != this.stepKey) loadStep(stepKey)
        return choices(metersOnStep, metersToManeuver, maneuverIsChoice = isLaneChoiceManeuver(maneuverType))
    }

    /**
     * Reads the lanes of a step: of each intersection after its first, which is the maneuver the
     * step began with, and of [maneuver], the first intersection of the next step. With them the
     * side the step's exit leaves on, which points on the way have the lane at that side leave the
     * route, and where roads without lane data leave on that side. Lane data that cannot be read
     * leaves the step without lane choices; it never ends the drive.
     *
     * @param stepKey the route, leg and step, as in the progress snapshot
     * @param stepPoints the shape of the step; each intersection lies on one of its points
     * @param exitModifier the modifier of the next step's own maneuver, as the route's step has it
     *   (not the banner): the side its exit or fork leaves on
     */
    fun load(
        stepKey: String,
        intersections: List<StepIntersection?>,
        stepPoints: List<Point>,
        maneuver: StepIntersection?,
        exitModifier: String? = null,
    ) {
        this.stepKey = stepKey
        points = emptyList()
        maneuverPoint = null
        exitEdge = null
        branches = emptyList()
        rowFailed = false
        reported.clear()
        try {
            val exitLanes = maneuver?.routeLanes().orEmpty()
            val edge = RunUpRule.exitEdge(ManeuverMapping.modifier(exitModifier), exitLanes)
            val read = stepLanes(intersections, stepPoints).map {
                val intersection = intersections[it.index]
                val lanes = intersection?.routeLanes().orEmpty()
                LanePoint(
                    "$stepKey/${it.index}",
                    it.metersFromStepStart,
                    it.counts,
                    it.onFreeway,
                    intersection?.lanesAhead(),
                    lanes,
                    RunUpRule.edgeLeaves(lanes, edge),
                    intersection?.fromLeftFields().orEmpty(),
                )
            }
            val counts = maneuver?.laneCounts()
            // The road that leads to the maneuver leaves the step's last intersection. The card
            // has the maneuver's lanes already, so none are drawn for it here.
            val atManeuver = if (maneuver != null && counts != null) {
                val approach = intersections.lastOrNull() ?: maneuver
                LanePoint(
                    "$stepKey/end",
                    Double.NaN,
                    counts,
                    approach.isFreeway(),
                    lanes = null,
                    routeLanes = exitLanes,
                    edgeLeaves = false,
                    fromLeft = maneuver.fromLeftFields(),
                )
            } else {
                null
            }
            val sideBranches = if (edge == null) emptyList() else exitSideBranches(intersections, stepPoints, edge)
            // All of the step or nothing: a step read in part could give words a road without
            // lane data should have withheld.
            points = read
            maneuverPoint = atManeuver
            exitEdge = edge
            branches = sideBranches
        } catch (e: RuntimeException) {
            onError(e)
        }
    }

    /**
     * The points ahead of a car that is [metersOnStep] into the loaded step, nearest first.
     *
     * @param maneuverIsChoice the view stays up for the next maneuver, an exit or a fork, so its
     *   lanes are a lane choice; for a turn the view goes down onto the junction instead
     */
    fun choices(metersOnStep: Double, metersToManeuver: Double, maneuverIsChoice: Boolean): List<LaneChoice> {
        val atManeuver = maneuverPoint.takeIf { maneuverIsChoice && metersToManeuver >= 0.0 }
        if (points.isEmpty() && atManeuver == null) return emptyList()
        val ahead = ArrayList<LaneChoice>()
        for (i in points.indices) {
            val point = points[i]
            val metersAhead = point.metersFromStepStart - metersOnStep
            if (metersAhead >= 0.0) ahead.add(point.toChoice(metersAhead, atManeuver = false))
        }
        if (atManeuver != null) ahead.add(atManeuver.toChoice(metersToManeuver, atManeuver = true))
        return ahead
    }

    /**
     * The lanes the maneuver card shows ahead of the next maneuver: those of the nearest lane
     * choice among [choices] where the route goes straight on, with its distance. A choice is
     * shown from where the camera's close view starts for it ([LaneFocus.windowMeters]), in either
     * lane zoom, and then until the car has passed it, also when the car slows down and the window
     * gets shorter. Null on most of the road.
     *
     * The same object is handed out for as long as nothing in it changes, and its lanes are the
     * same object for the whole choice, so a screen can skip what did not change.
     *
     * @param choices the points ahead at this update, as [ahead] gave them
     * @param speedMps the car's speed, the one the camera gets
     */
    fun lanesAhead(choices: List<LaneChoice>, speedMps: Double?): LanesAhead? {
        // On a run-up the card shows the run-up's row instead, and nothing of a choice is kept.
        if (latch.exitKey != null) {
            cardKey = null
            cardLanes = null
            return null
        }
        val window = LaneFocus.windowMeters(tuning, speedMps)
        var nearest: LaneChoice? = null
        for (i in choices.indices) {
            val choice = choices[i]
            // The lanes of the maneuver itself are the card's own row.
            if (choice.atManeuver || !choice.counts(tuning) || !choice.isAhead) continue
            if (choice.metersAhead > window && choice.key != cardKey) continue
            if (nearest == null || choice.metersAhead < nearest.metersAhead) nearest = choice
        }
        val lanes = nearest?.let { shown -> points.firstOrNull { it.key == shown.key }?.lanes }
        if (nearest == null || lanes == null) {
            cardKey = null
            cardLanes = null
            return null
        }
        cardKey = nearest.key
        val distance = Distance.meters(nearest.metersAhead)
        val last = cardLanes
        if (last == null || last.lanes !== lanes || last.distance != distance) cardLanes = LanesAhead(distance, lanes)
        return cardLanes
    }

    /**
     * The run-up to the step's exit or fork at this update of the route progress, and the card's
     * row for it; null outside a run-up ([RunUpLatch], [RunUpRule.row]). The one latch for the card
     * and every camera moves at most once for each progress object, whoever asks first, as [ahead]
     * reads once: the engine's own observer and the map's cameras get the same answer. The row is
     * the same object for as long as it does not change.
     *
     * @param speedMps the car's speed, the one the card's lanes and the cameras get
     * @param nowSeconds the engine's clock, which a resume and a seek are timed on
     */
    fun runUp(progress: RouteProgress, speedMps: Double?, nowSeconds: Double): RunUpLanes? = runUpOnce(progress) {
        val metersOnStep = progress.currentLegProgress?.currentStepProgress?.distanceTraveled?.toDouble() ?: 0.0
        moveRunUp(ahead(progress), progress.currentState != RouteProgressState.OFF_ROUTE, metersOnStep, speedMps, nowSeconds)
    }

    /**
     * The card's row for [update], an update of the route progress: [move] moves the latch for the
     * first call with that object, and every later call with it gets the same answer.
     */
    internal fun runUpOnce(update: Any, move: () -> Unit): RunUpLanes? {
        if (update !== runUpUpdate) {
            runUpUpdate = update
            move()
        }
        return runUpRow
    }

    /**
     * One move of the latch, in plain values: [choices] as [ahead] gave them for a car
     * [metersOnStep] into the loaded step. The row it gives is also what [runUp] hands out until
     * the next move.
     *
     * @param onRoute false while the navigator has the car off the route
     */
    fun moveRunUp(choices: List<LaneChoice>, onRoute: Boolean, metersOnStep: Double, speedMps: Double?, nowSeconds: Double): RunUpLanes? {
        val exit = latch.onProgress(nowSeconds, choices, speedMps, onRoute)
        val row = if (exit == null) null else rowOf(exit.key, metersOnStep)
        // The object stays the same while the row does, so a screen skips it.
        if (row != runUpRow) runUpRow = row
        return runUpRow
    }

    /** The exit of the run-up on now ([RunUpLatch.exitKey]); null outside a run-up. */
    val runUpKey: String? get() = latch.exitKey

    /** The key of the lane choice whose lanes [lanesAhead] gave the card at its last call; null when it gave none. */
    val choiceKey: String? get() = cardKey

    /** What began the run-up on now ([RunUpLatch.startKey]): a choice's key, `edge` or `resume`; null outside a run-up. */
    val runUpStartKey: String? get() = latch.startKey

    /**
     * The roads without lane data still ahead of a car [metersOnStep] into the loaded step that
     * leave on the side of its exit, nearest first, keyed by their intersection as the points are,
     * for the debug log's watch of diverges ([DivergeWatch]). Empty on most steps.
     */
    fun branchesAhead(metersOnStep: Double): List<DivergeWatch.Branch> {
        if (branches.isEmpty()) return emptyList()
        val step = stepKey ?: return emptyList()
        val ahead = ArrayList<DivergeWatch.Branch>(branches.size)
        for (branch in branches) {
            val metersAhead = branch.metersFromStepStart - metersOnStep
            if (metersAhead >= 0.0) ahead.add(DivergeWatch.Branch("$step/${branch.index}", metersAhead))
        }
        return ahead
    }

    /**
     * The card's row on the run-up to the exit [exitKey], for a car [metersOnStep] into the step:
     * the next point on the way where the lane at the exit's side leaves, or the exit's own lanes.
     * Lane data that cannot be made into a row costs the row, reported once a step, and never the
     * drive.
     */
    private fun rowOf(exitKey: String, metersOnStep: Double): RunUpLanes? {
        val exit = maneuverPoint?.takeIf { it.key == exitKey } ?: return null
        if (rowFailed) return null
        return try {
            val ahead = ArrayList<RunUpRule.LanePoint>(points.size)
            for (point in points) {
                val metersAhead = point.metersFromStepStart - metersOnStep
                if (metersAhead >= 0.0) ahead.add(RunUpRule.LanePoint(point.key, metersAhead, point.routeLanes))
            }
            val branchAhead = branches.any { it.metersFromStepStart - metersOnStep >= 0.0 }
            RunUpRule.row(exitEdge, ahead, exit.key, exit.routeLanes, branchAhead)
        } catch (e: RuntimeException) {
            rowFailed = true
            onError(e)
            null
        }
    }

    /**
     * The points among [choices] that have come as near as the close view's start
     * ([LaneFocus.windowMeters]) since the last call, for the debug log. Each point is handed out
     * once: when the view would go close for it, or would if the rule that passes it over were
     * looser. Empty on most updates.
     *
     * @param choices the points ahead at this update, as [ahead] gave them
     * @param speedMps the car's speed, the one the camera gets
     */
    fun entering(choices: List<LaneChoice>, speedMps: Double?): List<LaneChoice> {
        if (choices.isEmpty()) return emptyList()
        val window = LaneFocus.windowMeters(tuning, speedMps)
        var entered: ArrayList<LaneChoice>? = null
        for (i in choices.indices) {
            val choice = choices[i]
            if (!choice.isAhead || choice.metersAhead > window || !reported.add(choice.key)) continue
            val list = entered ?: ArrayList<LaneChoice>(1).also { entered = it }
            list.add(choice)
        }
        return entered ?: emptyList()
    }

    /**
     * The "lane_choice" line of the debug log for [choice]: its key, how far ahead it is and how far
     * the close view's start, in meters, its lanes, the speed in meters a second, and why it is
     * passed over when it is. No coordinates. The lane counts are what a looser rule would be
     * judged by after a drive. Then whether the lane at the side the step's exit leaves on leaves
     * the route there, and every lane as the route lists it, from the leftmost ([fromLeftFields]),
     * which tells after a drive which lanes the route marked where.
     */
    fun debugFields(choice: LaneChoice, speedMps: Double?): Map<String, Any?> = mapOf(
        "kind" to if (choice.atManeuver) "maneuver" else "straight",
        "key" to choice.key,
        "metersAhead" to Math.round(choice.metersAhead),
        "window" to Math.round(LaneFocus.windowMeters(tuning, speedMps)),
        "lanes" to choice.laneCount,
        "usable" to choice.usableLanes,
        "leaving" to choice.leavingLanes,
        "designated" to choice.designatedLanes,
        "freeway" to choice.onFreeway,
        "speed" to speedMps?.let { Motion.round(it, 1) },
        "skipped" to choice.skipReason(tuning),
        "edgeLeaves" to choice.edgeLeaves,
        "fromLeft" to pointOf(choice.key)?.fromLeft,
    ).filterValues { it != null }

    /** The loaded point with [key]: one on the way, or the maneuver's own. */
    private fun pointOf(key: String): LanePoint? = points.firstOrNull { it.key == key } ?: maneuverPoint?.takeIf { it.key == key }

    /** A drive ended or a new one begins: nothing is kept of the last step, nor of a run-up. */
    fun clear() {
        forgetStep()
        // Forgotten: the time counts only for a seek.
        latch.reset(nowSeconds = 0.0)
    }

    /**
     * A seek of a simulated drive put the car somewhere else: nothing is kept of the step, as at
     * the end of a drive, but a run-up ends at [nowSeconds], so it may begin again within
     * [CameraTuning.runUpResumeSeconds] on its reach alone where the seek lands on the same approach.
     */
    fun onSeek(nowSeconds: Double) {
        forgetStep()
        latch.reset(nowSeconds, seek = true)
    }

    private fun forgetStep() {
        stepKey = null
        points = emptyList()
        maneuverPoint = null
        exitEdge = null
        branches = emptyList()
        rowFailed = false
        lastProgress = null
        lastAhead = emptyList()
        cardKey = null
        cardLanes = null
        runUpUpdate = null
        runUpRow = null
        reported.clear()
    }

    private fun LanePoint.toChoice(metersAhead: Double, atManeuver: Boolean) = LaneChoice(
        key = key,
        metersAhead = metersAhead,
        laneCount = counts.lanes,
        usableLanes = counts.usable,
        designatedLanes = counts.designated,
        onFreeway = onFreeway,
        atManeuver = atManeuver,
        edgeLeaves = edgeLeaves && !atManeuver,
    )

    companion object {
        /**
         * The maneuvers the camera stays up for, by the navigation SDK's own list, which the app
         * does not change. Where one of them has lanes, it is a lane choice at the maneuver. A
         * maneuver of an unknown kind is none.
         */
        private val VIEW_STAYS_UP_FOR = setOf(
            StepManeuver.CONTINUE,
            StepManeuver.MERGE,
            StepManeuver.ON_RAMP,
            StepManeuver.OFF_RAMP,
            StepManeuver.FORK,
        )

        /** Whether a maneuver of this [type], as the route's banner names it, is a lane choice where it has lanes. */
        fun isLaneChoiceManeuver(type: String?): Boolean = type in VIEW_STAYS_UP_FOR
    }
}

/**
 * The lanes of one intersection as the lane choice rule counts them. Lanes kept for buses, car
 * pools, taxis and the like are counted apart: they are no choice for this car.
 */
internal class LaneCounts(
    /** The lanes open to every car. */
    val lanes: Int,
    /** Of those, the lanes the route can be followed from. */
    val usable: Int,
    /** Of those, the lanes the route prefers. */
    val active: Int,
    /** The lanes kept for some vehicles only. */
    val designated: Int,
)

/** The lane counts of this intersection; null when the route has no lanes for it. */
internal fun StepIntersection.laneCounts(): LaneCounts? {
    val all = lanes() ?: return null
    var lanes = 0
    var usable = 0
    var active = 0
    var designated = 0
    for (lane in all) {
        if (lane == null) continue
        if (lane.isDesignated()) {
            designated++
            continue
        }
        lanes++
        if (lane.isUsable()) usable++
        if (lane.active() == true) active++
    }
    return if (lanes + designated == 0) null else LaneCounts(lanes, usable, active, designated)
}

/**
 * The lanes of an intersection the route goes straight through, as the maneuver card draws them:
 * every lane from the leftmost, the ones the route can be followed from lit, the ones that leave
 * the route and the designated ones dim. Null when the route has no lanes for it.
 */
internal fun StepIntersection.lanesAhead(): LaneInfo? {
    val all = lanes() ?: return null
    val openLaneUsable = all.any { it != null && !it.isDesignated() && it.isUsable() }
    val lanes = ArrayList<Lane>(all.size)
    for (lane in all) {
        if (lane == null) continue
        lanes.add(
            ManeuverMapping.laneAhead(
                lane.indications().orEmpty(),
                usable = lane.isUsable(),
                usedDirection = lane.validIndication(),
                designated = lane.isDesignated(),
                openLaneUsable = openLaneUsable,
            ),
        )
    }
    return if (lanes.isEmpty()) null else LaneInfo(lanes)
}

/**
 * Every lane of this intersection as the route lists it, from the leftmost, read as it is: its
 * arrows, whether the route can be followed from it and prefers it, and the kinds of vehicle it is
 * kept for ([designatedKinds]). Empty when the route has no lanes for it. Nothing in it throws on
 * odd lane data: a word the app does not know is a kind of its own, never an error.
 */
internal fun StepIntersection.routeLanes(): List<RouteLane> {
    val all = lanes() ?: return emptyList()
    val lanes = ArrayList<RouteLane>(all.size)
    for (lane in all) {
        if (lane == null) continue
        lanes.add(
            RouteLane(
                indications = lane.indications().orEmpty().mapNotNull(ManeuverMapping::modifier).toSet(),
                valid = lane.valid(),
                active = lane.active(),
                validIndication = ManeuverMapping.modifier(lane.validIndication()),
                kinds = lane.designatedKinds(),
            ),
        )
    }
    return lanes
}

/**
 * The kinds of vehicle a lane is kept for, as the route's lane access names them
 * ([DesignatedKind.of]: a name the app does not list, or none, is [DesignatedKind.OTHER]); empty for
 * a lane open to every car. A lane is kept for some vehicles exactly when this is not empty, as
 * [isDesignated] reads it for the card.
 */
internal fun IntersectionLanes.designatedKinds(): Set<DesignatedKind> =
    access()?.designated().orEmpty().mapTo(LinkedHashSet()) { DesignatedKind.of(it) }

/**
 * Every lane of this intersection as the route lists it, from the leftmost, for the debug log: its
 * arrows ("ind"), whether the route can be followed from it ("valid") and prefers it ("active"),
 * the arrow it takes from there ("validInd"), and, only where the lane has any, the vehicles it is
 * kept for ("des"). A field the route does not give is null. Arrows and kinds of vehicle only, as
 * the route names them. Empty when the route has no lanes for it.
 */
internal fun StepIntersection.fromLeftFields(): List<Map<String, Any?>> {
    val all = lanes() ?: return emptyList()
    val lanes = ArrayList<Map<String, Any?>>(all.size)
    for (lane in all) {
        if (lane == null) continue
        // Copied: the route may hand out a view of its own data.
        val designated = lane.access()?.designated()?.toList()
        val fields = mapOf(
            "ind" to lane.indications()?.toList(),
            "valid" to lane.valid(),
            "active" to lane.active(),
            "validInd" to lane.validIndication(),
        )
        lanes.add(if (designated.isNullOrEmpty()) fields else fields + ("des" to designated))
    }
    return lanes
}

/** A lane kept for buses, car pools, taxis and the like. */
private fun IntersectionLanes.isDesignated(): Boolean = !access()?.designated().isNullOrEmpty()

/** Where the route does not say which lanes it can be followed from, the preferred ones count. */
private fun IntersectionLanes.isUsable(): Boolean = valid() ?: active() ?: false

/** One intersection of a step that has lanes. */
internal class StepLanes(
    /** Which of the step's intersections it is, counted from 0. */
    val index: Int,
    /** Along the step's shape, from the start of the step. */
    val metersFromStepStart: Double,
    val counts: LaneCounts,
    /** The road that leads to it is a freeway. */
    val onFreeway: Boolean,
)

/**
 * The intersections of a step that have lanes, in the order the car reaches them. The first
 * intersection of a step is the maneuver the step began with and is left out. Each one lies on a
 * point of [stepPoints], the step's shape, which is how its distance is measured. A step with a
 * coordinate that is none has no result.
 */
internal fun stepLanes(intersections: List<StepIntersection?>, stepPoints: List<Point>): List<StepLanes> {
    val indices = ArrayList<Int>()
    val counts = ArrayList<LaneCounts>()
    for (i in 1 until intersections.size) {
        val laneCounts = intersections[i]?.laneCounts() ?: continue
        indices.add(i)
        counts.add(laneCounts)
    }
    if (indices.isEmpty()) return emptyList()
    val line = stepPoints.map { it.toGeoPoint() ?: return emptyList() }
    val places = indices.map { intersections[it]?.location()?.toGeoPoint() ?: return emptyList() }
    val meters = Polyline.distancesTo(line, places)
    return indices.mapIndexed { n, i ->
        // The road that leads to an intersection is the one that leaves the intersection before it.
        StepLanes(i, meters[n], counts[n], intersections[i - 1]?.isFreeway() == true)
    }
}

/** A road without lane data that leaves a step on the side of its exit ([exitSideBranches]). */
internal class SideBranch(
    /** Which of the step's intersections it leaves at, counted from 0. */
    val index: Int,
    /** Along the step's shape, from the start of the step. */
    val metersFromStepStart: Double,
)

/**
 * Where roads leave a step on the side of its exit, [edge], at intersections the route has no
 * lane data for: along the step from its start, in the order the car reaches them. Such a road
 * may take the lane at that side with it, which no lane data says, so the run-up's row has no
 * words until the car has passed it. Only intersections after the step's first, on a road that
 * leads to them as a freeway, count; a road that may not be entered, as an on ramp joining, is
 * none. A step with a coordinate that is none has none.
 */
internal fun exitSideBranches(intersections: List<StepIntersection?>, stepPoints: List<Point>, edge: LaneEdge): List<SideBranch> {
    val indices = ArrayList<Int>()
    val places = ArrayList<GeoPoint>()
    for (i in 1 until intersections.size) {
        val intersection = intersections[i] ?: continue
        if (intersection.laneCounts() != null || intersections[i - 1]?.isFreeway() != true) continue
        if (!intersection.branchesOff(edge)) continue
        indices.add(i)
        places.add(intersection.location().toGeoPoint() ?: return emptyList())
    }
    if (places.isEmpty()) return emptyList()
    val line = stepPoints.map { it.toGeoPoint() ?: return emptyList() }
    val meters = Polyline.distancesTo(line, places)
    return indices.mapIndexed { n, i -> SideBranch(i, meters[n]) }
}

/**
 * Whether a road that may be entered leaves this intersection on [edge]'s side of the route's way
 * out: its bearing lies less than half a turn clockwise of the way out for the right, and the
 * same counterclockwise for the left. The road the car comes in on is none.
 */
internal fun StepIntersection.branchesOff(edge: LaneEdge): Boolean {
    val bearings = bearings() ?: return false
    val entry = entry() ?: return false
    val out = out() ?: return false
    val into = `in`()
    val outBearing = bearings.getOrNull(out) ?: return false
    for (j in bearings.indices) {
        if (j == out || j == into || entry.getOrNull(j) != true) continue
        val bearing = bearings[j] ?: continue
        val turn = Math.floorMod(bearing - outBearing, FULL_TURN)
        val onSide = if (edge == LaneEdge.RIGHT) turn in 1 until HALF_TURN else turn in HALF_TURN + 1 until FULL_TURN
        if (onSide) return true
    }
    return false
}

private const val FULL_TURN = 360
private const val HALF_TURN = 180

/** A coordinate outside the globe is no place to measure from. */
internal fun Point.toGeoPoint(): GeoPoint? {
    val latitude = latitude()
    val longitude = longitude()
    return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude, longitude) else null
}

/** Whether the road that leaves this intersection, the one the car is on until the next, is a freeway. */
internal fun StepIntersection.isFreeway(): Boolean =
    classes()?.contains(MOTORWAY_CLASS) == true || mapboxStreetsV8()?.roadClass() in FREEWAY_ROAD_CLASSES

private const val MOTORWAY_CLASS = "motorway"
private val FREEWAY_ROAD_CLASSES = setOf("motorway", "trunk")
