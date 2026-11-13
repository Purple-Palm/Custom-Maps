package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.max

/**
 * The card's lane row on a run-up: the last part of a freeway step before its exit or fork, while
 * the camera holds its close view for it. Pure: the map engine reads the lanes from the route and
 * asks here once per progress update.
 *
 * The exit's side is the edge lanes are counted from ([exitEdge]), among the lanes open to every
 * car: offset 0 is the lane at that edge. On the way to an exit, a point where the route goes
 * straight on and the lane at the exit's edge leaves the route (an earlier exit, a split or a lane
 * that ends on that side) is an edge point: only such a point changes what to do to be on the
 * exit's side.
 *
 * - The row shows the next edge point ahead, lit on one lane: the composed lane, which leads
 *   through every point after it to the exit, counted the careful way ([compose]).
 * - Once no edge point is left, the row shows the exit's own lanes, lit on the lanes to use there.
 * - The lanes to use have words only where the counts leave no doubt, and never while a road
 *   without lane data leaves on the exit's side before the exit. The words count from the exit's
 *   side; they never name the far side, and never say "keep right".
 * - A lane kept for car pools or buses is drawn dim and is not lit while an open lane can be used.
 *
 * Odd lane data gives a row without words, never an error.
 */
object RunUpRule {

    /** Points on the way no farther apart than this, with the same lanes, are taken as one point. */
    const val EDGE_CLUSTER_METERS = 100.0

    /** Why a row has no words although it lights a lane: the counts of the points on the way leave doubt, so the lane is the careful one. */
    const val ODD_COMPOSED = "composed"

    /** Why a row has no words: the exit's lanes to use are not side by side. */
    const val ODD_PIECES = "pieces"

    /** Why a row has no words: a road without lane data leaves on the exit's side before the exit. */
    const val ODD_UNKNOWN_BRANCH = "unknown_branch"

    /** Why a row lights a lane kept for car pools or buses: the route marks no other lane. */
    const val ODD_DESIGNATED_ONLY = "designated_only"

    /**
     * Why a row read from the lane plan has no words: between the car and the row's point a lane on the exit's side
     * opens or may begin, or the lane the plan names there counts otherwise from the exit's edge where the car is.
     */
    const val ODD_OPENS_AHEAD = "opens_ahead"

    /** Why a row read from the lane plan has no words: by the plan's reading, a road without lane data leaves on the exit's side before the exit. */
    const val ODD_UNKNOWN_AHEAD = "unknown_ahead"

    /** Why a row has no words: the lane plan names no lane yet, at the row's point or where the car is, or the route's plan is on its way. */
    const val ODD_NOT_NAMED = "not_named"

    /** A point of the step where the route goes straight on, with its lanes as the route lists them. */
    data class LanePoint(
        /** The route, leg, step and intersection, as the lane choices key it. */
        val key: String,
        /** Along the route, from the car to the point. */
        val metersAhead: Double,
        /** Every lane of the point, from the leftmost. */
        val lanes: List<RouteLane>,
    )

    /**
     * One point on the way in counts from the exit's edge ([edgePoints]): a point of the route, or
     * several close together that are taken as one.
     */
    class EdgePoint internal constructor(members: List<LanePoint>, edge: LaneEdge) {
        private val first = members.first()

        /** The key of the last of the points taken as one: it stays the same until the car has passed all of them. */
        val key: String = members.last().key

        /** Along the route, from the car to the nearest of them. */
        val metersAhead: Double = first.metersAhead

        /** The lanes of the nearest of them, which the row draws. */
        val lanes: List<RouteLane> = first.lanes

        /** The lanes open to every car, as indices into [lanes], from the exit's edge inward. */
        internal val order: IntArray = openFromEdge(first.lanes, edge)

        /** The lanes open to every car. */
        val openLanes: Int get() = order.size

        /** Every lane, those kept for car pools and the like included. */
        val totalLanes: Int = first.lanes.size

        /** Lanes kept for car pools and the like at the exit's edge, before the first lane open to every car. */
        val designatedAtEdge: Int = designatedAtEdge(first.lanes, edge)

        /** By offset from the exit's edge: whether the lane can be used at every one of the points. */
        internal val usable: BooleanArray = BooleanArray(order.size) { k -> members.all { it.lanes[order[k]].usable } }

        /** The offset of the first lane that can be used; null when none can. */
        val lo: Int? = usable.indexOfFirst { it }.takeIf { it >= 0 }

        /** Whether the lane at the exit's edge leaves the route here while another goes on. */
        val isEdge: Boolean get() = (lo ?: 0) >= 1

        /** Whether the lane at [offset] from the exit's edge can be used at every one of the points. */
        fun isUsable(offset: Int): Boolean = offset in usable.indices && usable[offset]
    }

