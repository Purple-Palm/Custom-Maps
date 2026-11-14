package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.DriveViewControl
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RecenterControl
import kotlinx.coroutines.flow.StateFlow

/** Draws the map and the route and moves the camera, on the phone or in the car. */
interface MapRenderer {
    val cameraMode: StateFlow<CameraMode>

    /**
     * True while the guidance camera shows a close view: the view going down onto a junction or in
     * for a lane choice, held there, or coming back ([io.github.purplepalm.custommaps.nav.camera.CloseView]).
     * Worked out once per progress update of a drive, never per frame, and false outside guidance.
     */
    val closeView: StateFlow<Boolean>

    /**
     * The camera on the browsing map and in the route preview: My location, and the routes framed again.
     * During guidance the drive views decide ([setDriveCamera]); there a mode asked for here is a button
     * of the map's own, as the car's map has: [CameraMode.OVERVIEW] the Overview drive view for this map
     * and this drive, [CameraMode.FOLLOWING] the Normal one and the car at once, also out of a hold.
     */
    fun setCameraMode(mode: CameraMode)

    /** What the Overview toggle shows: it changes only when the drive view or Zoom in before turns is chosen. */
    val driveViewControl: StateFlow<DriveViewControl>

    /**
     * What Recenter shows while the driver holds the map during guidance, its count in whole seconds;
     * null while nothing is held. It changes once a second at most, and only during a hold.
     */
    val recenterControl: StateFlow<RecenterControl?>

    /**
     * The drive views' settings ([DriveCameraSettings]): the drive view, Zoom in before turns and Recenter
     * by itself. A change applies at once, also during a drive: a new drive view ends a hold of the map
     * and shows at once; the other two keep a hold, whose count takes the new value.
     */
    fun setDriveCamera(settings: DriveCameraSettings)

    /**
     * A tap on the Overview toggle during guidance: the other drive view, at once, also during a hold,
     * which it ends. Returns the drive view chosen, for the caller to keep for the next drive.
     */
    fun toggleDriveView(): DriveView

    /** A tap on Recenter: the map the driver holds goes back where the drive view says. Nothing without a hold. */
    fun recenter()

    /**
     * A look at the overview for a few seconds, a hold of its own that ends by itself; none while a turn
     * is near or where the overview could not stand that long.
     */
    fun lookAtRoutes()

    /** How much more room than the following view the overview keeps at the bottom of the map, in pixels; 0 for none. */
    fun setOverviewBottomReserve(pixels: Int)

    /** Screen area covered by UI, which the camera must keep the route out of. */
    fun setViewportPadding(padding: ViewportPadding)

    /**
     * How the camera moves during guidance and in the route preview. A change applies at once, also
     * during a drive: the camera goes on with what it was doing, in the new style.
     */
    fun setCameraStyle(style: CameraStyle)

    /**
     * How close the camera goes before turns and lane choices during guidance. A change applies at
     * once, also during a drive, and the view glides to it. The Mapbox standard camera style keeps
     * its own zoom whatever is set here.
     */
    fun setLaneZoom(mode: LaneZoom)

    /**
     * Who draws the lanes on the map ([LanesOnMap]): Mapbox's lane markings, painted arrows and
     * crosswalks where the map data has them, with the route line thin close in; the app's own
     * drawing of the freeway lanes during guidance, with its own route line in the lane the lane
     * plan names and, over the drawn lanes in a close view, the car as a bar across the road; or
     * neither. A change applies at once, also during a drive, and touches no trip: the card's lane
     * row, the run-up and its close view are the same with every choice. The car is drawn where it
     * is along the road, never in a lane.
     */
    fun setLanesOnMap(choice: LanesOnMap)

    /**
     * Debug: whether the route line of a drive is drawn by the app itself, along the lanes of the
     * route's lane plan, in place of the navigation SDK's, which comes back by itself if the app's
     * fails. Off, nothing changes, unless the app draws the lanes ([setLanesOnMap]), which draws
     * its own route line too. A change applies at once, also during a drive.
     */
    fun setOwnRouteLine(enabled: Boolean)

    /**
     * Marks [place] on the map with a pin at the place itself, such as the building, wherever a
     * route to it ends; null takes the pin away. One place is marked at a time.
     */
    fun showPlace(place: Place?)

    /**
     * Shows [request]: [LookOnMap.look] for the basemap's light and colours and for the map's own
     * marks, and the basemap's theme of [LookOnMap.theme]. Config writes on the style the map
     * shows, all within this call; never a style reload. A map whose style is still loading takes
     * the request there, with the look's own theme. The same request again writes nothing.
     *
     * Returns what was written, for the caller's own log line: the map writes no line about it.
     * After a write that includes the theme, [onSettled] hears once how many milliseconds later the
     * map had drawn all its tiles again, or null when that had not happened within 10 s.
     */
    fun setLook(request: LookOnMap, onSettled: (idleMs: Long?) -> Unit = {}): LookApplied
}

/**
 * What a map did with a look it was asked to show ([MapRenderer.setLook]), in plain values for a
 * log line.
 */
data class LookApplied(
    /** Keys of the basemap's config written now. */
    val keys: Int = 0,
    /** How many of the writes the map refused, and for which keys. */
    val errors: Int = 0,
    val failedKeys: List<String> = emptyList(),
    /** How long the call took on the main thread, in milliseconds. */
    val writeMs: Double = 0.0,
    /**
     * For keys given back to the style, as the day look gives back every colour the night sets:
     * "null" when each read back as the style had it, "snapshot" when one had to be written back
     * from what the style said as it loaded. Null when no key was given back.
     */
    val reset: String? = null,
    /** The style's transition the light change follows, in milliseconds; null when nothing was written. */
    val transitionMs: Long? = null,
    /** Whether the route line took the look's colours now. */
    val routeLine: Boolean = false,
    /** Whether the basemap's theme was written now; the settle time then follows. */
    val theme: Boolean = false,
    /**
     * Whether there was a map to take the look. False only from a renderer that has no map to write
     * on now, as the car's between two surfaces, whose next map takes the look as it is made: the
     * look's line then waits for the fields of a map that has one.
     */
    val onMap: Boolean = true,
)

/** Insets in pixels from each edge of the map surface. */
data class ViewportPadding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) { "Padding must not be negative: $this" }
    }

    companion object {
        val NONE = ViewportPadding(0, 0, 0, 0)
    }
}
