package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.model.Place
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

    fun setCameraMode(mode: CameraMode)

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
     * Whether the map draws lane markings, painted arrows and crosswalks where the map data has
     * them. A change applies at once. The car is drawn where it is on the road, never in a lane.
     */
    fun setLaneDetails(enabled: Boolean)

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
