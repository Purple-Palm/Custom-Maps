package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.model.LaneEdge
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The lane plan of a route: which lanes work at every station and, inside the card windows, the one
 * lane the map, the card and the words name (the design's 5.1 and 5.2: GEOM's planner with P3, P6 and
 * F1). Its inputs are the route's lanes as one set ([LaneTopology]: every lane with its kinds, every
 * lane point with the lanes it allows and prefers, every maneuver), the planning speed of each road
 * ([PlanningSpeed], fixed per route, never the car's speed now) and the card windows; nothing about
 * the car, and nothing about where across the road it is.
 *
 * - **Allowed** at a point: the lanes the route can be followed from (valid, else preferred), never a
 *   lane kept for car pools and the like; **preferred**: the allowed lanes the route prefers, a
 *   tie-break only.
 * - **Feasibility**, worked backward on a grid of [GRID_METERS]: from a lane at a station every later
 *   point can be met, with the fewest lane changes. A change takes the planning speed times
 *   [COMFORTABLE_CHANGE_SECONDS], or [LATEST_CHANGE_SECONDS] at the latest, goes to a neighbour that is
 *   there for all of it, never across a lane kept for car pools, and never across a point unless both
 *   lanes are allowed there; following the taper into a lane a lane feeds is no change.
 * - **The band** at a station: the lanes still feasible with comfortable changes [REACTION_SECONDS] of
 *   driving later (a [Flag.Kind.TIGHT] fallback to the latest changes, a [Flag.Kind.CONFLICT] where
 *   none is left, and there the nearer maneuver wins: the band that meets it). On a freeway the band
 *   narrows for an exit no later than [FREEWAY_FLOOR_METERS] before the gore (F1): from there only
 *   lanes that reach the exit's lanes without a change. Between two maneuvers the band only narrows:
 *   a lane that left it does not come back.
 * - **The named lane** (P6, E6): inside a card window, one lane of the band even while every lane
 *   works: the fewest changes to every point ahead, then preferred at the next point, then the lane
 *   nearest the maneuver's edge. It stays while it stays in the band and moves early, to the next
 *   point's preferred lane or one that needs fewer changes. Outside card windows no lane is named
 *   (P3): the band is kept and the path follows it.
 *
 * Pure and deterministic; built once for a route, off the main thread, and never stored.
 */
object LanePlanner {

    /** The planner's grid, in metres along the route. */
    const val GRID_METERS = 5.0

    /** The band keeps this much driving of reaction ahead of every narrowing. */
    const val REACTION_SECONDS = 2.5

    /** A comfortable lane change: the planning speed times this. */
    const val COMFORTABLE_CHANGE_SECONDS = 9.3

    /** The latest lane change: the planning speed times this. */
    const val LATEST_CHANGE_SECONDS = 7.6

    /** On a freeway the band narrows for an exit no later than this before the gore (the half mile of exit-only paint). */
    const val FREEWAY_FLOOR_METERS = 805.0

    /**
     * How far before the point where the last of [changes] lane changes must be done they begin, at
     * [speedMps]: the reaction time and [changes] comfortable or latest changes of driving.
     */
    fun changeMeters(changes: Int, speedMps: Double, comfortable: Boolean): Double =
        speedMps * (REACTION_SECONDS + changes * if (comfortable) COMFORTABLE_CHANGE_SECONDS else LATEST_CHANGE_SECONDS)

    /** The plan of [topology]'s route, naming lanes inside [windows]. */
    fun plan(topology: LaneTopology, windows: List<CardWindow> = CardWindows.latched(topology.evidence.route)): LanePlan {
        val runs = topology.runs.map { RunPlanner(topology, it, windows).plan() }
        return LanePlan(topology, windows, runs)
    }
}

/** A stretch of the plan where nothing changes: its band, its named lane and where that lane is. */
data class PlanSegment(
    /** Where it begins, in metres along the route. */
    val from: Double,
    /** Where it ends, not included. */
    val to: Double,
    /** The run it is on ([LaneTopology.runs]). */
    val run: Int,
    /** The lanes that work, as lane ids of the topology, from the left. */
    val band: List<Int>,
    /** The lane named here; null where none is (outside card windows, or where no lane works). */
    val named: Int?,
    /**
     * Where the named lane is: how many lanes open to every car lie between it and the exit's edge,
     * lanes that may already be there counted ([LaneTopology.fromEdge] with the plan's named lanes).
     */
    val namedFromEdge: Int?,
    /** The side of the next exit, the edge counts are from. */
    val edge: LaneEdge?,
    /** Whether it lies in a card window. */
    val inWindow: Boolean,
    /** A tight or conflicting stretch; null where the band is comfortable. */
    val flag: LanePlan.Flag.Kind?,
)

/** The lane plan of a route ([LanePlanner.plan]). */
class LanePlan internal constructor(
    val topology: LaneTopology,
    /** The card windows lanes are named in. */
    val windows: List<CardWindow>,
    runs: List<RunResult>,
) {
    /** A stretch where the band is not comfortable. */
    data class Flag(val kind: Kind, val from: Double, val to: Double) {
        enum class Kind {
            /** Only the latest lane changes meet every point ahead. */
            TIGHT,

            /** No lane meets every point ahead; the band meets the nearer maneuver. */
            CONFLICT,
        }

        /** How long the plan lacks a comfortable band here: the deficit, in metres. */
        val deficitMeters: Double get() = to - from
    }

    /**
     * The named lane moves from lane [from] to lane [to] at [station]; [fromEdge] and [toEdge] count it
     * from the exit's edge just before and at the move, lanes that are there only. [back]: it moves away
     * from the edge without a maneuver between, which the plan should never do.
     */
    data class Move(val station: Double, val from: Int, val to: Int, val fromEdge: Int?, val toEdge: Int?, val back: Boolean)

    /** The plan in route order, stretch by stretch. */
    val segments: List<PlanSegment> = runs.flatMap { it.segments }

    val flags: List<Flag> = runs.flatMap { it.flags }

    val moves: List<Move> = runs.flatMap { it.moves }

    /** Every lane the plan names somewhere. */
    val named: Set<Int> = segments.mapNotNullTo(LinkedHashSet()) { it.named }

    /** The stretch of the plan at [station]: the same object anywhere in it; null outside every run. */
    fun segmentAt(station: Double): PlanSegment? {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val segment = segments[mid]
            when {
                station < segment.from -> hi = mid - 1
                station >= segment.to -> lo = mid + 1
                else -> return segment
            }
        }
        return null
    }

    /** The second pass of the evidence for this plan ([LaneEvidence.withPlan]). */
    fun evidence(): LaneEvidence = topology.evidence.withPlan(topology, named, windows)

    internal class RunResult(val segments: List<PlanSegment>, val flags: List<Flag>, val moves: List<Move>)
}

