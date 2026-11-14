package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.DriveViewControl
import io.github.purplepalm.custommaps.nav.model.RecenterControl

/** What the camera of a map is to do during guidance. */
enum class CameraWish {
    /** Follow the car. */
    FOLLOW,

    /** Show the overview of the rest of the route. */
    OVERVIEW,

    /** Leave the map alone: the driver holds it. */
    LEAVE,
}

/**
 * What a tap on the Overview toggle did: the drive view it chose, the wish to hand to the camera (null
 * when the camera is to keep what it does), and whether it ended a hold of the map.
 */
data class ToggleResult(val driveView: DriveView, val wish: CameraWish?, val endedHold: Boolean)

/** Why a hold of the map ended, by its name in the log. */
enum class HoldEnd(val logName: String) {
    /** Its count ran out. */
    TIMER("timer"),

    /** The driver tapped Recenter. */
    RECENTER("recenter"),

    /** Guidance ended. */
    GUIDANCE("guidance"),

    /** The driver tapped the Overview toggle. */
    TOGGLE("toggle"),

    /** The drive view was changed in Settings. */
    SETTINGS("settings"),

    /** A look at the overview took its place. */
    LOOK("look"),

    /** A look ended without a move: the plan came to want the overview. */
    PLAN("plan"),

    /** A look ended at once: a point came near. */
    NEAR("near"),
}

/** A hold of the map that ended, for the log: its kind, why, how long it held, how often a touch started its count again, a point near. */
data class HoldEnded(val kind: MapHold.Kind, val ended: HoldEnd, val heldSeconds: Double, val restarts: Int, val near: Boolean)

/**
 * The drive camera of one map: the plan of the drive views ([DriveViewPlanner]), the map the driver
 * holds ([MapHold]) and their settings, and the one wish the map's camera carries out. Each call takes
 * the time and returns the wish to hand to the camera now, or null to leave it as it is: only when the
 * wish changes, except at the start of guidance, whose answer is always handed on.
 *
 * - Recenter, the count at 0 and the end of a look ask the plan where the map goes back to
 *   ([DriveViewPlanner.onRecenter]): the car in Normal, in the Overview the overview unless a turn is near.
 * - The Overview toggle always does one thing: it switches the drive view, and during a hold ends it
 *   first. A drive view changed in Settings does the same; a change of Zoom in before turns or of
 *   Recenter by itself keeps a hold, whose count takes the new value at once.
 * - A look (the routes badge) shows the overview for [DriveViewTuning.lookSeconds]; none while a point is
 *   near or where the overview could not stand [DriveViewTuning.buttonOverviewSeconds], none where the
 *   map shows the overview anyway, and one running ends at once when a point comes near.
 */
