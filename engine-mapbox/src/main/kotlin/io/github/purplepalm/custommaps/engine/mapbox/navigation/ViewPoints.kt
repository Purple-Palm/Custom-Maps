package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.base.utils.DecodeUtils.stepGeometryToPoints
import io.github.purplepalm.custommaps.nav.camera.ApproachKind
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.DriveViewRule
import io.github.purplepalm.custommaps.nav.camera.FocusKind
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.ViewInput
import io.github.purplepalm.custommaps.nav.camera.ViewPoint
import io.github.purplepalm.custommaps.nav.model.ManeuverType

/**
 * The points ahead that the view behind the car is wanted for ([ViewPoint]), with the car's place on
 * the route, for the drive views' planner ([io.github.purplepalm.custommaps.nav.camera.DriveViewPlanner]):
 * read from the route the app holds, with no request and no other service.
 *
 * - Once for each step the car enters, the leg's steps are walked from the end of that step on: each
 *   maneuver the view comes down for ([DriveViewRule.approachKind], the step's own maneuver, never the
 *   banner's) is a point, and the walk goes on past maneuvers it does not come down for, a straight
 *   name change or continue, at most [MOST_STRAIGHT_STEPS] of them. On each step it walks past, the
 *   freeway lane choices that count ([LaneChoice.counts]) are points too, so a lane choice behind a
 *   straight step is seen before its step begins. The arrival always ends the walk; so do the leg's
 *   end and the second point.
 * - On each update only the distances move: the current step's lane choices as the engine reads them
 *   for the camera, and the walked points the step's remaining metres and seconds before them.
 *
 * Each point keeps one key from its first update to its last: a maneuver is keyed by the route, leg
 * and step that end in it, as the camera keys steps; a lane choice by its intersection, as the engine's
 * lane choices key it, so a point walked ahead keeps its key when its step becomes the car's. Each
 * maneuver's [ViewPoint.focus] is the camera's own kind of junction for it, read by the same rule
 * ([ManeuverFactsReader]). A route whose steps cannot be read gives the update's lane choices alone
 * and one report for that step; it never ends the drive. One per engine, so the phone's map and the
 * car's read the same answer for an update whichever asks first. Main thread only.
 */