    /** The composed lane at one point: its offset from the exit's edge, and whether the counts leave no doubt about it. */
    data class Composed(val need: Int, val exact: Boolean)

    /**
     * A row as the card draws it ([drawRow]): [lanes], and [target], the words for its lit lanes, null where they have
     * none. [inPieces]: the lit lanes are lanes open to every car but do not lie side by side.
     */
    class DrawnRow(val lanes: LaneInfo, val target: LaneTarget?, val inPieces: Boolean)

    /**
     * The side the exit leaves on: the side of the maneuver's own [modifier], the route step's and
     * not the banner's. For a maneuver straight on or without a side, the edge that the exit's
     * lanes to use touch, when they touch exactly one. Null when it cannot be told.
     */
    fun exitEdge(modifier: TurnModifier?, exitLanes: List<RouteLane>): LaneEdge? = when (modifier) {
        TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT -> LaneEdge.RIGHT
        TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT -> LaneEdge.LEFT
        else -> edgeTouched(exitLanes)
    }

    /**
     * How many lanes open to every car lie at [edge] before the first one the route can be
     * followed from; null when none can.
     */
    fun edgeOffset(lanes: List<RouteLane>, edge: LaneEdge): Int? {
        val order = openFromEdge(lanes, edge)
        for (k in order.indices) if (lanes[order[k]].usable) return k
        return null
    }

    /**
     * Whether the lane at the exit's [edge] leaves the route at this point while another lane
     * goes on with it: the point is an edge point. Never where the side is not known.
     */
    fun edgeLeaves(lanes: List<RouteLane>, edge: LaneEdge?): Boolean =
        edge != null && (edgeOffset(lanes, edge) ?: 0) >= 1

    /**
     * The points on the way still ahead, in counts from the exit's [edge], nearest first.
     * Consecutive points no more than [EDGE_CLUSTER_METERS] apart with the same number of lanes
     * and the same lanes kept for car pools are one point, as one junction can come as several
     * points: a lane counts as usable there when it is usable at each of them. A point where no
     * lane open to every car can be used is left out; it says nothing about where to drive.
     */
    fun edgePoints(points: List<LanePoint>, edge: LaneEdge): List<EdgePoint> {
        val ahead = points.filter { it.metersAhead >= 0.0 && it.metersAhead < Double.POSITIVE_INFINITY }.sortedBy { it.metersAhead }
        val result = ArrayList<EdgePoint>()
        var members = ArrayList<LanePoint>()
        for (point in ahead) {
            val last = members.lastOrNull()
            if (last != null && !oneJunction(last, point)) {
                result.add(EdgePoint(members, edge))
                members = ArrayList()
            }
            members.add(point)
        }
        if (members.isNotEmpty()) result.add(EdgePoint(members, edge))
        return result.filter { it.lo != null }
    }

    /**
     * The composed lane at each of [points], worked out backward from the exit: the lane at a
     * point that leads through every point after it to the exit's lanes to use.
     *
     * The need of a point is the need of the next one, plus the lanes that leave at its exit's
     * edge, plus the lanes kept for car pools that begin at that edge between the two. Where the
     * counts cannot say where lanes went, the careful reading: lanes added between two edge points
     * are taken to be on the far side, so they move nothing, and lanes gone between them are taken
     * at the exit's edge, so the need grows by them. The need is then kept within the point's open
     * lanes, and on a lane that can be used there.
     *
     * Exact only where none of this was needed at the point or at any after it: lanes added or
     * gone between two edge points, a change of the lanes kept for car pools at the edge between
     * them, or a lane kept within bounds.
     *
     * @param exitNeed the offset of the first of the exit's lanes to use
     * @param exitDesignatedAtEdge the exit's lanes kept for car pools at its edge, before the first open one
     */
    fun compose(points: List<EdgePoint>, exitNeed: Int, exitDesignatedAtEdge: Int): List<Composed> {
        val needs = IntArray(points.size)
        val exacts = BooleanArray(points.size)
        var needNext = exitNeed.coerceAtLeast(0)
        var exactNext = true
        var designatedNext = exitDesignatedAtEdge.coerceAtLeast(0)
        var nextEdge: EdgePoint? = null
        for (i in points.indices.reversed()) {
            val point = points[i]
            val lo = point.lo ?: 0
            var need = needNext + lo + max(0, designatedNext - point.designatedAtEdge)
            var exact = exactNext
            val next = nextEdge
            if (point.isEdge && next != null) {
                // The lanes that go on past this point should be the lanes of the next edge point.
                val goOn = point.totalLanes - lo
                if (next.totalLanes != goOn || next.designatedAtEdge != point.designatedAtEdge) {
                    exact = false
                    need += max(0, goOn - next.totalLanes)
                }
            }
            if (need > point.openLanes - 1) {
                need = point.openLanes - 1
                exact = false
            }
            if (!point.isUsable(need)) {
                need = (lo until need).lastOrNull { point.isUsable(it) } ?: lo
                exact = false
            }
            needs[i] = need
            exacts[i] = exact
            needNext = need
            exactNext = exact
            designatedNext = point.designatedAtEdge
            if (point.isEdge) nextEdge = point
        }
        return List(points.size) { Composed(needs[it], exacts[it]) }
    }

