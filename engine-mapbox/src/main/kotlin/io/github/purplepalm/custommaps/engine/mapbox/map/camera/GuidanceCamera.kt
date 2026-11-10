package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.github.purplepalm.custommaps.nav.model.CameraMode

/**
 * The camera of one map: it shows the route in the preview, follows the car during guidance, and
 * follows it on the browsing map before guidance. A map has one at a time, and while that one is
 * not idle nothing else moves the map's camera.
 *
 * The map gives the viewport data source the routes and the padding. The camera takes each update
 * of the navigator to the data source itself and decides when a new frame is computed from it.
 * Main thread only.
 */
internal interface GuidanceCamera {

    /** True while the camera leaves the map alone: before the first request and after the driver moved the map. */
    val isIdle: Boolean

    /** The camera's own name for what it is doing, for the debug log. */
    val state: String

    /** Follows the car. Asking for what the camera already does changes nothing. */
    fun requestFollowing()

    /** Shows the whole remaining route. */
    fun requestOverview()

    /**
     * Follows the car on the browsing map, where no route is shown: the camera goes to [puck],
     * where the car is drawn, at the browsing zoom and tilt, and stays on the car from then on.
     * The padding is none, so the car sits in the middle of the map whatever panels are shown. To
     * the screen this still counts as idle; the camera's own state name for it is [IDLE_FOLLOW].
     * It ends with the next request, or when the driver moves the map.
     */
    fun requestIdleFollow(puck: Point)

    /** Stops moving the map at once. */
    fun requestIdle()

    /**
     * Outside guidance: the phone's location gave the puck a new position, and the puck starts to
     * glide to [target] at a steady speed over [glideMillis].
     */
    fun onPuckGlide(target: Point, glideMillis: Long)

    /**
     * A new matched position from the navigator. [isTeleport] is true when the position jumped, as it
     * does when the navigator matches the car to another road.
     */
    fun onLocation(location: Location, isTeleport: Boolean)

    /** The route progress of the same update of the navigator; it comes right after the location. */
    fun onRouteProgress(progress: RouteProgress)

    /** There is no guidance, or no longer: whatever the camera kept for the drive is dropped. */
    fun onGuidanceEnded()

    /** False while the map is not on screen; a camera that draws its own frames stops them then. */
    fun setVisible(visible: Boolean)

    /**
     * What this camera adds to the once-a-second camera line of the debug log. Counts cover the time
     * since the last call and start again.
     */
    fun diagnostics(): Map<String, Any?>

    /**
     * Stops for good, as when another camera takes its place: the camera lets go of the map, takes
     * back every limit and override it set on the data source, and calls no listener again.
     */
    fun destroy()

    companion object {
        /** The state name of a camera that follows the car on the browsing map. */
        const val IDLE_FOLLOW = "IDLE_FOLLOW"
    }

    /** Hears what the camera does. Called on the main thread, the first time while the camera is created. */
    interface Listener {

        /**
         * The camera changed what it does. [mode] is what the screen shows it as, [state] the
         * camera's own name for it, and [moving] is true while the camera is still on its way there.
         */
        fun onCameraState(mode: CameraMode, state: String, moving: Boolean)

        /**
         * Something else began to move the map while the camera had it, such as a gesture or the
         * compass; the camera goes idle. [owner] is who started [animator], and [state] what the
         * camera was doing.
         */
        fun onCameraTaken(owner: String?, animator: String, state: String)
    }
}
