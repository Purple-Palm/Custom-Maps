package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * A road's axis for the app's own lane drawing, in flat metres (the design's 4.1, after GEOM 2.1 and 2.2):
 * a piece of the route's polyline, one drawing window long, in [LocalMeters] of one origin, with every inner
 * vertex replaced by a circular arc that moves the axis no more than [MAX_DEV_METERS] from the vertex. The
 * axis is kept as exact pieces, straight lines and arcs, so its point, tangent, normal and curvature are
 * known exactly at every place along it.
 *
 * Every lateral position of the drawing is an offset t from the axis along its left normal, left positive
 * ([offset]). On an arc of radius r the offset at t is an arc of radius r - t on the inner side, regular
 * while 1 - kappa t > 0, kappa being the signed curvature (+1/r on a left arc). Where 1 - kappa t <= 0 the
 * offset folds over itself ([folds]): nothing with lane lines may be drawn there. The style's line offset is
 * used for nothing: it folds inside bends.
 *
 * Places along the axis are route stations, metres along the route as the steps' shapes measure them: on a
 * straight piece the station of the polyline itself; on an arc the stations of the two tangent lengths it
 * replaces, spread evenly over the arc. The way back to latitude and longitude uses the same origin and the
 * same scale ([toGeo]), so a point taken there and back is the point it was. Over a window of a few
 * kilometres the east-west scale, fixed at the origin's latitude, drifts by a few hundredths of a percent
 * (at latitude 35 about 0.033 % over 3 km): a few millimetres across a carriageway.
 *
 * Pure; built once for a window, off the main thread, and never stored.
 */
class RoadAxis private constructor(
    private val frame: LocalMeters,
    /** The pieces in route order, each with the stations it covers. */
    val pieces: List<Piece>,
    /** Every turned vertex with its arc, in route order. */
    val fillets: List<Fillet>,
) {

    /** A piece of the axis: from station [from] to station [to], [length] metres long in the window's frame. */
    sealed class Piece {
        abstract val from: Double
        abstract val to: Double
        abstract val length: Double

        /** The point, tangent and curvature [u] metres into the piece. */
        internal abstract fun at(u: Double, station: Double): Place
    }

    /** A straight piece from [start] in the direction ([dx], [dy]), a unit vector. */
    class Line internal constructor(
        val start: LocalPoint,
        val dx: Double,
        val dy: Double,
        override val length: Double,
        override val from: Double,
        override val to: Double,
    ) : Piece() {
        override fun at(u: Double, station: Double) = Place(station, LocalPoint(start.x + dx * u, start.y + dy * u), dx, dy, 0.0)
    }

    /**
     * An arc around [centre] of [radius], from the angle [startAngle] (radians, counterclockwise from east)
     * turning by [sweep] radians to the left ([turn] +1) or the right ([turn] -1).
     */
    class Arc internal constructor(
        val centre: LocalPoint,
        val radius: Double,
        val startAngle: Double,
        val turn: Int,
        val sweep: Double,
        override val from: Double,
        override val to: Double,
    ) : Piece() {
        override val length: Double get() = radius * sweep

        override fun at(u: Double, station: Double): Place {
            // Measured by the angle turned, so an arc of a radius near zero (a road that turns back on itself) still has its
            // tangents from the way in to the way out.
            val fraction = if (to > from) ((station - from) / (to - from)).coerceIn(0.0, 1.0) else 0.0
            val angle = startAngle + turn * sweep * fraction
            val point = LocalPoint(centre.x + radius * cos(angle), centre.y + radius * sin(angle))
            // The tangent of a counterclockwise arc is its radius turned a quarter turn to the left.
            return Place(station, point, -sin(angle) * turn, cos(angle) * turn, turn / radius)
        }
    }

    /**
     * The vertex [vertex] of the window's polyline at [station], where the road turns by [turnDegrees], replaced
     * by an arc of [radius] metres from station [from] to station [to]: the smallest of [MAX_RADIUS_METERS],
     * [fitRadius] (its tangent points stay within half of each neighbouring segment, all of the window's first
     * and last) and [devRadius] (the arc stays within [MAX_DEV_METERS] of the vertex).
     */
    data class Fillet(
        val vertex: Int,
        val station: Double,
        val turnDegrees: Double,
        val radius: Double,
        val fitRadius: Double,
        val devRadius: Double,
        val from: Double,
        val to: Double,
    ) {
        /** How far the arc's middle lies from the vertex it replaces, in metres. */
        val deviation: Double get() = radius / cos(Math.toRadians(turnDegrees) / 2.0) - radius
    }

    /** The axis at [station]: its [point], unit tangent ([tx], [ty]) and signed [curvature] (positive turning left). */
    data class Place(val station: Double, val point: LocalPoint, val tx: Double, val ty: Double, val curvature: Double) {
        /** The left normal's x. */
        val nx: Double get() = -ty

        /** The left normal's y. */
        val ny: Double get() = tx
    }

    /** The station where the axis begins. */
    val from: Double get() = pieces.first().from

    /** The station where it ends. */
    val to: Double get() = pieces.last().to

    /** The axis at [station], clamped to [from]..[to]; exact on the pieces. */
    fun at(station: Double): Place {
        val s = station.coerceIn(from, to)
        val piece = pieces[pieceIndex(s)]
        val u = if (piece.to > piece.from) (s - piece.from) / (piece.to - piece.from) * piece.length else 0.0
        return piece.at(u, s)
    }

    /** The point [t] metres to the left of the axis at [station] (to the right where [t] is negative). */
    fun offset(station: Double, t: Double): LocalPoint {
        val place = at(station)
        return LocalPoint(place.point.x + place.nx * t, place.point.y + place.ny * t)
    }

    /** 1 - kappa t at [station]: above 0 the offset at [t] is regular there, at 0 or below it folds. */
    fun regularity(station: Double, t: Double): Double = 1.0 - at(station).curvature * t

    /** Whether the offset at [t] folds over itself at [station]: 1 - kappa t <= 0. */
    fun folds(station: Double, t: Double): Boolean = regularity(station, t) <= 0.0

    /** Whether [station] lies on a straight piece, where the axis is the polyline itself. */
    fun straightAt(station: Double): Boolean = pieces[pieceIndex(station.coerceIn(from, to))] is Line

    /**
     * Stations to sample the axis at: every piece's ends, and within each piece at most [stepMeters] of
     * stations apart and, on an arc, at most [SAMPLE_DEGREES] of turn apart, so even a tight arc's offset is
     * drawn round. Ascending, from [from] to [to], none repeated.
     */
    fun stations(stepMeters: Double = SAMPLE_METERS): DoubleArray {
        val out = ArrayList<Double>()
        out.add(from)
        for (piece in pieces) {
            val span = piece.to - piece.from
            var n = max(1, ceil(span / stepMeters).toInt())
            if (piece is Arc) n = max(n, ceil(Math.toDegrees(piece.sweep) / SAMPLE_DEGREES).toInt())
            for (j in 1..n) {
                val s = if (j == n) piece.to else piece.from + span * j / n
                if (s > out.last() + SAME_STATION_METERS) out.add(s)
            }
        }
        return out.toDoubleArray()
    }

    /** A point of the window's frame as latitude and longitude, with the frame's own origin and scale. */
    fun toGeo(point: LocalPoint): GeoPoint = GeoPoint(frame.latitudeOf(point), frame.longitudeOf(point))

    /** A coordinate as a point of the window's frame. */
    fun toLocal(point: GeoPoint): LocalPoint = frame.toLocal(point.latitude, point.longitude)

    /** The index of the piece that holds [station]: the one it lies in, the later one at a boundary. */
    private fun pieceIndex(station: Double): Int {
        var lo = 0
        var hi = pieces.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pieces[mid].from <= station) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {

        /** An arc moves the axis no more than this from the vertex it replaces (the design's maxDevM). */
        const val MAX_DEV_METERS = 0.5

        /** No arc is wider than this: a vertex that barely turns keeps short tangents. */
        const val MAX_RADIUS_METERS = 3_000.0

        /** The axis is sampled at most this far apart, in stations ([stations]). */
        const val SAMPLE_METERS = 1.0

        /** And on an arc at most this much turn apart, in degrees. */
        const val SAMPLE_DEGREES = 5.0

        /** Points of the polyline closer than this, in metres, are one point. */
        private const val SAME_POINT_METERS = 1e-6

        /** Samples closer than this, in stations, are one sample. */
        private const val SAME_STATION_METERS = 1e-9

        /** A vertex that turns by less than this, in radians, is no turn. */
        private const val NO_TURN_RADIANS = 1e-9

        /**
         * The axis of the polyline [points], whose stations along the route are [stations] (by default the first at 0
         * and each next one [Geo.meters] further), in the frame of [origin] (by default the first point). Points that
         * repeat the one before them are dropped. Needs two points that differ.
         */
        fun of(points: List<GeoPoint>, stations: DoubleArray? = null, origin: GeoPoint? = null): RoadAxis {
            require(stations == null || stations.size == points.size) { "One station per point" }
            require(points.isNotEmpty()) { "An axis needs points" }
            val first = origin ?: points.first()
            val frame = LocalMeters(first.latitude, first.longitude)
            val local = ArrayList<LocalPoint>(points.size)
            val at = ArrayList<Double>(points.size)
            var along = stations?.get(0) ?: 0.0
            for (i in points.indices) {
                if (i > 0 && stations == null) along += Geo.meters(points[i - 1], points[i])
                val p = frame.toLocal(points[i].latitude, points[i].longitude)
                val s = stations?.get(i) ?: along
                if (local.isNotEmpty() && local.last().distanceTo(p) <= SAME_POINT_METERS) continue
                local.add(p)
                at.add(s)
            }
            require(local.size >= 2) { "An axis needs two points that differ" }
            return build(frame, local, at.toDoubleArray())
        }

        private fun build(frame: LocalMeters, pts: List<LocalPoint>, st: DoubleArray): RoadAxis {
            val n = pts.size
            val pieces = ArrayList<Piece>()
            val fillets = ArrayList<Fillet>()
            var prevEnd = pts[0]
            var prevStation = st[0]
            for (i in 1 until n - 1) {
                val a = pts[i - 1]
                val b = pts[i]
                val c = pts[i + 1]
                val lenPrev = a.distanceTo(b)
                val lenNext = b.distanceTo(c)
                val u1x = (b.x - a.x) / lenPrev
                val u1y = (b.y - a.y) / lenPrev
                val u2x = (c.x - b.x) / lenNext
                val u2y = (c.y - b.y) / lenNext
                val theta = acos((u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0))
                if (theta < NO_TURN_RADIANS) continue
                val availPrev = if (i - 1 > 0) lenPrev / 2.0 else lenPrev
                val availNext = if (i + 1 < n - 1) lenNext / 2.0 else lenNext
                val half = theta / 2.0
                val fit = min(availPrev, availNext) / tan(half)
                val sec = 1.0 / cos(half) - 1.0
                val dev = if (sec > 1e-12) MAX_DEV_METERS / sec else Double.POSITIVE_INFINITY
                val r = min(MAX_RADIUS_METERS, min(fit, dev))
                val tl = r * tan(half)
                val t1 = LocalPoint(b.x - u1x * tl, b.y - u1y * tl)
                val turn = if (u1x * u2y - u1y * u2x > 0.0) 1 else -1
                // The centre lies on the left normal of the way in for a left turn, on the right one for a right turn.
                val centre = LocalPoint(t1.x - u1y * turn * r, t1.y + u1x * turn * r)
                // Stations of the two tangent points, along the polyline's own stations of each segment.
                var sIn = st[i] - tl / lenPrev * (st[i] - st[i - 1])
                val sOut = st[i] + tl / lenNext * (st[i + 1] - st[i])
                if (t1.distanceTo(prevEnd) > SAME_POINT_METERS) {
                    pieces.add(line(prevEnd, t1, prevStation, sIn, u1x, u1y))
                } else {
                    // Two arcs that meet: the second begins where the first ends, with no straight piece between.
                    sIn = prevStation
                }
                val a0 = atan2(t1.y - centre.y, t1.x - centre.x)
                pieces.add(Arc(centre, r, a0, turn, theta, sIn, sOut))
                fillets.add(Fillet(i, st[i], Math.toDegrees(theta), r, fit, dev, sIn, sOut))
                prevEnd = LocalPoint(b.x + u2x * tl, b.y + u2y * tl)
                prevStation = sOut
            }
            val last = pts[n - 1]
            if (last.distanceTo(prevEnd) > SAME_POINT_METERS || pieces.isEmpty()) {
                val dx = last.x - prevEnd.x
                val dy = last.y - prevEnd.y
                val len = hypot(dx, dy)
                // The direction of the last segment, also where the last arc ends right at the last point.
                val ux = if (len > 0.0) dx / len else (pts[n - 1].x - pts[n - 2].x) / pts[n - 2].distanceTo(pts[n - 1])
                val uy = if (len > 0.0) dy / len else (pts[n - 1].y - pts[n - 2].y) / pts[n - 2].distanceTo(pts[n - 1])
                pieces.add(line(prevEnd, last, prevStation, max(st[n - 1], prevStation), ux, uy))
            }
            return RoadAxis(frame, pieces, fillets)
        }

        private fun line(start: LocalPoint, end: LocalPoint, from: Double, to: Double, ux: Double, uy: Double): Line {
            val length = start.distanceTo(end)
            return Line(start, ux, uy, length, from, max(from, to))
        }
    }
}
