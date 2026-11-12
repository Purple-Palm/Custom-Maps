package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A route and its path measured as the map measures lines (the design's 5.3, E1). A line layer's progress, trims and
 * gradient stops are fractions of the line's length "as measured on the webmercator projected coordinate plane", and
 * the navigation SDK measures its route line the same way, in EPSG:3857 ([projectedDistance], its calculateDistance),
 * so its vanishing point is a fraction of the route's projected length. The plan and the drawing place everything in
 * metres along the route. This table pairs them: at every station where the route or the path has a point, the metre
 * station, the route's projected length up to it and the path's. Every trim, gradient stop and seam of the app's own
 * line is a ratio of projected lengths through it, never a ratio of metres: on a long north-south route a metre ratio
 * puts the cut tens of metres off (58 m on 333 km from latitude 0).
 *
 * The table belongs to one route ([routeId]) and one path of it ([pathVersion]); a fraction of another route is never
 * read through it ([pathFractionAt] with ids). Between its rows both lengths are linear in the station. Pure; built once
 * for a path, off the main thread; one lookup is a binary search.
 */
class StationTable private constructor(
    /** The navigator's id of the route. */
    val routeId: String,
    /** The version of the path the table measures. */
    val pathVersion: Int,
    private val stations: DoubleArray,
    private val route: DoubleArray,
    private val path: DoubleArray,
) {

    /** How many rows the table has. */
    val rows: Int get() = stations.size

    /** The route's whole projected length, in fractions of the world's width. */
    val routeLength: Double get() = route.last()

    /** The path's whole projected length, in fractions of the world's width. */
    val pathLength: Double get() = path.last()

    /** The share of the route's projected length up to [station], 0 to 1. */
    fun routeFraction(station: Double): Double = fraction(route, station)

    /** The share of the path's projected length up to the place of [station], 0 to 1. */
    fun pathFraction(station: Double): Double = fraction(path, station)

    /**
     * The station where the route's projected length reaches [routeFraction] of the whole: where, along the route, the
     * navigation SDK's vanishing point lies. Clamped to the route.
     */
    fun stationAt(routeFraction: Double): Double {
        if (routeLength <= 0.0 || routeFraction.isNaN()) return stations.first()
        val target = routeFraction.coerceIn(0.0, 1.0) * routeLength
        // The first row whose length reaches the target, then back between it and the row before.
        var lo = 0
        var hi = route.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (route[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return stations.first()
        val r0 = route[lo - 1]
        val r1 = route[lo]
        val f = if (r1 > r0) (target - r0) / (r1 - r0) else 1.0
        return stations[lo - 1] + (stations[lo] - stations[lo - 1]) * f
    }

    /** The path's fraction at the place [routeFraction] of the route stands for: the own line's trim for the SDK's vanishing point. */
    fun pathFractionAt(routeFraction: Double): Double = pathFraction(stationAt(routeFraction))

    /** [pathFractionAt] read only through the table of [routeId] and [pathVersion]: null where this table is another route's or path's. */
    fun pathFractionAt(routeId: String, pathVersion: Int, routeFraction: Double): Double? =
        if (routeId == this.routeId && pathVersion == this.pathVersion) pathFractionAt(routeFraction) else null

    private fun fraction(lengths: DoubleArray, station: Double): Double {
        val whole = lengths.last()
        if (whole <= 0.0 || station.isNaN()) return 0.0
        val k = RoutePath.indexAtOrBefore(stations, station)
        if (k < 0) return 0.0
        if (k >= stations.size - 1) return 1.0
        val s0 = stations[k]
        val s1 = stations[k + 1]
        val f = if (s1 > s0) (station - s0) / (s1 - s0) else 0.0
        return ((lengths[k] + (lengths[k + 1] - lengths[k]) * f) / whole).coerceIn(0.0, 1.0)
    }

    companion object {

        /** EPSG:3857 x of [longitude] in degrees, as a fraction of the world's width (the SDK's projectX). */
        fun projectX(longitude: Double): Double = longitude / 360.0 + 0.5

        /**
         * EPSG:3857 y of [latitude] in degrees, as a fraction of the world's width from the north (the SDK's projectY),
         * with its clamp as it is: below 0 it gives 0, above 1 it gives 1.1.
         */
        fun projectY(latitude: Double): Double {
            val s = sin(latitude * PI / 180.0)
            val y = 0.5 - 0.25 * ln((1.0 + s) / (1.0 - s)) / PI
            return when {
                y < 0.0 -> 0.0
                y > 1.0 -> 1.1
                else -> y
            }
        }

        /** The distance from [a] to [b] in EPSG:3857, in fractions of the world's width: the SDK's calculateDistance. */
        fun projectedDistance(a: GeoPoint, b: GeoPoint): Double {
            val dx = projectX(a.longitude) - projectX(b.longitude)
            val dy = projectY(a.latitude) - projectY(b.latitude)
            return sqrt(dx * dx + dy * dy)
        }

        /** The projected length of [points] up to each of them, from 0 at the first. */
        fun projectedLengths(points: List<GeoPoint>): DoubleArray {
            val out = DoubleArray(points.size)
            for (i in 1 until points.size) out[i] = out[i - 1] + projectedDistance(points[i - 1], points[i])
            return out
        }

        /** The table of [path]: its route's line and its own points with their stations. */
        fun of(path: RoutePath): StationTable = of(path.routeId, path.version, path.route, path.routeStations, path.points, path.stations)

        /**
         * The table of the route [routeId], its line [route] with each point's station [routeStations], and of the path
         * [pathVersion], its points [path] with their stations [pathStations]; both ascending.
         */
        fun of(
            routeId: String,
            pathVersion: Int,
            route: List<GeoPoint>,
            routeStations: DoubleArray,
            path: List<GeoPoint>,
            pathStations: DoubleArray,
        ): StationTable {
            require(route.size == routeStations.size && path.size == pathStations.size) { "One station per point" }
            require(route.isNotEmpty() && path.isNotEmpty()) { "A table needs points" }
            val routeLengths = projectedLengths(route)
            val pathLengths = projectedLengths(path)
            // Every station where either has a point, once.
            val all = (routeStations.asList() + pathStations.asList()).filter { !it.isNaN() }.sorted()
            val rows = ArrayList<Double>(all.size)
            for (s in all) if (rows.isEmpty() || s > rows.last()) rows.add(s)
            val stations = rows.toDoubleArray()
            return StationTable(
                routeId,
                pathVersion,
                stations,
                DoubleArray(stations.size) { lengthAt(route, routeStations, routeLengths, stations[it]) },
                DoubleArray(stations.size) { lengthAt(path, pathStations, pathLengths, stations[it]) },
            )
        }

        /**
         * The projected length of the line [points] (stations [stations], lengths [lengths]) up to its place at [station]:
         * to the last point at or before it, and on from there to the place between that point and the next, as far
         * along as the station is.
         */
        private fun lengthAt(points: List<GeoPoint>, stations: DoubleArray, lengths: DoubleArray, station: Double): Double {
            val k = RoutePath.indexAtOrBefore(stations, station)
            if (k < 0) return 0.0
            if (k >= points.size - 1 || stations[k] == station) return lengths[k]
            val s0 = stations[k]
            val s1 = stations[k + 1]
            val f = if (s1 > s0) (station - s0) / (s1 - s0) else 0.0
            val a = points[k]
            val b = points[k + 1]
            val at = GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            return lengths[k] + projectedDistance(a, at)
        }
    }
}
