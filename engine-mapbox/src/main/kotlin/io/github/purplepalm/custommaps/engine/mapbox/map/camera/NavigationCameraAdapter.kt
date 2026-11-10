package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.ValueAnimator
import android.os.SystemClock
import com.mapbox.common.location.Location
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.animation.CameraAnimationsLifecycleListener
import com.mapbox.maps.plugin.animation.CameraAnimationsPlugin
import com.mapbox.maps.plugin.animation.CameraAnimatorType
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.data.ViewportData
import com.mapbox.navigation.ui.maps.camera.data.ViewportDataSource
import com.mapbox.navigation.ui.maps.camera.data.ViewportDataSourceUpdateObserver
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraStateChangedObserver
import io.github.purplepalm.custommaps.nav.camera.OncePerUpdate
import io.github.purplepalm.custommaps.nav.model.CameraMode

/**
 * The navigation SDK's own camera as a [GuidanceCamera]: [NavigationCamera] with
 * [NavigationBasicGesturesHandler], which turns it idle when anything else moves the map. It serves
 * two camera styles.
 *
 * Mapbox standard is the camera as it comes. The data source is evaluated on the location and again
 * on the route progress of each update of the navigator, and each evaluation starts a new animation
 * of the camera of one second.
 *
 * Mapbox tuned ([tuned]) is the same camera with calmer moves:
 * - The data source is evaluated once for each update of the navigator, on the route progress, so
 *   every frame is computed from the location and the progress of the same update, and no animation
 *   is started only to be replaced a moment later.
 * - Following frames are reached with the longer animations of [FollowingTransition].
 * - A jump of the matched position moves the camera there at once.
 */
