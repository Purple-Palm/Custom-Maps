package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState

/**
 * Whether a new surface of the car map shows the overview again. The host sends a new surface when
 * the car's screen changes its size or layout, and when the app comes back to that screen; by itself
 * the new map follows the car during a drive and frames the routes in the preview. The overview
 * comes back only when the driver asked for it during the drive under way and has not left it since:
 *
 * - The map's Overview button during a drive asks for it ([onButton]). Recenter forgets it, and so
 *   does the route preview's Recenter, which frames the routes of a drive that has not started.
 * - The driver moving the map, with a gesture or a zoom button, leaves it ([onDriverMoved]).
 * - A drive that ends forgets it ([onGuidance]), so the next drive starts by following the car.
 *
 * Plain state: the caller reports each state of the drive as it changes, and the state of now with
 * each button and with each new surface ([restores]).
 */
class CarOverview {

    /** Whether the driver asked for the overview during the drive under way and has not left it since. */
    private var asked = false

    /** The drive is in [state] now: outside a drive there is no overview to bring back. */
    fun onGuidance(state: GuidanceState) {
        if (state != GuidanceState.ACTIVE) asked = false
    }

    /** A button of the map's action strip asked the camera for [mode] while the drive was in [guidance]. */
    fun onButton(mode: CameraMode, guidance: GuidanceState) {
        asked = mode == CameraMode.OVERVIEW && guidance == GuidanceState.ACTIVE
    }

    /** The driver moved the map, with a gesture or a zoom button: the camera has left the overview. */
    fun onDriverMoved() {
        asked = false
    }

    /**
     * Whether a new surface asks for the overview again once its style has loaded, the drive being in
     * [guidance] now: never outside a drive, also when its end has not been reported yet. Asking
     * does not use it up: the next surface of the same drive shows it too.
     */
    fun restores(guidance: GuidanceState): Boolean {
        onGuidance(guidance)
        return asked
    }
}