/** The plan of one run of the topology. */
private class RunPlanner(val topology: LaneTopology, val run: LaneTopology.Run, val windows: List<CardWindow>) {
    private val route = topology.evidence.route
    private val nodes = route.nodes

    /** The run's lanes; a lane's local index is its place here. */
    private val lanes = topology.lanes.filter { it.run == run.index }
    private val size = lanes.size
    private val local = HashMap<Int, Int>().apply { lanes.forEachIndexed { i, lane -> put(lane.id, i) } }

    /** The grid's stations, from the run's start up to but not at its end, where its lanes are no longer there. */
    private val n = max(1, ceil((run.to - run.from) / LanePlanner.GRID_METERS - 1e-9).toInt())
    private val stations = DoubleArray(n) { run.from + it * LanePlanner.GRID_METERS }

    /** For each lane, the lanes it feeds along a taper (local indices). */
    private val feeds: Array<IntArray> = Array(size) { l -> (0 until size).filter { f -> lanes[f].fedBy == lanes[l].id && !lanes[f].designated }.toIntArray() }

    /** For each lane, the stations it is there at. */
    private val exists = Array(size) { l -> BitSet(n).apply { for (i in 0 until n) if (lanes[l].exists(stations[i])) set(i) } }

    /** At each station, the lanes there, from the left (shared between stations where they are the same). */
    private val order: Array<IntArray> = run {
        val out = arrayOfNulls<IntArray>(n)
        var last: IntArray? = null
        for (i in 0 until n) {
            val here = (0 until size).filter { exists[it][i] }.sortedBy { lanes[it].slot }.toIntArray()
            out[i] = if (last != null && last.contentEquals(here)) last else here
            last = out[i]
        }
        Array(n) { requireNotNull(out[it]) }
    }

    /** The planning speed at each station: the road's that leaves the last node at or before it. */
    private val speeds = DoubleArray(n).also { speeds ->
        var k = 0
        for (i in 0 until n) {
            while (k + 1 < nodes.size && nodes[k + 1].station <= stations[i]) k++
            speeds[i] = PlanningSpeed.of(nodes[k])
        }
    }

