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
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LanesAhead

/**
 * The points with lanes ahead of the car, read from the lane data the route carries for its
 * intersections. No request and no other service is involved.
 *
 * The maneuver card shows the lanes of the next maneuver only. An intersection the route goes
 * straight through has lanes of its own, for example where a freeway splits or where two lanes
 * turn into an exit; those are read here, and so is the next maneuver where it is an exit or a
 * fork. Whether a point is worth a close view is [LaneChoice.skipReason]'s decision: every point
 * with lanes is handed out, so the ones passed over can be logged with their lane counts. The
 * maneuver card gets the lanes of a lane choice ahead from here as well ([lanesAhead]).
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
    )

    private var stepKey: String? = null
    private var points: List<LanePoint> = emptyList()

    /** The next maneuver's own lanes; whether it is a lane choice depends on the kind of maneuver. */
    private var maneuverPoint: LanePoint? = null

    private var lastProgress: RouteProgress? = null
    private var lastAhead: List<LaneChoice> = emptyList()

    /** The choice whose lanes the card shows, and what was handed out for it at the last update. */
    private var cardKey: String? = null
    private var cardLanes: LanesAhead? = null

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
        // Off the route its lanes are not the car's, and its distances are stale until a new route comes.
        if (progress.currentState == RouteProgressState.OFF_ROUTE) return emptyList()
        val leg = progress.currentLegProgress ?: return emptyList()
        val stepProgress = leg.currentStepProgress ?: return emptyList()
        val key = "${progress.navigationRoute.id}/${leg.legIndex}/${stepProgress.stepIndex}"
        if (key != stepKey) {
            load(
                stepKey = key,
                intersections = stepProgress.step?.intersections().orEmpty(),
                stepPoints = stepProgress.stepPoints.orEmpty(),
                maneuver = leg.upcomingStep?.intersections()?.firstOrNull(),
            )
        }
        return choices(
            metersOnStep = stepProgress.distanceTraveled.toDouble(),
            metersToManeuver = stepProgress.distanceRemaining.toDouble(),
            maneuverIsChoice = isLaneChoiceManeuver(progress.bannerInstructions?.primary()?.type()),
        )
    }

    /**
     * Reads the lanes of a step: of each intersection after its first, which is the maneuver the
     * step began with, and of [maneuver], the first intersection of the next step. Lane data that
     * cannot be read leaves the step without lane choices; it never ends the drive.
     *
     * @param stepKey the route, leg and step, as in the progress snapshot
     * @param stepPoints the shape of the step; each intersection lies on one of its points
     */
    fun load(stepKey: String, intersections: List<StepIntersection?>, stepPoints: List<Point>, maneuver: StepIntersection?) {
        this.stepKey = stepKey
        points = emptyList()
        maneuverPoint = null
        try {
            points = stepLanes(intersections, stepPoints).map {
                LanePoint("$stepKey/${it.index}", it.metersFromStepStart, it.counts, it.onFreeway, intersections[it.index]?.lanesAhead())
            }
            val counts = maneuver?.laneCounts()
            if (counts != null) {
                // The road that leads to the maneuver leaves the step's last intersection. The card
                // has the maneuver's lanes already, so none are kept for it here.
                val approach = intersections.lastOrNull() ?: maneuver
                maneuverPoint = LanePoint("$stepKey/end", Double.NaN, counts, approach.isFreeway(), lanes = null)
            }
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

    /** A drive ended or a new one begins: nothing is kept of the last step. */
    fun clear() {
        stepKey = null
        points = emptyList()
        maneuverPoint = null
        lastProgress = null
        lastAhead = emptyList()
        cardKey = null
        cardLanes = null
    }

    private fun LanePoint.toChoice(metersAhead: Double, atManeuver: Boolean) = LaneChoice(
        key = key,
        metersAhead = metersAhead,
        laneCount = counts.lanes,
        usableLanes = counts.usable,
        designatedLanes = counts.designated,
        onFreeway = onFreeway,
        atManeuver = atManeuver,
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
    val lanes = ArrayList<Lane>(all.size)
    for (lane in all) {
        if (lane == null) continue
        val usable = !lane.isDesignated() && lane.isUsable()
        lanes.add(ManeuverMapping.laneAhead(lane.indications().orEmpty(), usable, lane.validIndication()))
    }
    return if (lanes.isEmpty()) null else LaneInfo(lanes)
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

/** A coordinate outside the globe is no place to measure from. */
private fun Point.toGeoPoint(): GeoPoint? {
    val latitude = latitude()
    val longitude = longitude()
    return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude, longitude) else null
}

/** Whether the road that leaves this intersection, the one the car is on until the next, is a freeway. */
internal fun StepIntersection.isFreeway(): Boolean =
    classes()?.contains(MOTORWAY_CLASS) == true || mapboxStreetsV8()?.roadClass() in FREEWAY_ROAD_CLASSES

private const val MOTORWAY_CLASS = "motorway"
private val FREEWAY_ROAD_CLASSES = setOf("motorway", "trunk")
