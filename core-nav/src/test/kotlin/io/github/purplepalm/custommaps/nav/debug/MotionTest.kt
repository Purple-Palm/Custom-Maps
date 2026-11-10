package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.geo.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionTest {

    private val ms = 1_000_000L

    @Test
    fun `cadence counts ticks and the longest pause, including one still going on`() {
        val cadence = Cadence()
        cadence.tick(0)
        cadence.tick(16 * ms)
        cadence.tick(32 * ms)

        assertEquals(Cadence.Window(count = 3, longestGapMillis = 968), cadence.take(1_000 * ms))

        cadence.tick(1_500 * ms)
        assertEquals(Cadence.Window(count = 1, longestGapMillis = 1_468), cadence.take(2_000 * ms))
    }

    @Test
    fun `cadence has no gap before the first tick`() {
        assertEquals(Cadence.Window(count = 0, longestGapMillis = null), Cadence().take(1_000 * ms))
    }

    @Test
    fun `a reset cadence starts over, with no gap back to the last tick before it`() {
        val cadence = Cadence()
        cadence.tick(0)
        // A longer gap than any after the reset, so one that the reset kept would show.
        cadence.tick(500 * ms)

        cadence.reset()
        // The next guidance, ten seconds later.
        cadence.tick(10_000 * ms)

        assertEquals(Cadence.Window(count = 1, longestGapMillis = 16), cadence.take(10_016 * ms))
        cadence.reset()
        assertEquals(Cadence.Window(count = 0, longestGapMillis = null), cadence.take(20_000 * ms))
    }

    @Test
    fun `camera steps keep the largest change between two frames`() {
        val steps = CameraSteps()
        steps.onCamera(0.0, 0.0, 15.0, 359.0, 45.0)
        steps.onCamera(0.0, 0.0, 15.5, 1.0, 45.0)
        // About 10 m north along the meridian.
        steps.onCamera(0.00009, 0.0, 15.5, 1.0, 40.0)

        val taken = steps.take()

        assertEquals(Motion.round(Math.toRadians(0.00009) * Geo.EARTH_RADIUS_METERS, 2), taken["m"])
        assertEquals(10.01, taken["m"] as Double, 0.005)
        assertEquals(0.5, taken["zoom"])
        assertEquals(2.0, taken["bearing"])
        assertEquals(5.0, taken["pitch"])
        assertEquals(mapOf("m" to 0.0, "zoom" to 0.0, "bearing" to 0.0, "pitch" to 0.0), steps.take())
    }

    @Test
    fun `camera steps carry on from the last frame of the previous window`() {
        val steps = CameraSteps()
        steps.onCamera(0.0, 0.0, 15.0, 0.0, 45.0)
        steps.take()

        steps.onCamera(0.0, 0.0, 16.0, 0.0, 45.0)

        assertEquals(1.0, steps.take()["zoom"])
    }

    @Test
    fun `reset camera steps compare the next frame with nothing before it`() {
        val steps = CameraSteps()
        steps.onCamera(0.0, 0.0, 15.0, 0.0, 45.0)
        steps.onCamera(0.0, 0.0, 15.5, 0.0, 45.0)

        steps.reset()
        // The next guidance starts somewhere else, at another zoom, bearing and pitch.
        steps.onCamera(0.001, 0.0, 16.0, 90.0, 60.0)

        assertEquals(mapOf("m" to 0.0, "zoom" to 0.0, "bearing" to 0.0, "pitch" to 0.0), steps.take())
        steps.onCamera(0.001, 0.0, 16.25, 90.0, 60.0)
        assertEquals(0.25, steps.take()["zoom"])
    }

    @Test
    fun `bearing difference goes the short way round`() {
        assertEquals(20.0, Motion.bearingDifference(350.0, 10.0))
        assertEquals(20.0, Motion.bearingDifference(10.0, 350.0))
        assertEquals(180.0, Motion.bearingDifference(0.0, 180.0))
        assertEquals(0.0, Motion.bearingDifference(720.0, 0.0))
    }

    @Test
    fun `round keeps NaN instead of turning it into zero`() {
        assertEquals(15.62, Motion.round(15.6249, 2))
        assertEquals(-0.000123, Motion.round(-0.0001234, 6))
        assertTrue(Motion.round(Double.NaN, 2).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Motion.round(Double.POSITIVE_INFINITY, 1))
    }
}
