package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import com.mapbox.common.Cancelable
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.animation.CameraAnimationsLifecycleListener
import com.mapbox.maps.plugin.animation.CameraAnimationsPlugin
import com.mapbox.maps.plugin.animation.CameraAnimatorType
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.data.ViewportData
import com.mapbox.navigation.ui.maps.camera.data.ViewportDataSource
import com.mapbox.navigation.ui.maps.camera.data.ViewportDataSourceUpdateObserver
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraStateChangedObserver
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.IdleFollow
import io.github.purplepalm.custommaps.nav.camera.JumpFrame
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.camera.OncePerUpdate
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.LaneZoom

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
 * - The Large lane zoom applies: before each frame is computed, the limits of the data source are
 *   moved so that the frame of a maneuver may be fitted close, and the frame near a lane choice on
 *   a freeway is close ([LaneZoomFrames.beforeTunedEvaluate]). The camera's animations glide there.
 *   Mapbox standard keeps its own zoom whatever the lane zoom is.
 *
 * In both, after a seek of a simulated drive ([onJump]) the camera goes at once to the frame of the
 * car's new place, computed with that place's own progress, as the drawn car does.
 *
 * On the browsing map, before guidance, [NavigationCamera] has nothing to follow the car with. Both
 * styles then move the map with plain eases of the camera plugin: one to the car, and one with every
 * new position of the puck, as long and as steady as the puck's own glide, so the two stay together.
 * They carry an owner name of their own; an animation of any other owner is the driver's.
 */
