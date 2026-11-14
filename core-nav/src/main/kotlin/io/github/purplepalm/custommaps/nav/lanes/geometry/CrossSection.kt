package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.RouteNode
import io.github.purplepalm.custommaps.nav.lanes.StreetWays
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
 *   line to the right and the oncoming half, as wide as those lanes where the road begins, has no lines
 *   ([Carriageway.TWO_WAY]); a lane that opens on the left, a left turn pocket, opens into it. Freeways and ramps are
 *   one-way; a street is one-way or two-way as [StreetWays] tells, street by street from intersection to intersection,
 *   and where no source tells, its lanes are laid out but not drawn ([drawsAt], [undrawnStreets]): where they lie across
 *   the road is not known. One road of a run sits as the first of its streets so told does; a later one told otherwise is
 *   not drawn. The streets over a card window's approach are drawn all or none ([StreetStretch]).
 * - **Street junctions** are no ramp starts: where the route turns at a junction it reaches on a street, the lanes it
 *   reached it in do not go on straight across the junction, and the road after it is laid out from the turn on (the
 *   drawing leaves the junction's inside to the map's own road, [RoutePath]).
 * - **Where no lane is named** (C1): the route's path runs on the road's line over such an approach and heads for it
 *   over the band before it, so there the carriageway that these rules laid off the road's line moves sideways, by the
 *   least that puts the road's line at least half a lane inside its edge lines, and holds that to the gore. It moves no
 *   faster than the path moves across a lane ([RoutePath.moveMeters]), anticipating each change of its edges, and
 *   smoothly over [SHIFT_SMOOTH_METERS]; elsewhere it moves back onto its place the same way ([Shift]).
 * - **Tapers** (4.3), each a half-cosine from the lane's inner edge: a lane that is added opens along the
 *   topology's own taper (a named deceleration lane over [LaneTopology.DECEL_TAPER_METERS], at full width
 *   [LaneTopology.DECEL_LATE_FULL_METERS] before the gore) or [LaneTopology.addedTaperMeters] (8:1 up to 45 mph,
 *   15:1 from 50); a lane on the exit's side that the plan does not name is drawn from where it may begin
 *   ([LaneTopology.Lane.possibleFrom]), [State.POSSIBLE] until it is known; so is a turn pocket, named or not
 *   ([LaneTopology.Lane.pocket]: along an added lane's taper, at full width no later than its late taper is), which
 *   the path enters from its neighbour along its late taper all the same ([lateEntry]); a lane that ends with nothing to take
 *   it narrows over [LaneTopology.endingTaperMeters] (W S from 45 mph, W S^2 / 60 below, at least 30 m), and so does one
 *   that leaves by a road between other lanes of its road. A lane that leaves by a road at an edge ends where it
 *   leaves; one followed in its place by another (its kinds changed: a lane kept for car pools or buses from there, or
 *   open to every car again) ends where the other begins, and no lane moves there. Where a lane opens or ends between
 *   others, the fewer lanes on one side of it move and the rest stay; a lane only possible there is not counted.
 * - **A ramp's start** ([RampStart]): where the route turns off its road and lanes go on, they keep their place,
 *   straight on along the old road, and settle onto the new road's carriageway as a path does past a turn
 *   ([turnOff]): from halfway to where their middle's straight-on line would cross its place on the new road, along
 *   a cubic that starts as fast as that line closes in and ends with neither slope nor curvature, never past their
 *   places; where that line never closes in, they move onto them over [RAMP_BLEND_METERS] from the turn's arc. The
 *   route geometry's diagonal across the lanes at a gore is never drawn.
 *
 * Pure; built once for a route's plan, off the main thread, and never stored.
 */
class CrossSection private constructor(
    val topology: LaneTopology,
    private val layouts: List<Layout>,
    /** Every place where the route turns onto another road and lanes go on, in route order; never a street junction. */
    val rampStarts: List<RampStart>,
    /** The streets whose lanes the evidence would draw but that are not drawn, for want of a source that tells how they sit. */
    val undrawnStreets: List<UndrawnStreet> = emptyList(),
) {

    /** The stretches of streets whose lanes are drawn, where a source tells how they sit ([drawsAt]), in route order. */
    val drawnStreets: List<ClosedFloatingPointRange<Double>> by lazy {
        layouts.flatMap { it.streetDrawn.orEmpty() }.sortedBy { it.start }
    }

    /**
     * A stretch from [from] to [to] where the evidence draws lanes on a street (known, bridged or a zone), on one road of the
     * run [run] ([roadFrom] to [roadTo]: from the run's start, or from a point where the route turns and lanes go on), that
     * is drawn whole or not at all: one street, from the intersection it leaves to the next, or the streets over a card
     * window's approach together, from [CARD_LEAD_METERS] before the window to its end. It is drawn where a source tells how
     * every street of it sits, all alike with the first street of its road a source tells of ([StreetWays]). [nodes] are the
     * intersections its streets leave, in route order.
     */
    data class StreetStretch(val run: Int, val roadFrom: Double, val roadTo: Double, val from: Double, val to: Double, val nodes: List<Int>)

    /**
     * Streets from [from] to [to] metres along the route where the evidence has lanes to draw, left undrawn: no source tells
     * whether they are one-way or two-way ([StreetWays]), or of a card window's approach where one does not; or [mixed]: told
     * otherwise than the first street of their road that a source tells of, or of a card window's approach where one is.
     */
    data class UndrawnStreet(val from: Double, val to: Double, val mixed: Boolean) {
        val meters: Double get() = to - from
    }

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
     * go on straight and settle onto it as a path does past a turn ([at], [turnOff]).
     */
    data class RampStart(val node: Int, val station: Double, val shift: Double) {
        /** How far the middle of the lanes lies to the left of its place on the new road at [at], along [axis]. */
        fun at(axis: RoadAxis, at: Double): Double = turnOff(axis, station, shift, at)

        /** Where the lanes lie on the new road for good, along [axis]. */
        fun settled(axis: RoadAxis): Double = turnOffEnd(axis, station, shift)

        /** Where the old road's lanes begin to go straight on along [axis]: where the arc at the turn begins. */
        fun begin(axis: RoadAxis): Double = beginOf(axis, station)
    }

    /**
     * A lane as it is drawn: from [from] (where it may begin, or opens) to [to] (where it is gone), with its tapers; for a turn
     * pocket drawn from where it may begin, the late taper the path enters it along ([lateFrom] to [lateTo]).
     */
    internal class DrawnLane(
        val lane: LaneTopology.Lane,
        val width: Double,
        val from: Double,
        val to: Double,
        val openTo: Double,
        val possibleTo: Double,
        val closeFrom: Double,
        val lateFrom: Double = Double.NaN,
        val lateTo: Double = Double.NaN,
    ) {
        /** How much of its width the lane has gained by [station], 0 to 1. */
        fun opened(station: Double): Double = ramp(station, from, openTo)

        /** How far along its late taper the path's entry has come by [station], 0 to 1; null without a late taper of its own. */
        fun entered(station: Double): Double? = if (lateFrom.isNaN()) null else ramp(station, lateFrom, lateTo)

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
        /**
         * On a street, the stretches of the road whose lanes are drawn: where a source tells how it sits ([StreetWays]);
         * null on a freeway or a ramp, drawn wherever the evidence has lanes.
         */
        val streetDrawn: List<ClosedFloatingPointRange<Double>>? = null,
    ) {
        /** How far the whole carriageway moves to the left where no lane is named ([Shift]); null where it stays. */
        var shift: Shift? = null

        /** Whether the lanes at [station] are drawn: always off streets, on a street where a source tells how it sits. */
        fun draws(station: Double): Boolean = streetDrawn?.any { station >= it.start && station <= it.endInclusive } ?: true

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
            shift?.let { edge += it.at(station) }
            return edge
        }
    }

    /**
     * How far a road's carriageway moves to the left (right where negative) so the road's line lies inside it where no lane
     * is named: its value every [step] metres from [from], straight between them; the first value before them and the last
     * after them.
     */
    internal class Shift(val from: Double, val step: Double, val values: DoubleArray) {
        fun at(station: Double): Double {
            val f = (station - from) / step
            if (f <= 0.0) return values.first()
            if (f >= values.size - 1) return values.last()
            val i = f.toInt()
            return values[i] + (values[i + 1] - values[i]) * (f - i)
        }
    }

    /**
     * The lanes drawn at [station], from the left. Along [axis], where given, a ramp's start puts each lane that was
     * there before the turn on its own straight line, and settles the lanes that go on onto their places on the new
     * road as a path settles past a turn ([turnOff]): each from where the middle begins to settle, by the same share of
     * how far it lay off its place there; without an axis the lanes lie on the new road from the turn on.
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
        val settled = abs(start.shift) <= SETTLED_METERS
        val settle = if (settled) null else settling(axis, begin, start.station, start.shift)
        // Where they lie on the new road for good ([RampStart.settled]).
        val end = if (settled) begin else settle?.to ?: (begin + RAMP_BLEND_METERS)
        if (station >= start.station && station >= end) return plain
        val before = layouts.firstOrNull { it.run == layout.run && it.to == start.station } ?: return plain
        val after = layouts.firstOrNull { it.run == layout.run && it.from == start.station } ?: return plain
        val old = before.placesAt(begin)
        // Each lane's place on the new road: from the turn on where it lies there, before the turn where it lies at the turn.
        val targets = after.placesAt(max(station, start.station))
        // How much of its offset from that place a lane still has: the straight-on line's own (1) until the middle begins
        // to settle, from there the middle's cubic of the offset it had there; where the middle's line never closes in,
        // the path's half-cosine of a half-cosine from the turn's arc.
        val from = settle?.from ?: begin
        val share = when {
            settle == null -> 1.0 - ramp(ramp(station, begin, begin + RAMP_BLEND_METERS), 0.0, 1.0)
            station < settle.from -> 1.0
            station >= settle.to -> 0.0
            else -> (1.0 - (station - settle.from) / (settle.to - settle.from)).let { u -> u * u * u }
        }
        return plain.map { p ->
            val q = old.firstOrNull { it.lane == p.lane }
            val t = targets.firstOrNull { it.lane == p.lane }
            val left = q?.let { lineOffset(axis, begin, it.left, station) }
            val right = q?.let { lineOffset(axis, begin, it.right, station) }
            when {
                left == null || right == null -> {
                    // A lane that was not there before the turn moves with the middle of those that were.
                    val shift = start.at(axis, station) - if (layout.to == start.station) start.shift else 0.0
                    p.copy(left = p.left + shift, right = p.right + shift)
                }
                // A lane that does not go on, or the straight-on stretch before the middle settles: its straight line.
                t == null || (settle != null && station < settle.from) -> p.copy(left = left, right = right)
                else -> {
                    // Its offset from its place where it began to settle, by the share left of it.
                    val l0 = if (settle == null) q.left else lineOffset(axis, begin, q.left, from) ?: q.left
                    val r0 = if (settle == null) q.right else lineOffset(axis, begin, q.right, from) ?: q.right
                    val t0 = after.placesAt(max(from, start.station)).firstOrNull { it.lane == p.lane } ?: t
                    p.copy(left = t.left + (l0 - t0.left) * share, right = t.right + (r0 - t0.right) * share)
                }
            }
        }
    }

    /** The lane [lane] at [station], where it is drawn; null elsewhere. */
    fun place(lane: Int, station: Double, axis: RoadAxis? = null): Place? = lanes(station, axis).firstOrNull { it.lane == lane }

    /** Where [lane] begins to be drawn: where it may begin, or where its taper begins; null for a lane drawn nowhere. */
    internal fun drawnFrom(lane: Int): Double? = drawnLane(lane)?.from

    /**
     * For a turn pocket drawn from where it may begin ([LaneTopology.Lane.pocket]), how far the path's entry into it from its
     * neighbour has come at [station], 0 to 1: along its late taper, from where it opens for the plan
     * ([LaneTopology.Lane.opens]) over [LaneTopology.Lane.taperMeters], whatever its drawn width there. Null for any other lane,
     * which the path enters along its drawn taper.
     */
    fun lateEntry(lane: Int, station: Double): Double? = drawnLane(lane)?.entered(station)

    /** The first drawn piece of [lane], by its id; null for a lane no road of a run draws. */
    private fun drawnLane(lane: Int): DrawnLane? = drawnById[lane]

    private val drawnById: Map<Int, DrawnLane> by lazy {
        val out = HashMap<Int, DrawnLane>()
        for (layout in layouts) for (d in layout.lanes) out.putIfAbsent(d.lane.id, d)
        out
    }

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
        // On a two-way street the oncoming half: as wide as the route's lanes where the road begins, left of its line.
        val outer = oncomingEdge(station, axis) ?: left
        return Edges(left, right, outer + APRON_METERS, right - APRON_METERS)
    }

    /** The kind of road at [station] on a run; null off every run. */
    fun roadAt(station: Double): RoadKind? = layoutAt(station)?.road

    /** How far the carriageway at [station] lies moved to the left of where the rules lay it, where no lane is named ([Shift]). */
    internal fun shiftAt(station: Double): Double = layoutAt(station)?.shift?.at(station) ?: 0.0

    /** How the carriageway at [station] sits on its road's line: one-way, or the route's half of a two-way street; null off every run. */
    fun carriagewayAt(station: Double): Carriageway? = layoutAt(station)?.carriageway

    /**
     * Whether the lanes at [station] are drawn: on a freeway or a ramp wherever a run's lanes are, on a street only where a
     * source tells whether it is one-way or two-way ([StreetWays]). False off every run.
     */
    fun drawsAt(station: Double): Boolean = layoutAt(station)?.draws(station) ?: false

    /**
     * The parts from [from] to [to] of the run [run] that lie on streets whose lanes are drawn ([drawsAt]), in route order:
     * the street stretches of the evidence that the drawing takes.
     */
    fun streetParts(from: Double, to: Double, run: Int): List<ClosedFloatingPointRange<Double>> {
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        for (layout in layouts) {
            if (layout.run != run || layout.to <= from || layout.from >= to) continue
            val drawn = layout.streetDrawn ?: continue
            for (span in drawn) {
                val a = max(max(from, layout.from), span.start)
                val b = min(min(to, layout.to), span.endInclusive)
                if (b - a <= SAME_STATION) continue
                val last = out.lastOrNull()
                if (last != null && a <= last.endInclusive + SAME_STATION) out[out.size - 1] = last.start..max(last.endInclusive, b) else out.add(a..b)
            }
        }
        return out
    }

    /**
     * Where the oncoming half of a two-way street ends at [station], left of the road's line: as far as the route's lanes are
     * wide where its road begins, or as far as the lanes there reach where they lie farther left (a left pocket opens into
     * it); null where the carriageway is no two-way street's.
     */
    fun oncomingEdge(station: Double, axis: RoadAxis? = null): Double? {
        val layout = layoutAt(station) ?: return null
        if (layout.carriageway != Carriageway.TWO_WAY) return null
        val left = lanes(station, axis).firstOrNull()?.left ?: layout.left
        return max(left, layout.left + layout.lanes.filter { it.from <= layout.from && it.to > layout.from }.sumOf { it.width })
    }

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

        /**
         * Past a turn whose straight-on line never closes in on the new road's line, a path and the lanes move onto it over
         * this distance from the turn's arc. A path on the road's line through a ramp's start keeps to it at least this far
         * past the turn, and a ramp's start reaches this far past [RAMP_START_MAX_METERS] at most.
         */
        const val RAMP_BLEND_METERS = 25.0

        /** The new road's line must lie farther out within this distance of the turn; otherwise the lanes blend at once. */
        const val RAMP_START_MAX_METERS = 300.0

        /** A ramp's start reaches back to where the arc at its turn begins, at most this far. */
        private const val RAMP_LOOK_BACK_METERS = 50.0

        /**
         * Where no lane is named, the carriageway's move onto the road's line ([Shift]) is smoothed over this distance: as long
         * as the asphalt's taper where a drawing begins.
         */
        const val SHIFT_SMOOTH_METERS = RoutePath.ASPHALT_TAPER_METERS

        /** That move is worked out every this many metres. */
        internal const val SHIFT_STEP_METERS = 2.0

        /**
         * The streets over a card window are drawn all or none from this far before it ([StreetStretch]): a drawing that ends
         * before them is back on the road's line by the window's start ([RoutePath.RETURN_METERS]).
         */
        const val CARD_LEAD_METERS = RoutePath.RETURN_METERS

        /** Stations closer than this are one. */
        private const val SAME_STATION = 1e-6

        /** A move smaller than this is none. */
        private const val SAME_OFFSET = 1e-9

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
         * The cross-section of [plan]'s route, its zones from [evidence] (the plan's second pass). [streets] tells how each
         * street of the route sits, one-way or two-way; without it, or where it tells nothing, a street's lanes are laid out
         * one-way but not drawn ([drawsAt]).
         */
        fun of(
            plan: LanePlan,
            evidence: LaneEvidence = plan.evidence(),
            streets: StreetWays? = null,
        ): CrossSection = Builder(plan, evidence, streets ?: StreetWays.none(plan.topology.evidence.route)).build()

        /**
         * The stretches of streets in [plan]'s route whose lanes [evidence] would draw, each whole or not at all ([StreetStretch]),
         * in route order: the ones [of] draws where a source tells how they sit. Without the layouts: a pass over the runs, the
         * evidence's stretches and the plan's card windows.
         */
        fun streetStretches(plan: LanePlan, evidence: LaneEvidence = plan.evidence()): List<StreetStretch> =
            Builder(plan, evidence, StreetWays.none(plan.topology.evidence.route)).streetStretches()

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

        /**
         * Where the arc at the turn at [station] begins along [axis] ([RoadAxis.filletAt]): the turn itself where the road does not
         * bend there.
         */
        internal fun beginOf(axis: RoadAxis, station: Double): Double = max(axis.from, axis.filletAt(station)?.from ?: station)

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
    private class Builder(val plan: LanePlan, val evidence: LaneEvidence, val streets: StreetWays) {
        private val topology = plan.topology
        private val route = topology.evidence.route
        private val nodes = route.nodes

        fun build(): CrossSection {
            val layouts = ArrayList<Layout>()
            // Each turn onto another road whose lanes go on, with the road before it and the road after it.
            val ramps = ArrayList<Triple<LaneTopology.Point, Layout, Layout>>()
            val undrawn = ArrayList<UndrawnStreet>()
            val drawn = topology.lanes.map { drawnLane(it) }
            for (run in topology.runs) {
                val runLanes = drawn.filter { it.lane.run == run.index }.sortedBy { it.lane.slot }
                var previous: Layout? = null
                for ((from, to, turn) in roadsOf(run)) {
                    // On a road the route turns onto, the old road's lanes that leave at the turn are not drawn.
                    val lanes = runLanes.filter { d ->
                        d.to > from && d.from < to && !(turn != null && d.from < from && d.lane.closes <= from + LaneTopology.LEAVE_PAST_POINT_METERS + 1e-9)
                    }
                    val road = nodeAt(from)?.road ?: RoadKind.FREEWAY
                    // A street sits as its sources tell and is drawn where they do; a freeway or a ramp is one-way.
                    val (kind, streetDrawn) = if (road == RoadKind.STREET) streetWay(from, to, undrawn) else Carriageway.ONE_WAY to null
                    val there = lanes.filter { it.from <= from && it.to > from }
                    val width = there.sumOf { it.widthAt(from) }
                    val left = if (kind == Carriageway.TWO_WAY) 0.0 else width / 2.0
                    val opening = lanes.filter { it.from > from && it.from < to && leftSide(it, lanes, it.from, opens = true) }
                    val closing = lanes.filter { it.closeFrom >= from && it.closeFrom < to && leftSide(it, lanes, it.closeFrom, opens = false) }
                    val zones = if (kind == Carriageway.TWO_WAY) emptyList() else zonesOf(run.index, from, to, road)
                    val layout = Layout(run.index, from, to, road, kind, lanes, left, opening, closing, zones, streetDrawn)
                    // A junction the route reaches on a street is no ramp's start: its lanes do not go on straight across it.
                    if (turn != null && previous != null && route.approachRoad(turn.node) != RoadKind.STREET) ramps.add(Triple(turn, previous, layout))
                    layouts.add(layout)
                    previous = layout
                }
            }
            layouts.sortBy { it.from }
            // Where no lane is named the carriageway moves onto the road's line first: the ramps' starts are read on the roads
            // as they are drawn.
            val spans = noLaneSpans()
            for (layout in layouts) {
                if (layout.carriageway != Carriageway.ONE_WAY || layout.road == RoadKind.STREET) continue
                val mine = spans.filter { it.first == layout.run && it.second.endInclusive > layout.from && it.second.start < layout.to }.map { it.second }
                if (mine.isNotEmpty()) layout.shift = shiftFor(layout, mine)
            }
            val starts = ArrayList<RampStart>()
            for ((turn, previous, layout) in ramps) {
                // The lanes that go on, where they lay on the old road and where they lie on the new one.
                val old = previous.placesAt(layout.from - 1e-6).filter { p -> layout.lanes.any { it.lane.id == p.lane } }
                val new = layout.placesAt(layout.from).filter { p -> old.any { it.lane == p.lane } }
                if (old.isNotEmpty() && new.isNotEmpty()) {
                    val oldMiddle = (old.first().left + old.last().right) / 2.0
                    val newMiddle = (new.first().left + new.last().right) / 2.0
                    starts.add(RampStart(turn.node, turn.station, oldMiddle - newMiddle))
                }
            }
            return CrossSection(topology, layouts, starts, undrawn.sortedBy { it.from })
        }

        /**
         * How the street from [from] to [to], one road of a run, sits ([StreetWays]), and the stretches of it whose lanes are
         * drawn. Over each stretch the evidence draws lanes on (known, bridged or a zone, merged where they touch) every street
         * the route takes, from intersection to intersection, is drawn where a source tells how it sits, alike with the first
         * street of the road a source tells of: the road sits as that one does, and a later one told otherwise is not drawn,
         * since one road of a run lays its lanes out one way. The streets over a card window's approach are drawn all or none
         * ([unitsOf]). So what the streets past a station are told never changes whether one before it is drawn, which lets a
         * drawing take the electronic horizon's answers window by window
         * ([io.github.purplepalm.custommaps.nav.lanes.HorizonEvidence]). The streets left undrawn go to [undrawn], those that
         * touch for the same cause as one.
         */
        private fun streetWay(from: Double, to: Double, undrawn: MutableList<UndrawnStreet>): Pair<Carriageway, List<ClosedFloatingPointRange<Double>>> {
            val units = groupsOf(from, to).flatMap { unitsOf(it) }
            val first = units.firstNotNullOfOrNull { unit -> unit.firstNotNullOfOrNull { wayOf(it) } }
            val drawn = ArrayList<ClosedFloatingPointRange<Double>>()
            val left = ArrayList<UndrawnStreet>()
            for (unit in units) {
                val ways = unit.map { wayOf(it) }
                val unknown = ways.any { it == null }
                if (!unknown && ways.all { it == first }) {
                    for (street in unit) join(drawn, street.from, street.to)
                    continue
                }
                // Told otherwise than its road, a street is mixed; one taken with a street no source tells is as none told.
                for ((street, way) in unit.zip(ways)) {
                    val mixed = way != null && (way != first || !unknown)
                    val last = left.lastOrNull()
                    if (last != null && last.mixed == mixed && street.from <= last.to + SAME_STATION) {
                        left[left.size - 1] = last.copy(to = max(last.to, street.to))
                    } else {
                        left.add(UndrawnStreet(street.from, street.to, mixed))
                    }
                }
            }
            undrawn.addAll(left)
            return (first ?: Carriageway.ONE_WAY) to drawn
        }

        /** The street that leaves intersection [node] where it lies over a stretch the evidence draws lanes on: from [from] to [to]. */
        private data class Street(val from: Double, val to: Double, val node: Int)

        /** How [street] sits as a source tells ([StreetWays]); one-way where the road that leaves its intersection is a freeway or a ramp. */
        private fun wayOf(street: Street): Carriageway? =
            if (nodes[street.node].road == RoadKind.STREET) streets.at(street.node)?.carriageway else Carriageway.ONE_WAY

        /** The streets over [group], each from the intersection it leaves to the next, cut to the group, in route order. */
        private fun streetsOf(group: ClosedFloatingPointRange<Double>): List<Street> {
            val out = ArrayList<Street>()
            var k = nodeIndex(group.start)
            while (k < nodes.size && nodes[k].station < group.endInclusive) {
                val a = max(group.start, nodes[k].station)
                val b = min(group.endInclusive, if (k + 1 < nodes.size) nodes[k + 1].station else route.lengthMeters)
                if (b - a > SAME_STATION) out.add(Street(a, b, k))
                k++
            }
            return out
        }

        /**
         * The streets over [group] as they are drawn, whole or not at all ([StreetStretch]): each alone, but those over a card
         * window's approach together, from [CARD_LEAD_METERS] before the window to its end, with any that share a street with
         * them. A card window's lanes are drawn all through it or not at all, and a drawing that ends before it is back on the
         * road's line by the window's start: the route never swings to the road's line and back inside a card window (the
         * design's 3.2 item 7a).
         */
        private fun unitsOf(group: ClosedFloatingPointRange<Double>): List<List<Street>> {
            val along = streetsOf(group)
            // For each street, the last of those taken with it.
            val last = IntArray(along.size) { it }
            for (window in plan.windows) {
                val a = window.from - CARD_LEAD_METERS
                val first = along.indexOfFirst { it.to > a + SAME_STATION && it.from < window.to - SAME_STATION }
                if (first < 0) continue
                last[first] = max(last[first], along.indexOfLast { it.from < window.to - SAME_STATION && it.to > a + SAME_STATION })
            }
            val units = ArrayList<List<Street>>()
            var i = 0
            while (i < along.size) {
                var j = last[i]
                var k = i
                while (k <= j) {
                    j = max(j, last[k])
                    k++
                }
                units.add(along.subList(i, j + 1).toList())
                i = j + 1
            }
            return units
        }

        /** [list] with [from] to [to] added at its end, joined to its last where they touch. */
        private fun join(list: MutableList<ClosedFloatingPointRange<Double>>, from: Double, to: Double) {
            val last = list.lastOrNull()
            if (last != null && from <= last.endInclusive + SAME_STATION) list[list.size - 1] = last.start..max(last.endInclusive, to) else list.add(from..to)
        }

        /** One road of a run: from [from] to [to], from the run's start or from the point [turn] where the route turns and lanes go on. */
        private data class RoadOfRun(val from: Double, val to: Double, val turn: LaneTopology.Point?)

        /** The roads of [run]: from its start, and from every point where the route turns and lanes go on, in route order. */
        private fun roadsOf(run: LaneTopology.Run): List<RoadOfRun> {
            val points = run.points.map { topology.points[it] }
            val turns = points.filter { it.turns && it.station > run.from && it.station < run.to && it !== points.last() }
            val bounds = turns.map { it.station } + run.to
            var from = run.from
            return bounds.mapIndexed { k, to -> RoadOfRun(from, to, if (k > 0) turns[k - 1] else null).also { from = to } }
        }

        /** Every street stretch of every run's roads that is drawn whole or not at all ([StreetStretch]), in route order. */
        fun streetStretches(): List<StreetStretch> {
            val out = ArrayList<StreetStretch>()
            for (run in topology.runs) {
                for (road in roadsOf(run)) {
                    if ((nodeAt(road.from)?.road ?: RoadKind.FREEWAY) != RoadKind.STREET) continue
                    for (group in groupsOf(road.from, road.to)) {
                        for (unit in unitsOf(group)) {
                            val told = unit.map { it.node }.filter { nodes[it].road == RoadKind.STREET }
                            out.add(StreetStretch(run.index, road.from, road.to, unit.first().from, unit.last().to, told))
                        }
                    }
                }
            }
            return out.sortedBy { it.from }
        }

        /**
         * The stretches from [from] to [to] that the evidence draws lanes on (known, bridged or a zone, with lanes), merged where
         * they touch, in route order.
         */
        private fun groupsOf(from: Double, to: Double): List<ClosedFloatingPointRange<Double>> {
            val groups = ArrayList<ClosedFloatingPointRange<Double>>()
            for (stretch in evidence.stretches) {
                if (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED && stretch.kind != LaneEvidence.Kind.ZONE) continue
                if (stretch.lanes <= 0) continue
                val a = max(stretch.from, from)
                val b = min(stretch.to, to)
                if (b - a <= SAME_STATION) continue
                val last = groups.lastOrNull()
                if (last != null && a <= last.endInclusive + SAME_STATION) groups[groups.size - 1] = last.start..max(last.endInclusive, b) else groups.add(a..b)
            }
            return groups
        }

        /** The index of the last intersection at or before [station]; 0 before the first. */
        private fun nodeIndex(station: Double): Int {
            var lo = 0
            var hi = nodes.size - 1
            var found = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (nodes[mid].station <= station + 1e-9) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }

        /**
         * Where the route's path runs on the road's line or heads for it, by run: each approach where no lane is named that is
         * drawn as a zone (C1) as far as it is drawn, and the band before it, which heads for it, back over its run's stretches
         * where the plan names no lane in a card window and no other such approach lies, within the drawn stretch that holds
         * the approach's start.
         */
        private fun noLaneSpans(): List<Pair<Int, ClosedFloatingPointRange<Double>>> {
            if (plan.noLane.none { it.zone }) return emptyList()
            val drawn = drawnStretches()
            val segments = plan.segments
            val out = ArrayList<Pair<Int, ClosedFloatingPointRange<Double>>>()
            for (n in plan.noLane) {
                if (!n.zone) continue
                val k = segments.indexOfFirst { it.to > n.from + SAME_STATION }
                if (k < 0) continue
                val run = segments[k].run
                for (stretch in drawn) {
                    if (stretch.third != run || stretch.second <= n.from || stretch.first >= n.to) continue
                    val to = min(n.to, stretch.second)
                    var from = max(n.from, stretch.first)
                    if (n.from >= stretch.first - SAME_STATION) {
                        // The approach begins on this drawn stretch: the band before it heads for it.
                        for (i in k downTo 0) {
                            val seg = segments[i]
                            if (seg.run != run || seg.to <= stretch.first) break
                            if (seg.from >= n.from - SAME_STATION) continue
                            // Another approach where no lane is named, or a card window's named lane: the path heads for that.
                            if (seg.noLane != null && seg.to < n.from - SAME_STATION || seg.noLane == null && seg.inWindow && seg.named != null) {
                                from = min(from, seg.to)
                                break
                            }
                            if (seg.noLane == null) from = seg.from
                        }
                        from = max(from, stretch.first)
                    }
                    if (to > from) out.add(run to from..to)
                }
            }
            return out
        }

        /** The stretches the app draws lanes on ([RoutePath]), by run, merged where they touch: from, to and the run. */
        private fun drawnStretches(): List<Triple<Double, Double, Int>> {
            val drawn = ArrayList<Triple<Double, Double, Int>>()
            for (stretch in evidence.stretches) {
                if (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED && stretch.kind != LaneEvidence.Kind.ZONE) continue
                if (stretch.road == RoadKind.STREET || stretch.lanes <= 0) continue
                for (run in topology.runs) {
                    val from = max(stretch.from, run.from)
                    val to = min(stretch.to, run.to)
                    if (to <= from) continue
                    val k = drawn.indexOfLast { it.third == run.index }
                    val last = if (k >= 0) drawn[k] else null
                    if (last != null && from <= last.second + 1e-6) drawn[k] = Triple(last.first, max(last.second, to), run.index) else drawn.add(Triple(from, to, run.index))
                }
            }
            return drawn
        }

        /**
         * How far the carriageway of [layout] moves so the road's line lies at least half a lane inside its edge lines over
         * [spans] ([Shift]); null where it lies so already. At each sample of the spans the move must lie between the least
         * that brings the road's line half a lane inside the left edge line and the most that keeps it half a lane inside the
         * right one (the middle where the carriageway is narrower than a lane), and it is smoothed over [SHIFT_SMOOTH_METERS]:
         * so it meets the tightest of those bounds within that distance of each sample, and the smoothing keeps it within them.
         * The move is the one nearest no move within those bounds as they reach along the road at the slope of a move across a
         * lane, so it begins before each change it needs, as where a lane leaving at the edge narrows the carriageway at once,
         * and ends after each it no longer does.
         */
        private fun shiftFor(layout: Layout, spans: List<ClosedFloatingPointRange<Double>>): Shift? {
            val n = ((layout.to - layout.from) / SHIFT_STEP_METERS).toInt() + 1
            if (n < 2) return null
            val half = laneMeters(layout.road) / 2.0
            val lo = DoubleArray(n) { Double.NEGATIVE_INFINITY }
            val hi = DoubleArray(n) { Double.POSITIVE_INFINITY }
            val inside = { x: Double -> spans.any { x >= it.start && x < it.endInclusive } }
            // The bounds at [x], into the samples [into]: the least and the most move there.
            fun bound(x: Double, vararg into: Int) {
                val places = layout.placesAt(x).filter { it.width > 0.0 }
                if (places.isEmpty()) return
                var low = half - places.first().left
                var high = -half - places.last().right
                if (low > high) {
                    // Narrower than a lane: its middle on the line.
                    low = (low + high) / 2.0
                    high = low
                }
                for (i in into) {
                    if (i < 0 || i >= n) continue
                    lo[i] = max(lo[i], low)
                    hi[i] = min(hi[i], high)
                }
            }
            for (i in 0 until n) {
                val x = layout.from + i * SHIFT_STEP_METERS
                if (inside(x)) bound(x, i)
            }
            // Where an edge steps or a taper begins between two samples, its bounds hold at both: the move is straight between
            // them.
            for (d in layout.lanes) {
                for (e in doubleArrayOf(d.from, d.openTo, d.possibleTo, d.closeFrom, d.to)) {
                    if (e <= layout.from || e >= layout.to) continue
                    val k = ((e - layout.from) / SHIFT_STEP_METERS).toInt()
                    for (x in doubleArrayOf(e - SAME_STATION, e + SAME_STATION)) if (inside(x)) bound(x, k, k + 1)
                }
            }
            var needed = false
            var speedFrom = Double.NaN
            for (i in 0 until n) {
                if (lo[i] > hi[i]) {
                    val mid = (lo[i] + hi[i]) / 2.0
                    lo[i] = mid
                    hi[i] = mid
                }
                if (lo[i] > 0.0 || hi[i] < 0.0) needed = true
                if (speedFrom.isNaN() && lo[i].isFinite()) speedFrom = layout.from + i * SHIFT_STEP_METERS
            }
            if (!needed) return null
            // The smoothing averages twice over [width] samples on each side: within the tightest bounds of twice that, its
            // average lies within each sample's own.
            val width = max(1, (SHIFT_SMOOTH_METERS / SHIFT_STEP_METERS / 2.0).toInt())
            val low = DoubleArray(n)
            val high = DoubleArray(n)
            for (i in 0 until n) {
                var l = Double.NEGATIVE_INFINITY
                var h = Double.POSITIVE_INFINITY
                for (j in max(0, i - 2 * width)..min(n - 1, i + 2 * width)) {
                    l = max(l, lo[j])
                    h = min(h, hi[j])
                }
                if (l > h) {
                    l = (l + h) / 2.0
                    h = l
                }
                low[i] = l
                high[i] = h
            }
            // A move across a lane's width takes as long as the path's own move across a lane; where a bound must be met
            // farther on, it is met from here at that slope.
            val slope = laneMeters(layout.road) / RoutePath.moveMeters(speedAt(speedFrom), layout.road)
            val reach = slope * SHIFT_STEP_METERS
            for (i in 1 until n) {
                low[i] = max(low[i], low[i - 1] - reach)
                high[i] = min(high[i], high[i - 1] + reach)
            }
            for (i in n - 2 downTo 0) {
                low[i] = max(low[i], low[i + 1] - reach)
                high[i] = min(high[i], high[i + 1] + reach)
            }
            var values = DoubleArray(n) { i -> if (low[i] <= high[i]) min(max(0.0, low[i]), high[i]) else (low[i] + high[i]) / 2.0 }
            repeat(2) { values = averaged(values, width) }
            // Within each sample's own bounds whatever the smoothing did where none could be met; a move too small to be one is none.
            for (i in 0 until n) {
                values[i] = min(max(values[i], lo[i]), hi[i])
                if (abs(values[i]) <= SAME_OFFSET) values[i] = 0.0
            }
            val first = values.indexOfFirst { abs(it) > SAME_OFFSET }
            if (first < 0) return null
            val last = values.indexOfLast { abs(it) > SAME_OFFSET }
            val start = max(0, first - 1)
            val end = min(n - 1, last + 1)
            return Shift(layout.from + start * SHIFT_STEP_METERS, SHIFT_STEP_METERS, values.copyOfRange(start, end + 1))
        }

        /** [values] averaged over [width] samples on each side, the first and the last taken again beyond the ends: held there. */
        private fun averaged(values: DoubleArray, width: Int): DoubleArray {
            val n = values.size
            val sums = DoubleArray(n + 1)
            for (i in 0 until n) sums[i + 1] = sums[i] + values[i]
            return DoubleArray(n) { i ->
                val before = max(0, width - i)
                val after = max(0, i + width - (n - 1))
                val inside = sums[min(n - 1, i + width) + 1] - sums[max(0, i - width)]
                (inside + before * values.first() + after * values.last()) / (2 * width + 1)
            }
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
            val placed = placedAt(lane)
            var lateFrom = Double.NaN
            var lateTo = Double.NaN
            if (possibleFrom != null && (lane.id !in plan.named || lane.pocket) && possibleFrom < lane.opens) {
                from = possibleFrom
                val taper = from + LaneTopology.addedTaperMeters(speedAt(from), road)
                if (lane.pocket) {
                    // A turn pocket is at full width no later than its late taper is, and the path enters it along that taper.
                    lateFrom = lane.opens
                    lateTo = lane.opens + lane.taperMeters
                    openTo = min(taper, lateTo)
                } else {
                    openTo = taper
                }
                possibleTo = lane.opens
            } else if (placed != null) {
                // A gain on the far side the electronic horizon placed: it opens late inside its zone, which ends where the
                // horizon's count rises, rather than late before the point that lists it.
                openTo = placed
                from = max(run.from, placed - LaneTopology.addedTaperMeters(speedAt(placed), road))
                possibleTo = from
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
            return DrawnLane(lane, width, from, to, openTo, possibleTo, lane.closes, lateFrom, lateTo)
        }

        /**
         * Where the electronic horizon placed the gain that adds [lane] ([LaneEvidence.placedGains]): the edge end where its
         * count rises, for a lane of a slot of its own the plan opens later than that; null for any other lane.
         */
        private fun placedAt(lane: LaneTopology.Lane): Double? {
            val k = lane.addedAt ?: return null
            val at = evidence.placedGains[topology.points[k].node] ?: return null
            if (replaces(lane) || at > lane.opens + 1e-9) return null
            return at
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
