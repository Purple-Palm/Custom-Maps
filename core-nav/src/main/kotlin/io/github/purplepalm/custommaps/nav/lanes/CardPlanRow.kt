package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.RunUpLanes

/**
 * The maneuver card's run-up row read from the lane plan (the design's 5.2 and 6), with every choice of who draws the
 * lanes on the map: the map, the card and the words name one lane.
 *
 * The interim's rule ([RunUpRule.row]) still picks the row's point, the next point on the way where the lane at the
 * exit's side leaves or the exit itself, and composes the row from the route's lanes; here the plan lights it, and
 * [RunUpRule.drawRow] draws it and words it again:
 * - **The lit lanes** at the row's point are the plan's: the lane it names there where the rule lights one lane, its
 *   amber set (the named lane and the lanes that still work) where the rule lights the exit's lanes to use.
 * - **Outlines**: a lane of the point that the map does not draw where the car is yet, one that opens on the way or may
 *   be there only from farther on, is an outline, never lit and never counted in the words. The lane the plan names at
 *   the point never is one: the lane the map lights flows into it along its taper.
 * - **Words** are the rule's, for the lit lanes counted without the outlines, and only while the plan names a lane at
 *   the car and at the point, no road without lane data leaves on the exit's side before the exit
 *   ([RunUpRule.ODD_UNKNOWN_AHEAD]), between the car and the point no lane on the exit's side opens or may begin, also
 *   one gone again by the point, and the named lane counts the same from the exit's edge at the car, every lane drawn
 *   there counted, as at the point ([RunUpRule.ODD_OPENS_AHEAD]). Where the plan names no lane at the car or the point,
 *   as before its card window, the rule's lanes stay lit, without words ([RunUpRule.ODD_NOT_NAMED]).
 * - Where no lane can be named on the approach (C1) the rule's lanes stay lit as the interim card has them, without
 *   words: the plan names no lane there, so neither do the words; so where the plan says nothing of the point.
 *
 * Per progress update: the plan at the car is the engine's one lookup for the update; here the plan at the point (one
 * search, the same answer while the point is), and a pass over the lanes of the point's run and over the roads without
 * lane data. Nothing in it throws on odd lanes; one instance per drive keeps the row the same object while it does not
 * change, so a screen skips it. Not thread-safe: the engine's main thread.
 */
class CardPlanRow {

    /** The row of the last update, handed out again while it is the same. */
    private var last: RunUpLanes? = null

    /** The point whose plan is held in [pointPlan], and that plan: the point changes a few times a run-up. */
    private var pointKey: Pair<LanePlan, Int>? = null
    private var pointPlan: PlanSegment? = null

