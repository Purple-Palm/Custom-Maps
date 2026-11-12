package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanSegment
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
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
 *   inside card windows, through zones too. On a C1 approach, where the plan names no lane, on the road's line. The
 *   app draws lanes on freeways and ramps where the evidence has them (known, bridged or a zone); streets wait for a
 *   one-way or two-way source.
 * - **Moves.** Inside drawn lanes and zones the path moves only toward the lane the plan heads for, the first named
 *   lane of the card window ahead, and never past it: the first move may go either way, then never back. A move
 *   between lanes is a half-cosine S of [moveMeters] per lane crossed (clamp(v x [MOVE_SECONDS_FREEWAY], 80, 270 m) on
 *   freeways, clamp(v x [MOVE_SECONDS_STREET], 25, 80 m) on streets, at the planning speed), starting at the plan's
 *   station, ahead of the car. Into a lane its neighbour feeds (a deceleration lane) it moves along that lane's own
 *   taper, with no S.
 * - **Turns.** Where the route turns onto another road with lanes, the path goes on with its lane straight along the
 *   old road until the new road's line lies farther out, then blends onto it ([CrossSection.RampStart]): the route
 *   geometry's diagonal at a gore is never drawn. Where the drawing ends at such a turn, the path goes on straight in
 *   its lane the same way and blends onto the new road's line.
 * - **The drawing's ends.** The path returns to the road's line only where the drawing ends, over [RETURN_METERS] on
 *   asphalt without lane lines (the asphalt then tapers over [ASPHALT_TAPER_METERS]); a drawing ends only outside
 *   card windows (inside them gaps are zones). At every end the path meets the route's own line, so the line the
 *   app draws is the navigation SDK's away from lanes.
 * - **Folds.** Where the path's offset would fold over itself on a curve too sharp for it (1 - kappa t <= 0, [RoadAxis]),
 *   it goes to the road's line there, over [RETURN_METERS] on each side ([folds]).
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
    /** The stretches where the path's offset would fold, and it keeps to the road's line. */
    val folds: List<Fold>,
    private val windows: List<Window>,
) {

    /**
     * A stretch from [from] to [to] where the app draws lanes; the path leaves the road's line at [from] and is back
     * on it at [back]; the drawing's asphalt runs from [asphaltFrom] to [asphaltTo]. [turnsAtEnd]: it ends where the
     * route turns onto a road without lanes, and the path goes on straight in its lane and blends onto that road.
     */
    data class Drawing(val from: Double, val to: Double, val back: Double, val turnsAtEnd: Boolean) {
        val asphaltFrom: Double get() = from - ASPHALT_TAPER_METERS
        val asphaltTo: Double get() = back + ASPHALT_TAPER_METERS
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

    /** A stretch from [from] to [to] where the path would fold and keeps to the road's line, blended in and out around it. */
    data class Fold(val from: Double, val to: Double) {
        val meters: Double get() = to - from
    }

    /** One drawing window: its axis and the path's offset at each of its samples. */
    internal class Window(val axis: RoadAxis, val stations: DoubleArray, val offsets: DoubleArray)

    /** How many metres of the route the path keeps to the road's line because its offset would fold there. */
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

        /** Where the drawing ends the path returns to the road's line over this distance, on asphalt without lane lines. */
        const val RETURN_METERS = 60.0

        /** Then the asphalt tapers over this distance into the map's own road (and the same before the drawing begins). */
        const val ASPHALT_TAPER_METERS = 40.0

        /** A drawing window is at most this long, one origin for its local metres. */
        const val WINDOW_METERS = 3_000.0

        /** The path's points leave out what lies within this of the line between their neighbours. */
        const val TOLERANCE_METERS = 0.05

        /** Offsets that differ by less than this are the same: no move. */
        private const val SAME_OFFSET_METERS = 1e-6

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

        /** The middle of [band], moving only toward [ref] (the plan's next lane) or, without one, toward the [edge] side. */
        data class Band(val band: List<Int>, val ref: Double?, val edge: Int) : Piece()

        /** Straight on from [offset] at the turn at [turn] (its station), then onto the new road's line. */
        data class Turn(val turn: Double, val offset: Double) : Piece()
    }

    /** A move in progress: [offset] left over at [from], gone by [to]. */
    private class Residual(val from: Double, val to: Double, val offset: Double) {
        fun at(station: Double): Double = offset * (1.0 - CrossSection.ramp(station, from, to))
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

        /** The first named lane of each card window, with where the window begins, in route order. */
        private val refs: List<Pair<Double, Int>> = plan.windows.mapNotNull { w ->
            plan.segmentAt(w.from)?.named?.let { w.from to it }
        }

        private val moves = ArrayList<Move>()
        private val folds = ArrayList<Fold>()

        fun build(): RoutePath {
            val routeId = route.routeId
            if (line.size < 2 || length <= 0.0) {
                return RoutePath(routeId, version, line, lineStations, line, lineStations, emptyList(), emptyList(), emptyList(), emptyList())
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
            return RoutePath(routeId, version, line, lineStations, points, stations.toDoubleArray(), drawings, moves, folds, windows)
        }

        /** The stretches the app draws lanes on, merged, and the regions they need with their returns. */
        private fun regions(): List<Region> {
            // The drawn stretches of each run, merged where they touch: a run's lanes are one drawing, another run's another.
            val drawn = ArrayList<Triple<Double, Double, Int>>()
            for (stretch in evidence.stretches) {
                if (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED && stretch.kind != LaneEvidence.Kind.ZONE) continue
                if (stretch.road == RoadKind.STREET || stretch.lanes <= 0) continue
                for (run in topology.runs) {
                    val from = max(stretch.from, run.from)
                    val to = min(min(stretch.to, run.to), length)
                    if (to <= from) continue
                    val k = drawn.indexOfLast { it.third == run.index }
                    val last = if (k >= 0) drawn[k] else null
                    if (last != null && from <= last.second + 1e-6) drawn[k] = Triple(last.first, max(last.second, to), run.index) else drawn.add(Triple(from, to, run.index))
                }
            }
            drawn.sortBy { it.first }
            val regions = ArrayList<Region>()
            for ((from, to, _) in drawn) {
                val turns = to < length - 1e-6 && nodes.any { abs(it.station - to) < 1e-6 && LaneEvidence.planTurns(it) }
                val reach = if (turns) CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + 1.0 else RETURN_METERS
                val drawing = Drawing(from, to, min(length, to + reach), turns)
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
            solve(region, axes, windowSamples)
            val ends = moves.subList(movesBefore, moves.size).map { it.to }
            while (moves.size > movesBefore) moves.removeAt(moves.size - 1)
            while (folds.size > foldsBefore) folds.removeAt(folds.size - 1)
            windowSamples = samplesOf(axes, events + ends)
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
                val local = st.indices.map { axis.offset(st[it], of[it]) }
                for (i in simplify(local)) out.add(axis.toGeo(local[i]) to st[i])
            }
            // Each drawing is back on the road's line where its return or its turn ends (at its end where nothing was left);
            // the region's last where the path is the route's own line again.
            for (i in region.drawings.indices) {
                val d = region.drawings[i]
                val end = if (i == region.drawings.size - 1) {
                    back
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

        /** Each window's samples: its axis' own, and [extra] stations that lie inside it. */
        private fun samplesOf(axes: List<RoadAxis>, extra: List<Double>): List<DoubleArray> = axes.map { axis ->
            val base = axis.stations()
            val inside = extra.filter { it > axis.from && it < axis.to }
            (base.toList() + inside).sorted().distinct().toDoubleArray()
        }

        /** Where the path is back on the road's line for good at the region's end: on a straight piece, not before the last drawing's end. */
        private fun backAt(region: Region, axes: List<RoadAxis>, samples: List<DoubleArray>, offsets: List<DoubleArray>): Double {
            var back = region.drawings.last().to
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
                out.add(safeCut(nominal, out.last()))
            }
            out.add(region.to)
            return out.distinct()
        }

        /** A cut near [nominal]: the middle of the line's segment there, moved past any turn's reach; never before [after]. */
        private fun safeCut(nominal: Double, after: Double): Double {
            var s = nominal
            repeat(20) {
                val turn = nodes.firstOrNull { LaneEvidence.planTurns(it) && s >= it.station - 60.0 && s <= it.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + 10.0 }
                if (turn == null) return midOfSegment(s).coerceAtLeast(after + 1.0)
                s = turn.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + 20.0
            }
            return midOfSegment(s).coerceAtLeast(after + 1.0)
        }

        private fun midOfSegment(s: Double): Double {
            val i = indexAtOrBefore(lineStations, s).coerceIn(0, line.size - 2)
            return (lineStations[i] + lineStations[i + 1]) / 2.0
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
            }
            for ((from, _) in refs) if (from > region.from && from < region.to) out.add(from)
            return out
        }

        /** The path's offset at every sample of every window of [region]. */
        private fun solve(region: Region, axes: List<RoadAxis>, samples: List<DoubleArray>): List<DoubleArray> {
            val out = samples.map { DoubleArray(it.size) }
            val residuals = ArrayList<Residual>()
            var hold = 0.0
            var piece: Piece = Piece.Middle
            var last = region.from
            for ((k, axis) in axes.withIndex()) {
                val s = samples[k]
                for (j in s.indices) {
                    val station = s[j]
                    if (k > 0 && j == 0 && station <= last) {
                        out[k][j] = out[k - 1].last()
                        continue
                    }
                    val next = pieceAt(region, station, axis, hold, piece)
                    // At a change, the old piece's value here; the band's middle never steps back behind it either.
                    val old = if (next != piece) valueOf(piece, station, axis, hold) else 0.0
                    val value = valueOf(next, station, axis, if (next != piece && next is Piece.Band) old else hold)
                    if (next != piece) {
                        val jump = value - old
                        if (abs(jump) > SAME_OFFSET_METERS) {
                            val kind = when {
                                piece == Piece.Middle && next !is Piece.Turn -> Move.Kind.ENTER
                                next == Piece.Middle && region.drawings.any { abs(it.to - station) < 1e-6 && !it.turnsAtEnd } -> Move.Kind.RETURN
                                else -> Move.Kind.LANE
                            }
                            val length = if (kind == Move.Kind.RETURN) RETURN_METERS else moveLength(station, jump)
                            val before = old + residuals.sumOf { it.at(station) }
                            residuals.add(Residual(station, station + length, -jump))
                            moves.add(Move(station, station + length, before, value, kind))
                        }
                        if (next is Piece.Turn && piece !is Piece.Turn) {
                            val end = continuationEnd(axis, next)
                            moves.add(Move(station, end, next.offset, 0.0, Move.Kind.TURN))
                        }
                    }
                    piece = next
                    hold = value
                    residuals.removeAll { it.to <= station }
                    out[k][j] = value + residuals.sumOf { it.at(station) }
                    last = station
                }
            }
            foldsOf(axes, samples, out)
            return out
        }

        /** Where a turn's straight-on line has blended onto the new road's line. */
        private fun continuationEnd(axis: RoadAxis, turn: Piece.Turn): Double {
            val begin = CrossSection.beginOf(axis, turn.turn)
            return max(turn.turn, CrossSection.crossing(axis, begin, turn.turn, turn.offset)) + CrossSection.RAMP_BLEND_METERS
        }

        /** The piece the path follows at [station]. */
        private fun pieceAt(region: Region, station: Double, axis: RoadAxis, hold: Double, current: Piece): Piece {
            // At a drawing's end where the route turns: straight on from where the turn's arc begins.
            for (d in region.drawings) {
                if (!d.turnsAtEnd) continue
                val begin = CrossSection.beginOf(axis, d.to)
                if (station >= begin && station <= d.back) {
                    return if (current is Piece.Turn && current.turn == d.to) current else Piece.Turn(d.to, hold)
                }
            }
            val drawing = region.drawings.firstOrNull { station >= it.from && station < it.to } ?: return Piece.Middle
            val seg: PlanSegment = plan.segmentAt(station) ?: return Piece.Middle
            if (seg.noLane != null) return Piece.Middle
            val named = seg.named
            if (seg.inWindow && named != null) return Piece.Named(named)
            val edge = when (seg.edge) {
                LaneEdge.RIGHT -> -1
                LaneEdge.LEFT -> 1
                null -> 0
            }
            val ref = refs.firstOrNull { it.first > station && it.first < drawing.to + 1e-6 && topology.lanes[it.second].run == seg.run }
                ?.let { (from, lane) -> section.place(lane, from)?.centre }
            return Piece.Band(seg.band, ref, edge)
        }

        /** What [piece] puts the path at, at [station], [hold] being where it was. */
        private fun valueOf(piece: Piece, station: Double, axis: RoadAxis, hold: Double): Double = when (piece) {
            Piece.Middle -> 0.0
            is Piece.Named -> namedCentre(piece.lane, station, axis) ?: hold
            is Piece.Band -> {
                val middle = section.middle(piece.band, station, axis) ?: hold
                toward(middle, hold, piece.ref, piece.edge)
            }
            is Piece.Turn -> CrossSection.continuation(axis, piece.turn, piece.offset, station)
        }

        /** The named [lane]'s middle; into a lane its neighbour feeds, along its taper from the feeder's middle. */
        private fun namedCentre(lane: Int, station: Double, axis: RoadAxis): Double? {
            val places = section.lanes(station, axis)
            val place = places.firstOrNull { it.lane == lane } ?: return null
            val feeder = topology.lanes[lane].fedBy?.let { f -> places.firstOrNull { it.lane == f } }
            val full = CrossSection.laneMeters(section.roadAt(station) ?: RoadKind.FREEWAY, topology.lanes[lane].kinds)
            if (feeder == null || place.width >= full - 1e-9) return place.centre
            val f = (place.width / full).coerceIn(0.0, 1.0)
            return feeder.centre * (1.0 - f) + place.centre * f
        }

        /** [middle], moving from [hold] only toward [ref] and never past it; without a reference, only toward the [edge] side. */
        private fun toward(middle: Double, hold: Double, ref: Double?, edge: Int): Double = when {
            ref != null && ref >= hold -> min(max(middle, hold), ref)
            ref != null -> max(min(middle, hold), ref)
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

        /** Where the offsets fold, the path keeps to the road's line, blended in and out over [RETURN_METERS]. */
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
            for ((from, to) in stretches) folds.add(Fold(from, to))
            for ((k, s) in samples.withIndex()) {
                val o = offsets[k]
                for (j in s.indices) {
                    var keep = 1.0
                    for ((from, to) in stretches) {
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

        /** The indices of [points] to keep: the ends, and every point that lies farther than [TOLERANCE_METERS] from its simplified line. */
        private fun simplify(points: List<LocalPoint>): List<Int> {
            if (points.size <= 2) return points.indices.toList()
            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to points.size - 1)
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
