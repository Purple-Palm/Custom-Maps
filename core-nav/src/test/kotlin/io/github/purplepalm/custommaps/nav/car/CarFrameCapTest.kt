package io.github.purplepalm.custommaps.nav.car

import kotlin.math.floor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car map's frame cap, held at 30 frames a second, or 60 with the Debug switch, from the render
 * thread's own ticks, against a replay of the map library's pacing ([PacedRenderThread]) and against
 * windows made up by hand.
 */
class CarFrameCapTest {

    @Test
    fun `the cap for the display's rate alone draws about 24 frames a second when frames take longer than a display frame`() {
        // 120 Hz, frames of 12 ms: each frame drawn makes the next call wait two display frames, one tick.
        val thread = PacedRenderThread(realHz = 120.0, drawMillis = 12.0, cap = CarFrameRate.maxFps(120.0))
        thread.runUntil(1_000.0)
        val second = thread.take()
        assertEquals(15, CarFrameRate.maxFps(120.0))
        assertTrue(second.drawn in 23..25, "${second.drawn}")
        assertTrue(second.calls in 94..98, "${second.calls}")
    }

    @Test
    fun `the render thread's ticks hold a real 30 at 120 Hz with frames of 12 ms`() {
        val run = drive(realHz = 120.0, drawMillis = 12.0, seconds = 12)
        // Before guidance the display's rate: 15, about 24 frames drawn.
        assertTrue(run.drawn.first() in 23..25, "${run.drawn}")
        // Two windows ask for more frames, then the cap settles within a tenth of 30 and stays.
        assertSettled(run)
        assertTrue(run.caps.last() in 19..20, "${run.caps}")
        assertEquals(CarFrameCap.Basis.RENDER, run.cap.basis)
    }

    @Test
    fun `about 30 frames a second at the display rates and frame times a phone has`() {
        val cases = listOf(
            120.0 to 5.0, 120.0 to 9.0, 120.0 to 16.0, 120.0 to 20.0,
            90.0 to 5.0, 90.0 to 12.0, 90.0 to 20.0,
            60.0 to 5.0, 60.0 to 12.0, 60.0 to 30.0,
            144.0 to 12.0,
        )
        for ((hz, draw) in cases) assertSettled(drive(realHz = hz, drawMillis = draw, seconds = 12), "$hz Hz, $draw ms")
    }

    @Test
    fun `frames quick to draw keep the cap for the display's rate`() {
        val run = drive(realHz = 120.0, drawMillis = 5.0, seconds = 8)
        assertEquals(listOf(15), run.caps.distinct())
        assertTrue(run.drawn.all { it in 29..31 }, "${run.drawn}")
    }

    @Test
    fun `frames the phone cannot draw 30 times a second leave the cap where it draws as many as it can`() {
        // 40 ms a frame at 60 Hz: every call draws, three display frames apart, 20 a second.
        val run = drive(realHz = 60.0, drawMillis = 40.0, seconds = 8)
        assertTrue(run.drawn.takeLast(4).all { it in 19..21 }, "${run.drawn}")
        assertEquals(1, run.caps.takeLast(4).distinct().size, "${run.caps}")
    }

