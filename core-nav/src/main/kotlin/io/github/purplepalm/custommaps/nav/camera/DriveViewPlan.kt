package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/*
 * When the Overview drive view leaves the overview for the view behind the car, and when it goes back
 * out: decided once per update of the navigator from plain numbers, so the phone's map and the car's
 * give the same answer and it is tested on the JVM. The cameras carry the answer out. In the Normal
 * drive view and in the plain Overview the same plan runs silently, for the log.
 */

/** The kind of point ahead that the view behind the car is wanted for; it decides how early. */
enum class ApproachKind {
    /** A maneuver the view tilts down for: a turn, the end of a road, a roundabout and its way out, the arrival. */
    TURN,

    /** An on or off ramp, a fork, a merge, or a continue that turns: on a freeway its lanes are picked early. */
    RAMP,

    /** A freeway lane choice that the lanes' close view goes in for ([LaneChoice.counts]). */
    LANE_CHOICE,
}

/** A point ahead on the route that the view behind the car is wanted for, as the map engine reads it. */
data class ViewPoint(
    /** The route, leg and step of a maneuver, or the key of a lane choice: a new route gives new keys. */
    val key: String,
    val kind: ApproachKind,
    /** What the camera does at the point ([FocusRule], from the step's own maneuver); [FocusKind.NONE] for a lane choice. */
    val focus: FocusKind,
    /** The road that leads to the point is a freeway. */
    val onFreeway: Boolean,
    /** Along the route, from the car to the point. */
    val metersAhead: Double,
    /** The route's seconds to the point; 0 where they are not known. */
    val secondsAhead: Double = 0.0,
)

/** One update of the navigator, as the planner reads it. */
class ViewInput(
    /** Seconds on one steady clock, the same for every call of one planner. */
    val nowSeconds: Double,
    val routeId: String,
    /** How far the car is along the route: the progress's distance travelled. */
    val routeMeters: Double,
    /** The navigator has the car off the route at this update. */
    val offRoute: Boolean,
    /** The car's matched speed; unknown counts as slow. */
    val speedMps: Double?,
    /** The points ahead, nearest first: the next one and the one after it. */
    val points: List<ViewPoint>,
    /**
     * The exit of the run-up on now ([RunUpLatch.exitKey], also through the ramp the latch holds
     * after the gore); null outside a run-up. A run-up is one approach: the view is down from its
     * start to its end.
     */
    val runUpKey: String? = null,
)

/** The numbers of the drive views, in seconds and metres: one place to tune after a drive. */
data class DriveViewTuning(
    /**
     * The view leaves the overview this long before the camera's own schedule for a point starts
     * (the tilt, or the lanes' close view): the 2.2 s move in, then a settled normal view before the
     * tilt or the zoom begins, and room for a decision made once a second.
     */
    val leaveLeadSeconds: Double = 5.0,
    /** After a point the view goes back out only when the next one is at least this far on (1.25 mi). */
    val returnMeters: Double = 2_000.0,
    /** And, below [alwaysOutMeters], only when the overview would then stand at least this long. */
    val minOverviewSeconds: Double = 30.0,
    /** A tap into the Overview, or a hold's end, shows it at once only when it could stand this long. */
    val buttonOverviewSeconds: Double = 10.0,
    /** A normal view the plan came down to by itself stands at least this long before it goes back out. */
    val minNormalSeconds: Double = 8.0,
    /** A look at the overview the driver asked for lasts this long. */
    val lookSeconds: Double = 10.0,
    /** While a point is near, the map the driver moved comes back at most this long after the last touch. */
    val recenterNearSeconds: Double = 3.0,
    /** A finger reported down with nothing moving for this long counts as lifted. */
    val staleFingerSeconds: Double = 30.0,
    /** The planner's hold after a point ends this much before the camera's own, whatever the phase of their clocks. */
    val holdEarlySeconds: Double = 0.5,
    /** A next point this far on (1.5 mi) or farther is always worth the overview. */
    val alwaysOutMeters: Double = 2_414.0,
    /** Off the route counts after this many updates in a row; a single one moves nothing. */
    val offRouteUpdates: Int = 2,
    /**
     * After a new route the plan does not go out by itself for this long: reroutes come in bursts,
     * and the view would jump out and back between them.
     */
    val rerouteSettleSeconds: Double = 30.0,
) {
    companion object {
        val PHONE = DriveViewTuning()
        val CAR = DriveViewTuning()
    }
}

