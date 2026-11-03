package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Routing and turn-by-turn guidance, independent of the map data provider. */
interface NavigationEngine {
    val guidanceState: StateFlow<GuidanceState>

    /** Routes offered in [GuidanceState.PREVIEW]; the first one is the selected route. */
    val routeOptions: StateFlow<List<RouteOption>>

    /** Latest progress while guidance is active, otherwise null. */
    val routeProgress: StateFlow<RouteProgressSnapshot?>

    /** Destination of the current preview, guidance or arrival. */
    val destination: StateFlow<Place?>

    /** Requests routes with alternatives from the current position. Starts no trip session. */
    suspend fun previewRoutes(destination: Place): RouteRequestResult

    fun selectRoute(routeId: String)

    /** Starts guidance on the selected route. [simulated] replays the drive instead of using GPS. */
    fun startGuidance(simulated: Boolean)

    fun stopGuidance(reason: StopReason)

    fun clearPreview()

    fun dismissArrival()
}

sealed interface RouteRequestResult {
    data object Success : RouteRequestResult

    /** The current position is not known yet. */
    data object NoLocation : RouteRequestResult

    data object NoRoute : RouteRequestResult

    data class Failed(val message: String) : RouteRequestResult
}
