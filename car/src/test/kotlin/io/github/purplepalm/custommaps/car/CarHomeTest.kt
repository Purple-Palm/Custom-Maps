package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The car home screen's buttons by the drive's state and the camera's (docs/STAGE-2.md, Action strips). */
class CarHomeTest {

    @Test
    fun `End during the drive, Start on phone before it, in the preview and at the arrival`() {
        assertEquals(CarHomeAction.END, CarHome.actionOf(GuidanceState.ACTIVE))
        for (state in listOf(GuidanceState.IDLE, GuidanceState.PREVIEW, GuidanceState.ARRIVED)) {
            assertEquals(CarHomeAction.START_ON_PHONE, CarHome.actionOf(state), "$state")
        }
    }

    @Test
    fun `the map's last button is Recenter while the camera is idle, otherwise the switch of the drive`() {
        val expected = mapOf(
            (GuidanceState.ACTIVE to CameraMode.IDLE) to CarMapButton.RECENTER,
            (GuidanceState.ACTIVE to CameraMode.FOLLOWING) to CarMapButton.OVERVIEW,
            // The overview's way back is to following, as the phone's guidance bar has it.
            (GuidanceState.ACTIVE to CameraMode.OVERVIEW) to CarMapButton.RECENTER,
            // The preview frames its routes and has nothing else to switch to.
            (GuidanceState.PREVIEW to CameraMode.IDLE) to CarMapButton.RECENTER_ROUTES,
            (GuidanceState.PREVIEW to CameraMode.OVERVIEW) to null,
            (GuidanceState.PREVIEW to CameraMode.FOLLOWING) to null,
            // Before a drive the camera reports idle also while it stays on the car: My location.
            (GuidanceState.IDLE to CameraMode.IDLE) to CarMapButton.RECENTER,
            (GuidanceState.IDLE to CameraMode.FOLLOWING) to CarMapButton.RECENTER,
            (GuidanceState.IDLE to CameraMode.OVERVIEW) to CarMapButton.RECENTER,
            (GuidanceState.ARRIVED to CameraMode.IDLE) to CarMapButton.RECENTER,
            (GuidanceState.ARRIVED to CameraMode.FOLLOWING) to CarMapButton.RECENTER,
            (GuidanceState.ARRIVED to CameraMode.OVERVIEW) to CarMapButton.RECENTER,
        )
        assertEquals(GuidanceState.entries.size * CameraMode.entries.size, expected.size)
        for ((states, button) in expected) {
            val (guidance, camera) = states
            assertEquals(button, CarHome.mapButtonOf(guidance, camera), "$guidance, $camera")
            assertEquals(CarHome(CarHome.actionOf(guidance), button), CarHome.of(guidance, camera))
        }
    }

    @Test
    fun `each button asks the camera for what it says`() {
        assertEquals(CameraMode.FOLLOWING, CarMapButton.RECENTER.target)
        assertEquals(CameraMode.OVERVIEW, CarMapButton.RECENTER_ROUTES.target)
        assertEquals(CameraMode.OVERVIEW, CarMapButton.OVERVIEW.target)
    }

    @Test
    fun `a zoom button steps by half a level`() {
        assertEquals(0.5, CarHome.ZOOM_STEP)
    }
}