    /** The side of the next exit at each station. */
    private val edges = arrayOfNulls<LaneEdge>(n).also { edges ->
        var k = 0
        for (i in 0 until n) {
            while (k < nodes.size && nodes[k].station < stations[i]) k++
            edges[i] = topology.evidence.edges.getOrNull(k)
        }
    }

    /** Whether each station lies in a card window. */
    private val inWindow = BooleanArray(n) { i -> windows.any { stations[i] >= it.from && stations[i] <= it.to } }

    /** A point of the run that says where to drive, on the grid: its station's index, its intersection and what it allows. */
    private class Rule(val index: Int, val station: Double, val node: Int, val allowed: BitSet, val preferred: BitSet, val maneuver: Boolean)

    private val rules: List<Rule> = topology.points.filter { it.run == run.index && it.constrains }.map { point ->
        Rule(nearest(point.station), point.station, point.node, bits(point.allowed), bits(point.preferred), point.turns)
    }

    /** A freeway floor (F1): from [from] to the gore at [to], the band holds only the lanes in [lanes]. */
    private class Floor(val from: Int, val to: Int, val lanes: BitSet)

    /**
     * The freeway floors (F1): from [LanePlanner.FREEWAY_FLOOR_METERS] before a freeway exit to its gore
     * only the lanes that reach the exit's lanes without a change: those lanes, and the lanes that feed
     * them along a taper.
     */
    private val floors: List<Floor> = rules.filter { it.maneuver && isFreewayExit(it) }.map { exit ->
        val reach = exit.allowed.clone() as BitSet
        var grew = true
        while (grew) {
            grew = false
            for (l in 0 until size) {
                val fed = lanes[l].fedBy?.let { local[it] } ?: continue
                if (reach.get(l) && !reach.get(fed)) {
                    reach.set(fed)
                    grew = true
                }
            }
        }
        Floor(nearest(max(exit.station - LanePlanner.FREEWAY_FLOOR_METERS, run.from)), exit.index, reach)
    }

    /** An exit or fork the route takes from a freeway. */
    private fun isFreewayExit(rule: Rule): Boolean =
        route.approachRoad(rule.node) == RoadKind.FREEWAY && nodes[rule.node].maneuver?.viewStaysUp == true

    private fun nearest(station: Double): Int = ((station - run.from) / LanePlanner.GRID_METERS + 0.5).toInt().coerceIn(0, n - 1)

    private fun bits(ids: Set<Int>): BitSet = BitSet(size).apply { for (id in ids) local[id]?.let { set(it) } }

    /** What the rules at each station allow (null for none): every rule there at once. */
    private fun allowedAt(upTo: Int): Array<BitSet?> {
        val out = arrayOfNulls<BitSet>(n)
        for (rule in rules) {
            if (rule.index > upTo) continue
            val here = out[rule.index]
            if (here == null) out[rule.index] = rule.allowed.clone() as BitSet else here.and(rule.allowed)
        }
        return out
    }

    /**
     * The fewest lane changes from each lane at each station to every rule up to the station [upTo],
     * with changes of [seconds]; [INF] where none works.
     */
    private fun costs(seconds: Double, allowed: Array<BitSet?>, upTo: Int): IntArray {
        val cost = IntArray(n * size) { INF }
        val nextRule = IntArray(n + 1).also { next ->
            next[n] = n
            for (i in n - 1 downTo 0) next[i] = if (allowed[i] != null) i else next[i + 1]
        }

        fun clear(l: Int, m: Int, from: Int, to: Int): Boolean {
            var j = nextRule[from]
            while (j < to) {
                val rule = allowed[j]
                if (rule != null && (!rule.get(l) || !rule.get(m))) return false
                j = nextRule[j + 1]
            }
            return true
        }
        for (i in upTo downTo 0) {
            val s = stations[i]
            for (l in order[i]) {
                if (lanes[l].designated) continue
                val rule = allowed[i]
                if (rule != null && !rule.get(l)) continue
                if (i == upTo) {
                    cost[i * size + l] = 0
                    continue
                }
                var best = INF
                if (exists[l].get(i + 1)) best = cost[(i + 1) * size + l]
                val span = speeds[i] * seconds
                var end = i
                while (end < upTo && stations[end] < s + span) end++
                val reachesEnd = !(end == upTo && stations[end] < s + span - 1e-9)
                if (reachesEnd) {
                    val k = order[i].indexOf(l)
                    for (m in intArrayOf(order[i].getOrElse(k - 1) { -1 }, order[i].getOrElse(k + 1) { -1 })) {
                        if (m < 0 || lanes[m].designated) continue
                        if (exists[m].nextClearBit(i) <= end) continue
                        if (exists[l].nextClearBit(i) <= (i + end) / 2) continue
                        if (!clear(l, m, i + 1, end)) continue
                        val next = cost[end * size + m]
                        if (next != INF) best = min(best, next + 1)
                    }
                }
                for (f in feeds[l]) {
                    if (!exists[f].get(i)) continue
                    var taperEnd = i
                    while (taperEnd < upTo && stations[taperEnd] < s + lanes[f].taperMeters) taperEnd++
                    if (taperEnd == i || !clear(l, f, i + 1, taperEnd)) continue
                    val next = cost[taperEnd * size + f]
                    if (next != INF) best = min(best, next)
                }
                cost[i * size + l] = best
            }
        }
        return cost
    }