internal class ViewPoints(
    private val tuning: CameraTuning = CameraTuning.PHONE,
    private val onError: (RuntimeException) -> Unit = {},
) {

    /** A point past the end of the step the walk began on: where it is from that step's end, in metres and seconds. */
    class Walked(
        val key: String,
        val kind: ApproachKind,
        val focus: FocusKind,
        val onFreeway: Boolean,
        val metersPastStep: Double,
        val secondsPastStep: Double,
    )

    private var walkedStep: String? = null
    private var walked: List<Walked> = emptyList()

    private var lastUpdate: Any? = null
    private var lastInput: ViewInput? = null

    /**
     * The planner's input for [progress], the same object for every call with it. [laneChoices] are
     * the current step's points with lanes as the engine reads them ([LaneChoices.ahead]), [speedMps]
     * the car's matched speed, [runUpKey] the exit of the run-up on now and [nowSeconds] the engine's
     * clock.
     */
    fun ahead(progress: RouteProgress, laneChoices: List<LaneChoice>, speedMps: Double?, runUpKey: String?, nowSeconds: Double): ViewInput {
        val route = progress.navigationRoute
        val leg = progress.currentLegProgress
        val step = leg?.currentStepProgress
        return ahead(
            update = progress,
            nowSeconds = nowSeconds,
            routeId = route.id,
            legIndex = leg?.legIndex ?: 0,
            stepIndex = step?.stepIndex,
            routeMeters = progress.distanceTraveled.toDouble(),
            offRoute = progress.currentState == RouteProgressState.OFF_ROUTE,
            speedMps = speedMps,
            metersToManeuver = step?.distanceRemaining?.toDouble() ?: Double.NaN,
            secondsToManeuver = step?.durationRemaining ?: 0.0,
            laneChoices = laneChoices,
            runUpKey = runUpKey,
            steps = { leg?.routeLeg?.steps().orEmpty() },
            stepPoints = { legStep -> route.directionsRoute.stepGeometryToPoints(legStep) },
        )
    }

    /**
     * The rule of [ahead] on plain values: the same object for every call with the same [update]; the
     * walk from the end of step [stepIndex] once for each step, through [steps] and the shape of each
     * step it walks past ([stepPoints]). Without a step there are no points but the lane choices.
     */
    fun ahead(
        update: Any,
        nowSeconds: Double,
        routeId: String,
        legIndex: Int,
        stepIndex: Int?,
        routeMeters: Double,
        offRoute: Boolean,
        speedMps: Double?,
        metersToManeuver: Double,
        secondsToManeuver: Double,
        laneChoices: List<LaneChoice>,
        runUpKey: String?,
        steps: () -> List<LegStep>,
        stepPoints: (LegStep) -> List<Point>,
    ): ViewInput {
        lastInput?.let { if (update === lastUpdate) return it }
        val points = ArrayList<ViewPoint>(MOST_POINTS + laneChoices.size)
        for (i in laneChoices.indices) {
            val choice = laneChoices[i]
            // The lanes of the maneuver itself are the maneuver's own point.
            if (choice.atManeuver || !choice.isAhead || !choice.counts(tuning)) continue
            points.add(ViewPoint(choice.key, ApproachKind.LANE_CHOICE, FocusKind.NONE, choice.onFreeway, choice.metersAhead, share(secondsToManeuver, choice.metersAhead, metersToManeuver)))
        }
        if (stepIndex != null && metersToManeuver.isFinite()) {
            val key = ManeuverFactsReader.stepKey(routeId, legIndex, stepIndex)
            if (key != walkedStep) {
                walkedStep = key
                walked = try {
                    walk(routeId, legIndex, steps(), stepIndex, stepPoints, tuning)
                } catch (e: RuntimeException) {
                    onError(e)
                    emptyList()
                }
            }
            for (point in walked) {
                points.add(ViewPoint(point.key, point.kind, point.focus, point.onFreeway, metersToManeuver + point.metersPastStep, secondsToManeuver + point.secondsPastStep))
            }
        }
        points.sortBy { it.metersAhead }
        val input = ViewInput(
            nowSeconds = nowSeconds,
            routeId = routeId,
            routeMeters = routeMeters,
            offRoute = offRoute,
            speedMps = speedMps,
            points = if (points.size > MOST_POINTS) points.subList(0, MOST_POINTS).toList() else points,
            runUpKey = runUpKey,
        )
        lastUpdate = update
        lastInput = input
        return input
    }

    /** A drive ended or a new one begins: nothing of the last walk is kept. */
    fun clear() {
        walkedStep = null
        walked = emptyList()
        lastUpdate = null
        lastInput = null
    }

    companion object {
        /** The planner looks at the next point and the one after it. */
        const val MOST_POINTS = 2

        /** The walk goes past at most this many maneuvers the view does not come down for. */
        const val MOST_STRAIGHT_STEPS = 12

        /**
         * The points past the end of step [stepIndex] of a leg's [steps], nearest first, at most
         * [MOST_POINTS]: the maneuver that ends that step and those after it that the view comes down
         * for, and on each step the walk goes past its freeway lane choices that count, by the shape of
         * that step ([stepPoints]). Past at most [MOST_STRAIGHT_STEPS] maneuvers it does not come down
         * for; the arrival and the leg's end end the walk.
         */
        fun walk(
            routeId: String,
            legIndex: Int,
            steps: List<LegStep>,
            stepIndex: Int,
            stepPoints: (LegStep) -> List<Point>,
            tuning: CameraTuning,
        ): List<Walked> {
            val points = ArrayList<Walked>(MOST_POINTS + 1)
            // From the end of the walk's first step to the start of step [index].
            var meters = 0.0
            var seconds = 0.0
            var straight = 0
            var index = stepIndex
            while (points.size < MOST_POINTS) {
                val step = steps.getOrNull(index) ?: break
                val upcoming = steps.getOrNull(index + 1) ?: break
                if (index > stepIndex) {
                    // The lane choices of a step the walk goes past, read as the engine reads the car's step.
                    for (lanes in stepLanes(step.intersections().orEmpty(), stepPoints(step))) {
                        val choice = LaneChoice(
                            key = "${ManeuverFactsReader.stepKey(routeId, legIndex, index)}/${lanes.index}",
                            metersAhead = meters + lanes.metersFromStepStart,
                            laneCount = lanes.counts.lanes,
                            usableLanes = lanes.counts.usable,
                            designatedLanes = lanes.counts.designated,
                            onFreeway = lanes.onFreeway,
                            atManeuver = false,
                        )
                        if (!choice.counts(tuning)) continue
                        points.add(Walked(choice.key, ApproachKind.LANE_CHOICE, FocusKind.NONE, choice.onFreeway, choice.metersAhead, seconds + share(step.duration(), lanes.metersFromStepStart, step.distance())))
                    }
                    meters += step.distance()
                    seconds += step.duration()
                }
                val maneuver = upcoming.maneuver()
                val type = maneuver.type()?.let(ManeuverMapping::type)
                val kind = type?.let { DriveViewRule.approachKind(it, ManeuverMapping.modifier(maneuver.modifier())) }
                if (kind != null) {
                    points.add(
                        Walked(
                            key = ManeuverFactsReader.stepKey(routeId, legIndex, index),
                            kind = kind,
                            // The camera's own kind of junction for it, by the same rule.
                            focus = ManeuverFactsReader.factsOf(routeId, legIndex, steps, index)?.kind ?: FocusKind.NONE,
                            onFreeway = step.intersections()?.lastOrNull()?.isFreeway() == true,
                            metersPastStep = meters,
                            secondsPastStep = seconds,
                        ),
                    )
                    if (type == ManeuverType.ARRIVE) break
                } else if (++straight > MOST_STRAIGHT_STEPS) {
                    break
                }
                index += 1
            }
            points.sortBy { it.metersPastStep }
            return if (points.size > MOST_POINTS) points.subList(0, MOST_POINTS).toList() else points
        }

        /** [whole]'s share of a stretch [part] metres into [length] metres; 0 where the length is none. */
        private fun share(whole: Double, part: Double, length: Double): Double =
            if (length > 0.0 && part.isFinite()) whole * (part / length).coerceIn(0.0, 1.0) else 0.0
    }
}