/** The plain rules of the drive views, shared by the planner and its tests. */
object DriveViewRule {

    /** A car slower than this, standing or of unknown speed counts as this fast, as for the tilt ([FocusWindow.MIN_SPEED_MPS]). */
    private const val MIN_SPEED_MPS = FocusWindow.MIN_SPEED_MPS

    /**
     * Which kind of point a maneuver is, from the step's own maneuver; null for one that is no reason
     * to leave the overview (going straight on, the departure, a notification).
     */
    fun approachKind(type: ManeuverType, modifier: TurnModifier?): ApproachKind? {
        val turns = modifier != null && modifier != TurnModifier.STRAIGHT
        return when (type) {
            ManeuverType.DEPART, ManeuverType.NOTIFICATION -> null
            ManeuverType.NEW_NAME -> if (turns) ApproachKind.TURN else null
            ManeuverType.CONTINUE -> if (turns) ApproachKind.RAMP else null
            ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK, ManeuverType.MERGE -> ApproachKind.RAMP
            ManeuverType.TURN, ManeuverType.END_OF_ROAD, ManeuverType.ROUNDABOUT, ManeuverType.ROTARY,
            ManeuverType.ROUNDABOUT_TURN, ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY,
            ManeuverType.ARRIVE, ManeuverType.UNKNOWN,
            -> ApproachKind.TURN
        }
    }

    /**
     * The speed the approach to [point] is planned at: the larger of the car's and the route's to
     * the point, and no less than 3 m/s. At a red light before a turn the car stands, but the route
     * still says the turn is near, so the view is down before the light, not when it turns green.
     */
    fun planSpeed(point: ViewPoint, speedMps: Double?): Double {
        var v = MIN_SPEED_MPS
        if (speedMps != null && speedMps > v) v = speedMps
        if (point.secondsAhead > 0.0 && point.metersAhead > 0.0) {
            val route = point.metersAhead / point.secondsAhead
            if (route.isFinite() && route > v) v = route
        }
        return v
    }

    /**
     * How far before [point] the camera's own schedule starts at [planSpeedMps]: the lanes' close
     * view ([LaneFocus.windowMeters]) for a lane choice and for a ramp, fork or merge on a freeway;
     * elsewhere the tilt of the junction view ([FocusWindow.windowMeters]) by the point's kind, a
     * complex junction's or a plain one's, with no screen cap: never shorter than the camera's, so
     * the view is down before the tilt starts.
     */
    fun scheduleMeters(point: ViewPoint, planSpeedMps: Double, camera: CameraTuning): Double {
        if (point.kind == ApproachKind.LANE_CHOICE || (point.kind == ApproachKind.RAMP && point.onFreeway)) {
            return LaneFocus.windowMeters(camera, planSpeedMps)
        }
        val kind = if (point.focus == FocusKind.COMPLEX) FocusKind.COMPLEX else FocusKind.TURN
        return FocusWindow.windowMeters(kind, planSpeedMps, Double.POSITIVE_INFINITY, camera)
    }

    /** How far before [point] the view leaves the overview: the camera's schedule and [DriveViewTuning.leaveLeadSeconds] more. */
    fun approachMeters(point: ViewPoint, speedMps: Double?, camera: CameraTuning, tuning: DriveViewTuning): Double {
        val v = planSpeed(point, speedMps)
        return scheduleMeters(point, v, camera) + v * tuning.leaveLeadSeconds
    }

    /**
     * How long an overview asked for now would stand before [next]'s approach brings the view down
     * again: the driving to that approach at the planned speed, less the way out
     * ([CameraTuning.longTransitionSeconds]). Negative inside the approach.
     */
    fun overviewSeconds(next: ViewPoint, speedMps: Double?, camera: CameraTuning, tuning: DriveViewTuning): Double =
        (next.metersAhead - approachMeters(next, speedMps, camera, tuning)) / planSpeed(next, speedMps) - camera.longTransitionSeconds
}

