package io.github.purplepalm.custommaps.engine.mapbox.search

import com.mapbox.search.ApiType
import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.common.IsoCountryCode
import com.mapbox.search.common.IsoLanguageCode
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.PlaceSuggestion
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Addresses and places through the Mapbox Search Box API, in English and within the US.
 * Results are ranked near the device position, which the search engine reads itself.
 */
internal class MapboxSearchProvider : SearchProvider {

    private val engine: SearchEngine by lazy {
        SearchEngine.createSearchEngineWithBuiltInDataProviders(ApiType.SEARCH_BOX, SearchEngineSettings())
    }

    /** Suggestions of the latest query, so a picked one can be resolved by id. */
    private val latest = mutableMapOf<String, SearchSuggestion>()

    override suspend fun suggest(query: String): List<PlaceSuggestion> {
        if (query.isBlank()) return emptyList()
        val options = SearchOptions(
            countries = listOf(IsoCountryCode.UNITED_STATES),
            languages = listOf(IsoLanguageCode.ENGLISH),
            limit = SUGGESTION_LIMIT,
        )
        val suggestions = suspendCancellableCoroutine { continuation ->
            val task = engine.search(
                query,
                options,
                object : SearchSuggestionsCallback {
                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                        if (continuation.isActive) continuation.resume(suggestions)
                    }

                    override fun onError(e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
            )
            continuation.invokeOnCancellation { task.cancel() }
        }
        latest.clear()
        suggestions.forEach { latest[it.id] = it }
        return suggestions.map { suggestion ->
            PlaceSuggestion(
                id = suggestion.id,
                name = suggestion.name,
                description = suggestion.fullAddress ?: suggestion.address?.formattedAddress() ?: suggestion.descriptionText,
                distance = suggestion.distanceMeters?.takeIf { it >= 0 }?.let(Distance::meters),
            )
        }
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): Place? {
        val original = latest[suggestion.id] ?: return null
        return suspendCancellableCoroutine { continuation ->
            val task = engine.select(
                original,
                object : SearchSelectionCallback {
                    override fun onResult(suggestion: SearchSuggestion, result: SearchResult, responseInfo: ResponseInfo) {
                        if (continuation.isActive) continuation.resume(result.toPlace())
                    }

                    override fun onResults(
                        suggestion: SearchSuggestion,
                        results: List<SearchResult>,
                        responseInfo: ResponseInfo,
                    ) {
                        // A category such as "coffee": take the closest match.
                        if (continuation.isActive) continuation.resume(results.firstOrNull()?.toPlace())
                    }

                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onError(e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
            )
            continuation.invokeOnCancellation { task.cancel() }
        }
    }

    private fun SearchResult.toPlace() = Place(
        name = name,
        address = fullAddress ?: address?.formattedAddress(),
        point = GeoPoint(coordinate.latitude(), coordinate.longitude()),
    )

    private companion object {
        const val SUGGESTION_LIMIT = 8
    }
}
