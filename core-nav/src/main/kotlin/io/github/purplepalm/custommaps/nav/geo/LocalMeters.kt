package io.github.purplepalm.custommaps.nav.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** A point on a small piece of the map: [x] meters east and [y] meters north of the piece's origin. */
data class LocalPoint(val x: Double, val y: Double) {
    fun distanceTo(other: LocalPoint): Double = hypot(other.x - x, other.y - y)
}

/**
 * Latitude and longitude near an origin as flat meters east and north of it, and back. Over the few
 * hundred meters around a junction the error against [Geo.meters] is far below a centimeter. Both
 * directions use the same scale, so a point taken there and back is the point it was.
 */
class LocalMeters(private val originLatitude: Double, private val originLongitude: Double) {

    private val metersPerDegreeEast = METERS_PER_DEGREE * cos(Math.toRadians(originLatitude))

    fun toLocal(latitude: Double, longitude: Double): LocalPoint =
        LocalPoint(wrap180(longitude - originLongitude) * metersPerDegreeEast, (latitude - originLatitude) * METERS_PER_DEGREE)

    fun latitudeOf(point: LocalPoint): Double = originLatitude + point.y / METERS_PER_DEGREE

    fun longitudeOf(point: LocalPoint): Double = wrap180(originLongitude + point.x / metersPerDegreeEast)

    private companion object {
        /** One degree of a great circle on the sphere of [Geo.EARTH_RADIUS_METERS]. */
        val METERS_PER_DEGREE = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

        /** A longitude, or a difference of two, the short way round: from -180 up to 180. Left as it is when it is already. */
        fun wrap180(degrees: Double): Double = when {
            degrees >= 180.0 -> degrees - 360.0
            degrees < -180.0 -> degrees + 360.0
            else -> degrees
        }
    }
}

/**
 * Lines of [LocalPoint]s: a road from its first point to its last. Points that repeat the one
 * before them, as a route's shape has where two steps meet, are steps of length 0 and are skipped
 * wherever a direction is needed.
 */
object LocalLine {

    /** Along the whole line. */
    fun length(points: List<LocalPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += points[i - 1].distanceTo(points[i])
        return total
    }

    /**
     * The first [meters] of the line: its points up to there, and the point at [meters] itself,
     * which cuts the step it falls on. The whole line when it is shorter.
     */
    fun head(points: List<LocalPoint>, meters: Double): List<LocalPoint> {
        if (points.isEmpty()) return points
        if (meters <= 0.0) return listOf(points[0])
        val out = ArrayList<LocalPoint>()
        out += points[0]
        var along = 0.0
        for (i in 1 until points.size) {
            val step = points[i - 1].distanceTo(points[i])
            if (step > 0.0 && along + step >= meters) {
                out += between(points[i - 1], points[i], ((meters - along) / step).coerceIn(0.0, 1.0))
                return out
            }
            along += step
            out += points[i]
        }
        return out
    }

    /** The point [meters] along the line from its first point; its last point when the line is shorter. */
    fun pointAt(points: List<LocalPoint>, meters: Double): LocalPoint = head(points, meters).last()

    /** The last [meters] of the line, ending with its last point. The whole line when it is shorter. */
    fun tail(points: List<LocalPoint>, meters: Double): List<LocalPoint> = head(points.asReversed(), meters).asReversed()

    /** The compass bearing from [from] to [to], in degrees clockwise from north, from 0 up to 360. */
    fun bearing(from: LocalPoint, to: LocalPoint): Double = (Math.toDegrees(atan2(to.x - from.x, to.y - from.y)) + 360.0) % 360.0

    /** The point at [fraction] of the way from [a] to [b]. */
    fun between(a: LocalPoint, b: LocalPoint, fraction: Double): LocalPoint =
        LocalPoint(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
}