    @Test
    fun `one window that asks for more frames raises nothing, two in a row do, for the faster of the two`() {
        val cap = displayCap(120.0)
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // Nor does the second between the two windows.
        assertFalse(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(15, cap.maxFps)
        assertTrue(cap.renderHz.isNaN())
        assertTrue(cap.onRenderWindow(ticks = 75, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // round(30 x 60 / 90), not the 24 the slower window alone would give.
        assertEquals(20, cap.maxFps)
        assertEquals(90.0, cap.rateHz, 1e-9)
        assertEquals(CarFrameCap.Basis.RENDER, cap.basis)
    }

    @Test
    fun `a raise that one window asked for, when no window comes after it, never goes`() {
        val cap = renderCap(ticks = 96)
        assertEquals(19, cap.maxFps)
        // The drive ends as the car stops: its last window is busy only in part, and no window follows.
        assertFalse(cap.onRenderWindow(ticks = 70, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        repeat(10) { assertFalse(cap.onSecond(onTimer = false, displayHz = 120.0)) }
        assertEquals(19, cap.maxFps)
        assertEquals(96.0, cap.renderHz, 1e-9)
    }

    @Test
    fun `a cap stays while the map draws within a tenth of 30 at it`() {
        val cap = displayCap(120.0)
        // 15 x 108 / 60 = 27: held, twice.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 108, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)) }
        // 15 x 107 / 60 is under 27: two windows raise it.
        assertFalse(cap.onRenderWindow(ticks = 107, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 107, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(17, cap.maxFps)
        // 17 x 116 / 60 is under 33: held; 17 x 117 / 60 is over: lowered at once.
        assertFalse(cap.onRenderWindow(ticks = 116, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 117, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `a window busy only in part between busy ones changes nothing`() {
        val cap = displayCap(120.0)
        repeat(3) {
            assertFalse(cap.onRenderWindow(ticks = 70, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
            assertFalse(cap.onRenderWindow(ticks = 120, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        }
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `a map that draws now and then says nothing, and clears a raise that waits`() {
        val cap = displayCap(120.0)
        assertFalse(cap.onRenderWindow(ticks = 80, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // Standing at a light: a few calls a second, under half of the display's frames.
        assertFalse(cap.onRenderWindow(ticks = 8, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 59, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 80, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 80, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(23, cap.maxFps)
    }

    @Test
    fun `a window that asks for fewer frames lowers the cap at once`() {
        val cap = CarFrameCap()
        assertTrue(cap.onSecond(onTimer = true, displayHz = null))
        assertEquals(30, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 120, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `a window off the display, short, without a display rate, or with the cap changed in it does not count`() {
        val cap = displayCap(120.0)
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 80, windowMillis = 1_000, displayHz = 120.0, onDisplay = false)) }
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 40, windowMillis = 499, displayHz = 120.0, onDisplay = true)) }
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 80, windowMillis = 1_000, displayHz = null, onDisplay = true)) }
        assertEquals(15, cap.maxFps)
        // The timer sets 30 in the middle of the next window, which then says nothing; the one after does.
        assertTrue(cap.onSecond(onTimer = true, displayHz = 120.0))
        assertFalse(cap.onRenderWindow(ticks = 120, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(30, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 120, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `on the timer the cap is for 60, and back on the display for the render thread's ticks`() {
        val cap = renderCap(ticks = 96)
        assertEquals(19, cap.maxFps)
        // A window that holds the cap counts the render thread's rate too.
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(19, cap.maxFps)
        assertEquals(90.0, cap.renderHz, 1e-9)
        assertTrue(cap.onSecond(onTimer = true, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.TIMER, cap.basis)
        assertFalse(cap.onSecond(onTimer = true, displayHz = 60.0))
        // The first second back: the ticks counted before, as the display's rate is what it was.
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(20, cap.maxFps)
        assertEquals(CarFrameCap.Basis.RENDER, cap.basis)
    }

    @Test
    fun `back on a display whose rate moved, the display's rate, and a cap that holds says so`() {
        val cap = renderCap(ticks = 90)
        assertTrue(cap.onSecond(onTimer = true, displayHz = null))
        // Back at 60 Hz: 30 holds, from the display now.
        assertFalse(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        assertEquals(60.0, cap.rateHz, 1e-9)
    }

    @Test
    fun `the render thread's rate holds while the display's does, and the display's takes over when it moves`() {
        val cap = renderCap(ticks = 96)
        assertEquals(19, cap.maxFps)
        // The display's rate alone would ask for 15, but the render thread's own count holds, during the
        // drive and after it, while the display stays at about 120 Hz.
        repeat(2) { assertFalse(cap.onSecond(onTimer = false, displayHz = 120.0)) }
        repeat(5) { assertFalse(cap.onSecond(onTimer = false, displayHz = 125.0)) }
        assertEquals(19, cap.maxFps)
        // The display slows to 60 Hz: its rate takes over, at once.
        assertTrue(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
    }

    @Test
    fun `a display rate that moved takes over even while windows come`() {
        val cap = renderCap(ticks = 96)
        assertTrue(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
    }

    @Test
    fun `before guidance the display's rate, and the first rate asks for a line even when the cap stays`() {
        val cap = CarFrameCap()
        assertEquals(30, cap.maxFps)
        assertTrue(cap.rateHz.isNaN())
        assertFalse(cap.onSecond(onTimer = false, displayHz = null))
        assertTrue(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        // Within a tenth: nothing new.
        assertFalse(cap.onSecond(onTimer = false, displayHz = 64.0))
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `a new surface keeps the rate counted, but not a raise that waits`() {
        val cap = displayCap(120.0)
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        cap.onSurface()
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(20, cap.maxFps)
        // The next surface starts with it, and its first window counts.
        cap.onSurface()
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(90.0, cap.renderHz, 1e-9)
    }

    @Test
    fun `about 60 frames a second at the display rates a phone has, with the Debug switch at 60`() {
        val cases = listOf(120.0 to 5.0, 120.0 to 12.0, 90.0 to 5.0, 60.0 to 5.0)
        for ((hz, draw) in cases) {
            val run = drive(realHz = hz, drawMillis = draw, seconds = 12, target = 60)
            assertSettled(run, "$hz Hz, $draw ms", target = 60)
            assertEquals(60, run.cap.targetFps)
        }
        // Frames quick to draw keep the cap for the display's rate: 30 at 120 Hz, 40 at 90, 60 at 60.
        assertEquals(listOf(30), drive(realHz = 120.0, drawMillis = 5.0, seconds = 8, target = 60).caps.distinct())
        assertEquals(listOf(40), drive(realHz = 90.0, drawMillis = 5.0, seconds = 8, target = 60).caps.distinct())
        assertEquals(listOf(60), drive(realHz = 60.0, drawMillis = 5.0, seconds = 8, target = 60).caps.distinct())
    }

    @Test
    fun `with the Debug switch at 60 the timer takes the cap to 60`() {
        val cap = CarFrameCap()
        cap.setTarget(60)
        assertTrue(cap.onSecond(onTimer = true, displayHz = null))
        assertEquals(60, cap.maxFps)
        assertEquals(CarFrameCap.Basis.TIMER, cap.basis)
        // Back on a display at 120 Hz: 30.
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(30, cap.maxFps)
    }

    @Test
    fun `a cap stays while the map draws within a tenth of 60 at it`() {
        val cap = displayCap(120.0, target = 60)
        assertEquals(30, cap.maxFps)
        // 30 x 108 / 60 = 54: held, twice.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 108, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)) }
        // 30 x 107 / 60 is under 54: two windows raise it, to round(60 x 60 / 107).
        assertFalse(cap.onRenderWindow(ticks = 107, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 107, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(34, cap.maxFps)
        // 34 x 116 / 60 is under 66: held; 34 x 117 / 60 is over: lowered at once.
        assertFalse(cap.onRenderWindow(ticks = 116, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 117, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(31, cap.maxFps)
    }

    @Test
    fun `the switch from 30 to 60 and back sets the cap at once, for the rate taken`() {
        // Before guidance, for the display's rate: 15 and 30 at 120 Hz.
        val display = displayCap(120.0)
        assertEquals(15, display.maxFps)
        assertTrue(display.setTarget(60))
        assertEquals(30, display.maxFps)
        assertEquals(60, display.targetFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, display.basis)
        assertEquals(120.0, display.rateHz, 1e-9)
        // The same target again changes nothing.
        assertFalse(display.setTarget(60))
        assertTrue(display.setTarget(30))
        assertEquals(15, display.maxFps)
        // During guidance, for the render thread's own ticks: round(30 x 60 / 96) and round(60 x 60 / 96).
        val render = renderCap(ticks = 96)
        assertEquals(19, render.maxFps)
        assertTrue(render.setTarget(60))
        assertEquals(38, render.maxFps)
        assertEquals(CarFrameCap.Basis.RENDER, render.basis)
        // Held once a second while the display's rate stays, at 60 as at 30.
        assertFalse(render.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(38, render.maxFps)
        assertTrue(render.setTarget(30))
        assertEquals(19, render.maxFps)
        // Before any rate, for the 60 Hz the map library believes.
        val fresh = CarFrameCap()
        assertEquals(30, fresh.maxFps)
        assertTrue(fresh.setTarget(60))
        assertEquals(60, fresh.maxFps)
        assertTrue(fresh.rateHz.isNaN())
    }

    @Test
    fun `the window under way at a switch says nothing, and a raise that waits is dropped`() {
        val cap = displayCap(120.0)
        // 15 x 90 / 60 is under 27: a raise waits for a second window.
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.setTarget(60))
        assertEquals(30, cap.maxFps)
        // The window the switch came in had two caps: it says nothing, and the raise is gone.
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // 30 x 90 / 60 is under 54: two windows of the new cap raise it, to round(60 x 60 / 90).
        assertFalse(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 90, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(40, cap.maxFps)
        // And back to 30: round(30 x 60 / 90), at once, for the rate it holds.
        assertTrue(cap.setTarget(30))
        assertEquals(20, cap.maxFps)
    }

    @Test
    fun `a target the switch has no word for stays within 1 and 60`() {
        val cap = displayCap(60.0)
        assertTrue(cap.setTarget(90))
        assertEquals(60, cap.targetFps)
        assertEquals(60, cap.maxFps)
        assertTrue(cap.setTarget(0))
        assertEquals(1, cap.targetFps)
        assertEquals(1, cap.maxFps)
    }

    @Test
    fun `the cap stays within 1 and 60`() {
        val cap = CarFrameCap()
        // A display at 30 Hz with frames slow to draw: 20 ticks a second would ask for 90.
        repeat(2) { cap.onRenderWindow(ticks = 20, windowMillis = 1_000, displayHz = 30.0, onDisplay = true) }
        assertEquals(60, cap.maxFps)
        assertEquals(1, CarFrameRate.maxFps(10_000.0))
    }

    /**
     * A cap for the display's [hz] alone, as before any guidance, for [target] frames a second, and the
     * camera line's first window starting.
     */
    private fun displayCap(hz: Double, target: Int = CarFrameRate.TARGET_FPS): CarFrameCap = CarFrameCap().also {
        it.setTarget(target)
        it.onSecond(onTimer = false, displayHz = hz)
        it.onWindowStart()
    }

    /** A cap from two windows of [ticks] a second at 120 Hz, after the display's rate. */
    private fun renderCap(ticks: Long): CarFrameCap = displayCap(120.0).also { cap ->
        cap.onRenderWindow(ticks = ticks, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)
        cap.onRenderWindow(ticks = ticks, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)
    }

    /** The frames drawn each second and the cap at the end of each, of a [drive]. */
    private class Run(val cap: CarFrameCap, val drawn: List<Int>, val caps: List<Int>)

    /**
     * A drive with the car's map on a display at [realHz] whose frames take [drawMillis] to draw, for
     * [target] frames a second: the cap for the display's rate first, as before guidance, then each
     * second a look half way through ([CarFrameCap.onSecond]) and the camera line's window at its end
     * ([CarFrameCap.onRenderWindow]), each new cap set on the render thread at once.
     */
    private fun drive(realHz: Double, drawMillis: Double, seconds: Int, target: Int = CarFrameRate.TARGET_FPS): Run {
        val cap = CarFrameCap()
        cap.setTarget(target)
        cap.onSecond(onTimer = false, displayHz = realHz)
        val thread = PacedRenderThread(realHz, drawMillis, cap.maxFps)
        // Guidance starts: the camera line's first window.
        cap.onWindowStart()
        val drawn = mutableListOf<Int>()
        val caps = mutableListOf<Int>()
        for (second in 1..seconds) {
            thread.runUntil(second * MILLIS - MILLIS / 2)
            if (cap.onSecond(onTimer = false, displayHz = realHz)) thread.setCap(cap.maxFps)
            thread.runUntil(second * MILLIS)
            val window = thread.take()
            drawn += window.drawn
            if (cap.onRenderWindow(window.calls + window.missed, MILLIS.toLong(), realHz, onDisplay = true)) thread.setCap(cap.maxFps)
            // The camera line's fields of the screen, as the next window starts.
            cap.onWindowStart()
            caps += cap.maxFps
        }
        return Run(cap, drawn, caps)
    }

    /** The last five seconds of [run] drew within a tenth of [target] a second, under one cap. */
    private fun assertSettled(run: Run, what: String = "", target: Int = CarFrameRate.TARGET_FPS) {
        val last = run.drawn.takeLast(5)
        val band = (target - target / 10)..(target + target / 10)
        assertTrue(last.all { it in band }, "$what: drawn ${run.drawn}, caps ${run.caps}")
        assertEquals(1, run.caps.takeLast(5).distinct().size, "$what: caps ${run.caps}")
    }

    /** One second of a [PacedRenderThread]: its calls, the display frames it counted as missed, the frames it drew. */
    private class Window(val calls: Long, val missed: Long, val drawn: Int)

    /**
     * The map library's frame pacing on the car's render thread (Maps SDK 11.34.1, `FpsManager`),
     * replayed: it believes the surface has 60 Hz while the display really refreshes at [realHz]. Each
     * call counts one tick, and the display frames it counts as missed when it comes more than 1/60 s
     * and a millisecond after the call before; under a cap of `c` it draws on a call when `ticks × c /
     * 60` passes the next whole number, and starts its count over at the first frame it draws once it
     * has counted 60 ticks, and whenever a cap is set. A frame drawn takes [drawMillis], a call let
     * pass almost nothing, and the map always has something new to draw, as while it follows the car:
     * each call asks for the next display frame once it is done.
     */
    private class PacedRenderThread(realHz: Double, private val drawMillis: Double, cap: Int) {
        private val period = MILLIS / realHz
        private var ratio = 0.0
        private var ticks = 0
        private var drawnIndex = 0
        private var lastCall = Double.NaN
        private var time = 0.0
        private var calls = 0L
        private var missed = 0L
        private var drawn = 0

        init {
            setCap(cap)
        }

        fun setCap(cap: Int) {
            ratio = (cap / BELIEVED_HZ).coerceIn(0.0, 1.0)
            ticks = 0
            drawnIndex = 0
        }

        /** Calls the render thread on each display frame it asks for, until [untilMillis]. */
        fun runUntil(untilMillis: Double) {
            while (time < untilMillis) {
                val now = time
                calls += 1
                var skipped = 0
                if (!lastCall.isNaN()) {
                    val elapsed = now - lastCall
                    if (elapsed > BELIEVED_PERIOD + 1.0) skipped = (elapsed / (BELIEVED_PERIOD + 1.0)).toInt()
                }
                lastCall = now
                ticks += skipped + 1
                missed += skipped
                val index = (ticks * ratio).toInt()
                val end = if (index > drawnIndex) {
                    drawnIndex = index
                    drawn += 1
                    if (ticks >= BELIEVED_HZ.toInt()) {
                        ticks = 0
                        drawnIndex = 0
                    }
                    now + drawMillis
                } else {
                    now + PASS_MILLIS
                }
                // The next display frame after the call is done.
                time = (floor(end / period) + 1) * period
            }
        }

        fun take(): Window = Window(calls, missed, drawn).also {
            calls = 0
            missed = 0
            drawn = 0
        }
    }

    private companion object {
        const val MILLIS = 1_000.0
        const val BELIEVED_HZ = 60.0
        const val BELIEVED_PERIOD = MILLIS / BELIEVED_HZ
        const val PASS_MILLIS = 0.05
    }
}
