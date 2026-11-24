package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * How much road ahead of the car the guidance camera shows, in a model of its perspective: the
 * Maps SDK's default vertical field of view, the camera's principal point on the car's row (the
 * bottom edge of the padding, where the navigation SDK puts the car), and the ground met by the
 * ray through a screen row. A model, not the map's own transform: the camera line of the debug
 * log gives its answer, to be checked against the screen.
 *
 * The lanes' close view goes no closer than the zoom that still shows a few seconds of driving
 * above the car ([zoomFor], [ZoomPlanner.target]). Meters and lane widths do not depend on the
 * latitude here; only the zoom that gives them does. Plain numbers, once per progress update.
 */
object LookAhead {

    /** The Maps SDK's default vertical field of view, 36.87 degrees: 2 atan(1/3). */
    val FIELD_OF_VIEW_RADIANS: Double = 2.0 * atan(1.0 / 3.0)

    /**
     * Ground meters from the car to the screen row [rowPx], at [zoom] and a tilt of
     * [pitchDegrees], 0 being straight down. Infinity where the row looks at or above the horizon,
     * 0 where it is not above the car's row, and not a number where the screen is not known.
     *
     * @param mapHeightPx the map's height, in pixels
     * @param carRowPx the car's row, in pixels from the map's top: the map's height less the bottom padding
     * @param rowPx the row to reach, in pixels from the map's top, for example the top padding
     * @param pixelRatio pixels per dp
     * @param latitude where the camera is
     */
    fun metersAhead(
        zoom: Double,
        pitchDegrees: Double,
        mapHeightPx: Double,
        carRowPx: Double,
        rowPx: Double,
        pixelRatio: Double,
        latitude: Double,
    ): Double = groundDp(pitchDegrees, mapHeightPx, carRowPx, rowPx, pixelRatio) * MapScale.metersPerDp(zoom, latitude)

    /**
     * The closest zoom at which the row [rowPx] still lies [meters] ahead of the car, the inverse
     * of [metersAhead]: the lanes can go up to it and still show that much road. Infinity, which
     * caps nothing, where [meters] is not more than 0 or not a number, where the screen is not
     * known or leaves no map between the car and the row, and where the row looks at or above the
     * horizon.
     */
    fun zoomFor(
        meters: Double,
        pitchDegrees: Double,
        mapHeightPx: Double,
        carRowPx: Double,
        rowPx: Double,
        pixelRatio: Double,
        latitude: Double,
    ): Double {
        if (!(meters > 0.0)) return NO_CAP
        val dp = groundDp(pitchDegrees, mapHeightPx, carRowPx, rowPx, pixelRatio)
        if (!(dp > 0.0) || dp == Double.POSITIVE_INFINITY) return NO_CAP
        val zoom = log2(MapScale.metersPerDp(0.0, latitude) * dp / meters)
        return if (zoom.isNaN()) NO_CAP else zoom
    }

    /**
     * The lowest tilt at which the row [rowPx] still lies [meters] or more ahead of the car at [zoom], the inverse of
     * [metersAhead] in the tilt: tilted less, a point [meters] ahead leaves the screen above that row. 0 where such a
     * point shows at every tilt; not a number where the screen is not known or [meters] is not more than 0. A point
     * ahead of a row r pixels above the car's is f sin(a) / cos(p + a) pixels ahead ([groundDp]), so the tilt is
     * acos(f sin(a) / its pixels) - a.
     *
     * @param carRowPx the row the camera's centre is on, in pixels from the map's top
     */
    fun pitchFor(
        meters: Double,
        zoom: Double,
        mapHeightPx: Double,
        carRowPx: Double,
        rowPx: Double,
        pixelRatio: Double,
        latitude: Double,
    ): Double {
        if (!(meters > 0.0) || !(mapHeightPx > 0.0) || !(pixelRatio > 0.0) || carRowPx.isNaN() || rowPx.isNaN()) return Double.NaN
        val above = carRowPx - rowPx
        if (above <= 0.0) return Double.NaN
        val f = 0.5 * mapHeightPx / tan(FIELD_OF_VIEW_RADIANS / 2.0)
        val ray = atan(above / f)
        val pointPx = meters / MapScale.metersPerDp(zoom, latitude) * pixelRatio
        val cosine = f * sin(ray) / pointPx
        if (cosine.isNaN()) return Double.NaN
        if (cosine >= 1.0) return 0.0
        return max(0.0, Math.toDegrees(acos(cosine) - ray))
    }

    /**
     * The ground from the car to the row, in dp at the camera's center. With the camera f =
     * 0.5 H / tan(fov / 2) pixels from the screen, a row v pixels above the car is seen at
     * a = atan(v / f) above the view's axis, and meets the ground f (cos p tan(p + a) - sin p)
     * pixels ahead at a tilt p, which is f sin(a) / cos(p + a).
     */
    private fun groundDp(pitchDegrees: Double, mapHeightPx: Double, carRowPx: Double, rowPx: Double, pixelRatio: Double): Double {
        if (!(mapHeightPx > 0.0) || !(pixelRatio > 0.0) || pitchDegrees.isNaN() || carRowPx.isNaN() || rowPx.isNaN()) return Double.NaN
        val above = carRowPx - rowPx
        if (above <= 0.0) return 0.0
        val f = 0.5 * mapHeightPx / tan(FIELD_OF_VIEW_RADIANS / 2.0)
        val pitch = Math.toRadians(pitchDegrees)
        val ray = atan(above / f)
        if (pitch + ray >= PI / 2.0) return Double.POSITIVE_INFINITY
        return f * (cos(pitch) * tan(pitch + ray) - sin(pitch)) / pixelRatio
    }

