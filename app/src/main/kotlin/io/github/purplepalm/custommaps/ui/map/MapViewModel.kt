package io.github.purplepalm.custommaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
import io.github.purplepalm.custommaps.nav.SearchProvider
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
    val progress: RouteProgressSnapshot? = null,
    val simulateDrive: Boolean = false,
    val message: UserMessage? = null,
)

/** Screen logic for the phone map: search, place, routes and guidance. Holds no Android types. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MapViewModel(
    private val navigation: NavigationEngine,
    private val search: SearchProvider,
    settings: AppSettings,
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

    /** What is sent to the search engine; separate from the text shown, so picking a suggestion does not search again. */
    private val query = MutableStateFlow("")

    private val engineState = combine(
        navigation.guidanceState,
        navigation.routeOptions,
        navigation.routeProgress,
        navigation.destination,
    ) { guidance, routes, progress, destination ->
        MapUiState(guidance = guidance, routes = routes, progress = progress, destination = destination)
    }

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
            query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged().collectLatest { text ->
                if (text.length < MIN_QUERY_LENGTH) {
                    local.update { it.copy(suggestions = emptyList(), searching = false) }
                    return@collectLatest
                }
                local.update { it.copy(searching = true) }
                try {
                    val suggestions = search.suggest(text)
                    local.update { it.copy(suggestions = suggestions, searching = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    local.update { it.copy(searching = false, message = UserMessage.SEARCH_FAILED) }
                }
            }
        }
    }

    fun onSearchTextChange(text: String) {
        local.update { it.copy(searchText = text, selectedPlace = null) }
        query.value = text.trim()
    }

    fun onSuggestionPicked(suggestion: PlaceSuggestion) {
        local.update { it.copy(searchText = suggestion.name, suggestions = emptyList(), busy = true) }
        query.value = ""
        viewModelScope.launch {
            val place = try {
                search.resolve(suggestion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            local.update {
                it.copy(
                    busy = false,
                    selectedPlace = place,
                    message = if (place == null) UserMessage.PLACE_NOT_FOUND else it.message,
                )
            }
        }
    }

    fun onDirections() {
        val place = local.value.selectedPlace ?: return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = when (val result = navigation.previewRoutes(place)) {
                RouteRequestResult.Success -> null
                RouteRequestResult.NoLocation -> UserMessage.NO_LOCATION
                RouteRequestResult.NoRoute -> UserMessage.NO_ROUTE
                is RouteRequestResult.Failed -> UserMessage.ROUTE_FAILED
            }
            // The place stays selected, so closing the preview goes back to it.
            local.update { it.copy(busy = false, message = message ?: it.message) }
        }
    }

    fun onRouteSelected(routeId: String) = navigation.selectRoute(routeId)

    /** Starts guidance; simulated when the debug setting is on. */
    fun onStart() = navigation.startGuidance(simulated = state.value.simulateDrive)

    fun onEnd() {
        navigation.stopGuidance(StopReason.USER_CANCELLED)
        clearSearch()
    }

    fun onCancelPreview() = navigation.clearPreview()

    fun onDismissArrival() {
        navigation.dismissArrival()
        clearSearch()
    }

    fun clearSearch() {
        query.value = ""
        local.update { Local() }
    }

    fun onMessageShown() {
        local.update { it.copy(message = null) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MIN_QUERY_LENGTH = 2
    }
}
