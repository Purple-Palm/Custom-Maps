package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.navigation.ui.maps.route.RouteLayerConstants

/**
 * The order of the route's own layers in a slot of the style, from the layer drawn first to the
 * one drawn last, as the debug log shows it once per style load.
 *
 * The maneuver arrow is meant to lie on top of the route line and trace the turn over it. The
 * arrow's layers go above the route line's top layer only if that layer exists when the arrow
 * makes its layers; made the other way round, the route line covers the arrow and only its head
 * shows at the sides. Plain lists of layer ids: no map needed.
 */
internal object LayerOrder {

    /** The route line's topmost layer; every other layer of the route line lies below it. */
    const val ROUTE_LINE_TOP = RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID

    /** What the ids of the maneuver arrow's layers start with: its shaft, its head and their casings. */
    const val ARROW_PREFIX = "mapbox-navigation-arrow-"

    /**
     * Whether every layer of the maneuver arrow is drawn after the route line's top layer, in
     * [ids] from first drawn to last. Null when the style has no route line or no arrow yet.
     */
    fun arrowAboveLine(ids: List<String>): Boolean? {
        val lineTop = ids.indexOf(ROUTE_LINE_TOP)
        val lowestArrow = ids.indexOfFirst { it.startsWith(ARROW_PREFIX) }
        if (lineTop < 0 || lowestArrow < 0) return null
        return lowestArrow > lineTop
    }

    /**
     * Whether the app's own route line ([ownLayers], its casing and its line, the casing first) lies as it should in
     * [ids], from first drawn to last: above the navigation SDK's route line, whose primary it replaces, the line above
     * its casing, and every layer of the maneuver arrow above both. Null when the own line is not in the style.
     */
    fun ownLineInPlace(ids: List<String>, ownLayers: List<String>): Boolean? {
        val own = ownLayers.map { ids.indexOf(it) }
        if (own.any { it < 0 }) return null
        val lineTop = ids.indexOf(ROUTE_LINE_TOP)
        val lowestArrow = ids.indexOfFirst { it.startsWith(ARROW_PREFIX) }
        val ordered = own.zipWithNext().all { (below, above) -> above > below }
        return ordered && lineTop in 0 until own.first() && (lowestArrow < 0 || lowestArrow > own.last())
    }
}
