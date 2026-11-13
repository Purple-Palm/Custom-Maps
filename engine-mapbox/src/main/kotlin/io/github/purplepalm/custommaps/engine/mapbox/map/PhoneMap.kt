package io.github.purplepalm.custommaps.engine.mapbox.map

import android.content.Context
import android.view.Gravity
import android.view.View
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import io.github.purplepalm.custommaps.engine.mapbox.R
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.engine.mapbox.offline.OfflineData
import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.MapRenderer
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.model.Place
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's map: a [MapView] that draws the [MapScene] every map of the app shares, with the parts
 * only a view has. The scene is the map itself: the style in the day or the night look, the puck, the
 * routes, the camera and the debug lines ([MapScene]). This class adds the Mapbox logo and the (i)
 * button in their fixed strip, the compass and the (i) dialog in the look's variants, the fade-in once
 * the style has loaded, and the tap that picks a route of the preview.
 *
 * The view's lifecycle follows the hosting activity; say with [setVisible] when the map is on screen,
 * and call [release] when the view leaves the composition.
 */
class PhoneMap internal constructor(
    context: Context,
    private val engine: MapboxNavigationEngine,
    debugLog: DebugLog,
    offlineData: OfflineData,
    /** The look the map shows from its first tile on; [setLook] brings any later one. */
    lookOnMap: LookOnMap,
) : MapRenderer {

    private val mapView = MapView(context, MapInitOptions(context, styleUri = null))
    private val density = context.resources.displayMetrics.density

    /** The map as an Android view, to place in the screen layout. */
    val view: View get() = mapView

    /** The (i) dialog, in the variant of the look; the SDK's own dialog. */
    private val attributionDialogs = LookAttribution(context, lookOnMap.look)

    /** The look of the compass and of the (i) dialog. */
    private var ornamentLook = lookOnMap.look

    private val mapClickListener = OnMapClickListener { point ->
        if (engine.guidanceState.value == GuidanceState.PREVIEW) scene.selectRouteNear(point, ROUTE_TAP_RADIUS_DP * density)
        false
    }

    /** What the scene asks of the view, and tells it. */
    private val host = object : MapScene.Host {
        override val shown: Boolean get() = mapView.isShown

        override val refreshRate: Float? get() = mapView.display?.refreshRate

        override fun onStyleLoaded() {
            mapView.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }

        override fun onLook(look: Look) {
            if (look != ornamentLook) {
                ornamentLook = look
                showCompass(look)
                attributionDialogs.setLook(look)
            }
        }
    }

    private val scene: MapScene

    init {
        mapView.alpha = 0f
        mapView.scalebar.enabled = false
        // The compass and the (i) dialog in the look's own variants. The logo and the (i) button
        // stay as they are.
        showCompass(lookOnMap.look)
        mapView.attribution.setCustomAttributionDialogManager(attributionDialogs)
        scene = MapScene(
            context = context,
            engine = engine,
            debugLog = debugLog,
            offlineData = offlineData,
            lookOnMap = lookOnMap,
            mapboxMap = mapView.mapboxMap,
            plugins = mapView,
            controllable = mapView,
            tuning = CameraTuning.PHONE,
            guidancePuck = engine.guidancePuck,
            host = host,
        )
        mapView.gestures.addOnMapClickListener(mapClickListener)
    }

    override val cameraMode: StateFlow<CameraMode> get() = scene.cameraMode

    override val closeView: StateFlow<Boolean> get() = scene.closeView

    /**
     * True once Mapbox refused the access token for the style or tiles. The map then stays empty;
     * a new token recreates the screen, and this map with it.
     */
    val accessDenied: StateFlow<Boolean> get() = scene.accessDenied

    override fun setCameraMode(mode: CameraMode) = scene.setCameraMode(mode)

    override fun setViewportPadding(padding: ViewportPadding) = scene.setViewportPadding(padding)

    override fun setCameraStyle(style: CameraStyle) = scene.setCameraStyle(style)

    override fun setLaneZoom(mode: LaneZoom) = scene.setLaneZoom(mode)

    override fun setLaneDetails(enabled: Boolean) = scene.setLaneDetails(enabled)

    override fun setOwnRouteLine(enabled: Boolean) = scene.setOwnRouteLine(enabled)

    override fun setDrawOwnLanes(enabled: Boolean) = scene.setDrawOwnLanes(enabled)

    override fun showPlace(place: Place?) = scene.showPlace(place)

    override fun setLook(request: LookOnMap, onSettled: (idleMs: Long?) -> Unit): LookApplied = scene.setLook(request, onSettled)

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
    fun onLocationPermissionGranted() = scene.onLocationPermissionGranted()

    /**
     * Call with false while the map is not on screen, as when the hosting activity is stopped, and
     * with true when it is again. A camera that draws its own frames draws none in between. The map
     * starts as not on screen.
     */
    fun setVisible(visible: Boolean) = scene.setVisible(visible)

    /** Stops listening to the navigator. The map view itself follows the hosting activity's lifecycle. */
    fun release() {
        scene.release()
        mapView.gestures.removeOnMapClickListener(mapClickListener)
    }

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

        private const val ROUTE_TAP_RADIUS_DP = 30f
        private const val FADE_IN_MS = 250L
    }
}
