package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.camera.RunUpLatch
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of the route where the camera is close and the card shows lanes: from [from] to [to]
 * metres along the route, the last part of the step that ends at the maneuver of [node], the index
 * of its intersection in the route; for a [Kind.RAMP] window the ramp from a gore to the point
 * [node].
 */
data class CardWindow(val from: Double, val to: Double, val kind: Kind, val node: Int) {

    enum class Kind {
        /** The run-up to a freeway exit or fork ([RunUpLatch]). */
        RUN_UP,

        /** The approach to a maneuver the camera frames as a junction, on a road that is no freeway. */
        STREET,

        /**
         * The ramp after a run-up's gore, held to the next point the lane plan constrains there, a split or a
         * maneuver with lane data, when it lies near ([LanePlanner.RAMP_BRIDGE_SECONDS]): [node] is that point's
         * intersection. Only the lane plan makes these.
         */
        RAMP,
    }

    val meters: Double get() = to - from
}

/**
 * Where the card windows of a route lie, worked out once for the route at its planning speeds
 * ([PlanningSpeed]), never from the car's speed now:
 * - a run-up for every step whose exit or fork counts as a lane choice, by the rule the lane choices
 *   apply once a step ([LaneChoice.counts] for the maneuver's own lanes, on a freeway approach): from
 *   the run-up's reach at the approach's planning speed ([RunUpLatch.reachMeters]: the close view's
 *   window, [LaneFocus.windowMeters], plus [CameraTuning.runUpSeconds] of driving, at least
 *   [CameraTuning.runUpMinMeters]) to the gore;
 * - a street window over the last [CameraTuning.maneuverFramingMeters] before every other maneuver
 *   the camera frames as a junction, on an approach that is no freeway: the navigation SDK's framing.
 *
 * A window never reaches back past the start of its step: the car sees the step's maneuver only once
 * it is on that step. Pure; the debug log counts the route's lane gaps inside them.
 */
object CardWindows {

    fun of(route: LaneRoute, tuning: CameraTuning = CameraTuning.PHONE): List<CardWindow> {
        val nodes = route.nodes
        val windows = ArrayList<CardWindow>()
        var stepStart = 0.0
        for (m in nodes.indices) {
            val node = nodes[m]
            val maneuver = node.maneuver ?: continue
            if (m > 0) {
                val approach = nodes[m - 1]
                val window = when {
                    runUpCounts(node, approach, tuning) ->
                        CardWindow(node.station - RunUpLatch.reachMeters(tuning, PlanningSpeed.of(approach)), node.station, CardWindow.Kind.RUN_UP, m)
                    framesStreetManeuver(maneuver, approach) ->
                        CardWindow(node.station - tuning.maneuverFramingMeters, node.station, CardWindow.Kind.STREET, m)
                    else -> null
                }
                if (window != null) {
                    val clipped = window.copy(from = max(stepStart, window.from))
                    if (clipped.meters > 0.0) windows.add(clipped)
                }
            }
            stepStart = node.station
        }
        return windows
    }

