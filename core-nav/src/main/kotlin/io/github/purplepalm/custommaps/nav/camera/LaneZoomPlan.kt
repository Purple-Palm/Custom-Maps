package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * What the Large lane zoom adds to the guidance camera: how wide a lane is on the screen at a zoom,
 * which points of a route are lane choices, when the view goes close for one, and the zoom the
 * camera aims at. Plain numbers in and out, like the rest of the camera math.
 */

/** The scale of the map's zoom levels: the world is 512 dp wide at zoom 0 and doubles with every level. */
object MapScale {

    /** The width of a freeway lane: 12 feet. */
    const val LANE_METERS = 3.7

    /** The length of the equator over the 512 dp of zoom 0. */
    private const val METERS_PER_DP_AT_ZOOM_0 = 40_075_016.686 / 512.0

    /** How much ground one dp covers at the center of the camera. */
    fun metersPerDp(zoom: Double, latitude: Double): Double =
        METERS_PER_DP_AT_ZOOM_0 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)

    /** How wide a lane is on the screen at the center of the camera. In a tilted view, nearer lanes are wider. */
    fun laneDp(zoom: Double, latitude: Double): Double = LANE_METERS / metersPerDp(zoom, latitude)

    /** The zoom at which a lane is [laneDp] wide. It depends on the latitude, so the same width looks the same everywhere. */
    fun zoomForLaneDp(laneDp: Double, latitude: Double): Double =
        log2(METERS_PER_DP_AT_ZOOM_0 * cos(Math.toRadians(latitude)) * laneDp / LANE_METERS)
}

/**
 * A point ahead on the route with lanes of its own: an intersection the route goes straight
 * through, or the next maneuver where that is an exit or a fork. Read from the route's lane data by
 * the map engine. Whether it is worth a close view is decided here, by [skipReason].
 */
data class LaneChoice(
    /** The route, leg, step and intersection, so the same point on a new route is a new choice. */
    val key: String,
    /** Along the route, from the car to the point. */
    val metersAhead: Double,
    /** The lanes that reach the point, not counting [designatedLanes]. */
    val laneCount: Int,
    /** Of those, the lanes the route can be followed from. */
    val usableLanes: Int,
    /** Lanes kept for buses, car pools, taxis and the like: no choice for this car. */
    val designatedLanes: Int,
    /** The road that leads to the point is a freeway. */
    val onFreeway: Boolean,
    /** The point is the next maneuver itself, an exit or a fork; false where the route goes straight on. */
    val atManeuver: Boolean,
) {
    /** The lanes that leave the route here. */
    val leavingLanes: Int get() = laneCount - usableLanes

    /**
     * Whether the point lies ahead of the car at a distance that is a number. A point behind the
     * car, or one whose distance could not be worked out, is no choice to show or to zoom in for.
     */
    val isAhead: Boolean get() = metersAhead >= 0.0 && metersAhead < Double.POSITIVE_INFINITY

    /**
     * Why the view stays as it is for this point; null when the point counts as a lane choice. The
     * view goes close on a freeway with enough lanes where some lanes leave the route: at an exit
     * or a fork one leaving lane is enough, and where the route goes straight on it takes
     * [CameraTuning.laneChoiceMinLeaving], since a single lane that turns into an exit is what
     * nearly every exit looks like.
     */
    fun skipReason(tuning: CameraTuning): LaneChoiceSkip? = when {
        !onFreeway -> LaneChoiceSkip.NOT_A_FREEWAY
        laneCount < tuning.laneChoiceMinLanes -> LaneChoiceSkip.TOO_FEW_LANES
        usableLanes <= 0 -> LaneChoiceSkip.NO_USABLE_LANE
        usableLanes >= laneCount -> LaneChoiceSkip.NO_LANE_LEAVES
        !atManeuver && leavingLanes < tuning.laneChoiceMinLeaving -> LaneChoiceSkip.TOO_FEW_LEAVING
        else -> null
    }

    /** Whether the view goes close for this point. */
    fun counts(tuning: CameraTuning): Boolean = skipReason(tuning) == null
}

/** Why a point with lanes gets no close view. The log names it, to show what a looser rule would add. */
enum class LaneChoiceSkip {
    /** The road is not a freeway: lane choices in town come with a turn, which has its own close view. */
    NOT_A_FREEWAY,

    /** Fewer lanes than [CameraTuning.laneChoiceMinLanes]. */
    TOO_FEW_LANES,

