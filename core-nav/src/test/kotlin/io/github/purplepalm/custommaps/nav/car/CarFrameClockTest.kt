package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.car.CarFrameClock.Companion.NONE
import io.github.purplepalm.custommaps.nav.car.CarFrameClock.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's frame clock against a model of its Android part: a display that sends a frame at every
 * step of its rate while the callback is posted, or none at all, and one timer that runs when the
 * clock says. The one who asks for frames asks again after each tick, as a moving camera does.
 */
class CarFrameClockTest {

    private class Rig(var wantsFrames: Boolean = true) {
        val clock = CarFrameClock()
        var now = 0L

        /** The display's frame period in nanoseconds; null while it sends no frames. */
        var display: Long? = HZ_60

        /** Each tick: where it came from and the time it was given. */
        val ticks = mutableListOf<Pair<Source, Long>>()

        fun start() {
            clock.start(now)
            clock.setWanted(wantsFrames, now)
        }

        /** Runs the display, the timer and the clock until [until]. */
        fun runTo(until: Long) {
            while (true) {
                val period = display
                val vsyncAt = if (period != null && clock.vsyncWanted) (now / period + 1) * period else Long.MAX_VALUE
                val timerAt = clock.timerAtNanos.takeIf { it != NONE }?.coerceAtLeast(now) ?: Long.MAX_VALUE
                val next = minOf(vsyncAt, timerAt)
                if (next > until) {
                    now = until
                    return
                }
                now = next
                val tick = if (vsyncAt <= timerAt) clock.onVsync(frameTimeNanos = vsyncAt, now = now) else clock.onTimer(now)
                if (tick != NONE) ticks += clock.source to tick
                clock.setWanted(wantsFrames, now)
            }
        }

        fun ticksFrom(source: Source) = ticks.count { it.first == source }
    }

    @Test
    fun `on a display at 60 Hz every frame ticks`() {
        val rig = Rig().apply { start(); runTo(SECOND) }
        assertEquals(59, rig.ticks.size)
        assertEquals(59, rig.ticksFrom(Source.VSYNC))
        assertEquals(59, rig.clock.vsyncTicks)
        assertEquals(0, rig.clock.timerTicks)
    }

    @Test
    fun `on a display at 120 Hz every second frame ticks, about 60 a second`() {
        val rig = Rig().apply { display = HZ_120; start(); runTo(SECOND) }
        assertEquals(60, rig.ticks.size, "ticks in a second at 120 Hz")
        assertGapsAtLeast(rig, MIN_GAP)
    }

    @Test
    fun `on a display at 90 Hz every second frame ticks`() {
        val rig = Rig().apply { display = HZ_90; start(); runTo(SECOND) }
        assertEquals(45, rig.ticks.size)
    }

    @Test
    fun `a vsync tick passes the frame's own time and a timer tick the time it ran`() {
        val rig = Rig().apply { start(); runTo(HZ_60) }
        assertEquals(Source.VSYNC to HZ_60, rig.ticks.single())
        rig.display = null
        rig.runTo(HZ_60 + 60 * MS)
        // 50 ms after the last frame the timer runs, and ticks with its own time.
        assertEquals(Source.TIMER to HZ_60 + 50 * MS, rig.ticks[1])
    }

    @Test
    fun `when the display's frames stop the timer takes over 50 ms after the last one and ticks every 16 ms`() {
        val rig = Rig().apply { start(); runTo(SECOND) }
        val lastFrame = rig.ticks.last().second
        rig.display = null
        rig.runTo(3 * SECOND)
        assertEquals(Source.TIMER, rig.clock.source)
        assertEquals(1, rig.clock.switches)
        assertEquals(50L, rig.clock.switchQuietMillis)
        val timer = rig.ticks.filter { it.first == Source.TIMER }.map { it.second }
        assertEquals(lastFrame + 50 * MS, timer.first())
        assertTrue(timer.zipWithNext { a, b -> b - a }.all { it == 16 * MS }, "the timer's ticks are 16 ms apart")
        // From 50 ms after the last frame to the end, one tick every 16 ms.
        assertEquals(((3 * SECOND - (lastFrame + 50 * MS)) / (16 * MS) + 1).toInt(), timer.size)
    }

    @Test
    fun `when the display's frames come back the timer stops at once`() {
        val rig = Rig().apply { start(); runTo(SECOND) }
        val lastFrame = rig.ticks.last().second
        rig.display = null
        rig.runTo(3 * SECOND)
        val timerTicks = rig.ticksFrom(Source.TIMER)
        rig.display = HZ_60
        rig.runTo(4 * SECOND)
        assertEquals(Source.VSYNC, rig.clock.source)
        assertEquals(2, rig.clock.switches)
        // Quiet from the last frame to the first one back, and the ticks the timer gave meanwhile.
        val firstBack = (3 * SECOND / HZ_60 + 1) * HZ_60
        assertEquals((firstBack - lastFrame) / MS, rig.clock.switchQuietMillis)
        assertEquals(timerTicks, rig.clock.switchTimerTicks)
        assertEquals(timerTicks, rig.ticksFrom(Source.TIMER), "no timer tick once the display is back")
        assertTrue(rig.ticks.last().first == Source.VSYNC)
        assertGapsAtLeast(rig, MIN_GAP)
    }

