package io.github.purplepalm.custommaps.nav.camera

import java.util.Random
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runs the camera math the way the camera does: once-a-second input, read at 60 frames a second. */
class CameraMotionTest {
    private val tuning = CameraTuning.PHONE
    private val excluded = listOf("continue", "merge", "on ramp", "off ramp", "fork")
    private val frame = 1.0 / 60

    @Test
    fun `spring never overshoots and is exact for any frame time`() {
        val fine = Spring(0.2).apply { snap(0.0) }
        val coarse = Spring(0.2).apply { snap(0.0) }
        repeat(60) { fine.step(10.0, frame) }
        coarse.step(10.0, 1.0)
        assertEquals(fine.value, coarse.value, 1e-9)
        assertTrue(fine.value < 10.0)
    }

    @Test
    fun `a spring settles on a target that holds still`() {
        val spring = Spring(tuning.centerTau).apply { snap(3.0) }
        assertTrue(spring.isSettled(3.0, 1e-6))
        assertFalse(spring.isSettled(4.0, 1e-6))
        repeat(600) { spring.step(4.0, frame) }
        assertTrue(spring.isSettled(4.0, 1e-6), "value ${spring.value} velocity ${spring.velocity}")
    }

    @Test
    fun `ramp with lead keeps a steady curve at a steady rotation rate`() {
        val curve = steadyCurve { 1.0 }
        assertTrue(curve.slowest > 4.8 && curve.fastest < 5.2, "$curve")
    }

    @Test
    fun `in a steady curve the spring trails the ramp by one time constant`() {
        // Not on the ramp, as with a lead of two time constants, and not two behind, as with none.
        val curve = steadyCurve { 1.0 }
        val oneTau = tuning.bearingTau * 5.0
        assertTrue(curve.leastLag > oneTau - 0.1 && curve.mostLag < oneTau + 0.1, "$curve")
    }

    @Test
    fun `fixes that come a little early or late keep the curve steady`() {
        // The navigator's updates are not exactly a second apart. These intervals are made up; they
        // cover the spread a drive log showed, 0.977 to 1.016 s.
        for (seed in 1L..5L) {
            val random = Random(seed)
            val curve = steadyCurve { 0.977 + (1.016 - 0.977) * random.nextDouble() }
            assertTrue(curve.slowest > 4.8 && curve.fastest < 5.2, "seed $seed: $curve")
        }
        val extremes = doubleArrayOf(0.977, 1.016, 1.016, 0.977, 0.977, 0.977, 1.016, 1.016, 1.016, 0.977)
        var next = 0
        val curve = steadyCurve { extremes[next++ % extremes.size] }
        assertTrue(curve.slowest > 4.8 && curve.fastest < 5.2, "extremes: $curve")
    }

    @Test
    fun `the end of a turn is reached and not passed`() {
        // A corner that takes three updates, one that starts late, and a bend. Then the road is straight.
        assertStopsOnLastSample(tuning.bearingTau, 0.0, 30.0, 60.0, 90.0)
        assertStopsOnLastSample(tuning.bearingTau, 0.0, 0.0, 45.0, 90.0)
        assertStopsOnLastSample(tuning.bearingTau, 0.0, 15.0, 30.0, 45.0)
        // The same for a zoom that stops changing, in and out.
        assertStopsOnLastSample(tuning.zoomTau, 15.0, 15.5, 16.2)
        assertStopsOnLastSample(tuning.zoomTau, 16.35, 15.8, 15.15)
    }

    @Test
    fun `a ramp starts on its first sample and reaches each later one after the hold`() {
        val ramp = SampleRamp(tuning.sampleHoldSeconds)
        assertFalse(ramp.ready)
        ramp.push(7.0, 10.0)
        assertTrue(ramp.ready)
        assertEquals(7.0, ramp.value(10.0), 1e-12)
        assertFalse(ramp.isMoving(10.0))

        ramp.push(9.0, 11.0)
        assertEquals(7.0, ramp.value(11.0), 1e-12)
        assertEquals(8.0, ramp.value(11.0 + tuning.sampleHoldSeconds / 2), 1e-12)
        assertTrue(ramp.isMoving(11.5))
        assertEquals(2.0 / tuning.sampleHoldSeconds, ramp.rate(11.5), 1e-12)
        val reached = 11.0 + tuning.sampleHoldSeconds + 0.001
        assertEquals(9.0, ramp.value(reached), 1e-12)
        assertEquals(0.0, ramp.rate(reached), 1e-12)
        assertFalse(ramp.isMoving(reached))

        ramp.forget()
        ramp.push(1.0, 20.0)
        assertEquals(1.0, ramp.value(20.0), 1e-12)
    }

