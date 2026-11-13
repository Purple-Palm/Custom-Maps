package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The car over the app's drawn lanes (the own lanes' design 5.4 and D13; T2, E4): in a close view where the lit lane has taken
 * the route line's place, the car is a bar across the carriageway at its place along the route, from edge line to edge line of
 * the lanes the drawing lays there ([LaneDrawing], the plan's cross-section), [DEPTH_METERS] deep and square to those lanes. On
 * a two-way street it spans the route's own lanes only, from the road's line ([CrossSection.Carriageway.TWO_WAY]), and its
 * tick stands on the outer side alone ([twoWay]). It says where the car is along the road and nothing about its lane.
 *
 * It is placed by route station, never at the matched point across the road: the puck's position, which the navigator
 * matches to the route's own line, gives only the puck's station on that line ([place]), and the bar stands there on the drawn
 * lanes. So at a gore, where the route's line runs its diagonal across the old road's lanes onto the ramp, the bar stays on
 * the lanes that go on, which keep straight on before they settle onto the ramp: nothing moves it sideways with the line.
 *
 * Built off the main thread for one window of a drawing ([of]): the bar every [STEP_METERS] of route station where the drawing
 * lays lanes, and the route's own line in the window. Per frame, [place] finds the puck's station on that line near the one it
 * found last and interpolates the bar there into its own fields, allocating nothing. The map makes the bar's image again only
 * where its [width] has changed by [WIDTH_STEP_METERS] or more ([widthChanged]). Pure; never stored.
 */
class CarBar private constructor(
    private val stations: DoubleArray,
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val bearings: DoubleArray,
    private val widths: DoubleArray,
    private val twoWays: BooleanArray,
    private val lineLatitudes: DoubleArray,
    private val lineLongitudes: DoubleArray,
    private val lineStations: DoubleArray,
) {

    /** The route station of the bar at the last [place] or [at]; not a number where none was found. */
    var station = Double.NaN
        private set

    /** Where the bar's middle lies; not a number where none was found. */
    var latitude = Double.NaN
        private set
    var longitude = Double.NaN
        private set

    /** The way the drawn lanes run there, which the bar lies square to: degrees clockwise from north, from 0 up to 360. */
    var bearing = Double.NaN
        private set

    /** From edge line to edge line, square to the lanes, in metres. */
    var width = Double.NaN
        private set

    /** The route's half of a two-way street: its tick stands on the outer side alone. */
    var twoWay = false
        private set

    /** Where the puck was found on the route's line last; not a number before the first, and after a puck off the line. */
    private var lastStation = Double.NaN

    /** The first route station the bar covers; not a number where it covers none. */
    val from: Double get() = if (stations.isEmpty()) Double.NaN else stations.first()

    /** The last route station it covers. */
    val to: Double get() = if (stations.isEmpty()) Double.NaN else stations.last()

    /**
     * The bar for a puck drawn at [latitude], [longitude]: the puck's station on the route's own line, near where it was found
     * last ([NEAR_BACK_METERS] behind to [NEAR_AHEAD_METERS] ahead, else anywhere in the window), within [MAX_OFF_LINE_METERS]
     * of it, and the bar there ([at]). False where the puck is off the line, outside the window or where no lane is drawn.
     */
    fun place(latitude: Double, longitude: Double): Boolean {
        val found = stationOf(latitude, longitude)
        return if (found.isNaN()) none() else at(found)
    }

    /** The bar at route [station], between the two places of the table around it; false where no lane is drawn there. */
    fun at(station: Double): Boolean {
        val n = stations.size
        if (n == 0 || station.isNaN() || station < stations[0] || station > stations[n - 1]) return none()
        val k = RoutePath.indexAtOrBefore(stations, station)
        if (k == n - 1 || stations[k] == station) return row(k, station)
        val s0 = stations[k]
        val s1 = stations[k + 1]
        // Between two drawn stretches nothing is drawn: the gap holds no bar.
        if (s1 - s0 > GAP_METERS) return none()
        val f = (station - s0) / (s1 - s0)
        this.station = station
        latitude = latitudes[k] + (latitudes[k + 1] - latitudes[k]) * f
        longitude = longitudes[k] + (longitudes[k + 1] - longitudes[k]) * f
        bearing = turnTowards(bearings[k], bearings[k + 1], f)
        width = widths[k] + (widths[k + 1] - widths[k]) * f
        twoWay = if (f < 0.5) twoWays[k] else twoWays[k + 1]
        return true
    }

    private fun row(k: Int, station: Double): Boolean {
        this.station = station
        latitude = latitudes[k]
        longitude = longitudes[k]
        bearing = bearings[k]
        width = widths[k]
        twoWay = twoWays[k]
        return true
    }

    private fun none(): Boolean {
        station = Double.NaN
        latitude = Double.NaN
        longitude = Double.NaN
        bearing = Double.NaN
        width = Double.NaN
        twoWay = false
        return false
    }

    /**
     * The station of the place of the route's line nearest to [latitude], [longitude]: near the one found last first, anywhere
     * in the window where nothing near lies within [MAX_OFF_LINE_METERS]; not a number where nothing does. Flat metres about the
     * puck, worked out in place: no allocation.
     */
    private fun stationOf(latitude: Double, longitude: Double): Double {
        val n = lineStations.size
        if (n < 2 || latitude.isNaN() || longitude.isNaN()) return Double.NaN
        val near = lastStation
        if (!near.isNaN()) {
            val lo = max(0, RoutePath.indexAtOrBefore(lineStations, near - NEAR_BACK_METERS))
            val hi = min(n - 2, max(lo, RoutePath.indexAtOrBefore(lineStations, near + NEAR_AHEAD_METERS)))
            val found = nearest(latitude, longitude, lo, hi)
            if (!found.isNaN()) {
                lastStation = found
                return found
            }
        }
        val found = nearest(latitude, longitude, 0, n - 2)
        lastStation = found
        return found
    }

    /** The station of the place nearest to the puck on the segments [lo] to [hi] of the line; not a number beyond [MAX_OFF_LINE_METERS]. */
    private fun nearest(latitude: Double, longitude: Double, lo: Int, hi: Int): Double {
        val east = METERS_PER_DEGREE * cos(Math.toRadians(latitude))
        var best = Double.POSITIVE_INFINITY
        var bestStation = Double.NaN
        var ax = (lineLongitudes[lo] - longitude) * east
        var ay = (lineLatitudes[lo] - latitude) * METERS_PER_DEGREE
        for (i in lo..hi) {
            val bx = (lineLongitudes[i + 1] - longitude) * east
            val by = (lineLatitudes[i + 1] - latitude) * METERS_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val square = dx * dx + dy * dy
            val t = if (square > 0.0) ((-ax * dx - ay * dy) / square).coerceIn(0.0, 1.0) else 0.0
            val off = hypot(ax + t * dx, ay + t * dy)
            if (off < best) {
                best = off
                bestStation = lineStations[i] + t * (lineStations[i + 1] - lineStations[i])
            }
            ax = bx
            ay = by
        }
        return if (best <= MAX_OFF_LINE_METERS) bestStation else Double.NaN
    }

    companion object {

        /** The bar's depth along the road (the design's 5.4). */
        const val DEPTH_METERS = 2.0

        /** The bar is laid out this often along the route; between two places it is interpolated. */
        const val STEP_METERS = 1.0

        /** Two places of the table farther apart than this have no lanes between them: the bar is not drawn there. */
        const val GAP_METERS = 1.5 * STEP_METERS

        /** The map makes the bar's image again only where its width has changed by this much since ([widthChanged]). */
        const val WIDTH_STEP_METERS = 0.5

        /**
         * Between two places of the table a step of the carriageway sideways or in width larger than this is a jump, where lanes
         * begin or end at once (past a ramp's start the lanes that do not go on are no longer the route's): the lanes' way there
         * is read on the side that does not jump.
         */
        const val JUMP_METERS = 0.5

        /** The puck is looked for this far behind the station it was found at last... */
        const val NEAR_BACK_METERS = 50.0

        /** ...and this far ahead: farther than a second of driving at any speed, also when frames are missed. */
        const val NEAR_AHEAD_METERS = 200.0

        /** A puck farther than this from the route's line is not on it, as for the own line's projection ([PuckProjection]). */
        const val MAX_OFF_LINE_METERS = PuckProjection.MAX_OFF_LINE_METERS

        /** The route's line is kept this far before and past the window, for a puck found just outside it. */
        const val LINE_MARGIN_METERS = 120.0

        /** A forward tick on each outer apron (the design's 5.4): how far outside the edge line it begins... */
        const val TICK_GAP_METERS = 0.05

        /** ...how wide it is at its base, on the bar's back edge... */
        const val TICK_WIDTH_METERS = 1.1

        /** ...and how far ahead of the bar's middle its point lies (PF's car_bar). */
        const val TICK_REACH_METERS = 2.6

        /**
         * The bar's white rim, on the ground: 1.5 dp where a freeway lane of 3.66 m is as wide on the screen as the close view
         * makes it ([io.github.purplepalm.custommaps.nav.camera.CameraTuning.freewayLaneDp]).
         */
        const val RIM_METERS = 0.39

        /** The bar's image is drawn this many dp to a metre; [sizeStops] scales it to its ground size on the map. */
        const val IMAGE_DP_PER_METER = 9.0

        private val METERS_PER_DEGREE = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

        /**
         * The stops of a zoom-only exponential expression, base 2, of the bar image's size: at every zoom its [IMAGE_DP_PER_METER]
         * dp to a metre are as large as a metre of ground at [latitude], as a line's ground width is ([LaneDrawing.widthStops]).
         * Exact at every zoom between the stops, since the ground doubles with each zoom.
         */
        fun sizeStops(latitude: Double): List<Pair<Double, Double>> =
            listOf(0.0 to sizeAt(0.0, latitude), LaneDrawing.MAX_ZOOM to sizeAt(LaneDrawing.MAX_ZOOM, latitude))

        /** The bar image's size at [zoom] and [latitude]: a metre of its image, [IMAGE_DP_PER_METER] dp, as wide as a metre of ground. */
        fun sizeAt(zoom: Double, latitude: Double): Double = 1.0 / (IMAGE_DP_PER_METER * MapScale.metersPerDp(zoom, latitude))

        /** Whether a bar drawn [shown] metres wide is to be made again for [now]: none drawn yet, or [WIDTH_STEP_METERS] or more apart. */
        fun widthChanged(shown: Double, now: Double): Boolean = shown.isNaN() || abs(now - shown) >= WIDTH_STEP_METERS

        /** From [from] degrees toward [to] by [fraction], the short way round, from 0 up to 360. */
        internal fun turnTowards(from: Double, to: Double, fraction: Double): Double {
            val turn = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0
            return ((from + turn * fraction) % 360.0 + 360.0) % 360.0
        }

        /**
         * The bar along [drawing] from route station [from] to [to]: at every multiple of [STEP_METERS] there where the drawing
         * lays lanes ([LaneDrawing.edgesAt]), square to the way the carriageway's middle runs between the places beside it on
         * the side without a jump ([JUMP_METERS]), and the route's own line there with [LINE_MARGIN_METERS] on each side. For the
         * route lines' worker: a window of a few kilometres takes a few thousand of the drawing's lookups.
         */
        fun of(drawing: LaneDrawing, from: Double, to: Double): CarBar {
            val at = ArrayList<Double>()
            val lefts = ArrayList<LocalPoint>()
            val rights = ArrayList<LocalPoint>()
            val twoWay = ArrayList<Boolean>()
            var frame: LocalMeters? = null
            if (from.isFinite() && to.isFinite()) {
                var k = ceil(from / STEP_METERS).toLong()
                while (k * STEP_METERS <= to) {
                    val s = k * STEP_METERS
                    k++
                    val (left, right) = drawing.edgesAt(s) ?: continue
                    val local = frame ?: LocalMeters((left.latitude + right.latitude) / 2.0, (left.longitude + right.longitude) / 2.0).also { frame = it }
                    at.add(s)
                    lefts.add(local.toLocal(left.latitude, left.longitude))
                    rights.add(local.toLocal(right.latitude, right.longitude))
                    twoWay.add(drawing.carriagewayAt(s) == CrossSection.Carriageway.TWO_WAY)
                }
            }
            val n = at.size
            val latitudes = DoubleArray(n)
            val longitudes = DoubleArray(n)
            val bearings = DoubleArray(n)
            val widths = DoubleArray(n)
            val middles = List(n) { LocalPoint((lefts[it].x + rights[it].x) / 2.0, (lefts[it].y + rights[it].y) / 2.0) }
            // Whether the carriageway goes on from place i to place i + 1 without a jump.
            val goesOn = BooleanArray(max(0, n - 1)) { i -> at[i + 1] - at[i] <= GAP_METERS && !jumps(lefts, rights, middles, i) }
            val lines = listOf(lefts, rights, middles)
            for (i in 0 until n) {
                val a = if (i > 0 && goesOn[i - 1]) i - 1 else i
                val b = if (i < n - 1 && goesOn[i]) i + 1 else i
                // The lanes' way: of the two edge lines and the middle, the one that moves least across the road between the
                // places beside this one. Where a lane opens or ends at one edge, the other edge runs with the lanes; where the
                // road widens on both sides, the middle does; where every lane moves together, as past a ramp's start, any.
                val across = lefts[i].distanceTo(rights[i])
                val nx = if (across > 0.0) (lefts[i].x - rights[i].x) / across else 0.0
                val ny = if (across > 0.0) (lefts[i].y - rights[i].y) / across else 0.0
                var dx = 0.0
                var dy = 0.0
                var least = Double.POSITIVE_INFINITY
                if (a != b) {
                    for (line in lines) {
                        val vx = line[b].x - line[a].x
                        val vy = line[b].y - line[a].y
                        val length = hypot(vx, vy)
                        if (length == 0.0) continue
                        val sideways = abs(vx * nx + vy * ny) / length
                        if (sideways < least) {
                            least = sideways
                            dx = vx
                            dy = vy
                        }
                    }
                }
                if (least == Double.POSITIVE_INFINITY) {
                    // Alone between two jumps: square to the line between its two edges, the axis's own way (the left edge lies
                    // a quarter turn counterclockwise of it).
                    dx = lefts[i].y - rights[i].y
                    dy = rights[i].x - lefts[i].x
                }
                val length = hypot(dx, dy)
                val ux = dx / length
                val uy = dy / length
                val m = middles[i]
                val local = requireNotNull(frame)
                latitudes[i] = local.latitudeOf(m)
                longitudes[i] = local.longitudeOf(m)
                bearings[i] = (Math.toDegrees(atan2(ux, uy)) + 360.0) % 360.0
                // Square to the lanes: the edges' distance across their way, not along the axis's normal where it turns away.
                widths[i] = abs((lefts[i].x - rights[i].x) * -uy + (lefts[i].y - rights[i].y) * ux)
            }
            val path = drawing.path
            val stations = path.routeStations
            val lineFrom = if (n == 0) 0 else max(0, RoutePath.indexAtOrBefore(stations, at.first() - LINE_MARGIN_METERS))
            val lineTo = if (n == 0) -1 else min(stations.size - 1, RoutePath.indexAtOrBefore(stations, at.last() + LINE_MARGIN_METERS) + 1)
            val count = max(0, lineTo - lineFrom + 1)
            return CarBar(
                stations = at.toDoubleArray(),
                latitudes = latitudes,
                longitudes = longitudes,
                bearings = bearings,
                widths = widths,
                twoWays = twoWay.toBooleanArray(),
                lineLatitudes = DoubleArray(count) { path.route[lineFrom + it].latitude },
                lineLongitudes = DoubleArray(count) { path.route[lineFrom + it].longitude },
                lineStations = DoubleArray(count) { stations[lineFrom + it] },
            )
        }

        /** Whether the carriageway jumps between places [i] and i + 1: in width, or its middle sideways, by more than [JUMP_METERS]. */
        private fun jumps(lefts: List<LocalPoint>, rights: List<LocalPoint>, middles: List<LocalPoint>, i: Int): Boolean {
            val w0 = lefts[i].distanceTo(rights[i])
            val w1 = lefts[i + 1].distanceTo(rights[i + 1])
            if (abs(w1 - w0) > JUMP_METERS || w0 == 0.0) return true
            // Across the road: from the right edge to the left one.
            val ax = (lefts[i].x - rights[i].x) / w0
            val ay = (lefts[i].y - rights[i].y) / w0
            val sideways = (middles[i + 1].x - middles[i].x) * ax + (middles[i + 1].y - middles[i].y) * ay
            return abs(sideways) > JUMP_METERS
        }
    }
}
