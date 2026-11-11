package io.github.purplepalm.custommaps.nav.look

import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A made-up sun and synthetic drives: invented step keys, distances and times, a place at 0, 0. */
class LookSwitchTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val fix = SunPlace.of(0.0, 0.0, PlaceSource.FIX)
    private val guess = SunPlace.ofTimeZone(0)
    private val tuning = LookTuning()

    /** The sun's elevation the next input sees. */
    private var elevation = 10.0
    private val sun = SunElevation { _, _, _ -> elevation }
    private var state = LookState.NONE

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private fun step(
        seconds: Long,
        trigger: LookTrigger,
        choice: LookChoice = LookChoice.AUTO,
        place: SunPlace = fix,
        drive: DriveMoment = DriveMoment.NONE,
        onScreen: Boolean = true,
    ): LookChange? {
        val result = LookSwitch.reduce(state, LookInput(at(seconds), choice, place, trigger, drive, onScreen), tuning, sun)
        state = result.state
        lastRelease = result.themeRelease
        return result.change
    }

    /** The theme the last input released, if any. */
    private var lastRelease: ThemeRelease? = null

    /** A guided drive [meters] before its next maneuver, at [speed] meters a second. */
    private fun driving(
        meters: Double,
        stepKey: String = "route-a/0/1",
        speed: Double = 13.0,
        tunnel: Boolean = false,
        closeView: Boolean = false,
    ) = DriveMoment(
        guidance = true,
        toManeuver = Distance.meters(meters),
        timeToManeuver = (meters / maxOf(speed, 1.0)).seconds,
        stepKey = stepKey,
        speedMps = speed,
        inTunnel = tunnel,
        closeView = closeView,
    )

    @Test
    fun `the first look goes at once and the line says what it is`() {
        elevation = -20.0
        val change = step(0, LookTrigger.START)
        assertNotNull(change)
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(
            mapOf("look" to Look.NIGHT, "cause" to LookCause.START, "choice" to LookChoice.AUTO, "place" to PlaceSource.FIX, "guidance" to false),
            change.logFields(),
        )
    }

    @Test
    fun `while browsing the sun switches at the next tick, past the band`() {
        elevation = 5.0
        step(0, LookTrigger.START)
        elevation = -2.0
        assertNull(step(60, LookTrigger.TICK))
        elevation = -3.0
        assertNull(step(120, LookTrigger.TICK))
        elevation = -3.6
        val change = step(180, LookTrigger.TICK)
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookCause.SUN, change.cause)
        assertEquals(Look.DAY, change.was)
        assertNull(change.heldSeconds)
    }

    @Test
    fun `during guidance a switch waits for the maneuver and goes a few seconds after it`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(2_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(200.0)))
        assertEquals(Look.NIGHT, state.waiting)
        assertEquals(LookHold.MANEUVER, state.waitingFor)
        assertNull(step(61, LookTrigger.DRIVE, drive = driving(150.0)))
        // The turn is made: a new step, 900 m to the next maneuver.
        assertNull(step(75, LookTrigger.DRIVE, drive = driving(900.0, stepKey = "route-a/0/2")))
        assertNull(step(80, LookTrigger.DRIVE, drive = driving(850.0, stepKey = "route-a/0/2")))
        val change = step(82, LookTrigger.DRIVE, drive = driving(820.0, stepKey = "route-a/0/2"))
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookCause.SUN, change.cause)
        assertEquals(22L, change.heldSeconds)
        assertEquals(LookHold.MANEUVER, change.heldBy)
        assertTrue(change.guidance)
    }

    @Test
    fun `a tunnel and a close view hold a switch for as long as they last`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(5_000.0, tunnel = true)))
        assertEquals(LookHold.TUNNEL, state.waitingFor)
        // Twenty minutes later, past the cap for maneuvers, still in the tunnel: still waiting.
        assertNull(step(60 + 1_200, LookTrigger.DRIVE, drive = driving(4_000.0, tunnel = true)))
        assertNull(step(60 + 1_201, LookTrigger.DRIVE, drive = driving(3_990.0, closeView = true)))
        assertEquals(LookHold.CLOSE_VIEW, state.waitingFor)
        val change = step(60 + 1_202, LookTrigger.DRIVE, drive = driving(3_980.0))
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookHold.CLOSE_VIEW, change.heldBy)
        assertEquals(1_202L, change.heldSeconds)
    }

    @Test
    fun `after ten minutes the window shrinks, and a turn close ahead still waits`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(250.0)))
        assertNull(step(60 + 599, LookTrigger.DRIVE, drive = driving(250.0)))
        // Past ten minutes: 120 m before a maneuver it still waits.
        assertNull(step(60 + 600, LookTrigger.DRIVE, drive = driving(120.0)))
        assertEquals(LookHold.MANEUVER, state.waitingFor)
        // The turn is made. Two seconds after it the switch still waits; four seconds after it, it goes
        // (before the cap it would have waited six).
        assertNull(step(60 + 610, LookTrigger.DRIVE, drive = driving(400.0, stepKey = "route-a/0/2")))
        assertNull(step(60 + 612, LookTrigger.DRIVE, drive = driving(390.0, stepKey = "route-a/0/2")))
        val change = step(60 + 614, LookTrigger.DRIVE, drive = driving(380.0, stepKey = "route-a/0/2"))
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(614L, change.heldSeconds)
        assertEquals(LookHold.MANEUVER, change.heldBy)
    }

    @Test
    fun `a long tunnel that ends just before a ramp does not send the switch at the ramp`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(9_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(9_000.0, tunnel = true)))
        // Twelve minutes in the tunnel; it ends 120 m before a ramp.
        assertNull(step(60 + 720, LookTrigger.DRIVE, drive = driving(150.0, tunnel = true)))
        assertNull(step(60 + 722, LookTrigger.DRIVE, drive = driving(120.0)))
        assertEquals(LookHold.MANEUVER, state.waitingFor)
        assertNull(step(60 + 726, LookTrigger.DRIVE, drive = driving(60.0, closeView = true)))
        assertEquals(LookHold.CLOSE_VIEW, state.waitingFor)
        // Past the ramp: the view comes back up, then the three seconds after the step change.
        assertNull(step(60 + 732, LookTrigger.DRIVE, drive = driving(1_500.0, stepKey = "route-a/0/2", closeView = true)))
        assertNull(step(60 + 734, LookTrigger.DRIVE, drive = driving(1_470.0, stepKey = "route-a/0/2")))
        val change = step(60 + 736, LookTrigger.DRIVE, drive = driving(1_440.0, stepKey = "route-a/0/2"))
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookHold.MANEUVER, change.heldBy)
    }

    @Test
    fun `a close view of a lane choice holds a switch with the next maneuver far away`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0, speed = 30.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(2_000.0, speed = 30.0, closeView = true)))
        assertEquals(LookHold.CLOSE_VIEW, state.waitingFor)
        assertNull(step(80, LookTrigger.DRIVE, drive = driving(1_400.0, speed = 30.0, closeView = true)))
        val change = step(90, LookTrigger.DRIVE, drive = driving(1_100.0, speed = 30.0))
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookHold.CLOSE_VIEW, change.heldBy)
        assertEquals(30L, change.heldSeconds)
    }

    @Test
    fun `a standing car takes a waiting switch, but not in a close view`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(40.0, speed = 0.0, closeView = true)))
        val change = step(61, LookTrigger.DRIVE, drive = driving(40.0, speed = 0.0))
        assertEquals(Look.NIGHT, change!!.look)
    }

    @Test
    fun `the driver's choice goes at once, even at a turn`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        val night = step(10, LookTrigger.CHOICE, choice = LookChoice.NIGHT, drive = driving(50.0, closeView = true))
        assertEquals(Look.NIGHT, night!!.look)
        assertEquals(LookCause.CHOICE, night.cause)
        assertNull(night.heldSeconds)
        assertFalse(night.logFields().containsKey("place"))
        // The sun's ticks change nothing while the choice is Night.
        assertNull(step(70, LookTrigger.TICK, choice = LookChoice.NIGHT))
        // Back to Auto: the sun's look, at once.
        val back = step(80, LookTrigger.CHOICE, choice = LookChoice.AUTO, drive = driving(40.0, closeView = true))
        assertEquals(Look.DAY, back!!.look)
        assertEquals(LookCause.CHOICE, back.cause)
    }

    @Test
    fun `back on screen the sun's look goes at once`() {
        elevation = 5.0
        step(0, LookTrigger.START)
        elevation = -10.0
        val change = step(3_600, LookTrigger.RESUME)
        assertEquals(Look.NIGHT, change!!.look)
        assertEquals(LookCause.RESUME, change.cause)
        assertFalse(change.deferTheme)
        assertNull(step(3_660, LookTrigger.RESUME))
    }

    @Test
    fun `back on screen near a turn the theme waits for the turn`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -10.0
        val change = step(3_600, LookTrigger.RESUME, drive = driving(100.0))
        assertEquals(Look.NIGHT, change!!.look)
        assertTrue(change.deferTheme)
        assertEquals(true, change.logFields()["themeDeferred"])
        assertNotNull(state.themeBehindSince)
        // The turn: a new step. Three seconds later the theme still waits; six seconds after it, it goes.
        assertNull(step(3_605, LookTrigger.DRIVE, drive = driving(800.0, stepKey = "route-a/0/2")))
        assertNull(lastRelease)
        step(3_608, LookTrigger.DRIVE, drive = driving(780.0, stepKey = "route-a/0/2"))
        assertNull(lastRelease)
        step(3_611, LookTrigger.DRIVE, drive = driving(760.0, stepKey = "route-a/0/2"))
        val release = lastRelease
        assertNotNull(release)
        assertEquals(11L, release!!.heldSeconds)
        assertEquals(LookHold.MANEUVER, release.heldBy)
        assertEquals(mapOf("themeHeldS" to 11L, "heldBy" to LookHold.MANEUVER), release.logFields())
        assertNull(state.themeBehindSince)
        // Nothing more to release.
        step(3_612, LookTrigger.DRIVE, drive = driving(750.0, stepKey = "route-a/0/2"))
        assertNull(lastRelease)
    }

    @Test
    fun `the map's request keeps the old theme until it is let go`() {
        elevation = 5.0
        var onMap = LookOnMap(step(0, LookTrigger.START, drive = driving(5_000.0))!!.look)
        assertEquals(LookOnMap(Look.DAY, Look.DAY), onMap)
        fun next(seconds: Long, trigger: LookTrigger, drive: DriveMoment) {
            val result = LookSwitch.reduce(state, LookInput(at(seconds), LookChoice.AUTO, fix, trigger, drive), tuning, sun)
            state = result.state
            onMap = onMap.after(result)
        }
        elevation = -10.0
        next(3_600, LookTrigger.RESUME, driving(100.0))
        assertEquals(LookOnMap(Look.NIGHT, Look.DAY), onMap)
        next(3_605, LookTrigger.DRIVE, driving(800.0, stepKey = "route-a/0/2"))
        assertEquals(LookOnMap(Look.NIGHT, Look.DAY), onMap)
        next(3_611, LookTrigger.DRIVE, driving(760.0, stepKey = "route-a/0/2"))
        assertEquals(LookOnMap(Look.NIGHT, Look.NIGHT), onMap)
        next(3_612, LookTrigger.DRIVE, driving(750.0, stepKey = "route-a/0/2"))
        assertEquals(LookOnMap(Look.NIGHT), onMap)
    }

    @Test
    fun `back on screen far from a turn the whole look goes`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -10.0
        val change = step(3_600, LookTrigger.RESUME, drive = driving(2_000.0))
        assertFalse(change!!.deferTheme)
        assertNull(state.themeBehindSince)
    }

    @Test
    fun `a theme left behind goes when guidance ends, and a whole look takes it along`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -10.0
        assertTrue(step(3_600, LookTrigger.RESUME, drive = driving(100.0))!!.deferTheme)
        step(3_630, LookTrigger.TICK, drive = DriveMoment.NONE)
        assertEquals(30L, lastRelease!!.heldSeconds)
        // Again, and this time the driver picks Day before the turn: the whole Day look goes, theme included.
        elevation = 5.0
        step(7_200, LookTrigger.START, drive = driving(5_000.0))
        elevation = -10.0
        assertTrue(step(10_800, LookTrigger.RESUME, drive = driving(100.0))!!.deferTheme)
        val day = step(10_805, LookTrigger.CHOICE, choice = LookChoice.DAY, drive = driving(50.0))
        assertEquals(Look.DAY, day!!.look)
        assertFalse(day.deferTheme)
        assertNull(state.themeBehindSince)
    }

    @Test
    fun `off screen a switch goes at once`() {
        elevation = 5.0
        step(0, LookTrigger.START)
        elevation = -10.0
        val change = step(60, LookTrigger.TICK, drive = driving(50.0), onScreen = false)
        assertEquals(Look.NIGHT, change!!.look)
    }

    @Test
    fun `the sun does not switch back within the dwell, a better position does`() {
        elevation = 5.0
        step(0, LookTrigger.START, place = guess)
        elevation = -4.0
        assertEquals(Look.NIGHT, step(60, LookTrigger.TICK, place = guess)!!.look)
        elevation = 5.0
        assertNull(step(120, LookTrigger.TICK, place = guess))
        assertNull(state.waiting)
        val better = step(180, LookTrigger.PLACE, place = fix)
        assertEquals(Look.DAY, better!!.look)
        assertEquals(LookCause.PLACE, better.cause)
        assertEquals(PlaceSource.FIX, better.source)
    }

    @Test
    fun `an elevation that wavers inside the band never switches`() {
        elevation = 5.0
        step(0, LookTrigger.START)
        var changes = 0
        for (minute in 1..240) {
            elevation = if (minute % 2 == 0) -2.6 else -3.4
            if (step(minute * 60L, LookTrigger.TICK) != null) changes++
        }
        assertEquals(0, changes)
    }

    @Test
    fun `an elevation that swings across the band switches at most once a dwell`() {
        elevation = 5.0
        step(0, LookTrigger.START)
        var changes = 0
        for (minute in 1..60) {
            elevation = if (minute % 2 == 0) 0.0 else -10.0
            if (step(minute * 60L, LookTrigger.TICK) != null) changes++
        }
        assertEquals(6, changes)
    }

    @Test
    fun `a waiting switch the sun no longer wants is dropped`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(100.0)))
        assertEquals(Look.NIGHT, state.waiting)
        elevation = 0.0
        assertNull(step(120, LookTrigger.TICK, drive = driving(5_000.0)))
        assertNull(state.waiting)
    }

    @Test
    fun `a progress update never computes the sun`() {
        var calls = 0
        val counting = SunElevation { _, _, _ ->
            calls++
            5.0
        }
        var current = LookSwitch.reduce(LookState.NONE, LookInput(at(0), LookChoice.AUTO, fix, LookTrigger.START, DriveMoment.NONE), tuning, counting).state
        val before = calls
        repeat(100) { second ->
            current = LookSwitch.reduce(current, LookInput(at(second + 1L), LookChoice.AUTO, fix, LookTrigger.DRIVE, driving(5_000.0)), tuning, counting).state
        }
        assertEquals(before, calls)
    }

    @ParameterizedTest
    @CsvSource(
        // meters ahead, speed m/s, expected hold (NONE for none)
        "301.0, 13.0, NONE",
        "300.0, 13.0, MANEUVER",
        "590.0, 30.0, MANEUVER",
        "620.0, 30.0, NONE",
        "100.0, 0.3, NONE",
    )
    fun `the maneuver window is 300 m or 20 s ahead`(meters: Double, speed: Double, expected: String) {
        val hold = LookSwitch.holdFor(driving(meters, speed = speed), stepChangedAt = null, at = t0)
        assertEquals(expected, hold?.name ?: "NONE")
    }

    @ParameterizedTest
    @CsvSource(
        // meters ahead, speed m/s, expected hold once the switch has waited ten minutes
        "151.0, 13.0, NONE",
        "150.0, 13.0, MANEUVER",
        "290.0, 30.0, MANEUVER",
        "310.0, 30.0, NONE",
    )
    fun `after the cap the window is 150 m or 10 s ahead`(meters: Double, speed: Double, expected: String) {
        val hold = LookSwitch.holdFor(driving(meters, speed = speed), stepChangedAt = null, at = t0, capped = true)
        assertEquals(expected, hold?.name ?: "NONE")
    }

    @Test
    fun `the log line holds enums, whole seconds and a boolean, and no place`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -4.0
        step(60, LookTrigger.TICK, drive = driving(100.0))
        val change = step(90, LookTrigger.DRIVE, drive = driving(100.0, speed = 0.0))!!
        val fields = change.logFields()
        assertEquals(setOf("look", "was", "cause", "choice", "place", "heldS", "heldBy", "guidance"), fields.keys)
        fields.values.forEach { value -> assertTrue(value is Enum<*> || value is Long || value is Boolean, "$value") }
        assertFalse(fields.toString().contains("0.0"))
    }
}
