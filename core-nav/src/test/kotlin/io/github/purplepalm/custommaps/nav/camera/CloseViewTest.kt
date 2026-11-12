package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloseViewTest {
    private val tuning = CameraTuning.PHONE
    private val excluded = listOf("continue", "merge", "on ramp", "off ramp", "fork")

    @Test
    fun `with both focuses at 0 and still there is no close view`() {
        for (large in listOf(false, true)) {
            assertFalse(CloseView.smoothFollow(0.0, false, 0.0, false, large), "large $large")
        }
    }

    @Test
    fun `the maneuver focus counts with either lane zoom, held or moving`() {
        for (large in listOf(false, true)) {
            assertTrue(CloseView.smoothFollow(1.0, false, 0.0, false, large), "down, large $large")
            // On the way back up the goal is 0 and the view still moves.
            assertTrue(CloseView.smoothFollow(0.0, true, 0.0, false, large), "coming back, large $large")
        }
    }

    @Test
    fun `the lane focus counts with Large only`() {
        assertTrue(CloseView.smoothFollow(0.0, false, 1.0, false, large = true))
        assertTrue(CloseView.smoothFollow(0.0, false, 0.0, true, large = true))
        assertFalse(CloseView.smoothFollow(0.0, false, 1.0, false, large = false))
        assertFalse(CloseView.smoothFollow(0.0, false, 0.0, true, large = false))
    }

    @Test
    fun `Mapbox tuned shows one only with Large, at a maneuver frame or in a lane window`() {
        assertTrue(CloseView.mapboxTuned(framesManeuver = true, laneWindow = false, large = true))
        assertTrue(CloseView.mapboxTuned(framesManeuver = false, laneWindow = true, large = true))
        assertFalse(CloseView.mapboxTuned(framesManeuver = false, laneWindow = false, large = true))
        for (frame in listOf(false, true)) {
            for (window in listOf(false, true)) {
                assertFalse(CloseView.mapboxTuned(frame, window, large = false), "Default, frame $frame, window $window")
            }
        }
    }

    /** Smooth follow's answer from the maneuver focus alone, as the camera reads it after a progress update. */
    private fun maneuverOnly(focus: ManeuverFocus, now: Double, large: Boolean) =
        CloseView.smoothFollow(focus.goal, focus.isAnimating(now), 0.0, false, large)

    @Test
    fun `through a turn the close view lasts from the way down to the end of the way back up`() {
        for (large in listOf(false, true)) {
            val focus = ManeuverFocus(tuning)
            // 2 km from a turn at 15 m/s: the road view.
            focus.onProgress(0.0, "r/0/0", 2_000.0, 0.0, "turn", excluded, 15.0)
            assertFalse(maneuverOnly(focus, 0.0, large))
            // Inside the window (180 m + 3.5 s at 15 m/s): the view goes down.
            focus.onProgress(100.0, "r/0/0", 200.0, 1_800.0, "turn", excluded, 15.0)
            assertTrue(maneuverOnly(focus, 100.0, large))
            // Past the turn, held into the next step.
            focus.onProgress(115.0, "r/0/1", 900.0, 5.0, "turn", excluded, 15.0)
            assertTrue(maneuverOnly(focus, 115.0, large))
            // The hold is over: on the way back up, which takes the ascent's 3 s.
            focus.onProgress(118.0, "r/0/1", 855.0, 50.0, "turn", excluded, 15.0)
            assertEquals(0.0, focus.goal)
            assertTrue(maneuverOnly(focus, 118.0, large))
            assertTrue(maneuverOnly(focus, 120.9, large))
            // Back up: the next progress update finds no close view.
            assertFalse(maneuverOnly(focus, 121.5, large), "large $large")
        }
    }

    @Test
    fun `a lane choice far from any turn makes a close view with Large only`() {
        val focus = LaneFocus(tuning)
        val choice = { metersAhead: Double -> listOf(LaneChoice("r/0/0/7", metersAhead, 5, 3, 0, onFreeway = true, atManeuver = false)) }
        fun closeView(now: Double, large: Boolean) =
            CloseView.smoothFollow(0.0, false, focus.goal, focus.isAnimating(now), large)

        // At 30 m/s the window is 750 m: 1.5 km ahead the view stays on the road.
        focus.onProgress(0.0, 0.0, choice(1_500.0), 30.0)
        assertFalse(closeView(0.0, large = true))
        // 600 m ahead the view goes in for it.
        focus.onProgress(30.0, 900.0, choice(600.0), 30.0)
        assertTrue(closeView(30.0, large = true))
        assertFalse(closeView(30.0, large = false))
        // Just past it the view is held.
        focus.onProgress(50.0, 1_510.0, emptyList(), 30.0)
        assertTrue(closeView(50.0, large = true))
        // The hold is over: on the way out, which takes 6 s.
        focus.onProgress(53.0, 1_600.0, emptyList(), 30.0)
        assertEquals(0.0, focus.goal)
        assertTrue(closeView(53.0, large = true))
        assertTrue(closeView(58.9, large = true))
        assertFalse(closeView(59.5, large = true))
        assertFalse(closeView(53.0, large = false))
    }
}
