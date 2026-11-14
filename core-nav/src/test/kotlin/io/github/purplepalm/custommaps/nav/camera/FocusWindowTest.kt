package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.model.LaneZoom
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Where the view is flat over a junction and its frame begins, and what the screen can hold. */
class FocusWindowTest {
    private val tuning = CameraTuning.PHONE

    @Test
    fun `a plain junction is framed 12 s ahead between 240 and 320 m, a complex one 15 s ahead between 280 and 400 m`() {
        // 0 to 75 mph; a car that stands or creeps counts as 3 m/s.
        val speeds = listOf(0.0, 3.0, 11.2, 15.6, 20.1, 24.6, 29.1, 33.5)
        val turn = listOf(240.0, 240.0, 240.0, 240.0, 241.2, 295.2, 320.0, 320.0)
        val complex = listOf(280.0, 280.0, 280.0, 280.0, 301.5, 369.0, 400.0, 400.0)
        for (i in speeds.indices) {
            val v = speeds[i]
            assertEquals(turn[i], FocusWindow.framingMeters(FocusKind.TURN, v, Double.POSITIVE_INFINITY, tuning), 1e-9, "turn at $v m/s")
            assertEquals(complex[i], FocusWindow.framingMeters(FocusKind.COMPLEX, v, Double.POSITIVE_INFINITY, tuning), 1e-9, "complex at $v m/s")
        }
    }

    @Test
    fun `the screen's cap holds the frame back, never nearer than the navigation SDK's own distance`() {
        // 0.15 mi wanted, 214 m fit: the frame begins at 214 m.
        assertEquals(214.0, FocusWindow.framingMeters(FocusKind.TURN, 13.4, 214.0, tuning), 1e-9)
        assertEquals(214.0, FocusWindow.framingMeters(FocusKind.COMPLEX, 25.0, 214.0, tuning), 1e-9)
        // A cap under 180 m gives the SDK's 180 m: never later than today.
        assertEquals(180.0, FocusWindow.framingMeters(FocusKind.TURN, 13.4, 120.0, tuning), 1e-9)
        // A cap above what is wanted changes nothing.
        assertEquals(240.0, FocusWindow.framingMeters(FocusKind.TURN, 13.4, 900.0, tuning), 1e-9)
    }

    @Test
    fun `a map not laid out yet frames at 180 m, no cap at all frames what is wanted`() {
        for (cap in listOf(Double.NaN, 0.0, -50.0, Double.NEGATIVE_INFINITY)) {
            for (kind in listOf(FocusKind.TURN, FocusKind.COMPLEX)) {
                assertEquals(180.0, FocusWindow.framingMeters(kind, 20.1, cap, tuning), 1e-9, "$kind cap $cap")
            }
        }
        assertEquals(241.2, FocusWindow.framingMeters(FocusKind.TURN, 20.1, Double.POSITIVE_INFINITY, tuning), 1e-9)
        assertEquals(400.0, FocusWindow.framingMeters(FocusKind.COMPLEX, 40.0, Double.POSITIVE_INFINITY, tuning), 1e-9)
        // A step the view stays up for has no framing of its own.
        assertEquals(180.0, FocusWindow.framingMeters(FocusKind.NONE, 20.1, Double.POSITIVE_INFINITY, tuning), 1e-9)
    }

    @Test
    fun `the tilt starts the descent's driving before the framing distance`() {
        val random = Random(18)
        repeat(500) {
            val kind = if (random.nextBoolean()) FocusKind.TURN else FocusKind.COMPLEX
            val v = random.nextDouble() * 45.0
            val cap = when (random.nextInt(4)) {
                0 -> Double.POSITIVE_INFINITY
                1 -> Double.NaN
                else -> random.nextDouble() * 500.0 - 50.0
            }
            val framing = FocusWindow.framingMeters(kind, v, cap, tuning)
            assertTrue(framing >= tuning.maneuverFramingMeters, "$kind $v $cap: $framing")
            assertEquals(framing + maxOf(v, 3.0) * tuning.descentSeconds, FocusWindow.windowMeters(kind, v, cap, tuning), 1e-9)
            // An uncapped window is never shorter than a capped one.
            assertTrue(FocusWindow.windowMeters(kind, v, Double.POSITIVE_INFINITY, tuning) >= FocusWindow.windowMeters(kind, v, cap, tuning) - 1e-9)
        }
        // With the navigation SDK's 180 m: 180 m and 3.5 s at 13.4 m/s.
        assertEquals(180.0 + 13.4 * 3.5, FocusWindow.windowMeters(FocusKind.TURN, 13.4, Double.NaN, tuning), 1e-9)
        // 30 mph at a turn: flat at 240 m, the tilt starts at about 287 m.
        assertEquals(240.0 + 13.4112 * 3.5, FocusWindow.windowMeters(FocusKind.TURN, 13.4112, Double.POSITIVE_INFINITY, tuning), 1e-9)
    }

    @Test
    fun `the bridge reaches 6,5 s of driving past the window`() {
        assertEquals(13.4 * 6.5, FocusWindow.bridgeMeters(13.4, tuning), 1e-9)
        assertEquals(3.0 * 6.5, FocusWindow.bridgeMeters(0.0, tuning), 1e-9)
    }

