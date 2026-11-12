package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.max
import kotlin.math.min

/**
 * The lanes of a route as one set, the way the lane plan reads them: the lane lists of consecutive
 * lane points aligned into lanes that keep their identity from point to point, so a lane can be
 * counted from the exit's edge anywhere along the way (the design's P8).
 *
 * - **Runs.** Consecutive lane points belong to one run while the route goes on along one road between
 *   them, or leaves it at the first point with lanes it can be followed from, which go on onto the
 *   new road (an exit's lanes onto its ramp). A node where the route turns without lane data ends a
 *   run. A run's lanes begin at the node where the route turned onto its road before its first point
 *   and end at its last point: past it the route's data says nothing of its lanes.
 * - **Clusters.** Points of one road no more than [RunUpRule.EDGE_CLUSTER_METERS] apart with the same
 *   lanes, and the same ones kept for car pools, are one junction: the same lanes at each.
 * - **What goes on past a point** the route goes straight through: at the exit's edge the lanes open
 *   to every car before the first one it can be followed from leave there, as the card's careful
 *   reading takes them ([RunUpRule.compose]); at the far edge unmarked lanes leave only where a road
 *   that may be entered leaves on their side, else they go on and the point only says where to drive
 *   ([LaneEvidence.goingOn], the evidence's rule 6); in the middle they go on. Past a point where the
 *   route turns, the lanes it can be followed from go on.
 * - **Alignments.** Where the next point lists more or fewer lanes than go on, the side of the change
 *   is read, first that holds: the lanes kept for car pools at the same places on both ([Alignment.Reason.ANCHOR]);
 *   for a gain, lanes at the exit's edge with only the exit's arrows ([LaneEvidence.addedSide],
 *   [Alignment.Reason.ARROWS]), at the exit itself or at a point with no road of its own on that side;
 *   a road that joins (a gain) or leaves (a loss) on one side only on the
 *   way ([Alignment.Reason.NODE]); else the careful reading of the card ([Alignment.Reason.CAREFUL]):
 *   lanes added on the far side, lanes gone at the exit's edge.
 * - **Late and early.** An added lane opens late: its taper ends at the point that first lists it
 *   ([addedTaperMeters]); at an exit it is a deceleration lane, full width [DECEL_LATE_FULL_METERS]
 *   before the gore after a taper of [DECEL_TAPER_METERS]; at a street turn a pocket, full width
 *   [POCKET_FULL_LATE_METERS] before a stop line [STOP_LINE_METERS] before the node after a taper of
 *   [POCKET_TAPER_METERS] (from [FAST_FROM_MPH]: [POCKET_FULL_LATE_FAST_METERS] and
 *   [POCKET_TAPER_FAST_METERS]). An added lane on the exit's side is entered from its neighbour along
 *   its taper ([Lane.fedBy]) and may already be there from the point before ([Lane.possibleFrom]). A
 *   lane gone without a road that explains it goes [VANISH_EARLY_METERS] after the point before; one
 *   a road explains goes there. A lane that becomes one kept for car pools does so
 *   [KINDS_EARLY_METERS] after the point before; one that opens to every car does so late.
 *
 * Pure: built from the evidence's first pass, once for a route, off the main thread, and never stored.
 */
