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
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.replay.route.ReplayRouteSession
import com.mapbox.navigation.core.trip.session.NavigationSessionState
import com.mapbox.navigation.core.trip.session.NavigationSessionStateObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
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
import java.time.Instant
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
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
        mapboxNavigation.unregisterNavigationSessionStateObserver(sessionStateObserver)
        navigation = null
    }

    override suspend fun previewRoutes(destination: Place): RouteRequestResult {
        val navigation = navigation ?: return RouteRequestResult.Failed("Navigation is not ready yet")
        if (_guidanceState.value == GuidanceState.ACTIVE) return RouteRequestResult.Failed("Guidance is running")
        val origin = deviceLocation.lastKnown() ?: lastPuckPosition ?: return RouteRequestResult.NoLocation
        val options = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .coordinatesList(listOf(origin, destination.point.toPoint()))
            .alternatives(true)
            .language(LANGUAGE)
            .voiceUnits(DirectionsCriteria.IMPERIAL)
            .build()
        // Billed per request (Directions API); no trip session is involved.
        return when (val response = navigation.requestRoutesAwait(options)) {
            is RouteResponse.Failure -> RouteRequestResult.Failed(response.message)
            is RouteResponse.Routes -> {
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
        showPreview(listOf(selected) + routes.filter { it.id != routeId })
    }

    override fun startGuidance(simulated: Boolean) {
        if (navigation == null || _previewRoutes.value.isEmpty()) return
        // The UI asks for location access first; without it a trip session cannot run.
        if (!hasLocationPermission()) {
            Log.w(TAG, "Guidance not started: location permission missing")
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
        val transition = GuidanceStateMachine.reduce(_guidanceState.value, event)
        transition.commands.forEach(::execute)
        _guidanceState.value = transition.state
        if (transition.state == GuidanceState.IDLE) _destination.value = null
    }

    private fun execute(command: GuidanceCommand) {
        val navigation = navigation
        when (command) {
            GuidanceCommand.CommitRoutes -> navigation?.setNavigationRoutes(_previewRoutes.value)
            is GuidanceCommand.StartTripSession -> {
                MapboxNavigationApp.attach(guidanceLifecycle)
                if (command.simulated) {
                    replaying = true
                    navigation?.let(replay::onAttached)
                } else {
                    try {
                        navigation?.startTripSession()
                    } catch (e: SecurityException) {
                        // Checked in startGuidance; only reachable if access was revoked in between.
                        Log.e(TAG, "Trip session could not start", e)
                    }
                }
            }
            GuidanceCommand.StopTripSession -> {
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
        if (_guidanceState.value != GuidanceState.ACTIVE) return@RouteProgressObserver
        val next = maneuverApi.getManeuvers(progress).value?.firstOrNull() ?: return@RouteProgressObserver
        _routeProgress.value = progress.toSnapshot(next)
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) = Unit

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) = Unit

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            dispatch(GuidanceEvent.Arrived)
        }
    }

    /** Logs every trip session the SDK enters; this is what Mapbox bills. */
    private val sessionStateObserver = NavigationSessionStateObserver { state ->
        val kind = when (state) {
            is NavigationSessionState.ActiveGuidance -> TripKind.ACTIVE_GUIDANCE
            is NavigationSessionState.FreeDrive -> TripKind.FREE_DRIVE
            else -> return@NavigationSessionStateObserver
        }
        if (state.sessionId == lastSessionId) return@NavigationSessionStateObserver
        lastSessionId = state.sessionId
        val start = TripSessionStart(Instant.now(), kind, simulated = replaying)
        Log.i(TAG, "Trip session started: $kind${if (start.simulated) " (simulated)" else ""}")
        scope.launch { tripLog.record(start) }
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
                        if (continuation.isActive) continuation.resume(RouteResponse.Failure(message))
                    }

                    override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                        if (continuation.isActive) continuation.resume(RouteResponse.Failure("Route request cancelled"))
                    }
                },
            )
            continuation.invokeOnCancellation { cancelRouteRequest(requestId) }
        }

    private sealed interface RouteResponse {
        data class Routes(val routes: List<NavigationRoute>) : RouteResponse

        data class Failure(val message: String) : RouteResponse
    }

    private companion object {
        const val TAG = "TripSession"
        const val LANGUAGE = "en"
    }
}
