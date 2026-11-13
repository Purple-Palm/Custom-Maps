package io.github.purplepalm.custommaps.engine.mapbox.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.core.graphics.createBitmap
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.ContextMode
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapOptions
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.applyDefaultParams
import com.mapbox.maps.extension.androidauto.DefaultMapboxCarMapGestureHandler
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.extension.androidauto.widgets.LogoWidget
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition
import com.mapbox.maps.toCameraOptions
import io.github.purplepalm.custommaps.engine.mapbox.map.MapPalette
import io.github.purplepalm.custommaps.engine.mapbox.map.MapScene
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.IdleFollow
import io.github.purplepalm.custommaps.nav.car.CarArea
import io.github.purplepalm.custommaps.nav.car.CarAreas
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.car.CarFrameRate
import io.github.purplepalm.custommaps.nav.car.CarLook
import io.github.purplepalm.custommaps.nav.car.CarOverview
import io.github.purplepalm.custommaps.nav.car.CarZoom
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.withFields
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.model.Place
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.mapbox.maps.extension.androidauto.R as CarMapR

/**
 * The map on the car's screen: the same [MapScene] as the phone's, drawn on the surface the car's host
 * gives the app. One per car session; its session makes it with [io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine.createCarMap]
 * once maps may be shown and calls [release] when the session ends. It starts, stops and asks for
 * nothing that Mapbox bills: it draws what the navigator and the engine already have.
 *
 * - **The surface.** One [MapboxCarMap] per session, set up with the car's context, the shared GL
 *   context the car's widgets need ([ContextMode.SHARED]), only the camera and the location
 *   component plugins, and no style of its own: the scene loads Mapbox Standard with the look. Its
 *   surface callback reaches the host through a wrapper that catches every exception: the car, the
 *   phone and the drive share one process, and car-app rethrows an exception of a surface callback on
 *   the main thread. Each surface the host sends ([MapboxCarMapObserver.onAttached]) is a new map:
 *   the car map keeps what it was asked for (the look, the camera style, the lane zoom and details,
 *   the Debug switches, the place, and an overview the driver asked for during the drive under way
 *   and has not left, [CarOverview]) and gives it to the new scene, with the camera where the driver
 *   left the old one.
 * - **The host's areas.** The padding comes from the stable and the visible area and the surface's
 *   size, worked out on every surface ([CarAreas]): following frames the stable area, the overview
 *   the visible one. [setViewportPadding] does nothing here.
 * - **Logo and attribution.** Mapbox's [LogoWidget], never resized or recoloured, and the text
 *   attribution as a [BitmapWidget], in the bottom left corner of the stable area, so no strip covers
 *   them and they do not move. A map surface has no view plugins: without these the car would show
 *   neither.
 * - **Camera and gestures.** The camera of the style chosen in Settings with [CameraTuning.CAR].
 *   The car's scroll, fling and pinch move the map without an animation of their own, so the gesture
 *   handler lets the camera go first ([MapScene.onDriverMoved]); a pinch past the zoom limits is
 *   dropped ([CarZoom]). The zoom buttons come through [zoomBy]. A gesture and a zoom button both
 *   leave the overview, also for the next surface.
 * - **Day and night** are the app's one look, the phone's, which the car's session hands on through
 *   [setLook] as the phone's screen does for its map: the car's map shows what the phone shows, and a
 *   basemap's theme that waits for a quiet moment of the drive waits here too. The host's own mode
 *   ([CarContext.isDarkMode]) colours only the images of its routing card; a "car_look" line says it
 *   beside the look the map shows ([CarLook]), as the map is made and with each look it takes.
 * - **Frames.** Each surface has its frame clock ([CarFrames]), started as the surface comes and
 *   stopped as it goes: the display's frames while the main thread gets them, a timer's while it
 *   gets none, a "frame_clock" line at each switch and the ticks of each second in the camera line.
 *   Smooth follow, the zoom buttons' ease and the puck ([CarPuck]) move on it, so the map goes on
 *   moving while Android gives the main thread no frames; the two Mapbox camera styles keep
 *   Mapbox's own animators, which wait for those frames.
 * - **Lines.** Every line of the car map carries `screen=car`: the scene's camera, camera state and
 *   map_layers lines among them, beside "car_surface", "car_look", "car_location" and "frame_clock".
 *   An exception caught here writes a "car_error" line with the call, the class and the message.
 *
 * The surface draws [CarFrameRate.TARGET_FPS] frames a second: the map library paces the car's surface
 * against a display rate of 60, so its cap is scaled by the rate the display's frames really come at
 * ([CarFrameRate], measured by [CarFrames]), worked out again each second and set when that rate
 * moves by more than a tenth; a "car_surface" line with `event` fps says each new cap. Main thread
 * only.
 */
