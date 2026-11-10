package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineScaleExpressions
import kotlin.math.pow

/**
 * How wide the route line is drawn at each zoom.
 *
 * The navigation SDK's line grows with the zoom: close in, as the Large lane zoom goes, its casing
 * is about as wide as a lane. On a plain road that is fine. Over lane details it would cover the
 * markings of the lane under it and look like a claim about which lane to drive in. So while lane
 * details are on, the line keeps the width it has at [HOLD_ZOOM] for every closer zoom, and reads
 * as a guide along the road with the markings visible beside it. Up to that zoom nothing changes.
 */
internal object RouteLineWidths {

    /** The width of a line at a zoom, in the units of the SDK's own stops. */
    class Stop(val zoom: Double, val width: Double)

    /** From here on in, the line is no wider while lane details are on: where the SDK's casing has a stop of its own. */
    const val HOLD_ZOOM = 16.5

    /** Between two stops the width grows by this factor for each zoom level, as in the SDK's expressions. */
    private const val BASE = 1.5

    // The SDK's own stops (RouteLineScaleExpressions.Builder in ui-maps 3.34), for the route, its
    // traffic colors and the alternatives alike, and for their casings.
    val LINE = listOf(Stop(4.0, 3.0), Stop(10.0, 4.0), Stop(13.0, 6.0), Stop(16.0, 10.0), Stop(19.0, 14.0), Stop(22.0, 18.0))
    val CASING = listOf(Stop(10.0, 7.0), Stop(14.0, 10.5), Stop(16.5, 15.5), Stop(19.0, 24.0), Stop(22.0, 29.0))

    /** The SDK's widths, as it has them without being told. */
    val sdk: RouteLineScaleExpressions by lazy { RouteLineScaleExpressions.Builder().build() }

    /** The SDK's widths up to [HOLD_ZOOM], and the width of that zoom from there on in. */
    val heldCloseIn: RouteLineScaleExpressions by lazy {
        val line = expression(heldFrom(LINE, HOLD_ZOOM))
        val casing = expression(heldFrom(CASING, HOLD_ZOOM))
        RouteLineScaleExpressions.Builder()
            .routeLineScaleExpression(line)
            .routeTrafficLineScaleExpression(line)
            .routeCasingLineScaleExpression(casing)
            .alternativeRouteLineScaleExpression(line)
            .alternativeRouteTrafficLineScaleExpression(line)
            .alternativeRouteCasingLineScaleExpression(casing)
            .build()
    }

    /** The width at [zoom] by [stops]: the first or last stop's outside them, and between two stops a curve of [BASE]. */
    fun widthAt(stops: List<Stop>, zoom: Double): Double {
        if (zoom <= stops.first().zoom) return stops.first().width
        if (zoom >= stops.last().zoom) return stops.last().width
        val above = stops.indexOfFirst { it.zoom > zoom }
        val from = stops[above - 1]
        val to = stops[above]
        val share = (BASE.pow(zoom - from.zoom) - 1.0) / (BASE.pow(to.zoom - from.zoom) - 1.0)
        return from.width + (to.width - from.width) * share
    }

    /**
     * [stops] as they are below [zoom], then the width at [zoom] for every closer zoom. The curve
     * below [zoom] stays what it was: a part of a curve of [BASE] is such a curve itself.
     */
    fun heldFrom(stops: List<Stop>, zoom: Double): List<Stop> {
        val held = widthAt(stops, zoom)
        return stops.filter { it.zoom < zoom } + Stop(zoom, held) + Stop(stops.last().zoom.coerceAtLeast(zoom + 1.0), held)
    }

    /** [stops] as a style expression, in the form the SDK builds its own. */
    fun expression(stops: List<Stop>): Expression = Expression.interpolate {
        exponential { literal(BASE) }
        zoom()
        stops.forEach { stop(it.zoom, it.width) }
    }
}