    @Test
    fun `a ramp left alone ends exactly on its sample`() {
        val ramp = SampleRamp(tuning.sampleHoldSeconds)
        // 0.1 + (0.3 - 0.1) is not 0.3 in floating point; the end of the ramp has to be.
        ramp.push(0.1, 0.0)
        ramp.push(0.3, 1.0)
        assertEquals(0.3, ramp.value(1.0 + tuning.sampleHoldSeconds + 0.001), 0.0)
        assertEquals(0.3, ramp.value(50.0), 0.0)
        // The same sample again then moves nothing.
        ramp.push(0.3, 3.0)
        assertFalse(ramp.isMoving(3.0))
        assertEquals(0.0, ramp.rate(3.5), 0.0)
    }

    @Test
    fun `a ramp given the same sample every second comes to rest within a tolerance`() {
        val ramp = SampleRamp(tuning.sampleHoldSeconds)
        ramp.push(15.0, 0.0)
        // The hold is longer than a second, so each sample starts the rest of the way over.
        for (second in 1..5) ramp.push(16.0, second.toDouble())
        assertTrue(ramp.isMoving(5.0), "still ${16.0 - ramp.value(5.0)} away")
        assertFalse(ramp.isMoving(5.0, tolerance = 1e-3))
        assertEquals(16.0, ramp.value(5.0), 1e-3)
        // A new sample moves it again, also with the tolerance.
        ramp.push(17.0, 6.0)
        assertTrue(ramp.isMoving(6.5, tolerance = 1e-3))
    }

    @Test
    fun `angles wrap and take the short way round`() {
        assertEquals(-170.0, Angles.wrap180(190.0), 1e-12)
        assertEquals(-180.0, Angles.wrap180(180.0), 1e-12)
        assertEquals(350.0, Angles.wrap360(-10.0), 1e-12)
        assertEquals(0.0, Angles.wrap360(720.0), 1e-12)
        // From 10 degrees the short way to 350 is 20 degrees back, not 340 forward.
        assertEquals(-10.0, Angles.nearest(350.0, 10.0), 1e-12)
        assertEquals(725.0, Angles.nearest(5.0, 730.0), 1e-12)
    }

    @Test
    fun `easing starts and ends without speed`() {
        assertEquals(0.0, easeInOut(-1.0), 0.0)
        assertEquals(0.0, easeInOut(0.0), 0.0)
        assertEquals(0.5, easeInOut(0.5), 1e-12)
        assertEquals(1.0, easeInOut(1.0), 0.0)
        assertEquals(1.0, easeInOut(2.0), 0.0)
        assertTrue(easeInOut(0.01) < 1e-4)
        assertTrue(1.0 - easeInOut(0.99) < 1e-4)
        assertEquals(15.0, lerp(10.0, 30.0, 0.25), 1e-12)
    }

