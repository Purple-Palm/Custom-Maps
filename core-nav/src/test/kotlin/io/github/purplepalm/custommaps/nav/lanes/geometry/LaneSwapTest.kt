package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LookAhead
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * The swap of the route line and the lit lane (the own lanes' design 5.3 and 8, FIX T5): a step on the camera's latched
 * state with hysteresis, never a half-drawn line. The camera is a model of the app's own: the lanes' close view goes in
 * from the open road at its highest zoom rate to the closest zoom that still shows 12 s of road at the peak speed
 * ([LookAhead.zoomFor] on the phone's screen of the camera tests), no closer than Large's 14 dp lanes; the junction view's
 * frame holds the car and the turn with 30 m past it, no closer than 28 dp. Latitude 0.
 */
class LaneSwapTest {

    private val tuning = CameraTuning.PHONE
    private val fps = 30.0

    /** A drawn freeway lane on the ground. */
    private val lane = LaneTopology.FREEWAY_LANE_METERS

    /** The zoom at latitude 0 where a drawn lane is [dp] wide. */
    private fun zoomOf(dp: Double) = log2(MapScale.metersPerDp(0.0, 0.0) * dp / lane)

    // The phone's screen as the camera tests have it: 2,400 px high, the car's row 459 px from the bottom, the card's
    // lane row at 838 px from the top, 3 px a dp.
    private val height = 2_400.0
    private val carRow = height - 459.0
    private val top = 838.0
    private val ratio = 3.0

    /** Large's freeway close view: 14 dp lanes of the camera's own width. */
    private val large = MapScale.zoomForLaneDp(tuning.freewayLaneDp, 0.0)

    /** The open road's zoom: Default's. */
    private val road = tuning.defaultMaxZoom

    /** Where the lanes' close view rests at a peak speed of [speed]: the 12 s floor, no closer than Large. */
    private fun rest(speed: Double): Double =
        min(large, LookAhead.zoomFor(speed * tuning.laneLookAheadSeconds, tuning.lanePitch, height, carRow, top, ratio, 0.0))

    /** The zoom frame by frame, from [from] toward each goal of [goals] (seconds, goal, latched) at the camera's highest rate. */
    private fun frames(from: Double, goals: List<Triple<Double, Double, Boolean>>): List<Pair<Double, Boolean>> {
        var zoom = from
        val step = tuning.maxZoomRate / fps
        val out = ArrayList<Pair<Double, Boolean>>()
        for ((seconds, goal, latched) in goals) {
            repeat((seconds * fps).toInt()) {
                zoom += max(-step, min(step, goal - zoom))
                out.add(zoom to latched)
            }
        }
        return out
    }

    /** The line's opacity frame by frame as the map writes it, and how often it flipped. */
    private fun run(frames: List<Pair<Double, Boolean>>): Pair<List<Double>, Int> {
        val swap = LaneSwap()
        val opacity = ArrayList<Double>()
        var flips = 0
        for ((zoom, latched) in frames) {
            // Once per progress update the latched state, at every camera change the zoom: here both every frame.
            if (swap.onLatched(latched)) flips++
            if (swap.onZoom(zoom, 0.0)) flips++
            opacity.add(swap.lineOpacity)
        }
        assertEquals(flips, swap.takeFlips())
        return opacity to flips
    }

    @Test
    fun `the swap's widths, each on both sides of its border`() {
        assertEquals(10.4, LaneSwap.SWAP_IN_DP)
        assertEquals(9.6, LaneSwap.SWAP_OUT_DP)
        val zIn = zoomOf(LaneSwap.SWAP_IN_DP)
        val zOut = zoomOf(LaneSwap.SWAP_OUT_DP)
        assertEquals(LaneSwap.SWAP_IN_DP, LaneSwap.laneDp(zIn, 0.0), 1e-9)
        // In at 10.4 dp, not just under it.
        val swap = LaneSwap()
        swap.onLatched(true)
        assertFalse(swap.onZoom(zIn - 1e-6, 0.0))
        assertFalse(swap.swapped)
        assertTrue(swap.onZoom(zIn, 0.0))
        assertTrue(swap.swapped)
        // Held down to 9.6 dp, out just under it.
        assertFalse(swap.onZoom(zOut, 0.0))
        assertTrue(swap.swapped)
        assertTrue(swap.onZoom(zOut - 1e-6, 0.0))
        assertFalse(swap.swapped)
        // The two zooms at latitude 0: 17.76 and 17.65.
        assertEquals(17.76, zIn, 0.005)
        assertEquals(17.65, zOut, 0.005)
    }

    @Test
    fun `a zoom resting between the two widths never makes the line blink`() {
        val zIn = zoomOf(LaneSwap.SWAP_IN_DP)
        val zOut = zoomOf(LaneSwap.SWAP_OUT_DP)
        for (start in listOf(false, true)) {
            val swap = LaneSwap()
            swap.onLatched(true)
            swap.onZoom(if (start) zIn else zOut - 0.1, 0.0)
            swap.takeFlips()
            var z = zOut + 0.001
            while (z < zIn - 0.001) {
                assertFalse(swap.onZoom(z, 0.0))
                assertFalse(swap.onZoom(zIn - (z - zOut), 0.0))
                z += 0.0005
            }
            assertEquals(start, swap.swapped)
            assertEquals(0, swap.takeFlips())
        }
    }

    @Test
    fun `the view letting go brings the line back at once, whatever the zoom`() {
        val swap = LaneSwap()
        swap.onLatched(true)
        swap.onZoom(18.5, 0.0)
        assertTrue(swap.swapped)
        assertEquals(0.0, swap.lineOpacity)
        assertTrue(swap.onLatched(false))
        assertFalse(swap.swapped)
        assertEquals(1.0, swap.lineOpacity)
        // Latched again at the same zoom: the lit lane again.
        assertTrue(swap.onLatched(true))
        assertTrue(swap.reset())
        assertFalse(swap.swapped)
    }

    @Test
    fun `on a freeway run-up at peaks of 20 to 40 m-s, one swap in and one out where the view rests past 10_4 dp, none else, and no half-drawn line`() {
        var speed = 20.0
        var swappedUpTo = 0.0
        while (speed <= 40.0 + 1e-9) {
            val rest = rest(speed)
            val frames = frames(road, listOf(Triple(tuning.runUpSeconds, rest, true), Triple(12.0, road, false)))
            val (opacity, flips) = run(frames)
            assertTrue(opacity.all { it == 0.0 || it == 1.0 }, "a half-drawn line at $speed m/s")
            val swaps = LaneSwap.laneDp(rest, 0.0) >= LaneSwap.SWAP_IN_DP
            assertEquals(if (swaps) 2 else 0, flips, "at $speed m/s, lanes ${LaneSwap.laneDp(rest, 0.0)} dp at rest")
            // Swapped from the frame the lane first reaches 10.4 dp to the frame the view lets go, and the line after.
            if (swaps) {
                swappedUpTo = speed
                val first = frames.indexOfFirst { LaneSwap.laneDp(it.first, 0.0) >= LaneSwap.SWAP_IN_DP }
                val letGo = frames.indexOfFirst { !it.second }
                assertTrue(opacity.subList(0, first).all { it == 1.0 })
                assertTrue(opacity.subList(first, letGo).all { it == 0.0 })
                assertTrue(opacity.subList(letGo, opacity.size).all { it == 1.0 })
            }
            speed += 0.5
        }
        // The swap comes up to about 33 m/s (75 mph) and never from 34 m/s (76 mph) on: there the line stays opaque.
        assertTrue(swappedUpTo in 33.0..33.5, "the last peak with a swap: $swappedUpTo m/s")
        for (peak in listOf(34.0, 35.0, 36.5, 40.0)) assertTrue(LaneSwap.laneDp(rest(peak), 0.0) < LaneSwap.SWAP_IN_DP, "$peak m/s")
    }

    @Test
    fun `in a junction view stopped in a queue 80 to 160 m before the turn, one swap at most and it holds through the stop`() {
        // The frame of the car and the turn, 30 m past it, between the card's row and the car's row, at most 28 dp lanes.
        val clearDp = (carRow - top) / ratio
        val closest = MapScale.zoomForLaneDp(tuning.junctionLaneDp, 0.0)
        fun frameZoom(toTurn: Double) = min(closest, log2(MapScale.metersPerDp(0.0, 0.0) * clearDp / (max(toTurn, 0.0) + 30.0)))
        val speed = 35.0 * 0.44704
        for (stop in listOf(160.0, 140.0, 120.0, 112.0, 108.0, 104.0, 98.0, 90.0, 80.0)) {
            val swap = LaneSwap()
            var flips = 0
            var zoom = frameZoom(180.0)
            var toTurn = 180.0
            var stopped = 0.0
            var swappedAtStop: Boolean? = null
            while (toTurn > 0.0) {
                zoom += max(-tuning.maxZoomRate / fps, min(tuning.maxZoomRate / fps, frameZoom(toTurn) - zoom))
                if (swap.onLatched(true)) flips++
                if (swap.onZoom(zoom, 0.0)) flips++
                assertTrue(swap.lineOpacity == 0.0 || swap.lineOpacity == 1.0)
                if (toTurn <= stop && stopped < 60.0) {
                    // In the queue for a minute: the state the stop began with holds.
                    if (swappedAtStop == null) swappedAtStop = swap.swapped
                    assertEquals(swappedAtStop, swap.swapped, "stopped at $stop m")
                    stopped += 1.0 / fps
                    continue
                }
                toTurn -= speed / fps
            }
            assertEquals(1, flips, "stopped at $stop m")
            assertTrue(swap.swapped)
        }
    }

    @Test
    fun `Mapbox tuned's close view and Default's zoom never swap, and from the swap the amber only grows`() {
        // Mapbox tuned's close view has lanes of about 7 to 9.2 dp.
        val swap = LaneSwap()
        swap.onLatched(true)
        var dp = 7.05
        while (dp <= 9.2) {
            assertFalse(swap.onZoom(zoomOf(dp), 0.0))
            dp += 0.05
        }
        assertFalse(swap.swapped)
        // Default's 16.35: the drawing small, under the line.
        assertFalse(swap.onZoom(tuning.defaultMaxZoom, 0.0))
        assertTrue(LaneSwap.laneDp(tuning.defaultMaxZoom, 0.0) < 5.0)
        // At the swap the amber is 9.8 dp, and wider at every closer zoom.
        val zIn = zoomOf(LaneSwap.SWAP_IN_DP)
        assertEquals(9.8, LaneSwap.amberDp(zIn, 0.0), 0.05)
        var z = zIn
        var last = LaneSwap.amberDp(z, 0.0)
        while (z < 20.0) {
            z += 0.05
            assertTrue(LaneSwap.amberDp(z, 0.0) > last)
            last = LaneSwap.amberDp(z, 0.0)
        }
        // 13.2 dp at 14 dp lanes and 26.3 dp at 28 dp.
        assertEquals(13.2, LaneSwap.amberDp(zoomOf(14.0), 0.0), 0.05)
        assertEquals(26.3, LaneSwap.amberDp(zoomOf(28.0), 0.0), 0.05)
    }
}
