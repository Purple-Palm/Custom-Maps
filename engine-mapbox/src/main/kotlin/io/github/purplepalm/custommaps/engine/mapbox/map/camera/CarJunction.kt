package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.utils.DecodeUtils.stepGeometryToPoints
import io.github.purplepalm.custommaps.engine.mapbox.map.StepShapes
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.JunctionFit
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The junction a camera anchored on the car frames itself ([CameraTuning.anchored]), in place of the navigation SDK's
 * frame of a maneuver: the maneuver point the view is latched down for and the road past it that its fit holds
 * ([JunctionFit.road]), read once for each junction from the route's step shapes, and the zoom of its fit from where the
 * car is ([zoomAt]). The data source is handed none of these points: on the car it frames no maneuver. Main thread only.
 */
internal class CarJunction {

    /** The step at whose end the junction lies, as "route/leg/step"; null while there is none. */
    var key: String? = null
        private set

    // The junction's points: the maneuver point first, then the road past it.
    private var latitudes = DoubleArray(0)
    private var longitudes = DoubleArray(0)

    /**
     * After [progress] reached the camera: the junction at the end of step [junctionKey], the latched one
     * ([io.github.purplepalm.custommaps.nav.camera.ManeuverFocus.latchedJunction]), read the first time it is asked for;
     * none for a null key. Its road is the step after it: the next step's shape for the junction of this step, the
     * shape of the step after that for the one past a step the view stays up for.
     */
    fun update(progress: RouteProgress, junctionKey: String?, tuning: CameraTuning) {
        if (junctionKey == key) return
        clear()
        if (junctionKey == null) return
        key = junctionKey
        val leg = progress.currentLegProgress ?: return
        val step = leg.currentStepProgress ?: return
        val stepKey = "${progress.navigationRoute.id}/${leg.legIndex}/${step.stepIndex}"
        try {
            if (junctionKey == stepKey) {
                hold(progress.upcomingStepPoints.orEmpty(), leg.upcomingStep?.maneuver()?.bearingBefore(), tuning)
            } else {
                // Past a step the view stays up for: the road after the junction at the end of the next step.
                val after = leg.routeLeg?.steps()?.getOrNull(step.stepIndex + 2)
                val shape = after?.let { progress.navigationRoute.directionsRoute.stepGeometryToPoints(it) }.orEmpty()
                val road = shape.ifEmpty { progress.upcomingStepPoints?.lastOrNull()?.let { listOf(it) }.orEmpty() }
                hold(road, after?.maneuver()?.bearingBefore(), tuning)
            }
        } catch (e: RuntimeException) {
            // A shape that cannot be read costs this junction its own fit, never the drive: the road view stays.
            hold(emptyList(), null, tuning)
        }
    }

    /**
     * The junction at the start of [road], the step after it: its maneuver point and the road its fit holds
     * ([JunctionFit.road]), with the way in on [bearingBefore] for its bend. Nothing for a road without points.
     */
    fun hold(road: List<Point>, bearingBefore: Double?, tuning: CameraTuning) {
        val origin = road.firstOrNull()
        if (origin == null) {
            latitudes = DoubleArray(0)
            longitudes = DoubleArray(0)
            return
        }
        val local = LocalMeters(origin.latitude(), origin.longitude())
        val points = JunctionFit.road(StepShapes.head(road, local, tuning.bendSearchMeters + tuning.largeMetersAfterManeuver), bearingBefore, tuning)
        latitudes = DoubleArray(points.size) { local.latitudeOf(points[it]) }
        longitudes = DoubleArray(points.size) { local.longitudeOf(points[it]) }
    }

    /** How many points the junction's fit holds; 0 without a junction. */
    val points: Int get() = latitudes.size

    /** No junction: the drive ended, the car was put somewhere else, or the lane zoom is Default. */
    fun clear() {
        key = null
        latitudes = DoubleArray(0)
        longitudes = DoubleArray(0)
    }

    /**
     * The closest zoom at which every point of the junction lies inside the following box with the car at [latitude],
     * [longitude] on its row, the view turned to [bearing] ([JunctionFit.zoomFor]); infinity where no point limits it,
     * not a number without a junction. Each point ahead counts [lagMeters] farther: the camera's center may trail the
     * moving car by that much, and the box's top is that much nearer to the car then.
     */
    fun zoomAt(
        latitude: Double,
        longitude: Double,
        bearing: Double,
        pitch: Double,
        mapHeightPx: Double,
        carRowPx: Double,
        rowPx: Double,
        halfWidthPx: Double,
        pixelRatio: Double,
        lagMeters: Double = 0.0,
    ): Double {
        if (latitudes.isEmpty()) return Double.NaN
        val car = LocalMeters(latitude, longitude)
        val heading = Math.toRadians(bearing)
        val sinH = sin(heading)
        val cosH = cos(heading)
        var zoom = Double.POSITIVE_INFINITY
        for (i in latitudes.indices) {
            val p = car.toLocal(latitudes[i], longitudes[i])
            val ahead = p.x * sinH + p.y * cosH
            val side = p.x * cosH - p.y * sinH
            val fromCamera = if (ahead >= 0.0) ahead + lagMeters else ahead
            zoom = min(zoom, JunctionFit.zoomFor(fromCamera, side, pitch, mapHeightPx, carRowPx, rowPx, halfWidthPx, pixelRatio, latitude))
        }
        return zoom
    }
}
