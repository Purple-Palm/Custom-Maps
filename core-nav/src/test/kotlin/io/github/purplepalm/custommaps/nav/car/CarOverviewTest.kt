package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The overview a new surface of the car map shows again. Each test reports the drive's states in
 * the order the engine goes through them, as the car map's collector of the drive's state does.
 */
class CarOverviewTest {

    /** A drive under way, its preview before it. */
    private fun driving(): CarOverview = CarOverview().apply {
        onGuidance(GuidanceState.IDLE)
        onGuidance(GuidanceState.PREVIEW)
        onGuidance(GuidanceState.ACTIVE)
    }

    @Test
    fun `nothing is brought back before the driver asks`() {
        assertFalse(CarOverview().restores(GuidanceState.ACTIVE))
        assertFalse(driving().restores(GuidanceState.ACTIVE))
    }

    @Test
    fun `the overview asked for during the drive comes back on every new surface of it`() {
        val overview = driving()
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        assertTrue(overview.restores(GuidanceState.ACTIVE))
        // A second new surface, as after a call reshaped the car's screen and once more after it.
        assertTrue(overview.restores(GuidanceState.ACTIVE))
    }

    /** The route preview, where the driver moved the map over the routes and framed them again with Recenter. */
    private fun recenteredPreview(): CarOverview = CarOverview().apply {
        onGuidance(GuidanceState.IDLE)
        onGuidance(GuidanceState.PREVIEW)
        onDriverMoved()
        onButton(CameraMode.OVERVIEW, GuidanceState.PREVIEW)
    }

    @Test
    fun `the preview's Recenter is not kept into the drive`() {
        val overview = recenteredPreview()
        // Start on the phone: the car map follows the car, and so does the map on the next surface.
        overview.onGuidance(GuidanceState.ACTIVE)
        assertFalse(overview.restores(GuidanceState.ACTIVE))
    }

    @Test
    fun `a new surface in the preview asks for nothing`() {
        // The scene frames the routes on every surface by itself.
        assertFalse(recenteredPreview().restores(GuidanceState.PREVIEW))
    }

    @Test
    fun `Recenter leaves the overview`() {
        val overview = driving()
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        overview.onButton(CameraMode.FOLLOWING, GuidanceState.ACTIVE)
        assertFalse(overview.restores(GuidanceState.ACTIVE))
        // Another ask of the camera than these two keeps nothing either.
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        overview.onButton(CameraMode.IDLE, GuidanceState.ACTIVE)
        assertFalse(overview.restores(GuidanceState.ACTIVE))
    }

    @Test
    fun `the driver moving the map leaves the overview until it is asked for again`() {
        val overview = driving()
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        overview.onDriverMoved()
        assertFalse(overview.restores(GuidanceState.ACTIVE))
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        assertTrue(overview.restores(GuidanceState.ACTIVE))
    }

    @Test
    fun `a drive that ends in the overview leaves none for the next drive`() {
        for (end in listOf(GuidanceState.IDLE, GuidanceState.ARRIVED)) {
            val overview = driving()
            overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
            overview.onGuidance(end)
            assertFalse(overview.restores(end), "$end")
            if (end == GuidanceState.ARRIVED) overview.onGuidance(GuidanceState.IDLE)
            overview.onGuidance(GuidanceState.PREVIEW)
            overview.onGuidance(GuidanceState.ACTIVE)
            assertFalse(overview.restores(GuidanceState.ACTIVE), "$end")
        }
    }

    @Test
    fun `an end seen only by the next surface still forgets the overview`() {
        // The drive ended while another app had the car's screen, and the next surface comes before
        // the end was reported: the state it is given is enough. The next report the overview gets
        // is of a new drive already.
        val overview = driving()
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        assertFalse(overview.restores(GuidanceState.IDLE))
        overview.onGuidance(GuidanceState.ACTIVE)
        assertFalse(overview.restores(GuidanceState.ACTIVE))
    }

    @Test
    fun `a start the navigator refused forgets the overview`() {
        // The routes are refused: the drive goes back to the preview, and the driver starts again.
        val overview = driving()
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        overview.onGuidance(GuidanceState.PREVIEW)
        overview.onGuidance(GuidanceState.ACTIVE)
        assertFalse(overview.restores(GuidanceState.ACTIVE))
    }

    @Test
    fun `the overview stays through the drive's own state again`() {
        // The state the button saw, reported once more by the collector after the press.
        val overview = driving()
        overview.onButton(CameraMode.OVERVIEW, GuidanceState.ACTIVE)
        overview.onGuidance(GuidanceState.ACTIVE)
        assertTrue(overview.restores(GuidanceState.ACTIVE))
    }
}
