package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.PlaceSuggestion

/** Search for addresses and places, ranked near the current position when it is known. */
interface SearchProvider {
    /** Suggestions while the driver types. */
    suspend fun suggest(query: String): List<PlaceSuggestion>

    /** Coordinates and details of a suggestion the driver picked, or null if it cannot be resolved. */
    suspend fun resolve(suggestion: PlaceSuggestion): Place?
}