    /**
     * The card's row for [rule], the interim's row of this update, with the car at [car] on the route of [plan], where
     * the plan is [atCar], and [point] the row's point in the plan; the same object as the last update's while equal.
     * Without a [plan] the rule's row stands, without its words while [planComing], the plan of the update's route on
     * its way: a plan that could not be made costs only the plan's reading. With a plan whose route has no such point,
     * the rule's lanes without words. Null without a rule row.
     */
    fun row(rule: RunUpLanes?, plan: LanePlan?, point: LaneTopology.Point?, car: Double, atCar: PlanSegment?, planComing: Boolean = false): RunUpLanes? {
        val row = when {
            rule == null -> null
            plan == null -> if (planComing) waiting(rule) else rule
            point == null -> waiting(rule)
            else -> of(rule, plan, point, car, atCar, segmentAtPoint(plan, point))
        }
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
         * The card's row for [rule] read from [plan] with the car at [car] (the plan there [atCar]) and [point] the row's
         * point in the plan, the plan there [atPoint].
         */
        fun of(
            rule: RunUpLanes,
            plan: LanePlan,
            point: LaneTopology.Point,
            car: Double,
            atCar: PlanSegment? = plan.segmentAt(car),
            atPoint: PlanSegment? = plan.segmentAt(point.station),
        ): RunUpLanes {
            if (atPoint == null || atPoint.noLane != null || !car.isFinite()) return waiting(rule)
            val topology = plan.topology
            val lanes = topology.evidence.route.nodes.getOrNull(point.node)?.lanes ?: return waiting(rule)
            if (lanes.size != rule.lanes.lanes.size || lanes.size != point.lanes.size) return waiting(rule)
            val named = named(point, atPoint)
            val outline = outline(plan, point, named, car).toSet()
            val ruleLit = rule.lanes.lanes.indices.filter { rule.lanes.lanes[it].isRecommended }
            val lit = when {
                named == null -> ruleLit
                ruleLit.size <= 1 -> listOf(named)
                else -> amber(point, atPoint).ifEmpty { ruleLit }
            }
            val edge = rule.edge ?: atPoint.edge
            // The rule's words vouch for its own lanes only: where the plan lights others, the two readings differ, and the
            // careful side is no words.
            val exact = rule.odd != RunUpRule.ODD_COMPOSED && lit.filter { it !in outline } == ruleLit.filter { it !in outline }
            val drawn = RunUpRule.drawRow(lanes, lit, outline, edge, exact)
            val namedAtCar = atCar?.takeIf { it.run == atPoint.run }?.named
            val (target, odd) = when {
                drawn.target == null -> null to when {
                    rule.odd == RunUpRule.ODD_DESIGNATED_ONLY -> rule.odd
                    !exact -> RunUpRule.ODD_COMPOSED
                    drawn.inPieces -> RunUpRule.ODD_PIECES
                    else -> null
                }
                named == null || namedAtCar == null -> null to RunUpRule.ODD_NOT_NAMED
                unknownAhead(plan, car) -> null to RunUpRule.ODD_UNKNOWN_AHEAD
                opensAhead(plan, point, atPoint, car, atCar) -> null to RunUpRule.ODD_OPENS_AHEAD
                else -> drawn.target to null
            }
            val need = if (edge == null) rule.need else needOf(lanes, drawn, outline, edge)
            return RunUpLanes(drawn.lanes, target, rule.key, odd, need, edge)
        }

        /** [rule] without its words, where the plan names no lane for it or is on its way ([RunUpRule.ODD_NOT_NAMED]). */
        fun waiting(rule: RunUpLanes): RunUpLanes =
            if (rule.target == null) rule else rule.copy(target = null, odd = RunUpRule.ODD_NOT_NAMED)

        /** The index in [point]'s lanes of the lane the plan names there; null where it names none of them. */
        internal fun named(point: LaneTopology.Point, atPoint: PlanSegment?): Int? =
            atPoint?.named?.let { point.lanes.indexOf(it) }?.takeIf { it >= 0 }

        /** The plan's amber set at [point]: the lanes that work there, the named one among them, as indices into its lanes. */
        internal fun amber(point: LaneTopology.Point, atPoint: PlanSegment?): List<Int> =
            atPoint?.band.orEmpty().mapNotNull { id -> point.lanes.indexOf(id).takeIf { it >= 0 } }.sorted()

        /**
         * The lanes of [point] a drawing does not show at [car] yet, as indices into its lanes: those not there, and not
         * possible there either; never [named], the lane the plan names at the point.
         */
        internal fun outline(plan: LanePlan, point: LaneTopology.Point, named: Int?, car: Double): List<Int> =
            point.lanes.indices.filter { it != named && !drawnAt(plan.topology.lanes.getOrNull(point.lanes[it]), plan, car) }

        /** Whether a drawing shows [lane] at [station]: where it is, or may already be and the plan does not name it (P8 with 2a). */
        internal fun drawnAt(lane: LaneTopology.Lane?, plan: LanePlan, station: Double): Boolean {
            if (lane == null) return false
            if (lane.exists(station)) return true
            val from = lane.possibleFrom ?: return false
            return lane.id !in plan.named && station >= from && station < lane.opens
        }

        /**
         * Whether, between the car at [car] and [point] ahead, the lane the plan names counts differently from the exit's
         * edge, or a lane on the exit's side opens or may begin: only the named lane itself, entered along its taper, keeps
         * the words. At the car every lane a drawing shows there counts, also one that ends before the point, but for a lane
         * in its last [LaneTopology.LEAVE_PAST_POINT_METERS], as the one that left at the point just passed; ahead, a lane
         * that opens or may begin counts also where it is gone again by the point. Never for a point the car has reached.
         */
        internal fun opensAhead(plan: LanePlan, point: LaneTopology.Point, atPoint: PlanSegment?, car: Double, atCar: PlanSegment?): Boolean {
            val pointStation = point.station
            if (!(car < pointStation) || atPoint == null) return false
            val named = atPoint.named ?: return false
            val edge = atPoint.edge ?: return false
            val topology = plan.topology
            val lit = topology.lanes.getOrNull(named) ?: return false
            val carLane = atCar?.takeIf { it.run == atPoint.run }?.named?.let { topology.lanes.getOrNull(it) }
            val lanes = topology.lanesOf(lit.run)
            val countAtPoint = atPoint.namedFromEdge
            if (carLane != null && countAtPoint != null) {
                var countAtCar = 0
                for (other in lanes) {
                    if (other.id == carLane.id || other.designated || other.closes <= car + LaneTopology.LEAVE_PAST_POINT_METERS) continue
                    if (drawnAt(other, plan, car) && exitSide(other, carLane, edge)) countAtCar++
                }
                if (countAtCar != countAtPoint) return true
            }
            for (lane in lanes) {
                if (lane.id == lit.id || lane.designated) continue
                // On the exit's side of the lane lit at the point, or of the one lit at the car where that is another.
                if (!exitSide(lane, lit, edge) && (carLane == null || !exitSide(lane, carLane, edge))) continue
                if (lane.opens > car && lane.opens <= pointStation) return true
                val possible = lane.possibleFrom
                if (possible != null && lane.id !in plan.named && possible > car && possible <= pointStation) return true
            }
            return false
        }

        /**
         * Whether a road without lane data that leaves on the side of an exit lies at or ahead of the car at [car] on the
         * step the car is on (the card's words wait until it is passed), by the roads' own stations rather than the plan's
         * 5 m grid.
         */
        internal fun unknownAhead(plan: LanePlan, car: Double): Boolean = plan.unknownExits.any { it.ahead(car) }

        /** Whether [lane] lies on the exit's side, [edge], of [of] across the road. */
        private fun exitSide(lane: LaneTopology.Lane, of: LaneTopology.Lane, edge: LaneEdge): Boolean =
            if (edge == LaneEdge.RIGHT) lane.slot > of.slot else lane.slot < of.slot

        /** Where the first lit lane of [drawn] is among the lanes open to every car that are no outline, counted from [edge]. */
        private fun needOf(lanes: List<RouteLane>, drawn: RunUpRule.DrawnRow, outline: Set<Int>, edge: LaneEdge): Int? {
            val open = lanes.indices.filter { !lanes[it].designated && it !in outline }.let { if (edge == LaneEdge.RIGHT) it.asReversed() else it }
            return open.indexOfFirst { drawn.lanes.lanes[it].isRecommended }.takeIf { it >= 0 }
        }
    }
}
