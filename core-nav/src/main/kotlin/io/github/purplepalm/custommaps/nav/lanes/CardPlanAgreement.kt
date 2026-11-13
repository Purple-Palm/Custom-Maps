package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.model.Lane

/**
 * Whether the maneuver card's lane row and the lane plan say the same at a lane point the card shows (the
 * design's section 6), for the debug log. The run-up's row reads the plan ([CardPlanRow]), so there the two
 * agree by construction and this checks it; the rows before the run-up are the navigation SDK's and the lane
 * choices' own, and this tells after a drive where they differ.
 *
 * 1. Where the card lights one lane and the plan names one at the point's station, they are the same lane.
 * 2. Where the card lights several lanes, or the plan names none there (outside its card windows), the
 *    card's lit lanes are the plan's amber set there, the lanes that work with the named one among them,
 *    but for the lanes a drawing would not show at the car yet (4).
 * 3. The card names the lanes in words only where, between the car and the point, no lane on the exit's
 *    side opens or may begin and the named lane's count from the exit's edge stays the same (no
 *    [Result.opensAhead]), no road without lane data leaves on the exit's side before the end of the step
 *    ([Result.unknownAhead]), and its own reading of the counts leaves no doubt ([Result.exact]).
 * 4. A lane of the point that a drawing would not show at the car yet, other than the one the plan names
 *    there, is an outline in the run-up's row, never lit ([Result.outline]).
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

        /** No lane can be named on this approach (C1): the plan names none and tints none, the card keeps its row, without words. */
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
        /** The point's lanes a drawing would not show at the car yet, the one the plan names there left out: the run-up row's outlines (4). */
        val outline: List<Int>,
    )

    /**
     * [row], shown with the car at [carStation], against [plan] at its lane point [point]. [atCar] is the
     * plan at the car's station, the engine's one lookup for the update.
     */
    fun of(row: Row, plan: LanePlan, point: LaneTopology.Point, carStation: Double, atCar: PlanSegment? = plan.segmentAt(carStation)): Result {
        val lit = row.lanes.indices.filter { row.lanes[it].isRecommended && !row.lanes[it].outline }
        if (row.lanes.size != point.lanes.size) return disagree(lit, Why.ROW, row)
        val atPoint = plan.segmentAt(point.station)
        val planLit = CardPlanRow.named(point, atPoint)
        val amber = CardPlanRow.amber(point, atPoint)
        val outline = CardPlanRow.outline(plan, point, planLit, carStation)
        val why = when {
            atPoint?.noLane != null -> Why.NO_LANE
            lit.size == 1 && planLit != null -> if (lit.single() == planLit) null else Why.LIT
            lit == amber || (row.source == CardLanes.Source.RUN_UP && lit == amber - outline.toSet()) -> null
            lit.size == 1 -> Why.NOT_NAMED
            else -> Why.AMBER
        }
        val opensAhead = CardPlanRow.opensAhead(plan, point, atPoint, carStation, atCar)
        val unknownAhead = CardPlanRow.unknownAhead(plan, carStation)
        val wordsOk = !row.words || (!opensAhead && !unknownAhead && row.exact)
        val because = why ?: if (wordsOk) null else Why.WORDS
        return Result(lit, planLit, amber, because == null, because, opensAhead, unknownAhead, row.exact, outline)
    }

    /** A row whose point is no lane point of the plan's route ([Why.NO_POINT]). */
    fun noPoint(row: Row): Result = disagree(row.lanes.indices.filter { row.lanes[it].isRecommended && !row.lanes[it].outline }, Why.NO_POINT, row)

    private fun disagree(lit: List<Int>, why: Why, row: Row) =
        Result(lit, planLit = null, amber = emptyList(), agree = false, why = why, opensAhead = false, unknownAhead = false, exact = row.exact, outline = emptyList())
}
