package io.github.purplepalm.custommaps.nav.car

/**
 * The car map's zoom by hand: the zoom buttons of the map's action strip step by [STEP], and neither
 * they nor a pinch take the zoom past [MIN] or [MAX], as in Mapbox's own car camera. Some head units
 * send no pinch at all, which is why the strip has the buttons.
 */
object CarZoom {

    /** One press of a zoom button, in zoom levels. */
    const val STEP = 0.5

    /** The farthest out the driver can zoom: a region. */
    const val MIN = 6.0

    /** The closest in: a few buildings. */
    const val MAX = 20.0

    /** The zoom after a button's [delta] from [zoom]: within the limits, and onto the nearest one from outside them. */
    fun after(zoom: Double, delta: Double): Double = (zoom + delta).coerceIn(MIN, MAX)

    /**
     * Whether a pinch by [scaleFactor] from [zoom] is passed on: the map library zooms it to
     * `zoom - (1 - scaleFactor)`, and one that would end outside the limits is dropped, unless it
     * comes toward them from outside, as from the whole world before the car's first position.
     */
    fun scaleAllowed(zoom: Double, scaleFactor: Double): Boolean {
        val to = zoom - (1.0 - scaleFactor)
        return when {
            to in MIN..MAX -> true
            zoom < MIN -> to > zoom
            zoom > MAX -> to < zoom
            else -> false
        }
    }
}
