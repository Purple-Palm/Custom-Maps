package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Where the road bends after a maneuver: [meters] along it from the maneuver point, and [degrees],
 * how far its direction there is from the one the car comes in on, negative to the left.
 */
data class Bend(val meters: Double, val degrees: Double)

/**
 * Finds where the road bends after a maneuver point.
 *
 * A route's step begins where the maneuver is, which is not always where the road visibly turns: at
 * a turn bay or a split mapped as a road of its own, the step of a left turn can begin 50 m or more
 * before the bend. The frame of the junction then has to reach the bend, not only the maneuver point.
 *
 * The bend is the first point after the maneuver point where the road's direction is
 * [minDegrees] or more off the direction the car comes in on. A road that only veers, by less
 * than that, bends where it has moved more than [sideMeters], about one lane, to the side of the
 * line the car comes in on. Both are looked for within [searchMeters] of the maneuver point, the
 * angle first. A turn at the maneuver point itself is a bend at 0 m.
 */
object TurnBend {

    /** Pieces of road shorter than this have no direction worth reading: points a few centimeters apart. */
    private const val MIN_PIECE_METERS = 0.5

    /**
     * The first bend of [points], the road from the maneuver point on (its first point), or null
     * when it goes on within [minDegrees] and [sideMeters] of the way in for [searchMeters] or to
     * its end.
     *
     * @param approachBearing the compass bearing the car comes in on, in degrees
     */
    fun bend(
        points: List<LocalPoint>,
        approachBearing: Double,
        minDegrees: Double = 20.0,
        sideMeters: Double = 4.0,
        searchMeters: Double = 150.0,
    ): Bend? {
        val road = spaced(points)
        if (road.size < 2) return null
        return byAngle(road, approachBearing, minDegrees, searchMeters) ?: bySide(road, approachBearing, sideMeters, searchMeters)
    }

    /** The first point whose way on is [minDegrees] or more off the way in. */
    private fun byAngle(road: List<LocalPoint>, approach: Double, minDegrees: Double, searchMeters: Double): Bend? {
        var along = 0.0
        for (i in 0 until road.size - 1) {
            if (along > searchMeters) return null
            val turn = Angles.wrap180(LocalLine.bearing(road[i], road[i + 1]) - approach)
            if (abs(turn) >= minDegrees) return Bend(along, turn)
            along += road[i].distanceTo(road[i + 1])
        }
        return null
    }

    /** The first place more than [sideMeters] to either side of the line in through the maneuver point. */
    private fun bySide(road: List<LocalPoint>, approach: Double, sideMeters: Double, searchMeters: Double): Bend? {
        val ux = sin(Math.toRadians(approach))
        val uy = cos(Math.toRadians(approach))
        val start = road[0]
        // How far a point is to the left of the line in, in meters; negative to the right.
        fun offset(p: LocalPoint) = ux * (p.y - start.y) - uy * (p.x - start.x)
        var along = 0.0
        for (i in 0 until road.size - 1) {
            val piece = road[i].distanceTo(road[i + 1])
            val from = offset(road[i])
            val to = offset(road[i + 1])
            if (abs(to) > sideMeters) {
                val share = (sideMeters * sign(to) - from) / (to - from)
                val meters = along + piece * share
                if (meters > searchMeters) return null
                return Bend(meters, Angles.wrap180(LocalLine.bearing(road[i], road[i + 1]) - approach))
            }
            along += piece
            if (along > searchMeters) return null
        }
        return null
    }

    /** The road without points that lie closer than [MIN_PIECE_METERS] to the one kept before them. */
    private fun spaced(points: List<LocalPoint>): List<LocalPoint> {
        val out = ArrayList<LocalPoint>(points.size)
        for (p in points) if (out.isEmpty() || out.last().distanceTo(p) >= MIN_PIECE_METERS) out += p
        return out
    }
}
