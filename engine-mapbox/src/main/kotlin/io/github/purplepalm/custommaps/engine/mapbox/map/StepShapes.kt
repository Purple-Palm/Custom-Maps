package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.Bend
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.TurnBend
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

    /**
     * Where the road of a step bends after its maneuver point, its first point, by the rule the
     * junction's frame uses ([TurnBend], with [tuning]'s numbers); null where it does not bend
     * within the search, and for a step without points.
     *
     * @param bearingBefore the compass bearing the car comes in on at the maneuver
     */
    fun bendOf(shape: List<Point>, bearingBefore: Double, tuning: CameraTuning): Bend? {
        val origin = shape.firstOrNull() ?: return null
        val local = LocalMeters(origin.latitude(), origin.longitude())
        val road = head(shape, local, tuning.bendSearchMeters)
        return TurnBend.bend(road, bearingBefore, tuning.bendDegrees, tuning.bendSideMeters, tuning.bendSearchMeters, tuning.bendMinMeters)
    }
}
