package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterFailureType
import com.mapbox.navigation.base.route.RouterOrigin
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
import com.mapbox.navigation.core.reroute.RerouteController
import com.mapbox.navigation.core.reroute.RerouteOptionsAdapter
import com.mapbox.navigation.core.reroute.RerouteState
import com.mapbox.navigation.core.routerefresh.ImmediateRouteRefreshResult
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.NavigationSessionState
import com.mapbox.navigation.core.trip.session.NavigationSessionStateObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.maneuver.model.Maneuver as MapboxManeuver
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.engine.mapbox.offline.OfflineData
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.guidance.CardContinuity
import io.github.purplepalm.custommaps.nav.guidance.CardUpdate
import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand
import io.github.purplepalm.custommaps.nav.guidance.GuidanceEvent
import io.github.purplepalm.custommaps.nav.guidance.GuidanceStateMachine
import io.github.purplepalm.custommaps.nav.guidance.StaleStart
import io.github.purplepalm.custommaps.nav.guidance.StartFreshness
import io.github.purplepalm.custommaps.nav.guidance.StartPlan
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.guidance.ThenTracker
import io.github.purplepalm.custommaps.nav.model.Distance
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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [NavigationEngine] on the Mapbox Navigation SDK. Every trip session start and stop goes through
 * [GuidanceStateMachine], which is the single place that decides when a billed session runs.
 * Inside a running trip, routes are refreshed or replaced only through [StartRefresher], which
 * keeps the trip the same billed trip. All methods are called on the main thread, like the SDK
 * callbacks.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal class MapboxNavigationEngine(
    context: Context,
    private val tripLog: TripLogStore,
    private val debugLog: DebugLog,
    private val offlineData: OfflineData,
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

    /** What the Then chip remembers from one progress update to the next; every drive starts it over. */
    private val thenTracker = ThenTracker()

    /** The route of the maneuver card on screen; null while there is none. */
    private var cardRouteId: String? = null

    /**
     * Tells the first updates of a trip that still carry where the trip before left the car from
     * the trip's own. Begun with each trip session, ended when it stops.
     */
    private val staleStart = StaleStart()

    /**
     * The points with lanes ahead of the car, read from the route once for each step. The engine
     * owns the one copy; the map's camera reads the same list, so both see the same lane choices.
     */
    internal val laneChoices = LaneChoices(onError = { error -> debugLog.error("laneChoices", error) })

    /**
     * Where the debug lines that describe whole routes are put together: off the main thread, since
     * they read the shape of every step, and one at a time, so the lines keep their order.
     */
    private val routeLines = Dispatchers.Default.limitedParallelism(1)

    /** The navigator's latest map-matched position, recorded with each progress update. */
    private var lastMatch: LocationMatcherResult? = null

    /** Writes the request of every reroute to the debug log, and changes none of them. */
    private val rerouteOptionsLogger = RerouteOptionsLogger(debugLog) { _destination.value }

    /**
     * When the routes of the preview arrived, on the clock that also runs while the phone sleeps,
     * and where they were requested from. Set together with each preview that opens.
     */
    private var previewedAt = 0L
    private var previewOrigin: Point? = null

    /** The plan of the start that is under way: made by [startGuidance], taken by the trip it starts. */
    private var startPlan = StartPlan.AS_PREVIEWED

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

    /**
     * Latest position of the map's location puck. A route request starts there when the location
     * service has no fix yet, and Start measures from it how far the car has moved.
     */
    var lastPuckPosition: Point? = null

    /**
     * The navigator as [StartRefresher] uses it inside a running trip. Nothing here decides
     * anything: the refresher calls these only when [io.github.purplepalm.custommaps.nav.guidance.InTripRefresh]
     * allows it, which is after the first progress of the trip and never once guidance has ended.
     */
    private val tripRoutes = object : TripRoutes<NavigationRoute> {

        override fun refresh(done: (RefreshOutcome) -> Unit) {
            val navigation = navigation ?: return done(RefreshOutcome.NOT_STARTED)
            // A refresh of the routes in use, inside the trip: fresh traffic, times and closures on
            // the same roads. An experimental call of the SDK, which this class opts in to.
            navigation.routeRefreshController.requestImmediateRouteRefresh { result ->
                done(
                    when (result) {
                        is ImmediateRouteRefreshResult.Success -> RefreshOutcome.SUCCESS
                        is ImmediateRouteRefreshResult.Failure -> RefreshOutcome.FAILURE
                        is ImmediateRouteRefreshResult.NotStarted -> RefreshOutcome.NOT_STARTED
                        is ImmediateRouteRefreshResult.Cancelled -> RefreshOutcome.CANCELLED
                    },
                )
            }
        }

        override fun request(done: (ReplanAnswer<NavigationRoute>) -> Unit): Long? {
            val navigation = navigation ?: return null
            val destination = _destination.value ?: return null
            // Where the navigator has the car on the road, and the way it drives there. An old
            // position is no start for routes that replace the ones in use: no request then.
            val from = livePosition() ?: return null
            // The request of the preview again: to the same stop point, with the same arrival target.
            val options = routeOptions(from.point, destination, from.originBearing())
            logRouteRequest(from.point, "navigator", from, destination, options, inTrip = true)
            // One request inside the running trip, which Mapbox counts as part of the trip.
            return navigation.requestRoutes(
                options,
                object : NavigationRouterCallback {
                    override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                        logRouteResponse(routes, options, routerOrigin, inTrip = true)
                        done(ReplanAnswer.Routes(routes, online = routerOrigin == RouterOrigin.ONLINE))
                    }

                    override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                        val message = reasons.firstOrNull()?.message ?: "Route request failed"
                        debugLog.log(
                            "route_failure",
                            mapOf("message" to message, "reasons" to reasons.map { it.debugFields() }, "inTrip" to true),
                        )
                        done(ReplanAnswer.Failed(message))
                    }

                    override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                        // After the refresher's own cancel it has moved on and takes no notice.
                        done(ReplanAnswer.Failed("Route request cancelled"))
                    }
                },
            )
        }

        override fun cancel(requestId: Long) {
            navigation?.cancelRouteRequest(requestId)
        }

        override fun set(routes: List<NavigationRoute>) {
            // Mapbox bills a second trip for routes set while the navigator has no progress, or
            // that end more than 100 m from where the trip goes. The refresher calls this from
            // inside a progress update, with a list that is not empty and ends where the trip ends.
            navigation?.setNavigationRoutes(routes) { result ->
                val error = result.error ?: return@setNavigationRoutes
                // The navigator keeps the routes it had; the drive goes on with them.
                debugLog.log("routes_rejected", mapOf("message" to error.message, "inTrip" to true))
            }
        }

        override fun summary(route: NavigationRoute): String? = route.directionsRoute.legs()?.firstOrNull()?.summary()

        override fun destination(route: NavigationRoute): Point? = soleDestination(route.waypoints?.map { it.location() })
    }

    /** Refreshes or replaces the routes of a started trip, as the plan of its start asks. */
    private val startRefresher = StartRefresher(
        routes = tripRoutes,
        debugLog = debugLog,
        // The session can stop a moment before guidance hears of it, from the trip notification.
        guidanceActive = { _guidanceState.value == GuidanceState.ACTIVE && tripSessionRunning },
        elapsedMillis = SystemClock::elapsedRealtime,
    )

    /**
     * The navigator's own reroute, heard from its first moment: while it fetches a route, and when
     * it has one. From then on the start plan leaves the routes alone.
     */
    private val rerouteStateObserver = RerouteController.RerouteStateObserver { state ->
        if (state is RerouteState.FetchingRoute || state is RerouteState.RouteFetched) startRefresher.onReroute()
    }

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
        mapboxNavigation.getRerouteController()?.registerRerouteStateObserver(rerouteStateObserver)
        setRerouteOptionsAdapter(mapboxNavigation, rerouteOptionsLogger)
        debugLog.log("navigator_attached")
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
        mapboxNavigation.unregisterNavigationSessionStateObserver(sessionStateObserver)
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        mapboxNavigation.unregisterOffRouteObserver(offRouteObserver)
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
        mapboxNavigation.getRerouteController()?.unregisterRerouteStateObserver(rerouteStateObserver)
        setRerouteOptionsAdapter(mapboxNavigation, null)
        // Without a navigator nothing can be refreshed or set any more.
        startRefresher.end()
        navigation = null
        lastMatch = null
        debugLog.log("navigator_detached")
    }

    /**
     * Gives the navigator the watcher of its reroute requests, or takes it back with null. The
     * watcher only writes to the debug log, so a navigator that will not take it costs its lines
     * and nothing else.
     */
    private fun setRerouteOptionsAdapter(mapboxNavigation: MapboxNavigation, adapter: RerouteOptionsAdapter?) {
        try {
            mapboxNavigation.setRerouteOptionsAdapter(adapter)
        } catch (e: RuntimeException) {
            debugLog.error("setRerouteOptionsAdapter", e)
        }
    }

    override suspend fun previewRoutes(destination: Place): RouteRequestResult {
        val navigation = navigation ?: return RouteRequestResult.Failed("Navigation is not ready yet").also {
            debugLog.log("route_request_refused", mapOf("reason" to "navigator not attached"))
        }
        if (_guidanceState.value == GuidanceState.ACTIVE) {
            debugLog.log("route_request_refused", mapOf("reason" to "guidance running"))
            return RouteRequestResult.Failed("Guidance is running")
        }
        deviceLocation.refresh()
        // Of any age: an old position is a better start than none, as it always was.
        val deviceFix = deviceLocation.lastFix()
        val origin = deviceFix?.point ?: lastPuckPosition ?: return RouteRequestResult.NoLocation.also {
            debugLog.log("route_request_refused", mapOf("reason" to "no location"))
        }
        // To the place's stop point when it has one, with the place itself as the arrival target.
        // A car that is moving sends its direction along, so the routes start the way it drives.
        val options = routeOptions(origin, destination, deviceFix?.originBearing())
        logRouteRequest(origin, if (deviceFix != null) "device" else "map puck", deviceFix, destination, options, inTrip = false)
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
                if (response.accessDenied) RouteRequestResult.AccessDenied else RouteRequestResult.Failed(response.message)
            }
            is RouteResponse.Routes -> {
                logRouteResponse(response.routes, options, response.routerOrigin, inTrip = false)
                if (response.routes.isEmpty()) {
                    RouteRequestResult.NoRoute
                } else {
                    _destination.value = destination
                    // What Start looks at to tell fresh routes from old ones.
                    previewedAt = SystemClock.elapsedRealtime()
                    previewOrigin = origin
                    showPreview(response.routes)
                    dispatch(GuidanceEvent.RoutesReady(response.routes.size))
                    // While the routes are looked at, and on into the drive, the street map along
                    // them is put on the phone, for a drive that loses its connection. A download of
                    // map tiles: no route request and no trip session. Only here, never for routes
                    // that come inside a trip.
                    offlineData.downloadPreview(response.routes, origin)
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
        // The UI asks for location access first. Approximate positions are too coarse to follow a
        // route, so guidance on GPS needs precise location; a replayed drive needs none of it.
        val precise = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = when {
            !simulated && !precise -> "precise location missing"
            !precise && !isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "location permission missing"
            else -> null
        }
        if (missing != null) {
            Log.w(TAG, "Guidance not started: $missing")
            debugLog.log("start_refused", mapOf("reason" to missing))
            return
        }
        // Only a start from the preview begins a trip; any other press changes no plan.
        if (_guidanceState.value == GuidanceState.PREVIEW) startPlan = planStart(simulated)
        dispatch(GuidanceEvent.StartRequested(simulated))
    }

    /**
     * What this start does about routes that may be old, from what is at hand at this moment: the
     * time since the routes arrived, and how far the car is from where they were requested. The
     * car's position is the map's puck, which moves while the map shows. The location service's
     * last answer is no second source: it is only asked for a route request, so at Start it still
     * is the position the routes were requested from. Without a puck nothing is measured and only
     * the age counts. Nothing is asked and nothing is waited for: the drive begins at once on the
     * previewed routes, and the plan is carried out inside the trip.
     */
    private fun planStart(simulated: Boolean): StartPlan {
        val ageMillis = SystemClock.elapsedRealtime() - previewedAt
        val here = lastPuckPosition
        val moved = metersBetween(previewOrigin, here)
        val plan = StartFreshness.plan(ageMillis.milliseconds, moved?.let(Distance::meters), simulated)
        debugLog.log(
            "start_plan",
            mapOf(
                "plan" to plan,
                "ageSeconds" to ageMillis / MILLIS_PER_SECOND,
                "movedMeters" to moved?.let { Motion.round(it, 1) },
                // What the distance was measured to; null when the map has shown no position yet.
                "position" to if (here != null) "map puck" else null,
                "simulated" to simulated,
            ),
        )
        return plan
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

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
        // The place the routes were asked to: each option says how far from it the route ends.
        val place = _destination.value
        _routeOptions.value = routes.map { it.toRouteOption(place) }
    }

    /**
     * Writes a debug line that describes whole routes. Its fields are put together on a background
     * thread, so the line is written a moment after the lines that follow it in the code.
     */
    private fun logRoutes(type: String, fields: () -> Map<String, Any?>) {
        scope.launch(routeLines) {
            val built = try {
                fields()
            } catch (e: RuntimeException) {
                // A route that cannot be read costs its line, never the drive.
                debugLog.error(type, e)
                return@launch
            }
            debugLog.log(type, built)
        }
    }

    /** The "route_request" line of every route request the app sends, before a trip or inside one. */
    private fun logRouteRequest(
        origin: Point,
        originSource: String,
        originFix: DeviceFix?,
        destination: Place,
        options: RouteOptions,
        inTrip: Boolean,
    ) {
        debugLog.log(
            "route_request",
            mapOf(
                "origin" to origin.debugFields(),
                "originSource" to originSource,
                "originFix" to originFix?.debugFields(),
                "destination" to mapOf(
                    "name" to destination.name,
                    "address" to destination.address,
                    "at" to listOf(destination.point.latitude, destination.point.longitude),
                ),
                "profile" to options.profile(),
                "alternatives" to options.alternatives(),
                "inTrip" to inTrip,
            ) + routeRequestFields(destination, options),
        )
    }

    /** The "route_response" line with every route of an answer, put together off the main thread. */
    private fun logRouteResponse(routes: List<NavigationRoute>, options: RouteOptions, routerOrigin: String?, inTrip: Boolean) {
        logRoutes("route_response") {
            mapOf(
                "count" to routes.size,
                "router" to routerOrigin,
                "inTrip" to inTrip,
                // The same for every route of a response.
                "waypoints" to routes.firstOrNull()?.waypointsDebugFields(options.coordinatesList()),
                "routes" to routes.map { it.debugFields() },
            )
        }
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
        // A drive that ends, by End or at the destination; a start whose routes were refused goes
        // back to the preview, whose download goes on.
        if (from == GuidanceState.ACTIVE && (transition.state == GuidanceState.IDLE || transition.state == GuidanceState.ARRIVED)) {
            offlineData.onTripEnded()
        }
        transition.commands.forEach(::execute)
    }

    private fun execute(command: GuidanceCommand) {
        val navigation = navigation
        when (command) {
            is GuidanceCommand.CommitRoutes -> {
                // The preview's download goes on into the drive until it is done.
                offlineData.onTripStarted()
                // Keeps the navigator alive for the drive, even without a screen.
                MapboxNavigationApp.attach(guidanceLifecycle)
                // A new drive: the Then chip and the lane choices know nothing of the last one.
                thenTracker.reset()
                laneChoices.clear()
                val commit = ++commitCount
                // What follows inside the trip waits for its first progress; a start that made no
                // plan is as previewed.
                startRefresher.begin(
                    trip = commit,
                    plan = startPlan,
                    routeId = _previewRoutes.value.firstOrNull()?.id,
                    chosenSummary = _routeOptions.value.firstOrNull()?.summary,
                )
                startPlan = StartPlan.AS_PREVIEWED
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
                // Before the session starts: its first updates may come at once, and the position the
                // navigator held from the trip before must not count as this trip's.
                staleStart.begin()
                lastMatch = null
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
                // Before the session stops: a refresh or a request still under way is given up,
                // and its answer finds no trip to change.
                startRefresher.end()
                staleStart.end()
                tripSessionRunning = false
                if (replaying) {
                    navigation?.let(replay::onDetached)
                    replaying = false
                }
                navigation?.stopTripSession()
            }
            GuidanceCommand.ClearRoutes -> {
                val trip = commitCount
                navigation?.let { navigator ->
                    navigator.setNavigationRoutes(emptyList()) { _ ->
                        // Only while no newer trip has begun on this navigator since.
                        if (trip == commitCount && this.navigation === navigator) forgetLastPosition(navigator)
                    }
                }
                MapboxNavigationApp.detach(guidanceLifecycle)
            }
            GuidanceCommand.ClearPreview -> {
                _previewRoutes.value = emptyList()
                _routeOptions.value = emptyList()
                _routeProgress.value = null
                cardRouteId = null
                thenTracker.reset()
                laneChoices.clear()
            }
        }
    }

    /**
     * Has the navigator forget where the trip that ended left the car. Kept, that position is
     * where the next trip of this navigator would begin: its first updates would carry it, as far
     * away as the two trips are apart, and the navigator would find the car off the route and
     * reroute for nothing. Called once a trip's session is stopped and its routes are cleared. The
     * reset starts no trip session, ends none and sets no routes; the map outside guidance shows
     * the phone's own location and does not change. The "trip_session_reset" line says how long it
     * took.
     */
    private fun forgetLastPosition(navigator: MapboxNavigation) {
        val asked = SystemClock.elapsedRealtime()
        try {
            navigator.resetTripSession {
                debugLog.log("trip_session_reset", mapOf("ms" to SystemClock.elapsedRealtime() - asked))
            }
        } catch (e: RuntimeException) {
            // The next trip may begin on the old position, as it did before; nothing else is lost.
            debugLog.error("resetTripSession", e)
        }
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
        val active = _guidanceState.value == GuidanceState.ACTIVE
        // An update that still carries where the trip before left the car: logged, and otherwise
        // left alone, by the card, the Then chip, the lane choices and the start plan alike.
        if (active && staleStart.isStale(lastMatch?.enhancedLocation?.fixTime())) {
            staleStart.countStaleUpdate()
            debugLog.log("progress", progress.debugFields() + mapOf("match" to lastMatch?.debugFields(), "staleStart" to true))
            return@RouteProgressObserver
        }
        val next = if (active) maneuverApi.getManeuvers(progress).value?.firstOrNull() else null
        // Without the next maneuver the card stays as it is, but never with a maneuver of the route
        // before a reroute or another change of route: it waits, dimmed, for the new route's.
        val card = if (active) CardContinuity.next(cardRouteId, progress.navigationRoute.id, hasManeuver = next != null) else null
        val snapshot = when {
            next != null -> snapshotOf(progress, next)
            card == CardUpdate.PENDING -> progress.toPendingSnapshot()
            else -> null
        }
        debugLog.log(
            "progress",
            progress.debugFields() + mapOf(
                "match" to lastMatch?.debugFields(),
                "thenShown" to snapshot?.showThen,
                "card" to card?.logName,
            ),
        )
        if (active) logLaneChoices(progress)
        if (snapshot != null) {
            _routeProgress.value = snapshot
            cardRouteId = progress.navigationRoute.id
        }
        // The first progress of a trip, the next one after a cancelled refresh, and the first one
        // after an answer to its replan.
        if (active && startRefresher.awaitsProgress) {
            startRefresher.onProgress(progress.toTripMoment(live = livePosition() != null))
        }
    }

    /**
     * The navigator's latest position on the road when it is a recent one ([DeviceFix.live]); null
     * for an old one, such as the phone's last known position that a trip can begin with, and
     * before the first.
     */
    private fun livePosition(): DeviceFix? = lastMatch?.enhancedLocation?.let(DeviceFix::of)?.takeIf { it.live }

    /**
     * Whether [result], a matched position of this trip, is still the one the navigator held from
     * the trip before ([StaleStart]); the map's camera and drawn car leave such a position alone.
     * False outside a trip.
     */
    internal fun carriesOldPosition(result: LocationMatcherResult): Boolean = staleStart.isStale(result.enhancedLocation.fixTime())

    /**
     * Writes a "lane_choice" line for each point with lanes that has come as near as the close
     * view's start: once for each point, with its lane counts, and with the reason when the view
     * stays as it is for it. With every camera style and either lane zoom, so a drive shows how
     * often the rule fires and how often a looser one would.
     */
    private fun logLaneChoices(progress: RouteProgress) {
        val speed = lastMatch?.enhancedLocation?.speed
        val entering = laneChoices.entering(laneChoices.ahead(progress), speed)
        for (i in entering.indices) debugLog.log("lane_choice", laneChoices.debugFields(entering[i], speed))
    }

    /** The UI's view of [progress] with its [next] maneuver, the Then rule applied and the lanes of a lane choice ahead. */
    private fun snapshotOf(progress: RouteProgress, next: MapboxManeuver): RouteProgressSnapshot {
        val decided = thenTracker.decide(progress.toSnapshot(next))
        // The speed the camera gets with this update, so the card's lanes and the close view come together.
        val lanes = laneChoices.lanesAhead(laneChoices.ahead(progress), lastMatch?.enhancedLocation?.speed)
        // Added onto what the tracker decided: a copy of a fresh snapshot would lose the rule's
        // answer and the following maneuver's remembered object.
        return if (lanes == null) decided else decided.copy(laneChoiceAhead = lanes)
    }

    private val locationObserver = object : LocationObserver {
        // Called for every position the trip session hands the navigator, before the navigator has it.
        override fun onNewRawLocation(rawLocation: MapboxLocation) {
            staleStart.onSourceFix(rawLocation.fixTime())
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            lastMatch = locationMatcherResult
        }
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        debugLog.log("off_route", mapOf("offRoute" to offRoute, "match" to lastMatch?.debugFields()))
        // The navigator reroutes a car that left the route; the start plan steps back for it.
        if (offRoute) startRefresher.onReroute()
    }

    /** Route changes inside the navigator: the committed route, reroutes, refreshes and the final clean-up. */
    private val routesObserver = RoutesObserver { result ->
        val routes = result.navigationRoutes
        val reason = result.reason
        // A reroute, or any other route the navigator put in place of the one the trip started on.
        if (reason == RoutesExtra.ROUTES_UPDATE_REASON_REROUTE) startRefresher.onReroute()
        startRefresher.onRoutesChanged(routes.firstOrNull()?.id)
        logRoutes("routes_updated") {
            mapOf(
                "reason" to reason,
                "ids" to routes.map { it.id },
                // The new route in full after a reroute; refreshes only update traffic and times.
                "primary" to routes.firstOrNull()?.takeIf { reason != RoutesExtra.ROUTES_UPDATE_REASON_REFRESH }?.debugFields(),
            )
        }
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
                        if (continuation.isActive) continuation.resume(RouteResponse.Routes(routes, routerOrigin))
                    }

                    override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                        val message = reasons.firstOrNull()?.message ?: "Route request failed"
                        val accessDenied = reasons.any { it.type == RouterFailureType.AUTHENTICATION_ERROR }
                        val failure = RouteResponse.Failure(message, reasons.map { it.debugFields() }, accessDenied)
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
        data class Routes(val routes: List<NavigationRoute>, val routerOrigin: String) : RouteResponse

        data class Failure(
            val message: String,
            val reasons: List<Map<String, Any?>>,
            val accessDenied: Boolean = false,
        ) : RouteResponse
    }

    private companion object {
        const val TAG = "TripSession"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
