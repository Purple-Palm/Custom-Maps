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
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesExtra
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.replay.history.ReplayEventUpdateLocation
import com.mapbox.navigation.core.replay.history.ReplayEventsObserver
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
import io.github.purplepalm.custommaps.nav.debug.LaneRowLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.guidance.CardContinuity
import io.github.purplepalm.custommaps.nav.guidance.CardUpdate
import io.github.purplepalm.custommaps.nav.guidance.DivergeWatch
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
import io.github.purplepalm.custommaps.nav.network.NetworkStatus
import io.github.purplepalm.custommaps.nav.network.OfflineReason
import io.github.purplepalm.custommaps.nav.network.offlineReason
import io.github.purplepalm.custommaps.nav.replay.ReplayPosition
import io.github.purplepalm.custommaps.nav.replay.ReplaySeek
import io.github.purplepalm.custommaps.nav.replay.SeekHold
import io.github.purplepalm.custommaps.nav.trip.TripEnding
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripMarker
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.nav.trip.TripTally
import io.github.purplepalm.custommaps.nav.trip.TripVitals
import java.io.File
import java.io.IOException
import java.time.Instant
import com.mapbox.common.location.Location as MapboxLocation
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
    /** This app's network as Android reports it, for the time a trip spends offline. */
    private val network: StateFlow<NetworkStatus>,
) : NavigationEngine, MapboxNavigationObserver {

    private val appContext = context.applicationContext
    private val scope = MainScope()
    private val deviceLocation = DeviceLocation()
    private val guidanceLifecycle = GuidanceLifecycleOwner()
    private val replaySession = wholeRouteReplay()
    private val maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(MapboxEngine.distanceFormatterOptions(context)))

    private var navigation: MapboxNavigation? = null
    private var replaying = false
    private var lastSessionId: String? = null

    /** Whether the last trip session of this run was simulated; null before the first. */
    private var lastTripSimulated: Boolean? = null

    /** Counts route commits; a commit that finishes after a newer one or after a stop is ignored. */
    private var commitCount = 0

    /** True from the moment this engine starts a trip session until it stops it. */
    private var tripSessionRunning = false

    /** Counts route requests; only the latest one may open a preview. */
    private var routeRequestCount = 0

    /** What the Then chip remembers from one progress update to the next; every drive starts it over. */
    private val thenTracker = ThenTracker()

    /** Which route the maneuver card on screen is of, so it never keeps one of the route before. */
    private val cardContinuity = CardContinuity()

    /**
     * Tells the first updates of a trip that still carry where the trip before left the car from
     * the trip's own. Begun with each trip session, ended when it stops.
     */
    private val staleStart = StaleStart()

    /** Adds up each trip for its "trip_summary" line, written when the trip ends. */
    private val tripTally = TripTally()

    /** How the trip under way ends, set when guidance learns it; null while the trip runs. */
    private var tripEnding: TripEnding? = null

    /** The phone's counters at the start of the trip under way; null until read. */
    private var startVitals: TripVitals? = null

    private val phoneVitals = PhoneVitals(appContext)

    /**
     * There while a trip runs, with what it has added up, renewed once a minute: a process that
     * ends during a trip leaves it behind, and the next start writes that trip's summary. Not
     * backed up, like the debug log it is for.
     */
    private val tripMarker = TripMarker(File(appContext.noBackupFilesDir, TRIP_MARKER_FILE))

    /** The marker's writes, one at a time and in order, off the main thread. */
    private val markerWrites = Dispatchers.IO.limitedParallelism(1)

    /** When the marker was last written, in milliseconds since boot. */
    private var markerWrittenAt = 0L

    /** The speed limit on the progress line that last had one; null at the start of a trip. */
    private var lastSpeedLimit: Map<String, Any?>? = null

    /**
     * Holds the navigator's updates from the moment a seek of a simulated drive is asked for until
     * the drive's new place has come in; the screen and the trip's summary leave them alone.
     */
    private val seekHold = SeekHold()

    /** The release of the replay slider that the seek under way goes to; null while none waits. */
    private var seekAsk: SeekAsk? = null

    /** True while the navigator forgets where the car was, before the replay goes to [seekAsk]. */
    private var seekResetting = false

    /**
     * Counts the navigator's resets for seeks, and the drives that ended while one was under way:
     * the answer of a reset that is no longer the latest changes nothing, not even a later drive's.
     */
    private var seekResets = 0

    /** The navigator whose reroutes the first seek of a simulated drive turned off; null while they run. */
    private var reroutesHeldOn: MapboxNavigation? = null

    /** Whether the navigator is fetching or putting in place a new route after the car left its route. */
    private var rerouting = false

    /** How far along its route the latest progress of the drive had the car, in metres; null before it. */
    private var routeMetres: Double? = null

    /** The replay's newest played position; null before the first of a simulated drive. */
    private var played: ReplayPosition? = null

    /** The first timestamp the replayer held at that position, on the replay's own clock. */
    private var replayStart = 0.0

    /** The seeks the trip's summary has taken in: the first position after each is a jump, not a stretch driven. */
    private var tallySeeks = 0

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

    /** The phone's newest position of the trip, before the navigator matched it. Kept in memory only, like [lastMatch]. */
    private var lastRawFix: MapboxLocation? = null

    /** Where [lastRawFix] lay against [lastMatch] when that came: the "raw" object of the "match" fields ([rawOffsetFields]). */
    private var lastRawOffset: Map<String, Any?>? = null

    /** What the maneuver card's lane row drew at the last "lane_row" line; every drive starts it over. */
    private val laneRowLog = LaneRowLog()

    /**
     * Whether the car went the route's way where the road divides ("diverge_taken") or left the
     * route there ("missed_maneuver"). Log only; reset where the Then chip's memory is.
     */
    private val divergeWatch = DivergeWatch()

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

    private val _replay = MutableStateFlow<ReplayPosition?>(null)
    override val replay: StateFlow<ReplayPosition?> = _replay.asStateFlow()

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
     * it has one. From then on the start plan leaves the routes alone, and a simulated drive does
     * not seek until the new route is in place.
     */
    private val rerouteStateObserver = RerouteController.RerouteStateObserver { state ->
        rerouting = state is RerouteState.FetchingRoute || state is RerouteState.RouteFetched
        if (rerouting) startRefresher.onReroute()
    }

    init {
        MapboxNavigationApp.registerObserver(this)
        // The time a trip spends without internet, as the offline chip counts it.
        scope.launch {
            network.map { it.offlineReason() != OfflineReason.NONE }.distinctUntilChanged().collect { offline ->
                tripTally.onOffline(offline, SystemClock.elapsedRealtime())
            }
        }
        // A trip that was under way when the last process of the app ended.
        scope.launch(markerWrites) {
            val left = tripMarker.read() ?: return@launch
            debugLog.log("trip_summary", summaryLine(left, TripEnding.PROCESS_DEATH, emptyMap()))
            tripMarker.clear()
        }
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
        // Nor can a simulated drive be moved. The navigator goes; a new one reroutes as usual.
        if (replaying) mapboxNavigation.mapboxReplayer.unregisterObserver(replayObserver)
        giveUpSeek()
        reroutesHeldOn = null
        rerouting = false
        navigation = null
        lastMatch = null
        lastRawFix = null
        lastRawOffset = null
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

    /**
     * Moves a simulated drive to where the replay slider was let go, by [ReplaySeek]'s rule, back as
     * well as forward. The replay stops, and the navigator forgets where the car was
     * ([MapboxNavigation.resetTripSession]), as Mapbox asks for before any large change of
     * position, so it finds the car on the route afresh at the new place. Then the replay goes
     * there and plays on. The routes stay as they are, and the trip session runs on: nothing is
     * started, ended, set or asked for that Mapbox bills. From the drive's first seek on, the
     * navigator's own reroutes are off until the drive ends, so no jump can make it fetch a route.
     * Until the new place has come in, the navigator's updates are held ([SeekHold]). Each release
     * ends in one "replay_seek" line.
     */
    override fun seekReplay(fraction: Double) {
        val navigator = navigation
        val position = _replay.value
        val answer = if (navigator != null && replaying) {
            ReplaySeek.answer(position, fraction, rerouting)
        } else {
            ReplaySeek.Answer.Stay(ReplaySeek.Why.NO_REPLAY)
        }
        if (answer !is ReplaySeek.Answer.Go || navigator == null || position == null) {
            val why = (answer as? ReplaySeek.Answer.Stay)?.why ?: ReplaySeek.Why.NO_REPLAY
            // Where the finger let go, for a release that moved nothing.
            val asked = position?.let { fraction.coerceIn(0.0, 1.0) * it.durationSeconds }?.takeIf { it.isFinite() }
            logSeek(from = if (replaying) played ?: position else null, toSeconds = asked, ok = false, why = why, ms = null)
            return
        }
        // The newest release goes; one that still waits for the navigator is replaced.
        seekAsk?.let { logSeek(it, ok = false, why = ReplaySeek.Why.REPLACED) }
        val ask = SeekAsk(
            from = played ?: position,
            toSeconds = answer.toSeconds,
            toTimestamp = replayStart + answer.toSeconds,
            routeId = navigator.getNavigationRoutes().firstOrNull()?.id,
            askedAt = SystemClock.elapsedRealtime(),
        )
        seekAsk = ask
        // The slider shows where the drive goes until the replay plays from there.
        _replay.value = position.copy(seconds = answer.toSeconds, seeking = true)
        if (seekResetting) return
        if (!holdReroutes(navigator)) {
            seekAsk = null
            showPlayed()
            logSeek(ask, ok = false, why = ReplaySeek.Why.REFUSED)
            return
        }
        seekResetting = true
        seekHold.ask()
        // What the replayer holds while it stands, by its first position's time: Mapbox's replay
        // session may start the drive over meanwhile ([onSeekReset]).
        val heldFirst = -navigator.mapboxReplayer.eventSeconds(0.0)
        // No more positions from where the car was.
        navigator.mapboxReplayer.stop()
        val reset = ++seekResets
        try {
            navigator.resetTripSession { onSeekReset(navigator, reset, heldFirst) }
        } catch (e: RuntimeException) {
            debugLog.error("resetTripSession", e)
            seekResetting = false
            seekAsk = null
            seekHold.cancel()
            navigator.mapboxReplayer.play()
            showPlayed()
            logSeek(ask, ok = false, why = ReplaySeek.Why.REFUSED)
        }
    }

    /**
     * The navigator has forgotten where the car was: the replay goes to the newest release and
     * plays on from there. The answer of a reset that is no longer the latest, as of a drive that
     * ended meanwhile, changes nothing; a drive whose route changed stays where it is.
     *
     * While the replay stands, Mapbox's replay session takes any update of the routes (a refresh,
     * new alternatives) for a new route: it puts the replay back to the route's start and plays it
     * at once. [heldFirst], the time of the first position the replayer held when it was stopped,
     * tells that apart. The drive then goes on from the start, as a jump, and the replay is not
     * started a second time.
     */
    private fun onSeekReset(navigator: MapboxNavigation, reset: Int, heldFirst: Double) {
        if (reset != seekResets) return
        seekResetting = false
        val ask = seekAsk ?: return
        seekAsk = null
        if (!replaying || navigation !== navigator) {
            logSeek(ask, ok = false, why = ReplaySeek.Why.ENDED)
            return
        }
        val replayer = navigator.mapboxReplayer
        if (-replayer.eventSeconds(0.0) != heldFirst) {
            // The session started the replay over and plays it: the car jumps to where it starts.
            // A run-up ends there, and may begin again soon if the car lands on the same approach.
            thenTracker.reset()
            laneChoices.onSeek(nowSeconds())
            divergeWatch.reset()
            seekHold.resume()
            showPlayed()
            logSeek(ask, ok = false, why = ReplaySeek.Why.ENDED)
            return
        }
        // The replayer counts its seconds from the first position it holds now.
        val first = -replayer.eventSeconds(0.0)
        val duration = replayer.durationSeconds()
        val sameRoute = ask.routeId == navigator.getNavigationRoutes().firstOrNull()?.id
        val moved = sameRoute && duration > 0.0 &&
            try {
                replayer.seekTo((ask.toTimestamp - first).coerceIn(0.0, duration))
                true
            } catch (e: IllegalStateException) {
                // The replayer was between two batches of positions; the drive goes on where it was.
                debugLog.error("replaySeek", e)
                false
            }
        if (moved) {
            // What the Then chip and the lane choices remember is of the place the car left. A
            // run-up ends at the seek, and may begin again soon if the car lands on the same approach.
            thenTracker.reset()
            laneChoices.onSeek(nowSeconds())
            divergeWatch.reset()
            seekHold.resume()
        } else {
            seekHold.cancel()
            showPlayed()
        }
        replayer.play()
        logSeek(ask, ok = moved, why = if (moved) null else if (sameRoute) ReplaySeek.Why.REFUSED else ReplaySeek.Why.ENDED)
    }

    /**
     * A drive ends, or its navigator goes: a release still waiting for the navigator is given up,
     * and the answer of a reset under way will change nothing.
     */
    private fun giveUpSeek() {
        seekAsk?.let { logSeek(it, ok = false, why = ReplaySeek.Why.ENDED) }
        seekAsk = null
        seekResetting = false
        seekResets += 1
        seekHold.end()
    }

    /** The slider shows the replay's newest played position again, after a seek that was not made. */
    private fun showPlayed() {
        if (replaying) _replay.value = played ?: _replay.value?.copy(seeking = false)
    }

    /** The "replay_seek" line of a release that went somewhere, or was given up on the way. */
    private fun logSeek(ask: SeekAsk, ok: Boolean, why: ReplaySeek.Why?) {
        logSeek(ask.from, ask.toSeconds, ok, why, ms = if (ok) SystemClock.elapsedRealtime() - ask.askedAt else null)
    }

    private fun logSeek(from: ReplayPosition?, toSeconds: Double?, ok: Boolean, why: ReplaySeek.Why?, ms: Long?) {
        debugLog.log("replay_seek", replaySeekFields(from, toSeconds, ok, why, ms))
    }

    /**
     * Turns the navigator's own reroutes off for the rest of a simulated drive, at its first seek.
     * The replay follows the route, so a drive has no use for them; but the navigator, finding the
     * car somewhere else at once, might take it for off the route, and a reroute is a route
     * request. Turned back on when the drive's trip session stops ([releaseReroutes]). False when
     * the navigator would not: the seek is then not made.
     */
    private fun holdReroutes(navigator: MapboxNavigation): Boolean {
        if (reroutesHeldOn === navigator) return true
        return try {
            navigator.setRerouteEnabled(false)
            reroutesHeldOn = navigator
            true
        } catch (e: RuntimeException) {
            debugLog.error("setRerouteEnabled", e)
            false
        }
    }

    /**
     * The navigator reroutes again, after a simulated drive that turned it off. Called once its trip
     * session has stopped, when it no longer looks for the car on a route.
     */
    private fun releaseReroutes() {
        val navigator = reroutesHeldOn ?: return
        reroutesHeldOn = null
        if (navigator !== navigation) return
        try {
            navigator.setRerouteEnabled(true)
        } catch (e: RuntimeException) {
            // Seen in the log: the next drive of this run would go without reroutes.
            debugLog.error("setRerouteEnabled", e)
        }
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
        if (from == GuidanceState.ACTIVE) {
            when (event) {
                GuidanceEvent.Arrived -> tripEnding = TripEnding.ARRIVED
                is GuidanceEvent.Stopped -> tripEnding = TripEnding.of(event.reason)
                else -> Unit
            }
        }
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
        if (GuidanceStateMachine.endsDrive(from, transition.state)) offlineData.onTripEnded()
        transition.commands.forEach(::execute)
    }

    private fun execute(command: GuidanceCommand) {
        val navigation = navigation
        when (command) {
            is GuidanceCommand.CommitRoutes -> {
                // Keeps the navigator alive for the drive, even without a screen. The preview's
                // download is not ended here: it goes on into the drive ([OfflineData.onTripStarted]).
                MapboxNavigationApp.attach(guidanceLifecycle)
                // A new drive: the Then chip and the lane choices know nothing of the last one.
                thenTracker.reset()
                laneChoices.clear()
                laneRowLog.reset()
                divergeWatch.reset()
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
                // The route the drive starts on, the first of the routes handed over.
                val routeId = _previewRoutes.value.firstOrNull()?.id
                // The session starts only once the navigator has the routes; started earlier, it
                // would begin as a separate free drive session.
                navigation?.setNavigationRoutes(_previewRoutes.value) { result ->
                    if (commit != commitCount) {
                        debugLog.log("routes_committed", mapOf("routeId" to routeId, "ignored" to "guidance ended before the routes were set"))
                        return@setNavigationRoutes
                    }
                    val error = result.error
                    if (error == null) {
                        debugLog.log("routes_committed", mapOf("routeId" to routeId, "ignoredAlternatives" to result.value?.ignoredAlternatives?.keys))
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
                lastRawFix = null
                lastRawOffset = null
                beginTripTally(command.simulated)
                // The preview's download goes on into the drive until it is done.
                offlineData.onTripStarted()
                if (command.simulated) {
                    replaying = true
                    played = null
                    routeMetres = null
                    navigation?.let { navigator ->
                        replaySession.onAttached(navigator)
                        // The slider follows what the replayer plays.
                        navigator.mapboxReplayer.registerObserver(replayObserver)
                    }
                    // The slider is there from the start, so the screen does not move when it comes.
                    _replay.value = ReplayPosition(seconds = 0.0, durationSeconds = 0.0)
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
                endTripTally()
                staleStart.end()
                tripSessionRunning = false
                // A seek still waiting for the navigator is given up with the drive.
                giveUpSeek()
                if (replaying) {
                    navigation?.let { navigator ->
                        navigator.mapboxReplayer.unregisterObserver(replayObserver)
                        replaySession.onDetached(navigator)
                    }
                    replaying = false
                }
                _replay.value = null
                navigation?.stopTripSession()
                releaseReroutes()
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
                cardContinuity.reset()
                thenTracker.reset()
                laneChoices.clear()
                laneRowLog.reset()
                divergeWatch.reset()
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

    /** A trip session starts: its summary starts over, and the phone's counters are read off the main thread. */
    private fun beginTripTally(simulated: Boolean) {
        val now = SystemClock.elapsedRealtime()
        tripTally.begin(now, simulated, offline = network.value.offlineReason() != OfflineReason.NONE)
        tripEnding = null
        startVitals = null
        lastSpeedLimit = null
        val trip = commitCount
        scope.launch {
            val read = withContext(Dispatchers.IO) { phoneVitals.read() }
            if (trip == commitCount) startVitals = read
        }
        writeTripMarker(now)
    }

    /** A progress update of the trip, one that carries the trip's own position. */
    private fun onTripProgress() {
        val now = SystemClock.elapsedRealtime()
        tripTally.onProgress(now)
        if (now - markerWrittenAt >= MARKER_EVERY_MILLIS) writeTripMarker(now)
    }

    private fun writeTripMarker(now: Long) {
        markerWrittenAt = now
        val fields = tripTally.fields(now, staleStart.staleUpdates)
        scope.launch(markerWrites) { tripMarker.write(fields) }
    }

    /**
     * The trip session stops: one "trip_summary" line, with how the trip ended and what it cost
     * the phone, and the marker goes. Nothing for a start whose session never began.
     */
    private fun endTripTally() {
        if (!tripTally.running) return
        val ending = tripEnding ?: TripEnding.END_IN_APP
        val fields = tripTally.fields(SystemClock.elapsedRealtime(), staleStart.staleUpdates)
        tripTally.end()
        tripEnding = null
        val start = startVitals
        scope.launch {
            val end = withContext(Dispatchers.IO) { phoneVitals.read() }
            debugLog.log("trip_summary", summaryLine(fields, ending, TripTally.vitalsFields(start, end)))
            withContext(markerWrites) { tripMarker.clear() }
        }
    }

    /**
     * "speedLimit" for the progress line of a drive, with the limit the navigator has for the road
     * under the car: on the first line of a trip and whenever it changes, and on no other line.
     */
    private fun speedLimitChange(active: Boolean): Map<String, Any?> {
        if (!active) return emptyMap()
        val info = lastMatch?.speedLimitInfo ?: return emptyMap()
        val limit = speedLimitFields(info.speed, info.unit.name, info.sign.name)
        if (limit == lastSpeedLimit) return emptyMap()
        lastSpeedLimit = limit
        return mapOf("speedLimit" to limit)
    }

    /** A map error of the drive's map, by the name of its kind, for the trip's summary. */
    internal fun onMapError(kind: String) {
        tripTally.onMapError(kind)
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
        val active = _guidanceState.value == GuidanceState.ACTIVE
        val fix = if (active) lastMatch?.enhancedLocation?.fixTime() else null
        // An update that still carries where the trip before left the car: logged, and otherwise
        // left alone, by the card, the Then chip, the lane choices and the start plan alike.
        if (active && staleStart.isStale(fix)) {
            staleStart.countStaleUpdate()
            debugLog.log("progress", progress.debugFields() + mapOf("match" to matchFields(), "staleStart" to true))
            return@RouteProgressObserver
        }
        // The same for one from before a seek of a simulated drive, or from while the navigator
        // knew no place for the car: the screen goes on with the new place.
        if (active && seekHold.isHeld(fix)) {
            debugLog.log("progress", progress.debugFields() + mapOf("match" to matchFields(), "replaySeek" to true))
            return@RouteProgressObserver
        }
        if (active) onTripProgress()
        if (active && replaying) routeMetres = progress.distanceTraveled.toDouble()
        // The run-up moves once for this update, here or in the map's camera if that asked first;
        // never for an update held back above.
        if (active) laneChoices.runUp(progress, lastMatch?.enhancedLocation?.speed, nowSeconds())
        val next = if (active) maneuverApi.getManeuvers(progress).value?.firstOrNull() else null
        // Without the next maneuver the card stays as it is, but never with a maneuver of the route
        // before a reroute or another change of route: it waits, dimmed, for the new route's.
        val card = if (active) cardContinuity.next(progress.navigationRoute.id, hasManeuver = next != null) else null
        val snapshot = when {
            next != null -> snapshotOf(progress, next)
            card == CardUpdate.PENDING -> progress.toPendingSnapshot()
            else -> null
        }
        debugLog.log(
            "progress",
            progress.debugFields() + mapOf(
                "match" to matchFields(),
                "thenShown" to snapshot?.showThen,
                "card" to card?.logName,
            ) + speedLimitChange(active),
        )
        if (active) logLaneChoices(progress)
        if (snapshot != null) {
            logLaneRow(progress, snapshot)
            _routeProgress.value = snapshot
        }
        // Every update the guards let through, the card's row of it known: was a diverge passed?
        if (active) watchDiverges(progress)
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
     * the trip before ([StaleStart]), or one from before a seek of a simulated drive ([SeekHold]);
     * the map's camera and drawn car leave such a position alone. False outside a trip.
     */
    internal fun carriesOldPosition(result: LocationMatcherResult): Boolean {
        val fix = result.enhancedLocation.fixTime()
        return staleStart.isStale(fix) || seekHold.isHeld(fix)
    }

    /**
     * How many seeks have moved a simulated drive in this run of the app. The first position after
     * the count changed that [carriesOldPosition] lets through is where the seek put the car.
     */
    internal val replaySeeks: Int get() = seekHold.seeks

    /**
     * The exit of the run-up on now, for the map's cameras, with [progress], the update they got:
     * the same latch the card's row comes from ([LaneChoices.runUp]), moved once for that update,
     * here if the engine's own observer has not yet. An update the screen leaves alone, from
     * before a trip's own first position or before a seek's new place, moves nothing. Null outside
     * a run-up.
     */
    internal fun runUpKey(progress: RouteProgress): String? {
        if (_guidanceState.value == GuidanceState.ACTIVE && !progressHeld()) {
            laneChoices.runUp(progress, lastMatch?.enhancedLocation?.speed, nowSeconds())
        }
        return laneChoices.runUpKey
    }

    /**
     * Whether the progress update that comes with the latest matched position is one the screen
     * leaves alone: it still carries where the trip before left the car ([StaleStart]), or it is
     * from before a seek's new place ([SeekHold]).
     */
    private fun progressHeld(): Boolean {
        val fix = lastMatch?.enhancedLocation?.fixTime()
        return staleStart.isStale(fix) || seekHold.isHeld(fix)
    }

    /**
     * The "match" fields of a "progress" or "off_route" line: the navigator's latest matched
     * position, and as "raw" where the phone's own position lay against it when it came. Null
     * before the first matched position.
     */
    private fun matchFields(): Map<String, Any?>? {
        val fields = lastMatch?.debugFields() ?: return null
        val raw = lastRawOffset ?: return fields
        return fields + ("raw" to raw)
    }

    /** The clock of the run-up's resume and of a seek, in seconds. */
    private fun nowSeconds(): Double = SystemClock.elapsedRealtime() / MILLIS_PER_SECOND.toDouble()

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

    /**
     * Writes a "lane_row" line when what the maneuver card's lane row draws changes with [snapshot],
     * the update about to be published ([LaneRowLog]): with every camera style and either lane zoom.
     */
    private fun logLaneRow(progress: RouteProgress, snapshot: RouteProgressSnapshot) {
        val runUpRowKey = snapshot.runUpLanes?.key
        val runUpMeters = if (runUpRowKey == null) null else laneChoices.ahead(progress).firstOrNull { it.key == runUpRowKey }?.metersAhead
        val fields = laneRowLog.onSnapshot(snapshot, laneChoices.choiceKey, runUpMeters, laneChoices.runUpKey, laneChoices.runUpStartKey) ?: return
        debugLog.log("lane_row", fields)
    }

    /**
     * What the maneuver card did with the words of the run-up's row whose key is [key]: [shown]
     * false when they did not fit beside the lanes and were left out. A change of that writes a
     * "lane_row" line ([LaneRowLog.onWordsShown]).
     */
    internal fun onRunUpWordsShown(key: String, shown: Boolean) {
        val fields = laneRowLog.onWordsShown(key, shown) ?: return
        debugLog.log("lane_row", fields)
    }

    /**
     * Gives the watch of diverges ([DivergeWatch]) [progress], an update the guards let through,
     * with what the lane choices, the card's row and the latest matched position say of it, and
     * writes the line that closes a watch. Reads only what the navigator already handed over.
     */
    private fun watchDiverges(progress: RouteProgress) {
        val leg = progress.currentLegProgress ?: return
        val stepProgress = leg.currentStepProgress ?: return
        val step = stepProgress.step
        val offRoute = progress.currentState == RouteProgressState.OFF_ROUTE
        val match = lastMatch
        val update = DivergeWatch.Update(
            nowSeconds = nowSeconds(),
            routeId = progress.navigationRoute.id,
            stepKey = "${progress.navigationRoute.id}/${leg.legIndex}/${stepProgress.stepIndex}",
            stepType = step?.maneuver()?.type(),
            stepModifier = step?.maneuver()?.modifier(),
            endsOnFreeway = step?.intersections()?.lastOrNull()?.isFreeway(),
            metersDone = progress.distanceTraveled.toDouble(),
            offRoute = offRoute,
            uncertain = progress.currentState == RouteProgressState.UNCERTAIN,
            points = laneChoices.ahead(progress),
            branches = if (offRoute) emptyList() else laneChoices.branchesAhead(stepProgress.distanceTraveled.toDouble()),
            probability = match?.roadEdgeMatchProbability?.toDouble(),
            sideM = rawSideMeters(),
            speedMps = match?.enhancedLocation?.speed,
            teleport = match?.isTeleport,
            card = laneRowLog.cardSource,
            words = laneRowLog.cardWords,
            runUp = laneChoices.runUpKey,
        )
        divergeWatch.onProgress(update)?.let(::logDiverge)
    }

    /**
     * Writes a "missed_maneuver" or "diverge_taken" line, with "match" holding only where the
     * phone's own position lay against the matched one ("raw"): no position goes into these lines.
     */
    private fun logDiverge(line: DivergeWatch.Line) {
        val raw = lastRawOffset
        debugLog.log(line.type, if (raw == null) line.fields else line.fields + ("match" to mapOf("raw" to raw)))
    }

    /** How far the phone's own position lay to the right of the latest matched one; null when not known. */
    private fun rawSideMeters(): Double? = lastRawOffset?.get("sideM") as? Double

    /**
     * The UI's view of [progress] with its [next] maneuver, the Then rule applied, and the run-up's
     * lane row or, outside a run-up, the lanes of a lane choice ahead.
     */
    private fun snapshotOf(progress: RouteProgress, next: MapboxManeuver): RouteProgressSnapshot {
        val decided = thenTracker.decide(progress.toSnapshot(next))
        // The speed the camera gets with this update, so the card's lanes and the close view come together.
        val speed = lastMatch?.enhancedLocation?.speed
        // The latch moved for this update already; this is its row.
        val runUp = laneChoices.runUp(progress, speed, nowSeconds())
        // None on a run-up: its row stands in for a lane choice's, and nothing of one is kept.
        val lanes = laneChoices.lanesAhead(laneChoices.ahead(progress), speed)
        // Added onto what the tracker decided: a copy of a fresh snapshot would lose the rule's
        // answer and the following maneuver's remembered object.
        return when {
            runUp != null -> decided.copy(runUpLanes = runUp)
            lanes != null -> decided.copy(laneChoiceAhead = lanes)
            else -> decided
        }
    }

    private val locationObserver = object : LocationObserver {
        // Called for every position the trip session hands the navigator, before the navigator has it.
        override fun onNewRawLocation(rawLocation: MapboxLocation) {
            val time = rawLocation.fixTime()
            staleStart.onSourceFix(time)
            seekHold.onSourceFix(time)
            lastRawFix = rawLocation
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            lastMatch = locationMatcherResult
            // Once for each matched position, for every line until the next.
            lastRawOffset = lastRawFix?.rawOffsetFields(locationMatcherResult.enhancedLocation, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
            if (tripTally.running && !carriesOldPosition(locationMatcherResult)) {
                val location = locationMatcherResult.enhancedLocation
                // The first position after a seek is where the car was put, not a stretch it drove.
                val seek = seekHold.seeks != tallySeeks
                tallySeeks = seekHold.seeks
                tripTally.onPosition(location.latitude, location.longitude, jump = locationMatcherResult.isTeleport || seek)
            }
        }
    }

    /**
     * The replay's newest played position, about once a second during a simulated drive, for the
     * slider: on the replayer's own clock, from the first position it still holds. While a seek
     * waits the replay is stopped, and the slider keeps showing where the seek goes.
     */
    private val replayObserver = ReplayEventsObserver { events ->
        val newest = events.lastOrNull { it is ReplayEventUpdateLocation } ?: return@ReplayEventsObserver
        val replayer = navigation?.mapboxReplayer ?: return@ReplayEventsObserver
        if (!replaying) return@ReplayEventsObserver
        replayStart = newest.eventTimestamp - replayer.eventSeconds(newest.eventTimestamp)
        val position = replayPosition(newest.eventTimestamp, replayStart, replayer.durationSeconds(), routeMetres)
        played = position
        if (seekAsk == null) _replay.value = position
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        tripTally.onOffRoute(offRoute)
        debugLog.log("off_route", mapOf("offRoute" to offRoute, "match" to matchFields()))
        // A diverge watched since the car passed it was missed, unless the guards hold the drive's
        // updates back: then the navigator does not know where the car is.
        if (offRoute && _guidanceState.value == GuidanceState.ACTIVE && !progressHeld()) {
            val match = lastMatch
            divergeWatch.onOffRoute(nowSeconds(), match?.roadEdgeMatchProbability?.toDouble(), rawSideMeters(), match?.enhancedLocation?.speed, match?.isTeleport)
                ?.let(::logDiverge)
        }
        // The navigator reroutes a car that left the route; the start plan steps back for it.
        if (offRoute) startRefresher.onReroute()
    }

    /** Route changes inside the navigator: the committed route, reroutes, refreshes and the final clean-up. */
    private val routesObserver = RoutesObserver { result ->
        val routes = result.navigationRoutes
        val reason = result.reason
        // A reroute, or any other route the navigator put in place of the one the trip started on.
        if (reason == RoutesExtra.ROUTES_UPDATE_REASON_REROUTE) {
            startRefresher.onReroute()
            tripTally.onReroute()
        }
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

    /**
     * Logs every trip session the SDK enters; this is what Mapbox bills. "simulated" is the trip's;
     * in the Idle state, which a trip ends with, it is that of the trip that just ended, and it is
     * left out before the first trip of the app's run.
     */
    private val sessionStateObserver = NavigationSessionStateObserver { state ->
        if (state !is NavigationSessionState.Idle) lastTripSimulated = replaying
        val simulated = if (state is NavigationSessionState.Idle) lastTripSimulated else replaying
        debugLog.log(
            "session_state",
            mapOf("state" to state.javaClass.simpleName, "sessionId" to state.sessionId, "simulated" to simulated).filterValues { it != null },
        )
        val kind = when (state) {
            is NavigationSessionState.ActiveGuidance -> TripKind.ACTIVE_GUIDANCE
            is NavigationSessionState.FreeDrive -> TripKind.FREE_DRIVE
            is NavigationSessionState.Idle -> {
                if (tripSessionRunning) onTripSessionStoppedOutsideApp()
                return@NavigationSessionStateObserver
            }
        }
        if (kind == TripKind.ACTIVE_GUIDANCE && tripTally.running) {
            tripTally.onTripId(state.sessionId)
            // The marker gets the trip's id at once, so that it says which trip a process ended in.
            writeTripMarker(SystemClock.elapsedRealtime())
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

        /** Under the app's no-backup files. */
        const val TRIP_MARKER_FILE = "trip-running"

        /** How often the marker of a running trip is written again. */
        const val MARKER_EVERY_MILLIS = 60_000L
    }
}

/**
 * One release of the replay slider that a seek goes to: from where the drive was, [from], to
 * [toSeconds] of its replay, which is [toTimestamp] on the replay's own clock, on the route
 * [routeId] the drive was on; asked for at [askedAt], in milliseconds since boot.
 */
private class SeekAsk(
    val from: ReplayPosition?,
    val toSeconds: Double,
    val toTimestamp: Double,
    val routeId: String?,
    val askedAt: Long,
)

/**
 * The "replay_seek" line of one release of the replay slider: where the drive was ([from]: `fromS`
 * of `durationS` seconds of its replay, `fromM` metres along the route), where it was sent
 * (`toS`; for a release that moved nothing, where the finger let go), whether the replay plays
 * from there (`ok`), how long that took from the release (`ms`), and else why not (`why`).
 * Numbers and names only.
 */
internal fun replaySeekFields(from: ReplayPosition?, toSeconds: Double?, ok: Boolean, why: ReplaySeek.Why?, ms: Long?): Map<String, Any?> =
    mapOf(
        "fromS" to from?.seconds?.let { Motion.round(it, 1) },
        "toS" to toSeconds?.let { Motion.round(it, 1) },
        "ok" to ok,
        "durationS" to from?.durationSeconds?.let { Motion.round(it, 1) },
        "fromM" to from?.metres?.let { Math.round(it) },
        "ms" to ms,
        "why" to why?.logName,
    ).filterValues { it != null }

/**
 * The replay of a simulated drive. It decodes the whole route at once, so the replayer holds the
 * drive from its start to its end, and a seek can go back as well as forward. By default it decodes
 * 100 km at a time and drops what it has played when it takes the next part, so only the part in
 * hand could be sought in. A route of a few hundred km takes a few MB of positions, one a second.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal fun wholeRouteReplay(): ReplayRouteSession = ReplayRouteSession().apply {
    setOptions(getOptions().toBuilder().decodeMinDistance(Double.MAX_VALUE).build())
}

/**
 * The slider's position for the replay's newest played position at [timestamp], with [first] the
 * first timestamp the replayer holds and [duration] how many seconds it holds after that.
 */
internal fun replayPosition(timestamp: Double, first: Double, duration: Double, metres: Double?): ReplayPosition {
    val held = max(duration, 0.0)
    return ReplayPosition(seconds = (timestamp - first).coerceIn(0.0, held), durationSeconds = held, metres = metres)
}

/**
 * The "trip_summary" line: the trip's id and whether it was simulated, how it ended, then the rest
 * of [fields] ([TripTally.fields]) and what the trip cost the phone ([TripTally.vitalsFields]).
 */
internal fun summaryLine(fields: Map<String, Any?>, ending: TripEnding, vitals: Map<String, Any?>): Map<String, Any?> {
    val line = LinkedHashMap<String, Any?>()
    line["trip"] = fields["trip"]
    line["simulated"] = fields["simulated"]
    line["ending"] = ending.logName
    line.putAll(fields)
    line.putAll(vitals)
    return line
}