    @Test
    fun `frames that stop and resume at 120 Hz never give two ticks within 15 ms`() {
        val rig = Rig().apply { display = HZ_120; start() }
        for (step in 1..6) {
            rig.display = if (step % 2 == 1) HZ_120 else null
            rig.runTo(step * SECOND)
        }
        assertGapsAtLeast(rig, MIN_GAP)
        assertEquals(5, rig.clock.switches)
        // About 60 a second either way.
        assertTrue(rig.ticks.size in 6 * 58..6 * 63, "${rig.ticks.size} ticks in 6 s")
    }

    @Test
    fun `nothing wanted, nothing runs`() {
        val rig = Rig(wantsFrames = false).apply { start(); display = null; runTo(10 * SECOND) }
        assertTrue(rig.ticks.isEmpty())
        assertFalse(rig.clock.vsyncWanted)
        assertEquals(NONE, rig.clock.timerAtNanos)
        assertEquals(0, rig.clock.switches)
    }

    @Test
    fun `the quiet counts from when frames were wanted, not from the last frame long before`() {
        val rig = Rig(wantsFrames = false).apply { start(); runTo(10 * SECOND) }
        rig.display = null
        rig.wantsFrames = true
        rig.clock.setWanted(true, rig.now)
        rig.runTo(10 * SECOND + 49 * MS)
        assertTrue(rig.ticks.isEmpty(), "no tick before the display has been quiet for 50 ms")
        rig.runTo(10 * SECOND + 50 * MS)
        assertEquals(Source.TIMER to 10 * SECOND + 50 * MS, rig.ticks.single())
        assertEquals(50L, rig.clock.switchQuietMillis)
    }

    @Test
    fun `on the timer a frame wanted again ticks at once`() {
        val rig = Rig().apply { start(); display = null; runTo(SECOND) }
        rig.wantsFrames = false
        rig.clock.setWanted(false, rig.now)
        assertEquals(NONE, rig.clock.timerAtNanos, "no timer while nothing is wanted")
        assertTrue(rig.clock.vsyncWanted, "the display callback stays posted to see the frames come back")
        rig.runTo(2 * SECOND)
        val before = rig.ticks.size
        rig.wantsFrames = true
        rig.clock.setWanted(true, rig.now)
        rig.runTo(2 * SECOND)
        assertEquals(before + 1, rig.ticks.size)
        assertEquals(Source.TIMER to 2 * SECOND, rig.ticks.last())
    }

    @Test
    fun `a timer that comes before the display has been quiet for 50 ms only looks`() {
        val clock = CarFrameClock()
        clock.start(0L)
        clock.setWanted(true, 0L)
        assertEquals(50 * MS, clock.timerAtNanos)
        assertEquals(NONE, clock.onTimer(49 * MS))
        assertEquals(Source.VSYNC, clock.source)
        assertEquals(50 * MS, clock.timerAtNanos)
        assertEquals(50 * MS, clock.onTimer(50 * MS))
        assertEquals(Source.TIMER, clock.source)
        assertEquals(66 * MS, clock.timerAtNanos)
    }

    @Test
    fun `stop ends everything and start begins afresh`() {
        val rig = Rig().apply { start(); display = null; runTo(SECOND) }
        rig.clock.stop()
        assertFalse(rig.clock.vsyncWanted)
        assertEquals(NONE, rig.clock.timerAtNanos)
        assertEquals(NONE, rig.clock.onVsync(rig.now, rig.now))
        assertEquals(NONE, rig.clock.onTimer(rig.now))
        rig.clock.start(rig.now)
        assertEquals(Source.VSYNC, rig.clock.source)
        assertEquals(0, rig.clock.vsyncTicks)
        assertEquals(0, rig.clock.timerTicks)
        rig.clock.setWanted(true, rig.now)
        assertEquals(rig.now + 50 * MS, rig.clock.timerAtNanos)
    }

    @Test
    fun `the camera line's counts start over with each window`() {
        val rig = Rig().apply { start(); runTo(SECOND) }
        rig.display = null
        rig.runTo(2 * SECOND)
        assertEquals(59, rig.clock.vsyncTicks)
        assertTrue(rig.clock.timerTicks > 0)
        rig.clock.takeTicks()
        assertEquals(0, rig.clock.vsyncTicks)
        assertEquals(0, rig.clock.timerTicks)
    }

    private fun assertGapsAtLeast(rig: Rig, gap: Long) {
        val gaps = rig.ticks.map { it.second }.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= gap }, "the shortest gap was ${gaps.minOrNull()} ns")
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000 * MS
        const val MIN_GAP = 15 * MS
        const val HZ_60 = 16_666_667L
        const val HZ_90 = 11_111_111L
        const val HZ_120 = 8_333_333L
    }
}