    /**
     * The card's row on a run-up.
     *
     * @param edge the side the exit leaves on ([exitEdge]); null when it cannot be told, and then
     *   the row is the exit's own lanes, without words
     * @param pointsAhead the step's points still ahead where the route goes straight on, with lanes
     * @param exitKey the exit's own key, as the lane choices key it
     * @param exitLanes the exit's own lanes as the route lists them, from the leftmost
     * @param branchAhead a road without lane data leaves on the exit's side between the car and the
     *   exit: no words until the car has passed it
     * @return null when the exit has no lanes
     */
    fun row(edge: LaneEdge?, pointsAhead: List<LanePoint>, exitKey: String, exitLanes: List<RouteLane>, branchAhead: Boolean): RunUpLanes? {
        if (exitLanes.isEmpty()) return null
        val exit = litAtExit(exitLanes, edge)
        if (edge == null) {
            val odd = if (exit.designatedOnly) ODD_DESIGNATED_ONLY else null
            return RunUpLanes(drawRow(exitLanes, exit.lit, emptySet(), edge = null, exact = true).lanes, target = null, key = exitKey, odd = odd, need = null)
        }
        val points = edgePoints(pointsAhead, edge)
        val plan = compose(points, exit.offset ?: 0, designatedAtEdge(exitLanes, edge))
        val shown = points.indexOfFirst { it.isEdge }
        val lanes: LaneInfo
        val key: String
        val need: Int?
        var target: LaneTarget?
        var odd: String?
        if (shown >= 0) {
            val point = points[shown]
            val composed = plan[shown]
            val row = drawRow(point.lanes, listOf(point.order[composed.need]), emptySet(), edge, composed.exact)
            lanes = row.lanes
            key = point.key
            need = composed.need
            target = row.target
            odd = if (composed.exact) null else ODD_COMPOSED
        } else {
            val row = drawRow(exitLanes, exit.lit, emptySet(), edge, exact = true)
            lanes = row.lanes
            key = exitKey
            need = exit.offset
            target = row.target
            odd = when {
                exit.designatedOnly -> ODD_DESIGNATED_ONLY
                row.inPieces -> ODD_PIECES
                else -> null
            }
            // The points on the way that are no edge points still count: the exit's lane has to
            // lead through each of them as counted.
            if (!exit.designatedOnly && points.isNotEmpty() && !plan[0].exact) {
                target = null
                odd = ODD_COMPOSED
            }
        }
        if (branchAhead && target != null) {
            target = null
            odd = ODD_UNKNOWN_BRANCH
        }
        return RunUpLanes(lanes, target, key, odd, need, edge)
    }

    /**
     * A point's lanes as the card draws them, lit on lanes chosen elsewhere, as the lane plan chooses them, and the words
     * for those lanes: the one place rows and words are composed. Every lane of [lanes], as the route lists them from
     * the leftmost, is drawn: [lit] (indices into [lanes]) lit with the arrow the route takes there, those kept for car
     * pools dim, the lanes of [outline] as outlines, never lit. The words name [lit] counted from [edge] among the lanes
     * open to every car that are no outline, and there are words only where [exact] (the counts on the way leave no
     * doubt), every lit lane is such a lane, the lit lanes lie side by side, are not every such lane, and are few enough
     * or near enough the edge to name ([LaneTarget.hasWords]).
     */
    fun drawRow(lanes: List<RouteLane>, lit: List<Int>, outline: Set<Int>, edge: LaneEdge?, exact: Boolean): DrawnRow {
        val on = lit.filter { it in lanes.indices && it !in outline }
        val drawn = LaneInfo(
            lanes.mapIndexed { i, lane ->
                val isLit = i in on
                Lane(lane.indications, isRecommended = isLit, activeIndication = if (isLit) litArrow(lane) else null, designated = lane.designated, outline = i in outline)
            },
        )
        if (edge == null || on.isEmpty()) return DrawnRow(drawn, target = null, inPieces = false)
        val open = openFromEdge(lanes, edge).filter { it !in outline }
        val places = on.map { open.indexOf(it) }.sorted()
        if (places.first() < 0) return DrawnRow(drawn, target = null, inPieces = false)
        val inOnePiece = places.last() - places.first() == places.size - 1
        val target = if (exact && inOnePiece) words(edge, places.size, places.first(), open.size) else null
        return DrawnRow(drawn, target, inPieces = !inOnePiece)
    }

