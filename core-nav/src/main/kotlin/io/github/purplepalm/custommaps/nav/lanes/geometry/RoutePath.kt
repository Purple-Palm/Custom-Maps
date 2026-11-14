package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanSegment
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The route as one path through its planned lanes (the design's 5.3, "the path", and 4.3): the line the app draws
 * the route along, built once for a route from its lane evidence, topology and plan, off the main thread.
 *
 * - **Where it runs.** On the road's line where the app draws no lanes, which is the route's own line, point for
 *   point; on the middle of the lanes that work (the plan's band) outside card windows; on the named lane's middle
 *   inside card windows, through zones too. Where no lane works (a conflict the plan flags), on the middle of the lanes
 *   drawn there, so the path stays on the drawn carriageway where it moves off the road's line. On a C1 approach, where
 *   the plan names no lane, on the road's line. The app draws lanes on freeways and ramps where the evidence has them
 *   (known, bridged or a zone), and on streets where a source also tells whether they are one-way or two-way
 *   ([CrossSection.drawsAt]). A junction the route reaches on a street and turns at ends the drawing before it and
 *   begins the one after it [JUNCTION_FAR_SIDE_METERS] past its node: its inside is the map's own road, which the path
 *   crosses on its own curve into the receiving road's lane, or onto its line where none is claimed ([Junction]); at a
 *   roundabout it keeps to the road's line, with no curve of its own.
 * - **Moves.** Inside drawn lanes and zones the path moves only toward the lane the plan heads for, the first named
 *   lane of the card window ahead, and never past it: the first move may go either way, then never back. A move
 *   between lanes is a half-cosine S of [moveMeters] per lane crossed (clamp(v x [MOVE_SECONDS_FREEWAY], 80, 270 m) on
 *   freeways, clamp(v x [MOVE_SECONDS_STREET], 25, 80 m) on streets, at the planning speed), starting at the plan's
 *   station, ahead of the car. Into a lane its neighbour feeds (a deceleration lane) it moves along that lane's own
 *   taper, with no S; into a turn pocket drawn from where it may begin, along its late taper. The lane it heads for lies
 *   where it runs at its full width, and while that lane is still opening the path goes no farther toward it than where
 *   it begins. A move blends from where the path would be without it
 *   toward its lane as that lane lies along the way, so the path moves with lanes that move together (at a ramp's start,
 *   in a zone); a move into a lane still opening sets off only the way that lane lies where the move ends, so it waits
 *   for the lane rather than set off the wrong way and come back. A move out of a lane that leaves with no taper at the
 *   edge the path moves from (by a road at that edge) is done by where that lane ends, in no less than
 *   [RAMP_MOVE_MIN_METERS] per lane crossed ([lengthBeforeRampStart]), so the path never lies off the lanes there.
 * - **No lane named (C1).** Ahead of an approach where no lane can be named (only a lane kept for car pools or buses
 *   can be used, or the lanes to use lie beyond one), the plan heads for the road's line, where the path runs on that
 *   approach: the band's middle before it leads the path toward the road's line and never past it, so the path reaches
 *   the approach on the line with no move back to it. Where the drawn carriageway lies off that line there, the
 *   cross-section moves it so the line lies at least half a lane inside its edge lines ([CrossSection]). Where such an
 *   approach ends at a turn onto a road with lanes, the path keeps to the road's line until those lanes lie on the new road
 *   ([CrossSection.RampStart]) and moves into its lane from there.
 * - **Turns.** Where the route turns onto another road with lanes, the path goes on with its lane straight along the
 *   old road and settles with the lanes onto the new road ([CrossSection.RampStart]), as it settles where the drawing
 *   ends at a turn: the route geometry's diagonal at a gore is never drawn. Only the lanes that go on are drawn past the turn, so a move toward a
 *   lane the plan names late ends there, in its lane ([lengthBeforeRampStart]). Where the drawing ends at such a turn,
 *   the path goes on straight from where it is the same way and, from halfway to where that line would cross the new
 *   road's line, settles onto it, never past it and with no kink where it meets it ([CrossSection.turnOff]); the turn is
 *   over there, and a drawing that begins within the turn's reach takes over from it.
 * - **The drawing's ends.** The path returns to the road's line only where the drawing ends, over [RETURN_METERS] on
 *   asphalt without lane lines (the asphalt then tapers over [ASPHALT_TAPER_METERS]); a drawing ends only outside
 *   card windows (inside them gaps are zones). At every end the path meets the route's own line, so the line the
 *   app draws is the navigation SDK's away from lanes, and the last [BACK_SAMPLED_METERS] before it keep every sample, so
 *   it joins that line without a kink. The turn or the return at a drawing's end takes over a move still under way there,
 *   so no move runs on past the drawing.
 * - **Folds.** Where the path's offset would fold over itself on a curve too sharp for it (1 - kappa t <= 0, [RoadAxis]),
 *   it keeps its lane and turns where it would cross itself ([Loops]), so the lane it is in and the lit lane go on
 *   through a bend of a few degrees whose arc is short between two close vertices; where the road turns there by more
 *   than [FOLD_KEEP_DEGREES] (a corner, a mapping kink) it goes to the road's line, over [RETURN_METERS] on each side
 *   ([folds]).
 *
 * The geometry is computed in drawing windows of at most [WINDOW_METERS], each in local metres of one origin
 * ([RoadAxis]), cut on straight pieces so the windows meet without a kink, and simplified to [TOLERANCE_METERS]. Every
 * point of the path keeps the route station it lies at ([stations]), for the station table. Pure and deterministic;
 * built once for a route, never stored.
 */
class RoutePath private constructor(
    /** The navigator's id of the route. */
    val routeId: String,
    /** Which path of the route this is: a new path of the same route gets a new version. */
    val version: Int,
    /** The route's own line, as the map draws the navigation SDK's, and the station of each of its points. */
    val route: List<GeoPoint>,
    val routeStations: DoubleArray,
    /** The path's points and the route station each lies at, ascending. */
    val points: List<GeoPoint>,
    val stations: DoubleArray,
    /** The stretches the app draws lanes on, with where the path leaves the road's line and is back on it. */
    val drawings: List<Drawing>,
    /** Every move of the path across the road, in route order. */
    val moves: List<Move>,
    /** The stretches where the path's offset would fold. */
    val folds: List<Fold>,
    /** The stretches past a ramp's start where the path keeps to the road's line, having reached the turn on it. */
    val diagonals: List<Diagonal>,
    private val windows: List<Window>,
    /** Every street junction the path crosses on its own curve ([Junction]), in route order. */
    val junctions: List<Junction> = emptyList(),
) {

    /**
     * A stretch from [from] to [to] where the app draws lanes; the path leaves the road's line at [from] and is back
     * on it at [back]; the drawing's asphalt runs from [asphaltFrom] to [asphaltTo]. [turnsAtEnd]: it ends where the
     * route turns onto a road without lanes, and the path goes on straight in its lane and blends onto that road.
     * [junctionEnd]: it ends where the route turns at a junction it reaches on a street, and its carriageway ends at the
     * junction's near side ([carriagewayTo]), where a stop line would stand; [junctionStart]: it begins on the road the
     * route turns onto at such a junction, at the junction's far side, and its asphalt begins there without a taper. The
     * inside of a street junction is the map's own road.
     */
    data class Drawing(
        val from: Double,
        val to: Double,
        val back: Double,
        val turnsAtEnd: Boolean,
        val junctionEnd: Boolean = false,
        val junctionStart: Boolean = false,
    ) {
        val asphaltFrom: Double get() = if (junctionStart) from else from - ASPHALT_TAPER_METERS
        val asphaltTo: Double get() = back + ASPHALT_TAPER_METERS

        /** Where its carriageway ends: at a street junction its near side, else where the drawing ends. */
        val carriagewayTo: Double get() = if (junctionEnd) to - LaneTopology.STOP_LINE_METERS else to
    }

    /** A move of the path from [from] to [to] (stations), from the offset [offsetFrom] to [offsetTo], of [kind]. */
    data class Move(val from: Double, val to: Double, val offsetFrom: Double, val offsetTo: Double, val kind: Kind) {
        enum class Kind {
            /** Into the drawing from the road's line where it begins. */
            ENTER,

            /** Between lanes, or onto the band's middle: an S per lane crossed. */
            LANE,

            /** Back to the road's line where the drawing ends. */
            RETURN,

            /** Straight on along the old road where the route turns, then blended onto the new road. */
            TURN,
        }
    }

    /**
     * A stretch from [from] to [to] where the path's offset would fold. [toLine]: the road turns there by more than
     * [FOLD_KEEP_DEGREES] and the path keeps to the road's line, blended in and out around it; else it keeps its offset and
     * turns where it would cross itself.
     */
    data class Fold(val from: Double, val to: Double, val toLine: Boolean) {
        val meters: Double get() = to - from
    }

    /**
     * A stretch from a ramp's start at [from] to [to] that the path follows on the road's line, having reached the turn on
     * it (no lane is named on the approach, C1): the route geometry's diagonal across the old road's lanes, which go on
     * straight there, until the ramp's lanes lie on the new road or the path moves into one of them. The drawing draws no
     * line and lights no lane there, so the diagonal crosses no line of the app's.
     */
    data class Diagonal(val from: Double, val to: Double)

    /**
     * Where the path crosses a junction the route reaches on a street and turns at ([Drawing.junctionEnd]), the junction at
     * the intersection [node]: from [from], the junction's near side, to [to], its far side ([JUNCTION_FAR_SIDE_METERS]
     * past the node), it runs along [points], each at the route station of [stations], ascending: a curve from [offsetFrom]
     * off the road's line at the near side to [offsetTo] off the new road's line at the far side, along the road's way at
     * both ends, never along the road's line, which turns in a corner at the node. Between [from] and [to] the path is these
     * points; [offsetAt] does not tell where it lies there.
     *
     * It ends in the middle of [receiving], the receiving road's lane, where the route turns at the junction from a lane the
     * plan names into a road drawn from the far side on: the nearest of its lanes open to every car, counted from the turn's
     * inside as the named lane is among the lanes the turn can be made from (k-th to k-th). Elsewhere it ends on the new
     * road's line, [receiving] null: where no lane is drawn past the junction no lane is claimed there. At a U-turn the curve
     * ends on the route's own line at the node, and from there the path is that line to the far side, turning back where the
     * route does: the route's line runs in the receiving carriageway's middle, and no lane is claimed past the junction. At a
     * roundabout there is no curve: nothing of the app's own lies inside one, and the path is the route's line there.
     */
    class Junction(
        val node: Int,
        val from: Double,
        val to: Double,
        val offsetFrom: Double,
        val offsetTo: Double,
        val points: List<GeoPoint>,
        val stations: DoubleArray,
        val receiving: Int? = null,
    )

    /** One drawing window: its axis and the path's offset at each of its samples. */
    internal class Window(val axis: RoadAxis, val stations: DoubleArray, val offsets: DoubleArray)

    /** How many metres of the route the path's offset would fold over. */
    val foldMeters: Double get() = folds.sumOf { it.meters }

    /** The path's offset from the road's line at [station], left positive: 0 away from the drawings. */
    fun offsetAt(station: Double): Double {
        val window = windowAt(station) ?: return 0.0
        val k = indexAtOrBefore(window.stations, station)
        if (k < 0) return window.offsets.first()
        if (k >= window.stations.size - 1) return window.offsets.last()
        val s0 = window.stations[k]
        val s1 = window.stations[k + 1]
        val f = if (s1 > s0) (station - s0) / (s1 - s0) else 0.0
        return window.offsets[k] + (window.offsets[k + 1] - window.offsets[k]) * f
    }

    /** The axis of the drawing window that holds [station]; null away from the drawings. */
    fun axisAt(station: Double): RoadAxis? = windowAt(station)?.axis

    /** How many drawing windows the path was built in. */
    val windowCount: Int get() = windows.size

    /** The axes of the drawing windows, in route order: the lanes are drawn along the same axes as the path. */
    internal val axes: List<RoadAxis> get() = windows.map { it.axis }

    private fun windowAt(station: Double): Window? {
        for (w in windows) if (station >= w.stations.first() && station <= w.stations.last()) return w
        return null
    }

    companion object {

        /** A move between lanes on a freeway or ramp takes this much driving at the planning speed, per lane crossed... */
        const val MOVE_SECONDS_FREEWAY = 6.0

        /** ...at least this far... */
        const val MOVE_MIN_FREEWAY_METERS = 80.0

        /** ...and at most this far. */
        const val MOVE_MAX_FREEWAY_METERS = 270.0

        /** On a street: this much driving per lane crossed... */
        const val MOVE_SECONDS_STREET = 4.0

        /** ...at least this far... */
        const val MOVE_MIN_STREET_METERS = 25.0

        /** ...and at most this far. */
        const val MOVE_MAX_STREET_METERS = 80.0

        /**
         * A move cut short to end at a ramp's start, where the plan names its lane late, still runs this far per lane
         * crossed: its S then never turns more than about 21 degrees from the road.
         */
        const val RAMP_MOVE_MIN_METERS = 15.0

        /** Where the drawing ends the path returns to the road's line over this distance, on asphalt without lane lines. */
        const val RETURN_METERS = 60.0

        /** Then the asphalt tapers over this distance into the map's own road (and the same before the drawing begins). */
        const val ASPHALT_TAPER_METERS = 40.0

        /**
         * The inside of a street junction reaches this far past its node, as far as its near side, where a stop line would
         * stand, lies before it ([LaneTopology.STOP_LINE_METERS]): the road the route turns onto is drawn from there.
         */
        const val JUNCTION_FAR_SIDE_METERS = LaneTopology.FAR_SIDE_METERS

        /**
         * A street junction's curve ([Junction]) leaves each side along the road's way, its handles reaching this share of the
         * way to where the two ways meet: a quarter circle's own share, so a right-angled turn is all but round.
         */
        const val JUNCTION_PULL = 0.55

        /** The maneuvers into, round or out of a roundabout: no curve of the app's own there. */
        private val ROUNDABOUT_TYPES = setOf(
            ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ROTARY, ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY,
        )

        /** The maneuvers into a roundabout: its way round begins there ([roundaboutWays]). */
        private val ROUNDABOUT_ENTRIES = setOf(ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ROTARY)

        /**
         * The ways round the roundabouts of [route], in route order, each from the intersection where the route enters one (a
         * maneuver into a roundabout) to [JUNCTION_FAR_SIDE_METERS] past the next intersection where the plan counts a maneuver
         * ([LaneEvidence.planTurns]): its way out ([ManeuverType.EXIT_ROUNDABOUT], [ManeuverType.EXIT_ROTARY]) or, where the
         * route gives none, the next maneuver. Nothing of the app's own lies on them, whatever lanes the route lists there.
         */
        fun roundaboutWays(route: LaneRoute): List<ClosedFloatingPointRange<Double>> {
            val nodes = route.nodes
            val out = ArrayList<ClosedFloatingPointRange<Double>>()
            for ((i, node) in nodes.withIndex()) {
                if (node.maneuver?.type !in ROUNDABOUT_ENTRIES) continue
                val wayOut = (i + 1 until nodes.size).firstOrNull { LaneEvidence.planTurns(nodes[it]) }?.let { nodes[it].station } ?: route.lengthMeters
                out.add(node.station..wayOut + JUNCTION_FAR_SIDE_METERS)
            }
            return out
        }

        /** A right turn's modifiers. */
        private val RIGHT_TURNS = setOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)


        /** The ways at a junction's two sides meet ahead of both only where they cross at more than this sine (about 6 degrees). */
        private const val JUNCTION_MEET_SINE = 0.1

        /** A junction's curve is drawn with a point about this often... */
        private const val JUNCTION_STEP_METERS = 1.0

        /** ...and with at least this many pieces. */
        private const val JUNCTION_MIN_POINTS = 8

        /** A drawing window is at most this long, one origin for its local metres. */
        const val WINDOW_METERS = 3_000.0

        /** The path's points leave out what lies within this of the line between their neighbours... */
        const val TOLERANCE_METERS = 0.05

        /** ...but keep every sample of this many metres before the path is back on the route's own line, so it joins it without a kink. */
        const val BACK_SAMPLED_METERS = 12.0

        /**
         * Where the path's offset would fold and the road turns there by more than this (a corner, a mapping kink), the path
         * keeps to the road's line around it; at a gentler bend, where the fold comes of a short arc between close vertices, it
         * keeps its offset.
         */
        const val FOLD_KEEP_DEGREES = 40.0

        /** Offsets that differ by less than this are the same: no move. */
        private const val SAME_OFFSET_METERS = 1e-6

        /** A turn shorter than this many axis samples is sampled this many times along it ([RoadAxis.SAMPLE_METERS]). */
        internal const val TURN_SAMPLES = 20

        /** Stations closer than this are one: where a lane ends, it is there just before and gone just after. */
        private const val SAME_STATION = 1e-6

        /** A fold is at a sharp bend where an arc turning by more than [FOLD_KEEP_DEGREES] lies within this of it. */
        private const val SHARP_REACH_METERS = 12.0

        /** How far a move between lanes runs per lane it crosses, on a road of [road] planned at [speedMps]. */
        fun moveMeters(speedMps: Double, road: RoadKind): Double {
            val v = if (speedMps.isFinite() && speedMps > 0.0) speedMps else 0.0
            return if (road == RoadKind.STREET) {
                (v * MOVE_SECONDS_STREET).coerceIn(MOVE_MIN_STREET_METERS, MOVE_MAX_STREET_METERS)
            } else {
                (v * MOVE_SECONDS_FREEWAY).coerceIn(MOVE_MIN_FREEWAY_METERS, MOVE_MAX_FREEWAY_METERS)
            }
        }

        /**
         * The path of [plan]'s route along [line], the route's own line (its steps' shapes one after the other, as the map
         * draws the route), whose stations are measured along it as the route's are. [evidence] is the plan's second pass
         * and [section] the cross-section built from them; [version] tells this path from earlier ones of the route.
         */
        fun of(
            plan: LanePlan,
            line: List<GeoPoint>,
            version: Int = 0,
            evidence: LaneEvidence = plan.evidence(),
            section: CrossSection = CrossSection.of(plan, evidence),
        ): RoutePath = Builder(plan, line, version, evidence, section).build()

        /**
         * How far a move by [jump] metres across a road of [road], [length] long, runs where a ramp's start lies [toStart]
         * metres ahead of where it begins: to the ramp's start, where only the lanes that go on onto the new road are drawn,
         * but at least [RAMP_MOVE_MIN_METERS] per lane crossed (at least one); [length] where it ends before.
         */
        fun lengthBeforeRampStart(length: Double, toStart: Double, jump: Double, road: RoadKind): Double {
            if (toStart >= length) return length
            val shortest = RAMP_MOVE_MIN_METERS * max(1.0, abs(jump) / LaneTopology.laneMeters(road))
            return min(length, max(toStart, shortest))
        }

        /**
         * A path that is [before]'s points (stations [beforeStations]) up to and at [at] and [after]'s (stations
         * [afterStations]) past it: the route line the map draws stays point for point up to [at], the end of the drawing
         * window on screen, where a path built anew with the electronic horizon's counts takes over (OL9). Both are paths of
         * one route, built from one plan, with their evidence the same up to beyond [at], so they lie on one another there to
         * a floating-point step. As the points and their stations.
         */
        fun spliced(
            before: List<GeoPoint>,
            beforeStations: DoubleArray,
            after: List<GeoPoint>,
            afterStations: DoubleArray,
            at: Double,
        ): Pair<List<GeoPoint>, DoubleArray> {
            require(before.size == beforeStations.size && after.size == afterStations.size) { "One station per point" }
            val points = ArrayList<GeoPoint>(max(before.size, after.size))
            val stations = ArrayList<Double>(max(before.size, after.size))
            for (i in before.indices) {
                if (beforeStations[i] > at) break
                points.add(before[i])
                stations.add(beforeStations[i])
            }
            for (i in after.indices) {
                // Past the cut, the new path as it is, a point repeated where two steps meet too.
                if (afterStations[i] <= at || (stations.isNotEmpty() && afterStations[i] < stations.last())) continue
                points.add(after[i])
                stations.add(afterStations[i])
            }
            return points to stations.toDoubleArray()
        }

        /** The index of the last of the ascending [values] at or before [value]; -1 before the first. */
        internal fun indexAtOrBefore(values: DoubleArray, value: Double): Int {
            var lo = 0
            var hi = values.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (values[mid] <= value) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }
    }

    /** What the path follows over a stretch: the road's line, a named lane, the band's middle, or a turn's straight-on line. */
    private sealed class Piece {
        object Middle : Piece()

        data class Named(val lane: Int) : Piece()

        /**
         * The middle of [band], moving only toward [ref] (where the plan's next lane lies at its full width, or the road's
         * line) and never past [begins] (where that lane begins, if it is still opening); without one, only toward the
         * [edge] side.
         */
        data class Band(val band: List<Int>, val ref: Double?, val begins: Double?, val edge: Int) : Piece()

        /** Straight on from [offset] at the turn at [turn] (its station), then onto the new road's line. */
        data class Turn(val turn: Double, val offset: Double) : Piece()

        /** Where the path was, [offset], held: where a return begins, or a band the next band takes over from. */
        data class Held(val offset: Double) : Piece()

        /**
         * Across a street junction, where the path is the junction's own curve ([Junction]): on the road's line for the solve,
         * which goes on past the far side from [offset], where the curve ends, in the receiving road's lane or on its line.
         */
        data class Across(val offset: Double) : Piece()
    }

    /** Where the plan heads from before [station] on the run [run]: the middle of [lane], or the road's line where it is null (C1). */
    private class Ref(val station: Double, val run: Int, val lane: Int?)

    /**
     * What the path follows, with the moves still under way: the piece it followed before them and each move after it, a
     * half-cosine blend from where the path would be without it toward its own piece, the newest toward the piece the path
     * follows now.
     * Every piece keeps its own value along the way, so where the lanes move together (at a ramp's start, in a zone) the path
     * moves with them, while a lane that is still opening draws the path in only as fast as the move goes. A move toward a
     * named lane that is the only one under way sets off only the way that lane lies from the path where the move ends: where
     * the lane is still opening on the other side, the path waits for it rather than setting off the wrong way and coming back.
     */
    private class Layer(var piece: Piece, var hold: Double, val from: Double, val to: Double, val way: Int, val waits: Boolean) {
        /** The value of [piece] at the station being solved. */
        var value = hold

        /** Where the path is with this layer, [below] being where it would be without it, at [station]. */
        fun blend(below: Double, station: Double): Double {
            var gap = value - below
            if (waits && gap * way <= 0.0) gap = 0.0
            return below + gap * CrossSection.ramp(station, from, to)
        }

        /** Whether its move is over at [station], [below] being where the path would be without it: never while it still waits. */
        fun over(below: Double, station: Double): Boolean = station >= to && !(waits && (value - below) * way < -SAME_OFFSET_METERS)
    }

    /** A stretch to draw: from where the lanes begin to where the path is back on the road's line, at most. */
    private class Region(val from: Double, var to: Double, val drawings: MutableList<Drawing>)

    private class Builder(
        val plan: LanePlan,
        val line: List<GeoPoint>,
        val version: Int,
        val evidence: LaneEvidence,
        val section: CrossSection,
    ) {
        private val topology: LaneTopology = plan.topology
        private val route = topology.evidence.route
        private val nodes = route.nodes
        private val lineStations = DoubleArray(line.size).also { s ->
            for (i in 1 until line.size) s[i] = s[i - 1] + Geo.meters(line[i - 1], line[i])
        }
        private val length = if (line.isEmpty()) 0.0 else lineStations.last()

        /**
         * Where the plan heads, in route order: the first lane each card window names, from where the plan first names it,
         * and the road's line on each approach where no lane is named (C1), from where the plan's stretches without one
         * begin. A window or an approach may begin between the plan's grid stations (the seam begins a run-up a few seconds
         * of driving before a narrowing), and the plan changes only at the first grid station inside it: a window's lane is
         * the first one any stretch inside it names, from where that stretch begins.
         */
        private val refs: List<Ref> = run {
            val lanes = plan.windows.mapNotNull { w ->
                val first = plan.segmentAt(w.from)?.takeIf { it.named != null }
                    ?: plan.segments.firstOrNull { it.named != null && it.from > w.from && it.from < w.to }
                first?.let {
                    val lane = requireNotNull(it.named)
                    Ref(max(w.from, it.from), topology.lanes[lane].run, lane)
                }
            }
            val lines = plan.noLane.mapNotNull { n ->
                plan.segments.firstOrNull { it.noLane != null && it.to > n.from && it.from < n.to }?.let { Ref(max(n.from, it.from), it.run, null) }
            }
            (lanes + lines).sortedBy { it.station }
        }

        private val moves = ArrayList<Move>()
        private val folds = ArrayList<Fold>()
        private val diagonals = ArrayList<Diagonal>()
        private val junctions = ArrayList<Junction>()

        fun build(): RoutePath {
            val routeId = route.routeId
            if (line.size < 2 || length <= 0.0) {
                return RoutePath(routeId, version, line, lineStations, line, lineStations, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
            val regions = regions()
            val windows = ArrayList<Window>()
            val drawings = ArrayList<Drawing>()
            val points = ArrayList<GeoPoint>()
            val stations = ArrayList<Double>()
            var next = 0 // the next point of the route's own line to copy
            for (region in regions) {
                val built = buildRegion(region) ?: continue
                windows.addAll(built.first)
                drawings.addAll(region.drawings)
                // The route's own line up to the region, then the region's points.
                while (next < line.size && lineStations[next] < region.from - 1e-9) {
                    points.add(line[next])
                    stations.add(lineStations[next])
                    next++
                }
                for ((p, s) in built.second) {
                    if (stations.isNotEmpty() && s <= stations.last() + 1e-9) continue
                    points.add(p)
                    stations.add(s)
                }
                while (next < line.size && lineStations[next] <= region.to + 1e-9) next++
            }
            while (next < line.size) {
                points.add(line[next])
                stations.add(lineStations[next])
                next++
            }
            return RoutePath(routeId, version, line, lineStations, points, stations.toDoubleArray(), drawings, moves, folds, diagonals, windows, junctions)
        }

        /** The stretches the app draws lanes on, merged, and the regions they need with their returns. */
        private fun regions(): List<Region> {
            // The drawn stretches of each run, merged where they touch: a run's lanes are one drawing, another run's another. A
            // street's stretches only where the cross-section draws its lanes, where a source tells how it sits.
            val drawn = ArrayList<Triple<Double, Double, Int>>()
            for (stretch in evidence.stretches) {
                if (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED && stretch.kind != LaneEvidence.Kind.ZONE) continue
                if (stretch.lanes <= 0) continue
                for (run in topology.runs) {
                    val from = max(stretch.from, run.from)
                    val to = min(min(stretch.to, run.to), length)
                    if (to <= from) continue
                    val parts = if (stretch.road == RoadKind.STREET) section.streetParts(from, to, run.index).map { it.start to it.endInclusive } else listOf(from to to)
                    for ((a, b) in parts) {
                        val k = drawn.indexOfLast { it.third == run.index }
                        val last = if (k >= 0) drawn[k] else null
                        if (last != null && a <= last.second + 1e-6) drawn[k] = Triple(last.first, max(last.second, b), run.index) else drawn.add(Triple(a, b, run.index))
                    }
                }
            }
            drawn.sortBy { it.first }
            val regions = ArrayList<Region>()
            for ((from, to, junctionEnd, junctionStart) in drawn.flatMap { (from, to, _) -> atJunctions(from, to) }) {
                val turns = to < length - 1e-6 && nodes.any { abs(it.station - to) < 1e-6 && LaneEvidence.planTurns(it) }
                val reach = if (turns) CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + 1.0 else RETURN_METERS
                val drawing = Drawing(from, to, min(length, to + reach), turns, junctionEnd, junctionStart)
                val end = min(length, to + reach)
                val last = regions.lastOrNull()
                if (last != null && from <= last.to) {
                    last.to = max(last.to, end)
                    last.drawings.add(drawing)
                } else {
                    regions.add(Region(from, end, mutableListOf(drawing)))
                }
            }
            return regions
        }

        /** A drawn stretch from [from] to [to], and whether it ends at a street junction's near side or begins at its far side. */
        private data class DrawnPart(val from: Double, val to: Double, val junctionEnd: Boolean, val junctionStart: Boolean)

        /** The ways round the route's roundabouts ([roundaboutWays]), where nothing of the app's own is drawn. */
        private val roundabouts = roundaboutWays(route)

        /**
         * The drawn stretch from [from] to [to] cut at every junction the route reaches on a street and turns at
         * ([LaneEvidence.planTurns]): the stretch before it ends at the junction, whose inside is the map's own road, and the
         * one after it begins [JUNCTION_FAR_SIDE_METERS] past the junction's node; nothing of it within that of the node. So too
         * where the route enters a roundabout on any road, and nothing of it from there to the far side of the way out
         * ([roundaboutWays]), whatever lanes the route lists on the way round: a stretch that begins on the way round begins past
         * it.
         */
        private fun atJunctions(from: Double, to: Double): List<DrawnPart> {
            val out = ArrayList<DrawnPart>()
            var start = from
            var startsAtJunction = false
            // A stretch that begins on a way round, or where one ends (the cross-section draws no street on it), begins at the
            // far side of its way out, square.
            for (way in roundabouts) {
                if (way.start <= start + 1e-6 && way.endInclusive >= start - 1e-6) {
                    start = max(start, way.endInclusive)
                    startsAtJunction = true
                }
            }
            for ((i, node) in nodes.withIndex()) {
                if (i == 0 || node.station < start - 1e-6 || node.station > to + 1e-6) continue
                if (!LaneEvidence.planTurns(node)) continue
                val way = roundabouts.firstOrNull { abs(it.start - node.station) < 1e-6 }
                if (way == null && route.approachRoad(i) != RoadKind.STREET) continue
                // A stretch whose carriageway would end before it begins, at the junction's near side, is none. A roundabout's
                // way in ends so whatever road reaches it.
                if (node.station - LaneTopology.STOP_LINE_METERS > start + 1e-6) out.add(DrawnPart(start, node.station, junctionEnd = true, junctionStart = startsAtJunction))
                start = max(start, way?.endInclusive ?: (node.station + JUNCTION_FAR_SIDE_METERS))
                startsAtJunction = true
            }
            if (to > start + 1e-6) out.add(DrawnPart(start, to, junctionEnd = false, junctionStart = startsAtJunction))
            return out
        }

        /** The region's windows and points, or null where nothing could be built. */
        private fun buildRegion(region: Region): Pair<List<Window>, List<Pair<GeoPoint, Double>>>? {
            val cuts = cuts(region)
            val axes = ArrayList<RoadAxis>()
            for (k in 0 until cuts.size - 1) axes.add(axisOf(cuts[k], cuts[k + 1]) ?: return null)
            // The samples of every window, with the events where the path's piece may change; then once more with every
            // move's end, so the path is exact where a move ends.
            val events = events(region)
            var windowSamples = samplesOf(axes, events)
            val movesBefore = moves.size
            val foldsBefore = folds.size
            val diagonalsBefore = diagonals.size
            solve(region, axes, windowSamples)
            val ends = moves.subList(movesBefore, moves.size).map { it.to }
            // A turn that settles within a few metres (a tight arc at its gore) is sampled at a [TURN_SAMPLES]th of its length,
            // so its chords meet the road's line without a kink.
            val turns = moves.subList(movesBefore, moves.size)
                .filter { it.kind == Move.Kind.TURN && abs(it.offsetFrom) > SAME_OFFSET_METERS && it.to - it.from < TURN_SAMPLES * RoadAxis.SAMPLE_METERS }
                .flatMap { m -> (1 until TURN_SAMPLES).map { m.from + (m.to - m.from) * it / TURN_SAMPLES } }
            while (moves.size > movesBefore) moves.removeAt(moves.size - 1)
            while (folds.size > foldsBefore) folds.removeAt(folds.size - 1)
            while (diagonals.size > diagonalsBefore) diagonals.removeAt(diagonals.size - 1)
            windowSamples = samplesOf(axes, events + ends + turns)
            val offsets = solve(region, axes, windowSamples)
            // Trim the end to where the path is back on the line, on a straight piece, and build the points.
            val windows = ArrayList<Window>()
            val out = ArrayList<Pair<GeoPoint, Double>>()
            val back = backAt(region, axes, windowSamples, offsets)
            region.to = back
            for ((k, axis) in axes.withIndex()) {
                val samples = windowSamples[k]
                val off = offsets[k]
                val keep = samples.indices.filter { samples[it] <= back + 1e-9 }
                if (keep.isEmpty()) continue
                val st = DoubleArray(keep.size) { samples[keep[it]] }
                val of = DoubleArray(keep.size) { off[keep[it]] }
                windows.add(Window(axis, st, of))
                // Where the offset folds at a bend it keeps, the path turns where it would cross itself: the place it crosses
                // itself, reached twice, kept as it is.
                val (local, at) = Loops.cut(st.indices.map { axis.offset(st[it], of[it]) }, st.asList(), { it }, { it })
                val twice = { i: Int -> (i > 0 && local[i] == local[i - 1]) || (i + 1 < local.size && local[i] == local[i + 1]) }
                // Where a turn's arc begins (a ramp's start, a drawing's turning end) the path's stations stop running with its
                // length: a point there keeps the straight-on chords on either side read by their stations where they lie.
                val arcs = (section.rampStarts.map { it.station }.filter { rs -> region.drawings.any { rs > it.from && rs < it.to } } +
                    region.drawings.filter { it.turnsAtEnd }.map { it.to })
                    .filter { it >= st.first() - CrossSection.RAMP_START_MAX_METERS && it <= st.last() + CrossSection.RAMP_START_MAX_METERS }
                    .map { CrossSection.beginOf(axis, it) }
                val arcStart = { i: Int -> arcs.any { abs(at[i] - it) < SAME_STATION } }
                // A street junction's two sides, where its curve takes over the path.
                val sides = region.drawings.filter { it.junctionEnd }.flatMap { listOf(it.carriagewayTo, it.to + JUNCTION_FAR_SIDE_METERS) }
                val side = { i: Int -> sides.any { abs(at[i] - it) < SAME_STATION } }
                // Every sample of the last metres before the path is back on the route's own line: one chord over them would
                // turn at the join.
                for (i in simplify(local) { at[it] >= back - BACK_SAMPLED_METERS || twice(it) || arcStart(it) || side(it) }) out.add(axis.toGeo(local[i]) to at[i])
            }
            // Across each street junction, its own curve in place of the samples.
            for (d in region.drawings) {
                if (!d.junctionEnd) continue
                val junction = junctionOf(region, d, axes, windowSamples, offsets, min(back, d.to + JUNCTION_FAR_SIDE_METERS)) ?: continue
                junctions.add(junction)
                out.removeAll { it.second > junction.from + SAME_STATION && it.second < junction.to - SAME_STATION }
                val at = out.indexOfFirst { it.second > junction.from + SAME_STATION }.let { if (it < 0) out.size else it }
                out.addAll(at, junction.points.indices.drop(1).dropLast(1).map { junction.points[it] to junction.stations[it] })
            }
            // Each drawing is back on the road's line where its return or its turn ends (at its end where nothing was left);
            // the region's last where the path is the route's own line again.
            for (i in region.drawings.indices) {
                val d = region.drawings[i]
                val end = if (i == region.drawings.size - 1) {
                    back
                } else if (d.junctionEnd) {
                    d.to + JUNCTION_FAR_SIDE_METERS
                } else {
                    moves.lastOrNull {
                        (it.kind == Move.Kind.RETURN && abs(it.from - d.to) < 1e-6) ||
                            (it.kind == Move.Kind.TURN && it.from <= d.to + 1e-6 && it.from >= d.to - CrossSection.RAMP_START_MAX_METERS)
                    }?.to ?: d.to
                }
                region.drawings[i] = d.copy(back = min(max(d.to, end), back))
            }
            return windows to out
        }

        /**
         * The curve across the street junction that ends the drawing [d], from its near side ([Drawing.carriagewayTo]) to [far]:
         * from where the path lies at the near side, along the road's way there, to where it lies at [far], along the new road's
         * way there; a cubic whose handles reach [JUNCTION_PULL] of the way to where the two ways would meet, or a third of the
         * way across where they do not meet ahead of both (a road that goes straight on, or turns back). Its points every metre
         * or so, at stations from the near side to [far] by their share of its length. Null where the junction lies off the
         * region's windows or its two sides do not lie apart.
         */
        private fun junctionOf(
            region: Region,
            d: Drawing,
            axes: List<RoadAxis>,
            samples: List<DoubleArray>,
            offsets: List<DoubleArray>,
            far: Double,
        ): Junction? {
            val near = d.carriagewayTo
            if (far <= near + SAME_STATION) return null
            val node = nodes.indexOfFirst { abs(it.station - d.to) < 1e-6 && LaneEvidence.planTurns(it) }
            // Nothing of the app's own inside a roundabout: the path is the route's line there.
            if (node >= 0 && nodes[node].maneuver?.type in ROUNDABOUT_TYPES) return null
            if (node >= 0 && nodes[node].maneuver?.modifier == TurnModifier.U_TURN) return uTurnOf(d, axes, samples, offsets, far, node)
            val a = axes.lastOrNull { near >= it.from - 1e-9 && near <= it.to + 1e-9 } ?: return null
            val b = axes.lastOrNull { far >= it.from - 1e-9 && far <= it.to + 1e-9 } ?: return null
            val t0 = offsetIn(axes, samples, offsets, near)
            val t1 = offsetIn(axes, samples, offsets, far)
            val p0 = a.offset(near, t0)
            val at0 = a.at(near)
            val p3 = a.toLocal(b.toGeo(b.offset(far, t1)))
            val at3 = b.at(far)
            val chord = p0.distanceTo(p3)
            if (chord < SAME_OFFSET_METERS) return null
            // Where the two ways meet: p0 + u t0 = p3 - v t3, for u and v ahead of both.
            val cross = at0.tx * at3.ty - at0.ty * at3.tx
            var u = chord / 3.0
            var v = chord / 3.0
            if (abs(cross) > JUNCTION_MEET_SINE) {
                val dx = p3.x - p0.x
                val dy = p3.y - p0.y
                val toMeet0 = (dx * at3.ty - dy * at3.tx) / cross
                val toMeet3 = (dx * at0.ty - dy * at0.tx) / cross
                if (toMeet0 > 0.0 && toMeet3 > 0.0) {
                    u = JUNCTION_PULL * toMeet0
                    v = JUNCTION_PULL * toMeet3
                }
            }
            val p1 = LocalPoint(p0.x + at0.tx * u, p0.y + at0.ty * u)
            val p2 = LocalPoint(p3.x - at3.tx * v, p3.y - at3.ty * v)
            val n = max(JUNCTION_MIN_POINTS, ceil(chord / JUNCTION_STEP_METERS).toInt())
            val local = (0..n).map { k ->
                val t = k.toDouble() / n
                val w = 1.0 - t
                LocalPoint(
                    w * w * w * p0.x + 3.0 * w * w * t * p1.x + 3.0 * w * t * t * p2.x + t * t * t * p3.x,
                    w * w * w * p0.y + 3.0 * w * w * t * p1.y + 3.0 * w * t * t * p2.y + t * t * t * p3.y,
                )
            }
            val lengths = DoubleArray(local.size)
            for (k in 1 until local.size) lengths[k] = lengths[k - 1] + local[k].distanceTo(local[k - 1])
            val total = lengths.last()
            val stations = DoubleArray(local.size) { k -> if (total > 0.0) near + (far - near) * lengths[k] / total else near }
            stations[local.size - 1] = far
            val receiving = receivingOf(region, d, b).second
            return Junction(node, near, far, t0, t1, local.map { a.toGeo(it) }, stations, receiving)
        }

        /**
         * The path across the street junction of a U-turn at the intersection [node], which ends the drawing [d]: from where the
         * path lies at the near side, along the road's way there, onto the route's own line at the node, along the road's way
         * where the turn's arc begins; a cubic whose handles reach a third of the way, its points every metre or so at stations
         * by their share of its length; then the route's own line from the node to [far], where it turns back as the route
         * does, on an undivided road at the node itself, across a median opening by its corners. So the line reaches the
         * junction and turns where the navigation SDK's U-turn arrow does, and claims no lane past it ([Junction.receiving]
         * null). Null where the junction lies off the region's windows or its near side at the node.
         */
        private fun uTurnOf(d: Drawing, axes: List<RoadAxis>, samples: List<DoubleArray>, offsets: List<DoubleArray>, far: Double, node: Int): Junction? {
            val near = d.carriagewayTo
            val at = nodes[node].station
            if (at <= near + SAME_STATION) return null
            val a = axes.lastOrNull { near >= it.from - 1e-9 && near <= it.to + 1e-9 } ?: return null
            val b = axes.lastOrNull { at >= it.from - 1e-9 && at <= it.to + 1e-9 } ?: return null
            val t0 = offsetIn(axes, samples, offsets, near)
            val p0 = a.offset(near, t0)
            val at0 = a.at(near)
            val p3 = a.toLocal(pointAt(at))
            // The road's way into the node: where the arc at the turn begins.
            val way = b.at(CrossSection.beginOf(b, at))
            val wx = way.tx
            val wy = way.ty
            val chord = p0.distanceTo(p3)
            if (chord < SAME_OFFSET_METERS) return null
            val u = chord / 3.0
            val p1 = LocalPoint(p0.x + at0.tx * u, p0.y + at0.ty * u)
            // The way in the frame of [a]: the axes' frames differ only by their origins.
            val p2 = LocalPoint(p3.x - wx * u, p3.y - wy * u)
            val n = max(JUNCTION_MIN_POINTS, ceil(chord / JUNCTION_STEP_METERS).toInt())
            val local = (0..n).map { k ->
                val t = k.toDouble() / n
                val w = 1.0 - t
                LocalPoint(
                    w * w * w * p0.x + 3.0 * w * w * t * p1.x + 3.0 * w * t * t * p2.x + t * t * t * p3.x,
                    w * w * w * p0.y + 3.0 * w * w * t * p1.y + 3.0 * w * t * t * p2.y + t * t * t * p3.y,
                )
            }
            val lengths = DoubleArray(local.size)
            for (k in 1 until local.size) lengths[k] = lengths[k - 1] + local[k].distanceTo(local[k - 1])
            val total = lengths.last()
            val points = ArrayList<GeoPoint>(local.size + 8)
            val stations = ArrayList<Double>(local.size + 8)
            for (k in local.indices) {
                points.add(a.toGeo(local[k]))
                stations.add(if (k == local.size - 1 || total <= 0.0) at else near + (at - near) * lengths[k] / total)
            }
            // From the node on, the route's own line, point for point, to the far side.
            for (i in line.indices) {
                if (lineStations[i] <= at + SAME_STATION || lineStations[i] >= far - SAME_STATION) continue
                points.add(line[i])
                stations.add(lineStations[i])
            }
            if (far > at + SAME_STATION) {
                points.add(pointAt(far))
                stations.add(far)
            }
            return Junction(node, near, far, t0, 0.0, points, stations.toDoubleArray(), null)
        }

        /** For each street junction a drawing ends at, by its node's station: where its curve ends, and in which lane. */
        private val receiving = HashMap<Double, Pair<Double, Int?>>()

        /**
         * Where the curve across the street junction that ends the drawing [d] of [region] ends at the junction's far side, along
         * [axis]: in the middle of the receiving road's lane ([Junction.receiving]) and that lane; on the new road's line and
         * null where no lane is named at the near side, the road past the junction is not drawn from its far side, at a U-turn
         * or at a roundabout.
         */
        private fun receivingOf(region: Region, d: Drawing, axis: RoadAxis): Pair<Double, Int?> = receiving.getOrPut(d.to) {
            val none = 0.0 to null
            val far = d.to + JUNCTION_FAR_SIDE_METERS
            val node = nodes.indexOfFirst { abs(it.station - d.to) < 1e-6 && LaneEvidence.planTurns(it) }
            val maneuver = nodes.getOrNull(node)?.maneuver ?: return@getOrPut none
            val modifier = maneuver.modifier
            if (modifier == TurnModifier.U_TURN || maneuver.type in ROUNDABOUT_TYPES) return@getOrPut none
            if (region.drawings.none { it.junctionStart && abs(it.from - far) < 1e-6 }) return@getOrPut none
            val named = plan.segmentAt(d.carriagewayTo - SAME_STATION)?.takeIf { it.inWindow && it.noLane == null }?.named ?: return@getOrPut none
            val lanes = section.lanes(far, axis).filter { it.width > SAME_OFFSET_METERS && !topology.lanes[it.lane].designated }
            if (lanes.isEmpty()) return@getOrPut none
            // The named lane's place among the lanes the turn can be made from, counted from the turn's inside.
            val slot = topology.lanes[named].slot
            val usable = topology.points.firstOrNull { it.node == node }?.allowed.orEmpty().filter { !topology.lanes[it].designated }
            // From the right for a right turn; from the left for a left turn, and straight across.
            val right = modifier in RIGHT_TURNS
            val k = if (right) usable.count { topology.lanes[it].slot > slot } else usable.count { topology.lanes[it].slot < slot }
            val lane = if (right) lanes[max(0, lanes.size - 1 - k)] else lanes[min(lanes.size - 1, k)]
            lane.centre to lane.lane
        }

        /** The path's offset at [station] as the solve left it, along whichever of [axes] holds it. */
        private fun offsetIn(axes: List<RoadAxis>, samples: List<DoubleArray>, offsets: List<DoubleArray>, station: Double): Double {
            for (k in axes.indices.reversed()) {
                val st = samples[k]
                if (st.isEmpty() || station < st.first() - 1e-9 || station > st.last() + 1e-9) continue
                val j = indexAtOrBefore(st, station + 1e-9).coerceIn(0, st.size - 1)
                if (abs(st[j] - station) < 1e-6 || j == st.size - 1) return offsets[k][j]
                val f = (station - st[j]) / (st[j + 1] - st[j])
                return offsets[k][j] + (offsets[k][j + 1] - offsets[k][j]) * f
            }
            return 0.0
        }

        /** Each window's samples: its axis' own, and [extra] stations that lie inside it. */
        private fun samplesOf(axes: List<RoadAxis>, extra: List<Double>): List<DoubleArray> = axes.map { axis ->
            val base = axis.stations()
            val inside = extra.filter { it > axis.from && it < axis.to }
            (base.toList() + inside).sorted().distinct().toDoubleArray()
        }

        /** Where the path is back on the road's line for good at the region's end: on a straight piece, not before the last drawing's end. */
        private fun backAt(region: Region, axes: List<RoadAxis>, samples: List<DoubleArray>, offsets: List<DoubleArray>): Double {
            var back = region.drawings.last().let { if (it.junctionEnd) min(length, it.to + JUNCTION_FAR_SIDE_METERS) else it.to }
            search@ for (k in axes.indices.reversed()) {
                val s = samples[k]
                val o = offsets[k]
                for (j in s.indices.reversed()) {
                    if (abs(o[j]) > SAME_OFFSET_METERS) {
                        back = max(back, s[min(s.size - 1, j + 1)])
                        break@search
                    }
                }
            }
            // Out of any arc, where the axis is the route's own line.
            val axis = axes.lastOrNull { back >= it.from - 1e-9 } ?: return region.to
            while (true) {
                val f = axis.fillets.firstOrNull { back > it.from + 1e-9 && back < it.to - 1e-9 } ?: break
                back = f.to
            }
            return min(back, region.to)
        }

        /** Where to cut the region into windows of at most [WINDOW_METERS]: halfway along a segment of the line, away from turns. */
        private fun cuts(region: Region): List<Double> {
            val len = region.to - region.from
            val n = max(1, ceil(len / WINDOW_METERS).toInt())
            val out = ArrayList<Double>()
            out.add(region.from)
            for (k in 1 until n) {
                val nominal = region.from + len * k / n
                val cut = safeCut(nominal, out.last())
                if (cut < region.to - 1.0) out.add(cut)
            }
            out.add(region.to)
            return out.distinct()
        }

        /** A cut near [nominal]: the middle of the line's segment there, moved past any turn's reach; never before [after]. */
        private fun safeCut(nominal: Double, after: Double): Double {
            // The middle of a segment of the line, the first one from [nominal] on whose middle lies past every turn's reach: a
            // segment's middle may lie up to half a segment back, inside the reach that moved the cut on.
            var i = indexAtOrBefore(lineStations, nominal).coerceIn(0, line.lastIndex - 1)
            repeat(line.size) {
                val s = ((lineStations[i] + lineStations[i + 1]) * 0.5).coerceAtLeast(after + 1.0)
                val turn = nodes.firstOrNull { LaneEvidence.planTurns(it) && s >= it.station - 60.0 && s <= it.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + 10.0 }
                if (turn == null || i >= line.lastIndex - 1) return s
                i = max(i + 1, indexAtOrBefore(lineStations, turn.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + 20.0).coerceIn(0, line.lastIndex - 1))
            }
            return ((lineStations[i] + lineStations[i + 1]) * 0.5).coerceAtLeast(after + 1.0)
        }

        /** The axis of the line from [from] to [to], in the frame of its first point. */
        private fun axisOf(from: Double, to: Double): RoadAxis? {
            val pts = ArrayList<GeoPoint>()
            val st = ArrayList<Double>()
            pts.add(pointAt(from))
            st.add(from)
            for (i in line.indices) {
                if (lineStations[i] > from + 1e-9 && lineStations[i] < to - 1e-9) {
                    pts.add(line[i])
                    st.add(lineStations[i])
                }
            }
            pts.add(pointAt(to))
            st.add(to)
            return try {
                RoadAxis.of(pts, st.toDoubleArray(), pts.first())
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** The point of the line at [station], between its points. */
        private fun pointAt(station: Double): GeoPoint {
            val i = indexAtOrBefore(lineStations, station).coerceIn(0, line.size - 1)
            if (i >= line.size - 1) return line.last()
            val s0 = lineStations[i]
            val s1 = lineStations[i + 1]
            val f = if (s1 > s0) ((station - s0) / (s1 - s0)).coerceIn(0.0, 1.0) else 0.0
            val a = line[i]
            val b = line[i + 1]
            return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
        }

        /** Stations where the path's piece may change: the plan's segments, the drawings' ends, the windows' starts, the turns' arcs. */
        private fun events(region: Region): List<Double> {
            val out = ArrayList<Double>()
            for (seg in plan.segments) {
                if (seg.from > region.from && seg.from < region.to) out.add(seg.from)
                if (seg.to > region.from && seg.to < region.to) out.add(seg.to)
            }
            for (d in region.drawings) {
                out.add(d.from)
                out.add(d.to)
                if (d.junctionEnd) {
                    out.add(d.carriagewayTo)
                    out.add(d.to + JUNCTION_FAR_SIDE_METERS)
                }
            }
            for (ref in refs) if (ref.station > region.from && ref.station < region.to) out.add(ref.station)
            return out
        }

        /** The path's offset at every sample of every window of [region]. */
        private fun solve(region: Region, axes: List<RoadAxis>, samples: List<DoubleArray>): List<DoubleArray> {
            val out = samples.map { DoubleArray(it.size) }
            // The piece the path followed before the moves under way, and one layer for each move; the last one's piece is the one
            // the path follows now.
            val layers = arrayListOf(Layer(Piece.Middle, 0.0, 0.0, 0.0, 0, false))
            val settled = HashSet<Double>()
            // The ramps' starts inside the region's drawings, the next one the path has not reached, and the one whose
            // diagonal it follows on the road's line, with where that diagonal ends at the latest.
            val starts = section.rampStarts.filter { rs -> region.drawings.any { rs.station > it.from && rs.station < it.to } }
            var nextStart = 0
            var diagonal: CrossSection.RampStart? = null
            var diagonalTo = 0.0
            var last = region.from
            for ((k, axis) in axes.withIndex()) {
                val s = samples[k]
                for (j in s.indices) {
                    val station = s[j]
                    if (k > 0 && j == 0 && station <= last) {
                        out[k][j] = out[k - 1].last()
                        continue
                    }
                    for (layer in layers) layer.value = valueOf(layer.piece, station, axis, layer.hold)
                    val piece = layers.last().piece
                    val hold = layers.last().hold
                    // A turn goes straight on from where the path is: its piece and the moves still under way.
                    val next = pieceAt(region, station, axis, piece, settled) { pathOf(layers, station) }
                    if (next is Piece.Across && piece !is Piece.Across) {
                        // Into a street junction: its curve takes over the moves under way, and the solve is on the road's line.
                        layers.clear()
                        layers.add(Layer(next, 0.0, 0.0, 0.0, 0, false))
                    } else if (next != piece) {
                        // Out of a street junction: on from where its curve ends.
                        if (piece is Piece.Across) {
                            layers.last().let {
                                it.piece = Piece.Held(piece.offset)
                                it.hold = piece.offset
                                it.value = piece.offset
                            }
                        }
                        // The old piece's value here; the band's middle never steps back behind it either. Between two bands,
                        // where the path was: a lane that ends at its edge with no taper takes the old band's middle with it.
                        val bands = piece is Piece.Band && next is Piece.Band
                        val old = if (bands) hold else layers.last().value
                        // A band the path leaves is held where it was: its middle steps where one of its lanes is no longer
                        // drawn (at a ramp's start, or where a lane leaves at the edge with no taper); so is a turn, which is over.
                        if (piece is Piece.Band || piece is Piece.Turn) {
                            layers.last().let {
                                it.piece = Piece.Held(old)
                                it.value = old
                            }
                        }
                        val value = valueOf(next, station, axis, if (next is Piece.Band) old else hold)
                        val drawn = pathOf(layers, station)
                        // Where a drawing ends, its turn or its return takes over the moves still under way, so none runs on
                        // past the drawing: the turn from where the path is, the return from there to the road's line.
                        val turns = next is Piece.Turn && piece !is Piece.Turn
                        val returns = next == Piece.Middle && region.drawings.any { abs(it.to - station) < 1e-6 && !it.turnsAtEnd }
                        val jump = value - if (turns || returns) drawn else old
                        if (turns || returns) {
                            layers.clear()
                            layers.add(Layer(if (turns) next else Piece.Held(drawn), if (turns) value else drawn, 0.0, 0.0, 0, false).also { it.value = it.hold })
                        }
                        // Into a named lane that is still opening the path moves even from where it is already: the lane's middle may
                        // swing either way while it opens, and the move waits for it to come the way it ends up. Not into a lane its
                        // neighbour feeds: the path follows its taper from the feeder's middle, with no S.
                        val opening = next is Piece.Named && topology.lanes[next.lane].fedBy == null &&
                            section.place(next.lane, station, axis)?.state == CrossSection.State.OPENING
                        if (!turns && (abs(jump) > SAME_OFFSET_METERS || opening && !returns)) {
                            val kind = when {
                                piece == Piece.Middle || piece is Piece.Turn || piece is Piece.Across -> Move.Kind.ENTER
                                returns -> Move.Kind.RETURN
                                else -> Move.Kind.LANE
                            }
                            val length = if (kind == Move.Kind.RETURN) {
                                RETURN_METERS
                            } else {
                                val full = beforeRampStart(region, station, jump, moveLength(station, jump))
                                beforeEdgeLeaves(region, station, axes, axis, next, drawn, value, full)
                            }
                            val end = station + length
                            // A move toward a named lane, the only one under way, sets off only the way that lane lies from the
                            // path where the move ends.
                            val waits = next is Piece.Named && layers.size == 1
                            val way = if (waits) {
                                val endAxis = axes.lastOrNull { it.from <= end } ?: axis
                                valueOf(next, end, endAxis, value) - valueOf(layers.last().piece, end, endAxis, layers.last().value)
                            } else {
                                0.0
                            }
                            layers.add(Layer(next, value, station, end, if (abs(way) <= SAME_OFFSET_METERS) 0 else if (way > 0.0) 1 else -1, waits).also { it.value = value })
                            moves.add(Move(station, end, drawn, value, kind))
                        } else if (!turns) {
                            // No move: the path follows the new piece from where the old one was.
                            layers.last().let {
                                it.piece = next
                                it.value = value
                            }
                        }
                        if (turns) {
                            val turn = next as Piece.Turn
                            moves.add(Move(station, continuationEnd(axis, turn), turn.offset, 0.0, Move.Kind.TURN))
                        }
                    }
                    for (layer in layers) layer.hold = layer.value
                    out[k][j] = pathOf(layers, station)
                    settle(layers, station)
                    // A ramp's start the path reaches on the road's line, with no move under way: its diagonal runs from the
                    // turn to where the path leaves the line, or the ramp's lanes lie on the new road.
                    val onLine = layers.size == 1 && layers[0].piece == Piece.Middle
                    diagonal?.let { rs ->
                        if (!onLine || station >= diagonalTo) {
                            diagonals.add(Diagonal(rs.station, if (onLine) diagonalTo else station))
                            diagonal = null
                        }
                    }
                    while (nextStart < starts.size && station >= starts[nextStart].station) {
                        val rs = starts[nextStart++]
                        val to = settledAt(rs, axis)
                        if (diagonal == null && onLine && station < to) {
                            diagonal = rs
                            diagonalTo = to
                        }
                    }
                    last = station
                }
            }
            diagonal?.let { diagonals.add(Diagonal(it.station, min(diagonalTo, last))) }
            foldsOf(axes, samples, out)
            return out
        }

        /** Where the path is at [station]: the first layer's value, blended toward each next one's. */
        private fun pathOf(layers: List<Layer>, station: Double): Double {
            var path = layers[0].value
            for (i in 1 until layers.size) path = layers[i].blend(path, station)
            return path
        }

        /** Lets go of the moves that are over at [station]: the last one over becomes what the path follows before the rest. */
        private fun settle(layers: ArrayList<Layer>, station: Double) {
            var below = layers[0].value
            var over = 0
            for (i in 1 until layers.size) {
                if (layers[i].over(below, station)) over = i
                below = layers[i].blend(below, station)
            }
            if (over > 0) {
                repeat(over) { layers.removeAt(0) }
                layers[0].let { it.hold = it.value }
            }
        }

        /** Where a turn's straight-on line has blended onto the new road's line. */
        private fun continuationEnd(axis: RoadAxis, turn: Piece.Turn): Double = max(turn.turn, CrossSection.turnOffEnd(axis, turn.turn, turn.offset))

        /**
         * The piece the path follows at [station]; a turn that begins there goes straight on from where the path is, [drawn].
         * A turn is over once the path has settled onto the new road's line: [settled] keeps the stations of those turns.
         */
        private fun pieceAt(region: Region, station: Double, axis: RoadAxis, current: Piece, settled: MutableSet<Double>, drawn: () -> Double): Piece {
            // Across a street junction: the junction's own curve, from its near side to its far side.
            for (d in region.drawings) {
                if (d.junctionEnd && station > d.carriagewayTo + SAME_STATION && station < d.to + JUNCTION_FAR_SIDE_METERS - SAME_STATION) {
                    return Piece.Across(receivingOf(region, d, axis).first)
                }
            }
            // At a drawing's end where the route turns: straight on from where the turn's arc begins, until the path is on the
            // new road's line, where a drawing that begins within the turn's reach takes over.
            for (d in region.drawings) {
                if (!d.turnsAtEnd || d.junctionEnd || d.to in settled) continue
                val begin = CrossSection.beginOf(axis, d.to)
                if (station >= begin && station <= d.back) {
                    if (current !is Piece.Turn || current.turn != d.to) return Piece.Turn(d.to, drawn())
                    if (station <= continuationEnd(axis, current)) return current
                    settled.add(d.to)
                }
            }
            val drawing = region.drawings.firstOrNull { station >= it.from && station < it.to } ?: return Piece.Middle
            val seg: PlanSegment = plan.segmentAt(station) ?: return Piece.Middle
            if (seg.noLane != null) return Piece.Middle
            // On the road's line through a ramp's start (past an approach where no lane is named): the lanes there still go on
            // straight along the old road, across the new road's line, so the path stays on that line until they lie on the
            // new road, and moves into its lane from there.
            if (current == Piece.Middle && onRampStart(station, axis)) return Piece.Middle
            val named = seg.named
            if (seg.inWindow && named != null) return Piece.Named(named)
            val edge = when (seg.edge) {
                LaneEdge.RIGHT -> -1
                LaneEdge.LEFT -> 1
                null -> 0
            }
            val next = refs.firstOrNull { it.station > station && it.station < drawing.to + 1e-6 && it.run == seg.run }
            val begins = next?.let { r -> if (r.lane == null) 0.0 else section.place(r.lane, r.station)?.centre }
            val ref = next?.let { r -> if (r.lane == null) 0.0 else section.place(r.lane, section.fullFrom(r.lane, r.station))?.centre }
            // Where no lane works (a conflict), the middle of the lanes drawn there: the path keeps to the drawn carriageway.
            val band = seg.band.ifEmpty { section.lanes(station, axis).filter { it.width > 0.0 }.map { it.lane } }
            return Piece.Band(band, ref ?: begins, begins ?: ref, edge)
        }

        /**
         * Whether [station] lies where a ramp's start keeps the lanes on their straight-on lines along [axis]: from the turn to
         * where they have blended onto the new road ([settledAt]).
         */
        private fun onRampStart(station: Double, axis: RoadAxis): Boolean = section.rampStarts.any {
            station >= it.station && station < it.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS && station < settledAt(it, axis)
        }

        /**
         * Where the lanes past the ramp's start [start] have settled onto the new road along [axis] ([CrossSection.RampStart.settled]),
         * no farther than the ramp's start reaches; and no earlier than [CrossSection.RAMP_BLEND_METERS] past the turn, so a path
         * on the road's line there keeps to it across the gore even where the lanes need not move (their middle on its place
         * already), and the gore stays bare as where it crosses them.
         */
        private fun settledAt(start: CrossSection.RampStart, axis: RoadAxis): Double =
            min(
                start.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS,
                max(start.station + CrossSection.RAMP_BLEND_METERS, start.settled(axis)),
            )

        /** What [piece] puts the path at, at [station], [hold] being where it was. */
        private fun valueOf(piece: Piece, station: Double, axis: RoadAxis, hold: Double): Double = when (piece) {
            Piece.Middle -> 0.0
            is Piece.Named -> namedCentre(piece.lane, station, axis) ?: hold
            is Piece.Band -> {
                val middle = section.middle(piece.band, station, axis) ?: hold
                toward(middle, hold, piece.ref, piece.begins, piece.edge)
            }
            is Piece.Turn -> CrossSection.turnOff(axis, piece.turn, piece.offset, station)
            is Piece.Held -> piece.offset
            is Piece.Across -> 0.0
        }

        /**
         * The named [lane]'s middle; into a lane its neighbour feeds, along its taper from the feeder's middle; into a turn
         * pocket drawn from where it may begin, along its late taper ([CrossSection.lateEntry]), whatever its drawn width.
         */
        private fun namedCentre(lane: Int, station: Double, axis: RoadAxis): Double? {
            val places = section.lanes(station, axis)
            val place = places.firstOrNull { it.lane == lane } ?: return null
            val feeder = topology.lanes[lane].fedBy?.let { f -> places.firstOrNull { it.lane == f } } ?: return place.centre
            val late = section.lateEntry(lane, station)
            if (late != null) return if (late >= 1.0) place.centre else feeder.centre * (1.0 - late) + place.centre * late
            val full = CrossSection.laneMeters(section.roadAt(station) ?: RoadKind.FREEWAY, topology.lanes[lane].kinds)
            if (place.width >= full - 1e-9) return place.centre
            val f = (place.width / full).coerceIn(0.0, 1.0)
            return feeder.centre * (1.0 - f) + place.centre * f
        }

        /**
         * [middle], moving from [hold] only toward [ref] and never past it, nor past [begins] where the lane the plan names
         * next begins while it opens (a lane opening around the path draws it in where it is); without a reference, only toward
         * the [edge] side.
         */
        private fun toward(middle: Double, hold: Double, ref: Double?, begins: Double?, edge: Int): Double = when {
            ref != null && ref >= hold -> min(max(middle, hold), max(hold, min(ref, begins ?: ref)))
            ref != null -> max(min(middle, hold), min(hold, max(ref, begins ?: ref)))
            edge == 0 -> middle
            (middle - hold) * edge >= 0.0 -> middle
            else -> hold
        }

        /** How far a move by [jump] metres across the road at [station] runs: per lane crossed, at least one. */
        private fun moveLength(station: Double, jump: Double): Double {
            val node = nodes.lastOrNull { it.station <= station + 1e-9 } ?: nodes.firstOrNull()
            val road = section.roadAt(station) ?: node?.road ?: RoadKind.FREEWAY
            val speed = node?.let { PlanningSpeed.of(it) } ?: PlanningSpeed.mps(null, road)
            val lanes = abs(jump) / LaneTopology.laneMeters(road)
            return moveMeters(speed, road) * max(1.0, lanes)
        }

        /**
         * How far a move by [jump] from [station] of [length] runs where a ramp's start inside a drawing of [region] (the
         * route turns onto a road whose lanes go on) lies ahead of it: only those lanes are drawn past the turn, so the
         * move ends there ([lengthBeforeRampStart]).
         */
        private fun beforeRampStart(region: Region, station: Double, jump: Double, length: Double): Double {
            val start = section.rampStarts.firstOrNull { rs ->
                rs.station > station + 1e-6 && rs.station < station + length &&
                    region.drawings.any { rs.station > it.from && rs.station < it.to }
            } ?: return length
            return lengthBeforeRampStart(length, start.station - station, jump, section.roadAt(station) ?: RoadKind.FREEWAY)
        }

        /**
         * How far a move from [drawn] toward [next] (worth [value] where it begins), from [station] and [length] long, runs where a
         * lane at the drawn carriageway's edge ends there with no taper (it leaves by a road at that edge) before the move would
         * have the path half a lane inside the edge that is left: to where that lane ends, but at least [RAMP_MOVE_MIN_METERS]
         * per lane crossed ([lengthBeforeRampStart]), so the path and the lane lit about it do not lie off the asphalt there.
         */
        private fun beforeEdgeLeaves(
            region: Region,
            station: Double,
            axes: List<RoadAxis>,
            axis: RoadAxis,
            next: Piece,
            drawn: Double,
            value: Double,
            length: Double,
        ): Double {
            val road = section.roadAt(station) ?: RoadKind.FREEWAY
            val half = LaneTopology.laneMeters(road) / 2.0
            for (lane in topology.lanes.sortedBy { it.closes }) {
                val at = lane.closes
                if (at <= station + SAME_STATION || at >= station + length) continue
                if (region.drawings.none { at > it.from && at < it.to }) continue
                val there = axes.lastOrNull { it.from <= at } ?: axis
                val before = section.lanes(at - SAME_STATION, there).filter { it.width > SAME_OFFSET_METERS }
                val after = section.lanes(at + SAME_STATION, there).filter { it.width > SAME_OFFSET_METERS }
                if (after.isEmpty() || after.any { it.lane == lane.id }) continue
                // At the edge of the lanes that are there: a lane only possible beyond it is not there yet.
                val present = before.filter { it.state != CrossSection.State.POSSIBLE }
                val gone = present.indexOfFirst { it.lane == lane.id }
                if (gone != 0 && gone != present.size - 1) continue
                // Where the move would have the path when the lane ends.
                val target = valueOf(next, at, there, value)
                val path = drawn + (target - drawn) * CrossSection.ramp(at, station, station + length)
                if (path + half <= after.first().left + TOLERANCE_METERS && path - half >= after.last().right - TOLERANCE_METERS) continue
                return lengthBeforeRampStart(length, at - station, value - drawn, road)
            }
            return length
        }

        /**
         * Where the offsets fold: at a bend of more than [FOLD_KEEP_DEGREES] the path keeps to the road's line, blended in and out
         * over [RETURN_METERS]; elsewhere it keeps its offset (its points are cut where they would cross themselves).
         */
        private fun foldsOf(axes: List<RoadAxis>, samples: List<DoubleArray>, offsets: List<DoubleArray>) {
            val stretches = ArrayList<Pair<Double, Double>>()
            for ((k, axis) in axes.withIndex()) {
                val s = samples[k]
                val o = offsets[k]
                var start = -1.0
                for (j in s.indices) {
                    val fold = axis.folds(s[j], o[j])
                    if (fold && start < 0.0) start = s[j]
                    if (!fold && start >= 0.0) {
                        stretches.add(start to s[j])
                        start = -1.0
                    }
                }
                if (start >= 0.0) stretches.add(start to s.last())
            }
            if (stretches.isEmpty()) return
            val sharp = stretches.filter { (from, to) ->
                axes.any { axis -> axis.fillets.any { it.to >= from - SHARP_REACH_METERS && it.from <= to + SHARP_REACH_METERS && it.turnDegrees > FOLD_KEEP_DEGREES } }
            }
            for ((from, to) in stretches) folds.add(Fold(from, to, (from to to) in sharp))
            if (sharp.isEmpty()) return
            for ((k, s) in samples.withIndex()) {
                val o = offsets[k]
                for (j in s.indices) {
                    var keep = 1.0
                    for ((from, to) in sharp) {
                        val w = when {
                            s[j] < from - RETURN_METERS || s[j] > to + RETURN_METERS -> 1.0
                            s[j] in from..to -> 0.0
                            s[j] < from -> 1.0 - CrossSection.ramp(s[j], from - RETURN_METERS, from)
                            else -> CrossSection.ramp(s[j], to, to + RETURN_METERS)
                        }
                        keep = min(keep, w)
                    }
                    o[j] *= keep
                }
            }
        }

        /**
         * The indices of [points] to keep: the ends, those [fixed] says, and every point that lies farther than
         * [TOLERANCE_METERS] from its simplified line.
         */
        private fun simplify(points: List<LocalPoint>, fixed: (Int) -> Boolean): List<Int> {
            if (points.size <= 2) return points.indices.toList()
            val keep = BooleanArray(points.size) { it == 0 || it == points.size - 1 || fixed(it) }
            val stack = ArrayDeque<Pair<Int, Int>>()
            var from = 0
            for (i in 1 until points.size) {
                if (!keep[i]) continue
                stack.addLast(from to i)
                from = i
            }
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a < 2) continue
                var worst = -1.0
                var at = -1
                for (i in a + 1 until b) {
                    val d = distanceToSegment(points[i], points[a], points[b])
                    if (d > worst) {
                        worst = d
                        at = i
                    }
                }
                if (worst > TOLERANCE_METERS) {
                    keep[at] = true
                    stack.addLast(a to at)
                    stack.addLast(at to b)
                }
            }
            return points.indices.filter { keep[it] }
        }

        private fun distanceToSegment(p: LocalPoint, a: LocalPoint, b: LocalPoint): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 <= 0.0) return p.distanceTo(a)
            val f = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            return p.distanceTo(LocalPoint(a.x + dx * f, a.y + dy * f))
        }
    }
}