internal class NavigationCameraAdapter(
    mapboxMap: MapboxMap,
    private val cameraPlugin: CameraAnimationsPlugin,
    private val dataSource: MapboxNavigationViewportDataSource,
    private val tuned: Boolean,
    private val tuning: CameraTuning,
    private val listener: GuidanceCamera.Listener,
    /** The road past a maneuver point that the data source frames with Large; the map owns it. */
    private val junction: JunctionPoints,
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

    /** Settings, Lane zoom. Only the tuned style acts on it. */
    private var laneZoom = LaneZoom.DEFAULT

    /**
     * For the tuned style: whether the lane choices' schedule is in for one on a freeway. It runs
     * with either lane zoom and moves the limits only with Large.
     */
    private val laneFocus = LaneFocus(tuning)

    // For the tuned style: the car's speed and latitude at the latest position.
    private var speedMps: Double? = null
    private var latitude = Double.NaN

    /** After a seek: the new place's progress frames it, and the camera goes there at once. */
    private val jumpFrame = JumpFrame()

    // For the camera line: the camera's frame updates, and the ones cut short by the next.
    private var frameUpdates = 0
    private var frameUpdatesCut = 0

    /** True while this camera follows the car on the browsing map; the navigation camera is idle then. */
    private var idleFollowing = false

    /** The ease that is taking the map to the car. */
    private var idleEase: Cancelable? = null

    /** True while the navigation camera is sent idle on the way to another state, which is reported instead. */
    private var quiet = false

    /**
     * Counts the camera's frame updates, each one a new animation, and the ones cancelled before
     * they finished because the next update or a gesture came first. Also reports who takes the map
     * from the camera, and ends the following on the browsing map then. Added before
     * [gesturesHandler], so it still sees what the camera was doing.
     */
    private val animations = object : CameraAnimationsLifecycleListener {
        override fun onAnimatorStarting(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) {
            when {
                owner == NavigationCamera.NAVIGATION_CAMERA_OWNER -> if (type == CameraAnimatorType.CENTER) frameUpdates += 1
                // This camera's own eases on the browsing map.
                owner == IDLE_FOLLOW_OWNER -> Unit
                idleFollowing -> {
                    listener.onCameraTaken(owner, type.name, state)
                    stopIdleFollow()
                    listener.onCameraState(CameraMode.IDLE, state, moving = false)
                }
                navigationCamera.state != NavigationCameraState.IDLE -> listener.onCameraTaken(owner, type.name, state)
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
        if (!quiet) listener.onCameraState(state.toCameraMode(), state.name, state.isTransition())
    }

    init {
        cameraPlugin.addCameraAnimationsLifecycleListener(animations)
        cameraPlugin.addCameraAnimationsLifecycleListener(gesturesHandler)
        // Reports the state it starts in, idle, at once.
        navigationCamera.registerNavigationCameraStateChangeObserver(stateObserver)
    }

    override val isIdle: Boolean get() = !idleFollowing && navigationCamera.state == NavigationCameraState.IDLE

    override val state: String get() = if (idleFollowing) GuidanceCamera.IDLE_FOLLOW else navigationCamera.state.name

    override fun requestFollowing() {
        stopIdleFollow()
        if (tuned) {
            // Start and Recenter both come through here, so both get the tuned frames.
            navigationCamera.requestNavigationCameraToFollowing(frameTransitionOptions = FollowingTransition.FRAME_OPTIONS)
        } else {
            navigationCamera.requestNavigationCameraToFollowing()
        }
    }

    override fun requestOverview() {
        stopIdleFollow()
        navigationCamera.requestNavigationCameraToOverview()
    }

    override fun requestIdleFollow(puck: Point) {
        if (!idleFollowing) {
            // The navigation camera lets go of the map; what is reported is the following, not that.
            quiet = true
            navigationCamera.requestNavigationCameraToIdle()
            quiet = false
            idleFollowing = true
            listener.onCameraState(CameraMode.IDLE, state, moving = false)
        }
        // Also while it follows already. A plain tap on the map cancels the running ease and starts
        // nothing, so the camera can stand short of the car until the phone has a new position.
        easeToCar(puck, ARRIVE_MS, steady = false)
    }

    override fun requestIdle() {
        if (idleFollowing) {
            stopIdleFollow()
            listener.onCameraState(CameraMode.IDLE, state, moving = false)
        }
        navigationCamera.requestNavigationCameraToIdle()
    }

    override fun onPuckGlide(target: Point, glideMillis: Long) {
        if (idleFollowing) easeToCar(target, glideMillis, steady = true)
    }

    override fun onLocation(location: Location, isTeleport: Boolean) {
        dataSource.onLocationChanged(location)
        if (tuned) {
            speedMps = location.speed ?: speedMps
            latitude = location.latitude
        }
        // After a seek the progress of the new place comes right after and frames it.
        if (!jumpFrame.locationFrames(SystemClock.elapsedRealtime())) return
        if (!tuned) {
            dataSource.evaluate()
            return
        }
        // The route progress of this update comes right after the location and evaluates.
        if (pacing.locationComputes(SystemClock.elapsedRealtime(), isTeleport)) dataSource.evaluate()
        // The puck jumps on a teleport, and the camera with it: neither slides across the map.
        if (isTeleport) navigationCamera.resetFrame()
    }

    override fun onRouteProgress(progress: RouteProgress, laneChoices: List<LaneChoice>) {
        dataSource.onRouteProgressChanged(progress)
        if (tuned) {
            laneFocus.onProgress(
                now = nowSeconds(),
                routeMeters = progress.distanceTraveled.toDouble(),
                choices = laneChoices,
                speedMps = speedMps,
                onRoute = progress.currentState != RouteProgressState.OFF_ROUTE,
            )
            LaneZoomFrames.beforeTunedEvaluate(
                dataSource,
                laneZoom,
                tuning,
                progress,
                laneWindow = laneFocus.goal > 0.0,
                latitude = latitude,
                junction = junction,
            )
        }
        dataSource.evaluate()
        if (tuned) pacing.onProgress(SystemClock.elapsedRealtime())
        // A seek put the car here: the camera goes to the new place's frame at once, with it.
        if (jumpFrame.progressJumps()) navigationCamera.resetFrame()
    }

    override fun onJump() {
        jumpFrame.onJump(SystemClock.elapsedRealtime())
        // Mapbox standard keeps nothing of the road; Mapbox tuned only the lane choices' schedule.
        if (tuned) laneFocus.reset(nowSeconds())
    }

    override fun setLaneZoom(mode: LaneZoom) {
        // The limits follow with the next progress update.
        laneZoom = mode
    }

    override fun onGuidanceEnded() {
        jumpFrame.reset()
        // Mapbox standard keeps nothing else for a drive.
        if (!tuned) return
        laneFocus.reset(nowSeconds())
        speedMps = null
        LaneZoomFrames.resetLimits(dataSource, tuning)
    }

    // It draws no frames of its own that would have to stop.
    override fun setVisible(visible: Boolean) = Unit

    override fun diagnostics(): Map<String, Any?> {
        val limits = dataSource.options.followingFrameOptions
        val fields = mapOf(
            "frameUpdates" to frameUpdates,
            "frameUpdatesCut" to frameUpdatesCut,
            // The setting, whether the lane choices' schedule is in for one (it moves the limits
            // only in the tuned style with Large, and does not run in Mapbox standard), and the
            // limits the data source fits its frames within.
            "laneZoom" to laneZoom,
            "laneGoal" to laneFocus.goal,
            "maxZoom" to limits.maxZoom,
            "minZoom" to limits.minZoom,
        )
        frameUpdates = 0
        frameUpdatesCut = 0
        return fields
    }

    override fun destroy() {
        // First, so that going idle below is not reported.
        navigationCamera.unregisterNavigationCameraStateChangeObserver(stateObserver)
        cameraPlugin.removeCameraAnimationsLifecycleListener(gesturesHandler)
        cameraPlugin.removeCameraAnimationsLifecycleListener(animations)
        // Cancels the animation it is running, on the browsing map and with a route.
        stopIdleFollow()
        navigationCamera.requestNavigationCameraToIdle()
        // The tuned style moved the limits of the data source; Mapbox standard set nothing on it.
        if (tuned) LaneZoomFrames.resetLimits(dataSource, tuning)
        source.detach()
    }

    /**
     * Eases the map to the car at [target], to the browsing zoom and tilt and without padding. A new
     * ease takes the place of the one before, so each names all of them; what is there already
     * starts no animation. [steady] moves at an even speed, as the puck glides.
     */
    private fun easeToCar(target: Point, millis: Long, steady: Boolean) {
        val options = MapAnimationOptions.Builder().owner(IDLE_FOLLOW_OWNER).duration(millis)
        if (steady) options.interpolator(STEADY)
        idleEase = cameraPlugin.easeTo(
            CameraOptions.Builder().center(target).zoom(IdleFollow.ZOOM).pitch(IdleFollow.PITCH).padding(NO_PADDING).build(),
            options.build(),
        )
    }

    /** Ends the following on the browsing map, if it is on, without a word to the listener. */
    private fun stopIdleFollow() {
        if (!idleFollowing) return
        idleFollowing = false
        idleEase?.cancel()
        idleEase = null
    }

    /** The clock of [laneFocus], in seconds. */
    private fun nowSeconds(): Double = SystemClock.elapsedRealtime() / MILLIS_PER_SECOND

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

    private companion object {
        /** Who the eases on the browsing map belong to, as the camera plugin reports it. */
        const val IDLE_FOLLOW_OWNER = "CUSTOM_MAPS_IDLE_FOLLOW"
        const val ARRIVE_MS = (IdleFollow.ARRIVE_SECONDS * 1_000).toLong()
        const val MILLIS_PER_SECOND = 1_000.0
        val STEADY = LinearInterpolator()
        val NO_PADDING = EdgeInsets(0.0, 0.0, 0.0, 0.0)
    }
}
