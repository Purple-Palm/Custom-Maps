package io.github.purplepalm.custommaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.nav.AccessDeniedException
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.PlaceSuggestion
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UserMessage {
    NO_LOCATION,
    NO_ROUTE,
    ROUTE_FAILED,
    SEARCH_FAILED,
    PLACE_NOT_FOUND,
    START_FAILED,
    TOKEN_REJECTED,
}

data class MapUiState(
    val guidance: GuidanceState = GuidanceState.IDLE,
    val searchText: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val searching: Boolean = false,
    val selectedPlace: Place? = null,
    val busy: Boolean = false,
    val routes: List<RouteOption> = emptyList(),
    val destination: Place? = null,
    val simulateDrive: Boolean = false,
    val message: UserMessage? = null,
) {
    /**
     * The place the map marks with a pin: the one picked from search, then the one of the routes,
     * of the drive and of the arrival. Null once the place card is closed or guidance has ended.
     */
    val pinnedPlace: Place? get() = selectedPlace ?: destination
}

/** Screen logic for the phone map: search, place, routes and guidance. Holds no Android types. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MapViewModel(
    private val navigation: NavigationEngine,
    private val search: SearchProvider,
    settings: AppSettings,
    private val debugLog: DebugLog,
) : ViewModel() {

    private data class Local(
        val searchText: String = "",
        val suggestions: List<PlaceSuggestion> = emptyList(),
        val searching: Boolean = false,
        val selectedPlace: Place? = null,
        val busy: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    /** The place lookup and route request in flight; cancelled when the driver moves on. */
    private var resolveJob: Job? = null
    private var directionsJob: Job? = null

    /**
     * What is sent to the search engine; separate from the text shown, so picking a suggestion does
     * not search again. [attempt] lets the same text run again after a search failed.
     */
    private data class Query(val text: String, val attempt: Int = 0)

    private val query = MutableStateFlow(Query(""))
    private var failedSearches = 0

    private val engineState = combine(
        navigation.guidanceState,
        navigation.routeOptions,
        navigation.destination,
    ) { guidance, routes, destination ->
        MapUiState(guidance = guidance, routes = routes, destination = destination)
    }

    /**
     * Guidance progress, about one update a second during a drive. It is kept out of [state], so
     * that an update reaches only what shows the progress and the rest of the screen stays as it is.
     */
    val progress: StateFlow<RouteProgressSnapshot?> = navigation.routeProgress

    val state: StateFlow<MapUiState> = combine(engineState, local, settings.simulateDrive) { engine, local, simulate ->
        engine.copy(
            searchText = local.searchText,
            suggestions = local.suggestions,
            searching = local.searching,
            selectedPlace = local.selectedPlace,
            busy = local.busy,
            simulateDrive = simulate,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapUiState())

    init {
        viewModelScope.launch {
            var previous = navigation.guidanceState.value
            navigation.guidanceState.collect { current ->
                // Guidance goes back to the routes only when the navigator refused them at Start.
                if (previous == GuidanceState.ACTIVE && current == GuidanceState.PREVIEW) {
                    local.update { it.copy(message = UserMessage.START_FAILED) }
                }
                previous = current
            }
        }
        viewModelScope.launch {
            query
                // Clearing goes through at once, which also cancels a search still running.
                .debounce { if (it.text.length < MIN_QUERY_LENGTH) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collectLatest { (text) -> runSearch(text) }
        }
    }

    private suspend fun runSearch(text: String) {
        if (text.length < MIN_QUERY_LENGTH) {
            local.update { it.copy(suggestions = emptyList(), searching = false) }
            return
        }
        local.update { it.copy(searching = true) }
        try {
            val suggestions = search.suggest(text)
            local.update { it.copy(suggestions = suggestions, searching = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedSearches += 1
            val message = if (e is AccessDeniedException) UserMessage.TOKEN_REJECTED else UserMessage.SEARCH_FAILED
            local.update { it.copy(searching = false, message = message) }
        }
    }

    fun onSearchTextChange(text: String) {
        cancelLookups()
        local.update { it.copy(searchText = text, selectedPlace = null, busy = false) }
        query.value = Query(text.trim(), failedSearches)
    }

    fun onSuggestionPicked(suggestion: PlaceSuggestion) {
        debugLog.log("ui_pick", mapOf("name" to suggestion.name, "description" to suggestion.description))
        // The next stop, searched for right after arriving: the arrival card makes way for its place card.
        if (navigation.guidanceState.value == GuidanceState.ARRIVED) navigation.dismissArrival()
        cancelLookups()
        local.update { it.copy(searchText = suggestion.name, suggestions = emptyList(), selectedPlace = null, busy = true) }
        query.value = Query("")
        resolveJob = viewModelScope.launch {
            var failure = UserMessage.PLACE_NOT_FOUND
            val place = try {
                search.resolve(suggestion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccessDeniedException) {
                failure = UserMessage.TOKEN_REJECTED
                null
            } catch (e: Exception) {
                null
            }
            local.update {
                it.copy(
                    busy = false,
                    selectedPlace = place,
                    message = if (place == null) failure else it.message,
                )
            }
        }
    }

    fun onDirections() {
        val place = local.value.selectedPlace ?: return
        debugLog.log("ui_directions", mapOf("name" to place.name))
        directionsJob?.cancel()
        local.update { it.copy(busy = true) }
        directionsJob = viewModelScope.launch {
            val message = when (val result = navigation.previewRoutes(place)) {
                RouteRequestResult.Success, RouteRequestResult.Superseded -> null
                RouteRequestResult.NoLocation -> UserMessage.NO_LOCATION
                RouteRequestResult.NoRoute -> UserMessage.NO_ROUTE
                is RouteRequestResult.Failed -> UserMessage.ROUTE_FAILED
                RouteRequestResult.AccessDenied -> UserMessage.TOKEN_REJECTED
            }
            // The place stays selected, so closing the preview goes back to it.
            local.update { it.copy(busy = false, message = message ?: it.message) }
        }
    }

    fun onRouteSelected(routeId: String) = navigation.selectRoute(routeId)

    /** Starts guidance; simulated when the debug setting is on. */
    fun onStart() {
        debugLog.log("ui_start", mapOf("simulated" to state.value.simulateDrive))
        navigation.startGuidance(simulated = state.value.simulateDrive)
    }

    fun onEnd() {
        debugLog.log("ui_end")
        navigation.stopGuidance(StopReason.USER_CANCELLED)
        clearSearch()
    }

    fun onCancelPreview() {
        debugLog.log("ui_close_preview")
        navigation.clearPreview()
    }

    fun onDismissArrival() {
        debugLog.log("ui_arrival_done")
        navigation.dismissArrival()
        clearSearch()
    }

    fun clearSearch() {
        cancelLookups()
        query.value = Query("")
        local.update { Local() }
    }

    private fun cancelLookups() {
        resolveJob?.cancel()
        directionsJob?.cancel()
    }

    fun onLocationAccessChanged(granted: Boolean, precise: Boolean) {
        debugLog.log("location_access", mapOf("granted" to granted, "precise" to precise))
    }

    fun onMessageShown() {
        local.value.message?.let { debugLog.log("ui_message", mapOf("message" to it)) }
        local.update { it.copy(message = null) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MIN_QUERY_LENGTH = 2
    }
}
