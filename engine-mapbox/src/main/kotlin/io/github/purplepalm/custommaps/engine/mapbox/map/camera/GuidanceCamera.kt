package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.common.location.Location
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.github.purplepalm.custommaps.nav.model.CameraMode

/**
 * The camera of one map during guidance and in the route preview. A map has one at a time, and while
 * that one is not idle nothing else moves the map's camera.
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

    /** Stops moving the map at once. */
    fun requestIdle()

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