    fun plan(): LanePlan.RunResult {
        val last = n - 1
        val comfortable = costs(LanePlanner.COMFORTABLE_CHANGE_SECONDS, allowedAt(last), last)
        val latest = costs(LanePlanner.LATEST_CHANGE_SECONDS, allowedAt(last), last)
        val bands = arrayOfNulls<IntArray>(n)
        val flags = arrayOfNulls<LanePlan.Flag.Kind>(n)
        val costOf = arrayOfNulls<IntArray>(n)
        val maneuvers = rules.filter { it.maneuver }
        // The nearer maneuver's own plan, for a stretch where no lane meets every point ahead.
        val near = HashMap<Int, Pair<IntArray, IntArray>>()
        for (i in 0 until n) {
            var band = comfortableBand(i, comfortable, last)
            var source = comfortable
            if (band.isEmpty() && order[i].any { !lanes[it].designated }) {
                band = latestBand(i, latest)
                flags[i] = LanePlan.Flag.Kind.TIGHT
                if (band.isEmpty()) {
                    flags[i] = LanePlan.Flag.Kind.CONFLICT
                    val maneuver = maneuvers.firstOrNull { it.index >= i }
                    if (maneuver != null && maneuver.index < last) {
                        val (show, late) = near.getOrPut(maneuver.index) {
                            val upTo = maneuver.index
                            costs(LanePlanner.COMFORTABLE_CHANGE_SECONDS, allowedAt(upTo), upTo) to
                                costs(LanePlanner.LATEST_CHANGE_SECONDS, allowedAt(upTo), upTo)
                        }
                        band = comfortableBand(i, show, maneuver.index)
                        source = show
                        if (band.isEmpty()) band = latestBand(i, late)
                    }
                }
            }
            // F1: close to a freeway exit only the lanes that reach its lanes without a change, where any is left.
            for (floor in floors) {
                if (i < floor.from || i > floor.to) continue
                val held = band.filter { floor.lanes.get(it) }.toIntArray()
                if (held.isNotEmpty()) band = held
            }
            // Between two maneuvers the band only narrows.
            if (i > 0 && maneuvers.none { stations[i - 1] < it.station && it.station <= stations[i] }) {
                val before = requireNotNull(bands[i - 1])
                val narrowed = band.filter { it in before || !exists[it].get(i - 1) }.toIntArray()
                if (narrowed.isNotEmpty()) band = narrowed
            }
            bands[i] = band
            costOf[i] = source
        }
        val named = name(bands, costOf)
        return results(bands, flags, named)
    }

    private fun comfortableBand(i: Int, cost: IntArray, upTo: Int): IntArray {
        val reaction = stations[i] + speeds[i] * LanePlanner.REACTION_SECONDS
        var j = i
        while (j < upTo && stations[j] < reaction) j++
        return order[i].filter { l ->
            !lanes[l].designated && cost[j * size + l] != INF && exists[l].nextClearBit(i) > j
        }.toIntArray()
    }

    private fun latestBand(i: Int, cost: IntArray): IntArray =
        order[i].filter { l -> !lanes[l].designated && cost[i * size + l] != INF }.toIntArray()

