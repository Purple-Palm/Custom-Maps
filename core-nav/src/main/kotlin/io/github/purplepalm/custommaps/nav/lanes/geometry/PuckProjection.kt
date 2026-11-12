package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where the car is along a route by the app's own projection of the puck onto the route's line: the trim of the app's
 * own route line where the navigation SDK skips its vanishing point (the design's 5.3). The SDK leaves its point where it
 * was while the puck is more than 10 m from its line around the route progress's index, or while that index is older
 * than 1.5 s; the own line follows this projection instead.
 *
 * The projection is the nearest place of the line to the puck within a window around a station the caller gives (where
 * the line was last trimmed): the segments from the last point at or before [BACK_METERS] behind that station to the
 * first point after [AHEAD_METERS] ahead of it; and only within [MAX_OFF_LINE_METERS] of the puck. A place at the
 * window's first or last point, where the line goes on beyond the window, is no projection: the car is outside the
 * window, and nothing is answered. Built once with a route's line, off the main thread; a call looks at the few segments
 * of its window only.
 */
class PuckProjection private constructor(
    private val points: List<GeoPoint>,
    private val stations: DoubleArray,
) {

    /**
     * The station of the place of the line nearest to [latitude], [longitude] within the window around [near]; null where
     * that place lies more than [MAX_OFF_LINE_METERS] from it, at the window's edge, or where the line has no segment.
     */
    fun station(latitude: Double, longitude: Double, near: Double): Double? {
        if (points.size < 2 || near.isNaN()) return null
        val first = max(0, RoutePath.indexAtOrBefore(stations, near - BACK_METERS))
        val last = min(points.size - 1, max(first + 1, RoutePath.indexAtOrBefore(stations, near + AHEAD_METERS) + 1))
        // In metres around the puck as the origin: the window is short enough for one flat frame.
        val local = LocalMeters(latitude, longitude)
        var best = Double.POSITIVE_INFINITY
        var bestStation = Double.NaN
        var bestAtEdge = false
        var a = local.toLocal(points[first].latitude, points[first].longitude)
        for (i in first until last) {
            val next = points[i + 1]
            val b = local.toLocal(next.latitude, next.longitude)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val square = dx * dx + dy * dy
            val t = if (square > 0.0) ((-a.x * dx - a.y * dy) / square).coerceIn(0.0, 1.0) else 0.0
            val off = hypot(a.x + t * dx, a.y + t * dy)
            val station = stations[i] + t * (stations[i + 1] - stations[i])
            // The nearer place wins; of two as near, the one nearer to where the line was trimmed.
            if (off < best - SAME_METERS || (off <= best + SAME_METERS && abs(station - near) < abs(bestStation - near))) {
                best = off
                bestStation = station
                bestAtEdge = (i == first && t == 0.0 && first > 0) || (i == last - 1 && t == 1.0 && last < points.size - 1)
            }
            a = b
        }
        return if (best <= MAX_OFF_LINE_METERS && !bestAtEdge) bestStation else null
    }

    companion object {

        /** The window reaches this far back along the route from where the line was last trimmed... */
        const val BACK_METERS = 100.0

        /** ...and this far ahead: about 30 s of freeway driving without a trim. */
        const val AHEAD_METERS = 1_000.0

        /** A puck farther than this from the route's line is not on it: no projection (a first setting). */
        const val MAX_OFF_LINE_METERS = 30.0

        /** Two places this close to the puck are as near as each other. */
        private const val SAME_METERS = 1e-9

        /** The projection onto the line [points] whose stations along the route are [stations], ascending. */
        fun of(points: List<GeoPoint>, stations: DoubleArray): PuckProjection {
            require(points.size == stations.size) { "One station per point" }
            return PuckProjection(points, stations)
        }
    }
}
