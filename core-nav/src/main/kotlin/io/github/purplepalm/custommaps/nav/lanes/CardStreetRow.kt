package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/**
 * The maneuver card's own row at a maneuver on a street, lit from the lane plan (the own lanes' design 6, E12): where the
 * route has lanes at a maneuver it reaches on a street and the plan names a lane there, the row lights the plan's amber set
 * at the maneuver's station, the lanes that work for it with the one the map lights among them, so that the card and the map
 * light the same lanes ([CardPlanAgreement]'s rules 1 and 2). Elsewhere the navigation SDK's row stands: on a freeway or a
 * ramp, where the route has no lanes at the maneuver, where the plan names none there (an approach where none can be, C1:
 * a roundabout, lanes kept for others alone), and where the SDK's row has another count of lanes than the route's.
 * - The row is the SDK's, lane for lane with its arrows; only which lanes are lit changes, and a lit lane shows the arrow
 *   the route takes from it.
 * - A lane kept for buses, car pools and the like is drawn dim and is never lit while a lane open to every car is in the
 *   set; a lane only for bicycles is drawn dim and never lit: it is no lane for a car.
 * - The row has no words, as a maneuver's own row never has.
 *
 * Per progress update: the plan at the maneuver's lane point, one search that holds while the point does; one instance for a
 * drive hands out the same row object while it does not change, so a screen skips it. Nothing in it throws on odd lanes. Not
 * thread-safe: the engine's main thread.
 */
class CardStreetRow {

    /** The row of the last update, handed out again while it is the same. */
    private var last: LaneInfo? = null

    /** The point whose plan is held in [pointPlan], and that plan: the point changes once a maneuver. */
    private var pointKey: Pair<LanePlan, Int>? = null
    private var pointPlan: PlanSegment? = null

    /**
     * The card's row of the maneuver whose lanes the navigation SDK reads as [sdk], with [point] the maneuver's lane point in
     * [plan], the plan of the update's route (either null where the route has no lanes there or no plan of it is held): lit
     * from the plan at a maneuver on a street, else [sdk]; the same object as the last update's while equal.
     */
    fun row(sdk: LaneInfo?, plan: LanePlan?, point: LaneTopology.Point?): LaneInfo? {
        val row = if (sdk == null || plan == null || point == null) sdk else of(sdk, plan, point, segmentAtPoint(plan, point))
        if (row != last) last = row
        return last
    }

    /** A drive ended or a new one begins: nothing of the last one is kept. */
    fun reset() {
        last = null
        pointKey = null
        pointPlan = null
    }

    private fun segmentAtPoint(plan: LanePlan, point: LaneTopology.Point): PlanSegment? {
        val key = plan to point.node
        if (key != pointKey) {
            pointKey = key
            pointPlan = plan.segmentAt(point.station)
        }
        return pointPlan
    }

    companion object {
        /**
         * The card's row of the maneuver at [point] of [plan], whose lanes the navigation SDK reads as [sdk], with the plan
         * there [atPoint]: [sdk] lit on the plan's amber set at a maneuver the route reaches on a street, else [sdk] itself.
         */
        fun of(sdk: LaneInfo, plan: LanePlan, point: LaneTopology.Point, atPoint: PlanSegment? = plan.segmentAt(point.station)): LaneInfo {
            val route = plan.topology.evidence.route
            val node = route.nodes.getOrNull(point.node) ?: return sdk
            if (route.approachRoad(point.node) != RoadKind.STREET || atPoint == null || atPoint.noLane != null) return sdk
            val lanes = node.lanes
            if (lanes.size != sdk.lanes.size || lanes.size != point.lanes.size) return sdk
            // The plan's lanes that work there, never one only for bicycles; those kept for others only where no open lane is.
            val amber = CardPlanRow.amber(point, atPoint).filter { !bicycle(lanes[it]) }
            val lit = amber.filter { !lanes[it].designated }.ifEmpty { amber }
            if (lit.isEmpty()) return sdk
            val turn = node.maneuver?.modifier
            return LaneInfo(
                sdk.lanes.mapIndexed { i, shown ->
                    val dim = shown.designated || lanes[i].designated
                    if (i in lit) {
                        shown.copy(isRecommended = true, activeIndication = arrow(shown, lanes[i], turn), designated = dim)
                    } else {
                        shown.copy(isRecommended = false, activeIndication = null, designated = dim)
                    }
                },
            )
        }

        /** Whether [lane] is kept for bicycles alone. */
        internal fun bicycle(lane: RouteLane): Boolean = lane.kinds == setOf(DesignatedKind.BICYCLE)

        /**
         * The arrow the route takes from the lit lane [shown] the route lists as [lane], at a maneuver that turns [turn]: the
         * route's own for the lane, the SDK's where it lit the lane, the maneuver's where the lane has it, its only arrow, its
         * straight arrow, else its first one; straight on for a lane without arrows.
         */
        private fun arrow(shown: Lane, lane: RouteLane, turn: TurnModifier?): TurnModifier {
            val arrows = shown.indications
            return lane.validIndication?.takeIf { it in arrows }
                ?: shown.activeIndication
                ?: turn?.takeIf { it in arrows }
                ?: arrows.singleOrNull()
                ?: TurnModifier.STRAIGHT.takeIf { it in arrows }
                ?: arrows.firstOrNull()
                ?: TurnModifier.STRAIGHT
        }
    }
}
