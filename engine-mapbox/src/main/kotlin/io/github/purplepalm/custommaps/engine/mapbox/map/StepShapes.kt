package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint

/**
 * The shape of a route step near a maneuver as [LocalPoint] meters, for the shapes the app works out
 * itself, and back. Only the part near the maneuver point is converted: a step can be kilometers long.
 */
internal object StepShapes {

    /** The first [meters] of [points], converted with [local], and the point after that where there is one. */
    fun head(points: List<Point>, local: LocalMeters, meters: Double): List<LocalPoint> {
        val out = ArrayList<LocalPoint>()
        var along = 0.0
        for (point in points) {
            val next = local.toLocal(point.latitude(), point.longitude())
            if (out.isNotEmpty()) along += out.last().distanceTo(next)
            out += next
            if (along >= meters) break
        }
        return out
    }

    /** The last [meters] of [points], converted with [local] and in their order, with the point before that where there is one. */
    fun tail(points: List<Point>, local: LocalMeters, meters: Double): List<LocalPoint> =
        head(points.asReversed(), local, meters).asReversed()

    /** [point] on the map again. */
    fun toPoint(point: LocalPoint, local: LocalMeters): Point = Point.fromLngLat(local.longitudeOf(point), local.latitudeOf(point))
}
