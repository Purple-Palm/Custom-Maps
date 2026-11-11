package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The maneuver arrow of a U-turn through a median opening, drawn as one curve.
 *
 * On a divided road the map has one one-way road for each direction and a short road across the
 * median that joins them. A U-turn there goes up to the opening, across it and back down the other
 * side: two corners, a few meters apart. The navigation SDK's arrow follows that shape exactly, so
 * close in it draws a box. Here both corners are rounded into one arc, as the turn is driven: its
 * radius is half the way across, at most [MAX_RADIUS_METERS], and smaller where the corners or the
 * arrow leave it less room. Only the arrow is reshaped, in memory; the route and its line keep the
 * road's own shape.
 *
 * Everything is in [LocalPoint] meters: plain numbers, tested on the JVM.
 */
object UTurnArrow {

    /** The arrow starts this far before the maneuver point and reaches at least this far past it, as the SDK's does. */
    const val ARROW_METERS = 30.0

    /** The two corners lie at least this far apart along the road, about one lane. */
    const val MIN_ACROSS_METERS = 4.0

    /** And at most this far apart: across a wider median the route makes two turns, not one U. */
    const val MAX_ACROSS_METERS = 40.0

    /** The curve's radius is half the way across, and never more than this. */
    const val MAX_RADIUS_METERS = 8.0

    /** A bend of the road at least this sharp is a corner. */
    const val CORNER_DEGREES = 45.0

    /** Through its two corners the road turns back by at least this much. */
    const val REVERSAL_DEGREES = 135.0

    /** The arrow goes at least this far past the second corner, so its head points back along the road. */
    const val RETURN_METERS = 10.0

    /** How much of the road after the maneuver point [shape] may look at: the farthest a second corner and the return can reach. */
    const val ONWARD_METERS = ARROW_METERS + MAX_ACROSS_METERS + RETURN_METERS

    /** How much of the road on each side of a corner gives its direction. */
    private const val DIRECTION_METERS = 5.0

    /** Points closer than this to the one kept before them add nothing but noise to a direction. */
    private const val MIN_SPACING_METERS = 0.5

    /** The smallest curve worth drawing; with less room the arrow stays as it is. */
    private const val MIN_RADIUS_METERS = 1.0

    /** The straight end of the arrow after the curve, at least, so the head takes the road's direction. */
    private const val STRAIGHT_END_METERS = 1.0

    /** The arc is drawn in pieces of at most this many degrees. */
    private const val ARC_STEP_DEGREES = 10.0

    /**
     * The arrow as one curve, or null when the road does not turn back through two corners within
     * [MIN_ACROSS_METERS] to [MAX_ACROSS_METERS] of each other, both the same way round: then the
     * SDK's own arrow is the right one, as for an undivided road where the U-turn is one point.
     *
     * @param approach the road up to the maneuver point, which is its last point
     * @param onward the road from the maneuver point on, its first point, for [ONWARD_METERS]
     *   where the step is that long
     * @return the arrow's points, from [ARROW_METERS] before the maneuver point to [ARROW_METERS]
     *   past it, or to [RETURN_METERS] past the second corner where that is farther
     */
    fun shape(approach: List<LocalPoint>, onward: List<LocalPoint>): List<LocalPoint>? {
        val before = LocalLine.tail(spaced(approach.asReversed()).asReversed(), ARROW_METERS)
        val after = spaced(onward)
        if (before.size < 2 || after.size < 2) return null
        val path = before + after.drop(1)
        val along = cumulative(path)
        val maneuver = before.size - 1
        val last = path.size - 1

        val first = (maneuver until last).firstOrNull { abs(turnAt(path, it)) >= CORNER_DEGREES } ?: return null
        if (along[first] - along[maneuver] > ARROW_METERS) return null
        val second = (first + 1 until last).firstOrNull { abs(turnAt(path, it)) >= CORNER_DEGREES } ?: return null
        val left = turnAt(path, first) < 0.0
        if ((turnAt(path, second) < 0.0) != left) return null
        val across = along[second] - along[first]
        if (across < MIN_ACROSS_METERS || across > MAX_ACROSS_METERS) return null

        val corner1 = path[first]
        val corner2 = path[second]
        val inbound = unit(LocalLine.pointAt(path, along[first] - min(DIRECTION_METERS, along[first])), corner1)
        val outbound = unit(corner2, LocalLine.pointAt(path, along[second] + DIRECTION_METERS))
        val acrossDir = unit(corner1, corner2)
        if (inbound == null || outbound == null || acrossDir == null) return null
        if (abs(turnDegrees(inbound, outbound)) < REVERSAL_DEGREES) return null
        // Both corners the same way round, also with the directions measured over a few meters.
        if ((cross(inbound, acrossDir) > 0.0) != left || (cross(acrossDir, outbound) > 0.0) != left) return null

        val end = min(along[last], max(along[maneuver] + ARROW_METERS, along[second] + RETURN_METERS))
        val angle1 = angleBetween(inbound, acrossDir)
        val angle2 = angleBetween(acrossDir, outbound)
        val tan1 = tan(angle1 / 2.0)
        val tan2 = tan(angle2 / 2.0)
        val radius = minOf(
            across / 2.0,
            MAX_RADIUS_METERS,
            corner1.distanceTo(corner2) / (tan1 + tan2),
            along[first] / tan1,
            (end - along[second] - STRAIGHT_END_METERS) / tan2,
        )
        if (!(radius >= MIN_RADIUS_METERS)) return null

        val out = ArrayList<LocalPoint>()
        for (i in 0 until first) if (along[i] < along[first] - radius * tan1) out += path[i]
        out += fillet(corner1, inbound, acrossDir, radius, tan1, left)
        out += fillet(corner2, acrossDir, outbound, radius, tan2, left)
        for (i in second + 1 until last) if (along[i] > along[second] + radius * tan2 && along[i] < end) out += path[i]
        out += LocalLine.pointAt(path, end)
        return spaced(out)
    }

