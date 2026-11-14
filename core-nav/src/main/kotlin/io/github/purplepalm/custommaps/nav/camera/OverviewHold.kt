package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/** One overview fit in plain numbers: its center, its zoom and the padding of its four sides, in pixels. North is up. */
data class OverviewFit(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val top: Double,
    val left: Double,
    val bottom: Double,
    val right: Double,
)

/**
 * Keeps the overview still while the car drives along the route it shows (Smooth follow): the camera
 * keeps the fit it shows while a new one still lies inside it and is less than [REFIT_ZOOM] closer. The
 * rest of a route only gets shorter as the car drives, so a fit made earlier still holds all of it; the
 * map stands, only the car moves, and every few minutes the overview glides once to a closer fit.
 * A fit wider by more than [ZOOM_SLACK] (the car off the route, a new route, a route offered) or one
 * whose center has left the kept view is taken at once, and so is any change of the padding.
 */
object OverviewHold {

    /** A new fit this much closer is worth a glide: the rest of the route would be a fifth larger on the screen. */
    const val REFIT_ZOOM = 0.25

    /** A fit wider by no more than this is the same fit. */
    const val ZOOM_SLACK = 0.02

    /** A new fit may reach this share of half the view past where it would still lie inside the kept one. */
    const val EDGE_SLACK = 0.03

    /** Padding that differs by no more than this is the same, in pixels. */
    const val PADDING_SLACK_PX = 0.5

    /** One degree of latitude on the ground. */
    private const val METERS_PER_DEGREE = 111_319.49

    /**
     * Whether the camera keeps [kept], the overview it shows, now that the navigation SDK fitted [fit].
     * Inside is tested with the two centers and zooms: a frame smaller by the factor 2^-Δzoom may sit as
     * much off center as that leaves room for, plus [EDGE_SLACK] of half the view. A value that is not a
     * number, or a map with no room, keeps nothing.
     *
     * @param mapWidthPx the map's size in pixels, as are the paddings
     * @param pixelRatio pixels per dp
     */
    fun keeps(kept: OverviewFit, fit: OverviewFit, mapWidthPx: Double, mapHeightPx: Double, pixelRatio: Double): Boolean {
        // Each test is written so that a value that is not a number fails it.
        if (!(samePadding(kept.top, fit.top) && samePadding(kept.left, fit.left) &&
                samePadding(kept.bottom, fit.bottom) && samePadding(kept.right, fit.right))
        ) {
            return false
        }
        val zoomIn = fit.zoom - kept.zoom
        if (!(zoomIn >= -ZOOM_SLACK && zoomIn < REFIT_ZOOM)) return false
        val halfWidth = (mapWidthPx - kept.left - kept.right) / 2.0
        val halfHeight = (mapHeightPx - kept.top - kept.bottom) / 2.0
        val metersPerPixel = MapScale.metersPerDp(kept.zoom, kept.lat) / pixelRatio
        if (!(halfWidth > 0.0 && halfHeight > 0.0 && metersPerPixel > 0.0 && metersPerPixel.isFinite())) return false
        val east = abs(Angles.wrap180(fit.lon - kept.lon)) * METERS_PER_DEGREE * cos(Math.toRadians(kept.lat))
        val north = abs(fit.lat - kept.lat) * METERS_PER_DEGREE
        val room = 1.0 - 2.0.pow(-max(zoomIn, 0.0)) + EDGE_SLACK
        return east / (halfWidth * metersPerPixel) <= room && north / (halfHeight * metersPerPixel) <= room
    }

    private fun samePadding(a: Double, b: Double): Boolean = abs(a - b) <= PADDING_SLACK_PX
}