class DriveCamera(
    camera: CameraTuning = CameraTuning.PHONE,
    private val tuning: DriveViewTuning = DriveViewTuning.PHONE,
) {
    private val planner = DriveViewPlanner(camera, tuning)
    private val hold = MapHold(tuning)
    private var settings = DriveCameraSettings()
    private var guiding = false
    private var ended: HoldEnded? = null

    /** What the camera is to do now: during guidance the plan's view or the driver's map; [CameraWish.FOLLOW] outside it. */
    var wish = CameraWish.FOLLOW
        private set

    /** A point is near ([DriveViewPlanner.near]). */
    val near: Boolean get() = planner.near

    /** Counts the changes of the plan, for a log line per change. */
    val planRevision: Int get() = planner.revision

    /** What the Overview toggle shows: it changes only when the drive view or Zoom in before turns is chosen. */
    fun driveViewControl(): DriveViewControl = DriveViewControl(settings.driveView, settings.turnZoom)

    /** What Recenter shows: null without a hold; its count in whole seconds, or none while Recenter by itself is off. */
    fun recenterControl(nowSeconds: Double): RecenterControl? =
        if (!guiding || hold.kind == MapHold.Kind.NONE) null else RecenterControl(hold.secondsLeft(nowSeconds, settings.recenterSeconds))

    /** The settings changed: a change of the drive view ends a hold and switches; the others keep the hold. */
    fun setSettings(settings: DriveCameraSettings, nowSeconds: Double): CameraWish? {
        val before = this.settings
        this.settings = settings
        if (settings.driveView != before.driveView) endHold(nowSeconds, HoldEnd.SETTINGS)
        planner.setTurnZoom(settings.turnZoom, nowSeconds)
        planner.setDriveView(settings.driveView, nowSeconds)
        settleLook(nowSeconds)
        return update()
    }

    /** A tap on the Overview toggle: the other drive view, also during a hold, which it ends. */
    fun toggle(nowSeconds: Double): ToggleResult {
        val view = if (settings.driveView == DriveView.NORMAL) DriveView.OVERVIEW else DriveView.NORMAL
        val endedHold = guiding && hold.kind != MapHold.Kind.NONE
        endHold(nowSeconds, HoldEnd.TOGGLE)
        settings = settings.copy(driveView = view)
        planner.setDriveView(view, nowSeconds)
        return ToggleResult(view, update(), endedHold)
    }

    /**
     * Guidance starts, or the map that shows it was made anew during the drive ([fromPreview] false):
     * the wish to hand to the camera, always, also when it is what the camera does. Null while guidance
     * runs already, so a second start asks nothing.
     */
    fun onGuidanceStarted(nowSeconds: Double, fromPreview: Boolean): CameraWish? {
        if (guiding) return null
        guiding = true
        hold.release()
        ended = null
        planner.onGuidanceStarted(nowSeconds, fromPreview)
        wish = wishOf(planner.wanted)
        return wish
    }

    /** Guidance ended: a hold is dropped, and the map follows its own rules again. */
    fun onGuidanceEnded(nowSeconds: Double) {
        if (!guiding) return
        endHold(nowSeconds, HoldEnd.GUIDANCE)
        guiding = false
        planner.onGuidanceEnded()
        wish = CameraWish.FOLLOW
    }

    /** One update of the navigator. During a hold the plan goes on deciding, and the camera is asked for nothing. */
    fun onProgress(input: ViewInput): CameraWish? {
        if (!guiding) return null
        planner.onProgress(input)
        hold.onNear(planner.near)
        settleLook(input.nowSeconds)
        return update()
    }

    /** A gesture or the compass took the map from the camera: the map is the driver's. Nothing to ask; the camera let go. */
    fun onMapTaken(nowSeconds: Double) {
        if (!guiding) return
        hold.onTaken(nowSeconds)
        update()
    }

    /** A camera move of the driver's own started or ended: one assignment, for every animator of a drag. */
    fun onMapMoved(nowSeconds: Double) = hold.onMapMoved(nowSeconds)

    /** The first finger went down on the map, or the last one came up. */
    fun onFinger(down: Boolean, nowSeconds: Double) = hold.onFinger(down, nowSeconds)

    /** A tap on Recenter: the hold ends, and the map goes back where the plan says. */
    fun onRecenter(nowSeconds: Double): CameraWish? {
        if (!guiding || hold.kind == MapHold.Kind.NONE) return null
        endHold(nowSeconds, HoldEnd.RECENTER)
        planner.onRecenter(nowSeconds)
        return update()
    }

    /** A tap on the routes badge: a look at the overview, where one may begin; null where none does. */
    fun onLook(nowSeconds: Double): CameraWish? {
        if (!guiding || planner.near || planner.wanted == CameraMode.OVERVIEW || !planner.overviewHasTime()) return null
        endHold(nowSeconds, HoldEnd.LOOK)
        hold.onLook(nowSeconds)
        return update()
    }

    /** The next new route is one the driver picked ([DriveViewPlanner.onRouteChosenByDriver]). */
    fun onRouteChosenByDriver() = planner.onRouteChosenByDriver()

    /** The car was put somewhere else along the route, as a seek puts it: the plan decides afresh from there. */
    fun onJump(nowSeconds: Double) {
        if (guiding) planner.onJump(nowSeconds)
    }

    /** The hold's clock, a few times a second while one runs: its end, when the count ran out. */
    fun onTick(nowSeconds: Double): CameraWish? {
        if (!guiding || !hold.isOver(nowSeconds, settings.recenterSeconds)) return null
        endHold(nowSeconds, HoldEnd.TIMER)
        planner.onRecenter(nowSeconds)
        return update()
    }

    /** The hold that ended last, once, for the log; null when none ended since the last call. */
    fun takeHoldEnded(): HoldEnded? = ended.also { ended = null }

    /** For the once-a-second camera line: the drive view, the plan and why, its point, a point near, a hold and its count. */
    fun debugFields(nowSeconds: Double): Map<String, Any?> {
        val point = planner.point
        return mapOf(
            "view" to settings.driveView,
            "plan" to planner.plan,
            "planWhy" to planner.planReason,
            "pointM" to point?.let { Motion.round(it.metersAhead, 0) },
            "pointKind" to point?.kind,
            "approachM" to planner.approachMeters.takeIf { it.isFinite() }?.let { Motion.round(it, 0) },
            "near" to planner.near,
            "hold" to hold.kind.takeIf { it != MapHold.Kind.NONE },
            "holdS" to hold.secondsLeft(nowSeconds, settings.recenterSeconds),
        ).filterValues { it != null }
    }

    /** For the line written when the plan or the wish changes: what the plan saw and decided. Numbers, kinds and words only. */
    fun driveViewFields(): Map<String, Any?> {
        val point = planner.point
        return mapOf(
            "view" to settings.driveView,
            "wish" to wish,
            "reason" to planner.reason,
            "pointKind" to point?.kind,
            "pointM" to point?.let { Motion.round(it.metersAhead, 0) },
            "gapM" to planner.gapMeters.takeIf { it.isFinite() }?.let { Motion.round(it, 0) },
            "approachM" to planner.approachMeters.takeIf { it.isFinite() }?.let { Motion.round(it, 0) },
            "standS" to planner.standSeconds.takeIf { it.isFinite() }?.let { Motion.round(it, 1) },
            "speed" to planner.speedMps?.takeIf { it.isFinite() }?.let { Motion.round(it, 1) },
            "planSpeed" to planner.planSpeed.takeIf { it.isFinite() }?.let { Motion.round(it, 1) },
            "near" to planner.near,
            "silent" to planner.silent,
            "turnZoom" to settings.turnZoom,
        ).filterValues { it != null }
    }

    /** A look ends at once when a point comes near (going back as Recenter does), and without a move when the plan wants the overview. */
    private fun settleLook(nowSeconds: Double) {
        if (!guiding || hold.kind != MapHold.Kind.LOOK) return
        if (planner.near) {
            endHold(nowSeconds, HoldEnd.NEAR)
            planner.onRecenter(nowSeconds)
        } else if (planner.wanted == CameraMode.OVERVIEW) {
            endHold(nowSeconds, HoldEnd.PLAN)
        }
    }

    private fun endHold(nowSeconds: Double, why: HoldEnd) {
        if (hold.kind == MapHold.Kind.NONE) return
        ended = HoldEnded(hold.kind, why, nowSeconds - hold.since, hold.restarts, planner.near)
        hold.release()
    }

    private fun update(): CameraWish? {
        val now = when {
            !guiding -> CameraWish.FOLLOW
            hold.kind == MapHold.Kind.MAP_MOVED -> CameraWish.LEAVE
            hold.kind == MapHold.Kind.LOOK -> CameraWish.OVERVIEW
            else -> wishOf(planner.wanted)
        }
        if (now == wish) return null
        wish = now
        return now
    }

    private fun wishOf(mode: CameraMode): CameraWish = if (mode == CameraMode.OVERVIEW) CameraWish.OVERVIEW else CameraWish.FOLLOW
}
