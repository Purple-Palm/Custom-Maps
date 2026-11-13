package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car map's frame rate cap, against a model of the map library's pacing: it draws on a call of
 * its render thread when `calls x cap / 60` passes the next whole number, and starts its count over
 * once it has been called 60 times, at the first frame it draws after that.
 */
class CarFrameRateTest {

    /** The frames the map draws in a second at [displayHz] under [maxFps], paced against [believedHz]. */
    private fun drawnInASecond(displayHz: Int, maxFps: Int, believedHz: Int = 60): Int {
        val ratio = (maxFps.toDouble() / believedHz).coerceIn(0.0, 1.0)
        var calls = 0
        var drawnIndex = 0
        var drawn = 0
        repeat(displayHz) {
            calls += 1
            val index = (calls * ratio).toInt()
            if (index > drawnIndex) {
                drawnIndex = index
                drawn += 1
                if (calls >= believedHz) {
                    calls = 0
                    drawnIndex = 0
                }
            }
        }
        return drawn
    }

    @Test
    fun `the old cap of 30 drew 60 frames a second on a display at 120 Hz`() {
        assertEquals(60, drawnInASecond(displayHz = 120, maxFps = 30))
    }

    @Test
    fun `a real 30 frames a second at 120, 90 and 60 Hz`() {
        assertEquals(15, CarFrameRate.maxFps(120.0))
        assertEquals(20, CarFrameRate.maxFps(90.0))
        assertEquals(30, CarFrameRate.maxFps(60.0))
        for (hz in listOf(120, 90, 60)) {
            assertEquals(30, drawnInASecond(displayHz = hz, maxFps = CarFrameRate.maxFps(hz.toDouble())), "at $hz Hz")
        }
    }

    @Test
    fun `on the timer the rate is taken for 60, which caps at 30`() {
        val hz = CarFrameRate.displayHz(CarFrameClock.Source.TIMER, vsyncHz = 120.0)
        assertEquals(60.0, hz)
        assertEquals(30, CarFrameRate.maxFps(hz!!))
    }

    @Test
    fun `on the display the measured rate counts, and there is none before it is measured`() {
        assertEquals(90.0, CarFrameRate.displayHz(CarFrameClock.Source.VSYNC, vsyncHz = 90.0))
        assertNull(CarFrameRate.displayHz(CarFrameClock.Source.VSYNC, vsyncHz = null))
    }

    @Test
    fun `the cap stays within 1 and 60, and a rate that is no rate keeps 30`() {
        assertEquals(60, CarFrameRate.maxFps(20.0))
        assertEquals(1, CarFrameRate.maxFps(10_000.0))
        assertEquals(30, CarFrameRate.maxFps(0.0))
        assertEquals(30, CarFrameRate.maxFps(Double.NaN))
        assertEquals(30, CarFrameRate.maxFps(Double.POSITIVE_INFINITY))
        // Half way between two caps it rounds up: 30 x 60 / 144 = 12.5.
        assertEquals(13, CarFrameRate.maxFps(144.0))
    }

    @Test
    fun `the cap is set again only when the rate moves by more than a tenth`() {
        assertTrue(CarFrameRate.changed(appliedHz = null, displayHz = 60.0), "the first measured rate")
        assertFalse(CarFrameRate.changed(appliedHz = 120.0, displayHz = 125.0))
        assertFalse(CarFrameRate.changed(appliedHz = 120.0, displayHz = 108.5))
        assertTrue(CarFrameRate.changed(appliedHz = 120.0, displayHz = 132.5))
        assertTrue(CarFrameRate.changed(appliedHz = 120.0, displayHz = 107.5))
        assertTrue(CarFrameRate.changed(appliedHz = 120.0, displayHz = 60.0), "off the display onto the timer")
    }

    @Test
    fun `back-to-back display frames give the display's rate`() {
        val rate = VsyncRate()
        frames(rate, period = HZ_120, count = 17)
        assertEquals(120.0, rate.hz()!!, 0.01)
    }

    @Test
    fun `frames the main thread misses do not lower the rate`() {
        val rate = VsyncRate()
        var time = 0L
        rate.onFrame(time, backToBack = false)
        // Under load more than half of the frames come a display frame late: nine of sixteen.
        repeat(16) { index ->
            time += if (index % 2 == 0 || index == 15) 2 * HZ_120 else HZ_120
            rate.onFrame(time, backToBack = true)
        }
        assertEquals(120.0, rate.hz()!!, 0.01)
    }

    @Test
    fun `a few intervals the display's timing shortens do not raise the rate`() {
        val rate = VsyncRate()
        var time = 0L
        rate.onFrame(time, backToBack = false)
        // Two of sixteen frames come 7 ms after the one before, as a frame time set to the display's
        // grid after a late frame can.
        repeat(16) { index ->
            time += if (index == 4 || index == 11) 7_000_000L else HZ_120
            rate.onFrame(time, backToBack = true)
        }
        assertEquals(120.0, rate.hz()!!, 0.01)
    }

    @Test
    fun `frames that were not asked for one right after the other say nothing`() {
        val rate = VsyncRate()
        var time = 0L
        repeat(20) {
            time += 3 * HZ_60
            rate.onFrame(time, backToBack = false)
        }
        assertNull(rate.hz())
    }

    @Test
    fun `there is a rate only after eight intervals`() {
        val rate = VsyncRate()
        frames(rate, period = HZ_60, count = 8)
        assertNull(rate.hz(), "seven intervals")
        frames(rate, period = HZ_60, count = 1, from = 8 * HZ_60)
        assertEquals(60.0, rate.hz()!!, 0.01)
    }

    @Test
    fun `a display that changes its rate is followed once its new frames fill the window`() {
        val rate = VsyncRate()
        frames(rate, period = HZ_120, count = 17)
        frames(rate, period = HZ_60, count = 16, from = 16 * HZ_120 + HZ_60)
        assertEquals(60.0, rate.hz()!!, 0.01)
    }

    @Test
    fun `a new surface starts its measure over`() {
        val rate = VsyncRate()
        frames(rate, period = HZ_90, count = 17)
        assertEquals(90.0, rate.hz()!!, 0.01)
        rate.reset()
        assertNull(rate.hz())
    }

    /** [count] back-to-back display frames [period] apart from [from], the first one counted as the start. */
    private fun frames(rate: VsyncRate, period: Long, count: Int, from: Long = 0L) {
        for (index in 0 until count) rate.onFrame(from + index * period, backToBack = index > 0 || from > 0L)
    }

    private companion object {
        const val HZ_60 = 16_666_667L
        const val HZ_90 = 11_111_111L
        const val HZ_120 = 8_333_333L
    }
}