    @Test
    fun `tilt goes down smoothly before a turn, stays down through it and comes back`() {
        val focus = ManeuverFocus(tuning)
        val v = 50 / 3.6
        var t = 0.0
        var x = 0.0
        var next = 0.0
        var lastPitch = tuning.cruisePitch
        var maxRate = 0.0
        var pitchAt180 = Double.NaN
        var pitchAtTurn = Double.NaN
        while (t < 80.0) {
            x += v * frame
            if (t >= next) {
                next += 1.0
                if (x < 600.0) {
                    focus.onProgress(t, "r/0/0", 600.0 - x, x, "turn", excluded, v)
                } else {
                    focus.onProgress(t, "r/0/1", 2600.0 - x, x - 600.0, "turn", excluded, v)
                }
            }
            val pitch = focus.pitchAt(t)
            maxRate = maxOf(maxRate, abs(pitch - lastPitch) / frame)
            lastPitch = pitch
            if (pitchAt180.isNaN() && 600.0 - x <= 180.0) pitchAt180 = pitch
            if (pitchAtTurn.isNaN() && x >= 600.0) pitchAtTurn = pitch
            t += frame
        }
        assertTrue(pitchAt180 < 3.0, "pitch at 180 m: $pitchAt180")
        assertEquals(0.0, pitchAtTurn, 1e-9)
        assertEquals(tuning.cruisePitch, lastPitch, 1e-9)
        assertTrue(maxRate < 30.0, "peak $maxRate deg/s")
    }

