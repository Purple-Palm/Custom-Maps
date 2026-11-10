package io.github.purplepalm.custommaps.engine.mapbox.map

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import com.mapbox.common.Cancelable
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.Style
import com.mapbox.maps.StyleDataLoadedType
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import io.github.purplepalm.custommaps.engine.mapbox.AccessErrors
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.GuidanceCamera
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.GuidancePuck
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.NavigationCameraAdapter
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.PlatformPuck
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.SmoothNavigationCamera
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.nav.MapRenderer
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.IdleFollow
import io.github.purplepalm.custommaps.nav.debug.Cadence
import io.github.purplepalm.custommaps.nav.debug.CameraSteps
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.RepeatFolder
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The phone's map: Mapbox Standard at night with 3D buildings, the device location, the routes of
 * the preview and, during guidance, the active route with traffic and the next maneuver arrow.
 *
 * Before guidance the puck comes from the platform location provider, so no trip session (and no
 * free drive billing) is needed just to see where you are. The camera follows the car there too,
 * from its first position until the driver moves the map, and again after My location
 * ([IdleFollow]); that takes no trip session and no request either. The view's lifecycle follows
 * the hosting activity; say with [setVisible] when the map is on screen, and call [release] when the
 * view leaves the composition.
 *
 * During guidance, while the map is on screen, it writes a "camera" line to the debug log once a
 * second: where the camera is and where the viewport data source wants it, how smoothly the camera
 * and the puck moved, and how fast the map rendered (see [cameraSample]).
 */