    /** No lane is marked as usable: a gap in the data, not a choice. */
    NO_USABLE_LANE,

    /** Every lane goes on with the route. */
    NO_LANE_LEAVES,

    /** The route goes straight on and fewer lanes than [CameraTuning.laneChoiceMinLeaving] leave it. */
    TOO_FEW_LEAVING,
}

/**
 * How far the view has moved in for a lane choice, as a smooth function of time: 0 on the road, 1
 * near a lane choice. Like [ManeuverFocus] it is fed once a second from the route progress and read
 * on every frame. The tilt stays as it is: at freeway speed a view from straight above would show
 * only a few seconds of road.
 *
 * - In over [CameraTuning.laneInSeconds], from [windowMeters] before the choice. Once a choice
 *   holds the view it keeps it: a car that brakes, and so shortens the window, stays close.
 * - Held past the choice for at least [CameraTuning.laneHoldMeters] and
 *   [CameraTuning.laneHoldSeconds]. Off the route, and on a new route, the meters say nothing of
 *   the choice, and the time alone holds the view.
 * - Out over [CameraTuning.laneOutSeconds], unless the next choice is no more than
 *   [CameraTuning.laneCoalesceSeconds] of driving from its own window: choices close together keep
 *   the view in.
 * - A reversal starts from where the focus is and takes the share of the time that is left to go.
 */
class LaneFocus(private val tuning: CameraTuning) {
    private var from = 0.0
    private var to = 0.0
    private var start = 0.0
    private var duration = 1.0

    /** Where on the route the choice that holds the view lies; not a number when that is unknown. */
    private var choiceAt = Double.NaN

    /** When the car passed that choice; not a number while it is still ahead. */
    private var passedAt = Double.NaN
    private var lastRouteMeters = Double.NaN

    /** The choice that holds the view close, for the log; null on the road. */
    var current: LaneChoice? = null
        private set

    /** The focus being moved to: 0 or 1. */
    var goal = 0.0
        private set

    fun focusAt(now: Double) = lerp(from, to, easeInOut((now - start) / duration))

    fun isAnimating(now: Double) = from != to && now - start < duration

    /**
     * @param routeMeters how far the car is along the current route
     * @param choices the points with lanes ahead of the car; the ones that do not count are passed over
     * @param speedMps the car's speed; unknown counts as standing
     * @param onRoute false while the navigator has the car off the route, where [routeMeters]
     *   stands still and [choices] is empty
     */
    fun onProgress(now: Double, routeMeters: Double, choices: List<LaneChoice>, speedMps: Double?, onRoute: Boolean = true) {
        // A new route counts its meters from its own start, and off the route they stand still.
        // Where the last choice lay says nothing then: only the time still holds the view, and a
        // car that never reaches the choice does not keep it close.
        if (!onRoute || routeMeters < lastRouteMeters - ROUTE_RESTART_METERS) choiceAt = Double.NaN
        lastRouteMeters = routeMeters

        val window = windowMeters(tuning, speedMps)
        val reach = window + knownSpeed(speedMps) * tuning.laneCoalesceSeconds
        val currentKey = current?.key
        var inWindow: LaneChoice? = null
        var held: LaneChoice? = null
        var soon = false
        for (i in choices.indices) {
            val choice = choices[i]
            if (!choice.isAhead || !choice.counts(tuning)) continue
            if (choice.metersAhead <= window && (inWindow == null || choice.metersAhead < inWindow.metersAhead)) inWindow = choice
            if (choice.key == currentKey) held = choice
            if (choice.metersAhead <= reach) soon = true
        }
        val ahead = inWindow ?: held
        if (ahead != null) {
            current = ahead
            choiceAt = routeMeters + ahead.metersAhead
            passedAt = Double.NaN
        } else if (current != null) {
            if (passedAt.isNaN()) passedAt = now
            val holding = now - passedAt < tuning.laneHoldSeconds || routeMeters < choiceAt + tuning.laneHoldMeters
            if (!holding && !soon) {
                current = null
                passedAt = Double.NaN
            }
        }
        val newGoal = if (current != null) 1.0 else 0.0
        if (newGoal != goal) animateTo(newGoal, now)
    }

    /** Guidance ended or the route was cleared: back to the road view from where the focus is. */
    fun reset(now: Double) {
        current = null
        choiceAt = Double.NaN
        passedAt = Double.NaN
        lastRouteMeters = Double.NaN
        if (goal != 0.0) animateTo(0.0, now)
    }