@OptIn(MapboxExperimental::class)
internal class CarNavigationMap(
    private val carContext: CarContext,
    private val engine: MapboxNavigationEngine,
    debugLog: DebugLog,
    /** The app's look as the map is made, which its first tiles are drawn in; [setLook] brings every later one. */
    lookOnMap: LookOnMap,
) : CarMapRenderer {

    /** The shared debug log, each line marked as the car's. */
    private val log = debugLog.withFields(mapOf(SCREEN to CAR))

    private val scope = MainScope()
    private val carMap = MapboxCarMap()

    /** The frames of the map on the attached surface: from the display, or from a timer while the main thread gets none. */
    private val frames = CarFrames(log)

    // What the car map is asked for, kept from one surface to the next.
    private var cameraStyle = CameraStyle.DEFAULT
    private var laneZoom = LaneZoom.DEFAULT
    private var laneDetails = false
    private var ownRouteLine = false
    private var drawOwnLanes = false
    private var place: Place? = null

    /** Whether a new surface during the drive shows the overview again: only one the driver asked for in it and has not left. */
    private val overview = CarOverview()

    /**
     * Where the camera of the last surface was when the host replaced that surface with a new one;
     * the new surface starts there. A surface the host destroys leaves nothing: the next one, maybe
     * much later, starts where the car is.
     */
    private var leftCamera: CameraOptions? = null

    // The host's areas as it last reported them; null while it has said nothing.
    private var stable: CarArea? = null
    private var visible: CarArea? = null

    /** The app's look as last asked for, kept from one surface to the next: each new map is made with it. */
    private var lookRequest: LookOnMap = lookOnMap

    // What belongs to the surface attached now.
    private var carSurface: MapboxCarMapSurface? = null
    private var scene: MapScene? = null
    private var sceneJobs: Job? = null
    private var logo: LogoWidget? = null
    private var logoWidthPx = 0
    private var logoHeightPx = 0
    private var attribution: BitmapWidget? = null
    private var attributionHeightPx = 0
    private var attributionLook: Look? = null

    /** Surfaces attached so far, for the "car_surface" lines. */
    private var attaches = 0

    /** Whether the engine counts a surface of this car map as showing the map: the phone's map is capped meanwhile. */
    private var surfaceShown = false

    /**
     * The display rate the surface's frame rate cap is set for, kept for the next surface; null until
     * one was measured, when the cap is the one for the rate the map library believes.
     */
    private var capHz: Double? = null

    /** The cap on the surface's frames that is set now. */
    private var maxFps = CarFrameRate.maxFps(CarFrameRate.ASSUMED_HZ)

    /** New positions of the platform's location on this surface since the last "car_location" check. */
    private var platformFixes = 0

    private var released = false

    private val _cameraMode = MutableStateFlow(CameraMode.IDLE)
    override val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _closeView = MutableStateFlow(false)
    override val closeView: StateFlow<Boolean> = _closeView.asStateFlow()

    /** What the scene asks of the car's surface, and tells it. */
    private val sceneHost = object : MapScene.Host {
        // Only a surface that is there has a scene.
        override val shown: Boolean get() = true

        // The host does not say how fast its display refreshes; the camera line leaves it out.
        override val refreshRate: Float? get() = null

        override fun onStyleLoaded() {
            // The scene draws the guidance state right after this: an overview the driver asked for
            // during this drive, and has not left, is asked for again once that is done.
            scope.launch { guard("restoreOverview") { restoreOverview() } }
        }

        override fun onLook(look: Look) {
            drawAttribution(look)
        }

        // The frame clock's ticks of the second, from the display and from the timer while the main
        // thread got no frames, the cap on the surface's frames, and the display rate measured now.
        override fun cameraFields(): Map<String, Any?> = mapOf(
            "clock" to frames.takeTicks(),
            "maxFps" to maxFps,
            "measuredHz" to frames.displayHz()?.let { Motion.round(it, 1) },
        )
    }

    private val observer = object : MapboxCarMapObserver {
        override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) = guard("onAttached") { attach(mapboxCarMapSurface) }

        override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) = guard("onDetached") { detach(mapboxCarMapSurface) }

        override fun onVisibleAreaChanged(visibleArea: Rect, edgeInsets: EdgeInsets) = guard("onVisibleAreaChanged") {
            visible = visibleArea.toCarArea()
            onAreas(EVENT_VISIBLE)
        }

        override fun onStableAreaChanged(stableArea: Rect, edgeInsets: EdgeInsets) = guard("onStableAreaChanged") {
            stable = stableArea.toCarArea()
            onAreas(EVENT_STABLE)
        }
    }

    /**
     * The car's gestures. Each one lets the camera go before the map moves, since none starts an
     * animation the camera could see; the default handler then moves the map, and does nothing on a
     * fling.
     */
    private val gestures = object : DefaultMapboxCarMapGestureHandler() {
        override fun onScroll(mapboxCarMapSurface: MapboxCarMapSurface, visibleCenter: ScreenCoordinate, distanceX: Float, distanceY: Float) {
            try {
                driverMoved(KIND_SCROLL)
                super.onScroll(mapboxCarMapSurface, visibleCenter, distanceX, distanceY)
            } catch (e: RuntimeException) {
                failed("onScroll", e)
            }
        }

        override fun onFling(mapboxCarMapSurface: MapboxCarMapSurface, velocityX: Float, velocityY: Float) {
            try {
                driverMoved(KIND_FLING)
                super.onFling(mapboxCarMapSurface, velocityX, velocityY)
            } catch (e: RuntimeException) {
                failed("onFling", e)
            }
        }

        override fun onScale(mapboxCarMapSurface: MapboxCarMapSurface, focusX: Float, focusY: Float, scaleFactor: Float) {
            try {
                driverMoved(KIND_SCALE)
                val zoom = mapboxCarMapSurface.mapSurface.mapboxMap.cameraState.zoom
                if (CarZoom.scaleAllowed(zoom, scaleFactor.toDouble())) super.onScale(mapboxCarMapSurface, focusX, focusY, scaleFactor)
            } catch (e: RuntimeException) {
                failed("onScale", e)
            }
        }
    }

    init {
        log.log(LOOK_LINE, CarLook.fields(hostDark(), CarLook.CAUSE_START, lookRequest))
        guard("setup") {
            val callback = carMap.prepareSurfaceCallback(carContext, initOptions())
            carMap.registerObserver(observer)
            carMap.setGestureHandler(gestures)
            // The one surface callback of the app; Mapbox's own, behind a wrapper that catches.
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(GuardedSurfaceCallback(callback, { released }, ::failed))
        }
        // A drive that ends takes its overview along: the next one starts by following the car.
        scope.launch { engine.guidanceState.collect { state -> guard("guidance") { overview.onGuidance(state) } } }
    }

    override fun setCameraMode(mode: CameraMode) {
        guard("setCameraMode") {
            overview.onButton(mode, engine.guidanceState.value)
            scene?.setCameraMode(mode)
        }
    }

    /** The car's padding comes from the host's areas; one set from outside is not used. */
    override fun setViewportPadding(padding: ViewportPadding) = Unit

    override fun setCameraStyle(style: CameraStyle) {
        cameraStyle = style
        guard("setCameraStyle") { scene?.setCameraStyle(style) }
    }

    override fun setLaneZoom(mode: LaneZoom) {
        laneZoom = mode
        guard("setLaneZoom") { scene?.setLaneZoom(mode) }
    }

    override fun setLaneDetails(enabled: Boolean) {
        laneDetails = enabled
        guard("setLaneDetails") { scene?.setLaneDetails(enabled) }
    }

    override fun setOwnRouteLine(enabled: Boolean) {
        ownRouteLine = enabled
        guard("setOwnRouteLine") { scene?.setOwnRouteLine(enabled) }
    }

    override fun setDrawOwnLanes(enabled: Boolean) {
        drawOwnLanes = enabled
        guard("setDrawOwnLanes") { scene?.setDrawOwnLanes(enabled) }
    }

    override fun showPlace(place: Place?) {
        this.place = place
        guard("showPlace") { scene?.showPlace(place) }
    }

    /**
     * The app's look, the one the phone's map shows ([LookOnMap]), with a theme that waits as on the
     * phone: written on the map of the surface there is, and kept for every later surface. The same
     * request again writes nothing. A "car_look" line says what the map did with it. Without a
     * surface there is no map to write on ([LookApplied.onMap] false): the next surface's map takes
     * the look as it is made.
     */
    override fun setLook(request: LookOnMap, onSettled: (idleMs: Long?) -> Unit): LookApplied {
        if (released) return NOT_ON_MAP
        if (request == lookRequest) return if (scene != null) LookApplied() else NOT_ON_MAP
        lookRequest = request
        var applied = NOT_ON_MAP
        guard("setLook") {
            val shown = scene?.setLook(request, onSettled)
            applied = shown ?: NOT_ON_MAP
            log.log(LOOK_LINE, CarLook.fields(hostDark(), CarLook.CAUSE_APP, request) + writeFields(shown))
        }
        return applied
    }

    override fun zoomBy(delta: Double) {
        guard("zoomBy") {
            // The scene lets the camera go before it zooms, as for a gesture.
            overview.onDriverMoved()
            scene?.zoomBy(delta)
        }
    }

    override fun release() {
        if (released) return
        released = true
        // The surface's scene goes while its map is still there.
        guard("releaseObserver") { carMap.unregisterObserver(observer) }
        // The library has no teardown, and the host sends nothing after the session ended: the map
        // surface, its render thread, its GL context and its location provider would outlive the
        // session, one more with every reconnect.
        guard("releaseSurface") {
            carMap.carMapSurface?.mapSurface?.let { surface ->
                surface.onStop()
                surface.surfaceDestroyed()
                surface.onDestroy()
            }
        }
        guard("releaseCallback") {
            carMap.setGestureHandler(null)
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        }
        frames.stop()
        showsSurface(false)
        scope.cancel()
        log.log(SURFACE_LINE, mapOf("event" to EVENT_RELEASE, "attaches" to attaches))
    }

    /** A new surface: a new map, given everything the car map keeps. */
    private fun attach(surface: MapboxCarMapSurface) {
        if (released) return
        attaches += 1
        carSurface = surface
        showsSurface(true)
        val mapSurface = surface.mapSurface
        // The car's screen does not need more, and the frames cost the phone's battery and heat: the
        // cap for the display rate measured on the surface before, until this one's is measured.
        maxFps = CarFrameRate.maxFps(capHz ?: CarFrameRate.ASSUMED_HZ)
        mapSurface.setMaximumFps(maxFps)
        // Where the driver left the last surface's map, else where the car was last seen; without
        // either the map waits for the car's first position.
        (leftCamera ?: lastPositionCamera())?.let(mapSurface.mapboxMap::setCamera)
        leftCamera = null
        // The surface's frames, before anything on it asks for one.
        frames.start()
        val created = MapScene(
            context = carContext,
            engine = engine,
            debugLog = log,
            // The phone's map keeps the same style ahead, in the same tile store; a second map of it
            // would load the same tiles again.
            offlineData = null,
            // The app's look: the new map draws its first tiles in the whole of it, theme and all.
            lookOnMap = lookRequest,
            mapboxMap = mapSurface.mapboxMap,
            plugins = mapSurface,
            controllable = mapSurface,
            tuning = CameraTuning.CAR,
            guidancePuck = engine.guidancePuck,
            host = sceneHost,
            onPlatformFix = { platformFixes += 1 },
            // Smooth follow moves on the car's own frames, also while the main thread gets none from the display.
            frames = frames,
            // And so does the puck, which the camera follows.
            puckRelay = CarPuck(frames, log),
        )
        scene = created
        created.setCameraStyle(cameraStyle)
        created.setLaneZoom(laneZoom)
        created.setLaneDetails(laneDetails)
        created.setOwnRouteLine(ownRouteLine)
        created.setDrawOwnLanes(drawOwnLanes)
        created.showPlace(place)
        created.setVisible(true)
        // The library keeps the areas the host reported, also one that came before any surface, but
        // tells its observers of the stable one only while a surface is there, and never again for a
        // new surface.
        carMap.stableArea?.let { stable = it.toCarArea() }
        carMap.visibleArea?.let { visible = it.toCarArea() }
        addWidgets(surface)
        applyAreas()
        platformFixes = 0
        sceneJobs = scope.launch(Dispatchers.Main.immediate) {
            launch { created.cameraMode.collect { _cameraMode.value = it } }
            launch { created.closeView.collect { _closeView.value = it } }
            launch { watchLocation() }
            launch { followFrameRate() }
        }
        log.log(SURFACE_LINE, surfaceFields(EVENT_ATTACH))
    }

    /**
     * The surface goes. When the host sends a new one, the old map is still there and its camera is
     * kept; when the host destroys the surface, the map is gone already, and only what listens is
     * let go of.
     */
    private fun detach(surface: MapboxCarMapSurface) {
        if (surface !== carSurface) return
        sceneJobs?.cancel()
        sceneJobs = null
        // No camera shows a close view without a surface: a switch of the app's look waits for none.
        _closeView.value = false
        val map = surface.mapSurface.mapboxMap
        val mapThere = map.isValid()
        leftCamera = null
        if (mapThere) {
            val shown = map.cameraState
            // A map that never left the whole world has nothing worth keeping.
            if (shown.zoom >= MIN_KEPT_ZOOM) leftCamera = shown.toCameraOptions()
            guard("removeWidgets") { removeWidgets(surface) }
        }
        // Whatever goes wrong here, the next surface starts from nothing of this one.
        guard("releaseScene") { scene?.release() }
        // No frame for a surface that has gone; the next one starts the clock again.
        frames.stop()
        showsSurface(false)
        scene = null
        carSurface = null
        logo = null
        attribution = null
        attributionLook = null
        log.log(SURFACE_LINE, mapOf("event" to EVENT_DETACH, "attaches" to attaches, "mapThere" to mapThere))
    }

    /** The host's areas changed: the padding and the logo's place again, and a line. */
    private fun onAreas(event: String) {
        applyAreas()
        if (carSurface != null) log.log(SURFACE_LINE, surfaceFields(event))
    }

    private fun applyAreas() {
        val surface = carSurface ?: return
        val container = surface.surfaceContainer
        val density = carContext.resources.displayMetrics.density
        val paddings = CarAreas.paddings(container.width, container.height, density, stable, visible)
        scene?.setPadding(following = paddings.following, overview = paddings.overview)
        val place = CarAreas.ornaments(container.width, container.height, density, stable, visible, logoWidthPx, logoHeightPx, attributionHeightPx)
        logo?.setPosition(bottomLeft(place.logoX, place.logoBottom))
        attribution?.setPosition(bottomLeft(place.attributionX, place.attributionBottom))
    }

    /** The Mapbox logo as the library draws it, and the text attribution beside it. */
    private fun addWidgets(surface: MapboxCarMapSurface) {
        val mapSurface = surface.mapSurface
        val resources = carContext.resources
        // The size the library's own logo bitmap has at the car's density, to place the text beside it.
        BitmapFactory.decodeResource(resources, CarMapR.drawable.mapbox_logo_icon)?.let { bitmap ->
            logoWidthPx = bitmap.width
            logoHeightPx = bitmap.height
            bitmap.recycle()
        }
        val logoWidget = LogoWidget(carContext)
        mapSurface.addWidget(logoWidget)
        logo = logoWidget
        val text = attributionBitmap(lookRequest.look)
        attributionHeightPx = text.height
        val textWidget = BitmapWidget(text, bottomLeft(0, 0))
        mapSurface.addWidget(textWidget)
        attribution = textWidget
        attributionLook = lookRequest.look
    }

    private fun removeWidgets(surface: MapboxCarMapSurface) {
        logo?.let(surface.mapSurface::removeWidget)
        attribution?.let(surface.mapSurface::removeWidget)
    }

    /** The attribution in [look]'s colours, once for each change of the look. */
    private fun drawAttribution(look: Look) {
        val widget = attribution ?: return
        if (look == attributionLook) return
        attributionLook = look
        guard("drawAttribution") { widget.updateBitmap(attributionBitmap(look)) }
    }

    /**
     * The text attribution, "© Mapbox © OpenStreetMap", drawn at the car's density in the look's
     * colours, with a thin edge of the other colour so it reads on any part of the map.
     */
    private fun attributionBitmap(look: Look): Bitmap {
        val density = carContext.resources.displayMetrics.density
        val day = look == Look.DAY
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ATTRIBUTION_TEXT_DP * density
            color = if (day) MapPalette.GRAPHITE else MapPalette.PLATINUM
        }
        val edge = Paint(ink).apply {
            style = Paint.Style.STROKE
            strokeWidth = ATTRIBUTION_EDGE_DP * density
            strokeJoin = Paint.Join.ROUND
            color = if (day) ATTRIBUTION_DAY_EDGE else MapPalette.GRAPHITE
        }
        val pad = ATTRIBUTION_EDGE_DP * density
        val metrics = ink.fontMetrics
        val width = ceil(ink.measureText(ATTRIBUTION) + 2 * pad).toInt().coerceAtLeast(1)
        val height = ceil(metrics.descent - metrics.ascent + 2 * pad).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val baseline = pad - metrics.ascent
        canvas.drawText(ATTRIBUTION, pad, baseline, edge)
        canvas.drawText(ATTRIBUTION, pad, baseline, ink)
        return bitmap
    }

    /**
     * What the map of the surface did with a look, for its "car_look" line: whether there was one
     * ([shown] null without a surface, when the next surface's map takes the look as it is made), the
     * style's settings written, the errors, how long the write took and whether it wrote the theme.
     */
    private fun writeFields(shown: LookApplied?): Map<String, Any?> = mapOf(
        "onMap" to (shown != null),
        "keys" to shown?.keys,
        "errors" to shown?.errors?.takeIf { it > 0 },
        "failedKeys" to shown?.failedKeys?.takeIf { it.isNotEmpty() },
        "writeMs" to shown?.writeMs,
        "theme" to shown?.theme,
    ).filterValues { it != null }

    /**
     * During a drive, the overview the driver asked for in it and has not left, again on a new
     * surface ([CarOverview]). The preview needs nothing here: the scene frames its routes on every
     * surface.
     */
    private fun restoreOverview() {
        if (overview.restores(engine.guidanceState.value)) scene?.setCameraMode(CameraMode.OVERVIEW)
    }

    /** Tells the engine whether a surface of this car map shows the map, once for each change: the phone's map draws less meanwhile. */
    private fun showsSurface(shown: Boolean) {
        if (shown == surfaceShown) return
        surfaceShown = shown
        engine.onCarSurface(shown)
    }

    /** The driver took the map with a gesture of [kind]: the camera lets go of it and leaves the overview. */
    private fun driverMoved(kind: String) {
        overview.onDriverMoved()
        scene?.onDriverMoved(kind)
    }

    /**
     * The surface's frame rate cap, once a second: for the display rate measured now, when it moved
     * by more than a tenth from the one the cap is set for ([CarFrameRate]); a "car_surface" line
     * with `event` fps says each rate the cap was worked out for.
     */
    private suspend fun followFrameRate() {
        while (true) {
            delay(FRAME_RATE_CHECK_MS)
            guard("frameRate") {
                val hz = frames.displayHz() ?: return@guard
                if (!CarFrameRate.changed(capHz, hz)) return@guard
                capHz = hz
                val fps = CarFrameRate.maxFps(hz)
                if (fps != maxFps) {
                    maxFps = fps
                    carSurface?.mapSurface?.setMaximumFps(fps)
                }
                log.log(SURFACE_LINE, surfaceFields(EVENT_FPS))
            }
        }
    }

    /**
     * Whether the platform's location reaches the car map outside guidance: with the phone locked and
     * location allowed only while in use, it may not. A "car_location" line some seconds after each
     * surface comes, and whenever that changes. Numbers only.
     */
    private suspend fun watchLocation() {
        var arriving: Boolean? = null
        var since = 0L
        while (true) {
            delay(LOCATION_CHECK_MS)
            since += LOCATION_CHECK_MS
            val fixes = platformFixes
            platformFixes = 0
            // During guidance the engine's puck feeds the map.
            if (engine.guidanceState.value == GuidanceState.ACTIVE) continue
            val now = fixes > 0
            if (now == arriving) continue
            arriving = now
            log.log(
                LOCATION_LINE,
                mapOf("arriving" to now, "fixes" to fixes, "windowS" to LOCATION_CHECK_MS / MILLIS_PER_SECOND, "sinceAttachS" to since / MILLIS_PER_SECOND),
            )
        }
    }

    /** The surface, the areas and how often a surface came, for a "car_surface" line. */
    private fun surfaceFields(event: String): Map<String, Any?> {
        val container = carSurface?.surfaceContainer
        val width = container?.width ?: 0
        val height = container?.height ?: 0
        return mapOf(
            "event" to event,
            "width" to width,
            "height" to height,
            "dpi" to container?.dpi,
            "density" to carContext.resources.displayMetrics.density,
            "stable" to CarAreas.insets(width, height, stable)?.let { listOf(it.top, it.left, it.bottom, it.right) },
            "visible" to CarAreas.insets(width, height, visible)?.let { listOf(it.top, it.left, it.bottom, it.right) },
            "attaches" to attaches,
            "maxFps" to maxFps,
            "measuredHz" to capHz?.let { Motion.round(it, 1) },
        )
    }

    /** The camera at the car's last known position at the browsing zoom; null while there is none. */
    private fun lastPositionCamera(): CameraOptions? {
        val at = engine.lastPosition ?: return null
        return CameraOptions.Builder()
            .center(Point.fromLngLat(at.longitude, at.latitude))
            .zoom(IdleFollow.ZOOM)
            .pitch(IdleFollow.PITCH)
            .build()
    }

    /** The map options of every surface: the car's context, shared with the widgets, two plugins, no style. */
    private fun initOptions() = MapInitOptions(
        context = carContext,
        mapOptions = MapOptions.Builder().applyDefaultParams(carContext).contextMode(ContextMode.SHARED).build(),
        plugins = listOf(Plugin.Mapbox(Plugin.MAPBOX_CAMERA_PLUGIN_ID), Plugin.Mapbox(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID)),
        styleUri = null,
        mapName = MAP_NAME,
    )

    /**
     * The host's day or night mode now, for the "car_look" lines; the map does not follow it. Null,
     * with a "car_error" line, when the context cannot say (it is asked as the car map is made).
     */
    private fun hostDark(): Boolean? = try {
        carContext.isDarkMode
    } catch (e: RuntimeException) {
        failed("isDarkMode", e)
        null
    }

    /** Runs [block], and writes a "car_error" line instead of letting an exception reach the host. */
    private inline fun guard(call: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            failed(call, e)
        }
    }

    private fun failed(call: String, e: RuntimeException) {
        log.log(ERROR_LINE, CarFailure.fields(call, e))
    }

    private fun Rect.toCarArea() = CarArea(left, top, right, bottom)

    /** A place [x] pixels from the surface's left edge and [bottom] pixels up from its bottom edge. */
    private fun bottomLeft(x: Int, bottom: Int): WidgetPosition = WidgetPosition {
        horizontalAlignment = WidgetPosition.Horizontal.LEFT
        verticalAlignment = WidgetPosition.Vertical.BOTTOM
        offsetX = x.toFloat()
        // Toward the bottom is positive.
        offsetY = -bottom.toFloat()
    }

    companion object {
        /** The text attribution that Mapbox and OpenStreetMap ask for on every map. */
        const val ATTRIBUTION = "© Mapbox © OpenStreetMap"

        /** What a look comes to while no surface's map is there to write it on. */
        private val NOT_ON_MAP = LookApplied(onMap = false)

        private const val ATTRIBUTION_TEXT_DP = 12f
        private const val ATTRIBUTION_EDGE_DP = 1.5f
        private const val ATTRIBUTION_DAY_EDGE = 0xFFFFFFFF.toInt()

        private const val SCREEN = "screen"
        private const val CAR = "car"
        private const val MAP_NAME = "car"

        private const val LOOK_LINE = "car_look"
        private const val SURFACE_LINE = "car_surface"
        private const val LOCATION_LINE = "car_location"
        private const val ERROR_LINE = "car_error"

        private const val EVENT_ATTACH = "attach"
        private const val EVENT_DETACH = "detach"
        private const val EVENT_STABLE = "stable"
        private const val EVENT_VISIBLE = "visible"
        private const val EVENT_RELEASE = "release"
        private const val EVENT_FPS = "fps"

        private const val KIND_SCROLL = "scroll"
        private const val KIND_FLING = "fling"
        private const val KIND_SCALE = "scale"

        /** A camera farther out than this, as the whole world before a first position, is not kept for the next surface. */
        private const val MIN_KEPT_ZOOM = 3.0

        /** How often the car map looks whether the platform's location reaches it. */
        private const val LOCATION_CHECK_MS = 10_000L

        /** How often the frame rate cap is worked out again. */
        private const val FRAME_RATE_CHECK_MS = 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