    private const val NO_CAP = Double.POSITIVE_INFINITY
}

/**
 * The road view of a camera anchored on the car ([CameraTuning.anchored]): the zoom at which
 * [CameraTuning.roadAheadSeconds] of driving at the highest recent speed ([PeakSpeed] over
 * [CameraTuning.roadPeakSeconds]) lies between the car and the top of the following box, in the
 * model of [LookAhead] at the car's tilt ([CameraTuning.cruisePitch]) and on the map's own pixel
 * ratio: close in town, farther out with speed. Never less road than
 * [CameraTuning.roadMinAheadMeters], at a stop, a crawl or without a speed, and never more than
 * [CameraTuning.roadMaxAheadMeters]. On the car it takes the place of the navigation SDK's fit of
 * the road, which frames about a kilometre there. Plain numbers, once per progress update.
 */
object RoadView {

    /**
     * How much road the view shows above the car at [peakSpeedMps]: a speed that is missing, not
     * more than 0 or not a finite number counts as standing.
     */
    fun aheadMeters(tuning: CameraTuning, peakSpeedMps: Double?): Double {
        val speed = if (peakSpeedMps != null && peakSpeedMps > 0.0 && peakSpeedMps < Double.POSITIVE_INFINITY) peakSpeedMps else 0.0
        return (speed * tuning.roadAheadSeconds).coerceIn(tuning.roadMinAheadMeters, tuning.roadMaxAheadMeters)
    }

    /**
     * The road view's zoom at [peakSpeedMps] ([aheadMeters]), on a map [mapHeightPx] high whose
     * following padding puts the car on the row [carRowPx] and the box's top on the row [rowPx],
     * at [pixelRatio] pixels a dp, seen at a tilt of [pitchDegrees] ([pitch]); no closer than
     * [CameraTuning.maxZoom]. Not a number where the screen is not known or leaves no map between
     * the car and the row: the camera then keeps the zoom it has.
     */
    fun zoom(
        tuning: CameraTuning,
        peakSpeedMps: Double?,
        mapHeightPx: Double,
        carRowPx: Double,
        rowPx: Double,
        pixelRatio: Double,
        latitude: Double,
        pitchDegrees: Double = tuning.cruisePitch,
    ): Double {
        val zoom = LookAhead.zoomFor(aheadMeters(tuning, peakSpeedMps), pitchDegrees, mapHeightPx, carRowPx, rowPx, pixelRatio, latitude)
        return if (zoom.isFinite()) min(zoom, tuning.maxZoom) else Double.NaN
    }

    /**
     * The tilt the road view is worked out at: the car's own ([CameraTuning.cruisePitch]), but while a junction view from
     * above ([JunctionView.ABOVE]) goes down or stays down (the focus's goal 1) the tilt of the frame, the one the camera
     * goes to then, so the same road stays between the car and the box's top as the camera looks down. On the way back
     * up, the car's tilt the view comes back to. A frame without a tilt counts as the car's.
     *
     * @param focusGoal [ManeuverFocus.goal]
     * @param framePitch the tilt of the data source's frame, which the camera told it ([guidancePitch] a sample ahead)
     */
    fun pitch(tuning: CameraTuning, view: JunctionView, focusGoal: Double, framePitch: Double?): Double =
        if (tuning.anchored && view == JunctionView.ABOVE && focusGoal >= 1.0 && framePitch != null && !framePitch.isNaN()) framePitch else tuning.cruisePitch
}

/**
 * The highest speed of the last [windowSeconds]: the lanes' look-ahead floor is worked out for it
 * over [CameraTuning.laneCapSpeedSeconds], and the car's road view ([RoadView]) over
 * [CameraTuning.roadPeakSeconds]. The view goes out at once when the car speeds up, and back in
 * only after the speed has stayed lower that long, so a speed that swings in traffic does not
 * swing the zoom. Fed once per progress update.
 */
class PeakSpeed(private val windowSeconds: Double) {

    /** The lanes' look-ahead floor's: over [CameraTuning.laneCapSpeedSeconds]. */
    constructor(tuning: CameraTuning) : this(tuning.laneCapSpeedSeconds)

    private var times = DoubleArray(INITIAL_CAPACITY)
    private var speeds = DoubleArray(INITIAL_CAPACITY)
    private var size = 0

    /**
     * @param speedMps the car's speed now; one that is missing, not more than 0 or not a number
     *   adds nothing
     * @return the highest speed of the last [windowSeconds], this one included; null when there
     *   was none
     */
    fun onSpeed(nowSeconds: Double, speedMps: Double?): Double? {
        var kept = 0
        for (i in 0 until size) {
            val age = nowSeconds - times[i]
            if (age >= 0.0 && age <= windowSeconds) {
                times[kept] = times[i]
                speeds[kept] = speeds[i]
                kept++
            }
        }
        size = kept
        if (speedMps != null && speedMps > 0.0 && speedMps < Double.POSITIVE_INFINITY && !nowSeconds.isNaN()) {
            if (size == times.size) {
                times = times.copyOf(size * 2)
                speeds = speeds.copyOf(size * 2)
            }
            times[size] = nowSeconds
            speeds[size] = speedMps
            size++
        }
        if (size == 0) return null
        var peak = speeds[0]
        for (i in 1 until size) if (speeds[i] > peak) peak = speeds[i]
        return peak
    }

    /** Guidance ended or a new drive begins: no speed is remembered. */
    fun reset() {
        size = 0
    }

    private companion object {
        /** One speed a second for a little over ten seconds; it grows when updates come faster. */
        const val INITIAL_CAPACITY = 16
    }
}