class LaneTopology private constructor(
    /** The evidence the topology was read from (its first pass). */
    val evidence: LaneEvidence,
    /** Every lane, by [Lane.id]. */
    val lanes: List<Lane>,
    /** The lane points of the route in route order, each with the lane every listed lane is. */
    val points: List<Point>,
    /** The runs, in route order. */
    val runs: List<Run>,
    /** Every change of the lane count between two points of a run, with the side it was read on. */
    val alignments: List<Alignment>,
) {

    /** One lane along a run. */
    data class Lane(
        /** Its index in [lanes]. */
        val id: Int,
        /** The run it is on (an index into [runs]). */
        val run: Int,
        /** Its place across the road: only the order among lanes there at the same station counts, from the left. */
        val slot: Double,
        /** The kinds of vehicle it is kept for; empty for a lane open to every car. */
        val kinds: Set<DesignatedKind>,
        /** Where it begins along the route, in metres: for an added lane, where it opens late. */
        val opens: Double,
        /** Where it ends: where it leaves the route's road, or the run's end. */
        val closes: Double,
        /** The point (an index into [points]) that first lists it; null for a lane of the run's first point. */
        val addedAt: Int? = null,
        /** For a lane added on the exit's side: the lane it is entered from along its taper, with no lane change. */
        val fedBy: Int? = null,
        /** The length of that taper, in metres. */
        val taperMeters: Double = 0.0,
        /**
         * For a lane added on the exit's side: where it may already be, the lane point before the one that
         * first lists it, at most [EvidenceTuning.possibleLaneMaxM] back (the evidence's rule 2a); null
         * otherwise. Before [opens] it is only possible.
         */
        val possibleFrom: Double? = null,
    ) {
        /** Kept for some kinds of vehicle only. */
        val designated: Boolean get() = kinds.isNotEmpty()

        /** Whether the lane is there at [station]: from [opens], up to but not at [closes]. */
        fun exists(station: Double): Boolean = station >= opens && station < closes
    }

    /** A lane point of the route as the topology reads it. */
    data class Point(
        /** Its index in the route's intersections. */
        val node: Int,
        val station: Double,
        /** The run it is on. */
        val run: Int,
        /** The points of one junction share a cluster number. */
        val cluster: Int,
        /** For each lane the point lists, from the leftmost, the lane it is (an id into [lanes]). */
        val lanes: List<Int>,
        /** The route turns here ([LaneEvidence.turnsAt]): the exit, fork or turn this point is the lanes of. */
        val turns: Boolean,
        /** The side of the route's next exit ([LaneEvidence.edges]); null where it cannot be told. */
        val edge: LaneEdge?,
        /** The lanes the route can be followed from ([RouteLane.usable]). */
        val allowed: Set<Int>,
        /** Of those, the ones the route prefers ([RouteLane.active]); a tie-break only. */
        val preferred: Set<Int>,
        /** Lanes the route prefers that it says it cannot be followed from: their preference is ignored. */
        val activeOutsideValid: Int,
        /** Lanes it can be followed from whose one arrow points away from the exit's side. */
        val arrowContradicts: Int,
    ) {
        /** Whether the point says where to drive: it lets some lane be used, or the route turns here. */
        val constrains: Boolean get() = allowed.isNotEmpty() || turns
    }

    /** A stretch of the route whose lane points are read as one set of lanes. */
    data class Run(
        val index: Int,
        /** Where its lanes begin, in metres along the route. */
        val from: Double,
        /** Where they end. */
        val to: Double,
        /** Its points, as indices into [points]. */
        val points: IntRange,
    )

    /**
     * Between the points [before] and [after] (indices into [points]) the lanes that go on past the
     * first, [lanesBefore], become [lanesAfter] at the second; the lanes added or gone lie on [side],
     * as [by] read it, from or at [at] metres along the route.
     */
    data class Alignment(
        val before: Int,
        val after: Int,
        val lanesBefore: Int,
        val lanesAfter: Int,
        val side: LaneEdge?,
        val by: Reason,
        val at: Double,
    ) {
        enum class Reason {
            /** The lanes kept for car pools lie at the same places on both. */
            ANCHOR,

            /** For a gain: the lanes at the exit's edge carry only the exit's arrows. */
            ARROWS,

            /** A road joins (a gain) or leaves (a loss) on one side on the way. */
            NODE,

            /** Nothing tells: added on the far side, gone at the exit's edge (the card's careful reading). */
            CAREFUL,
        }

        /** Whether something told the side, rather than the careful reading. */
        val explained: Boolean get() = by != Reason.CAREFUL

        /** Lanes added (positive) or gone (negative). */
        val change: Int get() = lanesAfter - lanesBefore
    }

    /** The lanes of [run] there at [station], from the left. */
    fun lanesAt(run: Int, station: Double): List<Lane> =
        runLanes[run].filter { it.exists(station) }.sortedBy { it.slot }

    /**
     * How many lanes open to every car lie between [lane] and [edge] at [station]: 0 for the lane at the
     * edge. With [named], a lane that may already be there ([Lane.possibleFrom]) counts from where it may
     * begin unless it is one of [named]; without it, only lanes that are there count. Null where the lane
     * is not there and not possible either.
     */
    fun fromEdge(lane: Int, station: Double, edge: LaneEdge, named: Set<Int>? = null): Int? {
        val me = lanes.getOrNull(lane) ?: return null
        if (!counts(me, station, named)) return null
        var n = 0
        for (other in runLanes[me.run]) {
            if (other.id == me.id || other.designated || !counts(other, station, named)) continue
            if (if (edge == LaneEdge.RIGHT) other.slot > me.slot else other.slot < me.slot) n++
        }
        return n
    }

    /** Whether [lane] counts at [station]: it is there, or with [named] given it may be there and is not named. */
    private fun counts(lane: Lane, station: Double, named: Set<Int>?): Boolean {
        if (lane.exists(station)) return true
        val from = lane.possibleFrom ?: return false
        return named != null && lane.id !in named && station >= from && station < lane.opens
    }

    /** The lanes of each run. */
    private val runLanes: List<List<Lane>> = runs.map { run -> lanes.filter { it.run == run.index } }

    companion object {

        /** The width of a lane on a freeway or a ramp (design 4.2), for tapers. */
        const val FREEWAY_LANE_METERS = 3.66

        /** The width of a lane on a street. */
        const val STREET_LANE_METERS = 3.35

        /** Up to this planning speed an added lane's taper is [ADDED_TAPER_SLOW] to one, from it [ADDED_TAPER_FAST] to one. */
        const val FAST_FROM_MPH = 50.0

        /** An added lane's taper, lengthwise to one across, below [FAST_FROM_MPH]. */
        const val ADDED_TAPER_SLOW = 8.0

        /** The same from [FAST_FROM_MPH]. */
        const val ADDED_TAPER_FAST = 15.0

        /** From this speed a lane that ends tapers over its width times the speed, below over the width times the speed squared over 60. */
        const val ENDING_FORMULA_FROM_MPH = 45.0

        /** The shortest taper of a lane that ends. */
        const val ENDING_TAPER_MIN_METERS = 30.0

        /** A deceleration lane opening late reaches full width this far before the gore. */
        const val DECEL_LATE_FULL_METERS = 150.0

        /** After a taper this long. */
        const val DECEL_TAPER_METERS = 60.0

        /** The stop line lies this far before the node of a street turn. */
        const val STOP_LINE_METERS = 10.0

        /** A turn pocket opening late reaches full width this far before the stop line, below [FAST_FROM_MPH]. */
        const val POCKET_FULL_LATE_METERS = 30.0

        /** After a taper this long, below [FAST_FROM_MPH]. */
        const val POCKET_TAPER_METERS = 15.0

        /** [POCKET_FULL_LATE_METERS] from [FAST_FROM_MPH]. */
        const val POCKET_FULL_LATE_FAST_METERS = 45.0

        /** [POCKET_TAPER_METERS] from [FAST_FROM_MPH]. */
        const val POCKET_TAPER_FAST_METERS = 30.0

        /** A lane gone between two points with nothing that explains where goes this far after the first: early. */
        const val VANISH_EARLY_METERS = 30.0

        /** A lane that becomes one kept for car pools between two points does so this far after the first: early. */
        const val KINDS_EARLY_METERS = 50.0

        /** A lane that leaves at a point is there up to this far past it, so it is there at the point itself. */
        const val LEAVE_PAST_POINT_METERS = 5.0

        private const val FULL_TURN = 360
        private const val HALF_TURN = 180
        private const val FEET_PER_METER = 1.0 / 0.3048

        private val LEFT_TURNS = setOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT, TurnModifier.U_TURN)
        private val RIGHT_TURNS = setOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)

        /** The lanes of [evidence]'s route as one set. */
        fun of(evidence: LaneEvidence): LaneTopology = Builder(evidence).build()

        /** The width of a lane of [road], for tapers. */
        fun laneMeters(road: RoadKind): Double = if (road == RoadKind.STREET) STREET_LANE_METERS else FREEWAY_LANE_METERS

        /**
         * The taper of a lane that is added on a road of [road] planned at [speedMps]: [ADDED_TAPER_SLOW]
         * or, from [FAST_FROM_MPH], [ADDED_TAPER_FAST] times the lane's width (design 4.3).
         */
        fun addedTaperMeters(speedMps: Double, road: RoadKind): Double =
            laneMeters(road) * if (mph(speedMps) >= FAST_FROM_MPH) ADDED_TAPER_FAST else ADDED_TAPER_SLOW

        /**
         * The taper of a lane that ends on a road of [road] planned at [speedMps]: the lane's width times
         * the speed from [ENDING_FORMULA_FROM_MPH], below the width times the speed squared over 60 (in
         * feet and miles an hour), at least [ENDING_TAPER_MIN_METERS] (design 4.3).
         */
        fun endingTaperMeters(speedMps: Double, road: RoadKind): Double {
            val v = mph(speedMps)
            val widthFeet = laneMeters(road) * FEET_PER_METER
            val feet = if (v >= ENDING_FORMULA_FROM_MPH) widthFeet * v else widthFeet * v * v / 60.0
            return max(feet / FEET_PER_METER, ENDING_TAPER_MIN_METERS)
        }

        private fun mph(speedMps: Double): Double = if (speedMps.isFinite() && speedMps > 0.0) speedMps / PlanningSpeed.MPS_PER_MPH else 0.0

        /**
         * Of [lanes] at a point the route goes straight through, the indices of those that go on past it:
         * at [edge], the exit's side, the lanes open to every car before the first the route can be followed
         * from ([usableAll] for each lane: usable at every point of the junction) leave; at the far edge the
         * unmarked lanes leave where a road that may be entered leaves on their side ([branchLeft],
         * [branchRight]); the rest go on. A point that lets no lane be used says nothing: all go on.
         */
        fun goingOn(lanes: List<RouteLane>, usableAll: BooleanArray, edge: LaneEdge?, branchLeft: Boolean, branchRight: Boolean): List<Int> {
            val n = lanes.size
            if ((0 until n).none { usableAll[it] }) return (0 until n).toList()
            val keep = BooleanArray(n) { true }
            // The far edge, or both where the exit's side is not known: the evidence's rule 6.
            val first = lanes.indexOfFirst { it.marked }
            val last = lanes.indexOfLast { it.marked }
            if (edge != LaneEdge.LEFT && first > 0 && branchLeft) for (i in 0 until first) keep[i] = false
            if (edge != LaneEdge.RIGHT && last in 0 until n - 1 && branchRight) for (i in last + 1 until n) keep[i] = false
            // The exit's edge: the card's careful reading.
            if (edge != null) {
                for (i in if (edge == LaneEdge.RIGHT) (n - 1 downTo 0) else (0 until n)) {
                    if (lanes[i].designated) continue
                    if (usableAll[i]) break
                    keep[i] = false
                }
            }
            return (0 until n).filter { keep[it] }
        }

        /** The side of the roads that join [node] (that may not be entered), when they all lie on one side; null otherwise. */
        fun joiningSide(node: RouteNode): LaneEdge? {
            val out = node.outIndex ?: return null
            val outBearing = node.bearings.getOrNull(out) ?: return null
            var side: LaneEdge? = null
            for (j in node.bearings.indices) {
                if (j == out || j == node.inIndex || node.entry.getOrNull(j) != false) continue
                val bearing = node.bearings[j] ?: continue
                val turn = Math.floorMod(bearing - outBearing, FULL_TURN)
                val here = when {
                    turn in 1 until HALF_TURN -> LaneEdge.RIGHT
                    turn in HALF_TURN + 1 until FULL_TURN -> LaneEdge.LEFT
                    else -> return null
                }
                if (side != null && side != here) return null
                side = here
            }
            return side
        }

        /** The other edge. */
        fun far(edge: LaneEdge): LaneEdge = if (edge == LaneEdge.RIGHT) LaneEdge.LEFT else LaneEdge.RIGHT
    }

    /** A lane while the topology is being built. */
    private class Building(val id: Int, val run: Int, val slot: Double, val kinds: Set<DesignatedKind>, var opens: Double) {
        var closes = Double.POSITIVE_INFINITY
        var addedAt: Int? = null
        var fedBy: Int? = null
        var taper = 0.0
        var possibleFrom: Double? = null

        fun freeze() = Lane(id, run, slot, kinds, opens, closes, addedAt, fedBy, taper, possibleFrom)
    }

    private class Builder(val evidence: LaneEvidence) {
        private val route = evidence.route
        private val nodes = route.nodes
        private val turning = BooleanArray(nodes.size) { LaneEvidence.turnsAt(nodes[it]) }
        private val edges = evidence.edges
        private val tuning = evidence.tuning

        private val lanes = ArrayList<Building>()
        private val points = ArrayList<Point>()
        private val runs = ArrayList<Run>()
        private val alignments = ArrayList<Alignment>()

        /** The lanes on the road after the last point read, from the left. */
        private var current = ArrayList<Building>()
        private var cluster = -1
        private var minSlot = 0.0
        private var maxSlot = 0.0

        fun build(): LaneTopology {
            val lanePoints = evidence.lanePoints
            var runStart = 0
            for (k in lanePoints.indices) {
                val p = lanePoints[k]
                if (k == 0 || !connected(lanePoints[k - 1], p)) {
                    if (k > 0) endRun(runStart, k - 1)
                    startRun(p)
                    runStart = k
                } else {
                    next(lanePoints[k - 1], p)
                }
            }
            if (lanePoints.isNotEmpty()) endRun(runStart, lanePoints.size - 1)
            return LaneTopology(evidence, lanes.map { it.freeze() }, points, runs, alignments)
        }

        /** Whether the points [a] and [b], one after the other, are on one run. */
        private fun connected(a: Int, b: Int): Boolean {
            if ((a + 1 until b).any { turning[it] }) return false
            return !turning[a] || nodes[a].lanes.any { it.usable }
        }

        /** Where the road the point [p] lies on begins: the last node where the route turned before it, else the route's start. */
        private fun roadStart(p: Int): Double {
            for (j in p - 1 downTo 0) if (turning[j]) return nodes[j].station
            return 0.0
        }

        /** Where the road after the point [p] ends: the next node where the route turns after it, else the route's end. */
        private fun roadEnd(p: Int): Double {
            for (j in p + 1 until nodes.size) if (turning[j]) return nodes[j].station
            return max(route.lengthMeters, nodes.last().station)
        }

        private fun startRun(p: Int) {
            val run = runs.size
            runs.add(Run(run, roadStart(p), roadStart(p), points.size..points.size))
            current = ArrayList()
            minSlot = 0.0
            maxSlot = nodes[p].lanes.size - 1.0
            for ((i, lane) in nodes[p].lanes.withIndex()) {
                current.add(newLane(run, i.toDouble(), lane.kinds, roadStart(p)))
            }
            cluster++
            addPoint(p, current.map { it.id })
        }

        /** The run ends at its last point: past it the route's lane data says nothing more. */
        private fun endRun(first: Int, last: Int) {
            val run = runs.size - 1
            val end = min(points.last().station + LEAVE_PAST_POINT_METERS, roadEnd(points.last().node))
            for (lane in lanes) {
                if (lane.run == run && lane.closes == Double.POSITIVE_INFINITY) lane.closes = end
            }
            runs[run] = runs[run].copy(to = end, points = (points.size - (last - first + 1)) until points.size)
        }

        private fun newLane(run: Int, slot: Double, kinds: Set<DesignatedKind>, opens: Double): Building {
            val lane = Building(lanes.size, run, slot, kinds, opens)
            lanes.add(lane)
            minSlot = min(minSlot, slot)
            maxSlot = max(maxSlot, slot)
            return lane
        }

        /** The point [b] after the point [a] on the same run. */
        private fun next(a: Int, b: Int) {
            val before = points.last()
            if (oneJunction(before, a, b)) {
                addPoint(b, current.map { it.id })
                return
            }
            cluster++
            val run = runs.size - 1
            val leaveAt = nodes[a].station + LEAVE_PAST_POINT_METERS
            val goOn: List<Int> = if (turning[a]) {
                current.indices.filter { current[it].id in before.allowed }
            } else {
                val members = points.filter { it.cluster == before.cluster }.map { nodes[it.node] }
                val node = nodes[a]
                val usableAll = BooleanArray(node.lanes.size) { i -> members.all { m -> m.lanes.getOrNull(i)?.usable == true } }
                val branchLeft = members.any { LaneEvidence.branchesOff(it, LaneEdge.LEFT) }
                val branchRight = members.any { LaneEvidence.branchesOff(it, LaneEdge.RIGHT) }
                goingOn(node.lanes, usableAll, edges[a], branchLeft, branchRight)
            }
            val cont = ArrayList<Building>(goOn.size)
            for ((i, lane) in current.withIndex()) if (i in goOn) cont.add(lane) else lane.closes = min(lane.closes, leaveAt)
            align(a, b, run, cont)
        }

        /** Whether the point [b] is one junction with the point [a] before it, the last of [before]'s cluster. */
        private fun oneJunction(before: Point, a: Int, b: Int): Boolean {
            if (before.turns || turning[b]) return false
            if (nodes[b].station - nodes[a].station > RunUpRule.EDGE_CLUSTER_METERS) return false
            val la = nodes[a].lanes
            val lb = nodes[b].lanes
            return la.size == lb.size && la.indices.all { la[it].designated == lb[it].designated }
        }

        /** Reads the lanes of [b] against [cont], the lanes that go on past the point [a]. */
        private fun align(a: Int, b: Int, run: Int, cont: List<Building>) {
            val after = nodes[b].lanes
            val n0 = cont.size
            val n1 = after.size
            val edge = edges[b]
            val shift: Int
            var side: LaneEdge? = null
            var reason = Alignment.Reason.CAREFUL
            if (n0 == n1) {
                shift = 0
            } else {
                val candidates = anchored(cont, after)
                val gain = n1 > n0
                val k = if (gain) n1 - n0 else n0 - n1
                // The shift puts the change on the right with 0, on the left with the extreme one.
                fun shiftFor(s: LaneEdge): Int = if (s == LaneEdge.RIGHT) 0 else if (gain) k else -k
                val anchoredShift = candidates.singleOrNull()
                // An edge lane's arrow tells where it was added only where the point has no road of its own
                // on that side for the arrow to point to: at the exit itself, or with no such road there.
                val arrows = if (gain && edge != null && (turning[b] || !LaneEvidence.branchesOff(nodes[b], edge))) {
                    LaneEvidence.addedSide(after, k, edge)
                } else {
                    null
                }
                val node = if (gain) joinsOn(a, b) else leavesOn(a, b)
                val careful = if (edge == null) LaneEdge.RIGHT else if (gain) far(edge) else edge
                when {
                    anchoredShift != null && candidates.size < shifts(n0, n1).count() -> {
                        shift = anchoredShift
                        reason = Alignment.Reason.ANCHOR
                        side = sideOf(shift, n0, n1)
                    }
                    arrows != null && shiftFor(arrows) in candidates -> {
                        shift = shiftFor(arrows)
                        reason = Alignment.Reason.ARROWS
                        side = arrows
                    }
                    node != null && shiftFor(node) in candidates -> {
                        shift = shiftFor(node)
                        reason = Alignment.Reason.NODE
                        side = node
                    }
                    else -> {
                        shift = if (shiftFor(careful) in candidates) shiftFor(careful) else candidates.first()
                        side = sideOf(shift, n0, n1)
                    }
                }
            }
            val mapped = arrayOfNulls<Building>(n1)
            for (i in 0 until n0) {
                val j = i + shift
                if (j in 0 until n1) mapped[j] = cont[i] else cont[i].closes = min(cont[i].closes, lossAt(a, b, reason))
            }
            // Added lanes, from the inside out on each side, so each can be entered from the one inside it.
            val leftBase = minSlot
            val rightBase = maxSlot
            for (j in (0 until min(shift, n1)).reversed()) mapped[j] = added(a, b, run, j, leftBase - (shift - j), mapped.getOrNull(j + 1), LaneEdge.LEFT)
            for (j in max(shift + n0, 0) until n1) mapped[j] = added(a, b, run, j, rightBase + (j - (shift + n0) + 1), mapped.getOrNull(j - 1), LaneEdge.RIGHT)
            val next = ArrayList<Building>(n1)
            for (j in 0 until n1) {
                val lane = requireNotNull(mapped[j])
                next.add(if (lane.kinds != after[j].kinds) rekind(a, b, run, lane, after[j].kinds) else lane)
            }
            if (n0 != n1) alignments.add(Alignment(points.size - 1, points.size, n0, n1, side, reason, if (n1 > n0) opensAt(a, b) else lossAt(a, b, reason)))
            current = next
            addPoint(b, next.map { it.id })
        }

        /** The shifts that lay [after]'s lanes over [cont]: lane i of [cont] is lane i + shift of [after]. */
        private fun shifts(n0: Int, n1: Int): IntRange = if (n1 >= n0) 0..(n1 - n0) else (n1 - n0)..0

        /** Of the shifts, those that put the most lanes kept for car pools on lanes kept for car pools. */
        private fun anchored(cont: List<Building>, after: List<RouteLane>): List<Int> {
            val all = shifts(cont.size, after.size)
            var best = Int.MIN_VALUE
            val out = ArrayList<Int>()
            for (d in all) {
                var score = 0
                for (i in cont.indices) {
                    val j = i + d
                    if (j !in after.indices) continue
                    val a = cont[i].kinds.isNotEmpty()
                    val b = after[j].designated
                    if (a && b) score += ANCHOR_MATCH else if (a != b) score -= ANCHOR_MATCH
                }
                if (score > best) {
                    best = score
                    out.clear()
                }
                if (score == best) out.add(d)
            }
            return out
        }

        /** Where a shift puts the lanes added or gone: the right for 0, the left for the extreme, null for both. */
        private fun sideOf(shift: Int, n0: Int, n1: Int): LaneEdge? = when {
            shift == 0 -> LaneEdge.RIGHT
            shift == n1 - n0 -> LaneEdge.LEFT
            else -> null
        }

        /** The side roads join on between the point [a] and the point [b], when only one. */
        private fun joinsOn(a: Int, b: Int): LaneEdge? {
            var side: LaneEdge? = null
            for (j in a until b) {
                if (!LaneEvidence.joins(nodes[j])) continue
                val here = joiningSide(nodes[j]) ?: return null
                if (side != null && side != here) return null
                side = here
            }
            return side
        }

        /** The side roads that may be entered leave on after the point [a] and before the point [b], when only one. */
        private fun leavesOn(a: Int, b: Int): LaneEdge? {
            var side: LaneEdge? = null
            for (j in a + 1 until b) {
                val left = LaneEvidence.branchesOff(nodes[j], LaneEdge.LEFT)
                val right = LaneEvidence.branchesOff(nodes[j], LaneEdge.RIGHT)
                if (left && right) return null
                val here = if (left) LaneEdge.LEFT else if (right) LaneEdge.RIGHT else continue
                if (side != null && side != here) return null
                side = here
            }
            return side
        }

        /** Where lanes gone between [a] and [b] leave: at the road that explains it, else early. */
        private fun lossAt(a: Int, b: Int, reason: Alignment.Reason): Double {
            if (reason == Alignment.Reason.NODE) {
                for (j in a + 1 until b) {
                    if (LaneEvidence.branchesOff(nodes[j], LaneEdge.LEFT) || LaneEvidence.branchesOff(nodes[j], LaneEdge.RIGHT)) {
                        return nodes[j].station + LEAVE_PAST_POINT_METERS
                    }
                }
            }
            return min(nodes[a].station + VANISH_EARLY_METERS, nodes[b].station)
        }

        /** The planning speed of the road that leads to the point [b], and its kind. */
        private fun approachSpeed(b: Int): Double = PlanningSpeed.of(nodes[if (b > 0) b - 1 else b])

        /** How far before the point [b] a lane it adds opens late, and its taper. */
        private fun lateOpening(b: Int): Pair<Double, Double> {
            val road = route.approachRoad(b)
            val v = approachSpeed(b)
            return when {
                !turning[b] -> addedTaperMeters(v, road).let { it to it }
                road == RoadKind.STREET -> if (mph(v) >= FAST_FROM_MPH) {
                    (STOP_LINE_METERS + POCKET_FULL_LATE_FAST_METERS + POCKET_TAPER_FAST_METERS) to POCKET_TAPER_FAST_METERS
                } else {
                    (STOP_LINE_METERS + POCKET_FULL_LATE_METERS + POCKET_TAPER_METERS) to POCKET_TAPER_METERS
                }
                else -> (DECEL_LATE_FULL_METERS + DECEL_TAPER_METERS) to DECEL_TAPER_METERS
            }
        }

        /** Where a lane the point [b] adds opens: late, never before the point [a] before it. */
        private fun opensAt(a: Int, b: Int): Double {
            val sb = nodes[b].station
            return min(max(sb - lateOpening(b).first, nodes[a].station + LEAVE_PAST_POINT_METERS), sb)
        }

        /**
         * A lane the point [b] lists that none of the lanes going on past [a] is, lane [j] of [b], at
         * [slot], on [side] of the lanes that go on, beside [inner]. On the exit's side and open to every
         * car it is entered from [inner] along its taper, and may be there from the point before.
         */
        private fun added(a: Int, b: Int, run: Int, j: Int, slot: Double, inner: Building?, side: LaneEdge): Building {
            val lane = newLane(run, slot, nodes[b].lanes[j].kinds, opensAt(a, b))
            lane.addedAt = points.size
            if (side == edges[b] && lane.kinds.isEmpty()) {
                lane.fedBy = inner?.id
                lane.taper = lateOpening(b).second
                val possible = max(nodes[a].station, nodes[b].station - tuning.possibleLaneMaxM)
                if (possible < lane.opens) lane.possibleFrom = possible
            }
            return lane
        }

        /** [old] becomes a lane kept for [kinds] between [a] and [b]: a new lane in its place. */
        private fun rekind(a: Int, b: Int, run: Int, old: Building, kinds: Set<DesignatedKind>): Building {
            val sa = nodes[a].station
            val sb = nodes[b].station
            val at = if (kinds.isEmpty()) max(sb - addedTaperMeters(approachSpeed(b), route.approachRoad(b)), sa) else min(sa + KINDS_EARLY_METERS, sb)
            old.closes = min(old.closes, at)
            val lane = newLane(run, old.slot, kinds, at)
            lane.addedAt = points.size
            return lane
        }

        private fun addPoint(p: Int, ids: List<Int>) {
            val node = nodes[p]
            val edge = edges[p]
            val allowed = LinkedHashSet<Int>()
            val preferred = LinkedHashSet<Int>()
            var activeOutsideValid = 0
            var arrowContradicts = 0
            val away = when (edge) {
                LaneEdge.RIGHT -> LEFT_TURNS
                LaneEdge.LEFT -> RIGHT_TURNS
                null -> emptySet()
            }
            for ((i, lane) in node.lanes.withIndex()) {
                if (lane.usable) {
                    allowed.add(ids[i])
                    if (lane.active == true) preferred.add(ids[i])
                    if (turning[p] && lane.indications.size == 1 && lane.indications.first() in away) arrowContradicts++
                }
                if (lane.active == true && !lane.usable) activeOutsideValid++
            }
            points.add(
                Point(
                    node = p,
                    station = node.station,
                    run = runs.size - 1,
                    cluster = cluster,
                    lanes = ids,
                    turns = turning[p],
                    edge = edge,
                    allowed = allowed,
                    preferred = preferred,
                    activeOutsideValid = activeOutsideValid,
                    arrowContradicts = arrowContradicts,
                ),
            )
        }

        private companion object {
            /** What a lane kept for car pools on one in the other list scores, and one on an open lane costs. */
            const val ANCHOR_MATCH = 10
        }
    }
}