    /**
     * The arc that rounds [corner], from where it leaves the road that comes in along [inbound] to
     * where it joins the road that goes on along [outbound]; [tanHalf] is the tangent of half the
     * corner's angle.
     */
    private fun fillet(corner: LocalPoint, inbound: Unit2, outbound: Unit2, radius: Double, tanHalf: Double, left: Boolean): List<LocalPoint> {
        val reach = radius * tanHalf
        val start = LocalPoint(corner.x - inbound.x * reach, corner.y - inbound.y * reach)
        // The center lies to the side the road turns to, one radius from where the arc starts.
        val side = if (left) Unit2(-inbound.y, inbound.x) else Unit2(inbound.y, -inbound.x)
        val center = LocalPoint(start.x + side.x * radius, start.y + side.y * radius)
        val sweep = angleBetween(inbound, outbound) * (if (left) 1.0 else -1.0)
        val from = atan2(start.y - center.y, start.x - center.x)
        val pieces = max(1, ceil(abs(Math.toDegrees(sweep)) / ARC_STEP_DEGREES).toInt())
        return (0..pieces).map { k ->
            val a = from + sweep * k / pieces
            LocalPoint(center.x + radius * cos(a), center.y + radius * sin(a))
        }
    }

    /** A direction of length 1. */
    private class Unit2(val x: Double, val y: Double)

    private fun unit(from: LocalPoint, to: LocalPoint): Unit2? {
        val length = from.distanceTo(to)
        return if (length > 0.0) Unit2((to.x - from.x) / length, (to.y - from.y) / length) else null
    }

    /** Positive where [b] turns left from [a]: x is east and y north. */
    private fun cross(a: Unit2, b: Unit2): Double = a.x * b.y - a.y * b.x

    /** The angle between two directions, from 0 to pi. */
    private fun angleBetween(a: Unit2, b: Unit2): Double = acos((a.x * b.x + a.y * b.y).coerceIn(-1.0, 1.0))

    /** The change of compass bearing from [a] to [b], in degrees from -180 to 180: negative to the left. */
    private fun turnDegrees(a: Unit2, b: Unit2): Double =
        wrap180(Math.toDegrees(atan2(b.x, b.y)) - Math.toDegrees(atan2(a.x, a.y)))

    /** How sharply the road bends at point [i], in degrees: negative to the left. */
    private fun turnAt(path: List<LocalPoint>, i: Int): Double =
        wrap180(LocalLine.bearing(path[i], path[i + 1]) - LocalLine.bearing(path[i - 1], path[i]))

    private fun wrap180(degrees: Double): Double = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** Along the line to each of its points. */
    private fun cumulative(path: List<LocalPoint>): DoubleArray {
        val along = DoubleArray(path.size)
        for (i in 1 until path.size) along[i] = along[i - 1] + path[i - 1].distanceTo(path[i])
        return along
    }

    /** The line without points that lie closer than [MIN_SPACING_METERS] to the one kept before them. */
    private fun spaced(points: List<LocalPoint>): List<LocalPoint> {
        val out = ArrayList<LocalPoint>(points.size)
        for (p in points) if (out.isEmpty() || out.last().distanceTo(p) >= MIN_SPACING_METERS) out += p
        return out
    }
}
