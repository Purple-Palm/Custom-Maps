package io.github.purplepalm.custommaps.nav.model

import kotlin.time.Duration

/** One of the routes offered for a destination. */
data class RouteOption(
    val id: String,
    val duration: Duration,
    val distance: Distance,
    /** Main roads of the route, for example "I-280 S, CA-85 S". May be empty. */
    val summary: String,
)