/**
 * The plan of one map: whether the Overview drive view shows the overview or follows the car. One per
 * map; every call takes the time, so tests drive the clock. A call returns the view to ask the camera
 * for now, or null to leave it alone: only when the wanted view changes, never once a second, since a
 * Mapbox camera style starts its flight again when it is asked for the state it flies to.
 *
 * The plan, the smart Overview's, runs in every drive view: in the Normal one and in the plain
 * Overview silently ([silent]), so an ordinary drive shows in the log what the Overview would have done.
 * - It leaves the overview when a point ahead comes within its approach ([DriveViewRule.approachMeters]),
 *   or a run-up begins, and stays down for that point until it is passed: braking, a red light or a
 *   jam never send it back out before the point. That latch is [near].
 * - Once per point passed, when the planner's hold after it ends (the camera's, half a second sooner),
 *   it stays down when the next point is already within its approach, nearer than
 *   [DriveViewTuning.returnMeters], or below [DriveViewTuning.alwaysOutMeters] with an overview that
 *   would stand less than [DriveViewTuning.minOverviewSeconds]; it waits while the view came down by
 *   itself less than [DriveViewTuning.minNormalSeconds] ago, or a new route came less than
 *   [DriveViewTuning.rerouteSettleSeconds] ago; otherwise it goes out. With no point passed to
 *   measure from (a start without the preview, a new route, back on the route, after a seek) the
 *   gap is the next point's distance from the car. An overview that stands at such a moment is
 *   brought down only by an approach.
 * - Off the route after [DriveViewTuning.offRouteUpdates] updates in a row it follows the car.
 */
