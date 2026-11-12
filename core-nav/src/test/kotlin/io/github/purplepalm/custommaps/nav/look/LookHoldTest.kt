package io.github.purplepalm.custommaps.nav.look

import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * What keeps a switch away from a turn or a tunnel during guidance, at the edges of [LookTuning]'s
 * windows: a car rolling slowly, a speed nobody knows, a jam in a tunnel, the seconds after a step
 * change, a choice sent again; and the dwell after a sun switch. A made-up sun and synthetic drives:
 * invented step keys, distances and times, a place at 0, 0.
 */
class LookHoldTest {

    private val t0 = Instant.parse("2000-01-01T00:00:00Z")
    private val fix = SunPlace.of(0.0, 0.0, PlaceSource.FIX)

    /** The sun's elevation the next input sees. */
    private var elevation = 10.0
    private val sun = SunElevation { _, _, _ -> elevation }
    private var state = LookState.NONE

    private fun step(
        seconds: Long,
        trigger: LookTrigger,
        choice: LookChoice = LookChoice.AUTO,
        drive: DriveMoment = DriveMoment.NONE,
    ): LookChange? {
        val result = LookSwitch.reduce(state, LookInput(t0.plusSeconds(seconds), choice, fix, trigger, drive), LookTuning(), sun)
        state = result.state
        return result.change
    }

    /**
     * A guided drive [meters] before its next maneuver, the car at [speed] meters a second (null:
     * not known). The time to the maneuver is the route's, at 13 meters a second, whatever the car
     * does.
     */
    private fun driving(
        meters: Double,
        speed: Double? = 13.0,
        stepKey: String = "route-a/0/1",
        tunnel: Boolean = false,
    ) = DriveMoment(
        guidance = true,
        toManeuver = Distance.meters(meters),
        timeToManeuver = (meters / 13.0).seconds,
        stepKey = stepKey,
        speedMps = speed,
        inTunnel = tunnel,
    )

    @ParameterizedTest
    @CsvSource(
        // speed m/s 100 m before a maneuver, expected hold (NONE for none)
        "0.49, NONE",
        "0.5, MANEUVER",
        "4.0, MANEUVER",
    )
    fun `only a car below half a meter a second stands, and a slow one waits for the turn`(speed: Double, expected: String) {
        val hold = LookSwitch.holdFor(driving(100.0, speed = speed), stepChangedAt = null, at = t0)
        assertEquals(expected, hold?.name ?: "NONE")
    }

    @Test
    fun `a speed nobody knows is never standing`() {
        assertEquals(LookHold.MANEUVER, LookSwitch.holdFor(driving(100.0, speed = null), stepChangedAt = null, at = t0))
        // Two seconds after a step change, with the next maneuver far away: that window holds too.
        val after = driving(1_500.0, speed = null, stepKey = "route-a/0/2")
        assertEquals(LookHold.MANEUVER, LookSwitch.holdFor(after, stepChangedAt = t0, at = t0.plusSeconds(2)))
        // With nothing close, it goes.
        assertNull(LookSwitch.holdFor(driving(1_500.0, speed = null), stepChangedAt = null, at = t0))
    }

    @Test
    fun `a car standing in a tunnel jam holds a switch until it is out`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(5_000.0, tunnel = true)))
        assertEquals(LookHold.TUNNEL, state.waitingFor)
        // The car stands in the tunnel, minute after minute, past the cap for maneuvers.
        for (second in listOf(120L, 600L, 1_200L)) {
            assertNull(step(second, LookTrigger.DRIVE, drive = driving(4_000.0, speed = 0.0, tunnel = true)))
            assertEquals(LookHold.TUNNEL, state.waitingFor)
        }
        // Out of the tunnel and still standing: the switch goes.
        val change = step(1_201, LookTrigger.DRIVE, drive = driving(3_990.0, speed = 0.0))
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookHold.TUNNEL, change.heldBy)
    }

    @Test
    fun `the same choice sent again does not take a waiting switch past a turn`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(200.0)))
        assertEquals(Look.NIGHT, state.waiting)
        // Auto once more, as when the choice is read again: nothing changes, the switch still waits.
        assertNull(step(61, LookTrigger.CHOICE, choice = LookChoice.AUTO, drive = driving(150.0)))
        assertEquals(LookHold.MANEUVER, state.waitingFor)
        // It goes after the turn, as the sun's switch.
        assertNull(step(75, LookTrigger.DRIVE, drive = driving(900.0, stepKey = "route-a/0/2")))
        val change = step(81, LookTrigger.DRIVE, drive = driving(820.0, stepKey = "route-a/0/2"))
        assertEquals(LookCause.SUN, change!!.cause)
        assertEquals(LookHold.MANEUVER, change.heldBy)
        assertEquals(21L, change.heldSeconds)
    }

    @ParameterizedTest
    @CsvSource(
        // milliseconds after the step change, the switch waited past the cap, expected hold
        "5999, false, MANEUVER",
        "6000, false, NONE",
        "2999, true, MANEUVER",
        "3000, true, NONE",
    )
    fun `the window after a step change is 6 s, and 3 s once the cap has passed`(afterMs: Long, capped: Boolean, expected: String) {
        val drive = driving(1_500.0, stepKey = "route-a/0/2")
        val hold = LookSwitch.holdFor(drive, stepChangedAt = t0, at = t0.plusMillis(afterMs), capped = capped)
        assertEquals(expected, hold?.name ?: "NONE")
    }

    @Test
    fun `the sun switches back once the dwell has passed, not a second before`() {
        elevation = 5.0
        step(0, LookTrigger.START)
        elevation = -4.0
        assertEquals(LookCause.SUN, step(60, LookTrigger.TICK)!!.cause)
        elevation = 5.0
        assertNull(step(60 + 599, LookTrigger.TICK))
        val back = step(60 + 600, LookTrigger.TICK)
        assertEquals(Look.DAY, back!!.look)
        assertEquals(LookCause.SUN, back.cause)
    }
}
