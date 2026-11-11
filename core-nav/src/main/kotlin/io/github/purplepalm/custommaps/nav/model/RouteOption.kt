package io.github.purplepalm.custommaps.nav.model

import kotlin.time.Duration

/** One of the routes offered for a destination. */
data class RouteOption(
    val id: String,
    val duration: Duration,
    val distance: Distance,
    /** Main roads of the route, for example "Highway A South, Oak Street". May be empty. */
    val summary: String,
    /**
     * How far the route's last point is from the place itself, when both are known. A route ends
     * on a road, at the place's stop point or where the road is nearest to the place, so this is
     * seldom zero; a large value without a stop point means the route may end on another side of
     * the place than the driver expects.
     */
    val endGap: Distance? = null,
)
