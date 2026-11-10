package io.github.purplepalm.custommaps.nav.geo

import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distances on the Earth for every module: the debug log, stop points, lanes and the predictor all
 * measure with these, so they agree to the centimeter.
 */
object Geo {

    /** The mean Earth radius (IUGG), in meters. */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * The great-circle distance in meters between two WGS 84 coordinates in degrees, by the haversine
     * formula on a sphere of [EARTH_RADIUS_METERS]. Allocates nothing, so it can run for every frame
     * or every point of a trace. Takes the short way across the 180th meridian.
     */
    fun meters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val phi1 = Math.toRadians(latitude1)
        val phi2 = Math.toRadians(latitude2)
        val halfDeltaPhi = sin((phi2 - phi1) / 2)
        val halfDeltaLambda = sin(Math.toRadians(longitude2 - longitude1) / 2)
        val h = halfDeltaPhi * halfDeltaPhi + cos(phi1) * cos(phi2) * halfDeltaLambda * halfDeltaLambda
        // Rounding can push h a hair above 1 for nearly opposite points.
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(min(1.0, h)))
    }

    /** The great-circle distance in meters between [a] and [b]; see the coordinate overload. */
    fun meters(a: GeoPoint, b: GeoPoint): Double = meters(a.latitude, a.longitude, b.latitude, b.longitude)
}
