package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * How the lit lane goes over to a new route's lane plan at a route change (the design's D8): a new
 * route, a reroute, an alternative the driver takes. The new plan's moves start no nearer than its
 * planning speed at the car times [LanePlanner.REACTION_SECONDS] ahead of the car ([holdMeters]); up to
 * there the lit lane is held:
 * - where both routes run on the same carriageway at the car ([sameCarriageway]: their lines ahead of
 *   the car lie within [SAME_CARRIAGEWAY_METERS] of each other over that distance) and count from the
 *   same exit edge, the lane at the old plan's count from the exit's edge;
 * - else the new plan's own lane at the car: after a moment matched onto a road beside the route, the
 *   lane the plan had there belongs to that road, and its count says nothing here.
 *
 * Only a lane the new plan lights at the car is held: nothing is lit outside its card windows. Pure;
 * nothing in it throws on odd input, lines of no length or points that are no number included.
 */
object LaneCarry {

    /** Two routes run on the same carriageway at the car when their lines ahead of it lie no farther apart than this. */
    const val SAME_CARRIAGEWAY_METERS = 5.0

    /** The lines are compared at points no farther apart than this along each. */
    private const val SAMPLE_METERS = 1.0

    /**
     * Up to [to] metres along the new route, from the car at [from], the lit lane is [lane] (a lane of the
     * new plan's topology), [fromEdge] lanes from the exit's [edge] as the new plan counts them; from [to]
     * the new plan's own named lane. [kept]: the old plan's count from the exit's edge is kept; false: the
     * new plan's own lane at the car is held. [moveTo]: the lane the light moves to at [to], where the new
     * plan names another one there; null where it names the same lane or none.
     */
    data class Carry(
        val from: Double,
        val to: Double,
        val lane: Int,
        val fromEdge: Int?,
        val edge: LaneEdge?,
        val kept: Boolean,
        val moveTo: Int?,
    ) {
        /** The route change moves the light once, at [to]. */
        val moves: Boolean get() = moveTo != null
    }

    /** How far ahead of the car at [station] the new plan [plan] moves nothing: its planning speed there times the reaction time. */
    fun holdMeters(plan: LanePlan, station: Double): Double {
        val nodes = plan.topology.evidence.route.nodes
        if (nodes.isEmpty()) return 0.0
        var k = 0
        while (k + 1 < nodes.size && nodes[k + 1].station <= station) k++
        return PlanningSpeed.of(nodes[k]) * LanePlanner.REACTION_SECONDS
    }

    /**
     * The lit lane from the car at [newStation] metres along the new route, planned [new], when the plan
     * before it, [old] (null at the start of a drive), had the car at [oldStation]. [oldAhead] and
     * [newAhead] are the two routes' lines from the car's place on each, in metres east and north of one
     * origin. Null where the new plan lights no lane at the car.
     */
    fun of(
        old: LanePlan?,
        oldStation: Double,
        oldAhead: List<LocalPoint>,
        new: LanePlan,
        newStation: Double,
        newAhead: List<LocalPoint>,
    ): Carry? {
        val here = new.segmentAt(newStation) ?: return null
        val own = here.named ?: return null
        val edge = here.edge
        val hold = holdMeters(new, newStation)
        val to = newStation + hold
        val before = old?.segmentAt(oldStation)
        val count = before?.namedFromEdge
        val kept = if (count != null && edge != null && before?.edge == edge && sameCarriageway(oldAhead, newAhead, hold)) {
            new.topology.lanesAt(here.run, newStation).firstOrNull { !it.designated && new.topology.fromEdge(it.id, newStation, edge, new.named) == count }?.id
        } else {
            null
        }
        val lane = kept ?: own
        val fromEdge = if (edge != null) new.topology.fromEdge(lane, newStation, edge, new.named) else null
        val next = new.segmentAt(to)?.named
        return Carry(newStation, to, lane, fromEdge, edge, kept = kept != null, moveTo = next?.takeIf { it != lane })
    }

    /**
     * Whether the lines [old] and [new], each from the car's place on its route, run on the same carriageway
     * for [meters] (the hold, some tens of metres): every point of each over that distance lies within
     * [SAME_CARRIAGEWAY_METERS] of the other (taken a little longer, so the other's end does not count as a
     * parting).
     */
    fun sameCarriageway(old: List<LocalPoint>, new: List<LocalPoint>, meters: Double): Boolean {
        if (old.isEmpty() || new.isEmpty() || !(meters >= 0.0)) return false
        val reach = meters + SAME_CARRIAGEWAY_METERS
        return within(LocalLine.head(old, meters), LocalLine.head(new, reach)) && within(LocalLine.head(new, meters), LocalLine.head(old, reach))
    }

    /** Whether every point of [line], taken at most [SAMPLE_METERS] apart, lies within [SAME_CARRIAGEWAY_METERS] of [other]. */
    private fun within(line: List<LocalPoint>, other: List<LocalPoint>): Boolean {
        if (!near(line[0], other)) return false
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val length = a.distanceTo(b)
            if (!(length >= 0.0)) return false
            val steps = max(1, ceil(length / SAMPLE_METERS).toInt())
            for (k in 1..steps) {
                if (!near(LocalLine.between(a, b, k.toDouble() / steps), other)) return false
            }
        }
        return true
    }

    /** Whether [point] lies within [SAME_CARRIAGEWAY_METERS] of the line [other]. */
    private fun near(point: LocalPoint, other: List<LocalPoint>): Boolean {
        if (other.size == 1) return point.distanceTo(other[0]) <= SAME_CARRIAGEWAY_METERS
        for (i in 1 until other.size) {
            if (distanceToSegment(point, other[i - 1], other[i]) <= SAME_CARRIAGEWAY_METERS) return true
        }
        return false
    }

    private fun distanceToSegment(p: LocalPoint, a: LocalPoint, b: LocalPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val square = dx * dx + dy * dy
        val t = if (square > 0.0) min(1.0, max(0.0, ((p.x - a.x) * dx + (p.y - a.y) * dy) / square)) else 0.0
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
