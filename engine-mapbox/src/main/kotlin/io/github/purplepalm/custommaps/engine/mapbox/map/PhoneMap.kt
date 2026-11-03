package io.github.purplepalm.custommaps.engine.mapbox.map

import android.content.Context
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
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.nav.MapRenderer
import io.github.purplepalm.custommaps.nav.ViewportPadding
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
) : MapRenderer {

    private val mapView = MapView(context, MapInitOptions(context, styleUri = null))
    private val mapboxMap = mapView.mapboxMap
    private val scope = MainScope()
    private val density = context.resources.displayMetrics.density

    /** The map as an Android view, to place in the screen layout. */
    val view: View get() = mapView

    private var style: Style? = null
    private var guidanceState = GuidanceState.IDLE
    private var lastPuck: Point? = null
    private var lastBearing: Double? = null
    private var centeredOnFirstFix = false

    private val defaultLocationProvider = DefaultLocationProvider(context)
    private val navigationLocationProvider = NavigationLocationProvider()

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

    /** During guidance the navigator's routes (they change on reroute) are drawn instead of the preview. */
    private val routesObserver = RoutesObserver { result ->
        if (guidanceState != GuidanceState.ACTIVE) return@RoutesObserver
        val routes = result.navigationRoutes
        if (routes.isEmpty()) return@RoutesObserver
        drawRoutes(routes)
        viewportDataSource.onRouteChanged(routes.first())
        viewportDataSource.evaluate()
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

    init {
        mapView.alpha = 0f
        mapView.scalebar.enabled = false
        mapboxMap.subscribeStyleDataLoaded { event ->
            if (event.type == StyleDataLoadedType.STYLE) MapPalette.applyNight(mapboxMap)
        }
        mapboxMap.loadStyle(Style.STANDARD) { loaded ->
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
        navigationCamera.registerNavigationCameraStateChangeObserver { state -> _cameraMode.value = state.toCameraMode() }
        mapView.gestures.addOnMapClickListener(mapClickListener)

        MapboxNavigationApp.registerObserver(navigationObserver)
        scope.launch {
            combine(engine.guidanceState, engine.previewRoutes) { state, _ -> state }.collect { state ->
                guidanceState = state
                render(state)
            }
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
        // The Mapbox logo and attribution must stay visible above whatever covers the bottom of the map.
        val ornamentMargin = ORNAMENT_MARGIN_DP * density
        mapView.logo.updateSettings { marginBottom = padding.bottom + ornamentMargin }
        mapView.attribution.updateSettings { marginBottom = padding.bottom + ornamentMargin }
        mapView.compass.updateSettings { marginTop = padding.top + ornamentMargin }
    }

    /** Stops listening to the navigator. The map view itself follows the hosting activity's lifecycle. */
    fun release() {
        scope.cancel()
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

    private fun drawRoutes(routes: List<com.mapbox.navigation.base.route.NavigationRoute>) {
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

    private companion object {
        /** Standard style slot above roads and below 3D buildings and labels. */
        const val ROUTE_SLOT = "middle"
        const val ROUTE_TAP_RADIUS_DP = 30f
        const val ORNAMENT_MARGIN_DP = 6f
        const val FADE_IN_MS = 250L
        const val CAMERA_EASE_MS = 800L
        const val BROWSE_ZOOM = 15.5
        const val BROWSE_PITCH = 40.0
    }
}
