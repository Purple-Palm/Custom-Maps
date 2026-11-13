package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.camera.RunUpLatch
import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/**
 * The maneuver card's lane row along a made-up route ([LaneScenes]) for a car at its planning speed, as the
 * engine's lane choices make it (engine `LaneChoices`: the points of each step, the one run-up latch, the
 * run-up's row from [RunUpRule], a lane choice's row within its close view's window) and as [CardLanes]
 * picks one; the next maneuver's own row lights the lanes the route marks, as the navigation SDK's banner
 * does. With a [plan], as the engine has it once the route's plan is held: the latch begins no later than the
 * plan's seam ([LanePlan.seams]) and holds through the ramps the plan holds through ([LanePlan.rampAfter]), with the
 * split's row on the ramp, and the run-up's row is read from the plan ([CardPlanRow]). Keys are the route's node
 * indices. Test code only.
 */
internal class CardDrive(private val route: LaneRoute, private val tuning: CameraTuning = CameraTuning.PHONE, private val plan: LanePlan? = null) {

    /** A row the card began to show with the car at [station]: of the lane point [node], with [target] as its words. */
    data class Shown(val station: Double, val node: Int, val row: CardPlanAgreement.Row, val target: LaneTarget?, val odd: String?)

    private val nodes = route.nodes
    private val maneuvers = nodes.indices.filter { nodes[it].maneuver != null }

    /**
     * Every row the card shows as the car drives the route from its start, one update each [every] metres, each when it
     * begins; with [everyUpdate], the row of every update.
     */
    fun drive(every: Double = 1.0, everyUpdate: Boolean = false): List<Shown> {
        val latch = RunUpLatch(tuning)
        val shown = ArrayList<Shown>()
        var cardKey: Int? = null
        var last: Triple<CardLanes.Source, Int, Pair<List<Lane>, LaneTarget?>>? = null
        var station = 0.0
        while (station < route.lengthMeters) {
            val k = maneuvers.indexOfLast { nodes[it].station <= station }
            val stepFirst = maneuvers[k]
            val exitNode = maneuvers.getOrNull(k + 1)
            if (exitNode == null) break
            val speed = PlanningSpeed.of(nodes[nodes.indices.last { nodes[it].station <= station }])
            val now = station / speed
            val stepStart = nodes[stepFirst].station
            val exit = nodes[exitNode]
            val edge = RunUpRule.exitEdge(exit.maneuver?.modifier, exit.lanes)
            val points = (stepFirst + 1 until exitNode).filter { nodes[it].isLanePoint }
            val choices = ArrayList<LaneChoice>()
            for (p in points) {
                val ahead = nodes[p].station - station
                if (ahead >= 0.0) choices.add(choice(p, ahead, nodes[p - 1].freeway, atManeuver = false, edgeLeaves = RunUpRule.edgeLeaves(nodes[p].lanes, edge)))
            }
            val toExit = exit.station - station
            if (exit.isLanePoint && exit.maneuver?.viewStaysUp == true && toExit >= 0.0) {
                choices.add(choice(exitNode, toExit, nodes[exitNode - 1].freeway, atManeuver = true, edgeLeaves = false))
            }
            // As the engine hands the latch the ramp its plan holds through after the gore of the exit it holds, and where the
            // plan begins the step's exit's run-up.
            val ramp = latch.exitKey?.toIntOrNull()?.let { plan?.rampAfter(it) }
            val seam = plan?.seams?.firstOrNull { it.node == exitNode }?.let { exit.station - it.from } ?: Double.NaN
            val held = if (plan == null) {
                latch.onProgress(now, choices, speed, onRoute = true)
            } else {
                latch.onProgress(
                    now, choices, speed, onRoute = true, routeMeters = station,
                    rampMeters = ramp?.meters ?: Double.NaN, rampMps = ramp?.planningMps ?: Double.NaN, seamMeters = seam,
                )
            }
            var source = CardLanes.Source.NONE
            var node = -1
            var lanes: List<Lane> = emptyList()
            var target: LaneTarget? = null
            var odd: String? = null
            if (held != null || latch.rampAhead >= 0.0) {
                val ahead = points.filter { nodes[it].station - station >= 0.0 }.map { RunUpRule.LanePoint(it.toString(), nodes[it].station - station, nodes[it].lanes) }
                val branchAhead = (stepFirst + 1 until exitNode).any { b ->
                    edge != null && !nodes[b].isLanePoint && nodes[b - 1].freeway && LaneEvidence.branchesOff(nodes[b], edge) && nodes[b].station - station >= 0.0
                }
                val rule = RunUpRule.row(edge, ahead, exitNode.toString(), exit.lanes, branchAhead)
                val row = if (rule == null || plan == null) rule else CardPlanRow.of(rule, plan, plan.topology.points.first { it.node == rule.key.toInt() }, station)
                if (row != null) {
                    source = CardLanes.Source.RUN_UP
                    node = row.key.toInt()
                    lanes = row.lanes.lanes
                    target = row.target
                    odd = row.odd
                }
                cardKey = null
            } else {
                val window = LaneFocus.windowMeters(tuning, speed)
                val nearest = choices.filter { !it.atManeuver && it.counts(tuning) && it.isAhead && (it.metersAhead <= window || it.key.toInt() == cardKey) }
                    .minByOrNull { it.metersAhead }
                cardKey = nearest?.key?.toInt()
                if (nearest != null) {
                    source = CardLanes.Source.CHOICE
                    node = nearest.key.toInt()
                    lanes = marked(nodes[node].lanes)
                } else if (exit.isLanePoint) {
                    source = CardLanes.Source.MANEUVER
                    node = exitNode
                    lanes = marked(exit.lanes)
                }
            }
            val now2 = Triple(source, node, lanes to target)
            if (source != CardLanes.Source.NONE && (now2 != last || everyUpdate)) {
                shown.add(Shown(station, node, CardPlanAgreement.Row(source, lanes, words = target != null, exact = odd != RunUpRule.ODD_COMPOSED), target, odd))
            }
            last = now2
            station += every
        }
        return shown
    }

