package io.github.purplepalm.custommaps.nav.lanes

import kotlin.math.roundToLong

/**
 * The "lane_plan" line of the debug log: once for each primary route a drive uses, right after its
 * "lane_evidence" line, the lane plan of the route ([LanePlan]) in numbers only. Counts, metres relative to
 * a gore, a split or a junction, lane counts from the exit's edge, the app's own keys and short names:
 * never a coordinate, a shape, a road's name or an instruction.
 */
object LanePlanLine {

    const val TYPE = "lane_plan"

    /**
     * The line's fields for [plan], whose evidence's second pass is [evidence] ([LanePlan.evidence]):
     * - "routeId"; "ms", how long reading the route and making the plan took ([buildMillis]);
     * - "windows": the card windows lanes are named in that hold lane data, and the approaches where no
     *   lane can be named, in route order, each with
     *   - "kind" (`run_up`, `ramp` from a gore to the split or point it is held to, `street`, or
     *     `approach` for an approach without a window of its own), "key" (the app's own key of its maneuver
     *     or point, [keyOf]) and "fromM", where it begins, in metres before its end (all metres of an entry
     *     are relative to the end: the gore, the split or the junction);
     *   - a run-up's "seam": where the card's latch begins it on its own ("latchM"), where the plan's band
     *     first narrows for a lane point within its reach ("narrowingM") and how much earlier the plan
     *     begins it ("earlierM");
     *   - "points": the lane points in it (a ramp's after its gore), each "atM", the lane named there counted
     *     from the edge of the exit or fork ahead ("lane", lanes that may already be there counted) and the size
     *     of its amber set ("amber");
     *   - "moves" of the named lane, each "atM" relative to the lane point it is made for, "from" and "to"
     *     counted from the exit's edge among the lanes there, and "back" where it moves away from the edge;
     *   - "tightM" and "conflictM": how long the band is tight or in conflict, stretch by stretch;
     *   - "unknownExits", the roads without lane data on the exit's side before it, and
     *     "unknownExitDropped", where the hold of the edge lane was dropped for them;
     *   - "conflict" (`designatedOnly`, `designatedBetween`) or "roundabout": why no lane is named;
     *   - "possibleLanes", the lanes that may already be there, and "zones", the zones in it;
     * - "replanMoves" and "replanKept" after a change of route, from [carry] (the design's D8): whether the
     *   light moves once the new plan takes over, and whether the old plan's count was kept;
     * - "odd": lanes the route prefers that it says cannot be used ("activeOutsideValid") and lanes it can
     *   be followed from whose one arrow points away from the exit's side ("arrowContradicts"), over the route.
     */
    fun fields(
        plan: LanePlan,
        evidence: LaneEvidence,
        keyOf: (Int) -> String?,
        buildMillis: Double? = null,
        carry: LaneCarry.Carry? = null,
    ): Map<String, Any?> {
        val topology = plan.topology
        val nodes = topology.evidence.route.nodes
        val entries = ArrayList<Pair<Double, Map<String, Any?>>>()
        for (window in plan.windows) {
            val end = nodes[window.node].station
            // A ramp's points after its gore, which the run-up's entry has.
            val points = topology.points.filter { it.station <= window.to && (it.station > window.from || (it.station == window.from && window.kind != CardWindow.Kind.RAMP)) }
            val noLane = plan.noLane.firstOrNull { it.node == window.node && window.kind != CardWindow.Kind.RAMP }
            if (points.isEmpty() && noLane == null && window.kind == CardWindow.Kind.STREET) continue
            entries.add(window.from to window(plan, evidence, window.kind.logName, window.node, window.from, window.to, end, points, noLane, keyOf))
        }
        // An approach where no lane can be named that no card window covers, as an exit no open lane leads to.
        for (approach in plan.noLane) {
            if (plan.windows.any { it.node == approach.node && it.kind != CardWindow.Kind.RAMP }) continue
            val end = nodes[approach.node].station
            val points = topology.points.filter { it.station >= approach.from && it.station <= approach.to }
            entries.add(approach.from to window(plan, evidence, "approach", approach.node, approach.from, approach.to, end, points, approach, keyOf))
        }
        entries.sortBy { it.first }
        return linkedMapOf(
            "routeId" to topology.evidence.route.routeId,
            "ms" to buildMillis?.let { Math.round(it * 10.0) / 10.0 },
            "windows" to entries.map { it.second },
            "replanMoves" to carry?.let { if (it.moves) 1 else 0 },
            "replanKept" to carry?.kept,
            "odd" to linkedMapOf(
                "activeOutsideValid" to topology.points.sumOf { it.activeOutsideValid },
                "arrowContradicts" to topology.points.sumOf { it.arrowContradicts },
            ),
        ).filterValues { it != null }
    }

