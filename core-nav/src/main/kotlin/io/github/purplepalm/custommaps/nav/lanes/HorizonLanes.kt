package io.github.purplepalm.custommaps.nav.lanes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The lane counts of Mapbox's electronic horizon between lane points, as the drawing of the app's own lanes takes them
 * (the own lanes' design 2.1, 3.2 items 4, 5, 7 and 7a; OL9). Between two lane points in a row on one freeway
 * carriageway ([Between]), A and B, no node where the route turns between them and every edge from the one that leads to A
 * to the one that leads to B a motorway and no ramp, the horizon's counts are read only where they agree with the route's
 * own lists ([HorizonConvention]): the edge that ends at A counts A's lanes and the edge that ends at B counts B's, both
 * with every listed lane or both with the lanes open to every car. With neither, nothing of the horizon is taken there.
 * With one, between A and B ([Piece]):
 *
 * - where the horizon's count is the lanes the plan draws, a stretch without lane data is bridged at that count: lane
 *   lines, no arrows, no tints and no lane of its own ([Piece.Kind.BRIDGED]); the plan's count from the exit's edge
 *   carries through it as through any bridge (P8);
 * - a change of the count at an edge end where the plan's lanes do not change is a zone of 2 x taper + 60 m without lines
 *   ending at that edge end (the evidence's rule 5: its side is not known), as is any stretch where the horizon counts
 *   other lanes than the plan draws ([Piece.Kind.CHANGE], [Piece.Kind.DIFFERS]): lines there would draw a count the
 *   horizon contradicts;
 * - a gain the plan puts on the far side from the exit's edge, which it opens late because nothing says where it begins,
 *   opens where the horizon's count rises by as many lanes instead, inside its zone ending at that edge end, in place of
 *   the zone placed late ([placed], [Piece.Kind.PLACED]): a lane on the far side counts for no lane from the exit's edge.
 *
 * Nothing here names, lights or tints a lane, and the plan's counts never change: a lane the plan adds on the exit's
 * side, one that may already be there (2a), the lanes of a lane point's own reach, the approaches where no lane is named
 * (C1) and roundabouts stay as the plan made them. The counts' meaning is not verified (do they count one direction, and
 * lanes kept for car pools? E17): the agreement at both lane points is the gate. Pure; made off the main thread from one
 * update's stretches ([HorizonAhead]) and never stored. It holds no edge id, shape or name.
 */
class HorizonLanes private constructor(
    /** The navigator's id of the route. */
    val routeId: String,
    /** Every two lane points in a row on one freeway carriageway that the horizon covered, in route order. */
    val between: List<Between>,
) {

    /** From [from] to [to] metres along the route the horizon counts [lanes] lanes, as it gives them. */
    data class Count(val from: Double, val to: Double, val lanes: Int) {
        val meters: Double get() = to - from
    }

    /** What the drawing takes from the horizon from [from] to [to] metres along the route, where it draws [lanes] lanes. */
    data class Piece(val from: Double, val to: Double, val kind: Kind, val lanes: Int) {
        val meters: Double get() = to - from

        enum class Kind {
            /** The horizon's count is the lanes the plan draws: lane lines where the route has no lane data. */
            BRIDGED,

            /** The count changes at an edge end where the plan's lanes do not: a zone ending at that edge end. */
            CHANGE,

            /** The horizon counts other lanes than the plan draws here: no lines. */
            DIFFERS,

            /** The zone of a gain on the far side placed where the horizon's count rises, ending at that edge end. */
            PLACED,
        }
    }

    /**
     * Two lane points in a row on one freeway carriageway: [before] and [after], indices into the plan's topology points, at
     * [from] and [to] metres along the route; [afterNode] is the intersection of the second. [convention]: the reading under
     * which the horizon's count of the edge that leads to each equals that point's list, [HorizonConvention.NONE] where none
     * does, and nothing of the horizon is taken. [counts]: the horizon's count between them, as it gives it; [stretches]: how
     * many of the horizon's stretches lie between them. [placed]: where the plan's gain on the far side opens instead of
     * late, the edge end where the count rises; null where none is placed. [pieces]: what the drawing takes, in route order.
     */
    data class Between(
        val before: Int,
        val after: Int,
        val from: Double,
        val to: Double,
        val afterNode: Int,
        val convention: HorizonConvention,
        val counts: List<Count>,
        val stretches: Int,
        val placed: Double?,
        val pieces: List<Piece>,
    )

    /** Whether the horizon gave the drawing anything here. */
    val isEmpty: Boolean get() = between.none { it.pieces.isNotEmpty() }

    /** Where each placed gain opens, the edge end where the count rises, by the intersection of the point that first lists its lanes. */
    val placedGains: Map<Int, Double> by lazy {
        between.mapNotNull { b -> b.placed?.let { b.afterNode to it } }.toMap()
    }

    /** How many of the horizon's stretches lie between the points it agrees with: those the drawing takes from it. */
    val stretchesUsed: Int get() = between.filter { it.convention != HorizonConvention.NONE }.sumOf { it.stretches }

    /** The pairs of lane points whose first lies at or past [station]: what a drawing window sent from there takes. */
    fun from(station: Double): HorizonLanes = HorizonLanes(routeId, between.filter { it.from >= station })

    /** The pairs whose first lies before [station]: what the windows sent before took, which stays. */
    fun before(station: Double): HorizonLanes = HorizonLanes(routeId, between.filter { it.from < station })

    /** These pairs and [other]'s, of the same route, in route order. */
    operator fun plus(other: HorizonLanes): HorizonLanes {
        require(other.routeId == routeId || other.between.isEmpty() || between.isEmpty()) { "Another route's horizon" }
        val id = if (between.isEmpty()) other.routeId else routeId
        return HorizonLanes(id, (between + other.between).sortedBy { it.from })
    }

    companion object {

        /** Nothing from the horizon for the route [routeId]. */
        fun none(routeId: String): HorizonLanes = HorizonLanes(routeId, emptyList())

        /**
         * What [plan]'s drawing takes from [ahead], one update of the horizon along the plan's route: every pair of lane
         * points in a row on one freeway carriageway that the stretches cover entirely, from the car on, an edge ending at
         * each point within [tolerance] ([HorizonLanesLine.MATCH_TOLERANCE_M]: an intersection's lanes describe its
         * approach) and the stretches reaching past the second by as much. A pair with an edge of no count, a ramp, a road
         * that is no motorway or a node where the route turns is left alone, as is one the stretches do not reach.
         */
        fun of(plan: LanePlan, ahead: HorizonAhead, tolerance: Double = HorizonLanesLine.MATCH_TOLERANCE_M): HorizonLanes {
            val topology = plan.topology
            val routeId = topology.evidence.route.routeId
            if (ahead.routeId != routeId || ahead.stretches.isEmpty()) return none(routeId)
            val out = ArrayList<Between>()
            for (k in 0 until topology.points.size - 1) {
                between(plan, k, ahead, tolerance)?.let { out.add(it) }
            }
            return HorizonLanes(routeId, out)
        }

        /** The pair of the topology's points [k] and k + 1, as [ahead] covers it; null where it is left alone. */
        private fun between(plan: LanePlan, k: Int, ahead: HorizonAhead, tolerance: Double): Between? {
            val topology = plan.topology
            val route = topology.evidence.route
            val nodes = route.nodes
            val a = topology.points[k]
            val b = topology.points[k + 1]
            if (a.run != b.run || a.turns) return null
            // Covered entirely, from the car on.
            if (a.station < ahead.from || ahead.to < b.station + tolerance) return null
            // Two points of one junction leave nothing between them.
            if (b.station - a.station <= 2.0 * tolerance) return null
            // One freeway carriageway: a freeway leads to the first, and on from it to the second, with no turn between.
            if (route.approachRoad(a.node) != RoadKind.FREEWAY) return null
            for (j in a.node until b.node) if (nodes[j].road != RoadKind.FREEWAY) return null
            for (j in a.node + 1 until b.node) if (LaneEvidence.planTurns(nodes[j])) return null
            val ia = matchedEnd(ahead, a.station, tolerance) ?: return null
            val ib = matchedEnd(ahead, b.station, tolerance) ?: return null
            if (ib <= ia) return null
            for (i in ia..ib) {
                val edge = ahead.stretches[i].edge
                if (edge.laneCount == null || !edge.motorway || edge.ramp) return null
            }
            val countA = requireNotNull(ahead.stretches[ia].edge.laneCount)
            val countB = requireNotNull(ahead.stretches[ib].edge.laneCount)
            val listedA = nodes[a.node].lanes
            val listedB = nodes[b.node].lanes
            val convention = when {
                countA == listedA.size && countB == listedB.size -> HorizonConvention.ALL
                countA == listedA.count { !it.designated } && countB == listedB.count { !it.designated } -> HorizonConvention.GENERAL
                else -> HorizonConvention.NONE
            }
            val counts = profile(ahead, ia, ib, a.station, b.station, tolerance)
            val stretches = ib - ia
            if (convention == HorizonConvention.NONE) {
                return Between(k, k + 1, a.station, b.station, b.node, convention, counts, stretches, null, emptyList())
            }
            val general = convention == HorizonConvention.GENERAL
            val lanes = topology.lanesOf(a.run)
            // The horizon's changes between the points, apart from those at a point itself.
            val changes = counts.dropLast(1).map { it.to }.filter { it > a.station + tolerance && it < b.station - tolerance }
            val placed = placedGain(plan, k, counts, changes, general, tolerance)
            val moved = placed?.let { x -> addedLanes(topology, k).associate { it.id to x } }.orEmpty()
            val drawn = snapped(drawnProfile(plan, lanes, a.station, b.station, general, moved), counts.map { it.from } + b.station, tolerance)
            val all = if (general) drawnProfile(plan, lanes, a.station, b.station, false, moved) else null
            val pieces = ArrayList<Piece>()
            // Where the counts agree, a bridge at the lanes the plan draws; where they do not, no lines.
            for ((from, to, agree) in compare(counts, drawn)) {
                val drawnLanes = lanesAt(all ?: drawn, (from + to) / 2.0)
                pieces.add(Piece(from, to, if (agree) Piece.Kind.BRIDGED else Piece.Kind.DIFFERS, max(1, drawnLanes)))
            }
            // A change at an edge end where the plan's lanes do not change: its zone, placed at that edge end.
            val drawnChanges = drawn.dropLast(1).map { it.to }
            for (x in changes) {
                val before = lanesAt(counts, x - SAME)
                val after = lanesAt(counts, x + SAME)
                if (x == placed) {
                    pieces.add(Piece(max(a.station, x - zoneMeters(route, x, after > before)), x, Piece.Kind.PLACED, max(1, lanesAt(all ?: drawn, x + SAME))))
                    continue
                }
                val matched = drawnChanges.any { abs(it - x) <= SAME && lanesAt(drawn, x + SAME) - lanesAt(drawn, x - SAME) == after - before }
                if (matched) continue
                val from = max(a.station, x - zoneMeters(route, x, after > before))
                pieces.add(Piece(from, x, Piece.Kind.CHANGE, max(1, max(lanesAt(all ?: drawn, from + SAME), lanesAt(all ?: drawn, x - SAME)))))
            }
            pieces.sortWith(compareBy<Piece>({ it.from }, { it.kind.ordinal }))
            return Between(k, k + 1, a.station, b.station, b.node, convention, counts, stretches, placed, pieces)
        }

        /** The index of the stretch of [ahead] whose end lies nearest [station], within [tolerance]; null where none does. */
        private fun matchedEnd(ahead: HorizonAhead, station: Double, tolerance: Double): Int? {
            val nearest = ahead.endNearest(station) ?: return null
            if (abs(nearest.end - station) > tolerance) return null
            val i = ahead.stretches.indexOfFirst { it === nearest }
            return i.takeIf { it >= 0 }
        }

        /**
         * The horizon's count from [from] to [to]: the stretches after the one at index [ia], which leads to the first point,
         * up to the one at index [ib], which leads to the second; the first taken back to [from] and the last on to [to]
         * where their ends lie a little off the points; merged where the count does not change. An edge end within
         * [tolerance] of a point is that point's: a change there is the point's own, a few metres off.
         */
        private fun profile(ahead: HorizonAhead, ia: Int, ib: Int, from: Double, to: Double, tolerance: Double): List<Count> {
            val out = ArrayList<Count>()
            for (i in ia + 1..ib) {
                val s = ahead.stretches[i]
                var t = if (i == ib) to else s.end.coerceIn(from, to)
                if (t <= from + tolerance) t = from
                if (t >= to - tolerance) t = to
                // Each from where the one before ends: the first from the first point.
                val start = out.lastOrNull()?.to ?: from
                if (t <= start) continue
                val lanes = requireNotNull(s.edge.laneCount)
                val last = out.lastOrNull()
                if (last != null && last.lanes == lanes) out[out.size - 1] = last.copy(to = t) else out.add(Count(start, t, lanes))
            }
            return out
        }

        /**
         * The lanes the plan draws from [from] to [to] of the run whose [lanes] are given ([CardPlanRow.drawnAt]: there, or
         * possibly there and not named), only those open to every car where [general], as counts where they do not change;
         * a lane of [moved] counted from the station given for it instead of where it opens.
         */
        private fun drawnProfile(plan: LanePlan, lanes: List<LaneTopology.Lane>, from: Double, to: Double, general: Boolean, moved: Map<Int, Double>): List<Count> {
            val counted = lanes.filter { !general || !it.designated }
            val breaks = ArrayList<Double>()
            for (lane in counted) {
                val opens = moved[lane.id] ?: lane.opens
                breaks.add(opens)
                breaks.add(lane.closes)
                lane.possibleFrom?.let { breaks.add(it) }
            }
            val stations = (listOf(from) + breaks.filter { it > from && it < to } + to).distinct().sorted()
            val out = ArrayList<Count>()
            for (i in 0 until stations.size - 1) {
                val mid = (stations[i] + stations[i + 1]) / 2.0
                val n = counted.count { lane ->
                    val opens = moved[lane.id]
                    if (opens != null) mid >= opens && mid < lane.closes else CardPlanRow.drawnAt(lane, plan, mid)
                }
                val last = out.lastOrNull()
                if (last != null && last.lanes == n) out[out.size - 1] = last.copy(to = stations[i + 1]) else out.add(Count(stations[i], stations[i + 1], n))
            }
            return out
        }

        /** [counts] with every change that lies within [tolerance] of one of [at] moved there: a change a few metres off is the same. */
        private fun snapped(counts: List<Count>, at: List<Double>, tolerance: Double): List<Count> {
            if (counts.size < 2) return counts
            val bounds = DoubleArray(counts.size + 1)
            bounds[0] = counts.first().from
            for (i in counts.indices) bounds[i + 1] = counts[i].to
            for (i in 1 until counts.size) {
                val near = at.minByOrNull { abs(it - bounds[i]) }
                if (near != null && abs(near - bounds[i]) <= tolerance) bounds[i] = near
                bounds[i] = bounds[i].coerceIn(bounds[i - 1], bounds[bounds.size - 1])
            }
            val out = ArrayList<Count>()
            for (i in counts.indices) {
                if (bounds[i + 1] <= bounds[i]) continue
                val last = out.lastOrNull()
                if (last != null && last.lanes == counts[i].lanes) out[out.size - 1] = last.copy(to = bounds[i + 1]) else out.add(Count(bounds[i], bounds[i + 1], counts[i].lanes))
            }
            return out
        }

        /** The stretches between [counts] and [drawn] (over the same span) where the two agree or do not, merged, as (from, to, agree). */
        private fun compare(counts: List<Count>, drawn: List<Count>): List<Triple<Double, Double, Boolean>> {
            val stations = (counts.flatMap { listOf(it.from, it.to) } + drawn.flatMap { listOf(it.from, it.to) }).distinct().sorted()
            val out = ArrayList<Triple<Double, Double, Boolean>>()
            for (i in 0 until stations.size - 1) {
                val from = stations[i]
                val to = stations[i + 1]
                if (to - from <= SAME) continue
                val mid = (from + to) / 2.0
                val agree = lanesAt(counts, mid) == lanesAt(drawn, mid)
                val last = out.lastOrNull()
                // A bridge's count changes where the horizon's does: a piece of its own for each count.
                val sameCount = last != null && lanesAt(counts, (last.first + last.second) / 2.0) == lanesAt(counts, mid)
                if (last != null && last.third == agree && (!agree || sameCount)) out[out.size - 1] = Triple(last.first, to, agree) else out.add(Triple(from, to, agree))
            }
            return out
        }

        /** The lanes [counts] give at [station]: the first's before them, the last's after. */
        private fun lanesAt(counts: List<Count>, station: Double): Int {
            if (counts.isEmpty()) return 0
            for (c in counts) if (station < c.to) return c.lanes
            return counts.last().lanes
        }

        /**
         * Where the horizon places the gain between the points [k] and k + 1 that the plan puts on the far side from the
         * exit's edge: the plan's own alignment there gains lanes on that side, the horizon's count rises by as many lanes
         * (as the convention counts them) at one edge end between the points and nowhere else, from the lanes the plan draws
         * past the first to those it draws before the second, and that edge end lies no later than where the plan opens them,
         * with room for their taper after the first point. Null otherwise: the plan's late opening stays.
         */
        private fun placedGain(plan: LanePlan, k: Int, counts: List<Count>, changes: List<Double>, general: Boolean, tolerance: Double): Double? {
            val topology = plan.topology
            val alignment = topology.alignments.firstOrNull { it.before == k && it.after == k + 1 } ?: return null
            val b = topology.points[k + 1]
            val edge = b.edge ?: return null
            if (alignment.change <= 0 || alignment.side != LaneTopology.far(edge)) return null
            if (changes.size != 1) return null
            val added = addedLanes(topology, k)
            if (added.size != alignment.change) return null
            val gained = added.count { !general || !it.designated }
            val x = changes.single()
            val before = lanesAt(counts, x - SAME)
            val after = lanesAt(counts, x + SAME)
            if (gained == 0 || after - before != gained) return null
            val a = topology.points[k]
            val lanes = topology.lanesOf(a.run)
            val plain = drawnProfile(plan, lanes, a.station, b.station, general, emptyMap())
            if (lanesAt(plain, a.station + tolerance) != before || lanesAt(plain, b.station - tolerance) != after) return null
            val route = topology.evidence.route
            val taper = LaneTopology.addedTaperMeters(PlanningSpeed.of(route.nodes[if (b.node > 0) b.node - 1 else b.node]), RoadKind.FREEWAY)
            if (x > added.minOf { it.opens } + SAME || x - taper < a.station + LaneTopology.LEAVE_PAST_POINT_METERS) return null
            return x
        }

        /**
         * The lanes the point k + 1 adds to those that go on past the point [k] of the same run: first listed there, and in a
         * slot of their own (not a lane whose kinds changed, which takes another's place).
         */
        internal fun addedLanes(topology: LaneTopology, k: Int): List<LaneTopology.Lane> {
            val run = topology.points[k + 1].run
            val lanes = topology.lanesOf(run)
            return lanes.filter { lane ->
                lane.addedAt == k + 1 && lanes.none { it.id != lane.id && it.slot == lane.slot && abs(it.closes - lane.opens) < SAME }
            }
        }

        /** The zone of a change at [x]: twice the taper of a gain ([gain]) or of a lane that ends, and [LaneEvidence.ZONE_MIDDLE_METERS]. */
        private fun zoneMeters(route: LaneRoute, x: Double, gain: Boolean): Double {
            val node = route.nodes.lastOrNull { it.station <= x } ?: route.nodes.first()
            val speed = PlanningSpeed.of(node)
            val taper = if (gain) LaneTopology.addedTaperMeters(speed, RoadKind.FREEWAY) else LaneTopology.endingTaperMeters(speed, RoadKind.FREEWAY)
            return 2.0 * taper + LaneEvidence.ZONE_MIDDLE_METERS
        }

        /** Stations closer than this are one. */
        private const val SAME = 1e-6
    }
}
