package io.github.purplepalm.custommaps.nav.camera

import java.util.Random
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One mile an hour in meters a second. */
private const val MPH = 0.44704

/** Runs the camera math the way the camera does: once-a-second input, read at 60 frames a second. */
class CameraMotionTest {
    private val tuning = CameraTuning.PHONE
    private val excluded = listOf("continue", "merge", "on ramp", "off ramp", "fork")
    private val frame = 1.0 / 60

    /** Sixteen phases of the once-a-second updates against the turns, each a whole sixteenth. */
    private val phases = (0 until 16).map { it / 16.0 }

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
    fun `a spring from rest moves by the closed form and stays short of its target`() {
        val tau = 0.2
        val spring = Spring(tau).apply { snap(0.0) }
        // After one time constant a critically damped spring has gone 1 - 2 / e of the way.
        spring.step(10.0, tau)
        assertEquals(10.0 * (1.0 - 2.0 / Math.E), spring.value, 1e-9)
        assertEquals(10.0 / tau / Math.E, spring.velocity, 1e-9)
        repeat(600) {
            spring.step(10.0, frame)
            assertTrue(spring.value <= 10.0, "value ${spring.value}")
        }
    }

    @Test
    fun `a snapped spring is at rest`() {
        val spring = Spring(0.2).apply { snap(0.0) }
        spring.step(10.0, 0.1)
        assertTrue(spring.velocity > 1.0, "velocity ${spring.velocity}")
        spring.snap(4.0)
        assertEquals(4.0, spring.value, 0.0)
        assertEquals(0.0, spring.velocity, 0.0)
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
        // Asked about a moment before the sample came, it stands where it started.
        assertEquals(7.0, ramp.value(10.99), 1e-12)
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
                    focus.onBanner(t, "r/0/0", 600.0 - x, x, "turn", excluded, v)
                } else {
                    focus.onBanner(t, "r/0/1", 2600.0 - x, x - 600.0, "turn", excluded, v)
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
    fun `the guidance tilt rises for the lanes and goes down over a junction`() {
        assertEquals(45.0, guidancePitch(tuning, laneShare = 0.0, junctionFocus = 0.0), 1e-9)
        assertEquals(55.0, guidancePitch(tuning, laneShare = 1.0, junctionFocus = 0.0), 1e-9)
        assertEquals(0.0, guidancePitch(tuning, laneShare = 0.0, junctionFocus = 1.0), 1e-9)
        // Over a junction the junction wins, whatever the lanes ask.
        assertEquals(0.0, guidancePitch(tuning, laneShare = 1.0, junctionFocus = 1.0), 1e-9)
        assertEquals(50.0, guidancePitch(tuning, laneShare = 0.5, junctionFocus = 0.0), 1e-9)
        assertEquals(27.5, guidancePitch(tuning, laneShare = 1.0, junctionFocus = 0.5), 1e-9)
        // With the lane tilt equal to the cruise tilt the lanes tilt nothing.
        val flat = tuning.copy(lanePitch = tuning.cruisePitch)
        assertEquals(45.0, guidancePitch(flat, laneShare = 1.0, junctionFocus = 0.0), 1e-9)
    }

    @Test
    fun `without the lanes the guidance tilt is the maneuver focus's own`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 300.0, 0.0, "turn", excluded, 15.0)
        focus.onBanner(20.0, "r/0/1", 900.0, 5.0, "turn", excluded, 15.0)
        var t = 0.0
        while (t < 30.0) {
            assertEquals(focus.pitchAt(t), guidancePitch(tuning, 0.0, focus.focusAt(t)), 0.0, "at $t s")
            t += frame
        }
    }

    @Test
    fun `braking never tilts the view back up before the turn`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 250.0, 0.0, "turn", excluded, 25.0)
        assertEquals(1.0, focus.goal)
        focus.onBanner(1.0, "r/0/0", 230.0, 20.0, "turn", excluded, 2.0)
        assertEquals(1.0, focus.goal)
    }

    @Test
    fun `excluded maneuvers keep the cruise tilt`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 50.0, 0.0, "fork", excluded, 20.0)
        assertEquals(tuning.cruisePitch, focus.pitchAt(10.0), 1e-9)
    }

    @Test
    fun `after a fork the view stays up`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 50.0, 500.0, "fork", excluded, 20.0)
        assertEquals(0.0, focus.goal)
        // Past the fork, with a turn far ahead: there was no view down to hold.
        focus.onBanner(1.0, "r/0/1", 900.0, 0.0, "turn", excluded, 20.0)
        assertEquals(0.0, focus.goal)
        focus.onBanner(2.0, "r/0/1", 880.0, 20.0, "turn", excluded, 20.0)
        assertEquals(0.0, focus.goal)
        assertEquals(tuning.cruisePitch, focus.pitchAt(2.0), 1e-9)
    }

    @Test
    fun `the view stays down after a turn until the car is both far enough and long enough past it`() {
        // A car that stops just after the turn: long past it, but not far.
        val stopped = pastATurn()
        stopped.onBanner(101.0, "r/0/1", 897.0, 3.0, "turn", excluded, 0.0)
        stopped.onBanner(111.0, "r/0/1", 897.0, 3.0, "turn", excluded, 0.0)
        assertEquals(1.0, stopped.goal)

        // A car that is quickly far past the turn, but not long.
        val quick = pastATurn()
        quick.onBanner(101.0, "r/0/1", 870.0, 30.0, "turn", excluded, 30.0)
        assertEquals(1.0, quick.goal)

        // Far enough and long enough: the view rises.
        val gone = pastATurn()
        gone.onBanner(101.0, "r/0/1", 870.0, 30.0, "turn", excluded, 30.0)
        gone.onBanner(103.5, "r/0/1", 795.0, 105.0, "turn", excluded, 30.0)
        assertEquals(0.0, gone.goal)
    }

    @Test
    fun `a car that stands or has no speed yet still gets the tilt before the turn`() {
        // The window opens at the framing distance plus what a slow car covers during the descent.
        val window = tuning.maneuverFramingMeters + 3.0 * tuning.descentSeconds
        for (speed in listOf(null, 0.0)) {
            val outside = ManeuverFocus(tuning)
            outside.onBanner(0.0, "r/0/0", window + 1.0, 0.0, "turn", excluded, speed)
            assertEquals(0.0, outside.goal, "speed $speed")
            val inside = ManeuverFocus(tuning)
            inside.onBanner(0.0, "r/0/0", window - 1.0, 0.0, "turn", excluded, speed)
            assertEquals(1.0, inside.goal, "speed $speed")
        }
    }

    @Test
    fun `without the facts the tilt starts down exactly at the SDK's framing distance plus the descent`() {
        // Standing, slow, at town speed and on a freeway: the latch opens at the window and no farther out.
        for (speed in listOf(0.0, 3.0, 13.0, 30.0)) {
            val window = FocusWindow.windowMeters(FocusKind.TURN, speed, Double.NaN, tuning)
            assertEquals(tuning.maneuverFramingMeters + maxOf(speed, 3.0) * tuning.descentSeconds, window, 1e-9, "speed $speed")
            val outside = ManeuverFocus(tuning)
            outside.onBanner(0.0, "r/0/0", window + 0.01, 0.0, "turn", excluded, speed)
            assertEquals(0.0, outside.goal, "speed $speed")
            val at = ManeuverFocus(tuning)
            at.onBanner(0.0, "r/0/0", window, 0.0, "turn", excluded, speed)
            assertEquals(1.0, at.goal, "speed $speed")
        }
        // 180 m and 3.5 s at 13 m/s.
        assertEquals(225.5, FocusWindow.windowMeters(FocusKind.TURN, 13.0, Double.NaN, tuning), 1e-9)
    }

    @Test
    fun `a drive that ends before a turn leaves nothing for the next drive`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 150.0, 450.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.goal)
        focus.reset(1.0)
        assertEquals(0.0, focus.goal)
        // The same route again from its start: the same step, now far from its turn.
        focus.onBanner(60.0, "r/0/0", 600.0, 0.0, "turn", excluded, 10.0)
        assertEquals(0.0, focus.goal)
        assertEquals(tuning.cruisePitch, focus.pitchAt(70.0), 1e-9)
    }

    @Test
    fun `a step without a maneuver type keeps the cruise tilt`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 50.0, 0.0, null, excluded, 20.0)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `a close second turn keeps the view down`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 8.0)
        focus.onBanner(20.0, "r/0/1", 120.0, 5.0, "turn", excluded, 8.0)
        focus.onBanner(23.0, "r/0/1", 96.0, 29.0, "turn", excluded, 8.0)
        assertEquals(1.0, focus.goal)
    }

    @Test
    fun `between two close turns the view stays down as far as the bridge reaches`() {
        // At 30 mph the bridge reaches 340 m from the first turn, at 45 mph 420 m (window, plus 6.5 s of
        // driving from the hold's end 2 s past the turn); farther apart the view goes up once and down once.
        for ((mph, gaps) in listOf(30.0 to listOf(250.0, 260.0, 300.0, 340.0), 45.0 to listOf(250.0, 260.0, 300.0, 340.0, 360.0, 420.0))) {
            for (gap in gaps) {
                for (phase in phases) assertEquals(0, closeTurns(gap, mph * MPH, phase).moves, "$gap m at $mph mph, phase $phase")
            }
        }
        for ((mph, gap) in listOf(30.0 to 360.0, 45.0 to 460.0)) {
            for (phase in phases) assertEquals(2, closeTurns(gap, mph * MPH, phase).moves, "$gap m at $mph mph, phase $phase")
        }
    }

    @Test
    fun `the bridge latches the next turn as the hold ends, and without it the view rises and falls`() {
        for (phase in phases) {
            val bridged = closeTurns(300.0, 30.0 * MPH, phase)
            assertEquals(ManeuverFocus.Latch.BRIDGE, bridged.secondBy, "phase $phase")
            // Close enough to be inside its own window as the hold ends: the window latches it.
            assertEquals(ManeuverFocus.Latch.WINDOW, closeTurns(250.0, 30.0 * MPH, phase).secondBy, "phase $phase")
            // With no bridge, 300 m apart rise and fall as they always did.
            assertEquals(2, closeTurns(300.0, 30.0 * MPH, phase, tuning.copy(bridgeSeconds = 0.0)).moves, "phase $phase")
        }
    }

    @Test
    fun `the junction keep follows the focus as the tilt rises and is 1 through the hold`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(10.0, "r/0/1", 900.0, 0.0, "turn", excluded, 10.0)
        for (t in listOf(10.0, 11.0, 11.5)) {
            focus.onBanner(t, "r/0/1", 900.0 - 10.0 * (t - 10.0), 10.0 * (t - 10.0), "turn", excluded, 10.0)
            assertEquals(1.0, focus.keepAt(t), 1e-9, "at $t s, in the hold")
        }
        // Far from the next turn the view goes up, and the keep with it.
        focus.onBanner(13.0, "r/0/1", 870.0, 30.0, "turn", excluded, 10.0)
        assertEquals(0.0, focus.goal)
        var t = 13.0
        while (t < 17.0) {
            assertEquals(focus.focusAt(t), focus.keepAt(t), 1e-12, "at $t s")
            t += frame
        }
    }

    @Test
    fun `while the view stays down for a turn not framed yet the kept zoom is let go over 3 s`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(10.0, "r/0/1", 300.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(11.0, "r/0/1", 290.0, 10.0, "turn", excluded, 10.0)
        focus.onBanner(12.0, "r/0/1", 280.0, 20.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.keepAt(12.0), 1e-9)
        // The hold ends 30 m past the turn: the next one is 270 m on, in the bridge's reach (215 + 65 m) and not framed.
        focus.onBanner(13.0, "r/0/1", 270.0, 30.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.goal)
        assertEquals(ManeuverFocus.Latch.BRIDGE, focus.latchedBy)
        assertEquals(1.0, focus.keepAt(13.0), 1e-9)
        assertEquals(0.5, focus.keepAt(13.0 + tuning.keptZoomReleaseSeconds / 2), 1e-9)
        assertEquals(0.0, focus.keepAt(13.0 + tuning.keptZoomReleaseSeconds), 1e-9)
        assertEquals(0.0, focus.keepAt(20.0), 1e-9)
        // The tilt stays down meanwhile, also once the next turn is framed.
        focus.onBanner(14.0, "r/0/1", 260.0, 40.0, "turn", excluded, 10.0)
        focus.onBanner(22.0, "r/0/1", 179.0, 121.0, "turn", excluded, 10.0)
        for (at in listOf(13.0, 15.0, 22.0)) assertEquals(1.0, focus.focusAt(at), 1e-9, "at $at s")
        assertEquals(0.0, focus.keepAt(22.0), 1e-9)
        // The end of guidance takes the release back: the keep is the focus again.
        focus.reset(23.0)
        assertEquals(focus.focusAt(24.0), focus.keepAt(24.0), 1e-12)
        assertEquals(null, focus.latchedBy)
    }

    @Test
    fun `a next turn framed already as the hold ends keeps the junction zoom with the focus`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(10.0, "r/0/1", 150.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(13.0, "r/0/1", 120.0, 30.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.goal)
        assertEquals(ManeuverFocus.Latch.WINDOW, focus.latchedBy)
        for (at in listOf(13.0, 14.5, 16.0, 20.0)) assertEquals(focus.focusAt(at), focus.keepAt(at), 1e-12, "at $at s")
    }

    @Test
    fun `with today's numbers the facts latch and frame where the banner's type did`() {
        val today = tuning.copy(turnFramingMinMeters = 180.0, turnFramingMaxMeters = 180.0, complexFramingMinMeters = 180.0, complexFramingMaxMeters = 180.0)
        for (v in (1..40).map { it.toDouble() }) {
            for (phase in (0 until 10).map { it / 10.0 }) {
                val byFacts = ManeuverFocus(today)
                val byBanner = ManeuverFocus(tuning)
                var k = 0
                while (true) {
                    val t = phase + k
                    val d = 1_500.0 - v * t
                    if (d < 0.0) break
                    byFacts.onProgress(t, "r/0/0", d, 3_000.0 - d, ManeuverFacts("r/0/0", FocusKind.TURN), Double.POSITIVE_INFINITY, v)
                    byBanner.onBanner(t, "r/0/0", d, 3_000.0 - d, "turn", excluded, v)
                    assertEquals(byBanner.goal, byFacts.goal, "$v m/s phase $phase at $d m")
                    assertEquals(d <= 180.0 + maxOf(v, 3.0) * tuning.descentSeconds, byFacts.goal == 1.0, "$v m/s phase $phase at $d m")
                    // The SDK framed today's maneuvers from 180 m on.
                    assertEquals(d <= 180.0, byFacts.framesManeuver, "$v m/s phase $phase at $d m")
                    assertEquals(d <= 180.0, byBanner.framesManeuver, "$v m/s phase $phase at $d m")
                    k += 1
                }
            }
        }
    }

    @Test
    fun `the view latches within one update of its window, a complex junction about 3 s earlier`() {
        for (v in listOf(5.0, 10.0, 13.4, 20.1, 29.1, 35.0)) {
            for (kind in listOf(FocusKind.TURN, FocusKind.COMPLEX)) {
                val window = FocusWindow.windowMeters(kind, v, Double.POSITIVE_INFINITY, tuning)
                for (phase in phases) {
                    val focus = ManeuverFocus(tuning)
                    var k = 0
                    var latchedAt = Double.NaN
                    while (latchedAt.isNaN()) {
                        val d = 1_500.0 - v * (phase + k)
                        focus.onProgress(phase + k, "r/0/0", d, 0.0, ManeuverFacts("r/0/0", kind), Double.POSITIVE_INFINITY, v)
                        if (focus.goal == 1.0) latchedAt = d
                        k += 1
                    }
                    assertTrue(latchedAt <= window && latchedAt > window - v, "$kind $v m/s phase $phase: $latchedAt, window $window")
                    assertEquals(ManeuverFocus.Latch.WINDOW, focus.latchedBy)
                    assertEquals(FocusWindow.framingMeters(kind, v, Double.POSITIVE_INFINITY, tuning), focus.framingMeters, 1e-9)
                }
            }
            val turn = FocusWindow.windowMeters(FocusKind.TURN, v, Double.POSITIVE_INFINITY, tuning)
            val complex = FocusWindow.windowMeters(FocusKind.COMPLEX, v, Double.POSITIVE_INFINITY, tuning)
            assertTrue(complex - turn >= 2.0 * v && complex - turn <= 4.0 * v + 40.0, "$v m/s: $turn and $complex")
        }
    }

    @Test
    fun `the frame begins at the framing distance and is kept through braking`() {
        val focus = ManeuverFocus(tuning)
        val facts = ManeuverFacts("r/0/0", FocusKind.TURN)
        var t = 0.0
        var d = 800.0
        var v = 50.0
        // At 50 m/s the window opens at 320 + 175 m.
        while (focus.goal == 0.0) {
            focus.onProgress(t, "r/0/0", d, 900.0 - d, facts, Double.POSITIVE_INFINITY, v)
            assertFalse(focus.framesManeuver)
            t += 1.0
            d -= v
        }
        assertEquals(320.0, focus.framingMeters, 1e-9)
        // Hard braking to a crawl: at 3 m/s a junction would be framed from 240 m, this one stays at its 320 m.
        for (slower in listOf(20.0, 10.0, 3.0)) {
            v = slower
            focus.onProgress(t, "r/0/0", d, 900.0 - d, facts, Double.POSITIVE_INFINITY, v)
            t += 1.0
            d -= v
        }
        var framedFrom = Double.NaN
        while (d > 0.0) {
            focus.onProgress(t, "r/0/0", d, 900.0 - d, facts, Double.POSITIVE_INFINITY, v)
            assertEquals(d <= 320.0, focus.framesManeuver, "at $d m")
            if (focus.framesManeuver && framedFrom.isNaN()) framedFrom = d
            assertEquals(1.0, focus.goal)
            assertEquals(320.0, focus.framingMeters, 1e-9)
            t += 1.0
            d -= v
        }
        assertTrue(framedFrom <= 320.0 && framedFrom > 317.0, "framed from $framedFrom m")
    }

    @Test
    fun `a car standing at the framing distance with a meter of wobble keeps its frame`() {
        val focus = ManeuverFocus(tuning)
        val facts = ManeuverFacts("r/0/0", FocusKind.TURN)
        // Up to the light at 10 m/s, then standing with the matched position wobbling by 1.3 m.
        focus.onProgress(0.0, "r/0/0", 300.0, 0.0, facts, Double.POSITIVE_INFINITY, 10.0)
        focus.onProgress(1.0, "r/0/0", 270.0, 30.0, facts, Double.POSITIVE_INFINITY, 10.0)
        focus.onProgress(2.0, "r/0/0", 250.0, 50.0, facts, Double.POSITIVE_INFINITY, 4.0)
        assertEquals(240.0, focus.framingMeters, 1e-9)
        var changes = 0
        var last = focus.framesManeuver
        for (i in 0 until 20) {
            val d = if (i % 2 == 0) 240.8 else 239.5
            focus.onProgress(3.0 + i, "r/0/0", d, 300.0 - d, facts, Double.POSITIVE_INFINITY, 0.0)
            if (focus.framesManeuver != last) changes += 1
            last = focus.framesManeuver
        }
        assertEquals(1, changes)
        assertTrue(focus.framesManeuver)
        // The next step starts unframed.
        focus.onProgress(30.0, "r/0/1", 900.0, 0.0, ManeuverFacts("r/0/1", FocusKind.TURN), Double.POSITIVE_INFINITY, 10.0)
        assertFalse(focus.framesManeuver)
    }

    @Test
    fun `a step the view stays up for never latches for itself`() {
        val focus = ManeuverFocus(tuning)
        var d = 600.0
        var t = 0.0
        while (d >= 0.0) {
            focus.onProgress(t, "r/0/0", d, 600.0 - d, ManeuverFacts("r/0/0", FocusKind.NONE), Double.POSITIVE_INFINITY, 10.0)
            assertEquals(0.0, focus.goal, "at $d m")
            assertFalse(focus.framesManeuver)
            assertEquals(null, focus.latchedBy)
            assertTrue(focus.framingMeters.isNaN())
            d -= 10.0
            t += 1.0
        }
    }

    @Test
    fun `past a straight name change 100 m before a turn the view is flat by 240 m before the turn`() {
        val v = 30.0 * MPH
        val turn = ManeuverFacts("r/0/1", FocusKind.TURN, turnDegrees = 90.0)
        val nameChange = ManeuverFacts("r/0/0", FocusKind.NONE, next = turn, nextMeters = 100.0)
        val window = FocusWindow.windowMeters(FocusKind.TURN, v, Double.POSITIVE_INFINITY, tuning)
        for (phase in phases) {
            val focus = ManeuverFocus(tuning)
            var latchedAt = Double.NaN
            var framedAt = Double.NaN
            var k = 0
            while (true) {
                val t = phase + k
                val s = v * t
                val toTurn = 1_100.0 - s
                if (toTurn < 0.0) break
                if (s < 1_000.0) {
                    focus.onProgress(t, "r/0/0", 1_000.0 - s, s, nameChange, Double.POSITIVE_INFINITY, v)
                } else {
                    focus.onProgress(t, "r/0/1", toTurn, s - 1_000.0, turn, Double.POSITIVE_INFINITY, v)
                }
                if (focus.goal == 1.0 && latchedAt.isNaN()) latchedAt = toTurn
                if (focus.framesManeuver && framedAt.isNaN()) framedAt = toTurn
                if (!latchedAt.isNaN()) {
                    assertEquals(1.0, focus.goal, "phase $phase at $toTurn m")
                    assertEquals(ManeuverFocus.Latch.AHEAD, focus.latchedBy, "phase $phase at $toTurn m")
                }
                k += 1
            }
            // Latched on the name change's step, at the turn's own window; the descent of 3.5 s ends at 240 m.
            assertTrue(latchedAt <= window && latchedAt > window - v, "phase $phase: latched at $latchedAt m")
            val flatAt = latchedAt - v * tuning.descentSeconds
            assertTrue(flatAt <= 240.0 + 1e-9 && flatAt > 240.0 - v, "phase $phase: flat at $flatAt m")
            // The step between is short: the SDK frames the turn with the name change's end, from the framing distance.
            assertTrue(framedAt <= 240.0 && framedAt > 240.0 - v, "phase $phase: framed at $framedAt m")
        }
    }

    @Test
    fun `past a longer step the junction is framed from its own step`() {
        val v = 10.0
        val turn = ManeuverFacts("r/0/1", FocusKind.TURN)
        val nameChange = ManeuverFacts("r/0/0", FocusKind.NONE, next = turn, nextMeters = 200.0)
        val focus = ManeuverFocus(tuning)
        // 240 m + 35 m before the turn the view goes down, 75 m before the name change.
        focus.onProgress(0.0, "r/0/0", 80.0, 0.0, nameChange, Double.POSITIVE_INFINITY, v)
        assertEquals(0.0, focus.goal)
        focus.onProgress(1.0, "r/0/0", 70.0, 10.0, nameChange, Double.POSITIVE_INFINITY, v)
        assertEquals(1.0, focus.goal)
        assertEquals(ManeuverFocus.Latch.AHEAD, focus.latchedBy)
        // Inside the framing distance, but 200 m of step lie between: the SDK could not frame the turn yet.
        focus.onProgress(4.0, "r/0/0", 30.0, 50.0, nameChange, Double.POSITIVE_INFINITY, v)
        assertFalse(focus.framesManeuver)
        // On the turn's own step it is framed at once, and the view stays down from the step change on.
        focus.onProgress(7.0, "r/0/1", 200.0, 0.0, turn, Double.POSITIVE_INFINITY, v)
        assertTrue(focus.framesManeuver)
        assertEquals(1.0, focus.goal)
        assertEquals(ManeuverFocus.Latch.AHEAD, focus.latchedBy)
        assertEquals(240.0, focus.framingMeters, 1e-9)
        assertFalse(focus.afterManeuver)
    }

    @Test
    fun `each latch is counted once, a latch ahead once for both of its steps`() {
        val focus = ManeuverFocus(tuning)
        val turn = ManeuverFacts("r/0/1", FocusKind.TURN)
        val nameChange = ManeuverFacts("r/0/0", FocusKind.NONE, next = turn, nextMeters = 100.0)
        assertEquals(0, focus.latches)
        focus.onProgress(0.0, "r/0/0", 400.0, 0.0, nameChange, Double.POSITIVE_INFINITY, 10.0)
        assertEquals(0, focus.latches)
        focus.onProgress(1.0, "r/0/0", 170.0, 230.0, nameChange, Double.POSITIVE_INFINITY, 10.0)
        focus.onProgress(2.0, "r/0/0", 160.0, 240.0, nameChange, Double.POSITIVE_INFINITY, 10.0)
        assertEquals(1, focus.latches)
        // The turn's own step: the same latch.
        focus.onProgress(18.0, "r/0/1", 100.0, 0.0, turn, Double.POSITIVE_INFINITY, 10.0)
        focus.onProgress(19.0, "r/0/1", 90.0, 10.0, turn, Double.POSITIVE_INFINITY, 10.0)
        assertEquals(1, focus.latches)
        // The next turn latches anew; after a reset the same turn would latch again.
        focus.onProgress(28.0, "r/0/2", 200.0, 0.0, ManeuverFacts("r/0/2", FocusKind.TURN), Double.POSITIVE_INFINITY, 10.0)
        assertEquals(2, focus.latches)
        focus.reset(29.0)
        focus.onProgress(30.0, "r/0/2", 190.0, 10.0, ManeuverFacts("r/0/2", FocusKind.TURN), Double.POSITIVE_INFINITY, 10.0)
        assertEquals(3, focus.latches)
    }

    @Test
    fun `settled, the focus is at its goal and does not move`() {
        val focus = ManeuverFocus(tuning)
        focus.onProgress(0.0, "r/0/0", 200.0, 0.0, ManeuverFacts("r/0/0", FocusKind.TURN), Double.POSITIVE_INFINITY, 10.0)
        assertEquals(1.0, focus.goal)
        assertTrue(focus.isAnimating(0.5))
        focus.settle(0.5)
        assertEquals(1.0, focus.focusAt(0.5), 1e-12)
        assertFalse(focus.isAnimating(0.5))
        assertEquals(tuning.maneuverPitch, focus.pitchAt(0.6), 1e-12)
        // Settling with nothing latched leaves the road view where it is.
        val road = ManeuverFocus(tuning)
        road.settle(3.0)
        assertEquals(0.0, road.focusAt(3.0), 1e-12)
        assertFalse(road.isAnimating(3.0))
    }

    @Test
    fun `with the junction view's windows the bridge reaches 400 m at 30 mph and 480 m at 45 mph`() {
        for ((mph, gaps) in listOf(30.0 to listOf(300.0, 350.0, 400.0), 45.0 to listOf(300.0, 350.0, 400.0, 450.0))) {
            for (gap in gaps) {
                for (phase in phases) assertEquals(0, closeTurns(gap, mph * MPH, phase, byFacts = true).moves, "$gap m at $mph mph, phase $phase")
            }
        }
        for ((mph, gap) in listOf(30.0 to 420.0, 45.0 to 520.0)) {
            for (phase in phases) assertEquals(2, closeTurns(gap, mph * MPH, phase, byFacts = true).moves, "$gap m at $mph mph, phase $phase")
        }
    }

    @Test
    fun `a capped frame begins later, never later than the navigation SDK's own distance`() {
        val focus = ManeuverFocus(tuning)
        val facts = ManeuverFacts("r/0/0", FocusKind.TURN)
        focus.onProgress(0.0, "r/0/0", 400.0, 0.0, facts, 214.0, 10.0)
        assertEquals(214.0, focus.framingMeters, 1e-9)
        assertEquals(0.0, focus.goal)
        focus.onProgress(1.0, "r/0/0", 214.0 + 35.0, 151.0, facts, 214.0, 10.0)
        assertEquals(1.0, focus.goal)
        val small = ManeuverFocus(tuning)
        small.onProgress(0.0, "r/0/0", 215.0, 0.0, facts, 120.0, 10.0)
        assertEquals(1.0, small.goal)
        assertEquals(180.0, small.framingMeters, 1e-9)
        // A map not laid out yet frames at the SDK's distance too.
        val unknown = ManeuverFocus(tuning)
        unknown.onProgress(0.0, "r/0/0", 400.0, 0.0, facts, Double.NaN, 10.0)
        assertEquals(180.0, unknown.framingMeters, 1e-9)
    }

    @Test
    fun `a reroute with the same indices is a new step`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "a/0/0", 150.0, 0.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.goal)
        focus.onBanner(1.0, "b/0/0", 900.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(4.0, "b/0/0", 870.0, 30.0, "turn", excluded, 10.0)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `a reversal halfway down starts from where the tilt is and takes half of the way up`() {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(0.0, "r/0/0", 200.0, 0.0, "turn", excluded, 10.0)
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
        focus.onBanner(0.0, "r/0/0", 100.0, 0.0, "turn", excluded, 10.0)
        // Past the turn and past the hold: the view starts to rise.
        focus.onBanner(20.0, "r/0/1", 400.0, 0.0, "turn", excluded, 10.0)
        focus.onBanner(23.0, "r/0/1", 370.0, 30.0, "turn", excluded, 10.0)
        assertEquals(0.0, focus.goal)
        // Halfway up, the next turn's window opens: 180 m and 3.5 s at 10 m/s.
        val turnedAt = 23.0 + tuning.ascentSeconds / 2
        assertEquals(0.5, focus.focusAt(turnedAt), 1e-9)
        focus.onBanner(turnedAt, "r/0/1", 215.0, 185.0, "turn", excluded, 10.0)

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
        focus.onBanner(0.0, "r/0/0", 200.0, 0.0, "turn", excluded, 10.0)
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

    /** How the view went between two close turns: its moves after the first latch, and how the second step latched. */
    private class CloseTurns(val moves: Int, val secondBy: ManeuverFocus.Latch?)

    /**
     * Two turns [gap] meters apart at a steady [speed], one update a second at [phase]: how often the
     * goal changed from the first time it went down until the car reached the second turn, and how
     * the step to the second turn latched.
     */
    private fun closeTurns(gap: Double, speed: Double, phase: Double, tuning: CameraTuning = this.tuning, byFacts: Boolean = false): CloseTurns {
        val focus = ManeuverFocus(tuning)
        val first = 600.0
        val second = first + gap
        var down = false
        var moves = 0
        var last = 0.0
        var secondBy: ManeuverFocus.Latch? = null
        var k = 0
        while (true) {
            // A phase of whole sixteenths keeps every time exact, so the hold's 2 s end on the update they should.
            val t = phase + k
            val s = speed * t
            if (s >= second) break
            val step = if (s < first) 0 else 1
            val end = if (step == 0) first else second
            if (byFacts) {
                focus.onProgress(t, "r/0/$step", end - s, if (step == 0) s else s - first, ManeuverFacts("r/0/$step", FocusKind.TURN), Double.POSITIVE_INFINITY, speed)
            } else {
                focus.onBanner(t, "r/0/$step", end - s, if (step == 0) s else s - first, "turn", excluded, speed)
            }
            if (down && focus.goal != last) moves += 1
            if (focus.goal == 1.0) down = true
            last = focus.goal
            if (step == 1) secondBy = focus.latchedBy
            k += 1
        }
        return CloseTurns(moves, secondBy)
    }

    /** A view that went down for the turn at the end of step r/0/0, as the car reaches the turn at 100 s. */
    private fun pastATurn(): ManeuverFocus {
        val focus = ManeuverFocus(tuning)
        focus.onBanner(90.0, "r/0/0", 100.0, 500.0, "turn", excluded, 10.0)
        assertEquals(1.0, focus.goal)
        focus.onBanner(100.0, "r/0/0", 1.0, 599.0, "turn", excluded, 10.0)
        return focus
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
