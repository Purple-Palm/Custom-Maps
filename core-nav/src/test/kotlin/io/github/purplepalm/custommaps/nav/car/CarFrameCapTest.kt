package io.github.purplepalm.custommaps.nav.car

import kotlin.math.floor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car map's frame cap, held at 30 frames a second from the render thread's own ticks, against a
 * replay of the map library's pacing ([PacedRenderThread]) and against windows made up by hand.
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
    fun `the cap stays within 1 and 60`() {
        val cap = CarFrameCap()
        // A display at 30 Hz with frames slow to draw: 20 ticks a second would ask for 90.
        repeat(2) { cap.onRenderWindow(ticks = 20, windowMillis = 1_000, displayHz = 30.0, onDisplay = true) }
        assertEquals(60, cap.maxFps)
        assertEquals(1, CarFrameRate.maxFps(10_000.0))
    }

    /** A cap for the display's [hz] alone, as before any guidance, and the camera line's first window starting. */
    private fun displayCap(hz: Double): CarFrameCap = CarFrameCap().also {
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
     * A drive with the car's map on a display at [realHz] whose frames take [drawMillis] to draw: the
     * cap for the display's rate first, as before guidance, then each second a look half way through
     * ([CarFrameCap.onSecond]) and the camera line's window at its end ([CarFrameCap.onRenderWindow]),
     * each new cap set on the render thread at once.
     */
    private fun drive(realHz: Double, drawMillis: Double, seconds: Int): Run {
        val cap = CarFrameCap()
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

    /** The last five seconds of [run] drew within a tenth of 30 a second, under one cap. */
    private fun assertSettled(run: Run, what: String = "") {
        val last = run.drawn.takeLast(5)
        assertTrue(last.all { it in 27..33 }, "$what: drawn ${run.drawn}, caps ${run.caps}")
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