    private fun choice(p: Int, ahead: Double, onFreeway: Boolean, atManeuver: Boolean, edgeLeaves: Boolean): LaneChoice {
        val lanes = nodes[p].lanes
        val open = lanes.count { !it.designated }
        return LaneChoice(
            key = p.toString(),
            metersAhead = ahead,
            laneCount = open,
            usableLanes = lanes.count { it.usable },
            designatedLanes = lanes.size - open,
            onFreeway = onFreeway,
            atManeuver = atManeuver,
            edgeLeaves = edgeLeaves,
        )
    }

    /**
     * The lanes of a point as the card draws a lane choice's or the maneuver's own row: the lanes the route
     * marks lit, a lane kept for car pools and the like only where it marks no lane open to every car.
     */
    private fun marked(lanes: List<RouteLane>): List<Lane> {
        val openMarked = lanes.any { !it.designated && it.marked }
        return lanes.map { lane ->
            val on = lane.marked && (!lane.designated || !openMarked)
            Lane(lane.indications, isRecommended = on, activeIndication = if (on) lane.validIndication ?: lane.indications.minByOrNull(::sharpness) ?: TurnModifier.STRAIGHT else null, designated = lane.designated)
        }
    }

    private fun sharpness(turn: TurnModifier): Int = when (turn) {
        TurnModifier.STRAIGHT -> 0
        TurnModifier.SLIGHT_LEFT, TurnModifier.SLIGHT_RIGHT -> 1
        TurnModifier.LEFT, TurnModifier.RIGHT -> 2
        TurnModifier.SHARP_LEFT, TurnModifier.SHARP_RIGHT -> 3
        TurnModifier.U_TURN -> 4
    }

    companion object {
        /** The offsets of [indices] (into [lanes]) from [edge] among the lanes open to every car, as the prototype counts them. */
        fun offsets(lanes: List<Lane>, indices: List<Int>, edge: LaneEdge): List<Int> {
            val open = lanes.indices.filter { !lanes[it].designated }
            val order = if (edge == LaneEdge.RIGHT) open.asReversed() else open
            return indices.map { order.indexOf(it) }.sorted()
        }
    }
}
