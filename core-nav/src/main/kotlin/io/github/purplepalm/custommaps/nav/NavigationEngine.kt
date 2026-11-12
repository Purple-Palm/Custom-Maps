package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.replay.ReplayPosition
import io.github.purplepalm.custommaps.nav.replay.ReplaySeek
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

    /**
     * Where a simulated drive is on its replay, once a second while it plays; null unless a
     * simulated drive runs.
     */
    val replay: StateFlow<ReplayPosition?>

    /**
     * The newest position of the phone this process has: during a trip the navigator's, otherwise
     * where the map last drew it; null before the first. Read when asked, never pushed. Kept in
     * memory only: never stored, logged or sent.
     */
    val lastPosition: GeoPoint?

    /** Requests routes with alternatives from the current position. Starts no trip session. */
    suspend fun previewRoutes(destination: Place): RouteRequestResult

    fun selectRoute(routeId: String)

    /** Starts guidance on the selected route. [simulated] replays the drive instead of using GPS. */
    fun startGuidance(simulated: Boolean)

    fun stopGuidance(reason: StopReason)

    /**
     * Moves a simulated drive to [fraction] of its replay, from 0 at the start to 1 at the end, by
     * [ReplaySeek]'s rule. It starts and ends no trip session and asks for no route. Does nothing
     * outside a simulated drive.
     */
    fun seekReplay(fraction: Double)

    fun clearPreview()

    fun dismissArrival()
}

sealed interface RouteRequestResult {
    data object Success : RouteRequestResult

    /** The current position is not known yet. */
    data object NoLocation : RouteRequestResult

    data object NoRoute : RouteRequestResult

    data class Failed(val message: String) : RouteRequestResult

    /** The routing service refused the access token. */
    data object AccessDenied : RouteRequestResult

    /** A newer request or guidance came first, so this response was dropped and nothing changed. */
    data object Superseded : RouteRequestResult
}
