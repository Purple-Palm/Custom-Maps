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

    /** The route line's bottom layer; every other layer of the route line, its other routes included, lies above it. */
    const val ROUTE_LINE_BOTTOM = RouteLayerConstants.BOTTOM_LEVEL_ROUTE_LINE_LAYER_ID

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

    /**
     * Whether the app's own lanes lie as the design orders them in [ids], from first drawn to last: [under], the drawing's
     * layers under the route, in their order and all below the navigation SDK's route line (its bottom layer); [over],
     * the lane arrows and their edge, in their order, above the SDK's route line and above every layer of the app's own
     * route line ([ownLine]) that is there, and below every layer of the maneuver arrow. Null when the lanes' layers are
     * not in the style.
     */
    fun lanesInPlace(ids: List<String>, under: List<String>, over: List<String>, ownLine: List<String>): Boolean? {
        val low = under.map { ids.indexOf(it) }
        val high = over.map { ids.indexOf(it) }
        if (low.any { it < 0 } || high.any { it < 0 }) return null
        val lineBottom = ids.indexOf(ROUTE_LINE_BOTTOM)
        val lineTop = ids.indexOf(ROUTE_LINE_TOP)
        val ownTop = ownLine.map { ids.indexOf(it) }.maxOrNull() ?: -1
        val lowestArrow = ids.indexOfFirst { it.startsWith(ARROW_PREFIX) }
        val ordered = low.zipWithNext().all { (below, above) -> above > below } && high.zipWithNext().all { (below, above) -> above > below }
        return ordered &&
            lineBottom >= 0 && low.last() < lineBottom &&
            lineTop >= 0 && high.first() > maxOf(lineTop, ownTop) &&
            (lowestArrow < 0 || lowestArrow > high.last())
    }
}
