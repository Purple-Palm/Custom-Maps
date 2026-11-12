package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge

/**
 * Whether the maneuver card's lane row and the lane plan say the same at a lane point the card shows (the
 * design's section 6), for the debug log: until the card reads the plan, the two are worked out apart, and
 * this tells after a drive where they would differ.
 *
 * 1. Where the card lights one lane and the plan names one at the point's station, they are the same lane.
 * 2. Where the card lights several lanes, or the plan names none there (outside its card windows), the
 *    card's lit lanes are the plan's amber set there: the lanes that work, the named one among them.
 * 3. The card names the lanes in words only where, between the car and the point, no lane on the exit's
 *    side opens or may begin and the named lane's count from the exit's edge stays the same (no
 *    [Result.opensAhead]), no road without lane data leaves on the exit's side before the end of the step
 *    ([Result.unknownAhead]), and its own reading of the counts leaves no doubt ([Result.exact]).
 * 4. A lane of the point that a drawing would not show at the car yet is an outline in the row
 *    ([Result.outline]): the card does not draw outlines yet, so this is only said, never compared.
 *
 * Lanes are counted from the left, as the row lists them. Pure; nothing in it throws on odd lanes.
 */
object CardPlanAgreement {

    /** Why the card and the plan do not agree. */
    enum class Why(
        /** How the debug log names it. */
        val logName: String,
    ) {
        /** The card lights one lane, the plan names another. */
        LIT("lit"),

        /** The card lights several lanes, or the plan names none, and the lit lanes are not the plan's amber set. */
        AMBER("amber"),

        /** The card lights one lane where the plan names none, and the lanes that work there are not that one alone. */
        NOT_NAMED("not_named"),

        /** No lane can be named on this approach (C1): the plan names none and tints none, the card keeps its row. */
        NO_LANE("no_lane"),

        /** The row's lanes are not the point's: another count. */
        ROW("row"),

        /** The row's point is no lane point of the plan's route. */
        NO_POINT("no_point"),

        /** The card names the lanes in words where the plan would not (3). */
        WORDS("words"),
    }

    /** The card's row at a lane point, in plain values. */
    data class Row(
        /** Where the row comes from ([CardLanes.of]). */
        val source: CardLanes.Source,
        /** The row's lanes from the leftmost, the ones it lights recommended. */
        val lanes: List<Lane>,
        /** Whether the row names the lanes to use in words. */
        val words: Boolean,
        /** Whether the card's own reading of the counts on the way leaves no doubt (a run-up row's odd is not `composed`). */
        val exact: Boolean = true,
    )

    /** The comparison of one row with the plan; lanes are indices into the row, from the left. */
    data class Result(
        /** The lanes the card lights. */
        val lit: List<Int>,
        /** The lane the plan names at the point's station; null where it names none. */
        val planLit: Int?,
        /** The plan's amber set there: the lanes that work, the named one among them. */
        val amber: List<Int>,
        val agree: Boolean,
        /** Why they do not agree; null where they do. */
        val why: Why?,
        /** Between the car and the point an exit-side lane opens or may begin, or the named lane's count from the exit's edge changes. */
        val opensAhead: Boolean,
        /** A road without lane data leaves on the exit's side between the car and the end of its step. */
        val unknownAhead: Boolean,
        /** The card's own reading of the counts leaves no doubt. */
        val exact: Boolean,
        /** The point's lanes a drawing would not show at the car yet (4). */
        val outline: List<Int>,
    )

    /**
     * [row], shown with the car at [carStation], against [plan] at its lane point [point]. [atCar] is the
     * plan at the car's station, the engine's one lookup for the update.
     */
    fun of(row: Row, plan: LanePlan, point: LaneTopology.Point, carStation: Double, atCar: PlanSegment? = plan.segmentAt(carStation)): Result {
        val lit = row.lanes.indices.filter { row.lanes[it].isRecommended }
        if (row.lanes.size != point.lanes.size) return disagree(lit, Why.ROW, row)
        val topology = plan.topology
        val atPoint = plan.segmentAt(point.station)
        val named = atPoint?.named
        val planLit = named?.let { point.lanes.indexOf(it) }?.takeIf { it >= 0 }
        val amber = atPoint?.band.orEmpty().mapNotNull { id -> point.lanes.indexOf(id).takeIf { it >= 0 } }.sorted()
        val why = when {
            atPoint?.noLane != null -> Why.NO_LANE
            lit.size == 1 && planLit != null -> if (lit.single() == planLit) null else Why.LIT
            lit == amber -> null
            lit.size == 1 -> Why.NOT_NAMED
            else -> Why.AMBER
        }
        val opensAhead = opensAhead(plan, point, atPoint, carStation, atCar)
        val unknownAhead = unknownAhead(plan, carStation)
        val wordsOk = !row.words || (!opensAhead && !unknownAhead && row.exact)
        val outline = point.lanes.indices.filter { !countsAt(topology.lanes.getOrNull(point.lanes[it]), plan, carStation) }
        val because = why ?: if (wordsOk) null else Why.WORDS
        return Result(lit, planLit, amber, because == null, because, opensAhead, unknownAhead, row.exact, outline)
    }