class PhoneMap internal constructor(
    context: Context,
    private val engine: MapboxNavigationEngine,
    private val debugLog: DebugLog,
) : MapRenderer {

    private val mapView = MapView(context, MapInitOptions(context, styleUri = null))
    private val mapboxMap = mapView.mapboxMap
    private val scope = MainScope()
    private val density = context.resources.displayMetrics.density

    /** The map as an Android view, to place in the screen layout. */
    val view: View get() = mapView

    private var style: Style? = null
    private var lastPuck: Point? = null
    private var lastBearing: Double? = null

    /** Whether the camera stays on the car while no route is on the map. */
    private val idleFollow = IdleFollow()

    /** The puck's positions outside guidance: the phone's own location, without a trip session. */
    private val platformPuck = PlatformPuck(context)

    /** The puck's positions during guidance, from the navigator. */
    private val guidancePuck = GuidancePuck()

    /** The navigator's routes; they change on reroute and are empty outside guidance. */
    private var navigatorRoutes: List<NavigationRoute> = emptyList()

    /**
     * Read from the engine rather than copied from its flow: a map created during a drive gets the
     * navigator's routes as soon as it registers, before a collector would have run.
     */
    private val guidanceState: GuidanceState get() = engine.guidanceState.value

    private val routeLineOptions = MapboxRouteLineApiOptions.Builder().vanishingRouteLineEnabled(true).build()
    private val routeLineApi = MapboxRouteLineApi(routeLineOptions)
    private val routeLineView = MapboxRouteLineView(
        MapboxRouteLineViewOptions.Builder(context)
            .routeLineColorResources(MapPalette.routeLineColors)
            .slotName(ROUTE_SLOT)
            .build(),
    )
    private val routeArrowApi = MapboxRouteArrowApi()
    private val routeArrowView = MapboxRouteArrowView(
        RouteArrowOptions.Builder(context)
            .withArrowColor(MapPalette.PLATINUM)
            .withArrowCasingColor(MapPalette.GRAPHITE)
            .withSlotName(ROUTE_SLOT)
            .build(),
    )

    /** When the traveled part of the route line last took the puck's position, in nanoseconds. */
    private var routeLineUpdatedAt = 0L

    private val viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)

    /**
     * How the camera moves. The map starts with the style Settings starts with, so that the stored
     * choice, which [setCameraStyle] brings a moment later, mostly finds its camera already there.
     */
    private var cameraStyle = CameraStyle.DEFAULT

    /** Moves the map during guidance and in the route preview, in the way of [cameraStyle]. */
    private var camera: GuidanceCamera

    /** Whether the map is on screen, as the hosting screen says with [setVisible]. */
    private var visible = false

    /**
     * The puck position the data source was last given outside guidance; null once the navigator's
     * positions have replaced it.
     */
    private var fedPuck: Point? = null

    /** When the route preview last compared the puck with [fedPuck], in milliseconds since boot. */
    private var puckCheckedAt = 0L

    private val _cameraMode = MutableStateFlow(CameraMode.IDLE)
    override val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _accessDenied = MutableStateFlow(false)

    /**
     * True once Mapbox refused the access token for the style or tiles. The map then stays empty;
     * a new token recreates the screen, and this map with it.
     */
    val accessDenied: StateFlow<Boolean> = _accessDenied.asStateFlow()

    /** Tile and style errors; offline, the same error comes for every tile, so repeats are only counted. */
    private val mapErrorFolder = RepeatFolder(debugLog, scope)

    // What the once-a-second camera line measures. Everything here runs on the main thread.
    private var cameraSampling: Job? = null
    private val cameraCadence = Cadence()
    private val cameraSteps = CameraSteps()
    private val puckCadence = Cadence()
    private var fixes = 0
    private var lastFixAt = 0L
    private var lastKeyPoints = 0
    private var padding: ViewportPadding? = null
    private var paddingChanges = 0
    private var transitionStartedAt = 0L

    // The map's own frame rate, reported about once a second on the render thread while it draws.
    @Volatile private var renderFps = Double.NaN

    @Volatile private var renderFpsAt = 0L

    private val puckPositionListener = OnIndicatorPositionChangedListener { point -> onPuckMoved(point) }
    private val puckBearingListener = OnIndicatorBearingChangedListener { bearing -> lastBearing = bearing }

    private val mapClickListener = OnMapClickListener { point ->
        if (guidanceState == GuidanceState.PREVIEW) selectRouteNear(point)
        false
    }

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.registerRoutesObserver(routesObserver)
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.registerLocationObserver(locationObserver)
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.unregisterLocationObserver(locationObserver)
        }
    }

    /** During guidance the navigator's routes are drawn instead of the preview. */
    private val routesObserver = RoutesObserver { result ->
        navigatorRoutes = result.navigationRoutes
        if (guidanceState == GuidanceState.ACTIVE) showNavigatorRoutes()
    }

    private val routeProgressObserver = RouteProgressObserver { progress -> onRouteProgress(progress) }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            if (guidanceState != GuidanceState.ACTIVE) return
            fixes += 1
            lastFixAt = SystemClock.elapsedRealtimeNanos()
            lastKeyPoints = locationMatcherResult.keyPoints.size
            guidancePuck.onFix(locationMatcherResult)
            camera.onLocation(locationMatcherResult.enhancedLocation, locationMatcherResult.isTeleport)
        }
    }

    /**
     * Tile and style failures go to the debug log, the first of each kind in full and the rest as a
     * count once a minute; a refused token is also shown on screen, checked on every error.
     */
    private val mapErrors = mapboxMap.subscribeMapLoadingError { error ->
        if (AccessErrors.isAccessDenied(error.message)) _accessDenied.value = true
        mapErrorFolder.record(
            type = "map_error",
            key = RepeatFolder.keyOf(error.type.name, error.message),
            fields = mapOf("errorType" to error.type.name, "message" to error.message, "source" to error.sourceId),
            origin = error.sourceId,
        )
    }

    /**
     * Every camera frame while the camera line is being sampled: how often it moved, and its largest
     * step. Subscribed only then, so browsing the map costs no call per frame.
     */
    private var cameraChanges: Cancelable? = null

    private val cameraListener = object : GuidanceCamera.Listener {
        override fun onCameraState(mode: CameraMode, state: String, moving: Boolean) {
            _cameraMode.value = mode
            // How long the transition into this state took ("ms" on the state it ended in).
            val now = SystemClock.elapsedRealtime()
            val tookMs = if (transitionStartedAt != 0L && !moving) now - transitionStartedAt else null
            transitionStartedAt = if (moving) now else 0L
            debugLog.log("camera_state", mapOf("state" to state, "ms" to tookMs, "style" to cameraStyle).filterValues { it != null })
        }

        // Says why the camera stopped following: a gesture, the compass, or something else.
        override fun onCameraTaken(owner: String?, animator: String, state: String) {
            debugLog.log("camera_taken", mapOf("owner" to owner, "animator" to animator, "from" to state))
            // The map is the driver's now, also on the browsing map.
            idleFollow.onMapMoved()
        }
    }

    init {
        // First: everything registered below may call back, and what it calls expects a camera.
        camera = createCamera(cameraStyle)

        mapView.alpha = 0f
        mapView.scalebar.enabled = false
        mapboxMap.subscribeStyleDataLoaded { event ->
            if (event.type == StyleDataLoadedType.STYLE) MapPalette.applyNight(mapboxMap)
        }
        mapboxMap.loadStyle(Style.STANDARD) { loaded ->
            debugLog.log("map_style_loaded", mapOf("refreshHz" to mapView.display?.refreshRate))
            style = loaded
            mapView.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            render(guidanceState)
        }

        // While the camera follows on the browsing map, the two Mapbox styles move it with the puck's
        // own updates, and the data source learns each position, in every style: the route's camera
        // then starts from where the car is. Nothing is computed from it before a route is shown.
        platformPuck.onGlide = { target ->
            if (idleFollow.following) {
                camera.onPuckGlide(target, PlatformPuck.GLIDE_MS)
                feedPuck(target)
            }
        }
        mapView.location.setLocationProvider(platformPuck)
        mapView.location.updateSettings {
            enabled = true
            locationPuck = createDefault2DPuck(withBearing = true)
            puckBearingEnabled = true
            puckBearing = PuckBearing.COURSE
        }
        mapView.location.addOnIndicatorPositionChangedListener(puckPositionListener)
        mapView.location.addOnIndicatorBearingChangedListener(puckBearingListener)

        // Called on the render thread, about once a second while the map draws.
        mapView.setOnFpsChangedListener { fps ->
            renderFps = fps
            renderFpsAt = SystemClock.elapsedRealtimeNanos()
        }
        mapView.gestures.addOnMapClickListener(mapClickListener)

        MapboxNavigationApp.registerObserver(navigationObserver)
        scope.launch {
            combine(engine.guidanceState, engine.previewRoutes) { state, _ -> state }.collect { state -> render(state) }
        }
    }

    override fun setCameraMode(mode: CameraMode) {
        debugLog.log("camera_request", mapOf("mode" to mode, "from" to camera.state, "guidance" to guidanceState))
        when (mode) {
            CameraMode.FOLLOWING -> when (guidanceState) {
                GuidanceState.ACTIVE -> camera.requestFollowing()
                // My location: the camera goes to the car and stays on it.
                GuidanceState.IDLE, GuidanceState.ARRIVED -> if (idleFollow.onMyLocation()) followCar()
                // The preview shows the route, and its screen has no button for this.
                GuidanceState.PREVIEW -> Unit
            }
            CameraMode.OVERVIEW ->
                if (guidanceState == GuidanceState.ACTIVE || guidanceState == GuidanceState.PREVIEW) camera.requestOverview()
            CameraMode.IDLE -> {
                idleFollow.onMapMoved()
                camera.requestIdle()
            }
        }
    }

    override fun setViewportPadding(padding: ViewportPadding) {
        // The same padding again would only make the camera start its move over.
        if (padding == this.padding) return
        // A new padding moves the camera at once; the next camera line says to what, and how often.
        this.padding = padding
        paddingChanges += 1
        val insets = EdgeInsets(padding.top.toDouble(), padding.left.toDouble(), padding.bottom.toDouble(), padding.right.toDouble())
        viewportDataSource.followingPadding = insets
        viewportDataSource.overviewPadding = insets
        viewportDataSource.evaluate()
    }

    override fun setCameraStyle(style: CameraStyle) {
        if (style == cameraStyle) return
        // What the camera was doing is asked of the new one again, so a drive goes on being followed.
        val mode = _cameraMode.value
        camera.destroy()
        // The old camera took back what it had set on the data source, but the frame the data source
        // holds was computed with it. The new camera gets a frame of its own to start from.
        viewportDataSource.evaluate()
        cameraStyle = style
        // The new camera starts idle; a transition the old one was in ends with it.
        transitionStartedAt = 0L
        camera = createCamera(style)
        when (mode) {
            CameraMode.FOLLOWING -> camera.requestFollowing()
            CameraMode.OVERVIEW -> camera.requestOverview()
            // On the browsing map the new camera stays on the car if the old one did.
            CameraMode.IDLE -> if (idleFollow.following) followCar()
        }
    }

    /**
     * Puts the Mapbox logo and the attribution button in a fixed strip at the bottom left of the map,
     * just above [bottomInset] pixels of navigation bar or keyboard. The screen keeps that strip free
     * of panels ([ORNAMENT_STRIP_DP]), so the logo is never covered and does not move with the panels.
     * Mapbox lets apps move the logo but not resize or fade it.
     */
    fun setOrnamentBottomInset(bottomInset: Int) {
        val bottom = bottomInset + ORNAMENT_MARGIN_DP * density
        val left = ORNAMENT_SIDE_DP * density
        mapView.logo.updateSettings {
            position = Gravity.BOTTOM or Gravity.START
            marginLeft = left
            marginBottom = bottom
        }
        mapView.attribution.updateSettings {
            position = Gravity.BOTTOM or Gravity.START
            marginLeft = left + (LOGO_WIDTH_DP + ORNAMENT_GAP_DP) * density
            marginBottom = bottom
        }
    }

    /**
     * Puts the compass just below [topInset] pixels of panels at the top of the map. It follows the
     * panels as they are, also when the camera's padding does not.
     */
    fun setCompassTopInset(topInset: Int) {
        mapView.compass.updateSettings { marginTop = topInset + ORNAMENT_MARGIN_DP * density }
    }

    /**
     * Shows the puck right after the driver grants location access. Without this the platform
     * provider notices the new permission only at its next check, which can take a few seconds.
     */
    fun onLocationPermissionGranted() {
        platformPuck.restart()
    }

    /**
     * Call with false while the map is not on screen, as when the hosting activity is stopped, and
     * with true when it is again. A camera that draws its own frames draws none in between. The map
     * starts as not on screen.
     */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        camera.setVisible(visible)
    }

    /** Stops listening to the navigator. The map view itself follows the hosting activity's lifecycle. */
    fun release() {
        stopCameraSampling()
        mapErrors.cancel()
        // The counts of the current minute, before the windows' timers go with the scope.
        mapErrorFolder.flush()
        scope.cancel()
        MapboxNavigationApp.unregisterObserver(navigationObserver)
        platformPuck.onGlide = null
        camera.destroy()
        // Nothing feeds the data source any more. Without this it keeps its style subscription, and a
        // frame still waiting for the map's size keeps the data source and the map alive.
        viewportDataSource.onDestroy()
        mapView.location.removeOnIndicatorPositionChangedListener(puckPositionListener)
        mapView.location.removeOnIndicatorBearingChangedListener(puckBearingListener)
        mapView.gestures.removeOnMapClickListener(mapClickListener)
        routeLineApi.cancel()
        routeLineView.cancel()
    }

    private fun render(state: GuidanceState) {
        if (style == null) return
        if (state == GuidanceState.ACTIVE) startCameraSampling() else stopCameraSampling()
        when (state) {
            GuidanceState.PREVIEW -> {
                useLocationProvider(guidance = false)
                val routes = engine.previewRoutes.value
                if (routes.isEmpty()) return
                drawRoutes(routes)
                clearArrows()
                viewportDataSource.onRouteChanged(routes.first())
                // The car may have moved since the map last centered on it.
                if (tracksPuckInPreview) lastPuck?.let(::feedPuck)
                viewportDataSource.evaluate()
                idleFollow.onRouteShown()
                camera.requestOverview()
            }
            GuidanceState.ACTIVE -> {
                useLocationProvider(guidance = true)
                // From here on the data source gets the navigator's positions.
                fedPuck = null
                // Also brings back the route when the map is created again during a drive.
                showNavigatorRoutes()
                idleFollow.onRouteShown()
                camera.requestFollowing()
            }
            GuidanceState.IDLE, GuidanceState.ARRIVED -> {
                useLocationProvider(guidance = false)
                clearRoutes()
                camera.onGuidanceEnded()
                // A camera that still showed the route goes back to the car and stays on it. One the
                // driver had taken the map from, and one that is on the car already, are left alone.
                val showedRoute = !camera.isIdle && !idleFollow.following
                val routeGone = idleFollow.onRouteGone(routeCameraHadMap = showedRoute)
                // The car may have had its position before the map was ready, or during the drive.
                val located = lastPuck != null && idleFollow.onCarLocated()
                if (routeGone || located) {
                    followCar()
                } else if (showedRoute) {
                    // The car has no position to go to yet.
                    camera.requestIdle()
                }
            }
        }
    }

    private fun onRouteProgress(progress: RouteProgress) {
        if (guidanceState != GuidanceState.ACTIVE) return
        val style = style ?: return
        routeLineApi.updateWithRouteProgress(progress) { update -> routeLineView.renderRouteLineUpdate(style, update) }
        routeArrowView.renderManeuverUpdate(style, routeArrowApi.addUpcomingManeuverArrow(progress))
        camera.onRouteProgress(progress)
    }

    private fun onPuckMoved(point: Point) {
        lastPuck = point
        engine.lastPuckPosition = point
        if (cameraSampling != null) puckCadence.tick(SystemClock.elapsedRealtimeNanos())
        val style = style ?: return
        when (guidanceState) {
            GuidanceState.ACTIVE -> updateTraveledRouteLine(style, point)
            GuidanceState.PREVIEW -> if (tracksPuckInPreview) trackPuckInPreview(point)
            // True once: at the car's first position, and when a position comes after it was asked for.
            GuidanceState.IDLE, GuidanceState.ARRIVED -> if (idleFollow.onCarLocated()) followCar()
        }
    }

    /**
     * Outside guidance the data source learns where the car is only while the camera follows the
     * car. A preview opened after the driver had taken the map would frame the place where that
     * was, and so would one the car drives on in; Start would fly there before it found the car. So
     * in the preview the data source gets the puck's position: when the preview opens, and then
     * whenever the car has moved. Mapbox standard goes without, as it always has.
     */
    private val tracksPuckInPreview: Boolean get() = cameraStyle != CameraStyle.MAPBOX_STANDARD

    /** Called on every frame of the puck: looks once a second, and acts only when the car has moved. */
    private fun trackPuckInPreview(point: Point) {
        val now = SystemClock.elapsedRealtime()
        if (now - puckCheckedAt < PREVIEW_TRACK_MS) return
        puckCheckedAt = now
        val fed = fedPuck
        if (fed != null && Geo.meters(fed.latitude(), fed.longitude(), point.latitude(), point.longitude()) < PREVIEW_TRACK_METERS) return
        feedPuck(point)
        viewportDataSource.evaluate()
    }

    /**
     * Moves the end of the traveled part of the route line to the puck. The puck moves on every
     * frame, but the route line takes a new point only every 62.5 ms and answers a call in between
     * with an error, which the view would only log. So a call is made only when one is due, and only
     * an accepted update is rendered.
     */
    private fun updateTraveledRouteLine(style: Style, point: Point) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - routeLineUpdatedAt < routeLineOptions.vanishingRouteLineUpdateIntervalNano) return
        val update = routeLineApi.updateTraveledRouteLine(point)
        // Read again: the route line counts its interval from the end of its own work.
        routeLineUpdatedAt = SystemClock.elapsedRealtimeNanos()
        if (update.isValue) routeLineView.renderRouteLineUpdate(style, update)
    }

    private fun showNavigatorRoutes() {
        val routes = navigatorRoutes
        if (routes.isEmpty()) return
        drawRoutes(routes)
        viewportDataSource.onRouteChanged(routes.first())
        viewportDataSource.evaluate()
    }

    private fun drawRoutes(routes: List<NavigationRoute>) {
        val style = style ?: return
        routeLineApi.setNavigationRoutes(routes) { value -> routeLineView.renderRouteDrawData(style, value) }
    }

    private fun clearRoutes() {
        val style = style ?: return
        routeLineApi.clearRouteLine { value -> routeLineView.renderClearRouteLineValue(style, value) }
        clearArrows()
        viewportDataSource.clearRouteData()
        viewportDataSource.evaluate()
    }

    private fun clearArrows() {
        val style = style ?: return
        routeArrowView.render(style, routeArrowApi.clearArrows())
    }

    private fun selectRouteNear(point: Point) {
        routeLineApi.findClosestRoute(point, mapboxMap, ROUTE_TAP_RADIUS_DP * density) { result ->
            result.value?.navigationRoute?.id?.let(engine::selectRoute)
        }
    }

    private fun useLocationProvider(guidance: Boolean) {
        val current = mapView.location.getLocationProvider()
        // Guidance starts: without this the puck would first jump to where the last drive ended.
        if (guidance && current !== guidancePuck) guidancePuck.newDrive()
        val wanted = if (guidance) guidancePuck else platformPuck
        if (current !== wanted) mapView.location.setLocationProvider(wanted)
    }

    /** Sends the camera to the car on the browsing map, to stay on it until the driver moves the map. */
    private fun followCar() {
        val puck = lastPuck ?: return
        camera.requestIdleFollow(puck)
        // The data source learns where the car is now, and then with every position while this lasts.
        feedPuck(puck)
    }

    /** Tells the data source where the car is, outside guidance. */
    private fun feedPuck(point: Point) {
        fedPuck = point
        viewportDataSource.onLocationChanged(
            Location.Builder().latitude(point.latitude()).longitude(point.longitude()).bearing(lastBearing).build(),
        )
    }

    /**
     * The camera of a style, and the puck that goes with it: Mapbox standard keeps the SDK's puck,
     * the other styles glide it a little longer and let it jump when the matched position jumps.
     */
    private fun createCamera(style: CameraStyle): GuidanceCamera {
        val standard = style == CameraStyle.MAPBOX_STANDARD
        guidancePuck.glideMillis = if (standard) GuidancePuck.STANDARD_GLIDE_MS else GuidancePuck.SMOOTH_GLIDE_MS
        guidancePuck.jumpOnTeleport = !standard
        val created = when (style) {
            CameraStyle.MAPBOX_STANDARD, CameraStyle.MAPBOX_TUNED ->
                NavigationCameraAdapter(mapboxMap, mapView.camera, viewportDataSource, tuned = !standard, cameraListener)
            CameraStyle.SMOOTH_FOLLOW ->
                SmoothNavigationCamera(mapboxMap, mapView.location, mapView.camera, viewportDataSource, CameraTuning.PHONE, cameraListener)
        }
        created.setVisible(visible)
        return created
    }

    /** Starts the once-a-second camera line; does nothing if it is already running. */
    private fun startCameraSampling() {
        if (cameraSampling != null) return
        // Each guidance measures afresh: nothing carries over from the last one, minutes or hours ago.
        cameraCadence.reset()
        puckCadence.reset()
        cameraSteps.reset()
        fixes = 0
        lastFixAt = 0L
        lastKeyPoints = 0
        // Drops what the camera counted before this guidance.
        camera.diagnostics()
        // The first line says what the padding is.
        paddingChanges = 1
        startFrameStats()
        cameraChanges = mapboxMap.subscribeCameraChanged { event ->
            cameraCadence.tick(SystemClock.elapsedRealtimeNanos())
            val shown = event.cameraState
            cameraSteps.onCamera(shown.center.latitude(), shown.center.longitude(), shown.zoom, shown.bearing, shown.pitch)
        }
        cameraSampling = scope.launch {
            while (true) {
                delay(CAMERA_SAMPLE_MS)
                // Taken even when hidden, so every logged line covers one second. In the background
                // the map draws nothing; the "activity" lines say when that was.
                val sample = cameraSample()
                if (mapView.isShown) debugLog.log("camera", sample)
            }
        }
    }

    private fun stopCameraSampling() {
        cameraSampling?.cancel() ?: return
        cameraSampling = null
        cameraChanges?.cancel()
        cameraChanges = null
        stopFrameStats()
    }

    /**
     * The camera line, about 500 bytes a second (1.8 MB an hour of driving). Lags and steps are in
     * meters, gaps in milliseconds. A smooth camera moves on every frame ("camUpdates" close to
     * "render.frames", "camGapMs" near one frame); a stepping one shows long gaps and large "steps".
     * "to" is the frame the viewport data source asks for. "renderFps" is the map's own frame rate
     * against the screen's refresh; "animatorsEnabled" is false when Android's animations are
     * switched off. "style" is the camera style in use, so drives in different styles can be compared.
     *
     * The camera of the style adds its own fields. The two Mapbox styles: "frameUpdates", the camera
     * animations started, and "frameUpdatesCut", those restarted before they finished. Smooth follow:
     * "camFps", "camMaxGapMs" and "camWrites" (its frames, the longest time between two of them and
     * how many set the map's camera), "focus" and "focusGoal" (how far the view is tilted onto the
     * next maneuver, from 0 to 1, and where that is going), "zoomTarget", "bearingError" (the bearing
     * still to turn) and "onPuck" (1 while the camera centers on the drawn car, 0 while it frames a
     * maneuver or the route).
     */
    private fun cameraSample(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtimeNanos()
        val shown = mapboxMap.cameraState
        val target = viewportDataSource.getViewportData().cameraForFollowing
        val puck = lastPuck
        val fix = guidancePuck.lastLocation
        // False until this guidance has had a fix.
        val hasFix = lastFixAt != 0L
        val cameraMoves = cameraCadence.take(now)
        val puckMoves = puckCadence.take(now)
        val fps = renderFps.takeIf { renderFpsAt != 0L && now - renderFpsAt <= FPS_FRESH_NANOS }
        val sample = mapOf(
            "style" to cameraStyle,
            "state" to camera.state,
            "zoom" to Motion.round(shown.zoom, 2),
            "pitch" to Motion.round(shown.pitch, 1),
            "bearing" to Motion.round(shown.bearing, 1),
            "to" to mapOf(
                "zoom" to target.zoom?.let { Motion.round(it, 2) },
                "pitch" to target.pitch?.let { Motion.round(it, 1) },
                "bearing" to target.bearing?.let { Motion.round(it, 1) },
                "m" to target.center?.let { metersBetween(shown.center, it) },
            ).filterValues { it != null },
            // How far the puck trails the latest map-matched fix (it glides there over a second), how
            // long ago that fix came, and how far the camera center is from the puck. The first two
            // and "keyPoints" are left out until this guidance has had a fix.
            "puckLagM" to if (hasFix && puck != null && fix != null) metersBetween(puck, Point.fromLngLat(fix.longitude, fix.latitude)) else null,
            "sinceFixMs" to if (hasFix) (now - lastFixAt) / NANOS_PER_MILLI else null,
            "camLagM" to puck?.let { metersBetween(shown.center, it) },
            "camUpdates" to cameraMoves.count,
            "camGapMs" to cameraMoves.longestGapMillis,
            "puckUpdates" to puckMoves.count,
            "puckGapMs" to puckMoves.longestGapMillis,
            "steps" to cameraSteps.take(),
            "fixes" to fixes,
            "keyPoints" to lastKeyPoints.takeIf { hasFix },
        ) + camera.diagnostics() + mapOf(
            "padding" to padding?.takeIf { paddingChanges > 0 }?.let { listOf(it.top, it.left, it.bottom, it.right) },
            "paddingChanges" to paddingChanges.takeIf { it > 0 },
            "render" to takeFrameStats(),
            "renderFps" to fps?.let { Motion.round(it, 1) },
            "animatorsEnabled" to ValueAnimator.areAnimatorsEnabled(),
            "hz" to mapView.display?.refreshRate?.let { Math.round(it) },
        )
        fixes = 0
        paddingChanges = 0
        return sample.filterValues { it != null }
    }

    // The render thread statistics are @MapboxExperimental: opted in here, for the camera line only.
    @OptIn(MapboxExperimental::class)
    private fun startFrameStats() {
        val recorder = mapView.renderThreadStatsRecorder
        if (!recorder.isRecording) recorder.start()
    }

    /**
     * The frames of the last second: how many the map drew, how long the render thread took for them,
     * and how many screen refreshes it missed. Mapbox advises sessions of one to two seconds.
     */
    @OptIn(MapboxExperimental::class)
    private fun takeFrameStats(): Map<String, Any?>? {
        val recorder = mapView.renderThreadStatsRecorder
        if (!recorder.isRecording) return null
        val stats = recorder.stop()
        recorder.start()
        val frames = stats.frameTimeList
        return mapOf(
            "frames" to frames.size,
            "fps" to Motion.round(frames.size * 1_000.0 / stats.totalTime.coerceAtLeast(1L), 1),
            "missedVsync" to stats.totalSkippedVsync,
            "p50Ms" to stats.percentile50?.let { Motion.round(it, 1) },
            "p95Ms" to stats.percentile95?.let { Motion.round(it, 1) },
            "maxMs" to frames.maxOrNull()?.let { Motion.round(it, 1) },
        )
    }

    /** The recorder keeps every frame time until stopped, so it never runs outside guidance. */
    @OptIn(MapboxExperimental::class)
    private fun stopFrameStats() {
        val recorder = mapView.renderThreadStatsRecorder
        if (recorder.isRecording) recorder.stop()
    }

    private fun metersBetween(from: Point, to: Point): Double =
        Motion.round(Geo.meters(from.latitude(), from.longitude(), to.latitude(), to.longitude()), 1)

    companion object {
        /** Margin around the logo and attribution; the logo itself is 85 by 21 dp in this SDK. */
        private const val ORNAMENT_MARGIN_DP = 6f
        private const val ORNAMENT_SIDE_DP = 12f
        private const val ORNAMENT_GAP_DP = 4f
        private const val LOGO_WIDTH_DP = 85f
        private const val LOGO_HEIGHT_DP = 21f

        /** Height of the strip above the bottom inset that the logo and attribution need; panels stay above it. */
        const val ORNAMENT_STRIP_DP = ORNAMENT_MARGIN_DP + LOGO_HEIGHT_DP + ORNAMENT_MARGIN_DP

        /** Standard style slot above roads and below 3D buildings and labels. */
        private const val ROUTE_SLOT = "middle"
        private const val ROUTE_TAP_RADIUS_DP = 30f
        private const val FADE_IN_MS = 250L
        private const val CAMERA_SAMPLE_MS = 1_000L

        /** In the preview the data source follows the puck: it looks this often and acts past this distance. */
        private const val PREVIEW_TRACK_MS = 1_000L
        private const val PREVIEW_TRACK_METERS = 5.0
        private const val NANOS_PER_MILLI = 1_000_000L

        /** The map reports its frame rate about once a second; an older value means it stopped drawing. */
        private const val FPS_FRESH_NANOS = 2_000_000_000L
    }
}
