package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.car.CarZoom
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState

/** The one action of the car screen's action strip. */
enum class CarHomeAction {
    /** Before a drive and in the route preview: how a drive starts, which is on the phone. */
    START_ON_PHONE,

    /** During a drive: it ends, as the phone's End ends it. */
    END,
}

/** The last button of the map's action strip, after Pan and the two zoom buttons, and the camera it asks for. */
enum class CarMapButton(val target: CameraMode) {
    /** Recenter: the camera takes the map back and follows the car again. */
    RECENTER(CameraMode.FOLLOWING),

    /** Recenter in the route preview, which has no camera on the car: the routes framed again. */
    RECENTER_ROUTES(CameraMode.OVERVIEW),

    /** During a drive while the camera follows the car: the whole of the rest of the route. */
    OVERVIEW(CameraMode.OVERVIEW),
}

/**
 * What the car's home screen offers over the map, from the drive's state and the camera's. The map
 * fills the screen in every state; the host draws these buttons (docs/STAGE-2.md, Action strips).
 *
 * - The action strip holds one action: [CarHomeAction.START_ON_PHONE] before a drive, in the route
 *   preview and at the arrival, [CarHomeAction.END] during the drive.
 * - The map's action strip holds Pan, zoom in and zoom out, and [mapButton]: Recenter while the
 *   camera is idle, otherwise the switch between following the car and the overview. During a drive
 *   the overview offers Recenter, back to following, as the phone's guidance bar does. Before a drive
 *   the camera never shows a following state of its own, so Recenter stays, the phone's My location.
 *   In the route preview the camera shows the routes and has nothing to switch to; once the driver
 *   has moved the map, Recenter frames the routes again.
 */
data class CarHome(val action: CarHomeAction, val mapButton: CarMapButton?) {

    companion object {
        /** How far one press of a zoom button zooms in; zoom out is the same back. */
        const val ZOOM_STEP = CarZoom.STEP

        fun of(guidance: GuidanceState, camera: CameraMode): CarHome = CarHome(actionOf(guidance), mapButtonOf(guidance, camera))

        fun actionOf(guidance: GuidanceState): CarHomeAction =
            if (guidance == GuidanceState.ACTIVE) CarHomeAction.END else CarHomeAction.START_ON_PHONE

        fun mapButtonOf(guidance: GuidanceState, camera: CameraMode): CarMapButton? = when (guidance) {
            GuidanceState.ACTIVE -> if (camera == CameraMode.FOLLOWING) CarMapButton.OVERVIEW else CarMapButton.RECENTER
            GuidanceState.PREVIEW -> if (camera == CameraMode.IDLE) CarMapButton.RECENTER_ROUTES else null
            GuidanceState.IDLE, GuidanceState.ARRIVED -> CarMapButton.RECENTER
        }
    }
}
