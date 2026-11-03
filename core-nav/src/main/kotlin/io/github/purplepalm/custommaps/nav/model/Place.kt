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
    val point: GeoPoint,
)