    private fun animateTo(newGoal: Double, now: Double) {
        val current = focusAt(now)
        val full = if (newGoal > current) tuning.laneInSeconds else tuning.laneOutSeconds
        from = current
        to = newGoal
        start = now
        duration = (full * abs(newGoal - current)).coerceAtLeast(MIN_MOVE_SECONDS)
        goal = newGoal
    }

    companion object {
        /** The shortest move, so a reversal right after a start is not a jerk. */
        private const val MIN_MOVE_SECONDS = 0.3

        /** The matched position can slip back by less than this; a new route starts far below the old one. */
        private const val ROUTE_RESTART_METERS = 1.0

        /**
         * How far before a lane choice the close view starts: [CameraTuning.laneLeadSeconds] of
         * driving at [speedMps], within the two window distances. The card shows the lanes of a
         * choice from the same distance, so both come together.
         */
        fun windowMeters(tuning: CameraTuning, speedMps: Double?): Double =
            (knownSpeed(speedMps) * tuning.laneLeadSeconds).coerceIn(tuning.laneMinWindowMeters, tuning.laneMaxWindowMeters)

        /** A speed that is missing, negative or not a number counts as standing. */
        private fun knownSpeed(speedMps: Double?): Double = if (speedMps != null && speedMps > 0.0) speedMps else 0.0
    }
}

/**
 * The zoom the camera aims at, from the zoom of the frame the navigation SDK fitted and from the
 * two schedules, [ManeuverFocus] and [LaneFocus]. With [LaneZoom.DEFAULT] it is the frame's zoom,
 * unchanged. With [LaneZoom.LARGE] the frame may be fitted closer than before, and the zoom is:
 * - on the road, the frame's zoom up to [CameraTuning.defaultMaxZoom], as with Default. A frame of
 *   the open road counts no closer than that: it is fitted to the road ahead of the car and says
 *   nothing of a junction;
 * - toward a maneuver, up to the zoom of the frame fitted around the car and the junction, which
 *   keeps both in view, and no closer than [CameraTuning.junctionLaneDp], along the same eased
 *   curve as the tilt;
 * - after the maneuver, the zoom the junction had, let go along that curve as the tilt comes back.
 *   Where the view stays down for a next maneuver, it is kept until that one is framed. What was
 *   let go is not taken up again: when the tilt turns back down for a next maneuver that is not
 *   framed yet, the zoom waits where it is;
 * - near a lane choice, the zoom of [CameraTuning.freewayLaneDp], along [LaneFocus]'s curve. In
 *   the frame of a maneuver that is no closer than the frame was fitted: its center is the
 *   frame's own point, not the car, and closer than its fit the car and the junction would be
 *   pushed off the map.
 *
 * Called on every frame; it allocates nothing.
 */
class ZoomPlanner(private val tuning: CameraTuning) {

    enum class Kind { ROAD, JUNCTION, AFTER_JUNCTION, LANES }

    /** What decides the zoom now, for the log. */
    var kind = Kind.ROAD
        private set

    /** The zoom over the junction the car is at or has just left; not a number on the road. */
    private var junctionZoom = Double.NaN

    /**
     * How much of [junctionZoom] is still kept, from 1 to 0: the lowest the focus has been since
     * that zoom was taken from a frame.
     */
    private var keptShare = 1.0

