package io.github.purplepalm.custommaps.nav.look

import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a map is asked to show after each step of the switch: the app hands it
 * `lookOnMap.after(step)` after every input, so every change that is not deferred has to bring its
 * theme along. A made-up sun and synthetic drives: invented step keys, distances and times, a place
 * at 0, 0.
 */
class LookOnMapTest {

    private val t0 = Instant.parse("2000-01-01T00:00:00Z")
    private val fix = SunPlace.of(0.0, 0.0, PlaceSource.FIX)
    private val guess = SunPlace.ofTimeZone(0)

    /** The sun's elevation the next input sees. */
    private var elevation = 10.0
    private val sun = SunElevation { _, _, _ -> elevation }
    private var state = LookState.NONE

    /** What the map was last asked to show; the night look before the first step. */
    private var onMap = LookOnMap(Look.NIGHT)

    /** One input; its step goes to the map as the app hands it on. */
    private fun step(
        seconds: Long,
        trigger: LookTrigger,
        choice: LookChoice = LookChoice.AUTO,
        place: SunPlace = fix,
        drive: DriveMoment = DriveMoment.NONE,
    ): LookChange? {
        val result = LookSwitch.reduce(state, LookInput(t0.plusSeconds(seconds), choice, place, trigger, drive), LookTuning(), sun)
        state = result.state
        onMap = onMap.after(result)
        return result.change
    }

    /** A guided drive [meters] before its next maneuver, at 13 meters a second. */
    private fun driving(meters: Double, stepKey: String = "route-a/0/1") = DriveMoment(
        guidance = true,
        toManeuver = Distance.meters(meters),
        timeToManeuver = (meters / 13.0).seconds,
        stepKey = stepKey,
        speedMps = 13.0,
    )

    @Test
    fun `every change that is not deferred gives the map the whole look, theme included`() {
        // The first look of a screen, under the time zone's guess.
        elevation = 5.0
        assertEquals(LookCause.START, step(0, LookTrigger.START, place = guess)?.cause)
        assertEquals(LookOnMap(Look.DAY, Look.DAY), onMap)
        // The sun sets.
        elevation = -4.0
        assertEquals(LookCause.SUN, step(60, LookTrigger.TICK, place = guess)?.cause)
        assertEquals(LookOnMap(Look.NIGHT, Look.NIGHT), onMap)
        // A first fix, where the sun is still up.
        elevation = 5.0
        assertEquals(LookCause.PLACE, step(120, LookTrigger.PLACE)?.cause)
        assertEquals(LookOnMap(Look.DAY, Look.DAY), onMap)
        // The driver picks Night, then Auto again.
        assertEquals(LookCause.CHOICE, step(180, LookTrigger.CHOICE, choice = LookChoice.NIGHT)?.cause)
        assertEquals(LookOnMap(Look.NIGHT, Look.NIGHT), onMap)
        assertEquals(LookCause.CHOICE, step(240, LookTrigger.CHOICE)?.cause)
        assertEquals(LookOnMap(Look.DAY, Look.DAY), onMap)
        // Back on screen after dark, with no drive.
        elevation = -10.0
        val resume = step(3_600, LookTrigger.RESUME)
        assertEquals(LookCause.RESUME, resume?.cause)
        assertFalse(resume!!.deferTheme)
        assertEquals(LookOnMap(Look.NIGHT, Look.NIGHT), onMap)
    }

    @Test
    fun `a switch that waited for a turn brings its theme when it goes`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(2_000.0))
        elevation = -4.0
        assertNull(step(60, LookTrigger.TICK, drive = driving(200.0)))
        assertEquals(LookOnMap(Look.DAY), onMap)
        // The turn, then six seconds on the new step.
        assertNull(step(75, LookTrigger.DRIVE, drive = driving(900.0, stepKey = "route-a/0/2")))
        assertEquals(LookOnMap(Look.DAY), onMap)
        val change = step(81, LookTrigger.DRIVE, drive = driving(820.0, stepKey = "route-a/0/2"))
        assertEquals(LookCause.SUN, change!!.cause)
        assertEquals(LookHold.MANEUVER, change.heldBy)
        assertEquals(LookOnMap(Look.NIGHT, Look.NIGHT), onMap)
    }

    @Test
    fun `a choice while the theme waits gives the map the whole look`() {
        elevation = 5.0
        step(0, LookTrigger.START, drive = driving(5_000.0))
        elevation = -10.0
        assertTrue(step(3_600, LookTrigger.RESUME, drive = driving(100.0))!!.deferTheme)
        assertEquals(LookOnMap(Look.NIGHT, Look.DAY), onMap)
        val day = step(3_605, LookTrigger.CHOICE, choice = LookChoice.DAY, drive = driving(50.0))
        assertFalse(day!!.deferTheme)
        assertEquals(LookOnMap(Look.DAY), onMap)
    }
}
