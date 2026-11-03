package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesExtra
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.replay.route.ReplayRouteSession
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.NavigationSessionState
import com.mapbox.navigation.core.trip.session.NavigationSessionStateObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand
import io.github.purplepalm.custommaps.nav.guidance.GuidanceEvent
import io.github.purplepalm.custommaps.nav.guidance.GuidanceStateMachine
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import java.io.IOException
import java.time.Instant
import com.mapbox.common.location.Location as MapboxLocation
import kotlin.coroutines.resume
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [NavigationEngine] on the Mapbox Navigation SDK. Every trip session start and stop goes through
 * [GuidanceStateMachine], which is the single place that decides when a billed session runs.
 * All methods are called on the main thread, like the SDK callbacks.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal class MapboxNavigationEngine(
    context: Context,
    private val tripLog: TripLogStore,
    private val debugLog: DebugLog,
) : NavigationEngine, MapboxNavigationObserver {

    private val appContext = context.applicationContext
    private val scope = MainScope()
    private val deviceLocation = DeviceLocation()
    private val guidanceLifecycle = GuidanceLifecycleOwner()
    private val replay = ReplayRouteSession()
    private val maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(MapboxEngine.distanceFormatterOptions(context)))

    private var navigation: MapboxNavigation? = null
    private var replaying = false
    private var lastSessionId: String? = null

    /** Counts route commits; a commit that finishes after a newer one or after a stop is ignored. */
    private var commitCount = 0

    /** True from the moment this engine starts a trip session until it stops it. */
    private var tripSessionRunning = false

    /** Counts route requests; only the latest one may open a preview. */
    private var routeRequestCount = 0

    /** The navigator's latest map-matched position, recorded with each progress update. */
    private var lastMatch: LocationMatcherResult? = null

    private val _guidanceState = MutableStateFlow(GuidanceState.IDLE)
    override val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    private val _routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
    override val routeOptions: StateFlow<List<RouteOption>> = _routeOptions.asStateFlow()

    private val _routeProgress = MutableStateFlow<RouteProgressSnapshot?>(null)
    override val routeProgress: StateFlow<RouteProgressSnapshot?> = _routeProgress.asStateFlow()

    private val _destination = MutableStateFlow<Place?>(null)
    override val destination: StateFlow<Place?> = _destination.asStateFlow()

    private val _previewRoutes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** Routes of the preview, the selected one first. Only the map reads them; they are not in the navigator yet. */
    val previewRoutes: StateFlow<List<NavigationRoute>> = _previewRoutes.asStateFlow()

    /** Latest position of the map's location puck, used when the location service has no fix yet. */
    var lastPuckPosition: Point? = null

    init {
        MapboxNavigationApp.registerObserver(this)
    }

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        navigation = mapboxNavigation
        mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.registerArrivalObserver(arrivalObserver)
        mapboxNavigation.registerNavigationSessionStateObserver(sessionStateObserver)
        mapboxNavigation.registerLocationObserver(locationObserver)
        mapboxNavigation.registerOffRouteObserver(offRouteObserver)
        mapboxNavigation.registerRoutesObserver(routesObserver)
        debugLog.log("navigator_attached")
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
        mapboxNavigation.unregisterNavigationSessionStateObserver(sessionStateObserver)
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        mapboxNavigation.unregisterOffRouteObserver(offRouteObserver)
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
        navigation = null
        lastMatch = null
        debugLog.log("navigator_detached")
    }

    override suspend fun previewRoutes(destination: Place): RouteRequestResult {
        val navigation = navigation ?: return RouteRequestResult.Failed("Navigation is not ready yet").also {
            debugLog.log("route_request_refused", mapOf("reason" to "navigator not attached"))
        }
        if (_guidanceState.value == GuidanceState.ACTIVE) {
            debugLog.log("route_request_refused", mapOf("reason" to "guidance running"))
            return RouteRequestResult.Failed("Guidance is running")
        }
        val deviceFix = deviceLocation.lastKnown()
        val origin = deviceFix ?: lastPuckPosition ?: return RouteRequestResult.NoLocation.also {
            debugLog.log("route_request_refused", mapOf("reason" to "no location"))
        }
        val options = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .coordinatesList(listOf(origin, destination.point.toPoint()))
            .alternatives(true)
            .language(LANGUAGE)
            .voiceUnits(DirectionsCriteria.IMPERIAL)
            .build()
        debugLog.log(
            "route_request",
            mapOf(
                "origin" to origin.debugFields(),
                "originSource" to if (deviceFix != null) "device" else "map puck",
                "destination" to mapOf(
                    "name" to destination.name,
                    "address" to destination.address,
                    "at" to listOf(destination.point.latitude, destination.point.longitude),
                ),
                "profile" to options.profile(),
                "alternatives" to options.alternatives(),
            ),
        )
        val request = ++routeRequestCount
        // Billed per request (Directions API); no trip session is involved.
        val response = navigation.requestRoutesAwait(options)
        if (request != routeRequestCount || _guidanceState.value == GuidanceState.ACTIVE) {
            debugLog.log("route_response_dropped", mapOf("reason" to if (request != routeRequestCount) "newer request" else "guidance running"))
            return RouteRequestResult.Superseded
        }
        return when (response) {
            is RouteResponse.Failure -> {
                debugLog.log("route_failure", mapOf("message" to response.message, "reasons" to response.reasons))
                RouteRequestResult.Failed(response.message)
            }
            is RouteResponse.Routes -> {
                debugLog.log("route_response", mapOf("count" to response.routes.size, "routes" to response.routes.map { it.debugFields() }))
                if (response.routes.isEmpty()) {
                    RouteRequestResult.NoRoute
                } else {
                    _destination.value = destination
                    showPreview(response.routes)
                    dispatch(GuidanceEvent.RoutesReady(response.routes.size))
                    RouteRequestResult.Success
                }
            }
        }
    }

    override fun selectRoute(routeId: String) {
        if (_guidanceState.value != GuidanceState.PREVIEW) return
        val routes = _previewRoutes.value
        val selected = routes.firstOrNull { it.id == routeId } ?: return
        if (routes.first() == selected) return
        debugLog.log("route_selected", mapOf("id" to routeId, "listIndex" to routes.indexOf(selected)))
        showPreview(listOf(selected) + routes.filter { it.id != routeId })
    }

    override fun startGuidance(simulated: Boolean) {
        if (navigation == null || _previewRoutes.value.isEmpty()) {
            debugLog.log("start_refused", mapOf("reason" to if (navigation == null) "navigator not attached" else "no routes"))
            return
        }
        // The UI asks for location access first; without it a trip session cannot run.
        if (!hasLocationPermission()) {
            Log.w(TAG, "Guidance not started: location permission missing")
            debugLog.log("start_refused", mapOf("reason" to "location permission missing"))
            return
        }
        dispatch(GuidanceEvent.StartRequested(simulated))
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun stopGuidance(reason: StopReason) {
        dispatch(GuidanceEvent.Stopped(reason))
    }

    override fun clearPreview() {
        dispatch(GuidanceEvent.PreviewCleared)
    }

    override fun dismissArrival() {
        dispatch(GuidanceEvent.ArrivalDismissed)
    }

    private fun showPreview(routes: List<NavigationRoute>) {
        _previewRoutes.value = routes
        _routeOptions.value = routes.map { it.toRouteOption() }
    }

    private fun dispatch(event: GuidanceEvent) {
        val from = _guidanceState.value
        val transition = GuidanceStateMachine.reduce(from, event)
        debugLog.log(
            "guidance",
            mapOf(
                "event" to event.toString(),
                "from" to from,
                "to" to transition.state,
                "commands" to transition.commands.map { it.toString() },
            ),
        )
        // The state changes first, so SDK callbacks that fire while the commands run already see it.
        _guidanceState.value = transition.state
        if (transition.state == GuidanceState.IDLE) _destination.value = null
        transition.commands.forEach(::execute)
    }

    private fun execute(command: GuidanceCommand) {
        val navigation = navigation
        when (command) {
            is GuidanceCommand.CommitRoutes -> {
                // Keeps the navigator alive for the drive, even without a screen.
                MapboxNavigationApp.attach(guidanceLifecycle)
                val commit = ++commitCount
                // The session starts only once the navigator has the routes; started earlier, it
                // would begin as a separate free drive session.
                navigation?.setNavigationRoutes(_previewRoutes.value) { result ->
                    if (commit != commitCount) {
                        debugLog.log("routes_committed", mapOf("ignored" to "guidance ended before the routes were set"))
                        return@setNavigationRoutes
                    }
                    val error = result.error
                    if (error == null) {
                        debugLog.log("routes_committed", mapOf("ignoredAlternatives" to result.value?.ignoredAlternatives?.keys))
                        dispatch(GuidanceEvent.RoutesCommitted(command.simulated))
                    } else {
                        Log.e(TAG, "Navigator rejected the routes: ${error.message}")
                        debugLog.log("routes_rejected", mapOf("message" to error.message))
                        dispatch(GuidanceEvent.CommitFailed)
                    }
                }
            }
            is GuidanceCommand.StartTripSession -> {
                if (command.simulated) {
                    replaying = true
                    navigation?.let(replay::onAttached)
                } else {
                    try {
                        navigation?.startTripSession()
                    } catch (e: SecurityException) {
                        // Checked in startGuidance; only reachable if access was revoked in between.
                        Log.e(TAG, "Trip session could not start", e)
                        debugLog.error("startTripSession", e)
                        return
                    }
                }
                tripSessionRunning = true
            }
            GuidanceCommand.StopTripSession -> {
                commitCount++
                tripSessionRunning = false
                if (replaying) {
                    navigation?.let(replay::onDetached)
                    replaying = false
                }
                navigation?.stopTripSession()
            }
            GuidanceCommand.ClearRoutes -> {
                navigation?.setNavigationRoutes(emptyList())
                MapboxNavigationApp.detach(guidanceLifecycle)
            }
            GuidanceCommand.ClearPreview -> {
                _previewRoutes.value = emptyList()
                _routeOptions.value = emptyList()
                _routeProgress.value = null
            }
        }
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
        debugLog.log("progress", progress.debugFields() + ("match" to lastMatch?.debugFields()))
        if (_guidanceState.value != GuidanceState.ACTIVE) return@RouteProgressObserver
        val next = maneuverApi.getManeuvers(progress).value?.firstOrNull() ?: return@RouteProgressObserver
        _routeProgress.value = progress.toSnapshot(next)
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: MapboxLocation) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            lastMatch = locationMatcherResult
        }
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        debugLog.log("off_route", mapOf("offRoute" to offRoute, "match" to lastMatch?.debugFields()))
    }

    /** Route changes inside the navigator: the committed route, reroutes, refreshes and the final clean-up. */
    private val routesObserver = RoutesObserver { result ->
        val routes = result.navigationRoutes
        debugLog.log(
            "routes_updated",
            mapOf(
                "reason" to result.reason,
                "ids" to routes.map { it.id },
                // The new route in full after a reroute; refreshes only update traffic and times.
                "primary" to routes.firstOrNull()?.takeIf { result.reason != RoutesExtra.ROUTES_UPDATE_REASON_REFRESH }?.debugFields(),
            ),
        )
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) {
            debugLog.log("waypoint_arrival", mapOf("leg" to routeProgress.currentLegProgress?.legIndex))
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {
            debugLog.log("next_leg", mapOf("leg" to routeLegProgress.legIndex))
        }

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            debugLog.log("arrival", mapOf("match" to lastMatch?.debugFields()))
            dispatch(GuidanceEvent.Arrived)
        }
    }

    /** Logs every trip session the SDK enters; this is what Mapbox bills. */
    private val sessionStateObserver = NavigationSessionStateObserver { state ->
        debugLog.log("session_state", mapOf("state" to state.javaClass.simpleName, "sessionId" to state.sessionId, "simulated" to replaying))
        val kind = when (state) {
            is NavigationSessionState.ActiveGuidance -> TripKind.ACTIVE_GUIDANCE
            is NavigationSessionState.FreeDrive -> TripKind.FREE_DRIVE
            is NavigationSessionState.Idle -> {
                if (tripSessionRunning) onTripSessionStoppedOutsideApp()
                return@NavigationSessionStateObserver
            }
        }
        if (state.sessionId == lastSessionId) return@NavigationSessionStateObserver
        lastSessionId = state.sessionId
        val start = TripSessionStart(Instant.now(), kind, simulated = replaying)
        Log.i(TAG, "Trip session started: $kind${if (start.simulated) " (simulated)" else ""}")
        scope.launch {
            try {
                tripLog.record(start)
            } catch (e: IOException) {
                // Storage full or unreadable: the drive goes on, only this entry is lost.
                debugLog.error("tripLog.record", e)
            }
        }
    }

    /**
     * The SDK's trip notification has an End Navigation button that stops the session directly.
     * Guidance then ends the usual way, which also clears the routes and lets the navigator go.
     * It runs after the SDK has finished its own stop.
     */
    private fun onTripSessionStoppedOutsideApp() {
        tripSessionRunning = false
        scope.launch {
            if (_guidanceState.value != GuidanceState.ACTIVE) return@launch
            Log.i(TAG, "Trip session stopped outside the app")
            dispatch(GuidanceEvent.Stopped(StopReason.STOPPED_OUTSIDE_APP))
        }
    }

    private suspend fun MapboxNavigation.requestRoutesAwait(options: RouteOptions): RouteResponse =
        suspendCancellableCoroutine { continuation ->
            val requestId = requestRoutes(
                options,
                object : NavigationRouterCallback {
                    override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                        if (continuation.isActive) continuation.resume(RouteResponse.Routes(routes))
                    }

                    override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                        val message = reasons.firstOrNull()?.message ?: "Route request failed"
                        val failure = RouteResponse.Failure(message, reasons.map { it.debugFields() })
                        if (continuation.isActive) continuation.resume(failure)
                    }

                    override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                        if (continuation.isActive) continuation.resume(RouteResponse.Failure("Route request cancelled", emptyList()))
                    }
                },
            )
            continuation.invokeOnCancellation { cancelRouteRequest(requestId) }
        }

    private sealed interface RouteResponse {
        data class Routes(val routes: List<NavigationRoute>) : RouteResponse

        data class Failure(val message: String, val reasons: List<Map<String, Any?>>) : RouteResponse
    }

    private companion object {
        const val TAG = "TripSession"
        const val LANGUAGE = "en"
    }
}