    /** The lane named at each station (local index, -1 for none): P6 inside card windows, nothing outside (P3). */
    private fun name(bands: Array<IntArray?>, costOf: Array<IntArray?>): IntArray {
        val named = IntArray(n) { -1 }
        var prev = -1
        var nextRule = 0
        for (i in 0 until n) {
            val band = requireNotNull(bands[i])
            val s = stations[i]
            while (nextRule < rules.size && rules[nextRule].station < s) nextRule++
            if (!inWindow[i] || band.isEmpty()) {
                prev = -1
                continue
            }
            val cost = requireNotNull(costOf[i])
            val next = rules.getOrNull(nextRule)
            val preferred = band.filter { next != null && next.preferred.get(it) }
            val edge = edges[i]
            fun rank(l: Int) = Rank(cost[i * size + l], if (l in preferred) 0 else 1, fromEdge(l, i, edge), lanes[l].id)
            val choice = if (prev >= 0 && prev in band) {
                val better = band.filter { cost[i * size + it] < cost[i * size + prev] }
                when {
                    preferred.isNotEmpty() && prev !in preferred -> preferred.minByOrNull(::rank)
                    better.isNotEmpty() -> better.minByOrNull(::rank)
                    else -> prev
                }
            } else {
                band.minByOrNull(::rank)
            }
            named[i] = choice ?: -1
            prev = named[i]
        }
        return named
    }

    /** P6's order: fewest changes, then preferred at the next point, then nearest the maneuver's edge; the id last. */
    private data class Rank(val cost: Int, val preferred: Int, val fromEdge: Int, val id: Int) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int = compareValuesBy(this, other, { it.cost }, { it.preferred }, { it.fromEdge }, { it.id })
    }

    /** How many lanes open to every car that are there lie between the local lane [l] and [edge] at station [i]. */
    private fun fromEdge(l: Int, i: Int, edge: LaneEdge?): Int {
        if (edge == null) return 0
        val here = order[i]
        var count = 0
        for (m in here) {
            if (m == l || lanes[m].designated) continue
            if (if (edge == LaneEdge.RIGHT) lanes[m].slot > lanes[l].slot else lanes[m].slot < lanes[l].slot) count++
        }
        return count
    }

    private fun results(bands: Array<IntArray?>, flags: Array<LanePlan.Flag.Kind?>, named: IntArray): LanePlan.RunResult {
        // A lane that may already be there counts from where it may begin unless the plan names it; lanes of
        // other runs are never there at the same stations, so the run's own names are all that count.
        val namedIds = HashSet<Int>()
        for (l in named) if (l >= 0) namedIds.add(lanes[l].id)
        val segments = ArrayList<PlanSegment>()
        val moves = ArrayList<LanePlan.Move>()
        val flagList = ArrayList<LanePlan.Flag>()
        var flagStart = -1
        for (i in 0 until n) {
            val s = stations[i]
            val to = if (i + 1 < n) stations[i + 1] else run.to
            val band = requireNotNull(bands[i]).map { lanes[it].id }
            val id = if (named[i] >= 0) lanes[named[i]].id else null
            val edge = edges[i]
            val count = if (id != null && edge != null) topology.fromEdge(id, s, edge, namedIds) else null
            val segment = PlanSegment(s, max(to, s), run.index, band, id, count, edge, inWindow[i], flags[i])
            val lastSegment = segments.lastOrNull()
            if (lastSegment != null && lastSegment.copy(from = segment.from, to = segment.to) == segment) {
                segments[segments.size - 1] = lastSegment.copy(to = segment.to)
            } else {
                segments.add(segment)
            }
            if (i > 0 && named[i] >= 0 && named[i - 1] >= 0 && named[i] != named[i - 1]) {
                val before = fromEdge(named[i - 1], i - 1, edge)
                val after = fromEdge(named[i], i, edge)
                val crossed = rules.any { it.maneuver && stations[i - 1] < it.station && it.station <= s }
                moves.add(LanePlan.Move(s, lanes[named[i - 1]].id, lanes[named[i]].id, before, after, back = after > before && !crossed))
            }
            if (flags[i] != null && (i == 0 || flags[i - 1] != flags[i])) flagStart = i
            if (flags[i] != null && (i + 1 == n || flags[i + 1] != flags[i])) {
                flagList.add(LanePlan.Flag(requireNotNull(flags[i]), stations[flagStart], to))
            }
        }
        return LanePlan.RunResult(segments, flagList, moves)
    }

    private companion object {
        const val INF = Int.MAX_VALUE
    }
}
