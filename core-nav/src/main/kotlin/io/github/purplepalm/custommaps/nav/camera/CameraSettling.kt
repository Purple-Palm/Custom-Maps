package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.debug.Motion
import kotlin.math.abs

/**
 * How far a camera that moves on its own springs still is from where it is going, and how fast it
 * still moves there: zoom levels, degrees of tilt and of bearing, and pixels of padding. Read now and
 * then after a move between modes, such as the flight into the driving view at Start, to tell when
 * the move has come to rest; the line that says a move ended comes when its path is done, while the
 * springs still have some way to go.
 */
data class CameraSettling(
    val zoomGap: Double,
    val zoomSpeed: Double,
    val pitchGap: Double,
    val pitchSpeed: Double,
    val bearingGap: Double,
    val bearingSpeed: Double,
    val paddingGap: Double,
) {

    /**
     * Whether the move is over: zoom, tilt, bearing and padding are on their targets and hardly
     * move, within what no one sees. A camera that follows the car through a curve keeps turning,
     * and is not at rest by this measure while it turns.
     */
    val atRest: Boolean
        get() = abs(zoomGap) < ZOOM_REST && abs(zoomSpeed) < ZOOM_REST * PER_SECOND &&
            abs(pitchGap) < ANGLE_REST && abs(pitchSpeed) < ANGLE_REST * PER_SECOND &&
            abs(bearingGap) < ANGLE_REST && abs(bearingSpeed) < ANGLE_REST * PER_SECOND &&
            abs(paddingGap) < PADDING_REST

    /** The gaps, rounded for the debug log. */
    fun gapFields(): Map<String, Any?> = mapOf(
        "zoomGap" to Motion.round(abs(zoomGap), 3),
        "pitchGap" to Motion.round(abs(pitchGap), 2),
        "bearingGap" to Motion.round(abs(bearingGap), 2),
        "paddingGap" to Motion.round(abs(paddingGap), 1),
    )

    companion object {
        /** A hundredth of a zoom level: lanes less than one percent wider or narrower. */
        const val ZOOM_REST = 0.01

        /** Half a degree of tilt or bearing. */
        const val ANGLE_REST = 0.5

        /** One pixel of padding. */
        const val PADDING_REST = 1.0

        /** A speed below four times a gap a second counts as still. */
        private const val PER_SECOND = 4.0
    }
}