    @Test
    fun `while the lanes' close view holds with Large the frame fits at its zoom`() {
        val lanes = MapScale.zoomForLaneDp(14.0, 40.0)
        assertEquals(lanes, FocusWindow.capZoom(LaneZoom.LARGE, 1.0, lanes, tuning), 1e-9)
        // Coming in or let go, with Default, or a lanes' zoom under the road's: the road's zoom.
        assertEquals(tuning.defaultMaxZoom, FocusWindow.capZoom(LaneZoom.LARGE, 0.0, lanes, tuning), 1e-9)
        assertEquals(tuning.defaultMaxZoom, FocusWindow.capZoom(LaneZoom.LARGE, 0.5, lanes, tuning), 1e-9)
        assertEquals(tuning.defaultMaxZoom, FocusWindow.capZoom(LaneZoom.DEFAULT, 1.0, lanes, tuning), 1e-9)
        assertEquals(tuning.defaultMaxZoom, FocusWindow.capZoom(LaneZoom.LARGE, 1.0, 15.0, tuning), 1e-9)
        // At the lanes' zoom a 502 dp frame holds about 133 m: any junction is framed from 180 m.
        val cap = FocusWindow.capMeters(502.0, 40.0, 30.0, 90.0, 0.0, tuning, lanes)
        assertEquals(132.7, cap, 0.1)
        for (v in listOf(11.2, 20.1, 27.0, 33.5)) {
            assertEquals(180.0, FocusWindow.framingMeters(FocusKind.TURN, v, cap, tuning), 1e-9, "$v m/s")
            assertEquals(180.0, FocusWindow.framingMeters(FocusKind.COMPLEX, v, cap, tuning), 1e-9, "$v m/s")
        }
        // The road's zoom is the default one.
        assertEquals(FocusWindow.capMeters(502.0, 40.0, 30.0, 90.0, 0.0, tuning),
            FocusWindow.capMeters(502.0, 40.0, 30.0, 90.0, 0.0, tuning, tuning.defaultMaxZoom), 1e-9)
    }

    @Test
    fun `a taller frame holds more road, and the road past a right angle lies across it`() {
        // 502 dp of frame at 0, 40 and 47 degrees, the road past the junction straight on, Large's 30 m.
        assertEquals(440.4, FocusWindow.capMeters(502.0, 0.0, 30.0, 0.0, 0.0, tuning), 0.1)
        assertEquals(330.3, FocusWindow.capMeters(502.0, 40.0, 30.0, 0.0, 0.0, tuning), 0.1)
        assertEquals(290.8, FocusWindow.capMeters(502.0, 47.0, 30.0, 0.0, 0.0, tuning), 0.1)
        // 340 dp at 40 degrees: 214 m straight on, 244 m at a right angle and at a U-turn, whose road
        // after the junction does not lie along the approach.
        assertEquals(214.1, FocusWindow.capMeters(340.0, 40.0, 30.0, 0.0, 0.0, tuning), 0.1)
        assertEquals(244.1, FocusWindow.capMeters(340.0, 40.0, 30.0, 90.0, 0.0, tuning), 0.1)
        assertEquals(244.1, FocusWindow.capMeters(340.0, 40.0, 30.0, 180.0, 0.0, tuning), 0.1)
        // An angle the route does not tell counts as straight on.
        assertEquals(FocusWindow.capMeters(340.0, 40.0, 30.0, 0.0, 0.0, tuning), FocusWindow.capMeters(340.0, 40.0, 30.0, null, 0.0, tuning), 1e-9)
        // Halfway: a slight turn of 45 degrees keeps about 21 m of the 30 along the approach.
        assertEquals(244.1 - 30.0 * Math.cos(Math.toRadians(45.0)), FocusWindow.capMeters(340.0, 40.0, 30.0, 45.0, 0.0, tuning), 0.1)
        // The Default lane zoom keeps 100 m, and the steps framed with the junction count in full.
        assertEquals(144.1, FocusWindow.capMeters(340.0, 40.0, 100.0, 0.0, 0.0, tuning), 0.1)
        assertEquals(194.1, FocusWindow.capMeters(340.0, 40.0, 30.0, 90.0, 50.0, tuning), 0.1)
        // On that phone a right-angle turn is framed from about 240 m at any speed (the cap's 244 m at
        // speed), a turn straight on from 214 m.
        val rightAngle = FocusWindow.capMeters(340.0, 40.0, 30.0, 90.0, 0.0, tuning)
        val straight = FocusWindow.capMeters(340.0, 40.0, 30.0, 0.0, 0.0, tuning)
        for ((v, framed) in listOf(11.2 to 240.0, 20.1 to 241.2, 29.1 to rightAngle)) {
            assertEquals(framed, FocusWindow.framingMeters(FocusKind.TURN, v, rightAngle, tuning), 1e-9, "$v m/s")
            assertEquals(straight, FocusWindow.framingMeters(FocusKind.TURN, v, straight, tuning), 1e-9, "$v m/s")
        }
    }
}
