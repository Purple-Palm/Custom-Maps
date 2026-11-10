package io.github.purplepalm.custommaps.nav.model

/** A search suggestion: an address or a place, not yet resolved to coordinates. */
data class PlaceSuggestion(
    val id: String,
    val name: String,
    val description: String?,
    /** Straight-line distance from the current position, when known. */
    val distance: Distance?,
)

/** A resolved destination. */
data class Place(
    val name: String,
    val address: String?,
    /** The place itself, such as the building: its pin, and the side of the road it is on at arrival. */
    val point: GeoPoint,
    /**
     * Where a car should stop for the place, when the search service gives one: the parking lot,
     * say, or the curb in front. Routes end here.
     */
    val stopPoint: GeoPoint? = null,
    /** The search provider's id of the place, when it has one; the same place gets the same id every time. */
    val providerId: String? = null,
) {
    /** Where routes are requested to: the stop point when there is one, otherwise the place itself. */
    val routePoint: GeoPoint get() = stopPoint ?: point
}
