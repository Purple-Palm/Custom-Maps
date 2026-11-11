package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.log2
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
     * The ground from the car to the row, in dp at the camera's center. With the camera f =
     * 0.5 H / tan(fov / 2) pixels from the screen, a row v pixels above the car is seen at
     * a = atan(v / f) above the view's axis, and meets the ground f (cos p tan(p + a) - sin p)
     * pixels ahead at a tilt p.
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
 * The highest speed of the last [CameraTuning.laneCapSpeedSeconds], which the lanes' look-ahead
 * floor is worked out for: the view goes out at once when the car speeds up, and back in only
 * after the speed has stayed lower that long, so a speed that swings in traffic does not swing the
 * zoom. Fed once per progress update.
 */
class PeakSpeed(private val tuning: CameraTuning) {
    private var times = DoubleArray(INITIAL_CAPACITY)
    private var speeds = DoubleArray(INITIAL_CAPACITY)
    private var size = 0

    /**
     * @param speedMps the car's speed now; one that is missing, not more than 0 or not a number
     *   adds nothing
     * @return the highest speed of the last [CameraTuning.laneCapSpeedSeconds], this one
     *   included; null when there was none
     */
    fun onSpeed(nowSeconds: Double, speedMps: Double?): Double? {
        var kept = 0
        for (i in 0 until size) {
            val age = nowSeconds - times[i]
            if (age >= 0.0 && age <= tuning.laneCapSpeedSeconds) {
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
