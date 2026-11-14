package io.github.purplepalm.custommaps.nav.car

import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car map's frame cap: the display's own cap, moved only by the frames the map draws, at 30
 * frames a second or 60 with the Debug switch, against a replay of the map library's pacing
 * ([PacedRenderThread]) and against series of windows made up by hand.
 */
class CarFrameCapTest {

    @Test
    fun `the cap for the display's rate alone draws about 24 frames a second when frames take longer than a display frame`() {
        // 120 Hz, frames of 12 ms: each frame drawn makes the next call wait two display frames, one tick.
        val thread = PacedRenderThread(realHz = 120.0, frameMillis = { 12.0 }, cap = CarFrameRate.maxFps(120.0))
        thread.runUntil(1_000.0)
        val second = thread.take()
        assertEquals(15, CarFrameRate.maxFps(120.0))
        assertTrue(second.drawn in 23..25, "${second.drawn}")
        assertTrue(second.calls in 94..98, "${second.calls}")
    }

    @Test
    fun `with the phone's screen off at 60 Hz the cap holds 30 through a hundred windows of late calls`() {
        val cap = displayCap(60.0)
        assertEquals(30, cap.maxFps)
        val random = Random(SEED)
        repeat(100) {
            // Calls of which 30 drew, and display frames counted as missed that were not: ticks well
            // over the display's frames, which the cap of old took for the render thread's rate.
            val calls = 31 + random.nextInt(11)
            val ticks = 60L + random.nextInt(21)
            assertTrue(ticks >= calls)
            assertFalse(cap.onRenderWindow(ticks = ticks, drawn = 30, p95Ms = 18.0 + random.nextInt(12), windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
            // The display's rate as the main thread measures it, a little off now and then.
            assertFalse(cap.onSecond(onTimer = false, displayHz = 58.0 + random.nextInt(5)))
            cap.onWindowStart()
        }
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        assertTrue(cap.renderHz in 60.0..80.0, "${cap.renderHz}")
    }

    @Test
    fun `the screen turned on and off and on again changes nothing at 60 Hz`() {
        val cap = displayCap(60.0)
        repeat(3) {
            // On: 60 calls a second, every second one drawn.
            repeat(20) { assertFalse(cap.onRenderWindow(ticks = 60, drawn = 30, p95Ms = 9.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
            // Off: late calls, missed frames that were not, a few more frames drawn now and then.
            repeat(20) { index ->
                val drawn = if (index % 3 == 0) 36L else 30L
                assertFalse(cap.onRenderWindow(ticks = 78, drawn = drawn, p95Ms = 22.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
            }
        }
        assertEquals(30, cap.maxFps)
    }

    @Test
    fun `at 120 Hz with frames of 9 to 16 ms the cap rises within two windows and holds about 30`() {
        val run = drive(realHz = 120.0, frameMillis = { index -> 9.0 + index % 8 }, seconds = 12)
        // Before guidance the display's cap, 15, which draws about 24 frames a second.
        assertTrue(run.drawn.first() in 23..25, "${run.drawn}")
        // The first window asks, the second raises it.
        assertEquals(15, run.caps[0], "${run.caps}")
        assertTrue(run.caps[1] > 15, "${run.caps}")
        assertSettled(run)
        assertEquals(CarFrameCap.Basis.DRAWN, run.cap.basis)
        assertEquals(15, run.cap.baseFps)
        assertTrue(run.caps.last() in 18..20, "${run.caps}")
    }

    @Test
    fun `about 30 frames a second at the display rates and frame times a phone has`() {
        val cases = listOf(
            120.0 to 5.0, 120.0 to 9.0, 120.0 to 12.0, 120.0 to 16.0, 120.0 to 20.0,
            90.0 to 5.0, 90.0 to 12.0, 90.0 to 20.0,
            60.0 to 5.0, 60.0 to 12.0, 60.0 to 30.0,
            144.0 to 12.0,
        )
        for ((hz, draw) in cases) assertSettled(drive(realHz = hz, frameMillis = { draw }, seconds = 12), "$hz Hz, $draw ms")
    }

    @Test
    fun `frames quick to draw keep the display's cap`() {
        val run = drive(realHz = 120.0, frameMillis = { 5.0 }, seconds = 8)
        assertEquals(listOf(15), run.caps.distinct())
        assertTrue(run.drawn.all { it in 29..31 }, "${run.drawn}")
        assertEquals(CarFrameCap.Basis.DISPLAY, run.cap.basis)
    }

    @Test
    fun `frames slower than one frame of the target raise nothing, however few the map draws`() {
        // 40 ms a frame at 60 Hz: every call draws, three display frames apart, 20 a second, whatever the cap.
        val run = drive(realHz = 60.0, frameMillis = { 40.0 }, seconds = 8)
        assertTrue(run.drawn.takeLast(4).all { it in 19..21 }, "${run.drawn}")
        assertEquals(listOf(30), run.caps.distinct())
        // Windows made by hand: 24 drawn at 30 with a 95th percentile over 33 ms.
        val cap = displayCap(60.0)
        repeat(10) { assertFalse(cap.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 36.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
        assertEquals(30, cap.maxFps)
        // The same windows with quick frames raise it after two: round(30 x 30 / 24).
        assertFalse(cap.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 30.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 30.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
        assertEquals(38, cap.maxFps)
        assertEquals(24.0, cap.drawnFps, 1e-9)
        // One frame of the target is the limit: 33.3 ms holds it back, as no window without one asks.
        val slow = displayCap(60.0)
        repeat(2) { assertFalse(slow.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 1_000.0 / 30, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
        repeat(2) { assertFalse(slow.onRenderWindow(ticks = 60, drawn = 24, p95Ms = null, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
        assertEquals(30, slow.maxFps)
    }

    @Test
    fun `one window that asks for more frames raises nothing, two in a row do, for the smaller step`() {
        val cap = displayCap(120.0)
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 14.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // Nor does the second between the two windows.
        assertFalse(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(15, cap.maxFps)
        assertTrue(cap.drawnFps.isNaN())
        assertTrue(cap.onRenderWindow(ticks = 90, drawn = 20, p95Ms = 14.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // round(15 x 30 / 24), not the 23 the window that drew 20 alone would give.
        assertEquals(19, cap.maxFps)
        assertEquals(24.0, cap.drawnFps, 1e-9)
        assertEquals(CarFrameCap.Basis.DRAWN, cap.basis)
        // The lines' rate is the ticks of the window, which set nothing.
        assertEquals(90.0, cap.rateHz, 1e-9)
        assertEquals(120.0, cap.displayRateHz, 1e-9)
    }

    @Test
    fun `a step that one window asked for, when no window comes after it, never goes`() {
        val cap = raisedCap()
        assertEquals(19, cap.maxFps)
        // The drive ends as the car stops: its last window asks, and no window follows.
        assertFalse(cap.onRenderWindow(ticks = 100, drawn = 20, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        repeat(10) { assertFalse(cap.onSecond(onTimer = false, displayHz = 120.0)) }
        assertEquals(19, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DRAWN, cap.basis)
    }

    @Test
    fun `the cap stays while the map draws from nine tenths to five fourths of 30`() {
        val cap = displayCap(120.0)
        // 27 a second is no step, twice.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 108, drawn = 27, p95Ms = 10.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)) }
        // 53 in two seconds is under 27: two windows raise it, to round(15 x 30 / 26.5).
        assertFalse(cap.onRenderWindow(ticks = 212, drawn = 53, p95Ms = 10.0, windowMillis = 2_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 212, drawn = 53, p95Ms = 10.0, windowMillis = 2_000, displayHz = 120.0, onDisplay = true))
        assertEquals(17, cap.maxFps)
        // 37.5 a second is no step either; 38 twice lowers it, to round(17 x 30 / 38), held at the base.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 240, drawn = 75, p95Ms = 10.0, windowMillis = 2_000, displayHz = 120.0, onDisplay = true)) }
        assertFalse(cap.onRenderWindow(ticks = 240, drawn = 76, p95Ms = 10.0, windowMillis = 2_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 240, drawn = 76, p95Ms = 10.0, windowMillis = 2_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        assertEquals(38.0, cap.drawnFps, 1e-9)
    }

    @Test
    fun `two windows that draw over five fourths of the target lower the cap, one does not`() {
        // Raised far at 120 Hz: two windows that drew 15 give round(15 x 30 / 15).
        val cap = displayCap(120.0)
        cap.onRenderWindow(ticks = 120, drawn = 15, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)
        assertTrue(cap.onRenderWindow(ticks = 120, drawn = 15, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(30, cap.maxFps)
        // One window of 40: nothing; one in between, and the next 40 is a first one again.
        assertFalse(cap.onRenderWindow(ticks = 120, drawn = 40, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 120, drawn = 30, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 120, drawn = 40, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(30, cap.maxFps)
        // Two in a row: for the smaller step, round(30 x 30 / 40) = 23 (22.5), not the 20 of 45.
        assertTrue(cap.onRenderWindow(ticks = 120, drawn = 45, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(23, cap.maxFps)
        assertEquals(40.0, cap.drawnFps, 1e-9)
        assertEquals(CarFrameCap.Basis.DRAWN, cap.basis)
        // A window that asks for more and one that asks for fewer are no pair.
        assertFalse(cap.onRenderWindow(ticks = 120, drawn = 20, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 120, drawn = 40, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(23, cap.maxFps)
    }

    @Test
    fun `the cap never goes below the display's cap, whatever the ticks`() {
        val cap = displayCap(60.0)
        val random = Random(SEED)
        repeat(100) {
            val ticks = 60L + random.nextInt(141)
            val drawn = 38L + random.nextInt(23)
            assertFalse(cap.onRenderWindow(ticks = ticks, drawn = drawn, p95Ms = 8.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
        }
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
    }

    @Test
    fun `a window busy only in part between busy ones changes nothing`() {
        val cap = displayCap(120.0)
        repeat(3) {
            // Under half of the display's frames: says nothing, and the ask before it goes.
            assertFalse(cap.onRenderWindow(ticks = 50, drawn = 12, p95Ms = 10.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
            assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 10.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        }
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `a map that draws now and then says nothing, and clears a step that waits`() {
        val cap = displayCap(120.0)
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // Standing at a light: a few calls a second, under half of the display's frames.
        assertFalse(cap.onRenderWindow(ticks = 8, drawn = 2, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 59, drawn = 15, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(19, cap.maxFps)
    }

    @Test
    fun `a window off the display, short, without a display rate, with the cap changed in it, or before a base does not count`() {
        val cap = displayCap(120.0)
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = false)) }
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 48, drawn = 12, p95Ms = 12.0, windowMillis = 499, displayHz = 120.0, onDisplay = true)) }
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = null, onDisplay = true)) }
        assertEquals(15, cap.maxFps)
        // The timer and back set two caps in the middle of the next window, which then says nothing; the
        // one after asks, and the next raises.
        assertTrue(cap.onSecond(onTimer = true, displayHz = 120.0))
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(19, cap.maxFps)
        // Before any display rate there is no base to move from.
        val fresh = CarFrameCap()
        repeat(2) { assertFalse(fresh.onRenderWindow(ticks = 60, drawn = 20, p95Ms = 12.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
        assertEquals(30, fresh.maxFps)
        assertEquals(null, fresh.basis)
    }

    @Test
    fun `on the timer the cap is the one for 60 Hz, and back on the display the cap the frames drawn moved`() {
        val cap = raisedCap()
        assertEquals(19, cap.maxFps)
        assertTrue(cap.onSecond(onTimer = true, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.TIMER, cap.basis)
        assertTrue(cap.drawnFps.isNaN())
        assertFalse(cap.onSecond(onTimer = true, displayHz = 60.0))
        // Windows while the clock runs on its timer say nothing.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 60, drawn = 20, p95Ms = 12.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = false)) }
        // The first second back at 120 Hz: the cap the frames drawn moved it to there.
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(19, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DRAWN, cap.basis)
        assertEquals(15, cap.baseFps)
        assertEquals(24.0, cap.drawnFps, 1e-9)
        // A cap moved above the timer's own is not kept on the timer either: 45, then 30 on the timer.
        val high = displayCap(120.0)
        repeat(2) { high.onRenderWindow(ticks = 120, drawn = 15, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true) }
        repeat(2) { high.onRenderWindow(ticks = 120, drawn = 20, p95Ms = 6.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true) }
        assertEquals(45, high.maxFps)
        assertTrue(high.onSecond(onTimer = true, displayHz = 120.0))
        assertEquals(30, high.maxFps)
        assertTrue(high.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(45, high.maxFps)
    }

    @Test
    fun `a second on the timer between two windows breaks the pair, even when the cap stays`() {
        val cap = displayCap(60.0)
        assertFalse(cap.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 20.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
        // At 60 Hz the timer's cap is the display's: nothing changes but the pair.
        assertFalse(cap.onSecond(onTimer = true, displayHz = 60.0))
        assertEquals(CarFrameCap.Basis.TIMER, cap.basis)
        assertFalse(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertFalse(cap.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 20.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
        assertEquals(30, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 60, drawn = 24, p95Ms = 20.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true))
        assertEquals(38, cap.maxFps)
    }

    @Test
    fun `back on a display whose rate moved, the new display's cap, and the cap moved before is gone`() {
        val cap = raisedCap()
        assertTrue(cap.onSecond(onTimer = true, displayHz = null))
        assertEquals(60.0, cap.rateHz, 1e-9)
        // Back at 64 Hz: the timer's 30 holds, from the display and for its rate now.
        assertFalse(cap.onSecond(onTimer = false, displayHz = 64.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        assertEquals(64.0, cap.rateHz, 1e-9)
        // At 120 Hz again: the display's cap, not the one moved at 120 Hz before 60 came between.
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(15, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
    }

    @Test
    fun `a display's rate within a tenth keeps the base and the cap moved, a rate that moved further takes its own base`() {
        val cap = raisedCap()
        repeat(5) { assertFalse(cap.onSecond(onTimer = false, displayHz = 125.0)) }
        repeat(5) { assertFalse(cap.onSecond(onTimer = false, displayHz = 112.0)) }
        assertEquals(19, cap.maxFps)
        // The display slows to 60 Hz: its cap takes over, at once.
        assertTrue(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        assertTrue(cap.drawnFps.isNaN())
    }

    @Test
    fun `before guidance the display's cap, and the first rate asks for a line even when the cap stays`() {
        val cap = CarFrameCap()
        assertEquals(30, cap.maxFps)
        assertTrue(cap.rateHz.isNaN())
        assertFalse(cap.onSecond(onTimer = false, displayHz = null))
        assertTrue(cap.onSecond(onTimer = false, displayHz = 60.0))
        assertEquals(30, cap.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, cap.basis)
        // Within a tenth: nothing new.
        assertFalse(cap.onSecond(onTimer = false, displayHz = 64.0))
        assertEquals(60.0, cap.rateHz, 1e-9)
        assertTrue(cap.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(15, cap.maxFps)
    }

    @Test
    fun `a new surface keeps the cap, but not a step that waits`() {
        val cap = displayCap(120.0)
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        cap.onSurface()
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(15, cap.maxFps)
        assertTrue(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(19, cap.maxFps)
        // The next surface starts with it, and its first window counts.
        cap.onSurface()
        assertEquals(19, cap.maxFps)
        assertFalse(cap.onRenderWindow(ticks = 90, drawn = 28, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(90.0, cap.renderHz, 1e-9)
    }

    @Test
    fun `about 60 frames a second at 120 Hz with the Debug switch at 60`() {
        for (frames in listOf<(Int) -> Double>({ 12.0 }, { index -> 9.0 + index % 8 })) {
            val run = drive(realHz = 120.0, frameMillis = frames, seconds = 15, target = 60)
            assertSettled(run, "120 Hz", target = 60)
            assertEquals(60, run.cap.targetFps)
            assertEquals(30, run.cap.baseFps)
        }
        // Frames quick to draw keep the display's cap: 30 at 120 Hz, 40 at 90, 60 at 60.
        assertEquals(listOf(30), drive(realHz = 120.0, frameMillis = { 5.0 }, seconds = 8, target = 60).caps.distinct())
        assertEquals(listOf(40), drive(realHz = 90.0, frameMillis = { 5.0 }, seconds = 8, target = 60).caps.distinct())
        assertEquals(listOf(60), drive(realHz = 60.0, frameMillis = { 5.0 }, seconds = 8, target = 60).caps.distinct())
    }

    @Test
    fun `at 60 Hz with the Debug switch at 60 the cap is 60 and moves neither way`() {
        val cap = displayCap(60.0, target = 60)
        assertEquals(60, cap.maxFps)
        // Too few, with quick frames: it is the most there is.
        repeat(4) { assertFalse(cap.onRenderWindow(ticks = 60, drawn = 40, p95Ms = 9.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
        // Too many, with late calls: never below the display's cap.
        repeat(4) { assertFalse(cap.onRenderWindow(ticks = 90, drawn = 80, p95Ms = 9.0, windowMillis = 1_000, displayHz = 60.0, onDisplay = true)) }
        assertEquals(60, cap.maxFps)
    }

    @Test
    fun `with the Debug switch at 60 a frame of 60 is the limit, and the cap stays from 54 to 75 drawn`() {
        val cap = displayCap(120.0, target = 60)
        assertEquals(30, cap.maxFps)
        // Frames of 20 ms hold the map back at 60: no step.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 80, drawn = 40, p95Ms = 20.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)) }
        // 54 is no step; under it twice with quick frames raises, to round(30 x 60 / 53).
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 108, drawn = 54, p95Ms = 9.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)) }
        assertFalse(cap.onRenderWindow(ticks = 106, drawn = 53, p95Ms = 9.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 106, drawn = 53, p95Ms = 9.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(34, cap.maxFps)
        // 75 is no step; over it twice lowers, to round(34 x 60 / 76), held at the base 30.
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 120, drawn = 75, p95Ms = 9.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)) }
        assertFalse(cap.onRenderWindow(ticks = 120, drawn = 76, p95Ms = 9.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 120, drawn = 76, p95Ms = 9.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(30, cap.maxFps)
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
    fun `the switch from 30 to 60 and back starts from the new target's base at once`() {
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
        // A cap the frames drawn moved goes with the switch: the new target starts from its base.
        val raised = raisedCap()
        assertEquals(19, raised.maxFps)
        assertTrue(raised.setTarget(60))
        assertEquals(30, raised.maxFps)
        assertEquals(CarFrameCap.Basis.DISPLAY, raised.basis)
        assertTrue(raised.drawnFps.isNaN())
        assertFalse(raised.onSecond(onTimer = false, displayHz = 120.0))
        assertEquals(30, raised.maxFps)
        assertTrue(raised.setTarget(30))
        assertEquals(15, raised.maxFps)
        // Before any rate, for the 60 Hz the map library believes.
        val fresh = CarFrameCap()
        assertEquals(30, fresh.maxFps)
        assertTrue(fresh.setTarget(60))
        assertEquals(60, fresh.maxFps)
        assertTrue(fresh.rateHz.isNaN())
    }

    @Test
    fun `the window under way at a switch says nothing, and a step that waits is dropped`() {
        val cap = displayCap(120.0)
        // 24 a second is under 27: a step waits for a second window.
        assertFalse(cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.setTarget(60))
        assertEquals(30, cap.maxFps)
        // The window the switch came in had two caps: it says nothing, and the step is gone.
        assertFalse(cap.onRenderWindow(ticks = 80, drawn = 40, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        // 40 a second is under 54: two windows of the new cap raise it, to round(30 x 60 / 40).
        assertFalse(cap.onRenderWindow(ticks = 80, drawn = 40, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertTrue(cap.onRenderWindow(ticks = 80, drawn = 40, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true))
        assertEquals(45, cap.maxFps)
        // And back to 30: the display's cap for it, at once.
        assertTrue(cap.setTarget(30))
        assertEquals(15, cap.maxFps)
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
        // A display at 30 Hz: its cap is 60, and windows that draw 10 with quick frames cannot raise it.
        val cap = displayCap(30.0)
        assertEquals(60, cap.maxFps)
        repeat(2) { assertFalse(cap.onRenderWindow(ticks = 30, drawn = 10, p95Ms = 5.0, windowMillis = 1_000, displayHz = 30.0, onDisplay = true)) }
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

    /** A cap at 120 Hz that two windows drawing 24 with frames of 12 ms raised to 19. */
    private fun raisedCap(): CarFrameCap = displayCap(120.0).also { cap ->
        cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)
        cap.onRenderWindow(ticks = 96, drawn = 24, p95Ms = 12.0, windowMillis = 1_000, displayHz = 120.0, onDisplay = true)
    }

    /** The frames drawn each second and the cap at the end of each, of a [drive]. */
    private class Run(val cap: CarFrameCap, val drawn: List<Int>, val caps: List<Int>)

    /**
     * A drive with the car's map on a display at [realHz] whose frames take [frameMillis] of their
     * index to draw, for [target] frames a second: the display's cap first, as before guidance, then
     * each second a look half way through ([CarFrameCap.onSecond]) and the camera line's window at its
     * end ([CarFrameCap.onRenderWindow]), each new cap set on the render thread at once.
     */
    private fun drive(realHz: Double, frameMillis: (Int) -> Double, seconds: Int, target: Int = CarFrameRate.TARGET_FPS): Run {
        val cap = CarFrameCap()
        cap.setTarget(target)
        cap.onSecond(onTimer = false, displayHz = realHz)
        val thread = PacedRenderThread(realHz, frameMillis, cap.maxFps)
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
            if (cap.onRenderWindow(window.calls + window.missed, window.drawn.toLong(), window.p95Ms, MILLIS.toLong(), realHz, onDisplay = true)) {
                thread.setCap(cap.maxFps)
            }
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

    /**
     * One second of a [PacedRenderThread]: its calls, the display frames it counted as missed, the
     * frames it drew and the 95th percentile of the calls' times, as the recorder works it out.
     */
    private class Window(val calls: Long, val missed: Long, val drawn: Int, val p95Ms: Double?)

    /**
     * The map library's frame pacing on the car's render thread (Maps SDK 11.34.1, `FpsManager`),
     * replayed: it believes the surface has 60 Hz while the display really refreshes at [realHz]. Each
     * call counts one tick, and the display frames it counts as missed when it comes more than 1/60 s
     * and a millisecond after the call before; under a cap of `c` it draws on a call when `ticks × c /
     * 60` passes the next whole number, and starts its count over at the first frame it draws once it
     * has counted 60 ticks, and whenever a cap is set. The n-th frame drawn takes [frameMillis] of n, a
     * call let pass almost nothing, and the map always has something new to draw, as while it follows
     * the car: each call asks for the next display frame once it is done.
     */
    private class PacedRenderThread(realHz: Double, private val frameMillis: (Int) -> Double, cap: Int) {
        private val period = MILLIS / realHz
        private var ratio = 0.0
        private var ticks = 0
        private var drawnIndex = 0
        private var lastCall = Double.NaN
        private var time = 0.0
        private var calls = 0L
        private var missed = 0L
        private var drawn = 0
        private var drawnSoFar = 0
        private val times = ArrayList<Double>()

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
                val took = if (index > drawnIndex) {
                    drawnIndex = index
                    drawn += 1
                    if (ticks >= BELIEVED_HZ.toInt()) {
                        ticks = 0
                        drawnIndex = 0
                    }
                    frameMillis(drawnSoFar++)
                } else {
                    PASS_MILLIS
                }
                times += took
                // The next display frame after the call is done.
                time = (floor((now + took) / period) + 1) * period
            }
        }

        fun take(): Window {
            val sorted = times.sorted()
            val p95 = if (sorted.isEmpty()) null else sorted[ceil(0.95 * sorted.size).toInt() - 1]
            return Window(calls, missed, drawn, p95).also {
                calls = 0
                missed = 0
                drawn = 0
                times.clear()
            }
        }
    }

    private companion object {
        const val MILLIS = 1_000.0
        const val BELIEVED_HZ = 60.0
        const val BELIEVED_PERIOD = MILLIS / BELIEVED_HZ
        const val PASS_MILLIS = 0.05
        const val SEED = 20_000_101L
    }
}
