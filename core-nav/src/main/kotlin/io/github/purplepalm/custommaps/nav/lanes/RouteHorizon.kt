package io.github.purplepalm.custommaps.nav.lanes

/**
 * The electronic horizon along the route the drive is on, as its newest update laid it out ([HorizonAhead]), with the
 * stretches the updates before it laid behind the car kept ([HorizonAhead.after]): held on the main thread for whatever
 * reads the road from it. The drawing of the app's own lanes reads the lane counts between lane points from it, where they
 * agree with the route's lists; and where the route's own data says nothing of a street of the route, whether it is one-way
 * and what class it is ([oneWayAt], [roadClassAt]), a source for the streets the app draws (the design's 3.3). Forgotten at
 * a new route, when the app stops listening to the horizon and at a drive's end; nothing of it is stored.
 */
class RouteHorizon {

    /** The newest update's stretches, with those kept behind the car; null before the first of the route and after [clear]. */
    var ahead: HorizonAhead? = null
        private set

    /**
     * The newest update, [newer]: it takes the place of what was held, with the stretches of what was held behind the car
     * kept where both are of one route and meet ([HorizonAhead.after]). A copy of a few dozen stretches, once a second.
     */
    fun take(newer: HorizonAhead) {
        ahead = ahead?.let { newer.after(it) } ?: newer
    }

    /** The stretches held for the route [routeId]; null for any other route and while none are held. */
    fun aheadOf(routeId: String?): HorizonAhead? = ahead?.takeIf { routeId != null && it.routeId == routeId }

    /** Whether the horizon's edge at [station] of the route [routeId] is one-way; null where it holds none there. */
    fun oneWayAt(routeId: String?, station: Double): Boolean? = aheadOf(routeId)?.at(station)?.edge?.oneWay

    /** The functional class of the horizon's edge at [station] of the route [routeId]; null where it holds none there. */
    fun roadClassAt(routeId: String?, station: Double): HorizonRoadClass? = aheadOf(routeId)?.at(station)?.edge?.roadClass

    /** Nothing held any more. */
    fun clear() {
        ahead = null
    }
}