    /** The exit's lanes to use, as indices into its lanes, and where they lie. */
    private class ExitLanes(
        val lit: List<Int>,
        /** The offset of the first of them from the exit's edge; null without an edge or an open lane lit. */
        val offset: Int?,
        val inOnePiece: Boolean,
        /** Only lanes kept for car pools and the like are lit. */
        val designatedOnly: Boolean,
    )

    /**
     * The exit's lanes to use: those the route can be followed from, narrowed to the ones it
     * prefers where it prefers any. Where it marks only lanes kept for car pools and the like,
     * those, so the row is never empty.
     */
    private fun litAtExit(lanes: List<RouteLane>, edge: LaneEdge?): ExitLanes {
        val use = toUse(lanes)
        if (use.isEmpty()) {
            val marked = lanes.indices.filter { lanes[it].designated && lanes[it].marked }
            val lit = marked.filter { lanes[it].active == true }.ifEmpty { marked }
            return ExitLanes(lit, offset = null, inOnePiece = false, designatedOnly = lit.isNotEmpty())
        }
        val open = if (edge == null) openFromEdge(lanes, LaneEdge.LEFT) else openFromEdge(lanes, edge)
        val places = use.map { open.indexOf(it) }.sorted()
        val inOnePiece = places.last() - places.first() == places.size - 1
        return ExitLanes(use, offset = if (edge == null) null else places.first(), inOnePiece = inOnePiece, designatedOnly = false)
    }

    /** The lanes the route can be followed from, narrowed to the ones it prefers where it prefers any. */
    private fun toUse(lanes: List<RouteLane>): List<Int> {
        val usable = lanes.indices.filter { lanes[it].usable }
        return usable.filter { lanes[it].active == true }.ifEmpty { usable }
    }

    /** The edge that the lanes to use touch, when they touch exactly one of the two. */
    private fun edgeTouched(lanes: List<RouteLane>): LaneEdge? {
        val lit = toUse(lanes)
        val leftmost = lanes.indexOfFirst { !it.designated }
        val rightmost = lanes.indexOfLast { !it.designated }
        if (lit.isEmpty() || leftmost < 0) return null
        val left = leftmost in lit
        val right = rightmost in lit
        return when {
            left && !right -> LaneEdge.LEFT
            right && !left -> LaneEdge.RIGHT
            else -> null
        }
    }

    /** The words for [count] lanes [offset] lanes in from [edge], of [openLanes]: none where every open lane would be lit. */
    private fun words(edge: LaneEdge, count: Int, offset: Int?, openLanes: Int): LaneTarget? {
        if (offset == null || count <= 0 || count >= openLanes) return null
        return LaneTarget(edge, count, offset).takeIf { it.hasWords }
    }

    /** The arrow the route takes from a lit lane: the one the route names, else the lane's arrow that turns least, else straight on. */
    private fun litArrow(lane: RouteLane): TurnModifier =
        lane.validIndication ?: lane.indications.minByOrNull(::sharpness) ?: TurnModifier.STRAIGHT

    /** How far an arrow turns away from straight on, left or right alike. */
    private fun sharpness(turn: TurnModifier): Int = when (turn) {
        TurnModifier.STRAIGHT -> 0
        TurnModifier.SLIGHT_LEFT, TurnModifier.SLIGHT_RIGHT -> 1
        TurnModifier.LEFT, TurnModifier.RIGHT -> 2
        TurnModifier.SHARP_LEFT, TurnModifier.SHARP_RIGHT -> 3
        TurnModifier.U_TURN -> 4
    }

    /** Two consecutive points close enough with the same lanes: one junction. */
    private fun oneJunction(nearer: LanePoint, farther: LanePoint): Boolean =
        farther.metersAhead - nearer.metersAhead <= EDGE_CLUSTER_METERS &&
            nearer.lanes.size == farther.lanes.size &&
            nearer.lanes.indices.all { nearer.lanes[it].designated == farther.lanes[it].designated }

    /** The lanes open to every car, as indices into [lanes], from [edge] inward. */
    private fun openFromEdge(lanes: List<RouteLane>, edge: LaneEdge): IntArray {
        val open = lanes.indices.filter { !lanes[it].designated }
        return (if (edge == LaneEdge.RIGHT) open.asReversed() else open).toIntArray()
    }

    /** Lanes kept for car pools and the like at [edge], before the first lane open to every car. */
    private fun designatedAtEdge(lanes: List<RouteLane>, edge: LaneEdge): Int {
        var count = 0
        for (i in if (edge == LaneEdge.RIGHT) lanes.indices.reversed() else lanes.indices) {
            if (!lanes[i].designated) break
            count++
        }
        return count
    }
}
