package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.RouteNode
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Where every lane of a route lies across its road (the design's 4.2 and 4.3): one cross-section gives every
 * lateral position of the app's own drawing, so the asphalt's edges, the lane lines, the lit lane, the tint, the
 * route's path, the arrow, the eraser and the car bar agree by construction, with the drawing if not with the
 * ground. Positions are offsets from the road's axis ([RoadAxis]), left positive, in metres.
 *
 * - **Widths** (4.2): a lane is [LaneTopology.FREEWAY_LANE_METERS] wide on freeways and ramps and
 *   [LaneTopology.STREET_LANE_METERS] on streets; a lane only for bicycles is [BICYCLE_LANE_METERS], never a
 *   vehicle lane; [APRON_METERS] of asphalt lie outside each edge line. The lanes there at a station lie side by
 *   side in the topology's order across the road.
 * - **Where the carriageway sits**: a one-way carriageway is centred on its road's line where a run's lanes begin
 *   and wherever the route goes on onto another road with lanes (a ramp, a split's branch). From there a lane that
 *   opens or ends at an edge does so on its side and the other lanes stay where they are; where the topology read
 *   the side of a change only carefully (nothing explains it: the evidence's rule-5 zone), the road widens or
 *   narrows on both sides by half a lane over the zone, which moves its lanes toward the exit's edge. Where the
 *   topology had to put such a change on the other side (a gain on the exit's side or a loss on the far side, where a
 *   lane kept for car pools or buses rules the careful side out), the road widens or narrows on that side only and
 *   the other edge stays, as where the side is known: moving every lane, the named one too, away from the exit's edge
 *   and back would move the route's path back with it. On a two-way street the route's lanes lie from the road's
 *   line to the right and the oncoming half, as wide as those lanes, has no lines ([Carriageway.TWO_WAY]; first
 *   drawn with the streets).
 * - **Tapers** (4.3), each a half-cosine from the lane's inner edge: a lane that is added opens along the
 *   topology's own taper (a named deceleration lane over [LaneTopology.DECEL_TAPER_METERS], at full width
 *   [LaneTopology.DECEL_LATE_FULL_METERS] before the gore) or [LaneTopology.addedTaperMeters] (8:1 up to 45 mph,
 *   15:1 from 50); a lane on the exit's side that the plan does not name is drawn from where it may begin
 *   ([LaneTopology.Lane.possibleFrom]), [State.POSSIBLE] until it is known; a lane that ends with nothing to take
 *   it narrows over [LaneTopology.endingTaperMeters] (W S from 45 mph, W S^2 / 60 below, at least 30 m), and so does one
 *   that leaves by a road between other lanes of its road. A lane that leaves by a road at an edge ends where it
 *   leaves; one followed in its place by another (its kinds changed: a lane kept for car pools or buses from there, or
 *   open to every car again) ends where the other begins, and no lane moves there. Where a lane opens or ends between
 *   others, the fewer lanes on one side of it move and the rest stay; a lane only possible there is not counted.
 * - **A ramp's start** ([RampStart]): where the route turns off its road and lanes go on, they keep their place,
 *   straight on along the old road, until the new road's line lies farther out, and then blend onto the new
 *   road's carriageway over [RAMP_BLEND_METERS]: the route geometry's diagonal across the lanes at a gore is
 *   never drawn.
 *
 * Pure; built once for a route's plan, off the main thread, and never stored.
 */
class CrossSection private constructor(
    val topology: LaneTopology,
    private val layouts: List<Layout>,
    /** Every place where the route turns onto another road and lanes go on, in route order. */
    val rampStarts: List<RampStart>,
) {

    /** How a carriageway sits on its road's line. */
    enum class Carriageway {
        /** Centred on the line unless an edge is anchored: freeways, ramps, one-way streets. */
        ONE_WAY,

        /** The route's lanes from the line to the right; the oncoming half without lines (two-way streets). */
        TWO_WAY,
    }

    /** What is known of a lane where it is drawn. */
    enum class State {
        /** At its full width. */
        FULL,

        /** Widening along its taper. */
        OPENING,

        /** Narrowing along its taper, already gone for the plan. */
        CLOSING,

        /** A lane that may already be there and is known only farther on: no line beside it, no arrow, never lit. */
        POSSIBLE,
    }

    /** The lane [lane] at a station: between [left] and [right] (offsets from the axis, left positive). */
    data class Place(val lane: Int, val left: Double, val right: Double, val state: State) {
        val centre: Double get() = (left + right) / 2.0
        val width: Double get() = left - right
    }

    /** The carriageway at a station: its edge lines [left] and [right], and the asphalt beyond them to [asphaltLeft] and [asphaltRight]. */
    data class Edges(val left: Double, val right: Double, val asphaltLeft: Double, val asphaltRight: Double)

    /**
     * Where the route leaves its road at the intersection [node], at [station], onto another with lanes that go on:
     * there the lanes that go on lay with their middle [shift] metres to the left of their middle on the new road; they
     * go on straight until the new road's line lies farther out, then blend onto it over [RAMP_BLEND_METERS] ([at]).
     */
    data class RampStart(val node: Int, val station: Double, val shift: Double) {
        /** How far the middle of the lanes lies to the left of its place on the new road at [at], along [axis]. */
        fun at(axis: RoadAxis, at: Double): Double = continuation(axis, station, shift, at)

        /** Where the old road's lanes begin to go straight on along [axis]: where the arc at the turn begins. */
        fun begin(axis: RoadAxis): Double = beginOf(axis, station)
    }

    /** A lane as it is drawn: from [from] (where it may begin, or opens) to [to] (where it is gone), with its tapers. */
    internal class DrawnLane(
        val lane: LaneTopology.Lane,
        val width: Double,
        val from: Double,
        val to: Double,
        val openTo: Double,
        val possibleTo: Double,
        val closeFrom: Double,
    ) {
        /** How much of its width the lane has gained by [station], 0 to 1. */
        fun opened(station: Double): Double = ramp(station, from, openTo)

        /** How much of its width it has lost by [station], 0 to 1. */
        fun closed(station: Double): Double = ramp(station, closeFrom, to)

        /** Its width at [station]. */
        fun widthAt(station: Double): Double =
            if (station < from || station >= to) 0.0 else width * max(0.0, min(opened(station), 1.0 - closed(station)))

        fun state(station: Double): State = when {
            station < possibleTo -> State.POSSIBLE
            to > closeFrom && station >= closeFrom -> State.CLOSING
            openTo > from && station < openTo -> State.OPENING
            else -> State.FULL
        }
    }

    /**
     * One road of a run, from [from] to [to]: its kind, the lanes drawn on it in the order across the road, where
     * its left edge lies where it begins ([left]), the lanes whose opening or end moves that edge, and the moves of
     * the rule-5 zones.
     */
    internal class Layout(
        val run: Int,
        val from: Double,
        val to: Double,
        val road: RoadKind,
        val carriageway: Carriageway,
        val lanes: List<DrawnLane>,
        val left: Double,
        val leftOpening: List<DrawnLane>,
        val leftClosing: List<DrawnLane>,
        /** From, to and how far the left edge moves over each zone. */
        val zones: List<Triple<Double, Double, Double>>,
    ) {
        /** The lanes there at [station], from the left, on this road. */
        fun placesAt(station: Double): List<Place> {
            var edge = leftAt(station)
            val out = ArrayList<Place>(lanes.size)
            for (d in lanes) {
                if (station < d.from || station >= d.to) continue
                val w = d.widthAt(station)
                out.add(Place(d.lane.id, edge, edge - w, d.state(station)))
                edge -= w
            }
            return out
        }

        /** Where the left edge lies at [station]. */
        fun leftAt(station: Double): Double {
            var edge = left
            for (d in leftOpening) edge += d.width * d.opened(station)
            for (d in leftClosing) edge -= d.width * d.closed(station)
            for ((from, to, by) in zones) edge += by * ramp(station, from, to)
            return edge
        }
    }

    /**
     * The lanes drawn at [station], from the left. Along [axis], where given, a ramp's start puts each lane that was
     * there before the turn on its own straight line until the new road's line lies farther out, then blends it onto
     * the new road over [RAMP_BLEND_METERS]; without an axis the lanes lie on the new road from the turn on.
     */
    fun lanes(station: Double, axis: RoadAxis? = null): List<Place> {
        val layout = layoutAt(station) ?: return emptyList()
        val plain = layout.placesAt(station)
        if (axis == null) return plain
        val start = rampStarts.firstOrNull {
            (layout.from == it.station || layout.to == it.station) &&
                station >= it.station - RAMP_LOOK_BACK_METERS && station < it.station + RAMP_START_MAX_METERS + RAMP_BLEND_METERS
        } ?: return plain
        val begin = start.begin(axis)
        if (station < begin) return plain
        val crossing = max(start.station, crossing(axis, begin, start.station, start.shift))
        if (station >= crossing + RAMP_BLEND_METERS) return plain
        val before = layouts.firstOrNull { it.run == layout.run && it.to == start.station } ?: return plain
        val old = before.placesAt(begin)
        val f = ramp(station, crossing, crossing + RAMP_BLEND_METERS)
        return plain.map { p ->
            val q = old.firstOrNull { it.lane == p.lane }
            val left = q?.let { lineOffset(axis, begin, it.left, station) }
            val right = q?.let { lineOffset(axis, begin, it.right, station) }
            if (left == null || right == null) {
                // A lane that was not there before the turn moves with the middle of those that were.
                val shift = start.at(axis, station) - if (layout.to == start.station) start.shift else 0.0
                p.copy(left = p.left + shift, right = p.right + shift)
            } else {
                p.copy(left = left * (1.0 - f) + p.left * f, right = right * (1.0 - f) + p.right * f)
            }
        }
    }

    /** The lane [lane] at [station], where it is drawn; null elsewhere. */
    fun place(lane: Int, station: Double, axis: RoadAxis? = null): Place? = lanes(station, axis).firstOrNull { it.lane == lane }

    /** The first station at or after [station] where [lane] is drawn at its full width: past its taper where it opens. */
    fun fullFrom(lane: Int, station: Double): Double {
        val drawn = layouts.firstNotNullOfOrNull { layout -> layout.lanes.firstOrNull { it.lane.id == lane } } ?: return station
        return max(station, drawn.openTo)
    }

    /** The middle across the road of [lanes] at [station]: halfway between the outer edges of those drawn there. */
    fun middle(lanes: Collection<Int>, station: Double, axis: RoadAxis? = null): Double? {
        val places = lanes(station, axis).filter { it.lane in lanes && it.width > 0.0 }
        if (places.isEmpty()) return null
        return (places.maxOf { it.left } + places.minOf { it.right }) / 2.0
    }

    /** The carriageway's edges at [station]; null where no lane is drawn. */
    fun edges(station: Double, axis: RoadAxis? = null): Edges? {
        val places = lanes(station, axis)
        if (places.none { it.width > 0.0 }) return null
        val left = places.first().left
        val right = places.last().right
        val layout = requireNotNull(layoutAt(station))
        val outer = if (layout.carriageway == Carriageway.TWO_WAY) {
            // The oncoming half: as wide as the route's lanes where the road begins, left of its line.
            max(left, layout.left + layout.lanes.filter { it.from <= layout.from && it.to > layout.from }.sumOf { it.width })
        } else {
            left
        }
        return Edges(left, right, outer + APRON_METERS, right - APRON_METERS)
    }

    /** The kind of road at [station] on a run; null off every run. */
    fun roadAt(station: Double): RoadKind? = layoutAt(station)?.road

    /** The run whose roads hold [station]: the later one where two meet; null off every run. */
    fun runAt(station: Double): Int? = layoutAt(station)?.run

    /** The road of a run that holds [station]: the later one where two meet. */
    private fun layoutAt(station: Double): Layout? {
        var lo = 0
        var hi = layouts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (layouts[mid].from <= station) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        for (k in found downTo 0) if (station < layouts[k].to) return layouts[k]
        return null
    }

    companion object {

        /** A lane only for bicycles (4.2): never a vehicle lane. */
        const val BICYCLE_LANE_METERS = 1.8

        /** Asphalt outside each edge line, with no line on it. */
        const val APRON_METERS = 1.2

        /** At a ramp's start the lanes blend onto the new road over this distance, once its line lies farther out. */
        const val RAMP_BLEND_METERS = 25.0

        /** The new road's line must lie farther out within this distance of the turn; otherwise the lanes blend at once. */
        const val RAMP_START_MAX_METERS = 300.0

        /** A ramp's start reaches back to where the arc at its turn begins, at most this far. */
        private const val RAMP_LOOK_BACK_METERS = 50.0

        /** The road's line turned this far from the old road's way (the cosine), the straight-on line is no longer read across it. */
        private const val CROSS_LIMIT = 0.1

        /** The straight-on line is followed this finely while looking for where the new road's line passes it. */
        private const val CROSSING_STEP_METERS = 1.0

        /** A path at a turn closer than this to the new road's line is on it. */
        private const val SETTLED_METERS = 1e-6

        /** How far on the straight-on line's slope is read where a path settles onto the new road's line. */
        private const val SLOPE_STEP_METERS = 0.5

        /** A lane's full width on a road of [road] for the kinds of vehicle [kinds]. */
        fun laneMeters(road: RoadKind, kinds: Set<DesignatedKind> = emptySet()): Double = when {
            kinds.isNotEmpty() && kinds.all { it == DesignatedKind.BICYCLE } -> BICYCLE_LANE_METERS
            else -> LaneTopology.laneMeters(road)
        }

        /** A half-cosine from 0 at [from] to 1 at [to], with no slope at either end; 0 before, 1 after, a step where they meet. */
        fun ramp(station: Double, from: Double, to: Double): Double = when {
            station < from -> 0.0
            station >= to -> 1.0
            else -> (1.0 - cos(PI * (station - from) / (to - from))) / 2.0
        }

        /**
         * The cross-section of [plan]'s route, its zones from [evidence] (the plan's second pass). [carriageway] tells, for
         * each run, how its carriageways sit: one-way unless a source says otherwise.
         */
        fun of(
            plan: LanePlan,
            evidence: LaneEvidence = plan.evidence(),
            carriageway: (run: Int) -> Carriageway = { Carriageway.ONE_WAY },
        ): CrossSection = Builder(plan, evidence, carriageway).build()

        /**
         * Where something that lay [shift] metres to the left of its place on a new road lies at [at], the route having
         * turned onto that road at [station] along [axis]: until the arc at the turn begins, [shift]; from there straight
         * on along the old road's way until the new road's place lies farther out; then blended onto it over
         * [RAMP_BLEND_METERS]; 0 after. Where the new road's place does not lie farther out within
         * [RAMP_START_MAX_METERS], the blend begins at the turn.
         */
        fun continuation(axis: RoadAxis, station: Double, shift: Double, at: Double): Double {
            val begin = beginOf(axis, station)
            if (at < begin) return shift
            val crossing = max(station, crossing(axis, begin, station, shift))
            val line = lineOffset(axis, begin, shift, at) ?: return 0.0
            return when {
                at < crossing -> line
                at < crossing + RAMP_BLEND_METERS -> line * (1.0 - ramp(at, crossing, crossing + RAMP_BLEND_METERS))
                else -> 0.0
            }
        }

        /**
         * Where a path that lay [shift] metres to the left of a new road's line lies at [at], the route having turned onto that
         * road at [station] along [axis] where no lanes are drawn past the turn: until the arc at the turn begins, [shift];
         * from there straight on along the old road's way, and from halfway to where that straight-on line would cross the
         * new road's line it settles onto it as its offset there times (1 - u) cubed for u from 0 to 1, starting as fast as
         * the straight-on line closes in and ending on the line with neither slope nor curvature, so it never swings past the
         * line and meets it without a kink ([turnOffEnd] says where). Where the straight-on line does not close in on the
         * new road's line (it lies outside the turn, or the turn is too sharp to read the old road's way across it), the
         * path keeps to the new road's line at an offset that shrinks from [shift] to 0 over [RAMP_BLEND_METERS] from the
         * arc's start along a half-cosine of a half-cosine, with neither slope nor curvature at either end; on the line already
         * ([shift] 0), it stays on it.
         */
        fun turnOff(axis: RoadAxis, station: Double, shift: Double, at: Double): Double {
            val begin = beginOf(axis, station)
            if (at < begin) return shift
            if (abs(shift) <= SETTLED_METERS) return 0.0
            val settle = settling(axis, begin, station, shift)
                ?: return shift * (1.0 - ramp(ramp(at, begin, begin + RAMP_BLEND_METERS), 0.0, 1.0))
            if (at < settle.from) return lineOffset(axis, begin, shift, at) ?: 0.0
            if (at >= settle.to) return 0.0
            val u = 1.0 - (at - settle.from) / (settle.to - settle.from)
            return settle.offset * u * u * u
        }

        /** Where [turnOff] lies on the new road's line for good. */
        fun turnOffEnd(axis: RoadAxis, station: Double, shift: Double): Double {
            val begin = beginOf(axis, station)
            if (abs(shift) <= SETTLED_METERS) return begin
            return settling(axis, begin, station, shift)?.to ?: (begin + RAMP_BLEND_METERS)
        }

        /** Where [turnOff] settles onto the new road's line: from [from], where the path lies [offset] off it, to [to]. */
        private class Settling(val from: Double, val to: Double, val offset: Double)

        /**
         * How [turnOff] settles at the turn at [station], the arc beginning at [begin]: halfway from the arc's start to where
         * the straight-on line would cross the new road's line, along a cubic whose slope there is the straight-on line's own;
         * null where that line does not cross it within [RAMP_START_MAX_METERS] or does not close in on it there.
         */
        private fun settling(axis: RoadAxis, begin: Double, station: Double, shift: Double): Settling? {
            val crossing = crossing(axis, begin, station, shift)
            val before = lineOffset(axis, begin, shift, crossing - SLOPE_STEP_METERS) ?: return null
            val after = lineOffset(axis, begin, shift, crossing + SLOPE_STEP_METERS) ?: return null
            if (before * shift <= 0.0 || after * shift > 0.0) return null
            val from = (begin + crossing) * 0.5
            val offset = lineOffset(axis, begin, shift, from) ?: return null
            val ahead = lineOffset(axis, begin, shift, from + SLOPE_STEP_METERS) ?: return null
            val closing = (offset - ahead) / SLOPE_STEP_METERS * sign(offset)
            if (offset * shift <= 0.0 || closing <= 0.0) return null
            return Settling(from, min(station + RAMP_START_MAX_METERS, from + 3.0 * abs(offset) / closing), offset)
        }

        /** Where the arc at the turn at [station] begins along [axis]: the turn itself where the road does not bend there. */
        internal fun beginOf(axis: RoadAxis, station: Double): Double {
            val fillet = axis.fillets.firstOrNull { abs(it.station - station) < 1e-6 }
            return max(axis.from, fillet?.from ?: station)
        }

        /** The offset at [at] of the line straight on from [shift] to the left of the axis at [begin]; null where it is no longer read across. */
        internal fun lineOffset(axis: RoadAxis, begin: Double, shift: Double, at: Double): Double? {
            val start = axis.at(begin)
            val ox = start.point.x + start.nx * shift
            val oy = start.point.y + start.ny * shift
            val here = axis.at(at)
            val dot = here.tx * start.tx + here.ty * start.ty
            if (dot < CROSS_LIMIT) return null
            // The point of the line on the axis' normal here: t = ((O - P) x d) / (n x d), and n x d = -(t . d).
            val cross = (ox - here.point.x) * start.ty - (oy - here.point.y) * start.tx
            return cross / -dot
        }

        /**
         * Where the straight-on line from [shift] at [begin] passes the new road's place (0) after the turn at [station]: the
         * first station where its sign turns, between two samples [CROSSING_STEP_METERS] apart; [station] where it does not
         * within [RAMP_START_MAX_METERS].
         */
        internal fun crossing(axis: RoadAxis, begin: Double, station: Double, shift: Double): Double {
            if (shift == 0.0) return begin
            var prev = begin
            var prevValue = shift
            var s = begin
            val end = min(axis.to, station + RAMP_START_MAX_METERS)
            while (s < end) {
                s = min(end, s + CROSSING_STEP_METERS)
                val v = lineOffset(axis, begin, shift, s) ?: return station
                if (v == 0.0 || (v > 0.0) != (shift > 0.0)) return prev + (s - prev) * prevValue / (prevValue - v)
                prev = s
                prevValue = v
            }
            return station
        }
    }

    /** Lays out every run of the topology once. */
    private class Builder(val plan: LanePlan, val evidence: LaneEvidence, val carriageway: (Int) -> Carriageway) {
        private val topology = plan.topology
        private val nodes = topology.evidence.route.nodes

        fun build(): CrossSection {
            val layouts = ArrayList<Layout>()
            val starts = ArrayList<RampStart>()
            val drawn = topology.lanes.map { drawnLane(it) }
            for (run in topology.runs) {
                val kind = carriageway(run.index)
                val points = run.points.map { topology.points[it] }
                val runLanes = drawn.filter { it.lane.run == run.index }.sortedBy { it.lane.slot }
                // The roads of the run: from its start, and from every point where the route turns and lanes go on.
                val turns = points.filter { it.turns && it.station > run.from && it.station < run.to && it !== points.last() }
                val bounds = turns.map { it.station } + run.to
                var from = run.from
                var previous: Layout? = null
                for ((k, to) in bounds.withIndex()) {
                    val turn = if (k > 0) turns[k - 1] else null
                    // On a road the route turns onto, the old road's lanes that leave at the turn are not drawn.
                    val lanes = runLanes.filter { d ->
                        d.to > from && d.from < to && !(turn != null && d.from < from && d.lane.closes <= from + LaneTopology.LEAVE_PAST_POINT_METERS + 1e-9)
                    }
                    val road = nodeAt(from)?.road ?: RoadKind.FREEWAY
                    val there = lanes.filter { it.from <= from && it.to > from }
                    val width = there.sumOf { it.widthAt(from) }
                    val left = if (kind == Carriageway.TWO_WAY) 0.0 else width / 2.0
                    val opening = lanes.filter { it.from > from && it.from < to && leftSide(it, lanes, it.from, opens = true) }
                    val closing = lanes.filter { it.closeFrom >= from && it.closeFrom < to && leftSide(it, lanes, it.closeFrom, opens = false) }
                    val zones = if (kind == Carriageway.TWO_WAY) emptyList() else zonesOf(run.index, from, to, road)
                    val layout = Layout(run.index, from, to, road, kind, lanes, left, opening, closing, zones)
                    if (turn != null && previous != null) {
                        // The lanes that go on, where they lay on the old road and where they lie on the new one.
                        val old = previous.placesAt(from - 1e-6).filter { p -> lanes.any { it.lane.id == p.lane } }
                        val new = layout.placesAt(from).filter { p -> old.any { it.lane == p.lane } }
                        if (old.isNotEmpty() && new.isNotEmpty()) {
                            val oldMiddle = (old.first().left + old.last().right) / 2.0
                            val newMiddle = (new.first().left + new.last().right) / 2.0
                            starts.add(RampStart(turn.node, turn.station, oldMiddle - newMiddle))
                        }
                    }
                    layouts.add(layout)
                    previous = layout
                    from = to
                }
            }
            layouts.sortBy { it.from }
            return CrossSection(topology, layouts, starts)
        }

        /**
         * Whether [d] opens ([opens]) or ends on the left side at [station]: it has fewer lanes of [lanes] to its left there
         * than to its right, so the lanes on its left move and the others stay; at the left edge it is, at the right edge it
         * is not. With as many on each side, the lanes that move are those whose move goes toward the next exit's edge: where
         * a lane opens, the lanes between it and that edge move out to it; where one ends, the lanes on its far side close up
         * toward it (the left side where the edge is not known). So a lane the plan names never moves away from the exit's
         * edge for a lane opening or ending beside it. A lane of its own slot is not counted: where a lane's kinds change,
         * the lane that ends and the one that takes its place lie in one place, so both are put on the same side (the left
         * one on a tie), and the road neither widens nor narrows there. Nor is a lane that is only possible there ([State.POSSIBLE]): it is not
         * there yet, so a lane at the edge with possible lanes beyond it still ends at that edge, and the lanes on its other
         * side stay where they are.
         */
        private fun leftSide(d: DrawnLane, lanes: List<DrawnLane>, station: Double, opens: Boolean): Boolean {
            var left = 0
            var right = 0
            for (other in lanes) {
                if (other === d || other.lane.slot == d.lane.slot || station < other.from || station >= other.to) continue
                if (station < other.possibleTo) continue
                if (other.lane.slot < d.lane.slot) left++ else right++
            }
            if (left != right) return left < right
            // A lane whose kinds change and the lane that takes its place stay on one side.
            if (replaces(d.lane) || replacedAt(d.lane)) return true
            return when (edgeAfter(d.lane.run, station)) {
                LaneEdge.RIGHT -> !opens
                LaneEdge.LEFT -> opens
                null -> true
            }
        }

        /** The side of the next exit at [station] on [run]: the edge of the run's first lane point at or after it. */
        private fun edgeAfter(run: Int, station: Double): LaneEdge? =
            topology.points.firstOrNull { it.run == run && it.station >= station - 1e-9 }?.edge

        /**
         * How the left edge moves over the rule-5 zones on the road from [from] to [to] of [run]: where the topology put
         * a change nothing explains on one side, the edge moves back by half of it, so the road widens or narrows by
         * half a lane on each side, moving every lane toward the exit's edge. Not where that move would take every lane
         * away from the exit's edge (the change put on the side the careful reading would not choose): there the other
         * edge stays.
         */
        private fun zonesOf(run: Int, from: Double, to: Double, road: RoadKind): List<Triple<Double, Double, Double>> {
            val out = ArrayList<Triple<Double, Double, Double>>()
            for (alignment in topology.alignments) {
                if (alignment.explained) continue
                val after = topology.points[alignment.after]
                if (after.run != run) continue
                val side = alignment.side ?: continue
                val zone = evidence.zones.firstOrNull { it.cause == LaneEvidence.Zone.Cause.CHANGE && it.after == after.node }
                val zFrom = zone?.from ?: min(alignment.at, after.station)
                val zTo = zone?.to ?: after.station
                if (zTo <= from || zFrom >= to) continue
                val change = alignment.change * laneMeters(road)
                val by = if (side == LaneEdge.LEFT) -change / 2.0 else change / 2.0
                // Every lane moves with the left edge: toward the exit's edge, or not at all.
                val toward = when (after.edge) {
                    LaneEdge.LEFT -> by >= 0.0
                    LaneEdge.RIGHT -> by <= 0.0
                    null -> true
                }
                if (toward) out.add(Triple(zFrom, zTo, by))
            }
            return out
        }

        /** The last intersection at or before [station]. */
        private fun nodeAt(station: Double): RouteNode? {
            var lo = 0
            var hi = nodes.size - 1
            var found: RouteNode? = null
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (nodes[mid].station <= station + 1e-9) {
                    found = nodes[mid]
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found ?: nodes.firstOrNull()
        }

        private fun speedAt(station: Double): Double = nodeAt(station)?.let { PlanningSpeed.of(it) } ?: PlanningSpeed.mps(null, RoadKind.FREEWAY)

        private fun roadAt(station: Double): RoadKind = nodeAt(station)?.road ?: RoadKind.FREEWAY

        /** How [lane] is drawn: where it may begin or opens, where it ends, and its tapers. */
        private fun drawnLane(lane: LaneTopology.Lane): DrawnLane {
            val run = topology.runs[lane.run]
            val road = roadAt(lane.opens)
            val width = laneMeters(road, lane.kinds)
            val possibleFrom = lane.possibleFrom
            val from: Double
            val openTo: Double
            val possibleTo: Double
            if (possibleFrom != null && lane.id !in plan.named && possibleFrom < lane.opens) {
                from = possibleFrom
                openTo = from + LaneTopology.addedTaperMeters(speedAt(from), road)
                possibleTo = lane.opens
            } else {
                from = lane.opens
                val taper = when {
                    lane.opens <= run.from + 1e-9 -> 0.0
                    lane.taperMeters > 0.0 -> lane.taperMeters
                    replaces(lane) -> 0.0
                    else -> LaneTopology.addedTaperMeters(speedAt(lane.opens), road)
                }
                openTo = from + taper
                possibleTo = from
            }
            val tapers = vanishes(lane, run) || interiorEnd(lane, run)
            val to = if (tapers) lane.closes + LaneTopology.endingTaperMeters(speedAt(lane.closes), roadAt(lane.closes)) else lane.closes
            return DrawnLane(lane, width, from, to, openTo, possibleTo, lane.closes)
        }

        /**
         * Whether [lane] leaves by a road with lanes of its run on both sides of it (a lane kept for car pools beyond it):
         * the lanes on one side then close up, along the ending taper, not in a step.
         */
        private fun interiorEnd(lane: LaneTopology.Lane, run: LaneTopology.Run): Boolean {
            if (lane.closes >= run.to - 1e-9 || replacedAt(lane)) return false
            val at = lane.closes - 1e-6
            val others = topology.lanes.filter { it.run == lane.run && it.id != lane.id && it.exists(at) }
            return others.any { it.slot < lane.slot } && others.any { it.slot > lane.slot }
        }

        /** Whether a lane of [lane]'s slot takes its place where it ends. */
        private fun replacedAt(lane: LaneTopology.Lane): Boolean =
            topology.lanes.any { it.run == lane.run && it.id != lane.id && it.slot == lane.slot && abs(it.opens - lane.closes) < 1e-9 }

        /** Whether [lane] takes the place of a lane of the same slot that ends where it opens (its kinds changed). */
        private fun replaces(lane: LaneTopology.Lane): Boolean =
            topology.lanes.any { it.run == lane.run && it.id != lane.id && it.slot == lane.slot && abs(it.closes - lane.opens) < 1e-9 }

        /**
         * Whether [lane] ends with nothing to take it: not at its run's end, not where it leaves by a road (just past an
         * intersection), not where a lane of its slot takes its place.
         */
        private fun vanishes(lane: LaneTopology.Lane, run: LaneTopology.Run): Boolean {
            if (lane.closes >= run.to - 1e-9) return false
            if (nodes.any { abs(it.station + LaneTopology.LEAVE_PAST_POINT_METERS - lane.closes) < 1e-6 }) return false
            return !replacedAt(lane)
        }
    }
}