    /**
     * @param frameZoom the zoom of the frame the navigation SDK fitted
     * @param latitude where the camera is; a lane's width on the screen depends on it
     * @param junctionFocus [ManeuverFocus.focusAt] now
     * @param afterManeuver [ManeuverFocus.afterManeuver]
     * @param laneFocus [LaneFocus.focusAt] now
     * @param roadFrame the frame is one of the open road, with the car on its usual place; false
     *   for a frame fitted around the car and a maneuver
     */
    fun target(
        mode: LaneZoom,
        frameZoom: Double,
        latitude: Double,
        junctionFocus: Double,
        afterManeuver: Boolean,
        laneFocus: Double,
        roadFrame: Boolean,
    ): Double {
        if (mode == LaneZoom.DEFAULT) {
            reset()
            return frameZoom
        }
        val road = min(frameZoom, tuning.defaultMaxZoom)
        if (junctionFocus <= 0.0) junctionZoom = Double.NaN
        // The frame shows the road by now, beyond the junction or short of the next one that
        // the view stays down for: what the junction had is kept.
        val keeps = !junctionZoom.isNaN() && (afterManeuver || roadFrame)
        val junction = if (keeps) {
            // The focus can turn back toward 1 here, for a next maneuver that is latched and not
            // framed yet. Going back in to the junction behind the car would be a bounce.
            keptShare = min(keptShare, junctionFocus)
            lerp(road, max(road, junctionZoom), keptShare)
        } else {
            val full = if (roadFrame) road else max(road, min(frameZoom, closest(tuning.junctionLaneDp, latitude)))
            if (junctionFocus > 0.0 && !roadFrame) {
                junctionZoom = full
                keptShare = 1.0
            }
            lerp(road, full, junctionFocus)
        }
        val lanesGoal = max(road, closest(tuning.freewayLaneDp, latitude))
        // A lane choice that still holds the view when a maneuver is framed stays within that frame.
        val lanes = lerp(road, if (roadFrame) lanesGoal else min(lanesGoal, max(road, frameZoom)), laneFocus)
        val zoom = max(junction, lanes)
        kind = when {
            zoom <= road + SAME_ZOOM -> Kind.ROAD
            // In the frame of a maneuver both may stand at the frame's zoom: the junction decides it.
            lanes > junction || (roadFrame && lanes >= junction) -> Kind.LANES
            afterManeuver || keeps -> Kind.AFTER_JUNCTION
            else -> Kind.JUNCTION
        }
        return zoom
    }

    /** Guidance ended: nothing is remembered of the last junction. */
    fun reset() {
        kind = Kind.ROAD
        junctionZoom = Double.NaN
    }

    /**
     * The zoom at which a lane is [laneDp] wide, within the limit. Where the latitude is not a
     * number no zoom is close enough to ask for, so the road's zoom stays.
     */
    private fun closest(laneDp: Double, latitude: Double): Double {
        val zoom = MapScale.zoomForLaneDp(laneDp, latitude)
        return if (zoom.isNaN()) Double.NEGATIVE_INFINITY else min(zoom, tuning.maxZoom)
    }

    private companion object {
        /** Closer than the road by less than this is still the road, for the log. */
        const val SAME_ZOOM = 0.01
    }
}

/**
 * How many seconds ahead of the moving car the camera center aims, so that the center trails the
 * car by no more than [maxLagDp] on the screen. A center spring of [centerTau] trails a car at
 * [speedMps] by 2 × tau × speed; aimed speed × lead ahead, it trails by (2 × tau - lead) × speed.
 * At the zooms guidance had so far that is within the limit and the lead is zero; close in, the
 * same meters are many more dp.
 */
fun centerLeadSeconds(centerTau: Double, speedMps: Double, metersPerDp: Double, maxLagDp: Double): Double {
    // Also when the speed is not a number.
    if (!(speedMps >= MIN_LEAD_SPEED_MPS)) return 0.0
    return (2.0 * centerTau - maxLagDp * metersPerDp / speedMps).coerceIn(0.0, 2.0 * centerTau)
}

/** A car slower than this is not led: its speed, read from how the drawn car moves, is mostly noise. */
private const val MIN_LEAD_SPEED_MPS = 0.5

/** Distances along a line of points. */
object Polyline {

    /**
     * How far along [points], from the first of them, each of [targets] lies: the distance to the
     * point of the line nearest to it. The targets come in the order they lie along the line, as
     * the intersections of a route step do, so each search starts where the last one ended; a
     * target that lies on a point of the line ends its search there.
     */
    fun distancesTo(points: List<GeoPoint>, targets: List<GeoPoint>): DoubleArray {
        val result = DoubleArray(targets.size)
        if (points.isEmpty()) return result
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) along[i] = along[i - 1] + Geo.meters(points[i - 1], points[i])
        var from = 0
        for (t in targets.indices) {
            val target = targets[t]
            var nearest = from
            var nearestMeters = Double.MAX_VALUE
            for (i in from until points.size) {
                val meters = Geo.meters(points[i], target)
                if (meters < nearestMeters) {
                    nearestMeters = meters
                    nearest = i
                }
                if (meters < ON_POINT_METERS) break
            }
            result[t] = along[nearest]
            from = nearest
        }
        return result
    }

    /** A target this close to a point of the line lies on it: route coordinates are exact to about 0.1 m. */
    private const val ON_POINT_METERS = 1.0
}
