package io.github.purplepalm.custommaps.engine.mapbox.search

import com.mapbox.common.MapboxOptions
import com.mapbox.search.ApiType
import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.SelectOptions
import com.mapbox.search.ServiceProvider
import com.mapbox.search.common.CompletionCallback
import com.mapbox.search.common.SearchRequestException
import com.mapbox.search.common.IsoCountryCode
import com.mapbox.search.common.IsoLanguageCode
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import io.github.purplepalm.custommaps.engine.mapbox.AccessErrors
import io.github.purplepalm.custommaps.nav.AccessDeniedException
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.debug.DebugLog
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
 * No search history is kept: picked places would otherwise pile up in the SDK's history file.
 */
internal class MapboxSearchProvider(private val debugLog: DebugLog) : SearchProvider {

    private var engine: SearchEngine? = null
    private var engineToken: String? = null

    /** Suggestions of the latest query, so a picked one can be resolved by id. */
    private val latest = mutableMapOf<String, SearchSuggestion>()

    override suspend fun suggest(query: String): List<PlaceSuggestion> {
        if (query.isBlank()) return emptyList()
        debugLog.log("search_query", mapOf("query" to query))
        val options = SearchOptions(
            countries = listOf(IsoCountryCode.UNITED_STATES),
            languages = listOf(IsoLanguageCode.ENGLISH),
            limit = SUGGESTION_LIMIT,
        )
        val suggestions = suspendCancellableCoroutine { continuation ->
            val task = engine().search(
                query,
                options,
                object : SearchSuggestionsCallback {
                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                        if (continuation.isActive) continuation.resume(suggestions)
                    }

                    override fun onError(e: Exception) {
                        debugLog.log("search_error", mapOf("stage" to "suggest", "query" to query, "error" to e.toString()))
                        if (continuation.isActive) continuation.resumeWithException(e.withAccessDenied())
                    }
                },
            )
            continuation.invokeOnCancellation { task.cancel() }
        }
        debugLog.log(
            "search_suggestions",
            mapOf(
                "query" to query,
                "items" to suggestions.map {
                    mapOf(
                        "name" to it.name,
                        "address" to it.fullAddress,
                        "type" to it.type.toString(),
                        "meters" to it.distanceMeters,
                        "categories" to it.categories,
                    )
                },
            ),
        )
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
        val original = latest[suggestion.id] ?: return null.also {
            debugLog.log("search_resolve_missing", mapOf("name" to suggestion.name))
        }
        debugLog.log("search_resolve", mapOf("name" to original.name, "address" to original.fullAddress))
        val place = suspendCancellableCoroutine { continuation ->
            val task = engine().select(
                original,
                SelectOptions(addResultToHistory = false),
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
                        debugLog.log("search_error", mapOf("stage" to "resolve", "name" to original.name, "error" to e.toString()))
                        if (continuation.isActive) continuation.resumeWithException(e.withAccessDenied())
                    }
                },
            )
            continuation.invokeOnCancellation { task.cancel() }
        }
        debugLog.log(
            "search_result",
            mapOf(
                "name" to place?.name,
                "address" to place?.address,
                "at" to place?.let { listOf(it.point.latitude, it.point.longitude) },
            ),
        )
        return place
    }

    /**
     * The search engine for the current access token; a replaced token gets a fresh engine.
     * It has no local history or favorites to search.
     */
    private fun engine(): SearchEngine {
        val token = MapboxOptions.accessToken
        engine?.takeIf { engineToken == token }?.let { return it }
        val created = SearchEngine.createSearchEngine(ApiType.SEARCH_BOX, SearchEngineSettings())
        if (engine == null) clearHistory()
        engine = created
        engineToken = token
        return created
    }

    /** Removes places that earlier builds left in the SDK's history file. Once per process. */
    private fun clearHistory() {
        ServiceProvider.INSTANCE.historyDataProvider().clear(
            object : CompletionCallback<Unit> {
                override fun onComplete(result: Unit) = debugLog.log("search_history_cleared")

                override fun onError(e: Exception) =
                    debugLog.log("search_error", mapOf("stage" to "clear history", "error" to e.toString()))
            },
        )
    }

    /** A refused token becomes [AccessDeniedException], so the screen can say what to fix. */
    private fun Exception.withAccessDenied(): Exception =
        if (this is SearchRequestException && AccessErrors.isAccessDenied(code)) AccessDeniedException(message) else this

    private fun SearchResult.toPlace() = Place(
        name = name,
        address = fullAddress ?: address?.formattedAddress(),
        point = GeoPoint(coordinate.latitude(), coordinate.longitude()),
    )

    private companion object {
        const val SUGGESTION_LIMIT = 8
    }
}
