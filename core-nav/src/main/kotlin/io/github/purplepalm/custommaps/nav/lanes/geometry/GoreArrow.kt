package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The maneuver arrow at a gore of the app's own lanes (the own lanes' design 5.3, D11). The navigation SDK's arrow of a
 * maneuver follows the route's own line, which at a gore cuts across the lanes to the ramp; where the app draws the lanes,
 * the arrow follows its path instead, in the lit lane up to the gore and on into the ramp past it, from [BEFORE_METERS]
 * before the gore to [AFTER_METERS] after it. Pure; built once with the path, off the main thread, and kept in memory
 * with it, never stored.
 */
object GoreArrow {

    /** The arrow begins this far before the gore, along the route. */
    const val BEFORE_METERS = 12.0

    /** It ends this far past the gore. */
    const val AFTER_METERS = 30.0

    /** The arrow's last piece is at least this long, so its head points the way the path goes; shorter ones are dropped. */
    const val HEAD_PIECE_METERS = 1.0

    /** A maneuver this close to a gore along the route is the gore's: both are where the route leaves its road. */
    const val SAME_PLACE_METERS = 1.0

    /**
     * The arrow at the gore at route station [gore], along the path of [points], each at the route station of [stations]
     * (ascending): a point where it begins, [BEFORE_METERS] before the gore, the path's points after it, and a point where
     * it ends, [AFTER_METERS] past the gore; cut where the path ends. Empty where it would have fewer than two points.
     */
    fun along(points: List<GeoPoint>, stations: DoubleArray, gore: Double): List<GeoPoint> {
        require(points.size == stations.size) { "A station for every point" }
        if (points.size < 2) return emptyList()
        val from = max(stations.first(), gore - BEFORE_METERS)
        val to = min(stations.last(), gore + AFTER_METERS)
        if (to <= from) return emptyList()
        val out = ArrayList<GeoPoint>()
        out.add(at(points, stations, from))
        for (k in points.indices) if (stations[k] > from && stations[k] < to) out.add(points[k])
        out.add(at(points, stations, to))
        while (out.size > 2 && Geo.meters(out[out.size - 2], out.last()) < HEAD_PIECE_METERS) out.removeAt(out.size - 2)
        return if (out.size >= 2 && Geo.meters(out[out.size - 2], out.last()) > 0.0) out else emptyList()
    }

    /** The gore of [gores] (route stations) a maneuver at route station [maneuver] lies at; null where it lies at none. */
    fun goreAt(gores: List<Double>, maneuver: Double): Double? =
        gores.firstOrNull { abs(it - maneuver) <= SAME_PLACE_METERS }

    /** The path's point at route station [station]: between the two points around it, in proportion to their stations. */
    private fun at(points: List<GeoPoint>, stations: DoubleArray, station: Double): GeoPoint {
        var lo = 0
        var hi = stations.size - 1
        if (station <= stations[lo]) return points[lo]
        if (station >= stations[hi]) return points[hi]
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (stations[mid] <= station) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = stations[hi] - stations[lo]
        val f = if (span > 0.0) (station - stations[lo]) / span else 0.0
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }
}
