package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min

/**
 * A junction's own fit, for a camera anchored on the car ([CameraTuning.anchored]) that frames its junctions itself in
 * place of the navigation SDK's frame of a maneuver: the closest zoom at which the junction's points lie inside the
 * following box with the car on its row, the box's bottom edge, at the camera's tilt.
 *
 * How far ahead of the car a point lies, along the view's bearing, has to reach no higher than the box's top, in the
 * model of [LookAhead]. How far to its side has to stay within the box's half-width at the scale of the car's row. The
 * perspective draws a point farther ahead closer to the car's column than that, so the side is held with room to spare,
 * and as the car comes up to the junction the side stays the same while the road ahead shortens: the fit only comes
 * closer on the way in. A point behind the car's row cannot be shown with the car on its row, and limits nothing, as
 * the SDK leaves such points out of its frames. Plain numbers, once per progress update.
 */
object JunctionFit {

    /**
     * The road past a maneuver point that its fit holds: the first [CameraTuning.largeMetersAfterManeuver] of [road], and
     * where the road bends [CameraTuning.bendMinMeters] or more past the point ([TurnBend]), as far past the bend: the
     * points the junction's frame holds on the phone with Large ([JunctionFrame]). The maneuver point comes first; a road
     * without points has none.
     *
     * @param road the step after the maneuver, from its maneuver point on, in local meters
     * @param approachBearing the compass bearing the car comes in on; without it no bend is looked for
     */
    fun road(road: List<LocalPoint>, approachBearing: Double?, tuning: CameraTuning): List<LocalPoint> {
        if (road.isEmpty()) return road
        val bend = approachBearing?.let {
            TurnBend.bend(road, it, tuning.bendDegrees, tuning.bendSideMeters, tuning.bendSearchMeters, tuning.bendMinMeters)
        }
        val past = if (bend != null && bend.meters >= tuning.bendMinMeters) bend.meters else 0.0
        return LocalLine.head(road, past + tuning.largeMetersAfterManeuver)
    }

    /**
     * The closest zoom at which a point [aheadMeters] ahead of the car along the view's bearing and [sideMeters] to its
     * side, either way, lies inside the box: below the row [rowPx] on a map [mapHeightPx] high with the car on the row
     * [carRowPx] at a tilt of [pitchDegrees], and no farther from the car's column than [halfWidthPx] at the scale of the
     * car's row, at [pixelRatio] pixels a dp. Infinity where the point limits no zoom: behind the car's row, on the car,
     * and wherever the model has no answer.
     */
    fun zoomFor(
        aheadMeters: Double,
        sideMeters: Double,
        pitchDegrees: Double,
        mapHeightPx: Double,
        carRowPx: Double,
        rowPx: Double,
        halfWidthPx: Double,
        pixelRatio: Double,
        latitude: Double,
    ): Double {
        if (!(aheadMeters >= 0.0) || sideMeters.isNaN() || !(mapHeightPx > 0.0)) return NO_LIMIT
        val ahead = LookAhead.zoomFor(aheadMeters, pitchDegrees, mapHeightPx, carRowPx, rowPx, pixelRatio, latitude)
        return min(ahead, sideZoom(abs(sideMeters), halfWidthPx, pixelRatio, latitude))
    }

    /** The closest zoom at which [sideMeters] are no more than [halfWidthPx] at the scale of the car's row. */
    private fun sideZoom(sideMeters: Double, halfWidthPx: Double, pixelRatio: Double, latitude: Double): Double {
        if (!(sideMeters > 0.0) || !(halfWidthPx > 0.0) || !(pixelRatio > 0.0)) return NO_LIMIT
        val zoom = log2(MapScale.metersPerDp(0.0, latitude) * halfWidthPx / pixelRatio / sideMeters)
        return if (zoom.isNaN()) NO_LIMIT else zoom
    }

    private const val NO_LIMIT = Double.POSITIVE_INFINITY
}
