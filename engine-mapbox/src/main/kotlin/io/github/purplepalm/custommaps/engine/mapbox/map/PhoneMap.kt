package io.github.purplepalm.custommaps.engine.mapbox.map

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import com.mapbox.common.Cancelable
import com.mapbox.api.directions.v5.models.ManeuverModifier
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraState
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapLoadingErrorType
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.Style
import com.mapbox.maps.StyleDataLoadedType
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.CameraAnimationsLifecycleListener
import com.mapbox.maps.plugin.animation.CameraAnimatorType
import com.mapbox.maps.plugin.animation.MapAnimationOwnerRegistry
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
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.base.utils.DecodeUtils.stepsGeometryToPoints
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesExtra
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.routealternatives.AlternativeRouteMetadata
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.ManeuverArrow
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import io.github.purplepalm.custommaps.engine.mapbox.AccessErrors
import io.github.purplepalm.custommaps.engine.mapbox.R
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.GuidanceCamera
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.GuidancePuck
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.JunctionPoints
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.LaneZoomFrames
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.NavigationCameraAdapter
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.PlatformPuck
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.SmoothNavigationCamera
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.engine.mapbox.navigation.RouteLanePlan
import io.github.purplepalm.custommaps.engine.mapbox.offline.OfflineData
import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.MapRenderer
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.IdleFollow
import io.github.purplepalm.custommaps.nav.camera.LookAhead
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.debug.Cadence
import io.github.purplepalm.custommaps.nav.debug.CameraSteps
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.RepeatFolder
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.guidance.UTurnArrow
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's map: Mapbox Standard with 3D buildings in the day or the night look ([setLook]), the
 * device location, the routes of the preview and, during guidance, the active route with traffic
 * and the next maneuver arrow. The place being looked at or driven to has a pin of its own
 * ([showPlace]), wherever its route ends.
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
 *
 * Lane details, the lane markings Mapbox Standard can draw, are off unless [setLaneDetails] turns
 * them on ([LaneDetails]). The puck stays where the navigator puts the car, on the line of the
 * road: nothing here moves it sideways into a lane, since no position the phone has says which
 * lane the car is in.
 *
 * Behind a Debug switch ([setOwnRouteLine]) the route line of a drive is the app's own, drawn along
 * the lanes of the route's lane plan in place of the navigation SDK's primary line ([OwnRouteLine]).
 *
 * While the map exists, the style it shows is among what is kept ahead of the car during guidance
 * for stretches without a connection ([OfflineData]).
 */