class DriveViewPlanner(
    private val camera: CameraTuning = CameraTuning.PHONE,
    private val tuning: DriveViewTuning = DriveViewTuning.PHONE,
) {
    /** Why the plan wants what it wants, by its name in the log. */
    enum class Reason {
        /** The Normal drive view, or no guidance: the car. */
        NORMAL_VIEW,

        /** Guidance started: from the preview in the overview, otherwise behind the car until the first update decides. */
        START,

        /** The overview: the next point is far enough away. */
        OPEN_ROAD,

        /** Down for a point within its approach, or for a run-up. */
        APPROACH,

        /** A point was just passed; the hold after it runs. */
        JUST_PASSED,

        /** As the hold ended, the next point was already within its approach. */
        NEXT_IS_NEAR,

        /** The next point is less than [DriveViewTuning.returnMeters] on: down until it is passed. */
        GAP_TOO_SHORT,

        /** The overview would stand less than [DriveViewTuning.minOverviewSeconds] before the next approach. */
        STAY_TOO_SHORT,

        /** The view came down by itself less than [DriveViewTuning.minNormalSeconds] ago: it waits, then decides again. */
        SETTLING,

        /** The driver turned the Overview on, and it shows. */
        BUTTON,

        /** The driver turned the Overview on near a point: it shows at the first moment it can stand. */
        BUTTON_TOO_CLOSE,

        /** Off the route. */
        OFF_ROUTE,

        /** A new route came less than [DriveViewTuning.rerouteSettleSeconds] ago: the way out waits. */
        NEW_ROUTE,

        /** The driver picked another route: judged as a tap on the Overview. */
        DRIVER_ROUTE,

        /** Zoom in before turns is off: the plain Overview, out all the way. */
        PLAIN,

        /** A hold of the map ended in the overview: out as if the plan had gone out by itself. */
        RECENTERED,

        /** A hold of the map ended right before an approach: down until that approach opens and its point is passed. */
        RECENTER_TOO_CLOSE,
    }

    /** Where the plan stands. */
    private enum class Phase {
        /** The overview, until an approach opens, a run-up begins or the car leaves the route. */
        OUT,

        /** Down for the point [target], latched until it is passed. */
        DOWN,

        /** [target] was passed; the hold after it runs, then the way out is decided. */
        AFTER,

        /** Down until [target], the next point, is passed. */
        STAY,

        /** Down, deciding the way out again on every update. */
        DECIDE,

        /** Off the route. */
        OFF_ROUTE,
    }

    private var view = DriveView.NORMAL
    private var turnZoom = true
    private var guiding = false
    private var phase = Phase.DECIDE

    /** The point the plan is down or waiting for; null in the other phases. */
    private var target: String? = null
    private var targetKind: ApproachKind? = null

    /** Where along the route [target] lies, from the last update it lay ahead. */
    private var targetAt = Double.NaN

    /** When the last point was passed. */
    private var passedAt = Double.NaN

    /** Where along the route the last point passed lies: the gap is measured from it; not a number when there is none. */
    private var passedFrom = Double.NaN

    /**
     * When the plan last came down from the overview by itself; not a number before, so at a start,
     * where the view did not come down by itself, the first update decides with no wait.
     */
    private var cameDownAt = Double.NaN

    /** When the last new route came that the driver did not pick; not a number before. */
    private var rerouteAt = Double.NaN

    /** A tap into the Overview, or the driver's route, waits for the first moment the overview can stand. */
    private var tapped = false
    private var tapReason = Reason.BUTTON

    /** The next new route is one the driver picked. */
    private var driverRoute = false
    private var offRouteRun = 0
    private var routeId: String? = null

    // The latest update, for a decision between two updates.
    private var routeMeters = Double.NaN
    private var speed: Double? = null
    private var points: List<ViewPoint> = emptyList()

    // The nearest point of the latest update, and where it lies along the route: passed when it is no longer ahead.
    private var nearestKey: String? = null
    private var nearestAt = Double.NaN
    private var nearestKind: ApproachKind? = null

    /** Why [plan] is what it is. */
    var planReason = Reason.START
        private set

    /** The gap the way out was last decided on, in metres; not a number before. */
    var gapMeters = Double.NaN
        private set

    /** How long the overview could have stood at that decision; not a number before. */
    var standSeconds = Double.NaN
        private set

    /** Counts the changes of the plan's phase, reason or wanted view, for a log line per change. */
    var revision = 0
        private set

    /** What the smart Overview shows now: [CameraMode.OVERVIEW] or [CameraMode.FOLLOWING]; computed in every drive view. */
    val plan: CameraMode
        get() = if (guiding && phase == Phase.OUT) CameraMode.OVERVIEW else CameraMode.FOLLOWING

    /** What the map shows now in the drive view chosen: the car in Normal, the overview in the plain Overview, else [plan]. */
    val wanted: CameraMode
        get() = when {
            !guiding || view == DriveView.NORMAL -> CameraMode.FOLLOWING
            !turnZoom -> CameraMode.OVERVIEW
            else -> plan
        }

    /** Why [wanted] is what it is. */
    val reason: Reason
        get() = when {
            !guiding || view == DriveView.NORMAL -> Reason.NORMAL_VIEW
            !turnZoom -> Reason.PLAIN
            else -> planReason
        }

    /** The plan is not what the map shows: the Normal drive view, or the plain Overview. */
    val silent: Boolean get() = view == DriveView.NORMAL || !turnZoom

    /**
     * A point is near: from the first update within its approach (or a run-up's start) until it is
     * passed and the hold after it ended, a new route came or guidance ended. In every drive view.
     */
    val near: Boolean get() = guiding && (phase == Phase.DOWN || phase == Phase.AFTER)

    /** The point the plan looks at, for the log: the one it is down or waiting for, else the nearest; null without one. */
    val point: ViewPoint?
        get() {
            val key = target
            if (key != null) points.firstOrNull { it.key == key }?.let { return it }
            return points.firstOrNull()
        }

    /** How far before [point] the view leaves the overview at the latest update; not a number without one. */
    val approachMeters: Double
        get() = point?.let { DriveViewRule.approachMeters(it, speed, camera, tuning) } ?: Double.NaN

    /** The speed [point]'s approach is planned at; not a number without one. */
    val planSpeed: Double
        get() = point?.let { DriveViewRule.planSpeed(it, speed) } ?: Double.NaN

    /**
     * Whether an overview asked for now would stand at least [DriveViewTuning.buttonOverviewSeconds]
     * before the next approach, with no point near; true while no point is known.
     */
    fun overviewHasTime(): Boolean {
        if (near) return false
        val next = points.firstOrNull() ?: return true
        return !inApproach(next) && DriveViewRule.overviewSeconds(next, speed, camera, tuning) >= tuning.buttonOverviewSeconds
    }

    /** The driver chose a drive view. Turned to the Overview, it shows at once unless a point is near ([Reason.BUTTON_TOO_CLOSE]). */
    fun setDriveView(view: DriveView, nowSeconds: Double): CameraMode? {
        if (view == this.view) return null
        val before = wanted
        this.view = view
        if (view == DriveView.OVERVIEW) {
            tap(Reason.BUTTON)
        } else {
            tapped = false
        }
        return changed(before)
    }

    /**
     * Zoom in before turns. Turned off, the Overview stays out from now on, also down for a turn: out
     * at once. Turned on, it comes down at once inside an approach, and otherwise at the next one.
     */
    fun setTurnZoom(on: Boolean, nowSeconds: Double): CameraMode? {
        if (on == turnZoom) return null
        val before = wanted
        turnZoom = on
        // The plain Overview had the view out; an overview that stands is brought down only by an approach.
        if (on && guiding && view == DriveView.OVERVIEW && (phase == Phase.STAY || phase == Phase.DECIDE)) goOut(Reason.PLAIN)
        return changed(before)
    }

    /**
     * Guidance starts, or the map that shows it was made anew during the drive. From the preview the
     * plan is in the overview, and the first update keeps it unless a point is near; otherwise it
     * follows the car and the first update decides, with no wait. The plain Overview shows the
     * overview for every start. Null while guidance runs already: a second call asks nothing.
     */
    fun onGuidanceStarted(nowSeconds: Double, fromPreview: Boolean): CameraMode? {
        if (guiding) return null
        startOver()
        guiding = true
        if (fromPreview) {
            goOut(Reason.START)
        } else {
            phase = Phase.DECIDE
            planReason = Reason.START
        }
        bump()
        return wanted
    }

    /** Guidance ended: nothing is kept for the next drive but the drive view and Zoom in before turns. */
    fun onGuidanceEnded() {
        if (!guiding) return
        guiding = false
        startOver()
        bump()
    }

    /**
     * A hold of the map ended (Recenter, its count at 0, a look's end): what the map goes back to. The
     * car in the Normal drive view, the overview in the plain Overview. In the smart Overview the
     * overview ([Reason.RECENTERED]), also where the view had stayed down between close points or to
     * settle, and the plan is out from then on until the next approach; but the view behind the car
     * when a point is near, off the route, or when the overview would stand less than
     * [DriveViewTuning.buttonOverviewSeconds] before the next approach ([Reason.RECENTER_TOO_CLOSE],
     * down until that point is passed).
     */
    fun onRecenter(nowSeconds: Double): CameraMode {
        if (guiding && (phase == Phase.STAY || phase == Phase.DECIDE)) {
            val next = points.firstOrNull()
            if (next != null && !overviewHasTime()) {
                stay(next, Reason.RECENTER_TOO_CLOSE)
            } else {
                goOut(Reason.RECENTERED)
            }
            bump()
        }
        return wanted
    }

    /** The next new route is one the driver picked: it is judged as a tap on the Overview, with no wait for a burst of reroutes. */
    fun onRouteChosenByDriver() {
        driverRoute = true
    }

    /**
     * The car was put somewhere else along the route, as a seek of a simulated drive puts it. The plan
     * decides again from the new place as at a new route: nothing passed is kept, so a seek back
     * leaves no passed point ahead of the car and a seek forward no view held for a point jumped over.
     * An overview that stands is brought down only by an approach.
     */
    fun onJump(nowSeconds: Double) {
        if (!guiding) return
        val before = wanted
        passedFrom = Double.NaN
        nearestKey = null
        if (phase != Phase.OUT && phase != Phase.OFF_ROUTE) decideFromHere()
        changed(before)
    }

    /** One update of the navigator. */
    fun onProgress(input: ViewInput): CameraMode? {
        if (!guiding) return null
        val before = wanted
        val now = input.nowSeconds
        val known = routeId
        routeId = input.routeId
        if (known != null && known != input.routeId) onNewRoute(now)
        if (input.offRoute) {
            // A single update off the route moves nothing.
            offRouteRun += 1
            if (offRouteRun >= tuning.offRouteUpdates && phase != Phase.OFF_ROUTE) {
                if (phase == Phase.OUT) cameDownAt = now
                phase = Phase.OFF_ROUTE
                target = null
                targetKind = null
                passedFrom = Double.NaN
                planReason = Reason.OFF_ROUTE
            }
            return changed(before)
        }
        offRouteRun = 0
        routeMeters = input.routeMeters
        speed = input.speedMps
        points = input.points
        // Back on a route: decided as with no point passed, after a settled normal view.
        if (phase == Phase.OFF_ROUTE) decideFromHere()
        step(input)
        return changed(before)
    }

    private fun step(input: ViewInput) {
        val now = input.nowSeconds
        // The nearest point of the update before, and whether the car has passed it since.
        val lastKey = nearestKey
        val lastAt = nearestAt
        val lastKind = nearestKind
        val first = input.points.firstOrNull()
        nearestKey = first?.key
        nearestAt = if (first == null) Double.NaN else input.routeMeters + first.metersAhead
        nearestKind = first?.kind
        val passedOne = lastKey != null && input.points.none { it.key == lastKey }
        // A run-up is one approach: down from its start to its end, whatever its lane choices do on the way.
        val runUp = input.runUpKey
        if (runUp != null) {
            if (phase == Phase.OUT) cameDownAt = now
            if (phase != Phase.DOWN || target != runUp) {
                phase = Phase.DOWN
                target = runUp
                targetKind = ApproachKind.LANE_CHOICE
                planReason = Reason.APPROACH
            }
            // It moves with the car: where it ends is where the hold after it starts.
            targetAt = input.routeMeters
            return
        }
        when (phase) {
            Phase.OUT -> {
                val ahead = inApproach(input.points)
                if (ahead != null) {
                    cameDownAt = now
                    down(ahead, Reason.APPROACH)
                }
            }
            Phase.DOWN -> if (!tracksTarget(input)) passed(input)
            Phase.STAY -> {
                if (tracksTarget(input)) {
                    // The approach of the point waited for, or of a later one with a longer approach, opens.
                    val ahead = inApproach(input.points)
                    if (ahead != null) {
                        down(ahead, Reason.APPROACH)
                    } else {
                        honourTap()
                    }
                } else {
                    passed(input)
                }
            }
            Phase.AFTER -> {
                // A point passed during the hold, as the way out of a roundabout right after its way in: the hold starts again from it.
                if (passedOne) {
                    passedAt = now
                    passedFrom = lastAt
                    targetKind = lastKind
                }
                holdOrDecide(input)
            }
            Phase.DECIDE -> decide(input)
            Phase.OFF_ROUTE -> Unit
        }
    }

    /** Whether [target] is still ahead, and where it lies along the route if it is. */
    private fun tracksTarget(input: ViewInput): Boolean {
        val kept = input.points.firstOrNull { it.key == target } ?: return false
        targetAt = input.routeMeters + kept.metersAhead
        return true
    }

    private fun passed(input: ViewInput) {
        phase = Phase.AFTER
        passedAt = input.nowSeconds
        passedFrom = targetAt
        planReason = Reason.JUST_PASSED
        holdOrDecide(input)
    }

    /** The planner's hold after a point: its metres and the camera's seconds less half a second; then the way out is decided once. */
    private fun holdOrDecide(input: ViewInput) {
        val lanes = targetKind == ApproachKind.LANE_CHOICE
        val holdSeconds = (if (lanes) camera.laneHoldSeconds else camera.holdAfterManeuverSeconds) - tuning.holdEarlySeconds
        val holdMeters = if (lanes) camera.laneHoldMeters else camera.holdAfterManeuverMeters
        if (input.nowSeconds - passedAt < holdSeconds || input.routeMeters - passedFrom < holdMeters) return
        phase = Phase.DECIDE
        target = null
        decide(input)
    }

    /** The way out, decided on this update: the next point near, the gap, the time the overview could stand, the waits. */
    private fun decide(input: ViewInput) {
        val now = input.nowSeconds
        val ahead = inApproach(input.points)
        if (ahead != null) {
            down(ahead, if (planReason == Reason.JUST_PASSED) Reason.NEXT_IS_NEAR else Reason.APPROACH)
            return
        }
        val next = input.points.firstOrNull() ?: return
        val stand = DriveViewRule.overviewSeconds(next, speed, camera, tuning)
        if (tapped && stand >= tuning.buttonOverviewSeconds) {
            goOut(tapReason)
            return
        }
        val gap = if (passedFrom.isNaN()) next.metersAhead else input.routeMeters + next.metersAhead - passedFrom
        gapMeters = gap
        standSeconds = stand
        when {
            gap < tuning.returnMeters -> stay(next, Reason.GAP_TOO_SHORT)
            gap < tuning.alwaysOutMeters && stand < tuning.minOverviewSeconds -> stay(next, Reason.STAY_TOO_SHORT)
            // Both waits only hold the way out back; the longer one names it while both hold.
            now - rerouteAt < tuning.rerouteSettleSeconds -> planReason = Reason.NEW_ROUTE
            now - cameDownAt < tuning.minNormalSeconds -> planReason = Reason.SETTLING
            else -> goOut(Reason.OPEN_ROAD)
        }
    }

    /** A new route: its keys are all new. No move for the route alone; a normal view decides again, an overview stays. */
    private fun onNewRoute(nowSeconds: Double) {
        passedFrom = Double.NaN
        nearestKey = null
        if (driverRoute) {
            // Judged as a tap on the Overview, on this update's points of the new route.
            driverRoute = false
            when (phase) {
                Phase.OUT -> planReason = Reason.DRIVER_ROUTE
                Phase.OFF_ROUTE -> Unit
                else -> {
                    decideFromHere()
                    tapped = true
                    tapReason = Reason.DRIVER_ROUTE
                    planReason = Reason.DRIVER_ROUTE
                }
            }
            return
        }
        rerouteAt = nowSeconds
        if (phase != Phase.OUT && phase != Phase.OFF_ROUTE) {
            decideFromHere()
            planReason = Reason.NEW_ROUTE
        }
    }

    /** Down, with no point passed to measure from: the next update decides the way out. */
    private fun decideFromHere() {
        phase = Phase.DECIDE
        target = null
        targetKind = null
    }

    /**
     * A tap into the Overview, or a route the driver picked: the overview at once unless a point is near,
     * the car is off the route or the overview could not stand [DriveViewTuning.buttonOverviewSeconds];
     * then it waits for the first moment it can.
     */
    private fun tap(why: Reason) {
        if (!guiding || phase == Phase.OUT) return
        if (phase != Phase.OFF_ROUTE && overviewHasTime()) {
            goOut(why)
            return
        }
        tapped = true
        tapReason = why
        if (phase != Phase.OFF_ROUTE) planReason = if (why == Reason.BUTTON) Reason.BUTTON_TOO_CLOSE else why
    }

    /** A tap that waits is honoured at the first update the overview can stand. */
    private fun honourTap() {
        if (tapped && overviewHasTime()) goOut(tapReason)
    }

    private fun goOut(why: Reason) {
        phase = Phase.OUT
        target = null
        targetKind = null
        passedFrom = Double.NaN
        tapped = false
        planReason = why
    }

    private fun down(point: ViewPoint, why: Reason) {
        phase = Phase.DOWN
        target = point.key
        targetKind = point.kind
        targetAt = routeMeters + point.metersAhead
        planReason = why
    }

    private fun stay(point: ViewPoint, why: Reason) {
        phase = Phase.STAY
        target = point.key
        targetKind = point.kind
        targetAt = routeMeters + point.metersAhead
        planReason = why
    }

    /** The nearest of [points] that lies within its approach; null when none does. */
    private fun inApproach(points: List<ViewPoint>): ViewPoint? {
        for (i in points.indices) if (inApproach(points[i])) return points[i]
        return null
    }

    private fun inApproach(point: ViewPoint): Boolean =
        point.metersAhead >= 0.0 && point.metersAhead <= DriveViewRule.approachMeters(point, speed, camera, tuning)

    private fun startOver() {
        phase = Phase.DECIDE
        target = null
        targetKind = null
        targetAt = Double.NaN
        passedAt = Double.NaN
        passedFrom = Double.NaN
        cameDownAt = Double.NaN
        rerouteAt = Double.NaN
        tapped = false
        driverRoute = false
        offRouteRun = 0
        routeId = null
        routeMeters = Double.NaN
        speed = null
        points = emptyList()
        nearestKey = null
        nearestAt = Double.NaN
        nearestKind = null
        planReason = Reason.NORMAL_VIEW
        gapMeters = Double.NaN
        standSeconds = Double.NaN
    }

    // What the last call left, to count a change of the plan for the log.
    private var seenPhase: Phase? = null
    private var seenReason: Reason? = null
    private var seenWanted: CameraMode? = null

    private fun bump() {
        if (seenPhase != phase || seenReason != planReason || seenWanted != wanted) {
            seenPhase = phase
            seenReason = planReason
            seenWanted = wanted
            revision += 1
        }
    }

    private fun changed(before: CameraMode): CameraMode? {
        bump()
        val now = wanted
        return if (now != before) now else null
    }
}
