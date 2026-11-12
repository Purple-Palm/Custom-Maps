package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.max

/**
 * The evidence model's first settings, in metres; each is to be tuned from the "lane_evidence" lines
 * of the drives.
 */
data class EvidenceTuning(
    /** How far back from a lane point its lanes hold on a freeway or a ramp, at most: to the intersection before it, and no farther than this. */
    val reachFreewayM: Double = 1_000.0,
    /** The same on a street. */
    val reachStreetM: Double = 250.0,
    /** Two lane points on a street are bridged only when they are no farther apart than this. */
    val bridgeStreetM: Double = 400.0,
    /** A stretch without lane data between two drawn stretches, outside a card window, counts as short up to this long. */
    val zoneBridgeMaxM: Double = 600.0,
    /** A lane on the exit's side that a lane point lists and the one before it does not may exist from that one on, at most this far back. */
    val possibleLaneMaxM: Double = 3_000.0,
) {
    companion object {
        val DEFAULT = EvidenceTuning()
    }
}

/**
 * Where along a route its own lane data holds, and how far: the route's lane points are the only
 * evidence, and every stretch is one of [Kind]. The rules, in the design's numbering:
 *
 * 1. A lane point (an intersection the route lists lanes for) holds at its node.
 * 2. Its lanes reach back to the intersection before it, at most [EvidenceTuning.reachFreewayM] on a
 *    freeway or a ramp and [EvidenceTuning.reachStreetM] on a street: a lane list says nothing of the
 *    far side of a node where a road joins or leaves.
 * 3. Past a lane point the lanes are unknown, until the next point's reach or a bridge.
 * 4. Two lane points in a row whose lanes agree (as many lanes, and the same ones kept for car pools
 *    and the like, after rule 6) are bridged across the intersections without lane data between them
 *    where a road only joins, and where roads leave only on the side of the route's next exit. Such a
 *    road may take the lane at that side with it: it is an [UnknownExit]. Never across a node where
 *    the route turns ([turnsAt]), and on streets only when the points are at most
 *    [EvidenceTuning.bridgeStreetM] apart.
 * 6. Lanes the route cannot be followed from, at the edge of a point it goes straight through, leave
 *    there only where a road that may be entered leaves on their side ([branchesOff]); otherwise they
 *    go on and the point only says which lanes to use ([goingOn]).
 * 7. Everything else is unknown ([Kind.NONE]).
 *
 * Counted, not drawn yet (the lane topology of a later stage decides how to draw them): the [gaps]
 * (rule 7a); the [changes] of the lane count between points on one carriageway, rule 5 being those
 * nothing explains ([CountChange.explained]); and the lanes a point lists and the one before it does
 * not, with the side their arrows put them on, the exit's or none (rule 2a, [AddedLanes]).
 *
 * Pure; built once for a route, off the main thread, and never stored.
 */
