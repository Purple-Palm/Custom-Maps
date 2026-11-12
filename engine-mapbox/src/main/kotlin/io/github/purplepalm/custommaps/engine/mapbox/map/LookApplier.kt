package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap

/**
 * When a look the map is asked for reaches each part of the map: the basemap's config, its theme,
 * and the route line's colours. It writes nothing itself; it says what to write, and when.
 *
 * A style load reports the style's data first (the STYLE event) and is done later, when the
 * style-load callback runs. The basemap's config can be written from the first, before any tile is
 * drawn; the route line's layers exist only from the second. So a request made:
 * - before the style's data is kept, and the style's data writes the whole look once;
 * - between the two goes to the basemap at once, and to the route line at the style load;
 * - after the style load goes everywhere at once.
 * The same request again writes nothing.
 *
 * The theme is the one write that draws all of the basemap's tiles again. A style's data takes the
 * look's own theme, whatever the request says: its tiles are new anyway. Later a request whose
 * theme differs from its look ([LookOnMap.theme]) keeps the theme the basemap shows, and the theme
 * follows only with a request that lets it go. The route line keeps its colours across style
 * loads; it is made with the look of the first request. Main thread only.
 */
internal class LookApplier(request: LookOnMap) {

    /** What to write now: every key of [basemap]'s column but the theme, the theme of [theme]'s, the route line's colours of [routeLine]; null for none. */
    data class Writes(val basemap: Look? = null, val theme: Look? = null, val routeLine: Look? = null) {
        companion object {
            val NONE = Writes()
        }
    }

    /** The latest request. */
    var request: LookOnMap = request
        private set

    private var styleData = false
    private var styleLoaded = false

    /** The look the basemap shows, theme aside; null before the style's data in this style load. */
    var basemapLook: Look? = null
        private set

    /** The theme the basemap shows; null before the style's data in this style load. */
    var themeLook: Look? = null
        private set

    /** The look of the route line's colours; the map makes its route line with the first request's. */
    var routeLineLook: Look = request.look
        private set

    /** A style load begins: nothing of the basemap is written for it yet. */
    fun onStyleLoading() {
        styleData = false
        styleLoaded = false
        basemapLook = null
        themeLook = null
    }

    /** The style's data is there (STYLE): the whole look of the request, with the look's own theme. */
    fun onStyleData(): Writes {
        styleData = true
        basemapLook = request.look
        themeLook = request.look
        return Writes(basemap = request.look, theme = request.look)
    }

    /** The style has loaded: what the request still needs, the basemap only if it changed meanwhile. */
    fun onStyleLoaded(): Writes {
        styleLoaded = true
        return due()
    }

    /** A new request: what of it can be written now. */
    fun setLook(request: LookOnMap): Writes {
        this.request = request
        return due()
    }

    private fun due(): Writes {
        val look = request.look
        val basemap = if (styleData && basemapLook != look) look else null
        // A theme that waits keeps what the basemap shows; only a whole look writes one.
        val theme = if (styleData && request.theme == look && themeLook != look) look else null
        val routeLine = if (styleLoaded && routeLineLook != look) look else null
        if (basemap != null) basemapLook = basemap
        if (theme != null) themeLook = theme
        if (routeLine != null) routeLineLook = routeLine
        return Writes(basemap, theme, routeLine)
    }
}
