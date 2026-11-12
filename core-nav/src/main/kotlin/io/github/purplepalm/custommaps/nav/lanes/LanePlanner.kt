package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.camera.RunUpLatch
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The lane plan of a route: which lanes work at every station and, inside the card windows, the one
 * lane the map, the card and the words name (the design's 5.1 and 5.2: GEOM's planner with P3, P6, P7
 * and F1). Its inputs are the route's lanes as one set ([LaneTopology]: every lane with its kinds, every
 * lane point with the lanes it allows and prefers, every maneuver), the planning speed of each road
 * ([PlanningSpeed], fixed per route, never the car's speed now), the card windows and the stations of
 * the roads without lane data that leave on the side of an exit; nothing about the car, and nothing
 * about where across the road it is.
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
 *   driving later (a [LanePlan.Flag.Kind.TIGHT] fallback to the latest changes, a
 *   [LanePlan.Flag.Kind.CONFLICT] where none is left, and there the nearer maneuver wins: the band that
 *   meets it). On a freeway the band narrows for an exit no later than [FREEWAY_FLOOR_METERS] before the
 *   gore (F1): from there only lanes that reach the exit's lanes without a change; where a road without
 *   lane data leaves on the exit's side within that distance, only from that road on, because the
 *   exit's own lane cannot be painted before it. Between two maneuvers the band only narrows: a lane
 *   that left it does not come back.
 * - **The card windows** are the run-ups and street approaches as the card shows them, with two
 *   changes: a run-up begins no later than [SEAM_SECONDS] of driving before the band's first narrowing
 *   for a lane point within its reach (the seam, [LanePlan.Seam]), never before its step begins; and
 *   after its gore it holds through the ramp to the next point the plan constrains there, a split or a
 *   maneuver with lane data, when that lies within [RAMP_BRIDGE_SECONDS] of driving at the ramp's
 *   planning speed ([CardWindow.Kind.RAMP]).
 * - **The named lane** (P6, E6): inside a card window, one lane of the band even while every lane
 *   works: the fewest changes to every point ahead, then preferred at the next point, then the lane
 *   nearest the maneuver's edge. It stays while it stays in the band and moves early, to the next
 *   point's preferred lane or one that needs fewer changes. Outside card windows no lane is named
 *   (P3): the band is kept and the path follows it.
 * - **Unknown exits** (P7): before a road without lane data that leaves on the side of the next exit,
 *   the lane at that edge may be the road's own lane, so it is not named unless it is the only lane of
 *   the band; past the road the named lane moves to the edge once where that needs fewer changes. The
 *   hold lasts to the road where it lies at least one comfortable change and the reaction time of
 *   driving before the exit; nearer, the edge lane is named from where the band holds nothing else
 *   ([LanePlan.UnknownExitHold.droppedAt]). Until the road is passed the card shows no words
 *   ([PlanSegment.unknownExitAhead]).
 * - **No lane** (C1): on the approach to a maneuver no lane open to every car can meet (only a lane
 *   kept for car pools and the like is valid there, or the lanes to use lie beyond one, which is never
 *   crossed), and on the approach to a roundabout and the way round it, no lane is named and nothing
 *   is tinted: the band there is empty ([LanePlan.NoLane]). The approach runs from the start of the
 *   maneuver's card window, or, for an exit that has none because no open lane leads to it, from as far
 *   before it as the close view's window at the approach's planning speed, to the maneuver; the
 *   evidence draws it as a zone.
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

    /** A run-up begins at least this much driving before the band's first narrowing for a lane point within its reach (the seam). */
    const val SEAM_SECONDS = 6.0

    /** A run-up holds through the ramp after its gore to a point the plan constrains no more than this much driving away. */
    const val RAMP_BRIDGE_SECONDS = 15.0

    /**
     * How far before the point where the last of [changes] lane changes must be done they begin, at
     * [speedMps]: the reaction time and [changes] comfortable or latest changes of driving.
     */
    fun changeMeters(changes: Int, speedMps: Double, comfortable: Boolean): Double =
        speedMps * (REACTION_SECONDS + changes * if (comfortable) COMFORTABLE_CHANGE_SECONDS else LATEST_CHANGE_SECONDS)

    /**
     * The plan of [topology]'s route. [windows] are the card windows as the card shows them on its own
     * ([CardWindows.latched]); the plan moves each run-up's start by the seam and adds the ramps it holds
     * through. [unknownExits] are the stations along the route of the roads without lane data that leave
     * on the side of the exit of their step, as the lane choices read them for the card's words.
     */
    fun plan(
        topology: LaneTopology,
        windows: List<CardWindow> = CardWindows.latched(topology.evidence.route),
        unknownExits: List<Double> = emptyList(),
    ): LanePlan {
        val route = topology.evidence.route
        val exits = unknownExits.filter { it.isFinite() }.distinct().sorted().toDoubleArray()
        val planners = topology.runs.map { RunPlanner(topology, it, exits) }
        val seams = ArrayList<LanePlan.Seam>()
        val planned = ArrayList<CardWindow>()
        for (window in windows) {
            val k = if (window.kind == CardWindow.Kind.RUN_UP) topology.points.indexOfFirst { it.node == window.node } else -1
            if (k < 0) {
                planned.add(window)
                continue
            }
            val seam = seam(route, planners[topology.points[k].run], window)
            seams.add(seam)
            planned.add(window.copy(from = seam.from))
            bridge(topology, k)?.let { planned.add(it) }
        }
        planned.sortBy { it.from }
        val noLane = noLane(topology, planners, planned)
        val results = planners.map { it.result(planned, noLane) }
        val holds = exits.indices.map { e ->
            val station = exits[e]
            UnknownExitRecord(station, route).hold(
                inWindow = planned.any { station >= it.from && station <= it.to },
                droppedAt = results.mapNotNull { it.drops[e] }.minOrNull(),
            )
        }
        return LanePlan(topology, planned, seams, holds, noLane, results)
    }

    /** The maneuvers that begin a way into a roundabout. */
    private val ROUNDABOUT_ENTRIES = setOf(ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ROTARY)

    /** The maneuvers that end a way round a roundabout. */
    private val ROUNDABOUT_EXITS = setOf(ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY)

    /** The approaches where no lane is named (C1), in route order. */
    private fun noLane(topology: LaneTopology, planners: List<RunPlanner>, windows: List<CardWindow>): List<LanePlan.NoLane> {
        val route = topology.evidence.route
        val nodes = route.nodes
        val out = ArrayList<LanePlan.NoLane>()
        var stepStart = 0.0
        for (m in nodes.indices) {
            val maneuver = nodes[m].maneuver ?: continue
            if (m > 0) {
                val point = topology.points.firstOrNull { it.node == m }
                val cause = when {
                    maneuver.type in ROUNDABOUT_ENTRIES || maneuver.type in ROUNDABOUT_EXITS -> LanePlan.NoLane.Cause.ROUNDABOUT
                    point == null || !point.turns -> null
                    point.allowed.isEmpty() && nodes[m].lanes.any { it.designated && it.marked } -> LanePlan.NoLane.Cause.DESIGNATED_ONLY
                    point.allowed.isNotEmpty() && planners[point.run].beyondDesignated(point.station) -> LanePlan.NoLane.Cause.DESIGNATED_BETWEEN
                    else -> null
                }
                if (cause != null) {
                    val station = nodes[m].station
                    val window = windows.firstOrNull { it.node == m && it.kind != CardWindow.Kind.RAMP }
                    val from = window?.from ?: max(stepStart, station - LaneFocus.windowMeters(CameraTuning.PHONE, PlanningSpeed.of(nodes[m - 1])))
                    if (station > from) out.add(LanePlan.NoLane(m, from, station, cause, zone = maneuver.type !in ROUNDABOUT_EXITS))
                }
            }
            stepStart = nodes[m].station
        }
        return out
    }

    /** Where the run-up [window], whose exit lies on the run [planner] plans, begins with the seam. */
    private fun seam(route: LaneRoute, planner: RunPlanner, window: CardWindow): LanePlan.Seam {
        val nodes = route.nodes
        val gore = window.to
        var stepStart = 0.0
        for (m in window.node - 1 downTo 0) {
            if (nodes[m].maneuver != null) {
                stepStart = nodes[m].station
                break
            }
        }
        val approach = nodes[if (window.node > 0) window.node - 1 else window.node]
        // The reach as the latch has it on the phone, at the approach's planning speed, as CardWindows.latched does.
        val reach = RunUpLatch.reachMeters(CameraTuning.PHONE, PlanningSpeed.of(approach))
        val narrowing = planner.firstNarrowing(gore, gore - reach)
        val from = if (narrowing == null) window.from else max(stepStart, min(window.from, narrowing - SEAM_SECONDS * planner.speedAt(narrowing)))
        return LanePlan.Seam(window.node, window.from, narrowing, from)
    }

    /**
     * The ramp a run-up holds through after its gore, the lane point [k] of [topology]: to the next point
     * of the same run that the plan constrains (one where the route turns, or where some lane open to every
     * car cannot be used, with a lane that can), when it lies within [RAMP_BRIDGE_SECONDS] of driving at
     * the planning speed of the road that leaves the gore; null otherwise, and where that point is a
     * roundabout, for which no lane is named (C1).
     */
    private fun bridge(topology: LaneTopology, k: Int): CardWindow? {
        val points = topology.points
        val exit = points[k]
        val nodes = topology.evidence.route.nodes
        val speed = PlanningSpeed.of(nodes[exit.node])
        for (j in k + 1 until points.size) {
            val point = points[j]
            if (point.run != exit.run) return null
            if (point.allowed.isEmpty()) continue
            val restricts = point.turns || point.lanes.any { !topology.lanes[it].designated && it !in point.allowed }
            if (!restricts) continue
            val type = nodes[point.node].maneuver?.type
            if (type in ROUNDABOUT_ENTRIES || type in ROUNDABOUT_EXITS) return null
            return if (point.station - exit.station <= RAMP_BRIDGE_SECONDS * speed) {
                CardWindow(exit.station, point.station, CardWindow.Kind.RAMP, point.node)
            } else {
                null
            }
        }
        return null
    }

    /** An unknown exit at [station] and the maneuver it lies before. */
    private class UnknownExitRecord(val station: Double, route: LaneRoute) {
        private val node: Int? = route.nodes.indices.firstOrNull { route.nodes[it].station >= station && LaneEvidence.turnsAt(route.nodes[it]) }

        fun hold(inWindow: Boolean, droppedAt: Double?) = LanePlan.UnknownExitHold(station, node, inWindow, droppedAt)
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
    /**
     * A road without lane data leaves on the side of the next exit between here and that exit (P7): the
     * lane at that edge may be the road's own, so the card's words wait until it is passed.
     */
    val unknownExitAhead: Boolean = false,
    /** Why no lane is named here and the band is empty, on an approach where none can be (C1); null elsewhere. */
    val noLane: LanePlan.NoLane.Cause? = null,
)

/** The lane plan of a route ([LanePlanner.plan]). */
class LanePlan internal constructor(
    val topology: LaneTopology,
    /**
     * The card windows lanes are named in, in route order: the run-ups from where the seam begins them,
     * the ramps they hold through ([CardWindow.Kind.RAMP]) and the street approaches.
     */
    val windows: List<CardWindow>,
    /** For each run-up, where it begins and why ([Seam]). */
    val seams: List<Seam>,
    /** For each road without lane data that leaves on the side of an exit, whether its hold lasted ([UnknownExitHold]). */
    val unknownExits: List<UnknownExitHold>,
    /** The approaches where no lane is named (C1), in route order. */
    val noLane: List<NoLane>,
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

    /**
     * Where the run-up to the exit or fork at the intersection [node] begins (the seam, D15): [latchFrom]
     * where the card's run-up latch begins it on its own ([CardWindows.latched]), [narrowing] the station
     * where the band first narrows for a lane point within the run-up's reach (null where it does not),
     * and [from] where the plan begins it: the earlier of [latchFrom] and [LanePlanner.SEAM_SECONDS] of
     * driving before [narrowing], never before its step begins. All in metres along the route.
     */
    data class Seam(val node: Int, val latchFrom: Double, val narrowing: Double?, val from: Double) {
        /** How much earlier than the latch on its own the run-up begins, in metres. */
        val earlierMeters: Double get() = latchFrom - from
    }

    /**
     * A road without lane data that leaves at [station] on the side of the exit of its step, before the
     * maneuver at the intersection [node] (null past the route's last). [inWindow]: it lies in a card
     * window. [droppedAt]: the station where the plan named the lane at that edge before the road all the
     * same, because no other lane of the band was left there (P7's limit); null where the hold lasted.
     */
    data class UnknownExitHold(val station: Double, val node: Int?, val inWindow: Boolean, val droppedAt: Double?) {
        val held: Boolean get() = droppedAt == null
    }

    /**
     * From [from] to [to] metres along the route, the approach to the maneuver at the intersection [node],
     * no lane is named and none is tinted, for [cause] (C1). [zone]: the evidence draws the approach as a
     * zone without lane lines, to the gore or the stop line; not on the way round a roundabout, where
     * nothing of the app's own is drawn.
     */
    data class NoLane(val node: Int, val from: Double, val to: Double, val cause: Cause, val zone: Boolean = true) {
        enum class Cause(
            /** How the debug log names it. */
            val logName: String,
        ) {
            /** Only a lane kept for car pools and the like is valid at the maneuver. */
            DESIGNATED_ONLY("designatedOnly"),

            /** The lanes to use lie beyond a lane kept for car pools and the like, which is never crossed. */
            DESIGNATED_BETWEEN("designatedBetween"),

            /** A roundabout: its approach, or the way round it to its exit. */
            ROUNDABOUT("roundabout"),
        }
    }

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
    fun evidence(): LaneEvidence = topology.evidence.withPlan(topology, named, windows, noLane)

    /** One run's part of the plan; [drops] maps an unknown exit (its index among the sorted stations) to where its hold was dropped. */
    internal class RunResult(val segments: List<PlanSegment>, val flags: List<Flag>, val moves: List<Move>, val drops: Map<Int, Double>)
}

/** The plan of one run of the topology. */
private class RunPlanner(val topology: LaneTopology, val run: LaneTopology.Run, private val unknownExits: DoubleArray) {
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

    /** At each station, where the next maneuver the route turns at lies: the end of the step the station is on. */
    private val nextTurn = DoubleArray(n).also { next ->
        var k = 0
        for (i in 0 until n) {
            while (k < nodes.size && (nodes[k].station < stations[i] || !LaneEvidence.turnsAt(nodes[k]))) k++
            next[i] = if (k < nodes.size) nodes[k].station else Double.POSITIVE_INFINITY
        }
    }

    /** A point of the run that says where to drive, on the grid: its station's index, its intersection and what it allows. */
    private class Rule(val index: Int, val station: Double, val node: Int, val allowed: BitSet, val preferred: BitSet, val maneuver: Boolean)

    private val rules: List<Rule> = topology.points.filter { it.run == run.index && it.constrains }.map { point ->
        Rule(nearest(point.station), point.station, point.node, bits(point.allowed), bits(point.preferred), point.turns)
    }

    /** The rules that narrow what may be driven: a maneuver, or a point where some lane open to every car cannot be used. */
    private val narrowing: List<Rule> = rules.filter { rule -> rule.maneuver || order[rule.index].any { !lanes[it].designated && !rule.allowed.get(it) } }

    /** A freeway floor (F1): from [from] to the gore at [to], the band holds only the lanes in [lanes]. */
    private class Floor(val from: Int, val to: Int, val lanes: BitSet)

    /**
     * The freeway floors (F1): from [LanePlanner.FREEWAY_FLOOR_METERS] before a freeway exit to its gore
     * only the lanes that reach the exit's lanes without a change: those lanes, and the lanes that feed
     * them along a taper. Where an unknown exit lies in that distance, from the first station past it:
     * before it the lane at the edge may be its own (P7).
     */
    private val floors: List<Floor> = rules.filter { it.maneuver && isFreewayExit(it) }.mapNotNull { exit ->
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
        val floorFrom = exit.station - LanePlanner.FREEWAY_FLOOR_METERS
        val unknown = unknownExits.filter { it >= floorFrom && it < exit.station }.maxOrNull()
        val from = max(nearest(max(floorFrom, run.from)), if (unknown != null) atOrAfter(unknown) else 0)
        if (from <= exit.index) Floor(from, exit.index, reach) else null
    }

    /** An exit or fork the route takes from a freeway. */
    private fun isFreewayExit(rule: Rule): Boolean =
        route.approachRoad(rule.node) == RoadKind.FREEWAY && nodes[rule.node].maneuver?.viewStaysUp == true

    private fun nearest(station: Double): Int = ((station - run.from) / LanePlanner.GRID_METERS + 0.5).toInt().coerceIn(0, n - 1)

    /** The first station of the grid at or after [station]. */
    private fun atOrAfter(station: Double): Int = ceil((station - run.from) / LanePlanner.GRID_METERS - 1e-9).toInt().coerceIn(0, n - 1)

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
     * with changes of [seconds]; [INF] where none works. With [crossDesignated], lanes kept for car pools
     * and the like may be driven through, never used at a rule: what the plan would do if they could be
     * crossed.
     */
    private fun costs(seconds: Double, allowed: Array<BitSet?>, upTo: Int, crossDesignated: Boolean = false): IntArray {
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
                if (lanes[l].designated && !crossDesignated) continue
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
                        if (m < 0 || (lanes[m].designated && !crossDesignated)) continue
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

    /** The band at each station (local indices), its flag and the costs it was taken from. */
    private val bands = arrayOfNulls<IntArray>(n)
    private val flags = arrayOfNulls<LanePlan.Flag.Kind>(n)
    private val costOf = arrayOfNulls<IntArray>(n)

    init {
        val last = n - 1
        val comfortable = costs(LanePlanner.COMFORTABLE_CHANGE_SECONDS, allowedAt(last), last)
        val latest = costs(LanePlanner.LATEST_CHANGE_SECONDS, allowedAt(last), last)
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

    /** The planning speed at [station] on the run. */
    fun speedAt(station: Double): Double = speeds[nearest(station)]

    /**
     * Whether no lane open to every car meets the maneuver of the run's rule at [station] only because a
     * lane kept for car pools and the like lies between: the approach to it, from the maneuver before,
     * has a station where no lane works, and there one would with such lanes crossed.
     */
    fun beyondDesignated(station: Double): Boolean {
        val target = rules.firstOrNull { it.maneuver && it.station == station } ?: return false
        val start = rules.lastOrNull { it.maneuver && it.index < target.index }?.index?.plus(1) ?: 0
        val conflict = (start..target.index).firstOrNull { flags[it] == LanePlan.Flag.Kind.CONFLICT } ?: return false
        val crossed = costs(LanePlanner.LATEST_CHANGE_SECONDS, allowedAt(target.index), target.index, crossDesignated = true)
        return order[conflict].any { !lanes[it].designated && crossed[conflict * size + it] != INF }
    }

    /**
     * The first station of the run, up to the gore at [gore], where a lane open to every car leaves a band
     * that keeps another, for the next point ahead that narrows what may be driven, when that point lies
     * no earlier than [pointsFrom] (within the run-up's reach) and no later than the gore; null where none
     * does. At the run's first station every lane there counts as having been in the band.
     */
    fun firstNarrowing(gore: Double, pointsFrom: Double): Double? {
        var r = 0
        for (i in 0 until n) {
            val s = stations[i]
            if (s > gore) break
            while (r < narrowing.size && narrowing[r].station < s) r++
            val rule = narrowing.getOrNull(r) ?: break
            if (rule.station > gore) break
            if (rule.station < pointsFrom) continue
            val band = requireNotNull(bands[i])
            if (band.isEmpty()) continue
            val before = if (i > 0) requireNotNull(bands[i - 1]) else order[0].filter { !lanes[it].designated }.toIntArray()
            if (before.any { it !in band && exists[it].get(i) }) return s
        }
        return null
    }

    /** The lane at the exit's edge at station [i] (a local index), or -1 where the side is not known or no lane is open there. */
    private fun edgeLane(i: Int): Int {
        val here = order[i]
        return when (edges[i]) {
            LaneEdge.RIGHT -> here.lastOrNull { !lanes[it].designated } ?: -1
            LaneEdge.LEFT -> here.firstOrNull { !lanes[it].designated } ?: -1
            null -> -1
        }
    }

    /** The run's part of the plan, naming lanes inside [windows] but never on the approaches of [noLane]. */
    fun result(windows: List<CardWindow>, noLane: List<LanePlan.NoLane>): LanePlan.RunResult {
        val inWindow = BooleanArray(n) { i -> windows.any { stations[i] >= it.from && stations[i] <= it.to } }
        val noLaneAt = Array(n) { i -> noLane.firstOrNull { stations[i] >= it.from && stations[i] <= it.to }?.cause }
        val drops = HashMap<Int, Double>()
        val named = name(inWindow, noLaneAt, drops)
        return results(inWindow, noLaneAt, named, drops)
    }

    /**
     * The lane named at each station (local index, -1 for none): P6 inside card windows, nothing outside
     * (P3), and the lane at the exit's edge held back before an unknown exit (P7); where only that lane
     * is left in the band, the hold of every unknown exit ahead on the step is dropped there ([drops]).
     */
    private fun name(inWindow: BooleanArray, noLaneAt: Array<LanePlan.NoLane.Cause?>, drops: HashMap<Int, Double>): IntArray {
        val named = IntArray(n) { -1 }
        var prev = -1
        var nextRule = 0
        var nextExit = 0
        for (i in 0 until n) {
            val band = requireNotNull(bands[i])
            val s = stations[i]
            while (nextRule < rules.size && rules[nextRule].station < s) nextRule++
            while (nextExit < unknownExits.size && unknownExits[nextExit] <= s) nextExit++
            if (!inWindow[i] || noLaneAt[i] != null || band.isEmpty()) {
                prev = -1
                continue
            }
            var candidates = band
            if (nextExit < unknownExits.size && unknownExits[nextExit] <= nextTurn[i]) {
                val edgeLane = edgeLane(i)
                if (edgeLane >= 0 && edgeLane in band) {
                    val others = band.filter { it != edgeLane }.toIntArray()
                    if (others.isNotEmpty()) {
                        candidates = others
                    } else {
                        var e = nextExit
                        while (e < unknownExits.size && unknownExits[e] <= nextTurn[i]) drops.putIfAbsent(e++, s)
                    }
                }
            }
            val cost = requireNotNull(costOf[i])
            val next = rules.getOrNull(nextRule)
            val preferred = candidates.filter { next != null && next.preferred.get(it) }
            val edge = edges[i]
            fun rank(l: Int) = Rank(cost[i * size + l], if (l in preferred) 0 else 1, fromEdge(l, i, edge), lanes[l].id)
            val choice = if (prev >= 0 && prev in candidates) {
                val better = candidates.filter { cost[i * size + it] < cost[i * size + prev] }
                when {
                    preferred.isNotEmpty() && prev !in preferred -> preferred.minByOrNull(::rank)
                    better.isNotEmpty() -> better.minByOrNull(::rank)
                    else -> prev
                }
            } else {
                candidates.minByOrNull(::rank)
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

    /** Whether an unknown exit lies at or ahead of station [i] before the end of its step. */
    private fun unknownExitAhead(i: Int): Boolean {
        val s = stations[i]
        var lo = 0
        var hi = unknownExits.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (unknownExits[mid] < s) lo = mid + 1 else hi = mid
        }
        return lo < unknownExits.size && unknownExits[lo] <= nextTurn[i]
    }

    private fun results(inWindow: BooleanArray, noLaneAt: Array<LanePlan.NoLane.Cause?>, named: IntArray, drops: Map<Int, Double>): LanePlan.RunResult {
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
            val band = if (noLaneAt[i] != null) emptyList() else requireNotNull(bands[i]).map { lanes[it].id }
            val id = if (named[i] >= 0) lanes[named[i]].id else null
            val edge = edges[i]
            val count = if (id != null && edge != null) topology.fromEdge(id, s, edge, namedIds) else null
            val segment = PlanSegment(s, max(to, s), run.index, band, id, count, edge, inWindow[i], flags[i], unknownExitAhead(i), noLaneAt[i])
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
        return LanePlan.RunResult(segments, flagList, moves, drops)
    }

    private companion object {
        const val INF = Int.MAX_VALUE
    }
}