class LaneEvidence private constructor(
    val route: LaneRoute,
    /** The card windows of the route ([CardWindows]), which the gaps are measured against. */
    val windows: List<CardWindow>,
    /** The indices of the route's lane points, in the order the car reaches them. */
    val lanePoints: List<Int>,
    /** The whole route from its start to its end, in the order the car drives it. */
    val stretches: List<Stretch>,
    val gaps: List<Gap>,
    val changes: List<CountChange>,
    val added: List<AddedLanes>,
    val unknownExits: List<UnknownExit>,
) {

    /** What is known of a stretch's lanes. */
    enum class Kind {
        /** A lane point's own lanes: at it and over its reach (rules 1, 2). */
        KNOWN,

        /** Between two lane points whose lanes agree (rule 4). */
        BRIDGED,

        /** Nothing (rules 3, 7). */
        NONE,
    }

    /**
     * From [from] to [to] metres along the route, on one kind of [road], with [lanes] lanes (0 for
     * [Kind.NONE]) as the lane point [point] lists them (its index in the route): for a known stretch
     * the point whose reach it is, for a bridged one the point before it, whose lanes go on.
     */
    data class Stretch(val from: Double, val to: Double, val kind: Kind, val lanes: Int, val road: RoadKind, val point: Int?) {
        val meters: Double get() = to - from
    }

    /**
     * A stretch without lane data between two lane points on one carriageway that are not bridged
     * ([before] and [after]): from [from] to [to] metres along the route, of which [inWindowMeters]
     * lie inside card windows (rule 7a). [shortOutside]: it lies outside every card window and is at
     * most [EvidenceTuning.zoneBridgeMaxM] long.
     */
    data class Gap(val from: Double, val to: Double, val inWindowMeters: Double, val shortOutside: Boolean, val before: Int, val after: Int) {
        val meters: Double get() = to - from

        val inWindow: Boolean get() = inWindowMeters > 0.0
    }

    /**
     * Between two lane points on one carriageway, [before] and [after], the lane count changes from
     * [lanesBefore] lanes going on past the first to [lanesAfter] at the second. [byNode]: an
     * intersection from the first point up to the second explains it, a road that joins for a gain
     * and one that leaves for a loss. [side]: for a gain, the side the added lanes lie on, read from
     * their arrows ([addedSide]); null where they do not tell.
     */
    data class CountChange(
        val before: Int,
        val after: Int,
        val lanesBefore: Int,
        val lanesAfter: Int,
        val byNode: Boolean,
        val side: LaneEdge?,
    ) {
        /** Whether something explains the change: an intersection, or for a gain the added lanes' arrows (rule 5 otherwise). */
        val explained: Boolean get() = byNode || (lanesAfter > lanesBefore && side != null)
    }

    /**
     * [lanes] lanes that the lane point [point] lists and the point before it on the same carriageway,
     * [previous], does not: lanes on the side of the exit ([side]), or on a side the arrows do not tell
     * (null). Where it is on the exit's side and the route does not take it, such a lane may already
     * exist from [possibleFrom] on: the previous point, at most [EvidenceTuning.possibleLaneMaxM] back
     * (rule 2a).
     */
    data class AddedLanes(val point: Int, val previous: Int, val lanes: Int, val side: LaneEdge?, val possibleFrom: Double)

    /**
     * An intersection without lane data inside a bridge where a road that may be entered leaves on
     * [edge], the side of the route's next exit: it may take the lane at that side with it.
     */
    data class UnknownExit(val node: Int, val station: Double, val edge: LaneEdge)

    /** How many lanes the route's lane points list for each kind of designated lane. */
    val designated: Map<DesignatedKind, Int> by lazy { designatedCounts() }

    /** The maneuvers of the route that are turns at a junction the camera frames, on a road that is no freeway ([CardWindows.framesStreetManeuver]). */
    val streetTurns: List<Int> by lazy {
        route.nodes.indices.filter { m ->
            val maneuver = route.nodes[m].maneuver
            m > 0 && maneuver != null && turnsAt(route.nodes[m]) && CardWindows.framesStreetManeuver(maneuver, route.nodes[m - 1])
        }
    }

    private fun designatedCounts(): Map<DesignatedKind, Int> {
        val counts = DesignatedKind.entries.associateWithTo(LinkedHashMap()) { 0 }
        for (p in lanePoints) {
            for (lane in route.nodes[p].lanes) {
                for (kind in lane.kinds) counts[kind] = counts.getValue(kind) + 1
            }
        }
        return counts
    }

    companion object {

        /** The maneuvers where the route goes on along the road it is on. */
        private val STRAIGHT_ON = setOf(ManeuverType.CONTINUE, ManeuverType.NEW_NAME, ManeuverType.MERGE, ManeuverType.NOTIFICATION)

        private val RIGHT_ARROWS = setOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)
        private val LEFT_ARROWS = setOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT)

        private const val FULL_TURN = 360
        private const val HALF_TURN = 180

        /**
         * The evidence of [route]'s lanes, its gaps measured against [windows].
         */
        fun of(
            route: LaneRoute,
            windows: List<CardWindow> = CardWindows.of(route),
            tuning: EvidenceTuning = EvidenceTuning.DEFAULT,
        ): LaneEvidence = Builder(route, windows, tuning).build()

        /**
         * Whether the route turns at [node]: at the maneuver a step begins with, unless it is one where
         * the route goes on along its road (continue, new name, merge, notification). The departure and
         * the arrival count as turns: nothing reaches across them.
         */
        fun turnsAt(node: RouteNode): Boolean = node.maneuver.let { it != null && it.type !in STRAIGHT_ON }

        /**
         * Whether a road that may be entered leaves [node] on [edge]'s side of the route's way out: its
         * bearing lies less than half a turn clockwise of the way out for the right, and the same
         * counterclockwise for the left. The road the route comes in on is none.
         */
        fun branchesOff(node: RouteNode, edge: LaneEdge): Boolean {
            val out = node.outIndex ?: return false
            val outBearing = node.bearings.getOrNull(out) ?: return false
            for (j in node.bearings.indices) {
                if (j == out || j == node.inIndex || node.entry.getOrNull(j) != true) continue
                val bearing = node.bearings[j] ?: continue
                val turn = Math.floorMod(bearing - outBearing, FULL_TURN)
                val onSide = if (edge == LaneEdge.RIGHT) turn in 1 until HALF_TURN else turn in HALF_TURN + 1 until FULL_TURN
                if (onSide) return true
            }
            return false
        }

        /** Whether a road that may not be entered from [node] meets it, other than the one the route comes in on: a road that joins. */
        fun joins(node: RouteNode): Boolean {
            for (j in node.bearings.indices) {
                if (j == node.outIndex || j == node.inIndex || node.bearings[j] == null) continue
                if (node.entry.getOrNull(j) == false) return true
            }
            return false
        }

        /**
         * The lanes of a lane point the route goes straight through that go on past it (rule 6): the
         * lanes at an edge that the route cannot be followed from leave only where a road that may be
         * entered leaves on that side. A point that marks no lane at all says nothing of where its
         * lanes go: they all go on.
         */
        fun goingOn(node: RouteNode): List<RouteLane> {
            val lanes = node.lanes
            val first = lanes.indexOfFirst { it.marked }
            if (first < 0) return lanes
            val afterLast = lanes.indexOfLast { it.marked } + 1
            val from = if (first > 0 && branchesOff(node, LaneEdge.LEFT)) first else 0
            val to = if (afterLast < lanes.size && branchesOff(node, LaneEdge.RIGHT)) afterLast else lanes.size
            return lanes.subList(from, to)
        }

        /**
         * The side [added] lanes of [lanes] that a point lists, and the point before it does not, lie
         * on: the side of the route's next exit, [edge], where each of the [added] lanes at that edge
         * has only arrows toward the exit; null where they do not tell.
         */
        fun addedSide(lanes: List<RouteLane>, added: Int, edge: LaneEdge?): LaneEdge? {
            if (edge == null || added <= 0 || added > lanes.size) return null
            val arrows = if (edge == LaneEdge.RIGHT) RIGHT_ARROWS else LEFT_ARROWS
            val outer = if (edge == LaneEdge.RIGHT) lanes.subList(lanes.size - added, lanes.size) else lanes.subList(0, added)
            return if (outer.all { it.indications.isNotEmpty() && arrows.containsAll(it.indications) }) edge else null
        }
    }

    /** One pass over a route's intersections. */
    private class Builder(val route: LaneRoute, val windows: List<CardWindow>, val tuning: EvidenceTuning) {
        private val nodes = route.nodes
        private val turning = BooleanArray(nodes.size) { turnsAt(nodes[it]) }
        private val exitEdges = exitEdges()
        private val points = nodes.indices.filter { nodes[it].isLanePoint }

        /** For each pair of lane points in a row, the first's index into [points]: whether they are bridged. */
        private val bridged = BooleanArray(max(points.size - 1, 0))

        private val gaps = ArrayList<Gap>()
        private val changes = ArrayList<CountChange>()
        private val added = ArrayList<AddedLanes>()
        private val unknownExits = ArrayList<UnknownExit>()

        fun build(): LaneEvidence {
            for (k in 0 until points.size - 1) pair(k, points[k], points[k + 1])
            return LaneEvidence(route, windows, points, stretches(), gaps, changes, added, unknownExits)
        }

        /**
         * The side of the route's next exit at each intersection: the side of the next maneuver where
         * the route turns ([RunUpRule.exitEdge]), at the intersection itself where it turns there.
         */
        private fun exitEdges(): Array<LaneEdge?> {
            val edges = arrayOfNulls<LaneEdge>(nodes.size)
            var next: LaneEdge? = null
            for (i in nodes.indices.reversed()) {
                if (turning[i]) next = RunUpRule.exitEdge(nodes[i].maneuver?.modifier, nodes[i].lanes)
                edges[i] = next
            }
            return edges
        }

        /** The lane points [a] and [b] in a row, the [k]th pair. */
        private fun pair(k: Int, a: Int, b: Int) {
            // The same carriageway: the route goes on at the first point and at every intersection to the second.
            if (turning[a] || (a + 1 until b).any { turning[it] }) return
            val before = goingOn(nodes[a])
            val after = nodes[b].lanes
            val agree = before.map { it.designated } == after.map { it.designated }
            val throughOk = (a + 1 until b).all { bridgeable(it) }
            val onStreet = (a until b).any { nodes[it].road == RoadKind.STREET }
            val near = !onStreet || nodes[b].station - nodes[a].station <= tuning.bridgeStreetM
            bridged[k] = agree && throughOk && near
            if (bridged[k]) {
                for (j in a + 1 until b) {
                    val edge = exitEdges[j] ?: continue
                    if (branchesOff(nodes[j], edge)) unknownExits.add(UnknownExit(j, nodes[j].station, edge))
                }
            } else {
                val from = nodes[a].station
                val to = knownFrom(b)
                if (to > from) {
                    val inWindow = CardWindows.metersInside(windows, from, to)
                    gaps.add(Gap(from, to, inWindow, shortOutside = inWindow <= 0.0 && to - from <= tuning.zoneBridgeMaxM, a, b))
                }
            }
            if (before.size != after.size) countChange(a, b, before.size, after)
        }

        private fun countChange(a: Int, b: Int, lanesBefore: Int, after: List<RouteLane>) {
            val gain = after.size - lanesBefore
            val byNode = (a until b).any { j -> if (gain > 0) joins(nodes[j]) else enterable(nodes[j]) }
            val side = if (gain > 0) addedSide(after, gain, exitEdges[b]) else null
            changes.add(CountChange(a, b, lanesBefore, after.size, byNode, side))
            if (gain > 0) {
                val possibleFrom = max(nodes[a].station, nodes[b].station - tuning.possibleLaneMaxM)
                added.add(AddedLanes(b, a, gain, side, possibleFrom))
            }
        }

        /** A road that may be entered leaves [node] on either side. */
        private fun enterable(node: RouteNode): Boolean = branchesOff(node, LaneEdge.LEFT) || branchesOff(node, LaneEdge.RIGHT)

        /**
         * Whether a bridge reaches across the intersection [j], which has no lane data: no road that
         * may be entered leaves it, or such roads leave only on the side of the route's next exit.
         */
        private fun bridgeable(j: Int): Boolean {
            val node = nodes[j]
            return when (exitEdges[j]) {
                LaneEdge.RIGHT -> !branchesOff(node, LaneEdge.LEFT)
                LaneEdge.LEFT -> !branchesOff(node, LaneEdge.RIGHT)
                null -> !enterable(node)
            }
        }

        /** Where the reach of the lane point [b] begins: at the intersection before it, at most the reach of its road back. */
        private fun knownFrom(b: Int): Double {
            if (b == 0) return nodes[b].station
            val approach = nodes[b - 1]
            val reach = if (approach.road == RoadKind.STREET) tuning.reachStreetM else tuning.reachFreewayM
            return max(approach.station, nodes[b].station - reach)
        }

        /** The stretches of the route, one road between two intersections at a time, merged where nothing changes. */
        private fun stretches(): List<Stretch> {
            val out = ArrayList<Stretch>()
            if (nodes.isEmpty()) {
                if (route.lengthMeters > 0.0) out.add(Stretch(0.0, route.lengthMeters, Kind.NONE, 0, RoadKind.STREET, null))
                return out
            }
            if (nodes[0].station > 0.0) add(out, Stretch(0.0, nodes[0].station, Kind.NONE, 0, nodes[0].road, null))
            var k = -1 // the last lane point at or before the segment, as an index into points
            for (i in 0 until nodes.size - 1) {
                if (k + 1 < points.size && points[k + 1] == i) k++
                val from = nodes[i].station
                val to = nodes[i + 1].station
                val road = nodes[i].road
                val next = if (k + 1 < points.size) points[k + 1] else -1
                val inBridge = k >= 0 && next >= 0 && bridged[k]
                val knownFrom = if (next == i + 1) max(from, knownFrom(next)) else to
                if (knownFrom > from) {
                    val stretch = if (inBridge) {
                        Stretch(from, knownFrom, Kind.BRIDGED, nodes[next].lanes.size, road, points[k])
                    } else {
                        Stretch(from, knownFrom, Kind.NONE, 0, road, null)
                    }
                    add(out, stretch)
                }
                if (to > knownFrom) add(out, Stretch(knownFrom, to, Kind.KNOWN, nodes[next].lanes.size, road, next))
            }
            val last = nodes.last()
            if (route.lengthMeters > last.station) add(out, Stretch(last.station, route.lengthMeters, Kind.NONE, 0, last.road, null))
            return out
        }

        /** Adds [stretch] after the last of [out], or lengthens that one where they are alike. */
        private fun add(out: ArrayList<Stretch>, stretch: Stretch) {
            if (stretch.meters <= 0.0) return
            val last = out.lastOrNull()
            if (last != null && last.to == stretch.from && last.kind == stretch.kind && last.lanes == stretch.lanes &&
                last.road == stretch.road && last.point == stretch.point
            ) {
                out[out.size - 1] = last.copy(to = stretch.to)
            } else {
                out.add(stretch)
            }
        }
    }
}
