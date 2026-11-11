package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the drawn car goes after the navigator moved it at once to another place, as when it
 * matches the car to another road or a position comes far from the last. The car is put on the
 * new position and glides on from there, along its heading at its speed, for as long as one glide
 * between two updates takes. Put there and left, it stood still until the next update a second
 * later, and looked frozen.
 */
object PuckGlide {

    /** Below this speed, in meters a second, the car is taken to stand: it is put there and stays. */
    const val MOVING_METERS_PER_SECOND = 1.0

    /**
     * The point [seconds] of driving ahead of [latitude], [longitude], at [speed] meters a second
     * along [bearing], degrees clockwise from north; null for a car that stands, or without a speed
     * or a heading.
     */
    fun ahead(latitude: Double, longitude: Double, bearing: Double?, speed: Double?, seconds: Double): GeoPoint? {
        if (bearing == null || !bearing.isFinite() || speed == null || !speed.isFinite()) return null
        if (speed < MOVING_METERS_PER_SECOND || !(seconds > 0.0)) return null
        val meters = speed * seconds
        val radians = Math.toRadians(bearing)
        val local = LocalMeters(latitude, longitude)
        val point = LocalPoint(meters * sin(radians), meters * cos(radians))
        return GeoPoint(local.latitudeOf(point), local.longitudeOf(point))
    }
}