    /** A row whose point is no lane point of the plan's route ([Why.NO_POINT]). */
    fun noPoint(row: Row): Result = disagree(row.lanes.indices.filter { row.lanes[it].isRecommended }, Why.NO_POINT, row)

    private fun disagree(lit: List<Int>, why: Why, row: Row) =
        Result(lit, planLit = null, amber = emptyList(), agree = false, why = why, opensAhead = false, unknownAhead = false, exact = row.exact, outline = emptyList())

    /**
     * Whether, between the car and the point ahead, the lane the plan names counts differently from the
     * exit's edge, or a lane on the exit's side of the lane named at the point opens or may begin: only the
     * named lane itself, entered along its taper, keeps the words. At the car only lanes still there at the
     * point count, so the lane that left at the point just passed is none. Never for a point the car has
     * reached.
     */
    private fun opensAhead(plan: LanePlan, point: LaneTopology.Point, atPoint: PlanSegment?, car: Double, atCar: PlanSegment?): Boolean {
        val pointStation = point.station
        if (!(car < pointStation) || atPoint == null) return false
        val named = atPoint.named ?: return false
        val edge = atPoint.edge ?: return false
        val topology = plan.topology
        val lit = topology.lanes.getOrNull(named) ?: return false
        val carLane = atCar?.takeIf { it.run == atPoint.run }?.named?.let { topology.lanes.getOrNull(it) }
        val countAtPoint = atPoint.namedFromEdge
        if (carLane != null && countAtPoint != null) {
            var countAtCar = 0
            for (other in topology.lanes) {
                if (other.run != carLane.run || other.id == carLane.id || other.designated || other.closes <= pointStation) continue
                if (!countsAt(other, plan, car)) continue
                if (if (edge == LaneEdge.RIGHT) other.slot > carLane.slot else other.slot < carLane.slot) countAtCar++
            }
            if (countAtCar != countAtPoint) return true
        }
        for (lane in topology.lanes) {
            if (lane.run != lit.run || lane.id == lit.id || lane.designated || !lane.exists(pointStation)) continue
            val exitSide = if (edge == LaneEdge.RIGHT) lane.slot > lit.slot else lane.slot < lit.slot
            if (!exitSide) continue
            if (lane.opens > car && lane.opens <= pointStation) return true
            val possible = lane.possibleFrom
            if (possible != null && lane.id !in plan.named && possible > car && possible <= pointStation) return true
        }
        return false
    }

    /**
     * Whether a road without lane data that leaves on the side of an exit lies at or ahead of the car on the
     * step the car is on (the card's words wait until it is passed), by the roads' own stations rather than
     * the plan's 5 m grid.
     */
    private fun unknownAhead(plan: LanePlan, car: Double): Boolean {
        if (plan.unknownExits.isEmpty()) return false
        val nodes = plan.topology.evidence.route.nodes
        val stepEnd = nodes.indices.firstOrNull { nodes[it].station >= car && LaneEvidence.turnsAt(nodes[it]) }
        return plan.unknownExits.any { it.station >= car && it.node == stepEnd }
    }

    /** Whether a drawing shows [lane] at [station]: where it is, or may already be and the plan does not name it (P8 with 2a). */
    private fun countsAt(lane: LaneTopology.Lane?, plan: LanePlan, station: Double): Boolean {
        if (lane == null) return false
        if (lane.exists(station)) return true
        val from = lane.possibleFrom ?: return false
        return lane.id !in plan.named && station >= from && station < lane.opens
    }
}
