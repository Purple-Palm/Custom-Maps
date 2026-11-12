package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.camera.RunUpLatch
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import kotlin.math.max

/**
 * A stretch of the route where the camera is close and the card shows lanes: from [from] to [to]
 * metres along the route, the last part of the step that ends at the maneuver of [node], the index
 * of its intersection in the route.
 */
data class CardWindow(val from: Double, val to: Double, val kind: Kind, val node: Int) {

    enum class Kind {
        /** The run-up to a freeway exit or fork ([RunUpLatch]). */
        RUN_UP,

        /** The approach to a maneuver the camera frames as a junction, on a road that is no freeway. */
        STREET,
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