internal class NavigationCameraAdapter(
    mapboxMap: MapboxMap,
    private val cameraPlugin: CameraAnimationsPlugin,
    private val dataSource: MapboxNavigationViewportDataSource,
    private val tuned: Boolean,
    private val listener: GuidanceCamera.Listener,
) : GuidanceCamera {

    private val source = DetachableSource(dataSource)
    private val navigationCamera =
        if (tuned) {
            NavigationCamera(mapboxMap, cameraPlugin, source, FollowingTransition(mapboxMap, cameraPlugin))
        } else {
            NavigationCamera(mapboxMap, cameraPlugin, source)
        }
    private val gesturesHandler = NavigationBasicGesturesHandler(navigationCamera)

    /** For the tuned style: which message of an update the frame is computed on. */
    private val pacing = OncePerUpdate()

    // For the camera line: the camera's frame updates, and the ones cut short by the next.
    private var frameUpdates = 0
    private var frameUpdatesCut = 0

    /**
     * Counts the camera's frame updates, each one a new animation, and the ones cancelled before
     * they finished because the next update or a gesture came first. Also reports who takes the map
     * from the camera. Added before [gesturesHandler], so it still sees what the camera was doing.
     */
    private val animations = object : CameraAnimationsLifecycleListener {
        override fun onAnimatorStarting(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) {
            if (owner == NavigationCamera.NAVIGATION_CAMERA_OWNER) {
                if (type == CameraAnimatorType.CENTER) frameUpdates += 1
            } else if (navigationCamera.state != NavigationCameraState.IDLE) {
                listener.onCameraTaken(owner, type.name, navigationCamera.state.name)
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

    private val stateObserver = NavigationCameraStateChangedObserver { state ->
        listener.onCameraState(state.toCameraMode(), state.name, state.isTransition())
    }

    init {
        cameraPlugin.addCameraAnimationsLifecycleListener(animations)
        cameraPlugin.addCameraAnimationsLifecycleListener(gesturesHandler)
        // Reports the state it starts in, idle, at once.
        navigationCamera.registerNavigationCameraStateChangeObserver(stateObserver)
    }

    override val isIdle: Boolean get() = navigationCamera.state == NavigationCameraState.IDLE

    override val state: String get() = navigationCamera.state.name

    override fun requestFollowing() {
        if (tuned) {
            // Start and Recenter both come through here, so both get the tuned frames.
            navigationCamera.requestNavigationCameraToFollowing(frameTransitionOptions = FollowingTransition.FRAME_OPTIONS)
        } else {
            navigationCamera.requestNavigationCameraToFollowing()
        }
    }

    override fun requestOverview() = navigationCamera.requestNavigationCameraToOverview()

    override fun requestIdle() = navigationCamera.requestNavigationCameraToIdle()

    override fun onLocation(location: Location, isTeleport: Boolean) {
        dataSource.onLocationChanged(location)
        if (!tuned) {
            dataSource.evaluate()
            return
        }
        // The route progress of this update comes right after the location and evaluates.
        if (pacing.locationComputes(SystemClock.elapsedRealtime(), isTeleport)) dataSource.evaluate()
        // The puck jumps on a teleport, and the camera with it: neither slides across the map.
        if (isTeleport) navigationCamera.resetFrame()
    }

    override fun onRouteProgress(progress: RouteProgress) {
        dataSource.onRouteProgressChanged(progress)
        dataSource.evaluate()
        if (tuned) pacing.onProgress(SystemClock.elapsedRealtime())
    }

    // This camera keeps nothing for a drive.
    override fun onGuidanceEnded() = Unit

    // It draws no frames of its own that would have to stop.
    override fun setVisible(visible: Boolean) = Unit

    override fun diagnostics(): Map<String, Any?> {
        val fields = mapOf("frameUpdates" to frameUpdates, "frameUpdatesCut" to frameUpdatesCut)
        frameUpdates = 0
        frameUpdatesCut = 0
        return fields
    }

    override fun destroy() {
        // First, so that going idle below is not reported.
        navigationCamera.unregisterNavigationCameraStateChangeObserver(stateObserver)
        cameraPlugin.removeCameraAnimationsLifecycleListener(gesturesHandler)
        cameraPlugin.removeCameraAnimationsLifecycleListener(animations)
        // Cancels the animation it is running.
        navigationCamera.requestNavigationCameraToIdle()
        // Neither style of this camera sets anything on the data source, so nothing is taken back.
        source.detach()
    }

    private fun NavigationCameraState.isTransition(): Boolean =
        this == NavigationCameraState.TRANSITION_TO_FOLLOWING || this == NavigationCameraState.TRANSITION_TO_OVERVIEW

    private fun NavigationCameraState.toCameraMode(): CameraMode = when (this) {
        NavigationCameraState.FOLLOWING, NavigationCameraState.TRANSITION_TO_FOLLOWING -> CameraMode.FOLLOWING
        NavigationCameraState.OVERVIEW, NavigationCameraState.TRANSITION_TO_OVERVIEW -> CameraMode.OVERVIEW
        NavigationCameraState.IDLE -> CameraMode.IDLE
    }

    /**
     * [NavigationCamera] listens to its data source for as long as it lives and has no call to stop.
     * It gets this stand-in, which passes everything on to the real data source and can take the
     * camera's observer off it again when the camera is destroyed.
     */
    private class DetachableSource(private val source: ViewportDataSource) : ViewportDataSource {
        private val observers = ArrayList<ViewportDataSourceUpdateObserver>(1)

        override fun getViewportData(): ViewportData = source.getViewportData()

        override fun registerUpdateObserver(viewportDataSourceUpdateObserver: ViewportDataSourceUpdateObserver) {
            observers += viewportDataSourceUpdateObserver
            source.registerUpdateObserver(viewportDataSourceUpdateObserver)
        }

        override fun unregisterUpdateObserver(viewportDataSourceUpdateObserver: ViewportDataSourceUpdateObserver) {
            observers -= viewportDataSourceUpdateObserver
            source.unregisterUpdateObserver(viewportDataSourceUpdateObserver)
        }

        fun detach() {
            observers.forEach(source::unregisterUpdateObserver)
            observers.clear()
        }
    }
}