class PhoneMap internal constructor(
    context: Context,
    private val engine: MapboxNavigationEngine,
    private val debugLog: DebugLog,
    private val offlineData: OfflineData,
    /** The look the map shows from its first tile on; [setLook] brings any later one. */
    lookOnMap: LookOnMap,
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
    private val platformPuck = PlatformPuck(context) { lastPuck }

    /** The puck's positions during guidance, from the navigator. */
    private val guidancePuck = GuidancePuck()

    /** The navigator's routes; they change on reroute and are empty outside guidance. */
    private var navigatorRoutes: List<NavigationRoute> = emptyList()

    /** The navigator while it is attached: it knows where each of its alternatives leaves the route. */
    private var navigation: MapboxNavigation? = null

    /**
     * Read from the engine rather than copied from its flow: a map created during a drive gets the
     * navigator's routes as soon as it registers, before a collector would have run.
     */
    private val guidanceState: GuidanceState get() = engine.guidanceState.value

    private val routeLineOptions = MapboxRouteLineApiOptions.Builder().vanishingRouteLineEnabled(true).build()
    private val routeLineApi = MapboxRouteLineApi(routeLineOptions)
    /**
     * Made once, with the first look's colours; a later look changes its options. A second view
     * would add its layers again above the maneuver arrow.
     */
    private val routeLineView = MapboxRouteLineView(
        MapboxRouteLineViewOptions.Builder(context)
            .routeLineColorResources(MapPalette.routeLineColorResources(lookOnMap.look))
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

    /** Where the own route line's paths are built: off the main thread, one at a time. */
    private val ownLineWorker = Dispatchers.Default.limitedParallelism(1)

    /**
     * The route line drawn by the app itself, behind the Debug switch; with the switch off it touches
     * nothing. The SDK's route line is fed as always either way.
     */
    private val ownLine: OwnRouteLine = OwnRouteLine(
        // The map's word that the own source's data loaded, for the swap from the SDK's line to the own one.
        OwnRouteLine.layersOn(mapboxMap) { dataId -> ownLine.onSourceLoaded(dataId) },
        primary = object : OwnRouteLine.Primary {
            override fun show() {
                style?.let(routeLineView::showPrimaryRoute)
            }

            override fun hide() {
                style?.let(routeLineView::hidePrimaryRoute)
            }
        },
        worker = object : OwnRouteLine.Worker {
            override fun <T> run(work: () -> T, done: (Result<T>) -> Unit): OwnRouteLine.Task {
                val job = scope.launch(ownLineWorker) {
                    val result = try {
                        Result.success(work())
                    } catch (e: RuntimeException) {
                        Result.failure(e)
                    } catch (e: OutOfMemoryError) {
                        // What the path took is free again here: it costs the own line, never the drive.
                        Result.failure(e)
                    }
                    withContext(Dispatchers.Main) { done(result) }
                }
                return OwnRouteLine.Task { job.cancel() }
            }
        },
        debugLog = debugLog,
        // The app's own lanes' layers, with their Debug switch on.
        lanePort = LaneLayers.on(mapboxMap),
    )

    /** The lane plan of the drive's route, answered on the route lines' worker: the own line's path is built from it. */
    private val planListener: (String, RouteLanePlan?) -> Unit = { routeId, plan ->
        if (ownLine.enabled) {
            navigatorRoutes.firstOrNull()?.takeIf { it.id == routeId }?.let { ownLine.onPlan(routeId, ownLineBuild(it, plan)) }
        }
    }

    /** The step whose maneuver arrow was worked out last: route, leg and step; null before any. */
    private var arrowStep: String? = null

    /** The arrow of that step when it is a U-turn drawn as one curve; null while the SDK's own arrow is shown. */
    private var uTurnArrow: ManeuverArrow? = null

    /** Lane markings on the map where Mapbox has them; off until Settings says otherwise. */
    private val laneDetails = LaneDetails.on(mapboxMap, debugLog)

    /** Whether the route line view was told to stay thin close in; that follows [laneDetails]. */
    private var routeLineHeld = false

    /** The pin of the place being looked at or driven to; none until [showPlace] brings a place. */
    private val placePin = PlacePin.on(mapboxMap, debugLog)

    /** The basemap's config, written one key a call. */
    private val basemap = MapLook.basemapOf(mapboxMap)

    /** When a requested look reaches the basemap, its theme and the route line. */
    private val lookApplier = LookApplier(lookOnMap)

    /** The basemap's own values, read in this style load before its first write. */
    private var styleSnapshot: Map<String, MapLook.Read> = emptyMap()

    /** What the style's data wrote, for the "map_style_loaded" line. */
    private var styleWritten = MapLook.Written.NONE

    /** The (i) dialog, in the variant of the look; the SDK's own dialog. */
    private val attributionDialogs = LookAttribution(context, lookOnMap.look)

    /** The look of the compass and of the (i) dialog. */
    private var ornamentLook = lookOnMap.look

    // The one wait for the map to settle after a write of the theme ([watchLookSettled]).
    private var lookIdle: Cancelable? = null
    private var lookIdleTimeout: Job? = null
    private var onLookSettled: ((Long?) -> Unit)? = null

    private val viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)

    /**
     * With Large, the road from a maneuver point to past where it bends, which the data source
     * frames besides its own points while it frames that maneuver ([JunctionPoints]). The cameras
     * set it with each progress; the map takes it back at a new route and when the route goes.
     */
    private val junctionPoints = JunctionPoints(viewportDataSource)

    /**
     * How the camera moves. The map starts with the style Settings starts with, so that the stored
     * choice, which [setCameraStyle] brings a moment later, mostly finds its camera already there.
     */
    private var cameraStyle = CameraStyle.DEFAULT

    /** How close the camera goes before turns and lane choices; Settings brings the stored choice. */
    private var laneZoom = LaneZoom.DEFAULT

    /** Moves the map during guidance and in the route preview, in the way of [cameraStyle]. */
    private var camera: GuidanceCamera

    /** The route the data source frames: the first of the preview, or the navigator's; null without one. */
    private var framedRoute: NavigationRoute? = null

    /** The latest route progress of the drive; null outside guidance. */
    private var lastProgress: RouteProgress? = null

    /**
     * Whether the latest matched position is the one the navigator held from the trip before; the
     * route progress of the same update is then left alone too. The navigator reports the position
     * of an update before its progress.
     */
    private var oldPosition = false

    /**
     * The seeks of a simulated drive this map has seen. The first position after another one is
     * where the seek put the car: the drawn car and the camera go there at once.
     */
    private var seenSeeks = engine.replaySeeks

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

    /** Set from the camera after each progress update it took; false outside guidance and for a new camera. */
    private val _closeView = MutableStateFlow(false)
    override val closeView: StateFlow<Boolean> = _closeView.asStateFlow()

    private val _accessDenied = MutableStateFlow(false)

    /**
     * True once Mapbox refused the access token for the style or tiles. The map then stays empty;
     * a new token recreates the screen, and this map with it.
     */
    val accessDenied: StateFlow<Boolean> = _accessDenied.asStateFlow()

    /** Watches a camera that ended a move until its springs are at rest ([watchSettling]); null otherwise. */
    private var settleWatch: Job? = null

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
            // First: the routes observer hears the routes at once, and draws them with what it
            // asks of the navigator.
            navigation = mapboxNavigation
            mapboxNavigation.registerRoutesObserver(routesObserver)
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.registerLocationObserver(locationObserver)
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.unregisterLocationObserver(locationObserver)
            navigation = null
        }
    }

    /** During guidance the navigator's routes are drawn instead of the preview. */
    private val routesObserver = RoutesObserver { result ->
        navigatorRoutes = result.navigationRoutes
        if (guidanceState == GuidanceState.ACTIVE) showNavigatorRoutes(refresh = result.reason == RoutesExtra.ROUTES_UPDATE_REASON_REFRESH)
    }

    private val routeProgressObserver = RouteProgressObserver { progress -> onRouteProgress(progress) }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            if (guidanceState != GuidanceState.ACTIVE) return
            // Where the trip before left the car, which a new trip reports before its own first
            // position: the drawn car and the camera stay where they are, and so does the route
            // line with the update that comes with it.
            oldPosition = engine.carriesOldPosition(locationMatcherResult)
            if (oldPosition) return
            val seek = engine.replaySeeks != seenSeeks
            seenSeeks = engine.replaySeeks
            fixes += 1
            lastFixAt = SystemClock.elapsedRealtimeNanos()
            lastKeyPoints = locationMatcherResult.keyPoints.size
            // A seek put the car here: the camera lets go of the road it was on, and both go there
            // at once and glide on from the new place, like after a jump of the navigator's.
            if (seek) camera.onJump()
            guidancePuck.onFix(locationMatcherResult, jump = seek)
            camera.onLocation(locationMatcherResult.enhancedLocation, locationMatcherResult.isTeleport || seek)
        }
    }

    /**
     * Tile and style failures go to the debug log, the first of each kind in full and the rest as a
     * count once a minute; a refused token is also shown on screen, checked on every error.
     */
    private val mapErrors = mapboxMap.subscribeMapLoadingError { error ->
        if (AccessErrors.isAccessDenied(error.message)) _accessDenied.value = true
        // Counted for the summary of a trip under way, by kind only.
        engine.onMapError(error.type.name)
        // The own route line's source: the SDK's line again at once. Its message is left out, as it may quote the path.
        val ownSource = error.sourceId == OwnRouteLine.SOURCE
        if (ownSource && error.type == MapLoadingErrorType.SOURCE && ownLine.enabled) ownLine.onSourceError()
        val fields = mapOf("errorType" to error.type.name, "message" to error.message, "source" to error.sourceId)
        mapErrorFolder.record(
            type = "map_error",
            key = RepeatFolder.keyOf(error.type.name, error.message),
            fields = if (ownSource) fields - "message" else fields,
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
            settleWatch?.cancel()
            if (tookMs != null) watchSettling(state, now)
        }

        // Says why the camera stopped following: a gesture, the compass, or something else.
        override fun onCameraTaken(owner: String?, animator: String, state: String) {
            debugLog.log("camera_taken", mapOf("owner" to owner, "animator" to animator, "from" to state))
            // The map is the driver's now, also on the browsing map.
            idleFollow.onMapMoved()
        }
    }

    /**
     * The driver's own moves of the map: a touch gesture or the compass, each of which starts a
     * camera animation of its own. The cameras report only what is taken from them, and before the
     * car's first position no camera has the map. A map the driver moved then is left where it is
     * when the car appears. Touches on a map that is not shown yet do not count.
     */
    private val driverMoves = object : CameraAnimationsLifecycleListener {
        // Called for every animator of every move of a drag: nothing here but the two comparisons.
        override fun onAnimatorStarting(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) {
            if (style != null && (owner == MapAnimationOwnerRegistry.GESTURES || owner == MapAnimationOwnerRegistry.COMPASS)) {
                idleFollow.onMapMoved()
            }
        }

        override fun onAnimatorInterrupting(
            type: CameraAnimatorType,
            runningAnimator: ValueAnimator,
            runningAnimatorOwner: String?,
            newAnimator: ValueAnimator,
            newAnimatorOwner: String?,
        ) = Unit

        override fun onAnimatorEnding(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) = Unit

        override fun onAnimatorCancelling(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) = Unit
    }

    init {
        // First: everything registered below may call back, and what it calls expects a camera.
        camera = createCamera(cameraStyle)
        mapView.camera.addCameraAnimationsLifecycleListener(driverMoves)

        mapView.alpha = 0f
        mapView.scalebar.enabled = false
        // The compass and the (i) dialog in the look's own variants. The logo and the (i) button
        // stay as they are.
        showCompass(lookOnMap.look)
        mapView.attribution.setCustomAttributionDialogManager(attributionDialogs)
        // The look is written when the style's data is parsed, before any tile draws, so no frame
        // of the other look appears.
        mapboxMap.subscribeStyleDataLoaded { event ->
            if (event.type == StyleDataLoadedType.STYLE) onStyleData()
        }
        lookApplier.onStyleLoading()
        mapboxMap.loadStyle(Style.STANDARD) { loaded ->
            debugLog.log("map_style_loaded", styleLoadedFields())
            style = loaded
            // The route line's layers first, then the arrow's, before anything is drawn: the arrow
            // goes above the route line only if the line's top layer is there when the arrow makes
            // its layers. Left to the first renders, the arrow came first and the line covered it.
            // Both kinds of layer stay in the style, in this order, for as long as the map lives.
            routeLineView.initializeLayers(loaded)
            // A look asked for while the style loaded: the basemap if it changed since the style's
            // data, then the route line's colours.
            applyLook(lookApplier.onStyleLoaded())
            clearArrows()
            // The own route line's layers, with its switch on: above the route line and below the arrow.
            ownLine.onStyleLoaded(lookApplier.routeLineLook)
            logLayerOrder(loaded)
            // Lane details are set a moment after this callback, never within it (see LaneDetails).
            laneDetails.onStyleLoaded()
            applyRouteLineWidths()
            // The style comes without the pin of a place that was shown before it loaded.
            placePin.onStyleLoaded()
            mapView.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            render(guidanceState)
        }

        // While the camera follows on the browsing map, the two Mapbox styles move it with the puck's
        // own updates, and the data source learns each position, in every style: the route's camera
        // then starts from where the car is. Nothing is computed from it before a route is shown.
        platformPuck.onGlide = { target, glideMillis ->
            if (idleFollow.following) {
                camera.onPuckGlide(target, glideMillis)
                feedPuck(target)
            }
        }
        mapView.location.setLocationProvider(platformPuck)
        mapView.location.updateSettings {
            enabled = true
            // Mapbox's own dot and bearing arrow on its white disc, with a graphite edge around the
            // disc so it shows on the light day map too; the same in both looks. The edge is the
            // puck's shadow image, which with a bearing is the white disc alone.
            locationPuck = createDefault2DPuck(withBearing = true).copy(shadowImage = ImageHolder.from(R.drawable.puck_ring))
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
        engine.lanePlans.addListener(planListener)
        // The style this map shows is kept ahead of the car during guidance from now on. The style
        // may still be loading: it is taken when it has loaded, and again each time it loads.
        offlineData.attachMap(mapboxMap)
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
        settleWatch?.cancel()
        camera.destroy()
        // The old camera took back what it had set on the data source, and the frames are set up
        // for the new style and the lane zoom. The frame the data source holds was computed for
        // the old camera: the new one gets a frame of its own to start from.
        LaneZoomFrames.apply(viewportDataSource, style, laneZoom, CameraTuning.PHONE, framedRoute, lastProgress, junctionPoints)
        viewportDataSource.evaluate()
        cameraStyle = style
        // The new camera starts idle; a transition the old one was in ends with it.
        transitionStartedAt = 0L
        camera = createCamera(style)
        // It has seen no progress yet: its first one says whether it shows a close view.
        _closeView.value = false
        when (mode) {
            CameraMode.FOLLOWING -> camera.requestFollowing()
            CameraMode.OVERVIEW -> camera.requestOverview()
            // On the browsing map the new camera stays on the car if the old one did.
            CameraMode.IDLE -> if (idleFollow.following) followCar()
        }
    }

    override fun setLaneZoom(mode: LaneZoom) {
        if (mode == laneZoom) return
        laneZoom = mode
        // First the frames of the data source, then the camera, then a frame computed for both.
        // During a drive the camera glides to it.
        LaneZoomFrames.apply(viewportDataSource, cameraStyle, mode, CameraTuning.PHONE, framedRoute, lastProgress, junctionPoints)
        camera.setLaneZoom(mode)
        viewportDataSource.evaluate()
    }

    override fun setLaneDetails(enabled: Boolean) {
        laneDetails.setEnabled(enabled)
        applyRouteLineWidths()
    }

    override fun setOwnRouteLine(enabled: Boolean) {
        if (enabled == ownLine.enabled) return
        ownLine.setEnabled(enabled)
        // The own layers it added to the middle slot, or took out of it.
        style?.let(::logLayerOrder)
        // During a drive the route it is on gets its own line at once.
        if (enabled && guidanceState == GuidanceState.ACTIVE) navigatorRoutes.firstOrNull()?.let { ownLineRoute(it, refresh = false) }
    }

    override fun showPlace(place: Place?) {
        // At the place itself: the route line shows where the route ends, which may be elsewhere.
        placePin.show(place?.point)
    }

    override fun setLook(request: LookOnMap, onSettled: (idleMs: Long?) -> Unit): LookApplied {
        // A map that has gone writes nothing.
        if (!mapboxMap.isValid()) return LookApplied()
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val writes = lookApplier.setLook(request)
        val written = applyLook(writes)
        if (request.look != ornamentLook) {
            ornamentLook = request.look
            showCompass(request.look)
            attributionDialogs.setLook(request.look)
        }
        val writeMs = Motion.round((SystemClock.elapsedRealtimeNanos() - startedNanos) / NANOS_PER_MILLI.toDouble(), 1)
        if (writes.theme != null) watchLookSettled(SystemClock.elapsedRealtime(), onSettled)
        return LookApplied(
            keys = written.keys,
            errors = written.errors,
            failedKeys = written.failedKeys,
            writeMs = writeMs,
            reset = written.reset,
            transitionMs = if (written.keys > 0) mapboxMap.getStyleTransition().duration else null,
            routeLine = writes.routeLine != null,
            theme = writes.theme != null,
        )
    }

    /**
     * Puts the Mapbox logo and the attribution button in a fixed strip at the bottom left of the map,
     * just above [bottomInset] pixels of navigation bar or keyboard. The screen keeps that strip free
     * of panels ([ORNAMENT_STRIP_DP]), so the logo is never covered, not even by a panel's shadow,
     * and does not move with the panels. Mapbox lets apps move the logo but not resize or fade it.
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

    /** The compass of [look]: a white disc by day, a graphite one at night, each with its ring and needle. */
    private fun showCompass(look: Look) {
        val drawable = if (look == Look.DAY) R.drawable.compass_day else R.drawable.compass_night
        mapView.compass.updateSettings { image = ImageHolder.from(drawable) }
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
        // A wait for the map to settle after a look ends without an answer.
        onLookSettled = null
        endLookSettled(null)
        stopCameraSampling()
        laneDetails.release()
        placePin.release()
        mapErrors.cancel()
        // The counts of the current minute, before the windows' timers go with the scope.
        mapErrorFolder.flush()
        scope.cancel()
        MapboxNavigationApp.unregisterObserver(navigationObserver)
        engine.lanePlans.removeListener(planListener)
        ownLine.release()
        // Nothing is kept ahead for this map any more, and nothing there holds on to it.
        offlineData.detachMap(mapboxMap)
        platformPuck.onGlide = null
        mapView.camera.removeCameraAnimationsLifecycleListener(driverMoves)
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

    /**
     * The style's data is parsed (STYLE): the basemap's own values are read once, then the whole
     * requested look is written with its own theme, before any tile draws.
     */
    private fun onStyleData() {
        styleSnapshot = MapLook.snapshot(basemap)
        styleWritten = applyLook(lookApplier.onStyleData())
    }

    /**
     * The "map_style_loaded" line: the screen's refresh rate, the look the basemap shows, and how
     * many keys of the basemap's config the style's data wrote and how many of them the map refused.
     */
    private fun styleLoadedFields(): Map<String, Any?> {
        val written = styleWritten
        return mapOf(
            "refreshHz" to mapView.display?.refreshRate,
            "look" to lookApplier.basemapLook,
            "keys" to written.keys,
            "errors" to written.errors,
            "failedKeys" to written.failedKeys.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    /** Writes what [writes] says is due now: the basemap's config with its theme, and the route line's colours. */
    private fun applyLook(writes: LookApplier.Writes): MapLook.Written {
        val written =
            if (writes.basemap == null && writes.theme == null) MapLook.Written.NONE else MapLook.write(basemap, writes.basemap, writes.theme, styleSnapshot)
        writes.routeLine?.let { look -> style?.let { applyRouteLineLook(it, look) } }
        return written
    }

    /**
     * Gives the route line [look]'s colours. The view keeps the rest of its options, the widths held
     * for lane details among them; routes on the map are drawn again with them.
     */
    private fun applyRouteLineLook(style: Style, look: Look) {
        routeLineView.updateDynamicOptions(style) { routeLineColorResources(MapPalette.routeLineColorResources(look)) }
        if (framedRoute != null) routeLineApi.getRouteDrawData { value -> routeLineView.renderRouteDrawData(style, value) }
        // Every look, the own line's switch on or off: with it off the own line only keeps the look, for when it is
        // turned on, and touches nothing.
        ownLine.setLook(look)
    }

    /**
     * Waits once, from [writtenAt] (milliseconds since boot), for the map to be idle after a write of
     * the theme: no transition running and every tile drawn. [listener] then hears how long that
     * took, or null after [LOOK_SETTLE_MS], or when a newer write of the theme takes the wait over.
     * One subscription for the one event, cancelled at its first call; nothing runs per frame.
     */
    private fun watchLookSettled(writtenAt: Long, listener: (Long?) -> Unit) {
        endLookSettled(null)
        onLookSettled = listener
        lookIdle = mapboxMap.subscribeMapIdle { endLookSettled(SystemClock.elapsedRealtime() - writtenAt) }
        lookIdleTimeout = scope.launch {
            delay(LOOK_SETTLE_MS)
            endLookSettled(null)
        }
    }

    /** Ends the wait of [watchLookSettled], if one runs, and tells its listener [idleMs]. */
    private fun endLookSettled(idleMs: Long?) {
        lookIdle?.cancel()
        lookIdle = null
        lookIdleTimeout?.cancel()
        lookIdleTimeout = null
        val listener = onLookSettled ?: return
        onLookSettled = null
        listener(idleMs)
    }

    private fun render(state: GuidanceState) {
        if (style == null) return
        if (state == GuidanceState.ACTIVE) startCameraSampling() else stopCameraSampling()
        when (state) {
            GuidanceState.PREVIEW -> {
                useLocationProvider(guidance = false)
                // The preview shows the SDK's line, never the own one.
                if (ownLine.enabled) ownLine.onGuidanceEnded()
                val routes = engine.previewRoutes.value
                if (routes.isEmpty()) return
                drawRoutes(routes)
                clearArrows()
                frameRoute(routes.first())
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
                // The SDK's line shown again, for the next preview, and the own source cleared.
                if (ownLine.enabled) ownLine.onGuidanceEnded()
                clearRoutes()
                camera.onGuidanceEnded()
                _closeView.value = false
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

    /**
     * A move of the camera into [state] ended at [endedAt]; its springs still have some way to go.
     * Ten times a second, for at most [SETTLE_WATCH_MS], the camera is asked how far it still is,
     * and one "camera_settled" line says when it came to rest ("ms" after the move's end), or that
     * it had not by then, with how far it still was. Only a camera with springs of its own, Smooth
     * follow, has anything to say; nothing runs on the display's frames for it.
     */
    private fun watchSettling(state: String, endedAt: Long) {
        settleWatch = scope.launch {
            while (true) {
                val settling = camera.settling() ?: return@launch
                val waited = SystemClock.elapsedRealtime() - endedAt
                if (settling.atRest || waited >= SETTLE_WATCH_MS) {
                    val fields = mapOf("state" to state, "ms" to waited, "settled" to settling.atRest)
                    debugLog.log("camera_settled", if (settling.atRest) fields else fields + settling.gapFields())
                    return@launch
                }
                delay(SETTLE_POLL_MS)
            }
        }
    }

    private fun onRouteProgress(progress: RouteProgress) {
        if (guidanceState != GuidanceState.ACTIVE || oldPosition) return
        val style = style ?: return
        routeLineApi.updateWithRouteProgress(progress) { update -> routeLineView.renderRouteLineUpdate(style, update) }
        renderManeuverArrow(style, progress)
        lastProgress = progress
        // The engine reads the lane choices once for each update; its own observer got the same
        // list, and the run-up's latch moves once for it, so the card and the camera agree.
        camera.onRouteProgress(progress, engine.laneChoices.ahead(progress), engine.runUpKey(progress))
        // Once per update, and published only when it changes.
        _closeView.value = camera.closeView
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
        if (ownLine.enabled) trimOwnLine(point, accepted = update.isValue)
    }

    /**
     * The own route line's trim for the same update: the SDK's vanishing point where it took one, on the
     * route it holds; else, while the navigator follows the car on the route, the app's own projection.
     */
    private fun trimOwnLine(point: Point, accepted: Boolean) {
        val tracking = lastProgress?.currentState == RouteProgressState.TRACKING
        if (accepted) {
            ownLine.onPuck(point.latitude(), point.longitude(), true, routeLineApi.getPrimaryNavigationRoute()?.id, routeLineApi.getVanishPointOffset(), tracking)
        } else {
            ownLine.onPuck(point.latitude(), point.longitude(), false, null, Double.NaN, tracking)
        }
    }

    private fun showNavigatorRoutes(refresh: Boolean = false) {
        val routes = navigatorRoutes
        if (routes.isEmpty()) return
        // Where an alternative still shares the road with the route, as behind the car and up to
        // where it turns off, only the route is drawn. The navigator knows where each alternative
        // leaves the route; it is read on the phone, with no request. It is valid until the routes
        // change again, and every change comes through here.
        drawRoutes(routes, navigation?.getAlternativeMetadataFor(routes).orEmpty())
        if (ownLine.enabled) ownLineRoute(routes.first(), refresh)
        frameRoute(routes.first())
        viewportDataSource.evaluate()
    }

    /**
     * Gives the own route line the drive's primary [route]: what builds its path once the route's lane
     * plan has been answered (otherwise the plan's own answer brings it, [planListener]), and its traffic
     * for a [refresh].
     */
    private fun ownLineRoute(route: NavigationRoute, refresh: Boolean) {
        val answer = engine.lanePlans.answerFor(route.id)
        ownLine.onRoute(route.id, refresh, answer?.let { ownLineBuild(route, it.plan) }) { drawing ->
            ownLineRuns(route.directionsRoute.legs().orEmpty(), drawing)
        }
    }

    /** Builds the own line's path of [route] through the lanes of its lane [plan], or along its own line without one. */
    private fun ownLineBuild(route: NavigationRoute, plan: RouteLanePlan?) = OwnRouteLine.Build { version ->
        val directions = route.directionsRoute
        ownLineDrawing(route.id, directions.legs().orEmpty(), directions.stepsGeometryToPoints(), plan, version)
    }

    /** Gives the data source the route to frame; a route it has already changes nothing. */
    private fun frameRoute(route: NavigationRoute) {
        // The road past a maneuver of the route before is no part of this one's frames.
        if (route.id != framedRoute?.id) junctionPoints.clear()
        framedRoute = route
        viewportDataSource.onRouteChanged(route)
    }

    /**
     * Draws [routes], the first one as the route. With [alternatives], the navigator's account of
     * where each other route leaves the first one, the part they share is drawn only once, as the
     * route; the preview has no such account and draws every route whole.
     */
    private fun drawRoutes(routes: List<NavigationRoute>, alternatives: List<AlternativeRouteMetadata> = emptyList()) {
        val style = style ?: return
        routeLineApi.setNavigationRoutes(routes, alternatives) { value -> routeLineView.renderRouteDrawData(style, value) }
    }

    private fun clearRoutes() {
        val style = style ?: return
        routeLineApi.clearRouteLine { value -> routeLineView.renderClearRouteLineValue(style, value) }
        clearArrows()
        framedRoute = null
        lastProgress = null
        oldPosition = false
        junctionPoints.clear()
        viewportDataSource.clearRouteData()
        viewportDataSource.evaluate()
    }

    private fun clearArrows() {
        val style = style ?: return
        arrowStep = null
        uTurnArrow = null
        routeArrowView.render(style, routeArrowApi.clearArrows())
    }

    /**
     * The arrow of the next maneuver. The navigation SDK works its arrow out again with every
     * update: the last 30 m of the step and the first 30 m of the next one, which is the same
     * shape for the whole step. A U-turn across a median opening is drawn as one curve instead
     * ([UTurnArrow]), worked out once for its step and then left on the map until the step
     * changes. It is kept in memory only, like everything else of the route.
     */
    private fun renderManeuverArrow(style: Style, progress: RouteProgress) {
        val leg = progress.currentLegProgress
        val step = leg?.let { "${progress.navigationRoute.id}/${it.legIndex}/${it.currentStepProgress?.stepIndex}" }
        if (step != arrowStep) {
            arrowStep = step
            val shaped = if (isUTurn(progress)) uTurnArrowOf(progress) else null
            // The arrow of the step before goes first, whichever kind it was: an update of the
            // SDK's arrow draws only the first arrow the arrow API holds.
            if (shaped != null || uTurnArrow != null) routeArrowApi.clearArrows()
            uTurnArrow = shaped
            if (shaped != null) {
                routeArrowView.render(style, routeArrowApi.showManeuverArrow())
                routeArrowView.render(style, routeArrowApi.addArrow(shaped))
            }
        }
        if (uTurnArrow == null) routeArrowView.renderManeuverUpdate(style, routeArrowApi.addUpcomingManeuverArrow(progress))
    }

    /** Whether the next maneuver is a U-turn, as its banner says, or the route's step where there is no banner yet. */
    private fun isUTurn(progress: RouteProgress): Boolean {
        val modifier = progress.bannerInstructions?.primary()?.modifier()
            ?: progress.currentLegProgress?.upcomingStep?.maneuver()?.modifier()
        return modifier == ManeuverModifier.UTURN
    }

    /**
     * The U-turn's arrow as one curve, from the shape of the step and of the next one around the
     * maneuver point; null where the road does not turn back through two corners, and the SDK's
     * arrow is the right one.
     */
    private fun uTurnArrowOf(progress: RouteProgress): ManeuverArrow? = try {
        val approach = progress.currentLegProgress?.currentStepProgress?.stepPoints
        val onward = progress.upcomingStepPoints
        val maneuver = onward?.firstOrNull()
        if (approach == null || maneuver == null) {
            null
        } else {
            val local = LocalMeters(maneuver.latitude(), maneuver.longitude())
            UTurnArrow.shape(
                StepShapes.tail(approach, local, UTurnArrow.ARROW_METERS),
                StepShapes.head(onward, local, UTurnArrow.ONWARD_METERS),
            )?.let { points -> ManeuverArrow(points.map { StepShapes.toPoint(it, local) }) }
        }
    } catch (e: RuntimeException) {
        // A shape that cannot be read costs the curve, never the drive: the SDK's arrow shows.
        debugLog.error("uTurnArrow", e)
        null
    }

    /**
     * The "map_layers" line, once per style load: the ids of the layers in the route's slot, in the
     * order they are drawn, and whether the maneuver arrow lies above the route line in it.
     */
    private fun logLayerOrder(style: Style) {
        val ids = style.styleLayers.map { it.id }.filter { slotOf(style, it) in ROUTE_SLOTS }
        val ownLine = OwnRouteLine.LAYERS.map { it.id }
        val ownLineInPlace = LayerOrder.ownLineInPlace(ids, ownLine)
        val lanesInPlace = LayerOrder.lanesInPlace(
            ids,
            under = LaneLayers.LAYERS.filter { it.group == LaneLayers.Group.UNDER_ROUTE }.map { it.id },
            over = LaneLayers.LAYERS.filter { it.group == LaneLayers.Group.OVER_ROUTE }.map { it.id },
            ownLine = ownLine,
        )
        debugLog.log(
            "map_layers",
            mapOf("arrowAboveLine" to LayerOrder.arrowAboveLine(ids), "middle" to ids) +
                // Only while the own route line's layers are in the style.
                (if (ownLineInPlace != null) mapOf("ownLine" to ownLineInPlace) else emptyMap()) +
                // Only while the app's own lanes' layers are.
                (if (lanesInPlace != null) mapOf("lanes" to lanesInPlace) else emptyMap()),
        )
    }

    /** The slot a layer of the style is in; null for one in none. */
    private fun slotOf(style: Style, layerId: String): String? =
        style.getStyleLayerProperty(layerId, "slot").value.contents as? String

    /**
     * Gives the route line the widths that go with lane details: on, the line stays thin close in,
     * so the lane markings beside it show ([RouteLineWidths]). The view keeps the widths for the
     * routes it draws later; routes on the map are drawn again with them.
     */
    private fun applyRouteLineWidths() {
        val style = style ?: return
        // The view starts with the SDK's widths.
        val held = laneDetails.enabled
        if (held == routeLineHeld) return
        routeLineHeld = held
        val widths = if (held) RouteLineWidths.heldCloseIn else RouteLineWidths.sdk
        routeLineView.updateDynamicOptions(style) { scaleExpressions(widths) }
        if (framedRoute != null) routeLineApi.getRouteDrawData { value -> routeLineView.renderRouteDrawData(style, value) }
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
     * the other styles glide it a little longer and let it jump when the matched position jumps. A
     * seek of a simulated drive puts it at the new place at once in every style.
     */
    private fun createCamera(style: CameraStyle): GuidanceCamera {
        val standard = style == CameraStyle.MAPBOX_STANDARD
        guidancePuck.glideMillis = if (standard) GuidancePuck.STANDARD_GLIDE_MS else GuidancePuck.SMOOTH_GLIDE_MS
        guidancePuck.jumpOnTeleport = !standard
        val created = when (style) {
            CameraStyle.MAPBOX_STANDARD, CameraStyle.MAPBOX_TUNED ->
                NavigationCameraAdapter(
                    mapboxMap,
                    mapView.camera,
                    viewportDataSource,
                    tuned = !standard,
                    CameraTuning.PHONE,
                    cameraListener,
                    junctionPoints,
                )
            CameraStyle.SMOOTH_FOLLOW ->
                SmoothNavigationCamera(
                    mapboxMap,
                    mapView.location,
                    mapView.camera,
                    viewportDataSource,
                    CameraTuning.PHONE,
                    cameraListener,
                    junctionPoints,
                )
        }
        created.setLaneZoom(laneZoom)
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
                // Lane details are looked at with each line, and set again if something turned them off.
                laneDetails.check()
                // The own route line, with its switch on: the SDK's line again where it has no data.
                if (ownLine.enabled) ownLine.check()
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
     * The camera line, about 600 bytes a second (2.2 MB an hour of driving). Lags and steps are in
     * meters, gaps in milliseconds. A smooth camera moves on every frame ("camUpdates" close to
     * "render.frames", "camGapMs" near one frame); a stepping one shows long gaps and large "steps".
     * "to" is the frame the viewport data source asks for. "laneDp" is how wide a lane of 3.7 m is
     * on the screen at the camera's center. "renderFps" is the map's own frame rate against the
     * screen's refresh; "animatorsEnabled" is false when Android's animations are switched off.
     * "style" is the camera style in use, so drives in different styles can be compared.
     *
     * The camera of the style adds its own fields. The two Mapbox styles: "frameUpdates", the camera
     * animations started, and "frameUpdatesCut", those restarted before they finished; "laneZoom"
     * (the setting), "laneGoal" (1 while the lane choices' schedule is in for one; it moves the
     * limits only in Mapbox tuned with Large) and "maxZoom" and "minZoom", the limits the data
     * source fits its frames within. Smooth follow: "camFps", "camMaxGapMs" and "camWrites" (its
     * frames, the longest time between two of them and how many set the map's camera), "focus" and
     * "focusGoal" (how far the view is tilted onto the next maneuver, from 0 to 1, and where that
     * is going), "zoomTarget", "bearingError" (the bearing still to turn) and "onPuck" (1 while the
     * camera centers on the drawn car, 0 while it frames a maneuver or the route); "laneZoom",
     * "zoomPlan" (what sets the zoom: ROAD, JUNCTION, AFTER_JUNCTION or LANES), "laneFocus" and
     * "laneGoal" (the lane choices' schedule: how far it is in for one, and where that is going;
     * it moves the zoom only with Large), "laneAheadM" (meters to the nearest lane choice that
     * counts), "laneChoice" (the one that holds the view) and "centerLeadS" (how many seconds ahead
     * of the car the center aims).
     *
     * "laneDetails" is the switch for lane markings on the map, "hdRoads" what the style says about
     * them, and "hdRoadsReapplied" how often they had to be set again since the map was made: 0
     * unless something keeps turning them off.
     *
     * "routeLine", only with the Debug switch for the app's own route line on, says whether the own
     * line is drawn ("own"), how often its trim was set that second ("trims"), how far at most its
     * trimmed end lay from the SDK's vanishing point along the route ("trimLagM", in meters) and how
     * long its part of a puck update took at most on the main thread ("trimMaxMs").
     *
     * "aheadM", while the camera follows the car, is how much road the camera as shown has clear
     * above the car, in meters, by the look-ahead model ([LookAhead]): from the car's row, the
     * padding's bottom edge, to the padding's top edge, under the card. With the speed it says how
     * many seconds of road the close view shows; a screenshot checks the model. The cameras add
     * "runUp" (the exit of the run-up whose close view holds), "laneCapZoom" (the closest zoom the
     * lanes may take and still show enough road) and "capSpeed" (the speed that floor is worked
     * out for, the highest of the last few seconds); Smooth follow's "laneChoice" has its "key".
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
            "laneDp" to Motion.round(MapScale.laneDp(shown.zoom, shown.center.latitude()), 1),
            "aheadM" to clearRoadAhead(shown),
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
        ) + camera.diagnostics() + junctionPoints.diagnostics() + laneDetails.diagnostics() +
            // With the Debug switch on: who draws the route line, and how its trim keeps up.
            (if (ownLine.enabled) ownLine.diagnostics() else emptyMap()) + mapOf(
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

    /**
     * How much road the camera as shown has clear above the car while it follows it, in whole
     * meters ([LookAhead.metersAhead]); null in any other mode, before the padding is set, and
     * where the model has no answer.
     */
    private fun clearRoadAhead(shown: CameraState): Long? {
        val padding = padding ?: return null
        if (_cameraMode.value != CameraMode.FOLLOWING) return null
        val height = mapboxMap.getSize().height.toDouble()
        val meters = LookAhead.metersAhead(
            zoom = shown.zoom,
            pitchDegrees = shown.pitch,
            mapHeightPx = height,
            carRowPx = height - padding.bottom,
            rowPx = padding.top.toDouble(),
            pixelRatio = mapboxMap.getMapOptions().pixelRatio.toDouble(),
            latitude = shown.center.latitude(),
        )
        return if (meters.isFinite()) Math.round(meters) else null
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

        /**
         * Free above the logo and the attribution button. The shadow under a panel over the light map
         * reaches about 10 dp below it, so this keeps it off the logo and the (i); the same in both
         * looks, so a change of look never moves a panel.
         */
        internal const val SHADOW_CLEARANCE_DP = 12f

        /** Height of the strip above the bottom inset that the logo and attribution need; panels stay above it. */
        const val ORNAMENT_STRIP_DP = ORNAMENT_MARGIN_DP + LOGO_HEIGHT_DP + SHADOW_CLEARANCE_DP

        /** Standard style slot above roads and below 3D buildings and labels. */
        private const val ROUTE_SLOT = "middle"

        /** The slots the "map_layers" line lists: the route's, and the own lanes' slots inside it where they are used. */
        private val ROUTE_SLOTS = setOf(ROUTE_SLOT, LaneLayers.UNDER_SLOT, LaneLayers.OVER_SLOT)
        private const val ROUTE_TAP_RADIUS_DP = 30f
        private const val FADE_IN_MS = 250L
        private const val CAMERA_SAMPLE_MS = 1_000L

        /** In the preview the data source follows the puck: it looks this often and acts past this distance. */
        private const val PREVIEW_TRACK_MS = 1_000L
        private const val PREVIEW_TRACK_METERS = 5.0
        private const val NANOS_PER_MILLI = 1_000_000L

        /** The map reports its frame rate about once a second; an older value means it stopped drawing. */
        private const val FPS_FRESH_NANOS = 2_000_000_000L

        /** How long, and how often, a camera that ended a move is asked whether its springs are at rest. */
        private const val SETTLE_WATCH_MS = 5_000L
        private const val SETTLE_POLL_MS = 100L

        /** How long a write of the theme waits for the map to settle before it says it did not. */
        private const val LOOK_SETTLE_MS = 10_000L
    }
}
