package io.github.purplepalm.custommaps.engine.mapbox.map

import android.content.Context
import android.view.Gravity
import android.view.View
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.StyleDataLoadedType
import com.mapbox.maps.plugin.PuckBearing
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
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
            navigationLocationProvider.changePosition(locationMatcherResult.enhancedLocation, locationMatcherResult.keyPoints)
            viewportDataSource.onLocationChanged(locationMatcherResult.enhancedLocation)
            viewportDataSource.evaluate()
        }
    }

    /** Tile and style failures go to the debug log; a refused token is also shown on screen. */
    private val mapErrors = mapboxMap.subscribeMapLoadingError { error ->
        debugLog.log("map_error", mapOf("errorType" to error.type.name, "message" to error.message, "source" to error.sourceId))
        if (AccessErrors.isAccessDenied(error.message)) _accessDenied.value = true
    }

    init {
        mapView.alpha = 0f
        mapView.scalebar.enabled = false
        mapboxMap.subscribeStyleDataLoaded { event ->
            if (event.type == StyleDataLoadedType.STYLE) MapPalette.applyNight(mapboxMap)
        }
        mapboxMap.loadStyle(Style.STANDARD) { loaded ->
            debugLog.log("map_style_loaded")
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

        mapView.camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navigationCamera))
        navigationCamera.registerNavigationCameraStateChangeObserver { state ->
            _cameraMode.value = state.toCameraMode()
            debugLog.log("camera_state", mapOf("state" to state.name))
        }
        mapView.gestures.addOnMapClickListener(mapClickListener)

        MapboxNavigationApp.registerObserver(navigationObserver)
        scope.launch {
            combine(engine.guidanceState, engine.previewRoutes) { state, _ -> state }.collect { state -> render(state) }
        }
    }

    override fun setCameraMode(mode: CameraMode) {
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
        scope.cancel()
        mapErrors.cancel()
        MapboxNavigationApp.unregisterObserver(navigationObserver)
        mapView.location.removeOnIndicatorPositionChangedListener(puckPositionListener)
        mapView.location.removeOnIndicatorBearingChangedListener(puckBearingListener)
        mapView.gestures.removeOnMapClickListener(mapClickListener)
        routeLineApi.cancel()
        routeLineView.cancel()
    }

    private fun render(state: GuidanceState) {
        if (style == null) return
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
    }
}