    /** How the line names a kind of card window. */
    private val CardWindow.Kind.logName: String get() = name.lowercase()

    private fun window(
        plan: LanePlan,
        evidence: LaneEvidence,
        kind: String,
        node: Int,
        from: Double,
        to: Double,
        end: Double,
        points: List<LaneTopology.Point>,
        noLane: LanePlan.NoLane?,
        keyOf: (Int) -> String?,
    ): Map<String, Any?> {
        val seam = if (kind == CardWindow.Kind.RUN_UP.logName) plan.seams.firstOrNull { it.node == node } else null
        val moves = plan.moves.filter { it.station >= from && it.station <= to }
        val flags = plan.flags.filter { it.to > from && it.from <= to }
        val unknown = plan.unknownExits.filter { it.node == node && kind == CardWindow.Kind.RUN_UP.logName }
        return linkedMapOf(
            "kind" to kind,
            "key" to keyOf(node),
            "fromM" to metres(from - end),
            "seam" to seam?.let {
                linkedMapOf(
                    "latchM" to metres(it.latchFrom - end),
                    "narrowingM" to it.narrowing?.let { at -> metres(at - end) },
                    "earlierM" to metres(it.earlierMeters),
                )
            },
            "points" to points.map { point ->
                val segment = plan.segmentAt(point.station)
                linkedMapOf(
                    "atM" to metres(point.station - end),
                    "lane" to segment?.namedFromEdge,
                    "amber" to (segment?.band?.size ?: 0),
                )
            }.takeIf { it.isNotEmpty() },
            "moves" to moves.map { move ->
                val run = plan.segmentAt(move.station)?.run
                val target = plan.topology.points.firstOrNull { it.station >= move.station && it.run == run }
                linkedMapOf(
                    "atM" to metres(move.station - (target?.station ?: end)),
                    "from" to move.fromEdge,
                    "to" to move.toEdge,
                    "back" to move.back.takeIf { it },
                ).filterValues { it != null }
            }.takeIf { it.isNotEmpty() },
            "tightM" to flags.filter { it.kind == LanePlan.Flag.Kind.TIGHT }.map { metres(it.deficitMeters) }.takeIf { it.isNotEmpty() },
            "conflictM" to flags.filter { it.kind == LanePlan.Flag.Kind.CONFLICT }.map { metres(it.deficitMeters) }.takeIf { it.isNotEmpty() },
            "unknownExits" to unknown.size.takeIf { it > 0 },
            "unknownExitDropped" to unknown.mapNotNull { it.droppedAt?.let { at -> metres(at - end) } }.takeIf { it.isNotEmpty() },
            "conflict" to noLane?.cause?.takeIf { it != LanePlan.NoLane.Cause.ROUNDABOUT }?.logName,
            "roundabout" to (noLane?.cause == LanePlan.NoLane.Cause.ROUNDABOUT).takeIf { it },
            "possibleLanes" to evidence.possibleLanes.filter { it.to > from && it.from <= to }.sumOf { it.lanes }.takeIf { it > 0 },
            "zones" to evidence.zones.count { it.to > from && it.from <= to }.takeIf { it > 0 },
        ).filterValues { it != null }
    }

    private fun metres(value: Double): Long = if (value.isFinite()) value.roundToLong() else 0L
}
