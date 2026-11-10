package io.github.purplepalm.custommaps.engine.mapbox.map

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import com.mapbox.common.Cancelable
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.Style
import com.mapbox.maps.StyleDataLoadedType
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.CameraAnimationsLifecycleListener
import com.mapbox.maps.plugin.animation.CameraAnimatorType
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.DefaultLocationProvider
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
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import io.github.purplepalm.custommaps.engine.mapbox.AccessErrors
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.nav.MapRenderer
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.debug.Cadence
import io.github.purplepalm.custommaps.nav.debug.CameraSteps
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.RepeatFolder
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.CameraMode
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
 * free drive billing) is needed just to see where you are. The view's lifecycle follows the hosting
 * activity; call [release] when the view leaves the composition.
 *
 * During guidance it writes a "camera" line to the debug log once a second: where the camera is and
 * where the navigation camera is taking it, how smoothly the camera and the puck moved, and how fast
 * the map rendered (see [cameraSample]).
 */
class PhoneMap internal constructor(
    context: Context,
    private val engine: MapboxNavigationEngine,
    private val debugLog: DebugLog,
) : MapRenderer {

    private val appContext = context.applicationContext
    private val mapView = MapView(context, MapInitOptions(context, styleUri = null))
    private val mapboxMap = mapView.mapboxMap
    private val scope = MainScope()
    private val density = context.resources.displayMetrics.density

    /** The map as an Android view, to place in the screen layout. */
    val view: View get() = mapView

    private var style: Style? = null
    private var lastPuck: Point? = null
    private var lastBearing: Double? = null
    private var centeredOnFirstFix = false

    private var defaultLocationProvider = DefaultLocationProvider(appContext)
    private val navigationLocationProvider = NavigationLocationProvider()

    /** The navigator's routes; they change on reroute and are empty outside guidance. */
    private var navigatorRoutes: List<NavigationRoute> = emptyList()

    /**
     * Read from the engine rather than copied from its flow: a map created during a drive gets the
     * navigator's routes as soon as it registers, before a collector would have run.
     */
    private val guidanceState: GuidanceState get() = engine.guidanceState.value

    private val routeLineApi = MapboxRouteLineApi(
        MapboxRouteLineApiOptions.Builder().vanishingRouteLineEnabled(true).build(),
    )
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

    private val viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)
    private val navigationCamera = NavigationCamera(mapboxMap, mapView.camera, viewportDataSource)

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
    private var frameUpdates = 0
    private var frameUpdatesCut = 0
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
            navigationLocationProvider.changePosition(locationMatcherResult.enhancedLocation, locationMatcherResult.keyPoints)
            viewportDataSource.onLocationChanged(locationMatcherResult.enhancedLocation)
            viewportDataSource.evaluate()
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

    /**
     * Counts the navigation camera's frame updates (each one a new animation of about a second) and
     * the ones cancelled before they finished, because the next update or a gesture came first. Also
     * logs who takes the camera from the navigation camera: any other animation (a gesture, the
     * compass) turns it to IDLE. Added before [NavigationBasicGesturesHandler], so it still sees the
     * state the camera was in.
     */
    private val cameraAnimations = object : CameraAnimationsLifecycleListener {
        override fun onAnimatorStarting(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) {
            if (owner == NavigationCamera.NAVIGATION_CAMERA_OWNER) {
                if (type == CameraAnimatorType.CENTER) frameUpdates += 1
            } else if (navigationCamera.state != NavigationCameraState.IDLE) {
                debugLog.log("camera_taken", mapOf("owner" to owner, "animator" to type.name, "from" to navigationCamera.state.name))
            }
        }

        // The navigation camera cancels its running animation itself before it starts the next one.
        override fun onAnimatorCancelling(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) {
            if (type == CameraAnimatorType.CENTER && owner == NavigationCamera.NAVIGATION_CAMERA_OWNER) frameUpdatesCut += 1
        }

        override fun onAnimatorInterrupting(
            type: CameraAnimatorType,
            runningAnimator: ValueAnimator,
            runningAnimatorOwner: String?,
            newAnimator: ValueAnimator,
            newAnimatorOwner: String?,
        ) = Unit

        override fun onAnimatorEnding(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) = Unit
    }

    init {
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

        mapView.location.setLocationProvider(defaultLocationProvider)
        mapView.location.updateSettings {
            enabled = true
            locationPuck = createDefault2DPuck(withBearing = true)
            puckBearingEnabled = true
            puckBearing = PuckBearing.COURSE
        }
        mapView.location.addOnIndicatorPositionChangedListener(puckPositionListener)
        mapView.location.addOnIndicatorBearingChangedListener(puckBearingListener)

        mapView.camera.addCameraAnimationsLifecycleListener(cameraAnimations)
        mapView.camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navigationCamera))
        navigationCamera.registerNavigationCameraStateChangeObserver { state ->
            _cameraMode.value = state.toCameraMode()
            // How long the transition into this state took ("ms" on the state it ended in).
            val now = SystemClock.elapsedRealtime()
            val tookMs = if (transitionStartedAt != 0L && !state.isTransition()) now - transitionStartedAt else null
            transitionStartedAt = if (state.isTransition()) now else 0L
            debugLog.log("camera_state", mapOf("state" to state.name, "ms" to tookMs).filterValues { it != null })
        }
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
        debugLog.log("camera_request", mapOf("mode" to mode, "from" to navigationCamera.state.name, "guidance" to guidanceState))
        when (mode) {
            CameraMode.FOLLOWING ->
                if (guidanceState == GuidanceState.ACTIVE) navigationCamera.requestNavigationCameraToFollowing() else centerOnPuck()
            CameraMode.OVERVIEW ->
                if (guidanceState == GuidanceState.ACTIVE || guidanceState == GuidanceState.PREVIEW) {
                    navigationCamera.requestNavigationCameraToOverview()
                }
            CameraMode.IDLE -> navigationCamera.requestNavigationCameraToIdle()
        }
    }

    override fun setViewportPadding(padding: ViewportPadding) {
        // A new padding moves the camera at once; the next camera line says to what, and how often.
        if (padding != this.padding) {
            this.padding = padding
            paddingChanges += 1
        }
        val insets = EdgeInsets(padding.top.toDouble(), padding.left.toDouble(), padding.bottom.toDouble(), padding.right.toDouble())
        viewportDataSource.followingPadding = insets
        viewportDataSource.overviewPadding = insets
        viewportDataSource.evaluate()
        mapView.compass.updateSettings { marginTop = padding.top + ORNAMENT_MARGIN_DP * density }
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
     * Shows the puck right after the driver grants location access. Without this the platform
     * provider notices the new permission only at its next check, which can take a few seconds.
     */
    fun onLocationPermissionGranted() {
        defaultLocationProvider = DefaultLocationProvider(appContext)
        if (guidanceState != GuidanceState.ACTIVE) mapView.location.setLocationProvider(defaultLocationProvider)
    }

    /** Stops listening to the navigator. The map view itself follows the hosting activity's lifecycle. */
    fun release() {
        stopCameraSampling()
        mapErrors.cancel()
        // The counts of the current minute, before the windows' timers go with the scope.
        mapErrorFolder.flush()
        scope.cancel()
        MapboxNavigationApp.unregisterObserver(navigationObserver)
        mapView.location.removeOnIndicatorPositionChangedListener(puckPositionListener)
        mapView.location.removeOnIndicatorBearingChangedListener(puckBearingListener)
        mapView.camera.removeCameraAnimationsLifecycleListener(cameraAnimations)
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
                viewportDataSource.evaluate()
                navigationCamera.requestNavigationCameraToOverview()
            }
            GuidanceState.ACTIVE -> {
                useLocationProvider(guidance = true)
                // Also brings back the route when the map is created again during a drive.
                showNavigatorRoutes()
                navigationCamera.requestNavigationCameraToFollowing()
            }
            GuidanceState.IDLE, GuidanceState.ARRIVED -> {
                useLocationProvider(guidance = false)
                clearRoutes()
                if (navigationCamera.state != NavigationCameraState.IDLE) {
                    navigationCamera.requestNavigationCameraToIdle()
                    centerOnPuck()
                }
            }
        }
    }

    private fun onRouteProgress(progress: RouteProgress) {
        if (guidanceState != GuidanceState.ACTIVE) return
        val style = style ?: return
        routeLineApi.updateWithRouteProgress(progress) { update -> routeLineView.renderRouteLineUpdate(style, update) }
        routeArrowView.renderManeuverUpdate(style, routeArrowApi.addUpcomingManeuverArrow(progress))
        viewportDataSource.onRouteProgressChanged(progress)
        viewportDataSource.evaluate()
    }

    private fun onPuckMoved(point: Point) {
        lastPuck = point
        engine.lastPuckPosition = point
        if (cameraSampling != null) puckCadence.tick(SystemClock.elapsedRealtimeNanos())
        val style = style ?: return
        if (guidanceState == GuidanceState.ACTIVE) {
            routeLineView.renderRouteLineUpdate(style, routeLineApi.updateTraveledRouteLine(point))
        } else if (!centeredOnFirstFix && guidanceState == GuidanceState.IDLE) {
            centeredOnFirstFix = true
            centerOnPuck()
        }
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
        val wanted = if (guidance) navigationLocationProvider else defaultLocationProvider
        if (mapView.location.getLocationProvider() !== wanted) {
            mapView.location.setLocationProvider(wanted)
        }
    }

    private fun centerOnPuck() {
        val point = lastPuck ?: return
        mapView.camera.easeTo(
            CameraOptions.Builder().center(point).zoom(BROWSE_ZOOM).pitch(BROWSE_PITCH).build(),
            MapAnimationOptions.Builder().duration(CAMERA_EASE_MS).build(),
        )
        // Keeps the following camera's data fresh for when guidance starts.
        viewportDataSource.onLocationChanged(
            Location.Builder().latitude(point.latitude()).longitude(point.longitude()).bearing(lastBearing).build(),
        )
    }

    /** Starts the once-a-second camera line; does nothing if it is already running. */
    private fun startCameraSampling() {
        if (cameraSampling != null) return
        // Each guidance measures afresh: nothing carries over from the last one, minutes or hours ago.
        cameraCadence.reset()
        puckCadence.reset()
        cameraSteps.reset()
        fixes = 0
        frameUpdates = 0
        frameUpdatesCut = 0
        // The first line says what the padding is.
        paddingChanges = 1
        startFrameStats()
        cameraChanges = mapboxMap.subscribeCameraChanged { event ->
            cameraCadence.tick(SystemClock.elapsedRealtimeNanos())
            val camera = event.cameraState
            cameraSteps.onCamera(camera.center.latitude(), camera.center.longitude(), camera.zoom, camera.bearing, camera.pitch)
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
     * "to" is where the navigation camera is taking the camera; "frameUpdatesCut" counts camera
     * animations restarted before they finished. "renderFps" is the map's own frame rate against the
     * screen's refresh; "animatorsEnabled" is false when Android's animations are switched off.
     */
    private fun cameraSample(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtimeNanos()
        val camera = mapboxMap.cameraState
        val target = viewportDataSource.getViewportData().cameraForFollowing
        val puck = lastPuck
        val fix = navigationLocationProvider.lastLocation
        val cameraMoves = cameraCadence.take(now)
        val puckMoves = puckCadence.take(now)
        val fps = renderFps.takeIf { renderFpsAt != 0L && now - renderFpsAt <= FPS_FRESH_NANOS }
        val sample = mapOf(
            "state" to navigationCamera.state.name,
            "zoom" to Motion.round(camera.zoom, 2),
            "pitch" to Motion.round(camera.pitch, 1),
            "bearing" to Motion.round(camera.bearing, 1),
            "to" to mapOf(
                "zoom" to target.zoom?.let { Motion.round(it, 2) },
                "pitch" to target.pitch?.let { Motion.round(it, 1) },
                "bearing" to target.bearing?.let { Motion.round(it, 1) },
                "m" to target.center?.let { metersBetween(camera.center, it) },
            ).filterValues { it != null },
            // How far the puck trails the latest map-matched fix (it glides there over a second), how
            // long ago that fix came, and how far the camera center is from the puck.
            "puckLagM" to if (puck != null && fix != null) metersBetween(puck, Point.fromLngLat(fix.longitude, fix.latitude)) else null,
            "sinceFixMs" to if (lastFixAt != 0L) (now - lastFixAt) / NANOS_PER_MILLI else null,
            "camLagM" to puck?.let { metersBetween(camera.center, it) },
            "camUpdates" to cameraMoves.count,
            "camGapMs" to cameraMoves.longestGapMillis,
            "puckUpdates" to puckMoves.count,
            "puckGapMs" to puckMoves.longestGapMillis,
            "steps" to cameraSteps.take(),
            "fixes" to fixes,
            "keyPoints" to lastKeyPoints,
            "frameUpdates" to frameUpdates,
            "frameUpdatesCut" to frameUpdatesCut,
            "padding" to padding?.takeIf { paddingChanges > 0 }?.let { listOf(it.top, it.left, it.bottom, it.right) },
            "paddingChanges" to paddingChanges.takeIf { it > 0 },
            "render" to takeFrameStats(),
            "renderFps" to fps?.let { Motion.round(it, 1) },
            "animatorsEnabled" to ValueAnimator.areAnimatorsEnabled(),
            "hz" to mapView.display?.refreshRate?.let { Math.round(it) },
        )
        fixes = 0
        frameUpdates = 0
        frameUpdatesCut = 0
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

    private fun NavigationCameraState.isTransition(): Boolean =
        this == NavigationCameraState.TRANSITION_TO_FOLLOWING || this == NavigationCameraState.TRANSITION_TO_OVERVIEW

    private fun NavigationCameraState.toCameraMode(): CameraMode = when (this) {
        NavigationCameraState.FOLLOWING, NavigationCameraState.TRANSITION_TO_FOLLOWING -> CameraMode.FOLLOWING
        NavigationCameraState.OVERVIEW, NavigationCameraState.TRANSITION_TO_OVERVIEW -> CameraMode.OVERVIEW
        NavigationCameraState.IDLE -> CameraMode.IDLE
    }

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
        private const val CAMERA_EASE_MS = 800L
        private const val BROWSE_ZOOM = 15.5
        private const val BROWSE_PITCH = 40.0
        private const val CAMERA_SAMPLE_MS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** The map reports its frame rate about once a second; an older value means it stopped drawing. */
        private const val FPS_FRESH_NANOS = 2_000_000_000L
    }
}