    @Test
    fun `braking never tilts the view back up before the turn`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 250.0, 0.0, "turn", excluded, 25.0)
        assertEquals(1.0, focus.goal)
        focus.onProgress(1.0, "r/0/0", 230.0, 20.0, "turn", excluded, 2.0)
        assertEquals(1.0, focus.goal)
    }

    @Test
    fun `excluded maneuvers keep the cruise tilt`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 50.0, 0.0, "fork", excluded, 20.0)
        assertEquals(tuning.cruisePitch, focus.pitchAt(10.0), 1e-9)
    }

    @Test
    fun `a step without a maneuver type keeps the cruise tilt`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 50.0, 0.0, null, excluded, 20.0)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `a close second turn keeps the view down`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 8.0)
        focus.onProgress(20.0, "r/0/1", 120.0, 5.0, "turn", excluded, 8.0)
        focus.onProgress(23.0, "r/0/1", 96.0, 29.0, "turn", excluded, 8.0)
        assertEquals(1.0, focus.goal)
    }

    @Test
    fun `a reroute with the same indices is a new step`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "a/0/0", 150.0, 0.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.goal)
        focus.onProgress(1.0, "b/0/0", 900.0, 0.0, "turn", excluded, 10.0)
        focus.onProgress(4.0, "b/0/0", 870.0, 30.0, "turn", excluded, 10.0)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `a reversal halfway down starts from where the tilt is and takes half of the way up`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 200.0, 0.0, "turn", excluded, 10.0)
        val turnedAt = tuning.descentSeconds / 2
        assertEquals(0.5, focus.focusAt(turnedAt), 1e-9)

        // Guidance ends in the middle of the descent.
        focus.reset(turnedAt)

        assertEquals(0.0, focus.goal)
        assertEquals(0.5, focus.focusAt(turnedAt), 1e-9)
        val back = tuning.ascentSeconds / 2
        assertTrue(focus.isAnimating(turnedAt + back - 0.01))
        assertFalse(focus.isAnimating(turnedAt + back + 0.001))
        assertEquals(0.0, focus.focusAt(turnedAt + back), 1e-9)
        assertEquals(tuning.cruisePitch, focus.pitchAt(turnedAt + back), 1e-9)
        assertSmooth(focus, from = 0.0, to = turnedAt + back + 1.0)
    }

    @Test
    fun `the next turn coming up while the view rises turns it back down from where it is`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 10.0)
        // Past the turn and past the hold: the view starts to rise.
        focus.onProgress(20.0, "r/0/1", 400.0, 0.0, "turn", excluded, 10.0)
        focus.onProgress(23.0, "r/0/1", 370.0, 30.0, "turn", excluded, 10.0)
        assertEquals(0.0, focus.goal)
        // Halfway up, the next turn's window opens: 180 m and 3.5 s at 10 m/s.
        val turnedAt = 23.0 + tuning.ascentSeconds / 2
        assertEquals(0.5, focus.focusAt(turnedAt), 1e-9)
        focus.onProgress(turnedAt, "r/0/1", 215.0, 185.0, "turn", excluded, 10.0)

        assertEquals(1.0, focus.goal)
        assertEquals(0.5, focus.focusAt(turnedAt), 1e-9)
        val down = tuning.descentSeconds / 2
        assertEquals(1.0, focus.focusAt(turnedAt + down), 1e-9)
        assertEquals(tuning.maneuverPitch, focus.pitchAt(turnedAt + down), 1e-9)
        assertSmooth(focus, from = 19.0, to = turnedAt + down + 1.0)
    }

    @Test
    fun `a reversal right after the start still takes a moment`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 200.0, 0.0, "turn", excluded, 10.0)
        focus.reset(0.2)
        assertTrue(focus.isAnimating(0.2 + 0.29))
        assertFalse(focus.isAnimating(0.2 + 0.301))
        assertEquals(0.0, focus.focusAt(0.2 + 0.301), 1e-9)
    }

    @Test
    fun `the car screen follows the puck a little more calmly`() {
        assertTrue(CameraTuning.CAR.centerTau > CameraTuning.PHONE.centerTau)
        assertEquals(CameraTuning.PHONE.copy(centerTau = CameraTuning.CAR.centerTau), CameraTuning.CAR)
    }

    /** How a spring took a steady curve: its turn in degrees a second, and how far it was behind the ramp. */
    private data class Curve(val slowest: Double, val fastest: Double, val leastLag: Double, val mostLag: Double)

    /**
     * A bearing that turns 5 degrees a second, sampled at the times [interval] gives and followed by
     * a ramp and a spring with the ramp's lead, as the camera follows it. Measured once the spring
     * has caught up.
     */
    private fun steadyCurve(interval: () -> Double): Curve {
        val ramp = SampleRamp(tuning.sampleHoldSeconds)
        val spring = Spring(tuning.bearingTau)
        var t = 0.0
        var next = 0.0
        var slowest = Double.MAX_VALUE
        var fastest = -Double.MAX_VALUE
        var leastLag = Double.MAX_VALUE
        var mostLag = -Double.MAX_VALUE
        while (t < 60.0) {
            if (t >= next) {
                ramp.push(5.0 * next, t)
                next += interval()
            }
            val before = spring.value
            spring.step(ramp.lead(t, tuning.bearingTau * tuning.rampLead), frame)
            if (t > 10.0) {
                val rate = (spring.value - before) / frame
                slowest = minOf(slowest, rate)
                fastest = maxOf(fastest, rate)
                val lag = ramp.value(t + frame) - spring.value
                leastLag = minOf(leastLag, lag)
                mostLag = maxOf(mostLag, lag)
            }
            t += frame
        }
        return Curve(slowest, fastest, leastLag, mostLag)
    }

    /**
     * Feeds [samples] once a second, and then the last of them for ten seconds more, to a ramp and
     * a spring of time constant [tau] that follows the ramp's lead, as the camera does. The spring
     * has to come up to the last sample without ever passing it or turning back.
     */
    private fun assertStopsOnLastSample(tau: Double, vararg samples: Double) {
        val ramp = SampleRamp(tuning.sampleHoldSeconds)
        val spring = Spring(tau).apply { snap(samples.first()) }
        val last = samples.last()
        val up = last >= samples.first()
        var t = 0.0
        var next = 0
        while (t < samples.size + 10.0) {
            if (t >= next) {
                ramp.push(samples[minOf(next, samples.size - 1)], t)
                next += 1
            }
            val before = spring.value
            spring.step(ramp.lead(t, tau * tuning.rampLead), frame)
            val past = if (up) spring.value - last else last - spring.value
            val back = if (up) before - spring.value else spring.value - before
            assertTrue(past < 1e-6, "at $t s: $past past $last")
            assertTrue(back < 1e-9, "at $t s: turned back by $back")
            t += frame
        }
        assertEquals(last, spring.value, 0.01)
    }

    /** No jump in the tilt and no turn faster than 30 degrees a second, frame by frame. */
    private fun assertSmooth(focus: ManeuverFocus, from: Double, to: Double) {
        var t = from
        var last = focus.pitchAt(t)
        while (t < to) {
            t += frame
            val pitch = focus.pitchAt(t)
            assertTrue(abs(pitch - last) / frame < 30.0, "at $t s: ${abs(pitch - last) / frame} deg/s")
            last = pitch
        }
    }
}