    /**
     * The card windows of [route] as the card and the close view show them, at the planning speeds: a
     * street window as in [of], and a run-up from where the run-up latch ([RunUpLatch]) begins it for a
     * car at the approach's planning speed, not from its reach:
     * - at the reach, or the step's start where that is nearer, when a point of the step ahead of it is
     *   one where the lane at the exit's side leaves ([RunUpRule.edgeLeaves]), or when the run-up of the
     *   step before ended at its gore and the step is no longer than the reach plus
     *   [CameraTuning.runUpResumeSeconds] of driving;
     * - else from the close view's window ([LaneFocus.windowMeters]) before the farthest point of the step
     *   that counts as a lane choice, the exit itself at the latest, never before the reach.
     *
     * The lane plan names a lane inside these windows only.
     */
    fun latched(route: LaneRoute, tuning: CameraTuning = CameraTuning.PHONE): List<CardWindow> {
        val nodes = route.nodes
        val windows = ArrayList<CardWindow>()
        var stepStart = 0.0
        var stepIndex = 0
        var runUpBefore = false
        for (m in nodes.indices) {
            val node = nodes[m]
            val maneuver = node.maneuver ?: continue
            var runUp = false
            if (m > 0) {
                val approach = nodes[m - 1]
                if (runUpCounts(node, approach, tuning)) {
                    runUp = true
                    val speed = PlanningSpeed.of(approach)
                    val reach = RunUpLatch.reachMeters(tuning, speed)
                    val window = LaneFocus.windowMeters(tuning, speed)
                    val stepMeters = node.station - stepStart
                    val first = min(reach, stepMeters)
                    val edge = RunUpRule.exitEdge(maneuver.modifier, node.lanes)
                    val onStep = (stepIndex + 1 until m).filter { nodes[it].isLanePoint && node.station - nodes[it].station <= first }
                    val edgeAhead = onStep.any { RunUpRule.edgeLeaves(nodes[it].lanes, edge) }
                    val resumes = runUpBefore && stepMeters <= reach + speed * tuning.runUpResumeSeconds
                    val begin = if (edgeAhead || resumes) {
                        first
                    } else {
                        var farthest = window
                        for (p in onStep) if (pointCounts(nodes[p], nodes[p - 1], tuning)) farthest = max(farthest, node.station - nodes[p].station + window)
                        min(first, farthest)
                    }
                    if (begin > 0.0) windows.add(CardWindow(node.station - begin, node.station, CardWindow.Kind.RUN_UP, m))
                } else if (framesStreetManeuver(maneuver, approach)) {
                    val from = max(stepStart, node.station - tuning.maneuverFramingMeters)
                    if (node.station > from) windows.add(CardWindow(from, node.station, CardWindow.Kind.STREET, m))
                }
            }
            runUpBefore = runUp
            stepStart = node.station
            stepIndex = m
        }
        return windows
    }

    /**
     * Whether the point [point], reached from [approach], where the route goes straight on, counts as a
     * lane choice as the lane choices count it ([LaneChoice.counts]).
     */
    fun pointCounts(point: RouteNode, approach: RouteNode, tuning: CameraTuning = CameraTuning.PHONE): Boolean {
        var open = 0
        var usable = 0
        var designated = 0
        for (lane in point.lanes) {
            if (lane.designated) {
                designated++
                continue
            }
            open++
            if (lane.usable) usable++
        }
        return LaneChoice("", 0.0, open, usable, designated, onFreeway = approach.freeway, atManeuver = false).counts(tuning)
    }

    /**
     * Whether the exit or fork at [exit], reached from [approach], counts as a lane choice at the
     * maneuver, as the lane choices count it: a maneuver the camera stays up for, with lanes, on a
     * freeway approach, with at least three lanes open to every car of which some can be used and
     * some leave the route.
     */
    fun runUpCounts(exit: RouteNode, approach: RouteNode, tuning: CameraTuning = CameraTuning.PHONE): Boolean {
        if (exit.maneuver?.viewStaysUp != true || !exit.isLanePoint) return false
        var open = 0
        var usable = 0
        var designated = 0
        for (lane in exit.lanes) {
            if (lane.designated) {
                designated++
                continue
            }
            open++
            if (lane.usable) usable++
        }
        val choice = LaneChoice(
            key = "",
            metersAhead = 0.0,
            laneCount = open,
            usableLanes = usable,
            designatedLanes = designated,
            onFreeway = approach.freeway,
            atManeuver = true,
        )
        return choice.counts(tuning)
    }

    /**
     * Whether the camera frames the maneuver [maneuver], reached from [approach], as a street
     * junction: one it does not stay up for, neither the departure nor the arrival, on an approach
     * that is no freeway.
     */
    fun framesStreetManeuver(maneuver: NodeManeuver, approach: RouteNode): Boolean =
        !maneuver.viewStaysUp &&
            maneuver.type != ManeuverType.DEPART &&
            maneuver.type != ManeuverType.ARRIVE &&
            approach.road != RoadKind.FREEWAY

    /** How many metres of the stretch from [from] to [to] lie inside [windows]; each metre counts once. */
    fun metersInside(windows: List<CardWindow>, from: Double, to: Double): Double {
        if (to <= from || windows.isEmpty()) return 0.0
        var inside = 0.0
        var reached = from
        for (window in windows.sortedBy { it.from }) {
            val start = max(window.from, reached)
            val end = minOf(window.to, to)
            if (end > start) {
                inside += end - start
                reached = end
            }
        }
        return inside
    }
}
