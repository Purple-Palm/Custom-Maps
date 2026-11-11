package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.github.purplepalm.custommaps.nav.camera.CameraSettling
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.LaneZoom

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
     * It ends with the next request, or when the driver moves the map. Asked again while it
     * follows, the camera goes to the car again.
     */
    fun requestIdleFollow(puck: Point)

    /** Stops moving the map at once. */
    fun requestIdle()

    /**
     * Outside guidance: the phone's location gave the puck a new position, and the puck starts to
     * glide to [target] at a steady speed over [glideMillis]. With 0 the position is far from where
     * the puck was drawn, and the puck is put there at once; a camera that follows it is put there
     * with it.
     */
    fun onPuckGlide(target: Point, glideMillis: Long)

    /**
     * A new matched position from the navigator. [isTeleport] is true when the position jumped, as it
     * does when the navigator matches the car to another road.
     */
    fun onLocation(location: Location, isTeleport: Boolean)

    /**
     * The car was put somewhere else along the route, as a seek of a simulated drive puts it; the
     * next position, a jump, is where it is now. What the camera keeps of the road it was on (the
     * view down onto a maneuver, the close view for lanes, the last junction's zoom) is let go
     * from where it is, so the camera starts from the new place as if it had come there by road.
     */
    fun onJump()

    /**
     * The route progress of the same update of the navigator; it comes right after the location.
     * [laneChoices] are the points with lanes ahead of the car at this update, as the engine reads
     * them from the route; the Large lane zoom goes close for the ones that count.
     */
    fun onRouteProgress(progress: RouteProgress, laneChoices: List<LaneChoice>)

    /** There is no guidance, or no longer: whatever the camera kept for the drive is dropped. */
    fun onGuidanceEnded()

    /**
     * How close the view goes before turns and lane choices. A change during a drive is glided to.
     * The map sets up the data source's frames for the mode ([LaneZoomFrames]) before it calls this.
     */
    fun setLaneZoom(mode: LaneZoom)

    /** False while the map is not on screen; a camera that draws its own frames stops them then. */
    fun setVisible(visible: Boolean)

    /**
     * What this camera adds to the once-a-second camera line of the debug log. Counts cover the time
     * since the last call and start again.
     */
    fun diagnostics(): Map<String, Any?>

    /**
     * How far the camera's own springs still are from where they go, as of its last frame; null for
     * a camera without springs of its own. Read now and then after a move between modes.
     */
    fun settling(): CameraSettling? = null

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
