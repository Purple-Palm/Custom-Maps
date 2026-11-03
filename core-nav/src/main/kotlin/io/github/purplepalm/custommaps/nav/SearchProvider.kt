package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.model.GeoPoint

/** Address search. */
interface SearchProvider {
    /** Results for [query], ranked closer to [proximity] when it is known. */
    suspend fun search(query: String, proximity: GeoPoint?): List<SearchResult>
}

data class SearchResult(
    val name: String,
    val address: String?,
    val point: GeoPoint,
)
